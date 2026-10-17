package data.hullmods;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;

/**
 * CombatSquad配置加载器
 * 
 * 负责从CSV文件加载小队配置和制造商列表
 * 使用Starsector API的loadCSV方法,与Moci_GachaManager保持一致
 * 
 * @author Moci
 * @version 1.0
 */
public class Moci_CombatSquadConfigLoader {
    
    // 配置文件路径常量
    private static final String CONFIG_DIR = "data/config/MociCombatSquad/";
    private static final String SQUAD_CONFIG_PATH = CONFIG_DIR + "Moci_CombatSquad_Configs.csv";
    private static final String MANUFACTURER_CONFIG_PATH = CONFIG_DIR + "Moci_CombatSquad_Manufacturers.csv";
    private static final String CALLSIGN_CONFIG_PATH = CONFIG_DIR + "Moci_CombatSquad_Callsigns.csv";
    private static final String AI_CALLSIGN_CONFIG_PATH = CONFIG_DIR + "Moci_CombatSquad_AI_Callsigns.csv";
    private static final String AI_PILOT_NAME_CONFIG_PATH = CONFIG_DIR + "Moci_CombatSquad_AI_PilotNames.csv";
    private static final String SQUAD_NAME_CONFIG_PATH = CONFIG_DIR + "Moci_CombatSquad_SquadNames.csv";
    private static final String AI_PORTRAIT_CONFIG_PATH = CONFIG_DIR + "Moci_CombatSquad_AI_Portraits.csv";
    private static final List<String> DEFAULT_AI_PORTRAITS = new ArrayList<>();
    static {
        DEFAULT_AI_PORTRAITS.add("graphics/portraits/portrait_ai1b.png");
        DEFAULT_AI_PORTRAITS.add("graphics/portraits/portrait_ai2b.png");
        DEFAULT_AI_PORTRAITS.add("graphics/portraits/portrait_ai3b.png");
    }
    
    // 存储加载的配置
    private static Map<String, List<SquadMemberConfig>> squadConfigs = new HashMap<>();
    private static List<String> allowedManufacturers = new ArrayList<>();
    private static List<String> callsigns = new ArrayList<>();
    private static List<String> aiCallsigns = new ArrayList<>();
    private static List<String> aiPilotNames = new ArrayList<>();
    private static List<String> squadPrefixes = new ArrayList<>();
    private static List<String> squadSuffixes = new ArrayList<>();
    private static List<String> aiPortraits = new ArrayList<>();
    
    // 配置是否已加载的标志
    private static boolean configsLoaded = false;
    
    /**
     * 小队成员配置数据类
     */
    public static class SquadMemberConfig {
        public String variantId;        // 装配ID
        public String displayName;      // 显示名称
        public int count;               // 数量(通常为1)
        public int officerLevel;        // 军官等级
        public String[] skills;         // 技能列表
        public String personality;      // 性格
        
        public SquadMemberConfig(String variantId, String displayName, int count, 
                                int officerLevel, String[] skills, String personality) {
            this.variantId = variantId;
            this.displayName = displayName;
            this.count = count;
            this.officerLevel = officerLevel;
            this.skills = skills;
            this.personality = personality;
        }
    }
    
    /**
     * 加载所有配置文件
     * 在游戏启动时调用
     */
    public static void loadAllConfigs() {
        if (configsLoaded) {
            return; // 避免重复加载
        }
        
        Logger log = Global.getLogger(Moci_CombatSquadConfigLoader.class);
        log.info("开始加载CombatSquad配置文件...");
        
        boolean squadLoaded = loadSquadConfigs();
        boolean manufacturerLoaded = loadAllowedManufacturers();
        boolean callsignLoaded = loadCallsigns();
        boolean aiCallsignLoaded = loadAiCallsigns();
        boolean aiPilotNameLoaded = loadAiPilotNames();
        boolean squadNameLoaded = loadSquadNames();
        boolean aiPortraitLoaded = loadAIPortraits();
        
        if (squadLoaded || manufacturerLoaded || callsignLoaded || aiCallsignLoaded
                || aiPilotNameLoaded || squadNameLoaded || aiPortraitLoaded) {
            configsLoaded = true;
            log.info("CombatSquad配置加载完成");
        } else {
            log.warn("所有配置文件加载失败,将使用默认配置");
        }
    }
    
