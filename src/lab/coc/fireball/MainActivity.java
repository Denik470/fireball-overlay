package lab.coc.fireball;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.SeekBar;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/** Settings + permission flow: overlay permission -> screen capture consent -> OverlayService. */
public final class MainActivity extends Activity {
    private static final float[] EQ = {0, 3.5f, 3.8f, 4.1f, 4.4f, 4.7f}, WM = {0.3f, 0.5f, 0.75f, 1f};
    /** Warden Fireball by equipment level 1..27 (game tables 19.32.4, special_abilities.csv GrandWardenFireball):
     *  DamageRadius 400/500/600 = 4/5/6 tiles (changes at levels 9 and 24 only), Damage. */
    static final int[] FB_DAMAGE = {1500, 1500, 1700, 1700, 1800, 1950, 1950, 2050, 2200, 2200, 2350, 2650, 2650, 2750, 3100, 3100, 3250, 3400,
            3400, 3500, 3650, 3650, 3750, 3900, 3900, 3950, 4100};
    static float fbRadius(int level) { return level >= 24 ? 6 : level >= 9 ? 5 : 4; }
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("fb", MODE_PRIVATE);
        float dp = getResources().getDisplayMetrics().density; int pad = (int) (16 * dp);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(pad, pad * 2, pad, pad);
        TextView title = new TextView(this); title.setText("Fireball overlay"); title.setTextSize(22); col.addView(title);
        TextView help = new TextView(this); help.setTextSize(14); help.setPadding(0, pad / 2, 0, pad);
        help.setText("Нажми «Старт» и разреши показ поверх игры и запись экрана. "
                + "В записи выбери «Одно приложение» → Clash of Clans. "
                + "Открой базу, нажми «Скан» и следуй подсказкам панели.");
        col.addView(help);
        col.addView(label("Огненный шар Хранителя (уровень снаряжения)"));
        col.addView(fbLevel());
        col.addView(label("Землетрясение"));
        col.addView(spinner(new String[]{"Выкл.", "1", "2", "3", "4", "5+"}, EQ, "eqR", 4.7f));
        col.addView(label("Мин. запас для Хранителя, клетки"));
        col.addView(spinner(new String[]{"0,3", "0,5", "0,75", "1"}, WM, "wmin", 0.5f));
        col.addView(label("Распознавание (ИИ), пока показан план"));
        col.addView(intSpinner(new String[]{"всегда — точнее держит план", "только при движении экрана", "никогда — экономит батарею"}, "ai", 0));
        col.addView(check("Подписи зданий на оверлее", "names", true));
        col.addView(check("Подчистка с края супершарами (1–2 здания)", "cleanup", false));
        col.addView(check("Хранитель только снаружи базы (рекомендуется)", "outside", true));
        col.addView(check("Сворачивать панель в кнопку 🔥, когда план готов", "autoMini", true));
        col.addView(check("Скрывать план, когда Хранитель высажен", "hideOnDeploy", true));
        Button start = new Button(this); start.setText("Старт"); start.setOnClickListener(v -> start()); col.addView(start);
        Button stop = new Button(this); stop.setText("Стоп"); stop.setOnClickListener(v -> startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STOP))); col.addView(stop);
        ScrollView sv = new ScrollView(this); sv.addView(col); setContentView(sv);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
    }

    private TextView label(String t) { TextView v = new TextView(this); v.setText(t); v.setPadding(0, 16, 0, 0); return v; }

    private Spinner spinner(String[] labels, float[] values, String key, float def) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        float cur = prefs.getFloat(key, def);
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - cur) < 1e-3) s.setSelection(i);
        s.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int i, long id) { prefs.edit().putFloat(key, values[i]).apply(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        return s;
    }

    private LinearLayout fbLevel() {
        LinearLayout control = new LinearLayout(this);
        control.setOrientation(LinearLayout.VERTICAL);
        TextView value = new TextView(this);
        control.addView(value);
        SeekBar slider = new SeekBar(this);
        slider.setContentDescription("Уровень огненного шара");
        java.util.List<Integer> levels = new java.util.ArrayList<>();
        int stored = fireballLevel(prefs);
        int initial = 0;
        for (int i = 1; i <= 27; i++) {
            if (i == 1 || FB_DAMAGE[i - 1] != FB_DAMAGE[i - 2] || fbRadius(i) != fbRadius(i - 1)) levels.add(i);
            if (i <= stored) initial = levels.size() - 1;
        }
        slider.setMax(levels.size() - 1);
        slider.setProgress(initial);
        value.setText(levelLabel(levels, initial));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int position, boolean fromUser) {
                int level = levels.get(position);
                value.setText(levelLabel(levels, position));
                if (fromUser) prefs.edit().putInt("fbLvl", level).putFloat("fbR", fbRadius(level)).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        control.addView(slider);
        return control;
    }

    static int fireballLevel(SharedPreferences prefs) {
        float radius = prefs.getFloat("fbR", 6);
        int fallback = radius <= 4 ? 8 : radius <= 5 ? 23 : 27;
        return Math.max(1, Math.min(27, prefs.getInt("fbLvl", fallback)));
    }

    static int quakeLevel(SharedPreferences prefs) {
        float radius = prefs.getFloat("eqR", 4.7f);
        for (int i = 0; i < EQ.length; i++) if (Math.abs(EQ[i] - radius) < 1e-3) return i;
        return 5;
    }

    private static String levelLabel(java.util.List<Integer> levels, int position) {
        int first = levels.get(position);
        int last = position + 1 == levels.size() ? 27 : levels.get(position + 1) - 1;
        return first == last ? String.valueOf(first) : first + "–" + last;
    }

    private Spinner intSpinner(String[] labels, String key, int def) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        s.setSelection(Math.max(0, Math.min(labels.length - 1, prefs.getInt(key, def))));
        s.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int i, long id) { prefs.edit().putInt(key, i).apply(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        return s;
    }

    private CheckBox check(String t, String key, boolean def) {
        CheckBox c = new CheckBox(this); c.setText(t); c.setChecked(prefs.getBoolean(key, def));
        c.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(key, on).apply()); return c;
    }

    private void start() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разреши «Поверх других приложений», затем снова «Старт»", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), 1);
    }

    @Override protected void onActivityResult(int req, int code, Intent data) {
        if (req != 1) return;
        if (code != RESULT_OK || data == null) { Toast.makeText(this, "Запись экрана не разрешена", Toast.LENGTH_SHORT).show(); return; }
        Intent i = new Intent(this, OverlayService.class).putExtra("code", code).putExtra("data", data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        moveTaskToBack(true);
    }
}
