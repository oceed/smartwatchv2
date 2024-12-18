package com.example.myapplication;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private SensorManager sensorManager;
    private Sensor heartRateSensor;
    private TextView heartRateTextView, locationTextView, mqttStatusTextView;

    private static final int PERMISSION_REQUEST_CODE = 1;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    // BroadcastReceiver for MQTT status
    private final BroadcastReceiver mqttStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            Log.d("MainActivity", "Received broadcast for status: " + status); // Log ketika status diterima
            mqttStatusTextView.setText("MQTT Status: " + status);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        heartRateTextView = findViewById(R.id.heartRateTextView);
        locationTextView = findViewById(R.id.locationTextView);
        mqttStatusTextView = findViewById(R.id.mqttStatusTextView); // Add this line

        // Start the background service
        startHeartRateLocationService();

        // Register MQTT status receiver
        registerReceiver(mqttStatusReceiver, new IntentFilter("MQTT_STATUS_UPDATE"));

        // Check and request permissions
        if (hasRequiredPermissions()) {
            initializeHeartRateSensor();
            initializeLocationUpdates();
            requestBatteryOptimizationPermission();
        } else {
            requestPermissions();
        }
    }

    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.ACCESS_FINE_LOCATION
        }, PERMISSION_REQUEST_CODE);
    }

    private void requestBatteryOptimizationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void initializeHeartRateSensor() {
        SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        if (sensorManager != null) {
            // Loop melalui semua sensor dan cari yang memiliki type 65599
            List<Sensor> sensorList = sensorManager.getSensorList(Sensor.TYPE_ALL);
            Sensor heartRateSensor = null;

            for (Sensor sensor : sensorList) {
                if (sensor.getType() == 65599) { // Gunakan ID sensor 65599
                    heartRateSensor = sensor;
                    break;
                }
            }

            if (heartRateSensor != null) {
                sensorManager.registerListener(new SensorEventListener() {
                    @Override
                    public void onSensorChanged(SensorEvent event) {
                        if (event.sensor.getType() == 65599) {
                            float heartRate = event.values[1];
                            heartRateTextView.setText("Heart Rate: " + heartRate + " BPM");
                        }
                    }

                    @Override
                    public void onAccuracyChanged(Sensor sensor, int accuracy) {
                        // Handle accuracy changes if needed
                    }
                }, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
            } else {
                Toast.makeText(this, "Custom Heart Rate Sensor not available!", Toast.LENGTH_LONG).show();
            }
        }

    }

    private void initializeLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setInterval(5000); // Update every 5 seconds
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    double latitude = location.getLatitude();
                    double longitude = location.getLongitude();
                    locationTextView.setText("Location: \nLat: " + latitude + "\nLng: " + longitude);
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
        }
    }

    private void startHeartRateLocationService() {
        Intent serviceIntent = new Intent(this, HeartRateLocationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                    grantResults[1] == PackageManager.PERMISSION_GRANTED) {
//                initializeHeartRateSensor();
                initializeLocationUpdates();
            } else {
                Toast.makeText(this, "Permissions Denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        // Minimize the app instead of finishing it
        super.onBackPressed();
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Intent restartServiceIntent = new Intent(this, HeartRateLocationService.class);
        startForegroundService(restartServiceIntent);
        unregisterReceiver(mqttStatusReceiver); // Unregister receiver when activity is destroyed
        if (sensorManager != null && heartRateSensor != null) {
            sensorManager.unregisterListener((SensorEventListener) this);
        }
        if (fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }
}
