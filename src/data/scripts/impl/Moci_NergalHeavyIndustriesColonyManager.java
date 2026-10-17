package data.scripts.impl;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.PersonImportance;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.ImportantPeopleAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Planets;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.NebulaEditor;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.terrain.BaseTiledTerrain;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import com.fs.starfarer.api.util.Misc;

/**
 * 为尼尔加重工创建独立新星系与固定落点。
 */
public class Moci_NergalHeavyIndustriesColonyManager {

    private static final String SYSTEM_NAME = "Helianthus";
    private static final String STAR_ID = "moci_nergal_star";
    private static final String STAR_TYPE = "star_yellow";

    private static final String LOTUS_ID = "moci_nergal_lotus";
    private static final String LOTUS_NAME = "Lotus";
    private static final String ASTER_ID = "moci_nergal_aster";
    private static final String ASTER_NAME = "Aster";
    private static final String PEONY_ID = "moci_nergal_peony";
    private static final String PEONY_NAME = "Peony";
    private static final String POPPY_ID = "moci_nergal_poppy";
    private static final String POPPY_NAME = "Poppy";
    private static final String EDELWEISS_ID = "moci_nergal_edelweiss";
    private static final String EDELWEISS_NAME = "Edelweiss";
    private static final String SNOWDROP_ID = "moci_nergal_snowdrop";
    private static final String SNOWDROP_NAME = "Snowdrop";

    private static final String LOTUS_STATION_ID = "moci_nergal_lotus_harbor";
    private static final String LOTUS_STATION_NAME = "Lotus Harbor";
    private static final String GATE_ID = "moci_nergal_gate";
    private static final String INNER_STABLE_ID = "moci_nergal_inner_stable";
    private static final String MID_STABLE_ID = "moci_nergal_mid_stable";
    private static final String OUTER_STABLE_ID = "moci_nergal_outer_stable";
    private static final String RELAY_ID = "moci_nergal_comm_relay";
    private static final String NAV_BUOY_ID = "moci_nergal_nav_buoy";
    private static final String SENSOR_ARRAY_ID = "moci_nergal_sensor_array";

    private static final String LOTUS_MARKET_ID = "market_moci_nergal_lotus";
    private static final String ASTER_MARKET_ID = "market_moci_nergal_aster";
    private static final String FACTION_ID = "Moci_NergalHeavyIndustries";
    private static final String NEX_NPC_NO_INVADE = "$nex_npc_no_invade";
    private static final String DEFAULT_HOSTILE_RELATIONS_APPLIED_KEY = "$moci_nergal_default_hostile_relations_v1";
    private static final String BASTION_FORTRESS = Moci_BastionFortress.INDUSTRY_ID;
    private static final String FLANAGAN_INSTITUTE = Moci_FlanaganInstitute.INDUSTRY_ID;
    private static final String LOTUS_ADMIN_ID = "moci_nergal_admin";
    private static final String LOTUS_STATION_COMMANDER_ID = "moci_nergal_station_commander";
    private static final String LOTUS_PORTMASTER_ID = "moci_nergal_portmaster";
    private static final String LOTUS_SECURITY_CHIEF_ID = "moci_nergal_security_chief";
    private static final String ASTER_ADMIN_ID = "moci_nergal_aster_admin";
    private static final String ASTER_PORTMASTER_ID = "moci_nergal_aster_portmaster";
    private static final String ASTER_STATION_COMMANDER_ID = "moci_nergal_aster_station_commander";
    private static final String ASTER_SECURITY_CHIEF_ID = "moci_nergal_aster_security_chief";

    private static final String DESCRIPTION_SYSTEM = "moci_nergal_helianthus";
    private static final String DESCRIPTION_LOTUS = "moci_nergal_lotus";
    private static final String DESCRIPTION_ASTER = "moci_nergal_aster";
    private static final String DESCRIPTION_PEONY = "moci_nergal_peony";
    private static final String DESCRIPTION_POPPY = "moci_nergal_poppy";
    private static final String DESCRIPTION_EDELWEISS = "moci_nergal_edelweiss";
    private static final String DESCRIPTION_SNOWDROP = "moci_nergal_snowdrop";

    private static final int LOTUS_MARKET_SIZE = 5;
    private static final int ASTER_MARKET_SIZE = 3;

