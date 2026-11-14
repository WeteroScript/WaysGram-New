package org.telegram.messenger.waysgram;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

public final class WaysGramLiveLocationService extends Service {
    private static final String CHANNEL_ID = "waysgram_live_location";
    private static final int NOTIFICATION_ID = 7108;
    private static final String PREFS = "waysgram_live_location";
    private static final String ACTION_START = "org.waysgram.action.START_LIVE_LOCATION";
    private static final String ACTION_STOP = "org.waysgram.action.STOP_LIVE_LOCATION";
    private static final String EXTRA_ID = "live_location_id";
    private static final String EXTRA_PEER = "peer_id";
    private static final String EXTRA_EXPIRY = "expires_at";

    private LocationManager locationManager;
    private LocationListener locationListener;
    private WaysGramApiClient apiClient;
    private String liveLocationId;
    private long peerId;
    private long expiresAt;
    private boolean stopping;
    private long lastUpdateAt;
    private final Handler expiryHandler = new Handler(Looper.getMainLooper());
    private final Runnable expiryRunnable = () -> endShare(true);

    public static void start(Context context, long peerId, String liveLocationId, long expiresAt) {
        if (context == null || peerId <= 0 || liveLocationId == null
                || !liveLocationId.matches("[a-f0-9]{32}") || expiresAt <= 0) return;
        Intent intent = new Intent(context, WaysGramLiveLocationService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_ID, liveLocationId);
        intent.putExtra(EXTRA_PEER, peerId);
        intent.putExtra(EXTRA_EXPIRY, expiresAt);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context, String liveLocationId) {
        if (context == null) return;
        Intent intent = new Intent(context, WaysGramLiveLocationService.class);
        intent.setAction(ACTION_STOP);
        intent.putExtra(EXTRA_ID, liveLocationId == null ? "" : liveLocationId);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            String requestedId = intent.getStringExtra(EXTRA_ID);
            if (liveLocationId == null) restoreState();
            if (liveLocationId != null && apiClient == null) {
                apiClient = new WaysGramApiClient(WaysGramSessionStore.getInstance(this));
            }
            if (requestedId == null || requestedId.isEmpty()
                    || liveLocationId == null || requestedId.equals(liveLocationId)) endShare(true);
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_START.equals(intent.getAction())) {
            liveLocationId = intent.getStringExtra(EXTRA_ID);
            peerId = intent.getLongExtra(EXTRA_PEER, 0);
            expiresAt = intent.getLongExtra(EXTRA_EXPIRY, 0);
            stopping = false;
            lastUpdateAt = 0;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(EXTRA_ID, liveLocationId)
                    .putLong(EXTRA_PEER, peerId)
                    .putLong(EXTRA_EXPIRY, expiresAt)
                    .apply();
        } else if (intent == null) {
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            liveLocationId = prefs.getString(EXTRA_ID, null);
            peerId = prefs.getLong(EXTRA_PEER, 0);
            expiresAt = prefs.getLong(EXTRA_EXPIRY, 0);
        }

        if (liveLocationId == null || !liveLocationId.matches("[a-f0-9]{32}")
                || peerId <= 0 || expiresAt <= System.currentTimeMillis() / 1000L) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        if (apiClient == null) apiClient = new WaysGramApiClient(WaysGramSessionStore.getInstance(this));
        expiryHandler.removeCallbacks(expiryRunnable);
        expiryHandler.postDelayed(expiryRunnable,
                Math.max(0L, expiresAt * 1000L - System.currentTimeMillis()));
        startLocationUpdates();
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Трансляция геолокации", NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Показывает, когда WaysGram передаёт ваше местоположение собеседнику.");
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, WaysGramLiveLocationService.class);
        stopIntent.setAction(ACTION_STOP);
        stopIntent.putExtra(EXTRA_ID, liveLocationId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getService(this, NOTIFICATION_ID, stopIntent, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("WaysGram · геолокация")
                .setContentText("Местоположение передаётся собеседнику")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", pending);
        return builder.build();
    }

    @SuppressWarnings("MissingPermission")
    private void startLocationUpdates() {
        if (locationListener != null) return;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
            endShare(true);
            return;
        }
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            endShare(true);
            return;
        }
        try {
            boolean fine = Build.VERSION.SDK_INT < 23
                    || checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
            Criteria criteria = new Criteria();
            criteria.setAccuracy(fine ? Criteria.ACCURACY_FINE : Criteria.ACCURACY_COARSE);
            String provider = locationManager.getBestProvider(criteria, true);
            if (provider == null) {
                provider = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                        ? LocationManager.NETWORK_PROVIDER
                        : locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                            ? LocationManager.GPS_PROVIDER : null;
            }
            if (provider == null) {
                endShare(true);
                return;
            }
            locationListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) { sendLocationUpdate(location); }
                @Override public void onStatusChanged(String providerName, int status, Bundle extras) { }
                @Override public void onProviderEnabled(String providerName) { }
                @Override public void onProviderDisabled(String providerName) { }
            };
            locationManager.requestLocationUpdates(provider, 10000L, 5.0f,
                    locationListener, Looper.getMainLooper());
            Location last = locationManager.getLastKnownLocation(provider);
            if (last != null) sendLocationUpdate(last);
        } catch (SecurityException | IllegalArgumentException exception) {
            endShare(true);
        }
    }

    private void sendLocationUpdate(Location location) {
        if (stopping || location == null || apiClient == null || liveLocationId == null) return;
        long now = System.currentTimeMillis() / 1000L;
        if (now >= expiresAt) {
            endShare(true);
            return;
        }
        if (now - lastUpdateAt < 8L) return;
        lastUpdateAt = now;
        JSONObject payload = new JSONObject();
        try {
            payload.put("recipient_id", peerId);
            payload.put("live_location_id", liveLocationId);
            payload.put("latitude", location.getLatitude());
            payload.put("longitude", location.getLongitude());
            if (location.hasAccuracy()) payload.put("accuracy_m", location.getAccuracy());
        } catch (Exception exception) {
            return;
        }
        apiClient.post("/api/messages/live-location", payload, new WaysGramApiClient.Callback() {
            @Override public void onSuccess(JSONObject response) { }
            @Override public void onError(String message, int statusCode) {
                if (statusCode == 401 || statusCode == 403 || statusCode == 404) endShare(true);
            }
        });
    }

    private void stopLocationUpdates() {
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); }
            catch (SecurityException ignored) { }
        }
        locationListener = null;
        locationManager = null;
    }

    private void endShare(boolean notifyBackend) {
        if (stopping) return;
        stopping = true;
        stopLocationUpdates();
        if (notifyBackend && apiClient != null && liveLocationId != null) {
            apiClient.delete("/api/messages/live-location/" + liveLocationId,
                    new WaysGramApiClient.Callback() {
                        @Override public void onSuccess(JSONObject response) { finishService(); }
                        @Override public void onError(String message, int statusCode) { finishService(); }
                    });
        } else {
            finishService();
        }
    }

    private void finishService() {
        expiryHandler.removeCallbacks(expiryRunnable);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        expiryHandler.removeCallbacks(expiryRunnable);
        stopLocationUpdates();
        if (apiClient != null) {
            apiClient.close();
            apiClient = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
