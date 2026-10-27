package fleetcommander.combat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import fleetcommander.FCConfig;
import fleetcommander.FCIds;
import org.apache.log4j.Logger;

import java.awt.Color;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Registered in data/config/settings.json; one instance per battle. Decides whether the commander takes over the
 * enemy side, runs it, and on any error hands control back to the vanilla admiral for the rest of the battle.
 * Also logs the player's own orders (settings "logPlayerOrders"), which is how the order mapping gets verified.
 */
public class FCCombatPlugin extends BaseEveryFrameCombatPlugin {
    private static final Logger log = Global.getLogger(FCCombatPlugin.class);
    /** The side the commander plays: the enemy of the player. */
    private static final int SIDE = 1;

    private CombatEngineAPI engine;
    private FCConfig config;
    private FleetCommander commander;
    private boolean decided, failed;
    private final IntervalUtil checkInterval = new IntervalUtil(0.5f, 0.5f);
    private final IntervalUtil playerOrderInterval = new IntervalUtil(1f, 1f);
    private final Set<AssignmentInfo> seenPlayerOrders = Collections.newSetFromMap(new IdentityHashMap<>());

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        this.config = FCConfig.get();
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || config == null || engine.isPaused()) return;
        try {
            if (config.settings.logPlayerOrders) logPlayerOrders(amount);
        } catch (Throwable t) {
            log.warn("Fleet Commander: player order logging failed", t);
        }
        if (failed || decided && commander == null) return;
        try {
            if (commander == null) {
                checkInterval.advance(amount);
                if (!checkInterval.intervalElapsed()) return;
                String reason = FleetCommander.reasonToStayOut(engine, SIDE, config);
                if (reason != null) {
                    decided = true;
                    log.info("Fleet Commander: staying out of this battle (" + reason + ")");
                    return;
                }
                if (engine.getFleetManager(SIDE).getDeployedCopyDFM().isEmpty()) return; // wait for deployment
                decided = true;
                commander = FleetCommander.takeOver(engine, SIDE, config);
                engine.getCustomData().put(FCIds.COMMANDER_KEY, commander);
                return;
            }
            if (engine.isCombatOver()) return;
            commander.advance(amount);
        } catch (Throwable t) {
            failed = true;
            log.error("Fleet Commander: error, handing command back to the vanilla admiral for this battle", t);
            try {
                if (commander != null) commander.handBack();
            } catch (Throwable t2) {
                log.error("Fleet Commander: hand-back failed too", t2);
            }
        }
    }

    private void logPlayerOrders(float amount) {
        playerOrderInterval.advance(amount);
        if (!playerOrderInterval.intervalElapsed()) return;
        CombatTaskManagerAPI tm = engine.getFleetManager(0).getTaskManager(false);
        List<AssignmentInfo> all = tm.getAllAssignments();
        seenPlayerOrders.removeIf(a -> !all.contains(a));
        for (AssignmentInfo a : all) {
            if (!seenPlayerOrders.add(a)) continue;
            String text = a.getType() + " on " + FCOrders.describe(a.getTarget());
            log.info("Fleet Commander: player-side order " + text);
            if (engine.getCombatUI() != null) {
                engine.getCombatUI().addMessage(0, new Color(120, 200, 255), "[Fleet Commander] your order = ",
                        Color.WHITE, text);
            }
        }
    }
}
