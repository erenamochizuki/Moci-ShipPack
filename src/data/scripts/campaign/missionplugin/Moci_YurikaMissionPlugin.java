package data.scripts.campaign.missionplugin;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.SpecialItemSpecAPI;
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
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
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
import data.scripts.impl.Moci_CampaignTime;
import data.scripts.impl.Moci_NergalHeavyIndustriesColonyManager;
import data.scripts.impl.Moci_YurikaStoryManager;
import data.scripts.util.Moci_TextLoader;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Moci_YurikaMissionPlugin extends BaseIntelPlugin {
    private static final String TEXT_ID = "Moci_CampaignText";
    private static final String INTEL_TAG_MOCI = "Moci";
    private static final String INTEL_TAG_NERGAL = "Nergal";
    private static final String INTEL_TAG_NERGAL_CN = "尼尔加重工";
    private static final String INTEL_TAG_IMPORTANT_CN = "重要";

    public enum MissionType {
        REMNANT,
        STORY_A,
        STORY_B,
        STORY_TDD
    }

    private enum State {
        ACTIVE,
        COMPLETED
    }

    private static final String REMNANT_FACTION = Factions.REMNANTS;
    private static final String STORY_FACTION = "ML_bounty";
    private static final String RUNNING_PREFIX = "$moci_yurika_mission_running_";
    private static final String LOTUS_ENTITY_ID = "moci_nergal_lotus";
    private static final String LOTUS_SYSTEM_NAME = "Helianthus";
    private static final String VORTEX_SYSTEM_NAME = "Vortex";
    private static final String RANK_LIEUTENANT_COMMANDER = "Moci_spaceLieutenantCommander";
    private static final String PORTRAIT_YURIKA = "graphics/portraits/characters/Moci_Yurika.png";
    private static final String PORTRAIT_RURI = "graphics/portraits/bounty/Hoshino_Ruri.png";
    private static final String PORTRAIT_TERESA = "graphics/portraits/characters/Moci_Teresa_Testarossa.png";
    private static final float PREFERRED_SPAWN_RANGE_LY = 10f;
    private static final float[] SPAWN_RANGE_STEPS_LY = new float[]{10f, 20f, 30f};

    private static final int REMNANT_BASE_CREDITS = 80000;
    private static final int REMNANT_PER_COUNT_BONUS = 20000;
    private static final int REMNANT_MAX_BONUS = 4;
    private static final int REMNANT_AFFINITY_GAIN = 10;
    private static final int REMNANT_ADVANCED_START_COUNT = 4;
    private static final int REMNANT_ADVANCED_BASE_MIN_FP = 100;
    private static final int REMNANT_ADVANCED_MIN_FP_PER_YEAR = 80;
    private static final int REMNANT_ADVANCED_BASE_MAX_FP = 200;
    private static final int REMNANT_ADVANCED_MAX_FP_PER_YEAR = 160;
    private static final int REMNANT_ADVANCED_FP_CAP = 1000;
    private static final int REMNANT_ADVANCED_CREDITS_PER_FP = 2000;
    private static final double REMNANT_OMEGA_CHANCE = 0.05d;

    private final MissionType missionType;
    private final String importantKey;

    private State state = State.ACTIVE;
    private CampaignFleetAPI targetFleet;
    private SectorEntityToken targetGate;
    private int remnantTargetFp;
    private boolean remnantOmegaFleet;
    private boolean remnantFleetSelectionMade;
    private final IntervalUtil stateCheckInterval = new IntervalUtil(1.25f, 1.25f);

    public Moci_YurikaMissionPlugin(InteractionDialogAPI dialog, MissionType missionType) {
        this.missionType = missionType;
        this.importantKey = "moci_yurika_mission_" + missionType.name().toLowerCase();
        setImportant(true);
        spawnMissionFleet();
        if (!hasSpawnedMissionTarget()) {
            setRunning(false);
            if (dialog != null && dialog.getTextPanel() != null) {
                dialog.getTextPanel().addPara(text("yurika_mission.start_failed"), Misc.getNegativeHighlightColor());
            }
            return;
        }
        Global.getSector().addScript(this);
        if (dialog == null) {
            Global.getSector().getIntelManager().addIntel(this, false);
        } else {
            Global.getSector().getIntelManager().addIntel(this, false, dialog.getTextPanel());
        }
    }

    public static boolean startMission(InteractionDialogAPI dialog, MissionType missionType) {
        if (missionType == null) {
            return false;
        }
        if (hasActiveMissionForType(missionType)) {
            return true;
        }
        Moci_YurikaMissionPlugin mission = new Moci_YurikaMissionPlugin(dialog, missionType);
        if (!mission.hasSpawnedMissionTarget()) {
            mission.setRunning(false);
            return false;
        }
        return true;
    }

    public static boolean hasActiveMissionForType(MissionType missionType) {
        if (missionType == null || Global.getSector() == null || Global.getSector().getIntelManager() == null) {
            return false;
        }
        for (IntelInfoPlugin intel : Global.getSector().getIntelManager().getIntel(Moci_YurikaMissionPlugin.class)) {
            Moci_YurikaMissionPlugin mission = (Moci_YurikaMissionPlugin) intel;
            if (mission.missionType == missionType && mission.isMissionActive()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasSpawnedMissionTarget() {
        return targetGate != null && targetFleet != null;
    }

    public boolean isMissionActive() {
        return state == State.ACTIVE && hasSpawnedMissionTarget();
    }

    @Override
    protected void advanceImpl(float amount) {
        if (state != State.ACTIVE) {
            return;
        }
        stateCheckInterval.advance(amount);
        if (!stateCheckInterval.intervalElapsed()) {
            return;
        }
        if (targetFleet == null) {
            spawnMissionFleet();
            return;
        }
        if (targetFleet.isEmpty()) {
            completeMission();
        }
    }

    private void spawnMissionFleet() {
        if (targetFleet != null) {
            return;
        }

        SectorEntityToken gate = pickGateForMissionSpawn();
        if (gate == null) {
            return;
        }

        CampaignFleetAPI fleet = null;
        boolean advancedRemnant = false;
        if (missionType == MissionType.REMNANT
                && Moci_YurikaStoryManager.getRemnantJobCount() >= REMNANT_ADVANCED_START_COUNT) {
            if (!remnantFleetSelectionMade) {
                float elapsedYears = Moci_CampaignTime.getElapsedYears();
                int minFp = getRemnantMinFp(elapsedYears);
                int maxFp = getRemnantMaxFp(elapsedYears);
                // Lock the scale at acceptance so time spent travelling cannot change the payout.
                remnantTargetFp = minFp + (int) (Math.random() * (maxFp - minFp + 1));
                remnantOmegaFleet = Math.random() < REMNANT_OMEGA_CHANCE;
                remnantFleetSelectionMade = true;
            }
            String factionId = remnantOmegaFleet ? Factions.OMEGA : REMNANT_FACTION;
            fleet = createScaledRemnantFleet(gate, factionId, remnantTargetFp);
            advancedRemnant = fleet != null && !fleet.isEmpty();
            if (!advancedRemnant) {
                remnantTargetFp = 0;
                remnantOmegaFleet = false;
            }
        }
        if (fleet == null || fleet.isEmpty()) {
            fleet = FleetFactoryV3.createEmptyFleet(
                    missionType == MissionType.REMNANT ? REMNANT_FACTION : STORY_FACTION,
                    missionType == MissionType.REMNANT ? FleetTypes.PATROL_MEDIUM : FleetTypes.PATROL_LARGE,
                    null
            );
        }
        if (fleet == null) {
            return;
        }

        fleet.setId("moci_yurika_" + missionType.name().toLowerCase() + "_" + Misc.genUID());
        fleet.setName(getFleetName());
        fleet.setNoFactionInName(true);
        fleet.setNoAutoDespawn(true);
        fleet.setTransponderOn(false);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_HOSTILE, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_ALWAYS_PURSUE, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_NO_REP_IMPACT, true);
        fleet.getMemoryWithoutUpdate().set(
                MemFlags.FLEET_INTERACTION_DIALOG_CONFIG_OVERRIDE_GEN,
                new MissionFIDConfig()
        );

        if (missionType == MissionType.REMNANT && !advancedRemnant) {
            configureRemnantFleet(fleet);
        } else if (missionType != MissionType.REMNANT) {
            configureStoryFleet(fleet);
        }

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

        targetGate = gate;
        targetFleet = fleet;
        Misc.makeImportant(fleet, importantKey);
        setRunning(true);
    }

    private CampaignFleetAPI createScaledRemnantFleet(SectorEntityToken gate, String factionId, int targetFp) {
        LocationAPI location = gate.getContainingLocation();
        Vector2f systemLocation = location instanceof StarSystemAPI ? ((StarSystemAPI) location).getLocation() : null;
        FleetParamsV3 params = new FleetParamsV3(
                systemLocation,
                factionId,
                1f,
                factionId.equals(Factions.OMEGA) ? FleetTypes.PATROL_LARGE : FleetTypes.PATROL_MEDIUM,
                targetFp,
                0f, 0f, 0f, 0f, 0f, 0f
        );
        params.ignoreMarketFleetSizeMult = true;
        params.withOfficers = true;
        // Preserve the requested scale even when late-game fleets exceed the normal AI ship count.
        params.doNotPrune = true;
        return FleetFactoryV3.createFleet(params);
    }

    private void configureRemnantFleet(CampaignFleetAPI fleet) {
        int completedJobs = Moci_YurikaStoryManager.getRemnantJobCount();
        addFleetMemberSafe(fleet, "glimmer_Assault");
        addFleetMemberSafe(fleet, "glimmer_Support");
        addFleetMemberSafe(fleet, "lumen_Standard");
        if (completedJobs >= 1) {
            addFleetMemberSafe(fleet, "lumen_Standard");
        }
        if (completedJobs >= 2) {
            addFleetMemberSafe(fleet, "brilliant_Standard");
        }
        if (completedJobs >= 4) {
            addFleetMemberSafe(fleet, "brilliant_Support");
        }
    }

    private void configureStoryFleet(CampaignFleetAPI fleet) {
        if (missionType == MissionType.STORY_A) {
            FleetMemberAPI flagship = addFleetMemberSafe(fleet, "Moci_Nadesico_A_base");
            if (flagship != null) {
                flagship.setShipName("ND-001A");
                PersonAPI commander = createStoryAYurikaOfficer();
                flagship.setCaptain(commander);
                flagship.setFlagship(true);
                fleet.setCommander(commander);
                makeAlwaysRecoverable(flagship);
            }
            FleetMemberAPI hikari = addFleetMemberSafe(fleet, "Moci_Aestivalis_HIKARI_base");
            if (hikari != null) {
                makeAlwaysRecoverable(hikari);
            }
            FleetMemberAPI izumi = addFleetMemberSafe(fleet, "Moci_Aestivalis_IZUMI_base");
            if (izumi != null) {
                makeAlwaysRecoverable(izumi);
            }
        } else if (missionType == MissionType.STORY_B) {
            FleetMemberAPI flagship = addFleetMemberSafe(fleet, "Moci_Nadesico_Base");
            if (flagship != null) {
                flagship.setShipName("NS-955B");
                PersonAPI commander = createStoryBRuriOfficer();
                flagship.setCaptain(commander);
                flagship.setFlagship(true);
                fleet.setCommander(commander);
                makeAlwaysRecoverable(flagship);
            }
            FleetMemberAPI hikari = addFleetMemberSafe(fleet, "Moci_Aestivalis_HIKARI_base");
            if (hikari != null) {
                makeAlwaysRecoverable(hikari);
            }
            FleetMemberAPI izumi = addFleetMemberSafe(fleet, "Moci_Aestivalis_IZUMI_base");
            if (izumi != null) {
                makeAlwaysRecoverable(izumi);
            }
        } else if (missionType == MissionType.STORY_TDD) {
            FleetMemberAPI flagship = addFleetMemberSafe(fleet, "Moci_TDD_1_Base");
            if (flagship != null) {
                flagship.setShipName("TDD-1");
                PersonAPI commander = createStoryTddTeresaOfficer();
                flagship.setCaptain(commander);
                flagship.setFlagship(true);
                fleet.setCommander(commander);
                makeAlwaysRecoverable(flagship);
            }
        }
    }

    private PersonAPI createOfficer(String id, String first, String last, FullName.Gender gender, String portrait, int level) {
        return createOfficer(id, first, last, gender, portrait, level, Ranks.SPACE_COMMANDER, Ranks.POST_FLEET_COMMANDER);
    }

    private PersonAPI createOfficer(String id, String first, String last, FullName.Gender gender, String portrait, int level,
                                    String rankId, String postId) {
        FactionAPI faction = Global.getSector().getFaction(STORY_FACTION);
        if (faction == null) {
            faction = Global.getSector().getFaction(Factions.INDEPENDENT);
        }
        PersonAPI person = faction.createRandomPerson();
        person.setId(id);
        person.getName().setFirst(first);
        person.getName().setLast(last);
        person.setGender(gender);
        person.setPortraitSprite(portrait);
        person.setRankId(rankId);
        person.setPostId(postId);
        person.setPersonality(Personalities.STEADY);
        person.getStats().setLevel(level);
        return person;
    }

    private static void applyStoryFlagshipSkillPackage(PersonAPI officer) {
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

    private PersonAPI createStoryAYurikaOfficer() {
        PersonAPI officer = createOfficer(
                "moci_yurika_story_a_yurika",
                "Yurika",
                "Misumaru",
                FullName.Gender.FEMALE,
                PORTRAIT_YURIKA,
                14,
                Ranks.SPACE_CAPTAIN,
                Ranks.POST_FLEET_COMMANDER
        );
        applyStoryFlagshipSkillPackage(officer);
        return officer;
    }

    private PersonAPI createStoryBRuriOfficer() {
        PersonAPI officer = createOfficer(
                "moci_yurika_story_b_ruri",
                "Ruri",
                "Hoshino",
                FullName.Gender.FEMALE,
                PORTRAIT_RURI,
                16,
                RANK_LIEUTENANT_COMMANDER,
                Ranks.POST_FLEET_COMMANDER
        );
        applyStoryFlagshipSkillPackage(officer);
        return officer;
    }

    private PersonAPI createStoryTddTeresaOfficer() {
        PersonAPI officer = createOfficer(
                "moci_yurika_story_tdd_teresa",
                "Teresa",
                "Testarossa",
                FullName.Gender.FEMALE,
                PORTRAIT_TERESA,
                16,
                RANK_LIEUTENANT_COMMANDER,
                Ranks.POST_FLEET_COMMANDER
        );
        applyStoryFlagshipSkillPackage(officer);
        return officer;
    }

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

    private static void makeAlwaysRecoverable(FleetMemberAPI member) {
        MociRecoverableShipHelper.ensureMemberVariantAlwaysRecoverable(member);
    }

    private void enforceCommanderOnFlagship(CampaignFleetAPI fleet) {
        MociFleetFlagshipHelper.syncFlagshipAndCommander(fleet);
    }

    private SectorEntityToken pickGateForMissionSpawn() {
        Vector2f lotusLocation = getLotusHyperspaceLocation();

        if (missionType == MissionType.REMNANT) {
            for (float rangeLY : SPAWN_RANGE_STEPS_LY) {
                SectorEntityToken gate = pickGateWithinRange(lotusLocation, rangeLY, true);
                if (gate != null) {
                    return gate;
                }
                gate = pickGateWithinRange(lotusLocation, rangeLY, false);
                if (gate != null) {
                    return gate;
                }
            }
            SectorEntityToken gate = pickGateWithinRange(lotusLocation, Float.MAX_VALUE, true);
            if (gate != null) {
                return gate;
            }
            gate = pickGateWithinRange(lotusLocation, Float.MAX_VALUE, false);
            if (gate != null) {
                return gate;
            }
        } else {
            for (float rangeLY : SPAWN_RANGE_STEPS_LY) {
                SectorEntityToken gate = pickGateWithinRange(lotusLocation, rangeLY, false);
                if (gate != null) {
                    return gate;
                }
            }
            SectorEntityToken gate = pickGateWithinRange(lotusLocation, Float.MAX_VALUE, false);
            if (gate != null) {
                return gate;
            }
        }

        WeightedRandomPicker<SectorEntityToken> emergency = new WeightedRandomPicker<>();
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (isExcludedSpawnSystem(system, false)) {
                continue;
            }
            for (SectorEntityToken entity : system.getCustomEntities()) {
                if (!isGateEntity(entity)) {
                    continue;
                }
                emergency.add(entity, 1f);
            }
        }
        return emergency.pick();
    }

    private SectorEntityToken pickGateWithinRange(Vector2f lotusLocation, float maxRangeLY, boolean remnantOnly) {
        WeightedRandomPicker<SectorEntityToken> picker = new WeightedRandomPicker<>();
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (isExcludedSpawnSystem(system, remnantOnly)) {
                continue;
            }
            if (lotusLocation != null && maxRangeLY < Float.MAX_VALUE) {
                float distanceLY = Misc.getDistanceLY(lotusLocation, system.getLocation());
                if (distanceLY > maxRangeLY) {
                    continue;
                }
            }
            float systemWeight = getRangeWeight(system, lotusLocation, maxRangeLY);
            if (systemWeight <= 0f) {
                continue;
            }
            for (SectorEntityToken entity : system.getCustomEntities()) {
                if (!isGateEntity(entity)) {
                    continue;
                }
                picker.add(entity, systemWeight);
            }
        }
        return picker.pick();
    }

    private float getRangeWeight(StarSystemAPI system, Vector2f lotusLocation, float maxRangeLY) {
        if (system == null) {
            return 0f;
        }
        float distanceLY;
        if (lotusLocation != null) {
            distanceLY = Misc.getDistanceLY(lotusLocation, system.getLocation());
        } else {
            distanceLY = (float) Math.sqrt(system.getLocation().lengthSquared()) / Misc.getUnitsPerLightYear();
        }

        if (maxRangeLY < Float.MAX_VALUE && distanceLY > maxRangeLY) {
            return 0f;
        }

        float effectiveRange = maxRangeLY < Float.MAX_VALUE ? Math.max(1f, maxRangeLY) : Math.max(30f, distanceLY);
        return 1f + Math.max(0f, effectiveRange - distanceLY) / effectiveRange;
    }

    private boolean isExcludedSpawnSystem(StarSystemAPI system, boolean remnantOnly) {
        if (system == null) {
            return true;
        }
        if (system.getTags().contains(Tags.THEME_HIDDEN)) {
            return true;
        }
        if (system.getTags().contains(Tags.SYSTEM_CUT_OFF_FROM_HYPER)) {
            return true;
        }
        if (VORTEX_SYSTEM_NAME.equals(system.getBaseName()) || VORTEX_SYSTEM_NAME.equals(system.getName())) {
            return true;
        }
        if (remnantOnly && !isRemnantActivitySystem(system)) {
            return true;
        }
        return false;
    }

    private boolean isRemnantActivitySystem(StarSystemAPI system) {
        if (system == null) {
            return false;
        }
        if (system.hasTag(Tags.THEME_REMNANT_NO_FLEETS) || system.hasTag(Tags.THEME_REMNANT_DESTROYED)) {
            return false;
        }
        return system.hasTag(Tags.THEME_REMNANT)
                || system.hasTag(Tags.THEME_REMNANT_MAIN)
                || system.hasTag(Tags.THEME_REMNANT_SECONDARY)
                || system.hasTag(Tags.THEME_REMNANT_SUPPRESSED)
                || system.hasTag(Tags.THEME_REMNANT_RESURGENT);
    }

    private Vector2f getLotusHyperspaceLocation() {
        if (Global.getSector() == null) {
            return null;
        }

        SectorEntityToken lotus = Global.getSector().getEntityById(LOTUS_ENTITY_ID);
        if (lotus != null && lotus.getContainingLocation() instanceof StarSystemAPI) {
            StarSystemAPI system = (StarSystemAPI) lotus.getContainingLocation();
            return system.getLocation();
        }

        StarSystemAPI system = Global.getSector().getStarSystem(LOTUS_SYSTEM_NAME);
        if (system != null) {
            return system.getLocation();
        }
        return null;
    }

    private boolean isGateEntity(SectorEntityToken entity) {
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
        String lower = customType.toLowerCase();
        return "inactive_gate".equals(lower) || "active_gate".equals(lower) || lower.contains("gate");
    }

    private void completeMission() {
        if (state == State.COMPLETED) {
            return;
        }
        state = State.COMPLETED;

        if (targetFleet != null) {
            Misc.makeUnimportant(targetFleet, importantKey);
        }

        String rewardSummary = grantReward();
        setImportant(false);
        setRunning(false);
        endAfterDelay(3f);

        Moci_CampaignFeedback.addMissionCompletedMessage(getMissionName(), rewardSummary);
    }

    private String grantReward() {
        CargoAPI cargo = Global.getSector().getPlayerFleet().getCargo();

        if (missionType == MissionType.REMNANT) {
            int completed = Moci_YurikaStoryManager.getRemnantJobCount();
            float reward;
            String rewardSummary;
            String reportKey = "yurika_mission.report_remnant_completed";
            if (completed < REMNANT_ADVANCED_START_COUNT || remnantTargetFp <= 0) {
                reward = REMNANT_BASE_CREDITS + Math.min(REMNANT_MAX_BONUS, completed) * REMNANT_PER_COUNT_BONUS;
                cargo.getCredits().add(reward);
                rewardSummary = formatText("yurika_mission.reward_credits",
                        Moci_TextLoader.mapOf("credits", Misc.getWithDGS((int) reward)));
            } else {
                reward = (float) remnantTargetFp * REMNANT_ADVANCED_CREDITS_PER_FP;
                cargo.getCredits().add(reward);
                if (remnantOmegaFleet) {
                    SpecialItemSpecAPI artifact = pickVanillaArtifact();
                    if (artifact != null) cargo.addSpecial(new SpecialItemData(artifact.getId(), null), 1);
                    rewardSummary = formatText("yurika_mission.reward_omega",
                            Moci_TextLoader.mapOf("fleetFp", String.valueOf(remnantTargetFp),
                                    "credits", Misc.getWithDGS((int) reward),
                                    "artifact", artifact == null ? text("yurika_mission.reward_artifact_fallback") : artifact.getName()));
                    reportKey = "yurika_mission.report_remnant_omega_completed";
                } else {
                    String coreId = getAICoreRewardId(remnantTargetFp);
                    int coreCount = getAICoreRewardCount(remnantTargetFp);
                    cargo.addCommodity(coreId, coreCount);
                    String coreName = text("yurika_mission.core_" + coreId);
                    rewardSummary = formatText("yurika_mission.reward_advanced",
                            Moci_TextLoader.mapOf("fleetFp", String.valueOf(remnantTargetFp),
                                    "credits", Misc.getWithDGS((int) reward), "core", coreName,
                                    "count", String.valueOf(coreCount)));
                    reportKey = "yurika_mission.report_remnant_advanced_completed";
                }
            }
            float affinity = Moci_YurikaStoryManager.completeRemnantJob(REMNANT_AFFINITY_GAIN);
            Moci_YurikaStoryManager.setPendingReport(
                    formatText(reportKey, Moci_TextLoader.mapOf("reward", rewardSummary,
                            "amount", String.valueOf(REMNANT_AFFINITY_GAIN), "affinity", String.valueOf((int) affinity))), false);
            return rewardSummary;
        }

        if (missionType == MissionType.STORY_A) {
            cargo.getCredits().add(120000f);
            cargo.addHullmods("Moci_GravityReceiver", 1);
            cargo.addWeapons("Moci_split_missile", 2);
            Moci_YurikaStoryManager.markStoryCompleted(
                    Moci_YurikaStoryManager.StoryMissionType.NADE_A,
                    text("yurika_mission.report_story_a_completed"),
                    false
            );
            Moci_NergalHeavyIndustriesColonyManager.syncLotusStoryContacts();
        } else if (missionType == MissionType.STORY_B) {
            cargo.getCredits().add(220000f);
            cargo.addHullmods("Moci_GravityAntenna", 1);
            cargo.addWeapons("Moci_split_missile", 4);
            Moci_YurikaStoryManager.markStoryCompleted(
                    Moci_YurikaStoryManager.StoryMissionType.NADE_B,
                    text("yurika_mission.report_story_b_completed"),
                    false
            );
        } else if (missionType == MissionType.STORY_TDD) {
            cargo.getCredits().add(350000f);
            cargo.addWeapons("Moci_heavry_phasecl", 2);
            cargo.addWeapons("Moci_split_missile", 6);
            cargo.addSupplies(200f);
            Moci_YurikaStoryManager.markStoryCompleted(
                    Moci_YurikaStoryManager.StoryMissionType.TDD,
                    text("yurika_mission.report_story_tdd_completed"),
                    false
            );
        }
        return null;
    }

    static int getRemnantMinFp(float elapsedYears) {
        return Math.min(REMNANT_ADVANCED_FP_CAP, Math.round(REMNANT_ADVANCED_BASE_MIN_FP
                + Math.max(0f, elapsedYears) * REMNANT_ADVANCED_MIN_FP_PER_YEAR));
    }

    static int getRemnantMaxFp(float elapsedYears) {
        return Math.min(REMNANT_ADVANCED_FP_CAP, Math.round(REMNANT_ADVANCED_BASE_MAX_FP
                + Math.max(0f, elapsedYears) * REMNANT_ADVANCED_MAX_FP_PER_YEAR));
    }

    static String getAICoreRewardId(int fleetFp) {
        if (fleetFp >= 400) return "alpha_core";
        if (fleetFp >= 200) return "beta_core";
        return "gamma_core";
    }

    static int getAICoreRewardCount(int fleetFp) {
        return Math.max(1, fleetFp / 200 - 1);
    }

    private SpecialItemSpecAPI pickVanillaArtifact() {
        List<SpecialItemSpecAPI> candidates = new ArrayList<>();
        for (SpecialItemSpecAPI spec : Global.getSettings().getAllSpecialItemSpecs()) {
            if (spec.getSourceMod() == null && spec.hasTag("codex_unlockable")
                    && !spec.hasTag("mission_item") && !spec.hasTag("no_drop")
                    && !spec.hasTag("single_bp")) candidates.add(spec);
        }
        if (!candidates.isEmpty()) return candidates.get((int) (Math.random() * candidates.size()));
        SpecialItemSpecAPI fallback = Global.getSettings().getSpecialItemSpec("pristine_nanoforge");
        return fallback != null && fallback.getSourceMod() == null ? fallback : null;
    }

    private void setRunning(boolean running) {
        Global.getSector().getMemoryWithoutUpdate().set(RUNNING_PREFIX + missionType.name().toLowerCase(), running);
    }

    private String getFleetName() {
        switch (missionType) {
            case REMNANT:
                return remnantOmegaFleet ? text("yurika_mission.fleet_omega") : text("yurika_mission.fleet_remnant");
            case STORY_A:
                return text("yurika_mission.fleet_story_a");
            case STORY_B:
                return text("yurika_mission.fleet_story_b");
            case STORY_TDD:
                return text("yurika_mission.fleet_story_tdd");
            default:
                return text("common.unknown_fleet");
        }
    }

    private String getMissionName() {
        switch (missionType) {
            case REMNANT:
                return text("yurika_mission.name_remnant");
            case STORY_A:
                return text("yurika_mission.name_story_a");
            case STORY_B:
                return text("yurika_mission.name_story_b");
            case STORY_TDD:
                return text("yurika_mission.name_story_tdd");
            default:
                return text("common.unknown_mission");
        }
    }

    private String getMissionDescription() {
        switch (missionType) {
            case REMNANT:
                return text("yurika_mission.desc_remnant");
            case STORY_A:
                return text("yurika_mission.desc_story_a");
            case STORY_B:
                return text("yurika_mission.desc_story_b");
            case STORY_TDD:
                return text("yurika_mission.desc_story_tdd");
            default:
                return "";
        }
    }

    @Override
    public boolean runWhilePaused() {
        return false;
    }

    @Override
    public void addBulletPoints(TooltipMakerAPI info, IntelInfoPlugin.ListInfoMode mode, boolean isUpdate, Color tc, float initPad) {
        Color h = Misc.getHighlightColor();
        if (state == State.ACTIVE) {
            String place = targetGate != null ? targetGate.getName() : text("common.some_gate");
            info.addPara(formatText("yurika_mission.bullet_active", Moci_TextLoader.mapOf("place", place)), initPad, tc, h, place);
        } else {
            info.addPara(text("common.mission_completed"), initPad, tc, h);
        }
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, IntelInfoPlugin.ListInfoMode mode) {
        info.addPara(getName(), getTitleColor(mode), 0f);
        addBulletPoints(info, mode, false, getBulletColorForMode(mode), 0f);
    }

    @Override
    public String getName() {
        return getMissionName();
    }

    @Override
    public String getIcon() {
        if (targetFleet != null && targetFleet.getCommander() != null) {
            String portrait = targetFleet.getCommander().getPortraitSprite();
            if (portrait != null && !portrait.trim().isEmpty()) {
                return portrait;
            }
        }
        return getDefaultMissionPortrait();
    }

    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add(INTEL_TAG_MOCI);
        tags.add(INTEL_TAG_NERGAL);
        tags.add(INTEL_TAG_NERGAL_CN);
        if (isImportant()) {
            tags.add(INTEL_TAG_IMPORTANT_CN);
        }
        return tags;
    }

    @Override
    public SectorEntityToken getMapLocation(SectorMapAPI map) {
        if (targetFleet != null) {
            return targetFleet;
        }
        return targetGate;
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        Color h = Misc.getHighlightColor();
        float pad = 10f;

        info.addSectionHeading(text("common.section_summary"), Alignment.MID, pad);
        info.addPara(getMissionDescription(), pad, h);
        if (missionType == MissionType.REMNANT && remnantTargetFp > 0) {
            String detailsKey = remnantOmegaFleet ? "yurika_mission.omega_contract_details"
                    : "yurika_mission.advanced_contract_details";
            info.addPara(formatText(detailsKey, Moci_TextLoader.mapOf(
                    "fleetFp", String.valueOf(remnantTargetFp),
                    "credits", Misc.getWithDGS((int) ((float) remnantTargetFp * REMNANT_ADVANCED_CREDITS_PER_FP)),
                    "core", text("yurika_mission.core_" + getAICoreRewardId(remnantTargetFp)),
                    "count", String.valueOf(getAICoreRewardCount(remnantTargetFp)))), pad, h);
        }

        info.addSectionHeading(text("common.section_status"), Alignment.MID, pad);
        if (state == State.ACTIVE) {
            String place = targetGate != null ? targetGate.getName() : text("common.some_gate");
            LabelAPI label = info.addPara(formatText("common.target_location", Moci_TextLoader.mapOf("place", place)), pad, h, place);
            label.setHighlight(place);
        } else {
            info.addPara(text("yurika_mission.status_completed"), pad, h);
        }
    }

    private static String text(String key) {
        return Moci_TextLoader.getText(TEXT_ID, key);
    }

    private static String formatText(String key, Map<String, String> replacements) {
        return Moci_TextLoader.getTextWithReplacements(TEXT_ID, key, replacements);
    }

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

    private String getDefaultMissionPortrait() {
        switch (missionType) {
            case STORY_A:
                return PORTRAIT_YURIKA;
            case STORY_B:
                return PORTRAIT_RURI;
            case STORY_TDD:
                return PORTRAIT_TERESA;
            case REMNANT:
            default:
                return PORTRAIT_YURIKA;
        }
    }
}
