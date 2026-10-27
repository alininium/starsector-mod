package fleetcommander;

import fleetcommander.doctrine.ConfigSource;
import fleetcommander.doctrine.Doctrine;
import fleetcommander.doctrine.DoctrineLoader;
import fleetcommander.doctrine.RoleSet;
import fleetcommander.doctrine.Validation;
import fleetcommander.tactics.FCTactics;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Map;

/**
 * Everything read from data/config/fleet_commander at game start. Holds only parsed config (no game objects), so
 * keeping it in a static field is safe across battles and saves.
 */
public final class FCConfig {
    private static volatile FCConfig current;
    private static volatile ConfigSource lastSource;
    private static volatile boolean dirty;

    public final FCSettings settings;
    public final RoleSet roles;
    public final Map<String, Doctrine> doctrines;
    public final Validation validation;

    private FCConfig(FCSettings settings, RoleSet roles, Map<String, Doctrine> doctrines, Validation validation) {
        this.settings = settings;
        this.roles = roles;
        this.doctrines = Collections.unmodifiableMap(doctrines);
        this.validation = validation;
    }

    /** The loaded config, or null before the game finished loading (or if loading failed outright). */
    public static FCConfig get() {
        if (dirty && lastSource != null) {
            dirty = false;
            current = load(lastSource);
            current.logProblems();
        }
        return current;
    }

    public static void set(FCConfig config, ConfigSource source) {
        current = config;
        lastSource = source;
        dirty = false;
    }

    /** A tactic type was registered after loading: reload on next access. */
    public static void markDirty() {
        dirty = true;
    }

    public void logProblems() {
        org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(FCConfig.class);
        for (String w : validation.getWarnings()) log.warn("Fleet Commander config: " + w);
        for (String e : validation.getErrors()) log.error("Fleet Commander config: " + e);
        log.info("Fleet Commander " + FCIds.VERSION + ": loaded doctrines " + doctrines.keySet() + " ("
                + validation.errorCount() + " errors, " + validation.getWarnings().size() + " warnings)");
    }

    /** Reads settings, roles and doctrines. Never throws: problems are in the returned config's validation. */
    public static FCConfig load(ConfigSource source) {
        Validation v = new Validation();
        FCSettings settings;
        v.setFile(FCIds.SETTINGS_FILE);
        try {
            settings = FCSettings.parse(source.loadJSON(FCIds.SETTINGS_FILE), v);
        } catch (Exception e) {
            v.error("(file)", "could not read, using defaults: " + e.getMessage());
            settings = new FCSettings();
        }
        RoleSet roles;
        v.setFile(FCIds.ROLES_FILE);
        try {
            JSONObject j = source.loadJSON(FCIds.ROLES_FILE);
            roles = RoleSet.parse(j, v, "roles");
        } catch (Exception e) {
            v.error("(file)", "could not read: " + e.getMessage());
            roles = RoleSet.parse(new org.json.JSONArray(), v, "roles");
        }
        Map<String, Doctrine> doctrines = new DoctrineLoader(source, roles, FCTactics::check, v)
                .load(FCIds.DOCTRINE_INDEX);
        v.setFile(FCIds.DOCTRINE_INDEX);
        if (!doctrines.containsKey(FCIds.DEFAULT_DOCTRINE)) {
            v.error("(index)", "no valid \"" + FCIds.DEFAULT_DOCTRINE + "\" doctrine: the commander will stay off");
        }
        for (Map.Entry<String, String> e : settings.factionDoctrines.entrySet()) {
            if (!doctrines.containsKey(e.getValue())) {
                v.setFile(FCIds.SETTINGS_FILE);
                v.warn("factionDoctrines." + e.getKey(), "unknown doctrine \"" + e.getValue() + "\"");
            }
        }
        return new FCConfig(settings, roles, doctrines, v);
    }
}
