import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const project = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const source = path.join(project, "dist");
const target = path.join(
  project,
  "harmony",
  "entry",
  "src",
  "main",
  "resources",
  "rawfile",
  "www",
);
if (!fs.existsSync(path.join(source, "index.html"))) {
  throw new Error("请先运行 npm run build:web");
}
// 只重建固定的 rawfile/www，防止旧 hash 资源继续占用 HAP 空间。
if (path.basename(target) !== "www" || !target.startsWith(project + path.sep)) {
  throw new Error("鸿蒙资源目录不在项目内");
}
fs.rmSync(target, { recursive: true, force: true });
fs.cpSync(source, target, { recursive: true });
const htmlPath = path.join(target, "index.html");
let html = fs.readFileSync(htmlPath, "utf8");
if (!html.includes("<head>"))
  throw new Error("构建后的 index.html 缺少 head 标签");
// ArkWeb 对 resource:// 页面加载独立本地 JS/CSS 有跨源限制；鸿蒙包内联单 bundle 与样式。
const scriptTag = html.match(
  /<script type="module"[^>]*src="([^"]+)"[^>]*><\/script>/,
);
if (!scriptTag) throw new Error("鸿蒙网页构建缺少入口脚本");
const scriptPath = path.resolve(target, scriptTag[1]);
const script = fs.readFileSync(scriptPath, "utf8");
html = html.replace(
  scriptTag[0],
  () => `<script type="module">${script}</script>`,
);

const styleTag = html.match(/<link[^>]*rel="stylesheet"[^>]*href="([^"]+)"[^>]*>/);
if (!styleTag) throw new Error("鸿蒙网页构建缺少样式表");
const stylePath = path.resolve(target, styleTag[1]);
// 鸿蒙设备用系统中文字体，避免加载 rawfile 内的字体文件触发同类跨源限制。
const styles = fs
  .readFileSync(stylePath, "utf8")
  .replace(/@font-face\s*\{[^}]*\}\s*/gs, "");
html = html.replace(styleTag[0], () => `<style>${styles}</style>`);
fs.rmSync(path.join(target, "assets"), { recursive: true, force: true });
// 标志仅注入 HAP 内的页面，桌面和安卓共用的构建结果不受影响。
fs.writeFileSync(
  htmlPath,
  html.replace(
    "<head>",
    '<head><script>document.documentElement.dataset.harmony="true";</script>',
  ),
);

const version = JSON.parse(
  fs.readFileSync(path.join(project, "package.json"), "utf8"),
).version;
const parts = version.split(".").map(Number);
if (
  parts.length !== 3 ||
  parts.some((part) => !Number.isInteger(part) || part < 0 || part > 999)
) {
  throw new Error("鸿蒙版需要 x.y.z 格式的版本号，且每段不超过 999");
}
const appPath = path.join(project, "harmony", "AppScope", "app.json5");
const app = JSON.parse(fs.readFileSync(appPath, "utf8"));
app.app.versionCode = parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2];
app.app.versionName = version;
fs.writeFileSync(appPath, JSON.stringify(app, null, 2) + "\n");
console.log(`鸿蒙网页资源已同步：${target}`);
