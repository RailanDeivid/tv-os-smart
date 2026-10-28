package dev.dhruv.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

/**
 * Ambient mode: when the home screen has been left alone for a while, the screen shows only the time,
 * the weather and the next matches on a dark green glow. Left column: clock, date, the weather now and
 * the 10-day forecast. Right column: the next matches of the followed clubs. It reads what the launcher
 * already has cached. Any key brings the launcher back. The content drifts a few pixels every minute so
 * nothing stays lit in exactly the same place.
 */
final class AmbientView extends FrameLayout {
    private static final long REFRESH_MS = 30_000L;
    private static final long DRIFT_MS = 60_000L;
    private static final int MAX_MATCH_ROWS = 4;

    private final Weather weather;
    private final NextMatch[] matches;
    private final Typeface face;
    private final float density;
    private final LinearLayout content;
    private final TextView nowLine;
    private final LinearLayout forecastBlock;
    private final LinearLayout gamesBlock;
    private final View divider;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private boolean active;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private final Runnable drifter = new Runnable() {
        @Override
        public void run() {
            float range = 14 * density;
            content.animate().translationX((random.nextFloat() * 2 - 1) * range)
                    .translationY((random.nextFloat() * 2 - 1) * range).setDuration(1500).start();
            handler.postDelayed(this, DRIFT_MS);
        }
    };

