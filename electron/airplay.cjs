const { spawn } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const readline = require("node:readline");
const { app } = require("electron");

let helper = null;
let serial = 0;
const pending = new Map();

function requestAirPlay(action, data = {}) {
  if (!helper) {
    const executable = path.join(
      app.isPackaged
        ? process.resourcesPath
        : path.join(__dirname, "..", "build", "airplay"),
      process.platform === "win32" ? "airplay-bridge.exe" : "airplay-bridge",
    );
    if (!fs.existsSync(executable))
      throw new Error(
        "缺少 AirPlay 助手；开发环境请先运行 npm run build:airplay",
      );
    helper = spawn(executable, [], {
      windowsHide: true,
      stdio: ["pipe", "pipe", "pipe"],
    });
    readline.createInterface({ input: helper.stdout }).on("line", (line) => {
      let message;
      try {
        message = JSON.parse(line);
      } catch {
        return;
      }
      const waiting = pending.get(message.id);
      if (!waiting) return;
      clearTimeout(waiting.timer);
      pending.delete(message.id);
      if (message.error) waiting.reject(new Error(message.error));
      else waiting.resolve(message.result);
    });
    helper.on("exit", () => {
      helper = null;
      for (const waiting of pending.values()) {
        clearTimeout(waiting.timer);
        waiting.reject(new Error("AirPlay 助手已退出"));
      }
      pending.clear();
    });
    helper.on("error", () => helper?.kill());
  }

  const id = ++serial;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(
      () => {
        pending.delete(id);
        reject(new Error("AirPlay 设备响应超时"));
      },
      action === "scan" ? 12000 : 20000,
    );
    pending.set(id, { resolve, reject, timer });
    helper.stdin.write(`${JSON.stringify({ id, action, ...data })}\n`);
  });
}

function closeAirPlay() {
  helper?.kill();
}

module.exports = { requestAirPlay, closeAirPlay };
