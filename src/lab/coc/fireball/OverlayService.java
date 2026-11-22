package lab.coc.fireball;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Screen capture (MediaProjection) -> YOLO -> lattice -> base in map tiles; the user taps the target on a full-screen overlay;
 * the plan is drawn over the game and follows the camera: every new frame is detected again, its lattice fitted and aligned
 * to the scanned base by the integer (u, s) offset with most same-type matches.
 */
public final class OverlayService extends Service {
    static final String ACTION_STOP = "lab.coc.fireball.STOP";
    enum Mode { IDLE, BUSY, SURVEY, PICK, PLAN }

    /** Screen transform: grid -> capture px: u = (X-ox)-(Y-oy)-du, s = (X-ox)+(Y-oy)-ds; px = cx + a*u, py = cy + b*s. */
    static final class Xf { double a, b, cx, cy; int du, ds; double capW, capH; long time; }
    /** Everything the overlay draws; replaced as a whole. */
    static final class Scene {
        List<Solver.Bld> B = new ArrayList<>(); int ox, oy; Solver.Bld target; Solver.Result res; int opt;
        /** Clan Castle chosen by the user: the fireball cannot target it, so `target` is a defence whose splash covers it. */
        Solver.Bld via;
        Scene with(List<Solver.Bld> nb) { Scene n = new Scene(); n.B = nb; n.ox = ox; n.oy = oy; return n; }
        Solver.Plan plan() { return res == null || res.options.isEmpty() ? null : res.options.get(Math.min(opt, res.options.size() - 1)); }
    }

    volatile Mode mode = Mode.IDLE; volatile Scene scene = new Scene(); volatile Xf xf;
    /** Recognition (YOLO) while a plan is shown: 0 always, 1 only while the camera moves, 2 never (the light tracker still follows). */
    volatile int aiMode; private volatile long lastMove; private volatile int alignMiss;
    /** Building names drawn on the overlay. */
    volatile boolean names = true;
    /** Pick mode, "Правка": a tap selects a building (or an empty tile) and the panel lists types for it. */
    volatile boolean editing; volatile Solver.Bld selected; volatile double[] selectedTile;
    private Map<String, Integer> sizes = new HashMap<>();
    private double scale = -1, scanA; private Map<String, Set<Long>> index; private Map<String, Integer> counts;
    private final Tracker tracker = new Tracker(); private volatile Tracker.Ref ref; private float[] scanGrey; private volatile long lastFix;
    private Handler track; private HandlerThread trackT;

