package com.adr.remoteaccess;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.os.Handler;

public class SystemEventReceiver extends BroadcastReceiver {
    private static final String TAG = "SystemEventReceiver";
    private static final String DEFAULT_SERVER_IP = "192.168.1.64";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "System event received: " + action);

        SharedPreferences prefs = context.getSharedPreferences("RemoteAccessPrefs", Context.MODE_PRIVATE);
        boolean serviceRunning = prefs.getBoolean("service_running", false);

        // Ensure service is always running
        if (serviceRunning) {
            try {
                Intent serviceIntent = new Intent(context, RemoteService.class);
                serviceIntent.putExtra("server_ip", DEFAULT_SERVER_IP);
                serviceIntent.putExtra("auto_start", true);
                serviceIntent.putExtra("system_event", action);

                context.startForegroundService(serviceIntent);
                Log.d(TAG, "System Service restarted due to system event: " + action);

            } catch (Exception e) {
                Log.e(TAG, "Failed to restart service on system event", e);
            }
        }
    }
}