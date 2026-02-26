package com.olsc.wifi.lib;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class WifiConnectManager {
    
    private static final String TAG = "WifiConnectLib";

    public interface WifiConnectionListener {
        void onWifiConnected();
    }

    private static WifiConnectionListener sListener;

    /**
     * 开始 WIFI 检查流程。
     * 启动 WifiMainActivity 处理权限、连接检查以及必要时启动热点。
     */
    public static void startWifiCheck(Context context, WifiConnectionListener listener) {
        sListener = listener;
        Intent intent = new Intent(context, WifiMainActivity.class);
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    /**
     * Unity 友好版本，连接成功后向 Unity 发送消息。
     * @param context 上下文（通常为 UnityPlayer.currentActivity）
     * @param unityObjectName 接收消息的 Unity GameObject 名称
     * @param unityMethodName 调用 Unity 的方法名称
     */
    public static void startWifiCheckUnity(Context context, final String unityObjectName, final String unityMethodName) {
        Log.d(TAG, "Starting Wifi Check for Unity. Target: " + unityObjectName + "." + unityMethodName);
        startWifiCheck(context, new WifiConnectionListener() {
            @Override
            public void onWifiConnected() {
                Log.d(TAG, "WIFI Connected, notifying Unity...");
                try {
                    // 使用反射以避免编译时对 Unity 库的依赖
                    Class<?> unityPlayerClass = Class.forName("com.unity3d.player.UnityPlayer");
                    java.lang.reflect.Method sendMessageMethod = unityPlayerClass.getMethod("UnitySendMessage", String.class, String.class, String.class);
                    sendMessageMethod.invoke(null, unityObjectName, unityMethodName, "success");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to send message to Unity. Is UnityPlayer available?", e);
                }
            }
        });
    }

    /**
     * 内部方法，由 WifiMainActivity 在确认连接后调用。
     */
    static void notifySuccess() {
        if (sListener != null) {
            sListener.onWifiConnected();
            sListener = null;
        }
    }
}
