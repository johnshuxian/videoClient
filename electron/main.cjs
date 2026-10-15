const { app, BrowserWindow, dialog, ipcMain, shell } = require("electron");
const path = require("node:path");
const http = require("node:http");
const os = require("node:os");
const crypto = require("node:crypto");
const { Readable } = require("node:stream");
const { pipeline } = require("node:stream/promises");
const { parseEpisodes, rewriteManifest } = require("./media.cjs");
const { requestAirPlay, closeAirPlay } = require("./airplay.cjs");
const { createSettingsStore } = require("./settings.cjs");

const requestHeaders = {
  "User-Agent":
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125 Safari/537.36",
  Referer: "https://movie.douban.com/",
};
const token = crypto.randomBytes(24).toString("hex");
const catalogCache = new Map();
const imageCache = new Map();
const sourceTestRuns = new Map();
let proxyPort = 0;
let currentCast = null;
let mainWindow;
let settingsStore;
const hasSingleInstanceLock = app.requestSingleInstanceLock();
if (!hasSingleInstanceLock) app.quit();

async function stopCurrentCast() {
  if (!currentCast) return;
  const cast = currentCast;
  currentCast = null;
  if (cast.type === "airplay") await requestAirPlay("stop");
  else {
    try {
      await cast.control.stop();
    } finally {
      cast.control.dispose();
    }
  }
}

