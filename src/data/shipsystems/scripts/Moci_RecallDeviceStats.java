package data.shipsystems.scripts;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.HashMap;
import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import org.lazywizard.lazylib.MathUtils;
import org.magiclib.util.MagicLensFlare;

/**
 * 紧急召回装置 - 战术系统效果
 * 功能：自动识别并传送召回受损的战机到母舰附近
 * 
 * 主要特性：
 * 1. 自动扫描需要救援的友方战机
 * 2. 传送战机到母舰附近安全位置
 * 3. 支持LunaLib配置自定义召回阈值
 * 4. 防止多个母舰同时召回同一战机
 */
public class Moci_RecallDeviceStats extends BaseShipSystemScript {

    // ==================== 可配置参数 ====================

    // 召回条件阈值
    private static final float DEFAULT_HULL_THRESHOLD = 0.50f; // 默认船体阈值：50%
    private static final float DEFAULT_CR_THRESHOLD = 0.30f; // 默认CR阈值：30%
    private static final float OVERLOAD_HULL_THRESHOLD = 0.50f; // 过载时的船体阈值：50%

    // 距离相关
    private static final float MIN_RECALL_DISTANCE = 1000f; // 最小召回距离：1000米（太近不召回）
    private static final float SAFE_ZONE_RADIUS = 1000f; // 安全区半径：1000米（其他母舰附近）
    private static final float TELEPORT_RADIUS = 500f; // 传送落点半径：500米（母舰周围）

    // 战机状态要求
    private static final boolean REQUIRE_ALIVE = true; // 必须存活（不是残骸）
    private static final boolean REQUIRE_NOT_LOCKED = true; // 必须控制未锁定
    private static final boolean REQUIRE_NOT_RETREATING = true; // 必须未在撤退
    private static final boolean CHECK_NEAR_OTHER_CARRIER = true; // 检查是否靠近其他母舰

    // 传送后效果
    private static final float FORCED_OVERLOAD_DURATION = 1f; // 强制过载时长：1秒
    private static final boolean REPAIR_ENGINES = true; // 是否修复引擎
    private static final boolean DEACTIVATE_SYSTEM = true; // 是否关闭战术系统

    // 战机识别
    private static final String STRIKE_CRAFT_HULLMOD_ID = "Moci_MobileSuits"; // 战机船插ID（原版战机标识）

    // ==================== 视觉效果 ====================

    public static final Object KEY_JITTER = new Object();
    public static final Color JITTER_COLOR = new Color(100, 165, 255, 155);
    private static final Color BASIC_FLASH_COLOR = new Color(184, 155, 218, 200);
    private static final Color BASIC_GLOW_COLOR = new Color(180, 161, 255, 200);

