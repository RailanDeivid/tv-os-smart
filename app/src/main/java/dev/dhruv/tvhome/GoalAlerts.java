package dev.dhruv.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A card in the corner of the screen, over whatever app is open, when a followed club's match gets a goal
 * (either side), with a soft chime. While a match is on it asks football-data.org for the score every minute;
 * the rest of the time it only looks for fixtures every few hours. It runs inside the accessibility service,
 * which is the one part of the app that stays alive while another app is on screen.
 *
 * The free plan's scores are delayed, so the card can come after the goal on the broadcast. The first score
 * it sees for a match is only remembered, never announced; a goal is a change from the score it saw before.
 */
final class GoalAlerts {
    private static final String TAG = "GoalAlerts";
    private static final String API = "https://api.football-data.org/v4/";
    private static final long TICK_MS = 60L * 1000;
    private static final long IDLE_MS = 3L * 60 * 60 * 1000;   // between looks when no match is near
    private static final long RETRY_MS = 5L * 60 * 1000;   // after a failed look
    private static final long LEAD_MS = 5L * 60 * 1000;   // start watching this long before kick-off
    private static final long PLAY_MS = 3L * 60 * 60 * 1000;   // a match counts as on for this long after kick-off
    private static final long SHOW_MS = 8L * 1000;

    private static final class Team {
        final String name;
        final int id;
        final String competitions;
        long checkedAt;
        boolean busy;
        volatile long[] openKickoffs = new long[0];   // kick-offs of matches not over yet, from the last answer

        Team(String name, int id, String competitions) {
            this.name = name;
            this.id = id;
            this.competitions = competitions;
        }

        /** True while a match is about to start, or has been on for less than PLAY_MS: look often. */
        boolean matchOn(long now) {
            for (long kickoff : openKickoffs) {
                if (kickoff - LEAD_MS <= now && now <= kickoff + PLAY_MS) return true;
            }
            return false;
        }
    }

    private static final class Alert {
        long matchId;
        String title;      // "GOL DO SÃO PAULO!"
        String half;       // "1º TEMPO", "2º TEMPO" or "PRORROGAÇÃO"
        int minute;        // 0 when the API does not say
        int injury;        // added time, when the goal came in it
        String scorer = "";   // who scored: only when the API's match details carry the goals list
        String comp;
        String ourName;
        String theirName;
        String ourCrest;
        String theirCrest;
        int ourGoals;
        int theirGoals;
        boolean ourScored;
        boolean theirScored;
        boolean voided;    // the score went down: a goal was taken back

        /** "2º TEMPO · 67'" or "1º TEMPO · 45+2'"; the minute only when known. */
        String phaseText() {
            if (minute <= 0) return half;
            return half + " · " + minute + (injury > 0 ? "+" + injury : "") + "'";
        }
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences seen;   // last score seen per match, so a restart does not repeat or miss a goal
    private final List<Team> teams = new ArrayList<>();
    private final String token;
    private final WindowManager windows;
    private View card;
    private boolean running;
    private final Runnable hideCard = this::fadeOut;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            for (Team t : teams) {
                if (t.busy) continue;
                if (now - t.checkedAt >= (t.matchOn(now) ? TICK_MS - 1000 : IDLE_MS)) look(t);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    GoalAlerts(Context context) {
        this.context = context;
        this.seen = context.getSharedPreferences("goals", Context.MODE_PRIVATE);
        this.token = context.getString(R.string.football_api_key);
        this.windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        // Same clubs as the home screen: key|name|football-data team id|competitions, separated by semicolons.
        for (String entry : context.getString(R.string.teams_config).split(";")) {
            String[] f = entry.trim().split("\\|");
            if (f.length < 4) continue;
            try {
                teams.add(new Team(f[1].trim(), Integer.parseInt(f[2].trim()), f[3].trim()));
            } catch (NumberFormatException ignored) {
                // a malformed entry is skipped
            }
        }
    }

    void start() {
        if (running || token.isEmpty() || teams.isEmpty()) return;
        running = true;
        handler.post(tick);
    }

    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        hide();
    }

    // ---- looking at the API ---------------------------------------------------------------------

