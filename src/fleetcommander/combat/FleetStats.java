package fleetcommander.combat;

import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import java.util.List;

/** Aggregate facts about one side ("own.*" / "enemy.*"). */
public final class FleetStats implements FactSource {
    double weight, ships, lossFraction, avgSpeed, avgArmor, reserveWeight;
    double shFrigate, shDestroyer, shCruiser, shCapital, shCarrier, shPhase, shCivilian, shShieldless, shOmni;
    double dmgKinetic, dmgHe, dmgEnergy, dmgFrag;
    double centerDistance;

    void compute(List<ShipFacts> list, CombatFleetManagerAPI fm) {
        weight = ships = avgSpeed = avgArmor = 0;
        shFrigate = shDestroyer = shCruiser = shCapital = shCarrier = shPhase = shCivilian = shShieldless = shOmni = 0;
        double kin = 0, he = 0, en = 0, frag = 0;
        for (int i = 0; i < list.size(); i++) {
            ShipFacts f = list.get(i);
            double w = f.weight;
            ShipAPI s = f.ship;
            weight += w;
            ships++;
            avgSpeed += f.speed * w;
            avgArmor += s.getHullSpec().getArmorRating() * w;
            if (s.isFrigate()) shFrigate += w;
            else if (s.isDestroyer()) shDestroyer += w;
            else if (s.isCruiser()) shCruiser += w;
            else if (s.isCapital()) shCapital += w;
            if (f.carrier) shCarrier += w;
            if (f.phase) shPhase += w;
            if (f.civilian) shCivilian += w;
            if (f.noShield) shShieldless += w;
            if (f.omniShield) shOmni += w;
            kin += f.dpsKinetic;
            he += f.dpsHe;
            en += f.dpsEnergy;
            frag += f.dpsFrag;
        }
        if (weight > 0) {
            avgSpeed /= weight;
            avgArmor /= weight;
            shFrigate /= weight;
            shDestroyer /= weight;
            shCruiser /= weight;
            shCapital /= weight;
            shCarrier /= weight;
            shPhase /= weight;
            shCivilian /= weight;
            shShieldless /= weight;
            shOmni /= weight;
        }
        double dps = kin + he + en + frag;
        if (dps > 0) {
            dmgKinetic = kin / dps;
            dmgHe = he / dps;
            dmgEnergy = en / dps;
            dmgFrag = frag / dps;
        } else {
            dmgKinetic = dmgHe = dmgFrag = 0;
            dmgEnergy = 1;
        }
        // losses and reserves come from the fleet manager (includes ships we can't see: they are known losses)
        double lost = sumWeight(fm.getDestroyedCopy()) + sumWeight(fm.getDisabledCopy())
                + sumWeight(fm.getRetreatedCopy());
        double deployed = sumWeight(fm.getDeployedCopy());
        lossFraction = lost + deployed > 0 ? lost / (lost + deployed) : 0;
        reserveWeight = sumWeight(fm.getReservesCopy());
    }

    /** Ship weight from a fleet member (same scale as Misc.getShipWeight, without the live-ship adjustments). */
    static double memberWeight(FleetMemberAPI m) {
        if (m == null || m.isFighterWing() || m.getHullSpec() == null) return 0;
        double w;
        switch (m.getHullSpec().getHullSize()) {
            case CAPITAL_SHIP: w = 8; break;
            case CRUISER: w = 4; break;
            case DESTROYER: w = 2; break;
            case FRIGATE: w = 1; break;
            default: return 0;
        }
        if (m.getHullSpec().isPhase() && w <= 2) w += 2;
        if (m.getHullSpec().isCivilianNonCarrier()) w *= 0.25;
        return w;
    }

    private static double sumWeight(List<FleetMemberAPI> members) {
        double sum = 0;
        if (members == null) return 0;
        for (int i = 0; i < members.size(); i++) sum += memberWeight(members.get(i));
        return sum;
    }

    @Override
    public double get(String n) {
        switch (n) {
            case "weight": return weight;
            case "ships": return ships;
            case "lossFraction": return lossFraction;
            case "avgSpeed": return avgSpeed;
            case "avgArmor": return avgArmor;
            case "share.frigate": return shFrigate;
            case "share.destroyer": return shDestroyer;
            case "share.cruiser": return shCruiser;
            case "share.capital": return shCapital;
            case "share.carrier": return shCarrier;
            case "share.phase": return shPhase;
            case "share.civilian": return shCivilian;
            case "share.shieldless": return shShieldless;
            case "share.omniShield": return shOmni;
            case "damage.kinetic": return dmgKinetic;
            case "damage.he": return dmgHe;
            case "damage.energy": return dmgEnergy;
            case "damage.frag": return dmgFrag;
            case "centerDistance": return centerDistance;
            default: return Double.NaN;
        }
    }
}
