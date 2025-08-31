package com.adr.remoteaccess;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.os.Handler;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    private static final String DEFAULT_SERVER_IP = "192.168.1.64"; // Change to your Flask server IP

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
                Intent.ACTION_PACKAGE_REPLACED.equals(action)) {

            Log.d(TAG, "System boot completed or package updated - Starting System Service");

            try {
                // Always start the service after boot - this is a system service
                Intent serviceIntent = new Intent(context, RemoteService.class);
                serviceIntent.putExtra("server_ip", DEFAULT_SERVER_IP);
                serviceIntent.putExtra("auto_start", true);
                serviceIntent.putExtra("boot_start", true);

                // Start immediately
                context.startForegroundService(serviceIntent);
                Log.d(TAG, "System Service auto-started successfully");

                // Also start the main activity to show status (will close automatically after setup)
                Intent activityIntent = new Intent(context, MainActivity.class);
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

                // Delay activity start by 3 seconds to let service initialize
                new Handler().postDelayed(() -> {
                    try {
                        context.startActivity(activityIntent);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to start MainActivity", e);
                    }
                }, 3000);

                // Mark service as auto-started
                SharedPreferences prefs = context.getSharedPreferences("RemoteAccessPrefs", Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean("service_running", true);
                editor.putString("last_server_ip", DEFAULT_SERVER_IP);
                editor.putBoolean("auto_connect", true);
                editor.putLong("boot_start_time", System.currentTimeMillis());
                editor.apply();

            } catch (Exception e) {
                Log.e(TAG, "Failed to auto-start System Service", e);

                // Retry after 10 seconds if failed
                new Handler().postDelayed(() -> {
                    try {
                        Intent retryServiceIntent = new Intent(context, RemoteService.class);
                        retryServiceIntent.putExtra("server_ip", DEFAULT_SERVER_IP);
                        retryServiceIntent.putExtra("auto_start", true);
                        retryServiceIntent.putExtra("boot_start", true);
                        context.startForegroundService(retryServiceIntent);
                        Log.d(TAG, "System Service retry start successful");
                    } catch (Exception retryException) {
                        Log.e(TAG, "System Service retry failed", retryException);
                    }
                }, 10000);
            }
        }
    }
}