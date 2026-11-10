package lab.coc.fireball;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Warden fireball planner (port of fireball-studio/studio.html solve()).
 * Grid: absolute tiles of the 50x50 map, buildings = footprints (x, y, w), platform centre = (x + w/2, y + w/2).
 * Rules (user spec 2026-11-09):
 *  - Warden spot A: dist(A, footprint X) < dist(A, footprint Y) for every visible defence Y (X = target), margin >= wardenMin.
 *  - A deployable: inside the map, >= 1 tile from every footprint.
 *  - The 10 defences nearest X may be hidden by invis (all 2^10 subsets); fewest invis, every tolerance >= MIN_TOL,
 *    ties -> largest smallest tolerance.
 *  - Clean-up (optional): 1-2 defences whose centre is < 5 tiles from the deploy area count as killed by super balloons.
 */
final class Solver {
    static final double MIN_TOL = 0.2, STEP = 0.25, CLEAN_DIST = 5, INVIS_R = 4.0;
    static final int NEAR = 10, MAP = 50;

    static final Set<String> DEFENSES = new HashSet<>(java.util.Arrays.asList("Air Defense", "Air Sweeper", "Archer Tower", "Bomb Tower",
            "Cannon", "Crafting Station", "Eagle Artillery", "Firespitter", "Inferno Tower", "Monolith", "Mortar", "Multi Archer Tower",
            "Multi Gear Tower", "Revenge Tower", "Ricochet Cannon", "Scattershot", "Spell Tower", "Super Wizard Tower", "Wizard Tower", "X-Bow"));
    static final Map<String, String> RU = new HashMap<>();
    static {
        String[] p = {"Air Defense", "ПВО", "Air Sweeper", "Воздушка", "Archer Tower", "Лучницы", "Bomb Tower", "Бомб. башня", "Cannon", "Пушка",
                "Crafting Station", "Мастерская", "Eagle Artillery", "Орёл", "Firespitter", "Огнемёт", "Inferno Tower", "Инферно", "Monolith", "Монолит",
                "Mortar", "Мортира", "Multi Archer Tower", "Мульти-лучн.", "Multi Gear Tower", "Мульти-шест.", "Revenge Tower", "Башня мести",
                "Ricochet Cannon", "Рикошет", "Scattershot", "Швырятель", "Spell Tower", "Башня закл.", "Super Wizard Tower", "Супермаг",
                "Wizard Tower", "Маг. башня", "X-Bow", "Арбалет", "Town Hall", "Ратуша", "Clan Castle", "Замок клана"};
        for (int i = 0; i < p.length; i += 2) RU.put(p[i], p[i + 1]);
    }
    static String ru(String n) { String r = RU.get(n); return r == null ? n : r; }

    static final class Bld {
        final String name; final int w; double x, y; final double conf; final boolean def;
        final int u, s;  // footprint centre in the lattice frame of the scan (for tracking)
        Bld(String name, int w, double x, double y, double conf, int u, int s) {
            this.name = name; this.w = w; this.x = x; this.y = y; this.conf = conf; this.def = DEFENSES.contains(name); this.u = u; this.s = s;
        }
        double cx() { return x + w / 2.0; }
        double cy() { return y + w / 2.0; }
    }

    static final class Zone { double[] centre; double margin; float[] pts; List<Bld> group; float[] edge; }
    static final class Quake { double R, tol; double[] centre; int n; float[] zone, edge; }
    static final class Plan {
        int S, k; List<Bld> C; List<Zone> zones = new ArrayList<>(); double margin, wardenTol, minTol; int wardenK;
        double[] wardenPt; float[] area; List<Bld> binding = new ArrayList<>(), hidden = new ArrayList<>(), splash; Quake eq;
    }
    static final class Result { List<Plan> options = new ArrayList<>(); String error; long ms; List<Bld> splash; }

    private static double dist(double ax, double ay, double bx, double by) { return Math.hypot(ax - bx, ay - by); }
    static double distToFoot(double px, double py, Bld b) {
        double dx = Math.max(Math.max(b.x - px, 0), px - (b.x + b.w)), dy = Math.max(Math.max(b.y - py, 0), py - (b.y + b.w));
        return Math.hypot(dx, dy);
    }

    final List<Bld> B; final Bld T; final double fbR, eqR; final boolean cleanup, outsideOnly; double wardenMin = 0.5;
    float[] px, py, pd; int nP;

