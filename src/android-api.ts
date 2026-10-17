import { CapacitorHttp, registerPlugin } from "@capacitor/core";
import { CapacitorSQLite, SQLiteConnection } from "@capacitor-community/sqlite";
import { createMobileVideoAPI } from "./mobile-api";
import type { AppStatusBar, Dlna, VideoProxy } from "./mobile-api";

const proxy = registerPlugin<VideoProxy>("VideoProxy");
const dlna = registerPlugin<Dlna>("Dlna");
const statusBar = registerPlugin<AppStatusBar>("AppStatusBar");

export async function createAndroidVideoAPI(): Promise<Window["videoAPI"]> {
  const root = document.getElementById("root")!;
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
  await db.execute(
    "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
  );

  return createMobileVideoAPI({
    platform: "android",
    proxy,
    dlna,
    statusBar,
    request: async (url, timeout, headers, body) => {
      const response = await CapacitorHttp.request({
        url,
        method: body === undefined ? "GET" : "POST",
        headers,
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
    },
    loadSettings: async () => {
      const saved = await db.query("SELECT key, value FROM settings");
      const settings = new Map<string, unknown>();
      for (const row of saved.values || [])
        settings.set(row.key, JSON.parse(row.value));
      return settings;
    },
    saveSetting: async (key, value) => {
      await db.run(
        "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
        [key, JSON.stringify(value)],
      );
    },
  });
}
