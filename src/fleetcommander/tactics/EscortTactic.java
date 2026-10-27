package fleetcommander.tactics;

import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.ShipAPI;
import fleetcommander.combat.FCContext;
import fleetcommander.combat.FCOrders;
import fleetcommander.combat.FleetCommander;
import fleetcommander.combat.ShipFacts;
import fleetcommander.doctrine.Condition;
import fleetcommander.doctrine.Doctrine;
import fleetcommander.doctrine.Facts;
import fleetcommander.doctrine.Validation;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * escort: screens for our own ships that match "escortees" (carriers, the flagship...). One assignment per
 * escortee, weight 0, escorts given directly, as vanilla's Threat AI does. Dead escorts are replaced; the task
 * ends when the escortee dies or retreats.
 */
final class EscortTactic extends Tactic {
    Condition escortees = Condition.FALSE;
    ShipSpec ships;
    String order;

    static final class Escort {
        ShipFacts escortee;
        AssignmentInfo info;
        final List<ShipFacts> escorts = new ArrayList<>();
    }

    private final Map<ShipAPI, Escort> byEscortee = new LinkedHashMap<>();

    @Override
    protected int defaultMaxTasks() {
        return 99;
    }

    @Override
    protected String[] keys() {
        return new String[]{"escortees", "ships", "order"};
    }

    @Override
    protected void configure(JSONObject spec, Validation v, String path, Set<String> roles) {
        escortees = Condition.parse(spec.opt("escortees"), v, path + ".escortees",
                Doctrine.scopes(Facts.Scope.SHIP));
        if (!spec.has("escortees")) v.error(path, "missing \"escortees\" (which of our ships get escorts)");
        ships = ShipSpec.parse(v.object(spec, "ships", path), v, path + ".ships", roles, 0, 1, 1);
        order = v.string(spec, "order", path, "escort_light");
        if (!order.startsWith("escort_") || !FCOrders.isKnownOrder(order)) {
            v.error(path + ".order", "expected escort_light, escort_medium or escort_heavy");
        }
    }

    @Override
    public void update(FleetCommander c, boolean active) {
        if (!active) {
            endAll(c);
            return;
        }
        // end tasks whose escortee is gone
        for (Escort e : new ArrayList<>(byEscortee.values())) {
            if (!e.escortee.isAlive() || e.escortee.ship.isRetreating()
                    || c.picture().facts(e.escortee.ship) == null) {
                end(c, e);
                continue;
            }
            for (int i = e.escorts.size() - 1; i >= 0; i--) {
                ShipFacts f = e.escorts.get(i);
                if (!f.canTakeOrders() || c.claimOf(f) != this) e.escorts.remove(i);
            }
        }
        FCContext ctx = c.ctx();
        Set<ShipFacts> busyEscortees = new HashSet<>();
        for (Escort e : byEscortee.values()) busyEscortees.add(e.escortee);
        for (ShipFacts f : c.picture().own) {
            if (f.member == null || f.ship.isRetreating()) continue;
            Escort e = byEscortee.get(f.ship);
            if (e == null) {
                if (byEscortee.size() >= maxTasks || c.claimOf(f) == this) continue; // an escort can't be escorted
                ctx.clear();
                ctx.subject = f;
                boolean wants = escortees.test(ctx);
                ctx.clear();
                if (!wants) continue;
                e = new Escort();
                e.escortee = f;
                byEscortee.put(f.ship, e);
                busyEscortees.add(f);
            }
            fill(c, e, busyEscortees);
        }
    }

    private void fill(FleetCommander c, Escort e, Set<ShipFacts> exclude) {
        if (e.escorts.size() >= ships.max) return;
        List<ShipFacts> cands = ships.candidates(c, this, e.escortee, e.escortee.ship.getLocation(), exclude);
        for (ShipFacts f : cands) {
            if (e.escorts.size() >= ships.max) break;
            if (e.info == null || !c.orders().exists(e.info)) {
                e.info = c.orders().create(order, e.escortee.member, cp, false);
                if (e.info == null) return;
                String text = announceText(c, null, e.escortee, 1);
                if (text != null) c.say(this, text);
            }
            c.claim(f, this);
            e.escorts.add(f);
            c.orders().give(f.member, e.info);
            c.debug(id + ": " + f.displayName() + " escorts " + e.escortee.displayName());
        }
    }

    private void end(FleetCommander c, Escort e) {
        byEscortee.remove(e.escortee.ship);
        if (e.info != null) c.orders().remove(e.info);
        for (ShipFacts f : e.escorts) {
            if (c.claimOf(f) == this) c.release(f, this);
        }
    }

    @Override
    public void onShipLost(FleetCommander c, ShipFacts ship) {
        for (Escort e : byEscortee.values()) e.escorts.remove(ship);
    }

    @Override
    public void endAll(FleetCommander c) {
        for (Escort e : new ArrayList<>(byEscortee.values())) end(c, e);
    }
}
