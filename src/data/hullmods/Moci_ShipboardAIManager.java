package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import org.apache.log4j.Logger;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 舰载AI持久化管理器。
 */
public class Moci_ShipboardAIManager {

    private static final Logger LOG = Global.getLogger(Moci_ShipboardAIManager.class);

    private static final String DATA_MAP_KEY = "$moci_shipboard_ai_data_map";
    private static final Map<String, ShipboardAIData> RUNTIME_DATA_MAP = new LinkedHashMap<>();

    public static class ShipboardAIData implements Serializable {
        public String memberId;
        public String baseAiTypeId;
        public String currentAiTypeId;
        public PersonAPI aiPerson;
        public float experience;
        public boolean evolved;
        public String customName;
        public Set<String> learnedSkills = new LinkedHashSet<>();
        public Set<String> eliteSkills = new LinkedHashSet<>();
        public Set<String> learnedAuxSkills = new LinkedHashSet<>();
        public Set<String> defaultSkills = new LinkedHashSet<>();
        public Set<String> defaultAuxSkills = new LinkedHashSet<>();
    }

    public static class ExperienceGainResult {
        public final boolean applied;
        public final float gainedExp;
        public final int levelBefore;
        public final int levelAfter;

        public ExperienceGainResult(boolean applied, float gainedExp, int levelBefore, int levelAfter) {
            this.applied = applied;
            this.gainedExp = gainedExp;
            this.levelBefore = levelBefore;
            this.levelAfter = levelAfter;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ShipboardAIData> getDataMap() {
        if (Global.getSector() == null) {
            return RUNTIME_DATA_MAP;
        }
        Object obj = Global.getSector().getPersistentData().get(DATA_MAP_KEY);
        if (obj instanceof Map) {
            return (Map<String, ShipboardAIData>) obj;
        }
        Map<String, ShipboardAIData> created = new LinkedHashMap<>();
        Global.getSector().getPersistentData().put(DATA_MAP_KEY, created);
        return created;
    }

    public static List<FleetMemberAPI> getPlayerFleetMembersWithAI() {
        List<FleetMemberAPI> result = new ArrayList<>();
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        if (fleet == null || fleet.getFleetData() == null) {
            return result;
        }
        for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
            if (Moci_ShipboardAIConfigLoader.hasShipboardAISystem(member)) {
                result.add(member);
            }
        }
        return result;
    }

    public static ShipboardAIData getDataByMember(FleetMemberAPI member) {
        if (member == null) {
            return null;
        }
        return getDataMap().get(member.getId());
    }

    public static ShipboardAIData getOrCreateData(FleetMemberAPI member) {
        if (member == null || !Moci_ShipboardAIConfigLoader.hasShipboardAISystem(member)) {
            return null;
        }
        Map<String, ShipboardAIData> dataMap = getDataMap();
        String memberId = member.getId();
        ShipboardAIData data = dataMap.get(memberId);
        if (data != null) {
            canonicalizeAiTypeIds(data);
            ensureDataIntegrity(member, data);
            return data;
        }

        String aiTypeId = Moci_ShipboardAIConfigLoader.resolveAiTypeForMember(member);
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = Moci_ShipboardAIConfigLoader.getAiType(aiTypeId);
        if (type == null) {
            return null;
        }

        data = new ShipboardAIData();
        data.memberId = memberId;
        data.baseAiTypeId = aiTypeId;
        data.currentAiTypeId = aiTypeId;
        data.experience = getExperienceForLevel(1);
        data.evolved = false;

        // 默认 1 普通技能 + 1 辅助词条
        String initialSkill = pickFirst(type.defaultSkills, type.skillPool);
        if (initialSkill != null) {
            data.learnedSkills.add(initialSkill);
            data.defaultSkills.add(initialSkill);
        }
        String initialAux = pickFirst(type.defaultAuxEffects, type.auxSkillPool);
        if (initialAux != null) {
            data.learnedAuxSkills.add(initialAux);
            data.defaultAuxSkills.add(initialAux);
        }

        data.aiPerson = createAIPerson(type, getDisplayName(data, type), 1, getRankForMember(member));
        syncPersonSkills(data, null, null);
        dataMap.put(memberId, data);
        return data;
    }

