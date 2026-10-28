package dev.dhruv.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * "Next match" block under the date: one club's next fixture from football-data.org, in Brasilia
 * time. The last answer is cached, so it still shows when the network is down; it is re-fetched
 * at most every few hours (and every minute while there is nothing usable to show, or while a
 * match is on). A match in play or just over shows its score; the free plan's scores are delayed.
 */
final class NextMatch {
    private static final String API = "https://api.football-data.org/v4/";
    private static final int SHOWN = 2;   // next matches per club, any competition
    private static final long STALE_MS = 3L * 60 * 60 * 1000;
    private static final long RETRY_MS = 5L * 60 * 1000;   // after a failed call, wait before asking again
    private static final long KEEP_AFTER_KICKOFF_MS = 3L * 60 * 60 * 1000;   // the match, then its final score for about an hour
    private static final long LIVE_POLL_MS = 60L * 1000;   // while a match is on (free plan: 10 calls a minute)
    private static final long LIVE_LEAD_MS = 5L * 60 * 1000;   // start polling this long before kick-off

    /** The API rejects some calls that arrive at the same moment, so all clubs ask one after another. */
    static final Object API_LOCK = new Object();

    private final Context context;
    private final SharedPreferences prefs;
    private final String key;            // prefix for this club's cached values
    private final String displayName;
    private int teamId;                  // 0 = look it up by name in lookupCompetition
    private final String lookupCompetition;
    private final String lookupNamePrefix;
    private final String competitions;   // e.g. "PD,CL": only the competitions the free plan covers
    private final LinearLayout block;   // first child is the "Next match" caption; entries are added after it
    private boolean fetching;

    NextMatch(Context context, SharedPreferences prefs, String key, String displayName, int teamId,
              String lookupCompetition, String lookupNamePrefix, String competitions, LinearLayout block) {
        this.context = context;
        this.prefs = prefs;
        this.key = key;
        this.displayName = displayName;
        this.teamId = teamId != 0 ? teamId : prefs.getInt(key + "_id", 0);
        this.lookupCompetition = lookupCompetition;
        this.lookupNamePrefix = lookupNamePrefix;
        this.competitions = competitions;
        this.block = block;
    }

    /** Shows what is cached and, if it is old (or missing), asks the API in the background. */
    void refresh() {
        render();
        long now = System.currentTimeMillis();
        // Also due when a match has just dropped off and fewer than SHOWN are left to show.
        boolean live = liveWindow();
        boolean due = now - prefs.getLong(key + "_at", 0) > (live ? LIVE_POLL_MS : STALE_MS) || cachedMatches().size() < SHOWN;
        if (due && now - prefs.getLong(key + "_try", 0) > (live ? LIVE_POLL_MS : RETRY_MS)) fetch();
    }

    /** From just before kick-off until the cached answer says the match is over: ask often, to keep the score fresh. */
    private boolean liveWindow() {
        long now = System.currentTimeMillis();
        for (JSONObject m : cachedMatches()) {
            if (!"FINISHED".equals(m.optString("status")) && m.optLong("utc") - LIVE_LEAD_MS <= now) return true;
        }
        return false;
    }

    static boolean isLive(JSONObject m) {
        String s = m.optString("status");
        return s.equals("IN_PLAY") || s.equals("PAUSED");
    }

    /** True when there is a score worth showing: the match is on or has just finished. */
    static boolean hasScore(JSONObject m) {
        return m.has("ourGoals") && (isLive(m) || "FINISHED".equals(m.optString("status")));
    }

    /** "1 x 0", from our club's side. */
    static String scoreText(JSONObject m) {
        return m.optInt("ourGoals") + " x " + m.optInt("oppGoals");
    }

    /** "AO VIVO · 67'" (the minute only when the API sends it), "INTERVALO" or "ENCERRADO". */
    static String phase(JSONObject m) {
        String s = m.optString("status");
        if (s.equals("PAUSED")) return "INTERVALO";
        if (s.equals("FINISHED")) return "ENCERRADO";
        int minute = m.optInt("minute", 0);
        return minute > 0 ? "AO VIVO · " + minute + "'" : "AO VIVO";
    }

