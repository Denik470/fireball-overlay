package lab.coc.fireball;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import java.util.List;

/** Full-screen overlay: defences (pick mode) and the plan, drawn through the current screen transform of the service. */
final class OverlayView extends View {
    private final OverlayService svc; private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] loc = new int[2];
    private OverlayService.Xf x; private OverlayService.Scene sc;

    OverlayView(OverlayService s) {
        super(s); svc = s; dp = s.getResources().getDisplayMetrics().density;
        fill.setStyle(Paint.Style.FILL); line.setStyle(Paint.Style.STROKE); line.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER); text.setTextSize(11 * dp); text.setFakeBoldText(true);
        halo.set(text); halo.setStyle(Paint.Style.STROKE); halo.setStrokeWidth(3 * dp); halo.setColor(0xD8000000);
    }

    // grid (absolute tiles) <-> view pixels
    private float[] scr(double X, double Y) {
        double lx = X - sc.ox, ly = Y - sc.oy, u = lx - ly - x.du, s = lx + ly - x.ds;
        return new float[]{(float) (x.cx + x.a * u - loc[0]), (float) (x.cy + x.b * s - loc[1])};
    }
    double[] toGrid(float vx, float vy, OverlayService.Xf x, OverlayService.Scene sc) {
        getLocationOnScreen(loc);
        double u = (vx + loc[0] - x.cx) / x.a + x.du, s = (vy + loc[1] - x.cy) / x.b + x.ds;
        return new double[]{(u + s) / 2 + sc.ox, (s - u) / 2 + sc.oy};
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) svc.pickAt(e.getX(), e.getY());
        return true;
    }

    private void quad(Path p, double X0, double Y0, double X1, double Y1) {
        float[] a = scr(X0, Y0), b = scr(X1, Y0), c = scr(X1, Y1), d = scr(X0, Y1);
        p.moveTo(a[0], a[1]); p.lineTo(b[0], b[1]); p.lineTo(c[0], c[1]); p.lineTo(d[0], d[1]); p.close();
    }
    private void foot(Path p, Solver.Bld b) { quad(p, b.x, b.y, b.x + b.w, b.y + b.w); }
    private Path circle(double cx, double cy, double r) {
        Path p = new Path();
        for (int i = 0; i <= 64; i++) { float[] q = scr(cx + r * Math.cos(2 * Math.PI * i / 64), cy + r * Math.sin(2 * Math.PI * i / 64)); if (i == 0) p.moveTo(q[0], q[1]); else p.lineTo(q[0], q[1]); }
        return p;
    }
    private void stroke(Canvas c, Path p, int color, float w, float dash) {
        line.setColor(color); line.setStrokeWidth(w * dp); line.setPathEffect(dash > 0 ? new DashPathEffect(new float[]{dash * dp, dash * 0.6f * dp}, 0) : null);
        c.drawPath(p, line); line.setPathEffect(null);
    }
    private void paint(Canvas c, Path p, int color) { fill.setColor(color); c.drawPath(p, fill); }
    private void label(Canvas c, double X, double Y, String t, int color) {
        float[] q = scr(X, Y); c.drawText(t, q[0], q[1], halo); text.setColor(color); c.drawText(t, q[0], q[1], text);
    }
    private void aim(Canvas c, double X, double Y, int color) {
        float[] q = scr(X, Y); float r = 9 * dp;
        for (int k = 0; k < 2; k++) {
            line.setColor(k == 0 ? 0xCC000000 : color); line.setStrokeWidth((k == 0 ? 4.5f : 2f) * dp);
            c.drawCircle(q[0], q[1], r, line);
            c.drawLine(q[0] - 1.6f * r, q[1], q[0] + 1.6f * r, q[1], line); c.drawLine(q[0], q[1] - 1.6f * r, q[0], q[1] + 1.6f * r, line);
        }
    }
    private Path cells(float[] pts, double size) {
        Path p = new Path(); double h = size / 2;
        for (int i = 0; i < pts.length; i += 3) quad(p, pts[i] - h, pts[i + 1] - h, pts[i] + h, pts[i + 1] + h);
        return p;
    }
    private static int marginColor(double m, int alpha) { int rgb = m >= 0.75 ? 0x3DDC84 : m >= 0.25 ? 0xFFD54A : 0xFF5A5A; return (alpha << 24) | rgb; }

    @Override protected void onDraw(Canvas c) {
        x = svc.xf; sc = svc.scene; OverlayService.Mode mode = svc.mode;
        if (x == null || sc == null || sc.B.isEmpty() || mode == OverlayService.Mode.IDLE) return;
        getLocationOnScreen(loc);
        Solver.Plan o = mode == OverlayService.Mode.PLAN ? sc.plan() : null; Solver.Bld T = sc.target;
        if (o != null) {
            double tcx = T.cx(), tcy = T.cy();
            // Warden area, brighter = more tolerance
            double maxM = Math.max(0.3, Math.min(o.wardenTol, 3)); Path[] lv = {new Path(), new Path(), new Path(), new Path()}; double h = Solver.STEP / 2;
            for (int i = 0; i < o.area.length; i += 3) { int k = (int) Math.min(3, Math.floor(4 * Math.min(o.area[i + 2], maxM) / maxM)); quad(lv[k], o.area[i] - h, o.area[i + 1] - h, o.area[i] + h, o.area[i + 1] + h); }
            for (int k = 0; k < 4; k++) paint(c, lv[k], ((int) (255 * (0.25 + 0.18 * k)) << 24) | 0x28BEFF);
            double[] wp = o.wardenPt;
            for (Solver.Bld d : o.binding) {
                double dcx = d.cx(), dcy = d.cy(), mx = (dcx + tcx) / 2, my = (dcy + tcy) / 2, vx = -(dcy - tcy), vy = dcx - tcx, n = Math.hypot(vx, vy), ux = vx / n, uy = vy / n;
                double t = (wp[0] - mx) * ux + (wp[1] - my) * uy;
                float[] p = scr(mx + ux * (t - 8), my + uy * (t - 8)), q = scr(mx + ux * (t + 8), my + uy * (t + 8));
                Path seg = new Path(); seg.moveTo(p[0], p[1]); seg.lineTo(q[0], q[1]); stroke(c, seg, 0xFF78D7FF, 2, 8);
            }
            aim(c, wp[0], wp[1], 0xFF8FE3FF); label(c, wp[0] + 1.1, wp[1] + 1.1, "ДЕД ±" + String.format("%.1f", o.wardenTol), 0xFF8FE3FF);
            for (Solver.Bld b : o.C) {
                float[] p1 = scr(b.x, b.y), p2 = scr(b.x + b.w, b.y + b.w), p3 = scr(b.x, b.y + b.w), p4 = scr(b.x + b.w, b.y);
                line.setColor(0xFFFF1744); line.setStrokeWidth(4 * dp); c.drawLine(p1[0], p1[1], p2[0], p2[1], line); c.drawLine(p3[0], p3[1], p4[0], p4[1], line);
            }
            // fireball splash
            Path fb = circle(tcx, tcy, svc.getSharedPreferences("fb", 0).getFloat("fbR", 6)); paint(c, fb, 0x1AFF8C00); stroke(c, fb, 0xF2FF8C00, 2, 0);
            Path sp = new Path(); for (Solver.Bld b : o.splash) if (b != T) foot(sp, b); paint(c, sp, 0x40FF8C00);
            // invis
            for (int i = 0; i < o.zones.size(); i++) {
                Solver.Zone z = o.zones.get(i);
                paint(c, cells(z.edge, 0.14), marginColor(z.margin, 255));
                stroke(c, circle(z.centre[0], z.centre[1], Solver.INVIS_R), 0xE6BE78FF, 1.5f, 6);
                Path g = new Path(); for (Solver.Bld b : z.group) foot(g, b); stroke(c, g, 0xFFC88CFF, 2.5f, 0);
                aim(c, z.centre[0], z.centre[1], 0xFFFFFFFF); label(c, z.centre[0], z.centre[1] - 0.8, "ИНВИЗ " + (i + 1), 0xFFE2C6FF);
            }
            if (o.eq != null) {
                paint(c, cells(o.eq.edge, 0.14), 0xFFFFAA3C);
                stroke(c, circle(o.eq.centre[0], o.eq.centre[1], o.eq.R), 0xFFFFAA3C, 2.5f, 10);
                aim(c, o.eq.centre[0], o.eq.centre[1], 0xFFFFAA3C); label(c, o.eq.centre[0] + 1.1, o.eq.centre[1] + 1.1, "ТРЯС ±" + String.format("%.1f", o.eq.tol), 0xFFFFC27A);
            }
        }
        // defences
        boolean pick = mode == OverlayService.Mode.PICK;
        Path weak = new Path(), ok = new Path();
        for (Solver.Bld b : sc.B) if (b.def && b != T) foot(b.conf < 0.6 ? weak : ok, b);
        stroke(c, ok, pick ? 0xCCFFFFFF : 0x80FFFFFF, pick ? 1.5f : 1, 0); stroke(c, weak, 0xFFFFD54A, pick ? 2 : 1.2f, 0);
        if (pick) for (Solver.Bld b : sc.B) if (b.def) label(c, b.cx(), b.cy(), Solver.ru(b.name) + (b.conf < 0.6 ? " ?" : ""), b.conf < 0.6 ? 0xFFFFD54A : 0xFFFFFFFF);
        if (T != null) { Path t = new Path(); foot(t, T); paint(c, t, 0x59FF2828); stroke(c, t, 0xFFFF3B3B, 3, 0); label(c, T.cx(), T.cy(), Solver.ru(T.name), 0xFFFF6B6B); }
    }
}
