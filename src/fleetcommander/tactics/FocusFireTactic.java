package fleetcommander.tactics;

/** focus_fire: converge on the best-scoring enemy ship with ships of the line (Engage: careful attack). */
final class FocusFireTactic extends TaskTactic {
    @Override
    String defaultOrder() {
        return "engage";
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
    protected int defaultMaxTasks() {
        return 2;
    }

    @Override
    String defaultAnnounce() {
        return "focus fire on {target}";
    }
}
