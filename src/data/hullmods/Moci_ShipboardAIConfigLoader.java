package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 舰载AI配置加载器。
 *
 * 从CSV读取AI类型定义；"船插 -> AI类型"映射由代码内置。
 */
public class Moci_ShipboardAIConfigLoader {

    private static final Logger LOG = Global.getLogger(Moci_ShipboardAIConfigLoader.class);

    private static final String AI_TYPES_PATH = "data/config/Moci_ShipboardAI/Moci_ShipboardAI_Types.csv";
    private static final String DEFAULT_AI_RANK_ID = "Moci_AI_01";
    private static final Map<String, AITypeDefinition> AI_TYPES = new LinkedHashMap<>();
    private static final Map<String, String> HULLMOD_TO_AI_TYPE = new LinkedHashMap<>();

    private static boolean loaded = false;

    public static class AITypeDefinition {
        public final String aiId;
        public final String name;
        public final String portrait;
        public final boolean canRename;
        public final int levelCap;
        public final String personality;
        public final String rankId;
        public final List<String> skillPool;
        public final List<String> defaultSkills;
        public final int maxSkills;
        public final List<String> defaultAuxEffects;
        public final List<String> auxSkillPool;
        public final boolean isEvolved;
        public final boolean hasEvolutionRoute;
        public final int evolutionLevel;
        public final boolean inheritLevel;
        public final boolean inheritSkills;
        public final boolean inheritAux;
        public final String evolvedAiId;
        public final List<String> evolvedAiIds;

        public AITypeDefinition(
                String aiId,
                String name,
                String portrait,
                boolean canRename,
                int levelCap,
                String personality,
                String rankId,
                List<String> skillPool,
                List<String> defaultSkills,
                int maxSkills,
                List<String> defaultAuxEffects,
                List<String> auxSkillPool,
                boolean isEvolved,
                boolean hasEvolutionRoute,
                int evolutionLevel,
                boolean inheritLevel,
                boolean inheritSkills,
                boolean inheritAux,
                List<String> evolvedAiIds
        ) {
            this.aiId = aiId;
            this.name = name;
            this.portrait = portrait;
            this.canRename = canRename;
            this.levelCap = levelCap;
            this.personality = personality;
            this.rankId = rankId;
            this.skillPool = skillPool;
            this.defaultSkills = defaultSkills;
            this.maxSkills = maxSkills;
            this.defaultAuxEffects = defaultAuxEffects;
            this.auxSkillPool = auxSkillPool;
            this.isEvolved = isEvolved;
            this.hasEvolutionRoute = hasEvolutionRoute;
            this.evolutionLevel = evolutionLevel;
            this.inheritLevel = inheritLevel;
            this.inheritSkills = inheritSkills;
            this.inheritAux = inheritAux;
            this.evolvedAiIds = evolvedAiIds == null ? new ArrayList<String>() : evolvedAiIds;
            this.evolvedAiId = this.evolvedAiIds.isEmpty() ? "" : this.evolvedAiIds.get(0);
        }
    }

