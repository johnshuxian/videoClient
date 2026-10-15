const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { createSettingsStore } = require("./settings.cjs");

test("设置、设备和播放记录写入指定目录后，重新启动仍能读取", (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "yingchuang-settings-"));
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
  first.set("filter-ads", false);
  first.set("devices", [
    { id: "tv-1", name: "客厅电视", host: "192.0.2.10", type: "airplay", paired: true },
  ]);
  first.set("airplay-credentials", { "tv-1": "pairing-token" });
  first.set("history", [{ key: "movie-1", position: 120, duration: 3600 }]);
  first.close();

  const reopened = createSettingsStore(directory);
  assert.deepEqual(reopened.get("vod-sources"), [
    { name: "测试源", url: "https://example.com" },
  ]);
  assert.equal(reopened.get("filter-ads"), false);
  assert.equal(reopened.get("devices")[0].name, "客厅电视");
  assert.equal(reopened.get("airplay-credentials")["tv-1"], "pairing-token");
  assert.equal(reopened.get("history")[0].position, 120);
  assert.equal(reopened.get("color-theme"), undefined);
  reopened.set("filter-ads", true);
  assert.equal(reopened.get("filter-ads"), true);
  reopened.close();
});
