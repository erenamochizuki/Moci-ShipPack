package data.shipsystems.scripts;

import java.awt.Color;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;
import com.fs.starfarer.api.util.Misc;

import data.hullmods.Moci_LandingSequence;
import data.scripts.Moci_LaunchLifecycle;
import data.scripts.Moci_LaunchLifecycle.Reservation;
import data.scripts.util.Moci_TravelDriveControl;

/**
 * Moci模组 - 母舰起飞旅行驱动系统
 * 
 * 主要功能：
 * 1. 检测附近的航母并从中起飞
 * 2. 根据舰船舰级匹配合适的载机平台
 * 3. 动态调整舰船速度和加速度
 * 4. 管理起飞动画和音效
 * 5. 智能计算起飞位置，避免卡在航母内部
 * 
 * 舰级匹配规则：
 * - 护卫舰：只能在驱逐舰以上的载机起飞
 * - 驱逐舰：只能在巡洋舰以上的载机起飞
 * - 巡洋舰：只能在主力舰上起飞
 * - 主力舰：不能使用此系统起飞
 * 
 * @author Moci
 */
public class Moci_MSLaunchTravelDrive extends BaseShipSystemScript {
    private static final boolean ENABLE_DEBUG_LOGS = false;
    private static final String LOG_PREFIX = "[MOCI_LAUNCH] ";

    // 巡航驱动脚本与即时起飞时创建的临时脚本共用船上的完成标记。
    private static final String LAUNCHED_KEY = "moci_mslaunch_completed";

    // 载机航母引用
    private ShipAPI carrier;

    // 起飞计时器存储键（存储在舰船的customData中）
    // 用于碰撞管理和槽位释放，2秒后两者都会被处理
    public static final String LAUNCH_TIMER_KEY = "moci_mslaunch_timer";
    
    // 起飞后的持续时间（秒）- 2秒后恢复碰撞并释放槽位
    public static final float LAUNCH_DURATION = 2f;
    
    // 是否启用安全起飞位置计算（用于测试碰撞管理器）
    private static final boolean ENABLE_SAFE_LAUNCH_POSITION = false;

    // 起飞记忆：Carrier/Slot/位置, 供后续复用
    public static final String MEMORY_CARRIER_KEY = "moci_launch_memory_carrier_id";
    public static final String MEMORY_SLOT_KEY = "moci_launch_memory_slot_id";
    public static final String MEMORY_POSITION_KEY = "moci_launch_memory_position";

