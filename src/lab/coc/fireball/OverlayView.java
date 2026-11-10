package lab.coc.fireball;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen overlay: defences (pick mode) and the plan. Shapes are built once per scene/plan in grid coordinates
 * (Geo) and mapped to the screen with one affine matrix per frame, so following the camera costs a few path transforms.
 */
final class OverlayView extends View {
    private final OverlayService svc; private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DashPathEffect dash6, dash8, dash10;
    private final int[] loc = new int[2];
    private final Matrix m = new Matrix(); private final float[] mv = new float[9], pt = new float[2];
    private final Path tmp = new Path();
    private OverlayService.Xf x; private OverlayService.Scene sc;
    private Geo geo;

    OverlayView(OverlayService s) {
        super(s); svc = s; dp = s.getResources().getDisplayMetrics().density;
        fill.setStyle(Paint.Style.FILL); line.setStyle(Paint.Style.STROKE); line.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER); text.setTextSize(11 * dp); text.setFakeBoldText(true);
        halo.set(text); halo.setStyle(Paint.Style.STROKE); halo.setStrokeWidth(3 * dp); halo.setColor(0xD8000000);
        dash6 = new DashPathEffect(new float[]{6 * dp, 3.6f * dp}, 0); dash8 = new DashPathEffect(new float[]{8 * dp, 4.8f * dp}, 0);
        dash10 = new DashPathEffect(new float[]{10 * dp, 6 * dp}, 0);
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

    // ---- grid-space geometry, rebuilt only when the scene or the chosen option changes ----
    private static final class Geo {
        OverlayService.Scene sc; Solver.Plan o; boolean pick;
        final Path[] area = {new Path(), new Path(), new Path(), new Path()};
        final List<Path> bind = new ArrayList<>(); final Path cleanX = new Path(), fb = new Path(), splash = new Path();
        final List<Path> zEdge = new ArrayList<>(), zCircle = new ArrayList<>(), zGroup = new ArrayList<>(); final List<Integer> zColor = new ArrayList<>();
        Path eqEdge, eqCircle; final Path ok = new Path(), weak = new Path(), target = new Path();
    }

    private static void quad(Path p, double X0, double Y0, double X1, double Y1) {
        p.moveTo((float) X0, (float) Y0); p.lineTo((float) X1, (float) Y0); p.lineTo((float) X1, (float) Y1); p.lineTo((float) X0, (float) Y1); p.close();
    }
    private static void foot(Path p, Solver.Bld b) { quad(p, b.x, b.y, b.x + b.w, b.y + b.w); }
    private static Path circle(double cx, double cy, double r) {
        Path p = new Path();
        for (int i = 0; i <= 64; i++) { float qx = (float) (cx + r * Math.cos(2 * Math.PI * i / 64)), qy = (float) (cy + r * Math.sin(2 * Math.PI * i / 64)); if (i == 0) p.moveTo(qx, qy); else p.lineTo(qx, qy); }
        return p;
    }
    private static Path cells(float[] pts, double size) {
        Path p = new Path(); double h = size / 2;
        for (int i = 0; i < pts.length; i += 3) quad(p, pts[i] - h, pts[i + 1] - h, pts[i] + h, pts[i + 1] + h);
        return p;
    }
    private static int marginColor(double m) { int rgb = m >= 0.75 ? 0x3DDC84 : m >= 0.25 ? 0xFFD54A : 0xFF5A5A; return 0xFF000000 | rgb; }

    private Geo build(OverlayService.Scene sc, Solver.Plan o, boolean pick) {
        Geo g = new Geo(); g.sc = sc; g.o = o; g.pick = pick; Solver.Bld T = sc.target;
        if (o != null) {
            double tcx = T.cx(), tcy = T.cy();
            double maxM = Math.max(0.3, Math.min(o.wardenTol, 3)), h = Solver.STEP / 2;
            for (int i = 0; i < o.area.length; i += 3) { int k = (int) Math.min(3, Math.floor(4 * Math.min(o.area[i + 2], maxM) / maxM)); quad(g.area[k], o.area[i] - h, o.area[i + 1] - h, o.area[i] + h, o.area[i + 1] + h); }
            double[] wp = o.wardenPt;
            for (Solver.Bld d : o.binding) {
                double dcx = d.cx(), dcy = d.cy(), mx = (dcx + tcx) / 2, my = (dcy + tcy) / 2, vx = -(dcy - tcy), vy = dcx - tcx, n = Math.hypot(vx, vy), ux = vx / n, uy = vy / n;
                double t = (wp[0] - mx) * ux + (wp[1] - my) * uy;
                Path seg = new Path(); seg.moveTo((float) (mx + ux * (t - 8)), (float) (my + uy * (t - 8))); seg.lineTo((float) (mx + ux * (t + 8)), (float) (my + uy * (t + 8))); g.bind.add(seg);
            }
            for (Solver.Bld b : o.C) {
                g.cleanX.moveTo((float) b.x, (float) b.y); g.cleanX.lineTo((float) (b.x + b.w), (float) (b.y + b.w));
                g.cleanX.moveTo((float) b.x, (float) (b.y + b.w)); g.cleanX.lineTo((float) (b.x + b.w), (float) b.y);
            }
            g.fb.set(circle(tcx, tcy, svc.getSharedPreferences("fb", 0).getFloat("fbR", 6)));
            for (Solver.Bld b : o.splash) if (b != T) foot(g.splash, b);
            for (Solver.Zone z : o.zones) {
                g.zEdge.add(cells(z.edge, 0.14)); g.zColor.add(marginColor(z.margin)); g.zCircle.add(circle(z.centre[0], z.centre[1], Solver.INVIS_R));
                Path gp = new Path(); for (Solver.Bld b : z.group) foot(gp, b); g.zGroup.add(gp);
            }
            if (o.eq != null) { g.eqEdge = cells(o.eq.edge, 0.14); g.eqCircle = circle(o.eq.centre[0], o.eq.centre[1], o.eq.R); }
        }
        for (Solver.Bld b : sc.B) if (b.def && b != T) foot(b.conf < 0.6 ? g.weak : g.ok, b);
        if (T != null) foot(g.target, T);
        return g;
    }

