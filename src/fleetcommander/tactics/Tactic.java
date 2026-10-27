package fleetcommander.tactics;

import fleetcommander.combat.FCOrders;
import fleetcommander.combat.FleetCommander;
import fleetcommander.combat.ShipFacts;
import fleetcommander.doctrine.Condition;
import fleetcommander.doctrine.Facts;
import fleetcommander.doctrine.Validation;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One tactic instance from a doctrine (DOCTRINE_FORMAT.md section 7). A fresh instance is made for every battle, so
 * fields may hold per-battle state. Subclasses read their own fields in {@link #configure} and act in
 * {@link #update}.
 */
public abstract class Tactic {
    static final String[] COMMON_KEYS = {"id", "type", "enabled", "priority", "phases", "when", "cpPriority",
            "maxTasks", "interval", "announce"};

    public String id;
    public String type;
    public int priority;
    /** Null = all phases. */
    Set<String> phases;
    Condition when = Condition.TRUE;
    FCOrders.CpPriority cp = FCOrders.CpPriority.NORMAL;
    int maxTasks;
    /** Seconds between updates; 0 = the doctrine's think interval. */
    public double interval;
    String announce;

    /** Countdown to the next update, managed by the commander. */
    public double timer;
    /** Set while a fleet-wide Full Assault suspends this tactic. */
    public boolean suspended;

    /** Default for maxTasks when the doctrine doesn't say. */
    protected int defaultMaxTasks() {
        return 1;
    }

    /** Keys this type accepts besides the common ones. */
    protected abstract String[] keys();

    /** Reads type-specific fields. Record problems in {@code v}; never touch the game here (offline checks). */
    protected abstract void configure(JSONObject spec, Validation v, String path, Set<String> roles);

    /** Called when due. {@code active} is false when the phase or "when" excludes the tactic: wind down. */
    public abstract void update(FleetCommander c, boolean active);

    /** Another tactic took one of our ships. */
    public abstract void onShipLost(FleetCommander c, ShipFacts ship);

    /** End everything (phase change, full assault, battle end). */
    public abstract void endAll(FleetCommander c);

    /** Adds our ships to each target's "attackers" count; called after every pass. */
    public void countAttackers() {
    }

    /** Whether a fleet-wide Full Assault suspends this tactic (it would cancel the assault). */
    public boolean suspendedByFullAssault() {
        return true;
    }

    public boolean isActive(FleetCommander c) {
        if (phases != null && !phases.contains(c.phase())) return false;
        c.ctx().clear();
        return when.test(c.ctx());
    }

    final void configureCommon(JSONObject spec, Validation v, String path, Set<String> roles,
                               Set<String> knownPhases) {
        List<String> allowed = new ArrayList<>(Arrays.asList(COMMON_KEYS));
        Collections.addAll(allowed, keys());
        v.checkKeys(spec, path, allowed.toArray(new String[0]));
        id = v.string(spec, "id", path, "?");
        type = v.string(spec, "type", path, "?");
        priority = v.integer(spec, "priority", path, 50);
        maxTasks = v.integer(spec, "maxTasks", path, defaultMaxTasks());
        interval = v.number(spec, "interval", path, 0);
        if (interval != 0 && interval < 0.25) v.error(path + ".interval", "must be at least 0.25 seconds");
        announce = v.string(spec, "announce", path, null);
        String cpName = v.string(spec, "cpPriority", path, "normal");
        switch (cpName) {
            case "high": cp = FCOrders.CpPriority.HIGH; break;
            case "low": cp = FCOrders.CpPriority.LOW; break;
            case "normal": cp = FCOrders.CpPriority.NORMAL; break;
            default: v.error(path + ".cpPriority", "expected high, normal or low");
        }
        if (spec.has("phases")) {
            JSONArray arr = spec.optJSONArray("phases");
            if (arr == null) {
                v.error(path + ".phases", "expected a list of phase ids");
            } else {
                phases = new HashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    String p = arr.optString(i);
                    if (!knownPhases.contains(p)) {
                        v.warn(path + ".phases", "phase \"" + p + "\" is not defined in this doctrine "
                                + knownPhases);
                    }
                    phases.add(p);
                }
            }
        }
        when = Condition.parse(spec.opt("when"), v, path + ".when", Facts.GLOBAL);
        configure(spec, v, path, roles);
    }

    /** Announcement text with {target}, {count}, {doctrine} filled in; null when nothing should be said. */
    protected String announceText(FleetCommander c, String fallback, ShipFacts target, int count) {
        String text = announce != null ? announce : fallback;
        if (text == null || text.isEmpty()) return null;
        return text.replace("{target}", target == null ? "" : target.displayName())
                .replace("{count}", String.valueOf(count))
                .replace("{doctrine}", c.doctrine().name);
    }
}
