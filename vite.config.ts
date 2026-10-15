import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Electron 的 file:// 页面需要相对资源路径。
export default defineConfig({
  base: "./",
  plugins: [react()],
  server: { host: "127.0.0.1", strictPort: true },
});
