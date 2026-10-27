package fleetcommander.doctrine;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Collects problems found while reading config files. Errors make the file unusable; warnings (unknown keys,
 * suspicious values) are only reported. Every message names the file and the path of the field.
 */
public class Validation {
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private String file = "?";

    public void setFile(String file) {
        this.file = file;
    }

    public String getFile() {
        return file;
    }

    public void error(String path, String message) {
        errors.add(file + ": " + path + ": " + message);
    }

    public void warn(String path, String message) {
        warnings.add(file + ": " + path + ": " + message);
    }

    public List<String> getErrors() {
        return errors;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public int errorCount() {
        return errors.size();
    }

    /** Warns about keys not in {@code allowed}. Keys starting with "_" are free-form notes and never warned about. */
    public void checkKeys(JSONObject json, String path, String... allowed) {
        if (json == null) return;
        Set<String> ok = new HashSet<>(Arrays.asList(allowed));
        for (Iterator<?> it = json.keys(); it.hasNext(); ) {
            String key = String.valueOf(it.next());
            if (key.startsWith("_") || ok.contains(key)) continue;
            warn(path, "unknown key \"" + key + "\" (ignored; typo?)");
        }
    }

    // ---- typed readers: return the default and record an error on a wrong type ----

    public double number(JSONObject json, String key, String path, double def) {
        if (json == null || !json.has(key) || json.isNull(key)) return def;
        Object v = json.opt(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        error(path + "." + key, "expected a number, got " + describe(v));
        return def;
    }

    public int integer(JSONObject json, String key, String path, int def) {
        double d = number(json, key, path, def);
        if (d != Math.floor(d)) warn(path + "." + key, "expected a whole number, rounding " + d);
        return (int) Math.round(d);
    }

    public boolean bool(JSONObject json, String key, String path, boolean def) {
        if (json == null || !json.has(key) || json.isNull(key)) return def;
        Object v = json.opt(key);
        if (v instanceof Boolean) return (Boolean) v;
        error(path + "." + key, "expected true or false, got " + describe(v));
        return def;
    }

    /** Boolean that may be null (meaning "not set"). */
    public Boolean optBool(JSONObject json, String key, String path) {
        if (json == null || !json.has(key) || json.isNull(key)) return null;
        return bool(json, key, path, false);
    }

    public String string(JSONObject json, String key, String path, String def) {
        if (json == null || !json.has(key) || json.isNull(key)) return def;
        Object v = json.opt(key);
        if (v instanceof String) return (String) v;
        error(path + "." + key, "expected text, got " + describe(v));
        return def;
    }

    public JSONObject object(JSONObject json, String key, String path) {
        if (json == null || !json.has(key) || json.isNull(key)) return null;
        Object v = json.opt(key);
        if (v instanceof JSONObject) return (JSONObject) v;
        error(path + "." + key, "expected an object { ... }, got " + describe(v));
        return null;
    }

    public static String describe(Object v) {
        if (v == null || v == JSONObject.NULL) return "null";
        if (v instanceof String) return "text \"" + v + "\"";
        if (v instanceof Number) return "number " + v;
        if (v instanceof Boolean) return String.valueOf(v);
        if (v instanceof JSONObject) return "an object";
        return "a list";
    }
}
