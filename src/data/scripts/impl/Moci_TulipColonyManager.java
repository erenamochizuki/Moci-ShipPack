package data.scripts.impl;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.ImportantPeopleAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.ids.*;
import com.fs.starfarer.api.impl.campaign.intel.contacts.ContactIntel;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.util.IntervalUtil;

import java.awt.Color;

/**
 * Tulip殖民地管理器
 * 负责在Hybrasil星系的Elada星球轨道上添加Tulip卫星殖民地
 */
public class Moci_TulipColonyManager {
    
    // 星系和星球ID
    private static final String SYSTEM_ID = "hybrasil";
    private static final String ELADA_ID = "elada";
    private static final String TULIP_ID = "moci_tulip";
    
    // 生成标记键
    private static final String TULIP_SPAWNED_KEY = "Moci_TulipColonySpawned";

    // 大和号流程完成标记（用于解锁联络员与任务链）
    private static final String YAMATO_BATTLE_COMPLETED_KEY = "$moci_yamato_battle_completed";
    private static final String YAMATO_BATTLE_COMPLETED_PERSISTENT_KEY = "Moci_YamatoBattleCompleted";

    // Tulip联络员
    private static final String OKITA_CONTACT_ID = "moci_tulip_contact_okita";
    private static final String OKITA_CONTACT_POST_ID = "Moci_Liaison_Officer";
    private static final String OKITA_CONTACT_ADDED_KEY = "$moci_okita_contact_added";
    private static final String OKITA_CONTACT_REGISTERED_KEY = "$moci_okita_contact_registered";
    private static final String OKITA_TULIP_BAR_MET_KEY = "$moci_okita_bar_met";
    private static final String OKITA_TULIP_BAR_MET_PERSISTENT_KEY = "Moci_OkitaTulipBarMet";
    
    // 卫星参数
    private static final String TULIP_NAME = "Tulip";
    private static final String PLANET_TYPE = Planets.BARREN_BOMBARDED;
    private static final int PLANET_RADIUS = 50;
    private static final float ORBIT_DISTANCE = 850f; // 距离Elada 450单位
    private static final float ORBIT_PERIOD = 45f; // 轨道周期35天
    
    // 轨道站参数
    private static final String STATION_ID = "moci_tulip_station";
    private static final String STATION_NAME = "Tulip轨道站";
    private static final float STATION_ORBIT_DISTANCE = 200f; // 距离Tulip 200单位
    private static final float STATION_ORBIT_PERIOD = 15f; // 轨道周期15天
    
    // 市场参数
    private static final String FACTION_ID = Factions.INDEPENDENT;
    private static final int MARKET_SIZE = 3; // 3级殖民地
    
    /**
     * 初始化Tulip殖民地系统
     * 检查是否已生成，如果未生成则创建
     */
    public static void initializeTulipColony() {
        // 检查是否已经生成过
        if (isTulipSpawned()) {
            registerTulipContactScript();
            return;
        }
        
        // 查找Hybrasil星系
        StarSystemAPI system = Global.getSector().getStarSystem(SYSTEM_ID);
        if (system == null) {
            Global.getLogger(Moci_TulipColonyManager.class)
                .warn("未找到Hybrasil星系，无法生成Tulip殖民地");
            return;
        }
        
        // 查找Elada星球
        SectorEntityToken eladaEntity = system.getEntityById(ELADA_ID);
        if (eladaEntity == null || !(eladaEntity instanceof PlanetAPI)) {
            Global.getLogger(Moci_TulipColonyManager.class)
                .warn("未找到Elada星球，无法生成Tulip殖民地");
            return;
        }
        
        PlanetAPI elada = (PlanetAPI) eladaEntity;
        
        // 生成Tulip卫星
        createTulipMoon(system, elada);
        
        // 标记已生成
        markTulipSpawned();
        registerTulipContactScript();
        
        Global.getLogger(Moci_TulipColonyManager.class)
            .info("Tulip殖民地生成成功！");
    }
    
