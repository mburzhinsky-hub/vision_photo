package com.visionphoto.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import java.util.HashMap;
import java.util.Map;

public class LocationAlertService extends Service implements LocationListener {
    private static final String CHANNEL_MONITOR = "vision_photo_monitor";
    private static final String CHANNEL_NEARBY = "vision_photo_nearby";
    private static final int FOREGROUND_ID = 1101;
    private static final float ALERT_RADIUS_METERS = 3000f;
    private static final long MIN_TIME_MS = 60_000L;
    private static final float MIN_DISTANCE_METERS = 120f;

    private LocationManager locationManager;

    private final Spot[] spots = new Spot[]{
            new Spot(
                    "Moscow City · Viewpoint 01",
                    55.747975,
                    37.540821
            ),
            new Spot(
                    "Leningradsky Station · Viewpoint 01",
                    55.776586,
                    37.654944
            )
    };

    private final Map<String, Boolean> insideState = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(FOREGROUND_ID, buildMonitoringNotification());
        startTracking();
    }

    private void createChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel monitor = new NotificationChannel(
                    CHANNEL_MONITOR,
                    "Nearby location monitoring",
                    NotificationManager.IMPORTANCE_LOW
            );
            monitor.setDescription("Keeps Vision Photo ready to detect nearby photo locations.");

            NotificationChannel nearby = new NotificationChannel(
                    CHANNEL_NEARBY,
                    "Nearby photo locations",
                    NotificationManager.IMPORTANCE_HIGH
            );
            nearby.setDescription("Alerts when you are within 3 km of a saved Vision Photo location.");

            manager.createNotificationChannel(monitor);
            manager.createNotificationChannel(nearby);
        }
    }

    private Notification buildMonitoringNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                10,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_MONITOR)
                .setSmallIcon(com.visionphoto.app.R.drawable.ic_launcher)
                .setContentTitle("VISION PHOTO")
                .setContentText("Nearby alerts are active · 3 km radius")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        boolean requested = false;

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        MIN_TIME_MS,
                        MIN_DISTANCE_METERS,
                        this
                );
                requested = true;
            }
        } catch (Exception ignored) {}

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        MIN_TIME_MS,
                        MIN_DISTANCE_METERS,
                        this
                );
                requested = true;
            }
        } catch (Exception ignored) {}

        if (!requested) {
            stopSelf();
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        for (Spot spot : spots) {
            float[] results = new float[1];
            Location.distanceBetween(
                    location.getLatitude(),
                    location.getLongitude(),
                    spot.latitude,
                    spot.longitude,
                    results
            );

            float distance = results[0];
            boolean insideNow = distance <= ALERT_RADIUS_METERS;
            boolean wasInside = Boolean.TRUE.equals(insideState.get(spot.title));

            if (insideNow && !wasInside) {
                showNearbyNotification(spot, distance);
            }

            insideState.put(spot.title, insideNow);
        }
    }

    private void showNearbyNotification(Spot spot, float distanceMeters) {
        int distanceKmRounded = Math.max(1, Math.round(distanceMeters / 1000f));

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                spot.title.hashCode(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new Notification.Builder(this, CHANNEL_NEARBY)
                .setSmallIcon(com.visionphoto.app.R.drawable.ic_launcher)
                .setContentTitle("VISION PHOTO · LOCATION NEARBY")
                .setContentText(spot.title + " · about " + distanceKmRounded + " km away")
                .setStyle(new Notification.BigTextStyle()
                        .bigText("You are within 3 km of " + spot.title + ". Tap to open Vision Photo."))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .build();

        getSystemService(NotificationManager.class)
                .notify(spot.title.hashCode(), notification);
    }

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onDestroy() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static class Spot {
        final String title;
        final double latitude;
        final double longitude;

        Spot(String title, double latitude, double longitude) {
            this.title = title;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
