import { createMobileVideoAPI } from "./mobile-api";
import type { HistoryItem } from "./types";

type HistoryRow = {
  key: string;
  itemJson: string;
  detailJson: string;
  line: number;
  episode: number;
  position: number;
  duration: number;
  updatedAt: number;
};
type SettingRow = { key: string; value: string };

function historyRow(entry: HistoryItem): HistoryRow {
  return {
    key: entry.key,
    itemJson: JSON.stringify(entry.item),
    detailJson: entry.detail ? JSON.stringify(entry.detail) : "",
    line: entry.line,
    episode: entry.episode,
    position: entry.position,
    duration: entry.duration,
    updatedAt: entry.updatedAt,
  };
}

type BridgeReply = { id?: number; ok?: boolean; data?: unknown; error?: string; event?: string; deviceId?: string };
type BridgeRequest = {
  id: number;
  action: string;
  url?: string;
  timeout?: number;
  headers?: Record<string, string>;
  body?: unknown;
  image?: boolean;
  filterAds?: boolean;
  deviceHost?: string;
  deviceId?: string;
  stream?: string;
  title?: string;
  isHls?: boolean;
  startPosition?: number;
  theme?: string;
  fullscreen?: boolean;
  brightness?: number;
  surface?: "page" | "detail" | "settings";
  history?: HistoryRow;
  historyKey?: string;
  sourceUrl?: string;
  setting?: SettingRow;
};

