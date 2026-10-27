package fleetcommander.combat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import fleetcommander.FCConfig;
import fleetcommander.FCIds;
import fleetcommander.FCSettings;
import fleetcommander.doctrine.Doctrine;
import fleetcommander.doctrine.RoleSet;
import fleetcommander.doctrine.Validation;
import fleetcommander.tactics.FCTactics;
import fleetcommander.tactics.Tactic;
import org.apache.log4j.Logger;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The enemy side's commander for one battle: reads the battle, picks the phase, runs the doctrine's tactics in
 * priority order and keeps track of which tactic owns which ship (DESIGN.md section 3).
 */
public final class FleetCommander {
    private static final Logger log = Global.getLogger(FleetCommander.class);

    private final CombatEngineAPI engine;
    private final int us;
    private final CombatFleetManagerAPI fm;
    private final CombatTaskManagerAPI tm;
    private final FCSettings settings;
    private final Doctrine doctrine;
    private final RoleSet roles;
    private final BattlePicture picture;
    private final FCContext ctx;
    private final FCOrders orders;
    private final Random random = new Random();
    private final List<Tactic> tactics = new ArrayList<>();
    private final Map<ShipAPI, Tactic> claims = new HashMap<>();

    private final double thinkInterval, reactionTime, scoreNoise;
    private double time, lastRefresh = -1;
    private boolean fullAssault, fullRetreat, silenced;

    private FleetCommander(CombatEngineAPI engine, int us, FCConfig config, Doctrine doctrine, BattlePicture picture,
                           FCContext ctx) {
        this.engine = engine;
        this.us = us;
        this.fm = engine.getFleetManager(us);
        this.tm = fm.getTaskManager(false);
        this.settings = config.settings;
        this.doctrine = doctrine;
        this.roles = doctrine.roles != null ? doctrine.roles : config.roles;
        this.picture = picture;
        this.ctx = ctx;
        double speed = settings.difficulty;
        this.thinkInterval = doctrine.commander.thinkInterval / speed;
        this.reactionTime = doctrine.commander.reactionTime / speed;
        this.scoreNoise = doctrine.commander.scoreNoise;
        boolean useCp = settings.useCommandPoints != null ? settings.useCommandPoints
                : doctrine.commander.useCommandPoints;
        BattleCreationContext bcc = engine.getContext();
        boolean retreats = bcc == null || (bcc.aiRetreatAllowed && !bcc.fightToTheLast);
        this.orders = new FCOrders(engine, tm, settings.orderMapping, useCp, doctrine.commander.cpReserve,
                doctrine.commander.commandWindow, settings.announceOrders && doctrine.commander.announce, retreats,
                settings.debugLog);

        Validation v = new Validation();
        v.setFile(doctrine.file);
        java.util.Set<String> phaseIds = new java.util.HashSet<>();
        for (Doctrine.Phase p : doctrine.phases) phaseIds.add(p.id);
        if (phaseIds.isEmpty()) phaseIds.add("main");
        for (JSONObject spec : doctrine.tactics) {
            if (!spec.optBoolean("enabled", true)) continue;
            Tactic t = FCTactics.create(spec, v, "tactics[" + spec.optString("id") + "]", roles.knownRoles(), phaseIds);
            if (t != null) tactics.add(t);
        }
        for (String e : v.getErrors()) log.error("Fleet Commander: " + e);
        tactics.sort(Comparator.comparingInt((Tactic t) -> -t.priority));
        for (Tactic t : tactics) t.timer = random.nextDouble() * 0.5; // first pass soon, staggered
    }

    // ---------------------------------------------------------------------------------------------------------
    // Takeover

