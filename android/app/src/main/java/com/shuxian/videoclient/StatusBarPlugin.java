package com.shuxian.videoclient;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.provider.Settings;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "AppStatusBar")
public class StatusBarPlugin extends Plugin {
    // 只调整当前应用窗口的状态栏，不修改手机的系统主题。
    private boolean light = false;
    private boolean fullscreen = false;
    private int previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    @Override
    public void load() {
        super.load();
        light = !getActivity().getPreferences(Context.MODE_PRIVATE).getBoolean("dark", true);
    }

    @PluginMethod
    public void deviceInfo(PluginCall call) {
        JSObject result = new JSObject();
        result.put("isTv", isTelevision(getActivity()));
        call.resolve(result);
    }

    private static boolean isTelevision(Context context) {
        int type = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
        return type == Configuration.UI_MODE_TYPE_TELEVISION
            || context.getPackageManager().hasSystemFeature("android.software.leanback");
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
        boolean enterFullscreen = call.getBoolean("fullscreen", false);
        getActivity().runOnUiThread(() -> {
            Activity activity = getActivity();
            if (enterFullscreen && !fullscreen) {
                previousOrientation = activity.getRequestedOrientation();
                fullscreen = true;
                // 锁住页面方向，只由网页中的播放器横向铺满屏幕。
                applyColor(activity, light, true);
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
                call.resolve();
                return;
            }
            if (!enterFullscreen && fullscreen) {
                activity.setRequestedOrientation(previousOrientation);
            }
            fullscreen = enterFullscreen;
            if (!enterFullscreen) {
                WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
                attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                activity.getWindow().setAttributes(attributes);
            }
            applyColor(getActivity(), light, fullscreen);
            call.resolve();
        });
    }

    @PluginMethod
    public void getBrightness(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            float current = getActivity().getWindow().getAttributes().screenBrightness;
            if (current < 0) {
                int system = Settings.System.getInt(getActivity().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 128);
                current = system / 255f;
            }
            JSObject result = new JSObject();
            result.put("brightness", current);
            call.resolve(result);
        });
    }

    @PluginMethod
    public void setBrightness(PluginCall call) {
        float brightness = Math.max(0.05f, Math.min(1f, call.getFloat("brightness", 0.5f)));
        getActivity().runOnUiThread(() -> {
            // 只调整当前播放窗口，不修改手机的系统亮度设置。
            WindowManager.LayoutParams attributes = getActivity().getWindow().getAttributes();
            attributes.screenBrightness = brightness;
            getActivity().getWindow().setAttributes(attributes);
            call.resolve();
        });
    }

    @PluginMethod
    public void resetBrightness(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            WindowManager.LayoutParams attributes = getActivity().getWindow().getAttributes();
            attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getActivity().getWindow().setAttributes(attributes);
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
        boolean isTv = isTelevision(activity);
        // 播放全屏隐藏系统栏；退出后恢复系统栏及用户选择的主题。
        int color = fullscreen ? Color.BLACK : light ? Color.rgb(237, 243, 247) : Color.rgb(11, 16, 26);
        // Android 15 的状态栏透明，底色来自应用窗口。
        window.getDecorView().setBackgroundColor(color);
        if (Build.VERSION.SDK_INT < 35) window.setStatusBarColor(color);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(light && !fullscreen);
        if (fullscreen || isTv) {
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
    }
}
