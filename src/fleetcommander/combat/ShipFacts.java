package fleetcommander.combat;

import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;

/**
 * Facts about one deployed ship, refreshed by {@link BattlePicture} every pass. The object lives as long as the
 * ship is on the field, so per-ship caches (weapon damage profile) survive between passes.
 */
public final class ShipFacts implements FactSource {
    public static final float DEFAULT_ISOLATION_RANGE = 2000f;
    public static final float THREAT_RANGE = 2000f;

    public final ShipAPI ship;
    public DeployedFleetMemberAPI member;
    /** True for the commander's own ships. */
    public final boolean ours;
    /** Role from roles.json (own ships only). */
    public String role = "";
    /** Seen this pass (used to drop ships that left the field). */
    boolean seen;

    public float weight;
    double dp, hull, armor, flux, hardFlux, cr, speed;
    boolean overloaded, venting, shieldDown, noShield, omniShield, phase, phased, carrier, civilian;
    boolean retreating, fluxLocked, playerFlagship, flagship, officer;
    public double support, threatHere, strayDistance, distance, nearestDistance, damageMatch;
    /** Our ships currently ordered against this (enemy) ship; counted by the tactics each pass. */
    public int attackers;

    // weapon profile, computed once per ship (sustained DPS by damage type)
    private boolean profiled;
    double dpsKinetic, dpsHe, dpsEnergy, dpsFrag;

    ShipFacts(ShipAPI ship, boolean ours) {
        this.ship = ship;
        this.ours = ours;
    }

    /** Values that only need the ship itself. Pairwise values (support, threat, distances) are set by the picture. */
    void update(ShipAPI playerShip) {
        ShipAPI s = ship;
        weight = Misc.getShipWeight(s);
        dp = s.getFleetMember() != null ? s.getFleetMember().getDeploymentPointsCost() : 0;
        hull = s.getHullLevel();
        armor = armorLevel(s);
        flux = s.getFluxLevel();
        float maxFlux = s.getMaxFlux();
        hardFlux = maxFlux > 0 ? s.getFluxTracker().getHardFlux() / maxFlux : 0;
        overloaded = s.getFluxTracker().isOverloaded();
        venting = s.getFluxTracker().isVenting();
        ShieldAPI sh = s.getShield();
        noShield = sh == null || sh.getType() == ShieldAPI.ShieldType.NONE || sh.getType() == ShieldAPI.ShieldType.PHASE;
        omniShield = !noShield && sh.getType() == ShieldAPI.ShieldType.OMNI;
        shieldDown = !noShield && !sh.isOn();
        phase = s.getHullSpec().isPhase();
        phased = s.isPhased();
        carrier = s.getNumFighterBays() >= 2;
        civilian = s.getHullSpec().isCivilianNonCarrier();
        cr = s.getCurrentCR();
        speed = s.getMaxSpeed();
        retreating = s.isRetreating();
        playerFlagship = s == playerShip;
        flagship = s.getFleetMember() != null && s.getFleetMember().isFlagship();
        PersonAPI captain = s.getCaptain();
        officer = captain != null && !captain.isDefault();
        fluxLocked = false;
        ShipAPI target = s.getShipTarget();
        if (flux > 0.8 && target != null) {
            Vector2f v = s.getVelocity();
            float dx = target.getLocation().x - s.getLocation().x, dy = target.getLocation().y - s.getLocation().y;
            fluxLocked = (v.x * dx + v.y * dy) < 0 && v.lengthSquared() > 100f;
        }
        if (!profiled) profile();
    }

    private void profile() {
        profiled = true;
        List<WeaponAPI> weapons = ship.getAllWeapons();
        if (weapons == null) return;
        for (int i = 0; i < weapons.size(); i++) {
            WeaponAPI w = weapons.get(i);
            if (w == null || w.isDecorative() || w.getDerivedStats() == null) continue;
            float dps = w.getDerivedStats().getSustainedDps();
            DamageType t = w.getDamageType();
            if (t == DamageType.KINETIC) dpsKinetic += dps;
            else if (t == DamageType.HIGH_EXPLOSIVE) dpsHe += dps;
            else if (t == DamageType.ENERGY) dpsEnergy += dps;
            else if (t == DamageType.FRAGMENTATION) dpsFrag += dps;
        }
    }

