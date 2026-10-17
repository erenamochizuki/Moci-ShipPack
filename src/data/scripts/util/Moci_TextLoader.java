package data.scripts.util;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本加载器 - 从外部JSON文件加载本地化文本
 * 用于实现船插、技能等描述文本的外置化管理
 * 
 * 使用方法：
 * 1. 在 data/config/Moci_Hullmod_Texts/Moci_Hullmod_Texts.json 中定义文本
 * 2. 使用 Moci_TextLoader.getText() 获取文本
 * 3. 使用 Moci_TextLoader.getHighlights() 获取高亮列表
 */
public class Moci_TextLoader {
    private static final Logger log = Global.getLogger(Moci_TextLoader.class);
    
    // 配置文件路径
    private static final String HULLMOD_CONFIG_PATH = "data/config/Moci_Hullmod_Texts/Moci_Hullmod_Texts.json";
    private static final String INTEL_CONFIG_PATH = "data/config/Moci_IntelText/Moci_Intel_Texts.json";
    private static final String CAMPAIGN_CONFIG_PATH = "data/config/Moci_CampaignText/Moci_Campaign_Texts.json";
    private static final String[] CONFIG_PATHS = {HULLMOD_CONFIG_PATH, INTEL_CONFIG_PATH, CAMPAIGN_CONFIG_PATH};
    
    // 缓存加载的文本数据
    private static Map<String, JSONObject> textCache = new HashMap<>();
    
    // 是否已初始化
    private static boolean initialized = false;
    
    /**
     * 初始化文本加载器
     * 从JSON文件加载所有文本数据到缓存
     */
    public static void initialize() {
        if (initialized) {
            return;
        }
        
        for (String configPath : CONFIG_PATHS) {
            loadConfigPath(configPath);
        }
        initialized = true;
        log.info("文本加载器初始化成功，累计加载了 " + textCache.size() + " 条文本数据");
    }

    private static void loadConfigPath(String configPath) {
        try {
            String jsonContent = Global.getSettings().loadText(configPath);
            JSONObject root = new JSONObject(jsonContent);
            if (root != null && root.length() > 0) {
                for (String entryId : JSONObject.getNames(root)) {
                    JSONObject entry = root.getJSONObject(entryId);
                    textCache.put(entryId, entry);
                }
            }
            log.info("加载文本配置: " + configPath + " (共 " + (root == null ? 0 : root.length()) + " 项)");
        } catch (IOException e) {
            log.warn("无法加载文本配置文件: " + configPath, e);
        } catch (JSONException e) {
            log.warn("文本配置文件JSON格式错误: " + configPath, e);
        }
    }
    
    /**
     * 获取文本
     * 
     * @param hullmodId 船插ID（如 "Moci_MobileSuitsIDcard"）
     * @param key 文本键（如 "description.main"）
     * @return 文本内容，如果未找到返回 "[缺失文本: " + key + "]"
     */
    public static String getText(String hullmodId, String key) {
        return getText(hullmodId, key, "[缺失文本: " + key + "]");
    }

    public static String getText(String hullmodId, String key, String fallback) {
        String value = getTextOrNull(hullmodId, key);
        if (value != null) {
            return value;
        }
        if (fallback != null) {
            return fallback;
        }
        return "[缺失文本: " + key + "]";
    }

    public static String getTextOrNull(String hullmodId, String key) {
        if (!initialized) {
            initialize();
        }
        JSONObject hullmodData = textCache.get(hullmodId);
        if (hullmodData == null) {
            return null;
        }

        String[] keys = key.split("\\.");
        Object current = hullmodData;
        for (String part : keys) {
            if (!(current instanceof JSONObject)) {
                return null;
            }
            current = ((JSONObject) current).opt(part);
            if (current == null) {
                return null;
            }
        }

        if (current instanceof String) {
            return (String) current;
        }
        return null;
    }
    
    /**
     * 获取高亮文本列表
     * 
     * @param hullmodId 船插ID
     * @param key 高亮键（如 "description.main_highlights"）
     * @return 高亮文本列表，如果未找到返回空列表
     */
    public static List<String> getHighlights(String hullmodId, String key) {
        if (!initialized) {
            initialize();
        }
        
        List<String> result = new ArrayList<>();
        
        try {
            JSONObject hullmodData = textCache.get(hullmodId);
            if (hullmodData == null) {
                return result;
            }
            
            // 支持嵌套键
            String[] keys = key.split("\\.");
            Object current = hullmodData;
            
            for (String k : keys) {
                if (current instanceof JSONObject) {
                    current = ((JSONObject) current).get(k);
                } else {
                    return result;
                }
            }
            
            if (current instanceof JSONArray) {
                JSONArray array = (JSONArray) current;
                for (int i = 0; i < array.length(); i++) {
                    result.add(array.getString(i));
                }
            }
            
        } catch (JSONException e) {
            log.warn("获取高亮列表时发生错误: " + key + " (在 " + hullmodId + " 中)", e);
        }
        
        return result;
    }
    
    /**
     * 获取文本并替换占位符
     * 
     * @param hullmodId 船插ID
     * @param key 文本键
     * @param replacements 替换映射（如 {"damage": "50", "health": "200"}）
     * @return 替换后的文本
     */
    public static String getTextWithReplacements(String hullmodId, String key, Map<String, String> replacements) {
        String text = getText(hullmodId, key);
        
        if (replacements != null) {
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                text = text.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        
        // 保留 } 分隔符，Starsector需要它们来防止换行
        return text;
    }
    
    /**
     * 获取高亮列表并替换占位符
     * 
     * @param hullmodId 船插ID
     * @param key 高亮键
     * @param replacements 替换映射
     * @return 替换后的高亮列表
     */
    public static List<String> getHighlightsWithReplacements(String hullmodId, String key, Map<String, String> replacements) {
        List<String> highlights = getHighlights(hullmodId, key);
        
        if (replacements != null && !highlights.isEmpty()) {
            List<String> result = new ArrayList<>();
            for (String highlight : highlights) {
                String replaced = highlight;
                for (Map.Entry<String, String> entry : replacements.entrySet()) {
                    replaced = replaced.replace("{" + entry.getKey() + "}", entry.getValue());
                }
                result.add(replaced);
            }
            return result;
        }
        
        return highlights;
    }

    /**
     * 构造简单的字符串替换映射。
     */
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
    
    /**
     * 重新加载文本数据（用于调试）
     */
    public static void reload() {
        textCache.clear();
        initialized = false;
        initialize();
    }
}
