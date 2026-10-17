package data.hullmods;

import java.util.LinkedHashMap;
import java.util.Map;

import data.scripts.util.Moci_TextLoader;

/**
 * 舰载AI文本注册表。
 *
 * 负责加载：
 * 1. UI文本
 * 2. 辅助词条显示名称
 * 3. 辅助词条描述模板
 */
public class Moci_ShipboardAITextRegistry {

    private static final String INTEL_TEXT_ID = "Moci_ShipboardAIIntel";

    private Moci_ShipboardAITextRegistry() {
    }

    public static void reload() {
        Moci_TextLoader.reload();
    }

    public static String getUiText(String key, String fallback) {
        return Moci_TextLoader.getText(INTEL_TEXT_ID, "ui." + key, fallback);
    }

    public static String getAuxDisplayName(String auxId, String fallback) {
        return Moci_TextLoader.getText(INTEL_TEXT_ID, "aux." + auxId + ".name", fallback);
    }

    public static String getAuxDescriptionTemplate(String auxId, String fallback) {
        return Moci_TextLoader.getText(INTEL_TEXT_ID, "aux." + auxId + ".description", fallback);
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
