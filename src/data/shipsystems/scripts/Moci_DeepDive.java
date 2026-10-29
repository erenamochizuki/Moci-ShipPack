package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;

/**
 * 深潜战术系统（Moci版）
 *
 * 功能目标：
 * 1. 激活后进入“不可碰撞 + 难以被锁定”的深潜状态。
 * 2. 激活期间临时免疫船体伤害；机动加成由磁流体控制系统船插负责。
 * 3. 通过自定义标记 + 引擎插件，强制清理敌方对本舰的目标锁定与自动开火。
 *
 * 设计说明：
 * - 使用自定义深潜标记，不进入原生 isPhased 流程。
 * - 所有核心步骤均使用中文注释，便于后续维护与二次开发。
 */
public class Moci_DeepDive extends BaseShipSystemScript {

    /** 供 AI/插件识别“当前舰船是否处于深潜状态”的自定义数据键。 */
    public static final String DEEP_DIVE_FLAG_KEY = "Moci_DeepDive_Key";

    /** 玩家方视觉透明倍率。 */
    private static final float PLAYER_ALPHA_MULT = 0.75f;
    /** 非玩家方视觉透明倍率。 */
    private static final float ENEMY_ALPHA_MULT = 0.95f;

    /** 深潜期间装甲修复比例（每秒按 ShipAPI 舰体装甲值百分比计算）。 */
    private static final float ARMOR_REPAIR_PERCENT_PER_SECOND = 2.5f;
    /** 深潜期间装甲修复上限（每秒固定值）。 */
    private static final float ARMOR_REPAIR_FLAT_CAP_PER_SECOND = 100f;
    /** 玩家状态栏显示键（装甲修复）。 */
    private static final String STATUS_KEY_ARMOR_REPAIR = "moci_deep_dive_status_armor_repair";

    /** 当前系统所属舰船引用。 */
    private ShipAPI ship;
    /** 仅在首次 apply 时记录原始船体尺寸，便于退出深潜时恢复。 */
    private boolean initialized = false;
    /** 原始船体尺寸缓存。 */
    private HullSize originalHullSize;
    /** 每次进入深潜前记录碰撞类型，unapply 也能独立恢复。 */
    private CollisionClass originalCollision;
    /** 是否在本次深潜期间对舰船施加了 drone/fighter 伪装。 */
    private boolean spoofedAsDroneFighter = false;

    /**
     * 获取舰船上的深潜系统实例。
     * 优先检查相位披风槽，其次检查普通系统槽。
     */
    public static ShipSystemAPI getDeepDiveSystem(ShipAPI ship) {
        if (ship == null) {
            return null;
        }

        ShipSystemAPI system = ship.getPhaseCloak();
        if (system != null && "Moci_DeepDive".equals(system.getId())) {
            return system;
        }

        system = ship.getSystem();
        if (system != null && "Moci_DeepDive".equals(system.getId())) {
            return system;
        }

        return null;
    }

    /**
     * 判断舰船当前是否处于深潜生效阶段。
     * 既检查 customData 标记，也检查系统自身状态，避免状态切换边缘帧漏判。
     */
    public static boolean isDeepDiveActive(ShipAPI ship) {
        if (ship == null) {
            return false;
        }

        Object flag = ship.getCustomData().get(DEEP_DIVE_FLAG_KEY);
        if (flag instanceof Boolean && (Boolean) flag) {
            return true;
        }

        ShipSystemAPI system = getDeepDiveSystem(ship);
        return system != null && (system.isOn()
                || system.isActive()
                || system.isChargeup()
                || system.isChargedown());
    }

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        // 基础实体校验：只对舰船实体生效。
        if (!(stats.getEntity() instanceof ShipAPI)) {
            return;
        }
        ship = (ShipAPI) stats.getEntity();

        // 首次进入时记录原始尺寸，避免反复覆盖。
        if (!initialized) {
            initialized = true;
            originalHullSize = ship.getHullSize();
        }