    private static final float SYSTEM_X = -5600f;
    private static final float SYSTEM_Y = 17000f;
    private static final float STAR_RADIUS = 680f;
    private static final float STAR_CORONA = 360f;
    private static final float LOTUS_ORBIT_DISTANCE = 2300f;
    private static final float LOTUS_ORBIT_PERIOD = 95f;
    private static final float ASTER_ORBIT_DISTANCE = 420f;
    private static final float ASTER_ORBIT_PERIOD = 32f;
    private static final float LOTUS_STATION_ORBIT_DISTANCE = 240f;
    private static final float LOTUS_STATION_ORBIT_PERIOD = 18f;
    private static final float GATE_ORBIT_DISTANCE = 12600f;
    private static final float GATE_ORBIT_PERIOD = 3460f;

    private static final String BELT_NAME = "Helianthus Belt";
    private static final String NEBULA_PATTERN =
            "          " +
            "   xxx    " +
            "  xxxxxx  " +
            " xxxxxxxx " +
            " xxxxxxxx " +
            " xxxxxxxx " +
            "  xxxxxx  " +
            "   xxxx   " +
            "    xx    " +
            "          ";

    public static void initializeNergalColony() {
        // 已有星系完全保留存档状态，不在读档时重置归属、市场或人员。
        // 同时检查恒星 ID，避免星系改名后被当作尚未生成。
        if (Global.getSector().getStarSystem(SYSTEM_NAME) != null
                || Global.getSector().getEntityById(STAR_ID) != null) {
            return;
        }

        ensureDefaultRelations();
        StarSystemAPI system = createSystem();

        if (system == null) {
            Global.getLogger(Moci_NergalHeavyIndustriesColonyManager.class)
                    .warn("创建尼尔加重工新星系失败");
            return;
        }

        createLotusHub(system);
        createAsterHub(system);
        ensurePeople();
    }

    private static void ensureDefaultRelations() {
        if (Boolean.TRUE.equals(Global.getSector().getPersistentData().get(DEFAULT_HOSTILE_RELATIONS_APPLIED_KEY))) {
            return;
        }

        FactionAPI faction = Global.getSector().getFaction(FACTION_ID);
        if (faction == null) {
            return;
        }

        faction.setRelationship(Factions.REMNANTS, -1f);
        faction.setRelationship(Factions.THREAT, -1f);
        faction.setRelationship(Factions.DERELICT, -1f);
        Global.getSector().getPersistentData().put(DEFAULT_HOSTILE_RELATIONS_APPLIED_KEY, true);
    }

    private static StarSystemAPI createSystem() {
        StarSystemAPI system = Global.getSector().createStarSystem(SYSTEM_NAME);
        system.setProcgen(true);
        system.getLocation().set(SYSTEM_X, SYSTEM_Y);
        system.setBackgroundTextureFilename("graphics/backgrounds/background5.jpg");

        PlanetAPI star = system.initStar(STAR_ID, STAR_TYPE, STAR_RADIUS, STAR_CORONA);
        system.setLightColor(new Color(246, 233, 187));
        star.setCustomDescriptionId(DESCRIPTION_SYSTEM);
        star.setInteractionImage("illustrations", "orbital");

        populateNewSystem(system);

        return system;
    }

    private static void populateNewSystem(StarSystemAPI system) {
        createSystemLayout(system);
        createStableLocationsAndDevices(system);
        addSystemGate(system, system.getStar());
        if (system.getJumpPoints().isEmpty()) {
            system.autogenerateHyperspaceJumpPoints(true, true, true);
        }
        system.addTag(Tags.SYSTEM_ALREADY_USED_FOR_STORY);
        cleanupHyperspace(system);
    }

