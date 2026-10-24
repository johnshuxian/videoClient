const path = require("node:path");
const fs = require("node:fs/promises");
const { createWriteStream } = require("node:fs");
const { createHash } = require("node:crypto");
const { Transform } = require("node:stream");
const { pipeline } = require("node:stream/promises");

const releaseApi = "https://api.github.com/repos/johnshuxian/videoClient/releases/latest";
const releaseBase = "https://github.com/johnshuxian/videoClient/releases/download/";

function updateAddress(url, route) {
  if (route !== "gh-proxy" && route !== "direct") throw new Error("无效的更新线路");
  return route === "gh-proxy" ? `https://gh-proxy.org/${url}` : url;
}

function isNewerVersion(candidate, current) {
  const pattern = /^v?(\d+)\.(\d+)\.(\d+)$/;
  const next = pattern.exec(candidate);
  const previous = pattern.exec(current);
  if (!next || !previous) throw new Error("更新版本号格式无效");
  for (let index = 1; index <= 3; index++) {
    if (Number(next[index]) !== Number(previous[index]))
      return Number(next[index]) > Number(previous[index]);
  }
  return false;
}

function releaseUpdate(release, current, platform, arch) {
  if (release.draft || release.prerelease) return null;
  if (!isNewerVersion(release.tag_name, current)) return null;
  const extension = platform === "win32" ? "exe" : platform === "darwin" ? "dmg" : null;
  if (!extension) throw new Error("当前平台不支持安装更新");
  const version = release.tag_name.replace(/^v/, "");
  const name = `videoClient-v${version}-${platform === "win32" ? "windows" : "macos"}-${arch}.${extension}`;
  const asset = release.assets?.find((item) => item.name === name);
  if (!asset) throw new Error("新版本暂未提供当前系统和架构的安装包");
  // 只接受当前仓库的固定版本附件，页面不能传入任意可执行文件地址。
  if (asset.browser_download_url !== `${releaseBase}${release.tag_name}/${name}` ||
      !/^sha256:[a-f0-9]{64}$/i.test(asset.digest || "") ||
      !Number.isSafeInteger(asset.size) || asset.size <= 0)
    throw new Error("更新安装包信息不完整，暂时无法下载");
  return { version, notes: release.body || "", name, size: asset.size,
    url: asset.browser_download_url, sha256: asset.digest.slice(7) };
}

function createDesktopUpdater(app, shell, onProgress) {
  let pending;
  let busy = false;
  return {
    async check(route) {
      if (busy) throw new Error("正在下载更新，请稍候");
      pending = null;
      const response = await fetch(updateAddress(releaseApi, route), {
        headers: { "User-Agent": "videoClient", Accept: "application/vnd.github+json" },
        signal: AbortSignal.timeout(25000),
        cache: "no-store",
      });
      if (!response.ok) throw new Error(`检查更新失败（HTTP ${response.status}）`);
      const update = releaseUpdate(await response.json(), app.getVersion(), process.platform, process.arch);
      if (update) pending = { ...update, route };
      return update && { version: update.version, notes: update.notes, size: update.size };
    },
    async install() {
      if (busy) throw new Error("正在下载更新，请稍候");
      if (!pending) throw new Error("请先检查更新");
      if (!app.isPackaged) throw new Error("请在已安装的正式版本中运行更新安装");
      busy = true;
      const update = pending;
      let partial;
      let installer;
      try {
        const directory = await fs.mkdtemp(path.join(app.getPath("temp"), "videoclient-update-"));
        installer = path.join(directory, update.name);
        partial = `${installer}.part`;
        const response = await fetch(updateAddress(update.url, update.route), {
          headers: { "User-Agent": "videoClient" },
          signal: AbortSignal.timeout(30 * 60_000),
        });
        if (!response.ok) throw new Error(`下载更新失败（HTTP ${response.status}）`);
        const hash = createHash("sha256");
        let received = 0;
        let reported = -1;
        await pipeline(response.body, new Transform({
          transform(chunk, _encoding, callback) {
            received += chunk.length;
            if (received > update.size) return callback(new Error("更新文件大小不符"));
            hash.update(chunk);
            const percent = Math.floor(received * 100 / update.size);
            if (percent !== reported) { reported = percent; onProgress(percent); }
            callback(null, chunk);
          },
        }), createWriteStream(partial, { flags: "wx" }));
        if (received !== update.size || hash.digest("hex") !== update.sha256.toLowerCase())
          throw new Error("更新文件校验失败，请重新下载");
        await fs.rename(partial, installer);
        const error = await shell.openPath(installer);
        if (error) throw new Error(`无法打开安装程序：${error}`);
        pending = null;
        // NSIS 需要替换正在运行的程序；安装器成功启动后才退出，数据库由 before-quit 关闭。
        if (process.platform === "win32") setTimeout(() => app.quit(), 500);
        return { status: "started" };
      } finally {
        busy = false;
        if (partial) await fs.rm(partial, { force: true });
      }
    },
  };
}

module.exports = { updateAddress, isNewerVersion, releaseUpdate, createDesktopUpdater };
