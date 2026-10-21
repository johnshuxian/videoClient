const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { DatabaseSync } = require("node:sqlite");
const { createSettingsStore } = require("./settings.cjs");

test("设置、设备和播放记录写入指定目录后，重新启动仍能读取", (t) => {
  const directory = fs.mkdtempSync(
    path.join(os.tmpdir(), "yingchuang-settings-"),
  );
  t.after(() => {
    fs.unlinkSync(path.join(directory, "settings.db"));
    fs.unlinkSync(path.join(directory, "settings.json"));
    fs.rmdirSync(directory);
  });

  fs.writeFileSync(
    path.join(directory, "settings.json"),
    JSON.stringify({ "color-theme": "light" }),
  );
  const first = createSettingsStore(directory);
  assert.equal(first.get("color-theme"), undefined);
  first.set("vod-sources", [{ name: "测试源", url: "https://example.com" }]);
  first.set("custom-source-url", "https://example.com");
  first.set("filter-ads", false);
  first.set("devices", [
    {
      id: "tv-1",
      name: "客厅电视",
      host: "192.0.2.10",
      type: "dlna",
    },
  ]);
  first.set("history", [{
    key: "source-a:movie-1",
    item: { id: "movie-1", title: "测试影片", sourceUrl: "source-a" },
    detail: { title: "测试影片", lines: [{ name: "主线", episodes: [{ label: "第 1 集", url: "https://example.com/1" }] }] },
    line: 0,
    episode: 1,
    position: 120,
    duration: 3600,
    updatedAt: 1000,
  }]);
  first.close();

  const reopened = createSettingsStore(directory);
  assert.deepEqual(reopened.get("vod-sources"), [
    { name: "测试源", url: "https://example.com" },
  ]);
  assert.equal(reopened.get("custom-source-url"), "https://example.com");
  assert.equal(reopened.get("filter-ads"), false);
  assert.equal(reopened.get("devices")[0].name, "客厅电视");
  assert.equal(reopened.get("history"), undefined);
  assert.equal(reopened.listHistory()[0].position, 120);
  assert.equal(reopened.listHistory()[0].detail.lines[0].episodes[0].label, "第 1 集");
  reopened.upsertHistory({
    ...reopened.listHistory()[0],
    position: 240,
    updatedAt: 2000,
  });
  reopened.upsertHistory({
    key: "source-b:movie-2",
    item: { id: "movie-2", title: "另一部影片", sourceUrl: "source-b" },
    line: 0,
    episode: 0,
    position: 30,
    duration: 1800,
    updatedAt: 1500,
  });
  assert.deepEqual(reopened.listHistory().map((entry) => entry.position), [240, 30]);
  reopened.close();

  const stored = new DatabaseSync(path.join(directory, "settings.db"));
  assert.equal(stored.prepare("SELECT detail_json FROM play_history WHERE key = ?").get("source-a:movie-1").detail_json, null);
  assert.equal(stored.prepare("SELECT count(*) AS total FROM detail_cache").get().total, 1);
  stored.close();

  const afterRestart = createSettingsStore(directory);
  assert.deepEqual(afterRestart.listHistory().map((entry) => entry.position), [240, 30]);
  afterRestart.deleteHistory("source-a:movie-1");
  assert.equal(afterRestart.listHistory().length, 1);
  afterRestart.clearHistory();
  assert.deepEqual(afterRestart.listHistory(), []);
  assert.equal(afterRestart.get("color-theme"), undefined);
  afterRestart.set("filter-ads", true);
  assert.equal(afterRestart.get("filter-ads"), true);
  afterRestart.close();
  const cleared = new DatabaseSync(path.join(directory, "settings.db"));
  assert.equal(cleared.prepare("SELECT count(*) AS total FROM detail_cache").get().total, 0);
  cleared.close();
});

test("旧播放记录中的详情迁入 detail_cache", (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "yingchuang-detail-"));
  const dbPath = path.join(directory, "settings.db");
  t.after(() => {
    fs.unlinkSync(dbPath);
    fs.rmdirSync(directory);
  });
  const database = new DatabaseSync(dbPath);
  database.exec(`CREATE TABLE play_history (
    key TEXT PRIMARY KEY, item_json TEXT NOT NULL, detail_json TEXT,
    line INTEGER NOT NULL, episode INTEGER NOT NULL, position REAL NOT NULL,
    duration REAL NOT NULL, updated_at INTEGER NOT NULL
  )`);
  database.prepare(`INSERT INTO play_history VALUES (?, ?, ?, 0, 0, 10, 100, 1)`).run(
    "source:movie", JSON.stringify({ id: "movie", sourceUrl: "source" }),
    JSON.stringify({ title: "旧详情", lines: [] }),
  );
  database.close();

  const store = createSettingsStore(directory);
  assert.equal(store.listHistory()[0].detail.title, "旧详情");
  store.close();
  const migrated = new DatabaseSync(dbPath);
  assert.equal(migrated.prepare("SELECT detail_json FROM play_history").get().detail_json, null);
  assert.equal(JSON.parse(migrated.prepare("SELECT detail_json FROM detail_cache").get().detail_json).title, "旧详情");
  migrated.close();
});

test("其它源播放记录只返回同名最新一条，旧数据库可回填查询字段", (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "yingchuang-other-source-"));
  const dbPath = path.join(directory, "settings.db");
  t.after(() => {
    fs.unlinkSync(dbPath);
    fs.rmdirSync(directory);
  });
  const old = new DatabaseSync(dbPath);
  old.exec(`CREATE TABLE play_history (
    key TEXT PRIMARY KEY, item_json TEXT NOT NULL, detail_json TEXT,
    line INTEGER NOT NULL, episode INTEGER NOT NULL, position REAL NOT NULL,
    duration REAL NOT NULL, updated_at INTEGER NOT NULL
  )`);
  const insert = old.prepare("INSERT INTO play_history VALUES (?, ?, NULL, 0, ?, 60, 600, ?)");
  insert.run("source-a:1", JSON.stringify({ id: "1", title: "同名影片", sourceUrl: "source-a" }), 1, 100);
  insert.run("source-b:2", JSON.stringify({ id: "2", title: "同名影片", sourceUrl: "source-b" }), 2, 200);
  insert.run("source-c:3", JSON.stringify({ id: "3", title: "其他影片", sourceUrl: "source-c" }), 3, 300);
  old.close();

  const store = createSettingsStore(directory);
  assert.equal(store.latestOtherSourceHistory("同名影片", "source-current").key, "source-b:2");
  assert.equal(store.latestOtherSourceHistory("同名影片", "source-b").key, "source-a:1");
  assert.equal(store.latestOtherSourceHistory("没有记录", "source-current"), null);
  store.upsertHistory({
    key: "source-d:4",
    item: { id: "4", title: "同名影片", sourceUrl: "source-d" },
    line: 0, episode: 4, position: 120, duration: 600, updatedAt: 400,
  });
  assert.equal(store.latestOtherSourceHistory("同名影片", "source-current").key, "source-d:4");
  store.close();
});
