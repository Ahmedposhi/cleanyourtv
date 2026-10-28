package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches and caches live weather for the current location using Open-Meteo API.
 * Automatically resolves coordinates via LocationManager, IP fallback, or Cairo default (30.0444, 31.2357).
 */
public final class WeatherEngine {
    private static final String TAG = "TvHome.Weather";
    private static final String PREF_NAME = "weather_engine_cache";
    private static final String KEY_TEXT = "weather_text";
    private static final String KEY_TIMESTAMP = "weather_timestamp";
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L; // 30 minutes

    public interface WeatherCallback {
        void onWeatherUpdated(String weatherText);
    }

    private final Context context;
    private final Handler mainHandler;
    private final ExecutorService executor;
    private WeatherCallback callback;
    private boolean running;

    public WeatherEngine(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void start(WeatherCallback callback) {
        this.callback = callback;
        this.running = true;

        // Deliver cached weather immediately to avoid blank headers on boot
        String cached = getCachedWeather();
        if (cached != null && !cached.isEmpty() && callback != null) {
            callback.onWeatherUpdated(cached);
        }

        refreshAsync();
    }

    public void stop() {
        this.running = false;
        this.callback = null;
    }

    public void refreshAsync() {
        executor.execute(() -> {
            try {
                double lat = 30.0444; // Default: Cairo, Egypt
                double lon = 31.2357;
                String city = "Cairo";

                Location loc = getBestLocation();
                if (loc != null) {
                    lat = loc.getLatitude();
                    lon = loc.getLongitude();
                } else {
                    // Quick attempt via IP Geolocation if network is alive
                    try {
                        URL ipUrl = new URL("https://freeipapi.com/api/json");
                        HttpURLConnection conn = (HttpURLConnection) ipUrl.openConnection();
                        conn.setConnectTimeout(2500);
                        conn.setReadTimeout(2500);
                        if (conn.getResponseCode() == 200) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) sb.append(line);
                            reader.close();
                            JSONObject ipJson = new JSONObject(sb.toString());
                            if (ipJson.has("latitude") && ipJson.has("longitude")) {
                                lat = ipJson.getDouble("latitude");
                                lon = ipJson.getDouble("longitude");
                                if (ipJson.has("cityName")) {
                                    String c = ipJson.getString("cityName");
                                    if (!c.isEmpty() && !c.equalsIgnoreCase("null")) city = c;
                                }
                            }
                        }
                        conn.disconnect();
                    } catch (Exception ignored) {
                        // Keep Cairo defaults
                    }
                }

                // Query Open-Meteo current forecast
                String endpoint = String.format(java.util.Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code",
                        lat, lon);
                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    if (json.has("current")) {
                        JSONObject current = json.getJSONObject("current");
                        double temp = current.getDouble("temperature_2m");
                        int code = current.getInt("weather_code");

                        String icon = weatherIcon(code);
                        String formatted = String.format(java.util.Locale.US, "%s %d°C · %s", icon, Math.round(temp), city);

                        saveCache(formatted);

                        if (running && callback != null) {
                            mainHandler.post(() -> {
                                if (running && callback != null) {
                                    callback.onWeatherUpdated(formatted);
                                }
                            });
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "Weather fetch error: " + e.getMessage());
            }
        });
    }

    private Location getBestLocation() {
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            Location gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (gps != null && net != null) {
                return gps.getTime() > net.getTime() ? gps : net;
            }
            return gps != null ? gps : net;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String weatherIcon(int code) {
        switch (code) {
            case 0: return "☀️";
            case 1:
            case 2: return "🌤️";
            case 3: return "⛅";
            case 45:
            case 48: return "🌫️";
            case 51:
            case 53:
            case 55:
            case 61:
            case 63:
            case 65:
            case 80:
            case 81:
            case 82: return "🌧️";
            case 71:
            case 73:
            case 75:
            case 77:
            case 85:
            case 86: return "❄️";
            case 95:
            case 96:
            case 99: return "⛈️";
            default: return "☀️";
        }
    }

    private String getCachedWeather() {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_TEXT, "");
    }

    private void saveCache(String weatherText) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_TEXT, weatherText)
                .putLong(KEY_TIMESTAMP, System.currentTimeMillis()).apply();
    }
}
