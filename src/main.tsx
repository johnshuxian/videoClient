import React from "react";
import { createRoot } from "react-dom/client";
import { Capacitor } from "@capacitor/core";
import App from "./App";
import "./style.css";

// 移动端先加载本机设置，再挂载页面，保证首屏选中的点播源正确。
async function start() {
  if (document.documentElement.dataset.harmony === "true") {
    document.body.classList.add("android-body");
    // Artplayer 只通过 Android/iOS 等 UA 标记启用手机触控控件；ArkWeb 默认 UA 不包含这些标记。
    (globalThis as typeof globalThis & { CUSTOM_USER_AGENT?: string }).CUSTOM_USER_AGENT =
      `${navigator.userAgent} Android`;
    document.getElementById("root")!.dataset.startupStep = "初始化鸿蒙数据";
    const { createHarmonyVideoAPI } = await import("./harmony-api");
    window.videoAPI = await createHarmonyVideoAPI();
  } else if (Capacitor.getPlatform() === "android") {
    document.body.classList.add("android-body");
    document.getElementById("root")!.dataset.startupStep = "初始化安卓数据";
    const { createAndroidVideoAPI } = await import("./android-api");
    window.videoAPI = await createAndroidVideoAPI();
  }
  document.getElementById("root")!.dataset.startupStep = "加载首页";
  createRoot(document.getElementById("root")!).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>,
  );
}

start().catch((error) => {
  document.getElementById("root")!.textContent =
    `影窗启动失败：${String(error)}`;
});
