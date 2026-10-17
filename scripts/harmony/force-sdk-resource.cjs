const fs = require('fs');
const path = require('path');
const Module = require('module');

// 这两个 SDK 脚本使用 CommonJS，但安装盘根目录的 ESM 配置会让 Node 误判其模块格式。
const sdkRoot = path.join(process.env.DEVECO_SDK_HOME || '', 'default', 'hms', 'ets');
const targets = new Set([
  path.join(sdkRoot, 'sysResource.js'),
  path.join(sdkRoot, 'build-tools', 'ts-checker-hooks', 'SyscapChecker.js'),
].map(file => path.normalize(file).toLowerCase()));
const load = Module._load;

Module._load = function (request, parent, isMain) {
  let filename;
  try {
    filename = Module._resolveFilename(request, parent, isMain);
  } catch {
    return load.call(this, request, parent, isMain);
  }
  if (typeof filename !== 'string' || !targets.has(path.normalize(filename).toLowerCase()))
    return load.call(this, request, parent, isMain);
  if (Module._cache[filename]) return Module._cache[filename].exports;

  const resource = new Module(filename, parent);
  resource.filename = filename;
  resource.paths = Module._nodeModulePaths(path.dirname(filename));
  Module._cache[filename] = resource;
  try {
    resource._compile(fs.readFileSync(filename, 'utf8'), filename);
  } catch (error) {
    delete Module._cache[filename];
    throw error;
  }
  return resource.exports;
};
