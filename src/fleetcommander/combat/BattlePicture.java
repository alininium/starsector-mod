package fleetcommander.combat;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.util.Misc;
import fleetcommander.doctrine.RoleSet;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * What the commander knows about the battle, rebuilt every pass (docs/DOCTRINE_FORMAT.md section 4). Enemy ships
 * count only while we are aware of them (engine.isAwareOf): no cheating through the fog of war.
 */
public final class BattlePicture {
    private final CombatEngineAPI engine;
    private final int us;
    private final CombatFleetManagerAPI ownFm, enemyFm;

    public final List<ShipFacts> own = new ArrayList<>();
    public final List<ShipFacts> enemy = new ArrayList<>();
    public final List<ObjectiveFacts> objectives = new ArrayList<>();
    private final Map<ShipAPI, ShipFacts> byShip = new HashMap<>();
    private final Map<BattleObjectiveAPI, ObjectiveFacts> byObjective = new HashMap<>();

    public final FleetStats ownStats = new FleetStats();
    public final FleetStats enemyStats = new FleetStats();
    public final Vector2f ownCenter = new Vector2f();
    public final Vector2f enemyCenter = new Vector2f();
    /** Weighted sums behind ownCenter, so a tactic can compute the centre without its own ships. */
    double ownSumX, ownSumY, ownSumW;

    double time, strengthRatio, totalRatio, cp;
    int objectivesHeld, objectivesEnemy;
    boolean stationBattle, playerShipVisible;

    BattlePicture(CombatEngineAPI engine, int us) {
        this.engine = engine;
        this.us = us;
        this.ownFm = engine.getFleetManager(us);
        this.enemyFm = engine.getFleetManager(1 - us);
    }

    public ShipFacts facts(ShipAPI ship) {
        return ship == null ? null : byShip.get(ship);
    }

    void refresh(double battleTime, int commandPoints, RoleSet roles, FCContext ctx) {
        time = battleTime;
        cp = commandPoints;
        for (ShipFacts f : byShip.values()) f.seen = false;
        own.clear();
        enemy.clear();
        stationBattle = false;
        ShipAPI player = engine.getPlayerShip();
        collect(ownFm.getDeployedCopyDFM(), true, player);
        collect(enemyFm.getDeployedCopyDFM(), false, player);
        for (Iterator<ShipFacts> it = byShip.values().iterator(); it.hasNext(); ) {
            if (!it.next().seen) it.remove();
        }
        playerShipVisible = player != null && byShip.containsKey(player) && !byShip.get(player).ours;

        center(own, ownCenter, true);
        center(enemy, enemyCenter, false);

        // pairwise facts: support (same side), threat (other side), nearest enemy
        for (int i = 0; i < own.size(); i++) pairwise(own.get(i), own, enemy);
        for (int i = 0; i < enemy.size(); i++) pairwise(enemy.get(i), enemy, own);

        ownStats.compute(own, ownFm);
        enemyStats.compute(enemy, enemyFm);
        double cd = Misc.getDistance(ownCenter, enemyCenter);
        ownStats.centerDistance = enemyStats.centerDistance = (own.isEmpty() || enemy.isEmpty()) ? 0 : cd;

        for (int i = 0; i < enemy.size(); i++) {
            ShipFacts e = enemy.get(i);
            e.damageMatch = e.matchFor(ownStats.dmgKinetic, ownStats.dmgHe, ownStats.dmgEnergy, ownStats.dmgFrag);
            e.attackers = 0;
        }
        for (int i = 0; i < own.size(); i++) {
            ShipFacts o = own.get(i);
            o.damageMatch = o.matchFor(enemyStats.dmgKinetic, enemyStats.dmgHe, enemyStats.dmgEnergy,
                    enemyStats.dmgFrag);
            ctx.subject = o;
            o.role = roles.roleOf(ctx);
        }
        ctx.subject = null;

        strengthRatio = ownStats.weight / Math.max(0.5, enemyStats.weight);
        totalRatio = (ownStats.weight + ownStats.reserveWeight)
                / Math.max(0.5, enemyStats.weight + enemyStats.reserveWeight);

        refreshObjectives();
    }

    private void collect(List<DeployedFleetMemberAPI> members, boolean ours, ShipAPI player) {
        List<ShipFacts> into = ours ? own : enemy;
        for (int i = 0; i < members.size(); i++) {
            DeployedFleetMemberAPI m = members.get(i);
            if (m == null || m.isFighterWing()) continue;
            ShipAPI s = m.getShip();
            if (s == null || !s.isAlive() || s.isHulk() || s.isStationModule() || s.isShuttlePod()) continue;
            if (s.isStation()) stationBattle = true;
            if (!ours && !engine.isAwareOf(us, s)) continue;
            ShipFacts f = byShip.get(s);
            if (f == null) {
                f = new ShipFacts(s, ours);
                byShip.put(s, f);
            }
            f.member = m;
            f.seen = true;
            f.update(player);
            into.add(f);
        }
    }

