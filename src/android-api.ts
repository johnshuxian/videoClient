import { CapacitorHttp, registerPlugin } from "@capacitor/core";
import { CapacitorSQLite, SQLiteConnection } from "@capacitor-community/sqlite";
import type {
  CatalogItem,
  CatalogType,
  SearchItem,
  Source,
  SourceCategory,
  VodDetail,
} from "./types";

type VideoProxy = {
  url(options: {
    url: string;
    image?: boolean;
    filterAds?: boolean;
    deviceHost?: string;
  }): Promise<{ url: string }>;
};
type Dlna = {
  devices(): Promise<{ items: import("./types").Device[]; warnings: string[] }>;
  cast(options: {
    deviceId: string;
    stream: string;
    url: string;
    title: string;
    isHls: boolean;
  }): Promise<{ ok: boolean }>;
  stop(): Promise<void>;
  progress(): Promise<{
    deviceId?: string;
    url?: string;
    position?: number;
    duration?: number;
  }>;
  addListener(
    name: "castEnded",
    callback: (event: { deviceId: string; error: string }) => void,
  ): Promise<{ remove(): Promise<void> }>;
};
type AppStatusBar = {
  deviceInfo(): Promise<{ isTv: boolean }>;
  setTheme(options: { theme: "light" | "dark" }): Promise<void>;
  setFullscreen(options: { fullscreen: boolean }): Promise<void>;
};

const proxy = registerPlugin<VideoProxy>("VideoProxy");
const dlna = registerPlugin<Dlna>("Dlna");
const statusBar = registerPlugin<AppStatusBar>("AppStatusBar");
const requestHeaders = {
  "User-Agent":
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125 Safari/537.36",
  Referer: "https://movie.douban.com/",
};

async function requestText(
  url: string,
  timeout = 12000,
  headers: Record<string, string> = {},
  body?: unknown,
) {
  const response = await CapacitorHttp.request({
    url,
    method: body === undefined ? "GET" : "POST",
    headers: { ...requestHeaders, ...headers },
    data: body,
    responseType: "text",
    connectTimeout: timeout,
    readTimeout: timeout,
  });
  if (response.status < 200 || response.status >= 300)
    throw new Error(`HTTP ${response.status}`);
  return typeof response.data === "string"
    ? response.data
    : JSON.stringify(response.data);
}

async function requestJson<T>(
  url: string,
  timeout = 12000,
  headers: Record<string, string> = {},
  body?: unknown,
): Promise<T> {
  return JSON.parse(await requestText(url, timeout, headers, body)) as T;
}

function sourceAddress(address: string) {
  const url = new URL(address);
  if (url.protocol !== "http:" && url.protocol !== "https:")
    throw new Error("点播源地址无效");
  return url;
}

function posterAddress(poster: string | undefined, sourceUrl: string) {
  if (!poster) return "";
  try {
    return new URL(poster, sourceUrl).href;
  } catch {
    return "";
  }
}

async function playableUrl(input: string) {
  let playable = sourceAddress(input).href;
  if (/^\/share\/[^/]+\/?$/.test(new URL(playable).pathname)) {
    const html = await requestText(playable, 12000, {
      Referer: `${new URL(playable).origin}/`,
    });
    const match = html.match(/\bvar\s+main\s*=\s*("(?:\\.|[^"\\])*")/);
    if (!match) throw new Error("该线路是播放网页，未找到视频地址");
    playable = new URL(JSON.parse(match[1]), playable).href;
  }
  return playable;
}

type VodEntry = {
  vod_id?: string | number;
  vod_name?: string;
  vod_pic?: string;
  vod_year?: string;
  vod_remarks?: string;
  vod_score?: string;
  vod_time?: string;
  type_name?: string;
  vod_area?: string;
  vod_actor?: string;
  vod_content?: string;
  vod_blurb?: string;
  vod_play_from?: string;
  vod_play_url?: string;
};

type VodResponse = {
  list?: VodEntry[] | null;
  total?: number;
  pagecount?: number;
  class?: { type_id: number; type_name: string; type_pid?: number }[];
};

