package data.hullmods.combatsquad;

import data.scripts.util.Moci_TextLoader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.campaign.events.OfficerManagerEvent;
import com.fs.starfarer.api.impl.campaign.ids.Voices;
import com.fs.starfarer.api.util.WeightedRandomPicker;

/**
 * Moci机师管理器 - 负责CombatSquad编队机师的生成、持久化和管理
 * 参考骤雨wingcom系统的设计思路，适配到CombatSquad系统
 * 
 * 主要功能：
 * 1. 个性化机师生成
 * 2. 双模式数据持久化（战役/战斗）
 * 3. 编队规模动态管理
 * 4. 机师特殊属性分配
 */
public class Moci_PilotManager {

    private static final String SHIPBOARD_AI_PERSON_ID_PREFIX = "moci_shipboard_ai_";
    private static final List<String> DEFAULT_AI_CALLSIGNS = Arrays.asList(
        Moci_TextLoader.getText("code_Moci_PilotManager", "t001", "拉姆达"), Moci_TextLoader.getText("code_Moci_PilotManager", "t002", "缪"), Moci_TextLoader.getText("code_Moci_PilotManager", "t003", "纽"), Moci_TextLoader.getText("code_Moci_PilotManager", "t004", "克西"), Moci_TextLoader.getText("code_Moci_PilotManager", "t005", "欧米克戎"), Moci_TextLoader.getText("code_Moci_PilotManager", "t006", "罗"), Moci_TextLoader.getText("code_Moci_PilotManager", "t007", "西格玛"), Moci_TextLoader.getText("code_Moci_PilotManager", "t008", "陶"),
        Moci_TextLoader.getText("code_Moci_PilotManager", "t009", "宇普西龙"), Moci_TextLoader.getText("code_Moci_PilotManager", "t010", "斐"), Moci_TextLoader.getText("code_Moci_PilotManager", "t011", "卡伊"), Moci_TextLoader.getText("code_Moci_PilotManager", "t012", "普赛")
    );
    private static final List<String> DEFAULT_AI_PILOT_NAMES = Arrays.asList(
        Moci_TextLoader.getText("code_Moci_PilotManager", "t013", "分析单元"), Moci_TextLoader.getText("code_Moci_PilotManager", "t014", "战术单元"), Moci_TextLoader.getText("code_Moci_PilotManager", "t015", "执行单元"), Moci_TextLoader.getText("code_Moci_PilotManager", "t016", "观测单元"), Moci_TextLoader.getText("code_Moci_PilotManager", "t017", "校准单元"), Moci_TextLoader.getText("code_Moci_PilotManager", "t018", "引导单元"),
        Moci_TextLoader.getText("code_Moci_PilotManager", "t019", "拦截单元"), Moci_TextLoader.getText("code_Moci_PilotManager", "t020", "应答单元"), Moci_TextLoader.getText("code_Moci_PilotManager", "t021", "仲裁单元"), Moci_TextLoader.getText("code_Moci_PilotManager", "t022", "巡检单元"), Moci_TextLoader.getText("code_Moci_PilotManager", "t023", "整流单元"), Moci_TextLoader.getText("code_Moci_PilotManager", "t024", "归档单元")
    );
    
    // 数据键值常量定义
    private static final String PILOT_KEY = "moci_combatSquad_pilot_";
    private static final String CALLSIGN_KEY = "moci_combatSquad_callsign_";
    private static final String EXPERIENCE_KEY = "moci_combatSquad_exp_";
    private static final String SQUAD_SIZE_KEY = "moci_combatSquad_squadSize_";
    private static final String SQUAD_NAME_KEY = "moci_combatSquad_squadName_";
    private static final String PILOT_TALENT_KEY = "moci_combatSquad_talent_";
    private static final String PILOT_ASSIGNED_KEY = "moci_combatSquad_assigned_";
    
    // 机师等级生成概率配置
    private static final Map<Integer, Float> LEVEL_PROBABILITIES = new HashMap<>();
    static {
        LEVEL_PROBABILITIES.put(0, 0.4f);  // 40% 新手机师
        LEVEL_PROBABILITIES.put(1, 0.35f); // 35% 有经验机师  
        LEVEL_PROBABILITIES.put(2, 0.20f); // 20% 资深机师
        LEVEL_PROBABILITIES.put(3, 0.05f); // 5%  精英机师
    }
    
