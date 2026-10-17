import { createMobileVideoAPI } from "./mobile-api";

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
  theme?: string;
  fullscreen?: boolean;
  brightness?: number;
  surface?: "page" | "detail" | "settings";
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
      const settings = new Map<string, unknown>();
      for (let index = 0; index < localStorage.length; index++) {
        const key = localStorage.key(index);
        if (!key?.startsWith("harmony-setting:")) continue;
        const value = localStorage.getItem(key);
        if (value !== null) settings.set(key.slice(16), JSON.parse(value));
      }
      return settings;
    },
    saveSetting: async (key, value) => {
      localStorage.setItem(`harmony-setting:${key}`, JSON.stringify(value));
    },
    proxy: { url: ({ url, image, filterAds, deviceHost }) =>
      call<{ url: string }>({ action: "proxyUrl", url, image, filterAds, deviceHost }) },
    dlna: {
      devices: () => call({ action: "devices" }),
      cast: ({ deviceId, stream, url, title, isHls }) =>
        call({ action: "cast", deviceId, stream, url, title, isHls }),
      stop: () => call<void>({ action: "castStop" }),
      progress: () => call({ action: "castProgress" }),
      addListener: async (_name, callback) => {
        castListeners.add(callback);
        return { remove: async () => { castListeners.delete(callback); } };
      },
    },
    statusBar: {
      deviceInfo: async () => ({ isTv: false }),
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
  };
}