    /**
     * 创建Tulip卫星及其市场
     */
    private static void createTulipMoon(StarSystemAPI system, PlanetAPI elada) {
        // 生成卫星
        // 参数：(ID, 轨道中心, 显示名称, 行星类型, 初始角度, 半径, 轨道距离, 轨道周期)
        PlanetAPI tulip = system.addPlanet(
            TULIP_ID,
            elada,
            TULIP_NAME,
            PLANET_TYPE,
            StarSystemGenerator.random.nextInt(360), // 随机初始角度
            PLANET_RADIUS,
            ORBIT_DISTANCE,
            ORBIT_PERIOD
        );
        
        // 设置星球颜色（荒芜轰炸星球的典型颜色）
        tulip.getSpec().setPlanetColor(new Color(200, 190, 180, 255));
        tulip.applySpecChanges();
        
        // 设置自定义描述
        tulip.setCustomDescriptionId("moci_planet_tulip");
        
        // 设置交互图片
        tulip.setInteractionImage("illustrations", "Moci_Tulip_Market");
        
        // 设置派系
        tulip.setFaction(FACTION_ID);
        
        // 创建市场（先不添加到经济系统）
        MarketAPI market = Global.getFactory().createMarket(
            "market_" + TULIP_ID,
            TULIP_NAME,
            MARKET_SIZE
        );
        
        market.setFactionId(FACTION_ID);
        market.setPrimaryEntity(tulip);
        market.setSize(MARKET_SIZE);
        
        // 关键：设置标记阻止原版自动生成人物
        // 这样我们就可以完全控制市场的人物配置
        market.getMemoryWithoutUpdate().set(MemFlags.MARKET_DO_NOT_INIT_COMM_LISTINGS, true);
        
        // 在添加产业之前创建轨道站实体
        // 这样轨道站产业会自动识别并使用这个预创建的实体
        SectorEntityToken station = createTulipStation(system, tulip);
        market.getConnectedEntities().add(station);
        station.setMarket(market);
        
        // 添加市场条件（特性）
        market.addCondition(Conditions.ORE_ULTRARICH); // 超丰富矿石
        market.addCondition(Conditions.RARE_ORE_ULTRARICH); // 超丰富稀有矿石
        market.addCondition(Conditions.VOLATILES_TRACE); // 微量挥发物
        market.addCondition(Conditions.RUINS_WIDESPREAD); // 广泛遗迹
        market.addCondition(Conditions.NO_ATMOSPHERE); // 无大气层
        market.addCondition(Conditions.LOW_GRAVITY); // 低重力
        market.addCondition(Conditions.METEOR_IMPACTS); // 流星撞击
        
        // 添加产业
        // 1. 居住区 & 基础设施
        market.addIndustry(Industries.POPULATION);
        
        // 2. 采矿业
        market.addIndustry(Industries.MINING);
        
        // 3. 特大型港口
        market.addIndustry(Industries.MEGAPORT);
        
        // 4. 重工业
        market.addIndustry(Industries.HEAVYINDUSTRY);
        
        // 5. 冶炼厂
        market.addIndustry(Industries.REFINING);
        
        // 6. 轨道站（会自动识别并使用上面创建的轨道站实体）
        market.addIndustry(Industries.ORBITALSTATION);
        
        // 7. 行星护盾
        market.addIndustry(Industries.PLANETARYSHIELD);
        
        // 8. 星际驿站
        market.addIndustry(Industries.WAYSTATION);
        
        // 添加子市场
        market.addSubmarket(Submarkets.SUBMARKET_OPEN); // 开放市场
        market.addSubmarket(Submarkets.SUBMARKET_BLACK); // 黑市
        market.addSubmarket(Submarkets.SUBMARKET_STORAGE); // 仓储
        market.addSubmarket("Moci_MobileSuitMarket"); // 机动战士市场
        
        // 设置市场调查等级
        market.setSurveyLevel(MarketAPI.SurveyLevel.FULL);
        
        // 将市场添加到星球
        tulip.setMarket(market);
        
        // 关键：使用false参数添加市场到经济系统
        // false表示不自动初始化人物和其他内容
        Global.getSector().getEconomy().addMarket(market, false);
        
        // 设置市场稳定度（默认5）
        market.getStability().modifyFlat("base", 5f, "基础稳定度");
        
        // 手动添加NPC人物（在市场添加到经济系统之后）
        // 因为我们设置了MARKET_DO_NOT_INIT_COMM_LISTINGS标记
        // 原版不会自动生成人物，我们需要手动添加所有需要的人物
        addTulipPeople(market);
    }
    
