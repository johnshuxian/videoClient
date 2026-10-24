const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { spawn } = require("node:child_process");

// 使用真实 React 页面验证确认操作；安装接口完全替换为本机测试桩，不启动安装器。
if (process.versions.electron) {
  const { app, BrowserWindow } = require("electron");
  app.commandLine.appendSwitch("no-proxy-server");
  app.whenReady().then(async () => {
    const window = new BrowserWindow({ show: false, width: 1200, height: 900,
      webPreferences: { backgroundThrottling: false } });
    window.webContents.on("console-message", (event) => process.stderr.write(event.message + "\n"));
    try {
      await window.loadURL(process.argv[2]);
      const result = await window.webContents.executeJavaScript(`(async () => {
        for (let i = 0; !window.updateUiTest && i < 1000; i++) await new Promise(resolve => setTimeout(resolve, 10));
        if (!window.updateUiTest) throw new Error("更新测试页面未加载");
        return window.updateUiTest();
      })()`);
      if (process.env.UPDATE_UI_CAPTURE_DIR) {
        const fs = require("node:fs/promises");
        await fs.mkdir(process.env.UPDATE_UI_CAPTURE_DIR, { recursive: true });
        // 隐藏窗口的首张抓图会唤醒合成器；等待绘制后再保存实际弹窗。
        await window.webContents.capturePage();
        await new Promise(resolve => setTimeout(resolve, 200));
        await fs.writeFile(path.join(process.env.UPDATE_UI_CAPTURE_DIR, "update-confirmation.png"), (await window.webContents.capturePage()).toPNG());
        await window.webContents.executeJavaScript('document.querySelector(".update-modal .load-more").click()');
        await new Promise(resolve => setTimeout(resolve, 200));
        await fs.writeFile(path.join(process.env.UPDATE_UI_CAPTURE_DIR, "update-settings.png"), (await window.webContents.capturePage()).toPNG());
        await window.webContents.executeJavaScript('window.previewUpdateResult()');
        await new Promise(resolve => setTimeout(resolve, 200));
        await fs.writeFile(path.join(process.env.UPDATE_UI_CAPTURE_DIR, "update-result.png"), (await window.webContents.capturePage()).toPNG());
      }
      process.stdout.write(`UPDATE_UI_RESULT=${JSON.stringify(result)}\n`);
      app.exit(0);
    } catch (error) { process.stderr.write(error.stack + "\n"); app.exit(1); }
  });
} else {
  test("点击版本号检查更新，线路持久化，取消不下载，同意后显示进度并安装", { timeout: 90000 }, async (t) => {
    const { createServer } = await import("vite");
    const server = await createServer({ configFile: false, root: path.join(__dirname, ".."),
      cacheDir: "node_modules/.vite-update-ui-test",
      optimizeDeps: { noDiscovery: true, include: ["react", "react/jsx-runtime", "react/jsx-dev-runtime", "react-dom/client", "lucide-react", "@capacitor/app"] },
      esbuild: { jsx: "automatic" },
      server: { host: "127.0.0.1", port: 0 },
      plugins: [{ name: "update-ui-test", configureServer(server) {
        server.middlewares.use(async (req, res, next) => {
          if (req.url !== "/update-test") return next();
          res.setHeader("Content-Type", "text/html");
          res.end(await server.transformIndexHtml("/update-test", `
            <div id="root"></div>
            <script type="module">
              import { createElement } from "react";
              import { createRoot } from "react-dom/client";
              import App from "/src/App.tsx";
              import "/src/style.css";
              const saved = new Map();
              const checked = [];
              let installs = 0, progress, finishInstall, mode = "update";
              const info = { version: "0.2.43", size: 1024 * 1024, notes: "修复播放问题" };
              window.videoAPI = {
                platform: "win32", readSetting: key => saved.get(key), saveSetting: (key, value) => saved.set(key, value),
                listHistory: () => [], setTheme() {}, onCastEnded: () => () => {}, catalog: async () => [],
                checkForUpdates: async route => {
                  checked.push(route);
                  if (mode === "error") throw new Error("测试网络错误");
                  return mode === "current" ? null : info;
                },
                onUpdateProgress: callback => { progress = callback; return () => {}; },
                downloadAndInstallUpdate: () => { installs++; return new Promise(resolve => { finishInstall = resolve; }); },
              };
              const root = createRoot(document.getElementById("root"));
              root.render(createElement(App));
              const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
              async function until(check, label) {
                for (let i = 0; i < 1000; i++) { if (check()) return; await wait(10); }
                throw new Error("等待超时：" + label);
              }
              const clickVersion = () => document.querySelector(".settings-version-button").click();
              const dialogButton = label => [...document.querySelectorAll(".update-modal button")].find(button => button.textContent === label);
              window.previewUpdateResult = () => { mode = "current"; clickVersion(); };
              window.updateUiTest = async () => {
                const results = {};
                await until(() => document.querySelector('[aria-label="播放设置"]'), "设置入口");
                document.querySelector('[aria-label="播放设置"]').click();
                await until(() => document.querySelector('[aria-label="更新线路"]'), "更新线路");
                const select = document.querySelector('[aria-label="更新线路"]');
                results.defaultProxy = select.value === "gh-proxy";
                const rows = [...document.querySelectorAll(".setting-row")];
                results.belowExactSearch = rows.findIndex(row => row.textContent.includes("线路选择")) === rows.findIndex(row => row.textContent.includes("精确搜索")) + 1;
                clickVersion();
                await until(() => dialogButton("取消"), "下载确认");
                results.proxyCheck = checked[0] === "gh-proxy";
                results.confirmation = document.querySelector(".update-modal").textContent.includes("0.2.43");
                dialogButton("取消").click();
                await until(() => !document.querySelector(".update-modal"), "取消弹窗");
                results.cancelNoDownload = installs === 0;
                select.value = "direct";
                select.dispatchEvent(new Event("change", { bubbles: true }));
                await until(() => saved.get("update-route") === "direct", "保存线路");
                clickVersion();
                await until(() => dialogButton("同意"), "再次下载确认");
                results.directCheck = checked.at(-1) === "direct";
                dialogButton("同意").click();
                await until(() => installs === 1, "触发安装接口");
                progress(75);
                await until(() => document.querySelector('[role="status"]')?.textContent.includes("75%"), "下载进度");
                results.downloadLocksControls = select.disabled && document.querySelector(".settings-version-button").disabled;
                finishInstall({ status: "started" });
                await until(() => document.querySelector('[role="status"]')?.textContent.includes("安装程序已打开"), "安装提示");
                results.agreeInstallsOnce = installs === 1;
                mode = "current";
                clickVersion();
                await until(() => document.querySelector('[role="status"]')?.textContent.includes("当前已是最新版本"), "最新版本提示");
                results.noUpdateNoDialog = !document.querySelector(".update-modal");
                results.latestResultIsPopup = !!document.querySelector(".update-result-backdrop .update-result-popup");
                results.noInlineResult = !document.querySelector(".settings-update-status");
                await wait(2200);
                results.resultDismissesAfterTwoSeconds = !document.querySelector(".update-result-popup");
                mode = "error";
                clickVersion();
                await until(() => document.querySelector('[role="status"]')?.textContent.includes("测试网络错误"), "网络错误提示");
                results.errorResultIsPopup = !!document.querySelector(".update-result-popup.is-error");
                results.failureUnlocksControls = !select.disabled && !document.querySelector(".settings-version-button").disabled;
                mode = "update";
                clickVersion();
                await until(() => dialogButton("取消"), "确认弹窗预览");
                return results;
              };
            </script>`));
        });
      } }],
    });
    let child;
    t.after(async () => { if (child?.exitCode === null) child.kill(); await server.close(); });
    await server.listen();
    const url = `http://127.0.0.1:${server.httpServer.address().port}/update-test`;
    const result = await new Promise((resolve, reject) => {
      const env = { ...process.env };
      delete env.ELECTRON_RUN_AS_NODE;
      child = spawn(require("electron"), [__filename, url], { env, windowsHide: true, stdio: ["ignore", "pipe", "pipe"] });
      let stdout = "", stderr = "";
      child.stdout.on("data", data => { stdout += data; });
      child.stderr.on("data", data => { stderr += data; });
      child.on("error", reject);
      child.on("exit", code => {
        const match = stdout.match(/UPDATE_UI_RESULT=(.+)/);
        if (code !== 0 || !match) reject(new Error(stderr || stdout || `Electron exit ${code}`));
        else resolve(JSON.parse(match[1]));
      });
    });
    for (const [check, passed] of Object.entries(result)) assert.equal(passed, true, check);
  });
}
