const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const { proxyStream } = require("./stream-proxy.cjs");

test("桌面视频代理保留错误状态，并继续支持清单改写和范围请求", async (t) => {
  let receivedHeaders;
  const upstream = http.createServer((req, res) => {
    if (req.url.startsWith("/status/")) {
      res.writeHead(Number(req.url.split("/").at(-1))).end("source error");
    } else if (req.url === "/disconnect") {
      req.socket.destroy();
    } else if (req.url === "/redirect.m3u8") {
      res.writeHead(302, { Location: "/nested/index.m3u8" }).end();
    } else if (req.url === "/nested/index.m3u8") {
      res.setHeader("Content-Type", "application/vnd.apple.mpegurl");
      res.end(
        '#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n#EXTINF:10,\npart.ts\n#EXT-X-ENDLIST',
      );
    } else if (req.url === "/video.mp4") {
      receivedHeaders = req.headers;
      res.writeHead(206, {
        "Content-Type": "video/mp4",
        "Content-Range": "bytes 2-5/10",
        "Content-Length": "4",
        "Accept-Ranges": "bytes",
      });
      res.end("2345");
    } else res.writeHead(404).end();
  });
  let proxyOrigin;
  const proxy = http.createServer((req, res) => {
    const target = new URL(req.url, "http://localhost").searchParams.get("url");
    void proxyStream(req, res, {
      target,
      requestHeaders: { "User-Agent": "videoClient-test" },
      filterAds: false,
      proxyUrl: (url) => `${proxyOrigin}/stream?url=${encodeURIComponent(url)}`,
    });
  });
  t.after(async () => {
    proxy.closeAllConnections();
    upstream.closeAllConnections();
    await Promise.all([
      new Promise((resolve) => proxy.close(resolve)),
      new Promise((resolve) => upstream.close(resolve)),
    ]);
  });
  await new Promise((resolve) => upstream.listen(0, "127.0.0.1", resolve));
  await new Promise((resolve) => proxy.listen(0, "127.0.0.1", resolve));
  const upstreamOrigin = `http://127.0.0.1:${upstream.address().port}`;
  proxyOrigin = `http://127.0.0.1:${proxy.address().port}`;
  const url = (path) =>
    `${proxyOrigin}/stream?url=${encodeURIComponent(upstreamOrigin + path)}`;

  await t.test("403/404/503 原样返回，错误响应允许跨域且不缓存", async () => {
    for (const status of [403, 404, 503]) {
      const response = await fetch(url(`/status/${status}`));
      assert.equal(response.status, status);
      assert.equal(response.headers.get("access-control-allow-origin"), "*");
      assert.equal(response.headers.get("cache-control"), "no-store");
      assert.match(await response.text(), new RegExp(`HTTP ${status}`));
    }
  });
  await t.test("连接中断返回可供播放器重试的 502", async () => {
    const response = await fetch(url("/disconnect"));
    assert.equal(response.status, 502);
    assert.equal(response.headers.get("access-control-allow-origin"), "*");
    assert.match(await response.text(), /上游视频请求失败/);
  });
  await t.test("重定向后的清单使用最终地址解析分片和密钥", async () => {
    const response = await fetch(url("/redirect.m3u8"));
    assert.equal(response.status, 200);
    const manifest = await response.text();
    assert.ok(manifest.includes(url("/nested/part.ts")));
    assert.ok(manifest.includes(`URI="${url("/nested/key.bin")}"`));
    assert.match(manifest, /#EXT-X-ENDLIST/);
  });
  await t.test("MP4 的 Range 请求和 206 响应保持完整", async () => {
    const response = await fetch(url("/video.mp4"), {
      headers: { Range: "bytes=2-5" },
    });
    assert.equal(receivedHeaders.range, "bytes=2-5");
    assert.equal(receivedHeaders.referer, `${upstreamOrigin}/`);
    assert.equal(receivedHeaders["user-agent"], "videoClient-test");
    assert.equal(response.status, 206);
    assert.equal(response.headers.get("content-range"), "bytes 2-5/10");
    assert.equal(response.headers.get("content-length"), "4");
    assert.equal(await response.text(), "2345");
  });
});
