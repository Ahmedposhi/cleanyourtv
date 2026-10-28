package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * Dock slider that always clips children to the pill shape. {@code clipToOutline} is unreliable on
 * some TV SoCs (icons still draw past the rounded edge), so this clips in {@link #dispatchDraw}.
 */
public class DockPillFrameLayout extends FrameLayout {

    private final Path clip = new Path();
    private final RectF bounds = new RectF();
    private float radius;

    public DockPillFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        radius = 24f * getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClipChildren(true);
        setClipToPadding(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bounds.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(clip);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }
}