    // 特殊天赋概率
    private static final float TALENT_PROBABILITY = 0.15f; // 15%概率获得特殊天赋
    
    // 声音类型权重选择器
    private static final WeightedRandomPicker<String> VOICE_PICKER = new WeightedRandomPicker<>();
    static {
        VOICE_PICKER.add(Voices.SOLDIER, 30);
        VOICE_PICKER.add(Voices.SPACER, 25);
        VOICE_PICKER.add(Voices.BUSINESS, 15);
        VOICE_PICKER.add(Voices.OFFICIAL, 10);
        VOICE_PICKER.add(Voices.SCIENTIST, 10);
        VOICE_PICKER.add(Voices.FAITHFUL, 5);
        VOICE_PICKER.add(Voices.VILLAIN, 5);
    }
    
    /**
     * 为指挥官创建完整的机师编队
     * @param commander 编队指挥官
     * @param ship 母舰
     * @param squadSize 编队规模
     * @param persistent 是否持久化存储
     */
    public static void createSquadPilots(PersonAPI commander, ShipAPI ship, int squadSize, boolean persistent) {
        if (commander == null || squadSize <= 0) return;
        
        String commanderId = commander.getId();
        int currentSize = getCurrentSquadSize(commanderId, persistent);
        
        // 如果编队规模没有变化，跳过创建
        if (currentSize == squadSize) {
            return;
        }
        
        // 创建编队名称（如果不存在）
        if (!hasSquadName(commanderId, persistent)) {
            createSquadName(commanderId, persistent);
        }
        
        // 为每个编队成员创建机师
        for (int i = 0; i < squadSize; i++) {
            PersonAPI existingPilot = getPilot(commanderId, i, persistent);
            if (existingPilot == null) {
                createSinglePilot(commander, i, persistent);
            }
        }
        
        // 更新编队规模记录
        setSquadSize(commanderId, squadSize, persistent);
        
    }
    
    /**
     * 创建单个机师
     * @param commander 编队指挥官
     * @param index 机师索引
     * @param persistent 是否持久化
     */
    private static void createSinglePilot(PersonAPI commander, int index, boolean persistent) {
        String commanderId = commander.getId();
        
        // 生成机师等级（考虑指挥官等级影响）
        int pilotLevel = generatePilotLevel(commander.getStats().getLevel(), persistent);
        
        // 创建机师对象，使用完全随机的技能偏好
        PersonAPI pilot = OfficerManagerEvent.createOfficer(
            commander.getFaction(), pilotLevel, 
            OfficerManagerEvent.SkillPickPreference.ANY, // 随机技能偏好
            false, // 不使用自动技能选择
            null,  // 无技能覆盖
            true,  // 使用核心技能列表
            false, // 不允许精英技能
            0,     // 精英技能数量为0
            new Random() // 使用随机种子
        );
        
        // 分配声音类型
        pilot.setVoice(VOICE_PICKER.pick());

        if (isShipboardAICommander(commander)) {
            pilot.setPortraitSprite(Moci_CombatSquadConfigLoader.getRandomAIPortrait());
        }
        
        // AI 指挥官的僚机走单独的呼号/姓名序列，普通军官保持原有随机逻辑
        String callsign = generateCallsign(commander, index);
        applyPilotNameSequence(pilot, commander, index);
        
        // 初始化经验值 - 所有机师从0经验开始，公平培养
        float initialExp = 0f;
        
        // 分配特殊天赋
        if (Math.random() < TALENT_PROBABILITY) {
            assignSpecialTalent(pilot);
        }
        
        // 存储机师数据
        savePilotData(commanderId, index, pilot, callsign, initialExp, persistent);
        
    }
    
    /**
     * 生成机师等级，所有机师都从1级开始
     */
    private static int generatePilotLevel(int commanderLevel, boolean persistent) {
        // 所有机师都从1级开始，实现公平的培养体验
        return 1;
    }
    
