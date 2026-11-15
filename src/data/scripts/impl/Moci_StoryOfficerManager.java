package data.scripts.impl;

import java.util.Map;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Skills;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.contacts.ContactIntel;
import com.fs.starfarer.api.impl.campaign.missions.hub.BaseHubMission;
import com.fs.starfarer.api.impl.campaign.missions.hub.BaseMissionHub;
import com.fs.starfarer.api.impl.campaign.rulecmd.AddRemoveCommodity;
import com.fs.starfarer.api.util.Misc;

import data.scripts.util.Moci_TextLoader;

/**
 * 剧情军官（冲田十三 / 星野琉璃 / 泰蕾莎）的联系人注册、好感度与招募。
 *
 * <p>好感度直接使用原版人物关系（{@code person.getRelToPlayer()}，界面显示 -100~100）。
 * 普通任务由原版任务枢纽提供：注册为军事联系人后，原版 {@code military} 标签任务会自动出现，
 * 完成时原版会自行提升人物关系。</p>
 */
public final class Moci_StoryOfficerManager {
    private static final Logger LOG = Global.getLogger(Moci_StoryOfficerManager.class);
    private static final String LOG_PREFIX = "[MOCI-OFFICER] ";
    private static final String TEXT_ID = "Moci_CampaignText";

    /** 招募所需好感度（原版人物关系，界面数值）。 */
    public static final int RECRUIT_REL = 50;

    public enum StoryOfficer {
        OKITA("okita", "moci_tulip_contact_okita", "moci_officer_okita",
                "Juzo", "Okita", FullName.Gender.MALE,
                "graphics/portraits/bounty/Moci_Okita.png", Personalities.STEADY,
                7, 14, 5, "Moci_OkitaSkill",
                new String[] {Skills.TARGET_ANALYSIS, Skills.BALLISTIC_MASTERY, Skills.IMPACT_MITIGATION,
                        Skills.DAMAGE_CONTROL, Skills.GUNNERY_IMPLANTS},
                new String[] {Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE}),
        RURI("ruri", "moci_nergal_contact_ruri", "moci_officer_ruri",
                "Ruri", "Hoshino", FullName.Gender.FEMALE,
                "graphics/portraits/bounty/Hoshino_Ruri.png", Personalities.STEADY,
                6, 12, 4, "Moci_RuriSkill",
                new String[] {Skills.FIELD_MODULATION, Skills.ENERGY_WEAPON_MASTERY, Skills.GUNNERY_IMPLANTS,
                        Skills.SYSTEMS_EXPERTISE},
                new String[] {Skills.TARGET_ANALYSIS, Skills.HELMSMANSHIP}),
        TERESA("teresa", "moci_nergal_contact_teresa", "moci_officer_teresa",
                "Teresa", "Testarossa", FullName.Gender.FEMALE,
                "graphics/portraits/characters/Moci_Teresa_Testarossa.png", Personalities.CAUTIOUS,
                6, 12, 4, "Moci_TeresaSkill",
                new String[] {Skills.MISSILE_SPECIALIZATION, Skills.SYSTEMS_EXPERTISE, Skills.FIELD_MODULATION,
                        Skills.HELMSMANSHIP},
                new String[] {Skills.COMBAT_ENDURANCE, Skills.TARGET_ANALYSIS});

        /** rules.csv 里使用的短名。 */
        public final String key;
        /** 作为联系人 NPC 时的 person id。 */
        public final String npcId;
        /** 招募后军官的 person id。 */
        public final String officerId;
        public final String firstName;
        public final String lastName;
        public final FullName.Gender gender;
        public final String portrait;
        public final String personality;
        public final int level;
        public final int maxLevel;
        public final int maxElite;
        public final String exclusiveSkillId;
        public final String[] eliteSkills;
        public final String[] normalSkills;

        StoryOfficer(String key, String npcId, String officerId, String firstName, String lastName,
                     FullName.Gender gender, String portrait, String personality,
                     int level, int maxLevel, int maxElite, String exclusiveSkillId,
                     String[] eliteSkills, String[] normalSkills) {
            this.key = key;
            this.npcId = npcId;
            this.officerId = officerId;
            this.firstName = firstName;
            this.lastName = lastName;
            this.gender = gender;
            this.portrait = portrait;
            this.personality = personality;
            this.level = level;
            this.maxLevel = maxLevel;
            this.maxElite = maxElite;
            this.exclusiveSkillId = exclusiveSkillId;
            this.eliteSkills = eliteSkills;
            this.normalSkills = normalSkills;
        }