    public static void refreshCampaignMember(FleetMemberAPI member) {
        if (member == null || !isPlayerFleetMember(member)) {
            return;
        }
        ShipboardAIData data = getOrCreateData(member);
        if (data == null) {
            return;
        }
        ensureDataIntegrity(member, data);

        PersonAPI captain = member.getCaptain();
        if (captain == null || captain.isDefault()) {
            member.setCaptain(data.aiPerson);
        }
    }

    public static void updateCombatState(ShipAPI ship, String modId) {
        if (ship == null || ship.getFleetMember() == null) {
            return;
        }
        FleetMemberAPI member = ship.getFleetMember();
        if (!Moci_ShipboardAIConfigLoader.hasShipboardAISystem(member)) {
            return;
        }
        ShipboardAIData data = getOrCreateData(member);
        if (data == null) {
            return;
        }
        ensureDataIntegrity(member, data);

        // 没有军官时，AI接管
        PersonAPI captain = ship.getCaptain();
        if (captain == null || captain.isDefault()) {
            ship.setCaptain(data.aiPerson);
            captain = ship.getCaptain();
        }

        MutableShipStatsAPI stats = ship.getMutableStats();
        Moci_ShipboardAIAuxEffectRegistry.clearAllEffects(stats, modId);

        // AI自动驾驶时，辅助词条不生效
        boolean aiDriving = captain == data.aiPerson || captain == null || captain.isDefault();
        if (aiDriving) {
            return;
        }

        // 有军官时，AI仅提供辅助词条效果
        for (String auxSkill : data.learnedAuxSkills) {
            float level = data.eliteSkills.contains(auxSkill) ? 2f : 1f;
            Moci_ShipboardAIAuxEffectRegistry.applySkillEffect(auxSkill, level, stats, modId);
        }
    }

    public static ExperienceGainResult addExperience(FleetMemberAPI member, float gainedExp) {
        ShipboardAIData data = getOrCreateData(member);
        if (data == null) {
            return new ExperienceGainResult(false, 0f, 0, 0);
        }
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
        if (type == null) {
            return new ExperienceGainResult(false, 0f, 0, 0);
        }

        int levelBefore = getCurrentLevel(data);
        if (levelBefore >= type.levelCap) {
            return new ExperienceGainResult(false, 0f, levelBefore, levelBefore);
        }

        float delta = Math.max(0f, gainedExp);
        if (delta <= 0f) {
            return new ExperienceGainResult(false, 0f, levelBefore, levelBefore);
        }

        data.experience += delta;
        int levelAfter = levelBefore;
        while (levelAfter < type.levelCap && data.experience >= getExperienceForLevel(levelAfter + 1)) {
            levelAfter++;
        }
        if (data.aiPerson != null && levelAfter != levelBefore) {
            data.aiPerson.getStats().setLevel(levelAfter);
        }
        return new ExperienceGainResult(true, delta, levelBefore, levelAfter);
    }

    public static boolean renameAI(FleetMemberAPI member, String newName) {
        ShipboardAIData data = getOrCreateData(member);
        if (data == null || newName == null || newName.trim().isEmpty()) {
            return false;
        }
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
        if (type == null || !type.canRename) {
            return false;
        }

        data.customName = newName.trim();
        if (data.aiPerson != null) {
            data.aiPerson.setName(new FullName(data.customName, "", FullName.Gender.ANY));
        }
        return true;
    }