function searchItem(vod: VodEntry, source: Source): SearchItem {
  return {
    id: String(vod.vod_id),
    title: vod.vod_name || "",
    poster: posterAddress(vod.vod_pic, source.url),
    year: vod.vod_year || "",
    remarks: vod.vod_remarks || "",
    source: source.name,
    sourceUrl: source.url,
    type: vod.type_name || "",
    rating: vod.vod_score || "",
    updatedAt: vod.vod_time || "",
  };
}

export async function createAndroidVideoAPI(): Promise<Window["videoAPI"]> {
  const root = document.getElementById("root")!;
  // React 挂载前标记电视设备，首屏直接使用大屏布局和遥控器导航。
  root.dataset.startupStep = "识别设备";
  const { isTv } = await statusBar.deviceInfo();
  if (isTv) document.body.classList.add("tv-body");
  root.dataset.startupStep = "连接本机数据库";
  const sqlite = new SQLiteConnection(CapacitorSQLite);
  const db = (await sqlite.isConnection("settings", false)).result
    ? await sqlite.retrieveConnection("settings", false)
    : await sqlite.createConnection(
        "settings",
        false,
        "no-encryption",
        1,
        false,
      );
  root.dataset.startupStep = "打开本机数据库";
  if (!(await db.isDBOpen()).result) await db.open();
  root.dataset.startupStep = "读取本机设置";
  await db.execute(
    "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
  );
  const saved = await db.query("SELECT key, value FROM settings");
  const settings = new Map<string, unknown>();
  for (const row of saved.values || [])
    settings.set(row.key, JSON.parse(row.value));
  // 首次安装默认深色；已有设置仍优先，避免覆盖用户手动选择。
  const initialTheme =
    settings.get("color-theme") === "light" ? "light" : "dark";
  document.documentElement.dataset.theme = initialTheme;
  root.dataset.startupStep = "设置界面主题";
  await statusBar.setTheme({ theme: initialTheme });
  let writes = Promise.resolve();
  let activeSearch = 0;
  const cancelledTests = new Set<number>();
  const catalogCache = new Map<
    string,
    { time: number; items: CatalogItem[] }
  >();
  const imageUrls = new Map<string, Promise<string>>();

  return {
    platform: "android",
    readSetting: (key) => settings.get(key),
    saveSetting: (key, value) => {
      settings.set(key, value);
      // UI 的设置接口是同步的；数据库写入按操作顺序排队。
      writes = writes
        .catch((error) => console.error("设置写入失败", error))
        .then(() =>
          db.run("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", [
            key,
            JSON.stringify(value),
          ]),
        )
        .then(() => undefined);
    },
    catalog: async (type: CatalogType, tag, page, sort) => {
      const cacheKey = `${type}:${tag}:${page}:${sort}`;
      const cached = catalogCache.get(cacheKey);
      if (cached && Date.now() - cached.time < 20 * 60_000) return cached.items;
      let items: CatalogItem[];
      if (type === "anime") {
        const categories: Record<string, string> = {
          TV: "1",
          OVA: "2",
          剧场版: "3",
          WEB: "5",
        };
        const query = new URLSearchParams({
          limit: "20",
          offset: String(page * 20),
        });
        const headers = {
          "User-Agent":
            "johnshuxian/videoClient/0.2.16 (https://github.com/johnshuxian/videoClient)",
          Referer: "https://bgm.tv/",
          Accept: "application/json",
        };
        let data: { data: Record<string, any>[] };
        if (categories[tag]) {
          query.set("type", "2");
          query.set("cat", categories[tag]);
          query.set("sort", sort === "date" ? "date" : "rank");
          data = await requestJson(
            `https://api.bgm.tv/v0/subjects?${query}`,
            20000,
            headers,
          );
        } else {
          data = await requestJson(
            `https://api.bgm.tv/v0/search/subjects?${query}`,
            20000,
            {
              ...headers,
              "Content-Type": "application/json",
            },
            {
              keyword: "",
              sort: sort === "heat" ? "heat" : "rank",
              filter: {
                type: [2],
                meta_tags: tag === "治愈" ? ["TV"] : ["TV", tag],
                ...(tag === "治愈" ? { tag: [tag] } : {}),
                ...(sort === "heat" ? {} : { rank: [">0"] }),
              },
            },
          );
        }
        items = data.data.map((item) => ({
          id: String(item.id),
          title: item.name_cn || item.name,
          poster: item.images?.common || item.images?.large || "",
          rating: item.rating?.score ? String(item.rating.score) : "",
          episodesInfo: item.eps ? `${item.eps} 集` : "",
        }));
      } else {
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
        const data = await requestJson<{ subjects?: Record<string, any>[] }>(
          `https://movie.douban.com/j/search_subjects?${query}`,
        );
        items = (data.subjects || []).map((item) => ({
          id: item.id,
          title: item.title,
          poster: item.cover || "",
          rating: item.rate,
          episodesInfo: item.episodes_info || "",
        }));
      }
      catalogCache.set(cacheKey, { time: Date.now(), items });
      return items;
    },
    sourceCategories: async (address): Promise<SourceCategory[]> => {
      const data = await requestJson<VodResponse>(sourceAddress(address).href);
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
    },
    sourceCatalog: async (source, categoryId, page) => {
      const url = sourceAddress(source.url);
      url.searchParams.set("ac", "videolist");
      url.searchParams.set("pg", String(Math.max(1, page)));
      if (categoryId !== null) url.searchParams.set("t", String(categoryId));
      const data = await requestJson<VodResponse>(url.href);
      if (
        !Array.isArray(data.list) &&
        !(data.list == null && Number(data.total) === 0)
      )
        throw new Error("点播源未返回影片列表");
      return {
        items: (data.list || [])
          .filter((vod) => vod?.vod_id != null && vod.vod_name)
          .map((vod) => searchItem(vod, source)),
        pageCount: Number(data.pagecount) || 0,
      };
    },
    importSources: async (address) => {
      const data = await requestJson<Source[] | { sources: Source[] }>(
        sourceAddress(address.trim()).href,
        15000,
      );
      const entries = Array.isArray(data) ? data : data.sources;
      if (!Array.isArray(entries))
        throw new Error("订阅文件需要包含 sources 数组");
      const seen = new Set<string>();
      const sources = entries.flatMap((item) => {
        if (typeof item?.name !== "string" || typeof item?.url !== "string")
          return [];
        try {
          const url = sourceAddress(item.url);
          if (seen.has(url.href)) return [];
          seen.add(url.href);
          return [
            {
              name: item.name.trim() || url.host,
              url: item.url,
              isAdult: !!item.isAdult,
            },
          ];
        } catch {
          return [];
        }
      });
      if (!sources.length) throw new Error("订阅文件中没有有效的点播源");
      return sources;
    },
    testSource: async (source, runId) => {
      const start = Date.now();
      try {
        const url = sourceAddress(source.url);
        url.searchParams.set("ac", "videolist");
        url.searchParams.set("wd", "长安");
        const data = await requestJson<VodResponse>(url.href, 8000);
        if (
          !Array.isArray(data.list) &&
          !(data.list == null && Number(data.total) === 0)
        )
          throw new Error("搜索接口未返回列表");
        if (cancelledTests.has(runId)) throw new Error("已取消");
        return {
          ok: true,
          latencyMs: Date.now() - start,
          resultCount: data.list?.length || 0,
        };
      } catch (error) {
        return {
          ok: false,
          latencyMs: Date.now() - start,
          resultCount: 0,
          error: (error as Error).message,
        };
      }
    },
    cancelSourceTest: (runId) => {
      cancelledTests.add(runId);
    },
    imageUrl: async (input) => {
      let original = String(input || "");
      try {
        const previous = new URL(original);
        if (
          previous.hostname === "127.0.0.1" &&
          previous.searchParams.has("url")
        )
          original = previous.searchParams.get("url") || "";
      } catch {
        /* 空海报使用页面占位背景。 */
      }
      if (!/^https?:\/\//i.test(original)) return "";
      // 同一海报会出现在搜索、详情和历史里，只向原生桥接层取一次地址。
      let pending = imageUrls.get(original);
      if (!pending) {
        pending = proxy
          .url({ url: original, image: true })
          .then((result) => result.url);
        imageUrls.set(original, pending);
        pending.catch(() => imageUrls.delete(original));
      }
      return pending;
    },
    search: async (query, sources, requestId, onProgress) => {
      activeSearch = requestId;
      const trimmed = query.trim().slice(0, 60);
      if (!trimmed) return { results: [], failed: [] };
      const enabled = sources.filter(
        (source) => !source.isAdult && /^https?:\/\//i.test(source.url),
      );
      const results: SearchItem[] = [];
      const failed: string[] = [];
      let completed = 0;
      await Promise.all(
        enabled.map(async (source) => {
          let items: SearchItem[] = [];
          let failedSource: string | undefined;
          try {
            const url = sourceAddress(source.url);
            url.searchParams.set("ac", "videolist");
            url.searchParams.set("wd", trimmed);
            const data = await requestJson<VodResponse>(url.href, 8500);
            items = (data.list || [])
              .filter((vod) => vod?.vod_id != null && vod.vod_name)
              .map((vod) => searchItem(vod, source));
            if (activeSearch === requestId) results.push(...items);
          } catch {
            failedSource = source.name;
            if (activeSearch === requestId) failed.push(source.name);
          } finally {
            if (activeSearch === requestId) {
              completed += 1;
              onProgress(completed, enabled.length, items, failedSource);
            }
          }
        }),
      );
      return { results, failed };
    },
    detail: async (address, id): Promise<VodDetail> => {
      const url = sourceAddress(address);
      url.searchParams.set("ac", "videolist");
      url.searchParams.set("ids", id);
      const data = await requestJson<VodResponse>(url.href);
      const vod = data.list?.[0];
      if (!vod) throw new Error("该资源没有详情");
      const names = String(vod.vod_play_from || "").split("$$$");
      const lines = String(vod.vod_play_url || "")
        .split("$$$")
        .flatMap((group, index) => {
          const episodes = group
            .split("#")
            .map((entry, episodeIndex) => {
              const separator = entry.indexOf("$");
              const label =
                separator < 0
                  ? `第 ${episodeIndex + 1} 集`
                  : entry.slice(0, separator);
              const episodeUrl =
                separator < 0 ? entry : entry.slice(separator + 1);
              return /^https?:\/\//i.test(episodeUrl)
                ? {
                    label: label || `第 ${episodeIndex + 1} 集`,
                    url: episodeUrl,
                  }
                : null;
            })
            .filter((episode) => episode !== null);
          return episodes.length
            ? [{ name: names[index] || `线路 ${index + 1}`, episodes }]
            : [];
        });
      return {
        title: vod.vod_name || "",
        poster: posterAddress(vod.vod_pic, address),
        year: vod.vod_year || "",
        area: vod.vod_area || "",
        actors: vod.vod_actor || "",
        description: String(vod.vod_content || vod.vod_blurb || "")
          .replace(/<[^>]*>/g, " ")
          .replace(/&nbsp;/g, " ")
          .trim(),
        lines,
      };
    },
    streamUrl: async (input, filterAds) => {
      const playable = await playableUrl(input);
      return {
        url: (await proxy.url({ url: playable, filterAds })).url,
        isHls: /\.m3u8(?:$|[?#])/i.test(playable),
      };
    },
    devices: () => dlna.devices(),
    airplayPairBegin: async () => {
      throw new Error("安卓端暂不支持 AirPlay");
    },
    airplayPairFinish: async () => {
      throw new Error("安卓端暂不支持 AirPlay");
    },
    cast: async (device, url, title, filterAds) => {
      const playable = await playableUrl(url);
      const stream = await proxy.url({
        url: playable,
        filterAds,
        deviceHost: device.host,
      });
      const result = await dlna.cast({
        deviceId: device.id,
        stream: stream.url,
        url,
        title,
        isHls: /\.m3u8(?:$|[?#])/i.test(playable),
      });
      return result.ok;
    },
    castStop: () => dlna.stop(),
    castProgress: async () => {
      const result = await dlna.progress();
      return result.deviceId && result.url && result.duration
        ? {
            deviceId: result.deviceId,
            url: result.url,
            position: result.position || 0,
            duration: result.duration || 0,
          }
        : null;
    },
    onCastEnded: (callback) => {
      const listener = dlna.addListener("castEnded", callback);
      return () => {
        void listener.then((handle) => handle.remove());
      };
    },
    setTheme: (theme) => {
      void statusBar
        .setTheme({ theme })
        .catch((error) => console.error("状态栏颜色更新失败", error));
    },
    setVideoFullscreen: (fullscreen) => {
      void statusBar
        .setFullscreen({ fullscreen })
        .catch((error) => console.error("播放时状态栏更新失败", error));
    },
    minimize: () => undefined,
    maximize: () => undefined,
    close: () => undefined,
  };
}