        /** 已招募标记；rules.csv 以 {@code $global.moci_<key>_recruited} 读取。 */
        public String recruitedKey() {
            return "$moci_" + key + "_recruited";
        }

        public static StoryOfficer fromKey(String key) {
            if (key == null) return null;
            for (StoryOfficer officer : values()) {
                if (officer.key.equalsIgnoreCase(key)) return officer;
            }
            return null;
        }
    }

    private Moci_StoryOfficerManager() {
    }

    // ------------------------------------------------------------------ 状态查询

    public static PersonAPI getNpc(StoryOfficer who) {
        if (who == null || Global.getSector() == null) return null;
        return Global.getSector().getImportantPeople().getPerson(who.npcId);
    }

    public static boolean isRecruited(StoryOfficer who) {
        return who != null && Global.getSector() != null
                && Global.getSector().getMemoryWithoutUpdate().getBoolean(who.recruitedKey());
    }

    /** 对应的主线/剧情任务是否已经完成。 */
    public static boolean isStoryDone(StoryOfficer who) {
        if (who == null || Global.getSector() == null) return false;
        switch (who) {
            case OKITA:
                return Global.getSector().getMemoryWithoutUpdate().getBoolean("$moci_okita_mission3_completed");
            case RURI:
                return Moci_YurikaStoryManager.isStoryCompleted(Moci_YurikaStoryManager.StoryMissionType.NADE_B);
            case TERESA:
                return Moci_YurikaStoryManager.isStoryCompleted(Moci_YurikaStoryManager.StoryMissionType.TDD);
            default:
                return false;
        }
    }

    /** 当前好感度（界面数值）；NPC 不存在时返回 0。 */
    public static int getRelInt(StoryOfficer who) {
        PersonAPI npc = getNpc(who);
        return npc == null ? 0 : npc.getRelToPlayer().getRepInt();
    }

    /** 不能招募的原因；返回 null 表示可以招募。 */
    public static String getRecruitBlockReason(StoryOfficer who) {
        PersonAPI npc = getNpc(who);
        if (npc == null || isRecruited(who)) {
            return text("story_officer.block_unavailable", "对方目前无法加入你的舰队。");
        }
        if (!isStoryDone(who)) {
            return text("story_officer.block_story", "需要先完成对应的主线任务。");
        }
        int rel = npc.getRelToPlayer().getRepInt();
        if (rel < RECRUIT_REL) {
            return format("story_officer.block_rel", "需要好感度达到 {required}（当前 {current}）。",
                    Moci_TextLoader.mapOf("required", String.valueOf(RECRUIT_REL), "current", String.valueOf(rel)));
        }
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        if (fleet != null && Misc.getNumNonMercOfficers(fleet) >= Misc.getMaxOfficers(fleet)) {
            return text("story_officer.block_officer_limit", "你的军官编制已满。");
        }
        if (hasActiveMissionFrom(npc)) {
            return text("story_officer.block_active_mission", "对方委托给你的任务尚未了结。");
        }
        return null;
    }

