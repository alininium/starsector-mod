package fleetcommander.tactics;

import fleetcommander.doctrine.Validation;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Tactic types by name (DOCTRINE_FORMAT.md section 13). Another mod can add types with {@link #register} from its
 * own onApplicationLoad; registering marks the config for a reload, so doctrines using the new type load on the
 * next access even though this mod read its config first.
 */
public final class FCTactics {
    private static final Map<String, Supplier<? extends Tactic>> TYPES = new LinkedHashMap<>();

    static {
        TYPES.put("retreat_damaged", RetreatDamagedTactic::new);
        TYPES.put("hunt_isolated", HuntIsolatedTactic::new);
        TYPES.put("pounce", PounceTactic::new);
        TYPES.put("focus_fire", FocusFireTactic::new);
        TYPES.put("escort", EscortTactic::new);
        TYPES.put("objectives", ObjectivesTactic::new);
        TYPES.put("fleet_stance", FleetStanceTactic::new);
        TYPES.put("avoid", AvoidTactic::new);
        TYPES.put("battle_line", BattleLineTactic::new);
    }

    private FCTactics() {
    }

    /** Adds a tactic type (from another mod). Its class must extend {@link Tactic}. */
    public static synchronized void register(String type, Supplier<? extends Tactic> factory) {
        TYPES.put(type, factory);
        fleetcommander.FCConfig.markDirty();
    }

    public static synchronized Set<String> types() {
        return TYPES.keySet();
    }

    /** Builds and configures a tactic from its doctrine entry; null if the type is unknown or the entry is invalid. */
    public static synchronized Tactic create(JSONObject spec, Validation v, String path, Set<String> roles,
                                             Set<String> phases) {
        String type = spec.optString("type", "");
        Supplier<? extends Tactic> f = TYPES.get(type);
        if (f == null) {
            v.error(path + ".type", type.isEmpty() ? "missing \"type\""
                    : "unknown tactic type \"" + type + "\" (known: " + TYPES.keySet() + ")");
            return null;
        }
        int before = v.errorCount();
        Tactic t = f.get();
        t.configureCommon(spec, v, path, roles, phases);
        return v.errorCount() == before ? t : null;
    }

    /** Doctrine.TacticChecker: validate an entry without keeping it. */
    public static void check(JSONObject spec, Validation v, String path, Set<String> roles, Set<String> phases) {
        create(spec, v, path, roles, phases);
    }
}
