package fleetcommander.doctrine;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;

/** A weighted sum of facts: { "value": 1, "isolation": 3, "distance": -2 }. Missing (NaN) facts count as 0. */
public final class Score {
    public static final Score ZERO = new Score(new String[0], new double[0]);

    private final String[] facts;
    private final double[] weights;

    private Score(String[] facts, double[] weights) {
        this.facts = facts;
        this.weights = weights;
    }

    public double eval(EvalContext ctx) {
        double sum = 0;
        for (int i = 0; i < facts.length; i++) {
            double f = ctx.fact(facts[i]);
            if (!Double.isNaN(f)) sum += weights[i] * f;
        }
        return sum;
    }

    public boolean isEmpty() {
        return facts.length == 0;
    }

    public static Score parse(JSONObject raw, Validation v, String path, EnumSet<Facts.Scope> scopes) {
        if (raw == null) return ZERO;
        List<String> names = new ArrayList<>();
        List<Double> ws = new ArrayList<>();
        for (Iterator<?> it = raw.keys(); it.hasNext(); ) {
            String name = String.valueOf(it.next());
            if (name.startsWith("_")) continue;
            String problem = Facts.check(name, scopes);
            if (problem != null) {
                v.error(path + "." + name, problem);
                continue;
            }
            Object w = raw.opt(name);
            if (!(w instanceof Number)) {
                v.error(path + "." + name, "expected a weight (number), got " + Validation.describe(w));
                continue;
            }
            names.add(name);
            ws.add(((Number) w).doubleValue());
        }
        double[] arr = new double[ws.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = ws.get(i);
        return new Score(names.toArray(new String[0]), arr);
    }
}