    Solver(List<Bld> B, Bld T, double fbR, double eqR, boolean cleanup, boolean outsideOnly) {
        this.B = B; this.T = T; this.fbR = fbR; this.eqR = eqR; this.cleanup = cleanup; this.outsideOnly = outsideOnly;
    }

    Solver margin(double m) { wardenMin = m; return this; }

    byte[] blockedGrid() {
        byte[] g = new byte[MAP * MAP];
        for (Bld b : B) for (int i = (int) Math.floor(b.x) - 1; i < Math.ceil(b.x + b.w) + 1; i++) for (int j = (int) Math.floor(b.y) - 1; j < Math.ceil(b.y + b.w) + 1; j++)
            if (i >= 0 && j >= 0 && i < MAP && j < MAP) g[i * MAP + j] = 1;
        if (outsideOnly) {
            // Outside of the base only. Flood from the map edge through cells >= 2 tiles from every footprint, so pockets
            // and narrow alleys inside the base (often a missed building or decoration) are not reachable; then allow free
            // cells next to the reached area (the 1-2 tile band along the outer edge).
            byte[] wide = new byte[MAP * MAP];
            for (Bld b : B) for (int i = (int) Math.floor(b.x) - 2; i < Math.ceil(b.x + b.w) + 2; i++) for (int j = (int) Math.floor(b.y) - 2; j < Math.ceil(b.y + b.w) + 2; j++)
                if (i >= 0 && j >= 0 && i < MAP && j < MAP) wide[i * MAP + j] = 1;
            byte[] seen = new byte[MAP * MAP]; int[] q = new int[MAP * MAP]; int qn = 0;
            for (int i = 0; i < MAP; i++) for (int j = 0; j < MAP; j++)
                if ((i == 0 || j == 0 || i == MAP - 1 || j == MAP - 1) && wide[i * MAP + j] == 0) { seen[i * MAP + j] = 1; q[qn++] = i * MAP + j; }
            while (qn > 0) {
                int k = q[--qn], i = k / MAP, j = k % MAP;
                int[][] nb = {{i + 1, j}, {i - 1, j}, {i, j + 1}, {i, j - 1}};
                for (int[] uv : nb) {
                    int u = uv[0], v = uv[1]; if (u < 0 || v < 0 || u >= MAP || v >= MAP) continue; int n = u * MAP + v;
                    if (seen[n] == 0 && wide[n] == 0) { seen[n] = 1; q[qn++] = n; }
                }
            }
            for (int i = 0; i < MAP; i++) for (int j = 0; j < MAP; j++) {
                int k = i * MAP + j; if (g[k] != 0 || seen[k] != 0) continue;
                boolean near = false;
                for (int u = i - 1; u <= i + 1 && !near; u++) for (int v = j - 1; v <= j + 1; v++)
                    if (u >= 0 && v >= 0 && u < MAP && v < MAP && seen[u * MAP + v] != 0) { near = true; break; }
                if (!near) g[k] = 1;
            }
        }
        return g;
    }

    void deployPoints(byte[] blocked) {
        int n = (int) (MAP / STEP); px = new float[n * n]; py = new float[n * n]; pd = new float[n * n]; nP = 0;
        for (int a = 0; a < n; a++) for (int c = 0; c < n; c++) {
            double x = (a + .5) * STEP, y = (c + .5) * STEP; int i = (int) Math.floor(x), j = (int) Math.floor(y);
            if (blocked[i * MAP + j] != 0) continue;
            double d = Math.min(3, Math.min(Math.min(x, y), Math.min(MAP - x, MAP - y)));
            for (int u = i - 3; u <= i + 3; u++) for (int v = j - 3; v <= j + 3; v++) {
                if (u < 0 || v < 0 || u >= MAP || v >= MAP || blocked[u * MAP + v] == 0) continue;
                double dx = Math.max(Math.max(u - x, 0), x - u - 1), dy = Math.max(Math.max(v - y, 0), y - v - 1); d = Math.min(d, Math.hypot(dx, dy));
            }
            px[nP] = (float) x; py[nP] = (float) y; pd[nP] = (float) d; nP++;
        }
    }