    private void look(Team t) {
        t.busy = true;
        t.checkedAt = System.currentTimeMillis();
        new Thread(() -> {
            boolean ok = false;
            try {
                String body;
                synchronized (NextMatch.API_LOCK) {   // the API refuses calls that arrive at the same moment
                    body = NextMatch.fetchJson(API + "teams/" + t.id + "/matches?competitions=" + t.competitions, token, TAG);
                    Thread.sleep(1500);
                }
                if (body != null) {
                    final List<Alert> alerts = absorb(t, new JSONObject(body));
                    ok = true;
                    if (!alerts.isEmpty()) addScorer(alerts.get(alerts.size() - 1));
                    handler.post(() -> {
                        if (running && !alerts.isEmpty()) show(alerts.get(alerts.size() - 1));
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, t.name + " look failed: " + e);
            } finally {
                if (!ok) t.checkedAt = System.currentTimeMillis() - IDLE_MS + RETRY_MS;
                t.busy = false;
            }
        }, "goal-alerts-" + t.id).start();
    }

    /** Reads one answer: remembers which matches are still open, and returns a card for each score that changed. */
    private List<Alert> absorb(Team t, JSONObject root) throws Exception {
        JSONArray matches = root.getJSONArray("matches");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        List<Long> open = new ArrayList<>();
        List<Alert> alerts = new ArrayList<>();
        for (int i = 0; i < matches.length(); i++) {
            JSONObject m = matches.getJSONObject(i);
            String status = m.optString("status");
            long kickoff = iso.parse(m.getString("utcDate")).getTime();
            boolean over = status.equals("FINISHED") || status.equals("AWARDED") || status.equals("POSTPONED")
                    || status.equals("CANCELLED") || status.equals("SUSPENDED");
            if (!over) open.add(kickoff);
            if (kickoff - LEAD_MS > now || kickoff + PLAY_MS < now) continue;   // only matches around now matter

            String id = "g" + m.optLong("id");
            JSONObject score = m.optJSONObject("score");
            JSONObject goals = score != null ? score.optJSONObject("fullTime") : null;
            boolean started = status.equals("IN_PLAY") || status.equals("PAUSED") || status.equals("FINISHED");
            if (!started || goals == null || goals.isNull("home") || goals.isNull("away")) {
                // Not started: the starting point is 0-0, so a goal in the first minutes is not missed.
                if (!seen.contains(id)) seen.edit().putString(id, "0-0").apply();
                continue;
            }
            int home = goals.optInt("home");
            int away = goals.optInt("away");
            String before = seen.getString(id, null);
            seen.edit().putString(id, home + "-" + away).apply();
            if (before == null) continue;   // first time this match is seen mid-way: remember, do not announce
            String[] p = before.split("-");
            int homeBefore = Integer.parseInt(p[0]);
            int awayBefore = Integer.parseInt(p[1]);
            if (home == homeBefore && away == awayBefore) continue;
            alerts.add(card(t, m, homeBefore, awayBefore, home, away));
        }
        long[] kickoffs = new long[open.size()];
        for (int i = 0; i < kickoffs.length; i++) kickoffs[i] = open.get(i);
        t.openKickoffs = kickoffs;
        return alerts;
    }

    private Alert card(Team t, JSONObject m, int homeBefore, int awayBefore, int home, int away) {
        JSONObject homeTeam = m.optJSONObject("homeTeam");
        JSONObject awayTeam = m.optJSONObject("awayTeam");
        boolean ourHome = homeTeam != null && homeTeam.optInt("id") == t.id;
        JSONObject ours = ourHome ? homeTeam : awayTeam;
        JSONObject theirs = ourHome ? awayTeam : homeTeam;
        String ourName = clubName(ours, t.name);
        String theirName = clubName(theirs, "?");
        int ourGoals = ourHome ? home : away;
        int theirGoals = ourHome ? away : home;
        int ourBefore = ourHome ? homeBefore : awayBefore;
        int theirBefore = ourHome ? awayBefore : homeBefore;

        Alert a = new Alert();
        a.ourName = ourName;
        a.theirName = theirName;
        a.ourCrest = ours != null ? ours.optString("crest", "") : "";
        a.theirCrest = theirs != null ? theirs.optString("crest", "") : "";
        a.ourGoals = ourGoals;
        a.theirGoals = theirGoals;
        a.voided = ourGoals + theirGoals < ourBefore + theirBefore;
        a.ourScored = !a.voided && ourGoals > ourBefore;
        a.theirScored = !a.voided && theirGoals > theirBefore;
        if (a.voided) {
            a.title = "GOL ANULADO";
        } else if (a.ourScored && a.theirScored) {
            a.title = "GOLS!";
        } else {
            a.title = "GOL DO " + (a.ourScored ? ourName : theirName).toUpperCase(new Locale("pt", "BR")) + "!";
        }
        a.matchId = m.optLong("id");
        a.half = half(m, ourGoals + theirGoals);
        a.minute = m.optInt("minute", 0);
        JSONObject competition = m.optJSONObject("competition");
        a.comp = NextMatch.labelFor(competition != null ? competition.optString("name", "") : "");
        return a;
    }

    /**
     * Which part of the match the goal came in. The half-time score says it: goals up to it are the 1st half,
     * any beyond it the 2nd.
     */
    private static String half(JSONObject m, int total) {
        JSONObject score = m.optJSONObject("score");
        if (score != null && "EXTRA_TIME".equals(score.optString("duration"))) return "PRORROGAÇÃO";
        JSONObject ht = score != null ? score.optJSONObject("halfTime") : null;
        boolean known = ht != null && !ht.isNull("home") && !ht.isNull("away");
        return known && total > ht.optInt("home") + ht.optInt("away") ? "2º TEMPO" : "1º TEMPO";
    }

    /**
     * Asks for the match's details, which list every goal with its scorer and minute, and fills in the newest
     * one. Whether the free plan includes that list is not documented: if it does not come, the card simply
     * has no scorer, and nothing else changes.
     */
    private void addScorer(Alert a) {
        if (a.voided || a.ourScored == a.theirScored || a.matchId == 0) return;   // one side scored, one goal
        try {
            String body;
            synchronized (NextMatch.API_LOCK) {
                body = NextMatch.fetchJson(API + "matches/" + a.matchId, token, TAG);
                Thread.sleep(1500);
            }
            if (body == null) return;
            JSONArray goals = new JSONObject(body).optJSONArray("goals");
            if (goals == null || goals.length() == 0) {
                Log.i(TAG, "match " + a.matchId + ": no goals list in the details (plan limit?)");
                return;
            }
            JSONObject last = goals.getJSONObject(goals.length() - 1);
            JSONObject scorer = last.optJSONObject("scorer");
            String name = scorer != null ? scorer.optString("name", "") : "";
            String type = last.optString("type", "");
            a.scorer = name + (type.equals("OWN") ? " (contra)" : type.equals("PENALTY") ? " (pênalti)" : "");
            if (last.optInt("minute", 0) > 0) {
                a.minute = last.optInt("minute");
                a.injury = last.isNull("injuryTime") ? 0 : last.optInt("injuryTime", 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "scorer lookup failed: " + e);
        }
    }

    /** A club's name in the style set by team_names in personal.properties: "short" (default), "full" or "tla". */
    private String clubName(JSONObject team, String fallback) {
        if (team == null) return fallback;
        String mode = context.getString(R.string.team_names).trim().toLowerCase(Locale.ROOT);
        String pick = mode.equals("full") ? team.optString("name", "")
                : mode.equals("tla") ? team.optString("tla", "") : team.optString("shortName", "");
        return pick.isEmpty() ? fallback : pick;
    }

    // ---- the card -------------------------------------------------------------------------------

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void show(Alert a) {
        hide();
        Typeface face = context.getResources().getFont(R.font.outfit);
        int accent = a.voided ? 0xFFFFB454 : 0xFF7CE38B;   // green for a goal, amber for one taken back

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setMinimumWidth(dp(290));
        box.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1E1E24, 0xFF0F0F12});
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), (accent & 0x00FFFFFF) | 0x66000000);
        box.setBackground(bg);

