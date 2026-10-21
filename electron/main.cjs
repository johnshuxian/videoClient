const { app, BrowserWindow, dialog, ipcMain, shell } = require("electron");
const path = require("node:path");
const http = require("node:http");
const dgram = require("node:dgram");
const os = require("node:os");
const crypto = require("node:crypto");
const { Readable } = require("node:stream");
const { pipeline } = require("node:stream/promises");
const { setTimeout: delay } = require("node:timers/promises");
const {
  parseEpisodes,
  rewriteManifest,
  videoUrlFromSharePage,
} = require("./media.cjs");
const { createSettingsStore } = require("./settings.cjs");
const { discoverDlnaOnInterfaces } = require("./dlna-discovery.cjs");

const requestHeaders = {
  "User-Agent":
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125 Safari/537.36",
  Referer: "https://movie.douban.com/",
};
const token = crypto.randomBytes(24).toString("hex");
const catalogCache = new Map();
const imageCache = new Map();
const sourceTestRuns = new Map();
let activeSearch;
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
  try {
    await cast.control.stop();
  } finally {
    cast.control.dispose();
  }
}

function reportCastEnded(cast) {
  if (currentCast !== cast) return;
  currentCast = null;
  cast.control.dispose();
  mainWindow?.webContents.send("cast:ended", {
    deviceId: cast.device.id,
    error: "",
  });
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

async function getLanIp(deviceHost) {
  if (deviceHost) {
    const socket = dgram.createSocket("udp4");
    try {
      // UDP connect 不发包，只让系统选择通往当前电视的本机网卡。
      await new Promise((resolve, reject) => {
        socket.once("error", reject);
        socket.connect(1900, deviceHost, () => {
          socket.off("error", reject);
          resolve();
        });
      });
      const address = socket.address().address;
      if (address !== "0.0.0.0") return address;
    } finally {
      socket.close();
    }
  }
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

async function resolvePlayableUrl(input) {
  const url = new URL(input);
  if (!["http:", "https:"].includes(url.protocol))
    throw new Error("播放地址无效");
  if (!/^\/share\/[^/]+\/?$/.test(url.pathname)) return url.href;

  const response = await fetchWithTimeout(url, 12000, {
    Referer: `${url.origin}/`,
  });
  if (!/text\/html/i.test(response.headers.get("content-type") || ""))
    return response.url;
  const playable = videoUrlFromSharePage(await response.text(), response.url);
  if (!playable)
    throw new Error("该线路返回的是播放网页，未找到可直接播放的视频地址");
  return playable;
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
    // Windows 使用页面内的窗口按钮，让标题栏与其余工具按钮保持一致。
    frame: process.platform !== "win32",
    ...(process.platform === "darwin" ? { titleBarStyle: "hidden" } : {}),
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

app
  .whenReady()
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
ipcMain.on("history:read", (event) => {
  event.returnValue = settingsStore.listHistory();
});
ipcMain.handle("history:latest-other-source", (_event, title, sourceUrl) =>
  settingsStore.latestOtherSourceHistory(title, sourceUrl),
);
ipcMain.on("history:upsert", (event, item) => {
  try {
    settingsStore.upsertHistory(item);
    event.returnValue = null;
  } catch (error) {
    event.returnValue = error.message;
  }
});
ipcMain.on("history:delete", (event, key) => {
  try {
    settingsStore.deleteHistory(key);
    event.returnValue = null;
  } catch (error) {
    event.returnValue = error.message;
  }
});
ipcMain.on("history:clear", (event) => {
  try {
    settingsStore.clearHistory();
    event.returnValue = null;
  } catch (error) {
    event.returnValue = error.message;
  }
});

ipcMain.handle("catalog", async (_, type, tag, page, sort) => {
  if (!["movie", "tv", "anime", "variety"].includes(type))
    throw new Error("无效的分类");
  const key = `${type}:${tag}:${sort}:${page}`;
  const cached = catalogCache.get(key);
  if (cached && Date.now() - cached.time < 20 * 60_000) return cached.items;
  let items;
  if (type === "anime") {
    const categories = { TV: "1", OVA: "2", 剧场版: "3", WEB: "5" };
    const category = categories[tag];
    const query = new URLSearchParams({
      // Bangumi 搜索接口单页最多返回 20 条，形式榜也用相同页长。
      limit: "20",
      offset: String(page * 20),
    });
    const headers = {
      "User-Agent": `johnshuxian/videoClient/${app.getVersion()} (https://github.com/johnshuxian/videoClient)`,
      Referer: "https://bgm.tv/",
      Accept: "application/json",
    };
    let data;
    if (category) {
      // 形式分类使用 Bangumi 的浏览接口，支持按排名或播出日期排序。
      query.set("type", "2");
      query.set("cat", category);
      query.set("sort", sort === "date" ? "date" : "rank");
      data = await (
        await fetchWithTimeout(
          `https://api.bgm.tv/v0/subjects?${query}`,
          20000,
          headers,
        )
      ).json();
    } else {
      // 题材通过搜索接口筛选 TV 动画；“治愈”只有用户标签，其余使用公共标签。
      const filter = {
        type: [2],
        meta_tags: tag === "治愈" ? ["TV"] : ["TV", tag],
        ...(tag === "治愈" ? { tag: [tag] } : {}),
        ...(sort === "heat" ? {} : { rank: [">0"] }),
      };
      const response = await fetch(
        `https://api.bgm.tv/v0/search/subjects?${query}`,
        {
          method: "POST",
          headers: { ...headers, "Content-Type": "application/json" },
          body: JSON.stringify({
            keyword: "",
            sort: sort === "heat" ? "heat" : "rank",
            filter,
          }),
          signal: AbortSignal.timeout(20000),
        },
      );
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      data = await response.json();
    }
    items = data.data.map((item) => ({
      id: String(item.id),
      title: item.name_cn || item.name,
      poster: item.images?.common || item.images?.large || "",
      rating: item.rating?.score ? String(item.rating.score) : "",
      episodesInfo: item.eps ? `${item.eps} 集` : "",
    }));
  } else {
    // 综艺复用豆瓣剧集标签；“最新”使用热门剧集按时间排序。
    const query = new URLSearchParams({
      type: type === "variety" ? "tv" : type,
      tag:
        type === "variety"
          ? "综艺"
          : type === "tv" && tag === "最新"
            ? "热门"
            : tag,
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
    items = (data.subjects || []).map((item) => ({
      id: item.id,
      title: item.title,
      poster: item.cover || "",
      rating: item.rate,
      episodesInfo: item.episodes_info || "",
    }));
  }
  catalogCache.set(key, { time: Date.now(), items });
  return items;
});

ipcMain.handle("source:categories", async (_, sourceUrl) => {
  const url = new URL(sourceUrl);
  if (!["http:", "https:"].includes(url.protocol))
    throw new Error("点播源地址无效");
  // Apple CMS 基础列表附带 class，分类筛选使用其中的 type_id。
  const data = await (await fetchWithTimeout(url, 12000)).json();
  if (!Array.isArray(data.class)) throw new Error("该点播源未返回分类标签");
  return data.class
    .map((entry) => ({
      id: Number(entry.type_id),
      name: String(entry.type_name || "").trim(),
      parentId: Number(entry.type_pid) || 0,
    }))
    .filter(
      (entry) => Number.isInteger(entry.id) && entry.id > 0 && entry.name,
    );
});

ipcMain.handle("source:catalog", async (_, source, categoryId, page) => {
  const url = new URL(source.url);
  if (!["http:", "https:"].includes(url.protocol))
    throw new Error("点播源地址无效");
  url.searchParams.set("ac", "videolist");
  url.searchParams.set("pg", String(Math.max(1, Number(page) || 1)));
  if (categoryId !== null) url.searchParams.set("t", String(categoryId));
  const data = await (await fetchWithTimeout(url, 12000)).json();
  // 空分类在部分 Apple CMS 源中以 list:null 表示，按空列表展示。
  if (
    !Array.isArray(data.list) &&
    !(data.list == null && Number(data.total) === 0)
  )
    throw new Error("点播源未返回影片列表");
  return {
    items: (data.list || [])
      .filter((vod) => vod?.vod_id != null && vod.vod_name)
      .map((vod) => ({
        id: String(vod.vod_id),
        title: vod.vod_name,
        poster: vod.vod_pic || "",
        year: vod.vod_year || "",
        remarks: vod.vod_remarks || "",
        source: source.name,
        sourceUrl: source.url,
        type: vod.type_name || "",
        rating: vod.vod_score || "",
        updatedAt: vod.vod_time || "",
      })),
    pageCount: Number(data.pagecount) || 0,
  };
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
  activeSearch?.abort();
  const controller = new AbortController();
  activeSearch = controller;
  const sources = (Array.isArray(enabled) ? enabled : []).filter(
    (source) => source && !source.isAdult && /^https?:\/\//i.test(source.url),
  );
  const trimmed = String(query).trim().slice(0, 60);
  if (!trimmed) {
    activeSearch = null;
    return { results: [], failed: [] };
  }
  let completed = 0;
  const results = [];
  const failed = [];
  await Promise.all(
    sources.map(async (source) => {
      let items = [];
      let failedSource;
      try {
        const url = new URL(source.url);
        url.searchParams.set("ac", "videolist");
        url.searchParams.set("wd", trimmed);
        const data = await (
          await fetchWithTimeout(url, 8500, {}, controller.signal)
        ).json();
        items = (Array.isArray(data.list) ? data.list : [])
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
        if (!controller.signal.aborted) results.push(...items);
      } catch {
        if (!controller.signal.aborted) {
          failedSource = source.name;
          failed.push(source.name);
        }
      } finally {
        if (!controller.signal.aborted && !event.sender.isDestroyed()) {
          // 每个源完成后立即发送结果；慢源不再阻挡已找到的影片。
          completed += 1;
          event.sender.send("search:progress", {
            requestId,
            completed,
            total: sources.length,
            items,
            failedSource,
          });
        }
      }
    }),
  );
  if (activeSearch === controller) activeSearch = null;
  return { results, failed };
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

ipcMain.handle("stream-url", async (_, url, filterAds) => {
  const playable = await resolvePlayableUrl(url);
  return {
    url: proxyUrl(playable, filterAds),
    isHls: /\.m3u8(?:$|[?#])/i.test(playable),
  };
});
ipcMain.handle("devices", async () => {
  try {
    const { discoverDlnaDevices, DlnaControlPoint } =
      await import("@sonn-audio/node-upnp");
    let found;
    if (process.platform === "darwin") {
      // 同时扫描默认出口和各网卡，避免 VPN 或多网卡让整个设备列表为空。
      const [defaultScan, interfaceScan] = await Promise.allSettled([
        discoverDlnaDevices({ timeoutMs: 3000 }),
        discoverDlnaOnInterfaces(),
      ]);
      if (defaultScan.status === "rejected")
        console.warn("DLNA 默认扫描失败：", defaultScan.reason);
      if (interfaceScan.status === "rejected")
        console.warn("DLNA 逐网卡扫描失败：", interfaceScan.reason);
      if (
        defaultScan.status === "rejected" &&
        interfaceScan.status === "rejected"
      )
        throw new Error("默认扫描与逐网卡扫描均失败");
      const byLocation = new Map();
      for (const device of [
        ...(defaultScan.status === "fulfilled" ? defaultScan.value : []),
        ...(interfaceScan.status === "fulfilled" ? interfaceScan.value : []),
      ]) {
        if (
          !byLocation.has(device.location) ||
          !byLocation.get(device.location).controlUrl ||
          device.localAddress
        )
          byLocation.set(device.location, device);
      }
      found = [...byLocation.values()];
    } else {
      found = await discoverDlnaDevices({ timeoutMs: 3000 });
    }
    const checked = await Promise.all(
      found.map(async (device) => {
        if (process.platform === "darwin" && !device.controlUrl) return null;
        const control = new DlnaControlPoint({ host: device.host });
        try {
          // 同一网络也会发现音箱；有明确能力表时只展示支持视频的设备。
          // macOS 已完成逐网卡搜索时，不再用默认出口重新发现并丢失结果。
          const formats =
            process.platform === "darwin"
              ? null
              : await control.getSinkContentTypes();
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
            localAddress: device.localAddress,
            controlUrl: device.controlUrl,
            type: "dlna",
          };
        } catch (error) {
          console.warn(`DLNA 设备 ${device.host} 能力检查失败：`, error);
          return null;
        } finally {
          control.dispose();
        }
      }),
    );
    const items = checked.filter(Boolean);
    settingsStore.set("devices", items);
    return { items, warnings: [] };
  } catch (error) {
    return { items: [], warnings: [`DLNA：${error.message}`] };
  }
});
ipcMain.handle("cast", async (_, device, url, title, startPosition = 0) => {
  if (device.type !== "dlna") throw new Error("只支持 DLNA 投屏设备");
  const playable = await resolvePlayableUrl(url);
  const localIp = device.localAddress || (await getLanIp(device.host));
  // 电视直接从视频源取流；本机地址只用于接收 DLNA 播放状态事件。
  const stream = playable;
  await stopCurrentCast();
  const { DlnaControlPoint, buildDidl, buildSoapRequest, extractTag } =
    await import("@sonn-audio/node-upnp");
  const mime = /\.m3u8(?:$|[?#])/i.test(playable)
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
  const control = new DlnaControlPoint({
    host: device.host,
    controlUrl: process.platform === "darwin" ? device.controlUrl : undefined,
  });
  const cast = {
    device,
    control,
    url,
    seenPlaying: false,
  };
  currentCast = cast;
  let started = false;
  try {
    const formats = await control.getSinkContentTypes();
    if (
      formats &&
      !formats.some(
        (type) =>
          type.startsWith("video/") ||
          type.startsWith("application/vnd.apple.mpegurl"),
      )
    )
      throw new Error("此设备仅支持音频播放");
    try {
      await control.subscribeEvents(
        {
          onTransport: ({ transportState, durationSeconds }) => {
            if (currentCast !== cast) return;
            if (durationSeconds) cast.duration = durationSeconds;
            // 忽略订阅时设备原本的 STOPPED，只在本次播放之后续投。
            if (transportState === "PLAYING") cast.seenPlaying = true;
            if (transportState === "STOPPED" && cast.seenPlaying)
              reportCastEnded(cast);
          },
        },
        localIp,
      );
    } catch (error) {
      console.warn("DLNA 播放状态订阅失败：", error);
    }
    const ok = await control.setUri(stream, didl);
    started = ok;
    if (!ok && currentCast === cast) {
      currentCast = null;
      control.dispose();
    }
    if (ok && startPosition > 0) {
      // TCL 等电视在 Play 回复时可能还没进入 PLAYING，过早 Seek 会被忽略。
      const totalSeconds = Math.floor(startPosition);
      const target = `${String(Math.floor(totalSeconds / 3600)).padStart(2, "0")}:${String(Math.floor(totalSeconds % 3600 / 60)).padStart(2, "0")}:${String(totalSeconds % 60).padStart(2, "0")}`;
      const serviceType = "urn:schemas-upnp-org:service:AVTransport:1";
      for (let attempt = 0; attempt < 10; attempt++) {
        await delay(500);
        try {
          const stateResponse = await fetch(control.controlUrl, {
            method: "POST",
            headers: {
              "Content-Type": "text/xml; charset=utf-8",
              SOAPAction: `"${serviceType}#GetTransportInfo"`,
            },
            body: buildSoapRequest(serviceType, "GetTransportInfo"),
            signal: AbortSignal.timeout(2000),
          });
          if (!stateResponse.ok) break;
          const state = extractTag(
            await stateResponse.text(),
            "CurrentTransportState",
          );
          if (state === "PLAYING") break;
        } catch {
          // 不支持状态查询的电视仍尝试 Seek。
          break;
        }
      }
      // 部分电视短时间内仍把进度报告为 0，以 Seek 的响应判断是否接受续播。
      const response = await fetch(control.controlUrl, {
        method: "POST",
        headers: {
          "Content-Type": "text/xml; charset=utf-8",
          SOAPAction: `"${serviceType}#Seek"`,
        },
        body: buildSoapRequest(serviceType, "Seek", {
          Unit: "REL_TIME",
          Target: target,
        }),
        signal: AbortSignal.timeout(5000),
      });
      if (!response.ok)
        throw new Error(`电视不支持从指定进度开始播放（HTTP ${response.status}）`);
    }
    return ok;
  } catch (error) {
    if (currentCast === cast) currentCast = null;
    if (started) await control.stop().catch(() => {});
    control.dispose();
    throw error;
  }
});
ipcMain.handle("cast:progress", async () => {
  const cast = currentCast;
  if (!cast) return null;
  try {
    // node-upnp 暂无读取进度的方法，向它已发现的 AVTransport 地址查询。
    const { buildSoapRequest, extractTag } =
      await import("@sonn-audio/node-upnp");
    const response = await fetch(cast.control.controlUrl, {
      method: "POST",
      headers: {
        "Content-Type": "text/xml; charset=utf-8",
        SOAPAction:
          '"urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo"',
      },
      body: buildSoapRequest(
        "urn:schemas-upnp-org:service:AVTransport:1",
        "GetPositionInfo",
      ),
      signal: AbortSignal.timeout(3000),
    });
    if (!response.ok) return null;
    const body = await response.text();
    const parseTime = (value) => {
      const parts = value?.split(":").map(Number);
      return parts?.length === 3 && parts.every(Number.isFinite)
        ? parts[0] * 3600 + parts[1] * 60 + parts[2]
        : 0;
    };
    const position = parseTime(extractTag(body, "RelTime"));
    const duration =
      parseTime(extractTag(body, "TrackDuration")) || cast.duration;
    const progress = { position, duration };
    if (currentCast !== cast) return null;
    return { deviceId: cast.device.id, url: cast.url, ...progress };
  } catch {
    // 某些电视不会回复进度查询；播放与自动换集仍照常进行。
    return null;
  }
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
});
