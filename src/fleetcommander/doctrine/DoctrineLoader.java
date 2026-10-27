package fleetcommander.doctrine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the doctrine index (CSV merged across mods), every listed doctrine file, resolves `extends` and parses
 * the result. A doctrine with errors is dropped (the errors are reported); the others still load.
 */
public final class DoctrineLoader {
    private final ConfigSource source;
    private final RoleSet globalRoles;
    private final Doctrine.TacticChecker checker;
    private final Validation v;

    private final Map<String, JSONObject> raw = new LinkedHashMap<>();
    private final Map<String, String> files = new HashMap<>();
    private final Map<String, JSONObject> resolved = new HashMap<>();
    private final List<String> resolving = new ArrayList<>();

    public DoctrineLoader(ConfigSource source, RoleSet globalRoles, Doctrine.TacticChecker checker, Validation v) {
        this.source = source;
        this.globalRoles = globalRoles;
        this.checker = checker;
        this.v = v;
    }

    public Map<String, Doctrine> load(String indexPath) {
        Map<String, Doctrine> out = new LinkedHashMap<>();
        JSONArray rows;
        v.setFile(indexPath);
        try {
            rows = source.loadDoctrineIndex(indexPath);
        } catch (Exception e) {
            v.error("(file)", "could not read the doctrine index: " + e.getMessage());
            return out;
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String id = row.optString("id", "").trim();
            String file = row.optString("file", "").trim();
            String enabled = row.optString("enabled", "true").trim();
            if (id.isEmpty() || id.startsWith("#")) continue;
            v.setFile(indexPath);
            if (file.isEmpty()) {
                v.error("row " + id, "missing \"file\"");
                continue;
            }
            if (enabled.equalsIgnoreCase("false") || enabled.equals("0")) continue;
            v.setFile(file);
            try {
                JSONObject j = source.loadJSON(file);
                String fileId = j.optString("id", "");
                if (!fileId.equals(id)) {
                    v.error("id", "the file says \"" + fileId + "\" but doctrines.csv lists it as \"" + id + "\"");
                    continue;
                }
                raw.put(id, j);
                files.put(id, file);
            } catch (Exception e) {
                v.error("(file)", "could not read: " + e.getMessage());
            }
        }
        for (String id : raw.keySet()) {
            v.setFile(files.get(id));
            int before = v.errorCount();
            JSONObject merged = resolve(id);
            if (merged == null || v.errorCount() > before) continue;
            Doctrine d = Doctrine.parse(merged, files.get(id), globalRoles, checker, v);
            if (v.errorCount() == before) out.put(id, d);
        }
        return out;
    }

    private JSONObject resolve(String id) {
        if (resolved.containsKey(id)) return resolved.get(id);
        JSONObject j = raw.get(id);
        if (j == null) return null;
        if (resolving.contains(id)) {
            v.error("extends", "circular extends: " + String.join(" -> ", resolving) + " -> " + id);
            return null;
        }
        String parentId = j.optString("extends", "");
        JSONObject result;
        if (parentId.isEmpty()) {
            result = j;
        } else {
            resolving.add(id);
            JSONObject parent = resolve(parentId);
            resolving.remove(id);
            if (parent == null) {
                if (!raw.containsKey(parentId)) {
                    v.setFile(files.get(id));
                    v.error("extends", "unknown doctrine \"" + parentId + "\" (not in doctrines.csv, or disabled)");
                }
                return null;
            }
            result = merge(parent, j);
        }
        resolved.put(id, result);
        return result;
    }

    /**
     * Child on top of parent: `select` is never inherited; `commander` merges key by key; `tactics` merge by id
     * (a matching entry merges field by field, a new id is appended); everything else is replaced.
     */
    static JSONObject merge(JSONObject parent, JSONObject child) {
        JSONObject out = (JSONObject) Json.copy(parent);
        out.remove("select");
        out.remove("extends");
        for (java.util.Iterator<?> it = child.keys(); it.hasNext(); ) {
            String k = String.valueOf(it.next());
            Object cv = child.opt(k);
            if (k.equals("commander") && cv instanceof JSONObject && out.opt(k) instanceof JSONObject) {
                Json.put(out, k, Json.deepMerge(out.optJSONObject(k), (JSONObject) cv));
            } else if (k.equals("tactics") && cv instanceof JSONArray && out.opt(k) instanceof JSONArray) {
                Json.put(out, k, mergeTactics(out.optJSONArray(k), (JSONArray) cv));
            } else if (!k.equals("extends")) {
                Json.put(out, k, Json.copy(cv));
            }
        }
        return out;
    }

    private static JSONArray mergeTactics(JSONArray base, JSONArray over) {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < base.length(); i++) {
            JSONObject t = base.optJSONObject(i);
            if (t != null) list.add(t);
        }
        for (int i = 0; i < over.length(); i++) {
            JSONObject t = over.optJSONObject(i);
            if (t == null) continue;
            String id = t.optString("id", "");
            boolean merged = false;
            for (int k = 0; k < list.size() && !id.isEmpty(); k++) {
                if (id.equals(list.get(k).optString("id", ""))) {
                    list.set(k, Json.deepMerge(list.get(k), t));
                    merged = true;
                    break;
                }
            }
            if (!merged) list.add(t);
        }
        JSONArray out = new JSONArray();
        for (JSONObject t : list) out.put(t);
        return out;
    }
}
