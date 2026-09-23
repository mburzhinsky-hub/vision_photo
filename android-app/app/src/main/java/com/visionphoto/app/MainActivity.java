package com.visionphoto.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
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
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executor;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final String APP_URL = "https://mburzhinsky-hub.github.io/vision_photo/";
    private static final String APP_HOST = "mburzhinsky-hub.github.io";

    private WebView webView;
    private LocationManager locationManager;
    private boolean pendingNativeLocationRequest = false;
    private CancellationSignal locationCancellation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable locationTimeout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

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
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

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
        public boolean isAndroidApp() {
            return true;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return locationManager != null && locationManager.isLocationEnabled();
        }

        try {
            return locationManager != null
                    && (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
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
            sendLocationError("Location services are turned off");
            Toast.makeText(
                    this,
                    "Turn on Location in Android settings and try again.",
                    Toast.LENGTH_LONG
            ).show();
            try {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } catch (Exception ignored) {}
            return;
        }

        Location freshCached = getBestRecentLocation();
        if (freshCached != null) {
            pendingNativeLocationRequest = false;
            sendLocationToWeb(freshCached);
            return;
        }

        String provider = chooseProvider();
        if (provider == null) {
            pendingNativeLocationRequest = false;
            sendLocationError("No location provider is available");
            return;
        }

        cancelLocationRequest();

        if (Build.VERSION.SDK_INT >= 30) {
            locationCancellation = new CancellationSignal();
            Executor executor = getMainExecutor();

            try {
                locationManager.getCurrentLocation(
                        provider,
                        locationCancellation,
                        executor,
                        location -> {
                            cancelLocationTimeout();
                            pendingNativeLocationRequest = false;

                            if (location != null) {
                                sendLocationToWeb(location);
                            } else {
                                Location cached = getBestRecentLocation();
                                if (cached != null) {
                                    sendLocationToWeb(cached);
                                } else {
                                    sendLocationError("Could not get a location fix");
                                }
                            }
                        }
                );
            } catch (SecurityException error) {
                pendingNativeLocationRequest = false;
                sendLocationError("Location permission is required");
                return;
            }

            locationTimeout = () -> {
                if (!pendingNativeLocationRequest) return;
                if (locationCancellation != null) {
                    locationCancellation.cancel();
                }
                pendingNativeLocationRequest = false;

                Location cached = getBestRecentLocation();
                if (cached != null) {
                    sendLocationToWeb(cached);
                } else {
                    sendLocationError("Location request timed out");
                }
            };
            handler.postDelayed(locationTimeout, 15000L);
        } else {
            Location cached = getBestRecentLocation();
            pendingNativeLocationRequest = false;
            if (cached != null) {
                sendLocationToWeb(cached);
            } else {
                sendLocationError("Could not determine location");
            }
        }
    }

    private String chooseProvider() {
        if (locationManager == null) return null;

        try {
            List<String> enabled = locationManager.getProviders(true);

            if (enabled.contains("fused")) {
                return "fused";
            }

            if (enabled.contains(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }

            if (enabled.contains(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }

            Criteria criteria = new Criteria();
            criteria.setAccuracy(Criteria.ACCURACY_FINE);
            return locationManager.getBestProvider(criteria, true);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Location getBestRecentLocation() {
        if (!hasLocationPermission() || locationManager == null) return null;

        Location best = null;

        try {
            for (String provider : locationManager.getProviders(true)) {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate == null) continue;

                if (best == null
                        || candidate.getTime() > best.getTime()
                        || (candidate.hasAccuracy() && best.hasAccuracy()
                        && candidate.getAccuracy() < best.getAccuracy())) {
                    best = candidate;
                }
            }
        } catch (SecurityException ignored) {}

        if (best == null) return null;

        long ageMs = Math.abs(System.currentTimeMillis() - best.getTime());
        if (ageMs <= 5 * 60_000L) {
            return best;
        }

        return null;
    }

    private void sendLocationToWeb(Location location) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("ok", true);
            payload.put("latitude", location.getLatitude());
            payload.put("longitude", location.getLongitude());
            payload.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
            payload.put("provider", location.getProvider());
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

    private void cancelLocationTimeout() {
        if (locationTimeout != null) {
            handler.removeCallbacks(locationTimeout);
            locationTimeout = null;
        }
    }

    private void cancelLocationRequest() {
        cancelLocationTimeout();

        if (locationCancellation != null) {
            try {
                locationCancellation.cancel();
            } catch (Exception ignored) {}
            locationCancellation = null;
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