    /**
     * 创建Tulip轨道站实体
     * 这个实体会被轨道站产业自动识别和使用
     * 
     * 关键点：
     * 1. 必须在添加ORBITALSTATION产业之前创建
     * 2. 必须添加Tags.STATION标签
     * 3. 必须添加到market.getConnectedEntities()
     * 4. 产业会自动识别并管理这个实体，提供防御和舰队
     * 
     * @return 创建的轨道站实体
     */
    private static SectorEntityToken createTulipStation(StarSystemAPI system, PlanetAPI tulip) {
        // 创建轨道站实体
        SectorEntityToken station = system.addCustomEntity(
            STATION_ID,
            STATION_NAME,
            "station_side06", // 使用原版的空间站类型
            FACTION_ID
        );
        
        // 添加STATION标签
        station.addTag(Tags.STATION);
        
        // 设置轨道站围绕Tulip行星运行
        station.setCircularOrbitPointingDown(
            tulip,
            StarSystemGenerator.random.nextInt(360), // 随机初始角度
            STATION_ORBIT_DISTANCE,
            STATION_ORBIT_PERIOD
        );
        
        // 设置自定义描述ID
        station.setCustomDescriptionId("moci_tulip_station");
        
        // 设置交互插图
        station.setInteractionImage("illustrations", "Moci_Tulip_Station");
        
        // 设置派系
        station.setFaction(FACTION_ID);
        
        Global.getLogger(Moci_TulipColonyManager.class)
            .info("Tulip轨道站实体创建成功！产业会自动识别并管理它。");
        
        return station;
    }
    
    /**
     * 检查Tulip是否已生成
     */
    private static boolean isTulipSpawned() {
        return Global.getSector().getPersistentData().containsKey(TULIP_SPAWNED_KEY);
    }
    
    /**
     * 标记Tulip已生成
     */
    private static void markTulipSpawned() {
        Global.getSector().getPersistentData().put(TULIP_SPAWNED_KEY, true);
    }
    
    /**
     * 为Tulip殖民地添加NPC人物
     * 
     * 重要：因为我们设置了MARKET_DO_NOT_INIT_COMM_LISTINGS标记，
     * 原版不会自动生成任何人物，所以我们需要手动添加所有需要的人物。
     * 
     * 包括：行政官、空间站主管、港口管理员、补给官、安全主管等
     * 
     * 优先级说明（数字越小越靠前）：
     * 0 - 行政官（Administrator）
     * 1 - 空间站主管（Station Commander）
     * 2 - 港口管理员（Portmaster）
     * 3 - 补给官（Supply Officer）
     * 4 - 安全主管（Security Chief）
     */
    private static void addTulipPeople(MarketAPI market) {
        ImportantPeopleAPI ip = Global.getSector().getImportantPeople();
        
        // 1. 创建并添加行政官（Administrator）
        // 行政官是殖民地的最高行政长官，应该排在最前面
        PersonAPI admin = createTulipAdmin(market);
        market.setAdmin(admin); // 设置为市场的官方管理员
        market.getCommDirectory().addPerson(admin, 0); // 优先级0，最靠前
        market.addPerson(admin);
        ip.addPerson(admin);
        ip.getData(admin).getLocation().setMarket(market);
        ip.checkOutPerson(admin, "permanent_staff");
        
        Global.getLogger(Moci_TulipColonyManager.class)
            .info("Tulip行政官创建成功: " + admin.getNameString());
        
        // 2. 创建并添加自定义空间站主管（Station Commander）
        // 因为设置了MARKET_DO_NOT_INIT_COMM_LISTINGS，原版不会自动生成
        // 所以这里直接添加我们的自定义人物即可
        PersonAPI stationCommander = createStationCommander(market);
        market.getCommDirectory().addPerson(stationCommander, 1); // 优先级1，第二位
        market.addPerson(stationCommander);
        ip.addPerson(stationCommander);
        ip.getData(stationCommander).getLocation().setMarket(market);
        ip.checkOutPerson(stationCommander, "permanent_staff");

        Global.getLogger(Moci_TulipColonyManager.class)
            .info("Tulip空间站主管创建成功: " + stationCommander.getNameString());
        
        // 3. 创建并添加港口管理员（Portmaster）
        // 原版通常会为有港口的市场自动添加港口管理员
        // 但因为我们阻止了自动生成，所以需要手动添加
        PersonAPI portmaster = createPortmaster(market);
        market.getCommDirectory().addPerson(portmaster, 2); // 优先级2，第三位
        market.addPerson(portmaster);
        ip.addPerson(portmaster);
        ip.getData(portmaster).getLocation().setMarket(market);
        ip.checkOutPerson(portmaster, "permanent_staff");
        
        Global.getLogger(Moci_TulipColonyManager.class)
            .info("Tulip港口管理员创建成功: " + portmaster.getNameString());
        
        // 4. 创建并添加补给官（Supply Officer）
        PersonAPI supplyOfficer = createSupplyOfficer(market);
        market.getCommDirectory().addPerson(supplyOfficer, 3); // 优先级3，第四位
        market.addPerson(supplyOfficer);
        ip.addPerson(supplyOfficer);
        ip.getData(supplyOfficer).getLocation().setMarket(market);
        ip.checkOutPerson(supplyOfficer, "permanent_staff");
        
        Global.getLogger(Moci_TulipColonyManager.class)
            .info("Tulip补给官创建成功: " + supplyOfficer.getNameString());
        
        // 5. 创建并添加安全主管（Security Chief）
        PersonAPI securityChief = createSecurityChief(market);
        market.getCommDirectory().addPerson(securityChief, 4); // 优先级4，第五位
        market.addPerson(securityChief);
        ip.addPerson(securityChief);
        ip.getData(securityChief).getLocation().setMarket(market);
        ip.checkOutPerson(securityChief, "permanent_staff");
        
        Global.getLogger(Moci_TulipColonyManager.class)
            .info("Tulip安全主管创建成功: " + securityChief.getNameString());
    }
    
