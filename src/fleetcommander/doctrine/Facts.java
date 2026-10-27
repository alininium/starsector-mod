package fleetcommander.doctrine;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * The names conditions and scores may use (docs/DOCTRINE_FORMAT.md section 4). Names are checked when a doctrine
 * loads, so a typo is reported instead of silently reading 0.
 *
 * A name is either prefixed ("battle.time", "own.weight", "enemy.share.carrier", "target.hull", "pack.leash") or
 * bare ("hull"), which means the subject of the condition: a ship or an objective, depending on where it is used.
 */
public final class Facts {

    /** What a condition or score may refer to, depending on where it appears in a doctrine. */
    public enum Scope {
        /** Bare names are ship facts. */
        SHIP,
        /** Bare names are objective facts. */
        OBJECTIVE,
        /** "target.*" is available. */
        TARGET,
        /** "pack.*" is available. */
        PACK
    }

    public static final Set<String> BATTLE = set(
            "time", "strengthRatio", "totalRatio", "objectives", "objectivesHeld", "objectivesEnemy", "cp",
            "isStationBattle", "playerFlagshipVisible");

    public static final Set<String> FLEET = set(
            "weight", "ships", "lossFraction", "avgSpeed", "avgArmor",
            "share.frigate", "share.destroyer", "share.cruiser", "share.capital",
            "share.carrier", "share.phase", "share.civilian", "share.shieldless", "share.omniShield",
            "damage.kinetic", "damage.he", "damage.energy", "damage.frag", "centerDistance");

    public static final Set<String> SHIP = set(
            "value", "weight", "dp", "size.frigate", "size.destroyer", "size.cruiser", "size.capital",
            "hull", "hullMissing", "armor", "armorMissing", "flux", "hardFlux", "overloaded", "venting",
            "shieldDown", "noShield", "phase", "phased", "phaseFluxed", "carrier", "civilian", "cr", "lowCR",
            "speed", "isolation", "support", "threatHere", "strayDistance", "distance", "nearestDistance",
            "retreating", "fluxLocked", "playerFlagship", "flagship", "officer", "damageMatch", "attackers", "age");

    public static final Set<String> OBJECTIVE = set(
            "distance", "nearestDistance", "threatHere", "supportHere", "held", "enemyHeld", "neutral");

    public static final Set<String> PACK = set("weight", "ships", "hullLost", "leash");

    /** Context-free conditions: selection, phases, tactic "when". */
    public static final EnumSet<Scope> GLOBAL = EnumSet.noneOf(Scope.class);

    private Facts() {
    }

    private static Set<String> set(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    /** Null if the name is valid in the given scopes, otherwise a message explaining why not. */
    public static String check(String name, EnumSet<Scope> scopes) {
        int dot = name.indexOf('.');
        String head = dot < 0 ? "" : name.substring(0, dot);
        String rest = dot < 0 ? name : name.substring(dot + 1);
        switch (head) {
            case "battle":
                return BATTLE.contains(rest) ? null : "unknown battle fact \"" + name + "\"";
            case "own":
            case "enemy":
                return FLEET.contains(rest) ? null : "unknown fleet fact \"" + name + "\"";
            case "target":
                if (!scopes.contains(Scope.TARGET)) return "\"target.*\" is not available here";
                return SHIP.contains(rest) ? null : "unknown ship fact \"" + name + "\"";
            case "pack":
                if (!scopes.contains(Scope.PACK)) return "\"pack.*\" is not available here";
                return PACK.contains(rest) ? null : "unknown pack fact \"" + name + "\"";
            default:
                break;
        }
        // bare name (may itself contain a dot, e.g. "size.frigate")
        if (scopes.contains(Scope.SHIP)) {
            return SHIP.contains(name) ? null : "unknown ship fact \"" + name + "\"";
        }
        if (scopes.contains(Scope.OBJECTIVE)) {
            return OBJECTIVE.contains(name) ? null : "unknown objective fact \"" + name + "\"";
        }
        return "\"" + name + "\" needs a prefix here (battle., own. or enemy.)";
    }
}