    /**
     * 生成机师呼号
     * 优先从配置文件加载,如果配置不存在则使用默认列表
     */
    private static String generateCallsign(PersonAPI commander, int index) {
        if (isShipboardAICommander(commander)) {
            return generateAiCallsign(index);
        }

        // 尝试从配置加载器获取呼号
        String callsign = Moci_CombatSquadConfigLoader.getRandomCallsign();
        
        if (callsign != null) {
            // 从配置文件获取成功
            Random random = new Random();
            // 有30%概率添加数字后缀
            if (random.nextFloat() < 0.3f) {
                int number = random.nextInt(99) + 1;
                callsign += "-" + number;
            }
            return callsign;
        }
        
        // 配置文件不存在,使用默认列表(向后兼容)
        List<String> callsigns = Arrays.asList(
            Moci_TextLoader.getText("code_Moci_PilotManager", "t025", "阿尔法"), Moci_TextLoader.getText("code_Moci_PilotManager", "t026", "贝塔"), Moci_TextLoader.getText("code_Moci_PilotManager", "t027", "伽马"), Moci_TextLoader.getText("code_Moci_PilotManager", "t028", "德尔塔"), Moci_TextLoader.getText("code_Moci_PilotManager", "t029", "艾普西隆"), Moci_TextLoader.getText("code_Moci_PilotManager", "t030", "泽塔"), Moci_TextLoader.getText("code_Moci_PilotManager", "t031", "艾塔"), Moci_TextLoader.getText("code_Moci_PilotManager", "t032", "西塔"),
            Moci_TextLoader.getText("code_Moci_PilotManager", "t033", "猎鹰"), Moci_TextLoader.getText("code_Moci_PilotManager", "t034", "老鹰"), Moci_TextLoader.getText("code_Moci_PilotManager", "t035", "猎豹"), Moci_TextLoader.getText("code_Moci_PilotManager", "t036", "黑鹰"), Moci_TextLoader.getText("code_Moci_PilotManager", "t037", "金雕"), Moci_TextLoader.getText("code_Moci_PilotManager", "t038", "银狼"), Moci_TextLoader.getText("code_Moci_PilotManager", "t039", "火狐"), Moci_TextLoader.getText("code_Moci_PilotManager", "t040", "寒鸦"),
            Moci_TextLoader.getText("code_Moci_PilotManager", "t041", "幽灵"), Moci_TextLoader.getText("code_Moci_PilotManager", "t042", "子弹"), Moci_TextLoader.getText("code_Moci_PilotManager", "t043", "闪电"), Moci_TextLoader.getText("code_Moci_PilotManager", "t044", "雷霆"), Moci_TextLoader.getText("code_Moci_PilotManager", "t045", "烈火"), Moci_TextLoader.getText("code_Moci_PilotManager", "t046", "寒冰"), Moci_TextLoader.getText("code_Moci_PilotManager", "t047", "钢刃"), Moci_TextLoader.getText("code_Moci_PilotManager", "t048", "利剑")
        );
        
        Random random = new Random();
        String baseName = callsigns.get(random.nextInt(callsigns.size()));
        
        // 有30%概率添加数字后缀
        if (random.nextFloat() < 0.3f) {
            int number = random.nextInt(99) + 1;
            baseName += "-" + number;
        }
        
        return baseName;
    }

    private static String generateAiCallsign(int index) {
        String callsign = Moci_CombatSquadConfigLoader.getAiCallsignByIndex(index);
        int sourceSize = Moci_CombatSquadConfigLoader.getAiCallsignCount();
        if (callsign == null) {
            sourceSize = DEFAULT_AI_CALLSIGNS.size();
            callsign = DEFAULT_AI_CALLSIGNS.get(index % sourceSize);
        }

        if (sourceSize > 0 && index >= sourceSize) {
            int sequence = index / sourceSize + 1;
            return callsign + "-" + sequence;
        }
        return callsign;
    }

    private static void applyPilotNameSequence(PersonAPI pilot, PersonAPI commander, int index) {
        if (pilot == null || !isShipboardAICommander(commander)) {
            return;
        }

        String name = Moci_CombatSquadConfigLoader.getAiPilotNameByIndex(index);
        int sourceSize = Moci_CombatSquadConfigLoader.getAiPilotNameCount();
        if (name == null) {
            sourceSize = DEFAULT_AI_PILOT_NAMES.size();
            name = DEFAULT_AI_PILOT_NAMES.get(index % sourceSize);
        }

        if (sourceSize > 0 && index >= sourceSize) {
            int cycle = index / sourceSize + 1;
            name += " " + toRomanNumeral(Math.min(cycle, 10));
        }

        pilot.setName(new FullName(name, "", FullName.Gender.ANY));
    }