    private static void createSystemLayout(StarSystemAPI system) {
        PlanetAPI star = system.getStar();
        if (star == null || system.getEntityById(LOTUS_ID) != null) {
            return;
        }

        addNebulaCloud(system, star, 35f, 9800f, 520f);
        addNebulaCloud(system, star, 210f, 12800f, 760f);

        PlanetAPI lotus = system.addPlanet(
                LOTUS_ID,
                star,
                LOTUS_NAME,
                Planets.PLANET_WATER,
                40f,
                135f,
                LOTUS_ORBIT_DISTANCE,
                LOTUS_ORBIT_PERIOD
        );
        lotus.getSpec().setPlanetColor(new Color(112, 170, 212, 255));
        lotus.getSpec().setAtmosphereColor(new Color(148, 210, 255, 180));
        lotus.getSpec().setCloudColor(new Color(225, 245, 255, 210));
        lotus.applySpecChanges();
        lotus.setCustomDescriptionId(DESCRIPTION_LOTUS);
        lotus.setInteractionImage("illustrations", "urban01");

        PlanetAPI aster = system.addPlanet(
                ASTER_ID,
                lotus,
                ASTER_NAME,
                Planets.BARREN,
                180f,
                55f,
                ASTER_ORBIT_DISTANCE,
                ASTER_ORBIT_PERIOD
        );
        aster.getSpec().setPlanetColor(new Color(168, 154, 134, 255));
        aster.applySpecChanges();
        aster.setCustomDescriptionId(DESCRIPTION_ASTER);
        aster.setInteractionImage("illustrations", "orbital_construction");

        PlanetAPI peony = system.addPlanet(
                PEONY_ID,
                star,
                PEONY_NAME,
                Planets.GAS_GIANT,
                210f,
                280f,
                7200f,
                430f
        );
        peony.getSpec().setPlanetColor(new Color(207, 173, 138, 255));
        peony.getSpec().setAtmosphereColor(new Color(220, 150, 120, 140));
        peony.getSpec().setCloudColor(new Color(244, 214, 188, 210));
        peony.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
        peony.getSpec().setGlowColor(new Color(198, 118, 80, 70));
        peony.getSpec().setUseReverseLightForGlow(true);
        peony.applySpecChanges();
        peony.setCustomDescriptionId(DESCRIPTION_PEONY);
        peony.setInteractionImage("illustrations", "space_wreckage");

        system.addRingBand(peony, "misc", "rings_dust0", 256f, 0, new Color(220, 190, 165, 120), 256f, 980f, 38f);
        system.addRingBand(
                peony,
                "misc",
                "rings_special0",
                256f,
                1,
                new Color(236, 212, 196, 170),
                256f,
                1160f,
                50f,
                Terrain.RING,
                "Peony Halo"
        );

        PlanetAPI poppy = system.addPlanet(
                POPPY_ID,
                peony,
                POPPY_NAME,
                Planets.PLANET_TERRAN,
                85f,
                48f,
                860f,
                44f
        );
        poppy.getSpec().setPlanetColor(new Color(176, 74, 64, 255));
        poppy.getSpec().setAtmosphereColor(new Color(162, 58, 50, 120));
        poppy.applySpecChanges();
        poppy.setCustomDescriptionId(DESCRIPTION_POPPY);
        poppy.setInteractionImage("illustrations", "orbital");

        PlanetAPI edelweiss = system.addPlanet(
                EDELWEISS_ID,
                star,
                EDELWEISS_NAME,
                Planets.ICE_GIANT,
                315f,
                190f,
                11200f,
                720f
        );
        edelweiss.getSpec().setPlanetColor(new Color(210, 226, 245, 255));
        edelweiss.getSpec().setCloudColor(new Color(228, 238, 250, 220));
        edelweiss.applySpecChanges();
        edelweiss.setCustomDescriptionId(DESCRIPTION_EDELWEISS);
        edelweiss.setInteractionImage("illustrations", "orbital_construction");

        PlanetAPI snowdrop = system.addPlanet(
                SNOWDROP_ID,
                star,
                SNOWDROP_NAME,
                Planets.FROZEN2,
                105f,
                90f,
                13500f,
                980f
        );
        snowdrop.getSpec().setPlanetColor(new Color(201, 214, 236, 255));
        snowdrop.applySpecChanges();
        snowdrop.setCustomDescriptionId(DESCRIPTION_SNOWDROP);
        snowdrop.setInteractionImage("illustrations", "orbital_construction");

        system.addRingBand(
                star,
                "misc",
                "rings_dust0",
                256f,
                0,
                new Color(166, 154, 146, 105),
                256f,
                9300f,
                220f,
                Terrain.RING,
                BELT_NAME
        );
        system.addRingBand(
                star,
                "misc",
                "rings_asteroids0",
                256f,
                1,
                new Color(182, 172, 165, 185),
                256f,
                9520f,
                235f,
                null,
                null
        );
        system.addAsteroidBelt(star, 180, 9430f, 420f, 220f, 320f, Terrain.ASTEROID_BELT, BELT_NAME);
    }