async function fetchWithTimeout(url, ms = 9000, headers = {}, cancelSignal) {
  const response = await fetch(url, {
    headers: { ...requestHeaders, ...headers },
    signal: cancelSignal
      ? AbortSignal.any([cancelSignal, AbortSignal.timeout(ms)])
      : AbortSignal.timeout(ms),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response;
}

function getLanIp() {
  for (const addresses of Object.values(os.networkInterfaces())) {
    for (const address of addresses || []) {
      if (address.family === "IPv4" && !address.internal)
        return address.address;
    }
  }
  throw new Error("未找到可用于投屏的局域网地址");
}

function proxyUrl(url, filterAds, host = "127.0.0.1") {
  return `http://${host}:${proxyPort}/${token}/stream?url=${encodeURIComponent(url)}&ads=${filterAds ? "1" : "0"}`;
}

function imageProxyUrl(url) {
  return `http://127.0.0.1:${proxyPort}/${token}/image?url=${encodeURIComponent(url)}`;
}

// 电视无法读取本机 loopback；代理监听局域网地址并用随机令牌限制访问。
const proxyServer = http.createServer(async (req, res) => {
  const requestUrl = new URL(req.url, "http://localhost");
  const isImage = requestUrl.pathname === `/${token}/image`;
  if (!isImage && requestUrl.pathname !== `/${token}/stream`) {
    res.writeHead(404).end();
    return;
  }
  const target = requestUrl.searchParams.get("url");
  if (!target || !/^https?:\/\//i.test(target)) {
    res.writeHead(400).end();
    return;
  }
  if (isImage) {
    const cached = imageCache.get(target);
    if (cached) {
      res.writeHead(200, {
        "Content-Type": cached.type,
        "Cache-Control": "public, max-age=3600",
      });
      res.end(cached.body);
      return;
    }
    try {
      const targetUrl = new URL(target);
      const referer = /(?:^|\.)doubanio\.com$/i.test(targetUrl.hostname)
        ? "https://movie.douban.com/"
        : `${targetUrl.origin}/`;
      let upstream = await fetch(target, {
        headers: { ...requestHeaders, Referer: referer },
        signal: AbortSignal.timeout(12000),
      });
      // 部分图片站拒绝带 Referer 的请求，换成普通图片请求再试一次。
      if (!upstream.ok) {
        upstream = await fetch(target, {
          headers: { "User-Agent": requestHeaders["User-Agent"] },
          signal: AbortSignal.timeout(12000),
        });
      }
      const type = upstream.headers.get("content-type") || "";
      if (!upstream.ok || !type.startsWith("image/"))
        throw new Error("海报不可用");
      const body = Buffer.from(await upstream.arrayBuffer());
      if (body.length > 0 && body.length < 3_000_000) {
        if (imageCache.size >= 200)
          imageCache.delete(imageCache.keys().next().value);
        imageCache.set(target, { type, body });
      }
      res.writeHead(200, {
        "Content-Type": type,
        "Cache-Control": "public, max-age=3600",
      });
      res.end(body);
    } catch {
      // 上游图片失效时返回统一占位图，不把浏览器的破图图标暴露给用户。
      res.writeHead(200, {
        "Content-Type": "image/svg+xml; charset=utf-8",
        "Cache-Control": "no-store",
      });
      res.end(
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 360"><rect width="240" height="360" fill="#203648"/><path d="M80 145h80v70H80zM92 128h56" fill="none" stroke="#6c899c" stroke-width="8" stroke-linecap="round"/><path d="m111 163 28 18-28 18z" fill="#6c899c"/></svg>',
      );
    }
    return;
  }
  const controller = new AbortController();
  const headerTimer = setTimeout(() => controller.abort(), 20000);
  res.on("close", () => controller.abort());
  try {
    const headers = {};
    if (req.headers.range) headers.Range = req.headers.range;
    const targetUrl = new URL(target);
    // 豆瓣海报要求豆瓣页面 Referer；视频与资源站图片通常要求各自站点的 Referer。
    headers.Referer = /(?:^|\.)doubanio\.com$/i.test(targetUrl.hostname)
      ? "https://movie.douban.com/"
      : `${targetUrl.origin}/`;
    // 只限制建立连接的时间；长视频流在收到响应头后不应被定时器中途截断。
    const upstream = await fetch(target, {
      headers: { ...requestHeaders, ...headers },
      signal: controller.signal,
    });
    clearTimeout(headerTimer);
    if (!upstream.ok) throw new Error(`HTTP ${upstream.status}`);
    const type = upstream.headers.get("content-type") || "";
    const manifest =
      /\.m3u8(?:$|[?#])/i.test(upstream.url) || /mpegurl/i.test(type);
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Cache-Control", "no-store");
    if (manifest) {
      const body = await upstream.text();
      res.setHeader(
        "Content-Type",
        "application/vnd.apple.mpegurl; charset=utf-8",
      );
      res.end(
        rewriteManifest(
          body,
          upstream.url,
          (nested) =>
            proxyUrl(
              nested,
              requestUrl.searchParams.get("ads") === "1",
              req.headers.host?.split(":")[0] || "127.0.0.1",
            ),
          requestUrl.searchParams.get("ads") === "1",
        ),
      );
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
    if (!res.headersSent)
      res.writeHead(502).end(`上游视频请求失败：${error.message}`);
    else res.destroy(error);
  }
});

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 900,
    minHeight: 620,
    title: "影窗",
    backgroundColor: "#f5f8fc",
    titleBarStyle: "hidden",
    titleBarOverlay:
      process.platform === "darwin"
        ? false
        : { color: "#f5f8fc", symbolColor: "#31465a", height: 44 },
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  if (process.env.VITE_DEV_SERVER_URL)
    mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL);
  else if (!app.isPackaged) mainWindow.loadURL("http://127.0.0.1:5173");
  else mainWindow.loadFile(path.join(__dirname, "../dist/index.html"));
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https:\/\//i.test(url)) shell.openExternal(url);
    return { action: "deny" };
  });
  mainWindow.webContents.on("will-navigate", (event, url) => {
    if (!url.startsWith("file://") && !url.startsWith("http://127.0.0.1:5173"))
      event.preventDefault();
  });
}

app.whenReady()
  .then(async () => {
    if (!hasSingleInstanceLock) return;
    // Windows 安装版把数据库放在实际的 exe 所在目录；开发版与 macOS 仍使用用户数据目录。
    const settingsDirectory =
      process.platform === "win32" && app.isPackaged
        ? path.dirname(app.getPath("exe"))
        : app.getPath("userData");
    settingsStore = createSettingsStore(settingsDirectory);
    await new Promise((resolve) => proxyServer.listen(0, "0.0.0.0", resolve));
    proxyPort = proxyServer.address().port;
    createWindow();
  })
  .catch((error) => {
    dialog.showErrorBox("影窗启动失败", error.message);
    app.quit();
  });

app.on("second-instance", () => {
  if (!mainWindow || mainWindow.isDestroyed()) {
    createWindow();
    return;
  }
  if (mainWindow.isMinimized()) mainWindow.restore();
  mainWindow.show();
  mainWindow.focus();
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
app.on("before-quit", () => {
  if (proxyServer.listening) proxyServer.close();
  settingsStore?.close();
  closeAirPlay();
});
app.on("activate", () => {
  if (hasSingleInstanceLock && BrowserWindow.getAllWindows().length === 0)
    createWindow();
});

ipcMain.on("settings:read", (event, key) => {
  event.returnValue = settingsStore.get(key);
});
ipcMain.on("settings:save", (event, key, value) => {
  try {
    settingsStore.set(key, value);
    event.returnValue = null;
  } catch (error) {
    event.returnValue = error.message;
  }
});

ipcMain.handle("catalog", async (_, type, tag, page) => {
  if (!["movie", "tv"].includes(type)) throw new Error("无效的分类");
  const key = `${type}:${tag}:${page}`;
  const cached = catalogCache.get(key);
  if (cached && Date.now() - cached.time < 20 * 60_000) return cached.items;
  // 豆瓣剧集没有“最新”标签，用热门剧集按时间排序获取新剧。
  const query = new URLSearchParams({
    type,
    tag: type === "tv" && tag === "最新" ? "热门" : tag,
    sort: tag === "最新" ? "time" : "recommend",
    page_limit: "24",
    page_start: String(page * 24),
  });
  const data = await (
    await fetchWithTimeout(
      `https://movie.douban.com/j/search_subjects?${query}`,
      12000,
    )
  ).json();
  const items = (data.subjects || []).map((item) => ({
    id: item.id,
    title: item.title,
    poster: item.cover || "",
    rating: item.rate,
    episodesInfo: item.episodes_info || "",
  }));
  catalogCache.set(key, { time: Date.now(), items });
  return items;
});

ipcMain.handle("sources:import", async (_, address) => {
  const url = new URL(String(address).trim());
  if (!["http:", "https:"].includes(url.protocol))
    throw new Error("请输入 HTTP 或 HTTPS 订阅地址");
  const data = await (await fetchWithTimeout(url, 15000)).json();
  const entries = Array.isArray(data) ? data : data.sources;
  if (!Array.isArray(entries)) throw new Error("订阅文件需要包含 sources 数组");
  const seen = new Set();
  const sources = entries.flatMap((item) => {
    if (typeof item?.name !== "string" || typeof item?.url !== "string")
      return [];
    try {
      const sourceUrl = new URL(item.url);
      if (
        !["http:", "https:"].includes(sourceUrl.protocol) ||
        seen.has(item.url)
      )
        return [];
      seen.add(item.url);
      return [
        {
          name: item.name.trim() || sourceUrl.host,
          url: item.url,
          isAdult: !!item.isAdult,
        },
      ];
    } catch {
      return [];
    }
  });
  if (sources.length === 0) throw new Error("订阅文件中没有有效的点播源");
  return sources;
});

ipcMain.handle("source:test", async (_, source, runId) => {
  const start = Date.now();
  const controller = new AbortController();
  const requests = sourceTestRuns.get(runId) || new Set();
  requests.add(controller);
  sourceTestRuns.set(runId, requests);
  try {
    const url = new URL(source.url);
    if (!["http:", "https:"].includes(url.protocol))
      throw new Error("源地址无效");
    url.searchParams.set("ac", "videolist");
    url.searchParams.set("wd", "长安");
    const data = await (
      await fetchWithTimeout(url, 8000, {}, controller.signal)
    ).json();
    // 部分 Apple CMS 接口在零结果时返回 list:null，仍说明搜索请求已被处理。
    if (
      !Array.isArray(data.list) &&
      !(data.list == null && Number(data.total) === 0)
    )
      throw new Error("搜索接口未返回列表");
    return {
      ok: true,
      latencyMs: Date.now() - start,
      resultCount: Array.isArray(data.list) ? data.list.length : 0,
    };
  } catch (error) {
    return {
      ok: false,
      latencyMs: Date.now() - start,
      resultCount: 0,
      error:
        error.name === "TimeoutError"
          ? "请求超时"
          : error.message === "fetch failed"
            ? "连接失败"
            : error.message,
    };
  } finally {
    requests.delete(controller);
    if (requests.size === 0 && sourceTestRuns.get(runId) === requests)
      sourceTestRuns.delete(runId);
  }
});
ipcMain.on("source:test:cancel", (_, runId) => {
  // 停止后立即取消这一轮尚在等待响应的请求。
  for (const controller of sourceTestRuns.get(runId) || []) controller.abort();
  sourceTestRuns.delete(runId);
});

ipcMain.handle("image-url", (_, input) => {
  let original = String(input || "");
  // 兼容旧播放记录中保存的本地代理地址；新代理端口每次启动都不同。
  try {
    const previous = new URL(original);
    if (previous.hostname === "127.0.0.1" && previous.searchParams.has("url"))
      original = previous.searchParams.get("url");
  } catch {
    /* 空图片由界面显示占位背景。 */
  }
  return /^https?:\/\//i.test(original) ? imageProxyUrl(original) : "";
});

ipcMain.handle("search", async (event, query, enabled, requestId) => {
  const sources = (Array.isArray(enabled) ? enabled : []).filter(
    (source) => source && !source.isAdult && /^https?:\/\//i.test(source.url),
  );
  const trimmed = String(query).trim().slice(0, 60);
  if (!trimmed) return { results: [], failed: [] };
  let completed = 0;
  const settled = await Promise.allSettled(
    sources.map(async (source) => {
      try {
        const url = new URL(source.url);
        url.searchParams.set("ac", "videolist");
        url.searchParams.set("wd", trimmed);
        const data = await (await fetchWithTimeout(url, 8500)).json();
        return (Array.isArray(data.list) ? data.list : [])
          .map((item) => ({
            id: String(item.vod_id),
            title: item.vod_name,
            poster: item.vod_pic || "",
            year: item.vod_year || "",
            remarks: item.vod_remarks || "",
            source: source.name,
            sourceUrl: source.url,
            type: item.type_name || "",
            rating: item.vod_score || "",
          }))
          .filter((item) => item.title && item.id);
      } finally {
        // 成功或失败都算完成一个源；请求编号用于区分连续发起的搜索。
        completed += 1;
        event.sender.send("search:progress", {
          requestId,
          completed,
          total: sources.length,
        });
      }
    }),
  );
  return {
    results: settled.flatMap((item) =>
      item.status === "fulfilled" ? item.value : [],
    ),
    failed: settled.flatMap((item, index) =>
      item.status === "rejected" ? [sources[index].name] : [],
    ),
  };
});

ipcMain.handle("detail", async (_, sourceUrl, id) => {
  const url = new URL(sourceUrl);
  if (!["http:", "https:"].includes(url.protocol))
    throw new Error("播放源地址无效");
  url.searchParams.set("ac", "videolist");
  url.searchParams.set("ids", String(id));
  const data = await (await fetchWithTimeout(url, 12000)).json();
  const vod = data.list?.[0];
  if (!vod) throw new Error("该资源没有详情");
  return {
    title: vod.vod_name,
    poster: vod.vod_pic || "",
    year: vod.vod_year || "",
    area: vod.vod_area || "",
    actors: vod.vod_actor || "",
    description: String(vod.vod_content || vod.vod_blurb || "")
      .replace(/<[^>]*>/g, " ")
      .replace(/&nbsp;/g, " ")
      .trim(),
    lines: parseEpisodes(vod),
  };
});

ipcMain.handle("stream-url", (_, url, filterAds) => proxyUrl(url, filterAds));
ipcMain.handle("devices", async () => {
  const [dlna, airplay] = await Promise.allSettled([
    (async () => {
      const { discoverDlnaDevices, DlnaControlPoint } =
        await import("@sonn-audio/node-upnp");
      const found = await discoverDlnaDevices({ timeoutMs: 3000 });
      const checked = await Promise.all(
        found.map(async (device) => {
          const control = new DlnaControlPoint({ host: device.host });
          try {
            // 同一网络也会发现音箱；有明确能力表时只展示支持视频的设备。
            const formats = await control.getSinkContentTypes();
            if (
              formats &&
              !formats.some(
                (type) =>
                  type.startsWith("video/") ||
                  type.startsWith("application/vnd.apple.mpegurl"),
              )
            )
              return null;
            return {
              id: device.location,
              name: device.name || device.host,
              host: device.host,
              type: "dlna",
              paired: true,
            };
          } finally {
            control.dispose();
          }
        }),
      );
      return checked.filter(Boolean);
    })(),
    requestAirPlay("scan", {
      credentials: settingsStore.get("airplay-credentials") || {},
    }),
  ]);
  const warnings = [];
  if (dlna.status === "rejected") warnings.push(`DLNA：${dlna.reason.message}`);
  if (airplay.status === "rejected")
    warnings.push(`AirPlay：${airplay.reason.message}`);
  const items = [
    ...(dlna.status === "fulfilled" ? dlna.value : []),
    ...(airplay.status === "fulfilled" ? airplay.value : []),
  ];
  if (warnings.length < 2) settingsStore.set("devices", items);
  return { items, warnings };
});
ipcMain.handle("airplay:pair-begin", (_, id) =>
  requestAirPlay("pair_begin", { id }),
);
ipcMain.handle("airplay:pair-finish", async (_, pin) => {
  const result = await requestAirPlay("pair_finish", { pin });
  settingsStore.set("airplay-credentials", {
    ...(settingsStore.get("airplay-credentials") || {}),
    [result.id]: result.credentials,
  });
  settingsStore.set(
    "devices",
    (settingsStore.get("devices") || []).map((device) =>
      device.type === "airplay" && device.id === result.id
        ? { ...device, paired: true }
        : device,
    ),
  );
  return true;
});
ipcMain.handle("cast", async (_, device, url, title, filterAds) => {
  const stream = proxyUrl(url, filterAds, getLanIp());
  await stopCurrentCast();
  if (device.type === "airplay") {
    await requestAirPlay("play", { id: device.id, url: stream });
    currentCast = { type: "airplay" };
    return true;
  }
  const { DlnaControlPoint, buildDidl } = await import("@sonn-audio/node-upnp");
  const mime = /\.m3u8(?:$|[?#])/i.test(url)
    ? "application/vnd.apple.mpegurl"
    : "video/mp4";
  const didl = buildDidl([
    {
      id: "0",
      parentId: "-1",
      title,
      upnpClass: "object.item.videoItem",
      resources: [{ url: stream, protocolInfo: `http-get:*:${mime}:*` }],
    },
  ]);
  const control = new DlnaControlPoint({ host: device.host });
  currentCast = { type: "dlna", control };
  const formats = await control.getSinkContentTypes();
  if (
    formats &&
    !formats.some(
      (type) =>
        type.startsWith("video/") ||
        type.startsWith("application/vnd.apple.mpegurl"),
    )
  ) {
    control.dispose();
    currentCast = null;
    throw new Error("此设备仅支持音频播放");
  }
  return control.setUri(stream, didl);
});
ipcMain.handle("cast:stop", stopCurrentCast);
ipcMain.on("window:minimize", () => mainWindow.minimize());
ipcMain.on("window:maximize", () => {
  if (mainWindow.isMaximized()) mainWindow.unmaximize();
  else mainWindow.maximize();
});
ipcMain.on("window:close", () => mainWindow.close());
ipcMain.on("theme:set", (_, theme) => {
  const light = theme === "light";
  mainWindow.setBackgroundColor(light ? "#f5f8fc" : "#0b101a");
  if (process.platform !== "darwin")
    mainWindow.setTitleBarOverlay({
      color: light ? "#f5f8fc" : "#0b101a",
      symbolColor: light ? "#31465a" : "#aab6c7",
      height: 44,
    });
});
