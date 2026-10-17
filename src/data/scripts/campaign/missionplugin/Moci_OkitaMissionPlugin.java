package data.scripts.campaign.missionplugin;

import java.awt.Color;
import java.util.Set;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

import data.campaign.MociFleetFlagshipHelper;
import data.campaign.MociRecoverableShipHelper;
import data.scripts.impl.Moci_CampaignFeedback;
import data.scripts.util.Moci_TextLoader;

/**
 * 冲田十三任务链（阶段任务）的核心任务插件。
 *
 * 设计思路（参考“栗子赏金”模式）：
 * 1. rules.csv 只负责“接任务入口”；
 * 2. 本类负责“任务实体生成 + 每帧状态推进 + 战斗配置 + 奖励结算 + Intel展示”；
 * 3. 每个阶段对应一个独立实例，互不干扰，便于后续单阶段调试。
 */
public class Moci_OkitaMissionPlugin extends BaseIntelPlugin {
    private static final String TEXT_ID = "Moci_CampaignText";
    /** 任务敌方派系，优先使用 MagicLib 的赏金派系。 */
    private static final String FACTION_ID = "ML_bounty";
    /** 动态生成任务舰队时使用的 ID 前缀（后接 stage + UID）。 */
    private static final String MISSION_FLEET_ID_PREFIX = "moci_okita_mission_fleet_";
    /** 挂在任务舰队内存中的阶段标记，供后续扩展或调试读取。 */
    private static final String MISSION_FLEET_STAGE_KEY = "$moci_okita_mission_fleet_stage";
    /** 任务是否“正在运行”的内存前缀：$moci_okita_mission_running_1/2/3。 */
    private static final String RUNNING_PREFIX = "$moci_okita_mission_running_";
    /** 独立派系随机僚机舰队规模（参考 HubMission 巡逻舰队中/大档位）。 */
    private static final float INDEPENDENT_MEDIUM_ESCORT_FRACTION = 0.35f;
    private static final float INDEPENDENT_LARGE_ESCORT_FRACTION = 0.50f;

    /** 三阶段“已完成”标记（规则和脚本均可读）。 */
    private static final String MISSION1_COMPLETED_KEY = "$moci_okita_mission1_completed";
    private static final String MISSION2_COMPLETED_KEY = "$moci_okita_mission2_completed";
    private static final String MISSION3_COMPLETED_KEY = "$moci_okita_mission3_completed";

    /** 阶段名称（用于 Intel 标题与完成提示）。 */
    private static final String MISSION1_NAME = "okita_mission.name_stage1";
    private static final String MISSION2_NAME = "okita_mission.name_stage2";
    private static final String MISSION3_NAME = "okita_mission.name_stage3";

    /** 当前任务所属阶段（1/2/3）。 */
    private final int stage;
    /** 当前任务状态。 */
    private State state = State.stage_1;
    /** 上一帧状态，用于检测状态变化后刷新 Intel。 */
    private State lastState = State.stage_1;

    /** 当前阶段对应的目标舰队（战斗对象）。 */
    private CampaignFleetAPI targetFleet;
    /** 目标舰队刷新所依附的星门实体。 */
    private SectorEntityToken targetGate;
    /** 任务状态轮询间隔，避免无意义的逐帧检查。 */
    private final IntervalUtil stateCheckInterval = new IntervalUtil(1.25f, 1.25f);

    private enum State {
        stage_1, // 战斗中
        stage_2  // 完成
    }

    /**
     * 构造任务实例并立刻启动。
     *
     * @param dialog 接任务时的对话对象（可为 null）
     * @param stage  阶段编号（1~3）
     */
    public Moci_OkitaMissionPlugin(InteractionDialogAPI dialog, int stage) {
        this.stage = stage;
        // 任务默认高亮，便于玩家在星图与 Intel 面板定位目标
        setImportant(true);
        // 初始化阶段目标舰队
        spawnMissionFleet();
        // 若未找到有效星门/舰队，不进入运行态，释放锁并给出提示。
        if (!hasSpawnedMissionTarget()) {
            setRunning(false);
            Global.getLogger(Moci_OkitaMissionPlugin.class)
                    .warn("Okita任务阶段 " + stage + " 启动失败：未找到可用星门或舰队生成失败。");
            if (dialog != null && dialog.getTextPanel() != null) {
                dialog.getTextPanel().addPara(text("okita_mission.start_failed"), Misc.getNegativeHighlightColor());
            }
            return;
        }
        // 注册成 every-frame script，驱动状态机推进
        Global.getSector().addScript(this);

        // 加入 Intel 面板；若有对话则把提示写入当前文本面板
        if (dialog == null) {
            Global.getSector().getIntelManager().addIntel(this, false);
        } else {
            Global.getSector().getIntelManager().addIntel(this, false, dialog.getTextPanel());
        }
    }

