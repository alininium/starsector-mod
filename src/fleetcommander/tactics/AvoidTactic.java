package fleetcommander.tactics;

import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.ShipAPI;
import fleetcommander.combat.FCContext;
import fleetcommander.combat.FleetCommander;
import fleetcommander.combat.ShipFacts;
import fleetcommander.doctrine.Condition;
import fleetcommander.doctrine.Doctrine;
import fleetcommander.doctrine.Facts;
import fleetcommander.doctrine.Validation;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * avoid: mark enemy ships that are too strong to fight right now (a juggernaut with a full screen) with Avoid (or
 * Ignore), until "release" holds for that ship (evaluated with the ship as subject) or the tactic turns off.
 */
final class AvoidTactic extends Tactic {
    TargetSpec targets;
    Condition release = Condition.FALSE;
    String order;

    private final Map<ShipAPI, AssignmentInfo> marked = new LinkedHashMap<>();

    @Override
    protected int defaultMaxTasks() {
        return 2;
    }

    @Override
    protected String[] keys() {
        return new String[]{"targets", "release", "order"};
    }

    @Override
    protected void configure(JSONObject spec, Validation v, String path, Set<String> roles) {
        targets = TargetSpec.parse(v.object(spec, "targets", path), v, path + ".targets", Facts.Scope.SHIP);
        if (spec.has("release")) {
            release = Condition.parse(spec.opt("release"), v, path + ".release", Doctrine.scopes(Facts.Scope.SHIP));
        }
        order = v.string(spec, "order", path, "avoid");
        if (!order.equals("avoid") && !order.equals("ignore")) v.error(path + ".order", "expected avoid or ignore");
    }

    @Override
    public void update(FleetCommander c, boolean active) {
        if (!active) {
            endAll(c);
            return;
        }
        FCContext ctx = c.ctx();
        for (Map.Entry<ShipAPI, AssignmentInfo> e : new ArrayList<>(marked.entrySet())) {
            ShipFacts f = c.picture().facts(e.getKey());
            boolean let = f == null || !f.isAlive();
            if (!let) {
                ctx.clear();
                ctx.subject = f;
                let = release.test(ctx);
                ctx.clear();
            }
            if (let) {
                marked.remove(e.getKey());
                c.orders().remove(e.getValue());
                c.debug(id + ": no longer avoiding " + (f != null ? f.displayName() : "a lost target"));
            }
        }
        while (marked.size() < maxTasks) {
            ShipFacts best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (ShipFacts e : c.picture().enemy) {
                if (e.member == null || marked.containsKey(e.ship)) continue;
                double s = targets.evaluate(c, e, 0);
                if (!Double.isNaN(s) && s > bestScore) {
                    best = e;
                    bestScore = s;
                }
            }
            if (best == null) break;
            AssignmentInfo info = c.orders().create(order, best.member, cp, true);
            if (info == null) break;
            marked.put(best.ship, info);
            String text = announceText(c, "avoid {target}", best, 0);
            if (text != null) c.say(this, text);
        }
    }

    @Override
    public boolean suspendedByFullAssault() {
        return true;
    }

    @Override
    public void onShipLost(FleetCommander c, ShipFacts ship) {
    }

    @Override
    public void endAll(FleetCommander c) {
        for (AssignmentInfo info : marked.values()) c.orders().remove(info);
        marked.clear();
    }
}
