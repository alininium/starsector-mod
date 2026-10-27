package fleetcommander.combat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.AssignmentTargetAPI;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only class that talks to the task manager: maps our order names to the game's assignment types, applies the
 * command point budget, announces orders and tidies up (DOCTRINE_FORMAT.md section 10).
 */
public final class FCOrders {
    private static final Logger log = Global.getLogger(FCOrders.class);

    public enum CpPriority { HIGH, NORMAL, LOW }

    /** Orders that are not assignments. */
    public static final Set<String> SPECIAL = Set.of("retreat", "retreat_direct", "free", "full_assault",
            "full_retreat");

    private static final Map<String, CombatAssignmentType> BUILT_IN = new HashMap<>();

    static {
        // "eliminate" -> INTERCEPT is our reading of the player's "Eliminate" order; logPlayerOrders verifies it,
        // and settings.json "orderMapping" can change it without a rebuild.
        BUILT_IN.put("eliminate", CombatAssignmentType.INTERCEPT);
        BUILT_IN.put("engage", CombatAssignmentType.ENGAGE);
        BUILT_IN.put("harass", CombatAssignmentType.HARASS);
        BUILT_IN.put("fighter_strike", CombatAssignmentType.STRIKE);
        BUILT_IN.put("avoid", CombatAssignmentType.AVOID);
        BUILT_IN.put("ignore", CombatAssignmentType.IGNORE);
        BUILT_IN.put("capture", CombatAssignmentType.CAPTURE);
        BUILT_IN.put("assault", CombatAssignmentType.ASSAULT);
        BUILT_IN.put("control", CombatAssignmentType.CONTROL);
        BUILT_IN.put("defend", CombatAssignmentType.DEFEND);
        BUILT_IN.put("escort_light", CombatAssignmentType.LIGHT_ESCORT);
        BUILT_IN.put("escort_medium", CombatAssignmentType.MEDIUM_ESCORT);
        BUILT_IN.put("escort_heavy", CombatAssignmentType.HEAVY_ESCORT);
        BUILT_IN.put("rally", CombatAssignmentType.RALLY_TASK_FORCE);
        BUILT_IN.put("rally_carriers", CombatAssignmentType.RALLY_CARRIER);
        BUILT_IN.put("move", CombatAssignmentType.RALLY_CIVILIAN);
    }

    /** Every order name a doctrine may use. */
    public static boolean isKnownOrder(String name) {
        return BUILT_IN.containsKey(name) || SPECIAL.contains(name);
    }

    private final CombatEngineAPI engine;
    private final CombatTaskManagerAPI tm;
    private final Map<String, CombatAssignmentType> mapping = new HashMap<>(BUILT_IN);
    private final boolean useCp;
    private final int cpReserve;
    /** Seconds after spending a CP during which further orders are free, like the player's command window. */
    private final double commandWindow;
    private double now, windowUntil = -1, lastSay = -100;
    private final boolean announce;
    private final boolean retreatsAllowed;
    private final boolean debug;
    /** Assignments we created (never removed by the tidy-up unless we remove them). */
    private final Set<AssignmentInfo> ours = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<AssignmentInfo> scratch = new ArrayList<>();
    private int ordersGiven;

    private static final Color PREFIX = new Color(255, 120, 90);
    private static final Color TEXT = new Color(230, 230, 230);

