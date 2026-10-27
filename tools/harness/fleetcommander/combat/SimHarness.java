package fleetcommander.combat;

import com.fs.starfarer.api.combat.AdmiralAIPlugin;
import com.fs.starfarer.api.combat.AssignmentTargetAPI;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import fleetcommander.FCConfig;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Offline smoke test of the commander loop. The game's combat interfaces are faked with dynamic proxies (any
 * method not scripted returns 0 / false / empty / null), ships are placed by hand, and the fake task manager
 * records every order. Not shipped; run by tools/run_harness.sh. It proves the loop runs without errors and
 * makes the decisions the doctrines describe; how the real game reacts to the orders still needs a playtest.
 */
public class SimHarness {
    static int failures;

    // ---------------------------------------------------------------------------------------------------------
    // proxy helper

    @SuppressWarnings("unchecked")
    static <T> T mock(Class<T> type, Map<String, Function<Object[], Object>> answers) {
        return (T) Proxy.newProxyInstance(SimHarness.class.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            Function<Object[], Object> f = answers.get(m.getName());
            if (f != null) return f.apply(a);
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("equals")) return p == a[0];
            if (m.getName().equals("toString")) return type.getSimpleName() + "@" + System.identityHashCode(p);
            Class<?> r = m.getReturnType();
            if (r == boolean.class) return false;
            if (r == int.class) return 0;
            if (r == float.class) return 0f;
            if (r == double.class) return 0d;
            if (r == long.class) return 0L;
            if (List.class.isAssignableFrom(r)) return new ArrayList<>();
            if (r == String.class) return "";
            return null;
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // fake ships

    static final class Ship {
        final String name;
        final int owner;
        final ShipAPI.HullSize size;
        final Vector2f loc;
        float hull = 1f, flux = 0f, speed = 100f;
        boolean overloaded, retreating, alive = true, carrier;
        ShipAPI api;
        DeployedFleetMemberAPI dfm;
        FleetMemberAPI member;

        Ship(String name, int owner, ShipAPI.HullSize size, float x, float y) {
            this.name = name;
            this.owner = owner;
            this.size = size;
            this.loc = new Vector2f(x, y);
            build();
        }

        private void build() {
            Ship s = this;
            ShipHullSpecAPI spec = mock(ShipHullSpecAPI.class, Map.of(
                    "getHullName", a -> s.name,
                    "getHullSize", a -> s.size,
                    "getArmorRating", a -> 500f));
            FluxTrackerAPI flux = mock(FluxTrackerAPI.class, Map.of(
                    "isOverloaded", a -> s.overloaded,
                    "getHardFlux", a -> s.flux * 1000f));
            ShieldAPI shield = mock(ShieldAPI.class, Map.of(
                    "getType", a -> ShieldAPI.ShieldType.FRONT,
                    "isOn", a -> true));
            ShipVariantAPI variant = mock(ShipVariantAPI.class, Map.of());
            member = mock(FleetMemberAPI.class, Map.of(
                    "getHullSpec", a -> spec,
                    "getDeploymentPointsCost", a -> 10f));
            Map<String, Function<Object[], Object>> m = new HashMap<>();
            m.put("getLocation", a -> s.loc);
            m.put("getVelocity", a -> new Vector2f());
            m.put("isAlive", a -> s.alive);
            m.put("isHulk", a -> !s.alive);
            m.put("getHullLevel", a -> s.hull);
            m.put("getFluxLevel", a -> s.flux);
            m.put("getMaxFlux", a -> 1000f);
            m.put("getFluxTracker", a -> flux);
            m.put("getShield", a -> shield);
            m.put("getHullSpec", a -> spec);
            m.put("getVariant", a -> variant);
            m.put("getHullSize", a -> s.size);
            m.put("isFrigate", a -> s.size == ShipAPI.HullSize.FRIGATE);
            m.put("isDestroyer", a -> s.size == ShipAPI.HullSize.DESTROYER);
            m.put("isCruiser", a -> s.size == ShipAPI.HullSize.CRUISER);
            m.put("isCapital", a -> s.size == ShipAPI.HullSize.CAPITAL_SHIP);
            m.put("getNumFighterBays", a -> s.carrier ? 3 : 0);
            m.put("getCurrentCR", a -> 0.7f);
            m.put("getMaxSpeed", a -> s.speed);
            m.put("isRetreating", a -> s.retreating);
            m.put("getFleetMember", a -> s.member);
            m.put("getName", a -> s.name);
            m.put("getOwner", a -> s.owner);
            api = mock(ShipAPI.class, m);
            dfm = mock(DeployedFleetMemberAPI.class, Map.of(
                    "getShip", a -> s.api,
                    "getMember", a -> s.member,
                    "getLocation", a -> s.loc,
                    "canBeGivenOrders", a -> s.alive && !s.retreating,
                    "canBeGivenRetreatOrders", a -> s.alive && !s.retreating,
                    "getOwner", a -> s.owner));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // fake task manager: records orders

    static final class Assignment {
        final CombatAssignmentType type;
        final AssignmentTargetAPI target;
        final List<DeployedFleetMemberAPI> members = new ArrayList<>();
        AssignmentInfo api;

        Assignment(CombatAssignmentType type, AssignmentTargetAPI target) {
            this.type = type;
            this.target = target;
            Assignment self = this;
            api = mock(AssignmentInfo.class, Map.of(
                    "getType", a -> self.type,
                    "getTarget", a -> self.target,
                    "getAssignedMembers", a -> self.members));
        }
    }

    static final class Tasks {
        final List<Assignment> all = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        int cp = 5, cpSpent;
        CombatTaskManagerAPI api;
        boolean fullAssault;

        Assignment find(AssignmentInfo info) {
            for (Assignment a : all) if (a.api == info) return a;
            return null;
        }

        Tasks(Map<ShipAPI, Ship> byApi, double[] now) {
            Tasks t = this;
            Map<String, Function<Object[], Object>> m = new HashMap<>();
            m.put("getCommandPointsLeft", a -> t.cp);
            m.put("createWaypoint2", a -> mock(AssignmentTargetAPI.class, Map.of("getLocation", x -> a[0])));
            m.put("createAssignment", a -> {
                if ((Boolean) a[2]) {
                    if (t.cp <= 0) throw new IllegalStateException("order with no CP left");
                    t.cp--;
                    t.cpSpent++;
                }
                Assignment as = new Assignment((CombatAssignmentType) a[0], (AssignmentTargetAPI) a[1]);
                t.all.add(as);
                t.log.add(String.format("%5.1fs %-14s on %s%s", now[0], as.type, name(as.target, byApi),
                        (Boolean) a[2] ? "  [CP]" : ""));
                return as.api;
            });
            m.put("giveAssignment", a -> {
                DeployedFleetMemberAPI d = (DeployedFleetMemberAPI) a[0];
                for (Assignment x : t.all) x.members.remove(d);
                Assignment as = t.find((AssignmentInfo) a[1]);
                if (as != null) as.members.add(d);
                return null;
            });
            m.put("removeAssignment", a -> {
                t.all.remove(t.find((AssignmentInfo) a[0]));
                return null;
            });
            m.put("getAllAssignments", a -> {
                List<AssignmentInfo> l = new ArrayList<>();
                for (Assignment x : t.all) l.add(x.api);
                return l;
            });
            m.put("getAssignmentFor", a -> {
                for (Assignment x : t.all) for (DeployedFleetMemberAPI d : x.members) if (d.getShip() == a[0]) return x.api;
                return null;
            });
            m.put("getAssignmentInfoForTarget", a -> {
                for (Assignment x : t.all) if (x.target == a[0]) return x.api;
                return null;
            });
            m.put("orderRetreat", a -> {
                Ship s = byApi.get(((DeployedFleetMemberAPI) a[0]).getShip());
                s.retreating = true;
                if ((Boolean) a[1]) {
                    t.cp--;
                    t.cpSpent++;
                }
                t.log.add(String.format("%5.1fs RETREAT        %s%s", now[0], s.name, (Boolean) a[1] ? "  [CP]" : ""));
                return null;
            });
            m.put("setFullAssault", a -> {
                t.fullAssault = true;
                t.log.add(String.format("%5.1fs FULL ASSAULT", now[0]));
                return null;
            });
            api = mock(CombatTaskManagerAPI.class, m);
        }

        static String name(AssignmentTargetAPI target, Map<ShipAPI, Ship> byApi) {
            if (target instanceof DeployedFleetMemberAPI) return byApi.get(((DeployedFleetMemberAPI) target).getShip()).name;
            if (target instanceof BattleObjectiveAPI) return "objective";
            return "waypoint " + target.getLocation();
        }

        int membersOf(CombatAssignmentType type, String targetName, Map<ShipAPI, Ship> byApi) {
            int n = 0;
            for (Assignment a : all) {
                if (a.type == type && a.target instanceof DeployedFleetMemberAPI
                        && name(a.target, byApi).equals(targetName)) n += a.members.size();
            }
            return n;
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    static final class Battle {
        final List<Ship> ships = new ArrayList<>();
        final Map<ShipAPI, Ship> byApi = new HashMap<>();
        final double[] now = {0};
        final Tasks enemyTasks, playerTasks;
        final CombatEngineAPI engine;
        boolean silenced;
        Ship playerShip;

        Battle(List<Ship> list) {
            ships.addAll(list);
            for (Ship s : list) byApi.put(s.api, s);
            enemyTasks = new Tasks(byApi, now);
            playerTasks = new Tasks(byApi, now);
            Battle b = this;
            AdmiralAIPlugin admiral = mock(AdmiralAIPlugin.class, Map.of("setNoOrders", a -> {
                b.silenced = (Boolean) a[0];
                return null;
            }));
            CombatFleetManagerAPI fm1 = fleetManager(1, enemyTasks, admiral);
            CombatFleetManagerAPI fm0 = fleetManager(0, playerTasks, null);
            Map<String, Function<Object[], Object>> e = new HashMap<>();
            e.put("getFleetManager", a -> (a[0] instanceof Integer ? (Integer) a[0] : 0) == 1 ? fm1 : fm0);
            e.put("isAwareOf", a -> true);
            e.put("getPlayerShip", a -> b.playerShip == null ? null : b.playerShip.api);
            e.put("isSimulation", a -> true);
            e.put("getObjectives", a -> new ArrayList<BattleObjectiveAPI>());
            e.put("getCustomData", a -> new HashMap<String, Object>());
            engine = mock(CombatEngineAPI.class, e);
        }

        CombatFleetManagerAPI fleetManager(int owner, Tasks tasks, AdmiralAIPlugin admiral) {
            Battle b = this;
            Map<String, Function<Object[], Object>> m = new HashMap<>();
            m.put("getTaskManager", a -> tasks.api);
            m.put("getAdmiralAI", a -> admiral);
            m.put("getGoal", a -> FleetGoal.ATTACK);
            m.put("getOwner", a -> owner);
            m.put("getDeployedCopyDFM", a -> {
                List<DeployedFleetMemberAPI> l = new ArrayList<>();
                for (Ship s : b.ships) if (s.owner == owner && s.alive && !s.retreating) l.add(s.dfm);
                return l;
            });
            m.put("getDeployedCopy", a -> {
                List<FleetMemberAPI> l = new ArrayList<>();
                for (Ship s : b.ships) if (s.owner == owner && s.alive) l.add(s.member);
                return l;
            });
            m.put("getDeployedFleetMember", a -> b.byApi.get(a[0]).dfm);
            return mock(CombatFleetManagerAPI.class, m);
        }
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        if (!ok) failures++;
    }

    static FleetCommander run(Battle b, FCConfig config, double seconds, Runnable everySecond) {
        FleetCommander c = FleetCommander.takeOver(b.engine, 1, config);
        double next = 1;
        for (double t = 0; t < seconds; t += 0.1) {
            b.now[0] = t;
            c.advance(0.1f);
            if (t >= next) {
                next += 1;
                if (everySecond != null) everySecond.run();
            }
        }
        return c;
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        // vanilla's Misc reads a few settings when it loads; any number/false will do here
        com.fs.starfarer.api.Global.setSettings(mock(com.fs.starfarer.api.SettingsAPI.class, Map.of()));
        FCConfig config = FCConfig.load(new ToolsSource(root));
        if (config.validation.errorCount() > 0) throw new IllegalStateException(config.validation.getErrors().toString());

        // --- Scenario 1: balanced fleet vs a player whose flagship wandered off, one overloaded ship, one wreck ---
        System.out.println("Scenario 1: default doctrine");
        Ship eCruiser = new Ship("Eagle", 1, ShipAPI.HullSize.CRUISER, 0, 3000);
        Ship eCarrier = new Ship("Heron", 1, ShipAPI.HullSize.CRUISER, 200, 3600);
        eCarrier.carrier = true;
        Ship eDest1 = new Ship("Hammerhead", 1, ShipAPI.HullSize.DESTROYER, -400, 3000);
        Ship eDest2 = new Ship("Medusa", 1, ShipAPI.HullSize.DESTROYER, 400, 3000);
        eDest2.speed = 110;
        Ship eFrig1 = new Ship("Wolf", 1, ShipAPI.HullSize.FRIGATE, -800, 2800);
        Ship eFrig2 = new Ship("Lasher", 1, ShipAPI.HullSize.FRIGATE, 800, 2800);
        Ship eFrig3 = new Ship("Hound", 1, ShipAPI.HullSize.FRIGATE, 600, 2900);
        Ship eWreck = new Ship("Enforcer", 1, ShipAPI.HullSize.DESTROYER, 0, 3300);
        eWreck.hull = 0.2f;

        Ship pFlag = new Ship("ISS Player (Medusa)", 0, ShipAPI.HullSize.DESTROYER, 3500, 1500); // alone, far right
        Ship pLine1 = new Ship("Dominator", 0, ShipAPI.HullSize.CRUISER, -500, 0);
        Ship pLine2 = new Ship("Enforcer P", 0, ShipAPI.HullSize.DESTROYER, -100, 0);
        Ship pOver = new Ship("Lasher P", 0, ShipAPI.HullSize.FRIGATE, 300, 1800); // near the enemy line
        Battle b = new Battle(List.of(eCruiser, eCarrier, eDest1, eDest2, eFrig1, eFrig2, eFrig3, eWreck,
                pFlag, pLine1, pLine2, pOver));
        b.playerShip = pFlag;
        double[] t = {0};
        FleetCommander c = run(b, config, 70, () -> {
            t[0] += 1;
            if (t[0] == 50) pOver.overloaded = true;  // overload at 50 s (after the opening phase)
            if (t[0] == 56) pOver.overloaded = false;
        });
        for (String line : b.enemyTasks.log) System.out.println("    " + line);
        check("doctrine is default", c.doctrine().id.equals("default"));
        check("vanilla admiral silenced", b.silenced);
        check("wreck ordered to retreat", eWreck.retreating);
        check("carrier got an escort", b.enemyTasks.log.stream().anyMatch(l -> l.contains("LIGHT_ESCORT") && l.contains("Heron")));
        check("isolated player flagship hunted with INTERCEPT (Eliminate)",
                b.enemyTasks.log.stream().anyMatch(l -> l.contains("INTERCEPT") && l.contains("ISS Player")));
        check("hunting pack has 2-4 ships", between(b.enemyTasks.membersOf(CombatAssignmentType.INTERCEPT,
                "ISS Player (Medusa)", b.byApi), 2, 4));
        check("pounce on the overloaded Lasher", b.enemyTasks.log.stream().anyMatch(l -> l.contains("Lasher P")
                && l.contains("INTERCEPT") && Double.parseDouble(l.trim().split("s ")[0]) >= 50));
        check("focus fire with ENGAGE", b.enemyTasks.log.stream().anyMatch(l -> l.contains("ENGAGE")));
        check("command points never went negative", b.enemyTasks.cp >= 0);
        check("no ship in two of our assignments", noDoubleAssignment(b.enemyTasks));

        // --- rescuers arrive: the hunt should be called off ---
        System.out.println("Scenario 1b: rescuers reach the hunted flagship");
        pLine1.loc.set(3300, 1500);
        pLine2.loc.set(3700, 1500);
        for (int i = 0; i < 150; i++) {
            b.now[0] = 70 + i * 0.1;
            c.advance(0.1f);
        }
        check("hunt on the flagship abandoned", b.enemyTasks.membersOf(CombatAssignmentType.INTERCEPT,
                "ISS Player (Medusa)", b.byApi) == 0);

        // --- Scenario 2: fast frigate fleet picks the wolfpack doctrine ---
        System.out.println("Scenario 2: wolfpack selection");
        List<Ship> wolves = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Ship w = new Ship("Wolf" + i, 1, ShipAPI.HullSize.FRIGATE, i * 300, 3000);
            w.speed = 160;
            wolves.add(w);
        }
        Ship lone = new Ship("Lone Hammerhead", 0, ShipAPI.HullSize.DESTROYER, 0, 1000);
        wolves.add(lone);
        Battle b2 = new Battle(wolves);
        FleetCommander c2 = run(b2, config, 15, null);
        for (String line : b2.enemyTasks.log) System.out.println("    " + line);
        check("doctrine is wolfpack", c2.doctrine().id.equals("wolfpack"));
        check("lone destroyer hunted in the opening", b2.enemyTasks.log.stream().anyMatch(l -> l.contains("Lone Hammerhead")));

        // --- Scenario 3: no command points: nothing but free retreats/escorts must be attempted, and no crash ---
        System.out.println("Scenario 3: zero command points");
        Ship a = new Ship("Onslaught", 1, ShipAPI.HullSize.CAPITAL_SHIP, 0, 3000);
        Ship d = new Ship("Enemy Lasher", 0, ShipAPI.HullSize.FRIGATE, 0, 0);
        Battle b3 = new Battle(List.of(a, d));
        b3.enemyTasks.cp = 0;
        run(b3, config, 20, null);
        check("no orders without CP", b3.enemyTasks.log.isEmpty());

        System.out.println(failures == 0 ? "harness OK" : "harness FAILED: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    static boolean between(int v, int lo, int hi) {
        return v >= lo && v <= hi;
    }

    static boolean noDoubleAssignment(Tasks t) {
        List<DeployedFleetMemberAPI> seen = new ArrayList<>();
        for (Assignment a : t.all) {
            for (DeployedFleetMemberAPI d : a.members) {
                if (seen.contains(d)) return false;
                seen.add(d);
            }
        }
        return true;
    }

    /** Plain-file config source (same as tools/ValidateConfig.java). */
    static final class ToolsSource implements fleetcommander.doctrine.ConfigSource {
        private final Path root;

        ToolsSource(Path root) {
            this.root = root;
        }

        @Override
        public org.json.JSONObject loadJSON(String path) throws Exception {
            return new org.json.JSONObject(java.nio.file.Files.readString(root.resolve(path)));
        }

        @Override
        public org.json.JSONArray loadDoctrineIndex(String path) throws Exception {
            List<String> lines = java.nio.file.Files.readAllLines(root.resolve(path));
            String[] header = lines.get(0).split(",");
            org.json.JSONArray rows = new org.json.JSONArray();
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) continue;
                String[] cells = lines.get(i).split(",");
                org.json.JSONObject row = new org.json.JSONObject();
                for (int k = 0; k < header.length; k++) row.put(header[k], cells[k]);
                rows.put(row);
            }
            return rows;
        }
    }
}