    /** Cached next matches, soonest first, minus anything already over. */
    private List<JSONObject> cachedMatches() {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray all = new JSONArray(prefs.getString(key + "_list", "[]"));
            long cutoff = System.currentTimeMillis() - KEEP_AFTER_KICKOFF_MS;
            for (int i = 0; i < all.length(); i++) {
                if (all.getJSONObject(i).getLong("utc") > cutoff) out.add(all.getJSONObject(i));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---- read-only access for the ambient screen ----
    List<JSONObject> upcoming() {
        return cachedMatches();
    }

    String displayName() {
        return displayName;
    }

    /**
     * A club's name in the style set by team_names in personal.properties: "short" (default: Barça, Racing),
     * "full" (Real Racing Club de Santander) or "tla" (the 3-letter code, RAC). Falls back to what is stored.
     */
    static String clubName(Context context, JSONObject m, boolean ours, String fallback) {
        String mode = context.getString(R.string.team_names).trim().toLowerCase(Locale.ROOT);
        String side = ours ? "our" : "opp";
        String pick = m.optString(side + (mode.equals("full") ? "Full" : mode.equals("tla") ? "Tla" : "Short"), "");
        return pick.isEmpty() ? fallback : pick;
    }

    static String labelFor(String competition) {
        return competitionLabel(competition);
    }

    private boolean hasUpcomingCached() {
        return !cachedMatches().isEmpty();
    }

    private static String competitionLabel(String name) {
        if (name.equals("Primera Division")) return "La Liga";
        if (name.equals("UEFA Champions League")) return "Champions League";
        if (name.startsWith("Campeonato Brasileiro")) return "Brasileirão";
        return name;
    }

    private void render() {
        while (block.getChildCount() > 1) block.removeViewAt(1);
        List<JSONObject> list = cachedMatches();
        if (list.isEmpty()) {
            block.setVisibility(View.GONE);
            return;
        }
        Typeface face = context.getResources().getFont(R.font.outfit);
        boolean first = true;
        for (JSONObject m : list) {
            boolean tbd = m.optBoolean("tbd");   // date known, kick-off time not confirmed yet
            SimpleDateFormat fmt = new SimpleDateFormat(tbd ? "EEE, d MMM" : "EEE, d MMM · HH:mm", new Locale("pt", "BR"));
            fmt.setTimeZone(TimeZone.getTimeZone("America/Sao_Paulo"));
            String when = fmt.format(new Date(m.optLong("utc")));

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            addCrest(row, m.optString("ourCrest"), 0);
            addText(row, clubName(context, m, true, displayName), 15, 0xFFFFFFFF, face, 8);
            boolean scored = hasScore(m);
            if (scored) addText(row, scoreText(m), 20, 0xFFFFFFFF, face, 12);
            else addText(row, "vs", 13, 0x8CFFFFFF, face, 12);
            addCrest(row, m.optString("oppCrest"), 12);
            addText(row, clubName(context, m, false, m.optString("opp")), 15, 0xFFFFFFFF, face, 8);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dp(first ? 8 : 12);
            first = false;
            block.addView(row, rowLp);

            Log.i("NextMatch", "show " + key + ": utc=" + utcIso(m.optLong("utc")) + " -> " + when + " | "
                    + displayName + " x " + m.optString("opp") + " | " + m.optString("comp") + (tbd ? " | time TBD" : ""));
            addLine((scored ? phase(m) : when + (tbd ? " · horário a definir" : " (Brasília)")) + " · " + competitionLabel(m.optString("comp")),
                    13, scored && isLive(m) ? 0xFF7CE38B : 0xA6FFFFFF, face, 3);
        }
        block.setVisibility(View.VISIBLE);
    }

    private static String utcIso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void addText(LinearLayout row, String text, int sp, int color, Typeface face, int startDp) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(startDp));
        row.addView(t, lp);
    }

    /** A club crest; hidden until the image has loaded, so a missing or unreadable one just leaves the name. */
    private void addCrest(LinearLayout row, String url, int startDp) {
        if (url == null || url.isEmpty()) return;
        ImageView crest = new ImageView(context);
        crest.setScaleType(ImageView.ScaleType.FIT_CENTER);
        crest.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
        lp.setMarginStart(dp(startDp));
        row.addView(crest, lp);
        ImageLoader.load(context, url, crest, 128, () -> crest.setVisibility(View.VISIBLE));
    }