    Zone dropZone(List<Bld> group) {
        double tcx = T.cx(), tcy = T.cy(), step = 0.1;
        double x0 = -1e9, x1 = 1e9, y0 = -1e9, y1 = 1e9;
        for (Bld b : group) { x0 = Math.max(x0, b.cx() - INVIS_R); x1 = Math.min(x1, b.cx() + INVIS_R); y0 = Math.max(y0, b.cy() - INVIS_R); y1 = Math.min(y1, b.cy() + INVIS_R); }
        if (x0 > x1 || y0 > y1) return null;
        double bestM = -1, bx = 0, by = 0; FloatList pts = new FloatList();
        for (double x = x0; x <= x1; x += step) for (double y = y0; y <= y1; y += step) {
            double worst = 0; for (Bld b : group) worst = Math.max(worst, dist(x, y, b.cx(), b.cy()));
            double m = Math.min(INVIS_R - worst, dist(x, y, tcx, tcy) - INVIS_R);
            if (m < 0) continue;
            pts.add((float) x, (float) y, (float) m); if (m > bestM) { bestM = m; bx = x; by = y; }
        }
        if (bestM < 0) return null;
        Zone z = new Zone(); z.centre = new double[]{bx, by}; z.margin = bestM; z.pts = pts.toArray(); z.group = group; return z;
    }

    Zone[] zone; int[] coverK, coverG; double[] coverM;

    void invisTable(List<Bld> near) {
        int n = near.size(), N = 1 << n; zone = new Zone[N]; boolean[] ok = new boolean[N]; ok[0] = true;
        for (int m = 1; m < N; m++) {
            boolean sub = true; for (int i = 0; i < n; i++) if ((m >> i & 1) != 0 && !ok[m ^ (1 << i)]) { sub = false; break; }
            if (!sub) continue;
            List<Bld> g = new ArrayList<>(); for (int i = 0; i < n; i++) if ((m >> i & 1) != 0) g.add(near.get(i));
            Zone z = dropZone(g);
            if (z != null && z.margin >= MIN_TOL) { zone[m] = z; ok[m] = true; }
        }
        coverK = new int[N]; coverG = new int[N]; coverM = new double[N];
        java.util.Arrays.fill(coverK, -1); coverK[0] = 0; coverM[0] = 9;
        for (int S = 1; S < N; S++) {
            int low = S & -S, bk = -1, bg = 0; double bm = 0;
            for (int g = S; g != 0; g = (g - 1) & S) {
                if ((g & low) == 0 || zone[g] == null || coverK[S ^ g] < 0) continue;
                int k = coverK[S ^ g] + 1; double mg = Math.min(coverM[S ^ g], zone[g].margin);
                if (bk < 0 || k < bk || (k == bk && mg > bm)) { bk = k; bm = mg; bg = g; }
            }
            coverK[S] = bk; coverM[S] = bm; coverG[S] = bg;
        }
    }

    List<Integer> coverMasks(int S) { List<Integer> r = new ArrayList<>(); while (S != 0) { r.add(0, coverG[S]); S ^= coverG[S]; } return r; }

    Quake quake(List<Bld> splash) {
        if (!(eqR > 0)) return null;
        double tcx = T.cx(), tcy = T.cy(); FloatList all = new FloatList(); int maxN = 0;
        for (double x = tcx - eqR - 1; x <= tcx + eqR + 1; x += 0.1) for (double y = tcy - eqR - 1; y <= tcy + eqR + 1; y += 0.1) {
            if (distToFoot(x, y, T) > eqR) continue;
            int c = 0; for (Bld b : splash) if (b != T && distToFoot(x, y, b) <= eqR) c++;
            all.add((float) x, (float) y, c); maxN = Math.max(maxN, c);
        }
        float[] p = all.toArray();
        for (int n = maxN; n >= 0; n--) {
            FloatList z = new FloatList(); for (int i = 0; i < p.length; i += 3) if (p[i + 2] >= n) z.add(p[i], p[i + 1], p[i + 2]);
            float[] zone = z.toArray(), edge = edgeCells(zone);
            double tol = 0; double[] aim = null;
            for (int i = 0; i < zone.length; i += 3) {
                double d = 9; for (int e = 0; e < edge.length; e += 3) d = Math.min(d, dist(zone[i], zone[i + 1], edge[e], edge[e + 1]));
                if (d > tol) { tol = d; aim = new double[]{zone[i], zone[i + 1]}; }
            }
            if (tol >= MIN_TOL) { Quake q = new Quake(); q.R = eqR; q.centre = aim; q.n = n; q.zone = zone; q.edge = edge; q.tol = tol; return q; }
        }
        return null;
    }