    private static boolean hasActiveMissionFrom(PersonAPI npc) {
        for (IntelInfoPlugin intel : Global.getSector().getIntelManager().getIntel(BaseHubMission.class)) {
            if (!(intel instanceof BaseHubMission)) continue;
            BaseHubMission mission = (BaseHubMission) intel;
            if (mission.getPerson() == npc && !mission.isEnded() && !mission.isEnding()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 好感度

    /**
     * 提升好感度并给出原版的关系变化提示。
     *
     * @param delta     关系变化量，0.1 = 界面上的 10
     * @param textPanel 对话文本面板；为 null 时提示显示在战役消息栏
     */
    public static void addRelationship(StoryOfficer who, float delta, TextPanelAPI textPanel) {
        PersonAPI npc = getNpc(who);
        if (npc == null || delta == 0f) return;
        CoreReputationPlugin.CustomRepImpact impact = new CoreReputationPlugin.CustomRepImpact();
        impact.delta = delta;
        Global.getSector().adjustPlayerReputation(
                new CoreReputationPlugin.RepActionEnvelope(
                        CoreReputationPlugin.RepActions.CUSTOM, impact, textPanel, false, true),
                npc);
        LOG.info(LOG_PREFIX + who.key + " 好感度 " + (delta > 0f ? "+" : "") + Math.round(delta * 100f)
                + " → " + npc.getRelToPlayer().getRepInt());
    }

    // ------------------------------------------------------------------ 联系人注册

    /**
     * 把 NPC 注册为原版军事联系人：带任务枢纽，对话里出现原版的任务列表入口。
     * 可重复调用。
     */
    public static void ensureContact(PersonAPI npc, MarketAPI market) {
        if (npc == null || market == null) return;

        if (!npc.hasTag(Tags.CONTACT_MILITARY)) {
            npc.addTag(Tags.CONTACT_MILITARY);
        }
        if (npc.getMarket() == null) {
            npc.setMarket(market);
        }

        ContactIntel intel = ContactIntel.getContactIntel(npc);
        if (intel == null || intel.isEnded() || intel.isEnding()) {
            intel = new ContactIntel(npc, market);
            intel.develop(null); // 直接成为正式联系人，而不是潜在联系人
            Global.getSector().getIntelManager().addIntel(intel, false);
        } else {
            ContactIntel.ContactState state = intel.getState();
            if (state == ContactIntel.ContactState.POTENTIAL
                    || state == ContactIntel.ContactState.SUSPENDED
                    || state == ContactIntel.ContactState.LOST_CONTACT
                    || state == ContactIntel.ContactState.LOST_CONTACT_DECIV) {
                intel.relocateToMarket(market, false);
                intel.develop(null);
            }
        }

        if (BaseMissionHub.get(npc) == null) {
            BaseMissionHub.set(npc, new BaseMissionHub(npc));
        }
    }

    // ------------------------------------------------------------------ 招募

    /**
     * 招募为玩家军官，并让 NPC 从星球上退场。条件不满足时不做任何事并返回 false。
     */
    public static boolean recruit(StoryOfficer who, InteractionDialogAPI dialog) {
        if (who == null || getRecruitBlockReason(who) != null) return false;
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        if (fleet == null) return false;

        PersonAPI officer = createOfficer(who);
        fleet.getFleetData().addOfficer(officer);
        if (dialog != null && dialog.getTextPanel() != null) {
            AddRemoveCommodity.addOfficerGainText(officer, dialog.getTextPanel());
        }

        retireNpc(who);
        Global.getSector().getMemoryWithoutUpdate().set(who.recruitedKey(), true);
        LOG.info(LOG_PREFIX + who.key + " 已招募为军官 level=" + who.level + "/" + who.maxLevel
                + " maxElite=" + who.maxElite);
        return true;
    }

    private static PersonAPI createOfficer(StoryOfficer who) {
        PersonAPI person = Global.getSector().getFaction(Factions.INDEPENDENT).createRandomPerson();
        person.setId(who.officerId);
        person.setName(new FullName(who.firstName, who.lastName, who.gender));
        person.setGender(who.gender);
        person.setPortraitSprite(who.portrait);
        person.setPersonality(who.personality);
        person.setRankId(Ranks.SPACE_CAPTAIN);
        person.setPostId(Ranks.POST_OFFICER);

        MutableCharacterStatsAPI stats = person.getStats();
        stats.setLevel(who.level);
        for (String skill : who.normalSkills) {
            stats.setSkillLevel(skill, 1f);
        }
        for (String skill : who.eliteSkills) {
            stats.setSkillLevel(skill, 2f);
        }
        // 专属技能额外附带，不占等级对应的技能数。
        stats.setSkillLevel(who.exclusiveSkillId, 2f);

        MemoryAPI memory = person.getMemoryWithoutUpdate();
        memory.set(MemFlags.OFFICER_MAX_LEVEL, who.maxLevel);
        memory.set(MemFlags.OFFICER_MAX_ELITE_SKILLS, who.maxElite);
        return person;
    }

    /** 结束联系人关系并把 NPC 从市场、通讯录和重要人物表里移除。 */
    private static void retireNpc(StoryOfficer who) {
        PersonAPI npc = getNpc(who);
        if (npc == null) return;

        MarketAPI market = npc.getMarket();
        ContactIntel intel = ContactIntel.getContactIntel(npc);
        if (intel != null) {
            intel.endImmediately();
        }
        BaseMissionHub.set(npc, null);
        if (market != null) {
            if (market.getCommDirectory() != null) {
                market.getCommDirectory().removePerson(npc);
            }
            market.removePerson(npc);
        }
        Global.getSector().getImportantPeople().removePerson(npc);
    }

    // ------------------------------------------------------------------ 文本

    private static String text(String key, String fallback) {
        return Moci_TextLoader.getText(TEXT_ID, key, fallback);
    }

    private static String format(String key, String fallback, Map<String, String> replacements) {
        String value = text(key, fallback);
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return value;
    }
}
