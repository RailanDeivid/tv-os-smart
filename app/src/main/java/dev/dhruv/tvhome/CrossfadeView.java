package dev.dhruv.tvhome;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Two stacked ImageViews: {@link #show} sets up the hidden one and crossfades it in. */
public class CrossfadeView extends FrameLayout {

    interface Setup {
        void apply(ImageView layer);
    }

    private static final long FADE_MS = 250;

    private final ImageView[] layers;
    private int front = 0;

    public CrossfadeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        layers = new ImageView[]{new ImageView(context), new ImageView(context)};
        for (ImageView iv : layers) {
            iv.setAlpha(0f);
            addView(iv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
    }

    void show(Setup setup) {
        ImageView current = layers[front];
        ImageView next = layers[1 - front];
        current.animate().cancel();
        next.animate().cancel();
        setup.apply(next);
        next.bringToFront();
        next.setAlpha(0f);
        next.animate().alpha(1f).setDuration(FADE_MS).start();
        current.animate().alpha(0f).setDuration(FADE_MS).start();
        front = 1 - front;
    }
}
