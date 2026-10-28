package dev.dhruv.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the system Watch Next ("Continue watching") list. Any failure (missing permission, no TV
 * provider, unexpected columns) gives an empty list, so the row simply stays hidden.
 */
final class WatchNext {

    static final class Item {
        long id;
        String title;
        String subtitle;
        String art;
        float progress;
        String intentUri;
        String pkg;
    }

    private static final int MAX_ITEMS = 10;
    private static final String[] PROJECTION = {
            "_id", "title", "episode_title", "season_display_number", "episode_display_number",
            "poster_art_uri", "thumbnail_uri", "last_playback_position_millis", "duration_millis",
            "intent_uri", "package_name",
    };

    private WatchNext() {
    }

    static List<Item> query(Context ctx) {
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, PROJECTION,
                null, null, "last_engagement_time_utc_millis DESC")) {
            if (c == null) return Collections.emptyList();
            List<Item> out = new ArrayList<>();
            while (c.moveToNext() && out.size() < MAX_ITEMS) {
                Item it = new Item();
                it.title = str(c, "title");
                if (it.title == null) continue;
                it.id = num(c, "_id");
                String season = str(c, "season_display_number");
                String episode = str(c, "episode_display_number");
                String epTitle = str(c, "episode_title");
                StringBuilder sub = new StringBuilder();
                if (season != null) sub.append('S').append(season);
                if (episode != null) sub.append(sub.length() > 0 ? " " : "").append('E').append(episode);
                if (epTitle != null) sub.append(sub.length() > 0 ? " · " : "").append(epTitle);
                it.subtitle = sub.length() > 0 ? sub.toString() : null;
                it.art = str(c, "poster_art_uri");
                if (it.art == null) it.art = str(c, "thumbnail_uri");
                long pos = num(c, "last_playback_position_millis");
                long dur = num(c, "duration_millis");
                it.progress = dur > 0 && pos > 0 ? Math.min(1f, (float) pos / dur) : 0f;
                it.intentUri = str(c, "intent_uri");
                it.pkg = str(c, "package_name");
                out.add(it);
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) return null;
        String s = c.getString(i);
        return s == null || s.trim().isEmpty() ? null : s;
    }

    private static long num(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0L : c.getLong(i);
    }
}
