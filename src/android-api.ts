import { CapacitorHttp, registerPlugin } from "@capacitor/core";
import { CapacitorSQLite, SQLiteConnection } from "@capacitor-community/sqlite";
import { createMobileVideoAPI } from "./mobile-api";
import type { AppStatusBar, Dlna, VideoProxy } from "./mobile-api";
import type { AppUpdate, HistoryItem, UpdateRoute } from "./types";

const proxy = registerPlugin<VideoProxy>("VideoProxy");
const dlna = registerPlugin<Dlna>("Dlna");
const statusBar = registerPlugin<AppStatusBar>("AppStatusBar");
const appUpdate = registerPlugin<{
  check(options: { route: UpdateRoute }): Promise<{ update: AppUpdate | null }>;
  downloadAndInstall(): Promise<{ status: "started" | "permission-required" }>;
  addListener(name: "progress", callback: (event: { percent: number }) => void): Promise<{ remove(): Promise<void> }>;
}>("AppUpdate");

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
  await db.execute(`CREATE TABLE IF NOT EXISTS play_history (
    key TEXT PRIMARY KEY,
    item_json TEXT NOT NULL,
    detail_json TEXT,
    title TEXT NOT NULL DEFAULT '',
    source_url TEXT NOT NULL DEFAULT '',
    line INTEGER NOT NULL,
    episode INTEGER NOT NULL,
    position REAL NOT NULL,
    duration REAL NOT NULL,
    updated_at INTEGER NOT NULL
  )`);
  await db.execute(`CREATE TABLE IF NOT EXISTS detail_cache (
    key TEXT PRIMARY KEY,
    detail_json TEXT NOT NULL
  )`);
  const historyColumns = await db.query("PRAGMA table_info(play_history)");
  if (!(historyColumns.values || []).some((column) => column.name === "title"))
    await db.execute("ALTER TABLE play_history ADD COLUMN title TEXT NOT NULL DEFAULT ''");
  if (!(historyColumns.values || []).some((column) => column.name === "source_url"))
    await db.execute("ALTER TABLE play_history ADD COLUMN source_url TEXT NOT NULL DEFAULT ''");
  // 旧记录从 item_json 回填查询字段，兼容最低 Android 版本的 SQLite。
  const oldRows = await db.query("SELECT key, item_json FROM play_history WHERE title = '' OR source_url = ''");
  for (const row of oldRows.values || []) {
    const item = JSON.parse(row.item_json) as HistoryItem["item"];
    await db.run("UPDATE play_history SET title = ?, source_url = ? WHERE key = ?",
      [item.title || "", item.sourceUrl || "", row.key], false);
  }
  await db.execute("CREATE INDEX IF NOT EXISTS play_history_title_updated ON play_history (title, updated_at DESC)");

  // 旧记录仅迁移一次；插入和删除旧值在同一事务内完成。
  const legacy = await db.query("SELECT value FROM settings WHERE key = 'history'");
  if (legacy.values?.length) {
    const entries = JSON.parse(legacy.values[0].value) as HistoryItem[];
    await db.beginTransaction();
    try {
      for (const entry of entries) {
        if (!entry?.key || !entry.item) continue;
        await db.run(`INSERT OR IGNORE INTO play_history
          (key, item_json, detail_json, title, source_url, line, episode, position, duration, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
          entry.key,
          JSON.stringify(entry.item),
          entry.detail ? JSON.stringify(entry.detail) : null,
          entry.item.title || "",
          entry.item.sourceUrl || "",
          entry.line || 0,
          entry.episode || 0,
          entry.position || 0,
          entry.duration || 0,
          entry.updatedAt || 0,
        ], false);
        if (entry.detail) {
          await db.run("INSERT OR IGNORE INTO detail_cache (key, detail_json) VALUES (?, ?)",
            [entry.key, JSON.stringify(entry.detail)], false);
        }
      }
      await db.run("DELETE FROM settings WHERE key = 'history'", [], false);
      await db.commitTransaction();
    } catch (error) {
      await db.rollbackTransaction();
      throw error;
    }
  }

  // 旧记录里的详情先迁到独立表，再清空旧列；中断后可再次执行。
  await db.beginTransaction();
  try {
    await db.run(`INSERT OR IGNORE INTO detail_cache (key, detail_json)
      SELECT key, detail_json FROM play_history
      WHERE detail_json IS NOT NULL AND detail_json <> ''`, [], false);
    await db.run("UPDATE play_history SET detail_json = NULL WHERE detail_json IS NOT NULL", [], false);
    await db.commitTransaction();
  } catch (error) {
    await db.rollbackTransaction();
    throw error;
  }

  const api = await createMobileVideoAPI({
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
    loadHistory: async () => {
      const saved = await db.query(
        `SELECT play_history.*, detail_cache.detail_json AS cached_detail_json
         FROM play_history LEFT JOIN detail_cache USING (key)
         ORDER BY play_history.updated_at DESC`,
      );
      return (saved.values || []).map((row) => ({
        key: row.key,
        item: JSON.parse(row.item_json),
        detail: row.cached_detail_json ? JSON.parse(row.cached_detail_json) : undefined,
        line: row.line,
        episode: row.episode,
        position: row.position,
        duration: row.duration,
        updatedAt: row.updated_at,
      }));
    },
    latestOtherSourceHistory: async (title, sourceUrl) => {
      const saved = await db.query(
        `SELECT play_history.*, detail_cache.detail_json AS cached_detail_json
         FROM play_history LEFT JOIN detail_cache USING (key)
         WHERE play_history.title = ? AND play_history.source_url <> ?
         ORDER BY play_history.updated_at DESC, play_history.key DESC LIMIT 1`,
        [title, sourceUrl],
      );
      const row = saved.values?.[0];
      return row ? {
        key: row.key,
        item: JSON.parse(row.item_json),
        detail: row.cached_detail_json ? JSON.parse(row.cached_detail_json) : undefined,
        line: row.line,
        episode: row.episode,
        position: row.position,
        duration: row.duration,
        updatedAt: row.updated_at,
      } : null;
    },
    upsertHistory: async (entry) => {
      await db.beginTransaction();
      try {
        await db.run(`INSERT OR REPLACE INTO play_history
          (key, item_json, title, source_url, line, episode, position, duration, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
          entry.key, JSON.stringify(entry.item), entry.item.title, entry.item.sourceUrl,
          entry.line, entry.episode,
          entry.position, entry.duration, entry.updatedAt,
        ], false);
        if (entry.detail) {
          const detailJson = JSON.stringify(entry.detail);
          await db.run(`UPDATE detail_cache SET detail_json = ?
            WHERE key = ? AND detail_json <> ?`, [detailJson, entry.key, detailJson], false);
          await db.run("INSERT OR IGNORE INTO detail_cache (key, detail_json) VALUES (?, ?)",
            [entry.key, detailJson], false);
        }
        await db.commitTransaction();
      } catch (error) {
        await db.rollbackTransaction();
        throw error;
      }
    },
    deleteHistory: async (key) => {
      await db.beginTransaction();
      try {
        await db.run("DELETE FROM play_history WHERE key = ?", [key], false);
        await db.run("DELETE FROM detail_cache WHERE key = ?", [key], false);
        await db.commitTransaction();
      } catch (error) {
        await db.rollbackTransaction();
        throw error;
      }
    },
    clearHistory: async () => {
      await db.beginTransaction();
      try {
        await db.run("DELETE FROM play_history", [], false);
        await db.run("DELETE FROM detail_cache", [], false);
        await db.commitTransaction();
      } catch (error) {
        await db.rollbackTransaction();
        throw error;
      }
    },
  });
  return {
    ...api,
    checkForUpdates: async (route) => (await appUpdate.check({ route })).update,
    downloadAndInstallUpdate: () => appUpdate.downloadAndInstall(),
    onUpdateProgress: (callback) => {
      const listener = appUpdate.addListener("progress", ({ percent }) => callback(percent));
      return () => { void listener.then((handle) => handle.remove()); };
    },
  };
}