    /** Cells of a 0.1-step point set (x, y, m triples) that have a neighbour outside it: the zone's outline. */
    static float[] edgeCells(float[] zone) {
        Set<Long> in = new HashSet<>();
        for (int i = 0; i < zone.length; i += 3) in.add(key(zone[i], zone[i + 1]));
        FloatList r = new FloatList();
        for (int i = 0; i < zone.length; i += 3) {
            float x = zone[i], y = zone[i + 1];
            if (!in.contains(key(x + .1f, y)) || !in.contains(key(x - .1f, y)) || !in.contains(key(x, y + .1f)) || !in.contains(key(x, y - .1f)))
                r.add(x, y, zone[i + 2]);
        }
        return r.toArray();
    }
    private static long key(float x, float y) { return ((long) Math.round(x * 10) << 32) ^ (Math.round(y * 10) & 0xffffffffL); }

    Result solve() {
        long t0 = System.currentTimeMillis(); Result res = new Result();
        final double tcx = T.cx(), tcy = T.cy();
        deployPoints(blockedGrid());
        List<Bld> defs = new ArrayList<>(); for (Bld b : B) if (b.def && b != T) defs.add(b);
        Collections.sort(defs, (p, q) -> Double.compare(dist(p.cx(), p.cy(), tcx, tcy), dist(q.cx(), q.cy(), tcx, tcy)));
        List<Bld> near = new ArrayList<>(defs.subList(0, Math.min(NEAR, defs.size()))), far = new ArrayList<>(defs.subList(near.size(), defs.size()));
        Map<Bld, float[]> M = new HashMap<>();
        for (Bld d : defs) {
            float[] a = new float[nP];
            for (int k = 0; k < nP; k++) {
                double x = px[k], y = py[k];
                // Margin = how far the Warden may drift before d becomes nearer than T, by distance to the footprint edge
                // (test 2026-11-10: a 3x3 tower beat a 2x2 target that was nearer by centre).
                a[k] = (float) ((distToFoot(x, y, d) - distToFoot(x, y, T)) / 2);
            }
            M.put(d, a);
        }
        List<Bld> splash = new ArrayList<>(); for (Bld b : B) if (!b.name.equals("Wall") && distToFoot(tcx, tcy, b) <= fbR) splash.add(b);
        res.splash = splash;
        Quake eq = quake(splash); double eqTol = eq != null ? eq.tol : 9;
        if (eqR > 0 && eq == null) { res.error = "Тряс нельзя бросить с допуском ≥ " + MIN_TOL + " кл."; return res; }
        invisTable(near);
        int nn = near.size(), N = 1 << nn;
        List<List<Bld>> combos = new ArrayList<>(); combos.add(new ArrayList<>());
        if (cleanup) {
            List<Bld> E = new ArrayList<>();
            for (Bld d : defs) for (int k = 0; k < nP; k++) if (dist(px[k], py[k], d.cx(), d.cy()) < CLEAN_DIST) { E.add(d); break; }
            for (int i = 0; i < E.size(); i++) {
                List<Bld> one = new ArrayList<>(); one.add(E.get(i)); combos.add(one);
                for (int j = i + 1; j < E.size(); j++) { List<Bld> two = new ArrayList<>(); two.add(E.get(i)); two.add(E.get(j)); combos.add(two); }
            }
        }
        List<Plan> plans = new ArrayList<>();
        for (List<Bld> C : combos) {
            Set<Bld> cset = new HashSet<>(C); int cmask = 0;
            for (int i = 0; i < nn; i++) if (cset.contains(near.get(i))) cmask |= 1 << i;
            float[] base = new float[nP];
            for (int k = 0; k < nP; k++) { float v = pd[k]; for (Bld d : far) if (!cset.contains(d)) v = Math.min(v, M.get(d)[k]); base[k] = v; }
            Set<Integer> needs = new LinkedHashSet<>();
            for (int k = 0; k < nP; k++) {
                if (base[k] < MIN_TOL) continue; int m = 0;
                for (int i = 0; i < nn; i++) { Bld d = near.get(i); if (!cset.contains(d) && M.get(d)[k] < MIN_TOL) m |= 1 << i; }
                needs.add(m);
            }
            LinkedHashSet<Integer> cands = new LinkedHashSet<>(needs);
            if (C.isEmpty()) {
                int kmin = Integer.MAX_VALUE; for (int m : needs) if (coverK[m] >= 0) kmin = Math.min(kmin, coverK[m]);
                if (kmin != Integer.MAX_VALUE) for (int S = 0; S < N; S++) {
                    if (coverK[S] != kmin) continue;
                    for (int m : needs) if ((m & S) == m) { cands.add(S); break; }
                }
            }
            for (int S : cands) {
                int inv = S & ~cmask; if (coverK[inv] < 0) continue;
                int full = S | cmask; List<float[]> vis = new ArrayList<>();
                for (int i = 0; i < nn; i++) { Bld d = near.get(i); if ((full >> i & 1) == 0 && !cset.contains(d)) vis.add(M.get(d)); }
                float best = -1; int arg = -1;
                for (int k = 0; k < nP; k++) {
                    float v = base[k]; if (v <= best) continue;
                    for (float[] a : vis) if (a[k] < v) { v = a[k]; if (v <= best) break; }
                    if (v > best) { best = v; arg = k; }
                }
                if (best < wardenMin) continue;  // the Warden walks a little before the ability: thin spots miss
                Plan p = new Plan(); p.S = S; p.C = C; p.k = coverK[inv]; p.margin = coverM[inv];
                for (int g : coverMasks(inv)) p.zones.add(zone[g]);
                p.wardenTol = best; p.wardenK = arg; p.minTol = Math.min(Math.min(p.margin, best), eqTol);
                plans.add(p);
            }
        }
        Collections.sort(plans, (p, q) -> p.k != q.k ? Integer.compare(p.k, q.k) : p.minTol != q.minTol ? Double.compare(q.minTol, p.minTol) : Integer.compare(p.C.size(), q.C.size()));
        for (Plan p : plans) {
            if (res.options.size() >= 4) break;
            boolean dup = false;
            for (Plan o : res.options) if (o.k == p.k && o.C.size() == p.C.size() && Math.abs(o.minTol - p.minTol) < 1e-6 && o.zones.size() == p.zones.size()) { dup = true; break; }
            if (!dup) res.options.add(p);
        }
        for (Plan o : res.options) finish(o, near, far, splash, eq, M);
        if (res.options.isEmpty()) res.error = "Бросок ненадёжен: нет точки для Хранителя с запасом ≥ " + wardenMin + " кл. Уменьши запас в настройках, включи подчистку или выбери другую цель.";
        res.ms = System.currentTimeMillis() - t0;
        return res;
    }

