package fleetcommander.combat;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;

/** Facts about one battle objective, from the commander's side. */
public final class ObjectiveFacts implements FactSource {
    public final BattleObjectiveAPI objective;
    double distance, nearestDistance, threatHere, supportHere;
    int owner;
    final int us;

    ObjectiveFacts(BattleObjectiveAPI objective, int us) {
        this.objective = objective;
        this.us = us;
    }

    public boolean held() {
        return owner == us;
    }

    public boolean enemyHeld() {
        return owner == 1 - us;
    }

    @Override
    public double get(String n) {
        switch (n) {
            case "distance": return distance;
            case "nearestDistance": return nearestDistance;
            case "threatHere": return threatHere;
            case "supportHere": return supportHere;
            case "held": return held() ? 1 : 0;
            case "enemyHeld": return enemyHeld() ? 1 : 0;
            case "neutral": return !held() && !enemyHeld() ? 1 : 0;
            default: return Double.NaN;
        }
    }
}
