const fs = require("node:fs");
const path = require("node:path");
const { DatabaseSync } = require("node:sqlite");

function createSettingsStore(userDataPath) {
  fs.mkdirSync(userDataPath, { recursive: true });
  const database = new DatabaseSync(path.join(userDataPath, "settings.db"));
  database.exec(
    "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
  );
  const read = database.prepare("SELECT value FROM settings WHERE key = ?");
  const write = database.prepare(
    "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
  );

  return {
    get(key) {
      const row = read.get(key);
      return row ? JSON.parse(row.value) : undefined;
    },
    set(key, value) {
      // 设置值包括数组和对象；以 JSON 存在 SQLite 中，保持渲染进程现有的数据格式。
      write.run(key, JSON.stringify(value));
    },
    close() {
      database.close();
    },
  };
}

module.exports = { createSettingsStore };
