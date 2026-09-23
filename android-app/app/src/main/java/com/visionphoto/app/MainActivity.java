package com.visionphoto.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import org.json.JSONObject;

import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final String APP_URL = "https://mburzhinsky-hub.github.io/vision_photo/?android=104";
    private static final String APP_HOST = "mburzhinsky-hub.github.io";

    private WebView webView;
    private LocationManager locationManager;
    private FusedLocationProviderClient fusedClient;
    private CancellationTokenSource fusedCancellation;
    private boolean pendingNativeLocationRequest = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable locationTimeout;
    private LocationListener oneShotListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        fusedClient = LocationServices.getFusedLocationProviderClient(this);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.clearCache(true);
        webView.addJavascriptInterface(new NativeBridge(), "VisionNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equalsIgnoreCase(uri.getScheme())
                        && APP_HOST.equalsIgnoreCase(uri.getHost())) {
                    return false;
                }

                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {}
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin,
                    GeolocationPermissions.Callback callback
            ) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                } else {
                    callback.invoke(origin, false, false);
                    pendingNativeLocationRequest = true;
                    requestLocationPermission();
                }
            }
        });

        webView.loadUrl(APP_URL);
        ensurePermissionsAndStartMonitoring();
    }

    private class NativeBridge {
        @JavascriptInterface
        public void requestLocation() {
            runOnUiThread(() -> requestNativeLocation());
        }

        @JavascriptInterface
        public String getLocationStatus() {
            boolean permission = hasLocationPermission();
            boolean enabled = isLocationEnabled();
            int play = GoogleApiAvailability.getInstance()
                    .isGooglePlayServicesAvailable(MainActivity.this);

            return "{\"permission\":" + permission
                    + ",\"enabled\":" + enabled
                    + ",\"playServices\":" + play + "}";
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        requestPermissions(
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                },
                REQ_LOCATION
        );
    }

    private boolean isLocationEnabled() {
        if (locationManager == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return locationManager.isLocationEnabled();
        }

        try {
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void ensurePermissionsAndStartMonitoring() {
        if (!hasLocationPermission()) {
            requestLocationPermission();
            return;
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS
            );
            return;
        }

        startLocationAlerts();
    }

    private void startLocationAlerts() {
        Intent intent = new Intent(this, LocationAlertService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void requestNativeLocation() {
        pendingNativeLocationRequest = true;

        if (!hasLocationPermission()) {
            requestLocationPermission();
            return;
        }

        if (!isLocationEnabled()) {
            pendingNativeLocationRequest = false;
            sendLocationError("Turn on Android Location and try again");
            try {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } catch (Exception ignored) {}
            return;
        }

        Location cached = getBestLocation(false);
        if (cached != null && isRecentEnough(cached, 120_000L)) {
            pendingNativeLocationRequest = false;
            sendLocationToWeb(cached, "cache");
            return;
        }

        requestFusedLocation();
    }

    private void requestFusedLocation() {
        cancelLocationRequest();

        fusedCancellation = new CancellationTokenSource();

        try {
            fusedClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    fusedCancellation.getToken()
            )
            .addOnSuccessListener(this, location -> {
                if (!pendingNativeLocationRequest) return;

                if (location != null) {
                    finishWithLocation(location, "fused");
                } else {
                    requestLocationManagerFallback();
                }
            })
            .addOnFailureListener(this, error -> {
                if (pendingNativeLocationRequest) {
                    requestLocationManagerFallback();
                }
            });
        } catch (SecurityException error) {
            pendingNativeLocationRequest = false;
            sendLocationError("Location permission is required");
            return;
        } catch (Exception error) {
            requestLocationManagerFallback();
        }

        locationTimeout = () -> {
            if (!pendingNativeLocationRequest) return;
            requestLocationManagerFallback();
        };
        handler.postDelayed(locationTimeout, 12_000L);
    }

    private void requestLocationManagerFallback() {
        cancelTimeoutOnly();

        if (!pendingNativeLocationRequest || locationManager == null) return;

        String provider = chooseProvider();
        if (provider == null) {
            Location cached = getBestLocation(true);
            pendingNativeLocationRequest = false;
            if (cached != null) {
                sendLocationToWeb(cached, "last-known");
            } else {
                sendLocationError("No location provider is available");
            }
            return;
        }

        oneShotListener = location -> {
            if (!pendingNativeLocationRequest) return;
            finishWithLocation(location, "android-" + location.getProvider());
        };

        try {
            locationManager.requestLocationUpdates(
                    provider,
                    0L,
                    0f,
                    oneShotListener,
                    Looper.getMainLooper()
            );
        } catch (SecurityException error) {
            pendingNativeLocationRequest = false;
            sendLocationError("Location permission is required");
            return;
        }

        locationTimeout = () -> {
            if (!pendingNativeLocationRequest) return;

            Location cached = getBestLocation(true);
            pendingNativeLocationRequest = false;
            removeOneShotListener();

            if (cached != null) {
                sendLocationToWeb(cached, "last-known");
            } else {
                sendLocationError("No location fix. Move near a window and try again.");
            }
        };
        handler.postDelayed(locationTimeout, 18_000L);
    }

    private String chooseProvider() {
        if (locationManager == null) return null;

        try {
            List<String> enabled = locationManager.getProviders(true);

            if (enabled.contains(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }

            if (enabled.contains(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }

            Criteria criteria = new Criteria();
            criteria.setAccuracy(Criteria.ACCURACY_FINE);
            return locationManager.getBestProvider(criteria, true);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Location getBestLocation(boolean allowOld) {
        if (!hasLocationPermission() || locationManager == null) return null;

        Location best = null;

        try {
            for (String provider : locationManager.getProviders(true)) {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate == null) continue;

                if (!allowOld && !isRecentEnough(candidate, 10 * 60_000L)) {
                    continue;
                }

                if (best == null
                        || candidate.getTime() > best.getTime()
                        || (candidate.hasAccuracy() && best.hasAccuracy()
                        && candidate.getAccuracy() < best.getAccuracy())) {
                    best = candidate;
                }
            }
        } catch (SecurityException ignored) {}

        return best;
    }

    private boolean isRecentEnough(Location location, long maxAgeMs) {
        return Math.abs(System.currentTimeMillis() - location.getTime()) <= maxAgeMs;
    }

    private void finishWithLocation(Location location, String source) {
        cancelLocationRequest();
        pendingNativeLocationRequest = false;
        sendLocationToWeb(location, source);
    }

    private void sendLocationToWeb(Location location, String source) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("ok", true);
            payload.put("latitude", location.getLatitude());
            payload.put("longitude", location.getLongitude());
            payload.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
            payload.put("provider", source);
        } catch (Exception ignored) {}

        evaluateLocationCallback(payload);
    }

    private void sendLocationError(String message) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("ok", false);
            payload.put("message", message);
        } catch (Exception ignored) {}

        evaluateLocationCallback(payload);
    }

    private void evaluateLocationCallback(JSONObject payload) {
        if (webView == null) return;

        String script = "window.__visionNativeLocation && window.__visionNativeLocation("
                + payload.toString()
                + ");";

        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private void cancelTimeoutOnly() {
        if (locationTimeout != null) {
            handler.removeCallbacks(locationTimeout);
            locationTimeout = null;
        }
    }

    private void removeOneShotListener() {
        if (oneShotListener != null && locationManager != null) {
            try {
                locationManager.removeUpdates(oneShotListener);
            } catch (Exception ignored) {}
            oneShotListener = null;
        }
    }

    private void cancelLocationRequest() {
        cancelTimeoutOnly();
        removeOneShotListener();

        if (fusedCancellation != null) {
            try {
                fusedCancellation.cancel();
            } catch (Exception ignored) {}
            fusedCancellation = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) {
                ensurePermissionsAndStartMonitoring();

                if (pendingNativeLocationRequest) {
                    requestNativeLocation();
                }
            } else {
                pendingNativeLocationRequest = false;
                sendLocationError("Location access denied");
                Toast.makeText(
                        this,
                        "Allow location access for Vision Photo.",
                        Toast.LENGTH_LONG
                ).show();
            }
        } else if (requestCode == REQ_NOTIFICATIONS) {
            startLocationAlerts();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        cancelLocationRequest();

        if (webView != null) {
            webView.removeJavascriptInterface("VisionNative");
            webView.destroy();
        }

        super.onDestroy();
    }
}