    // ---- drawing through the matrix ----
    private float[] scr(double X, double Y) { pt[0] = (float) X; pt[1] = (float) Y; m.mapPoints(pt); return pt; }
    private void stroke(Canvas c, Path p, int color, float w, DashPathEffect dash) {
        p.transform(m, tmp); line.setColor(color); line.setStrokeWidth(w * dp); line.setPathEffect(dash); c.drawPath(tmp, line); line.setPathEffect(null);
    }
    private void paint(Canvas c, Path p, int color) { p.transform(m, tmp); fill.setColor(color); c.drawPath(tmp, fill); }
    private void label(Canvas c, double X, double Y, String t, int color) {
        float[] q = scr(X, Y); c.drawText(t, q[0], q[1], halo); text.setColor(color); c.drawText(t, q[0], q[1], text);
    }
    private void aim(Canvas c, double X, double Y, int color) {
        float[] q = scr(X, Y); float qx = q[0], qy = q[1], r = 9 * dp;
        for (int k = 0; k < 2; k++) {
            line.setColor(k == 0 ? 0xCC000000 : color); line.setStrokeWidth((k == 0 ? 4.5f : 2f) * dp);
            c.drawCircle(qx, qy, r, line); c.drawLine(qx - 1.6f * r, qy, qx + 1.6f * r, qy, line); c.drawLine(qx, qy - 1.6f * r, qx, qy + 1.6f * r, line);
        }
    }

    @Override protected void onDraw(Canvas c) {
        x = svc.xf; sc = svc.scene; OverlayService.Mode mode = svc.mode;
        if (x == null || sc == null || sc.B.isEmpty() || mode == OverlayService.Mode.IDLE) return;
        getLocationOnScreen(loc);
        // grid (X, Y) -> view: vx = a(X - Y) + tx, vy = b(X + Y) + ty
        double tx = x.cx - loc[0] + x.a * (-sc.ox + sc.oy - x.du), ty = x.cy - loc[1] + x.b * (-sc.ox - sc.oy - x.ds);
        mv[0] = (float) x.a; mv[1] = (float) -x.a; mv[2] = (float) tx; mv[3] = (float) x.b; mv[4] = (float) x.b; mv[5] = (float) ty; mv[6] = 0; mv[7] = 0; mv[8] = 1;
        m.setValues(mv);
        boolean pick = mode == OverlayService.Mode.PICK;
        Solver.Plan o = mode == OverlayService.Mode.PLAN ? sc.plan() : null; Solver.Bld T = sc.target;
        Geo g = geo; if (g == null || g.sc != sc || g.o != o || g.pick != pick) geo = g = build(sc, o, pick);
        if (o != null) {
            for (int k = 0; k < 4; k++) paint(c, g.area[k], ((int) (255 * (0.25 + 0.18 * k)) << 24) | 0x28BEFF);
            for (Path p : g.bind) stroke(c, p, 0xFF78D7FF, 2, dash8);
            double[] wp = o.wardenPt;
            aim(c, wp[0], wp[1], 0xFF8FE3FF); label(c, wp[0] + 1.1, wp[1] + 1.1, "ДЕД ±" + String.format("%.1f", o.wardenTol), 0xFF8FE3FF);
            stroke(c, g.cleanX, 0xFFFF1744, 4, null);
            paint(c, g.fb, 0x1AFF8C00); stroke(c, g.fb, 0xF2FF8C00, 2, null); paint(c, g.splash, 0x40FF8C00);
            for (int i = 0; i < o.zones.size(); i++) {
                Solver.Zone z = o.zones.get(i);
                paint(c, g.zEdge.get(i), g.zColor.get(i)); stroke(c, g.zCircle.get(i), 0xE6BE78FF, 1.5f, dash6); stroke(c, g.zGroup.get(i), 0xFFC88CFF, 2.5f, null);
                aim(c, z.centre[0], z.centre[1], 0xFFFFFFFF); label(c, z.centre[0], z.centre[1] - 0.8, "ИНВИЗ " + (i + 1), 0xFFE2C6FF);
            }
            if (o.eq != null) {
                paint(c, g.eqEdge, 0xFFFFAA3C); stroke(c, g.eqCircle, 0xFFFFAA3C, 2.5f, dash10);
                aim(c, o.eq.centre[0], o.eq.centre[1], 0xFFFFAA3C); label(c, o.eq.centre[0] + 1.1, o.eq.centre[1] + 1.1, "ТРЯС ±" + String.format("%.1f", o.eq.tol), 0xFFFFC27A);
            }
        }
        stroke(c, g.ok, pick ? 0xCCFFFFFF : 0x80FFFFFF, pick ? 1.5f : 1, null); stroke(c, g.weak, 0xFFFFD54A, pick ? 2 : 1.2f, null);
        if (pick) for (Solver.Bld b : sc.B) if (b.def) label(c, b.cx(), b.cy(), Solver.ru(b.name) + (b.conf < 0.6 ? " ?" : ""), b.conf < 0.6 ? 0xFFFFD54A : 0xFFFFFFFF);
        if (T != null) { paint(c, g.target, 0x59FF2828); stroke(c, g.target, 0xFFFF3B3B, 3, null); label(c, T.cx(), T.cy(), Solver.ru(T.name), 0xFFFF6B6B); }
    }
}
