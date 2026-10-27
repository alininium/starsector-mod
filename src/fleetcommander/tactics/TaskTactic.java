package fleetcommander.tactics;

import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import fleetcommander.combat.FCContext;
import fleetcommander.combat.FCOrders;
import fleetcommander.combat.FactSource;
import fleetcommander.combat.FleetCommander;
import fleetcommander.combat.ShipFacts;
import fleetcommander.doctrine.Condition;
import fleetcommander.doctrine.Doctrine;
import fleetcommander.doctrine.Facts;
import fleetcommander.doctrine.Validation;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared engine of the tactics that send a group of our ships at one enemy ship: focus_fire, hunt_isolated and
 * pounce. Each task: pick the best target, claim a pack, wait the reaction time, order the attack, then keep,
 * refill, switch or abort it by the doctrine's "keep" rules.
 */
abstract class TaskTactic extends Tactic {
    enum OnEnd { REJOIN, FREE, NOTHING }

    TargetSpec targets;
    ShipSpec ships;
    String order;
    double minTime, timeout, switchMargin;
    Condition abort = Condition.FALSE;
    OnEnd onEnd;
    boolean requireStrength;

    final List<Task> tasks = new ArrayList<>();
    final List<Returning> returning = new ArrayList<>();
    private final Vector2f tmp = new Vector2f();

    /** One target and the pack sent at it. */
    static final class Task implements FactSource {
        ShipFacts target;
        final List<ShipFacts> pack = new ArrayList<>();
        AssignmentInfo info;
        double created, readyAt, lastSeen;
        double startHull;
        double score;
        int retries;
        TaskTactic owner;

        double packWeight() {
            double w = 0;
            for (ShipFacts f : pack) w += f.weight;
            return w;
        }

        @Override
        public double get(String n) {
            switch (n) {
                case "weight": return packWeight();
                case "ships": return pack.size();
                case "hullLost": {
                    if (startHull <= 0) return 0;
                    double now = 0;
                    for (ShipFacts f : pack) now += f.ship.getHullLevel() * f.weight;
                    return Math.max(0, (startHull - now) / startHull);
                }
                case "leash": return owner.leash(this);
                default: return Double.NaN;
            }
        }
    }

    /** A ship walking back to the main body after its task ended. */
    static final class Returning {
        ShipFacts ship;
        AssignmentInfo info;
        double until;
    }

    // ---- per-type defaults ----
    abstract String defaultOrder();

    abstract OnEnd defaultOnEnd();

    abstract boolean defaultRequireStrength();

    double defaultStrength() {
        return 1.5;
    }

    int defaultMin() {
        return 1;
    }

    int defaultMax() {
        return 4;
    }

    double defaultTimeout() {
        return 0;
    }

    String defaultAnnounce() {
        return null;
    }

    @Override
    protected String[] keys() {
        return new String[]{"targets", "ships", "order", "keep", "requireStrength", "isolationRange", "window"};
    }

    @Override
    protected void configure(JSONObject spec, Validation v, String path, Set<String> roles) {
        targets = TargetSpec.parse(v.object(spec, "targets", path), v, path + ".targets", Facts.Scope.SHIP);
        if (spec.has("isolationRange")) {
            targets.isolationRange = (float) v.number(spec, "isolationRange", path, targets.isolationRange);
        }
        ships = ShipSpec.parse(v.object(spec, "ships", path), v, path + ".ships", roles, defaultStrength(),
                defaultMin(), defaultMax());
        order = v.string(spec, "order", path, defaultOrder());
        if (!FCOrders.isKnownOrder(order) || FCOrders.SPECIAL.contains(order)) {
            v.error(path + ".order", "\"" + order + "\" can't target an enemy ship here (try eliminate, engage, "
                    + "harass, fighter_strike)");
        }
        requireStrength = v.bool(spec, "requireStrength", path, defaultRequireStrength());
        JSONObject keep = v.object(spec, "keep", path);
        String kp = path + ".keep";
        v.checkKeys(keep, kp, "minTime", "timeout", "switchMargin", "abort", "onEnd");
        minTime = v.number(keep, "minTime", kp, 8);
        timeout = v.number(keep, "timeout", kp, defaultTimeout());
        switchMargin = v.number(keep, "switchMargin", kp, 1.3);
        if (keep != null && keep.has("abort")) {
            abort = Condition.parse(keep.opt("abort"), v, kp + ".abort",
                    Doctrine.scopes(Facts.Scope.TARGET, Facts.Scope.PACK));
        }
        String end = v.string(keep, "onEnd", kp, defaultOnEnd().name().toLowerCase());
        switch (end) {
            case "rejoin": onEnd = OnEnd.REJOIN; break;
            case "free": onEnd = OnEnd.FREE; break;
            case "nothing": onEnd = OnEnd.NOTHING; break;
            default:
                v.error(kp + ".onEnd", "expected rejoin, free or nothing");
                onEnd = defaultOnEnd();
        }
        configureExtra(spec, v, path);
    }

