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
import data.scripts.Moci_CollisionStateManager;

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

    // 是否已经执行过一次起飞流程
    private boolean runOnce = false;

    // 载机航母引用
    private ShipAPI carrier;

    // 碰撞状态管理器修改器ID
    private static final String COLLISION_MODIFIER_ID = "moci_mslaunch_fighter_collision";
    
    // 碰撞状态优先级
    private static final int COLLISION_PRIORITY = 150;
    
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
        // 如果游戏暂停，直接返回
        if (Global.getCombatEngine().isPaused())
            return;

        // 获取当前舰船实体
        ShipAPI ship = (ShipAPI) stats.getEntity();

        // 判断是否为标准部署状态（玩家方：朝向90度，敌方：朝向270度）
        boolean standardDeploy = ship.getFacing() == 90f;

        // 如果已经执行过起飞且舰船不在撤退状态，则停用旅行驱动
        if (runOnce && !ship.isRetreating() && !ship.isDirectRetreat()) {
            ship.getTravelDrive().deactivate();
            unapply(stats, id);
            return;
        }

        // 敌方舰船的标准部署朝向为270度
        if (ship.getOwner() == 1)
            standardDeploy = ship.getFacing() == 270f;

        // 检查是否已经完成过载机部署
        boolean alreadyDone = Global.getCombatEngine().getCustomData()
                .get("moci_carrierDeployDone_" + ship.getId()) instanceof Boolean;

        // 如果没有找到载机或舰船正在撤退或不在标准部署状态或已经完成部署
        if (getRandomCarrier(ship, true) == null || ship.isRetreating() || !standardDeploy || alreadyDone) {

            // 系统关闭状态：恢复正常速度
            if (state == ShipSystemStatsScript.State.OUT) {
                stats.getMaxSpeed().unmodify(id); // 在系统关闭时将舰船速度恢复到正常水平
            } else {
                // 系统激活状态：大幅提升速度和加速度
                stats.getMaxSpeed().modifyFlat(id, 600f * effectLevel);
                stats.getAcceleration().modifyFlat(id, 600f * effectLevel);
            }

            // 标记该舰船已完成载机部署流程
            Global.getCombatEngine().getCustomData().put("moci_carrierDeployDone_" + ship.getId(), true);
        }

        // 检测到载机且满足起飞条件
        else {
            // 如果舰船未在降落且未执行过起飞流程
            if (!ship.isLanding() && !runOnce) {

                // 获取一个可用的载机
                carrier = getRandomCarrier(ship, false);
                Vector2f takeOffLoc = null;
                WeaponSlotAPI usedSlot = null;

                LaunchSlotResult slotResult = null;
                WeaponSlotAPI memorySlot = getMemoryWeaponSlot(ship, carrier);

                if (memorySlot != null) {
                    slotResult = claimLaunchSlot(ship, carrier, memorySlot);
                }

                if (slotResult == null) {
                    // 遍历载机的所有武器槽位，寻找起飞湾
                    for (WeaponSlotAPI wep : carrier.getHullSpec().getAllWeaponSlotsCopy()) {
                        slotResult = claimLaunchSlot(ship, carrier, wep);
                        if (slotResult != null) {
                            break;
                        }
                    }
                }

                if (slotResult != null) {
                    takeOffLoc = slotResult.location;
                    usedSlot = slotResult.slot;
                }

                // 如果没有找到合适的起飞湾，使用载机中心位置
                if (takeOffLoc == null) {
                    if (carrier.getLocation() != null) {
                        takeOffLoc = new Vector2f(carrier.getLocation()); // 防御性拷贝
                    } else {
                        Global.getLogger(this.getClass()).error("载机位置为null: " + carrier.getHullSpec().getHullId());
                        return; // 无法继续执行
                    }
                }

                // 智能调整起飞位置，避免卡在航母内部（可通过开关控制）
                if (ENABLE_SAFE_LAUNCH_POSITION) {
                    takeOffLoc = calculateSafeLaunchPosition(ship, carrier, takeOffLoc);
                }

                rememberLaunchPoint(ship, carrier, usedSlot, takeOffLoc);
                logLaunchEvent(ship, "起飞点确定。carrier=" + carrier.getName()
                        + ", slot=" + (usedSlot != null ? usedSlot.getId() : "fallback_center")
                        + ", location=" + takeOffLoc);

                // 设置起飞相关属性
                ship.setLaunchingShip(carrier); // 设置载机
                setShipLocation(ship, takeOffLoc); // 设置舰船位置到起飞点
                ship.setAnimatedLaunch(); // 启用起飞
                
                // 处理模块船的隐藏（不使用起飞动画，避免干扰）
                if (ship.getChildModulesCopy() != null && !ship.getChildModulesCopy().isEmpty()) {
                    for (ShipAPI module : ship.getChildModulesCopy()) {
                        // module.setLaunchingShip(carrier);
                        // module.setAnimatedLaunch();
                        
                        // 如果模块有Moci_module_collision_controller船插，触发隐藏效果
                        if (module.getVariant().hasHullMod("Moci_module_collision_controller")) {
                            data.hullmods.Moci_ModuleCollisionSync.startModuleHiding(module);
                        }
                    }
                }

                // 播放起飞音效
                Global.getSoundPlayer().playSound("fighter_takeoff", 1f, 1f, ship.getLocation(), new Vector2f());

                // 给舰船施加一个随机的前进力，模拟起飞推力
                CombatUtils.applyForce(ship, ship.getFacing(), (float) Math.random() * carrier.getMaxSpeed() * 0.50f);

                // 设置战机碰撞状态，防止与航母碰撞
                applyFighterCollision(ship);
                
                // 启动起飞计时器（用于碰撞恢复和槽位释放）
                ship.setCustomData(LAUNCH_TIMER_KEY, LAUNCH_DURATION);

                // 起飞完成后立刻复位旅行驱动，避免它继续阻断玩家战术系统输入
                resetTravelDriveAfterLaunch(ship, stats, id);

                // 标记已执行过起飞流程
                runOnce = true;
            }
        }
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
     * @param initial 是否为初始检测（true：仅检测是否存在，false：实际分配槽位）
     * @return 可用的载机，如果没有则返回null
     */
    private ShipAPI getRandomCarrier(ShipAPI ship, boolean initial) {
        ShipAPI rememberedCarrier = findRememberedCarrier(ship, initial);
        if (rememberedCarrier != null) {
            return rememberedCarrier;
        }

        ShipAPI potCarrier = null;

        // 在20000单位范围内搜索所有舰船
        for (ShipAPI candidateCarrier : CombatUtils.getShipsWithinRange(ship.getLocation(), 20000.0F)) {

            int takenSlots = 0;

            // 获取该载机已占用的槽位数量
            if (Global.getCombatEngine().getCustomData()
                    .get("moci_launchSlots" + candidateCarrier.getId()) instanceof Integer) {
                takenSlots = (int) Global.getCombatEngine().getCustomData().get("moci_launchSlots" + candidateCarrier.getId());
            } else {
                // 如果没有记录，初始化为0
                Global.getCombatEngine().getCustomData().put("moci_launchSlots" + candidateCarrier.getId(), takenSlots);
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
                potCarrier = candidateCarrier;

                // 如果不是初始检测，增加槽位计数并返回
                if (!initial) {
                    Global.getCombatEngine().getCustomData().put("moci_launchSlots" + potCarrier.getId(),
                            takenSlots + 1);
                    return potCarrier;
                }
            }
        }
        return potCarrier;
    }

    private ShipAPI findRememberedCarrier(ShipAPI ship, boolean initial) {
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

            if (!initial) {
                Global.getCombatEngine().getCustomData().put("moci_launchSlots" + candidateCarrier.getId(), takenSlots + 1);
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

    private LaunchSlotResult claimLaunchSlot(ShipAPI ship, ShipAPI carrier, WeaponSlotAPI slot) {
        if (ship == null || carrier == null || slot == null) {
            return null;
        }
        if (slot.getWeaponType() != WeaponType.LAUNCH_BAY) {
            return null;
        }
        if (Global.getCombatEngine().getCustomData().get("moci_launchSlots" + carrier.getId() + "_" + slot.getId()) != null) {
            return null;
        }

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

        Global.getCombatEngine().getCustomData().put("moci_launchSlots" + carrier.getId() + "_" + slot.getId(), "-");
        ship.setCustomData("moci_launch_carrier_id", carrier.getId());
        ship.setCustomData("moci_launch_slot_id", slot.getId());
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
        if (ship == null || Global.getCombatEngine() == null) {
            return false;
        }

        Moci_MSLaunchTravelDrive launcher = new Moci_MSLaunchTravelDrive();
        boolean launched = launcher.beginLaunchSequence(ship);
        if (launched) {
            Global.getCombatEngine().getCustomData().put("moci_carrierDeployDone_" + ship.getId(), true);
            ship.setCustomData("Moci_ImmediateLaunchUsed", true);
            resetTravelDriveAfterLaunch(ship, null, null);
        } else {
            ship.setCustomData("Moci_ImmediateLaunchUsed", false);
        }
        return launched;
    }

    private boolean beginLaunchSequence(ShipAPI ship) {
        carrier = getRandomCarrier(ship, false);
        if (carrier == null) {
            return false;
        }

        Vector2f takeOffLoc = null;
        WeaponSlotAPI usedSlot = null;

        LaunchSlotResult slotResult = null;
        WeaponSlotAPI memorySlot = getMemoryWeaponSlot(ship, carrier);

        if (memorySlot != null) {
            slotResult = claimLaunchSlot(ship, carrier, memorySlot);
        }

        if (slotResult == null) {
            for (WeaponSlotAPI wep : carrier.getHullSpec().getAllWeaponSlotsCopy()) {
                slotResult = claimLaunchSlot(ship, carrier, wep);
                if (slotResult != null) {
                    break;
                }
            }
        }

        if (slotResult != null) {
            takeOffLoc = slotResult.location;
            usedSlot = slotResult.slot;
        }

        if (takeOffLoc == null) {
            if (carrier.getLocation() != null) {
                takeOffLoc = new Vector2f(carrier.getLocation());
            } else {
                return false;
            }
        }

        if (ENABLE_SAFE_LAUNCH_POSITION) {
            takeOffLoc = calculateSafeLaunchPosition(ship, carrier, takeOffLoc);
        }

        rememberLaunchPoint(ship, carrier, usedSlot, takeOffLoc);
        logLaunchEvent(ship, "手动触发起飞。carrier=" + carrier.getName()
                + ", slot=" + (usedSlot != null ? usedSlot.getId() : "fallback_center")
                + ", location=" + takeOffLoc);

        ship.setLaunchingShip(carrier);
        setShipLocation(ship, takeOffLoc);
        ship.setAnimatedLaunch();

        if (ship.getChildModulesCopy() != null && !ship.getChildModulesCopy().isEmpty()) {
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (module != null && module.getVariant().hasHullMod("Moci_module_collision_controller")) {
                    data.hullmods.Moci_ModuleCollisionSync.startModuleHiding(module);
                }
            }
        }

        Global.getSoundPlayer().playSound("fighter_takeoff", 1f, 1f, ship.getLocation(), new Vector2f());
        CombatUtils.applyForce(ship, ship.getFacing(), (float) Math.random() * carrier.getMaxSpeed() * 0.50f);
        applyFighterCollision(ship);
        ship.setCustomData(LAUNCH_TIMER_KEY, LAUNCH_DURATION);
        resetTravelDriveAfterLaunch(ship, null, null);
        runOnce = true;
        return true;
    }

    private static void resetTravelDriveAfterLaunch(ShipAPI ship, MutableShipStatsAPI stats, String id) {
        if (stats != null && id != null) {
            stats.getMaxSpeed().unmodify(id);
            stats.getMaxTurnRate().unmodify(id);
            stats.getTurnAcceleration().unmodify(id);
            stats.getAcceleration().unmodify(id);
            stats.getDeceleration().unmodify(id);
        }

        if (ship != null && ship.getTravelDrive() != null) {
            ship.getTravelDrive().deactivate();
            ship.getTravelDrive().setCooldown(10f);
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

    /**
     * 应用战机碰撞状态
     * 在起飞时调用，防止与航母碰撞
     * 
     * @param ship 目标舰船
     */
    private void applyFighterCollision(ShipAPI ship) {
        if (ship == null) return;
        
        var collisionManager = Moci_CollisionStateManager.getInstance();
        
        // 确保默认碰撞状态已初始化（必须在任何修改之前）
        collisionManager.initializeDefaultCollision(ship);
        
        // 使用碰撞管理器设置战机碰撞状态
        collisionManager.setCollisionModifier(
            ship, 
            COLLISION_MODIFIER_ID, 
            COLLISION_PRIORITY, 
            CollisionClass.FIGHTER
        );
    }
}
