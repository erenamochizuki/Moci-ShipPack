package data.hullmods;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lazywizard.lazylib.combat.AIUtils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.ai.Moci_ReturnToCarrierModule;
import data.scripts.ships.ai.Moci_MechTurretAI;
import data.scripts.util.Moci_TextLoader;

/**
 * Moci_LandingSequence - 智能着陆序列管理器
 * 
 * 这个船体模组为机动战士（MS）提供自动着陆和修理功能。
 * 
 * # AI操作的着陆条件
 * 
 * AI控制的MS会在以下情况下自动触发着陆序列：
 * 
 * 1. 血量条件：结构不高于70%；部署结构50%至不足70%的机体改为50%
 * 2. 战备值条件：当前CR低于部署时CR的50%（最低40%）
 * 3. 弹药条件：所有主武器（最大尺寸的有限弹药武器）的弹药都低于或等于25%
 * 
 * 着陆距离限制（仅对弹药不足情况生效）：
 * - 如果所有主武器都打空：按 10000f 进行全图搜索
 * - 如果部分主武器有弹药：基础搜索距离800单位 + 每个弹药为0的非主武器增加800单位
 * - 如果是血量或CR不足：按 10000f 进行全图搜索
 * 
 * 部署CR低于40%或部署结构低于50%的机体，本场战斗禁止整备。
 * 整备完成或中止后需等待至少3秒才能再次整备。
 *
 * AI会自动搜索最近的可用航母（有空闲修理舱的友军舰船），并启动着陆AI进行自动着陆。
 * 
 * # 玩家手动着陆操作
 * 
 * 玩家可以通过以下步骤手动控制MS着陆：
 * 
 * 1. 按 R 键锁定目标：选择一艘有整备湾（修理舱）的友军航母作为目标
 * 2. 按 U 键启用自动驾驶：启用自动驾驶后，MS会自动执行着陆序列
 * 3. 等待着陆完成：着陆AI会自动导航MS飞向航母并完成着陆
 * 
 * 注意事项：
 * - 目标航母必须有可用的修理舱（未被占用）
 * - 必须启用自动驾驶才能执行着陆，纯手动操作无法着陆
 * - 如果目标航母不适合着陆，会显示"降落导航错误"提示
 * - 着陆过程中可以随时取消自动驾驶来中止着陆
 * 
 * # 技术实现
 * 
 * - 使用 Moci_LandingAI 替换舰船的默认AI来执行着陆序列
 * - 通过 Moci_RepairBayScript 管理航母的修理舱状态
 * - 支持标准舰载机式着陆（带动画）和直接固定位置着陆（无动画）两种模式
 * - 着陆完成后会自动恢复原始AI状态
 */
public class Moci_LandingSequence extends BaseHullMod {
    private static final boolean ENABLE_DEBUG_LOGS = false;
    private static final String TEXT_ID = "Moci_LandingSequence";
    private static final String MECH_TURRET_TEXT_ID = "Moci_MechTurretAI";
    private static final String LOG_PREFIX = "[MOCI_REFIT_ROUTE] ";
    private static final String WINGMAN_LOG_PREFIX = "[僚机整备] ";
    private static final float REFIT_SEARCH_INTERVAL_MIN = 0.8f;
    private static final float REFIT_SEARCH_INTERVAL_MAX = 1.25f;
    private static final float FULL_MAP_REFIT_SEARCH_DISTANCE = 10000f;
    private static final String REFIT_SEARCH_CACHE_KEY = "Moci_AutomaticRefitTargetCache";
    private static final String REFIT_SEARCH_NEXT_TIME_KEY = "Moci_AutomaticRefitTargetNextCheck";
    private static final String RETURN_TO_CARRIER_MODULE_KEY = "Moci_ReturnToCarrierModuleInstance";
    private static final String WINGMAN_REFIT_LAST_NEED_STATE_KEY = "Moci_WingmanRefitLastNeedState";
    private static final String WINGMAN_REFIT_LAST_SEARCH_TEXT_KEY = "Moci_WingmanRefitLastSearchText";
    private static final String WINGMAN_REFIT_PENDING_SEARCH_TEXT_KEY = "Moci_WingmanRefitPendingSearchText";
    private static final String MAIN_AMMO_EMPTY_AT_KEY = "Moci_MainAmmoEmptyAt";
    private static final String REFIT_TRIGGER_DETAIL_KEY = "Moci_RefitTriggerDetail";
    public static final String LAST_LANDING_CARRIER_ID_KEY = "Moci_LastLandingCarrierId";
    public static final String LAST_LANDING_BAY_SLOT_ID_KEY = "Moci_LastLandingBaySlotId";
    public static final String LAST_LANDING_LOCATION_KEY = "Moci_LastLandingLocation";

    /**
     * 是否在整备判定中忽略内置武器。
     *
     * 旧逻辑等价于 true：
     * - SMALL / MEDIUM 的内置武器完全不参与“是否需要整备”的判断
     * - LARGE 的内置武器仍然参与判断
     *
     * 现在按你的需求，默认改为 false：
     * - 关闭该开关后，内置武器和普通武器一样参与整备判定
     * - 也就是说，内置武器的缺弹/空弹会影响是否返航整备
     */
    public static final boolean SKIP_BUILT_IN_WEAPONS_FOR_REFIT = false;

