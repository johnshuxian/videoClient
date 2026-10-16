const { spawn } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const readline = require("node:readline");
const { app } = require("electron");

let helper = null;
let serial = 0;
const pending = new Map();
let eventHandler = () => {};

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
    const child = helper;
    child.stderr.on("data", (output) =>
      console.warn(`AirPlay 助手：${String(output).trim()}`),
    );
    child.stdin.on("error", (error) =>
      console.warn("AirPlay 助手输入失败：", error),
    );
    readline.createInterface({ input: helper.stdout }).on("line", (line) => {
      let message;
      try {
        message = JSON.parse(line);
      } catch {
        return;
      }
      if (message.event) {
        eventHandler(message);
        return;
      }
      const waiting = pending.get(message.requestId);
      if (!waiting) return;
      clearTimeout(waiting.timer);
      pending.delete(message.requestId);
      if (message.error) waiting.reject(new Error(message.error));
      else waiting.resolve(message.result);
    });
    const failPending = (error) => {
      if (helper === child) helper = null;
      for (const waiting of pending.values()) {
        clearTimeout(waiting.timer);
        waiting.reject(error);
      }
      pending.clear();
    };
    child.on("exit", () => failPending(new Error("AirPlay 助手已退出")));
    child.on("error", (error) =>
      failPending(new Error(`AirPlay 助手无法启动：${error.message}`)),
    );
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
    // 请求编号与 Apple TV 的设备 ID 必须分开，否则配对回包无法匹配等待中的请求。
    helper.stdin.write(
      `${JSON.stringify({ requestId: id, action, ...data })}\n`,
    );
  });
}

function closeAirPlay() {
  helper?.kill();
}

function setAirPlayEventHandler(handler) {
  eventHandler = handler;
}

module.exports = { requestAirPlay, closeAirPlay, setAirPlayEventHandler };
