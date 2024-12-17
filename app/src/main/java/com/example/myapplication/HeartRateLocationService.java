package com.example.myapplication;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

public class HeartRateLocationService extends Service {

    private SensorManager sensorManager;
    private Sensor heartRateSensor;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private String smartwatch = "ocid";

    private MqttAndroidClient mqttClient;
    private static final String MQTT_SERVER_URI = "tcp://206.189.40.4:1883"; // Ganti dengan IP server Anda
    private static final String MQTT_TOPIC = "health/heart_rate_location";

    private float currentHeartRate = 0;
    private double currentLatitude = 0.0, currentLongitude = 0.0;

    private final Handler handler = new Handler();
    private long lastPublishTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("HeartRateService", "Service started");

        // Initialize MQTT Client
        initializeMqttClient();

        // Initialize SensorManager and Heart Rate Sensor
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        }

        // Initialize FusedLocationProviderClient
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Start Heart Rate and Location Monitoring
        startHeartRateMonitoring();
        startLocationMonitoring();

        // Start Foreground Notification
        createNotificationChannel();
        startForeground(1, getForegroundNotification());
    }

    private void initializeMqttClient() {
        String clientId = MqttClient.generateClientId();
        mqttClient = new MqttAndroidClient(getApplicationContext(), MQTT_SERVER_URI, clientId);

        try {
            IMqttToken token = mqttClient.connect();
            token.setActionCallback(new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.d("MQTT", "Connected to MQTT broker");
                    updateMqttStatus("Connected");
                    // Subscribe to a topic (optional, for testing)
                    try {
                        mqttClient.subscribe(MQTT_TOPIC, 1);
                    } catch (MqttException e) {
                        Log.e("MQTT", "Failed to subscribe", e);
                    }
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e("MQTT", "Failed to connect to MQTT broker", exception);
                    updateMqttStatus("Disconnected");
                    // Retry connection
                    retryMqttConnection();
                }
            });
        } catch (Exception e) {
            Log.e("MQTT", "Error initializing MQTT client", e);
            updateMqttStatus("Error");
        }
    }

    private void retryMqttConnection() {
        new android.os.Handler().postDelayed(() -> {
            Log.d("MQTT", "Retrying MQTT connection...");
            initializeMqttClient();
        }, 5000); // Retry after 5 seconds
    }

    private void updateMqttStatus(String status) {
        Log.d("MQTT", "Broadcast sent for status: " + status);
        Intent intent = new Intent("MQTT_STATUS_UPDATE");
        intent.putExtra("status", status);
        sendBroadcast(intent);
    }

    private void publishDataToMqtt() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPublishTime >= 1000) { // Kirim setiap 1 detik
            lastPublishTime = currentTime;
            String deviceId = retrieveDeviceId();
            if (mqttClient != null && mqttClient.isConnected()) {
                try { // "heart_rate": %.1f,
                    String payload = String.format(
                            "{\"device\": \"%s\", \"latitude\": %.6f, \"longitude\": %.6f, \"timestamp\": \"%s\"}",
                            deviceId, // Gunakan ID unik perangkat, contoh: Build.SERIAL
                            // currentHeartRate,
                            currentLatitude,
                            currentLongitude,
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())
                    );
                    MqttMessage message = new MqttMessage(payload.getBytes());
                    message.setQos(1);
                    mqttClient.publish(MQTT_TOPIC, message);
                    Log.d("MQTT", "Data published: " + payload);
                } catch (MqttException e) {
                    Log.e("MQTT", "Failed to publish data", e);
                    // saveToLocalStorage(payload); // Simpan lokal jika gagal
                }
            } else {
                Log.w("MQTT", "Cannot publish, MQTT client not connected");
                saveToLocalStorage("Disconnected: " + Build.SERIAL); // Simpan status jika tidak terkoneksi
            }
        }
    }

    // Fungsi untuk mendapatkan ID perangkat yang unik
    private String retrieveDeviceId() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return Settings.Secure.getString(getApplicationContext().getContentResolver(), Settings.Secure.ANDROID_ID);
        } else {
            return Build.SERIAL; // Untuk perangkat lama
        }
    }

    // Metode menyimpan data lokal
    private void saveToLocalStorage(String payload) {
        // Anda bisa menyimpan ke SQLite atau file lokal untuk pengiriman ulang
        Log.d("LocalStorage", "Saving data locally: " + payload);
    }


    private void startHeartRateMonitoring() {
        if (heartRateSensor != null) {
            SensorEventListener heartRateListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (event.sensor.getType() == Sensor.TYPE_HEART_RATE) {
                        currentHeartRate = event.values[0];
                        handler.post(() -> publishDataToMqtt()); // Trigger MQTT publish
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                    // Optional: Handle accuracy changes
                }
            };
            sensorManager.registerListener(heartRateListener, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void startLocationMonitoring() {
        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setInterval(5000); // Update every 5 seconds
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    currentLatitude = location.getLatitude();
                    currentLongitude = location.getLongitude();
                    handler.post(() -> publishDataToMqtt()); // Trigger MQTT publish
                } else {
                    Log.w("Location", "Location is null");
                }
            }
        };

        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
        } else {
            Log.e("Location", "Location permission not granted");
        }
    }

    private Notification getForegroundNotification() {
        return new NotificationCompat.Builder(this, "HeartRateServiceChannel")
                .setContentTitle("Heart Rate and Location Monitoring")
                .setContentText("Monitoring and sending data to MQTT server.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "HeartRateServiceChannel",
                    "Heart Rate Service Channel",
                    NotificationManager.IMPORTANCE_LOW // Ubah ke LOW untuk mengurangi gangguan
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Logic untuk service Anda
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // We don't provide binding
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // Restart service with AlarmManager
        Intent restartServiceIntent = new Intent(this, HeartRateLocationService.class);
        PendingIntent pendingIntent = PendingIntent.getService(
                this,
                1,
                restartServiceIntent,
                PendingIntent.FLAG_IMMUTABLE
        );
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sensorManager != null && heartRateSensor != null) {
            sensorManager.unregisterListener((SensorEventListener) this);
        }
        if (fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
            } catch (Exception e) {
                Log.e("MQTT", "Error disconnecting MQTT client", e);
            }
        }
    }
}
