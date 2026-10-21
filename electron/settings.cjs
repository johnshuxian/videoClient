const fs = require("node:fs");
const path = require("node:path");
const { DatabaseSync } = require("node:sqlite");

function createSettingsStore(userDataPath) {
  fs.mkdirSync(userDataPath, { recursive: true });
  const database = new DatabaseSync(path.join(userDataPath, "settings.db"));
  database.exec(
    "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
  );
  database.exec(`CREATE TABLE IF NOT EXISTS play_history (
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
  database.exec(`CREATE TABLE IF NOT EXISTS detail_cache (
    key TEXT PRIMARY KEY,
    detail_json TEXT NOT NULL
  )`);
  const historyColumns = new Set(
    database.prepare("PRAGMA table_info(play_history)").all().map((column) => column.name),
  );
  if (!historyColumns.has("title"))
    database.exec("ALTER TABLE play_history ADD COLUMN title TEXT NOT NULL DEFAULT ''");
  if (!historyColumns.has("source_url"))
    database.exec("ALTER TABLE play_history ADD COLUMN source_url TEXT NOT NULL DEFAULT ''");
  // 旧记录只存 item_json；应用层回填避免依赖低版本 SQLite 的 JSON 函数。
  const missingTitles = database.prepare(
    "SELECT key, item_json FROM play_history WHERE title = '' OR source_url = ''",
  ).all();
  const fillTitle = database.prepare(
    "UPDATE play_history SET title = ?, source_url = ? WHERE key = ?",
  );
  for (const row of missingTitles) {
    const item = JSON.parse(row.item_json);
    fillTitle.run(item.title || "", item.sourceUrl || "", row.key);
  }
  database.exec("CREATE INDEX IF NOT EXISTS play_history_title_updated ON play_history (title, updated_at DESC)");
  const read = database.prepare("SELECT value FROM settings WHERE key = ?");
  const write = database.prepare(
    "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
  );
  const listHistory = database.prepare(
    `SELECT play_history.*, detail_cache.detail_json AS cached_detail_json
     FROM play_history LEFT JOIN detail_cache USING (key)
     ORDER BY play_history.updated_at DESC`,
  );
  const latestOtherSourceHistory = database.prepare(
    `SELECT play_history.*, detail_cache.detail_json AS cached_detail_json
     FROM play_history LEFT JOIN detail_cache USING (key)
     WHERE play_history.title = ? AND play_history.source_url <> ?
     ORDER BY play_history.updated_at DESC, play_history.key DESC LIMIT 1`,
  );
  const upsertHistory = database.prepare(`INSERT INTO play_history
    (key, item_json, title, source_url, line, episode, position, duration, updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(key) DO UPDATE SET
      item_json = excluded.item_json,
      title = excluded.title,
      source_url = excluded.source_url,
      line = excluded.line,
      episode = excluded.episode,
      position = excluded.position,
      duration = excluded.duration,
      updated_at = excluded.updated_at`);
  const upsertDetail = database.prepare(`INSERT INTO detail_cache (key, detail_json)
    VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET detail_json = excluded.detail_json
    WHERE detail_cache.detail_json <> excluded.detail_json`);
  const migrateHistory = database.prepare(`INSERT OR IGNORE INTO play_history
    (key, item_json, detail_json, title, source_url, line, episode, position, duration, updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
  const migrateDetail = database.prepare(
    "INSERT OR IGNORE INTO detail_cache (key, detail_json) VALUES (?, ?)",
  );
  const deleteHistory = database.prepare("DELETE FROM play_history WHERE key = ?");
  const deleteDetail = database.prepare("DELETE FROM detail_cache WHERE key = ?");

  // 旧版本把所有记录放在 settings.history；迁移成功后才删除旧值。
  const legacy = read.get("history");
  if (legacy) {
    database.exec("BEGIN");
    try {
      for (const entry of JSON.parse(legacy.value)) {
        if (!entry?.key || !entry.item) continue;
        migrateHistory.run(
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
        );
        if (entry.detail) migrateDetail.run(entry.key, JSON.stringify(entry.detail));
      }
      database.prepare("DELETE FROM settings WHERE key = 'history'").run();
      database.exec("COMMIT");
    } catch (error) {
      database.exec("ROLLBACK");
      throw error;
    }
  }

  // 旧版详情与进度同列保存；先复制到独立表，再清空旧列，重复启动也可安全迁移。
  database.exec("BEGIN");
  try {
    database.exec(`INSERT OR IGNORE INTO detail_cache (key, detail_json)
      SELECT key, detail_json FROM play_history WHERE detail_json IS NOT NULL AND detail_json <> ''`);
    database.exec("UPDATE play_history SET detail_json = NULL WHERE detail_json IS NOT NULL");
    database.exec("COMMIT");
  } catch (error) {
    database.exec("ROLLBACK");
    throw error;
  }

  const historyFromRow = (row) => row && ({
    key: row.key,
    item: JSON.parse(row.item_json),
    detail: row.cached_detail_json ? JSON.parse(row.cached_detail_json) : undefined,
    line: row.line,
    episode: row.episode,
    position: row.position,
    duration: row.duration,
    updatedAt: row.updated_at,
  });

  return {
    get(key) {
      const row = read.get(key);
      return row ? JSON.parse(row.value) : undefined;
    },
    set(key, value) {
      // 设置值包括数组和对象；以 JSON 存在 SQLite 中，保持渲染进程现有的数据格式。
      write.run(key, JSON.stringify(value));
    },
    listHistory() {
      return listHistory.all().map(historyFromRow);
    },
    latestOtherSourceHistory(title, sourceUrl) {
      return historyFromRow(latestOtherSourceHistory.get(title, sourceUrl)) || null;
    },
    upsertHistory(entry) {
      database.exec("BEGIN");
      try {
        upsertHistory.run(
          entry.key,
          JSON.stringify(entry.item),
          entry.item.title,
          entry.item.sourceUrl,
          entry.line,
          entry.episode,
          entry.position,
          entry.duration,
          entry.updatedAt,
        );
        if (entry.detail) upsertDetail.run(entry.key, JSON.stringify(entry.detail));
        database.exec("COMMIT");
      } catch (error) {
        database.exec("ROLLBACK");
        throw error;
      }
    },
    deleteHistory(key) {
      database.exec("BEGIN");
      try {
        deleteHistory.run(key);
        deleteDetail.run(key);
        database.exec("COMMIT");
      } catch (error) {
        database.exec("ROLLBACK");
        throw error;
      }
    },
    clearHistory() {
      database.exec("BEGIN");
      try {
        database.exec("DELETE FROM play_history");
        database.exec("DELETE FROM detail_cache");
        database.exec("COMMIT");
      } catch (error) {
        database.exec("ROLLBACK");
        throw error;
      }
    },
    close() {
      database.close();
    },
  };
}

module.exports = { createSettingsStore };
