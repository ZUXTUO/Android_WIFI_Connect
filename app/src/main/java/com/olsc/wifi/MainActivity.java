package com.olsc.wifi;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;
import com.olsc.wifi.lib.WifiConnectManager;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        WifiConnectManager.startWifiCheck(this, new WifiConnectManager.WifiConnectionListener() {
            @Override
            public void onWifiConnected() {
                Toast.makeText(MainActivity.this, "Main App: WIFI is connected! Proceeding...", Toast.LENGTH_LONG).show();
            }
        });
        
    }
}