    FCOrders(CombatEngineAPI engine, CombatTaskManagerAPI tm, Map<String, String> overrides, boolean useCp,
             int cpReserve, double commandWindow, boolean announce, boolean retreatsAllowed, boolean debug) {
        this.engine = engine;
        this.tm = tm;
        this.useCp = useCp;
        this.cpReserve = cpReserve;
        this.commandWindow = commandWindow;
        this.announce = announce;
        this.retreatsAllowed = retreatsAllowed;
        this.debug = debug;
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            try {
                mapping.put(e.getKey(), CombatAssignmentType.valueOf(e.getValue()));
                log.info("Fleet Commander: order \"" + e.getKey() + "\" mapped to " + e.getValue());
            } catch (IllegalArgumentException ex) {
                log.warn("Fleet Commander: orderMapping " + e.getKey() + " -> unknown assignment type "
                        + e.getValue());
            }
        }
    }

    public CombatAssignmentType typeOf(String order) {
        return mapping.get(order);
    }

    public boolean usesCommandPoints() {
        return useCp;
    }

    public boolean retreatsAllowed() {
        return retreatsAllowed;
    }

    /** Battle time, set by the commander every frame (for the command window and announcement pacing). */
    void setTime(double now) {
        this.now = now;
    }

    /**
     * Whether an order of this priority may be given now: always when orders are free or a command window is open,
     * otherwise only if the CP budget allows opening one.
     */
    public boolean canSpend(CpPriority p) {
        if (!useCp || now < windowUntil) return true;
        int left = tm.getCommandPointsLeft();
        switch (p) {
            case HIGH: return left > 0;
            case LOW: return left > cpReserve + 2;
            default: return left > cpReserve;
        }
    }

    public int commandPointsLeft() {
        return tm.getCommandPointsLeft();
    }

    /** The useCommandPoint flag for the next order: true opens a window (spends one CP), false inside a window. */
    private boolean spend() {
        if (!useCp || now < windowUntil) return false;
        windowUntil = now + commandWindow;
        if (debug) log.info("Fleet Commander: command point spent, " + tm.getCommandPointsLeft() + " left");
        return true;
    }

    /**
     * Creates an assignment of the named order on a target (enemy or own ship, objective, waypoint). Weight 0
     * stops the engine filling it with other ships, as vanilla does for escorts; pass autoFill to let it.
     * Null when the CP budget says no or the engine refused.
     */
    public AssignmentInfo create(String order, AssignmentTargetAPI target, CpPriority p, boolean autoFill) {
        CombatAssignmentType type = mapping.get(order);
        if (type == null || target == null || !canSpend(p)) return null;
        AssignmentInfo info = tm.createAssignment(type, target, spend());
        if (info == null) return null;
        if (!autoFill) tm.setAssignmentWeight(info, 0f);
        ours.add(info);
        ordersGiven++;
        if (debug) log.info("Fleet Commander: created " + type + " on " + describe(target));
        return info;
    }

    /** Puts a ship on an assignment (a direct order). CP were already spent by create(). */
    public void give(DeployedFleetMemberAPI member, AssignmentInfo info) {
        if (member == null || info == null) return;
        tm.giveAssignment(member, info, false);
    }

    /** Moves one ship to a point: waypoint + RALLY_CIVILIAN given to that ship (vanilla's own trick). */
    public AssignmentInfo moveTo(DeployedFleetMemberAPI member, Vector2f loc, CpPriority p) {
        if (member == null || !canSpend(p)) return null;
        AssignmentTargetAPI wp = tm.createWaypoint2(loc, false);
        AssignmentInfo info = create("move", wp, p, false);
        give(member, info);
        return info;
    }

    public AssignmentTargetAPI waypoint(Vector2f loc) {
        return tm.createWaypoint2(loc, false);
    }

    public void remove(AssignmentInfo info) {
        if (info == null) return;
        ours.remove(info);
        tm.removeAssignment(info);
    }

    public boolean retreat(DeployedFleetMemberAPI member, boolean direct, CpPriority p) {
        if (!retreatsAllowed || member == null || !member.canBeGivenRetreatOrders() || !canSpend(p)) return false;
        tm.orderRetreat(member, spend(), direct);
        ordersGiven++;
        return true;
    }

    /** Back to the ship's own judgement (Search &amp; Destroy direct order). */
    public void free(DeployedFleetMemberAPI member) {
        if (member != null) tm.orderSearchAndDestroy(member, false);
    }

    public void fullAssault() {
        tm.setFullAssault(true);
        ordersGiven++;
    }

    public boolean fullRetreat() {
        if (!retreatsAllowed || tm.isPreventFullRetreat()) return false;
        tm.orderFullRetreat();
        ordersGiven++;
        return true;
    }

    /** As vanilla's strategy AIs do after a pass: reassign, drop empty engine-made assignments, clear waypoints. */
    public void tidy() {
        tm.reassign();
        scratch.clear();
        List<AssignmentInfo> all = tm.getAllAssignments();
        for (int i = 0; i < all.size(); i++) {
            AssignmentInfo a = all.get(i);
            if (ours.contains(a)) continue;
            CombatAssignmentType t = a.getType();
            if (t == CombatAssignmentType.CONTROL || t == CombatAssignmentType.CAPTURE
                    || t == CombatAssignmentType.DEFEND || t == CombatAssignmentType.ASSAULT
                    || t == CombatAssignmentType.AVOID || t == CombatAssignmentType.IGNORE) continue;
            if (a.getAssignedMembers().isEmpty()) scratch.add(a);
        }
        for (int i = 0; i < scratch.size(); i++) tm.removeAssignment(scratch.get(i));
        // forget ours that the engine dropped (e.g. objective assignments turned into CONTROL)
        ours.removeIf(a -> !containsSame(all, a));
        tm.clearEmptyWaypoints();
    }

    /** Whether the engine still has this assignment. */
    public boolean exists(AssignmentInfo info) {
        return info != null && containsSame(tm.getAllAssignments(), info);
    }

    private static boolean containsSame(List<AssignmentInfo> list, AssignmentInfo a) {
        for (int i = 0; i < list.size(); i++) if (list.get(i) == a) return true;
        return false;
    }

    public AssignmentInfo assignmentOf(DeployedFleetMemberAPI member) {
        return member == null || member.getShip() == null ? null : tm.getAssignmentFor(member.getShip());
    }

    public AssignmentInfo assignmentOn(AssignmentTargetAPI target) {
        return tm.getAssignmentInfoForTarget(target);
    }

    public void say(String text) {
        if (!announce || engine.getCombatUI() == null || now - lastSay < 2.5) return;
        lastSay = now;
        engine.getCombatUI().addMessage(1, PREFIX, "Enemy commander: ", TEXT, text);
    }

    public int ordersGiven() {
        return ordersGiven;
    }

    static String describe(AssignmentTargetAPI t) {
        if (t instanceof DeployedFleetMemberAPI && ((DeployedFleetMemberAPI) t).getShip() != null) {
            return ((DeployedFleetMemberAPI) t).getShip().getHullSpec().getHullName();
        }
        if (t instanceof com.fs.starfarer.api.combat.BattleObjectiveAPI) {
            return ((com.fs.starfarer.api.combat.BattleObjectiveAPI) t).getDisplayName();
        }
        return t == null ? "nothing" : "waypoint";
    }
}
