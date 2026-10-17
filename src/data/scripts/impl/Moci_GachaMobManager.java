package data.scripts.impl;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Planets;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseManager;
import com.fs.starfarer.api.impl.campaign.procgen.NebulaEditor;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.procgen.themes.BaseThemeGenerator;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import com.fs.starfarer.api.impl.campaign.terrain.StarCoronaTerrainPlugin;
import com.fs.starfarer.api.util.Misc;

public class Moci_GachaMobManager {
    public static final String MemKey = "$Moci_GachaMobFleet";
    private static final String SYSTEM_NAME = "Vortex";
    private static final String SYSTEM_GENERATED_KEY = "Moci_VortexSystemGenerated";
    private static final String YAMATO_SPAWNED_KEY = "Moci_YamatoDerelictSpawned";

    /**
     * 检查Vortex星系是否已存在
     * 
     * @return 如果星系存在返回true，否则返回false
     */
    private static boolean isVortexSystemExists() {
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (SYSTEM_NAME.equals(system.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否已经标记为生成过Vortex星系
     * 
     * @return 如果已标记返回true，否则返回false
     */
    private static boolean isSystemGenerationMarked() {
        return Global.getSector().getPersistentData().containsKey(SYSTEM_GENERATED_KEY);
    }

    /**
     * 标记Vortex星系已生成
     */
    private static void markSystemGenerated() {
        Global.getSector().getPersistentData().put(SYSTEM_GENERATED_KEY, true);
    }

    /**
     * 智能生成扭蛋星系
     * 检查星系是否存在，如果不存在且未标记过则生成新星系
     * 支持中途加入mod的情况
     */
    public static void initializeGachaMobSystem() {
        if (isSystemGenerationMarked()) {
            return;
        }

        if (isVortexSystemExists()) {
            markSystemGenerated();
            return;
        }

        genGachaMobSystem();
    }

    /**
     * 创建大和号残骸
     * 
     * @param system          星系
     * @param derelictStation 废弃孢子母舰（作为轨道中心）
     * @return 创建的残骸实体，失败返回 null
     */
    private static SectorEntityToken createYamatoDerelict(StarSystemAPI system, SectorEntityToken derelictStation) {
        // 创建残骸数据
        com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin.DerelictShipData dparams = new com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin.DerelictShipData(
                new com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.PerShipData(
                        "Moci_Yamato_Base",
                        com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.ShipCondition.PRISTINE),
                true);

        // 生成残骸
        SectorEntityToken yamatoWreck = BaseThemeGenerator.addSalvageEntity(
                system,
                com.fs.starfarer.api.impl.campaign.ids.Entities.WRECK,
                Factions.NEUTRAL,
                dparams);

        if (yamatoWreck == null) {
            Global.getLogger(Moci_GachaMobManager.class).error("BaseThemeGenerator.addSalvageEntity 返回 null");
            return null;
        }

        // 设置轨道：围绕废弃孢子母舰运行
        yamatoWreck.setCircularOrbit(
                derelictStation,
                Misc.random.nextFloat() * 360f, // 随机初始角度
                derelictStation.getRadius() + 150f, // 距离孢子母舰150单位
                120f // 轨道周期120天
        );

        // 设置名称和ID
        yamatoWreck.setName("大和号残骸");
        yamatoWreck.setId("moci_yamato_derelict");

        // 添加唯一标签用于rules.csv匹配
        yamatoWreck.addTag("moci_yamato_derelict");
        
        // 设置唯一的内存标记，用于 rules.csv 精确匹配
        yamatoWreck.getMemoryWithoutUpdate().set("$moci_yamato_wreck", true);

        yamatoWreck.getMemoryWithoutUpdate().set(MemFlags.SALVAGE_SEED, Misc.random.nextLong());

        // 标记已生成
        Global.getSector().getPersistentData().put(YAMATO_SPAWNED_KEY, true);

        return yamatoWreck;
    }

    /**
     * 生成扭蛋星系的核心逻辑
     */
    @Deprecated
    public static void genGachaMobSystem() {
        // === 创建自定义Vortex星系 ===
        String name = "Vortex";
        StarSystemAPI system = Global.getSector().createStarSystem(name);
        system.setProcgen(true);
        system.getLocation().set(-7651, 18321); // 星系在星图上的坐标位置
        system.setBackgroundTextureFilename("graphics/backgrounds/background6.jpg"); // 背景纹理
        // system.setLightColor(new Color(81, 103, 124)); // 星系光照颜色

        // 创建中心天体（星云中心，非恒星）
        PlanetAPI star = system.initStar(name, "nebula_center_old", 100, 100);

        // === 星系类型和行星配置 ===
        system.setType(StarSystemGenerator.StarSystemType.NEBULA); // 设为星云类型星系

        // 添加各个行星，参数：(名称, 轨道中心, 显示名称, 行星类型, 初始角度, 半径, 轨道距离, 轨道周期)
        system.addPlanet("Crimtex", star, "Crimtex", Planets.FROZEN, StarSystemGenerator.random.nextInt(360), 34, 3313,
                198);
        PlanetAPI Punistex = system.addPlanet("Punistex", star, "Punistex", Planets.ICE_GIANT,
                StarSystemGenerator.random.nextInt(360), 215, 5450, 325);
        PlanetAPI Hoptex = system.addPlanet("Hoptex", star, "Hoptex", Planets.CRYOVOLCANIC,
                StarSystemGenerator.random.nextInt(360), 120, 7230, 365);

        system.addPlanet("Despatex", star, "Despatex", Planets.ROCKY_ICE, StarSystemGenerator.random.nextInt(360), 55,
                9100, 680);

        // 添加卫星
        system.addPlanet("Punistex_1", Punistex, "Punistex_1", Planets.ROCKY_ICE,
                StarSystemGenerator.random.nextInt(360), 25, 300, 60);

        system.addPlanet("Hoptex_1", Hoptex, "Hoptex_1", Planets.ROCKY_ICE, StarSystemGenerator.random.nextInt(360), 22,
                512, 31);

        // === 扭蛋实体固定位置设置 ===
        // 创建扭蛋核心实体
        SectorEntityToken entity = system.addCustomEntity("moci_gacha_entity", "沙仑的玫瑰", "moci_gacha_entity",
                Factions.NEUTRAL);

        // 设置固定的静态位置：坐标(1500, 1000)
        entity.getLocation().set(1500, 1000);

        // 设置为废弃空间站市场，提供标准的空间站功能
        com.fs.starfarer.api.util.Misc.setAbandonedStationMarket("moci_gacha_entity", entity);

        // 在空间站仓库中添加Gundam机甲（使用变体ID）
        entity.getMarket().getSubmarket(com.fs.starfarer.api.impl.campaign.ids.Submarkets.SUBMARKET_STORAGE)
                .getCargo()
                .addMothballedShip(com.fs.starfarer.api.fleet.FleetMemberType.SHIP, "Moci_RX_78_2_Gundam", null);

        // 添加一些相关物资到仓库
        entity.getMarket().getSubmarket(com.fs.starfarer.api.impl.campaign.ids.Submarkets.SUBMARKET_STORAGE)
                .getCargo().addCommodity(com.fs.starfarer.api.impl.campaign.ids.Commodities.SUPPLIES, 500);
        entity.getMarket().getSubmarket(com.fs.starfarer.api.impl.campaign.ids.Submarkets.SUBMARKET_STORAGE)
                .getCargo().addCommodity(com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL, 500);
        entity.getMarket().getSubmarket(com.fs.starfarer.api.impl.campaign.ids.Submarkets.SUBMARKET_STORAGE)
                .getCargo().addCommodity(com.fs.starfarer.api.impl.campaign.ids.Commodities.HEAVY_MACHINERY, 50);

        // === 添加废弃孢子母舰 ===
        // 在星系外围（距离中心约8000距离）放置废弃孢子母舰
        Vector2f stationLocation = new Vector2f();
        stationLocation.x = 6500f; // 距离中心较远的位置
        stationLocation.y = -4500f;
        SectorEntityToken derelictStation = system.addCustomEntity("vortex_derelict_station", "废弃的孢子母舰",
                com.fs.starfarer.api.impl.campaign.ids.Entities.DERELICT_MOTHERSHIP, Factions.NEUTRAL);
        derelictStation.getLocation().set(stationLocation);

        // === 添加大和号残骸（围绕废弃孢子母舰轨道）===
        SectorEntityToken yamatoWreck = createYamatoDerelict(system, derelictStation);
        if (yamatoWreck != null) {
            Global.getLogger(Moci_GachaMobManager.class).info("大和号残骸生成成功！");
        } else {
            Global.getLogger(Moci_GachaMobManager.class).warn("大和号残骸生成失败");
        }

        // 自动生成超空间航点
        system.autogenerateHyperspaceJumpPoints(true, true, true);

        system.getCenter().addTag(Tags.AMBIENT_LS); // 添加环境光源标签
        system.addTag(Tags.SYSTEM_ALREADY_USED_FOR_STORY); // 添加星系标签
        system.getCenter().getMemoryWithoutUpdate().set(PirateBaseManager.RECENTLY_USED_FOR_BASE, true);

        // === 星云地形编辑 ===
        HyperspaceTerrainPlugin terrain = (HyperspaceTerrainPlugin) Misc.getHyperspaceTerrain().getPlugin();
        NebulaEditor editor = new NebulaEditor(terrain);
        Vector2f location = system.getLocation();
        editor.clearArc(location.x, location.y, 0, 1000f, 0, 360, 0.25f); // 清理1000单位半径的圆形区域

        // === 移除恒星，创建星云中心 ===
        system.removeEntity(star);
        StarCoronaTerrainPlugin coronaPlugin = Misc.getCoronaFor(star);
        if (coronaPlugin != null) {
            system.removeEntity(coronaPlugin.getEntity());
        }
        system.setStar(null);
        system.initNonStarCenter(); // 初始化非恒星中心

        // 重新设置所有实体的轨道，使其围绕星系中心而非被移除的恒星
        for (SectorEntityToken sectorEntity : system.getAllEntities()) {
            if (sectorEntity.getOrbitFocus() == star ||
                    sectorEntity.getOrbitFocus() == system.getCenter()) {
                sectorEntity.setOrbit(null);
            }
        }

        system.setStar(star);

        // 加入不安全标签防止赏金刷新在这里
        system.addTag("theme_unsafe");

        // 标记星系生成完成
        markSystemGenerated();
    }
}
