package dev.dhruv.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

/** Quiet UI clicks: a tick when focus moves, a click on OK / launch. */
final class Sounds {

    boolean enabled;
    private final SoundPool pool;
    private final int focusId;
    private final int selectId;

    Sounds(Context ctx, boolean enabled) {
        this.enabled = enabled;
        pool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        focusId = pool.load(ctx, R.raw.focus, 1);
        selectId = pool.load(ctx, R.raw.select, 1);
    }

    void focus() {
        if (enabled) pool.play(focusId, 0.35f, 0.35f, 1, 0, 1f);
    }

    void select() {
        if (enabled) pool.play(selectId, 0.5f, 0.5f, 1, 0, 1f);
    }

    void release() {
        pool.release();
    }
}
