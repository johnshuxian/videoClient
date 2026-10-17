package com.shuxian.videoclient;

import android.content.Context;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VideoProxyPlugin.class);
        registerPlugin(DlnaPlugin.class);
        registerPlugin(StatusBarPlugin.class);
        super.onCreate(savedInstanceState);
        // 整个应用共用这个 WebView，关闭其原生手势缩放。
        getBridge().getWebView().getSettings().setSupportZoom(false);
        getBridge().getWebView().getSettings().setBuiltInZoomControls(false);
        // WebView 加载前先恢复上次应用配色，避免状态栏闪过另一种颜色。
        boolean light = !getPreferences(Context.MODE_PRIVATE).getBoolean("dark", true);
        getWindow().getDecorView().post(() -> StatusBarPlugin.applyColor(this, light, false));
    }
}
