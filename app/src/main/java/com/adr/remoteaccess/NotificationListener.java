package com.adr.remoteaccess;

import android.app.Notification;
import android.content.Intent;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.os.Bundle;
import android.content.ComponentName;
import java.util.Set;

public class NotificationListener extends NotificationListenerService {
    private static final String TAG = "NotificationListener";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String packageName = sbn.getPackageName();

            // Skip system notifications but include important ones
            if (packageName.equals("android") ||
                    packageName.equals("com.android.systemui") ||
                    packageName.equals(getPackageName())) {
                return;
            }

            Notification notification = sbn.getNotification();
            String title = "";
            String text = "";
            String subText = "";
            String bigText = "";

            if (notification.extras != null) {
                Bundle extras = notification.extras;

                // Extract all possible text content
                CharSequence titleSeq = extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence textSeq = extras.getCharSequence(Notification.EXTRA_TEXT);
                CharSequence subTextSeq = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
                CharSequence bigTextSeq = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

                if (titleSeq != null) title = titleSeq.toString();
                if (textSeq != null) text = textSeq.toString();
                if (subTextSeq != null) subText = subTextSeq.toString();
                if (bigTextSeq != null) bigText = bigTextSeq.toString();

                // Handle text lines for expanded notifications
                CharSequence[] textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                StringBuilder allLines = new StringBuilder();
                if (textLines != null) {
                    for (CharSequence line : textLines) {
                        if (line != null) {
                            allLines.append(line.toString()).append("\n");
                        }
                    }
                }
            }

            // Get notification category and priority
            String category = "";
            int priority = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                category = notification.category != null ? notification.category : "";
                priority = notification.priority;
            }

            // Send broadcast to RemoteService
            Intent intent = new Intent("NOTIFICATION_CAPTURED");
            intent.putExtra("package", packageName);
            intent.putExtra("title", title);
            intent.putExtra("text", text);
            intent.putExtra("sub_text", subText);
            intent.putExtra("big_text", bigText);
            intent.putExtra("category", category);
            intent.putExtra("priority", priority);
            intent.putExtra("timestamp", System.currentTimeMillis());
            intent.putExtra("id", sbn.getId());
            intent.putExtra("tag", sbn.getTag());
            intent.putExtra("group_key", sbn.getGroupKey());
            intent.putExtra("is_ongoing", notification.flags != 0);

            sendBroadcast(intent);

            Log.d(TAG, "Enhanced notification captured: " + packageName + " - " + title);

        } catch (Exception e) {
            Log.e(TAG, "Error processing notification", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        try {
            // Send notification removal event
            Intent intent = new Intent("NOTIFICATION_REMOVED");
            intent.putExtra("package", sbn.getPackageName());
            intent.putExtra("id", sbn.getId());
            intent.putExtra("timestamp", System.currentTimeMillis());
            sendBroadcast(intent);

            Log.d(TAG, "Notification removed: " + sbn.getPackageName());
        } catch (Exception e) {
            Log.e(TAG, "Error processing notification removal", e);
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "Enhanced notification listener connected");

        // Get all active notifications when listener connects
        try {
            StatusBarNotification[] activeNotifications = getActiveNotifications();
            if (activeNotifications != null) {
                for (StatusBarNotification sbn : activeNotifications) {
                    onNotificationPosted(sbn);
                }
                Log.d(TAG, "Processed " + activeNotifications.length + " active notifications");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing active notifications", e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.d(TAG, "Enhanced notification listener disconnected");

        // Request rebind for automatic reconnection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(new ComponentName(this, NotificationListener.class));
        }
    }
}