    @Override
    protected void advanceImpl(float amount) {
        // stage_1：任务进行中，检查舰队是否存在/是否被清空
        if (state == State.stage_1) {
            stateCheckInterval.advance(amount);
            if (stateCheckInterval.intervalElapsed()) {
                if (targetFleet == null) {
                    // 防御性重刷：目标舰队异常丢失时尝试重建
                    spawnMissionFleet();
                } else if (targetFleet.isEmpty()) {
                    // 敌舰队已被清空，任务完成
                    completeMission();
                }
            }
        }

        // 状态发生变化后刷新 Intel 摘要
        if (lastState != state) {
            lastState = state;
            sendUpdateIfPlayerHasIntel(new Object(), false);
        }
    }

    /** 完成阶段任务：取消高亮、发奖、写完成键、结束 Intel。 */
    private void completeMission() {
        if (state == State.stage_2) {
            return;
        }
        state = State.stage_2;

        if (targetFleet != null) {
            Misc.makeUnimportant(targetFleet, getImportantKey());
        }

        // 结算奖励并推进任务链内存状态
        grantReward();
        setMissionCompletedMemory();

        // 任务结束：取消重要标记并释放“运行中”锁
        setImportant(false);
        setRunning(false);
        // 给玩家几秒时间看完成提示，再从 Intel 列表自然结束
        endAfterDelay(3f);

        Moci_CampaignFeedback.addMissionCompletedMessage(getMissionName());
    }

    /** 按阶段发放奖励。 */
    private void grantReward() {
        CargoAPI cargo = Global.getSector().getPlayerFleet().getCargo();
        if (stage == 1) {
            // 第一阶段：武器包
            cargo.addWeapons("Moci_heavry_vulcan", 2);
            cargo.addWeapons("Moci_heavry_phasecl", 2);
            cargo.addWeapons("Moci_heavyblaster", 2);
            cargo.addWeapons("Moci_split_missile", 4);
        } else if (stage == 2 || stage == 3) {
            // 第二/三阶段：波动引擎
            cargo.addSpecial(new SpecialItemData("Moci_wave_motion_engine", null), 1);
        }
    }

    /** 写入“阶段已完成”标记，供 rules.csv 解锁下一阶段。 */
    private void setMissionCompletedMemory() {
        if (stage == 1) {
            setGlobalMemoryBoth(MISSION1_COMPLETED_KEY, true);
        } else if (stage == 2) {
            setGlobalMemoryBoth(MISSION2_COMPLETED_KEY, true);
        } else if (stage == 3) {
            setGlobalMemoryBoth(MISSION3_COMPLETED_KEY, true);
        }
    }

    /**
     * 统一按星际扇区内存规范写入 `$key`。
     */
    private void setGlobalMemoryBoth(String key, boolean value) {
        Global.getSector().getMemoryWithoutUpdate().set(key, value);
    }

    /** 标记某阶段是否正在运行（用于 rulecmd 防重复启动）。 */
    private void setRunning(boolean running) {
        Global.getSector().getMemoryWithoutUpdate().set(RUNNING_PREFIX + stage, running);
    }

    /** 是否成功生成了本阶段任务目标。 */
    public boolean hasSpawnedMissionTarget() {
        return targetGate != null && targetFleet != null;
    }

    /** 当前实例是否处于可执行阶段（用于命令层判断“是否已有活动任务”）。 */
    public boolean isMissionActive() {
        return state == State.stage_1 && hasSpawnedMissionTarget();
    }

    /** 供命令层查询的阶段号。 */
    public int getStage() {
        return stage;
    }

