import React from "react";
import { createRoot } from "react-dom/client";
import { Capacitor } from "@capacitor/core";
import App from "./App";
import "./style.css";

// Android 先从 SQLite 加载设置，再挂载页面，保证首屏选中的点播源正确。
async function start() {
  if (Capacitor.getPlatform() === "android") {
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
  document.getElementById("root")!.textContent = `影窗启动失败：${String(error)}`;
});