export async function createHarmonyVideoAPI(): Promise<Window["videoAPI"]> {
  let port!: MessagePort;
  let nextId = 0;
  const pending = new Map<
    number,
    { resolve(value: unknown): void; reject(error: Error): void }
  >();
  const castListeners = new Set<(event: { deviceId: string; error: string }) => void>();
  const ready = new Promise<void>((resolve) => {
    // ArkWeb 在页面加载结束后交给网页一个 MessagePort，网络请求通过它交给原生端。
    window.addEventListener("message", (event) => {
      if (event.data !== "__init_harmony_port__" || !event.ports[0]) return;
      port = event.ports[0];
      port.onmessage = (replyEvent: MessageEvent<string>) => {
        if (replyEvent.data === "__harmony_back__") {
          window.dispatchEvent(new Event("harmony-back"));
          return;
        }
        const reply = JSON.parse(replyEvent.data) as BridgeReply;
        if (reply.event === "castEnded") {
          castListeners.forEach((listener) => listener({ deviceId: reply.deviceId || "", error: "" }));
          return;
        }
        if (reply.id === undefined) return;
        const task = pending.get(reply.id);
        if (!task) return;
        pending.delete(reply.id);
        if (reply.ok) task.resolve(reply.data);
        else task.reject(new Error(reply.error || "网络请求失败"));
      };
      resolve();
    });
  });
  async function call<T>(request: Omit<BridgeRequest, "id">): Promise<T> {
    await ready;
    return new Promise<T>((resolve, reject) => {
      const id = ++nextId;
      pending.set(id, { resolve: (value) => resolve(value as T), reject });
      port.postMessage(JSON.stringify({ id, ...request }));
    });
  }

  const api = await createMobileVideoAPI({
    // 现有界面将 android 用作手机布局标志；鸿蒙在迁移 UI 前复用该布局。
    platform: "android",
    request: (url, timeout, headers, body) =>
      call<string>({ action: "request", url, timeout, headers, body }),
    loadSettings: async () => {
      const rows = await call<SettingRow[]>({ action: "settingsList" });
      const settings = new Map<string, unknown>(
        rows.map((row) => [row.key, JSON.parse(row.value)]),
      );
      const oldKeys: string[] = [];
      for (let index = 0; index < localStorage.length; index++) {
        const key = localStorage.key(index);
        if (!key?.startsWith("harmony-setting:") || key === "harmony-setting:history") continue;
        const value = localStorage.getItem(key);
        if (value === null) continue;
        oldKeys.push(key);
        const settingKey = key.slice("harmony-setting:".length);
        if (settings.has(settingKey)) continue;
        const parsed = JSON.parse(value);
        await call<void>({ action: "settingsSave", setting: { key: settingKey, value } });
        settings.set(settingKey, parsed);
      }
      // 数据库已有更新值时保留数据库版本；写入完成后才清理旧存储。
      oldKeys.forEach((key) => localStorage.removeItem(key));
      return settings;
    },
    saveSetting: async (key, value) => {
      await call<void>({ action: "settingsSave", setting: { key, value: JSON.stringify(value) } });
    },
    loadHistory: async () => {
      const rows = await call<HistoryRow[]>({ action: "historyList" });
      const pending = new Map<string, HistoryItem>();
      const legacy = localStorage.getItem("harmony-setting:history");
      if (legacy) {
        for (const entry of JSON.parse(legacy) as HistoryItem[]) {
          pending.set(entry.key, entry);
        }
      }
      const oldKeys: string[] = [];
      for (let index = 0; index < localStorage.length; index++) {
        const key = localStorage.key(index);
        if (!key?.startsWith("harmony-history:")) continue;
        oldKeys.push(key);
        const value = localStorage.getItem(key);
        if (value) {
          const entry = JSON.parse(value) as HistoryItem;
          if ((pending.get(entry.key)?.updatedAt ?? -1) < entry.updatedAt)
            pending.set(entry.key, entry);
        }
      }
      // 数据库写入全部成功后，再清理旧的 WebView 存储。
      for (const entry of pending.values()) {
        const index = rows.findIndex((row) => row.key === entry.key);
        if (index >= 0) {
          // 旧记录已经迁移但详情缺失时，仅补详情，不覆盖数据库里的新进度。
          if (!rows[index].detailJson && entry.detail) {
            const row = { ...rows[index], detailJson: JSON.stringify(entry.detail) };
            await call<void>({ action: "historyUpsert", history: row });
            rows[index] = row;
          }
        } else {
          const row = historyRow(entry);
          await call<void>({ action: "historyUpsert", history: row });
          rows.push(row);
        }
      }
      localStorage.removeItem("harmony-setting:history");
      oldKeys.forEach((key) => localStorage.removeItem(key));
      return rows
        .sort((first, second) => second.updatedAt - first.updatedAt)
        .map((row) => ({
          key: row.key,
          item: JSON.parse(row.itemJson),
          detail: row.detailJson ? JSON.parse(row.detailJson) : undefined,
          line: row.line,
          episode: row.episode,
          position: row.position,
          duration: row.duration,
          updatedAt: row.updatedAt,
        }));
    },
    latestOtherSourceHistory: async (title, sourceUrl) => {
      const row = await call<HistoryRow | null>({ action: "historyLatestOtherSource", title, sourceUrl });
      return row ? {
        key: row.key,
        item: JSON.parse(row.itemJson),
        detail: row.detailJson ? JSON.parse(row.detailJson) : undefined,
        line: row.line,
        episode: row.episode,
        position: row.position,
        duration: row.duration,
        updatedAt: row.updatedAt,
      } : null;
    },
    upsertHistory: async (entry) => {
      await call<void>({ action: "historyUpsert", history: historyRow(entry) });
    },
    deleteHistory: async (key) => {
      await call<void>({ action: "historyDelete", historyKey: key });
    },
    clearHistory: async () => {
      await call<void>({ action: "historyClear" });
    },
    proxy: { url: ({ url, image, filterAds, deviceHost }) =>
      call<{ url: string }>({ action: "proxyUrl", url, image, filterAds, deviceHost }) },
    dlna: {
      devices: () => call({ action: "devices" }),
      cast: ({ deviceId, stream, url, title, isHls, startPosition }) =>
        call({ action: "cast", deviceId, stream, url, title, isHls, startPosition }),
      stop: () => call<void>({ action: "castStop" }),
      progress: () => call({ action: "castProgress" }),
      addListener: async (_name, callback) => {
        castListeners.add(callback);
        return { remove: async () => { castListeners.delete(callback); } };
      },
    },
    statusBar: {
      setTheme: ({ theme }) => call<void>({ action: "setTheme", theme }),
      setFullscreen: ({ fullscreen }) => call<void>({ action: "setFullscreen", fullscreen }),
      getBrightness: async () => ({ brightness: await call<number>({ action: "getBrightness" }) }),
      setBrightness: ({ brightness }) => call<void>({ action: "setBrightness", brightness }),
      resetBrightness: () => call<void>({ action: "resetBrightness" }),
    },
  });
  return {
    ...api,
    setSystemBarSurface: (surface) => {
      void call<void>({ action: "setSurface", surface }).catch((error) =>
        console.error("状态栏背景更新失败", error),
      );
    },
    close: () => {
      void call<void>({ action: "exitApp" }).catch((error) =>
        console.error("鸿蒙应用退出失败", error),
      );
    },
  };
}