    /** 是否已有相同阶段的活动任务实例。 */
    public static boolean hasActiveMissionForStage(int stage) {
        if (Global.getSector() == null || Global.getSector().getIntelManager() == null) {
            return false;
        }
        for (Object intelObj : Global.getSector().getIntelManager().getIntel()) {
            if (!(intelObj instanceof Moci_OkitaMissionPlugin)) {
                continue;
            }
            Moci_OkitaMissionPlugin mission = (Moci_OkitaMissionPlugin) intelObj;
            if (mission.getStage() == stage && mission.isMissionActive()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成本阶段任务舰队并刷新到星门附近。
     *
     * 刷新策略：
     * - 优先在“非核心/非隐藏/非切断”星系选 gate 类实体；
     * - 刷新后赋予强制敌对、主动追击、无声望影响等任务属性；
     * - 注入 FID 配置锁定战斗行为。
     */
    private void spawnMissionFleet() {
        SectorEntityToken gate = pickGateForMissionSpawn();
        if (gate == null) {
            return;
        }
        this.targetGate = gate;

        CampaignFleetAPI fleet = FleetFactoryV3.createEmptyFleet(FACTION_ID, FleetTypes.PATROL_LARGE, null);
        if (fleet == null) {
            return;
        }
        fleet.setId(MISSION_FLEET_ID_PREFIX + stage + "_" + Misc.genUID());
        fleet.setName(text("okita_mission.enemy_fleet"));
        fleet.setNoFactionInName(true);
        fleet.setNoAutoDespawn(true);
        fleet.setTransponderOn(false);
        fleet.getMemoryWithoutUpdate().set(MISSION_FLEET_STAGE_KEY, stage);
        // 任务舰队行为标记
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_HOSTILE, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_ALWAYS_PURSUE, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_NO_REP_IMPACT, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_LOW_REP_IMPACT, true);
        fleet.getMemoryWithoutUpdate().set(
                MemFlags.FLEET_INTERACTION_DIALOG_CONFIG_OVERRIDE_GEN,
                new MissionFIDConfig());

        if (stage == 1) {
            configureStage1Fleet(fleet);
        } else if (stage == 2) {
            configureStage2Fleet(fleet);
        } else if (stage == 3) {
            configureStage3Fleet(fleet);
        }

        // 统一状态：排序 + 满 CR
        fleet.getFleetData().sort();
        enforceCommanderOnFlagship(fleet);
        MociRecoverableShipHelper.markFleetFlagshipForRecovery(fleet);
        for (FleetMemberAPI member : fleet.getMembersWithFightersCopy()) {
            member.getRepairTracker().setCR(1f);
        }

        LocationAPI location = gate.getContainingLocation();
        if (location == null) {
            return;
        }
        location.addEntity(fleet);
        Vector2f spawn = Misc.getPointAtRadius(gate.getLocation(), gate.getRadius() + 220f + (float) Math.random() * 120f);
        fleet.setLocation(spawn.x, spawn.y);
        fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, gate, 1000000f, text("common.guard_gate"));
        fleet.forceSync();

        this.targetFleet = fleet;
        // 在星图中标成任务关键目标
        Misc.makeImportant(fleet, getImportantKey());
    }

    /**
     * 防御性修正：确保“舰队指挥官 = 旗舰舰长”。
     * 某些情况下（排序后）旗舰会变化，导致指挥官不在预期旗舰上。
     */
    private void enforceCommanderOnFlagship(CampaignFleetAPI fleet) {
        if (fleet == null) {
            return;
        }

        FleetMemberAPI flagship = null;
        if (stage == 1) {
            // 第一阶段固定 Hiryu 为旗舰
            for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
                String hullId = member.getHullSpec() != null ? member.getHullSpec().getHullId() : null;
                if (hullId != null && hullId.contains("Moci_Hiryu")) {
                    flagship = member;
                    break;
                }
            }
        }

        if (flagship == null) {
            flagship = fleet.getFlagship();
        }
        MociFleetFlagshipHelper.syncFlagshipAndCommander(fleet, flagship, fleet.getCommander());
    }

    /** 重要目标 key（按阶段隔离）。 */
    private String getImportantKey() {
        return "moci_okita_mission_" + stage;
    }

