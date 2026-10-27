package fleetcommander.tactics;

import fleetcommander.combat.FCContext;
import fleetcommander.combat.FleetCommander;
import fleetcommander.combat.ShipFacts;
import fleetcommander.doctrine.Condition;
import fleetcommander.doctrine.Doctrine;
import fleetcommander.doctrine.Facts;
import fleetcommander.doctrine.Score;
import fleetcommander.doctrine.Validation;
import org.json.JSONObject;

/** The "targets" block: filter, then score (DOCTRINE_FORMAT.md section 8). */
final class TargetSpec {
    Condition require = Condition.TRUE;
    Score score = Score.ZERO;
    double minScore = Double.NEGATIVE_INFINITY;
    float isolationRange = ShipFacts.DEFAULT_ISOLATION_RANGE;

    static TargetSpec parse(JSONObject j, Validation v, String path, Facts.Scope subject) {
        TargetSpec s = new TargetSpec();
        if (j == null) return s;
        v.checkKeys(j, path, "require", "score", "minScore", "isolationRange");
        s.require = Condition.parse(j.opt("require"), v, path + ".require", Doctrine.scopes(subject));
        s.score = Score.parse(v.object(j, "score", path), v, path + ".score", Doctrine.scopes(subject));
        s.minScore = v.number(j, "minScore", path, Double.NEGATIVE_INFINITY);
        s.isolationRange = (float) v.number(j, "isolationRange", path, ShipFacts.DEFAULT_ISOLATION_RANGE);
        return s;
    }

    /** Score of an enemy ship, or NaN if it fails "require" or "minScore". Includes the doctrine's noise. */
    double evaluate(FleetCommander c, ShipFacts target, double age) {
        FCContext ctx = c.ctx();
        ctx.clear();
        ctx.subject = target;
        ctx.age = age;
        ctx.isolationRange = isolationRange;
        try {
            if (!require.test(ctx)) return Double.NaN;
            double s = score.eval(ctx) * c.noise();
            return s >= minScore ? s : Double.NaN;
        } finally {
            ctx.clear();
        }
    }
}