    private void center(List<ShipFacts> list, Vector2f out, boolean ours) {
        double x = 0, y = 0, w = 0;
        for (int i = 0; i < list.size(); i++) {
            ShipFacts f = list.get(i);
            Vector2f l = f.ship.getLocation();
            x += l.x * f.weight;
            y += l.y * f.weight;
            w += f.weight;
        }
        if (w > 0) out.set((float) (x / w), (float) (y / w));
        if (ours) {
            ownSumX = x;
            ownSumY = y;
            ownSumW = w;
        }
    }

    private void pairwise(ShipFacts f, List<ShipFacts> friends, List<ShipFacts> foes) {
        Vector2f loc = f.ship.getLocation();
        float r2 = ShipFacts.DEFAULT_ISOLATION_RANGE * ShipFacts.DEFAULT_ISOLATION_RANGE;
        float t2 = ShipFacts.THREAT_RANGE * ShipFacts.THREAT_RANGE;
        double support = 0, threat = 0, nearest = Double.MAX_VALUE;
        for (int i = 0; i < friends.size(); i++) {
            ShipFacts o = friends.get(i);
            if (o == f) continue;
            if (Misc.getDistanceSq(loc, o.ship.getLocation()) <= r2) support += o.weight;
        }
        for (int i = 0; i < foes.size(); i++) {
            ShipFacts o = foes.get(i);
            float d2 = Misc.getDistanceSq(loc, o.ship.getLocation());
            if (d2 <= t2) threat += o.weight;
            if (d2 < nearest) nearest = d2;
        }
        f.support = support;
        f.threatHere = threat;
        f.nearestDistance = foes.isEmpty() ? 1 : Math.min(1, Math.sqrt(nearest) / 10000.0);
        Vector2f sideCenter = f.ours ? ownCenter : enemyCenter;
        f.strayDistance = Math.min(1, Misc.getDistance(loc, sideCenter) / 5000.0);
        f.distance = Math.min(1, Misc.getDistance(loc, ownCenter) / 10000.0);
    }

    /** Weight of the ship's own side within range (excluding itself), for tactics with a custom isolation range. */
    public double supportWithin(ShipFacts f, float range) {
        List<ShipFacts> friends = f.ours ? own : enemy;
        float r2 = range * range;
        double support = 0;
        for (int i = 0; i < friends.size(); i++) {
            ShipFacts o = friends.get(i);
            if (o != f && Misc.getDistanceSq(f.ship.getLocation(), o.ship.getLocation()) <= r2) support += o.weight;
        }
        return support;
    }

    private void refreshObjectives() {
        objectives.clear();
        objectivesHeld = objectivesEnemy = 0;
        List<BattleObjectiveAPI> list = engine.getObjectives();
        if (list == null) return;
        float t2 = ShipFacts.THREAT_RANGE * ShipFacts.THREAT_RANGE;
        for (int i = 0; i < list.size(); i++) {
            BattleObjectiveAPI o = list.get(i);
            ObjectiveFacts f = byObjective.get(o);
            if (f == null) {
                f = new ObjectiveFacts(o, us);
                byObjective.put(o, f);
            }
            f.owner = o.getOwner();
            if (f.held()) objectivesHeld++;
            else if (f.enemyHeld()) objectivesEnemy++;
            Vector2f loc = o.getLocation();
            f.distance = own.isEmpty() ? 1 : Math.min(1, Misc.getDistance(loc, ownCenter) / 10000.0);
            double nearest = Double.MAX_VALUE, threat = 0, support = 0;
            for (int k = 0; k < own.size(); k++) {
                float d2 = Misc.getDistanceSq(loc, own.get(k).ship.getLocation());
                if (d2 < nearest) nearest = d2;
                if (d2 <= t2) support += own.get(k).weight;
            }
            for (int k = 0; k < enemy.size(); k++) {
                if (Misc.getDistanceSq(loc, enemy.get(k).ship.getLocation()) <= t2) threat += enemy.get(k).weight;
            }
            f.nearestDistance = own.isEmpty() ? 1 : Math.min(1, Math.sqrt(nearest) / 10000.0);
            f.threatHere = threat;
            f.supportHere = support;
            objectives.add(f);
        }
    }

    /** Centre of our fleet without the given ships (e.g. a hunting pack), or the full centre if nothing is left. */
    public Vector2f ownCenterWithout(List<ShipFacts> exclude, Vector2f out) {
        double x = ownSumX, y = ownSumY, w = ownSumW;
        for (int i = 0; i < exclude.size(); i++) {
            ShipFacts f = exclude.get(i);
            if (!f.seen) continue;
            Vector2f l = f.ship.getLocation();
            x -= l.x * f.weight;
            y -= l.y * f.weight;
            w -= f.weight;
        }
        if (w > 0.5) out.set((float) (x / w), (float) (y / w));
        else out.set(ownCenter);
        return out;
    }

    public double battleFact(String n) {
        switch (n) {
            case "time": return time;
            case "strengthRatio": return strengthRatio;
            case "totalRatio": return totalRatio;
            case "objectives": return objectives.size();
            case "objectivesHeld": return objectivesHeld;
            case "objectivesEnemy": return objectivesEnemy;
            case "cp": return cp;
            case "isStationBattle": return stationBattle ? 1 : 0;
            case "playerFlagshipVisible": return playerShipVisible ? 1 : 0;
            default: return Double.NaN;
        }
    }

    public CombatEngineAPI getEngine() {
        return engine;
    }
}