    /** Why the commander stays out of this battle, or null if it may take over. Checked until ships deploy. */
    static String reasonToStayOut(CombatEngineAPI engine, int us, FCConfig config) {
        FCSettings s = config.settings;
        if (!s.enabled) return "disabled in settings";
        if (!config.doctrines.containsKey(FCIds.DEFAULT_DOCTRINE)) return "no valid default doctrine";
        if (engine.isSimulation() && !s.enableInSimulator) return "simulator disabled in settings";
        if (engine.isMission() && !engine.isSimulation() && !s.enableInMissions) return "missions disabled in settings";
        CombatFleetManagerAPI own = engine.getFleetManager(us), other = engine.getFleetManager(1 - us);
        if (own.getGoal() == com.fs.starfarer.api.fleet.FleetGoal.ESCAPE
                || other.getGoal() == com.fs.starfarer.api.fleet.FleetGoal.ESCAPE) {
            return "pursuit or escape battle";
        }
        if (engine.hasPluginOfClass(com.fs.starfarer.api.impl.combat.threat.ThreatCombatStrategyForBothSidesPlugin.class)
                || engine.hasPluginOfClass(com.fs.starfarer.api.impl.combat.dweller.DwellerCombatStrategyForBothSidesPlugin.class)) {
            return "Threat/Dweller strategy AI is running";
        }
        for (com.fs.starfarer.api.combat.DeployedFleetMemberAPI m : own.getDeployedCopyDFM()) {
            ShipAPI ship = m.getShip();
            if (ship == null || ship.getVariant() == null) continue;
            if (ship.getVariant().hasHullMod("threat_hullmod") || ship.getVariant().hasHullMod("dweller_hullmod")) {
                return "Threat/Dweller fleet";
            }
        }
        CampaignFleetAPI fleet = enemyCampaignFleet(engine);
        if (fleet != null && fleet.getMemoryWithoutUpdate().getBoolean(FCIds.MEM_DISABLE)) return "$fc_disable set";
        return null;
    }

    static CampaignFleetAPI enemyCampaignFleet(CombatEngineAPI engine) {
        BattleCreationContext bcc = engine.getContext();
        return bcc == null ? null : bcc.getOtherFleet();
    }

    /** Picks the doctrine, silences the vanilla admiral and creates the commander. */
    static FleetCommander takeOver(CombatEngineAPI engine, int us, FCConfig config) {
        BattlePicture picture = new BattlePicture(engine, us);
        FCContext ctx = new FCContext(picture, new Random());
        picture.refresh(0, engine.getFleetManager(us).getTaskManager(false).getCommandPointsLeft(), config.roles, ctx);
        Doctrine doctrine = chooseDoctrine(engine, config, ctx);

        CombatTaskManagerAPI tm = engine.getFleetManager(us).getTaskManager(false);
        if (config.settings.bonusCommandPoints != 0) {
            tm.getCommandPointsStat().modifyFlat(FCIds.CP_BONUS_ID, config.settings.bonusCommandPoints);
        }
        FleetCommander c = new FleetCommander(engine, us, config, doctrine, picture, ctx);
        if (c.fm.getAdmiralAI() != null) c.silenceAdmiral(true);
        log.info("Fleet Commander: taking command of side " + us + " with doctrine \"" + doctrine.id + "\", "
                + c.tactics.size() + " tactics, " + (c.orders.usesCommandPoints() ? "CP " + tm.getCommandPointsLeft()
                + " (one CP opens a " + doctrine.commander.commandWindow + "s command window)" : "free orders")
                + (c.silenced ? "" : ", no vanilla admiral to silence yet"));
        if (c.orders.usesCommandPoints() && tm.getCommandPointsLeft() == 0) {
            log.warn("Fleet Commander: the enemy fleet has no command points; it can only act as points regenerate."
                    + " Set \"useCommandPoints\": false or \"bonusCommandPoints\" in settings.json to change this.");
        }
        c.orders.say("takes command (" + doctrine.name + ")");
        return c;
    }

    private static Doctrine chooseDoctrine(CombatEngineAPI engine, FCConfig config, FCContext ctx) {
        Map<String, Doctrine> all = config.doctrines;
        CampaignFleetAPI fleet = enemyCampaignFleet(engine);
        if (fleet != null) {
            String forced = fleet.getMemoryWithoutUpdate().getString(FCIds.MEM_DOCTRINE);
            if (forced != null && all.containsKey(forced)) return all.get(forced);
            if (forced != null) log.warn("Fleet Commander: fleet asks for unknown doctrine \"" + forced + "\"");
        }
        String faction = fleet != null && fleet.getFaction() != null ? fleet.getFaction().getId() : null;
        if (faction != null && config.settings.factionDoctrines.containsKey(faction)) {
            Doctrine d = all.get(config.settings.factionDoctrines.get(faction));
            if (d != null) return d;
        }
        ctx.clear();
        ctx.phase = "main";
        List<Doctrine> best = new ArrayList<>();
        double bestWeight = Double.NEGATIVE_INFINITY;
        for (Doctrine d : all.values()) {
            if (!d.selectable) continue;
            if (!d.selectFactions.isEmpty() && (faction == null || !d.selectFactions.contains(faction))) continue;
            if (!d.selectWhen.test(ctx)) continue;
            if (d.selectWeight > bestWeight) {
                best.clear();
                bestWeight = d.selectWeight;
            }
            if (d.selectWeight == bestWeight) best.add(d);
        }
        if (best.isEmpty()) return all.get(FCIds.DEFAULT_DOCTRINE);
        return best.get(new Random().nextInt(best.size()));
    }

