const { test } = require("node:test");
const assert = require("node:assert/strict");
const { createHash } = require("node:crypto");
const fs = require("node:fs/promises");
const path = require("node:path");
const os = require("node:os");
const { updateAddress, isNewerVersion, releaseUpdate, createDesktopUpdater } = require("./app-update.cjs");

const binary = Buffer.from("test installer contents");
function release(platform = "windows", arch = "x64") {
  const name = `videoClient-v0.2.43-${platform}-${arch}.${platform === "windows" ? "exe" : "dmg"}`;
  return { tag_name: "v0.2.43", body: "更新说明", assets: [{ name,
    browser_download_url: `https://github.com/johnshuxian/videoClient/releases/download/v0.2.43/${name}`,
    size: binary.length, digest: `sha256:${createHash("sha256").update(binary).digest("hex")}` }] };
}

test("版本逐段比较，拒绝降级、相同版本和预发布标签", () => {
  assert.equal(isNewerVersion("v0.2.100", "0.2.99"), true);
  assert.equal(isNewerVersion("1.0.0", "0.99.99"), true);
  assert.equal(isNewerVersion("v0.2.42", "0.2.42"), false);
  assert.equal(isNewerVersion("v0.2.9", "0.2.42"), false);
  assert.throws(() => isNewerVersion("v0.2.43-beta", "0.2.42"));
  assert.equal(releaseUpdate({ ...release(), prerelease: true }, "0.2.42", "win32", "x64"), null);
});

test("按系统和架构选择固定仓库安装包，缺包或缺少校验值时拒绝下载", () => {
  assert.equal(releaseUpdate(release(), "0.2.42", "win32", "x64").version, "0.2.43");
  assert.equal(releaseUpdate(release("macos", "arm64"), "0.2.42", "darwin", "arm64").name.endsWith(".dmg"), true);
  assert.throws(() => releaseUpdate(release(), "0.2.42", "darwin", "arm64"), /架构/);
  const invalid = release();
  invalid.assets[0].browser_download_url = "https://example.com/installer.exe";
  assert.throws(() => releaseUpdate(invalid, "0.2.42", "win32", "x64"), /不完整/);
  invalid.assets[0] = { ...release().assets[0], digest: null };
  assert.throws(() => releaseUpdate(invalid, "0.2.42", "win32", "x64"), /不完整/);
});

test("更新接口和安装包使用同一条选定线路", () => {
  const url = "https://github.com/johnshuxian/videoClient/releases/download/v0.2.43/app.exe";
  assert.equal(updateAddress(url, "gh-proxy"), `https://gh-proxy.org/${url}`);
  assert.equal(updateAddress(url, "direct"), url);
  assert.throws(() => updateAddress(url, "other"));
});

test("仅在用户调用安装后下载；校验失败不能启动安装程序", async (t) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "videoClient-update-test-"));
  t.after(async () => {
    // 清理范围只包含本测试在系统临时目录下创建的文件。
    assert.equal(path.dirname(path.resolve(directory)), path.resolve(os.tmpdir()));
    await fs.rm(directory, { recursive: true, force: true });
  });
  const requests = [];
  const opened = [];
  let corrupt = true;
  t.mock.method(globalThis, "fetch", async (url) => {
    requests.push(url);
    return new Response(url.includes("api.github.com")
      ? JSON.stringify(release(process.platform === "darwin" ? "macos" : "windows", process.arch))
      : corrupt ? Buffer.alloc(binary.length) : binary);
  });
  const updater = createDesktopUpdater({ isPackaged: true, getVersion: () => "0.2.42",
    getPath: () => directory, quit: () => {} }, { openPath: async (file) => { opened.push(file); return ""; } }, () => {});
  await assert.rejects(() => updater.install(), /先检查更新/);
  assert.equal((await updater.check("gh-proxy")).version, "0.2.43");
  assert.equal(requests.length, 1);
  assert.equal(opened.length, 0);
  await assert.rejects(() => updater.install(), /校验失败/);
  assert.equal(opened.length, 0);
  corrupt = false;
  assert.deepEqual(await updater.install(), { status: "started" });
  assert.equal(opened.length, 1);
  assert.deepEqual(await fs.readFile(opened[0]), binary);
  assert.equal(requests.every((url) => url.startsWith("https://gh-proxy.org/")), true);
  await assert.rejects(() => updater.install(), /先检查更新/);
});