    /**
     * 主要的战斗逻辑推进方法
     * 每帧调用，负责监控舰船状态并触发着陆序列
     */
    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        advanceLandingLogic(ship, amount);
    }

    /**
     * 可复用的着陆逻辑入口。
     * 供旧的 LandingSequence 船插和新的 MobileSuits ID 卡共用，
     * 避免复制整套着陆/整备判定流程。
     */
    public static void advanceLandingLogic(ShipAPI ship, float amount) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || ship == null || engine.isPaused()) return;

        // AI 被替换后会话仍然保留，玩家关闭自动驾驶时也能继续清理整备状态。
        Moci_RefitSession session = Moci_RefitSession.get(ship);
        if (session != null) session.advance();
        if (!ship.isAlive() || !engine.isEntityInPlay(ship)) return;

        Moci_MobileSuitRepairTracker tracker = Moci_MobileSuitRepairTracker.get(ship);
        if (tracker != null) {
            tracker.advance(amount);
            if (tracker.isActive()) return;
        }

        if (Moci_MechTurretAI.isDocked(ship) && areAllAmmoWeaponsEmpty(ship)) {
            Moci_MechTurretAI.releaseTurretTarget(ship);
        }

        boolean isPlayerShip = ship == engine.getPlayerShip();
        String blockedReason = getRefitBlockedReason(ship);
        if (isPlayerShip && blockedReason != null) {
            engine.maintainStatusForPlayerShip("Moci_RefitUnavailable",
                    Global.getSettings().getSpriteName("ui", "icon_tactical_bdeck"),
                    Moci_TextLoader.getText(TEXT_ID, "status.landing_nav"), blockedReason, true);
        }

        ShipAPI selectedTarget = ship.getShipTarget();
        boolean selectedFriendly = selectedTarget != null && selectedTarget.getOwner() == ship.getOwner();
        if (isPlayerShip && selectedFriendly) {
            Moci_MechTurretAI.advancePlayerNavigationModeSwitch(ship, selectedTarget);
            boolean landingAvailable = canManualLandOnTarget(ship, selectedTarget);
            boolean turretFeatureEnabled = Moci_MechTurretAI.shouldAssignTurretAI(ship);
            boolean turretAvailable = turretFeatureEnabled
                    && Moci_MechTurretAI.canUseAsTurretHost(ship, selectedTarget);
            boolean allowLanding = shouldUseRefitModeForSelectedTarget(
                    ship, selectedTarget, landingAvailable, turretAvailable);
            if (ship.getShipAI() != null && allowLanding) {
                Moci_RepairBayScript script = Moci_RepairBayHullModEffect.ensureRepairBayScript(selectedTarget);
                if (script != null) {
                    Moci_LandingAI.startLanding(ship, script.getVacancyFor(ship, null));
                }
            } else if (!turretFeatureEnabled) {
                engine.maintainStatusForPlayerShip("Moci_CarrierTarget",
                        Global.getSettings().getSpriteName("ui", "icon_tactical_bdeck"),
                        Moci_TextLoader.getText(TEXT_ID,
                                landingAvailable ? "status.landing_nav" : "status.landing_error"),
                        blockedReason != null ? blockedReason : Moci_TextLoader.getText(TEXT_ID,
                                landingAvailable ? "status.enable_autopilot" : "status.invalid_target"), true);
            }
        } else if (ship.getShipAI() != null) {
            Moci_RepairBayScript.Moci_RepairBay target = findAutomaticRefitTarget(ship);
            if (target != null && Moci_LandingAI.startLanding(ship, target) && !isPlayerShip) {
                ship.setShipTarget(target.getShip());
            }
        }
        clearReturnToCarrierModule(ship);
    }

    /** 返回展示给玩家的原因，说明该舰船为什么无法开始新一轮整备。 */
    public static String getRefitBlockedReason(ShipAPI ship) {
        switch (Moci_RefitPolicy.getBlockReason(ship)) {
            case DEPLOYMENT_CR_TOO_LOW:
                return Moci_TextLoader.getText(TEXT_ID, "status.deployment_cr_too_low",
                        "机体部署CR过低，无法整备");
            case DEPLOYMENT_HULL_TOO_LOW:
                return Moci_TextLoader.getText(TEXT_ID, "status.deployment_hull_too_low",
                        "机体部署结构值低于50%，无法整备");
            case COOLDOWN:
                Moci_MobileSuitRepairTracker tracker = Moci_MobileSuitRepairTracker.get(ship);
                return Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.refit_cooldown",
                        Moci_TextLoader.mapOf("seconds", String.format("%.1f",
                                tracker == null ? 0f : tracker.getCooldownRemaining())));
            default:
                return null;
        }
    }

    /**
     * 搜索可用的着陆点
     * 在所有友军舰船中寻找最近的可用航母修理舱
     * 新增：基于非最大尺寸空弹药武器数量的距离限制（仅对弹药不足情况生效）
     * 
     * @param ship 需要着陆的舰船
     * @return 找到的修理舱武器槽，如果没有找到则返回null
     */
    public static Moci_RepairBayScript.Moci_RepairBay searchForLand(ShipAPI ship){
        if (!Moci_RefitPolicy.canStartRefit(ship)) return null;
        ShipAPI carrier = null;
        float score = Float.MAX_VALUE;
        int allyCount = 0;
        int availableCarrierCount = 0;
        int distanceRejectedCount = 0;
        Map<String, Integer> rejectReasons = new HashMap<String, Integer>();
        
        // 检查是否因为弹药不足而需要着陆
        boolean isAmmoRefit = isAmmoRefitReason(ship);
        String preferredCarrierId = getRememberedLandingCarrierId(ship);
        String preferredBaySlotId = getRememberedLandingBaySlotId(ship);
        
        // 获取距离限制（仅在弹药不足且仍有主武器剩余弹药时应用）
        float maxSearchDistance = isAmmoRefit ? getMaxSearchDistance(ship) : FULL_MAP_REFIT_SEARCH_DISTANCE;
        String searchDistanceText = String.format("%.0f", maxSearchDistance);
        
        Global.getLogger(Moci_LandingSequence.class).debug(
            ship.getName() + " 搜索着陆点，着陆原因: " + (isAmmoRefit ? "弹药不足" : "血量/CR不足") + 
            "，最大搜索距离: " + searchDistanceText
        );
        
        // 遍历所有友军舰船
        for(ShipAPI s: AIUtils.getAlliesOnMap(ship)){
            allyCount++;
            String rejectReason = getCarrierLandingBlockReason(s);
            if (rejectReason != null) {
                incrementReasonCount(rejectReasons, rejectReason);
                continue;
            }

            float currDist = Misc.getDistance(s.getLocation(),ship.getLocation());
            
            // 应用距离限制：仅在弹药不足且距离超过限制时跳过
            if (isAmmoRefit && currDist > maxSearchDistance) {
                distanceRejectedCount++;
                incrementReasonCount(rejectReasons, "distance_limit");
                Global.getLogger(Moci_LandingSequence.class).debug(
                     ship.getName() + " 跳过距离过远的航母: " + s.getName() + 
                     " (距离: " + String.format("%.0f", currDist) + " > 限制: " + searchDistanceText + ")"
                 );
                 continue;
             }
             
            availableCarrierCount++;
            float candidateScore = currDist;
            if (preferredCarrierId != null && preferredCarrierId.equals(s.getId())) {
                candidateScore -= 1500f;
            }

            if(candidateScore < score){
                // 找到更近的可用航母
                carrier = s;
                score = candidateScore;
            }
        }

        String summary = carrier != null
                ? "搜:中 母=" + carrier.getName()
                : "搜:空 因=" + formatRejectReasons(rejectReasons);
        ship.setCustomData(WINGMAN_REFIT_PENDING_SEARCH_TEXT_KEY, summary);
        
        if(carrier == null) {
            Global.getLogger(Moci_LandingSequence.class).debug(
                ship.getName() + " 未找到可用的着陆点 (搜索距离限制: " + searchDistanceText + ")"
            );
            return null;
        }
        
        Global.getLogger(Moci_LandingSequence.class).debug(
            ship.getName() + " 找到着陆目标: " + carrier.getName() + 
            " (得分: " + String.format("%.0f", score) + ")"
        );
        
        // 返回找到的航母的空闲修理舱
        Moci_RepairBayScript script = Moci_RepairBayScript.getInstance(carrier);
        if (script == null) {
            return null;
        }
        return script.getVacancyFor(ship, preferredCarrierId != null && preferredCarrierId.equals(carrier.getId()) ? preferredBaySlotId : null);
    }

    /**
     * 实际是否应该进入整备流程。
     * 已处于炮塔模式时，除非所有有限弹药武器全部打空，否则不触发整备。
     */
    public static boolean shouldTriggerRefit(ShipAPI ship) {
        if (!needsRefit(ship)) {
            return false;
        }

        if (Moci_MechTurretAI.isDocked(ship) && !areAllAmmoWeaponsEmpty(ship)) {
            return false;
        }

        return true;
    }

    private static void clearAutomaticRefitSearchState(ShipAPI ship) {
        if (ship == null) {
            return;
        }
        ship.removeCustomData(REFIT_SEARCH_CACHE_KEY);
        ship.removeCustomData(REFIT_SEARCH_NEXT_TIME_KEY);
    }

    private static boolean isCachedRefitTargetUsable(ShipAPI ship, Moci_RepairBayScript.Moci_RepairBay bay) {
        if (bay == null) {
            return false;
        }

        ShipAPI host = bay.getShip();
        if (!isSuitableCarrierToLand(host)) {
            return false;
        }

        Moci_RepairBayScript script = Moci_RepairBayHullModEffect.ensureRepairBayScript(host);
        if (script == null) {
            return false;
        }

        Moci_RepairBayScript.Moci_RepairBayStatus status = script.getBay(bay);
        return status != null && status.isAvailableFor(ship);
    }

    private static float getNextAutomaticRefitSearchDelay() {
        return REFIT_SEARCH_INTERVAL_MIN
                + (float) Math.random() * (REFIT_SEARCH_INTERVAL_MAX - REFIT_SEARCH_INTERVAL_MIN);
    }

    /**
     * 自动整备目标查询。
     * 返回 null 说明当前不应进入自动整备，或虽然应整备但当前没有可用港位。
     */
    public static Moci_RepairBayScript.Moci_RepairBay findAutomaticRefitTarget(ShipAPI ship) {
        if (ship == null || Global.getCombatEngine() == null) {
            return null;
        }
        if (!shouldTriggerRefit(ship)) {
            clearAutomaticRefitSearchState(ship);
            return null;
        }

        Object cachedObj = ship.getCustomData().get(REFIT_SEARCH_CACHE_KEY);
        Moci_RepairBayScript.Moci_RepairBay cached =
                cachedObj instanceof Moci_RepairBayScript.Moci_RepairBay
                        ? (Moci_RepairBayScript.Moci_RepairBay) cachedObj
                        : null;
        if (cached != null && !isCachedRefitTargetUsable(ship, cached)) {
            cached = null;
            ship.removeCustomData(REFIT_SEARCH_CACHE_KEY);
        }

        float now = Global.getCombatEngine().getTotalElapsedTime(false);
        Object nextObj = ship.getCustomData().get(REFIT_SEARCH_NEXT_TIME_KEY);
        float nextTime = nextObj instanceof Float ? (Float) nextObj : -1f;

        // 搜索失败也要缓存，避免没有空闲整备位时每帧重复扫描。
        if (nextTime < 0f || now >= nextTime) {
            cached = searchForLand(ship);
            logWingmanSearchChanged(ship);
            if (cached != null) {
                ship.setCustomData(REFIT_SEARCH_CACHE_KEY, cached);
            } else {
                ship.removeCustomData(REFIT_SEARCH_CACHE_KEY);
            }
            ship.setCustomData(REFIT_SEARCH_NEXT_TIME_KEY, now + getNextAutomaticRefitSearchDelay());
        }

        return cached;
    }

    /**
     * 检查舰船及其子模块上的所有“会参与整备触发”的有限弹药武器是否已经全部清空。
     * 该判定用于炮塔模式与整备的兼容逻辑，比“主武器低弹药”更严格。
     */
    public static boolean areAllAmmoWeaponsEmpty(ShipAPI ship) {
        int ammoWeaponCount = 0;

        for (ShipAPI part : Moci_RepairBayScript.getRepairableParts(ship)) {
            for (WeaponAPI weapon : part.getAllWeapons()) {
                if (!isAmmoWeaponRelevantForRefit(weapon)) continue;
                ammoWeaponCount++;
                if (weapon.getAmmo() > 0) {
                    return false;
                }
            }
        }

        return ammoWeaponCount > 0;
    }

    /**
     * 计算最大搜索距离
     * 如果所有主武器都打空 -> 10000f 全图搜索
     * 否则：基础距离800f，每个弹药为0的非主武器增加800f
     * 
     * @param ship 需要着陆的舰船
     * @return 最大搜索距离
     */
    private static float getMaxSearchDistance(ShipAPI ship) {
        // 检查是否所有主武器都打空
        Object allEmptyObj = ship.getCustomData().get("Moci_AllMainWeaponsEmpty");
        boolean allMainWeaponsEmpty = false;
        if (allEmptyObj instanceof Boolean) {
            allMainWeaponsEmpty = (Boolean) allEmptyObj;
        }
        
        // 如果所有主武器都打空，使用全图搜索距离
        if (allMainWeaponsEmpty) {
            Global.getLogger(Moci_LandingSequence.class).debug(
                ship.getName() + " 所有主武器打空，使用全图搜索距离: " + FULL_MAP_REFIT_SEARCH_DISTANCE
            );
            return FULL_MAP_REFIT_SEARCH_DISTANCE;
        }
        
        float baseDistance = 800f; // 基础搜索距离
        float distancePerWeapon = 800f; // 每个弹药为0的非主武器增加的距离
        
        // 获取弹药为0的非主武器数量
        int emptyNonMainWeaponCount = 0;
        Object customData = ship.getCustomData().get("Moci_EmptyNonLargestWeapons");
        if (customData instanceof Integer) {
            emptyNonMainWeaponCount = (Integer) customData;
        }
        
        // 如果没有弹药为0的非主武器，使用基础距离
        if (emptyNonMainWeaponCount == 0) {
            Global.getLogger(Moci_LandingSequence.class).debug(
                ship.getName() + " 没有空弹药非主武器，使用基础搜索距离: " + baseDistance
            );
            return baseDistance;
        }
        
        // 计算实际搜索距离
        float maxDistance = baseDistance + (emptyNonMainWeaponCount * distancePerWeapon);
        
        Global.getLogger(Moci_LandingSequence.class).debug(
            ship.getName() + " 距离计算: 基础" + baseDistance + " + " + 
            emptyNonMainWeaponCount + "个空弹药非主武器×" + distancePerWeapon + " = " + maxDistance
        );
        
        return maxDistance;
    }

    /**
     * 检查舰船是否因为弹药不足而需要着陆
     * 
     * @param ship 要检查的舰船
     * @return 如果是因为弹药不足则返回true，如果是因为血量或CR则返回false
     */
    private static boolean isAmmoRefitReason(ShipAPI ship) {
        Object reason = ship.getCustomData().get("Moci_NeedsRefitReason");
        if (reason instanceof String) {
            return "AMMO".equals(reason);
        }
        return false;
    }

    /**
     * 检查指定航母是否适合着陆
     * 
     * @param carrier 候选航母
     * @return 如果航母存活且有可用修理舱则返回true
     */
    protected static boolean isSuitableCarrierToLand(ShipAPI carrier){
        return getCarrierLandingBlockReason(carrier) == null;
    }

    public static void recordLandingMemory(ShipAPI ship, Moci_RepairBayScript.Moci_RepairBay bay) {
        if (ship == null || bay == null || bay.getShip() == null) {
            return;
        }

        ship.setCustomData(LAST_LANDING_CARRIER_ID_KEY, bay.getShip().getId());
        ship.setCustomData(LAST_LANDING_BAY_SLOT_ID_KEY, bay.getSlotId());
        if (bay.getLocation() != null) {
            ship.setCustomData(LAST_LANDING_LOCATION_KEY, new org.lwjgl.util.vector.Vector2f(bay.getLocation()));
        }
        logRouteEvent(ship, "记录降落记忆。carrier=" + bay.getShip().getName()
                + ", bay=" + bay.getSlotId());
    }

    public static String getRememberedLandingCarrierId(ShipAPI ship) {
        if (ship == null) {
            return null;
        }
        Object value = ship.getCustomData().get(LAST_LANDING_CARRIER_ID_KEY);
        return value instanceof String ? (String) value : null;
    }

    public static String getRememberedLandingBaySlotId(ShipAPI ship) {
        if (ship == null) {
            return null;
        }
        Object value = ship.getCustomData().get(LAST_LANDING_BAY_SLOT_ID_KEY);
        return value instanceof String ? (String) value : null;
    }

    private static Moci_ReturnToCarrierModule getOrCreateReturnToCarrierModule(ShipAPI ship) {
        Object data = ship.getCustomData().get(RETURN_TO_CARRIER_MODULE_KEY);
        if (data instanceof Moci_ReturnToCarrierModule) {
            return (Moci_ReturnToCarrierModule) data;
        }

        Moci_ReturnToCarrierModule module = new Moci_ReturnToCarrierModule(ship);
        ship.setCustomData(RETURN_TO_CARRIER_MODULE_KEY, module);
        return module;
    }

    private static void clearReturnToCarrierModule(ShipAPI ship) {
        if (ship == null) {
            return;
        }
        Object data = ship.getCustomData().get(RETURN_TO_CARRIER_MODULE_KEY);
        if (data instanceof Moci_ReturnToCarrierModule) {
            ((Moci_ReturnToCarrierModule) data).clearTarget();
        }
        ship.removeCustomData(RETURN_TO_CARRIER_MODULE_KEY);
    }

    private static void logRouteEvent(ShipAPI ship, String message) {
        if (!ENABLE_DEBUG_LOGS) {
            return;
        }
        if (ship == null) {
            return;
        }
        Global.getLogger(Moci_LandingSequence.class).info(LOG_PREFIX + ship.getName() + " - " + message);
    }

    private static boolean isCombatSquadWingman(ShipAPI ship) {
        return ship != null && ship.getCustomData().get("Moci_WingmanCommander") instanceof ShipAPI;
    }

    private static String getCarrierLandingBlockReason(ShipAPI carrier) {
        if (carrier == null) {
            return "carrier_null";
        }
        if (!carrier.isAlive() || carrier.isHulk()
                || Global.getCombatEngine() == null || !Global.getCombatEngine().isEntityInPlay(carrier)) {
            return "carrier_dead";
        }
        if (carrier.isPhased()) {
            return "carrier_phased";
        }
        if (!Moci_RepairBayHullModEffect.canActAsRepairCarrier(carrier)) {
            return "not_repair_carrier";
        }

        Moci_RepairBayScript script = Moci_RepairBayHullModEffect.ensureRepairBayScript(carrier);
        if (script == null) {
            return "repair_script_missing";
        }
        if (!script.hasVacancy()) {
            return "no_vacancy";
        }
        return null;
    }

    private static void incrementReasonCount(Map<String, Integer> rejectReasons, String reason) {
        Integer count = rejectReasons.get(reason);
        rejectReasons.put(reason, count == null ? 1 : count + 1);
    }

    private static String formatRejectReasons(Map<String, Integer> rejectReasons) {
        if (rejectReasons == null || rejectReasons.isEmpty()) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> entry : rejectReasons.entrySet()) {
            if (!first) {
                builder.append("|");
            }
            first = false;
            builder.append(toShortRejectReason(entry.getKey())).append(entry.getValue());
        }
        return builder.toString();
    }

    private static String toShortRejectReason(String reason) {
        if ("distance_limit".equals(reason)) {
            return "超距";
        }
        if ("not_repair_carrier".equals(reason)) {
            return "非整备";
        }
        if ("no_vacancy".equals(reason)) {
            return "无位";
        }
        if ("carrier_phased".equals(reason)) {
            return "相位";
        }
        if ("carrier_dead".equals(reason)) {
            return "已毁";
        }
        if ("repair_script_missing".equals(reason)) {
            return "无脚本";
        }
        if ("carrier_null".equals(reason)) {
            return "空";
        }
        return reason;
    }

    private static float getCombatElapsedTime() {
        CombatEngineAPI engine = Global.getCombatEngine();
        return engine != null ? engine.getTotalElapsedTime(false) : 0f;
    }

    private static void clearMainAmmoEmptyMarker(ShipAPI ship) {
        if (ship == null) {
            return;
        }
        ship.removeCustomData(MAIN_AMMO_EMPTY_AT_KEY);
    }

    private static void setRefitTriggerDetail(ShipAPI ship, String detail) {
        if (ship == null) {
            return;
        }
        if (detail == null || detail.isEmpty()) {
            ship.removeCustomData(REFIT_TRIGGER_DETAIL_KEY);
            return;
        }
        ship.setCustomData(REFIT_TRIGGER_DETAIL_KEY, detail);
    }

    public static String getRefitTriggerDetail(ShipAPI ship) {
        if (ship == null) {
            return "原因=未知";
        }
        Object detail = ship.getCustomData().get(REFIT_TRIGGER_DETAIL_KEY);
        if (detail instanceof String && !((String) detail).isEmpty()) {
            return (String) detail;
        }
        return "原因=" + getRefitReasonDescription(ship.getCustomData().get("Moci_NeedsRefitReason"));
    }

    private static void resetWingmanRefitLogState(ShipAPI ship) {
        if (ship == null) {
            return;
        }
        ship.removeCustomData(WINGMAN_REFIT_LAST_NEED_STATE_KEY);
        ship.removeCustomData(WINGMAN_REFIT_LAST_SEARCH_TEXT_KEY);
        ship.removeCustomData(WINGMAN_REFIT_PENDING_SEARCH_TEXT_KEY);
        ship.removeCustomData(REFIT_TRIGGER_DETAIL_KEY);
        clearMainAmmoEmptyMarker(ship);
    }

    private static void logWingmanNeedChanged(ShipAPI ship, String stateKey, String text) {
        if (ship == null || !isCombatSquadWingman(ship)) {
            return;
        }
        Object last = ship.getCustomData().get(WINGMAN_REFIT_LAST_NEED_STATE_KEY);
        if (stateKey != null && stateKey.equals(last)) {
            return;
        }
        ship.setCustomData(WINGMAN_REFIT_LAST_NEED_STATE_KEY, stateKey);
        ship.removeCustomData(WINGMAN_REFIT_LAST_SEARCH_TEXT_KEY);
        ship.removeCustomData(WINGMAN_REFIT_PENDING_SEARCH_TEXT_KEY);
        Global.getLogger(Moci_LandingSequence.class).info(WINGMAN_LOG_PREFIX + ship.getName() + " - " + text);
    }

    private static void logWingmanSearchChanged(ShipAPI ship) {
        if (ship == null || !isCombatSquadWingman(ship)) {
            return;
        }
        Object need = ship.getCustomData().get(WINGMAN_REFIT_LAST_NEED_STATE_KEY);
        if (!(need instanceof String)) {
            return;
        }
        Object summary = ship.getCustomData().get(WINGMAN_REFIT_PENDING_SEARCH_TEXT_KEY);
        if (!(summary instanceof String)) {
            return;
        }
        Object last = ship.getCustomData().get(WINGMAN_REFIT_LAST_SEARCH_TEXT_KEY);
        if (summary.equals(last)) {
            return;
        }
        ship.setCustomData(WINGMAN_REFIT_LAST_SEARCH_TEXT_KEY, summary);
        Global.getLogger(Moci_LandingSequence.class).info(WINGMAN_LOG_PREFIX + ship.getName() + " - " + summary);
    }

    private static void updateMainAmmoEmptyState(ShipAPI ship, boolean allMainWeaponsEmpty,
                                                 String largestWeaponSize, int mainWeaponCount, int mainWeaponEmpty) {
        if (ship == null) {
            return;
        }
        Object existing = ship.getCustomData().get(MAIN_AMMO_EMPTY_AT_KEY);
        if (allMainWeaponsEmpty) {
            if (!(existing instanceof Float)) {
                float now = getCombatElapsedTime();
                ship.setCustomData(MAIN_AMMO_EMPTY_AT_KEY, now);
            }
        } else {
            clearMainAmmoEmptyMarker(ship);
        }
    }

    private static String describeBay(Moci_RepairBayScript.Moci_RepairBay bay) {
        if (bay == null) {
            return "none";
        }
        return (bay.getShip() != null ? bay.getShip().getName() : "null")
                + "/" + (bay.getSlotId() != null ? bay.getSlotId() : "no_slot");
    }

    private static String getRefitReasonSummary(ShipAPI ship) {
        if (ship == null) {
            return "ship=null";
        }

        Object reason = ship.getCustomData().get("Moci_NeedsRefitReason");
        if ("HULL_AND_CR".equals(reason)) {
            return "血CR";
        }
        if ("HULL_LOW".equals(reason)) {
            return "血";
        }
        if ("CR_LOW".equals(reason)) {
            return "CR";
        }
        if ("AMMO".equals(reason)) {
            return "弹";
        }
        return "无";
    }

    private static String getRefitReasonDescription(Object reason) {
        if ("HULL_AND_CR".equals(reason)) {
            return "血量和CR过低";
        }
        if ("HULL_LOW".equals(reason)) {
            return "血量过低";
        }
        if ("CR_LOW".equals(reason)) {
            return "CR过低";
        }
        if ("AMMO".equals(reason)) {
            return "主武器弹药不足";
        }
        return "未知";
    }

    private static boolean isWeaponLowAmmoForRefit(WeaponAPI weapon) {
        return weapon != null
                && weapon.getMaxAmmo() > 0
                && (float) weapon.getAmmo() / (float) weapon.getMaxAmmo() <= 0.25f;
    }

    private static void collectAmmoTriggerWeapons(List<String> results, ShipAPI weaponHost, String largestWeaponSize) {
        if (results == null || weaponHost == null || largestWeaponSize == null) {
            return;
        }
        for (WeaponAPI weapon : weaponHost.getAllWeapons()) {
            if (!isAmmoWeaponRelevantForRefit(weapon)) {
                continue;
            }
            if (!largestWeaponSize.equals(weapon.getSize().name())) {
                continue;
            }
            if (!isWeaponLowAmmoForRefit(weapon)) {
                continue;
            }
            results.add(describeAmmoTriggerWeapon(weaponHost, weapon));
        }
    }

    private static String describeAmmoTriggerWeapon(ShipAPI weaponHost, WeaponAPI weapon) {
        String hostName = weaponHost != null ? weaponHost.getName() : "unknown_host";
        String slotId = weapon != null && weapon.getSlot() != null ? weapon.getSlot().getId() : "no_slot";
        String weaponName = weapon != null ? weapon.getDisplayName() : null;
        if (weaponName == null || weaponName.isEmpty()) {
            weaponName = weapon != null ? weapon.getId() : "unknown_weapon";
        }

        int ammo = weapon != null ? weapon.getAmmo() : 0;
        int maxAmmo = weapon != null ? weapon.getMaxAmmo() : 0;
        int ammoPercent = maxAmmo > 0 ? Math.round((float) ammo / (float) maxAmmo * 100f) : 0;
        return hostName + "/" + slotId + "/" + weaponName
                + "=" + ammo + "/" + maxAmmo + "(" + ammoPercent + "%)";
    }

    private static String formatAmmoTriggerWeapons(List<String> triggerWeapons) {
        if (triggerWeapons == null || triggerWeapons.isEmpty()) {
            return "无";
        }

        int maxEntries = 6;
        StringBuilder builder = new StringBuilder();
        int count = Math.min(maxEntries, triggerWeapons.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append("; ");
            }
            builder.append(triggerWeapons.get(i));
        }
        if (triggerWeapons.size() > maxEntries) {
            builder.append("; ... +").append(triggerWeapons.size() - maxEntries);
        }
        return builder.toString();
    }

    private static float getCRRefitThreshold(ShipAPI ship) {
        if (ship == null) {
            return 0.4f;
        }
        return Moci_RefitPolicy.getCRRefitThreshold(ship);
    }

    public static boolean canManualLandOnTarget(ShipAPI ship, ShipAPI target) {
        return Moci_RefitPolicy.canStartRefit(ship)
                && target != null
                && target.getOwner() == ship.getOwner()
                && isSuitableCarrierToLand(target);
    }

    public static boolean shouldUseTurretModeForSelectedTarget(ShipAPI ship, ShipAPI target) {
        boolean landingAvailable = canManualLandOnTarget(ship, target);
        boolean turretAvailable = Moci_MechTurretAI.canUseAsTurretHost(ship, target);
        return shouldUseTurretModeForSelectedTarget(ship, target, landingAvailable, turretAvailable);
    }

    public static boolean shouldUseTurretModeForSelectedTarget(ShipAPI ship, ShipAPI target,
                                                               boolean landingAvailable, boolean turretAvailable) {
        if (ship == null || target == null || target.getOwner() != ship.getOwner()) {
            return false;
        }
        return turretAvailable && (!landingAvailable || !Moci_MechTurretAI.isRefitModeSelected(ship));
    }

    private static boolean shouldUseRefitModeForSelectedTarget(ShipAPI ship, ShipAPI target,
                                                               boolean landingAvailable, boolean turretAvailable) {
        if (ship == null || target == null || target.getOwner() != ship.getOwner()) {
            return false;
        }
        return landingAvailable && (!turretAvailable || Moci_MechTurretAI.isRefitModeSelected(ship));
    }

    private static void maintainSelectedFriendlyTargetStatus(CombatEngineAPI engine, ShipAPI ship,
                                                             boolean landingAvailable, boolean turretAvailable,
                                                             boolean autopilotEnabled) {
        if (engine == null || ship == null || engine.getPlayerShip() != ship) {
            return;
        }

        String icon = Global.getSettings().getSpriteName("ui", "icon_tactical_bdeck");
        Map<String, String> replacements = Moci_TextLoader.mapOf(
                "switch_key", org.lwjgl.input.Keyboard.getKeyName(Moci_MechTurretAI.modeSwitchKey)
        );
        if (landingAvailable && turretAvailable) {
            boolean turretMode = !Moci_MechTurretAI.isRefitModeSelected(ship);
            engine.maintainStatusForPlayerShip(
                    "Moci_SelectedFriendlyRouteMode",
                    icon,
                    turretMode
                            ? Moci_TextLoader.getText(MECH_TURRET_TEXT_ID, "status.navigation_title_turret")
                            : Moci_TextLoader.getText(MECH_TURRET_TEXT_ID, "status.navigation_title_refit"),
                    turretMode
                            ? Moci_TextLoader.getTextWithReplacements(MECH_TURRET_TEXT_ID, "status.mode_switch_to_refit", replacements)
                            : Moci_TextLoader.getTextWithReplacements(MECH_TURRET_TEXT_ID, "status.mode_switch_to_turret", replacements),
                    turretMode);
        }

        boolean turretMode = !Moci_MechTurretAI.isRefitModeSelected(ship);
        boolean currentModeAvailable = turretMode ? turretAvailable : landingAvailable;
        String blockedReason = getRefitBlockedReason(ship);
        String detail = !turretMode && blockedReason != null ? blockedReason : turretMode
                ? Moci_TextLoader.getText(MECH_TURRET_TEXT_ID, "status.target_enter_turret")
                : Moci_TextLoader.getText(MECH_TURRET_TEXT_ID, "status.target_enter_refit");

        engine.maintainStatusForPlayerShip(
                "Moci_SelectedFriendlyRoute",
                icon,
                currentModeAvailable
                        ? Moci_TextLoader.getText(MECH_TURRET_TEXT_ID, "status.target_title_available")
                        : Moci_TextLoader.getText(MECH_TURRET_TEXT_ID, "status.target_title_unavailable"),
                detail,
                !currentModeAvailable);
    }

    /**
     * 检查舰船是否需要修理
     * 检查三个维度：血量、战备值(CR)、弹药
     * 弹药部分只统计“会参与整备触发”的武器：
     * - 排除 MOCIBIT（完全不参与整备弹药逻辑）
     * - 排除 MOCI_AMMO（只在进湾后顺手补满）
     * - 排除自动回复弹药武器
     * - 按配置决定是否忽略部分内置武器
     * 
     * @param ship 要检查的舰船
     * @return 如果需要修理则返回true
     */
    public static boolean needsRefit(ShipAPI ship){
        if (!Moci_RefitPolicy.canStartRefit(ship)) {
            if (ship != null) {
                ship.removeCustomData("Moci_NeedsRefitReason");
                ship.removeCustomData("Moci_EmptyNonLargestWeapons");
                ship.removeCustomData("Moci_AllMainWeaponsEmpty");
                clearMainAmmoEmptyMarker(ship);
                resetWingmanRefitLogState(ship);
            }
            return false;
        }
        if (Moci_MechTurretAI.isDocked(ship) && !areAllAmmoWeaponsEmpty(ship)) {
            ship.setCustomData("Moci_EmptyNonLargestWeapons", 0);
            ship.setCustomData("Moci_NeedsRefitReason", null);
            ship.setCustomData("Moci_AllMainWeaponsEmpty", false);
            resetWingmanRefitLogState(ship);
            return false;
        }

        // 血量检查：低于70%或战备值低于部署CR的50%（阈值最低为40%）
        boolean hullLow = ship.getHullLevel() <= Moci_RefitPolicy.getHullRefitThreshold(ship);
        float crThreshold = getCRRefitThreshold(ship);
        boolean crLow = ship.getCurrentCR() < crThreshold;
        if(hullLow || crLow){
            // 因为血量或CR需要修理，清除弹药相关标记，使用 10000f 全图搜索距离
            ship.setCustomData("Moci_EmptyNonLargestWeapons", 0);
            ship.setCustomData("Moci_AllMainWeaponsEmpty", false);
            clearMainAmmoEmptyMarker(ship);
            if (hullLow && crLow) {
                ship.setCustomData("Moci_NeedsRefitReason", "HULL_AND_CR");
            } else if (hullLow) {
                ship.setCustomData("Moci_NeedsRefitReason", "HULL_LOW");
            } else {
                ship.setCustomData("Moci_NeedsRefitReason", "CR_LOW");
            }
            setRefitTriggerDetail(ship, "原因=" + getRefitReasonDescription(ship.getCustomData().get("Moci_NeedsRefitReason"))
                    + ", hull=" + String.format("%.2f", ship.getHullLevel())
                    + ", cr=" + String.format("%.2f", ship.getCurrentCR())
                    + ", cr阈值=" + String.format("%.2f", crThreshold));
            logWingmanNeedChanged(ship, getRefitReasonSummary(ship),
                    "需修 原=" + getRefitReasonSummary(ship)
                            + " 血=" + String.format("%.2f", ship.getHullLevel())
                            + " CR=" + String.format("%.2f", ship.getCurrentCR()));
            return true;
        }
        
        // 弹药检查：只关注最大尺寸的“会参与弹药整备触发”的有限弹药武器（主武器）
        String largestWeaponSize = null;   // 最大武器尺寸
        // 与实际整备共用部件集合，排除已毁/脱离模块，并包含仍挂接的嵌套模块。
        List<ShipAPI> repairableParts = Moci_RepairBayScript.getRepairableParts(ship);
        
        // 首先确定舰船上“最大尺寸的相关有限弹药武器”。
        // 不能把无限弹药武器混进来，否则会出现：
        // - 更大尺寸的能量武器存在
        // - 真正需要补给的最大尺寸有限弹药武器反而被跳过
        for (ShipAPI part : repairableParts) {
            for (WeaponAPI weapon : part.getAllWeapons()) {
                if(!isAmmoWeaponRelevantForRefit(weapon)) continue;
                
                String weaponSize = weapon.getSize().name();
                if (largestWeaponSize == null || isLargerWeaponSize(weaponSize, largestWeaponSize)) {
                    largestWeaponSize = weaponSize;
                }
            }
        }
        
        // 如果没有找到任何武器，不需要弹药补给
        if (largestWeaponSize == null) {
            ship.setCustomData("Moci_EmptyNonLargestWeapons", 0);
            ship.setCustomData("Moci_NeedsRefitReason", null);
            ship.setCustomData("Moci_AllMainWeaponsEmpty", false);
            resetWingmanRefitLogState(ship);
            return false;
        }
        
        Global.getLogger(Moci_LandingSequence.class).debug(
            ship.getName() + " 最大武器尺寸: " + largestWeaponSize
        );
        
        // 统计主武器（最大尺寸的有限弹药武器）的弹药情况
        int mainWeaponCount = 0;           // 主武器数量
        int mainWeaponLowAmmo = 0;         // 弹药 ≤ 25% 的主武器数量
        int mainWeaponEmpty = 0;           // 弹药为0的主武器数量
        int emptyNonMainWeaponCount = 0;   // 弹药为0的非主武器数量
        
        // 统计同一有效部件集合中的主武器和非主武器。
        for (ShipAPI part : repairableParts) {
            for (WeaponAPI weapon : part.getAllWeapons()) {
                if(!isAmmoWeaponRelevantForRefit(weapon)) continue;

                if (weapon.getSize().name().equals(largestWeaponSize)) {
                    // 这是主武器
                    mainWeaponCount++;

                    // 检查弹药比例
                    float ammoRatio = (float)weapon.getAmmo() / (float)weapon.getMaxAmmo();
                    if (ammoRatio <= 0.25f) {
                        mainWeaponLowAmmo++;
                    }
                    if (weapon.getAmmo() == 0) {
                        mainWeaponEmpty++;
                    }
                } else {
                    // 这是非主武器（小武器），只统计弹药为0的
                    if (weapon.getAmmo() == 0) {
                        emptyNonMainWeaponCount++;
                    }
                }
            }
        }
        
        // 判断是否需要弹药补给：所有主武器的弹药都 ≤ 25%
        boolean needsAmmoRefill = mainWeaponCount > 0 && mainWeaponLowAmmo == mainWeaponCount;

        // 判断是否所有主武器都打空了
        boolean allMainWeaponsEmpty = mainWeaponCount > 0 && mainWeaponEmpty == mainWeaponCount;
        updateMainAmmoEmptyState(ship, allMainWeaponsEmpty, largestWeaponSize, mainWeaponCount, mainWeaponEmpty);

        if (needsAmmoRefill) {
            List<String> triggerWeapons = new ArrayList<String>();
            for (ShipAPI part : repairableParts) {
                collectAmmoTriggerWeapons(triggerWeapons, part, largestWeaponSize);
            }

            // 存储弹药为0的非主武器数量，用于距离计算
            ship.setCustomData("Moci_EmptyNonLargestWeapons", emptyNonMainWeaponCount);
            ship.setCustomData("Moci_NeedsRefitReason", "AMMO");
            ship.setCustomData("Moci_AllMainWeaponsEmpty", allMainWeaponsEmpty);
            setRefitTriggerDetail(ship, "原因=" + getRefitReasonDescription("AMMO")
                    + ", 主武器尺寸=" + largestWeaponSize
                    + ", 低弹主武器=" + mainWeaponLowAmmo + "/" + mainWeaponCount
                    + ", 打空主武器=" + mainWeaponEmpty + "/" + mainWeaponCount
                    + ", 打空非主武器=" + emptyNonMainWeaponCount
                    + ", 触发武器=[" + formatAmmoTriggerWeapons(triggerWeapons) + "]");

            Global.getLogger(Moci_LandingSequence.class).debug(
                ship.getName() + " 需要弹药补给 - 主武器数量: " + mainWeaponCount + 
                ", 低弹药主武器: " + mainWeaponLowAmmo + 
                ", 空弹药主武器: " + mainWeaponEmpty +
                ", 空弹药非主武器数量: " + emptyNonMainWeaponCount +
                ", 所有主武器打空: " + allMainWeaponsEmpty
            );
            Float emptyAt = ship.getCustomData().get(MAIN_AMMO_EMPTY_AT_KEY) instanceof Float
                    ? (Float) ship.getCustomData().get(MAIN_AMMO_EMPTY_AT_KEY) : null;
            String timeText = emptyAt != null ? String.format("%.2f", emptyAt) : "无";
            String ammoState = allMainWeaponsEmpty ? "弹尽" : "弹低";
            logWingmanNeedChanged(ship, ammoState,
                    "需修 原=弹"
                            + (allMainWeaponsEmpty ? "尽" : "低")
                            + " 尺=" + largestWeaponSize
                            + " 主=" + mainWeaponCount
                            + " 空=" + mainWeaponEmpty
                            + " 时=" + timeText);
        } else {
            // 不需要修理时清除标记
            ship.setCustomData("Moci_EmptyNonLargestWeapons", 0);
            ship.setCustomData("Moci_NeedsRefitReason", null);
            ship.setCustomData("Moci_AllMainWeaponsEmpty", false);
            resetWingmanRefitLogState(ship);
        }
        
        return needsAmmoRefill;
    }

    private static boolean isAmmoWeaponRelevantForRefit(WeaponAPI weapon) {
        return Moci_AmmoRefitRules.isRelevantForAmmoRefitTrigger(
                weapon,
                SKIP_BUILT_IN_WEAPONS_FOR_REFIT
        );
    }

    /**
     * 比较武器尺寸大小
     * @param size1 武器尺寸1
     * @param size2 武器尺寸2  
     * @return 如果size1比size2大则返回true
     */
    private static boolean isLargerWeaponSize(String size1, String size2) {
        // 武器尺寸优先级：LARGE > MEDIUM > SMALL
        Map<String, Integer> sizeOrder = new HashMap<String, Integer>();
        sizeOrder.put("SMALL", 1);
        sizeOrder.put("MEDIUM", 2);
        sizeOrder.put("LARGE", 3);

        Integer order1Value = sizeOrder.get(size1);
        int order1 = (order1Value != null) ? order1Value.intValue() : 0;
        
        Integer order2Value = sizeOrder.get(size2);
        int order2 = (order2Value != null) ? order2Value.intValue() : 0;
        
        return order1 > order2;
    }

    /**
     * 检查该船体模组是否适用于指定舰船
     * 只有安装了Moci_MobileSuits船插的舰船才能使用
     */
    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        if (ship != null && ship.getVariant() != null) {
            return ship.getVariant().getHullMods().contains("Moci_MobileSuits");
        }
        return false;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship == null) return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_ship_null");
        if (ship.getVariant() == null) return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_variant_null");
        if (!ship.getVariant().getHullMods().contains("Moci_MobileSuits")) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_only_ms");
        }
        return null;
    }

    /**
     * 在改装界面是否显示该船体模组
     */
    @Override
    public boolean showInRefitScreenModPickerFor(ShipAPI ship) {
        return isApplicableToShip(ship);
    }

    /**
     * 添加船插描述信息
     */
    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 2f;
        Color h = Misc.getHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();

        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.ai_heading"), com.fs.starfarer.api.ui.Alignment.MID, pad);
        
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.ai_intro"), 0f, new Color[]{h, h},
                Moci_TextLoader.getHighlights(TEXT_ID, "description.ai_intro_highlights").toArray(new String[0]));
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.ai_cond_hull"), 0f, h,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.ai_cond_hull_highlights").toArray(new String[0]));
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.ai_cond_cr"), 0f, h,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.ai_cond_cr_highlights").toArray(new String[0]));
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.ai_cond_ammo"), 0f, h,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.ai_cond_ammo_highlights").toArray(new String[0]));
        
        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.player_heading"), com.fs.starfarer.api.ui.Alignment.MID, pad);
        
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.player_intro"), pad);
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.player_step_target"), 0f, h,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.player_step_target_highlights").toArray(new String[0]));
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.player_step_auto"), 0f, h,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.player_step_auto_highlights").toArray(new String[0]));
        
        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.notes_heading"), com.fs.starfarer.api.ui.Alignment.MID, pad);
        
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.notes_carrier"), 0f, new Color[]{h, h},
                Moci_TextLoader.getHighlights(TEXT_ID, "description.notes_carrier_highlights").toArray(new String[0]));
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.notes_deployment"), pad);
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.notes_cooldown"), pad);
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.notes_cancel"), 0f, h,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.notes_cancel_highlights").toArray(new String[0]));
    }

    /**
     * 统一的起降动画判定。
     *
     * 当前规则已经改为：
     * - 所有整备湾都必须播放着陆动画
     * - 所有整备湾都必须播放起飞动画
     *
     * 之所以保留这个方法名，是为了兼容现有调用点，避免大范围重构。
     */
    public static boolean bayShouldHidden(Moci_RepairBayScript.Moci_RepairBay bay){
        return true;
    }
}
