package fleetcommander.doctrine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A parsed doctrine (docs/DOCTRINE_FORMAT.md section 3), after `extends` was resolved. Immutable. */
public final class Doctrine {

    /** Commander pacing and command point use. */
    public static final class Commander {
        public double thinkInterval = 2.0;
        public double reactionTime = 1.0;
        public double scoreNoise = 0.1;
        public boolean useCommandPoints = true;
        public int cpReserve = 1;
        /** Seconds after spending a CP in which more orders are free (the player's command window). */
        public double commandWindow = 8.0;
        public boolean announce = true;
    }

    public static final class Phase {
        public final String id;
        public final Condition when;

        Phase(String id, Condition when) {
            this.id = id;
            this.when = when;
        }
    }

    public String id;
    public String name;
    public String description = "";
    public String file;

    /** False when the doctrine has no `select` block: it is only used when picked explicitly. */
    public boolean selectable;
    public Condition selectWhen = Condition.FALSE;
    public Set<String> selectFactions = Collections.emptySet();
    public double selectWeight;

    public final Commander commander = new Commander();
    public final List<Phase> phases = new ArrayList<>();
    /** Null = use the global roles.json. */
    public RoleSet roles;
    /** Tactic entries after merging, in file order; each is checked by the tactic registry at load. */
    public final List<JSONObject> tactics = new ArrayList<>();

    /** Validates a tactic entry for its type; implemented by the tactic registry. */
    public interface TacticChecker {
        void check(JSONObject spec, Validation v, String path, Set<String> roles, Set<String> phases);
    }

    static Doctrine parse(JSONObject j, String file, RoleSet globalRoles, TacticChecker checker, Validation v) {
        Doctrine d = new Doctrine();
        d.file = file;
        String p = "";
        v.checkKeys(j, "(doctrine)", "id", "name", "description", "extends", "select", "commander", "phases",
                "roles", "tactics", "formatVersion");
        d.id = v.string(j, "id", "(doctrine)", null);
        d.name = v.string(j, "name", "(doctrine)", d.id);
        d.description = v.string(j, "description", "(doctrine)", "");
        int format = v.integer(j, "formatVersion", "(doctrine)", 1);
        if (format != 1) v.error("formatVersion", "this version of the mod reads format 1, the file says " + format);

        JSONObject sel = v.object(j, "select", p);
        if (sel != null) {
            d.selectable = true;
            v.checkKeys(sel, "select", "when", "factions", "weight");
            d.selectWhen = Condition.parse(sel.has("when") ? sel.opt("when") : Boolean.TRUE, v, "select.when",
                    Facts.GLOBAL);
            d.selectWeight = v.number(sel, "weight", "select", 0);
            JSONArray f = sel.optJSONArray("factions");
            if (f != null) {
                Set<String> set = new HashSet<>();
                for (int i = 0; i < f.length(); i++) set.add(f.optString(i));
                d.selectFactions = set;
            }
        }

        JSONObject c = v.object(j, "commander", p);
        if (c != null) {
            v.checkKeys(c, "commander", "thinkInterval", "reactionTime", "scoreNoise", "useCommandPoints",
                    "cpReserve", "commandWindow", "announce");
            d.commander.thinkInterval = v.number(c, "thinkInterval", "commander", d.commander.thinkInterval);
            d.commander.reactionTime = v.number(c, "reactionTime", "commander", d.commander.reactionTime);
            d.commander.scoreNoise = v.number(c, "scoreNoise", "commander", d.commander.scoreNoise);
            d.commander.useCommandPoints = v.bool(c, "useCommandPoints", "commander", d.commander.useCommandPoints);
            d.commander.cpReserve = v.integer(c, "cpReserve", "commander", d.commander.cpReserve);
            d.commander.commandWindow = v.number(c, "commandWindow", "commander", d.commander.commandWindow);
            d.commander.announce = v.bool(c, "announce", "commander", d.commander.announce);
            if (d.commander.thinkInterval < 0.25) {
                v.error("commander.thinkInterval", "must be at least 0.25 seconds");
                d.commander.thinkInterval = 0.25;
            }
        }

        Set<String> phaseIds = new LinkedHashSet<>();
        JSONArray ph = j.optJSONArray("phases");
        if (ph != null) {
            for (int i = 0; i < ph.length(); i++) {
                String pp = "phases[" + i + "]";
                JSONObject o = ph.optJSONObject(i);
                if (o == null) {
                    v.error(pp, "expected { \"id\": ..., \"when\": ... }");
                    continue;
                }
                v.checkKeys(o, pp, "id", "when");
                String id = v.string(o, "id", pp, null);
                if (id == null) {
                    v.error(pp, "missing \"id\"");
                    continue;
                }
                Condition when = Condition.parse(o.opt("when"), v, pp + ".when", Facts.GLOBAL);
                d.phases.add(new Phase(id, when));
                phaseIds.add(id);
                if (!o.has("when") && i != ph.length() - 1) {
                    v.warn(pp, "a phase without \"when\" always matches, so the phases after it never run");
                }
            }
        }
        if (phaseIds.isEmpty()) phaseIds.add("main");

        if (j.has("roles")) d.roles = RoleSet.parse(j.opt("roles"), v, "roles");
        Set<String> roleIds = (d.roles != null ? d.roles : globalRoles).knownRoles();

        JSONArray t = j.optJSONArray("tactics");
        if (t == null) {
            v.error("tactics", "a doctrine needs a list of tactics");
        } else {
            Set<String> tacticIds = new HashSet<>();
            for (int i = 0; i < t.length(); i++) {
                String tp = "tactics[" + i + "]";
                JSONObject spec = t.optJSONObject(i);
                if (spec == null) {
                    v.error(tp, "expected a tactic { ... }");
                    continue;
                }
                String tid = spec.optString("id", "");
                if (tid.isEmpty()) v.error(tp, "missing \"id\"");
                else if (!tacticIds.add(tid)) v.error(tp, "duplicate tactic id \"" + tid + "\"");
                tp = "tactics[" + (tid.isEmpty() ? String.valueOf(i) : tid) + "]";
                if (!spec.optBoolean("enabled", true)) {
                    d.tactics.add(spec);
                    continue; // disabled entries are not checked: they may be partial overrides
                }
                checker.check(spec, v, tp, roleIds, phaseIds);
                d.tactics.add(spec);
            }
        }
        return d;
    }

    /** The phase id for this pass: the first phase whose condition holds, else the last one, else "main". */
    public String phaseFor(EvalContext ctx) {
        for (Phase p : phases) {
            if (p.when.test(ctx)) return p.id;
        }
        return phases.isEmpty() ? "main" : phases.get(phases.size() - 1).id;
    }

    public static EnumSet<Facts.Scope> scopes(Facts.Scope... s) {
        EnumSet<Facts.Scope> set = EnumSet.noneOf(Facts.Scope.class);
        Collections.addAll(set, s);
        return set;
    }
}