    /**
     * 通过值查找键（用于反向查找战机归属）
     */
    public static String getKeyByValue(Map<String, String> map, String value) {
        for (Entry<String, String> entry : map.entrySet()) {
            if (entry.getValue().equals(value)) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        ShipAPI ship;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
        } else {
            return;
        }

        ShipAPI fighter = null;
        if (effectLevel > 0) {
            float jitterLevel = effectLevel;
            final String fightersKey = ship.getId() + "_recall_device_target";
            boolean firstTime = false;

            // 优先召回玩家手动选中的目标
            ShipAPI target = ship.getShipTarget();
            if (target != null && target.getOwner() == ship.getOwner()
                    && target.getVariant().hasHullMod(STRIKE_CRAFT_HULLMOD_ID)) {
                fighter = target;
            } else {
                // 自动选择需要救援的战机
                List<ShipAPI> fighters;
                String recalledKey = "Moci_recalledFighters_" + ship.getOwner();
                HashMap<String, String> recalledFighters;

                // 初始化或获取已召回战机的记录表
                if (Global.getCombatEngine().getCustomData().get(recalledKey) == null) {
                    recalledFighters = new HashMap<String, String>();
                    Global.getCombatEngine().getCustomData().put(recalledKey, recalledFighters);
                } else {
                    recalledFighters = (HashMap<String, String>) Global.getCombatEngine().getCustomData()
                            .get(recalledKey);
                }

                // 首次激活时获取需要救援的战机列表
                if (!Global.getCombatEngine().getCustomData().containsKey(fightersKey)) {
                    fighters = getFighters(ship);
                    Global.getCombatEngine().getCustomData().put(fightersKey, fighters);
                    firstTime = true;
                } else {
                    fighters = (List<ShipAPI>) Global.getCombatEngine().getCustomData().get(fightersKey);
                }

                if (fighters == null) {
                    fighters = new ArrayList<ShipAPI>();
                }

                // 从列表中选择一个战机进行召回
                for (ShipAPI fighter2 : fighters) {
                    if (fighter2.isHulk()) {
                        continue;
                    }

                    // 检查这个战机是否已经被其他母舰召回
                    if (recalledFighters.get(fighter2.getId()) != null) {
                        // 如果不是我们的目标，跳过
                        if (!recalledFighters.get(fighter2.getId()).equals(ship.getId())) {
                            continue;
                        }
                    } else {
                        // 标记这个战机正在被当前母舰召回
                        recalledFighters.put(fighter2.getId(), ship.getId());
                    }
                    fighter = fighter2;
                    break;
                }
            }

            if (fighter == null) {
                return;
            }

            // 计算抖动特效范围
            float maxRangeBonus = fighter.getCollisionRadius() * 1f;
            float jitterRangeBonus = 5f + jitterLevel * maxRangeBonus;

            // 首次激活时播放音效
            if (firstTime) {
                Global.getSoundPlayer().playSound("system_phase_skimmer", 1f, 0.5f, fighter.getLocation(),
                        fighter.getVelocity());
            }

            // 施加抖动特效（视觉反馈）
            fighter.setJitter(KEY_JITTER, JITTER_COLOR, jitterLevel, 10, 0f, jitterRangeBonus);

            // 激活过程中逐渐降低战机透明度（fade out效果）
            if (state == State.IN) {
                float alpha = 1f - effectLevel * 0.5f;
                fighter.setExtraAlphaMult(alpha);
            }

            // 完全激活时执行传送
            if (effectLevel == 1) {
                if (ship != null) {
                    // 强制战机过载（防止立即返回战场）
                    fighter.getFluxTracker().forceOverload(FORCED_OVERLOAD_DURATION);

                    // 关闭战机的战术系统
                    if (DEACTIVATE_SYSTEM && fighter.getSystem() != null) {
                        fighter.getSystem().deactivate();
                    }

                    // 传送到母舰附近的随机位置
                    Vector2f teleportTarget = MathUtils.getRandomPointInCircle(ship.getLocation(), TELEPORT_RADIUS);
                    fighter.getLocation().set(teleportTarget);

                    // 恢复战机透明度
                    fighter.setExtraAlphaMult(1f);

                    // 修复战机的所有引擎
                    if (REPAIR_ENGINES) {
                        for (ShipEngineAPI engine : fighter.getEngineController().getShipEngines()) {
                            engine.repair();
                        }
                    }

                    // 播放传送特效
                    MagicLensFlare.createSharpFlare(Global.getCombatEngine(), fighter, fighter.getLocation(), 5f, 100f,
                            0f, BASIC_FLASH_COLOR, Color.white);
                    Global.getCombatEngine().addSmoothParticle(fighter.getLocation(), new Vector2f(), 100f, 0.7f, 0.1f,
                            BASIC_FLASH_COLOR);
                    Global.getCombatEngine().addSmoothParticle(fighter.getLocation(), new Vector2f(), 150f, 0.7f, 1f,
                            BASIC_GLOW_COLOR);
                    Global.getCombatEngine().addSmoothParticle(fighter.getLocation(), new Vector2f(), 200f, 1f, 0.05f,
                            Color.white);
                }
            }
        }
    }