    private static void createStableLocationsAndDevices(StarSystemAPI system) {
        PlanetAPI star = system.getStar();
        if (star == null) {
            return;
        }

        ensureStableLocation(system, INNER_STABLE_ID, star, 340f, LOTUS_ORBIT_DISTANCE, LOTUS_ORBIT_PERIOD);
        ensureStableLocation(system, MID_STABLE_ID, star, 270f, 7200f, 430f);
        ensureStableLocation(system, OUTER_STABLE_ID, star, 135f, 11200f, 720f);

        ensureObjective(system, RELAY_ID, "Helianthus Relay", Entities.COMM_RELAY, 100f, LOTUS_ORBIT_DISTANCE, LOTUS_ORBIT_PERIOD);
        ensureObjective(system, NAV_BUOY_ID, "Helianthus Nav Buoy", Entities.NAV_BUOY, 150f, 7200f, 430f);
        ensureObjective(system, SENSOR_ARRAY_ID, "Helianthus Sensor Array", Entities.SENSOR_ARRAY, 285f, 13500f, 980f);
    }

    private static void createLotusHub(StarSystemAPI system) {
        PlanetAPI lotus = (PlanetAPI) system.getEntityById(LOTUS_ID);
        if (lotus == null) {
            return;
        }

        lotus.setFaction(FACTION_ID);

        MarketAPI market = lotus.getMarket();
        if (market == null) {
            market = Global.getFactory().createMarket(LOTUS_MARKET_ID, LOTUS_NAME, LOTUS_MARKET_SIZE);
            markNoNpcInvasion(market);
        }

        market.setFactionId(FACTION_ID);
        market.setPrimaryEntity(lotus);
        market.setSize(LOTUS_MARKET_SIZE);
        market.setSurveyLevel(MarketAPI.SurveyLevel.FULL);
        market.setPlanetConditionMarketOnly(false);
        market.getMemoryWithoutUpdate().set(MemFlags.MARKET_DO_NOT_INIT_COMM_LISTINGS, true);

        ensureLotusStation(system, lotus, market);

        addConditionIfMissing(market, Conditions.HABITABLE);
        addConditionIfMissing(market, Conditions.WATER_SURFACE);
        addConditionIfMissing(market, Conditions.MILD_CLIMATE);
        addConditionIfMissing(market, Conditions.FARMLAND_ADEQUATE);
        addConditionIfMissing(market, Conditions.ORGANICS_ABUNDANT);

        addIndustryIfMissing(market, Industries.POPULATION);
        addIndustryIfMissing(market, Industries.MEGAPORT);
        addIndustryIfMissing(market, Industries.LIGHTINDUSTRY);
        addIndustryIfMissing(market, Industries.HEAVYINDUSTRY);
        addIndustryIfMissing(market, Industries.WAYSTATION);
        addIndustryIfMissing(market, FLANAGAN_INSTITUTE);
        removeIndustryIfPresent(market, Industries.PATROLHQ);
        addIndustryIfMissing(market, Industries.MILITARYBASE);
        addIndustryIfMissing(market, Industries.HIGHCOMMAND);
        addIndustryIfMissing(market, Industries.ORBITALSTATION);
        addIndustryIfMissing(market, BASTION_FORTRESS);
        setIndustryAICore(market, Industries.MILITARYBASE, Commodities.ALPHA_CORE);
        setIndustryAICore(market, Industries.HIGHCOMMAND, Commodities.ALPHA_CORE);
        setIndustryAICore(market, Industries.MEGAPORT, Commodities.BETA_CORE);

        addSubmarketIfMissing(market, Submarkets.SUBMARKET_OPEN);
        addSubmarketIfMissing(market, Submarkets.SUBMARKET_BLACK);
        addSubmarketIfMissing(market, Submarkets.SUBMARKET_STORAGE);
        addSubmarketIfMissing(market, Submarkets.GENERIC_MILITARY);
        addSubmarketIfMissing(market, "Moci_MobileSuitMarket");

        lotus.setMarket(market);
        ensureMarketInEconomy(market);
        surveyAllConditions(market);
        market.reapplyConditions();
        market.reapplyIndustries();
        market.getStability().modifyFlat("base", 6f, "基础稳定度");
    }

