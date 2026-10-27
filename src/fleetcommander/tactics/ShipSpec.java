package fleetcommander.tactics;

import fleetcommander.combat.FCContext;
import fleetcommander.combat.FleetCommander;
import fleetcommander.combat.ShipFacts;
import fleetcommander.doctrine.Condition;
import fleetcommander.doctrine.Doctrine;
import fleetcommander.doctrine.Facts;
import fleetcommander.doctrine.Validation;
import org.json.JSONArray;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The "ships" block: which of our ships carry a task out (DOCTRINE_FORMAT.md section 9). */
final class ShipSpec {
    enum Pick { NEAREST, FASTEST, STRONGEST, WEAKEST }

    /** Null = any role. */
    Set<String> roles;
    Condition require = Condition.TRUE;
    Pick pick = Pick.NEAREST;
    double strength;
    int min, max;

    static ShipSpec parse(JSONObject j, Validation v, String path, Set<String> knownRoles, double defStrength,
                          int defMin, int defMax) {
        ShipSpec s = new ShipSpec();
        s.strength = defStrength;
        s.min = defMin;
        s.max = defMax;
        if (j == null) return s;
        v.checkKeys(j, path, "roles", "require", "pick", "strength", "min", "max");
        JSONArray r = j.optJSONArray("roles");
        if (j.has("roles") && r == null) v.error(path + ".roles", "expected a list of role ids");
        if (r != null) {
            s.roles = new HashSet<>();
            for (int i = 0; i < r.length(); i++) {
                String role = r.optString(i);
                if (!knownRoles.contains(role)) v.warn(path + ".roles", "unknown role \"" + role + "\" " + knownRoles);
                s.roles.add(role);
            }
        }
        s.require = Condition.parse(j.opt("require"), v, path + ".require",
                Doctrine.scopes(Facts.Scope.SHIP, Facts.Scope.TARGET));
        String pick = v.string(j, "pick", path, "nearest");
        switch (pick) {
            case "nearest": s.pick = Pick.NEAREST; break;
            case "fastest": s.pick = Pick.FASTEST; break;
            case "strongest": s.pick = Pick.STRONGEST; break;
            case "weakest": s.pick = Pick.WEAKEST; break;
            default: v.error(path + ".pick", "expected nearest, fastest, strongest or weakest");
        }
        s.strength = v.number(j, "strength", path, defStrength);
        s.min = v.integer(j, "min", path, defMin);
        s.max = v.integer(j, "max", path, defMax);
        if (s.max < s.min) v.error(path, "max is below min");
        return s;
    }

    /**
     * Our ships this tactic may use for a task at {@code where} (target ship or objective), best first:
     * able to take orders, of a listed role, claimable by the tactic, and passing "require"
     * (with "target.*" = {@code target} when given).
     */
    List<ShipFacts> candidates(FleetCommander c, Tactic t, ShipFacts target, Vector2f where,
                               Set<ShipFacts> exclude) {
        List<ShipFacts> out = new ArrayList<>();
        FCContext ctx = c.ctx();
        for (ShipFacts f : c.picture().own) {
            if (!f.canTakeOrders() || (exclude != null && exclude.contains(f))) continue;
            if (roles != null && !roles.contains(f.role)) continue;
            if (c.claimOf(f) == t || !c.canClaim(f, t)) continue; // already busy with this tactic, or taken
            ctx.clear();
            ctx.subject = f;
            ctx.target = target;
            boolean ok = require.test(ctx);
            ctx.clear();
            if (ok) out.add(f);
        }
        final Vector2f at = where;
        switch (pick) {
            case FASTEST: out.sort(Comparator.comparingDouble((ShipFacts f) -> -f.ship.getMaxSpeed())); break;
            case STRONGEST: out.sort(Comparator.comparingDouble((ShipFacts f) -> -f.weight)); break;
            case WEAKEST: out.sort(Comparator.comparingDouble((ShipFacts f) -> f.weight)); break;
            default:
                if (at != null) {
                    out.sort(Comparator.comparingDouble((ShipFacts f) -> distSq(f.ship.getLocation(), at)));
                }
        }
        return out;
    }

    static float distSq(Vector2f a, Vector2f b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return dx * dx + dy * dy;
    }
}
