package fleetcommander;

import fleetcommander.doctrine.Validation;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Global switches from data/config/fleet_commander/settings.json (DESIGN.md section 6). */
public final class FCSettings {
    public boolean enabled = true;
    public boolean enableInSimulator = true;
    public boolean enableInMissions = true;
    /** Scales think interval and reaction time: 2.0 = twice as fast. */
    public double difficulty = 1.0;
    /** Null = each doctrine decides. */
    public Boolean useCommandPoints = null;
    public int bonusCommandPoints = 0;
    public boolean announceOrders = true;
    public final Map<String, String> factionDoctrines = new HashMap<>();
    /** Our order name -> CombatAssignmentType name, overriding the built-in mapping (e.g. after the order test). */
    public final Map<String, String> orderMapping = new HashMap<>();
    public boolean debugLog = false;
    public boolean logPlayerOrders = true;

    public static FCSettings parse(JSONObject j, Validation v) {
        FCSettings s = new FCSettings();
        if (j == null) return s;
        String p = "(settings)";
        v.checkKeys(j, p, "enabled", "enableInSimulator", "enableInMissions", "difficulty", "useCommandPoints",
                "bonusCommandPoints", "announceOrders", "factionDoctrines", "orderMapping", "debugLog",
                "logPlayerOrders");
        s.enabled = v.bool(j, "enabled", p, s.enabled);
        s.enableInSimulator = v.bool(j, "enableInSimulator", p, s.enableInSimulator);
        s.enableInMissions = v.bool(j, "enableInMissions", p, s.enableInMissions);
        s.difficulty = v.number(j, "difficulty", p, s.difficulty);
        if (s.difficulty <= 0.05) {
            v.error(p + ".difficulty", "must be above 0.05");
            s.difficulty = 1.0;
        }
        s.useCommandPoints = v.optBool(j, "useCommandPoints", p);
        s.bonusCommandPoints = v.integer(j, "bonusCommandPoints", p, 0);
        s.announceOrders = v.bool(j, "announceOrders", p, s.announceOrders);
        s.debugLog = v.bool(j, "debugLog", p, s.debugLog);
        s.logPlayerOrders = v.bool(j, "logPlayerOrders", p, s.logPlayerOrders);
        readMap(v.object(j, "factionDoctrines", p), s.factionDoctrines);
        readMap(v.object(j, "orderMapping", p), s.orderMapping);
        return s;
    }

    private static void readMap(JSONObject o, Map<String, String> into) {
        if (o == null) return;
        for (Iterator<?> it = o.keys(); it.hasNext(); ) {
            String k = String.valueOf(it.next());
            if (!k.startsWith("_")) into.put(k, o.optString(k));
        }
    }
}
