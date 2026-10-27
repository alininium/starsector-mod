package fleetcommander.tactics;

import fleetcommander.combat.FleetCommander;
import fleetcommander.combat.ShipFacts;
import fleetcommander.doctrine.Validation;
import org.json.JSONObject;

import java.util.Set;

/**
 * fleet_stance: a fleet-wide order, given once when "when" holds: full_assault (mop up when clearly winning;
 * suspends the other tactics, whose new orders would cancel it) or full_retreat (when hopeless; respects
 * fight-to-the-last battles).
 */
final class FleetStanceTactic extends Tactic {
    String order;
    boolean done;

    @Override
    protected String[] keys() {
        return new String[]{"order"};
    }

    @Override
    protected void configure(JSONObject spec, Validation v, String path, Set<String> roles) {
        order = v.string(spec, "order", path, "full_assault");
        if (!order.equals("full_assault") && !order.equals("full_retreat")) {
            v.error(path + ".order", "expected full_assault or full_retreat");
        }
        if (!spec.has("when")) v.warn(path, "no \"when\": the order is given as soon as the battle starts");
    }

    @Override
    public boolean suspendedByFullAssault() {
        return false;
    }

    @Override
    public void update(FleetCommander c, boolean active) {
        if (!active || done) return;
        if (order.equals("full_assault")) {
            c.orders().fullAssault();
            c.startFullAssault();
            done = true;
            String text = announceText(c, "all ships, full assault!", null, 0);
            if (text != null) c.say(this, text);
        } else if (c.orders().fullRetreat()) {
            c.startFullRetreat();
            done = true;
            String text = announceText(c, "all ships, retreat!", null, 0);
            if (text != null) c.say(this, text);
        }
    }

    @Override
    public void onShipLost(FleetCommander c, ShipFacts ship) {
    }

    @Override
    public void endAll(FleetCommander c) {
    }
}
