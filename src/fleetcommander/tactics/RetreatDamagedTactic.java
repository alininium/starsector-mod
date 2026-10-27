package fleetcommander.tactics;

import fleetcommander.combat.FCContext;
import fleetcommander.combat.FleetCommander;
import fleetcommander.combat.ShipFacts;
import fleetcommander.doctrine.Validation;
import org.json.JSONObject;

import java.util.Set;

/**
 * retreat_damaged: pull back our ships that match "ships.require" (e.g. hull below 30%) before they die, keeping
 * the last N combat ships and (optionally) the flagship on the field. Never runs in battles where the AI may not
 * retreat (fight-to-the-last encounters).
 */
final class RetreatDamagedTactic extends Tactic {
    ShipSpec ships;
    int keepLast;
    boolean keepFlagship;
    boolean direct;

    @Override
    protected int defaultMaxTasks() {
        return 99;
    }

    @Override
    protected String[] keys() {
        return new String[]{"ships", "keepLast", "keepFlagship", "order"};
    }

    @Override
    protected void configure(JSONObject spec, Validation v, String path, Set<String> roles) {
        ships = ShipSpec.parse(v.object(spec, "ships", path), v, path + ".ships", roles, 0, 0, 99);
        keepLast = v.integer(spec, "keepLast", path, 1);
        keepFlagship = v.bool(spec, "keepFlagship", path, true);
        String order = v.string(spec, "order", path, "retreat");
        if (!order.equals("retreat") && !order.equals("retreat_direct")) {
            v.error(path + ".order", "expected retreat or retreat_direct");
        }
        direct = order.equals("retreat_direct");
    }

    @Override
    public boolean suspendedByFullAssault() {
        return false;
    }

    @Override
    public void update(FleetCommander c, boolean active) {
        if (!active || !c.orders().retreatsAllowed()) return;
        int combatShips = 0;
        for (ShipFacts f : c.picture().own) {
            if (!f.ship.isRetreating() && f.get("civilian") < 0.5) combatShips++;
        }
        FCContext ctx = c.ctx();
        int ordered = 0;
        for (ShipFacts f : c.picture().own) {
            if (combatShips <= keepLast || ordered >= maxTasks) break;
            if (f.ship.isRetreating() || f.member == null || !f.member.canBeGivenRetreatOrders()) continue;
            if (keepFlagship && f.get("flagship") == 1.0) continue;
            if (ships.roles != null && !ships.roles.contains(f.role)) continue;
            if (!c.canClaim(f, this)) continue;
            ctx.clear();
            ctx.subject = f;
            boolean hurt = ships.require.test(ctx);
            ctx.clear();
            if (!hurt) continue;
            if (!c.orders().retreat(f.member, direct, cp)) continue;
            c.claim(f, this);
            combatShips--;
            ordered++;
            String text = announceText(c, "{target} is pulling back", f, 1);
            if (text != null) c.say(this, text);
            c.debug(id + ": retreat " + f.displayName());
        }
    }

    @Override
    public void onShipLost(FleetCommander c, ShipFacts ship) {
    }

    @Override
    public void endAll(FleetCommander c) {
        // retreat orders are final
    }
}