    /**
     * 检查战机是否在其他母舰附近
     * 用于避免召回已经在安全区域的战机
     */
    public static boolean isStrikeCraftNearCarrier(ShipAPI ship) {
        for (ShipAPI carrier : Global.getCombatEngine().getShips()) {
            if (ship.getOwner() != carrier.getOwner()) {
                continue;
            }
            if (!carrier.hasLaunchBays()) {
                continue;
            }
            if (carrier.getVariant().hasHullMod(STRIKE_CRAFT_HULLMOD_ID)) {
                continue;
            }

            if (carrier.isAlive() && MathUtils.getDistanceSquared(ship.getLocation(), carrier.getLocation()) < SAFE_ZONE_RADIUS * SAFE_ZONE_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取需要救援的战机列表
     * 
     * 召回条件（所有条件都可在类顶部配置）：
     * 1. 健康状态：船体低于阈值 或 CR低于阈值 或 (过载且受损)
     * 2. 距离要求：距离母舰超过最小召回距离
     * 3. 控制状态：控制未被锁定（如果启用检查）
     * 4. 行为状态：未在撤退（如果启用检查）
     * 5. 位置安全：附近没有其他母舰（如果启用检查）
     * 
     * 控制锁定说明：
     * - controlsLocked() 表示战机的控制权被某个系统锁定
     * - 例如：被牵引光束抓住、被某些特殊技能控制、正在执行强制命令等
     * - 锁定状态下战机无法响应正常命令，传送可能失败或产生bug
     * - 因此需要检查控制权是否自由，确保可以安全传送
     */
    public static List<ShipAPI> getFighters(ShipAPI carrier) {
        List<ShipAPI> result = new ArrayList<ShipAPI>();

        for (ShipAPI ship : Global.getCombatEngine().getShips()) {
            // 必须是同阵营
            if (ship.getOwner() != carrier.getOwner()) {
                continue;
            }
            // 必须是战机
            if (!ship.getVariant().hasHullMod(STRIKE_CRAFT_HULLMOD_ID)) {
                continue;
            }

            // 使用默认阈值
            float hullThreshold = DEFAULT_HULL_THRESHOLD;
            float crThreshold = DEFAULT_CR_THRESHOLD;

            // 判断是否需要救援（健康状态检查）
            boolean needsRescue = ship.getHullLevel() <= hullThreshold ||
                    ship.getCurrentCR() <= crThreshold ||
                    (ship.getHullLevel() <= OVERLOAD_HULL_THRESHOLD && ship.getFluxTracker().isOverloaded());

            if (!needsRescue) {
                continue;
            }

            // 状态检查：必须存活
            if (REQUIRE_ALIVE && (!ship.isAlive() || ship.isHulk())) {
                continue;
            }

            // 距离检查：必须远离母舰
            if (MathUtils.getDistanceSquared(ship.getLocation(), carrier.getLocation()) <= MIN_RECALL_DISTANCE * MIN_RECALL_DISTANCE) {
                continue;
            }

            // 控制检查：控制权必须自由（未被锁定）
            if (REQUIRE_NOT_LOCKED && ship.controlsLocked()) {
                continue;
            }

            // 行为检查：不能正在撤退
            if (REQUIRE_NOT_RETREATING && ship.isRetreating()) {
                continue;
            }

            // 位置检查：附近不能有其他母舰
            if (CHECK_NEAR_OTHER_CARRIER && isStrikeCraftNearCarrier(ship)) {
                continue;
            }

            // 通过所有检查，加入召回列表
            result.add(ship);
        }

        return result;
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        ShipAPI ship = null;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
        } else {
            return;
        }

        // 清理召回记录
        String recalledKey = "armaa_recalledFighters_" + ship.getOwner();
        final String fightersKey = ship.getId() + "_recall_device_target";

        if (Global.getCombatEngine().getCustomData().containsKey(fightersKey)) {
            Global.getCombatEngine().getCustomData().remove(fightersKey);
        }

        HashMap<String, String> recalledFighters = null;
        if (Global.getCombatEngine().getCustomData().get(recalledKey) != null) {
            recalledFighters = (HashMap<String, String>) Global.getCombatEngine().getCustomData().get(recalledKey);

            String fighterToRemove = getKeyByValue(recalledFighters, ship.getId());
            if (recalledFighters != null) {
                recalledFighters.remove(fighterToRemove);
            }
            Global.getCombatEngine().getCustomData().put(recalledKey, recalledFighters);
        }
    }

    @Override
    public StatusData getStatusData(int index, State state, float effectLevel) {
        return null;
    }
}