    private static void createAsterHub(StarSystemAPI system) {
        PlanetAPI aster = (PlanetAPI) system.getEntityById(ASTER_ID);
        if (aster == null) {
            return;
        }

        aster.setFaction(FACTION_ID);
        MarketAPI market = aster.getMarket();
        if (market == null) {
            market = Global.getFactory().createMarket(ASTER_MARKET_ID, ASTER_NAME, ASTER_MARKET_SIZE);
            markNoNpcInvasion(market);
        }

        market.setFactionId(FACTION_ID);
        market.setPrimaryEntity(aster);
        market.setSize(ASTER_MARKET_SIZE);
        market.setSurveyLevel(MarketAPI.SurveyLevel.FULL);
        market.setFreePort(false);
        market.setPlanetConditionMarketOnly(false);
        market.getMemoryWithoutUpdate().set(MemFlags.MARKET_DO_NOT_INIT_COMM_LISTINGS, true);

        addConditionIfMissing(market, Conditions.POPULATION_3);
        addConditionIfMissing(market, Conditions.NO_ATMOSPHERE);
        addConditionIfMissing(market, Conditions.LOW_GRAVITY);
        addConditionIfMissing(market, Conditions.ORE_RICH);
        addConditionIfMissing(market, Conditions.RARE_ORE_SPARSE);
        addConditionIfMissing(market, Conditions.RUINS_SCATTERED);

        addIndustryIfMissing(market, Industries.POPULATION);
        addIndustryIfMissing(market, Industries.SPACEPORT);
        addIndustryIfMissing(market, Industries.MINING);
        addIndustryIfMissing(market, Industries.LIGHTINDUSTRY);
        addIndustryIfMissing(market, Industries.GROUNDDEFENSES);
        addIndustryIfMissing(market, BASTION_FORTRESS);
        setIndustryAICore(market, Industries.SPACEPORT, Commodities.BETA_CORE);

        addSubmarketIfMissing(market, Submarkets.SUBMARKET_OPEN);
        addSubmarketIfMissing(market, Submarkets.SUBMARKET_BLACK);
        addSubmarketIfMissing(market, Submarkets.SUBMARKET_STORAGE);
        addSubmarketIfMissing(market, "Moci_MobileSuitMarket");

        aster.setMarket(market);
        ensureMarketInEconomy(market);
        surveyAllConditions(market);
        market.reapplyConditions();
        market.reapplyIndustries();
        market.getStability().modifyFlat("base", 4f, "基础稳定度");
        ensureAsterPeople(market);
    }

    private static void addSystemGate(StarSystemAPI system, PlanetAPI star) {
        if (star == null || system.getEntityById(GATE_ID) != null) {
            return;
        }

        SectorEntityToken gate = system.addCustomEntity(
                GATE_ID,
                "Helianthus Gate",
                "inactive_gate",
                null
        );
        gate.setCircularOrbit(star, 225f, GATE_ORBIT_DISTANCE, GATE_ORBIT_PERIOD);
    }

    private static void ensureStableLocation(
            StarSystemAPI system,
            String entityId,
            SectorEntityToken focus,
            float angle,
            float orbitRadius,
            float orbitPeriod
    ) {
        if (system.getEntityById(entityId) != null || focus == null) {
            return;
        }

        SectorEntityToken stable = system.addCustomEntity(
                entityId,
                null,
                Entities.STABLE_LOCATION,
                Factions.NEUTRAL
        );
        stable.setCircularOrbitPointingDown(focus, angle, orbitRadius, orbitPeriod);
    }

    private static void ensureObjective(
            StarSystemAPI system,
            String entityId,
            String name,
            String entityType,
            float angle,
            float orbitRadius,
            float orbitPeriod
    ) {
        PlanetAPI star = system.getStar();
        if (star == null) {
            return;
        }

        SectorEntityToken entity = system.getEntityById(entityId);
        if (entity == null) {
            entity = system.addCustomEntity(entityId, name, entityType, FACTION_ID);
            entity.setCircularOrbitPointingDown(star, angle, orbitRadius, orbitPeriod);
        } else {
            entity.setFaction(FACTION_ID);
        }
    }

    private static void addNebulaCloud(StarSystemAPI system, SectorEntityToken focus, float angle, float orbitRadius, float orbitDays) {
        SectorEntityToken cloud = system.addTerrain(
                Terrain.NEBULA,
                new BaseTiledTerrain.TileParams(
                        NEBULA_PATTERN,
                        10,
                        10,
                        "terrain",
                        "nebula_blue",
                        4,
                        4,
                        null
                )
        );
        cloud.setCircularOrbit(focus, angle, orbitRadius, orbitDays);
    }

