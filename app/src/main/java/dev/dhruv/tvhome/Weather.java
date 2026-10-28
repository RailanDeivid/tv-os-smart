package dev.dhruv.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 10-day forecast from Open-Meteo (free, no key) for the place set in personal.properties
 * (weather_lat, weather_lon, weather_city). Shown as two rows of five days under the top buttons.
 * The last answer is cached, so it still shows when the network is down. Without a location the
 * block stays hidden.
 */
final class Weather {
    private static final String URL_BASE = "https://api.open-meteo.com/v1/forecast"
            + "?current=temperature_2m,weather_code"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
            + "&forecast_days=10";
    private static final long STALE_MS = 30L * 60 * 1000;   // the current temperature should not be hours old
    private static final long RETRY_MS = 5L * 60 * 1000;
    private static final int PER_ROW = 5;
    private static final String K_DAILY = "wx_daily", K_AT = "wx_at", K_TRY = "wx_try", K_NOW = "wx_now";

    private final Context context;
    private final SharedPreferences prefs;
    private final LinearLayout block;   // first child is the title; the forecast rows are added after it
    private final TextView now;         // current temperature, shown above the date
    private final String url;           // null when no location is configured
    private final String city;
    private boolean fetching;

    Weather(Context context, SharedPreferences prefs, LinearLayout block, TextView now) {
        this.context = context;
        this.prefs = prefs;
        this.block = block;
        this.now = now;
        String lat = context.getString(R.string.weather_lat).trim();
        String lon = context.getString(R.string.weather_lon).trim();
        city = context.getString(R.string.weather_city).trim();
        String tz = TimeZone.getDefault().getID();
        url = lat.isEmpty() || lon.isEmpty() ? null
                : URL_BASE + "&latitude=" + lat + "&longitude=" + lon + "&timezone=" + java.net.URLEncoder.encode(tz);
        TextView title = (TextView) block.getChildAt(0);
        title.setText(city.isEmpty() ? context.getString(R.string.weather_title) : context.getString(R.string.weather_title) + " · " + city);
    }

    void refresh() {
        if (url == null) {
            block.setVisibility(View.GONE);
            now.setVisibility(View.GONE);
            return;
        }
        render();
        long now = System.currentTimeMillis();
        boolean due = now - prefs.getLong(K_AT, 0) > STALE_MS || !startsToday();
        if (due && now - prefs.getLong(K_TRY, 0) > RETRY_MS) fetch();
    }

    private static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date());
    }

    private JSONObject cached() {
        try {
            return new JSONObject(prefs.getString(K_DAILY, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ---- read-only access for the ambient screen ----
    JSONObject dailyData() {
        return cached();
    }

    JSONObject nowData() {
        try {
            return new JSONObject(prefs.getString(K_NOW, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    String city() {
        return city;
    }

    static String iconFor(int code) {
        return icon(code);
    }

    static String todayString() {
        return today();
    }

    private boolean startsToday() {
        JSONArray days = cached().optJSONArray("time");
        return days != null && days.length() > 0 && today().equals(days.optString(0));
    }

    private void renderNow() {
        try {
            JSONObject cur = new JSONObject(prefs.getString(K_NOW, "{}"));
            if (cur.has("temperature_2m")) {
                now.setText(icon(cur.optInt("weather_code")) + " " + Math.round(cur.optDouble("temperature_2m")) + "° agora");
                now.setVisibility(View.VISIBLE);
                return;
            }
        } catch (Exception ignored) {
        }
        now.setVisibility(View.GONE);
    }

    private void render() {
        renderNow();
        while (block.getChildCount() > 1) block.removeViewAt(1);
        JSONObject d = cached();
        JSONArray time = d.optJSONArray("time");
        if (time == null || time.length() == 0) {
            block.setVisibility(View.GONE);
            return;
        }
        Typeface face = context.getResources().getFont(R.font.outfit);
        String today = today();
        SimpleDateFormat parse = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat label = new SimpleDateFormat("EEE d", new Locale("pt", "BR"));
        LinearLayout row = null;
        int shown = 0;
        for (int i = 0; i < time.length(); i++) {
            String day = time.optString(i);
            if (day.compareTo(today) < 0) continue;   // a day that is already over
            if (shown % PER_ROW == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.topMargin = dp(shown == 0 ? 8 : 10);
                block.addView(row, rp);
            }
            String name;
            try {
                name = day.equals(today) ? "hoje" : label.format(parse.parse(day)).replace(".", "");
            } catch (Exception e) {
                name = day;
            }
            row.addView(cell(name, d.optJSONArray("weather_code").optInt(i), d.optJSONArray("temperature_2m_max").optDouble(i),
                    d.optJSONArray("temperature_2m_min").optDouble(i), d.optJSONArray("precipitation_probability_max").optInt(i), face));
            shown++;
        }
        block.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
    }

    private View cell(String name, int code, double max, double min, int rain, Typeface face) {
        LinearLayout c = new LinearLayout(context);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setLayoutParams(new LinearLayout.LayoutParams(dp(50), ViewGroup.LayoutParams.WRAP_CONTENT));
        c.addView(text(name.toUpperCase(new Locale("pt", "BR")), 11, 0xA6FFFFFF, face));
        c.addView(text(icon(code), 22, 0xFFFFFFFF, null));
        c.addView(text(Math.round(max) + "°", 14, 0xFFFFFFFF, face));
        c.addView(text(Math.round(min) + "°", 12, 0x8CFFFFFF, face));
        // always shown; a low chance is dimmed so a likely rain still stands out
        c.addView(text(rain + "%", 11, rain >= 30 ? 0xFF64B5F6 : 0xB3A9C6E3, face));   // low chance: paler, still readable
        return c;
    }

    private TextView text(String s, int sp, int color, Typeface face) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    /** WMO weather code -> emoji. */
    private static String icon(int code) {
        if (code == 0) return "☀️";
        if (code == 1) return "🌤️";
        if (code == 2) return "⛅";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "☁️";   // fog: the fog emoji is drawn on a grey square, so use a plain cloud
        if (code >= 51 && code <= 57) return "🌦️";
        if (code >= 61 && code <= 67) return "🌧️";
        if (code >= 71 && code <= 77) return "🌨️";
        if (code == 80) return "🌦️";            // light rain showers
        if (code == 81 || code == 82) return "🌧️"; // moderate / violent rain showers
        if (code >= 95) return "⛈️";
        return "🌡️";
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void fetch() {
        if (fetching) return;
        fetching = true;
        prefs.edit().putLong(K_TRY, System.currentTimeMillis()).apply();
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                int code = conn.getResponseCode();
                if (code == 200) {
                    StringBuilder body = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = r.readLine()) != null) body.append(line);
                    }
                    JSONObject all = new JSONObject(body.toString());
                    JSONObject daily = all.getJSONObject("daily");
                    JSONObject current = all.optJSONObject("current");
                    if (current != null) prefs.edit().putString(K_NOW, current.toString()).apply();
                    Log.i("Weather", "forecast days: " + daily.getJSONArray("time").length());
                    prefs.edit().putString(K_DAILY, daily.toString()).putLong(K_AT, System.currentTimeMillis()).apply();
                } else {
                    Log.w("Weather", "HTTP " + code);
                }
            } catch (Exception e) {
                Log.w("Weather", "fetch failed: " + e);   // keep showing the cached forecast
            } finally {
                if (conn != null) conn.disconnect();
                fetching = false;
                block.post(this::render);
            }
        }, "weather").start();
    }
}