    public static synchronized void loadAllConfigs() {
        AI_TYPES.clear();
        HULLMOD_TO_AI_TYPE.clear();

        boolean typeLoaded = loadAITypeConfig();
        loadBuiltInHullmodMap();

        if (AI_TYPES.isEmpty()) {
            loadBuiltInFallbackTypes();
        }

        loaded = true;
        LOG.info("舰载AI配置加载完成: aiTypes=" + AI_TYPES.size()
                + ", hullmodMappings=" + HULLMOD_TO_AI_TYPE.size()
                + ", csvTypeLoaded=" + typeLoaded);
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static Map<String, AITypeDefinition> getAllAiTypes() {
        ensureLoaded();
        return new LinkedHashMap<>(AI_TYPES);
    }

    public static Map<String, String> getHullmodToAiTypeMap() {
        ensureLoaded();
        return new LinkedHashMap<>(HULLMOD_TO_AI_TYPE);
    }

    public static AITypeDefinition getAiType(String aiTypeId) {
        ensureLoaded();
        return AI_TYPES.get(Moci_ShipboardAISystemRegistry.getCanonicalAiTypeId(aiTypeId));
    }

    public static String getCanonicalAiTypeId(String aiTypeId) {
        ensureLoaded();
        return Moci_ShipboardAISystemRegistry.getCanonicalAiTypeId(aiTypeId);
    }

    public static String getMappedAiTypeByHullmod(String hullmodId) {
        ensureLoaded();
        return HULLMOD_TO_AI_TYPE.get(hullmodId);
    }

    public static String getDefaultAiRankId() {
        return DEFAULT_AI_RANK_ID;
    }

    public static String resolveAiTypeForMember(FleetMemberAPI member) {
        ensureLoaded();
        if (member == null || member.getVariant() == null) {
            return null;
        }
        java.util.Collection<String> mods = member.getVariant().getHullMods();
        if (mods == null || mods.isEmpty()) {
            return null;
        }

        for (Map.Entry<String, String> entry : HULLMOD_TO_AI_TYPE.entrySet()) {
            if (mods.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static String resolveRankForMember(FleetMemberAPI member) {
        ensureLoaded();
        String aiTypeId = resolveAiTypeForMember(member);
        if (aiTypeId == null || aiTypeId.trim().isEmpty()) {
            return DEFAULT_AI_RANK_ID;
        }
        AITypeDefinition type = getAiType(aiTypeId);
        if (type == null || type.rankId == null || type.rankId.trim().isEmpty()) {
            return DEFAULT_AI_RANK_ID;
        }
        return type.rankId.trim();
    }

    public static boolean hasShipboardAISystem(FleetMemberAPI member) {
        return resolveAiTypeForMember(member) != null;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            loadAllConfigs();
        }
    }

    private static boolean loadAITypeConfig() {
        try {
            JSONArray csv = Global.getSettings().loadCSV(AI_TYPES_PATH);
            int loadedCount = 0;
            for (int i = 0; i < csv.length(); i++) {
                JSONObject row = csv.getJSONObject(i);
                String aiId = row.optString("ai_id", "").trim();
                if (aiId.isEmpty() || aiId.startsWith("#")) {
                    continue;
                }

                AITypeDefinition def = parseAiTypeRow(row);
                if (def == null) {
                    continue;
                }

                AI_TYPES.put(def.aiId, def);
                loadedCount++;
            }

            validateEvolutionTargets();
            LOG.info("舰载AI类型CSV加载完成: " + loadedCount + " 条");
            return loadedCount > 0;
        } catch (Exception ex) {
            LOG.warn("加载舰载AI类型CSV失败: " + AI_TYPES_PATH + ", error=" + ex.getMessage());
            return false;
        }
    }

    private static AITypeDefinition parseAiTypeRow(JSONObject row) {
        try {
            String aiId = row.optString("ai_id", "").trim();
            String name = row.optString("name", aiId).trim();
            String portrait = row.optString("portrait", "graphics/portraits/ai_pilot/Moci_system99.png").trim();
            boolean canRename = parseBoolean(row.opt("can_rename"), false);
            int levelCap = clamp(row.optInt("level_cap", 7), 1, 20);
            String personality = normalizePersonality(row.optString("personality", Personalities.STEADY));
            String rankId = row.optString("rank", DEFAULT_AI_RANK_ID).trim();
            if (rankId.isEmpty()) {
                rankId = DEFAULT_AI_RANK_ID;
            }

            List<String> skillPool = filterValidSkills(splitSemicolon(row.optString("skill_pool", "")));
            List<String> defaultSkills = filterValidSkills(splitSemicolon(row.optString("default_skills", "")));
            int maxSkills = clamp(row.optInt("max_skills", 5), 1, 12);

            List<String> defaultAux = filterValidAuxEffects(splitSemicolon(row.optString("default_aux_effects", "")));
            List<String> auxPool = filterValidAuxEffects(splitSemicolon(row.optString("aux_skill_pool", "")));

            boolean isEvolved = parseBoolean(row.opt("is_evolved"), false);
            boolean hasEvolutionRoute = parseBoolean(row.opt("has_evolution_route"), false);
            int evolutionLevel = clamp(row.optInt("evolution_level", levelCap), 1, 20);
            boolean inheritLevel = parseBoolean(row.opt("inherit_level"), true);
            boolean inheritSkills = parseBoolean(row.opt("inherit_skills"), true);
            boolean inheritAux = parseBoolean(row.opt("inherit_aux"), true);
            List<String> evolvedAiIds = dedupe(splitSemicolon(row.optString("evolved_ai_id", "")));

            if (skillPool.isEmpty()) {
                skillPool = new ArrayList<>(getDefaultSkillPool());
            }
            if (auxPool.isEmpty()) {
                auxPool = new ArrayList<>(getDefaultAuxPool());
            }
            if (defaultSkills.isEmpty() && !skillPool.isEmpty()) {
                defaultSkills = Arrays.asList(skillPool.get(0));
            }
            if (defaultAux.isEmpty() && !auxPool.isEmpty()) {
                defaultAux = Arrays.asList(auxPool.get(0));
            }

            // 保证默认项在池中
            for (String skill : defaultSkills) {
                if (!skillPool.contains(skill)) {
                    skillPool.add(skill);
                }
            }
            for (String aux : defaultAux) {
                if (!auxPool.contains(aux)) {
                    auxPool.add(aux);
                }
            }

            return new AITypeDefinition(
                    aiId, name, portrait, canRename, levelCap, personality, rankId,
                    dedupe(skillPool), dedupe(defaultSkills), maxSkills,
                    dedupe(defaultAux), dedupe(auxPool),
                    isEvolved, hasEvolutionRoute, evolutionLevel,
                    inheritLevel, inheritSkills, inheritAux, evolvedAiIds
            );
        } catch (Exception ex) {
            LOG.warn("解析舰载AI类型行失败: " + ex.getMessage());
            return null;
        }
    }

    private static void validateEvolutionTargets() {
        for (AITypeDefinition def : AI_TYPES.values()) {
            if (!def.hasEvolutionRoute) {
                continue;
            }
            if (def.evolvedAiIds == null || def.evolvedAiIds.isEmpty()) {
                LOG.warn("舰载AI进化配置缺失目标ID: aiId=" + def.aiId);
                continue;
            }
            for (String targetId : def.evolvedAiIds) {
                if (!AI_TYPES.containsKey(targetId)) {
                    LOG.warn("舰载AI进化配置目标不存在: aiId=" + def.aiId + ", evolvedAiId=" + targetId);
                }
            }
        }
    }

    private static void loadBuiltInFallbackTypes() {
        LOG.warn("使用内置舰载AI类型保底配置");

        List<String> systemSkillPool = new ArrayList<>(Arrays.asList(
                com.fs.starfarer.api.impl.campaign.ids.Skills.TARGET_ANALYSIS,
                com.fs.starfarer.api.impl.campaign.ids.Skills.HELMSMANSHIP,
                com.fs.starfarer.api.impl.campaign.ids.Skills.COMBAT_ENDURANCE,
                com.fs.starfarer.api.impl.campaign.ids.Skills.IMPACT_MITIGATION,
                com.fs.starfarer.api.impl.campaign.ids.Skills.DAMAGE_CONTROL,
                com.fs.starfarer.api.impl.campaign.ids.Skills.FIELD_MODULATION,
                com.fs.starfarer.api.impl.campaign.ids.Skills.SYSTEMS_EXPERTISE
        ));

        AITypeDefinition system99 = new AITypeDefinition(
                "Moci_system99", "99系统", "graphics/portraits/ai_pilot/Moci_system99.png", false, 7,
                Personalities.RECKLESS, DEFAULT_AI_RANK_ID,
                systemSkillPool,
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.TARGET_ANALYSIS),
                5,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_MOBILITY),
                getDefaultAuxPool(),
                false, true, 7, true, true, true, Arrays.asList("Moci_Nine")
        );

        AITypeDefinition nine = new AITypeDefinition(
                "Moci_Nine", "ai奈因", "graphics/portraits/ai_pilot/Moci_Nine.png", false, 10,
                Personalities.AGGRESSIVE, DEFAULT_AI_RANK_ID,
                getDefaultSkillPool(),
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.TARGET_ANALYSIS),
                7,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_DAMAGE_CONTROL),
                getDefaultAuxPool(),
                true, false, 10, true, true, true, new ArrayList<String>()
        );