    private static void cleanupHyperspace(StarSystemAPI system) {
        HyperspaceTerrainPlugin terrain = (HyperspaceTerrainPlugin) Misc.getHyperspaceTerrain().getPlugin();
        NebulaEditor editor = new NebulaEditor(terrain);
        float minRadius = terrain.getTileSize() * 2f;
        float radius = system.getMaxRadiusInHyperspace();

        editor.clearArc(system.getLocation().x, system.getLocation().y, 0, radius + minRadius * 0.5f, 0, 360f);
        editor.clearArc(system.getLocation().x, system.getLocation().y, 0, radius + minRadius, 0, 360f, 0.25f);
    }

    private static void ensurePeople() {
        MarketAPI market = getLotusMarket();
        if (market == null) {
            return;
        }

        Moci_YurikaStoryManager.ensureLoaded();

        ensureLotusAdmin(market);
        syncLotusStoryContacts(market);
        addPersonIfMissing(market, createPortmaster(market), 2);
        addPersonIfMissing(market, createSecurityChief(market), 3);
    }

    public static MarketAPI getLotusMarket() {
        SectorEntityToken lotus = Global.getSector().getEntityById(LOTUS_ID);
        if (lotus == null) {
            return null;
        }
        return lotus.getMarket();
    }

    public static MarketAPI getAsterMarket() {
        SectorEntityToken aster = Global.getSector().getEntityById(ASTER_ID);
        if (aster == null) {
            return null;
        }
        return aster.getMarket();
    }

    public static PersonAPI getLotusAdmin() {
        return getPersonById(getLotusMarket(), LOTUS_ADMIN_ID);
    }

    public static PersonAPI getLotusStationCommander() {
        return getPersonById(getLotusMarket(), LOTUS_STATION_COMMANDER_ID);
    }

    public static void syncLotusStoryContacts() {
        syncLotusStoryContacts(getLotusMarket());
    }

    private static void ensureAsterPeople(MarketAPI market) {
        if (market == null) {
            return;
        }

        if (market.getAdmin() == null) {
            addPersonIfMissing(market, createAsterAdmin(market), 0);
        }
        if (!hasPersonWithPost(market, Ranks.POST_PORTMASTER)) {
            addPersonIfMissing(market, createAsterPortmaster(market), 1);
        }
        if (!hasPersonWithPost(market, Ranks.POST_STATION_COMMANDER)) {
            addPersonIfMissing(market, createAsterStationCommander(market), 2);
        }
        if (!hasPersonWithPost(market, Ranks.POST_SECURITY_CHIEF)) {
            addPersonIfMissing(market, createAsterSecurityChief(market), 3);
        }
    }

    private static void addPersonIfMissing(MarketAPI market, PersonAPI person, int order) {
        if (market == null || person == null || hasPerson(market, person.getId())) {
            return;
        }

        ImportantPeopleAPI ip = Global.getSector().getImportantPeople();
        if (Ranks.POST_ADMINISTRATOR.equals(person.getPostId())) {
            market.setAdmin(person);
        }
        market.getCommDirectory().addPerson(person, order);
        market.addPerson(person);
        ip.addPerson(person);
        ip.getData(person).getLocation().setMarket(market);
        ip.checkOutPerson(person, "permanent_staff");
    }

    private static boolean hasPerson(MarketAPI market, String personId) {
        return getPersonById(market, personId) != null;
    }

    private static void ensureLotusAdmin(MarketAPI market) {
        PersonAPI person = getPersonById(market, LOTUS_ADMIN_ID);
        if (person == null) {
            addPersonIfMissing(market, createAdmin(market), 0);
            return;
        }
        applyLotusAdminProfile(person);
        // setAdmin 即使接收同一人物也会移除旧管理员的通讯录条目。
        if (market.getAdmin() != person) {
            market.setAdmin(person);
        }
    }

    private static void syncLotusStoryContacts(MarketAPI market) {
        if (market == null) {
            return;
        }
        if (shouldShowYurikaOnLotus()) {
            ensureLotusStationCommander(market);
            return;
        }
        removePersonIfPresent(market, LOTUS_STATION_COMMANDER_ID);
    }

    private static boolean shouldShowYurikaOnLotus() {
        Moci_YurikaStoryManager.ensureLoaded();
        return !Moci_YurikaStoryManager.shouldUseProspectorAsPrimaryContact();
    }

