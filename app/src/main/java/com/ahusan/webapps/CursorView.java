package com.ahusan.webapps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Mouse pointer drawn over the web page, moved with the remote's D-pad. */
public class CursorView extends View {

    private float x, y;
    private boolean pressed;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final float d;

    public CursorView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);

        fill.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
        stroke.setColor(0xFF000000);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.6f * d);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        ring.setColor(0x884F9DFF);
        ring.setStyle(Paint.Style.FILL);

        arrow.moveTo(0, 0);
        arrow.lineTo(0, 17);
        arrow.lineTo(4.2f, 13.2f);
        arrow.lineTo(7f, 19.5f);
        arrow.lineTo(9.6f, 18.4f);
        arrow.lineTo(6.9f, 12.2f);
        arrow.lineTo(12.4f, 12.2f);
        arrow.close();
        Matrix m = new Matrix();
        m.setScale(1.6f * d, 1.6f * d);
        arrow.transform(m);
    }

    public void moveTo(float nx, float ny) {
        x = nx;
        y = ny;
        invalidate();
    }

    public void setDown(boolean down) {
        pressed = down;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.translate(x, y);
        if (pressed) canvas.drawCircle(0, 0, 18 * d, ring);
        canvas.drawPath(arrow, fill);
        canvas.drawPath(arrow, stroke);
        canvas.restore();
    }
}