        AITypeDefinition haro = new AITypeDefinition(
                "Moci_haro", "哈罗AI", "graphics/portraits/Moci_haro_Base.png", true, 9,
                Personalities.STEADY, DEFAULT_AI_RANK_ID,
                getDefaultSkillPool(),
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.HELMSMANSHIP),
                6,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_ATTITUDE_CONTROL),
                getDefaultAuxPool(),
                false, false, 9, true, true, true, new ArrayList<String>()
        );

        AITypeDefinition alphaRandom = new AITypeDefinition(
                "Moci_ARP_AlphaRandom", "阿尔法舰载AI", "graphics/icons/cargo/ai_core_alpha.png", false, 9,
                Personalities.STEADY, DEFAULT_AI_RANK_ID,
                getDefaultSkillPool(),
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.TARGET_ANALYSIS),
                6,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_DAMAGE_MITIGATION),
                getDefaultAuxPool(),
                false, false, 9, true, true, true, new ArrayList<String>()
        );

        AITypeDefinition takao = new AITypeDefinition(
                "Moci_ARP_Takao", "高雄", "graphics/portraits/ai_pilot/Moci_ARP_Takao.png", false, 8,
                Personalities.AGGRESSIVE, DEFAULT_AI_RANK_ID,
                getDefaultSkillPool(),
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.BALLISTIC_MASTERY),
                6,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_TARGETING),
                getDefaultAuxPool(),
                false, false, 8, true, true, true, new ArrayList<String>()
        );

        AITypeDefinition kongou = new AITypeDefinition(
                "Moci_ARP_Kongou", "金刚", "graphics/portraits/ai_pilot/Moci_ARP_Kongou.png", false, 9,
                Personalities.AGGRESSIVE, DEFAULT_AI_RANK_ID,
                getDefaultSkillPool(),
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.BALLISTIC_MASTERY),
                6,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_TARGETING),
                getDefaultAuxPool(),
                false, false, 9, true, true, true, new ArrayList<String>()
        );

        AITypeDefinition haruna = new AITypeDefinition(
                "Moci_ARP_Haruna", "榛名", "graphics/portraits/ai_pilot/Moci_ARP_Haruna.png", false, 9,
                Personalities.CAUTIOUS, DEFAULT_AI_RANK_ID,
                getDefaultSkillPool(),
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.FIELD_MODULATION),
                6,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_SHIELD_MODULATION),
                getDefaultAuxPool(),
                false, false, 9, true, true, true, new ArrayList<String>()
        );

        AITypeDefinition omegaRandom = new AITypeDefinition(
                "Moci_ARP_OmegaRandom", "欧米伽舰载AI", "graphics/icons/cargo/ai_omega_core.png", false, 10,
                Personalities.AGGRESSIVE, DEFAULT_AI_RANK_ID,
                getDefaultSkillPool(),
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.TARGET_ANALYSIS),
                7,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_SYSTEM_OVERCLOCK),
                getDefaultAuxPool(),
                false, false, 10, true, true, true, new ArrayList<String>()
        );

        AITypeDefinition iona = new AITypeDefinition(
                "Moci_ARP_Iona", "伊欧娜", "graphics/portraits/ai_pilot/Moci_ARP_Iona.png", false, 10,
                Personalities.STEADY, DEFAULT_AI_RANK_ID,
                getDefaultSkillPool(),
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.TARGET_ANALYSIS),
                7,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_FLUX_STABILITY),
                getDefaultAuxPool(),
                false, false, 10, true, true, true, new ArrayList<String>()
        );

        AI_TYPES.put(system99.aiId, system99);
        AI_TYPES.put(nine.aiId, nine);
        AI_TYPES.put(haro.aiId, haro);
        AI_TYPES.put(alphaRandom.aiId, alphaRandom);
        AI_TYPES.put(takao.aiId, takao);
        AI_TYPES.put(kongou.aiId, kongou);
        AI_TYPES.put(haruna.aiId, haruna);
        AI_TYPES.put(omegaRandom.aiId, omegaRandom);
        AI_TYPES.put(iona.aiId, iona);
    }

    private static void loadBuiltInHullmodMap() {
        HULLMOD_TO_AI_TYPE.put(Moci_System99.HULLMOD_ID, Moci_System99.AI_TYPE_ID);
        HULLMOD_TO_AI_TYPE.put(Moci_HaroAISystem.HULLMOD_ID, Moci_HaroAISystem.AI_TYPE_ID);
        HULLMOD_TO_AI_TYPE.put(Moci_AlphaCoreAISystem.HULLMOD_ID, Moci_AlphaCoreAISystem.AI_TYPE_ID);
        HULLMOD_TO_AI_TYPE.put(Moci_OmegaCoreAISystem.HULLMOD_ID, Moci_OmegaCoreAISystem.AI_TYPE_ID);
    }

    private static List<String> getDefaultSkillPool() {
        return new ArrayList<>(Arrays.asList(
                com.fs.starfarer.api.impl.campaign.ids.Skills.COMBAT_ENDURANCE,
                com.fs.starfarer.api.impl.campaign.ids.Skills.HELMSMANSHIP,
                com.fs.starfarer.api.impl.campaign.ids.Skills.ENERGY_WEAPON_MASTERY,
                com.fs.starfarer.api.impl.campaign.ids.Skills.BALLISTIC_MASTERY,
                com.fs.starfarer.api.impl.campaign.ids.Skills.FIELD_MODULATION,
                com.fs.starfarer.api.impl.campaign.ids.Skills.TARGET_ANALYSIS,
                com.fs.starfarer.api.impl.campaign.ids.Skills.IMPACT_MITIGATION,
                com.fs.starfarer.api.impl.campaign.ids.Skills.DAMAGE_CONTROL,
                com.fs.starfarer.api.impl.campaign.ids.Skills.POINT_DEFENSE,
                com.fs.starfarer.api.impl.campaign.ids.Skills.MISSILE_SPECIALIZATION,
                com.fs.starfarer.api.impl.campaign.ids.Skills.SYSTEMS_EXPERTISE,
                com.fs.starfarer.api.impl.campaign.ids.Skills.COORDINATED_MANEUVERS
        ));
    }

    private static List<String> getDefaultAuxPool() {
        return new ArrayList<>(Arrays.asList(
                Moci_ShipboardAIAuxEffectRegistry.AUX_MOBILITY,
                Moci_ShipboardAIAuxEffectRegistry.AUX_FLUX_STABILITY,
                Moci_ShipboardAIAuxEffectRegistry.AUX_TARGETING,
                Moci_ShipboardAIAuxEffectRegistry.AUX_DAMAGE_MITIGATION,
                Moci_ShipboardAIAuxEffectRegistry.AUX_DAMAGE_CONTROL,
                Moci_ShipboardAIAuxEffectRegistry.AUX_SHIELD_MODULATION,
                Moci_ShipboardAIAuxEffectRegistry.AUX_ATTITUDE_CONTROL,
                Moci_ShipboardAIAuxEffectRegistry.AUX_SYSTEM_OVERCLOCK,
                Moci_ShipboardAIAuxEffectRegistry.AUX_PD_MATRIX,
                Moci_ShipboardAIAuxEffectRegistry.AUX_RAPID_CYCLE,
                Moci_ShipboardAIAuxEffectRegistry.AUX_RANGEFINDER,
                Moci_ShipboardAIAuxEffectRegistry.AUX_OVERLOAD_DAMPING,
                Moci_ShipboardAIAuxEffectRegistry.AUX_ZERO_FLUX_BOOST,
                Moci_ShipboardAIAuxEffectRegistry.AUX_HARD_FLUX_VENT,
                Moci_ShipboardAIAuxEffectRegistry.AUX_PROJECTILE_OPTIMIZATION,
                Moci_ShipboardAIAuxEffectRegistry.AUX_MISSILE_SUPPORT,
                Moci_ShipboardAIAuxEffectRegistry.AUX_SHIELD_GEOMETRY,
                Moci_ShipboardAIAuxEffectRegistry.AUX_ARMOR_PACKAGE,
                Moci_ShipboardAIAuxEffectRegistry.AUX_HANGAR_COORDINATION,
                Moci_ShipboardAIAuxEffectRegistry.AUX_VENT_EFFICIENCY
        ));
    }

    private static List<String> splitSemicolon(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }
        String[] parts = raw.split(";");
        for (String part : parts) {
            String trimmed = part == null ? "" : part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static List<String> filterValidSkills(List<String> skillIds) {
        List<String> result = new ArrayList<>();
        for (String skillId : skillIds) {
            try {
                if (Global.getSettings().getSkillSpec(skillId) != null) {
                    result.add(skillId);
                }
            } catch (Exception ex) {
                LOG.warn("非法技能ID已跳过: " + skillId);
            }
        }
        return result;
    }

    private static List<String> filterValidAuxEffects(List<String> auxIds) {
        List<String> result = new ArrayList<>();
        for (String auxId : auxIds) {
            if (Moci_ShipboardAIAuxEffectRegistry.isSupported(auxId)) {
                result.add(auxId);
            } else {
                LOG.warn("非法辅助词条ID已跳过: " + auxId);
            }
        }
        return result;
    }

    private static boolean parseBoolean(Object raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        String str = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (str.isEmpty()) {
            return defaultValue;
        }
        return "true".equals(str) || "1".equals(str) || "yes".equals(str);
    }

    private static String normalizePersonality(String personality) {
        if (personality == null || personality.trim().isEmpty()) {
            return Personalities.STEADY;
        }
        String p = personality.trim().toLowerCase(Locale.ROOT);
        if (Personalities.CAUTIOUS.equals(p) ||
                Personalities.TIMID.equals(p) ||
                Personalities.RECKLESS.equals(p) ||
                Personalities.AGGRESSIVE.equals(p) ||
                Personalities.STEADY.equals(p)) {
            return p;
        }
        return Personalities.STEADY;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static List<String> dedupe(List<String> input) {
        return new ArrayList<>(new LinkedHashSet<>(input));
    }
}