    /**
     * 加载小队配置
     * 使用Starsector API的loadCSV方法,参考Moci_GachaManager的实现
     * @return 是否加载成功
     */
    private static boolean loadSquadConfigs() {
        Logger log = Global.getLogger(Moci_CombatSquadConfigLoader.class);
        
        try {
            JSONArray csv = Global.getSettings().loadCSV(SQUAD_CONFIG_PATH);
            int loadedCount = 0;
            int skippedCount = 0;
            
            for (int i = 0; i < csv.length(); i++) {
                JSONObject row = csv.getJSONObject(i);
                
                // 跳过注释行
                String leaderHullId = row.optString("leader_hull_id", "");
                if (leaderHullId.isEmpty() || leaderHullId.startsWith("#")) {
                    continue;
                }
                
                try {
                    // 解析僚机1配置
                    SquadMemberConfig wingman1 = parseWingmanConfig(row, 1);
                    // 解析僚机2配置
                    SquadMemberConfig wingman2 = parseWingmanConfig(row, 2);
                    
                    if (wingman1 != null && wingman2 != null) {
                        List<SquadMemberConfig> squad = new ArrayList<>();
                        squad.add(wingman1);
                        squad.add(wingman2);
                        squadConfigs.put(leaderHullId, squad);
                        loadedCount++;
                    } else {
                        skippedCount++;
                        log.warn("小队配置不完整,跳过: " + leaderHullId);
                    }
                } catch (Exception e) {
                    skippedCount++;
                    log.warn("解析小队配置失败,跳过: " + leaderHullId + ", 错误: " + e.getMessage());
                }
            }
            
            log.info("小队配置加载完成: 成功" + loadedCount + "条, 跳过" + skippedCount + "条");
            return loadedCount > 0;
            
        } catch (Exception e) {
            log.warn("无法加载小队配置文件: " + SQUAD_CONFIG_PATH + ", 将使用默认配置. 错误: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 从CSV行解析僚机配置
     * @param row CSV行对象
     * @param wingmanIndex 僚机索引(1或2)
     * @return 僚机配置,如果解析失败则返回null
     */
    private static SquadMemberConfig parseWingmanConfig(JSONObject row, int wingmanIndex) {
        String prefix = "wingman" + wingmanIndex + "_";
        
        String variantId = row.optString(prefix + "variant_id", "");
        String displayName = row.optString(prefix + "name", "");
        int level = row.optInt(prefix + "level", 3);
        String skillsStr = row.optString(prefix + "skills", "");
        String personality = row.optString(prefix + "personality", "aggressive");
        
        if (variantId.isEmpty() || displayName.isEmpty()) {
            return null;
        }
        
        // 解析技能列表(使用分号分隔)
        String[] skills = skillsStr.split(";");
        
        return new SquadMemberConfig(variantId, displayName, 1, level, skills, personality);
    }
    
    /**
     * 加载允许的制造商列表
     * @return 是否加载成功
     */
    private static boolean loadAllowedManufacturers() {
        Logger log = Global.getLogger(Moci_CombatSquadConfigLoader.class);
        
        try {
            JSONArray csv = Global.getSettings().loadCSV(MANUFACTURER_CONFIG_PATH);
            int loadedCount = 0;
            
            for (int i = 0; i < csv.length(); i++) {
                JSONObject row = csv.getJSONObject(i);
                String manufacturer = row.optString("manufacturer", "");
                
                // 跳过空行和注释行
                if (manufacturer.isEmpty() || manufacturer.startsWith("#")) {
                    continue;
                }
                
                allowedManufacturers.add(manufacturer);
                loadedCount++;
            }
            
            log.info("制造商配置加载完成: " + loadedCount + "个制造商");
            return loadedCount > 0;
            
        } catch (Exception e) {
            log.warn("无法加载制造商配置文件: " + MANUFACTURER_CONFIG_PATH + ", 将使用默认配置. 错误: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取指定队长机的小队配置
     * @param leaderHullId 队长机Hull ID
     * @return 小队配置列表,如果不存在则返回null
     */
    public static List<SquadMemberConfig> getSquadConfig(String leaderHullId) {
        return squadConfigs.get(leaderHullId);
    }
    
    /**
     * 检查制造商是否被允许
     * @param manufacturer 制造商名称
     * @return 是否允许
     */
    public static boolean isManufacturerAllowed(String manufacturer) {
        if (allowedManufacturers.isEmpty()) {
            // 如果没有加载配置,返回false(使用默认逻辑)
            return false;
        }
        return allowedManufacturers.contains(manufacturer);
    }
    
    /**
     * 获取所有已加载的小队配置
     * @return 小队配置Map
     */
    public static Map<String, List<SquadMemberConfig>> getAllSquadConfigs() {
        return new HashMap<>(squadConfigs);
    }
    
    /**
     * 获取所有允许的制造商
     * @return 制造商列表
     */
    public static List<String> getAllowedManufacturers() {
        return new ArrayList<>(allowedManufacturers);
    }
    
    /**
     * 加载呼号列表
     * @return 是否加载成功
     */
    private static boolean loadCallsigns() {
        Logger log = Global.getLogger(Moci_CombatSquadConfigLoader.class);
        
        try {
            JSONArray csv = Global.getSettings().loadCSV(CALLSIGN_CONFIG_PATH);
            int loadedCount = 0;
            
            for (int i = 0; i < csv.length(); i++) {
                JSONObject row = csv.getJSONObject(i);
                String callsign = row.optString("callsign", "");
                
                // 跳过空行和注释行
                if (callsign.isEmpty() || callsign.startsWith("#")) {
                    continue;
                }
                
                callsigns.add(callsign);
                loadedCount++;
            }
            
            log.info("呼号配置加载完成: " + loadedCount + "个呼号");
            return loadedCount > 0;
            
        } catch (Exception e) {
            log.warn("无法加载呼号配置文件: " + CALLSIGN_CONFIG_PATH + ", 将使用默认配置. 错误: " + e.getMessage());
            return false;
        }
    }

    private static boolean loadAiCallsigns() {
        return loadSimpleStringList(AI_CALLSIGN_CONFIG_PATH, "callsign", aiCallsigns, "AI呼号");
    }

    private static boolean loadAiPilotNames() {
        return loadSimpleStringList(AI_PILOT_NAME_CONFIG_PATH, "name", aiPilotNames, "AI机师名称");
    }

    private static boolean loadSimpleStringList(String path, String fieldName, List<String> target, String label) {
        Logger log = Global.getLogger(Moci_CombatSquadConfigLoader.class);
        target.clear();

        try {
            JSONArray csv = Global.getSettings().loadCSV(path);
            int loadedCount = 0;

            for (int i = 0; i < csv.length(); i++) {
                JSONObject row = csv.getJSONObject(i);
                String value = row.optString(fieldName, "").trim();

                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }

                target.add(value);
                loadedCount++;
            }

            log.info(label + "配置加载完成: " + loadedCount + "条");
            return loadedCount > 0;

        } catch (Exception e) {
            log.warn("无法加载" + label + "配置文件: " + path + ", 将使用默认配置. 错误: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取随机呼号
     * @return 随机呼号,如果没有加载配置则返回null
     */
    public static String getRandomCallsign() {
        if (callsigns.isEmpty()) {
            return null; // 返回null表示使用默认逻辑
        }
        
        int index = (int) (Math.random() * callsigns.size());
        return callsigns.get(index);
    }
    
    /**
     * 获取所有呼号
     * @return 呼号列表
     */
    public static List<String> getAllCallsigns() {
        return new ArrayList<>(callsigns);
    }

    public static String getAiCallsignByIndex(int index) {
        return getIndexedValue(aiCallsigns, index);
    }

    public static String getAiPilotNameByIndex(int index) {
        return getIndexedValue(aiPilotNames, index);
    }

    public static int getAiCallsignCount() {
        return aiCallsigns.size();
    }

    public static int getAiPilotNameCount() {
        return aiPilotNames.size();
    }

    private static String getIndexedValue(List<String> values, int index) {
        if (values.isEmpty() || index < 0) {
            return null;
        }
        return values.get(index % values.size());
    }

    /**
     * 加载 AI 指挥官僚机头像列表
     * @return 是否加载成功
     */
    private static boolean loadAIPortraits() {
        Logger log = Global.getLogger(Moci_CombatSquadConfigLoader.class);

        try {
            JSONArray csv = Global.getSettings().loadCSV(AI_PORTRAIT_CONFIG_PATH);
            int loadedCount = 0;

            for (int i = 0; i < csv.length(); i++) {
                JSONObject row = csv.getJSONObject(i);
                String portrait = row.optString("portrait", "").trim();

                if (portrait.isEmpty() || portrait.startsWith("#")) {
                    continue;
                }

                aiPortraits.add(portrait);
                loadedCount++;
            }

            log.info("AI头像配置加载完成: " + loadedCount + "个头像");
            return loadedCount > 0;

        } catch (Exception e) {
            log.warn("无法加载AI头像配置文件: " + AI_PORTRAIT_CONFIG_PATH + ", 将使用默认配置. 错误: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取随机 AI 僚机头像
     * @return 随机头像路径
     */
    public static String getRandomAIPortrait() {
        List<String> portraits = aiPortraits.isEmpty() ? DEFAULT_AI_PORTRAITS : aiPortraits;
        int index = (int) (Math.random() * portraits.size());
        return portraits.get(index);
    }

    /**
     * 获取所有 AI 僚机头像
     * @return 头像列表
     */
    public static List<String> getAllAIPortraits() {
        if (aiPortraits.isEmpty()) {
            return new ArrayList<>(DEFAULT_AI_PORTRAITS);
        }
        return new ArrayList<>(aiPortraits);
    }
    
    /**
     * 加载编队名称配置(前缀和后缀)
     * @return 是否加载成功
     */
    private static boolean loadSquadNames() {
        Logger log = Global.getLogger(Moci_CombatSquadConfigLoader.class);
        
        try {
            JSONArray csv = Global.getSettings().loadCSV(SQUAD_NAME_CONFIG_PATH);
            int prefixCount = 0;
            int suffixCount = 0;
            
            for (int i = 0; i < csv.length(); i++) {
                JSONObject row = csv.getJSONObject(i);
                String type = row.optString("type", "");
                String name = row.optString("name", "");
                
                // 跳过空行和注释行
                if (type.isEmpty() || name.isEmpty() || type.startsWith("#")) {
                    continue;
                }
                
                if ("prefix".equals(type)) {
                    squadPrefixes.add(name);
                    prefixCount++;
                } else if ("suffix".equals(type)) {
                    squadSuffixes.add(name);
                    suffixCount++;
                }
            }
            
            log.info("编队名称配置加载完成: " + prefixCount + "个前缀, " + suffixCount + "个后缀");
            return prefixCount > 0 && suffixCount > 0;
            
        } catch (Exception e) {
            log.warn("无法加载编队名称配置文件: " + SQUAD_NAME_CONFIG_PATH + ", 将使用默认配置. 错误: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取随机编队前缀
     * @return 随机前缀,如果没有加载配置则返回null
     */
    public static String getRandomSquadPrefix() {
        if (squadPrefixes.isEmpty()) {
            return null;
        }
        
        int index = (int) (Math.random() * squadPrefixes.size());
        return squadPrefixes.get(index);
    }
    
    /**
     * 获取随机编队后缀
     * @return 随机后缀,如果没有加载配置则返回null
     */
    public static String getRandomSquadSuffix() {
        if (squadSuffixes.isEmpty()) {
            return null;
        }
        
        int index = (int) (Math.random() * squadSuffixes.size());
        return squadSuffixes.get(index);
    }
    
    /**
     * 获取所有编队前缀
     * @return 前缀列表
     */
    public static List<String> getAllSquadPrefixes() {
        return new ArrayList<>(squadPrefixes);
    }
    
    /**
     * 获取所有编队后缀
     * @return 后缀列表
     */
    public static List<String> getAllSquadSuffixes() {
        return new ArrayList<>(squadSuffixes);
    }
    
    /**
     * 检查配置是否已加载
     * @return 是否已加载
     */
    public static boolean isConfigsLoaded() {
        return configsLoaded;
    }
}