        // Top line: a dot and the headline on the left, the part of the match on the right.
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(context);
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(accent);
        dot.setBackground(dotShape);
        top.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
        TextView title = label(a.title, 11, accent, face, 600, 0.1f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart(dp(7));
        top.addView(title, titleLp);
        TextView phase = label(a.phaseText(), 10, 0xB3FFFFFF, face, 500, 0.08f);
        phase.setMaxLines(1);
        LinearLayout.LayoutParams phaseLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        phaseLp.setMarginStart(dp(16));
        top.addView(phase, phaseLp);
        box.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // The match: crest, name, score, name, crest. The side that just scored has its number in the accent colour.
        LinearLayout match = new LinearLayout(context);
        match.setOrientation(LinearLayout.HORIZONTAL);
        match.setGravity(Gravity.CENTER_VERTICAL);
        addCrest(match, a.ourCrest, 0);
        match.addView(label(a.ourName, 14, 0xFFFFFFFF, face, 500, 0f), margin(dp(8), 0));
        match.addView(label(String.valueOf(a.ourGoals), 27, a.ourScored ? accent : 0xFFFFFFFF, face, 700, 0f), margin(dp(14), 0));
        match.addView(label("x", 15, 0x80FFFFFF, face, 400, 0f), margin(dp(7), 0));
        match.addView(label(String.valueOf(a.theirGoals), 27, a.theirScored ? accent : 0xFFFFFFFF, face, 700, 0f), margin(dp(7), 0));
        match.addView(label(a.theirName, 14, 0xFFFFFFFF, face, 500, 0f), margin(dp(14), 0));
        addCrest(match, a.theirCrest, 8);
        LinearLayout.LayoutParams matchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        matchLp.topMargin = dp(8);
        box.addView(match, matchLp);

        if (!a.scorer.isEmpty()) {
            LinearLayout.LayoutParams scorerLp = margin(0, 0);
            scorerLp.topMargin = dp(8);
            box.addView(label(a.scorer, 13, 0xFFFFFFFF, face, 500, 0f), scorerLp);
        }
        if (!a.comp.isEmpty()) {
            LinearLayout.LayoutParams compLp = margin(0, 0);
            compLp.topMargin = dp(6);
            box.addView(label(a.comp, 10, 0x99FFFFFF, face, 400, 0.04f), compLp);
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(40);
        lp.y = dp(28);
        try {
            box.setAlpha(0f);
            box.setTranslationX(dp(60));
            windows.addView(box, lp);
            box.animate().alpha(1f).translationX(0f).setDuration(350)
                    .setInterpolator(new DecelerateInterpolator()).start();
            card = box;
            chime();
            handler.postDelayed(hideCard, SHOW_MS);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not show the card: " + e);
        }
    }

    /** A one-line text in the app's font; `weight` is the font's variable weight (400 regular, 700 bold). */
    private TextView label(String text, int sp, int color, Typeface face, int weight, float letterSpacing) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setFontVariationSettings("'wght' " + weight);
        t.setLetterSpacing(letterSpacing);
        t.setSingleLine(true);
        return t;
    }

