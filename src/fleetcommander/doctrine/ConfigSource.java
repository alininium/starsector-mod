package fleetcommander.doctrine;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Where config files come from. In game: Global.getSettings() (files are found across all enabled mods, and the
 * CSV index is merged across mods). Offline: plain files (tools/ValidateConfig.java).
 */
public interface ConfigSource {
    JSONObject loadJSON(String path) throws Exception;

    /** Rows of the doctrine index, merged across mods. Each row has at least "id" and "file". */
    JSONArray loadDoctrineIndex(String path) throws Exception;
}