    private void addLine(String text, int sp, int color, Typeface face, int topDp) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(topDp * context.getResources().getDisplayMetrics().density);
        block.addView(t, lp);
    }

    private void fetch() {
        final String token = context.getString(R.string.football_api_key);
        if (fetching || token.isEmpty()) return;
        fetching = true;
        prefs.edit().putLong(key + "_try", System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                synchronized (API_LOCK) {
                    if (teamId == 0) teamId = lookUpTeamId(token);
                    if (teamId != 0) {
                        String body = get(API + "teams/" + teamId + "/matches?competitions=" + competitions, token);
                        if (body != null) store(new JSONObject(body));
                    }
                    Thread.sleep(1500);
                }
            } catch (Exception e) {
                Log.w("NextMatch", key + " fetch failed: " + e);   // keep showing the cached match
            } finally {
                fetching = false;
                block.post(this::render);
            }
        }, "next-match-" + key).start();
    }

    private String get(String url, String token) throws Exception {
        return fetchJson(url, token, key);
    }

    /** GET with the API token; null (and a log line tagged with `who`) on any non-200 answer. */
    static String fetchJson(String url, String token, String who) throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            try {
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("X-Auth-Token", token);
                int code = conn.getResponseCode();
                if (code == 200) {
                    StringBuilder body = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = r.readLine()) != null) body.append(line);
                    }
                    return body.toString();
                }
                Log.w("NextMatch", who + " HTTP " + code + " (attempt " + attempt + ")");
                if (code != 400) return null;
            } finally {
                conn.disconnect();
            }
            Thread.sleep(2500);
        }
        return null;
    }

    private int lookUpTeamId(String token) throws Exception {
        String body = get(API + "competitions/" + lookupCompetition + "/teams", token);
        if (body == null) return 0;
        JSONArray all = new JSONObject(body).getJSONArray("teams");
        for (int i = 0; i < all.length(); i++) {
            JSONObject t = all.getJSONObject(i);
            if (t.optString("name").startsWith(lookupNamePrefix) || t.optString("shortName").startsWith(lookupNamePrefix)) {
                Log.i("NextMatch", key + " team id " + t.getInt("id") + " = " + t.optString("name"));
                prefs.edit().putInt(key + "_id", t.getInt("id")).apply();
                return t.getInt("id");
            }
        }
        return 0;
    }

    private void store(JSONObject root) throws Exception {
        JSONArray matches = root.getJSONArray("matches");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        List<JSONObject> upcoming = new ArrayList<>();
        for (int i = 0; i < matches.length(); i++) {
            JSONObject m = matches.getJSONObject(i);
            long t = iso.parse(m.getString("utcDate")).getTime();
            String status = m.optString("status");
            // FINISHED stays (until KEEP_AFTER_KICKOFF_MS) so the final score shows for a while
            boolean open = !(status.equals("AWARDED") || status.equals("POSTPONED")
                    || status.equals("CANCELLED") || status.equals("SUSPENDED"));
            if (!open || t <= now - KEEP_AFTER_KICKOFF_MS) continue;
            JSONObject home = m.getJSONObject("homeTeam");
            JSONObject other = home.optInt("id") == teamId ? m.getJSONObject("awayTeam") : home;
            String opponent = other.optString("shortName", other.optString("name", "?"));
            JSONObject mine = home.optInt("id") == teamId ? home : m.getJSONObject("awayTeam");
            Log.i("NextMatch", key + " crest " + other.optString("crest"));
            JSONObject entry = new JSONObject()
                    .put("ourFull", mine.optString("name", ""))
                    .put("ourShort", mine.optString("shortName", ""))
                    .put("ourTla", mine.optString("tla", ""))
                    .put("oppFull", other.optString("name", ""))
                    .put("oppShort", other.optString("shortName", ""))
                    .put("oppTla", other.optString("tla", ""))
                    .put("ourCrest", mine.optString("crest", ""))
                    .put("oppCrest", other.optString("crest", ""))
                    .put("utc", t)
                    .put("opp", opponent)
                    .put("comp", m.getJSONObject("competition").optString("name", ""))
                    .put("tbd", "SCHEDULED".equals(status))
                    .put("status", status);
            // The current score (full time once it is over); null before kick-off. The minute only comes on some plans.
            JSONObject score = m.optJSONObject("score");
            JSONObject goals = score != null ? score.optJSONObject("fullTime") : null;
            if (goals != null && !goals.isNull("home") && !goals.isNull("away")) {
                boolean ourHome = home.optInt("id") == teamId;
                entry.put("ourGoals", goals.optInt(ourHome ? "home" : "away")).put("oppGoals", goals.optInt(ourHome ? "away" : "home"));
            }
            if (m.has("minute") && !m.isNull("minute")) entry.put("minute", m.optInt("minute"));
            upcoming.add(entry);
        }
        Collections.sort(upcoming, (x, y) -> Long.compare(x.optLong("utc"), y.optLong("utc")));
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(SHOWN, upcoming.size()); i++) out.put(upcoming.get(i));
        Log.i("NextMatch", key + ": " + matches.length() + " matches, showing " + out.length());
        if (out.length() == 0) return;
        prefs.edit().putString(key + "_list", out.toString()).putLong(key + "_at", now).apply();
    }
}
