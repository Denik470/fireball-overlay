package lab.coc.fireball;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** YOLO (assets/model.onnx, NMS built in, 1x300x6) on 640 px tiles + isometric lattice fit (port of layout-recognition/recognize.py). */
final class Detector {
    static final class Det { String name; int w; double px, py, bw, conf; }
    /** px = cx + a*u, py = cy + b*s for every detection (u, s = footprint centre in lattice units). */
    static final class Lattice { double a, b, cx, cy, score; int[] u, s; }

    private static final int T = 640, STEP = 480;
    static final double CONF = 0.3;
    final String[] names; final Map<String, Integer> size = new HashMap<>();
    final double trainA, ratio;
    private final OrtEnvironment env; private final OrtSession session;
    private final float[] chw = new float[3 * T * T]; private final int[] pix = new int[T * T];
    private final Bitmap tile = Bitmap.createBitmap(T, T, Bitmap.Config.ARGB_8888);

    Detector(Context c) throws Exception {
        JSONObject spec = new JSONObject(new String(asset(c, "recognizer.json"), StandardCharsets.UTF_8));
        JSONArray n = spec.getJSONArray("names"); names = new String[n.length()];
        JSONObject sz = spec.getJSONObject("size");
        for (int i = 0; i < names.length; i++) { names[i] = n.getString(i); size.put(names[i], sz.optInt(names[i], 1)); }
        trainA = spec.getDouble("train_a"); ratio = spec.getDouble("ratio");
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2)));
        session = env.createSession(asset(c, "model.onnx"), o);
    }

    private static byte[] asset(Context c, String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[1 << 16]; int k;
            while ((k = in.read(b)) > 0) out.write(b, 0, k);
            return out.toByteArray();
        }
    }

    private List<Det> detectScaled(Bitmap img, double k) throws Exception {
        Bitmap im = k == 1 ? img : Bitmap.createScaledBitmap(img, (int) Math.round(img.getWidth() * k), (int) Math.round(img.getHeight() * k), true);
        int W = im.getWidth(), H = im.getHeight(); List<Det> out = new ArrayList<>();
        Canvas canvas = new Canvas(tile);
        for (int ty : starts(H)) for (int tx : starts(W)) {
            canvas.drawColor(Color.rgb(114, 114, 114));
            canvas.drawBitmap(im, -tx, -ty, null);
            tile.getPixels(pix, 0, T, 0, 0, T, T);
            for (int i = 0; i < pix.length; i++) {
                int c = pix[i];
                chw[i] = ((c >> 16) & 255) / 255f; chw[T * T + i] = ((c >> 8) & 255) / 255f; chw[2 * T * T + i] = (c & 255) / 255f;
            }
            float[][] r;
            try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), new long[]{1, 3, T, T});
                 OrtSession.Result res = session.run(Collections.singletonMap("images", in))) {
                r = ((float[][][]) res.get(0).getValue())[0];
            }
            double core = (T - STEP) / 2.0;
            for (float[] b : r) {
                if (b[4] < CONF) continue;
                double cx = (b[0] + b[2]) / 2, cy = (b[1] + b[3]) / 2;
                if ((tx > 0 && cx < core) || (tx + T < W && cx > T - core)) continue;
                if ((ty > 0 && cy < core) || (ty + T < H && cy > T - core)) continue;
                Det d = new Det(); d.name = names[(int) b[5]]; d.w = size.get(d.name);
                d.px = (tx + cx) / k; d.py = (ty + cy) / k; d.bw = (b[2] - b[0]) / k; d.conf = b[4];
                out.add(d);
            }
        }
        if (im != img) im.recycle();
        return out;
    }

    private static List<Integer> starts(int size) {
        java.util.TreeSet<Integer> s = new java.util.TreeSet<>();
        for (int v = 0; v <= Math.max(1, size - T); v += STEP) s.add(v);
        if (size > T) s.add(size - T);
        return new ArrayList<>(s);
    }

    /** Scale that brings the lattice step to the training shots; NaN if unknown. */
    double scaleFor(List<Det> d) {
        List<Double> widths = new ArrayList<>();
        for (Det x : d) if (x.w >= 2) widths.add(x.bw / (2 * x.w));
        return widths.isEmpty() ? Double.NaN : Math.max(0.4, Math.min(1.2, trainA / median(widths)));
    }

    /** k > 0: detect directly at that scale (tracking); k <= 0: probe at 1, then rescale (first scan). out[0] = scale used. */
    List<Det> detect(Bitmap img, double k, double[] used) throws Exception {
        List<Det> d;
        if (k > 0) d = detectScaled(img, k);
        else {
            k = 1; d = detectScaled(img, 1);
            double s = scaleFor(d);
            if (!Double.isNaN(s) && Math.abs(s - 1) > 0.08) { k = s; d = detectScaled(img, s); }
        }
        if (used != null) used[0] = k;
        int H = img.getHeight(); List<Det> kept = new ArrayList<>();
        for (Det x : d) if (0.07 * H < x.py && x.py < 0.81 * H) kept.add(x);  // top banner and the troop card bar
        return kept;
    }

    static double median(List<Double> v) {
        double[] a = new double[v.size()]; for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a); int n = a.length;
        return n % 2 == 1 ? a[n / 2] : (a[n / 2 - 1] + a[n / 2]) / 2;
    }

    Lattice fit(List<Det> dets) {
        List<Double> w = new ArrayList<>();
        for (Det d : dets) if (d.w >= 2) w.add(d.bw / (2 * d.w));
        if (w.isEmpty()) for (Det d : dets) w.add(d.bw / 2);
        double est = median(w); int n = dets.size(); Lattice best = null;
        for (int i = 0; i <= 120; i++) {
            double a = est * (0.85 + 0.3 * i / 120), b = a * ratio;
            double sxr = 0, sxi = 0, syr = 0, syi = 0;
            for (Det d : dets) {
                sxr += Math.cos(2 * Math.PI * d.px / a); sxi += Math.sin(2 * Math.PI * d.px / a);
                syr += Math.cos(2 * Math.PI * d.py / b); syi += Math.sin(2 * Math.PI * d.py / b);
            }
            double cx = a / (2 * Math.PI) * Math.atan2(sxi, sxr), cy = b / (2 * Math.PI) * Math.atan2(syi, syr);
            int[] u = new int[n], s = new int[n]; double res = 0; int bad = 0;
            for (int j = 0; j < n; j++) {
                Det d = dets.get(j); double fu = (d.px - cx) / a, fs = (d.py - cy) / b;
                u[j] = (int) Math.rint(fu); s[j] = (int) Math.rint(fs);
                res += Math.hypot(fu - u[j], fs - s[j]);
                if (Math.floorMod(u[j] + s[j], 2) != d.w % 2) bad++;
            }
            if (bad * 2 > n) { for (int j = 0; j < n; j++) u[j] += 1; cx -= a; bad = n - bad; }  // parity-consistent origin
            double score = res / n + 0.5 * bad / n;
            if (best == null || score < best.score) { best = new Lattice(); best.a = a; best.b = b; best.cx = cx; best.cy = cy; best.u = u; best.s = s; best.score = score; }
        }
        return best;
    }
}
