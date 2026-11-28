package lab.coc.fireball;

import java.util.Map;
import java.util.HashMap;
import java.util.List;

/** 19.32.4 buildings.csv, characters.csv and spells.csv. */
final class DamageEstimate {
    private static final Map<String, int[]> HP = new HashMap<>();
    private static final int[] REPAIR_DPS = {0, 50, 60, 70, 80, 85, 90, 95, 100};
    private static final int[] QUAKE_PERMILLE = {0, 145, 170, 210, 250, 290};
    private static final double REPAIR_RADIUS = 7; // planning estimate, in tiles
    private static final double REPAIR_SECONDS = 1; // repair budget before the ball lands
    static {
        HP.put("Air Defense", new int[]{800,850,900,950,1000,1050,1100,1210,1300,1400,1500,1650,1750,1850,1950,2050,2150});
        HP.put("Air Sweeper", new int[]{750,800,850,900,950,1000,1050});
        HP.put("Archer Tower", new int[]{380,420,460,500,540,580,630,690,750,810,890,970,1050,1130,1230,1310,1390,1510,1600,1700,1800});
        HP.put("Army Camp", new int[]{100,150,200,250,300,330,400,500,600,700,800,850,900,950});
        HP.put("BOBs Hut", new int[]{250});
        HP.put("Barracks", new int[]{100,200,250,300,360,420,500,575,650,730,810,900,980,1050,1150,1250,1350,1450,1520});
        HP.put("Blacksmith", new int[]{700,800,900,1000,1100,1200,1300,1400,1500,1600});
        HP.put("Bomb Tower", new int[]{650,700,750,850,1050,1300,1600,1900,2300,2500,2700,2900,3050});
        HP.put("Builders Hut", new int[]{250,1000,1300,1600,1800,1900,2000,2100,2200});
        HP.put("Cannon", new int[]{300,360,420,500,600,660,730,800,880,960,1060,1160,1260,1380,1500,1620,1740,1870,2000,2150,2250});
        HP.put("Clan Castle", new int[]{600,1200,1800,2600,3000,3400,4000,4400,4800,5200,5400,5600,5800,6000,6200});
        HP.put("Crafting Station", new int[]{1000});
        HP.put("Dark Barracks", new int[]{500,550,600,650,700,750,800,850,900,950,1000,1050,1100});
        HP.put("Dark Elixir Drill", new int[]{800,860,920,980,1060,1160,1280,1380,1480,1550,1600,1650});
        HP.put("Dark Elixir Storage", new int[]{2000,2200,2400,2600,2900,3200,3500,3800,4100,4300,4500,4700,4800,4900});
        HP.put("Dark Spell Factory", new int[]{600,660,720,780,840,950,1010,1070});
        HP.put("Eagle Artillery", new int[]{4000,4400,4800,5200,5600,5900,6200});
        HP.put("Elixir Collector", new int[]{75,150,300,400,500,550,600,660,720,780,860,960,1080,1180,1280,1350,1400,1450});
        HP.put("Elixir Storage", new int[]{150,300,450,600,800,1000,1250,1500,1700,1900,2100,2500,2900,3300,3700,3900,4050,4200,4300,4400});
        HP.put("Firespitter", new int[]{4500,5000,5300});
        HP.put("Gold Mine", new int[]{75,150,300,400,500,550,600,660,720,780,860,960,1080,1180,1280,1350,1400,1450});
        HP.put("Gold Storage", new int[]{150,300,450,600,800,1000,1250,1500,1700,1900,2100,2500,2900,3300,3700,3900,4050,4200,4300,4400});
        HP.put("Helper Hut", new int[]{500});
        HP.put("Inferno Tower", new int[]{1500,1800,2100,2400,2700,3000,3300,3700,4000,4400,4800,5100});
        HP.put("Laboratory", new int[]{500,550,600,650,700,750,830,950,1070,1140,1210,1280,1350,1400,1450,1500,1550});
        HP.put("Monolith", new int[]{4747,5050,5353,5656,5959});
        HP.put("Mortar", new int[]{400,450,500,550,600,650,700,800,950,1100,1300,1500,1700,1950,2150,2300,2450,2550});
        HP.put("Multi Archer Tower", new int[]{5000,5200,5400,5500,5900});
        HP.put("Multi Gear Tower", new int[]{4000,4200,4350,4700});
        HP.put("Pet House", new int[]{700,800,900,1000,1050,1100,1150,1200,1250,1300,1350,1400});
        HP.put("Revenge Tower", new int[]{5800,6200});
        HP.put("Ricochet Cannon", new int[]{5400,5700,6000,6100,6500});
        HP.put("Scattershot", new int[]{3600,4200,4800,5100,5410,5600,5800});
        HP.put("Siege Workshop", new int[]{1000,1100,1200,1300,1400,1500,1600,1700,1800});
        HP.put("Spell Factory", new int[]{425,470,520,600,720,840,960,1080,1150,1220});
        HP.put("Spell Tower", new int[]{2500,2800,3100,3200});
        HP.put("Super Wizard Tower", new int[]{6000,6300});
        HP.put("Town Hall", new int[]{400,800,1600,2000,2400,2800,3300,3900,4600,5500,6800,7500,8200,8900,9600,10000,10400,10800,11200});
        HP.put("Wizard Tower", new int[]{620,650,680,730,840,960,1200,1440,1600,1900,2120,2240,2500,2800,3000,3150,3300});
        HP.put("X-Bow", new int[]{1500,1900,2300,2700,3100,3400,3700,4000,4200,4400,4600,4800,5000,5450});
    }

    static int maxLevel(String name) {
        int[] hp = HP.get(name);
        return hp == null ? 0 : hp.length;
    }

    private static int health(Solver.Bld building) {
        int[] levels = HP.get(building.name);
        if (levels == null) return 0;
        if (building.level > 0 && building.level <= levels.length) return levels[building.level - 1];
        int max = 0;
        for (int hp : levels) max = Math.max(max, hp);
        return max;
    }

    static int platformColor(Solver.Bld building, List<Solver.Bld> buildings, Solver.Plan plan, int ballDamage, int quakeLevel) {
        int hp = health(building);
        if (hp == 0) return 0x609CA3AF;
        int quakeDamage = 0;
        if (plan.eq != null && Solver.distToFoot(plan.eq.centre[0], plan.eq.centre[1], building) <= plan.eq.R) {
            quakeDamage = hp * QUAKE_PERMILLE[Math.min(5, quakeLevel)] / 1000;
        }
        int repair = 0;
        if (building.def && quakeDamage > 0) {
            for (Solver.Bld hut : buildings) {
                if (!Solver.HUT.equals(hut.name) || plan.C.contains(hut)
                        || Solver.distToFoot(hut.cx(), hut.cy(), building) > REPAIR_RADIUS) continue;
                int level = hut.level == 0 ? REPAIR_DPS.length : Math.min(hut.level, REPAIR_DPS.length);
                repair += REPAIR_DPS[level - 1];
            }
        }
        int margin = ballDamage + quakeDamage - hp - Math.min(quakeDamage, (int) (repair * REPAIR_SECONDS));
        if (margin >= 0 && (repair == 0 || margin >= 400)) return 0x803DDC84;
        float risk = Math.min(1f, Math.max(0.2f, (400 - margin) / 300f));
        return ((int) (100 + 80 * risk) << 24) | 0xFF3838;
    }
}