    private void finish(Plan o, List<Bld> near, List<Bld> far, List<Bld> splash, Quake eq, Map<Bld, float[]> M) {
        Set<Bld> cset = new HashSet<>(o.C); List<Bld> vis = new ArrayList<>();
        for (int i = 0; i < near.size(); i++) { Bld d = near.get(i); if ((o.S >> i & 1) == 0) vis.add(d); else if (!cset.contains(d)) o.hidden.add(d); }
        vis.addAll(far); vis.removeAll(cset);
        o.splash = splash; o.eq = eq;
        FloatList area = new FloatList(); Map<Bld, Integer> bind = new HashMap<>();
        for (int k = 0; k < nP; k++) {
            float v = pd[k]; Bld arg = null;
            for (Bld d : vis) { float a = M.get(d)[k]; if (a < v) { v = a; arg = d; } }
            if (v >= 0) { area.add(px[k], py[k], v); if (arg != null && v < 0.6) bind.merge(arg, 1, Integer::sum); }
        }
        o.area = area.toArray(); o.binding.addAll(bind.keySet());
        o.wardenPt = new double[]{px[o.wardenK], py[o.wardenK]};
        for (Zone z : o.zones) if (z.edge == null) z.edge = edgeCells(z.pts);
    }

    static final class FloatList {
        float[] a = new float[96]; int n;
        void add(float x, float y, float m) { if (n + 3 > a.length) a = java.util.Arrays.copyOf(a, a.length * 2); a[n++] = x; a[n++] = y; a[n++] = m; }
        float[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }
}