    AmbientView(Context context, Weather weather, NextMatch[] matches) {
        super(context);
        this.weather = weather;
        this.matches = matches;
        this.density = context.getResources().getDisplayMetrics().density;
        this.face = context.getResources().getFont(R.font.outfit);

        // The same kind of dark green glow the home screen has behind a selected green app.
        GradientDrawable bg = new GradientDrawable();
        bg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        bg.setGradientCenter(0.30f, 0.38f);
        bg.setGradientRadius(context.getResources().getDisplayMetrics().widthPixels * 0.78f);
        bg.setColors(new int[]{0xFF064B2D, 0xFF03261A, 0xFF000000});
        setBackground(bg);
        setVisibility(GONE);
        setClickable(true);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(64), dp(24), dp(64), dp(24));
        addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- left column: time and weather ----
        LinearLayout left = new LinearLayout(context);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextClock clock = new TextClock(context);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 118);
        clock.setTextColor(0xFFFFFFFF);
        clock.setTypeface(face);
        clock.setFontVariationSettings("'wght' 200");
        clock.setLetterSpacing(-0.03f);
        clock.setIncludeFontPadding(false);
        left.addView(clock, wrap());

        TextClock date = new TextClock(context);
        date.setFormat24Hour("EEEE, d 'de' MMMM");
        date.setFormat12Hour("EEEE, d 'de' MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        date.setTextColor(0xB3FFFFFF);
        date.setTypeface(face);
        date.setLetterSpacing(0.12f);
        date.setAllCaps(true);
        LinearLayout.LayoutParams dateLp = wrap();
        dateLp.topMargin = dp(2);
        left.addView(date, dateLp);

        nowLine = text("", 26, 0xFFFFFFFF);
        LinearLayout.LayoutParams nowLp = wrap();
        nowLp.topMargin = dp(10);
        left.addView(nowLine, nowLp);

        forecastBlock = new LinearLayout(context);
        forecastBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams fp = wrap();
        fp.topMargin = dp(24);
        left.addView(forecastBlock, fp);

        // ---- divider ----
        divider = new View(context);
        divider.setBackgroundColor(0x2EFFFFFF);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(300));
        dl.setMarginStart(dp(28));
        dl.setMarginEnd(dp(36));
        content.addView(divider, dl);

        // ---- right column: matches ----
        gamesBlock = new LinearLayout(context);
        gamesBlock.setOrientation(LinearLayout.VERTICAL);
        gamesBlock.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(gamesBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    boolean isActive() {
        return active;
    }

    void show() {
        if (active) return;
        active = true;
        refresh();
        content.setTranslationX(0);
        content.setTranslationY(0);
        setAlpha(0f);
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(900).start();
        handler.removeCallbacks(refresher);
        handler.postDelayed(refresher, REFRESH_MS);
        handler.removeCallbacks(drifter);
        handler.postDelayed(drifter, DRIFT_MS);
    }

    void hide() {
        if (!active) return;
        active = false;
        stopTimers();
        animate().alpha(0f).setDuration(250).withEndAction(() -> {
            if (!active) setVisibility(GONE);
        }).start();
    }

    /** Closes without animation (the launcher is being paused). */
    void hideNow() {
        active = false;
        stopTimers();
        animate().cancel();
        setVisibility(GONE);
    }

    private void stopTimers() {
        handler.removeCallbacks(refresher);
        handler.removeCallbacks(drifter);
    }

    /** Rebuilds the changing parts from the launcher's cached data. */
    void refresh() {
        fillNow();
        fillForecast();
        fillGames();
        divider.setVisibility(gamesBlock.getVisibility() == VISIBLE ? VISIBLE : GONE);
    }

    // ---- current weather -----------------------------------------------------------------------

    private void fillNow() {
        String city = weather.city();
        JSONObject cur = weather.nowData();
        if (cur.has("temperature_2m")) {
            nowLine.setText(Weather.iconFor(cur.optInt("weather_code")) + "  " + Math.round(cur.optDouble("temperature_2m")) + "° agora"
                    + (city.isEmpty() ? "" : "  ·  " + city));
            nowLine.setVisibility(VISIBLE);
        } else {
            nowLine.setVisibility(GONE);
        }
    }

    // ---- forecast ------------------------------------------------------------------------------

    private void fillForecast() {
        forecastBlock.removeAllViews();
        JSONObject d = weather.dailyData();
        JSONArray time = d.optJSONArray("time");
        JSONArray code = d.optJSONArray("weather_code");
        JSONArray max = d.optJSONArray("temperature_2m_max");
        JSONArray min = d.optJSONArray("temperature_2m_min");
        JSONArray rain = d.optJSONArray("precipitation_probability_max");
        if (time == null || code == null || max == null || min == null || rain == null || time.length() == 0) {
            forecastBlock.setVisibility(GONE);
            return;
        }
        String today = Weather.todayString();
        SimpleDateFormat parse = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat label = new SimpleDateFormat("EEE d", new Locale("pt", "BR"));
        forecastBlock.addView(caption(getContext().getString(R.string.weather_title)));
        LinearLayout row = null;
        int shown = 0;
        for (int i = 0; i < time.length(); i++) {
            String day = time.optString(i);
            if (day.compareTo(today) < 0) continue;
            if (shown % 5 == 0) {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = wrap();
                rp.topMargin = dp(shown == 0 ? 8 : 6);
                forecastBlock.addView(row, rp);
            }
            String name;
            try {
                name = day.equals(today) ? "hoje" : label.format(parse.parse(day)).replace(".", "");
            } catch (Exception e) {
                name = day;
            }
            row.addView(dayCell(name, code.optInt(i), max.optDouble(i), min.optDouble(i), rain.optInt(i)));
            shown++;
        }
        forecastBlock.setVisibility(shown == 0 ? GONE : VISIBLE);
    }

    private View dayCell(String name, int code, double max, double min, int rain) {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setLayoutParams(new LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT));
        c.addView(centered(name.toUpperCase(new Locale("pt", "BR")), 11, 0xA6FFFFFF, true));
        c.addView(centered(Weather.iconFor(code), 24, 0xFFFFFFFF, false));
        c.addView(centered(Math.round(max) + "°", 16, 0xFFFFFFFF, true));
        c.addView(centered(Math.round(min) + "°", 12, 0x99FFFFFF, true));
        c.addView(centered(rain + "%", 11, rain >= 30 ? 0xFF7CC4FA : 0xB3B8D4E8, true));
        return c;
    }

    // ---- next matches --------------------------------------------------------------------------

    private void fillGames() {
        gamesBlock.removeAllViews();
        int shown = 0;
        for (NextMatch nm : matches) {
            for (JSONObject m : nm.upcoming()) {
                if (shown >= MAX_MATCH_ROWS) break;
                if (shown == 0) gamesBlock.addView(caption(getContext().getString(R.string.next_matches)));
                addMatch(nm.displayName(), m);
                shown++;
            }
        }
        gamesBlock.setVisibility(shown == 0 ? GONE : VISIBLE);
    }

    private void addMatch(String ourName, JSONObject m) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addCrest(row, m.optString("ourCrest"), 0);
        addInline(row, NextMatch.clubName(getContext(), m, true, ourName), 20, 0xFFFFFFFF, 10);
        boolean scored = NextMatch.hasScore(m);
        if (scored) addInline(row, NextMatch.scoreText(m), 24, 0xFFFFFFFF, 12);
        else addInline(row, "vs", 15, 0x99FFFFFF, 12);
        addCrest(row, m.optString("oppCrest"), 12);
        addInline(row, NextMatch.clubName(getContext(), m, false, m.optString("opp")), 20, 0xFFFFFFFF, 10);
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = dp(16);
        gamesBlock.addView(row, lp);

        boolean tbd = m.optBoolean("tbd");
        SimpleDateFormat fmt = new SimpleDateFormat(tbd ? "EEE, d MMM" : "EEE, d MMM · HH:mm", new Locale("pt", "BR"));
        fmt.setTimeZone(TimeZone.getTimeZone("America/Sao_Paulo"));
        String when = fmt.format(new Date(m.optLong("utc")));
        TextView info = text((scored ? NextMatch.phase(m) : when + (tbd ? " · horário a definir" : " (Brasília)"))
                + " · " + NextMatch.labelFor(m.optString("comp")),
                14, scored && NextMatch.isLive(m) ? 0xFF7CE38B : 0xB3FFFFFF);
        LinearLayout.LayoutParams ip = wrap();
        ip.topMargin = dp(3);
        ip.setMarginStart(dp(2));
        gamesBlock.addView(info, ip);
    }

    private void addCrest(LinearLayout row, String url, int startDp) {
        if (url == null || url.isEmpty()) return;
        ImageView crest = new ImageView(getContext());
        crest.setScaleType(ImageView.ScaleType.FIT_CENTER);
        crest.setVisibility(GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
        lp.setMarginStart(dp(startDp));
        row.addView(crest, lp);
        ImageLoader.load(getContext(), url, crest, 128, () -> crest.setVisibility(VISIBLE));
    }

    private void addInline(LinearLayout row, String s, int sp, int color, int startDp) {
        TextView t = text(s, sp, color);
        LinearLayout.LayoutParams lp = wrap();
        lp.setMarginStart(dp(startDp));
        row.addView(t, lp);
    }

    // ---- small helpers -------------------------------------------------------------------------

    private TextView caption(String s) {
        TextView t = text(s, 11, 0x99FFFFFF);
        t.setLetterSpacing(0.24f);
        t.setAllCaps(true);
        return t;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setSingleLine(true);
        return t;
    }

    private TextView centered(String s, int sp, int color, boolean font) {
        TextView t = text(s, sp, color);
        if (!font) t.setTypeface(null);   // emoji: leave the system font
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
