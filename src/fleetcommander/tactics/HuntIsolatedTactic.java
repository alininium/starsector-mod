package fleetcommander.tactics;

/**
 * hunt_isolated (the wolfpack): a pack strong enough to win hunts an enemy ship that strayed from its fleet, with
 * Eliminate, and gives up when rescuers arrive, the pack is hurt or lured away, or time runs out. The isolation
 * filter and the abort rules come from the doctrine.
 */
final class HuntIsolatedTactic extends TaskTactic {
    @Override
    String defaultOrder() {
        return "eliminate";
    }

    @Override
    OnEnd defaultOnEnd() {
        return OnEnd.REJOIN;
    }

    @Override
    boolean defaultRequireStrength() {
        return true;
    }

    @Override
    double defaultStrength() {
        return 2.0;
    }

    @Override
    int defaultMin() {
        return 2;
    }

    @Override
    double defaultTimeout() {
        return 45;
    }

    @Override
    String defaultAnnounce() {
        return "hunting the isolated {target}";
    }
}
