package fleetcommander;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import fleetcommander.doctrine.ConfigSource;
import org.json.JSONArray;
import org.json.JSONObject;

/** Loads and checks the config once, when the game starts. All problems go to starsector.log. */
public class FCModPlugin extends BaseModPlugin {
    /** In game: files are found across enabled mods; settings.json and doctrines.csv merge across mods. */
    static final class GameSource implements ConfigSource {
        @Override
        public JSONObject loadJSON(String path) throws Exception {
            if (path.equals(FCIds.SETTINGS_FILE)) return Global.getSettings().getMergedJSONForMod(path, FCIds.MOD_ID);
            return Global.getSettings().loadJSON(path);
        }

        @Override
        public JSONArray loadDoctrineIndex(String path) throws Exception {
            return Global.getSettings().getMergedSpreadsheetDataForMod("id", path, FCIds.MOD_ID);
        }
    }

    @Override
    public void onApplicationLoad() {
        GameSource source = new GameSource();
        FCConfig config = FCConfig.load(source);
        config.logProblems();
        FCConfig.set(config, source);
    }
}