    private WindowManager wm; private OverlayView overlay; private WindowManager.LayoutParams overlayLp, bubbleLp;
    private LinearLayout bubble; private TextView status; private LinearLayout buttons;
    private final Handler ui = new Handler(Looper.getMainLooper()); private Handler work, capture;
    private HandlerThread workT, captureT;
    private MediaProjection projection; private VirtualDisplay display; private ImageReader reader; private Image held;
    private final Object lock = new Object(); private volatile long lastTs; private int capW, capH, dpi;
    private Detector detector; private SharedPreferences prefs;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        startForegroundCompat();
        if (projection != null || intent == null) return START_NOT_STICKY;
        prefs = getSharedPreferences("fb", MODE_PRIVATE); aiMode = prefs.getInt("ai", 0); names = prefs.getBoolean("names", true);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(intent.getIntExtra("code", 0), (Intent) intent.getParcelableExtra("data"));
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }
        workT = new HandlerThread("fb-work"); workT.start(); work = new Handler(workT.getLooper());
        captureT = new HandlerThread("fb-capture"); captureT.start(); capture = new Handler(captureT.getLooper());
        trackT = new HandlerThread("fb-track"); trackT.start(); track = new Handler(trackT.getLooper());
        projection.registerCallback(new MediaProjection.Callback() { @Override public void onStop() { ui.post(OverlayService.this::stopSelf); } }, ui);
        int[] wh = screenSize(); capW = wh[0]; capH = wh[1];
        reader = newReader(capW, capH);
        display = projection.createVirtualDisplay("fireball", capW, capH, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, capture);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        addWindows();
        setStatus("Загружаю модель…"); mode = Mode.BUSY; refreshButtons();
        work.post(() -> {
            try { detector = new Detector(this); sizes = detector.size; ui.post(() -> { mode = Mode.IDLE; setStatus("Открой базу противника, дождись загрузки и нажми «Скан»"); refreshButtons(); }); }
            catch (Throwable e) { ui.post(() -> setStatus("Ошибка модели: " + e)); }
        });
        return START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("fb", "Fireball overlay", NotificationManager.IMPORTANCE_LOW));
            nb = new Notification.Builder(this, "fb");
        } else nb = new Notification.Builder(this);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification n = nb.setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("Fireball overlay").setContentText("Захват экрана включён")
                .addAction(new Notification.Action.Builder(null, "Стоп", stop).build()).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(1, n);
    }

    @Override public void onDestroy() {
        mode = Mode.IDLE;
        try { if (overlay != null) wm.removeView(overlay); } catch (Exception ignored) { }
        try { if (bubble != null) wm.removeView(bubble); } catch (Exception ignored) { }
        if (display != null) display.release();
        if (projection != null) projection.stop();
        synchronized (lock) { if (held != null) held.close(); held = null; if (reader != null) reader.close(); }
        if (workT != null) workT.quitSafely();
        if (captureT != null) captureT.quitSafely();
        if (trackT != null) trackT.quitSafely();
        super.onDestroy();
    }

    // ---------------- capture ----------------
    private int[] screenSize() {
        WindowManager w = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics m = new DisplayMetrics(); w.getDefaultDisplay().getRealMetrics(m); dpi = m.densityDpi;
        return new int[]{m.widthPixels, m.heightPixels};
    }

    private ImageReader newReader(int w, int h) {
        ImageReader r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        r.setOnImageAvailableListener(rd -> {
            synchronized (lock) {
                if (rd != reader) return;
                Image im; try { im = rd.acquireLatestImage(); } catch (Exception e) { return; }
                if (im == null) return;
                if (held != null) held.close();
                held = im;
            }
        }, capture);
        return r;
    }

    /** Follow rotation: the virtual display must match the screen or the game is letterboxed into it. */
    private void ensureSize() {
        int[] wh = screenSize();
        if (wh[0] == capW && wh[1] == capH) return;
        synchronized (lock) {
            ImageReader old = reader; capW = wh[0]; capH = wh[1];
            if (held != null) { held.close(); held = null; }
            reader = newReader(capW, capH);
            display.setSurface(reader.getSurface()); display.resize(capW, capH, dpi);
            old.close();
        }
    }

    /** Latest frame newer than `after` (ms), or null; `grey` (optional) gets the tracker's small grey copy of the same frame. */
    private Bitmap grab(long after) { return grab(after, null); }
    private Bitmap grab(long after, float[] grey) {
        synchronized (lock) {
            if (held == null || held.getTimestamp() / 1_000_000 < after) return null;
            lastTs = held.getTimestamp() / 1_000_000;
            if (grey != null) greyOf(held, grey);
            Image.Plane p = held.getPlanes()[0]; ByteBuffer buf = p.getBuffer(); buf.rewind();
            int w = held.getWidth(), h = held.getHeight(), rowPx = p.getRowStride() / p.getPixelStride();
            Bitmap full = Bitmap.createBitmap(rowPx, h, Bitmap.Config.ARGB_8888); full.copyPixelsFromBuffer(buf);
            if (rowPx == w) return full;
            Bitmap crop = Bitmap.createBitmap(full, 0, 0, w, h); full.recycle(); return crop;
        }
    }

    /** Small grey frame for the tracker (no bitmap copy): 2x2 samples per cell of the tracked screen band. Ts (ms) or -1. */
    private long grey(long after, float[] out) {
        synchronized (lock) {
            if (held == null || held.getTimestamp() / 1_000_000 < after) return -1;
            greyOf(held, out); return held.getTimestamp() / 1_000_000;
        }
    }
    private static void greyOf(Image im, float[] out) {
        Image.Plane p = im.getPlanes()[0]; ByteBuffer buf = p.getBuffer(); int rs = p.getRowStride(), ps = p.getPixelStride(), w = im.getWidth(), h = im.getHeight();
        double x0 = Tracker.X0 * w, y0 = Tracker.Y0 * h, fx = (Tracker.X1 - Tracker.X0) * w / Tracker.W, fy = (Tracker.Y1 - Tracker.Y0) * h / Tracker.H;
        int[] xs = new int[Tracker.W * 2];
        for (int i = 0; i < Tracker.W; i++) { xs[2 * i] = (int) (x0 + (i + 0.25) * fx) * ps; xs[2 * i + 1] = (int) (x0 + (i + 0.75) * fx) * ps; }
        for (int j = 0; j < Tracker.H; j++) {
            int r0 = (int) (y0 + (j + 0.25) * fy) * rs, r1 = (int) (y0 + (j + 0.75) * fy) * rs;
            for (int i = 0; i < Tracker.W; i++) {
                int s = 0;
                for (int q = 0; q < 4; q++) {
                    int o = ((q & 1) == 0 ? r0 : r1) + xs[2 * i + (q >> 1)];
                    s += 77 * (buf.get(o) & 255) + 150 * (buf.get(o + 1) & 255) + 29 * (buf.get(o + 2) & 255);
                }
                out[j * Tracker.W + i] = s / 1024f;
            }
        }
    }

    private Bitmap waitFrame(long after, long timeoutMs, float[] grey) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) { Bitmap b = grab(after, grey); if (b != null) return b; Thread.sleep(40); }
        return grab(0, grey);
    }

    // ---------------- scan / pick / plan ----------------
    private void scan() {
        if (detector == null || mode == Mode.BUSY) return;
        trackGen++; setPick(false);  // "Скан новый" from the pick step: the overlay must let touches through again
        mode = Mode.BUSY; editing = false; selected = null; selectedTile = null; setStatus("Сканирую… не двигай камеру"); refreshButtons();
        overlay.setVisibility(View.INVISIBLE); bubble.setVisibility(View.INVISIBLE);
        final long hidden = android.os.SystemClock.uptimeMillis();
        work.postDelayed(() -> {
            String err = null; Scene sc = null; Xf x = null; long t0 = System.currentTimeMillis();
            try {
                ensureSize();
                float[] g = new float[Tracker.N];
                Bitmap img = waitFrame(hidden + 120, 1500, g); scanGrey = g;
                ui.post(() -> { overlay.setVisibility(View.VISIBLE); bubble.setVisibility(View.VISIBLE); });
                if (img == null) throw new Exception("нет кадра с экрана");
                double[] used = new double[1];
                List<Detector.Det> d = detector.detect(img, -1, used); img.recycle();
                if (d.size() < 8) throw new Exception("почти ничего не найдено (" + d.size() + "). Открой базу целиком");
                Detector.Lattice L = detector.fit(d); scale = used[0]; scanA = L.a;
                sc = buildScene(d, L); x = new Xf(); x.a = L.a; x.b = L.b; x.cx = L.cx; x.cy = L.cy; x.capW = capW; x.capH = capH; x.time = System.currentTimeMillis();
                buildIndex(sc);
            } catch (Throwable e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
            final String fe = err; final Scene fs = sc; final Xf fx = x; final long ms = System.currentTimeMillis() - t0;
            ui.post(() -> {
                overlay.setVisibility(View.VISIBLE); bubble.setVisibility(View.VISIBLE);
                if (fe != null) { mode = Mode.IDLE; setStatus("Ошибка: " + fe); refreshButtons(); return; }
                scene = fs; xf = fx; ref = null; seen.clear(); mode = Mode.SURVEY;
                setStatus(surveyText(fs) + " (" + ms + " мс).\n" + SURVEY_HINT); refreshButtons(); overlay.invalidate();
                startTracking();
            });
        }, 60);
    }

    static final String SURVEY_HINT = "Подожди, пока база прогрузится, затем води камерой по базе, пока все защиты не получат рамку. Потом «Готово»";

    /** "Скан +": keep every building found so far and survey again; new buildings are only added. */
    private void improve() {
        Scene c = scene;
        if (detector == null || c == null || c.B.isEmpty() || xf == null || ref == null) { scan(); return; }
        trackGen++; setPick(false); editing = false; selected = null; selectedTile = null;
        Scene ns = c.with(c.B); buildIndex(ns); scene = ns; seen.clear(); mode = Mode.SURVEY;
        setStatus(surveyText(ns) + "\nДобавляю к прежнему скану: веди камеру по пропущенным местам, затем «Готово»"); refreshButtons(); overlay.invalidate();
        startTracking();
    }

    private Scene buildScene(List<Detector.Det> d, Detector.Lattice L) {
        List<Solver.Bld> all = new ArrayList<>();
        for (int j = 0; j < d.size(); j++) {
            Detector.Det x = d.get(j); int u = L.u[j], s = L.s[j];
            if (x.name.startsWith("Hero ")) continue;
            double xc = (u + s) / 2.0, yc = (s - u) / 2.0;
            all.add(new Solver.Bld(x.name, x.w, xc - x.w / 2.0, yc - x.w / 2.0, x.conf, u, s));
        }
        all.sort((p, q) -> Double.compare(q.conf, p.conf));
        List<Solver.Bld> kept = new ArrayList<>();
        for (Solver.Bld b : all) {
            boolean dup = false;
            for (Solver.Bld k : kept) {
                double ov = Math.max(0, Math.min(k.x + k.w, b.x + b.w) - Math.max(k.x, b.x)) * Math.max(0, Math.min(k.y + k.w, b.y + b.w) - Math.max(k.y, b.y));
                if (ov > 0.5 * Math.pow(Math.min(k.w, b.w), 2)) { dup = true; break; }
            }
            if (!dup) kept.add(b);
        }
        double x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9;
        for (Solver.Bld b : kept) { x0 = Math.min(x0, b.x); x1 = Math.max(x1, b.x + b.w); y0 = Math.min(y0, b.y); y1 = Math.max(y1, b.y + b.w); }
        Scene sc = new Scene(); sc.ox = (int) Math.round(Solver.MAP / 2.0 - (x0 + x1) / 2); sc.oy = (int) Math.round(Solver.MAP / 2.0 - (y0 + y1) / 2);
        for (Solver.Bld b : kept) { b.x += sc.ox; b.y += sc.oy; }
        sc.B = kept; return sc;
    }

    private void buildIndex(Scene sc) {
        Map<String, Set<Long>> idx = new HashMap<>(); Map<String, Integer> cnt = new HashMap<>();
        for (Solver.Bld b : sc.B) { if (b.manual) continue; idx.computeIfAbsent(b.name, k -> new HashSet<>()).add(key(b.u, b.s)); cnt.merge(b.name, 1, Integer::sum); }
        index = idx; counts = cnt;
    }
    private static long key(int u, int s) { return ((long) u << 32) | (s & 0xffffffffL); }

    void pickAt(float vx, float vy) {
        Scene sc = scene; Xf x = xf; if (sc == null || x == null || mode != Mode.PICK) return;
        double[] g = overlay.toGrid(vx, vy, x, sc);
        if (editing) { selectAt(sc, g); return; }
        Solver.Bld best = null; double bd = 1e9;
        for (Solver.Bld b : sc.B) {
            if (!b.def && !b.castle()) continue;
            boolean inside = g[0] >= b.x && g[0] <= b.x + b.w && g[1] >= b.y && g[1] <= b.y + b.w;
            double d = Math.hypot(g[0] - b.cx(), g[1] - b.cy()) - (inside ? 100 : 0);
            if (d < bd) { bd = d; best = b; }
        }
        if (best == null) return;
        if (best.castle()) { planVia(sc, best); return; }
        final Solver.Bld T = best; mode = Mode.BUSY; setPick(false);
        setStatus("Считаю план для цели «" + Solver.ru(T.name) + "»…"); refreshButtons();
        work.post(() -> {
            Solver.Result r = solver(sc.B, T).solve();
            ui.post(() -> showPlan(sc, T, r, null));
        });
    }

    private void showPlan(Scene sc, Solver.Bld T, Solver.Result r, Solver.Bld via) {
        Scene ns = sc.with(sc.B); ns.target = T; ns.res = r; ns.opt = 0; ns.via = via; scene = ns;
        mode = Mode.PLAN; showPlanStatus(); refreshButtons(); overlay.invalidate();
        startTracking();
    }

    /** Clan Castle: the fireball only targets defences, so try the defences whose splash reaches the castle and keep the best plan. */
    private void planVia(Scene sc, Solver.Bld cc) {
        float fbR = prefs.getFloat("fbR", 6);
        List<Solver.Bld> cand = new ArrayList<>();
        for (Solver.Bld b : sc.B) if (b.def && Solver.distToFoot(b.cx(), b.cy(), cc) <= fbR) cand.add(b);
        if (cand.isEmpty()) { setStatus("Рядом с замком нет защиты, чей файер его заденет. Выбери другую цель"); return; }
        mode = Mode.BUSY; setPick(false); setStatus("Замок клана: ищу защиту, через которую файер его заденет…"); refreshButtons();
        bestTarget(sc, cand, "Замок: пробую", (T, R) -> {
            if (T == null) { mode = Mode.PICK; setPick(true); setStatus("Через защиты рядом с замком надёжного броска нет. Уменьши запас или выбери цель вручную"); refreshButtons(); return; }
            showPlan(sc, T, R, cc);
        });
    }

    // ---------------- manual fixes («Правка») ----------------
    private void selectAt(Scene sc, double[] g) {
        Solver.Bld hit = null;
        for (Solver.Bld b : sc.B) if (g[0] >= b.x && g[0] <= b.x + b.w && g[1] >= b.y && g[1] <= b.y + b.w && (hit == null || b.w < hit.w)) hit = b;
        selected = hit; selectedTile = hit == null ? new double[]{Math.floor(g[0]), Math.floor(g[1])} : null;
        setStatus(hit == null ? "Пустое место: выбери, что здесь стоит" : "Выбрано: " + Solver.ru(hit.name) + ". Выбери верный тип или «Удалить»");
        showEditList(); overlay.invalidate();
    }

    /** Applies the type picked in the panel: null = delete; a new building is centred on the tapped tile. */
    private void applyEdit(String type) {
        Scene sc = scene; Solver.Bld sel = selected; double[] tile = selectedTile;
        List<Solver.Bld> nb = new ArrayList<>(sc.B);
        if (sel != null) {
            nb.remove(sel);
            if (type != null) {
                int w = sizes.containsKey(type) ? sizes.get(type) : sel.w;
                nb.add(manualBld(sc, type, w, sel.cx(), sel.cy()));
            }
        } else if (tile != null && type != null) {
            int w = sizes.containsKey(type) ? sizes.get(type) : 3;
            nb.add(manualBld(sc, type, w, tile[0] + 0.5, tile[1] + 0.5));
        }
        Scene ns = sc.with(nb); buildIndex(ns); scene = ns; selected = null; selectedTile = null;
        hideEditList(); setStatus(surveyText(ns) + ". Тапни следующее здание для правки или выключи «Правка»"); refreshButtons(); overlay.invalidate();
    }

    /** Building placed by hand, footprint snapped to whole tiles around (cx, cy). */
    private static Solver.Bld manualBld(Scene sc, String type, int w, double cx, double cy) {
        double x = Math.round(cx - w / 2.0), y = Math.round(cy - w / 2.0);
        double ccx = x + w / 2.0 - sc.ox, ccy = y + w / 2.0 - sc.oy;
        Solver.Bld b = new Solver.Bld(type, w, x, y, 1, (int) Math.round(ccx - ccy), (int) Math.round(ccx + ccy));
        b.manual = true; return b;
    }

    private void showPlanStatus() {
        Scene sc = scene; Solver.Plan p = sc.plan();
        if (p == null) { setStatus(Solver.ru(sc.target.name) + ": " + (sc.res.error != null ? sc.res.error : "решения нет")); return; }
        StringBuilder t = new StringBuilder();
        if (sc.via != null) t.append("Замок клана — файер в «").append(Solver.ru(sc.target.name)).append("», всплеск заденет замок\n");
        t.append("Вар ").append(sc.opt + 1).append('/').append(sc.res.options.size()).append(": ")
         .append(p.k == 0 ? "без инвизов" : p.k + " инвиз" + (p.k < 5 ? "а" : "ов"))
         .append(" · дед ±").append(String.format("%.1f", p.wardenTol));
        if (p.eq != null) t.append(" · тряс ±").append(String.format("%.1f", p.eq.tol));
        if (!p.C.isEmpty()) t.append(" · шары: ").append(p.C.size());
        int sd = 0; for (Solver.Bld b : p.splash) if (b.def && b != sc.target) sd++;
        t.append(" · во всплеске защит: ").append(sd);
        t.append(" · ").append(sc.res.ms).append(" мс");
        for (int i = 0; i < p.zones.size(); i++) {
            t.append("\nИнвиз ").append(i + 1).append(": ");
            List<Solver.Bld> g = p.zones.get(i).group;
            for (int j = 0; j < g.size(); j++) t.append(j > 0 ? ", " : "").append(Solver.ru(g.get(j).name));
        }
        t.append("\nПорядок: ").append(p.C.isEmpty() ? "" : "шары → ").append(p.eq != null ? "тряс → " : "")
         .append("Хранитель → ").append(p.zones.isEmpty() ? "" : "инвизы → ").append("сразу способность");
        setStatus(t.toString());
    }

    // ---------------- survey: grow the base while the camera pans ----------------
    /** Sightings of not-yet-known buildings, key name@u,s (scan lattice frame); added after 2 sightings. */
    private final Map<String, int[]> seen = new HashMap<>();

    private static String surveyText(Scene sc) {
        int nd = 0; for (Solver.Bld b : sc.B) if (b.def) nd++;
        return "Зданий: " + sc.B.size() + ", защит: " + nd;
    }

    private void survey(List<Detector.Det> d, Detector.Lattice L, Xf x) {
        Scene sc = scene; List<Solver.Bld> add = new ArrayList<>(); Map<Solver.Bld, String> relabel = new HashMap<>();
        for (int j = 0; j < d.size(); j++) {
            Detector.Det e = d.get(j); int u = L.u[j] + x.du, s = L.s[j] + x.ds;
            if (e.name.startsWith("Hero ")) continue;
            double bx = (u + s) / 2.0 - e.w / 2.0 + sc.ox, by = (s - u) / 2.0 - e.w / 2.0 + sc.oy;
            Solver.Bld known = same(sc.B, bx, by, e.w);
            if (known != null) {
                // Every sighting votes for a type; the label follows the clear majority (a hero in front or one bad frame no longer wins).
                if (known.manual) continue;
                known.votes.merge(e.name, e.conf, Double::sum); known.seen++;
                String top = known.name; double tv = known.votes.getOrDefault(top, 0.0);
                for (Map.Entry<String, Double> v : known.votes.entrySet()) if (v.getValue() > tv) { tv = v.getValue(); top = v.getKey(); }
                if (!top.equals(known.name) && tv > known.votes.getOrDefault(known.name, 0.0) + 0.5) relabel.put(known, top);
                continue;
            }
            if (overlaps(sc.B, bx, by, e.w) || overlaps(add, bx, by, e.w)) continue;
            String k = e.name + "@" + u + "," + s; int[] c = seen.get(k);
            if (c == null) { seen.put(k, new int[]{1}); continue; }
            if (++c[0] >= 2) add.add(new Solver.Bld(e.name, e.w, bx, by, e.conf, u, s));
        }
        if (add.isEmpty() && relabel.isEmpty()) return;
        List<Solver.Bld> nb = new ArrayList<>();
        for (Solver.Bld b : sc.B) { String r = relabel.get(b); nb.add(r == null ? b : b.copy(r, 0, 0)); }
        nb.addAll(add);
        Scene ns = sc.with(nb); buildIndex(ns); scene = ns;
        ui.post(() -> { if (mode == Mode.SURVEY) { setStatus(surveyText(ns) + "\nВеди камеру дальше или жми «Готово»"); overlay.invalidate(); } });
    }

    /** Known building of the same size at (almost) the same footprint. */
    private static Solver.Bld same(List<Solver.Bld> B, double x, double y, int w) {
        for (Solver.Bld k : B) if (k.w == w && Math.abs(k.x - x) < 0.6 && Math.abs(k.y - y) < 0.6) return k;
        return null;
    }

    private static boolean overlaps(List<Solver.Bld> B, double x, double y, int w) {
        for (Solver.Bld k : B) {
            double ov = Math.max(0, Math.min(k.x + k.w, x + w) - Math.max(k.x, x)) * Math.max(0, Math.min(k.y + k.w, y + w) - Math.max(k.y, y));
            if (ov > 0.5 * Math.pow(Math.min(k.w, w), 2)) return true;
        }
        return false;
    }

    /** End of survey: re-centre the whole base on the 50x50 map, then pick the target. */
    private void finishSurvey() {
        trackGen++; Scene sc = scene;
        double x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9;
        for (Solver.Bld b : sc.B) { x0 = Math.min(x0, b.x); x1 = Math.max(x1, b.x + b.w); y0 = Math.min(y0, b.y); y1 = Math.max(y1, b.y + b.w); }
        int dx = (int) Math.round(Solver.MAP / 2.0 - (x0 + x1) / 2), dy = (int) Math.round(Solver.MAP / 2.0 - (y0 + y1) / 2);
        List<Solver.Bld> nb = new ArrayList<>();
        for (Solver.Bld b : sc.B) nb.add(b.copy(b.name, dx, dy));
        Scene ns = new Scene(); ns.B = nb; ns.ox = sc.ox + dx; ns.oy = sc.oy + dy; buildIndex(ns); scene = ns;
        mode = Mode.PICK; setPick(true);
        setStatus(surveyText(ns) + ". Тапни по защите или замку — это цель. «Авто» — подберу лучшую. «Правка» — исправить здание"); refreshButtons(); overlay.invalidate();
    }

    // ---------------- auto target ----------------
    /** Splash value of a target: defences in the fireball radius count 1, other buildings 0.2. */
    static double splashValue(List<Solver.Bld> B, Solver.Bld T, double R) {
        double v = 0, cx = T.cx(), cy = T.cy();
        for (Solver.Bld b : B) if (b != T && !b.name.equals("Wall") && Solver.distToFoot(cx, cy, b) <= R) v += b.def ? 1 : 0.2;
        return v;
    }

    private void autoTarget() {
        Scene sc = scene; if (sc == null || mode != Mode.PICK) return;
        mode = Mode.BUSY; setPick(false); editing = false; hideEditList(); setStatus("Подбираю цель…"); refreshButtons();
        List<Solver.Bld> cand = new ArrayList<>(); for (Solver.Bld b : sc.B) if (b.def) cand.add(b);
        bestTarget(sc, cand, "Подбираю цель…", (T, R) -> {
            if (T == null) { mode = Mode.PICK; setPick(true); setStatus("Надёжного броска не нашёл. Уменьши запас или выбери цель вручную"); refreshButtons(); return; }
            showPlan(sc, T, R, null);
        });
    }

    interface Picked { void done(Solver.Bld target, Solver.Result result); }

    /** Up to 10 candidates by splash value, each solved; the best plan (splash, few invis/balloons, safe margin) goes to `then` on the UI thread. */
    private void bestTarget(Scene sc, List<Solver.Bld> cand0, String label, Picked then) {
        work.post(() -> {
            float fbR = prefs.getFloat("fbR", 6);
            List<Solver.Bld> cand = new ArrayList<>(cand0);
            final Map<Solver.Bld, Double> val = new HashMap<>(); for (Solver.Bld b : cand) val.put(b, splashValue(sc.B, b, fbR));
            cand.sort((p, q) -> Double.compare(val.get(q), val.get(p)));
            Solver.Bld bestT = null; Solver.Result bestR = null; double bestS = -1e9; int tried = 0, n = Math.min(10, cand.size());
            for (Solver.Bld T : cand) {
                if (tried >= n) break; tried++;
                final int nT = tried; ui.post(() -> setStatus(label + " " + nT + "/" + n));
                Solver.Result r = solver(sc.B, T).solve();
                if (r.options.isEmpty()) continue;
                Solver.Plan p = r.options.get(0);
                double score = val.get(T) - 0.7 * p.k - 0.3 * p.C.size() + 0.5 * Math.min(p.minTol, 1.5);
                if (score > bestS) { bestS = score; bestT = T; bestR = r; }
            }
            final Solver.Bld T = bestT; final Solver.Result R = bestR;
            ui.post(() -> then.done(T, R));
        });
    }

    private Solver solver(List<Solver.Bld> B, Solver.Bld T) {
        return new Solver(B, T, prefs.getFloat("fbR", 6), prefs.getFloat("eqR", 4.7f), prefs.getBoolean("cleanup", false), prefs.getBoolean("outside", true))
                .margin(prefs.getFloat("wmin", 0.5f));
    }

    // ---------------- tracking ----------------
    // Two loops: "fb-track" follows the camera every frame by phase correlation against a reference frame with a known
    // transform (~10-30 ms); "fb-work" keeps re-detecting with YOLO and, on a good match, makes that frame the new reference
    // (removes drift and fixes zoom). Large moves re-base the reference on the current frame.
    private static final double[] SCALES = {0.97, 1, 1.03}, WIDE = {0.8, 0.88, 0.95, 1, 1.05, 1.13, 1.25};
    private volatile int trackGen;
    private void startTracking() {
        final int gen = ++trackGen;
        Xf x0 = xf; float[] g0 = scanGrey;
        if (ref == null && x0 != null && g0 != null) ref = Tracker.makeRef(g0, x0);
        track.post(new Runnable() {
            long last = 0; final float[] g = new float[Tracker.N];
            @Override public void run() {
                if (gen != trackGen || (mode != Mode.PLAN && mode != Mode.SURVEY)) return;
                long ts = grey(last + 1, g);
                Tracker.Ref r = ref;
                if (ts < 0 || r == null) { track.postDelayed(this, 6); return; }
                last = ts;
                try {
                    Xf base = (Xf) r.xf;
                    if (base.capW != capW || base.capH != capH) { ref = null; track.postDelayed(this, 50); return; }
                    Tracker.Est e = tracker.match(r, g, r.sc, SCALES);
                    if (e.peak < 0.12) e = tracker.match(r, g, r.sc, WIDE);  // fast pinch zoom: search a wider scale range
                    if (e.peak >= 0.12) {
                        Xf prev = xf;
                        r.sc = e.sc; Xf x = derive(base, e); xf = x; lastFix = System.currentTimeMillis();
                        if (prev == null || Math.abs(x.cx - prev.cx) + Math.abs(x.cy - prev.cy) > 2 || Math.abs(x.a / prev.a - 1) > 0.004) lastMove = lastFix;
                        if ((Math.abs(e.tx) > 0.2 * Tracker.W || Math.abs(e.ty) > 0.2 * Tracker.H || Math.abs(e.sc - 1) > 0.1) && e.peak >= 0.25 && ref == r)
                            ref = Tracker.makeRef(g.clone(), x);
                        ui.post(OverlayService.this::onFix);
                    } else { lastMove = System.currentTimeMillis(); if (lastMove - lastFix > 400) ui.post(() -> setLag(false)); }
                } catch (Throwable ex) { ui.post(() -> setStatus("Трекинг: " + ex)); }
                track.post(this);
            }
        });
        work.post(new Runnable() {
            long last = 0; final float[] g = new float[Tracker.N];
            @Override public void run() {
                if (gen != trackGen || (mode != Mode.PLAN && mode != Mode.SURVEY)) return;
                // Recognition setting (survey always recognises): never, or only while the camera moved in the last 1.5 s.
                if (mode == Mode.PLAN && (aiMode == 2 || (aiMode == 1 && System.currentTimeMillis() - lastMove > 1500))) { work.postDelayed(this, 200); return; }
                try {
                    ensureSize();
                    Bitmap img = grab(last + 1, g);
                    if (img == null) { work.postDelayed(this, 30); return; }
                    last = lastTs;
                    Xf cur = xf; double k = scale;
                    if (cur != null && scanA > 0) k = Math.max(0.4, Math.min(1.2, scale * scanA / cur.a));
                    boolean probe = alignMiss >= 2; if (probe) k = -1;  // lost after a zoom: probe the scale again from the frame itself
                    double[] used = new double[1];
                    List<Detector.Det> d = detector.detect(img, k, used); img.recycle();
                    Xf x = null; Detector.Lattice L = null;
                    if (d.size() >= 6) { L = detector.fit(d); x = align(d, L); }
                    alignMiss = x == null ? alignMiss + 1 : 0;
                    if (probe && x != null) { scale = used[0]; scanA = L.a; }
                    if (x != null) {
                        if (x != null && gen == trackGen && mode == Mode.SURVEY) survey(d, L, x);
                        if (x != null && gen == trackGen && mode == Mode.PLAN && !autoHidden) wardenCheck(d, L, x);
                        if (x != null && gen == trackGen) {
                            Tracker.Ref nr = Tracker.makeRef(g.clone(), x); Xf now = xf;
                            if (now != null) nr.sc = now.a / x.a;  // camera zoom since that frame: prior for the tracker
                            ref = nr;
                        }
                    }
                } catch (Throwable e) { ui.post(() -> setStatus("Трекинг: " + e)); }
                work.postDelayed(this, mode == Mode.SURVEY ? 0 : 150);
            }
        });
    }

    // ---------------- hide the plan once the Warden is down ----------------
    volatile boolean autoHidden; private int wardenHits;
    /** A Grand Warden seen near the planned spot on 2 YOLO frames = deployed: hide the plan and collapse the panel. */
    private void wardenCheck(List<Detector.Det> d, Detector.Lattice L, Xf x) {
        Scene sc = scene; Solver.Plan p = sc.plan(); if (p == null || !prefs.getBoolean("hideOnDeploy", true)) return;
        boolean near = false;
        for (int j = 0; j < d.size(); j++) {
            if (!d.get(j).name.equals("Hero Grand Warden")) continue;
            int u = L.u[j] + x.du, s = L.s[j] + x.ds; double X = (u + s) / 2.0 + sc.ox, Y = (s - u) / 2.0 + sc.oy;
            if (Math.hypot(X - p.wardenPt[0], Y - p.wardenPt[1]) < 6) near = true;
        }
        wardenHits = near ? wardenHits + 1 : 0;
        if (wardenHits >= 2) {
            wardenHits = 0; autoHidden = true;
            ui.post(() -> { setCollapsed(true); setStatus("Хранитель высажен — план скрыт. Тап по 🔥 — вернуть"); overlay.invalidate(); });
        }
    }
    private void unhide() { if (autoHidden) { autoHidden = false; overlay.invalidate(); } }

    private void onFix() { overlay.invalidate(); setLag(true); }

    /** Transform of the current frame from the reference's: cur = sc * (ref - C) + C + t (screen px). */
    private Xf derive(Xf r, Tracker.Est e) {
        double fx = (Tracker.X1 - Tracker.X0) * r.capW / Tracker.W, fy = (Tracker.Y1 - Tracker.Y0) * r.capH / Tracker.H;
        double Cx = Tracker.X0 * r.capW + Tracker.W / 2.0 * fx, Cy = Tracker.Y0 * r.capH + Tracker.H / 2.0 * fy;
        Xf x = new Xf(); x.a = r.a * e.sc; x.b = r.b * e.sc; x.du = r.du; x.ds = r.ds; x.capW = r.capW; x.capH = r.capH; x.time = System.currentTimeMillis();
        x.cx = e.sc * (r.cx - Cx) + Cx + e.tx * fx; x.cy = e.sc * (r.cy - Cy) + Cy + e.ty * fy;
        return x;
    }

    private Xf align(List<Detector.Det> d, Detector.Lattice L) {
        Map<String, Set<Long>> idx = index; Map<String, Integer> cnt = counts; if (idx == null) return null;
        Map<Long, Double> votes = new HashMap<>();
        for (int j = 0; j < d.size(); j++) {
            Set<Long> same = idx.get(d.get(j).name); if (same == null) continue;
            double w = 1.0 / cnt.get(d.get(j).name);
            for (long k : same) {
                int u = (int) (k >> 32), s = (int) k, du = u - L.u[j], ds = s - L.s[j];
                if (Math.floorMod(du + ds, 2) != 0) continue;
                votes.merge(key(du, ds), w, Double::sum);
            }
        }
        long bestK = 0; double top = -1;
        for (Map.Entry<Long, Double> e : votes.entrySet()) if (e.getValue() > top) { top = e.getValue(); bestK = e.getKey(); }
        if (top < 0) return null;
        int du = (int) (bestK >> 32), ds = (int) bestK, hit = 0;
        for (int j = 0; j < d.size(); j++) { Set<Long> same = idx.get(d.get(j).name); if (same != null && same.contains(key(L.u[j] + du, L.s[j] + ds))) hit++; }
        if (hit < Math.max(6, 0.3 * d.size())) return null;
        Xf x = new Xf(); x.a = L.a; x.b = L.b; x.cx = L.cx; x.cy = L.cy; x.du = du; x.ds = ds; x.capW = capW; x.capH = capH; x.time = System.currentTimeMillis();
        return x;
    }

    // ---------------- windows ----------------
    // The panel collapses into a small round button (tap: open, drag: move, snaps to the nearest side edge).
    // It collapses by itself once a plan is ready. "Вид" cycles the drawing: full plan / marks only / hidden.
    volatile int view;  // 0 full, 1 marks only, 2 hidden
    private boolean collapsed; private Mode shownMode = Mode.IDLE;
    private LinearLayout panel; private TextView mini; private GradientDrawable miniBg; private boolean statusOpen;
    private float dp() { return getResources().getDisplayMetrics().density; }

    private void addWindows() {
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        view = prefs.getInt("view", 0);
        overlay = new OverlayView(this);
        overlayLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        overlayLp.gravity = Gravity.TOP | Gravity.LEFT;
        overlayLp.alpha = 0.8f;  // Android 12+: touches pass through an untouchable overlay only at opacity <= 0.8
        if (Build.VERSION.SDK_INT >= 28) overlayLp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30 ? 3 : 1;
        wm.addView(overlay, overlayLp);

        float dp = dp();
        bubble = new LinearLayout(this); bubble.setOrientation(LinearLayout.VERTICAL);
        panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xD0141820); bg.setCornerRadius(12 * dp); bg.setStroke((int) dp, 0x50FFFFFF);
        panel.setBackground(bg); panel.setPadding((int) (4 * dp), (int) (3 * dp), (int) (4 * dp), (int) (3 * dp));
        buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.VERTICAL);
        editList = new LinearLayout(this); editList.setOrientation(LinearLayout.VERTICAL);
        editScroll = new android.widget.ScrollView(this); editScroll.addView(editList); editScroll.setVisibility(View.GONE);
        editScroll.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int) (190 * dp)));
        status = new TextView(this); status.setTextColor(0xFFDDE3EA); status.setTextSize(11); status.setMaxWidth((int) (320 * dp));
        status.setPadding((int) (4 * dp), 0, (int) (4 * dp), (int) (2 * dp)); status.setMaxLines(2); status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setOnClickListener(v -> { statusOpen = !statusOpen; status.setMaxLines(statusOpen || helpOpen ? 30 : 2); });
        panel.addView(buttons); panel.addView(editScroll); panel.addView(status);
        mini = new TextView(this); mini.setText("🔥"); mini.setTextSize(18); mini.setGravity(Gravity.CENTER);
        miniBg = new GradientDrawable(); miniBg.setShape(GradientDrawable.OVAL); miniBg.setColor(0xB0141820); miniBg.setStroke((int) (3 * dp), 0xFF777777);
        mini.setBackground(miniBg); int sz = (int) (44 * dp); mini.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        bubble.addView(panel); bubble.addView(mini);
        bubbleLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.LEFT;
        bubbleLp.x = prefs.getInt("bx", (int) (8 * dp)); bubbleLp.y = prefs.getInt("by", (int) (60 * dp));
        if (Build.VERSION.SDK_INT >= 28) bubbleLp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30 ? 3 : 1;
        wm.addView(bubble, bubbleLp);
        mini.setOnTouchListener(dragger(true));
        setCollapsed(false);
    }

    /** Drag the floating window; a short touch without movement is a tap (opens the panel when `tapOpens`). */
    private View.OnTouchListener dragger(boolean tapOpens) {
        return new View.OnTouchListener() {
            float sx, sy; int bx, by; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN: sx = e.getRawX(); sy = e.getRawY(); bx = bubbleLp.x; by = bubbleLp.y; moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - sx) + Math.abs(e.getRawY() - sy) > 12 * dp()) moved = true;
                        if (moved) { bubbleLp.x = bx + (int) (e.getRawX() - sx); bubbleLp.y = by + (int) (e.getRawY() - sy); wm.updateViewLayout(bubble, bubbleLp); }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            if (tapOpens && e.getEventTime() - e.getDownTime() > 450 && mode == Mode.PLAN) cycleView();  // long press: switch view
                            else if (tapOpens) setCollapsed(false);
                            return true;
                        }
                        snap(); return true;
                }
                return true;
            }
        };
    }

    private void snap() {
        int w = screenSize()[0], bw = bubble.getWidth();
        bubbleLp.x = bubbleLp.x + bw / 2 < w / 2 ? (int) (4 * dp()) : Math.max(0, w - bw - (int) (4 * dp()));
        bubbleLp.y = Math.max(0, bubbleLp.y);
        wm.updateViewLayout(bubble, bubbleLp);
        prefs.edit().putInt("bx", bubbleLp.x).putInt("by", bubbleLp.y).apply();
    }

    private void setCollapsed(boolean c) {
        if (!c) unhide();
        collapsed = c; panel.setVisibility(c ? View.GONE : View.VISIBLE); mini.setVisibility(c ? View.VISIBLE : View.GONE);
        bubble.post(this::snap);
    }

    private void setPick(boolean on) {
        if (on) { overlayLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; overlayLp.alpha = 1f; }
        else { overlayLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; overlayLp.alpha = 0.8f; }
        wm.updateViewLayout(overlay, overlayLp);
    }

    private TextView lagDot;
    private void setLag(boolean ok) {
        int c = ok ? 0xFF3DDC84 : 0xFFFF5A5A;
        if (lagDot != null) lagDot.setTextColor(c);
        if (miniBg != null) miniBg.setStroke((int) (3 * dp()), mode == Mode.PLAN || mode == Mode.SURVEY ? c : 0xFF777777);
    }

    private void setStatus(String t) { statusText = t; if (!helpOpen) status.setText(t); }

    private TextView button(String text, int color, View.OnClickListener l) {
        float dp = dp();
        TextView b = new TextView(this); b.setText(text); b.setTextColor(Color.WHITE); b.setTextSize(12); b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(8 * dp); b.setBackground(bg);
        b.setPadding((int) (8 * dp), (int) (6 * dp), (int) (8 * dp), (int) (6 * dp)); b.setMinWidth((int) (36 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins((int) (2 * dp), (int) (2 * dp), (int) (2 * dp), (int) (2 * dp)); b.setLayoutParams(lp);
        b.setOnClickListener(l); return b;
    }

    private static final String[] VIEWS = {"весь план", "прицелы", "скрыт"}, AI = {"всегда", "при движ.", "выкл"};
    private void cycleView() { if (autoHidden) { unhide(); return; } view = (view + 1) % 3; prefs.edit().putInt("view", view).apply(); overlay.invalidate(); refreshButtons(); }

    // ---- help, close confirmation, manual-fix list ----
    private boolean helpOpen; private String statusText = ""; private long closeArmed;
    private android.widget.ScrollView editScroll; private LinearLayout editList;

    private static String help(Mode m) {
        switch (m) {
            case SURVEY: return "Сейчас ИИ ищет здания на каждом кадре и добавляет новые. Подожди загрузки базы и води камерой, пока все защиты не получат рамку. "
                    + "Жёлтая рамка — тип под вопросом (исправишь в «Правке»). «Готово» — закончить сбор.";
            case PICK: return "Тапни защиту или замок клана — это цель. Белая заливка — защита, жёлтая — тип под вопросом, синяя — замок клана "
                    + "(файер в замок не летит: подберу защиту, чей всплеск его заденет).\n«Авто» — цель, где файер заденет больше защит.\n"
                    + "«Правка» — тап по зданию: сменить тип или удалить; тап по пустому месту: добавить пропущенное.\n«Имена» — подписи вкл/выкл.\n"
                    + "«Скан +» — досканировать, найденное сохраняется. «Скан новый» — всё заново.";
            case PLAN: return "Голубая зона — куда ставить Хранителя (ярче — надёжнее), белые прицелы — инвизы, оранжевое — тряс и всплеск файера, "
                    + "красный крест — снести шарами.\n«◀ ▶» — варианты плана.\n«Показ» — весь план / только прицелы / скрыт.\n"
                    + "«ИИ» — распознавание во время плана: всегда / только пока двигаешь камеру / выкл. Выкл экономит батарею; план всё равно идёт за камерой, "
                    + "но после сильного зума может съехать, и план не скроется сам после высадки Хранителя.\n«Цель» — другая цель. «Имена» — подписи вкл/выкл.\n"
                    + "Точка: зелёная — план держится за базой, красная — потерял, подвигай камеру.";
            default: return "«Скан» — снять базу с экрана: открой базу противника, дождись загрузки и нажми.\n"
                    + "«Свернуть» — спрятать панель в кнопку 🔥: тап — открыть, перетащить — переместить, долгое нажатие при готовом плане — сменить показ.\n"
                    + "✕ — закрыть оверлей (нажать два раза).";
        }
    }

    private void toggleHelp() {
        helpOpen = !helpOpen;
        status.setText(helpOpen ? help(mode) : statusText); status.setMaxLines(helpOpen || statusOpen ? 30 : 2); refreshButtons();
    }

    /** Type list for the selected building: its survey votes first (what the model also saw there), then same-size types, then the rest. */
    private void showEditList() {
        editList.removeAllViews();
        final Solver.Bld sel = selected;
        List<String> types = Solver.editTypes(), order = new ArrayList<>();
        if (sel != null) {
            List<Map.Entry<String, Double>> v = new ArrayList<>(sel.votes.entrySet());
            v.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            for (Map.Entry<String, Double> e : v) if (types.contains(e.getKey())) order.add(e.getKey());
            for (String t : types) if (!order.contains(t) && sizes.containsKey(t) && sizes.get(t) == sel.w) order.add(t);
        }
        for (String t : types) if (!order.contains(t)) order.add(t);
        LinearLayout row = null; int i = 0;
        List<TextView> all = new ArrayList<>();
        if (sel != null) all.add(button("Удалить", 0xFF8E2424, v -> applyEdit(null)));
        for (String t : order) all.add(button(Solver.ru(t), t.equals(Solver.CASTLE) ? 0xFF1565C0 : 0xFF37474F, v -> applyEdit(t)));
        all.add(button("Отмена", 0xFF555555, v -> { selected = null; selectedTile = null; hideEditList(); overlay.invalidate(); }));
        for (TextView b : all) {
            if (i++ % 3 == 0) { row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); editList.addView(row); }
            row.addView(b);
        }
        editScroll.scrollTo(0, 0); editScroll.setVisibility(View.VISIBLE);
    }
    private void hideEditList() { if (editScroll != null) editScroll.setVisibility(View.GONE); }

    private TextView closeButton() {
        final TextView b = button("✕", 0xFF8E2424, null);
        b.setOnClickListener(v -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - closeArmed < 3000) { stopSelf(); return; }
            closeArmed = now; b.setText("Закрыть? Ещё раз");
            ui.postDelayed(() -> { if (android.os.SystemClock.uptimeMillis() - closeArmed >= 2900) b.setText("✕"); }, 3000);
        });
        return b;
    }

    private void refreshButtons() {
        if (mode == Mode.PLAN && shownMode != Mode.PLAN) { autoHidden = false; wardenHits = 0; }
        if (mode == Mode.PLAN && shownMode != Mode.PLAN && scene.plan() != null && prefs.getBoolean("autoMini", true)) setCollapsed(true);
        if (mode == Mode.PICK || mode == Mode.SURVEY) setCollapsed(false);
        if (mode != Mode.PICK) { editing = false; hideEditList(); }
        if (helpOpen && shownMode != mode) status.setText(help(mode));
        shownMode = mode;
        buttons.removeAllViews(); lagDot = null;
        List<View> b = new ArrayList<>();
        switch (mode) {
            case IDLE: b.add(button("Скан", 0xFF2E7D32, v -> scan())); break;
            case BUSY: break;
            case SURVEY:
                b.add(button("Готово", 0xFF2E7D32, v -> finishSurvey()));
                lagDot = new TextView(this); lagDot.setText("●"); lagDot.setTextColor(0xFF3DDC84); lagDot.setPadding(4, 0, 4, 0); b.add(lagDot);
                break;
            case PICK:
                b.add(button("Авто", 0xFF1565C0, v -> autoTarget()));
                b.add(button(editing ? "Правка ●" : "Правка ○", editing ? 0xFFEF6C00 : 0xFF555555, v -> {
                    editing = !editing; selected = null; selectedTile = null; hideEditList();
                    setStatus(editing ? "Правка: тапни здание, чтобы сменить тип или удалить, или пустое место — добавить" : "Тапни по защите или замку — это цель");
                    refreshButtons(); overlay.invalidate(); }));
                b.add(namesButton());
                b.add(button("Скан +", 0xFF2E7D32, v -> improve()));
                b.add(button("Скан новый", 0xFF1B5E20, v -> scan()));
                b.add(button("Отмена", 0xFF555555, v -> { setPick(false); mode = Mode.IDLE; scene = new Scene(); overlay.invalidate(); setStatus("Нажми «Скан»"); refreshButtons(); }));
                break;
            case PLAN:
                Scene sc = scene; int n = sc.res == null ? 0 : sc.res.options.size();
                if (n > 1) {
                    b.add(button("◀", 0xFF1565C0, v -> option(-1)));
                    b.add(button("Вар " + (sc.opt + 1) + "/" + n, 0xFF1565C0, v -> option(1)));
                    b.add(button("▶", 0xFF1565C0, v -> option(1)));
                }
                b.add(button("👁 Показ: " + VIEWS[view], 0xFF37474F, v -> cycleView()));
                b.add(namesButton());
                b.add(button("ИИ: " + AI[aiMode], aiMode == 0 ? 0xFF00838F : 0xFF555555, v -> { aiMode = (aiMode + 1) % 3; prefs.edit().putInt("ai", aiMode).apply(); refreshButtons(); }));
                lagDot = new TextView(this); lagDot.setText("●"); lagDot.setTextColor(0xFF3DDC84); lagDot.setPadding(4, 0, 4, 0); b.add(lagDot);
                b.add(button("Цель", 0xFF6A1B9A, v -> { trackGen++; mode = Mode.PICK; Scene c = scene; scene = c.with(c.B);
                    setPick(true); setStatus("Тапни по новой цели"); refreshButtons(); overlay.invalidate(); }));
                b.add(button("Скан +", 0xFF2E7D32, v -> improve()));
                b.add(button("Скан новый", 0xFF1B5E20, v -> { trackGen++; scan(); }));
                break;
        }
        if (mode != Mode.BUSY) { b.add(button(helpOpen ? "? ●" : "?", 0xFF37474F, v -> toggleHelp())); b.add(button("Свернуть", 0xFF37474F, v -> setCollapsed(true))); }
        b.add(closeButton());
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView drag = new TextView(this); drag.setText("⠿"); drag.setTextColor(0xFFAAB4C0); drag.setTextSize(18); drag.setPadding((int) (4 * dp()), 0, (int) (6 * dp()), 0);
        drag.setOnTouchListener(dragger(false)); row.addView(drag); buttons.addView(row);
        int inRow = 0;
        for (View v : b) {
            if (inRow == 4) { row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); buttons.addView(row); inRow = 0; }
            row.addView(v); if (v != lagDot) inRow++;
        }
    }

    private TextView namesButton() {
        return button(names ? "Имена ●" : "Имена ○", names ? 0xFF37474F : 0xFF555555, v -> { names = !names; prefs.edit().putBoolean("names", names).apply(); refreshButtons(); overlay.invalidate(); });
    }

    private void option(int d) {
        Scene cur = scene; int n = cur.res == null ? 0 : cur.res.options.size(); if (n < 2) return;
        Scene ns = cur.with(cur.B); ns.target = cur.target; ns.res = cur.res; ns.via = cur.via; ns.opt = Math.floorMod(cur.opt + d, n);
        scene = ns; overlay.invalidate(); showPlanStatus(); refreshButtons();
    }
}