    private LinearLayout.LayoutParams margin(int start, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(start);
        lp.topMargin = top;
        return lp;
    }

    /** A club crest, hidden until the image has loaded, so a missing one just leaves the name. */
    private void addCrest(LinearLayout row, String url, int startDp) {
        if (url == null || url.isEmpty()) return;
        ImageView crest = new ImageView(context);
        crest.setScaleType(ImageView.ScaleType.FIT_CENTER);
        crest.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
        lp.setMarginStart(dp(startDp));
        row.addView(crest, lp);
        ImageLoader.load(context, url, crest, 128, () -> crest.setVisibility(View.VISIBLE));
    }

    /** The timer's way out: the card fades away instead of vanishing. */
    private void fadeOut() {
        final View old = card;
        if (old == null) return;
        old.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            if (card == old) hide();
        }).start();
    }

    private void hide() {
        handler.removeCallbacks(hideCard);
        if (card == null) return;
        View old = card;
        card = null;
        try {
            windows.removeView(old);
        } catch (RuntimeException ignored) {
            // already gone
        }
    }

    /** Two soft notes (E5, then B5), each fading out: quiet enough to sit under a video's sound. */
    private void chime() {
        new Thread(() -> {
            AudioTrack track = null;
            try {
                int rate = 44100;
                double[] notes = {659.25, 987.77};
                int per = rate * 3 / 10;
                short[] pcm = new short[per * notes.length];
                for (int n = 0; n < notes.length; n++) {
                    for (int i = 0; i < per; i++) {
                        double t = i / (double) rate;
                        double attack = Math.min(1.0, i / (rate * 0.01));
                        double fade = Math.exp(-5.0 * t);
                        pcm[n * per + i] = (short) (Math.sin(2 * Math.PI * notes[n] * t) * attack * fade * 0.30 * Short.MAX_VALUE);
                    }
                }
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)   // follows the TV volume; TV notification streams are often muted
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(pcm.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                track.write(pcm, 0, pcm.length);
                track.play();
                Thread.sleep(1000);
            } catch (Exception e) {
                Log.w(TAG, "chime failed: " + e);
            } finally {
                if (track != null) track.release();
            }
        }, "goal-chime").start();
    }
}