    /**
     * 创建Tulip行政官
     * 行政官是殖民地的最高行政长官
     */
    private static PersonAPI createTulipAdmin(MarketAPI market) {
        // 使用派系创建随机人物
        PersonAPI admin = market.getFaction().createRandomPerson();
        
        // 设置唯一ID
        admin.setId("moci_tulip_admin");
        
        // 设置姓名（可选，如果不设置会使用随机名字）
        admin.getName().setFirst("Lexington");
        admin.getName().setLast("Omega");
        
        // 设置性别（可选）
        admin.setGender(FullName.Gender.FEMALE); // 或 FEMALE, ANY
        
        // 设置职位和等级
        admin.setRankId(Ranks.CITIZEN); // 公民等级
        admin.setPostId(Ranks.POST_ADMINISTRATOR); // 管理员职位
        
        // 设置重要性和声音（根据市场规模）
        // 3级市场使用MEDIUM重要性
        admin.setImportanceAndVoice(PersonImportance.VERY_HIGH, StarSystemGenerator.random);
        
        // 设置头像（可选，如果有自定义头像）
        admin.setPortraitSprite("graphics/portraits/Moci_omega_lexington_portrait.png");

        // 设置技能（可选）
        admin.getStats().setSkillLevel(Skills.INDUSTRIAL_PLANNING, 1);
        admin.getStats().setSkillLevel(Skills.HYPERCOGNITION, 1);
        admin.getStats().setSkillLevel("Moci_OmegaAdministrationSkill", 1);
        
        // 设置个性（可选）
        // admin.setPersonality(Personalities.STEADY);

        // 添加联络人标签：安全主管可以提供军事相关任务
        admin.addTag(Tags.CONTACT_MILITARY);
        
        return admin;
    }

    /**
     * 创建空间站主管
     * 负责管理轨道站的军事和防御
     */
    private static PersonAPI createStationCommander(MarketAPI market) {
        PersonAPI commander = market.getFaction().createRandomPerson();
        
        commander.setId("moci_tulip_station_commander");

        // 设置姓名（可选，如果不设置会使用随机名字）
        commander.getName().setFirst("Erena");
        commander.getName().setLast("Mochizuki");

        // 设置性别（可选）
        commander.setGender(FullName.Gender.FEMALE); // 或 FEMALE, ANY

        // 设置头像（可选，如果有自定义头像）
        commander.setPortraitSprite("graphics/portraits/Moci_Mochizuki.png");

        // 根据市场规模设置军衔
        // 3级市场使用SPACE_COMMANDER
        commander.setRankId(Ranks.SPACE_COMMANDER);

        commander.setPostId("Moci_Store_Manager"); //使用自定义postID
        // commander.setPostId(Ranks.POST_STATION_COMMANDER);
        
        // 3级市场使用MEDIUM重要性
        commander.setImportanceAndVoice(PersonImportance.VERY_HIGH, StarSystemGenerator.random);
        
        return commander;
    }
    
