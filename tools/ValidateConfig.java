import fleetcommander.FCConfig;
import fleetcommander.FCIds;
import fleetcommander.doctrine.Condition;
import fleetcommander.doctrine.ConfigSource;
import fleetcommander.doctrine.Doctrine;
import fleetcommander.doctrine.EvalContext;
import fleetcommander.doctrine.Facts;
import fleetcommander.doctrine.Score;
import fleetcommander.doctrine.Validation;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline check of the mod's config, run by build.sh: loads settings, roles and every doctrine through the same
 * code the game uses (with plain files instead of Global.getSettings()), prints every error and warning, then runs
 * self-tests of conditions, scores and doctrine merging. Exit code 1 on any error or failed test.
 *
 * Usage: java ValidateConfig <mod folder>
 */
public class ValidateConfig {
    private static int failures;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        FileSource source = new FileSource(root);
        FCConfig config = FCConfig.load(source);
        for (String w : config.validation.getWarnings()) System.out.println("warning: " + w);
        for (String e : config.validation.getErrors()) System.out.println("ERROR:   " + e);
        System.out.println("doctrines loaded: " + config.doctrines.keySet());
        for (Doctrine d : config.doctrines.values()) {
            int on = 0;
            for (JSONObject t : d.tactics) if (t.optBoolean("enabled", true)) on++;
            System.out.println("  " + d.id + " (" + d.name + "): " + on + " tactics, phases "
                    + d.phases.size() + (d.selectable ? ", selectable, weight " + d.selectWeight : ", explicit only"));
        }
        selfTests(config);
        int errors = config.validation.errorCount();
        System.out.println(errors == 0 && failures == 0 ? "config OK" :
                "config FAILED: " + errors + " errors, " + failures + " failed self-tests");
        System.exit(errors == 0 && failures == 0 ? 0 : 1);
    }

    /** Reads files below the mod folder, stripping '#' comments the way the game's loader does. */
    static final class FileSource implements ConfigSource {
        private final Path root;

        FileSource(Path root) {
            this.root = root;
        }

        @Override
        public JSONObject loadJSON(String path) throws Exception {
            return new JSONObject(stripComments(Files.readString(root.resolve(path), StandardCharsets.UTF_8)));
        }

        @Override
        public JSONArray loadDoctrineIndex(String path) throws Exception {
            List<String> lines = Files.readAllLines(root.resolve(path), StandardCharsets.UTF_8);
            JSONArray rows = new JSONArray();
            if (lines.isEmpty()) return rows;
            if (lines.get(0).startsWith("﻿")) throw new IllegalStateException("file starts with a UTF-8 BOM");
            String[] header = lines.get(0).split(",", -1);
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) continue;
                String[] cells = line.split(",", -1);
                if (cells.length != header.length) {
                    throw new IllegalStateException("line " + (i + 1) + " has " + cells.length + " cells, header has "
                            + header.length);
                }
                JSONObject row = new JSONObject();
                for (int k = 0; k < header.length; k++) row.put(header[k].trim(), cells[k].trim());
                rows.put(row);
            }
            return rows;
        }
    }

    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < text.length()) out.append(text.charAt(++i));
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
                out.append(c);
            } else if (c == '#') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
                out.append('\n');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------------------

    static final class MapContext implements EvalContext {
        final Map<String, Double> facts = new HashMap<>();
        String phase = "main";

        MapContext set(String k, double v) {
            facts.put(k, v);
            return this;
        }

        @Override
        public double fact(String name) {
            Double v = facts.get(name);
            return v == null ? Double.NaN : v;
        }

        @Override
        public String phase() {
            return phase;
        }

        @Override
        public boolean roll(Object key, double p) {
            return p >= 0.5;
        }
    }

    static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("SELF-TEST FAILED: " + what);
        }
    }

    static Condition cond(String json, java.util.EnumSet<Facts.Scope> scopes, Validation v) throws Exception {
        Object raw = json.trim().startsWith("{") ? new JSONObject(json) : Boolean.valueOf(json.trim());
        return Condition.parse(raw, v, "test", scopes);
    }

    private static void selfTests(FCConfig config) throws Exception {
        Validation v = new Validation();
        java.util.EnumSet<Facts.Scope> ship = Doctrine.scopes(Facts.Scope.SHIP, Facts.Scope.TARGET, Facts.Scope.PACK);
        MapContext ctx = new MapContext().set("hull", 0.25).set("battle.time", 30).set("target.isolation", 0.2)
                .set("pack.leash", 5000);

        check("compare <", cond("{\"fact\":\"hull\",\"<\":0.3}", ship, v).test(ctx));
        check("compare >= false", !cond("{\"fact\":\"hull\",\">=\":0.3}", ship, v).test(ctx));
        check("between", cond("{\"fact\":\"hull\",\"between\":[0.2,0.3]}", ship, v).test(ctx));
        check("all/any/not", cond("{\"all\":[{\"fact\":\"battle.time\",\"<\":40},{\"any\":[{\"fact\":\"hull\","
                + "\">\":0.9},{\"not\":{\"fact\":\"hull\",\">\":0.5}}]}]}", ship, v).test(ctx));
        check("missing fact is false", !cond("{\"fact\":\"flux\",\">\":-1}", ship, v).test(ctx));
        check("pack and target", cond("{\"any\":[{\"fact\":\"target.isolation\",\"<\":0.35},"
                + "{\"fact\":\"pack.leash\",\">\":4000}]}", ship, v).test(ctx));
        check("phase", cond("{\"phase\":[\"main\"]}", ship, v).test(ctx));
        check("no errors in valid test conditions", v.errorCount() == 0);

        Validation bad = new Validation();
        cond("{\"fact\":\"hul\",\"<\":0.3}", ship, bad);
        check("typo in fact name is an error", bad.errorCount() == 1);
        bad = new Validation();
        cond("{\"fact\":\"target.hull\",\"<\":0.3}", Facts.GLOBAL, bad);
        check("target.* outside a target scope is an error", bad.errorCount() >= 1);
        bad = new Validation();
        cond("{\"fact\":\"hull\",\"<\":0.3,\">\":0.1}", ship, bad);
        check("two operators is an error", bad.errorCount() == 1);

        Score s = Score.parse(new JSONObject("{\"hull\":2,\"battle.time\":0.1,\"flux\":5}"), v, "score", ship);
        check("score sums weights, skips missing facts", Math.abs(s.eval(ctx) - (0.5 + 3.0)) < 1e-9);

        // merging: wolfpack overrides default's hunt_loners priority and require, disables focus
        Doctrine wolf = config.doctrines.get("wolfpack");
        Doctrine def = config.doctrines.get(FCIds.DEFAULT_DOCTRINE);
        check("default and wolfpack loaded", wolf != null && def != null);
        if (wolf != null && def != null) {
            JSONObject hunt = find(wolf, "hunt_loners"), focus = find(wolf, "focus");
            check("merged tactic keeps parent type", hunt != null && "hunt_isolated".equals(hunt.optString("type")));
            check("merged tactic takes child priority", hunt != null && hunt.optInt("priority") == 95);
            check("merged tactic keeps parent keep block", hunt != null && hunt.optJSONObject("keep") != null
                    && hunt.optJSONObject("keep").optDouble("timeout") == 45);
            check("condition replaced whole, not merged",
                    hunt != null && hunt.optJSONObject("targets").optJSONObject("require").optJSONArray("all")
                            .length() == 2);
            check("disabled override", focus != null && !focus.optBoolean("enabled", true));
            check("select is not inherited", def.selectable && wolf.selectWeight == 10);
            check("new tactic appended", find(wolf, "dont_joust") != null);
            check("phases inherited", wolf.phases.size() == def.phases.size());
        }
        Doctrine line = config.doctrines.get("battle_line");
        check("battle_line loaded", line != null);
    }

    private static JSONObject find(Doctrine d, String id) {
        for (JSONObject t : d.tactics) if (id.equals(t.optString("id"))) return t;
        return null;
    }
}