    private static void ensureLotusStationCommander(MarketAPI market) {
        PersonAPI person = getPersonById(market, LOTUS_STATION_COMMANDER_ID);
        if (person == null) {
            addPersonIfMissing(market, createStationCommander(market), 1);
            return;
        }
        applyLotusStationCommanderProfile(person);
    }

    private static boolean hasPersonWithPost(MarketAPI market, String postId) {
        if (market == null || postId == null) {
            return false;
        }
        for (PersonAPI person : market.getPeopleCopy()) {
            if (postId.equals(person.getPostId())) {
                return true;
            }
        }
        return false;
    }

    private static void ensureLotusStation(StarSystemAPI system, PlanetAPI lotus, MarketAPI market) {
        if (system == null || lotus == null || market == null) {
            return;
        }

        SectorEntityToken station = system.getEntityById(LOTUS_STATION_ID);
        if (station == null) {
            station = system.addCustomEntity(
                    LOTUS_STATION_ID,
                    LOTUS_STATION_NAME,
                    "station_side06",
                    FACTION_ID
            );
            station.setCircularOrbitPointingDown(
                    lotus,
                    StarSystemGenerator.random.nextInt(360),
                    LOTUS_STATION_ORBIT_DISTANCE,
                    LOTUS_STATION_ORBIT_PERIOD
            );
            station.setInteractionImage("illustrations", "industrial_megafacility");
        }

        station.addTag(Tags.STATION);
        station.setFaction(FACTION_ID);
        if (!market.getConnectedEntities().contains(station)) {
            market.getConnectedEntities().add(station);
        }
        station.setMarket(market);
    }

    private static void addConditionIfMissing(MarketAPI market, String conditionId) {
        if (market == null || conditionId == null || market.hasCondition(conditionId)) {
            return;
        }
        market.addCondition(conditionId);
    }

    private static void addIndustryIfMissing(MarketAPI market, String industryId) {
        if (market == null || industryId == null || market.hasIndustry(industryId)) {
            return;
        }
        market.addIndustry(industryId);
    }

    private static void removeIndustryIfPresent(MarketAPI market, String industryId) {
        if (market == null || industryId == null || !market.hasIndustry(industryId)) {
            return;
        }
        market.removeIndustry(industryId, null, false);
    }

    private static void setIndustryAICore(MarketAPI market, String industryId, String coreId) {
        if (market == null || industryId == null || coreId == null) {
            return;
        }

        Industry industry = market.getIndustry(industryId);
        if (industry == null) {
            return;
        }
        industry.setAICoreId(coreId);
    }

    private static void addSubmarketIfMissing(MarketAPI market, String submarketId) {
        if (market == null || submarketId == null || market.hasSubmarket(submarketId)) {
            return;
        }
        market.addSubmarket(submarketId);
    }

    private static void ensureMarketInEconomy(MarketAPI market) {
        if (market == null) {
            return;
        }
        if (Global.getSector().getEconomy().getMarket(market.getId()) != null) {
            return;
        }
        Global.getSector().getEconomy().addMarket(market, false);
    }

    private static void markNoNpcInvasion(MarketAPI market) {
        if (market == null) {
            return;
        }
        market.getMemoryWithoutUpdate().set(NEX_NPC_NO_INVADE, true);
    }

    private static void surveyAllConditions(MarketAPI market) {
        if (market == null) {
            return;
        }
        for (MarketConditionAPI condition : market.getConditions()) {
            condition.setSurveyed(true);
        }
    }

    private static PersonAPI getPersonById(MarketAPI market, String personId) {
        if (market == null || personId == null) {
            return null;
        }
        for (PersonAPI person : market.getPeopleCopy()) {
            if (personId.equals(person.getId())) {
                return person;
            }
        }
        return null;
    }

    private static void removePersonIfPresent(MarketAPI market, String personId) {
        PersonAPI person = getPersonById(market, personId);
        if (market == null || person == null) {
            return;
        }
        market.removePerson(person);
        market.getCommDirectory().removePerson(person);
        Global.getSector().getImportantPeople().removePerson(person);
    }

    private static void applyLotusAdminProfile(PersonAPI person) {
        if (person == null) {
            return;
        }
        person.getName().setFirst("Prospector");
        person.getName().setLast("");
        person.setGender(FullName.Gender.MALE);
        person.setRankId(Ranks.CITIZEN);
        person.setPostId(Ranks.POST_ADMINISTRATOR);
        person.setPortraitSprite("graphics/portraits/characters/Moci_Prospector.png");
        person.addTag(Tags.CONTACT_TRADE);
    }

