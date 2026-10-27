package fleetcommander;

/** Every id, path and key the mod uses, in one place. */
public final class FCIds {
    public static final String MOD_ID = "fleet_commander";
    public static final String VERSION = "0.1.0";

    public static final String CONFIG_DIR = "data/config/fleet_commander/";
    public static final String SETTINGS_FILE = CONFIG_DIR + "settings.json";
    public static final String ROLES_FILE = CONFIG_DIR + "roles.json";
    public static final String DOCTRINE_INDEX = CONFIG_DIR + "doctrines.csv";

    public static final String DEFAULT_DOCTRINE = "default";

    /** Campaign fleet memory: force a doctrine id for this fleet's battles. */
    public static final String MEM_DOCTRINE = "$fc_doctrine";
    /** Campaign fleet memory: true = vanilla admiral keeps command of this fleet. */
    public static final String MEM_DISABLE = "$fc_disable";

    /** Stat modifier id for bonus command points. */
    public static final String CP_BONUS_ID = "fc_bonus";
    /** engine.getCustomData() key of the running commander. */
    public static final String COMMANDER_KEY = "fc_commander";

    private FCIds() {
    }
}