    /**
     * 判断指挥官是否为舰载AI
     */
    private static boolean isShipboardAICommander(PersonAPI commander) {
        if (commander == null) {
            return false;
        }
        String personId = commander.getId();
        return personId != null && personId.startsWith(SHIPBOARD_AI_PERSON_ID_PREFIX);
    }
    
    /**
     * 分配特殊天赋
     */
    private static void assignSpecialTalent(PersonAPI pilot) {
        List<String> talents = Arrays.asList(
            "moci_ace_pilot",        // 王牌机师 - 全属性小幅提升
            "moci_sharpshooter",     // 神射手 - 武器精度和伤害提升
            "moci_engineer",         // 工程师 - 系统冷却和维修提升
            "moci_tactician",        // 战术家 - 机动性和感知提升
            "moci_survivor"          // 幸存者 - 防御和回复能力提升
        );
        
        String talent = talents.get(new Random().nextInt(talents.size()));
        pilot.addTag("moci_special_talent");
        pilot.addTag(talent);
        
    }
    
    /**
     * 将数字转换为罗马数字（1-10）
     * @param num 数字（1-10）
     * @return 罗马数字字符串
     */
    private static String toRomanNumeral(int num) {
        switch (num) {
            case 1: return "I";
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            case 5: return "V";
            case 6: return "VI";
            case 7: return "VII";
            case 8: return "VIII";
            case 9: return "IX";
            case 10: return "X";
            default: return "I";
        }
    }
    
    /**
     * 创建编队名称
     */
    private static void createSquadName(String commanderId, boolean persistent) {
        Random random = new Random();
        
        // 尝试从配置加载器获取前缀和后缀
        String prefix = Moci_CombatSquadConfigLoader.getRandomSquadPrefix();
        String suffix = Moci_CombatSquadConfigLoader.getRandomSquadSuffix();
        
        // 如果配置不存在，使用默认列表
        if (prefix == null) {
            List<String> defaultPrefixes = Arrays.asList(
                Moci_TextLoader.getText("code_Moci_PilotManager", "t044", "雷霆"), Moci_TextLoader.getText("code_Moci_PilotManager", "t043", "闪电"), Moci_TextLoader.getText("code_Moci_PilotManager", "t045", "烈火"), Moci_TextLoader.getText("code_Moci_PilotManager", "t046", "寒冰"), Moci_TextLoader.getText("code_Moci_PilotManager", "t049", "钢铁"), Moci_TextLoader.getText("code_Moci_PilotManager", "t048", "利剑"), Moci_TextLoader.getText("code_Moci_PilotManager", "t033", "猎鹰"), Moci_TextLoader.getText("code_Moci_PilotManager", "t050", "雄鹰"),
                Moci_TextLoader.getText("code_Moci_PilotManager", "t051", "黑豹"), Moci_TextLoader.getText("code_Moci_PilotManager", "t052", "白狼"), Moci_TextLoader.getText("code_Moci_PilotManager", "t053", "红龙"), Moci_TextLoader.getText("code_Moci_PilotManager", "t054", "青鸟"), Moci_TextLoader.getText("code_Moci_PilotManager", "t055", "金翼"), Moci_TextLoader.getText("code_Moci_PilotManager", "t056", "银鳞"), Moci_TextLoader.getText("code_Moci_PilotManager", "t057", "紫电"), Moci_TextLoader.getText("code_Moci_PilotManager", "t058", "蓝焰")
            );
            prefix = defaultPrefixes.get(random.nextInt(defaultPrefixes.size()));
        }
        
        if (suffix == null) {
            List<String> defaultSuffixes = Arrays.asList(
                Moci_TextLoader.getText("code_Moci_PilotManager", "t059", "编队"), Moci_TextLoader.getText("code_Moci_PilotManager", "t060", "小队"), Moci_TextLoader.getText("code_Moci_PilotManager", "t061", "中队"), Moci_TextLoader.getText("code_Moci_PilotManager", "t062", "战队"), Moci_TextLoader.getText("code_Moci_PilotManager", "t063", "部队"), Moci_TextLoader.getText("code_Moci_PilotManager", "t064", "分队"), Moci_TextLoader.getText("code_Moci_PilotManager", "t065", "机群"), Moci_TextLoader.getText("code_Moci_PilotManager", "t066", "联队")
            );
            suffix = defaultSuffixes.get(random.nextInt(defaultSuffixes.size()));
        }
        
        // 生成两个罗马数字（I-X）
        int num1 = random.nextInt(10) + 1;  // 1-10
        int num2 = random.nextInt(10) + 1;  // 1-10
        String roman1 = toRomanNumeral(num1);
        String roman2 = toRomanNumeral(num2);
        
        // 格式: [罗马数字].[罗马数字][前缀][后缀]
        // 例如: V.IV雷霆编队
        String squadName = Moci_TextLoader.getText("code_Moci_PilotManager", "squad_name_format", "{roman1}.{roman2}{prefix}{suffix}")
                .replace("{roman1}", roman1).replace("{roman2}", roman2)
                .replace("{prefix}", prefix.trim()).replace("{suffix}", suffix.trim());
        
        if (persistent) {
            Global.getSector().getPersistentData().put(SQUAD_NAME_KEY + commanderId, squadName);
        } else {
            Global.getCombatEngine().getCustomData().put(SQUAD_NAME_KEY + commanderId, squadName);
        }
        
    }
    
