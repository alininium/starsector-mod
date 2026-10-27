package fleetcommander.tactics;

import fleetcommander.combat.FleetCommander;
import fleetcommander.doctrine.Validation;
import org.json.JSONObject;

/**
 * pounce: the moment an enemy overloads or starts venting, the nearest ships with spare flux get a short
 * Eliminate on it; the task ends when the window closes (no longer overloaded/venting) or after "window" seconds.
 */
final class PounceTactic extends TaskTactic {
    double window;

    @Override
    void configureExtra(JSONObject spec, Validation v, String path) {
        window = v.number(spec, "window", path, 6);
        if (timeout <= 0) timeout = window;
        if (interval == 0) interval = 0.5;
        if (minTime > window) minTime = 0;
    }

    @Override
    boolean typeAbort(FleetCommander c, Task t) {
        double age = c.time() - t.created;
        return age > 1.5 && t.target.get("overloaded") < 0.5 && t.target.get("venting") < 0.5;
    }

    @Override
    String defaultOrder() {
        return "eliminate";
    }

    @Override
    OnEnd defaultOnEnd() {
        return OnEnd.NOTHING;
    }

    @Override
    boolean defaultRequireStrength() {
        return false;
    }

    @Override
    double defaultStrength() {
        return 3.0;
    }

    @Override
    int defaultMax() {
        return 3;
    }

    @Override
    String defaultAnnounce() {
        return null; // too frequent to announce by default
    }
}
