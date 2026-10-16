package com.shuxian.videoclient;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.view.Window;
import androidx.core.view.WindowCompat;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "AppStatusBar")
public class StatusBarPlugin extends Plugin {
    // 只调整当前应用窗口的状态栏，不修改手机的系统主题。
    private boolean light = false;
    private boolean fullscreen = false;

    @Override
    public void load() {
        super.load();
        light = !getActivity().getPreferences(Context.MODE_PRIVATE).getBoolean("dark", true);
    }

    @PluginMethod
    public void setTheme(PluginCall call) {
        boolean isLight = !"dark".equals(call.getString("theme"));
        light = isLight;
        getActivity().runOnUiThread(() -> {
            getActivity().getPreferences(Context.MODE_PRIVATE).edit().putBoolean("dark", !isLight).apply();
            applyColor(getActivity(), isLight, fullscreen);
            call.resolve();
        });
    }

    @PluginMethod
    public void setFullscreen(PluginCall call) {
        fullscreen = call.getBoolean("fullscreen", false);
        getActivity().runOnUiThread(() -> {
            applyColor(getActivity(), light, fullscreen);
            call.resolve();
        });
    }

    @Override
    protected void handleOnConfigurationChanged(Configuration newConfig) {
        super.handleOnConfigurationChanged(newConfig);
        // 旋转屏幕后 Capacitor 会重设系统栏，恢复应用自己选定的颜色。
        getActivity().getWindow().getDecorView().post(() -> applyColor(getActivity(), light, fullscreen));
    }

    static void applyColor(Activity activity, boolean light, boolean fullscreen) {
        Window window = activity.getWindow();
        // 播放全屏始终使用纯黑状态栏，退出后恢复用户选择的主题。
        int color = fullscreen ? Color.BLACK : light ? Color.rgb(237, 243, 247) : Color.rgb(11, 16, 26);
        // Android 15 的状态栏透明，底色来自应用窗口。
        window.getDecorView().setBackgroundColor(color);
        if (Build.VERSION.SDK_INT < 35) window.setStatusBarColor(color);
        WindowCompat.getInsetsController(window, window.getDecorView()).setAppearanceLightStatusBars(light && !fullscreen);
    }
}