    /**
     * 保存机师数据到持久化存储
     */
    public static void savePilotData(String commanderId, int index, PersonAPI pilot, 
                                    String callsign, float experience, boolean persistent) {
        if (persistent) {
            // 战役模式 - 存储到Sector持久化数据
            Global.getSector().getPersistentData().put(PILOT_KEY + index + "_" + commanderId, pilot);
            Global.getSector().getPersistentData().put(CALLSIGN_KEY + index + "_" + commanderId, callsign);
            Global.getSector().getPersistentData().put(EXPERIENCE_KEY + index + "_" + commanderId, experience);
        } else {
            // 战斗模式 - 存储到Combat引擎临时数据
            Global.getCombatEngine().getCustomData().put(PILOT_KEY + index + "_" + commanderId, pilot);
            Global.getCombatEngine().getCustomData().put(CALLSIGN_KEY + index + "_" + commanderId, callsign);
            Global.getCombatEngine().getCustomData().put(EXPERIENCE_KEY + index + "_" + commanderId, experience);
        }
    }
    
    // ======================== 数据访问方法 ========================
    
    /**
     * 获取机师对象
     */
    public static PersonAPI getPilot(String commanderId, int index, boolean persistent) {
        String key = PILOT_KEY + index + "_" + commanderId;
        if (persistent) {
            return (PersonAPI) Global.getSector().getPersistentData().get(key);
        } else {
            if (Global.getCombatEngine() != null) {
                return (PersonAPI) Global.getCombatEngine().getCustomData().get(key);
            }
            return null;
        }
    }
    
    /**
     * 获取机师呼号
     */
    public static String getCallsign(String commanderId, int index, boolean persistent) {
        String key = CALLSIGN_KEY + index + "_" + commanderId;
        if (persistent) {
            return (String) Global.getSector().getPersistentData().get(key);
        } else {
            if (Global.getCombatEngine() != null) {
                return (String) Global.getCombatEngine().getCustomData().get(key);
            }
            return null;
        }
    }
    
    /**
     * 获取机师经验值
     */
    public static float getExperience(String commanderId, int index, boolean persistent) {
        String key = EXPERIENCE_KEY + index + "_" + commanderId;
        Object exp;
        if (persistent) {
            exp = Global.getSector().getPersistentData().get(key);
        } else {
            if (Global.getCombatEngine() != null) {
                exp = Global.getCombatEngine().getCustomData().get(key);
            } else {
                return 0f;
            }
        }
        return exp instanceof Float ? (Float) exp : 0f;
    }
    
    /**
     * 获取当前编队规模
     */
    public static int getCurrentSquadSize(String commanderId, boolean persistent) {
        String key = SQUAD_SIZE_KEY + commanderId;
        Object size;
        if (persistent) {
            size = Global.getSector().getPersistentData().get(key);
        } else {
            if (Global.getCombatEngine() != null) {
                size = Global.getCombatEngine().getCustomData().get(key);
            } else {
                return 0;
            }
        }
        return size instanceof Integer ? (Integer) size : 0;
    }
    
