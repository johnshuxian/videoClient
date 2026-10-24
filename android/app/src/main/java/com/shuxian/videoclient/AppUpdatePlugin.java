package com.shuxian.videoclient;

import android.widget.Toast;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.shuxian.videoclient.update.AppUpdater;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "AppUpdate")
public class AppUpdatePlugin extends Plugin {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AppUpdater updater;

    @Override public void load() { updater = new AppUpdater(getContext(), false); }

    @PluginMethod public void check(PluginCall call) {
        String route = call.getString("route", "gh-proxy");
        executor.execute(() -> {
            try {
                AppUpdater.UpdateInfo update = updater.check(route);
                JSObject result = new JSObject();
                result.put("update", update == null ? org.json.JSONObject.NULL : update.toJson());
                call.resolve(result);
            } catch (Exception error) { call.reject("检查更新失败：" + error.getMessage(), error); }
        });
    }

    @PluginMethod public void downloadAndInstall(PluginCall call) {
        executor.execute(() -> {
            try {
                updater.download(percent -> notifyListeners("progress", new JSObject().put("percent", percent)));
                getActivity().runOnUiThread(() -> {
                    try {
                        boolean started = updater.install(getActivity());
                        call.resolve(new JSObject().put("status", started ? "started" : "permission-required"));
                    } catch (Exception error) { call.reject("打开安装程序失败：" + error.getMessage(), error); }
                });
            } catch (Exception error) { call.reject("下载更新失败：" + error.getMessage(), error); }
        });
    }

    @Override protected void handleOnResume() {
        if (updater == null) return;
        try { updater.resumeInstallation(getActivity()); }
        catch (Exception error) { Toast.makeText(getContext(), "打开安装程序失败：" + error.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    @Override protected void handleOnDestroy() { executor.shutdownNow(); }
}