        CombatEngineAPI engine = Global.getCombatEngine();
        Moci_DeepDiveEnginePlugin.getInstance(engine);
        Moci_DeepDiveWeaponControl.attach(ship);
        ShipSystemAPI cloak = ship.getPhaseCloak();
        if (cloak == null) {
            cloak = ship.getSystem();
        }

        // 空闲/冷却阶段：退出深潜状态并恢复碰撞类型。
        if (state == State.IDLE || state == State.COOLDOWN) {
            unapply(stats, id);
            return;
        }

        // 充能/激活/退场阶段：应用深潜效果。
        if (state == State.IN || state == State.ACTIVE || state == State.OUT) {
            if (originalCollision == null) {
                originalCollision = ship.getCollisionClass();
            }
            // 取消实体碰撞，使舰船在深潜期间不可被常规碰撞阻挡。
            if (ship.getCollisionClass() != CollisionClass.NONE) {
                ship.setCollisionClass(CollisionClass.NONE);
            }

            // 视觉淡化（玩家与非玩家侧使用不同淡化倍率）。
            float alphaMult = (ship.getOwner() == 0) ? PLAYER_ALPHA_MULT : ENEMY_ALPHA_MULT;
            ship.setExtraAlphaMult(Math.max(0f, 1f - alphaMult * effectLevel));
            ship.setApplyExtraAlphaToEngines(true);

            // 隐藏友军覆盖层。
            ship.setForceHideFFOverlay(true);

            // 自动驾驶接管玩家舰时，若被标记为 drone/fighter，可能出现 AI 无法正常接管的问题。
            // 因此仅对非玩家舰保留该伪装，玩家舰始终保持原始舰船类别。
            boolean isPlayerShip = engine != null && ship == engine.getPlayerShip();
            // TDD 保持原始舰级和舰船 AI 身份，避免原版攻击模块按战机策略作战。
            // 隐身仍由无碰撞、自定义标记和反锁定监听器负责。
            if (!isPlayerShip && !Moci_DeepDiveWeaponControl.isTdd(ship)) {
                ship.setDrone(true);
                ship.setHullSize(HullSize.FIGHTER);
                spoofedAsDroneFighter = true;
            } else {
                ship.setDrone(false);
                if (originalHullSize != null) {
                    ship.setHullSize(originalHullSize);
                }
                spoofedAsDroneFighter = false;
            }

            // 写入深潜状态标记，供 AI 与插件联动识别。
            ship.setCustomData(DEEP_DIVE_FLAG_KEY, true);
            Moci_DeepDiveWeaponControl.suppressSurfaceWeapons(ship);

            // 若玩家当前锁定该目标，则立即清除目标锁定。
            if (engine != null && engine.getPlayerShip() != null && engine.getPlayerShip().getShipTarget() == ship) {
                engine.getPlayerShip().setShipTarget(null);
            }

            // 深潜期间（effectLevel > 0）免疫船体伤害。
            if (effectLevel > 0f) {
                stats.getHullDamageTakenMult().modifyMult(id, 0f);
            }

            // 深潜启用期间持续修复装甲：每秒回复装甲值 2.5% 或 100（取较小值）。
            if (engine != null && !engine.isPaused() && effectLevel > 0f) {
                repairArmorWhileDeepDive(ship, engine.getElapsedInLastFrame());
            }

            // 系统仅显示装甲修复；机动状态由提供加成的船插显示。
            if (engine != null && ship == engine.getPlayerShip() && effectLevel > 0f) {
                String armorRepairStatus = getArmorRepairStatusText(ship);
                engine.maintainStatusForPlayerShip(
                        STATUS_KEY_ARMOR_REPAIR,
                        "graphics/icons/hullsys/phase_cloak.png",
                        "深潜战术",
                        armorRepairStatus,
                        false
                );
            }

        }

    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        // 基础实体校验：只对舰船实体生效。
        if (!(stats.getEntity() instanceof ShipAPI)) {
            return;
        }
        ship = (ShipAPI) stats.getEntity();

        // 确保反锁定插件已注册（与原实现一致）。
        Moci_DeepDiveEnginePlugin.getInstance(Global.getCombatEngine());

