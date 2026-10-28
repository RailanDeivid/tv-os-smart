package dev.dhruv.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        // Glass: a translucent white veil (brighter at the top) over the glow behind it, with a light rim.
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.5f * density);
        edge.setColor(0x59FFFFFF);
        sheen.setStyle(Paint.Style.STROKE);
        sheen.setStrokeWidth(density);
        sheen.setColor(0x80FFFFFF);
    }

    /** 0 hides the band and the gap (the dock is off and every app is a plain grid tile). */
    void setDockSize(int dockSize) {
        this.dockSize = dockSize;
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float pad = 14 * density;
        rect.inset(-pad, -pad);
        rect.left = 0;                    // a sideways row: the band runs the whole width
        rect.right = parent.getWidth();
        float radius = 14 * density;
        band.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x42FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
        // a thin bright line just inside the top edge, like light catching the glass
        float inset = 1.5f * density;
        c.drawLine(rect.left + radius, rect.top + inset, rect.right - radius, rect.top + inset, sheen);
    }
}
