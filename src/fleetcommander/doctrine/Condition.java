package fleetcommander.doctrine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * A condition tree (docs/DOCTRINE_FORMAT.md section 5):
 * <pre>
 *   true | false
 *   { "fact": "battle.strengthRatio", "&gt;=": 1.5 }      operators &lt; &lt;= &gt; &gt;= == != between
 *   { "all": [ ... ] }  { "any": [ ... ] }  { "not": cond }
 *   { "phase": ["opening", "main"] }
 *   { "chance": 0.5 }                                rolled once per battle
 * </pre>
 * Immutable and shared between battles; per-battle state (chance rolls) lives in the {@link EvalContext}.
 */
public abstract class Condition {
    public static final Condition TRUE = new Const(true);
    public static final Condition FALSE = new Const(false);

    public abstract boolean test(EvalContext ctx);

    /** Parses a condition; on errors, records them and returns FALSE (so a broken condition never fires). */
    public static Condition parse(Object raw, Validation v, String path, EnumSet<Facts.Scope> scopes) {
        if (raw == null || raw == JSONObject.NULL) return TRUE;
        if (raw instanceof Boolean) return (Boolean) raw ? TRUE : FALSE;
        if (!(raw instanceof JSONObject)) {
            v.error(path, "expected a condition (true, false or { ... }), got " + Validation.describe(raw));
            return FALSE;
        }
        JSONObject o = (JSONObject) raw;
        if (o.has("all") || o.has("any")) {
            boolean all = o.has("all");
            String key = all ? "all" : "any";
            v.checkKeys(o, path, key);
            JSONArray arr = o.optJSONArray(key);
            if (arr == null) {
                v.error(path + "." + key, "expected a list of conditions");
                return FALSE;
            }
            List<Condition> parts = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                parts.add(parse(arr.opt(i), v, path + "." + key + "[" + i + "]", scopes));
            }
            return new Group(all, parts.toArray(new Condition[0]));
        }
        if (o.has("not")) {
            v.checkKeys(o, path, "not");
            return new Not(parse(o.opt("not"), v, path + ".not", scopes));
        }
        if (o.has("phase")) {
            v.checkKeys(o, path, "phase");
            Set<String> ids = new HashSet<>();
            Object p = o.opt("phase");
            if (p instanceof String) {
                ids.add((String) p);
            } else if (p instanceof JSONArray) {
                JSONArray arr = (JSONArray) p;
                for (int i = 0; i < arr.length(); i++) ids.add(arr.optString(i));
            } else {
                v.error(path + ".phase", "expected a phase id or a list of them");
                return FALSE;
            }
            return new Phase(ids);
        }
        if (o.has("chance")) {
            v.checkKeys(o, path, "chance");
            double p = v.number(o, "chance", path, 0);
            if (p < 0 || p > 1) v.error(path + ".chance", "must be between 0 and 1");
            return new Chance(p);
        }
        if (o.has("fact")) {
            String fact = v.string(o, "fact", path, null);
            if (fact == null) return FALSE;
            String problem = Facts.check(fact, scopes);
            if (problem != null) v.error(path + ".fact", problem);
            String op = null;
            for (Iterator<?> it = o.keys(); it.hasNext(); ) {
                String k = String.valueOf(it.next());
                if (k.equals("fact") || k.startsWith("_")) continue;
                if (Compare.isOperator(k) || k.equals("between")) {
                    if (op != null) v.error(path, "more than one operator (\"" + op + "\" and \"" + k + "\")");
                    op = k;
                } else {
                    v.warn(path, "unknown key \"" + k + "\" (ignored; typo?)");
                }
            }
            if (op == null) {
                v.error(path, "missing operator: one of < <= > >= == != between");
                return FALSE;
            }
            if (op.equals("between")) {
                JSONArray range = o.optJSONArray("between");
                if (range == null || range.length() != 2) {
                    v.error(path + ".between", "expected [min, max]");
                    return FALSE;
                }
                return new Between(fact, range.optDouble(0), range.optDouble(1));
            }
            if (!(o.opt(op) instanceof Number)) {
                v.error(path + "." + op, "expected a number, got " + Validation.describe(o.opt(op)));
                return FALSE;
            }
            return new Compare(fact, op, ((Number) o.opt(op)).doubleValue());
        }
        v.error(path, "not a condition: expected one of fact, all, any, not, phase, chance");
        return FALSE;
    }

    // ---------------------------------------------------------------------------------------------------------

    static final class Const extends Condition {
        private final boolean value;

        Const(boolean value) {
            this.value = value;
        }

        @Override
        public boolean test(EvalContext ctx) {
            return value;
        }
    }

    static final class Group extends Condition {
        private final boolean all;
        private final Condition[] parts;

        Group(boolean all, Condition[] parts) {
            this.all = all;
            this.parts = parts;
        }

        @Override
        public boolean test(EvalContext ctx) {
            for (Condition c : parts) {
                boolean r = c.test(ctx);
                if (all && !r) return false;
                if (!all && r) return true;
            }
            return all;
        }
    }

    static final class Not extends Condition {
        private final Condition inner;

        Not(Condition inner) {
            this.inner = inner;
        }

        @Override
        public boolean test(EvalContext ctx) {
            return !inner.test(ctx);
        }
    }

    static final class Phase extends Condition {
        private final Set<String> ids;

        Phase(Set<String> ids) {
            this.ids = ids;
        }

        @Override
        public boolean test(EvalContext ctx) {
            return ids.contains(ctx.phase());
        }
    }

    static final class Chance extends Condition {
        private final double p;

        Chance(double p) {
            this.p = p;
        }

        @Override
        public boolean test(EvalContext ctx) {
            return ctx.roll(this, p);
        }
    }

    static final class Compare extends Condition {
        private final String fact;
        private final int op;
        private final double value;
        private static final String[] OPS = {"<", "<=", ">", ">=", "==", "!="};

        static boolean isOperator(String k) {
            for (String s : OPS) if (s.equals(k)) return true;
            return false;
        }

        Compare(String fact, String op, double value) {
            this.fact = fact;
            int i = 0;
            while (!OPS[i].equals(op)) i++;
            this.op = i;
            this.value = value;
        }

        @Override
        public boolean test(EvalContext ctx) {
            double f = ctx.fact(fact);
            if (Double.isNaN(f)) return false;
            switch (op) {
                case 0: return f < value;
                case 1: return f <= value;
                case 2: return f > value;
                case 3: return f >= value;
                case 4: return Math.abs(f - value) < 1e-6;
                default: return Math.abs(f - value) >= 1e-6;
            }
        }
    }

    static final class Between extends Condition {
        private final String fact;
        private final double min, max;

        Between(String fact, double min, double max) {
            this.fact = fact;
            this.min = min;
            this.max = max;
        }

        @Override
        public boolean test(EvalContext ctx) {
            double f = ctx.fact(fact);
            return !Double.isNaN(f) && f >= min && f <= max;
        }
    }
}