    void configureExtra(JSONObject spec, Validation v, String path) {
    }

    /** Extra abort rule of the type, checked with the target's fresh facts (e.g. pounce: no longer overloaded). */
    boolean typeAbort(FleetCommander c, Task t) {
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------

    @Override
    public void update(FleetCommander c, boolean active) {
        updateReturning(c);
        if (!active) {
            endAll(c);
            return;
        }
        double now = c.time();
        for (Task t : new ArrayList<>(tasks)) maintain(c, t, now);
        // fill free slots, best target first
        for (int guard = 0; guard < 4; guard++) {
            Candidate best = bestCandidate(c);
            if (best == null) break;
            if (tasks.size() < maxTasks) {
                if (!start(c, best, now)) break;
                continue;
            }
            Task weakest = null;
            for (Task t : tasks) {
                if (now - t.created < minTime) continue;
                if (weakest == null || t.score < weakest.score) weakest = t;
            }
            if (weakest == null || best.score <= weakest.score * switchMargin || best.score <= weakest.score) break;
            end(c, weakest, "a better target");
            if (!start(c, best, now)) break;
        }
    }

    private void maintain(FleetCommander c, Task t, double now) {
        ShipFacts fresh = c.picture().facts(t.target.ship);
        if (!t.target.isAlive()) {
            end(c, t, "target destroyed");
            return;
        }
        if (fresh == null || fresh.ours) {
            if (now - t.lastSeen > 6) end(c, t, "lost sight of the target");
            return;
        }
        t.target = fresh;
        t.lastSeen = now;
        // drop pack members that died, retreated or were taken by another tactic
        for (int i = t.pack.size() - 1; i >= 0; i--) {
            ShipFacts f = t.pack.get(i);
            if (!f.canTakeOrders() || c.claimOf(f) != this) t.pack.remove(i);
        }
        if (t.pack.isEmpty() && t.info != null) {
            end(c, t, "no ships left");
            return;
        }
        double age = now - t.created;
        t.score = targets.evaluate(c, fresh, age);
        if (Double.isNaN(t.score)) t.score = 0;
        if (t.info != null) {
            FCContext ctx = c.ctx();
            ctx.clear();
            ctx.target = fresh;
            ctx.pack = t;
            ctx.age = age;
            ctx.isolationRange = targets.isolationRange;
            boolean abortNow = abort.test(ctx);
            ctx.clear();
            if (age >= minTime && abortNow) {
                end(c, t, "aborted");
                return;
            }
            if (typeAbort(c, t)) {
                end(c, t, "window closed");
                return;
            }
        }
        if (timeout > 0 && age > timeout) {
            end(c, t, "timed out");
            return;
        }
        refill(c, t);
        if (t.info == null && now >= t.readyAt) issue(c, t);
    }

    private void refill(FleetCommander c, Task t) {
        double need = ships.strength * t.target.weight;
        if (t.pack.size() >= ships.max || t.packWeight() >= need) return;
        Set<ShipFacts> in = new HashSet<>(t.pack);
        for (ShipFacts f : ships.candidates(c, this, t.target, t.target.ship.getLocation(), in)) {
            if (t.pack.size() >= ships.max || t.packWeight() >= need) break;
            c.claim(f, this);
            t.pack.add(f);
            t.startHull += f.ship.getHullLevel() * f.weight;
            if (t.info != null) c.orders().give(f.member, t.info);
        }
    }

    private void issue(FleetCommander c, Task t) {
        if (t.target.member == null) return;
        t.info = c.orders().create(order, t.target.member, cp, false);
        if (t.info == null) {
            if (++t.retries > 5) end(c, t, "no command points");
            return;
        }
        for (ShipFacts f : t.pack) c.orders().give(f.member, t.info);
        String text = announceText(c, defaultAnnounce(), t.target, t.pack.size());
        if (text != null) c.say(this, text);
        c.debug(id + ": " + order + " on " + t.target.displayName() + " with " + t.pack.size() + " ships");
    }

    static final class Candidate {
        ShipFacts target;
        double score;
    }

    private final Candidate cand = new Candidate();

    private Candidate bestCandidate(FleetCommander c) {
        cand.target = null;
        cand.score = Double.NEGATIVE_INFINITY;
        for (ShipFacts e : c.picture().enemy) {
            if (e.member == null || isTarget(e)) continue;
            double s = targets.evaluate(c, e, 0);
            if (Double.isNaN(s) || s <= cand.score) continue;
            cand.target = e;
            cand.score = s;
        }
        return cand.target == null ? null : cand;
    }

    private boolean isTarget(ShipFacts e) {
        for (Task t : tasks) if (t.target.ship == e.ship) return true;
        return false;
    }

    /** Forms a pack for the candidate; false (and nothing claimed) if no valid pack exists. */
    private boolean start(FleetCommander c, Candidate best, double now) {
        ShipFacts target = best.target;
        double need = ships.strength * target.weight;
        List<ShipFacts> pack = new ArrayList<>();
        double w = 0;
        for (ShipFacts f : ships.candidates(c, this, target, target.ship.getLocation(), null)) {
            if (pack.size() >= ships.max) break;
            if (pack.size() >= ships.min && w >= need) break;
            pack.add(f);
            w += f.weight;
        }
        if (pack.size() < ships.min || (requireStrength && w < need)) return false;
        Task t = new Task();
        t.owner = this;
        t.target = target;
        t.score = best.score;
        t.created = now;
        t.lastSeen = now;
        t.readyAt = now + c.reactionTime();
        for (ShipFacts f : pack) {
            c.claim(f, this);
            t.pack.add(f);
            t.startHull += f.ship.getHullLevel() * f.weight;
        }
        tasks.add(t);
        if (t.readyAt <= now) issue(c, t);
        return true;
    }

    /** Distance from the pack's centre to our fleet's centre without the pack. */
    double leash(Task t) {
        if (t.pack.isEmpty()) return 0;
        float x = 0, y = 0, w = 0;
        for (ShipFacts f : t.pack) {
            x += f.ship.getLocation().x * f.weight;
            y += f.ship.getLocation().y * f.weight;
            w += f.weight;
        }
        if (w <= 0) return 0;
        Vector2f body = lastCommander.picture().ownCenterWithout(t.pack, tmp);
        float dx = x / w - body.x, dy = y / w - body.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Set at every update so pack facts can reach the picture. */
    private FleetCommander lastCommander;

    @Override
    public void countAttackers() {
        for (Task t : tasks) {
            if (t.info != null) t.target.attackers += t.pack.size();
        }
    }

    void end(FleetCommander c, Task t, String reason) {
        tasks.remove(t);
        if (t.info != null) c.orders().remove(t.info);
        c.debug(id + ": ended task on " + t.target.displayName() + " (" + reason + ")");
        Vector2f body = c.picture().ownCenterWithout(t.pack, new Vector2f());
        for (ShipFacts f : t.pack) {
            if (c.claimOf(f) != this) continue;
            if (onEnd == OnEnd.REJOIN && f.canTakeOrders() && t.info != null) {
                AssignmentInfo move = c.orders().moveTo(f.member, body, FCOrders.CpPriority.LOW);
                if (move != null) {
                    Returning r = new Returning();
                    r.ship = f;
                    r.info = move;
                    r.until = c.time() + 25;
                    returning.add(r);
                    continue;
                }
            }
            if (onEnd == OnEnd.FREE && f.canTakeOrders()) c.orders().free(f.member);
            c.release(f, this);
        }
    }

    private void updateReturning(FleetCommander c) {
        lastCommander = c;
        if (returning.isEmpty()) return;
        List<ShipFacts> walkers = new ArrayList<>();
        for (Returning r : returning) walkers.add(r.ship);
        Vector2f body = c.picture().ownCenterWithout(walkers, tmp);
        for (int i = returning.size() - 1; i >= 0; i--) {
            Returning r = returning.get(i);
            boolean mine = c.claimOf(r.ship) == this;
            boolean arrived = Math.sqrt(ShipSpec.distSq(r.ship.ship.getLocation(), body)) < 1200;
            if (!mine || !r.ship.isAlive() || arrived || c.time() > r.until) {
                if (mine) {
                    c.orders().remove(r.info);
                    c.release(r.ship, this);
                }
                returning.remove(i);
            }
        }
    }

    @Override
    public void onShipLost(FleetCommander c, ShipFacts ship) {
        for (Task t : tasks) t.pack.remove(ship);
        for (int i = returning.size() - 1; i >= 0; i--) {
            if (returning.get(i).ship == ship) {
                c.orders().remove(returning.get(i).info);
                returning.remove(i);
            }
        }
    }

    @Override
    public void endAll(FleetCommander c) {
        lastCommander = c;
        for (Task t : new ArrayList<>(tasks)) end(c, t, "tactic off");
    }
}