    /**
     * 创建港口管理员
     * 负责管理港口的贸易和物流
     */
    private static PersonAPI createPortmaster(MarketAPI market) {
        PersonAPI portmaster = market.getFaction().createRandomPerson();
        
        portmaster.setId("moci_tulip_portmaster");

        portmaster.getName().setFirst("QingYou");
        portmaster.getName().setLast("Wu");

        // 设置性别（可选）
        portmaster.setGender(FullName.Gender.FEMALE); // 或 FEMALE, ANY

        // 设置头像（可选，如果有自定义头像）
        portmaster.setPortraitSprite("graphics/portraits/Moci_WuQingYou.png");
        
        // 港口管理员通常不设置军衔
        portmaster.setRankId(null);
        portmaster.setPostId(Ranks.POST_PORTMASTER);
        
        // 3级市场使用LOW重要性
        portmaster.setImportanceAndVoice(PersonImportance.LOW, StarSystemGenerator.random);
        
        return portmaster;
    }
    
    /**
     * 创建补给官
     * 负责管理物资供应
     */
    private static PersonAPI createSupplyOfficer(MarketAPI market) {
        PersonAPI officer = market.getFaction().createRandomPerson();
        
        officer.setId("moci_tulip_supply_officer");
        // 设置姓名（可选，如果不设置会使用随机名字）
        officer.getName().setFirst("Jinba");
        officer.getName().setLast("Mikoto");

        // 设置性别（可选）
        officer.setGender(FullName.Gender.FEMALE); // 或 FEMALE, ANY

        // 设置头像（可选，如果有自定义头像）
        officer.setPortraitSprite("graphics/portraits/Moci_Mikoto.png");


        officer.setRankId(Ranks.SPACE_COMMANDER);
        officer.setPostId(Ranks.POST_SUPPLY_OFFICER);
        
        // 3级市场使用LOW重要性
        officer.setImportanceAndVoice(PersonImportance.MEDIUM, StarSystemGenerator.random);
        
        return officer;
    }
    
    /**
     * 创建安全主管
     * 负责殖民地的安全和执法工作
     * 
     * 联络人级别说明：
     * PersonImportance决定了联络人的重要性，影响：
     * 1. 任务质量和奖励
     * 2. 在通讯目录中的显示顺序
     * 3. 与势力争霸（Nexerelin）的交互
     * 
     * 可用级别（从低到高）：
     * - VERY_LOW: 非常低（普通平民）
     * - LOW: 低（普通职员）
     * - MEDIUM: 中等（重要职员、小型市场管理员）
     * - HIGH: 高（大型市场管理员、重要军官）
     * - VERY_HIGH: 非常高（派系领袖、关键人物）
     * 
     * Tags.CONTACT_XXX 标签决定了联络人可以提供的任务类型：
     * - Tags.CONTACT_MILITARY: 军事任务（赏金、巡逻等）
     * - Tags.CONTACT_TRADE: 贸易任务（运输、采购等）
     * - Tags.CONTACT_UNDERWORLD: 地下世界任务（走私、黑市等）
     * - Tags.CONTACT_SCIENCE: 科研任务（调查、研究等）
     * - Tags.CONTACT_PATHER: 卢德教派任务
     * 
     * 安全主管通常具有MILITARY标签，可以提供安全相关的任务
     */
    private static PersonAPI createSecurityChief(MarketAPI market) {
        PersonAPI chief = market.getFaction().createRandomPerson();
        
        chief.setId("moci_tulip_security_chief");
        
        // 设置姓名："邪恶 海豚" -> "Evil Dolphin"
        chief.getName().setFirst("Evil");
        chief.getName().setLast("Dolphin");
        
        // 设置性别
        chief.setGender(FullName.Gender.FEMALE);
        
        // 设置头像
        chief.setPortraitSprite("graphics/portraits/Moci_Haiteng.png");
        
        // 设置职位
        chief.setRankId(Ranks.SPACE_COMMANDER); // 军衔：太空指挥官
        chief.setPostId(Ranks.POST_SECURITY_CHIEF); // 职位：安全主管
        
        // 设置重要性：安全主管是重要职位，使用MEDIUM级别
        chief.setImportanceAndVoice(PersonImportance.HIGH, StarSystemGenerator.random);
        
        // 添加联络人标签：安全主管可以提供军事相关任务
        chief.addTag(Tags.CONTACT_MILITARY);
        
        // 可选：添加技能
        // chief.getStats().setSkillLevel(Skills.COMBAT_ENDURANCE, 1);
        // chief.getStats().setSkillLevel(Skills.DAMAGE_CONTROL, 1);
        
        return chief;
    }

