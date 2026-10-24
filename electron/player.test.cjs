const test = require("node:test");
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const { createServer: createHttpServer } = require("node:http");
const path = require("node:path");
const { proxyStream } = require("./stream-proxy.cjs");

// 用真实 Electron、React 和 ArtPlayer 验证卸载后的媒体状态，覆盖库内的延迟任务。
if (process.versions.electron) {
  const { app, BrowserWindow } = require("electron");
  app.commandLine.appendSwitch("autoplay-policy", "no-user-gesture-required");
  app.commandLine.appendSwitch("mute-audio");
  // 全部测试服务都在本机，避免系统代理探测影响请求耗时和重试次数。
  app.commandLine.appendSwitch("no-proxy-server");
  app.whenReady().then(async () => {
    const window = new BrowserWindow({
      show: false,
      webPreferences: { backgroundThrottling: false },
    });
    window.webContents.on("console-message", event => {
      process.stderr.write(`${event.message}\n`);
    });
    try {
      await window.loadURL(process.argv[2]);
      const result = await window.webContents.executeJavaScript(`
        (async () => {
          for (let i = 0; !window.playerTest && i < 1000; i++)
            await new Promise(resolve => setTimeout(resolve, 10));
          if (!window.playerTest) throw new Error("播放器测试页面未能加载");
          return window.playerTest.run();
        })()
      `);
      process.stdout.write(`PLAYER_TEST_RESULT=${JSON.stringify(result)}\n`);
      app.exit(0);
    } catch (error) {
      process.stderr.write(`${error.stack}\n`);
      app.exit(1);
    }
  });
} else {
  test("播放器生命周期与 HLS 清单失败恢复", { timeout: 90000 }, async (t) => {
    const { createServer } = await import("vite");
    const requests = {};
    let proxyOrigin;
    // 使用生产代理和另一个端口，确保浏览器确实经过跨域错误处理。
    const proxy = createHttpServer((req, res) => {
      const target = new URL(req.url, "http://localhost").searchParams.get("url");
      void proxyStream(req, res, {
        target, requestHeaders: {}, filterAds: false,
        proxyUrl: url => proxyOrigin + "/stream?url=" + encodeURIComponent(url),
      });
    });
    const server = await createServer({
      configFile: false,
      root: path.join(__dirname, ".."),
      cacheDir: "node_modules/.vite-player-test",
      // 测试 HTML 由中间件生成；显式预编译依赖，避免冷启动发现新依赖后重载测试页面。
      optimizeDeps: {
        noDiscovery: true,
        force: true,
        include: ["react", "react/jsx-runtime", "react/jsx-dev-runtime", "react-dom/client", "artplayer", "hls.js", "lucide-react", "@capacitor/app"],
      },
      server: { host: "127.0.0.1", port: 0 },
      plugins: [{
        name: "player-lifecycle-test",
        transform(code, id) {
          if (id.replaceAll("\\", "/").endsWith("/src/App.tsx"))
            return `${code}\nexport { Player };`;
        },
        configureServer(server) {
          server.middlewares.use(async (req, res, next) => {
            if (req.url === "/request-counts") {
              res.setHeader("Content-Type", "application/json");
              res.end(JSON.stringify(requests));
              return;
            }
            if (req.url?.startsWith("/manifest/")) {
              const name = req.url.split("/").at(-1).replace(".m3u8", "");
              const attempt = requests[name] = (requests[name] || 0) + 1;
              if (name === "timeout" && attempt === 1) return;
              const status = name === "forbidden" ? 403 : name === "missing" ? 404
                : name === "unavailable" || name === "cancel" || name === "flaky" && attempt < 3 ? 503 : 200;
              res.writeHead(status, { "Content-Type": "application/vnd.apple.mpegurl" });
              res.end(status !== 200 ? "source unavailable" : name === "invalid" ? "<html>not a playlist</html>"
                : "#EXTM3U\n#EXT-X-TARGETDURATION:15\n#EXTINF:15,\n/test.wav\n#EXT-X-ENDLIST");
              return;
            }
            if (req.url === "/test.m3u8") {
              res.writeHead(200, { "Content-Type": "application/vnd.apple.mpegurl" });
              res.end("#EXTM3U\n#EXT-X-TARGETDURATION:15\n#EXTINF:15,\n/test.wav\n#EXT-X-ENDLIST");
              return;
            }
            if (req.url?.startsWith("/test.wav")) {
              // 本机生成短音频，让 Chromium 实际取流、自动播放，无需外部点播源。
              const samples = 8000 * 15;
              const wav = Buffer.alloc(44 + samples * 2);
              wav.write("RIFF", 0);
              wav.writeUInt32LE(wav.length - 8, 4);
              wav.write("WAVEfmt ", 8);
              wav.writeUInt32LE(16, 16);
              wav.writeUInt16LE(1, 20);
              wav.writeUInt16LE(1, 22);
              wav.writeUInt32LE(8000, 24);
              wav.writeUInt32LE(16000, 28);
              wav.writeUInt16LE(2, 32);
              wav.writeUInt16LE(16, 34);
              wav.write("data", 36);
              wav.writeUInt32LE(samples * 2, 40);
              res.writeHead(200, { "Content-Type": "audio/wav" });
              res.end(wav);
              return;
            }
            if (req.url !== "/player-test") return next();
            res.setHeader("Content-Type", "text/html");
            res.end(await server.transformIndexHtml("/player-test", `
              <div id="root"></div>
              <script type="module">
                import { createElement, StrictMode } from "react";
                import { createRoot } from "react-dom/client";
                import Artplayer from "artplayer";
                import Hls from "hls.js";
                import { Player } from "/src/App.tsx";

                const proxyOrigin = ${JSON.stringify(proxyOrigin)};
                const parsedManifests = new Set();
                const originalLoadSource = Hls.prototype.loadSource;
                Hls.prototype.loadSource = function(url) {
                  this.on(Hls.Events.MANIFEST_PARSED, () => parsedManifests.add(url));
                  originalLoadSource.call(this, url);
                };
                const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
                async function until(check, description = "播放器状态") {
                  for (let i = 0; i < 3000; i++) {
                    if (await check()) return;
                    await wait(10);
                  }
                  throw new Error("等待" + description + "超时：" + (document.querySelector(".video-error")?.textContent || ""));
                }
                window.videoAPI = {
                  platform: "win32",
                  streamUrl: async url => ({
                    url: url.includes("/manifest/") ? proxyOrigin + "/stream?url=" + encodeURIComponent(url) : url,
                    isHls: url.endsWith(".m3u8"),
                  }),
                };
                const playerInstance = { current: null };
                const props = {
                  filterAds: true, playbackSerial: 0, resume: 0,
                  title: "回归测试", episodeLabel: "第1集", hasNextEpisode: false,
                  onProgress() {}, onPlaying() {}, onEnded() {}, onNextEpisode() {},
                  onFailure: () => false, playerInstance,
                };
                let root;
                function render(url) {
                  root.render(createElement(StrictMode, null,
                    createElement(Player, { ...props, url })));
                }
                async function mount(url, waitForPlayback = true) {
                  root = createRoot(document.getElementById("root"));
                  render(url);
                  await until(() => playerInstance.current && (waitForPlayback
                    ? !playerInstance.current.video.paused
                    : playerInstance.current.video.src.startsWith("blob:")));
                  return playerInstance.current;
                }
                window.playerTest = { async run() {
                  const results = [];
                  // 先确认跨域代理可达，避免首次建立连接的耗时占用故障注入的重试预算。
                  const probe = proxyOrigin + "/stream?url=" + encodeURIComponent(location.origin + "/test.m3u8");
                  const response = await fetch(probe);
                  if (!response.ok || !(await response.text()).startsWith("#EXTM3U"))
                    throw new Error("跨域测试代理未就绪");
                  const firstUrl = location.origin + "/test.wav?first";
                  const secondUrl = location.origin + "/test.wav?second";
                  let failures = 0;
                  props.onFailure = () => { failures++; return false; };
                  for (const name of ["flaky", "timeout"]) {
                    failures = 0;
                    const url = location.origin + "/manifest/" + name + ".m3u8";
                    const proxied = proxyOrigin + "/stream?url=" + encodeURIComponent(url);
                    await mount(url, false);
                    await until(() => parsedManifests.has(proxied), name + " 清单恢复");
                    root.unmount();
                    const counts = await fetch("/request-counts").then(res => res.json());
                    results.push({
                      name: name === "flaky" ? "清单连续两次 503 后恢复" : "清单首次超时后恢复",
                      recovered: counts[name] === (name === "flaky" ? 3 : 2),
                      noPrematureFailure: failures === 0,
                    });
                  }
                  for (const [name, status, hint] of [
                    ["forbidden", 403, "拒绝访问"], ["missing", 404, "已失效"],
                    ["unavailable", 503, "请求失败"], ["invalid", 200, "格式无效"],
                  ]) {
                    failures = 0;
                    await mount(location.origin + "/manifest/" + name + ".m3u8", false);
                    await until(() => document.querySelector(".video-error"), name + " 错误提示");
                    const message = document.querySelector(".video-error").textContent;
                    const counts = await fetch("/request-counts").then(res => res.json());
                    results.push({
                      name: "清单最终失败：" + name,
                      boundedRetries: counts[name] === (status === 503 ? 3 : 1),
                      oneFailure: failures === 1,
                      readableMessage: message.includes(hint) && (status === 200 || message.includes("HTTP " + status)),
                    });
                    root.unmount();
                  }
                  failures = 0;
                  await mount(location.origin + "/manifest/cancel.m3u8", false);
                  await until(async () => {
                    const counts = await fetch("/request-counts").then(res => res.json());
                    return counts.cancel === 1;
                  });
                  root.unmount();
                  await wait(1300);
                  const counts = await fetch("/request-counts").then(res => res.json());
                  results.push({
                    name: "退出后取消清单重试",
                    noRetry: counts.cancel === 1,
                    noFailure: failures === 0,
                    active: Artplayer.instances.length === 0,
                  });
                  let endedFullscreen;
                  props.onEnded = fullscreen => { endedFullscreen = fullscreen; };
                  const first = await mount(firstUrl);
                  first.fullscreenWeb = true;
                  first.video.dispatchEvent(new Event("ended"));
                  await until(() => !first.fullscreenWeb, "最后一集结束后退出全屏");
                  results.push({ name: "最后一集全屏结束返回详情", exited: !first.fullscreenWeb, notified: endedFullscreen === true });
                  props.hasNextEpisode = true;
                  render(firstUrl);
                  await until(() => first.template.$player.classList.contains("has-next-episode"));
                  first.fullscreenWeb = true;
                  first.video.dispatchEvent(new Event("ended"));
                  results.push({ name: "有下一集时保留全屏连播", fullscreen: first.fullscreenWeb, notified: endedFullscreen === true });
                  first.fullscreenWeb = false;
                  props.hasNextEpisode = false;
                  render(firstUrl);
                  await until(() => !first.template.$player.classList.contains("has-next-episode"));
                  first.video.dispatchEvent(new Event("ended"));
                  results.push({ name: "非全屏结束保持当前详情", inline: !first.fullscreenWeb, notified: endedFullscreen === false });
                  // 在错误重连等待期间退出，然后立即播放另一部视频。
                  first.video.dispatchEvent(new Event("error"));
                  root.unmount();
                  const second = await mount(secondUrl);
                  await wait(1300);
                  results.push({
                    name: "报错后退出并播放另一影片",
                    stopped: first.video.paused && !first.video.getAttribute("src"),
                    active: Artplayer.instances.length === 1 && playerInstance.current === second,
                    playing: !second.video.paused,
                  });
                  root.unmount();
                  // HLS 的 customType 由库延迟调用，模拟已排队的回调在销毁后抵达。
                  const hlsUrl = location.origin + "/test.m3u8";
                  const hlsPlayer = await mount(hlsUrl, false);
                  const delayedHls = hlsPlayer.option.customType.m3u8;
                  hlsPlayer.fullscreenWeb = true;
                  root.unmount();
                  delayedHls(hlsPlayer.video, hlsUrl, hlsPlayer);
                  await wait(50);
                  results.push({
                    name: "全屏退出后抵达的 HLS 回调",
                    stopped: hlsPlayer.video.paused && !hlsPlayer.video.getAttribute("src"),
                    active: Artplayer.instances.length === 0 && playerInstance.current === null,
                    noOrphan: document.querySelectorAll(".art-video-player").length === 0,
                  });
                  // 新地址还在解析时，旧视频应立即释放；解析完成仍复用播放器。
                  const third = await mount(firstUrl);
                  let resolveStream;
                  window.videoAPI.streamUrl = () => new Promise(resolve => { resolveStream = resolve; });
                  render(secondUrl);
                  await until(() => resolveStream);
                  results.push({
                    name: "切换视频期间停止旧流",
                    stopped: third.video.paused && !third.video.getAttribute("src"),
                  });
                  resolveStream({ url: secondUrl, isHls: false });
                  await until(() => !third.video.paused);
                  results.push({
                    name: "新地址加载后继续播放",
                    active: playerInstance.current === third && Artplayer.instances.length === 1,
                    playing: third.video.src === secondUrl && !third.video.paused,
                  });
                  // 解析尚未完成就退出，稍后返回的地址不能创建新实例。
                  render(firstUrl);
                  const previousResolve = resolveStream;
                  await until(() => resolveStream !== previousResolve);
                  root.unmount();
                  resolveStream({ url: firstUrl, isHls: false });
                  await wait(50);
                  results.push({
                    name: "退出后返回的播放地址",
                    stopped: third.video.paused && !third.video.getAttribute("src"),
                    active: Artplayer.instances.length === 0 && playerInstance.current === null,
                  });
                  // 手机与鸿蒙共用网页全屏，但退出时还必须恢复各自的系统栏状态。
                  window.videoAPI.platform = "android";
                  window.videoAPI.streamUrl = async url => ({ url, isHls: false });
                  for (const platform of ["Android", "HarmonyOS"]) {
                    document.documentElement.dataset.harmony = String(platform === "HarmonyOS");
                    const fullscreenChanges = [];
                    window.videoAPI.setVideoFullscreen = fullscreen => fullscreenChanges.push(fullscreen);
                    const mobile = await mount(firstUrl);
                    mobile.fullscreenWeb = true;
                    mobile.video.dispatchEvent(new Event("ended"));
                    await until(() => !mobile.fullscreenWeb, platform + " 最后一集返回详情");
                    results.push({ name: platform + " 最后一集恢复系统栏",
                      exited: !mobile.fullscreenWeb, restored: fullscreenChanges.at(-1) === false,
                      notified: endedFullscreen === true,
                    });
                    root.unmount();
                  }
                  return results;
                }};
              </script>
            `));
          });
        },
      }],
    });
    let child;
    t.after(async () => {
      if (child?.exitCode === null) child.kill();
      await new Promise(resolve => proxy.close(resolve));
      await server.close();
    });
    await new Promise(resolve => proxy.listen(0, "127.0.0.1", resolve));
    proxyOrigin = `http://127.0.0.1:${proxy.address().port}`;
    await server.listen();
    const url = `http://127.0.0.1:${server.httpServer.address().port}/player-test`;
    const result = await new Promise((resolve, reject) => {
      const env = { ...process.env };
      delete env.ELECTRON_RUN_AS_NODE;
      child = spawn(require("electron"), [__filename, url], {
        env, windowsHide: true, stdio: ["ignore", "pipe", "pipe"],
      });
      let stdout = "";
      let stderr = "";
      child.stdout.on("data", data => { stdout += data; });
      child.stderr.on("data", data => { stderr += data; });
      child.on("error", reject);
      child.on("exit", code => {
        const match = stdout.match(/PLAYER_TEST_RESULT=(.+)/);
        if (code !== 0 || !match) reject(new Error((stderr || stdout || `Electron exit ${code}`) + "\n清单请求次数：" + JSON.stringify(requests)));
        else resolve(JSON.parse(match[1]));
      });
    });
    for (const scenario of result) {
      for (const [check, passed] of Object.entries(scenario)) {
        if (check !== "name") assert.equal(passed, true, `${scenario.name}: ${check}`);
      }
    }
  });
}