    /**
     * 安全添加舰船：
     * - 先尝试直接按 variantId 加入舰队；
     * - 失败时再尝试手动 createFleetMember 后加入。
     *
     * 这样可以兼容部分版本中 variant/hull 命名差异。
     */
    private static FleetMemberAPI addFleetMemberSafe(CampaignFleetAPI fleet, String... variantCandidates) {
        for (String variantId : variantCandidates) {
            try {
                FleetMemberAPI member = fleet.getFleetData().addFleetMember(variantId);
                if (member != null) {
                    return member;
                }
            } catch (Throwable ignored) {
            }

            try {
                FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, variantId);
                if (member != null) {
                    fleet.getFleetData().addFleetMember(member);
                    return member;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 把舰船变体设置为必定可打捞。 */
    private static void makeAlwaysRecoverable(FleetMemberAPI member) {
        MociRecoverableShipHelper.ensureMemberVariantAlwaysRecoverable(member);
    }

    /** 把舰船变体设置为不可打捞。 */
    private static void makeNotRecoverable(FleetMemberAPI member) {
        if (member == null || member.getVariant() == null) {
            return;
        }
        member.setVariant(member.getVariant().clone(), false, true);
        member.getVariant().setSource(VariantSource.REFIT);
        member.getVariant().removeTag(Tags.VARIANT_ALWAYS_RECOVERABLE);
        member.getVariant().addTag(Tags.VARIANT_UNBOARDABLE);
        member.getVariant().addTag(Tags.VARIANT_UNRESTORABLE);
    }

    /** 是否可作为随机僚机并入任务舰队。 */
    private static boolean isValidRandomEscort(FleetMemberAPI member) {
        return member != null
                && member.canBeDeployedForCombat()
                && !member.isFighterWing()
                && !member.isCivilian()
                && !member.isStation();
    }

    /** 生成一支“独立派系巡逻舰队规格”的随机战斗僚机池。 */
    private static CampaignFleetAPI createIndependentEscortPool(String fleetType, float fleetSizeFraction) {
        FactionAPI faction = Global.getSector().getFaction(Factions.INDEPENDENT);
        if (faction == null) {
            return null;
        }

        float maxPoints = faction.getApproximateMaxFPPerFleet(FactionAPI.ShipPickMode.PRIORITY_THEN_ALL);
        float doctrineMult = FleetFactoryV3.getDoctrineNumShipsMult(faction.getDoctrine().getNumShips());
        float combatPts = Math.max(12f, maxPoints * fleetSizeFraction * 0.75f * doctrineMult);

        FleetParamsV3 params = new FleetParamsV3(
                null,
                null,
                Factions.INDEPENDENT,
                null,
                fleetType,
                combatPts,
                0f,
                0f,
                0f,
                0f,
                0f,
                0f);
        params.ignoreMarketFleetSizeMult = true;
        params.withOfficers = false;

        CampaignFleetAPI pool = FleetFactoryV3.createFleet(params);
        if (pool == null || pool.isEmpty()) {
            return pool;
        }

        pool.inflateIfNeeded();
        pool.setInflater(null);
        return pool;
    }

    /** 给任务舰队添加一批独立派系随机僚机。 */
    private static void addIndependentRandomEscorts(CampaignFleetAPI fleet, String fleetType, float fleetSizeFraction) {
        CampaignFleetAPI pool = createIndependentEscortPool(fleetType, fleetSizeFraction);
        if (pool == null || pool.isEmpty()) {
            return;
        }

        for (FleetMemberAPI member : pool.getFleetData().getMembersListCopy()) {
            if (!isValidRandomEscort(member)) {
                continue;
            }
            pool.getFleetData().removeFleetMember(member);
            member.setFlagship(false);
            member.setCaptain(null);
            makeNotRecoverable(member);
            fleet.getFleetData().addFleetMember(member);
        }
    }

    /**
     * 构造通用军官模板。
     *
     * 注意：这里不直接塞技能包，技能由上层阶段方法按需求添加。
     */
    private static PersonAPI createBaseOfficer(
            String id,
            String firstName,
            String lastName,
            FullName.Gender gender,
            int level,
            String portraitPath) {
        return createBaseOfficer(
                id,
                firstName,
                lastName,
                gender,
                level,
                portraitPath,
                Ranks.SPACE_COMMANDER,
                Ranks.POST_FLEET_COMMANDER);
    }

    private static PersonAPI createBaseOfficer(
            String id,
            String firstName,
            String lastName,
            FullName.Gender gender,
            int level,
            String portraitPath,
            String rankId,
            String postId) {
        FactionAPI faction = Global.getSector().getFaction(FACTION_ID);
        if (faction == null) {
            faction = Global.getSector().getFaction(Factions.INDEPENDENT);
        }
        PersonAPI officer = faction.createRandomPerson();
        officer.setId(id);
        officer.getName().setFirst(firstName);
        officer.getName().setLast(lastName);
        officer.setGender(gender);
        officer.setPortraitSprite(portraitPath);
        officer.setPersonality(Personalities.RECKLESS);
        officer.setRankId(rankId);
        officer.setPostId(postId);
        officer.getStats().setLevel(level);
        return officer;
    }

    /** Lefina 专用技能包（按需求可继续扩展/裁剪）。 */
    private static void applyLefinaSkillPackage(PersonAPI officer) {
        if (officer == null) {
            return;
        }
        officer.getStats().setSkillLevel("cybernetic_augmentation", 1f);
        officer.getStats().setSkillLevel("tactical_drills", 1f);
        officer.getStats().setSkillLevel("electronic_warfare", 1f);
        officer.getStats().setSkillLevel("flux_regulation", 1f);
        officer.getStats().setSkillLevel("support_doctrine", 1f);
        officer.getStats().setSkillLevel("coordinated_maneuvers", 1f);
        officer.getStats().setSkillLevel("derelict_contingent", 1f);
        officer.getStats().setSkillLevel("helmsmanship", 2f);
        officer.getStats().setSkillLevel("combat_endurance", 2f);
        officer.getStats().setSkillLevel("impact_mitigation", 2f);
        officer.getStats().setSkillLevel("damage_control", 2f);
        officer.getStats().setSkillLevel("field_modulation", 2f);
        officer.getStats().setSkillLevel("point_defense", 2f);
        officer.getStats().setSkillLevel("target_analysis", 2f);
        officer.getStats().setSkillLevel("ballistic_mastery", 2f);
        officer.getStats().setSkillLevel("systems_expertise", 2f);
        officer.getStats().setSkillLevel("missile_specialization", 2f);
        officer.getStats().setSkillLevel("gunnery_implants", 2f);
        officer.getStats().setSkillLevel("energy_weapon_mastery", 2f);
        officer.getStats().setSkillLevel("ordnance_expert", 2f);
        officer.getStats().setSkillLevel("polarized_armor", 2f);
    }

    /** 第一阶段僚机军官：南部响介技能包（重装突击取向）。 */
    private static void applyStage1KyosukeSkillPackage(PersonAPI officer) {
        if (officer == null) {
            return;
        }
        officer.getStats().setSkillLevel("helmsmanship", 2f);
        officer.getStats().setSkillLevel("combat_endurance", 2f);
        officer.getStats().setSkillLevel("impact_mitigation", 2f);
        officer.getStats().setSkillLevel("damage_control", 2f);
        officer.getStats().setSkillLevel("ballistic_mastery", 2f);
        officer.getStats().setSkillLevel("missile_specialization", 2f);
        officer.getStats().setSkillLevel("target_analysis", 2f);
        officer.getStats().setSkillLevel("systems_expertise", 2f);
        officer.getStats().setSkillLevel("polarized_armor", 2f);
        officer.getStats().setSkillLevel("ordnance_expert", 2f);
    }

    /** 第一阶段僚机军官：艾克塞琳·勃朗宁技能包（中远程火力取向）。 */
    private static void applyStage1ExcellenSkillPackage(PersonAPI officer) {
        if (officer == null) {
            return;
        }
        officer.getStats().setSkillLevel("helmsmanship", 2f);
        officer.getStats().setSkillLevel("combat_endurance", 2f);
        officer.getStats().setSkillLevel("field_modulation", 2f);
        officer.getStats().setSkillLevel("point_defense", 2f);
        officer.getStats().setSkillLevel("target_analysis", 2f);
        officer.getStats().setSkillLevel("energy_weapon_mastery", 2f);
        officer.getStats().setSkillLevel("missile_specialization", 2f);
        officer.getStats().setSkillLevel("gunnery_implants", 2f);
        officer.getStats().setSkillLevel("systems_expertise", 2f);
        officer.getStats().setSkillLevel("ordnance_expert", 2f);
    }

    /** 第一阶段僚机军官：阿莉艾尔·奥格技能包（机动/相位取向）。 */
    private static void applyStage1ArdeliaSkillPackage(PersonAPI officer) {
        if (officer == null) {
            return;
        }
        officer.getStats().setSkillLevel("helmsmanship", 2f);
        officer.getStats().setSkillLevel("combat_endurance", 2f);
        officer.getStats().setSkillLevel("field_modulation", 2f);
        officer.getStats().setSkillLevel("phase_corps", 1f);
        officer.getStats().setSkillLevel("target_analysis", 2f);
        officer.getStats().setSkillLevel("energy_weapon_mastery", 2f);
        officer.getStats().setSkillLevel("systems_expertise", 2f);
        officer.getStats().setSkillLevel("gunnery_implants", 2f);
        officer.getStats().setSkillLevel("ordnance_expert", 2f);
        officer.getStats().setSkillLevel("flux_regulation", 1f);
    }

    /** 第一阶段旗舰军官（独立函数，便于后续单独调整）。 */
    private static PersonAPI createStage1LefinaOfficer() {
        PersonAPI officer = createBaseOfficer(
                "moci_okita_stage1_lefina",
                "Lefina",
                "Enfield",
                FullName.Gender.FEMALE,
                14,
                "graphics/portraits/bounty/Moci_Lefina_new.png");
        applyLefinaSkillPackage(officer);
        return officer;
    }

    /** 第二阶段旗舰军官：古代守。 */
    private static PersonAPI createStage2MamoruOfficer() {
        PersonAPI officer = createBaseOfficer(
                "moci_okita_stage2_mamoru",
                "Kodai",
                "Mamoru",
                FullName.Gender.MALE,
                14,
                "graphics/portraits/characters/Moci_Kodai_Mamoru.png",
                Ranks.SPACE_CAPTAIN,
                Ranks.POST_FLEET_COMMANDER);
        applyLefinaSkillPackage(officer);
        return officer;
    }

    /** 第三阶段旗舰军官：古代进。 */
    private static PersonAPI createStage3SusumuOfficer() {
        PersonAPI officer = createBaseOfficer(
                "moci_okita_stage3_susumu",
                "Kodai",
                "Susumu",
                FullName.Gender.MALE,
                14,
                "graphics/portraits/characters/Moci_Kodai_Susumu.png",
                Ranks.SPACE_CAPTAIN,
                Ranks.POST_FLEET_COMMANDER);
        applyLefinaSkillPackage(officer);
        return officer;
    }

    /** 第一阶段僚机军官：南部响介。 */
    private static PersonAPI createStage1KyosukeOfficer() {
        PersonAPI officer = createBaseOfficer(
                "moci_okita_stage1_kyosuke",
                "南部",
                "响介",
                FullName.Gender.MALE,
                10,
                "graphics/portraits/characters/ATX_Kyosuke_Nanbu.png");
        applyStage1KyosukeSkillPackage(officer);
        return officer;
    }

    /** 第二阶段僚机军官：如月千岁。 */
    private static PersonAPI createStage2ChitoseOfficer() {
        PersonAPI officer = createBaseOfficer(
                "moci_okita_stage2_chitose",
                "Kisaragi",
                "Chitose",
                FullName.Gender.FEMALE,
                14,
                "graphics/portraits/gacha/CHITOSE.png",
                "MS_pilot",
                Ranks.POST_OFFICER);
        applyStage1KyosukeSkillPackage(officer);
        return officer;
    }

    /** 第三阶段僚机军官：村云总司。 */
    private static PersonAPI createStage3SojiOfficer() {
        PersonAPI officer = createBaseOfficer(
                "moci_okita_stage3_soji",
                "Murakumo",
                "Soji",
                FullName.Gender.MALE,
                14,
                "graphics/portraits/gacha/SOUJI.png",
                "MS_pilot",
                Ranks.POST_OFFICER);
        applyStage1KyosukeSkillPackage(officer);
        return officer;
    }

    /** 第一阶段僚机军官：艾克塞琳·勃朗宁。 */
    private static PersonAPI createStage1ExcellenOfficer() {
        PersonAPI officer = createBaseOfficer(
                "moci_okita_stage1_excellen",
                "艾克塞琳",
                "勃朗宁",
                FullName.Gender.FEMALE,
                10,
                "graphics/portraits/characters/ATX_Excellen_Browning.png");
        applyStage1ExcellenSkillPackage(officer);
        return officer;
    }

    /** 第一阶段僚机军官：阿莉艾尔·奥格。 */
    private static PersonAPI createStage1ArdeliaOfficer() {
        PersonAPI officer = createBaseOfficer(
                "moci_okita_stage1_ardelia",
                "阿莉艾尔",
                "奥格",
                FullName.Gender.FEMALE,
                10,
                "graphics/portraits/characters/ATX_Arriere_Orge.png");
        applyStage1ArdeliaSkillPackage(officer);
        return officer;
    }

    /** 第一阶段舰队配置（旗舰+3带军官僚机+4无军官僚机）。 */
    private static void configureStage1Fleet(CampaignFleetAPI fleet) {
        FleetMemberAPI flagship = addFleetMemberSafe(fleet, "Moci_Hiryu_Base");
        if (flagship != null) {
            flagship.setShipName("Hiryu");
            PersonAPI commander = createStage1LefinaOfficer();
            flagship.setCaptain(commander);
            flagship.setFlagship(true);
            fleet.setCommander(commander);
            makeAlwaysRecoverable(flagship);
        }

        FleetMemberAPI altEisen = addFleetMemberSafe(fleet, "Moci_AltEisen_base");
        if (altEisen != null) {
            altEisen.setCaptain(createStage1KyosukeOfficer());
            makeAlwaysRecoverable(altEisen);
        }

        FleetMemberAPI weissRitter = addFleetMemberSafe(fleet, "Moci_ReinWeissRitter_Base");
        if (weissRitter != null) {
            weissRitter.setCaptain(createStage1ExcellenOfficer());
            makeAlwaysRecoverable(weissRitter);
        }

        FleetMemberAPI flickereiGeist = addFleetMemberSafe(fleet, "Moci_FlickereiGeist_base");
        if (flickereiGeist != null) {
            flickereiGeist.setCaptain(createStage1ArdeliaOfficer());
            makeAlwaysRecoverable(flickereiGeist);
        }

        addFleetMemberSafe(fleet, "Moci_Gespenst_Elite");
        addFleetMemberSafe(fleet, "Moci_Gespenst_pl_Elite");
        addFleetMemberSafe(fleet, "Moci_Gespenst_tt_Elite");
        addFleetMemberSafe(fleet, "Moci_Gespenst_hg_Elite");
    }

    /** 第二阶段舰队配置。 */
    private static void configureStage2Fleet(CampaignFleetAPI fleet) {
        FleetMemberAPI flagship = addFleetMemberSafe(fleet, "Moci_Yamato_ET_Base");
        if (flagship != null) {
            PersonAPI commander = createStage2MamoruOfficer();
            flagship.setCaptain(commander);
            flagship.setFlagship(true);
            fleet.setCommander(commander);
            makeAlwaysRecoverable(flagship);
        }

        FleetMemberAPI gespenst = addFleetMemberSafe(fleet, "Moci_Gespenst_Elite");
        if (gespenst != null) {
            gespenst.setCaptain(createStage2ChitoseOfficer());
            makeAlwaysRecoverable(gespenst);
        }

        addIndependentRandomEscorts(fleet, FleetTypes.PATROL_MEDIUM, INDEPENDENT_MEDIUM_ESCORT_FRACTION);
    }

    /** 第三阶段舰队配置。 */
    private static void configureStage3Fleet(CampaignFleetAPI fleet) {
        FleetMemberAPI flagship = addFleetMemberSafe(fleet, "Moci_Syunran_Base");
        if (flagship != null) {
            PersonAPI commander = createStage3SusumuOfficer();
            flagship.setCaptain(commander);
            flagship.setFlagship(true);
            fleet.setCommander(commander);
            makeAlwaysRecoverable(flagship);
        }

        FleetMemberAPI gespenst = addFleetMemberSafe(fleet, "Moci_Gespenst_Elite");
        if (gespenst != null) {
            gespenst.setCaptain(createStage3SojiOfficer());
            makeAlwaysRecoverable(gespenst);
        }

        addIndependentRandomEscorts(fleet, FleetTypes.PATROL_LARGE, INDEPENDENT_LARGE_ESCORT_FRACTION);
    }

    /**
     * 从全星域挑选一个“合格星门”作为刷新点。
     *
     * 权重策略：离核心越远权重越高（更偏向边缘区，符合任务氛围）。
     */
    private static SectorEntityToken pickGateForMissionSpawn() {
        WeightedRandomPicker<SectorEntityToken> picker = new WeightedRandomPicker<>();

        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (system.getTags().contains(Tags.THEME_CORE)) {
                continue;
            }
            if (system.getTags().contains(Tags.THEME_HIDDEN)) {
                continue;
            }
            if (system.getTags().contains(Tags.SYSTEM_CUT_OFF_FROM_HYPER)) {
                continue;
            }

            for (SectorEntityToken entity : system.getCustomEntities()) {
                if (!isGateEntity(entity)) {
                    continue;
                }
                float distance = (float) Math.sqrt(system.getLocation().lengthSquared());
                float weight = Math.max(1f, distance / 2500f);
                picker.add(entity, weight);
            }
        }

        // 兜底1：允许核心区星门，避免“无任务目标”。
        if (picker.isEmpty()) {
            for (StarSystemAPI system : Global.getSector().getStarSystems()) {
                if (system.getTags().contains(Tags.THEME_HIDDEN)) {
                    continue;
                }
                for (SectorEntityToken entity : system.getCustomEntities()) {
                    if (!isGateEntity(entity)) {
                        continue;
                    }
                    picker.add(entity, 1f);
                }
            }
        }

        // 兜底2：任意可识别 gate 实体（最后保险）。
        if (picker.isEmpty()) {
            for (StarSystemAPI system : Global.getSector().getStarSystems()) {
                for (SectorEntityToken entity : system.getCustomEntities()) {
                    if (!isGateEntity(entity)) {
                        continue;
                    }
                    picker.add(entity, 1f);
                }
            }
        }
        return picker.pick();
    }

    /** 判断实体是否可作为 gate 刷新点。 */
    private static boolean isGateEntity(SectorEntityToken entity) {
        if (entity == null) {
            return false;
        }
        if (entity.hasTag(Tags.GATE)) {
            return true;
        }
        String customType = entity.getCustomEntityType();
        if (customType == null) {
            return false;
        }
        return "inactive_gate".equals(customType)
                || "active_gate".equals(customType)
                || customType.contains("gate");
    }

    /** 根据阶段返回任务显示名。 */
    private String getMissionName() {
        if (stage == 1) {
            return text(MISSION1_NAME);
        } else if (stage == 2) {
            return text(MISSION2_NAME);
        } else if (stage == 3) {
            return text(MISSION3_NAME);
        }
        return text("common.unknown_mission");
    }

    /**
     * 自定义战斗配置：
     * - 禁止 AI 撤退；
     * - 禁止占点目标；
     * - 敌方全员部署；
     * - 战至最后。
     */
    private static class MissionFIDConfig implements FleetInteractionDialogPluginImpl.FIDConfigGen {
        @Override
        public FleetInteractionDialogPluginImpl.FIDConfig createConfig() {
            FleetInteractionDialogPluginImpl.FIDConfig config = new FleetInteractionDialogPluginImpl.FIDConfig();
            config.alwaysPursue = true;
            config.withSalvage = true;
            config.printXPToDialog = true;
            config.delegate = new FleetInteractionDialogPluginImpl.BaseFIDDelegate() {
                @Override
                public void battleContextCreated(InteractionDialogAPI dialog, BattleCreationContext bcc) {
                    bcc.aiRetreatAllowed = false;
                    bcc.objectivesAllowed = false;
                    bcc.enemyDeployAll = true;
                    bcc.fightToTheLast = true;
                }
            };
            return config;
        }
    }

    /** 暂停时不推进任务状态。 */
    @Override
    public boolean runWhilePaused() {
        return false;
    }

    /** Intel 列表短摘要。 */
    @Override
    public void addBulletPoints(TooltipMakerAPI info, IntelInfoPlugin.ListInfoMode mode, boolean isUpdate, Color tc, float initPad) {
        Color h = Misc.getHighlightColor();
        bullet(info);
        if (state == State.stage_1) {
            String place = targetGate != null ? targetGate.getName() : text("common.some_gate");
            info.addPara(formatText("okita_mission.bullet_active", Moci_TextLoader.mapOf("place", place)), initPad, tc, h, place);
        } else {
            info.addPara(text("common.mission_completed"), initPad, tc, h);
        }
        unindent(info);
    }

    /** Intel 列表项标题 + 摘要。 */
    @Override
    public void createIntelInfo(TooltipMakerAPI info, IntelInfoPlugin.ListInfoMode mode) {
        info.addPara(getName(), getTitleColor(mode), 0f);
        addBulletPoints(info, mode);
    }

    @Override
    public String getSortString() {
        return getName();
    }

    /** Intel 标题文本。 */
    @Override
    public String getName() {
        return getMissionName();
    }

    /** Intel UI 配色派系（玩家派系色）。 */
    @Override
    public FactionAPI getFactionForUIColors() {
        return Global.getSector().getPlayerFaction();
    }

    /** Intel 详情面板文本。 */
    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        Color h = Misc.getHighlightColor();
        float pad = 10f;

        info.addSectionHeading(text("common.section_summary"), Alignment.MID, pad);
        if (stage == 1) {
            info.addPara(text("okita_mission.desc_stage1"), pad, h);
        } else if (stage == 2) {
            info.addPara(text("okita_mission.desc_stage2"), pad, h);
        } else {
            info.addPara(text("okita_mission.desc_stage3"), pad, h);
        }

        info.addSectionHeading(text("common.section_status"), Alignment.MID, pad);
        if (state == State.stage_1) {
            String place = targetGate != null ? targetGate.getName() : text("common.some_gate");
            LabelAPI l = info.addPara(formatText("common.target_location", Moci_TextLoader.mapOf("place", place)), pad, h, place);
            l.setHighlight(place);
        } else {
            info.addPara(text("okita_mission.status_completed"), pad, h);
        }
    }

    private static String text(String key) {
        return Moci_TextLoader.getText(TEXT_ID, key);
    }

    private static String formatText(String key, java.util.Map<String, String> replacements) {
        return Moci_TextLoader.getTextWithReplacements(TEXT_ID, key, replacements);
    }

    /** Intel 图标。 */
    @Override
    public String getIcon() {
        // 优先使用当前阶段目标舰队指挥官（旗舰军官）头像作为任务图标。
        if (targetFleet != null && targetFleet.getCommander() != null) {
            String portrait = targetFleet.getCommander().getPortraitSprite();
            if (portrait != null && !portrait.trim().isEmpty()) {
                return portrait;
            }
        }
        // 回退：使用稳定存在的模组资源，避免贴图缺失导致报错。
        return "graphics/hullmods/Moci_CombatSquad.png";
    }

    /** Intel 标签，用于过滤分类。 */
    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add("Moci");
        tags.add("Tulip");
        return tags;
    }

    /** 星图定位：优先显示任务舰队，其次显示目标星门。 */
    @Override
    public SectorEntityToken getMapLocation(SectorMapAPI map) {
        if (targetFleet != null) {
            return targetFleet;
        }
        return targetGate;
    }
}