        ShipSystemAPI cloak = ship.getPhaseCloak();
        if (cloak == null) {
            cloak = ship.getSystem();
        }

        // 恢复视觉与系统状态。
        ship.setAlphaMult(1f);
        ship.setExtraAlphaMult(1f);
        if (originalCollision != null) {
            ship.setCollisionClass(originalCollision);
            originalCollision = null;
        }
        stats.getHullDamageTakenMult().unmodify(id);
        ship.setForceHideFFOverlay(false);
        if (spoofedAsDroneFighter) {
            ship.setDrone(false);
        }
        spoofedAsDroneFighter = false;

        // 恢复原始船体尺寸。
        if (originalHullSize != null) {
            ship.setHullSize(originalHullSize);
        }

        // 清除深潜标记。
        ship.setCustomData(DEEP_DIVE_FLAG_KEY, false);

        // 清理旧版系统可能遗留的机动修改，不影响船插独立 ID 的加成。
        ship.getMutableStats().getMaxSpeed().unmodify(id);
        ship.getMutableStats().getAcceleration().unmodify(id);
        ship.getMutableStats().getDeceleration().unmodify(id);
        ship.getMutableStats().getMaxTurnRate().unmodify(id);
        ship.getMutableStats().getTurnAcceleration().unmodify(id);
    }

    /**
     * 深潜期间装甲修复逻辑。
     *
     * 规则：
     * - 每秒最多修复 min(装甲值 * 2.5%, 100)。
     * - 修复量按各装甲格“缺失值占比”分配，避免只修某一侧。
     */
    private void repairArmorWhileDeepDive(ShipAPI ship, float amount) {
        if (ship == null || amount <= 0f || !ship.isAlive()) {
            return;
        }

        ArmorGridAPI armorGrid = ship.getArmorGrid();
        if (armorGrid == null) {
            return;
        }

        float[][] grid = armorGrid.getGrid();
        if (grid == null || grid.length == 0 || grid[0].length == 0) {
            return;
        }

        int width = grid.length;
        int height = grid[0].length;
        float maxArmorInCell = armorGrid.getMaxArmorInCell();

        float repairPerSecond = getArmorRepairPerSecond(ship);
        if (repairPerSecond <= 0f) {
            return;
        }

        float totalMissing = 0f;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                totalMissing += Math.max(0f, maxArmorInCell - grid[x][y]);
            }
        }
        if (totalMissing <= 0f) {
            return;
        }

        float repairBudget = Math.min(repairPerSecond * amount, totalMissing);
        if (repairBudget <= 0f) {
            return;
        }

        float repaired = 0f;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                float current = grid[x][y];
                float missing = Math.max(0f, maxArmorInCell - current);
                if (missing <= 0f) {
                    continue;
                }

                float toRepair = repairBudget * (missing / totalMissing);
                if (toRepair <= 0f) {
                    continue;
                }

                armorGrid.setArmorValue(x, y, Math.min(maxArmorInCell, current + toRepair));
                repaired += toRepair;
            }
        }

        if (repaired > 0f) {
            ship.syncWithArmorGridState();
        }
    }

    /**
     * 计算当前舰船的实际装甲修复速率（点/秒）。
     */
    private float getArmorRepairPerSecond(ShipAPI ship) {
        if (ship == null) {
            return 0f;
        }
        float armorRating = ship.getHullSpec() != null ? ship.getHullSpec().getArmorRating() : 0f;
        return Math.min(armorRating * (ARMOR_REPAIR_PERCENT_PER_SECOND / 100f), ARMOR_REPAIR_FLAT_CAP_PER_SECOND);
    }

    /**
     * 装甲修复速率状态文本。
     */
    private String getArmorRepairStatusText(ShipAPI ship) {
        return String.format("装甲修复中：%.1f/秒", getArmorRepairPerSecond(ship));
    }

    @Override
    public StatusData getStatusData(int index, State state, float effectLevel) {
        if (index == 0) {
            return new StatusData(getArmorRepairStatusText(ship), false);
        }
        return null;
    }
}