    /**
     * 系统主要逻辑处理方法
     * 
     * @param stats       舰船属性统计对象
     * @param id          系统唯一标识符
     * @param state       系统当前状态
     * @param effectLevel 效果强度等级 (0.0-1.0)
     */
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        if (Global.getCombatEngine() == null || Global.getCombatEngine().isPaused()
                || !(stats.getEntity() instanceof ShipAPI)) return;
        ShipAPI ship = (ShipAPI) stats.getEntity();
        boolean launched = Boolean.TRUE.equals(ship.getCustomData().get(LAUNCHED_KEY));
        boolean retreating = ship.isRetreating() || ship.isDirectRetreat();
        if (launched && !retreating) {
            Moci_TravelDriveControl.stopTravelDrive(ship);
            unapply(stats, id);
            return;
        }
        boolean standardDeploy = ship.getFacing() == (ship.getOwner() == 1 ? 270f : 90f);
        boolean alreadyDone = Boolean.TRUE.equals(Global.getCombatEngine().getCustomData()
                .get("moci_carrierDeployDone_" + ship.getId()));
        if (!retreating && standardDeploy && !alreadyDone && !ship.isLanding()
                && beginLaunchSequence(ship)) {
            unapply(stats, id);
            return;
        }
        // 无法从母舰部署，或舰船确实正在撤退时，使用正常的巡航推进效果。
        if (state == ShipSystemStatsScript.State.OUT) {
            stats.getMaxSpeed().unmodify(id);
        } else {
            stats.getMaxSpeed().modifyFlat(id, 600f * effectLevel);
            stats.getAcceleration().modifyFlat(id, 600f * effectLevel);
        }
        Global.getCombatEngine().getCustomData().put("moci_carrierDeployDone_" + ship.getId(), true);
    }

    /**
     * 计算安全的起飞位置，避免卡在航母内部
     * 修正边界计算逻辑，参考RiftCascadeEffect的正确实现
     * 
     * @param ship        起飞的舰船
     * @param carrier     航母
     * @param originalLoc 原始起飞位置
     * @return 调整后的安全起飞位置
     */
    private Vector2f calculateSafeLaunchPosition(ShipAPI ship, ShipAPI carrier, Vector2f originalLoc) {
        // 如果舰船已经是战机碰撞，不需要调整位置
        if (ship.getCollisionClass() == CollisionClass.FIGHTER) {
            return originalLoc;
        }
        // 计算从航母中心指向原始位置的方向和距离
        Vector2f carrierLoc = carrier.getLocation();
        // 如果原始位置就是航母中心（没有找到起飞湾的情况），随机选择一个方向
        float angle;
        float distanceFromCenter = Misc.getDistance(carrierLoc, originalLoc);
        if (distanceFromCenter < 10f) {
            // 原始位置太接近航母中心，随机选择一个方向
            angle = (float) Math.random() * 360f;
        } else {
            angle = Misc.getAngleInDegrees(carrierLoc, originalLoc);
        }
        
        // 如果原始位置已经在航母外部足够远，直接使用
        float carrierRadius = carrier.getCollisionRadius();
        float safeDistance = ship.getCollisionRadius() + 50f; // 额外50单位安全距离
        
        if (distanceFromCenter >= carrierRadius + safeDistance) {
            return originalLoc;
        }

        // 先计算圆形基准位置（参考RiftCascadeEffect的actualRadius计算）
        Vector2f direction = Misc.getUnitVectorAtDegreeAngle(angle);
        float actualRadius = carrierRadius + 30f + 50f * (float) Math.random(); // 仿照RiftCascadeEffect的逻辑
        direction.scale(actualRadius);
        Vector2f circularPos = new Vector2f();
        Vector2f.add(carrierLoc, direction, circularPos);

        // 尝试使用航母的精确边界来优化位置
        BoundsAPI bounds = carrier.getExactBounds();
        if (bounds != null) {
            // 找到边界上最接近圆形基准位置的点（参考RiftCascadeEffect逻辑）
            Vector2f bestBoundaryPoint = null;
            float bestDist = Float.MAX_VALUE;

            for (SegmentAPI segment : bounds.getSegments()) {
                // 将边界点从舰船局部坐标转换为世界坐标
                Vector2f worldP1 = new Vector2f(segment.getP1());
                Vector2f worldP2 = new Vector2f(segment.getP2());
                
                // 应用舰船的朝向和位置变换
                VectorUtils.rotate(worldP1, carrier.getFacing(), worldP1);
                VectorUtils.rotate(worldP2, carrier.getFacing(), worldP2);
                Vector2f.add(worldP1, carrierLoc, worldP1);
                Vector2f.add(worldP2, carrierLoc, worldP2);
                
                // 检查转换后的世界坐标点，找到最接近圆形基准位置的边界点
                float dist1 = Misc.getDistance(worldP1, circularPos);
                float dist2 = Misc.getDistance(worldP2, circularPos);

                if (dist1 < bestDist) {
                    bestDist = dist1;
                    bestBoundaryPoint = new Vector2f(worldP1);
                }
                if (dist2 < bestDist) {
                    bestDist = dist2;
                    bestBoundaryPoint = new Vector2f(worldP2);
                }
            }

            if (bestBoundaryPoint != null) {
                // 从边界点向圆形基准位置方向偏移安全距离（参考RiftCascadeEffect）
                Vector2f dir = Misc.getUnitVectorAtDegreeAngle(
                        Misc.getAngleInDegrees(bestBoundaryPoint, circularPos));
                dir.scale(safeDistance * 0.9f); // 使用0.9倍安全距离，避免过远
                Vector2f safePos = new Vector2f();
                Vector2f.add(bestBoundaryPoint, dir, safePos);
                

                return safePos;
            }
        }

        // 如果无法获取精确边界，使用圆形计算
        return circularPos;
    }

    /**
     * 设置舰船位置的辅助方法
     * 参考骤雨mod的安全实现，避免0向量问题
     * 
     * @param ship     目标舰船
     * @param location 新位置
     */
    private void setShipLocation(ShipAPI ship, Vector2f location) {
        // 位置有效性检查，防止0向量或无效坐标
        if (location == null || (Math.abs(location.x) < 1f && Math.abs(location.y) < 1f)) {
            Global.getLogger(this.getClass()).warn("检测到无效起飞位置: " + location + 
                ", 舰船: " + ship.getHullSpec().getHullId() + ", 使用载机位置作为fallback");
            
            // 使用载机位置加随机偏移作为安全fallback
            if (carrier != null) {
                Vector2f safePos = new Vector2f(carrier.getLocation());
                // 添加随机偏移避免重叠
                float angle = (float) Math.random() * 360f;
                float distance = carrier.getCollisionRadius() + ship.getCollisionRadius() + 50f;
                Vector2f offset = Misc.getUnitVectorAtDegreeAngle(angle);
                offset.scale(distance);
                Vector2f.add(safePos, offset, safePos);
                location = safePos;
            } else {
                Global.getLogger(this.getClass()).error("无法设置舰船位置：载机为null且位置无效");
                return;
            }
        }
        
        // 使用骤雨mod的安全位置设置方法
        Vector2f dif = new Vector2f(location);                    // 创建新向量作为差值
        Vector2f.sub(location, ship.getLocation(), dif);         // 计算位置差
        Vector2f.add(ship.getLocation(), dif, ship.getLocation()); // 将差值加到实体位置
    }

    /**
     * 取消系统效果，恢复舰船属性到默认状态
     * 
     * @param stats 舰船属性统计对象
     * @param id    系统唯一标识符
     */
    public void unapply(MutableShipStatsAPI stats, String id) {
        // 移除所有属性修改
        stats.getMaxSpeed().unmodify(id);
        stats.getMaxTurnRate().unmodify(id);
        stats.getTurnAcceleration().unmodify(id);
        stats.getAcceleration().unmodify(id);
        stats.getDeceleration().unmodify(id);
    }

    /**
     * 获取系统状态显示信息
     * 
     * @param index       状态索引
     * @param state       系统状态
     * @param effectLevel 效果等级
     * @return 状态数据对象
     */
    public StatusData getStatusData(int index, State state, float effectLevel) {
        if (index == 0) {
            return new StatusData("起飞中..", false);
        }
        return null;
    }

    /**
     * 获取附近可用的载机
     * 
     * @param ship    当前舰船
     * @return 可用的载机，如果没有则返回null
     */
    private ShipAPI getRandomCarrier(ShipAPI ship) {
        ShipAPI rememberedCarrier = findRememberedCarrier(ship);
        if (rememberedCarrier != null) {
            return rememberedCarrier;
        }

        // 在20000单位范围内搜索所有舰船
        for (ShipAPI candidateCarrier : CombatUtils.getShipsWithinRange(ship.getLocation(), 20000.0F)) {

            int takenSlots = 0;

            // 获取该载机已占用的槽位数量
            if (Global.getCombatEngine().getCustomData()
                    .get("moci_launchSlots" + candidateCarrier.getId()) instanceof Integer) {
                takenSlots = (int) Global.getCombatEngine().getCustomData().get("moci_launchSlots" + candidateCarrier.getId());
            }

            // ===== 根据舰船舰级进行载机匹配 =====
            if (ship.isFrigate()) {
                // 护卫舰：只能在驱逐舰以上的载机起飞
                if (candidateCarrier.isFrigate()) {
                    continue;
                }
            } else if (ship.isDestroyer()) {
                // 驱逐舰：只能在巡洋舰以上的载机起飞
                if (candidateCarrier.isFrigate() || candidateCarrier.isDestroyer()) {
                    continue;
                }
            } else if (ship.isCruiser()) {
                // 巡洋舰：只能在主力舰上起飞
                if (!candidateCarrier.isCapital()) {
                    continue;
                }
            } else if (ship.isCapital()) {
                // 主力舰：不能使用此系统起飞
                continue;
            }

            // 如果载机的所有槽位都已被占用，跳过
            // 注意：这里应该检查总战机湾数量，而不是当前可用数量
            int totalBays = candidateCarrier.getHullSpec().getFighterBays();
            if (takenSlots >= totalBays)
                continue;

            // 基本条件筛选：必须是同阵营、非战机、非自身
            if (candidateCarrier.getOwner() != ship.getOwner() || candidateCarrier.isFighter() || candidateCarrier == ship)
                continue;

            // 阵营细分：载机是友军但舰船不是友军的情况
            if (candidateCarrier.getOwner() == ship.getOwner() && ((candidateCarrier.isAlly() && !ship.isAlly())))
                continue;

            // 排除已损毁的载机
            if (candidateCarrier.isHulk())
                continue;

            // ===== 严格的载机判断逻辑 =====

            // 获取舰船规格中的战机湾总数
            int totalFighterBays = candidateCarrier.getHullSpec().getFighterBays();

            // 获取内置战机数量（通过检查默认战机组来判断）
            int builtInFighters = 0;
            if (candidateCarrier.getHullSpec().getBuiltInWings() != null) {
                builtInFighters = candidateCarrier.getHullSpec().getBuiltInWings().size();
            }

            // 计算可用于外部舰船的战机湾数量
            int availableBaysForExternal = totalFighterBays - builtInFighters;

            // 载机判断条件：
            // 1. 必须有战机湾 (totalFighterBays > 0)
            // 2. 当前可用战机湾数量 > 0 (candidateCarrier.getNumFighterBays() > 0)
            // 3. 可用于外部舰船的战机湾数量 >= 1 (排除只有浮游炮的小型舰船)
            // 4. 或者有专门的起飞湾 (candidateCarrier.getLaunchBaysCopy().size() > 0)
            boolean hasValidCarrierCapacity = (totalFighterBays > 0 &&
                    candidateCarrier.getNumFighterBays() > 0 &&
                    availableBaysForExternal >= 1) ||
                    !candidateCarrier.getLaunchBaysCopy().isEmpty();

            if (hasValidCarrierCapacity) {
                return candidateCarrier;
            }
        }
        return null;
    }

    private ShipAPI findRememberedCarrier(ShipAPI ship) {
        String rememberedCarrierId = Moci_LandingSequence.getRememberedLandingCarrierId(ship);
        if (rememberedCarrierId == null) {
            return null;
        }

        for (ShipAPI candidateCarrier : CombatUtils.getShipsWithinRange(ship.getLocation(), 20000.0F)) {
            if (!rememberedCarrierId.equals(candidateCarrier.getId())) {
                continue;
            }
            if (candidateCarrier.getOwner() != ship.getOwner() || candidateCarrier.isFighter() || candidateCarrier == ship) {
                return null;
            }
            if (candidateCarrier.isHulk()) {
                return null;
            }
            if (!candidateCarrier.isAlive() || candidateCarrier.isPhased()) {
                return null;
            }

            int takenSlots = 0;
            Object takenSlotsObj = Global.getCombatEngine().getCustomData().get("moci_launchSlots" + candidateCarrier.getId());
            if (takenSlotsObj instanceof Integer) {
                takenSlots = (Integer) takenSlotsObj;
            }

            int totalBays = candidateCarrier.getHullSpec().getFighterBays();
            if (takenSlots >= totalBays) {
                return null;
            }

            if (ship.isFrigate() && candidateCarrier.isFrigate()) {
                return null;
            }
            if (ship.isDestroyer() && (candidateCarrier.isFrigate() || candidateCarrier.isDestroyer())) {
                return null;
            }
            if (ship.isCruiser() && !candidateCarrier.isCapital()) {
                return null;
            }
            if (ship.isCapital()) {
                return null;
            }

            int totalFighterBays = candidateCarrier.getHullSpec().getFighterBays();
            int builtInFighters = candidateCarrier.getHullSpec().getBuiltInWings() != null
                    ? candidateCarrier.getHullSpec().getBuiltInWings().size()
                    : 0;
            int availableBaysForExternal = totalFighterBays - builtInFighters;
            boolean hasValidCarrierCapacity = (totalFighterBays > 0
                    && candidateCarrier.getNumFighterBays() > 0
                    && availableBaysForExternal >= 1)
                    || !candidateCarrier.getLaunchBaysCopy().isEmpty();
            if (!hasValidCarrierCapacity) {
                return null;
            }

            return candidateCarrier;
        }

        return null;
    }

    private WeaponSlotAPI getMemoryWeaponSlot(ShipAPI ship, ShipAPI carrier) {
        if (ship == null || carrier == null) {
            return null;
        }

        String rememberedCarrierId = Moci_LandingSequence.getRememberedLandingCarrierId(ship);
        String rememberedSlotId = Moci_LandingSequence.getRememberedLandingBaySlotId(ship);
        if (rememberedCarrierId == null || rememberedSlotId == null || !rememberedCarrierId.equals(carrier.getId())) {
            return null;
        }

        for (WeaponSlotAPI slot : carrier.getHullSpec().getAllWeaponSlotsCopy()) {
            if (rememberedSlotId.equals(slot.getId())) {
                return slot;
            }
        }
        return null;
    }

    private LaunchSlotResult claimLaunchSlot(ShipAPI ship, ShipAPI carrier, WeaponSlotAPI slot, Reservation reservation) {
        if (ship == null || carrier == null || slot == null) {
            return null;
        }
        if (slot.getWeaponType() != WeaponType.LAUNCH_BAY) {
            return null;
        }
        if (!reservation.claimSlot(slot.getId())) return null;

        if (Global.getCombatEngine().getPlayerShip() == ship) {
            Global.getSoundPlayer().playSound("ui_noise_static",
                    1f + MathUtils.getRandomNumberInRange(-0.3f, .3f), 1f,
                    carrier.getLocation(), new Vector2f());
            carrier.getFluxTracker().showOverloadFloatyIfNeeded("祝你好运！", Color.white, 2f, true);
        }

        ship.setFacing(carrier.getFacing() + slot.getAngle());
        Vector2f location = slot.computePosition(carrier);
        if (location != null) {
            location = new Vector2f(location);
        }

        return new LaunchSlotResult(slot, location);
    }

    private void rememberLaunchPoint(ShipAPI ship, ShipAPI carrier, WeaponSlotAPI slot, Vector2f takeOffLoc) {
        if (ship == null) {
            return;
        }

        if (carrier != null) {
            ship.setCustomData(MEMORY_CARRIER_KEY, carrier.getId());
        }
        if (slot != null) {
            ship.setCustomData(MEMORY_SLOT_KEY, slot.getId());
        }
        if (takeOffLoc != null) {
            ship.setCustomData(MEMORY_POSITION_KEY, new Vector2f(takeOffLoc));
        }
    }

    public static boolean launchImmediately(ShipAPI ship) {
        if (ship == null || Global.getCombatEngine() == null) return false;
        if (Moci_LaunchLifecycle.isLaunching(ship)) return true;
        boolean launched = new Moci_MSLaunchTravelDrive().beginLaunchSequence(ship);
        ship.setCustomData("Moci_ImmediateLaunchUsed", launched);
        return launched;
    }

    private boolean beginLaunchSequence(ShipAPI ship) {
        carrier = getRandomCarrier(ship);
        if (carrier == null) return false;
        Reservation reservation = Moci_LaunchLifecycle.reserve(ship, carrier);
        if (reservation == null) return false;
        boolean completed = false;
        try {
            Vector2f takeOffLoc = null;
            WeaponSlotAPI usedSlot = null;
            LaunchSlotResult slotResult = null;
            WeaponSlotAPI memorySlot = getMemoryWeaponSlot(ship, carrier);
            if (memorySlot != null) {
                slotResult = claimLaunchSlot(ship, carrier, memorySlot, reservation);
            }
            if (slotResult == null) {
                for (WeaponSlotAPI wep : carrier.getHullSpec().getAllWeaponSlotsCopy()) {
                    slotResult = claimLaunchSlot(ship, carrier, wep, reservation);
                    if (slotResult != null) break;
                }
            }
            if (slotResult != null) {
                takeOffLoc = slotResult.location;
                usedSlot = slotResult.slot;
            }
            if (takeOffLoc == null) {
                if (carrier.getLocation() == null) return false;
                takeOffLoc = new Vector2f(carrier.getLocation());
            }
            if (ENABLE_SAFE_LAUNCH_POSITION) {
                takeOffLoc = calculateSafeLaunchPosition(ship, carrier, takeOffLoc);
            }
            rememberLaunchPoint(ship, carrier, usedSlot, takeOffLoc);
            logLaunchEvent(ship, "起飞点确定。carrier=" + carrier.getName()
                    + ", slot=" + (usedSlot != null ? usedSlot.getId() : "fallback_center")
                    + ", location=" + takeOffLoc);
            ship.setLaunchingShip(carrier);
            setShipLocation(ship, takeOffLoc);
            ship.setAnimatedLaunch();
            if (ship.getChildModulesCopy() != null) {
                for (ShipAPI module : ship.getChildModulesCopy()) {
                    if (module != null && module.getVariant() != null
                            && module.getVariant().hasHullMod("Moci_module_collision_controller")) {
                        data.hullmods.Moci_ModuleCollisionSync.startModuleHiding(module);
                    }
                }
            }
            Global.getSoundPlayer().playSound("fighter_takeoff", 1f, 1f, ship.getLocation(), new Vector2f());
            CombatUtils.applyForce(ship, ship.getFacing(), (float) Math.random() * carrier.getMaxSpeed() * 0.50f);
            reservation.start();
            Moci_TravelDriveControl.stopTravelDrive(ship);
            ship.setCustomData(LAUNCHED_KEY, true);
            Global.getCombatEngine().getCustomData().put("moci_carrierDeployDone_" + ship.getId(), true);
            completed = true;
            return true;
        } finally {
            if (!completed) reservation.release();
        }
    }

    private void logLaunchEvent(ShipAPI ship, String message) {
        if (!ENABLE_DEBUG_LOGS) {
            return;
        }
        if (ship == null) {
            return;
        }
        Global.getLogger(Moci_MSLaunchTravelDrive.class).info(LOG_PREFIX + ship.getName() + " - " + message);
    }

    private static class LaunchSlotResult {
        private final WeaponSlotAPI slot;
        private final Vector2f location;

        private LaunchSlotResult(WeaponSlotAPI slot, Vector2f location) {
            this.slot = slot;
            this.location = location;
        }
    }

}
