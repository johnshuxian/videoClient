package com.shuxian.videoclient;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VideoProxyPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
