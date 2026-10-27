package fleetcommander.tactics;

import com.fs.starfarer.api.combat.AssignmentTargetAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import fleetcommander.combat.FleetCommander;
import fleetcommander.combat.ShipFacts;
import fleetcommander.doctrine.Validation;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * battle_line: one Defend point a little ahead of our line's centre, toward the enemy, given directly to the line
 * ships, so capitals and cruisers stay together instead of chasing frigates. The point moves with the fleet, but
 * only when it is more than "moveThreshold" away from where it should be (each move is a new order).
 */
final class BattleLineTactic extends Tactic {
    ShipSpec ships;
    float offset, moveThreshold;

    private AssignmentInfo info;
    private final Vector2f point = new Vector2f();
    private final List<ShipFacts> line = new ArrayList<>();

    @Override
    protected String[] keys() {
        return new String[]{"ships", "offset", "moveThreshold"};
    }

    @Override
    protected void configure(JSONObject spec, Validation v, String path, Set<String> roles) {
        ships = ShipSpec.parse(v.object(spec, "ships", path), v, path + ".ships", roles, 0, 2, 99);
        offset = (float) v.number(spec, "offset", path, 600);
        moveThreshold = (float) v.number(spec, "moveThreshold", path, 1200);
    }

    @Override
    public void update(FleetCommander c, boolean active) {
        if (!active || c.picture().enemy.isEmpty()) {
            endAll(c);
            return;
        }
        for (int i = line.size() - 1; i >= 0; i--) {
            if (c.claimOf(line.get(i)) != this || !line.get(i).canTakeOrders()) line.remove(i);
        }
        List<ShipFacts> cands = ships.candidates(c, this, null, null, null);
        // where the line should hold: centre of the line ships, pushed toward the enemy's centre
        List<ShipFacts> all = new ArrayList<>(line);
        for (ShipFacts f : cands) if (!all.contains(f)) all.add(f);
        if (all.size() < ships.min) {
            endAll(c);
            return;
        }
        float x = 0, y = 0, w = 0;
        for (ShipFacts f : all) {
            x += f.ship.getLocation().x * f.weight;
            y += f.ship.getLocation().y * f.weight;
            w += f.weight;
        }
        Vector2f centre = new Vector2f(x / w, y / w);
        Vector2f enemy = c.picture().enemyCenter;
        float dx = enemy.x - centre.x, dy = enemy.y - centre.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        Vector2f want = len < 1 ? centre : new Vector2f(centre.x + dx / len * offset, centre.y + dy / len * offset);

        if (info == null || !c.orders().exists(info)
                || Math.sqrt(ShipSpec.distSq(point, want)) > moveThreshold) {
            AssignmentTargetAPI wp = c.orders().waypoint(want);
            AssignmentInfo next = c.orders().create("defend", wp, cp, false);
            if (next == null) return;
            if (info != null) c.orders().remove(info);
            info = next;
            point.set(want);
            for (ShipFacts f : line) c.orders().give(f.member, info);
            c.debug(id + ": line point moved");
        }
        for (ShipFacts f : cands) {
            if (line.size() >= ships.max) break;
            if (line.contains(f)) continue;
            c.claim(f, this);
            line.add(f);
            c.orders().give(f.member, info);
        }
    }

    @Override
    public void onShipLost(FleetCommander c, ShipFacts ship) {
        line.remove(ship);
    }

    @Override
    public void endAll(FleetCommander c) {
        if (info != null) c.orders().remove(info);
        info = null;
        for (ShipFacts f : line) if (c.claimOf(f) == this) c.release(f, this);
        line.clear();
    }
}