    public static boolean assignAiType(FleetMemberAPI member, String aiTypeId, boolean keepProgress) {
        ShipboardAIData data = getOrCreateData(member);
        if (data == null || aiTypeId == null || aiTypeId.trim().isEmpty()) {
            return false;
        }

        Moci_ShipboardAIConfigLoader.AITypeDefinition newType = Moci_ShipboardAIConfigLoader.getAiType(aiTypeId.trim());
        if (newType == null) {
            return false;
        }

        Set<String> oldNormal = new LinkedHashSet<>(data.learnedSkills);
        Set<String> oldAux = new LinkedHashSet<>(data.learnedAuxSkills);
        int targetLevel = keepProgress ? Math.max(1, Math.min(getCurrentLevel(data), newType.levelCap)) : 1;
        float targetExp = keepProgress ? Math.max(getExperienceForLevel(targetLevel), data.experience) : getExperienceForLevel(1);

        data.baseAiTypeId = newType.aiId;
        data.currentAiTypeId = newType.aiId;
        data.evolved = newType.isEvolved;
        data.experience = targetExp;
        data.learnedSkills = new LinkedHashSet<>();
        data.eliteSkills = new LinkedHashSet<>();
        data.learnedAuxSkills = new LinkedHashSet<>();
        data.defaultSkills = new LinkedHashSet<>();
        data.defaultAuxSkills = new LinkedHashSet<>();

        String initialSkill = pickFirst(newType.defaultSkills, newType.skillPool);
        if (initialSkill != null) {
            data.learnedSkills.add(initialSkill);
            data.defaultSkills.add(initialSkill);
        }
        String initialAux = pickFirst(newType.defaultAuxEffects, newType.auxSkillPool);
        if (initialAux != null) {
            data.learnedAuxSkills.add(initialAux);
            data.defaultAuxSkills.add(initialAux);
        }

        if (!newType.canRename) {
            data.customName = null;
        }

        String displayName = getDisplayName(data, newType);
        if (data.aiPerson == null) {
            data.aiPerson = createAIPerson(newType, displayName, targetLevel, getRankForMember(member));
        } else {
            data.aiPerson.setPortraitSprite(newType.portrait);
            data.aiPerson.setPersonality(newType.personality);
            data.aiPerson.getStats().setLevel(targetLevel);
            data.aiPerson.setName(new FullName(displayName, "", FullName.Gender.ANY));
            data.aiPerson.setRankId(getRankForMember(member));
            data.aiPerson.setPostId(null);
        }
        syncPersonSkills(data, oldNormal, oldAux);
        return true;
    }

    public static boolean learnSkill(FleetMemberAPI member, String skillId) {
        ShipboardAIData data = getOrCreateData(member);
        if (data == null || skillId == null) {
            return false;
        }
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
        if (type == null) {
            return false;
        }
        if (!type.skillPool.contains(skillId)) {
            return false;
        }
        if (data.learnedSkills.contains(skillId)) {
            return false;
        }
        if (data.learnedSkills.size() >= type.maxSkills) {
            return false;
        }
        if (getAvailableSkillPickCount(data, type) <= 0) {
            return false;
        }

        Set<String> oldNormal = new LinkedHashSet<>(data.learnedSkills);
        Set<String> oldAux = new LinkedHashSet<>(data.learnedAuxSkills);
        data.learnedSkills.add(skillId);
        syncPersonSkills(data, oldNormal, oldAux);
        return true;
    }

    public static boolean eliteSkill(FleetMemberAPI member, String skillId) {
        ShipboardAIData data = getOrCreateData(member);
        if (data == null || skillId == null) {
            return false;
        }
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
        if (type == null) {
            return false;
        }
        if (!data.learnedSkills.contains(skillId)) {
            return false;
        }
        if (data.eliteSkills.contains(skillId)) {
            return false;
        }
        if (getAvailableElitePickCount(data, type) <= 0) {
            return false;
        }

        data.eliteSkills.add(skillId);
        if (data.aiPerson != null) {
            data.aiPerson.getStats().setSkillLevel(skillId, 2f);
        }
        return true;
    }

