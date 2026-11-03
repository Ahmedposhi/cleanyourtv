package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * Dock slider that always clips children to the pill shape. {@code clipToOutline} is unreliable on
 * some TV SoCs (icons still draw past the rounded edge), so this clips in {@link #dispatchDraw}.
 * The border stroke is redrawn after children so peeks cannot sit on top of the corner lines.
 */
public class DockPillFrameLayout extends FrameLayout {

    private final Path clip = new Path();
    private final RectF bounds = new RectF();
    private final RectF strokeBounds = new RectF();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float radius;
    private float strokeWidth;

    public DockPillFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        radius = 24f * density;
        strokeWidth = 1.2f * density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(strokeWidth);
        stroke.setColor(0x40FFFFFF);
        setWillNotDraw(false);
        // Canvas clipPath still keeps tiles inside the pill; these stay false so focus zoom
        // can use the inner padding (otherwise the active icon never looks bigger).
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Inset clip so tiles stay clear of the 1.2dp border stroke.
        float inset = strokeWidth;
        bounds.set(inset, inset, w - inset, h - inset);
        float r = Math.max(0f, radius - inset);
        clip.reset();
        clip.addRoundRect(bounds, r, r, Path.Direction.CW);
        strokeBounds.set(strokeWidth / 2f, strokeWidth / 2f, w - strokeWidth / 2f, h - strokeWidth / 2f);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(clip);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
        // Draw the rim after children so peeks never cover the corner lines.
        canvas.drawRoundRect(strokeBounds, radius, radius, stroke);
    }
}
