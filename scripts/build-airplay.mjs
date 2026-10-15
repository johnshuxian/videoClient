import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync } from "node:fs";
import { join, resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const venv = join(root, ".venv-airplay");
const python = join(
  venv,
  process.platform === "win32" ? "Scripts" : "bin",
  process.platform === "win32" ? "python.exe" : "python",
);

function run(program, args) {
  const result = spawnSync(program, args, { cwd: root, stdio: "inherit" });
  if (result.status !== 0) process.exit(result.status || 1);
}

if (!existsSync(python)) {
  run(process.platform === "win32" ? "python" : "python3", [
    "-m",
    "venv",
    venv,
  ]);
}
run(python, ["-m", "pip", "install", "-r", "scripts/airplay-requirements.txt"]);
// Upstream 0.18.0 still uses the older URL command on tvOS 26. Pin the
// tested AirPlay 2 queue fix until it is released upstream.
const installed = spawnSync(
  python,
  [
    "-c",
    "import importlib.metadata as m; print(m.distribution('pyatv').read_text('direct_url.json') or '')",
  ],
  { cwd: root, encoding: "utf8" },
);
if (!installed.stdout.includes("8144c77c")) {
  run(python, [
    "-m",
    "pip",
    "install",
    "--force-reinstall",
    "--no-deps",
    "git+https://github.com/robkochman/pyatv.git@8144c77c",
  ]);
}
mkdirSync(join(root, "build", "airplay"), { recursive: true });
mkdirSync(join(root, "build", "pyinstaller"), { recursive: true });
run(python, [
  "-m",
  "PyInstaller",
  "--noconfirm",
  "--onefile",
  "--collect-all",
  "pyatv",
  "--name",
  "airplay-bridge",
  "--distpath",
  join(root, "build", "airplay"),
  "--workpath",
  join(root, "build", "pyinstaller"),
  "--specpath",
  join(root, "build", "pyinstaller"),
  join(root, "electron", "airplay.py"),
]);