    public static boolean learnAuxEffect(FleetMemberAPI member, String skillId) {
        ShipboardAIData data = getOrCreateData(member);
        if (data == null || skillId == null) {
            return false;
        }
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
        if (type == null) {
            return false;
        }
        if (!type.auxSkillPool.contains(skillId)) {
            return false;
        }
        if (data.learnedAuxSkills.contains(skillId)) {
            return false;
        }
        if (getAvailableAuxPickCount(data, type) <= 0) {
            return false;
        }

        Set<String> oldNormal = new LinkedHashSet<>(data.learnedSkills);
        Set<String> oldAux = new LinkedHashSet<>(data.learnedAuxSkills);
        data.learnedAuxSkills.add(skillId);
        syncPersonSkills(data, oldNormal, oldAux);
        return true;
    }

    public static boolean canEvolve(FleetMemberAPI member) {
        return !getAvailableEvolutionOptions(member).isEmpty();
    }

    public static List<String> getAvailableEvolutionOptions(FleetMemberAPI member) {
        ShipboardAIData data = getOrCreateData(member);
        if (data == null) {
            return new ArrayList<>();
        }
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
        if (type == null || !type.hasEvolutionRoute || type.evolvedAiIds == null || type.evolvedAiIds.isEmpty()) {
            return new ArrayList<>();
        }
        if (getCurrentLevel(data) < type.evolutionLevel) {
            return new ArrayList<>();
        }

        List<String> result = new ArrayList<>();
        for (String targetId : type.evolvedAiIds) {
            if (targetId == null || targetId.trim().isEmpty()) {
                continue;
            }
            if (Moci_ShipboardAIConfigLoader.getAiType(targetId) != null) {
                result.add(targetId);
            }
        }
        return result;
    }

    public static boolean evolveAI(FleetMemberAPI member) {
        List<String> options = getAvailableEvolutionOptions(member);
        if (options.isEmpty()) {
            return false;
        }
        return evolveAI(member, options.get(0));
    }

