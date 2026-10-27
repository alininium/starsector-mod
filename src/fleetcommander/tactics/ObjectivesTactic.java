package fleetcommander.tactics;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import fleetcommander.combat.FCContext;
import fleetcommander.combat.FleetCommander;
import fleetcommander.combat.ObjectiveFacts;
import fleetcommander.combat.ShipFacts;
import fleetcommander.doctrine.Condition;
import fleetcommander.doctrine.Doctrine;
import fleetcommander.doctrine.Facts;
import fleetcommander.doctrine.Validation;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * objectives: Capture (or Assault, when "assaultWhen" holds) the objectives that pass "targets", best score
 * first; release objectives we hold once "release" holds (default: no enemies near), so their guards go back to
 * fighting. The engine fills capture assignments with free ships as usual; "ships" can add direct picks.
 */
final class ObjectivesTactic extends Tactic {
    TargetSpec targets;
    ShipSpec ships;
    String order;
    Condition assaultWhen = Condition.FALSE;
    Condition release;

    static final class Point {
        ObjectiveFacts objective;
        AssignmentInfo info;
        final List<ShipFacts> ships = new ArrayList<>();
    }

    private final Map<BattleObjectiveAPI, Point> points = new HashMap<>();

    @Override
    protected int defaultMaxTasks() {
        return 99;
    }

    @Override
    protected String[] keys() {
        return new String[]{"targets", "ships", "order", "assaultWhen", "release"};
    }

    @Override
    protected void configure(JSONObject spec, Validation v, String path, Set<String> roles) {
        targets = TargetSpec.parse(v.object(spec, "targets", path), v, path + ".targets", Facts.Scope.OBJECTIVE);
        ships = ShipSpec.parse(v.object(spec, "ships", path), v, path + ".ships", roles, 0, 0, 0);
        order = v.string(spec, "order", path, "capture");
        if (!order.equals("capture") && !order.equals("assault")) v.error(path + ".order", "expected capture or assault");
        if (spec.has("assaultWhen")) {
            assaultWhen = Condition.parse(spec.opt("assaultWhen"), v, path + ".assaultWhen", Facts.GLOBAL);
        }
        release = spec.has("release")
                ? Condition.parse(spec.opt("release"), v, path + ".release", Doctrine.scopes(Facts.Scope.OBJECTIVE))
                : null;
    }

    @Override
    public void update(FleetCommander c, boolean active) {
        if (!active) {
            endAll(c);
            return;
        }
        FCContext ctx = c.ctx();
        List<ObjectiveFacts> wanted = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (ObjectiveFacts o : c.picture().objectives) {
            ctx.clear();
            ctx.subject = o;
            if (o.held()) {
                boolean let = release != null ? release.test(ctx) : o.get("threatHere") <= 0;
                ctx.clear();
                Point p = points.get(o.objective);
                if (p != null && let) {
                    drop(c, p, true);
                    c.debug(id + ": released " + o.objective.getDisplayName());
                } else if (p != null) {
                    releaseShips(c, p); // captured: direct picks are free again, Control stays
                }
                continue;
            }
            boolean ok = targets.require.test(ctx);
            double s = targets.score.eval(ctx) * c.noise();
            ctx.clear();
            if (!ok || s < targets.minScore) continue;
            int at = 0;
            while (at < scores.size() && scores.get(at) >= s) at++;
            wanted.add(at, o);
            scores.add(at, s);
        }
        while (wanted.size() > maxTasks) wanted.remove(wanted.size() - 1);
        // drop capture orders on objectives we no longer want
        for (Point p : new ArrayList<>(points.values())) {
            if (!p.objective.held() && !wanted.contains(p.objective)) drop(c, p, true);
        }
        ctx.clear();
        boolean assault = assaultWhen.test(ctx);
        for (ObjectiveFacts o : wanted) {
            Point p = points.get(o.objective);
            if (p == null) {
                p = new Point();
                p.objective = o;
                points.put(o.objective, p);
            }
            if (p.info == null || !c.orders().exists(p.info)) {
                AssignmentInfo existing = c.orders().assignmentOn(o.objective);
                p.info = existing != null ? existing
                        : c.orders().create(assault ? "assault" : order, o.objective, cp, true);
                if (p.info == null) continue;
                if (existing == null) {
                    String text = announceText(c, null, null, 0);
                    if (text != null) c.say(this, text.replace("{objective}", o.objective.getDisplayName()));
                    c.debug(id + ": " + (assault ? "assault" : order) + " " + o.objective.getDisplayName());
                }
            }
            for (int i = p.ships.size() - 1; i >= 0; i--) {
                if (c.claimOf(p.ships.get(i)) != this || !p.ships.get(i).canTakeOrders()) p.ships.remove(i);
            }
            if (p.ships.size() < ships.max) {
                for (ShipFacts f : ships.candidates(c, this, null, o.objective.getLocation(), null)) {
                    if (p.ships.size() >= ships.max) break;
                    c.claim(f, this);
                    p.ships.add(f);
                    c.orders().give(f.member, p.info);
                }
            }
        }
    }

    private void releaseShips(FleetCommander c, Point p) {
        for (ShipFacts f : p.ships) {
            if (c.claimOf(f) == this) c.release(f, this);
        }
        p.ships.clear();
    }

    private void drop(FleetCommander c, Point p, boolean removeAssignment) {
        points.remove(p.objective.objective);
        releaseShips(c, p);
        if (removeAssignment) {
            AssignmentInfo a = c.orders().assignmentOn(p.objective.objective);
            if (a != null) c.orders().remove(a);
        }
    }

    @Override
    public void onShipLost(FleetCommander c, ShipFacts ship) {
        for (Point p : points.values()) p.ships.remove(ship);
    }

    @Override
    public void endAll(FleetCommander c) {
        for (Point p : new ArrayList<>(points.values())) drop(c, p, true);
    }
}