    /**
     * 注册 Tulip 联络员维护脚本
     */
    private static void registerTulipContactScript() {
        if (Global.getSector() == null) {
            return;
        }

        if (isOkitaContactReady()) {
            return;
        }

        if (!Global.getSector().hasScript(TulipContactScript.class)) {
            Global.getSector().addScript(new TulipContactScript());
        }
    }

    private static MarketAPI getTulipMarket() {
        SectorEntityToken tulipEntity = Global.getSector().getEntityById(TULIP_ID);
        if (tulipEntity == null || tulipEntity.getMarket() == null) {
            return null;
        }
        return tulipEntity.getMarket();
    }

    private static boolean hasYamatoBattleCompleted() {
        MemoryAPI globalMem = Global.getSector().getMemoryWithoutUpdate();
        if (globalMem.getBoolean(YAMATO_BATTLE_COMPLETED_KEY)) {
            Global.getSector().getPersistentData().put(YAMATO_BATTLE_COMPLETED_PERSISTENT_KEY, true);
            return true;
        }
        Object persistentFlag = Global.getSector().getPersistentData().get(YAMATO_BATTLE_COMPLETED_PERSISTENT_KEY);
        boolean completed = persistentFlag instanceof Boolean && (Boolean) persistentFlag;
        if (completed) {
            globalMem.set(YAMATO_BATTLE_COMPLETED_KEY, true);
        }
        return completed;
    }

    private static boolean hasOkitaTulipBarMet() {
        if (Global.getSector() == null) {
            return false;
        }
        MemoryAPI globalMem = Global.getSector().getMemoryWithoutUpdate();
        if (globalMem.getBoolean(OKITA_TULIP_BAR_MET_KEY)) {
            Global.getSector().getPersistentData().put(OKITA_TULIP_BAR_MET_PERSISTENT_KEY, true);
            return true;
        }
        Object persistentFlag = Global.getSector().getPersistentData().get(OKITA_TULIP_BAR_MET_PERSISTENT_KEY);
        boolean met = persistentFlag instanceof Boolean && (Boolean) persistentFlag;
        if (met) {
            globalMem.set(OKITA_TULIP_BAR_MET_KEY, true);
        }
        return met;
    }

    private static void setOkitaTulipBarMet(boolean met) {
        if (Global.getSector() == null) {
            return;
        }
        MemoryAPI globalMem = Global.getSector().getMemoryWithoutUpdate();
        globalMem.set(OKITA_TULIP_BAR_MET_KEY, met);
        Global.getSector().getPersistentData().put(OKITA_TULIP_BAR_MET_PERSISTENT_KEY, met);
    }

    public static void notifyOkitaBarMet() {
        if (Global.getSector() == null) {
            return;
        }
        setOkitaTulipBarMet(true);
        registerTulipContactScript();
        tryAddOkitaContactIfUnlocked();
    }

    private static boolean hasPerson(MarketAPI market, String personId) {
        for (PersonAPI person : market.getPeopleCopy()) {
            if (personId.equals(person.getId())) {
                return true;
            }
        }
        return false;
    }

    private static PersonAPI getPersonById(MarketAPI market, String personId) {
        for (PersonAPI person : market.getPeopleCopy()) {
            if (personId.equals(person.getId())) {
                return person;
            }
        }
        return null;
    }