    public static boolean evolveAI(FleetMemberAPI member, String targetAiId) {
        ShipboardAIData data = getOrCreateData(member);
        if (data == null || targetAiId == null || targetAiId.trim().isEmpty()) {
            return false;
        }
        Moci_ShipboardAIConfigLoader.AITypeDefinition oldType = Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
        if (oldType == null || !oldType.hasEvolutionRoute || oldType.evolvedAiIds == null || oldType.evolvedAiIds.isEmpty()) {
            return false;
        }
        String trimmedTargetId = targetAiId.trim();
        if (!oldType.evolvedAiIds.contains(trimmedTargetId)) {
            return false;
        }
        Moci_ShipboardAIConfigLoader.AITypeDefinition newType = Moci_ShipboardAIConfigLoader.getAiType(trimmedTargetId);
        if (newType == null) {
            return false;
        }
        if (getCurrentLevel(data) < oldType.evolutionLevel) {
            return false;
        }

        int oldLevel = getCurrentLevel(data);
        int newLevel = oldType.inheritLevel ? oldLevel : 1;
        newLevel = Math.max(1, Math.min(newLevel, newType.levelCap));

        Set<String> oldNormal = new LinkedHashSet<>(data.learnedSkills);
        Set<String> oldAux = new LinkedHashSet<>(data.learnedAuxSkills);

        Set<String> normal = oldType.inheritSkills ? new LinkedHashSet<>(data.learnedSkills) : new LinkedHashSet<>();
        Set<String> elite = oldType.inheritSkills ? new LinkedHashSet<>(data.eliteSkills) : new LinkedHashSet<>();
        Set<String> aux = oldType.inheritAux ? new LinkedHashSet<>(data.learnedAuxSkills) : new LinkedHashSet<>();

        // 进化后默认技能/词条替换为新形态默认项
        normal.removeAll(data.defaultSkills);
        aux.removeAll(data.defaultAuxSkills);

        Set<String> newDefaultSkills = new LinkedHashSet<>(newType.defaultSkills);
        Set<String> newDefaultAux = new LinkedHashSet<>(newType.defaultAuxEffects);
        if (newDefaultSkills.isEmpty()) {
            String pick = pickFirst(newType.defaultSkills, newType.skillPool);
            if (pick != null) {
                newDefaultSkills.add(pick);
            }
        }
        if (newDefaultAux.isEmpty()) {
            String pick = pickFirst(newType.defaultAuxEffects, newType.auxSkillPool);
            if (pick != null) {
                newDefaultAux.add(pick);
            }
        }

        normal.addAll(newDefaultSkills);
        aux.addAll(newDefaultAux);

        // 保证技能池合法并限制最大技能数
        normal.retainAll(new LinkedHashSet<>(newType.skillPool));
        aux.retainAll(new LinkedHashSet<>(newType.auxSkillPool));
        while (normal.size() > newType.maxSkills) {
            String removeId = null;
            for (String skill : normal) {
                if (!newDefaultSkills.contains(skill)) {
                    removeId = skill;
                    break;
                }
            }
            if (removeId == null) {
                break;
            }
            normal.remove(removeId);
            elite.remove(removeId);
        }

        elite.retainAll(normal);

        data.currentAiTypeId = newType.aiId;
        data.evolved = true;
        data.learnedSkills = normal;
        data.eliteSkills = elite;
        data.learnedAuxSkills = aux;
        data.defaultSkills = newDefaultSkills;
        data.defaultAuxSkills = newDefaultAux;
        if (!newType.canRename) {
            data.customName = null;
        }

        if (data.aiPerson == null) {
            data.aiPerson = createAIPerson(newType, getDisplayName(data, newType), newLevel, getRankForMember(member));
        } else {
            data.aiPerson.setPortraitSprite(newType.portrait);
            data.aiPerson.setPersonality(newType.personality);
            data.aiPerson.getStats().setLevel(newLevel);
            data.aiPerson.setName(new FullName(getDisplayName(data, newType), "", FullName.Gender.ANY));
            data.aiPerson.setRankId(getRankForMember(member));
            data.aiPerson.setPostId(null);
        }
        syncPersonSkills(data, oldNormal, oldAux);

        return true;
    }

    public static List<String> getAvailableSkillOptions(FleetMemberAPI member) {
        ShipboardAIData data = getOrCreateData(member);
        if (data == null) {
            return new ArrayList<>();
        }
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
        if (type == null || getAvailableSkillPickCount(data, type) <= 0 || data.learnedSkills.size() >= type.maxSkills) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String skill : type.skillPool) {
            if (!data.learnedSkills.contains(skill)) {
                result.add(skill);
            }
        }
        return result;
    }

