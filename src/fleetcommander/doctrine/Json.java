package fleetcommander.doctrine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/** Small JSON helpers for merging doctrines. */
public final class Json {
    private Json() {
    }

    /** Deep copy (objects and lists are copied; numbers, strings and booleans are immutable). */
    public static Object copy(Object v) {
        try {
            if (v instanceof JSONObject) {
                JSONObject src = (JSONObject) v, out = new JSONObject();
                for (Iterator<?> it = src.keys(); it.hasNext(); ) {
                    String k = String.valueOf(it.next());
                    out.put(k, copy(src.opt(k)));
                }
                return out;
            }
            if (v instanceof JSONArray) {
                JSONArray src = (JSONArray) v, out = new JSONArray();
                for (int i = 0; i < src.length(); i++) out.put(copy(src.opt(i)));
                return out;
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return v;
    }

    /**
     * Keys whose value is a condition: an override replaces the whole condition instead of merging into it
     * (merging two conditions key by key would produce nonsense such as two operators in one comparison).
     */
    public static final java.util.Set<String> ATOMIC = java.util.Set.of("when", "require", "abort", "release",
            "escortees", "assaultWhen");

    /**
     * Returns a copy of {@code base} with {@code over} applied: objects merge key by key (recursively), except the
     * {@link #ATOMIC} condition keys; anything else (numbers, text, lists) in {@code over} replaces the base value.
     */
    public static JSONObject deepMerge(JSONObject base, JSONObject over) {
        JSONObject out = (JSONObject) copy(base);
        try {
            for (Iterator<?> it = over.keys(); it.hasNext(); ) {
                String k = String.valueOf(it.next());
                Object ov = over.opt(k), bv = out.opt(k);
                if (ov instanceof JSONObject && bv instanceof JSONObject && !ATOMIC.contains(k)) {
                    out.put(k, deepMerge((JSONObject) bv, (JSONObject) ov));
                } else {
                    out.put(k, copy(ov));
                }
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
