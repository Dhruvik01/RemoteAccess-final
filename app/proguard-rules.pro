# Add project specific ProGuard rules here.

# Keep WebSocket classes
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# Keep JSON classes
-keep class org.json.** { *; }
-dontwarn org.json.**

# Keep our main classes
-keep class com.adr.remoteaccess.** { *; }

# Keep notification listener service
-keep class com.adr.remoteaccess.NotificationListener { *; }

# Keep boot receiver
-keep class com.adr.remoteaccess.BootReceiver { *; }

# Keep service classes
-keep class com.adr.remoteaccess.RemoteService { *; }

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator CREATOR;
}

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep serialization
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Media projection and camera2 API
-keep class android.media.projection.** { *; }
-keep class android.hardware.camera2.** { *; }