    /**
     * 获取编队规模 - Intel界面使用的简化版本
     */
    public static int getSquadSize(String commanderId) {
        return getCurrentSquadSize(commanderId, true);  // 默认使用持久化模式
    }
    
    /**
     * 设置编队规模
     */
    public static void setSquadSize(String commanderId, int size, boolean persistent) {
        String key = SQUAD_SIZE_KEY + commanderId;
        if (persistent) {
            Global.getSector().getPersistentData().put(key, size);
        } else {
            if (Global.getCombatEngine() != null) {
                Global.getCombatEngine().getCustomData().put(key, size);
            }
        }
    }
    
    /**
     * 获取编队名称
     */
    public static String getSquadName(String commanderId, boolean persistent) {
        String key = SQUAD_NAME_KEY + commanderId;
        if (persistent) {
            return (String) Global.getSector().getPersistentData().get(key);
        } else {
            if (Global.getCombatEngine() != null) {
                return (String) Global.getCombatEngine().getCustomData().get(key);
            }
            return null;
        }
    }
    
    /**
     * 设置编队名称 - Intel界面编辑功能使用
     */
    public static void setSquadName(String commanderId, String squadName) {
        String key = SQUAD_NAME_KEY + commanderId;
        Global.getSector().getPersistentData().put(key, squadName);
        
    }
    
    /**
     * 检查是否有编队名称
     */
    public static boolean hasSquadName(String commanderId, boolean persistent) {
        return getSquadName(commanderId, persistent) != null;
    }
    
    /**
     * 检查机师是否已分配
     */
    public static boolean isPilotAssigned(String commanderId, int index, boolean persistent) {
        String key = PILOT_ASSIGNED_KEY + index + "_" + commanderId;
        Object assigned;
        if (persistent) {
            assigned = Global.getSector().getPersistentData().get(key);
        } else {
            if (Global.getCombatEngine() != null) {
                assigned = Global.getCombatEngine().getCustomData().get(key);
            } else {
                return false;
            }
        }
        return assigned instanceof Boolean ? (Boolean) assigned : false;
    }
    
    /**
     * 设置机师分配状态
     */
    public static void setPilotAssigned(String commanderId, int index, boolean assigned, boolean persistent) {
        String key = PILOT_ASSIGNED_KEY + index + "_" + commanderId;
        if (persistent) {
            Global.getSector().getPersistentData().put(key, assigned);
        } else {
            if (Global.getCombatEngine() != null) {
                Global.getCombatEngine().getCustomData().put(key, assigned);
            }
        }
    }
    
    /**
     * 清理编队数据（用于编队解散或指挥官更换）
     */
    public static void cleanupSquadData(String commanderId, boolean persistent) {
        int squadSize = getCurrentSquadSize(commanderId, persistent);
        
        for (int i = 0; i < squadSize; i++) {
            if (persistent) {
                Global.getSector().getPersistentData().remove(PILOT_KEY + i + "_" + commanderId);
                Global.getSector().getPersistentData().remove(CALLSIGN_KEY + i + "_" + commanderId);
                Global.getSector().getPersistentData().remove(EXPERIENCE_KEY + i + "_" + commanderId);
                Global.getSector().getPersistentData().remove(PILOT_ASSIGNED_KEY + i + "_" + commanderId);
            } else {
                if (Global.getCombatEngine() != null) {
                    Global.getCombatEngine().getCustomData().remove(PILOT_KEY + i + "_" + commanderId);
                    Global.getCombatEngine().getCustomData().remove(CALLSIGN_KEY + i + "_" + commanderId);
                    Global.getCombatEngine().getCustomData().remove(EXPERIENCE_KEY + i + "_" + commanderId);
                    Global.getCombatEngine().getCustomData().remove(PILOT_ASSIGNED_KEY + i + "_" + commanderId);
                }
            }
        }
        
        // 清理编队级别数据
        if (persistent) {
            Global.getSector().getPersistentData().remove(SQUAD_SIZE_KEY + commanderId);
            Global.getSector().getPersistentData().remove(SQUAD_NAME_KEY + commanderId);
        } else {
            if (Global.getCombatEngine() != null) {
                Global.getCombatEngine().getCustomData().remove(SQUAD_SIZE_KEY + commanderId);
                Global.getCombatEngine().getCustomData().remove(SQUAD_NAME_KEY + commanderId);
            }
        }
        
    }
} 
