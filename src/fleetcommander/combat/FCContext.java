package fleetcommander.combat;

import fleetcommander.doctrine.EvalContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * The one mutable evaluation context of a battle. Callers set {@link #subject}, {@link #target}, {@link #pack}
 * and {@link #age} before evaluating, and reset them afterwards ({@link #clear()}).
 */
public final class FCContext implements EvalContext {
    private final BattlePicture picture;
    private final Random random;
    private final Map<Object, Boolean> rolls = new HashMap<>();

    String phase = "main";
    /** Bare names: a ship or an objective. */
    public FactSource subject;
    /** "target.*". */
    public ShipFacts target;
    /** "pack.*". */
    public FactSource pack;
    /** "age": seconds the current target has been a task target. */
    public double age;
    /** Isolation range for "isolation"/"support" when it differs from the default. */
    public float isolationRange = ShipFacts.DEFAULT_ISOLATION_RANGE;

    FCContext(BattlePicture picture, Random random) {
        this.picture = picture;
        this.random = random;
    }

    public void clear() {
        subject = null;
        target = null;
        pack = null;
        age = 0;
        isolationRange = ShipFacts.DEFAULT_ISOLATION_RANGE;
    }

    @Override
    public double fact(String name) {
        int dot = name.indexOf('.');
        if (dot > 0) {
            String head = name.substring(0, dot), rest = name.substring(dot + 1);
            switch (head) {
                case "battle": return picture.battleFact(rest);
                case "own": return picture.ownStats.get(rest);
                case "enemy": return picture.enemyStats.get(rest);
                case "target": return target == null ? Double.NaN : shipFact(target, rest);
                case "pack": return pack == null ? Double.NaN : pack.get(rest);
                default: break; // "size.frigate" etc.: a bare ship fact with a dot
            }
        }
        if (subject == null) return Double.NaN;
        if (subject instanceof ShipFacts) return shipFact((ShipFacts) subject, name);
        return subject.get(name);
    }

    private double shipFact(ShipFacts f, String name) {
        if (name.equals("age")) return age;
        if (isolationRange != ShipFacts.DEFAULT_ISOLATION_RANGE) {
            if (name.equals("support")) return picture.supportWithin(f, isolationRange);
            if (name.equals("isolation")) {
                return 1.0 / (1.0 + picture.supportWithin(f, isolationRange) / Math.max(0.1, f.weight));
            }
        }
        return f.get(name);
    }

    @Override
    public String phase() {
        return phase;
    }

    @Override
    public boolean roll(Object key, double p) {
        Boolean r = rolls.get(key);
        if (r == null) {
            r = random.nextDouble() < p;
            rolls.put(key, r);
        }
        return r;
    }
}
