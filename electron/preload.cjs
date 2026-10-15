const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("videoAPI", {
  platform: process.platform,
  readSetting: (key) => ipcRenderer.sendSync("settings:read", key),
  saveSetting: (key, value) => {
    const error = ipcRenderer.sendSync("settings:save", key, value);
    if (error) throw new Error(error);
  },
  catalog: (type, tag, page) => ipcRenderer.invoke("catalog", type, tag, page),
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
  airplayPairBegin: (id) => ipcRenderer.invoke("airplay:pair-begin", id),
  airplayPairFinish: (pin) => ipcRenderer.invoke("airplay:pair-finish", pin),
  cast: (device, url, title, filterAds) =>
    ipcRenderer.invoke("cast", device, url, title, filterAds),
  castStop: () => ipcRenderer.invoke("cast:stop"),
  setTheme: (theme) => ipcRenderer.send("theme:set", theme),
  minimize: () => ipcRenderer.send("window:minimize"),
  maximize: () => ipcRenderer.send("window:maximize"),
  close: () => ipcRenderer.send("window:close"),
});
