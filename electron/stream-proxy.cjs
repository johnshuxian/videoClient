const { Readable } = require("node:stream");
const { pipeline } = require("node:stream/promises");
const { rewriteManifest } = require("./media.cjs");

// 视频转发独立于 Electron 窗口，便于用真实 HTTP 请求验证跨域、状态码和清单改写。
async function proxyStream(
  req,
  res,
  { target, requestHeaders, proxyUrl, filterAds },
) {
  // 失败响应也必须允许跨域，否则 HLS 只能读到状态 0，无法判断是否应该重试。
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Cache-Control", "no-store");
  const controller = new AbortController();
  const headerTimer = setTimeout(() => controller.abort(), 20000);
  res.on("close", () => controller.abort());
  try {
    const headers = {};
    if (req.headers.range) headers.Range = req.headers.range;
    const targetUrl = new URL(target);
    headers.Referer = /(?:^|\.)doubanio\.com$/i.test(targetUrl.hostname)
      ? "https://movie.douban.com/"
      : `${targetUrl.origin}/`;
    // 只限制建立连接的时间，收到响应头后不截断长视频流。
    const upstream = await fetch(target, {
      headers: { ...requestHeaders, ...headers },
      signal: controller.signal,
    });
    clearTimeout(headerTimer);
    if (!upstream.ok) {
      // 保留源站状态：5xx 可以重试，403/404 应及时交给播放器切换线路。
      await upstream.body?.cancel();
      res.writeHead(upstream.status, {
        "Content-Type": "text/plain; charset=utf-8",
      });
      res.end(`上游视频请求失败：HTTP ${upstream.status}`);
      return;
    }
    const type = upstream.headers.get("content-type") || "";
    const manifest =
      /\.m3u8(?:$|[?#])/i.test(upstream.url) || /mpegurl/i.test(type);
    if (manifest) {
      const body = await upstream.text();
      res.setHeader(
        "Content-Type",
        "application/vnd.apple.mpegurl; charset=utf-8",
      );
      res.end(rewriteManifest(body, upstream.url, proxyUrl, filterAds));
      return;
    }
    for (const key of [
      "content-type",
      "content-length",
      "content-range",
      "accept-ranges",
    ]) {
      const value = upstream.headers.get(key);
      if (value) res.setHeader(key, value);
    }
    res.writeHead(upstream.status);
    await pipeline(Readable.fromWeb(upstream.body), res);
  } catch (error) {
    clearTimeout(headerTimer);
    if (res.destroyed) return;
    if (!res.headersSent) {
      res.writeHead(controller.signal.aborted ? 504 : 502, {
        "Content-Type": "text/plain; charset=utf-8",
      });
      res.end(`上游视频请求失败：${error.message}`);
    } else res.destroy(error);
  }
}

module.exports = { proxyStream };