    private static void ensureOkitaContactRegistered(PersonAPI okita, MarketAPI market) {
        if (okita == null || market == null) {
            return;
        }

        if (!OKITA_CONTACT_POST_ID.equals(okita.getPostId())) {
            okita.setPostId(OKITA_CONTACT_POST_ID);
        }
        if (!okita.hasTag(Tags.CONTACT_MILITARY)) {
            okita.addTag(Tags.CONTACT_MILITARY);
        }

        MemoryAPI globalMem = Global.getSector().getMemoryWithoutUpdate();

        ContactIntel intel = ContactIntel.getContactIntel(okita);
        if (intel == null || intel.isEnded() || intel.isEnding()) {
            intel = new ContactIntel(okita, market);
            intel.develop(null); // 直接转为正式联系人（非潜在联系人）
            Global.getSector().getIntelManager().addIntel(intel, false);
        } else {
            intel.relocateToMarket(market, false);
            ContactIntel.ContactState state = intel.getState();
            if (state == ContactIntel.ContactState.POTENTIAL
                    || state == ContactIntel.ContactState.SUSPENDED
                    || state == ContactIntel.ContactState.LOST_CONTACT
                    || state == ContactIntel.ContactState.LOST_CONTACT_DECIV) {
                intel.develop(null);
            }
        }

        globalMem.set(OKITA_CONTACT_REGISTERED_KEY, true);
    }

    private static boolean isOkitaContactReady() {
        if (Global.getSector() == null || !hasOkitaTulipBarMet()) {
            return false;
        }

        MarketAPI market = getTulipMarket();
        if (market == null) {
            return false;
        }

        PersonAPI okita = getPersonById(market, OKITA_CONTACT_ID);
        if (okita == null) {
            return false;
        }

        MemoryAPI globalMem = Global.getSector().getMemoryWithoutUpdate();
        if (!globalMem.getBoolean(OKITA_CONTACT_ADDED_KEY) || !globalMem.getBoolean(OKITA_CONTACT_REGISTERED_KEY)) {
            return false;
        }

        ContactIntel intel = ContactIntel.getContactIntel(okita);
        return intel != null && !intel.isEnded() && !intel.isEnding();
    }

    private static void tryAddOkitaContactIfUnlocked() {
        if (!hasOkitaTulipBarMet()) {
            return;
        }

        MarketAPI market = getTulipMarket();
        if (market == null) {
            return;
        }

        if (hasPerson(market, OKITA_CONTACT_ID)) {
            PersonAPI existing = getPersonById(market, OKITA_CONTACT_ID);
            ensureOkitaContactRegistered(existing, market);
            Global.getSector().getMemoryWithoutUpdate().set(OKITA_CONTACT_ADDED_KEY, true);
            return;
        }

        PersonAPI okita = createOkitaLiaison(market);
        ImportantPeopleAPI ip = Global.getSector().getImportantPeople();

        market.getCommDirectory().addPerson(okita, 5);
        market.addPerson(okita);
        ip.addPerson(okita);
        ip.getData(okita).getLocation().setMarket(market);
        ip.checkOutPerson(okita, "permanent_staff");
        ensureOkitaContactRegistered(okita, market);

        Global.getSector().getMemoryWithoutUpdate().set(OKITA_CONTACT_ADDED_KEY, true);
        Global.getLogger(Moci_TulipColonyManager.class).info("Tulip联络员已加入通讯录: " + okita.getNameString());
    }

    private static PersonAPI createOkitaLiaison(MarketAPI market) {
        PersonAPI liaison = market.getFaction().createRandomPerson();
        liaison.setId(OKITA_CONTACT_ID);
        liaison.getName().setFirst("Juzo");
        liaison.getName().setLast("Okita");
        liaison.setGender(FullName.Gender.MALE);
        liaison.setPortraitSprite("graphics/portraits/bounty/Moci_Okita.png");
        liaison.setRankId("spaceMarshal");
        liaison.setPostId(OKITA_CONTACT_POST_ID);
        liaison.setImportanceAndVoice(PersonImportance.HIGH, StarSystemGenerator.random);
        liaison.addTag(Tags.CONTACT_MILITARY);
        return liaison;
    }

    private static class TulipContactScript implements EveryFrameScript {
        // 每 1 秒检查一次
        private final IntervalUtil interval = new IntervalUtil(1f, 1f);
        private boolean done = false;

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public boolean runWhilePaused() {
            return false;
        }

        @Override
        public void advance(float amount) {
            if (done || Global.getSector() == null) {
                return;
            }
            if (isOkitaContactReady()) {
                done = true;
                return;
            }
            interval.advance(amount);
            if (!interval.intervalElapsed()) {
                return;
            }

            tryAddOkitaContactIfUnlocked();
            if (isOkitaContactReady()) {
                done = true;
            }
        }
    }

}
