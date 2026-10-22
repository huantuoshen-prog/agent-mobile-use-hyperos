package com.agent.mobileuse;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

final class AgentIcons {
    private AgentIcons() {}
    static Bitmap create(String state, int size) {
        if ("foreground".equals(state)) return GlowService.createSingleBlueEyeBitmap(size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        c.scale(size / 100f, size / 100f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.parseColor(AgentState.color(state)));
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(8); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        if ("background".equals(state)) {
            c.drawRoundRect(new RectF(14, 16, 67, 65), 8, 8, p);
            c.drawRoundRect(new RectF(35, 37, 88, 86), 8, 8, p);
        } else if ("completed".equals(state)) {
            Path path = new Path(); path.moveTo(20, 51); path.lineTo(42, 74); path.lineTo(82, 27); c.drawPath(path, p);
        } else if ("failed".equals(state)) {
            c.drawLine(25, 25, 75, 75, p); c.drawLine(75, 25, 25, 75, p);
        } else if ("cancelled".equals(state)) {
            c.drawRoundRect(new RectF(24, 24, 76, 76), 7, 7, p);
        } else if ("waiting".equals(state)) {
            Path path = new Path(); path.moveTo(28, 33); path.cubicTo(28, 10, 80, 10, 75, 37); path.cubicTo(73, 51, 50, 49, 50, 65); c.drawPath(path, p);
            c.drawPoint(50, 84, p);
        } else {
            c.drawRoundRect(new RectF(12, 20, 88, 80), 8, 8, p);
            c.drawLine(27, 37, 42, 50, p); c.drawLine(42, 50, 27, 63, p); c.drawLine(54, 63, 73, 63, p);
        }
        return bitmap;
    }
}