    private static double armorLevel(ShipAPI s) {
        ArmorGridAPI grid = s.getArmorGrid();
        if (grid == null) return 1;
        float max = grid.getMaxArmorInCell();
        float[][] cells = grid.getGrid();
        if (max <= 0 || cells == null) return 1;
        double sum = 0;
        int n = 0;
        for (float[] col : cells) {
            for (float c : col) {
                sum += c;
                n++;
            }
        }
        return n == 0 ? 1 : Math.min(1, sum / (n * max));
    }

    /**
     * How well a damage profile (shares of kinetic, HE, energy, frag) fits this ship right now, 0..1. Against a
     * raised shield with flux to spare, shield multipliers count; otherwise armour multipliers, which fade
     * toward hull damage (1.0) as the armour is stripped.
     */
    double matchFor(double kin, double he, double en, double frag) {
        if (phased) return 0;
        boolean shieldHit = !noShield && !shieldDown && !overloaded && !venting && flux < 0.85;
        double e;
        if (shieldHit) {
            e = kin * 2.0 + he * 0.5 + en * 1.0 + frag * 0.25;
        } else {
            double a = armor; // 1 = full armour, 0 = stripped
            e = kin * lerp(1.0, 0.5, a) + he * lerp(1.0, 2.0, a) + en + frag * lerp(1.0, 0.25, a);
        }
        return Math.max(0, Math.min(1, e / 2.0));
    }

    private static double lerp(double atZero, double atOne, double t) {
        return atZero + (atOne - atZero) * t;
    }

    public boolean isAlive() {
        return ship.isAlive() && !ship.isHulk();
    }

    public boolean canTakeOrders() {
        return member != null && member.canBeGivenOrders() && !ship.isRetreating() && isAlive();
    }

    public String displayName() {
        String name = ship.getName();
        String hullName = ship.getHullSpec().getHullName();
        return name == null || name.isEmpty() ? hullName : name + " (" + hullName + ")";
    }

    private static double b(boolean v) {
        return v ? 1 : 0;
    }

    @Override
    public double get(String n) {
        switch (n) {
            case "value": return weight / 8.0;
            case "weight": return weight;
            case "dp": return dp;
            case "size.frigate": return b(ship.isFrigate());
            case "size.destroyer": return b(ship.isDestroyer());
            case "size.cruiser": return b(ship.isCruiser());
            case "size.capital": return b(ship.isCapital());
            case "hull": return hull;
            case "hullMissing": return 1 - hull;
            case "armor": return armor;
            case "armorMissing": return 1 - armor;
            case "flux": return flux;
            case "hardFlux": return hardFlux;
            case "overloaded": return b(overloaded);
            case "venting": return b(venting);
            case "shieldDown": return b(shieldDown);
            case "noShield": return b(noShield);
            case "phase": return b(phase);
            case "phased": return b(phased);
            case "phaseFluxed": return b(phase && hardFlux > 0.5);
            case "carrier": return b(carrier);
            case "civilian": return b(civilian);
            case "cr": return cr;
            case "lowCR": return b(cr < 0.4);
            case "speed": return speed;
            case "isolation": return 1.0 / (1.0 + support / Math.max(0.1, weight));
            case "support": return support;
            case "threatHere": return threatHere;
            case "strayDistance": return strayDistance;
            case "distance": return distance;
            case "nearestDistance": return nearestDistance;
            case "retreating": return b(retreating);
            case "fluxLocked": return b(fluxLocked);
            case "playerFlagship": return b(playerFlagship);
            case "flagship": return b(flagship);
            case "officer": return b(officer);
            case "damageMatch": return damageMatch;
            case "attackers": return attackers;
            default: return Double.NaN; // "age" is answered by the context
        }
    }
}