    private static void applyLotusStationCommanderProfile(PersonAPI person) {
        if (person == null) {
            return;
        }
        person.getName().setFirst("Yurika");
        person.getName().setLast("Misumaru");
        person.setGender(FullName.Gender.FEMALE);
        person.setRankId(Ranks.SPACE_CAPTAIN);
        person.setPostId(Ranks.POST_STATION_COMMANDER);
        person.setPortraitSprite("graphics/portraits/characters/Moci_Yurika.png");
        person.addTag(Tags.CONTACT_MILITARY);
    }

    private static PersonAPI createAdmin(MarketAPI market) {
        PersonAPI person = market.getFaction().createRandomPerson();
        person.setId(LOTUS_ADMIN_ID);
        person.setImportanceAndVoice(PersonImportance.HIGH, StarSystemGenerator.random);
        applyLotusAdminProfile(person);
        return person;
    }

    private static PersonAPI createStationCommander(MarketAPI market) {
        PersonAPI person = market.getFaction().createRandomPerson();
        person.setId(LOTUS_STATION_COMMANDER_ID);
        person.setImportanceAndVoice(PersonImportance.MEDIUM, StarSystemGenerator.random);
        applyLotusStationCommanderProfile(person);
        return person;
    }

    private static PersonAPI createPortmaster(MarketAPI market) {
        PersonAPI person = market.getFaction().createRandomPerson();
        person.setId(LOTUS_PORTMASTER_ID);
        person.setPostId(Ranks.POST_PORTMASTER);
        person.setImportanceAndVoice(PersonImportance.LOW, StarSystemGenerator.random);
        person.addTag(Tags.CONTACT_TRADE);
        return person;
    }

    private static PersonAPI createAsterAdmin(MarketAPI market) {
        PersonAPI person = market.getFaction().createRandomPerson();
        person.setId(ASTER_ADMIN_ID);
        person.setRankId(Ranks.CITIZEN);
        person.setPostId(Ranks.POST_ADMINISTRATOR);
        person.setImportanceAndVoice(PersonImportance.MEDIUM, StarSystemGenerator.random);
        person.addTag(Tags.CONTACT_TRADE);
        return person;
    }

    private static PersonAPI createAsterPortmaster(MarketAPI market) {
        PersonAPI person = market.getFaction().createRandomPerson();
        person.setId(ASTER_PORTMASTER_ID);
        person.setPostId(Ranks.POST_PORTMASTER);
        person.setImportanceAndVoice(PersonImportance.LOW, StarSystemGenerator.random);
        person.addTag(Tags.CONTACT_TRADE);
        return person;
    }

    private static PersonAPI createAsterStationCommander(MarketAPI market) {
        PersonAPI person = market.getFaction().createRandomPerson();
        person.setId(ASTER_STATION_COMMANDER_ID);
        person.setRankId(Ranks.SPACE_COMMANDER);
        person.setPostId(Ranks.POST_STATION_COMMANDER);
        person.setImportanceAndVoice(PersonImportance.MEDIUM, StarSystemGenerator.random);
        person.addTag(Tags.CONTACT_MILITARY);
        return person;
    }

    private static PersonAPI createAsterSecurityChief(MarketAPI market) {
        PersonAPI person = market.getFaction().createRandomPerson();
        person.setId(ASTER_SECURITY_CHIEF_ID);
        person.setRankId(Ranks.SPACE_COMMANDER);
        person.setPostId(Ranks.POST_SECURITY_CHIEF);
        person.setImportanceAndVoice(PersonImportance.MEDIUM, StarSystemGenerator.random);
        person.addTag(Tags.CONTACT_MILITARY);
        return person;
    }

    private static PersonAPI createSecurityChief(MarketAPI market) {
        PersonAPI person = market.getFaction().createRandomPerson();
        person.setId(LOTUS_SECURITY_CHIEF_ID);
        person.setRankId(Ranks.SPACE_COMMANDER);
        person.setPostId(Ranks.POST_SECURITY_CHIEF);
        person.setImportanceAndVoice(PersonImportance.MEDIUM, StarSystemGenerator.random);
        person.addTag(Tags.CONTACT_MILITARY);
        return person;
    }
}
