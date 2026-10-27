package fleetcommander.doctrine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Ordered role rules: a ship gets the first role whose condition matches (docs/DOCTRINE_FORMAT.md section 6). */
public final class RoleSet {
    public static final String FALLBACK = "skirmisher";

    private final List<String> ids = new ArrayList<>();
    private final List<Condition> rules = new ArrayList<>();
    private final Set<String> known = new LinkedHashSet<>();

    public String roleOf(EvalContext shipCtx) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).test(shipCtx)) return ids.get(i);
        }
        return FALLBACK;
    }

    public Set<String> knownRoles() {
        return known;
    }

    /** Parses { "roles": [ { "id": ..., "require": cond }, ... ] } (or the list itself). */
    public static RoleSet parse(Object raw, Validation v, String path) {
        RoleSet set = new RoleSet();
        JSONArray arr = null;
        if (raw instanceof JSONObject) {
            v.checkKeys((JSONObject) raw, path, "roles");
            arr = ((JSONObject) raw).optJSONArray("roles");
        } else if (raw instanceof JSONArray) {
            arr = (JSONArray) raw;
        }
        if (arr == null) {
            v.error(path, "expected a list of roles");
            return set;
        }
        EnumSet<Facts.Scope> scopes = EnumSet.of(Facts.Scope.SHIP);
        for (int i = 0; i < arr.length(); i++) {
            String p = path + "[" + i + "]";
            JSONObject r = arr.optJSONObject(i);
            if (r == null) {
                v.error(p, "expected { \"id\": ..., \"require\": ... }");
                continue;
            }
            v.checkKeys(r, p, "id", "require");
            String id = v.string(r, "id", p, null);
            if (id == null) {
                v.error(p, "missing \"id\"");
                continue;
            }
            set.ids.add(id);
            set.rules.add(Condition.parse(r.opt("require"), v, p + ".require", scopes));
            set.known.add(id);
        }
        set.known.add(FALLBACK);
        return set;
    }
}
