package data.hullmods;

import java.util.LinkedHashMap;
import java.util.Map;

import data.scripts.util.Moci_TextLoader;

/**
 * CombatSquad 文本注册表。
 */
public class Moci_CombatSquadTextRegistry {

    private static final String TEXT_ID = "Moci_CombatSquadIntel";

    private Moci_CombatSquadTextRegistry() {
    }

    public static void reload() {
        Moci_TextLoader.reload();
    }

    public static String getText(String key, String fallback) {
        return Moci_TextLoader.getText(TEXT_ID, key, fallback);
    }

    public static String format(String key, String fallback, String... replacements) {
        return formatTemplate(getText(key, fallback), mapOf(replacements));
    }

    public static String formatTemplate(String template, Map<String, String> replacements) {
        if (template == null) {
            return "";
        }
        String result = template;
        if (replacements != null) {
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return result;
    }

    public static Map<String, String> mapOf(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        if (values == null) {
            return result;
        }
        for (int i = 0; i + 1 < values.length; i += 2) {
            result.put(values[i], values[i + 1]);
        }
        return result;
    }
}
