import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Electron 的 file:// 页面需要相对资源路径。
export default defineConfig({
  base: "./",
  plugins: [react()],
  // 部分电视的系统 WebView 更新较慢，避免产物包含较新的 JavaScript 语法。
  build: { target: "es2018" },
  server: { host: "127.0.0.1", strictPort: true },
});