    public static List<String> getAvailableEliteSkillOptions(FleetMemberAPI member) {
        ShipboardAIData data = getOrCreateData(member);
        if (data == null) {
            return new ArrayList<>();
        }
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
        if (type == null || getAvailableElitePickCount(data, type) <= 0) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String skill : data.learnedSkills) {
            if (!data.eliteSkills.contains(skill)) {
                result.add(skill);
            }
        }
        return result;
    }

    public static List<String> getAvailableAuxOptions(FleetMemberAPI member) {
        ShipboardAIData data = getOrCreateData(member);
        if (data == null) {
            return new ArrayList<>();
        }
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
        if (type == null || getAvailableAuxPickCount(data, type) <= 0) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String skill : type.auxSkillPool) {
            if (!data.learnedAuxSkills.contains(skill)) {
                result.add(skill);
            }
        }
        return result;
    }

    public static int getCurrentLevel(ShipboardAIData data) {
        if (data == null || data.aiPerson == null) {
            return 1;
        }
        return Math.max(1, data.aiPerson.getStats().getLevel());
    }

    public static float getCurrentExp(ShipboardAIData data) {
        return data == null ? 0f : data.experience;
    }

    public static float getNextLevelExp(ShipboardAIData data) {
        if (data == null) {
            return getExperienceForLevel(1);
        }
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
        if (type == null) {
            return getExperienceForLevel(getCurrentLevel(data) + 1);
        }
        int currentLevel = getCurrentLevel(data);
        if (currentLevel >= type.levelCap) {
            return getExperienceForLevel(type.levelCap);
        }
        return getExperienceForLevel(currentLevel + 1);
    }

    public static int getLevelCap(ShipboardAIData data) {
        if (data == null) {
            return 1;
        }
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
        return type == null ? 1 : type.levelCap;
    }

    public static int getAvailableSkillPickCount(ShipboardAIData data, Moci_ShipboardAIConfigLoader.AITypeDefinition type) {
        int level = getCurrentLevel(data);
        int granted = level;
        return Math.max(0, granted - data.learnedSkills.size());
    }

    public static int getAvailableElitePickCount(ShipboardAIData data, Moci_ShipboardAIConfigLoader.AITypeDefinition type) {
        int level = getCurrentLevel(data);
        int granted = level / 2;
        return Math.max(0, granted - data.eliteSkills.size());
    }

    public static int getAvailableAuxPickCount(ShipboardAIData data, Moci_ShipboardAIConfigLoader.AITypeDefinition type) {
        int level = getCurrentLevel(data);
        int granted = 1 + (level / 3);
        return Math.max(0, granted - data.learnedAuxSkills.size());
    }

    public static String getPersonalityLabel(String personalityId) {
        if ("timid".equals(personalityId)) {
            return Moci_ShipboardAITextRegistry.getUiText("personality_timid", "胆小");
        }
        if ("cautious".equals(personalityId)) {
            return Moci_ShipboardAITextRegistry.getUiText("personality_cautious", "谨慎");
        }
        if ("steady".equals(personalityId)) {
            return Moci_ShipboardAITextRegistry.getUiText("personality_steady", "沉着");
        }
        if ("aggressive".equals(personalityId)) {
            return Moci_ShipboardAITextRegistry.getUiText("personality_aggressive", "激进");
        }
        if ("reckless".equals(personalityId)) {
            return Moci_ShipboardAITextRegistry.getUiText("personality_reckless", "鲁莽");
        }
        return personalityId;
    }

    private static boolean isPlayerFleetMember(FleetMemberAPI member) {
        return member != null
                && member.getFleetData() != null
                && member.getFleetData().getFleet() != null
                && member.getFleetData().getFleet().isPlayerFleet();
    }

    private static void ensureDataIntegrity(FleetMemberAPI member, ShipboardAIData data) {
        canonicalizeAiTypeIds(data);
        if (data.aiPerson == null) {
            Moci_ShipboardAIConfigLoader.AITypeDefinition type = Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
            if (type != null) {
                int level = Math.max(1, computeLevelByExp(data.experience, type.levelCap));
                data.aiPerson = createAIPerson(type, getDisplayName(data, type), level, getRankForMember(member));
                syncPersonSkills(data, null, null);
            }
        }
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
        if (type != null) {
            int level = getCurrentLevel(data);
            if (level > type.levelCap) {
                data.aiPerson.getStats().setLevel(type.levelCap);
            }
        }

        if (member != null && member.getCaptain() == data.aiPerson) {
            // 保证同一对象在舰船上时名字/头像是最新的
            if (type != null) {
                data.aiPerson.setPortraitSprite(type.portrait);
                data.aiPerson.setPersonality(type.personality);
                data.aiPerson.setName(new FullName(getDisplayName(data, type), "", FullName.Gender.ANY));
            }
        }
        if (data.aiPerson != null) {
            data.aiPerson.setRankId(getRankForMember(member));
            data.aiPerson.setPostId(null);
        }
    }

    private static PersonAPI createAIPerson(Moci_ShipboardAIConfigLoader.AITypeDefinition type, String displayName, int level, String rankId) {
        PersonAPI person = Global.getFactory().createPerson();
        person.setId("moci_shipboard_ai_" + UUID.randomUUID());
        person.setFaction("player");
        person.setPortraitSprite(type.portrait);
        person.setName(new FullName(displayName, "", FullName.Gender.ANY));
        person.setPersonality(type.personality);
        person.setRankId(rankId == null || rankId.trim().isEmpty()
                ? Moci_ShipboardAIConfigLoader.getDefaultAiRankId()
                : rankId.trim());
        person.setPostId(null);
        person.getStats().setLevel(level);
        return person;
    }

    private static String getRankForMember(FleetMemberAPI member) {
        String rankId = Moci_ShipboardAIConfigLoader.resolveRankForMember(member);
        if (rankId == null || rankId.trim().isEmpty()) {
            return Moci_ShipboardAIConfigLoader.getDefaultAiRankId();
        }
        return rankId.trim();
    }

    private static int computeLevelByExp(float exp, int levelCap) {
        int level = 1;
        while (level < levelCap && exp >= getExperienceForLevel(level + 1)) {
            level++;
        }
        return level;
    }

    private static void canonicalizeAiTypeIds(ShipboardAIData data) {
        if (data == null) {
            return;
        }

        data.baseAiTypeId = Moci_ShipboardAIConfigLoader.getCanonicalAiTypeId(data.baseAiTypeId);
        data.currentAiTypeId = Moci_ShipboardAIConfigLoader.getCanonicalAiTypeId(data.currentAiTypeId);
        if ((data.currentAiTypeId == null || data.currentAiTypeId.trim().isEmpty())
                && data.baseAiTypeId != null && !data.baseAiTypeId.trim().isEmpty()) {
            data.currentAiTypeId = data.baseAiTypeId;
        }
    }

    private static void syncPersonSkills(ShipboardAIData data, Set<String> oldNormal, Set<String> oldAux) {
        if (data == null || data.aiPerson == null) {
            return;
        }

        Set<String> currentOfficerSkills = new LinkedHashSet<>(data.learnedSkills);

        Set<String> previousAll = new LinkedHashSet<>();
        if (oldNormal != null) {
            previousAll.addAll(oldNormal);
        }
        if (oldAux != null) {
            previousAll.addAll(oldAux);
        }

        // 清理已移除技能
        for (String oldSkill : previousAll) {
            if (!currentOfficerSkills.contains(oldSkill) && isOfficerSkillId(oldSkill)) {
                data.aiPerson.getStats().setSkillLevel(oldSkill, 0f);
            }
        }

        // 应用当前普通技能（普通技能支持精英化）
        for (String skill : data.learnedSkills) {
            float level = data.eliteSkills.contains(skill) ? 2f : 1f;
            data.aiPerson.getStats().setSkillLevel(skill, level);
        }
    }

    private static boolean isOfficerSkillId(String skillId) {
        if (skillId == null || skillId.trim().isEmpty()) {
            return false;
        }
        try {
            return Global.getSettings().getSkillSpec(skillId) != null;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String getDisplayName(ShipboardAIData data, Moci_ShipboardAIConfigLoader.AITypeDefinition type) {
        if (data != null && data.customName != null && !data.customName.trim().isEmpty()) {
            return data.customName.trim();
        }
        return type.name;
    }

    private static String pickFirst(List<String> preferred, List<String> fallback) {
        if (preferred != null && !preferred.isEmpty()) {
            return preferred.get(0);
        }
        if (fallback != null && !fallback.isEmpty()) {
            return fallback.get(0);
        }
        return null;
    }

    public static float getExperienceForLevel(int level) {
        if (level <= 1) {
            return 0f;
        }
        float total = 0f;
        for (int lv = 1; lv < level; lv++) {
            // 每级需求：512 基础 + 256 * (当前等级 - 1)
            total += 512f + 256f * (lv - 1);
        }
        return total;
    }
}