    /** At takeover the admiral's orders are cleared; later (admiral created after takeover) ours are kept. */
    private void silenceAdmiral(boolean clearTasks) {
        if (clearTasks) tm.clearTasks();
        fm.getAdmiralAI().setNoOrders(true);
        silenced = true;
    }

    /** Gives control back to the vanilla admiral (after an error, or when the battle ends). */
    void handBack() {
        try {
            for (Tactic t : tactics) t.endAll(this);
        } catch (Throwable ignored) {
            // best effort: we are already handling a failure
        }
        tm.clearTasks();
        if (fm.getAdmiralAI() != null) fm.getAdmiralAI().setNoOrders(false);
    }

    // ---------------------------------------------------------------------------------------------------------
    // The loop

    void advance(float amount) {
        time += amount;
        orders.setTime(time);
        if (!silenced && fm.getAdmiralAI() != null) silenceAdmiral(false); // the admiral may appear after takeover
        boolean anyDue = false;
        for (Tactic t : tactics) {
            t.timer -= amount;
            if (t.timer <= 0) anyDue = true;
        }
        if (!anyDue || fullRetreat) return;

        if (time - lastRefresh >= 0.2) {
            picture.refresh(time, tm.getCommandPointsLeft(), roles, ctx);
            lastRefresh = time;
            for (Iterator<Map.Entry<ShipAPI, Tactic>> it = claims.entrySet().iterator(); it.hasNext(); ) {
                ShipAPI s = it.next().getKey();
                if (!s.isAlive() || s.isHulk()) it.remove();
            }
        }
        ctx.clear();
        ctx.phase = doctrine.phaseFor(ctx);

        for (Tactic t : tactics) {
            if (t.timer > 0) continue;
            double base = t.interval > 0 ? t.interval / settings.difficulty : thinkInterval;
            t.timer = base * (0.8 + 0.4 * random.nextDouble());
            if (fullAssault && t.suspendedByFullAssault()) {
                if (!t.suspended) {
                    t.endAll(this);
                    t.suspended = true;
                }
                continue;
            }
            t.update(this, t.isActive(this));
            ctx.clear();
            if (fullRetreat) return;
        }
        for (ShipFacts e : picture.enemy) e.attackers = 0;
        for (Tactic t : tactics) t.countAttackers();
        orders.tidy();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Services for tactics

    public BattlePicture picture() {
        return picture;
    }

    public FCContext ctx() {
        return ctx;
    }

    public FCOrders orders() {
        return orders;
    }

    public Doctrine doctrine() {
        return doctrine;
    }

    public double time() {
        return time;
    }

    public String phase() {
        return ctx.phase;
    }

    public double reactionTime() {
        return reactionTime;
    }

    /** Random multiplier around 1 from the doctrine's scoreNoise. */
    public double noise() {
        return scoreNoise <= 0 ? 1 : 1 + (random.nextDouble() * 2 - 1) * scoreNoise;
    }

    public Tactic claimOf(ShipFacts f) {
        return claims.get(f.ship);
    }

    /** A tactic may take a ship that is free or held by a lower-priority tactic. */
    public boolean canClaim(ShipFacts f, Tactic t) {
        Tactic owner = claims.get(f.ship);
        return owner == null || owner == t || owner.priority < t.priority;
    }

    public void claim(ShipFacts f, Tactic t) {
        Tactic owner = claims.put(f.ship, t);
        if (owner != null && owner != t) owner.onShipLost(this, f);
    }

    public void release(ShipFacts f, Tactic t) {
        if (claims.get(f.ship) == t) claims.remove(f.ship);
    }

    public void startFullAssault() {
        fullAssault = true;
    }

    public void startFullRetreat() {
        fullRetreat = true;
    }

    public void say(Tactic t, String text) {
        orders.say(text);
    }

    public void debug(String text) {
        if (settings.debugLog) log.info("Fleet Commander [" + String.format("%.1f", time) + "s] " + text);
    }

    public int side() {
        return us;
    }

    public CombatEngineAPI engine() {
        return engine;
    }
}
