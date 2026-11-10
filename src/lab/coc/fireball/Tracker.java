package lab.coc.fireball;

/**
 * Fast camera tracking between YOLO anchors: phase correlation of small grey frames (W x H) against a reference frame
 * whose screen transform is known. Model: cur = sc * (ref - c) + c + t (pan + pinch zoom, c = centre of the small frame).
 * Scale is searched among a few candidates around the previous estimate; translation comes from the correlation peak.
 * Pure Java (no Android types) so it can be tested on the desktop.
 */
final class Tracker {
    static final int W = 256, H = 128, N = W * H;
    /** Part of the screen that is sampled (fractions): skips the top HUD and the troop bar. */
    static final double X0 = 0.04, X1 = 0.96, Y0 = 0.12, Y1 = 0.76;
    private static final float[] WIN = new float[N];
    static {
        for (int j = 0; j < H; j++) for (int i = 0; i < W; i++)
            WIN[j * W + i] = (float) ((0.5 - 0.5 * Math.cos(2 * Math.PI * (i + 0.5) / W)) * (0.5 - 0.5 * Math.cos(2 * Math.PI * (j + 0.5) / H)));
    }

    /** Reference: spectrum of the windowed frame + its screen transform (opaque to the tracker). */
    static final class Ref { final float[] re = new float[N], im = new float[N]; final Object xf; volatile double sc = 1; Ref(Object xf) { this.xf = xf; } }
    static final class Est { double sc, tx, ty, peak; }

    private final float[] bre = new float[N], bim = new float[N], tmp = new float[N];

    static Ref makeRef(float[] grey, Object xf) {
        Ref r = new Ref(xf); prep(grey, r.re, r.im); fft2(r.re, r.im, false); return r;
    }

    private static void prep(float[] g, float[] re, float[] im) {
        double m = 0; for (int k = 0; k < N; k++) m += g[k]; float mean = (float) (m / N);
        for (int k = 0; k < N; k++) { re[k] = (g[k] - mean) * WIN[k]; im[k] = 0; }
    }

    /** Best (scale, translation in small px) of `cur` against `ref`, trying scales prior * f for f in factors. */
    Est match(Ref ref, float[] cur, double prior, double[] factors) {
        Est[] es = new Est[factors.length]; int bi = 0;
        for (int i = 0; i < factors.length; i++) { es[i] = matchAt(ref, cur, prior * factors[i]); if (es[i].peak > es[bi].peak) bi = i; }
        if (bi == 0 || bi == factors.length - 1) return es[bi];
        // parabolic refinement of the scale between the neighbours (log scale)
        double l = Math.log(es[bi - 1].sc), c = Math.log(es[bi].sc), r = Math.log(es[bi + 1].sc);
        double pl = es[bi - 1].peak, pc = es[bi].peak, pr = es[bi + 1].peak, d = pl - 2 * pc + pr;
        if (d >= -1e-9) return es[bi];
        double off = 0.5 * (pl - pr) / d, h = (r - l) / 2;
        Est e = matchAt(ref, cur, Math.exp(c + Math.max(-0.5, Math.min(0.5, off)) * h));
        return e.peak >= pc * 0.98 ? e : es[bi];
    }

    Est matchAt(Ref ref, float[] cur, double sc) {
        // resample: R(p) = cur(sc * (p - c) + c); then R(p) = ref(p - t / sc)
        float[] src = cur;
        if (Math.abs(sc - 1) > 1e-4) {
            double cx = W / 2.0, cy = H / 2.0; double m = 0; for (int k = 0; k < N; k++) m += cur[k]; float mean = (float) (m / N);
            for (int j = 0; j < H; j++) for (int i = 0; i < W; i++) {
                double x = sc * (i - cx) + cx, y = sc * (j - cy) + cy; int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
                float v;
                if (x0 < 0 || y0 < 0 || x0 >= W - 1 || y0 >= H - 1) v = mean;
                else {
                    float fx = (float) (x - x0), fy = (float) (y - y0); int k = y0 * W + x0;
                    v = (cur[k] * (1 - fx) + cur[k + 1] * fx) * (1 - fy) + (cur[k + W] * (1 - fx) + cur[k + W + 1] * fx) * fy;
                }
                tmp[j * W + i] = v;
            }
            src = tmp;
        }
        prep(src, bre, bim); fft2(bre, bim, false);
        for (int k = 0; k < N; k++) {  // B * conj(A), normalised
            float ar = ref.re[k], ai = ref.im[k], br = bre[k], bi = bim[k];
            float r = br * ar + bi * ai, i = bi * ar - br * ai, mag = (float) Math.sqrt(r * r + i * i) + 1e-9f;
            bre[k] = r / mag; bim[k] = i / mag;
        }
        fft2(bre, bim, true);
        int arg = 0; float top = -1e9f; for (int k = 0; k < N; k++) if (bre[k] > top) { top = bre[k]; arg = k; }
        int pi = arg % W, pj = arg / W;
        double dx = pi + sub(bre[pj * W + (pi + W - 1) % W], top, bre[pj * W + (pi + 1) % W]);
        double dy = pj + sub(bre[((pj + H - 1) % H) * W + pi], top, bre[((pj + 1) % H) * W + pi]);
        if (dx > W / 2.0) dx -= W; if (dy > H / 2.0) dy -= H;
        Est e = new Est(); e.sc = sc; e.tx = dx * sc; e.ty = dy * sc; e.peak = top; return e;
    }

    private static double sub(float l, float c, float r) { double d = l - 2 * c + r; return Math.abs(d) < 1e-9 ? 0 : Math.max(-0.5, Math.min(0.5, 0.5 * (l - r) / d)); }

    // ---- 2D FFT (radix 2), inverse scaled by 1/N ----
    static void fft2(float[] re, float[] im, boolean inv) {
        for (int j = 0; j < H; j++) fft(re, im, j * W, 1, W, inv);
        for (int i = 0; i < W; i++) fft(re, im, i, W, H, inv);
        if (inv) for (int k = 0; k < N; k++) { re[k] /= N; im[k] /= N; }
    }
    private static void fft(float[] re, float[] im, int off, int stride, int n, boolean inv) {
        float[] xr = new float[n], xi = new float[n];
        for (int k = 0, j = 0; k < n; k++) {  // bit-reversed copy
            xr[j] = re[off + k * stride]; xi[j] = im[off + k * stride];
            int bit = n >> 1; while (j >= bit && bit > 0) { j -= bit; bit >>= 1; } j += bit;
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inv ? 1 : -1); float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int s = 0; s < n; s += len) {
                float cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = s + k, c = a + len / 2;
                    float tr = xr[c] * cr - xi[c] * ci, ti = xr[c] * ci + xi[c] * cr;
                    xr[c] = xr[a] - tr; xi[c] = xi[a] - ti; xr[a] += tr; xi[a] += ti;
                    float nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
                }
            }
        }
        for (int k = 0; k < n; k++) { re[off + k * stride] = xr[k]; im[off + k * stride] = xi[k]; }
    }
}
