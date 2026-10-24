const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("videoAPI", {
  platform: process.platform,
  checkForUpdates: (route) => ipcRenderer.invoke("update:check", route),
  downloadAndInstallUpdate: () => ipcRenderer.invoke("update:install"),
  onUpdateProgress: (callback) => {
    const listener = (_event, percent) => callback(percent);
    ipcRenderer.on("update:progress", listener);
    return () => ipcRenderer.removeListener("update:progress", listener);
  },
  readSetting: (key) => ipcRenderer.sendSync("settings:read", key),
  saveSetting: (key, value) => {
    const error = ipcRenderer.sendSync("settings:save", key, value);
    if (error) throw new Error(error);
  },
  listHistory: () => ipcRenderer.sendSync("history:read"),
  latestOtherSourceHistory: (title, sourceUrl) =>
    ipcRenderer.invoke("history:latest-other-source", title, sourceUrl),
  upsertHistory: (item) => {
    const error = ipcRenderer.sendSync("history:upsert", item);
    if (error) throw new Error(error);
  },
  deleteHistory: (key) => {
    const error = ipcRenderer.sendSync("history:delete", key);
    if (error) throw new Error(error);
  },
  clearHistory: () => {
    const error = ipcRenderer.sendSync("history:clear");
    if (error) throw new Error(error);
  },
  catalog: (type, tag, page, sort) =>
    ipcRenderer.invoke("catalog", type, tag, page, sort),
  sourceCategories: (sourceUrl) =>
    ipcRenderer.invoke("source:categories", sourceUrl),
  sourceCatalog: (source, categoryId, page) =>
    ipcRenderer.invoke("source:catalog", source, categoryId, page),
  importSources: (url) => ipcRenderer.invoke("sources:import", url),
  testSource: (source, runId) =>
    ipcRenderer.invoke("source:test", source, runId),
  cancelSourceTest: (runId) => ipcRenderer.send("source:test:cancel", runId),
  imageUrl: (url) => ipcRenderer.invoke("image-url", url),
  search: (query, sources, requestId, onProgress) => {
    const listener = (_, progress) => {
      if (progress.requestId === requestId)
        onProgress(
          progress.completed,
          progress.total,
          progress.items,
          progress.failedSource,
        );
    };
    ipcRenderer.on("search:progress", listener);
    return ipcRenderer
      .invoke("search", query, sources, requestId)
      .finally(() => ipcRenderer.removeListener("search:progress", listener));
  },
  detail: (sourceUrl, id) => ipcRenderer.invoke("detail", sourceUrl, id),
  streamUrl: (url, filterAds) =>
    ipcRenderer.invoke("stream-url", url, filterAds),
  devices: () => ipcRenderer.invoke("devices"),
  cast: (device, url, title, startPosition) =>
    ipcRenderer.invoke("cast", device, url, title, startPosition),
  castStop: () => ipcRenderer.invoke("cast:stop"),
  castProgress: () => ipcRenderer.invoke("cast:progress"),
  onCastEnded: (callback) => {
    const listener = (_, event) => callback(event);
    ipcRenderer.on("cast:ended", listener);
    return () => ipcRenderer.removeListener("cast:ended", listener);
  },
  setTheme: (theme) => ipcRenderer.send("theme:set", theme),
  setVideoFullscreen: () => {},
  minimize: () => ipcRenderer.send("window:minimize"),
  maximize: () => ipcRenderer.send("window:maximize"),
  close: () => ipcRenderer.send("window:close"),
});
