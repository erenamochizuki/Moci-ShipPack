package data.scripts.weapons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.AmmoTrackerAPI;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatAsteroidAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.util.Misc;

/**
 * 核心代码来自光环的中帮手
 */
public class Moci_HSITunnelController_v2 implements EveryFrameWeaponEffectPlugin {

    private static final Logger log = Global.getLogger(Moci_HSITunnelController_v2.class);

    // 动画控制参数
    private float animationSpeed = 8f; // 动画播放速度（从武器文件读取）
    private float currentFrameFloat = 6f; // 当前帧（浮点数，用于平滑计算），默认第6帧
    private boolean isDeploying = false; // 是否正在部署
    private boolean isRetracting = false; // 是否正在收回
    private int maxFrames = 6; // 最大帧数，默认为6
    private int defaultFrame = 6; // 默认帧，第6帧
    private boolean initialized = false;

    // 无人机AI状态枚举
    public enum AIStage {
        WAITING_ORDER, // 等待指令
        MOVE, // 移动向目标
        ATTACK, // 攻击目标
        MANUVER_TO_NEXT_ATTACK_POSITION, // 机动到下一个攻击位置
        END_ATTACK, // 结束攻击
        IN_CD, GO_TO_LAND // 返回母舰
    }

    private List<HSITunnelScript> drones = new ArrayList<>();

    protected static final int MAX_AMMO = 6; // 最大弹药/无人机数量（改为6个）

    // 不同点位无人机朝向
    private static final Map<Integer, Float> facingMap = new HashMap<>();

    private final ammoTracker ammoTracker = new ammoTracker(
            0.1f, // 每秒无人机回复速度
            6, // 最大弹药量（改为6）
            1 // 每次装填数量
    );

    // 新增枚举表示武器位置
    public enum WeaponSide {
        LEFT, RIGHT, NONE
    }

    static WeaponSide weaponSide = WeaponSide.NONE;

    static {
        // 6个无人机的朝向偏移
        facingMap.put(0, 180f); // 1号位置无人机朝向偏移
        facingMap.put(1, 180f); // 2号位置无人机朝向偏移
        facingMap.put(2, 180f); // 3号位置无人机朝向偏移
        facingMap.put(3, 180f); // 4号位置无人机朝向偏移
        facingMap.put(4, 180f); // 5号位置无人机朝向偏移
        facingMap.put(5, 180f); // 6号位置无人机朝向偏移
    }

    private static Vector2f findTarget(WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
        float range = weapon.getRange();
        boolean player = ship == Global.getCombatEngine().getPlayerShip();
        ShipAPI target = ship.getShipTarget();
        Vector2f targetLoc = null;
        if (ship.getShipAI() != null && ship.getAIFlags().hasFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)) {
            target = (ShipAPI) ship.getAIFlags().getCustom(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM);
        }
        if (target != null && target.getOwner() != ship.getOwner()) {
            float dist = Misc.getDistance(ship.getLocation(), target.getLocation());
            float radSum = ship.getCollisionRadius() + target.getCollisionRadius();
            if (dist > range + radSum)
                target = null;
        } else {
            if (player) {
                targetLoc = ship.getMouseTarget();
            } else {
                Object test = ship.getAIFlags().getCustom(ShipwideAIFlags.AIFlags.MANEUVER_TARGET);
                if (test instanceof ShipAPI) {
                    target = (ShipAPI) test;
                    float dist = Misc.getDistance(ship.getLocation(), target.getLocation());
                    float radSum = ship.getCollisionRadius() + target.getCollisionRadius();
                    if (dist > range + radSum)
                        target = null;
                }
            }
        }
        if (target == null && targetLoc == null) {
            target = Misc.findClosestShipEnemyOf(ship, ship.getLocation(), ShipAPI.HullSize.FIGHTER, range, true);
        }
        if (target != null && targetLoc == null) {
            targetLoc = target.getLocation();
        }
        return targetLoc;
    }

    Vector2f targetLocation = null;

    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 初始化
        if (!initialized) {
            maxFrames = weapon.getAnimation().getNumFrames() - 1;
            defaultFrame = maxFrames; // 默认帧设为最后一帧
            currentFrameFloat = defaultFrame; // 初始化当前帧为默认帧

            // 从武器文件读取动画速度
            if (weapon.getAnimation() != null) {
                animationSpeed = weapon.getAnimation().getFrameRate();
            }

            initialized = true;
        }

        // 初始化武器位置判断
        String slotId = weapon.getSlot().getId();
        if (slotId.endsWith("_L")) {
            weaponSide = WeaponSide.LEFT;
        } else if (slotId.endsWith("_R")) {
            weaponSide = WeaponSide.RIGHT;
        }

        // 暂停自动动画播放
        weapon.getAnimation().pause();

        // 检查是否有无人机处于活动状态
        boolean anyDroneActive = false;
        for (HSITunnelScript drone : drones) {
            if (drone.getStage() != AIStage.WAITING_ORDER && drone.getStage() != AIStage.IN_CD) {
                anyDroneActive = true;
                break;
            }
        }

        // 计算待命无人机数量
        int idleDrones = 0;
        for (HSITunnelScript drone : drones) {
            if (drone.getStage() == AIStage.WAITING_ORDER || drone.getStage() == AIStage.IN_CD) {
                idleDrones++;
            }
        }

        // 动画控制逻辑（类似Moci_BackpackGun）
        handleAnimation(amount, weapon, anyDroneActive, idleDrones);

        // 检查母舰是否被摧毁
        boolean end = weapon.getShip() != null && !weapon.getShip().isAlive();
        if (end) {
            // 对每个存活的无人机造成100000能量伤害
            for (HSITunnelScript drone : drones) {
                if (drone.getDrone().isAlive()) {
                    drone.getDrone().getMutableStats().getHullDamageTakenMult().unmodify("AMX_004_BIT");
                    engine.applyDamage(drone.getDrone(), drone.getDrone().getLocation(),
                            100000f, DamageType.ENERGY, 0f, true, false, weapon.getShip());
                }
            }
        }

        // 管理无人机数量和状态
        int droneNum = 0;

        // 无人机生成逻辑：像原版一样，进入战斗就生成无人机（不依赖开火状态）
        for (int i = 0; i < 6; i++) { // 改为6个无人机
            if (drones.size() < i + 1) {
                if (ammoTracker.deductOneAmmo()) {
                    spawnAndBindScript(engine, weapon, i);
                    droneNum++;
                } else {
                    break;
                }
            } else {
                if (!drones.get(i).drone.isAlive()) {
                    drones.remove(i);
                    i--;
                } else {
                    drones.get(i).resetPoint(i);
                    droneNum++;
                }
            }
        }

        ammoTracker.advance(amount, droneNum);

        if (!weapon.isFiring()) {
            targetLocation = null;
        } else {
            if (targetLocation == null) {
                targetLocation = findTarget(weapon);
            }
        }
        boolean hasTarget = targetLocation != null;

        // 检测母舰武器光束命中陨石并减速
        checkAsteroidHitAndStop(weapon);

        // v2版本的无人机释放逻辑：只有当动画达到第0帧时才释放所有无人机进入战斗状态
        boolean shouldReleaseDrones = hasTarget && Math.round(currentFrameFloat) <= 0;

        for (HSITunnelScript drone : drones) {
            drone.advance(amount);
            if (end) {
                drone.notifyEnd();
                continue;
            }
            if (shouldReleaseDrones) {
                if (drone.getStage() == AIStage.WAITING_ORDER) {
                    // 只有在等待状态时才释放无人机，避免重复设置
                    drone.setStage(AIStage.MOVE);
                    drone.setTarget(targetLocation, weaponSide);
                }
                // 移除else分支，避免在移动过程中重复设置目标
            }
        }

        // 新增：当所有无人机回归时重置武器状态
        if (idleDrones == 6) { // 改为6个
            boolean inCD = false;
            for (HSITunnelScript drone : drones) {
                drone.mainWeapon.setRemainingCooldownTo(0f);
                if (drone.getStage() == AIStage.IN_CD) {
                    inCD = true;
                }
            }
            if (!inCD) {
                weapon.setRemainingCooldownTo(0f);
                weapon.setAmmo(weapon.getMaxAmmo());
            }
        }
    }

    /**
     * 处理动画控制
     * 改进版：开火时播放到第0帧并保持，所有无人机归航后才回到第6帧
     */
    private void handleAnimation(float amount, WeaponAPI weapon, boolean anyDroneActive, int idleDrones) {
        boolean isFiring = weapon.isFiring();

        // 计算目标帧：改进的逻辑
        float targetFrame;
        if (isFiring || anyDroneActive) {
            // 开火时或有无人机活动时保持在第0帧
            targetFrame = 0;
            isDeploying = true;
            isRetracting = false;
        } else {
            // 停止开火且所有无人机都归航后才回到第6帧
            targetFrame = defaultFrame; // 第6帧
            isDeploying = false;
            isRetracting = true;
        }

        // 平滑过渡到目标帧
        smoothTransitionToFrame(targetFrame, amount);

        // 设置当前动画帧
        int currentFrame = Math.round(currentFrameFloat);
        currentFrame = Math.max(0, Math.min(currentFrame, maxFrames));
        weapon.getAnimation().setFrame(currentFrame);
    }

    /**
     * 平滑过渡到目标帧
     * 使用从武器文件读取的动画速度
     */
    private void smoothTransitionToFrame(float targetFrame, float amount) {
        float frameDifference = targetFrame - currentFrameFloat;

        if (Math.abs(frameDifference) < 0.01f) {
            currentFrameFloat = targetFrame;
            return;
        }

        // 使用从武器文件读取的动画速度
        float smoothStep = frameDifference * animationSpeed * amount;
        currentFrameFloat += smoothStep;
        currentFrameFloat = Math.max(0, Math.min(currentFrameFloat, maxFrames));
    }

    /**
     * 获取武器开火点位置（改为使用武器的开火点）
     */
    private static Vector2f getLocation(WeaponAPI weapon, int i) {
        // 使用武器的开火点位置
        if (weapon.getSpec().getHardpointFireOffsets() != null &&
                i < weapon.getSpec().getHardpointFireOffsets().size()) {
            Vector2f offset = weapon.getSpec().getHardpointFireOffsets().get(i);
            return OffSetToLocation(weapon.getLocation(), weapon.getCurrAngle(), offset);
        } else if (weapon.getSpec().getTurretFireOffsets() != null &&
                i < weapon.getSpec().getTurretFireOffsets().size()) {
            Vector2f offset = weapon.getSpec().getTurretFireOffsets().get(i);
            return OffSetToLocation(weapon.getLocation(), weapon.getCurrAngle(), offset);
        } else {
            // 如果没有足够的开火点，使用默认位置
            Vector2f defaultOffset = new Vector2f(-50f + i * 15f, -30f + (i % 2) * 20f);
            return OffSetToLocation(weapon.getLocation(), weapon.getCurrAngle(), defaultOffset);
        }
    }

    private static float getFacing(WeaponAPI weapon, int i) {
        if (facingMap.containsKey(i)) {
            return weapon.getCurrAngle() + facingMap.get(i);
        }
        return weapon.getCurrAngle();
    }

    public void spawnAndBindScript(CombatEngineAPI engine, WeaponAPI weapon, int point) {
        ShipHullSpecAPI spec = Global.getSettings().getHullSpec("Moci_AMX_004_bit");
        ShipVariantAPI v = Global.getSettings().createEmptyVariant("Moci_AMX_004_bit_Trail", spec);
        v.addWeapon("WS0001", "Moci_RX_93_v2_bit_beam");
        WeaponGroupSpec g = new WeaponGroupSpec(WeaponGroupType.LINKED);
        g.addSlot("WS0001");
        g.setAutofireOnByDefault(false);
        v.addWeaponGroup(g);
        ShipAPI w = engine.createFXDrone(v);
        w.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
        w.setOwner(weapon.getShip().getOwner());
        if (weapon.getShip().isAlly()) {
            w.setAlly(true);
        }
        w.setForceHideFFOverlay(true);
        w.setDrone(true);
        w.getAIFlags().setFlag(ShipwideAIFlags.AIFlags.DRONE_MOTHERSHIP, 100000f, weapon.getShip());

        // 查找无人机上的装饰武器
        WeaponAPI droneDecoWeapon = null;
        for (WeaponAPI dw : w.getAllWeapons()) {
            if (dw.getSpec().getWeaponId().equals("Moci_blinker_RX_93_v2")) {
                droneDecoWeapon = dw;
                break;
            }
        }

        w.getSpriteAPI().setTexWidth(0f);
        w.getSpriteAPI().setTexHeight(0f);

        MutableShipStatsAPI self = w.getMutableStats();
        MutableShipStatsAPI source = weapon.getShip().getMutableStats();
        self.getEnergyWeaponDamageMult().applyMods(source.getEnergyWeaponDamageMult());
        self.getEnergyWeaponRangeBonus().applyMods(source.getEnergyWeaponRangeBonus());
        self.getDamageToFighters().applyMods(source.getDamageToFighters());
        self.getDamageToFrigates().applyMods(source.getDamageToFrigates());
        self.getDamageToDestroyers().applyMods(source.getDamageToDestroyers());
        self.getDamageToCruisers().applyMods(source.getDamageToCruisers());
        self.getDamageToCapital().applyMods(source.getDamageToCapital());
        self.getEnergyWeaponFluxCostMod().modifyMult("AMX_004_BIT", 0f);
        self.getHardFluxDissipationFraction().modifyFlat("AMX_004_BIT", 1f);
        self.getHullDamageTakenMult().modifyMult("AMX_004_BIT", 0f);
        self.getTimeMult().modifyMult("AMX_004_BIT", 1f);
        self.getEnergyRoFMult().modifyMult("AMX_004_BIT", 0.5f);
        self.getCRLossPerSecondPercent().modifyMult("AMX_004_BIT", 0f);
        self.getEnergyWeaponDamageMult().modifyMult("AMX_004_BIT", 0.5f);
        w.setDoNotFlareEnginesWhenStrafingOrDecelerating(true);
        w.setCollisionClass(CollisionClass.NONE);
        w.setFacing(weapon.getCurrAngle());
        Global.getCombatEngine().addEntity(w);
        w.getLocation().set(getLocation(weapon, point));
        w.setShipAI(null);

        drones.add(new HSITunnelScript(w, weapon, point, droneDecoWeapon));
    }

    /**
     * 检测母舰武器光束命中陨石并使其停止
     */
    private void checkAsteroidHitAndStop(WeaponAPI weapon) {
        if (weapon == null || weapon.getBeams().isEmpty()) {
            return;
        }

        for (Object beam : weapon.getBeams()) {
            CombatEntityAPI hitTarget = ((com.fs.starfarer.api.combat.BeamAPI) beam).getDamageTarget();

            if (hitTarget != null && isAsteroid(hitTarget)) {
                hitTarget.getVelocity().set(0f, 0f);
                hitTarget.setAngularVelocity(0f);
            }
        }
    }

    /**
     * 判断目标是否为陨石
     */
    private boolean isAsteroid(CombatEntityAPI entity) {
        if (entity instanceof CombatAsteroidAPI) {
            return true;
        }
        return entity.getCollisionClass() == CollisionClass.ASTEROID;
    }

    public static class HSITunnelScript {
        private ShipAPI drone;
        private AIStage stage;

        private WeaponAPI source;
        private WeaponAPI mainWeapon;

        private Vector2f target = null;

        private Vector2f expectedLocation = new Vector2f();

        private final float turnRate, maxSpeed;

        private boolean ended = false;

        private int point = 0; // 无人机编号 (0,1,2,3,4,5)

        private boolean fired = false;

        private WeaponAPI decoWeapon;
        private int attackCount = 0;
        private final int maxAttackCount = 3;
        private float attackAngleOffset = 0f;

        private Vector2f controlPoint1 = null;
        private Vector2f controlPoint2 = null;
        private float bezierProgress = 0f;
        private Vector2f bezierStartPoint = null;
        private float totalBezierDistance = 0f;
        private float accumulatedDistance = 0f;
        private boolean curveDirectionSet = false; // 标记曲线方向是否已设置
        private boolean useRightCurve = false; // 使用右曲线还是左曲线
        private float randomFactor1, randomFactor2, randomFactor3, randomFactor4; // 贝塞尔曲线随机参数

        public void resetPoint(int point) {
            this.point = point;
        }

        public HSITunnelScript(ShipAPI drone, WeaponAPI weapon, int point, WeaponAPI decoWeapon) {
            this.drone = drone;
            stage = AIStage.WAITING_ORDER;
            source = weapon;
            this.point = point;
            this.decoWeapon = decoWeapon;
            this.attackAngleOffset = (float) (Math.random() * 120f - 60f);
            turnRate = 720f;
            maxSpeed = 600f;
            this.point = point;

            for (WeaponAPI w : drone.getAllWeapons()) {
                if (!w.isDecorative()) {
                    this.mainWeapon = w;
                    break;
                }
            }
        }

        private void updateDecoWeaponAnimation() {
            // 检查装饰武器是否存在且有动画
            if (decoWeapon == null || decoWeapon.getAnimation() == null || decoWeapon.getAnimation().getNumFrames() <= 1)
                return;

            if (stage != AIStage.WAITING_ORDER && stage != AIStage.IN_CD) {
                // 无人机处于活动状态，播放动画
                decoWeapon.getAnimation().play();
            } else {
                // 无人机处于待命状态，暂停动画
                decoWeapon.getAnimation().pause();
            }
        }

        ShipAPI shipTarget = null;
        float CD = 0;

        public void advance(float amount) {
            if (ended)
                return;

            updateDecoWeaponAnimation();

            float timeMult = 1f;

            if (drone != null && drone.isAlive() && source != null && source.getShip().isAlive()) {
                MutableShipStatsAPI self = drone.getMutableStats();
                MutableShipStatsAPI mothership = source.getShip().getMutableStats();

                timeMult = mothership.getTimeMult().getMult();

                self.getTimeMult().modifyMult("AMX_004_BIT", timeMult);

                if (timeMult != 1f) {
                    float flameAdjust = Math.max(-0.1f, Math.min(0.1f, (timeMult - 1f) * -0.05f));
                    drone.getEngineController().extendFlame(this, flameAdjust, flameAdjust, flameAdjust);
                }

                self.getEnergyWeaponDamageMult().modifyMult("AMX_004_BIT",
                        mothership.getEnergyWeaponDamageMult().getMult());
                self.getEnergyWeaponRangeBonus().modifyMult("AMX_004_BIT",
                        mothership.getEnergyWeaponRangeBonus().getMult());
                self.getDamageToFighters().modifyMult("AMX_004_BIT", mothership.getDamageToFighters().getMult());
                self.getDamageToFrigates().modifyMult("AMX_004_BIT", mothership.getDamageToFrigates().getMult());
                self.getDamageToDestroyers().modifyMult("AMX_004_BIT", mothership.getDamageToDestroyers().getMult());
                self.getDamageToCruisers().modifyMult("AMX_004_BIT", mothership.getDamageToCruisers().getMult());
                self.getDamageToCapital().modifyMult("AMX_004_BIT", mothership.getDamageToCapital().getMult());
            }

            if (stage == AIStage.WAITING_ORDER || stage == AIStage.IN_CD) {
                drone.setExtraAlphaMult2(0f);
                drone.setLayer(CombatEngineLayers.BELOW_SHIPS_LAYER);
                drone.setRenderEngines(false);
                mainWeapon.setRemainingCooldownTo(0f);
            } else {
                drone.setExtraAlphaMult2(1f);
                drone.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
                drone.setRenderEngines(true);
            }

            float toFace = drone.getFacing();
            Vector2f speed = new Vector2f();
            float mult = 1f;

            switch (stage) {
                case IN_CD:
                    if (CD > 0) {
                        CD -= amount * timeMult;
                    } else {
                        setStage(AIStage.WAITING_ORDER);
                    }
                case WAITING_ORDER:
                    expectedLocation = getLocation(source, point);
                    break;
                case MOVE:
                    if (controlPoint1 == null || controlPoint2 == null) {
                        Vector2f startPos = new Vector2f(drone.getLocation());
                        bezierStartPoint = new Vector2f(startPos);
                        Vector2f endPos = target;
                        Vector2f direction = Vector2f.sub(endPos, startPos, null);
                        float distance = direction.length();

                        if (distance == 0f || Float.isNaN(distance) || distance < 0.001f) {
                            setStage(AIStage.ATTACK);
                            controlPoint1 = null;
                            controlPoint2 = null;
                            bezierStartPoint = null;
                            totalBezierDistance = 0f;
                            accumulatedDistance = 0f;
                            curveDirectionSet = false; // 重置曲线方向标记
                            break;
                        }

                        direction.normalise();
                        Vector2f perpendicular = new Vector2f(-direction.y, direction.x);

                        // 只在首次设置时随机选择曲线方向和参数
                        if (!curveDirectionSet) {
                            useRightCurve = Math.random() > 0.5;

                            // 贝塞尔曲线参数的小幅随机偏移（±0.15范围内）
                            float randomOffset1 = (float) (Math.random() * 0.3f - 0.15f); // -0.15 到 +0.15
                            float randomOffset2 = (float) (Math.random() * 0.3f - 0.15f); // -0.15 到 +0.15
                            float randomOffset3 = (float) (Math.random() * 0.3f - 0.15f); // -0.15 到 +0.15
                            float randomOffset4 = (float) (Math.random() * 0.3f - 0.15f); // -0.15 到 +0.15

                            randomFactor1 = 0.5f + randomOffset1; // 0.35f - 0.65f
                            randomFactor2 = 0.8f + randomOffset2; // 0.65f - 0.95f
                            randomFactor3 = 0.3f + randomOffset3; // 0.15f - 0.45f
                            randomFactor4 = 0.6f + randomOffset4; // 0.45f - 0.75f

                            curveDirectionSet = true;
                        }

                        if (useRightCurve) {
                            // 右侧曲线（心形右半部分）
                            controlPoint1 = new Vector2f(
                                    startPos.x - direction.x * distance * randomFactor1
                                            + perpendicular.x * distance * randomFactor2,
                                    startPos.y - direction.y * distance * randomFactor1
                                            + perpendicular.y * distance * randomFactor2);
                            controlPoint2 = new Vector2f(
                                    endPos.x - direction.x * distance * randomFactor3
                                            + perpendicular.x * distance * randomFactor4,
                                    endPos.y - direction.y * distance * randomFactor3
                                            + perpendicular.y * distance * randomFactor4);
                        } else {
                            // 左侧曲线（心形左半部分）
                            controlPoint1 = new Vector2f(
                                    startPos.x - direction.x * distance * randomFactor1
                                            - perpendicular.x * distance * randomFactor2,
                                    startPos.y - direction.y * distance * randomFactor1
                                            - perpendicular.y * distance * randomFactor2);
                            controlPoint2 = new Vector2f(
                                    endPos.x - direction.x * distance * randomFactor3
                                            - perpendicular.x * distance * randomFactor4,
                                    endPos.y - direction.y * distance * randomFactor3
                                            - perpendicular.y * distance * randomFactor4);
                        }

                        totalBezierDistance = calculateBezierLength(bezierStartPoint, controlPoint1, controlPoint2,
                                target);
                        bezierProgress = 0f;
                        accumulatedDistance = 0f;

                        // 调试：检查贝塞尔曲线参数
                        if (totalBezierDistance <= 0f || Float.isNaN(totalBezierDistance)) {
                            // 如果贝塞尔曲线长度异常，直接切换到攻击状态
                            setStage(AIStage.ATTACK);
                            controlPoint1 = null;
                            controlPoint2 = null;
                            bezierStartPoint = null;
                            totalBezierDistance = 0f;
                            accumulatedDistance = 0f;
                            curveDirectionSet = false; // 重置曲线方向标记
                            break;
                        }
                    }

                    mult = 3f; // 移动速度倍率

                    float moveDistance = maxSpeed * mult * amount * timeMult;
                    accumulatedDistance += moveDistance;

                    bezierProgress = Math.min(1f, accumulatedDistance / totalBezierDistance);

                    expectedLocation = calculateBezierPoint(
                            bezierStartPoint,
                            controlPoint1,
                            controlPoint2,
                            target,
                            bezierProgress);

                    if (bezierProgress >= 1f) {
                        setStage(AIStage.ATTACK);
                        controlPoint1 = null;
                        controlPoint2 = null;
                        bezierStartPoint = null;
                        totalBezierDistance = 0f;
                        accumulatedDistance = 0f;
                        curveDirectionSet = false; // 重置曲线方向标记
                    }
                    break;

                case ATTACK:
                    if (shipTarget == null || !shipTarget.isAlive()) {
                        shipTarget = AIUtils.getNearestEnemy(drone);
                        if (shipTarget != null && Misc.getDistance(drone.getLocation(),
                                shipTarget.getLocation()) > mainWeapon.getRange()) {
                            shipTarget = null;
                        }
                    }
                    if (shipTarget == null) {
                        attackCount = 0;
                        attackAngleOffset = 0f;
                        setStage(AIStage.GO_TO_LAND);
                        target = null;
                        fired = false;
                        return;
                    }

                    float attackRange = mainWeapon != null ? mainWeapon.getRange() : 300f;
                    float rotationDiff = Math.abs(MathUtils.getShortestRotation(drone.getFacing(),
                            VectorUtils.getAngle(drone.getLocation(), shipTarget.getLocation())));
                    float distanceToTarget = MathUtils.getDistance(drone.getLocation(), shipTarget.getLocation());

                    // 优先开火：只要朝向正确且在射程内就立即开火
                    if (rotationDiff <= 1f && distanceToTarget <= attackRange) {
                        drone.giveCommand(ShipCommand.FIRE, shipTarget.getLocation(), 0);
                        fired = true;
                    }

                    // 同时移动到更好的攻击位置（动态调整）
                    float dynamicAttackRange = mainWeapon != null ? mainWeapon.getRange() * 0.8f : 240f;
                    expectedLocation = Vector2f.add(shipTarget.getLocation(),
                            (Vector2f) Misc.getUnitVectorAtDegreeAngle(
                                    MathUtils.clampAngle(shipTarget.getFacing() + point * 60f + attackAngleOffset)
                                            + 120f)
                                    .scale(shipTarget.getCollisionRadius() + dynamicAttackRange),
                            null);

                    // 攻击完成后的处理
                    if (fired && mainWeapon != null && mainWeapon.getCooldownRemaining() > 0 &&
                            mainWeapon.getBeams().isEmpty()) {

                        attackCount++;
                        if (attackCount < maxAttackCount) {
                            mainWeapon.setRemainingCooldownTo(0f);
                            attackAngleOffset += (Math.random() > 0.5 ? 30f : -30f);
                            fired = false;
                        } else {
                            CD = attackCount * ((float) 5 / maxAttackCount);
                            attackCount = 0;
                            shipTarget = null;
                            attackAngleOffset = 0f;
                            setStage(AIStage.GO_TO_LAND);
                            target = null;
                            fired = false;
                        }
                    }
                    break;

                case MANUVER_TO_NEXT_ATTACK_POSITION:
                    setStage(AIStage.ATTACK);
                    break;
                case END_ATTACK:
                    setStage(AIStage.GO_TO_LAND);
                    break;
                case GO_TO_LAND:
                    mult = 3.5f * timeMult;
                    expectedLocation = getLocation(source, point);
                    if (MathUtils.getDistanceSquared(expectedLocation, drone.getLocation()) <= 900f) {
                        setStage(AIStage.IN_CD);
                    }
                    break;
            }

            float expectedFacing = VectorUtils.getAngle(drone.getLocation(), expectedLocation);
            if (stage.equals(AIStage.ATTACK)) {
                if (shipTarget != null) {
                    expectedFacing = VectorUtils.getAngle(drone.getLocation(), shipTarget.getLocation());
                } else {
                    expectedFacing = VectorUtils.getAngle(drone.getLocation(), target);
                }
            }
            if (stage.equals(AIStage.WAITING_ORDER) || stage.equals(AIStage.IN_CD)) {
                expectedFacing = getFacing(source, point);
            }

            float rotation = MathUtils.getShortestRotation(drone.getFacing(), expectedFacing);
            float effectiveTurnAmount = turnRate * amount * timeMult;
            if (Math.abs(rotation) > effectiveTurnAmount) {
                toFace = drone.getFacing() + Math.signum(rotation) * effectiveTurnAmount;
            } else {
                toFace = drone.getFacing() + rotation;
            }
            drone.setFacing(toFace);

            Vector2f diff = Vector2f.sub(expectedLocation, drone.getLocation(), null);

            if (diff.length() != 0) {
                if (!stage.equals(AIStage.WAITING_ORDER) && !stage.equals(AIStage.IN_CD)) {
                    float effectiveAmount = amount * timeMult;
                    if (diff.length() > maxSpeed * effectiveAmount) {
                        diff.scale(maxSpeed * mult * effectiveAmount / diff.length());
                    }
                    drone.getEngineController().forceShowAccelerating();
                    drone.giveCommand(ShipCommand.ACCELERATE, drone.getMouseTarget(), 0);
                    for (ShipEngineControllerAPI.ShipEngineAPI e : drone.getEngineController().getShipEngines()) {
                        drone.getEngineController().setFlameLevel(e.getEngineSlot(), 2f);
                    }
                }
            }
            Vector2f shouldMove = Vector2f.add(drone.getLocation(), diff, null);
            drone.getLocation().set(shouldMove);
        }

        private Vector2f calculateBezierPoint(Vector2f p0, Vector2f p1, Vector2f p2, Vector2f p3, float t) {
            float u = 1 - t;
            float tt = t * t;
            float uu = u * u;
            float uuu = uu * u;
            float ttt = tt * t;

            Vector2f point = new Vector2f();
            point.x = uuu * p0.x + 3 * uu * t * p1.x + 3 * u * tt * p2.x + ttt * p3.x;
            point.y = uuu * p0.y + 3 * uu * t * p1.y + 3 * u * tt * p2.y + ttt * p3.y;

            return point;
        }

        private float calculateBezierLength(Vector2f p0, Vector2f p1, Vector2f p2, Vector2f p3) {
            float length = 0f;
            int segments = 20;
            Vector2f prevPoint = new Vector2f(p0);

            for (int i = 1; i <= segments; i++) {
                float t = (float) i / segments;
                Vector2f currentPoint = calculateBezierPoint(p0, p1, p2, p3, t);
                length += MathUtils.getDistance(prevPoint, currentPoint);
                prevPoint = currentPoint;
            }

            return length;
        }

        public void notifyEnd() {
            ended = true;
        }

        public void beginLand() {
        }

        public void setStage(AIStage stage) {
            this.stage = stage;
        }

        public AIStage getStage() {
            return stage;
        }

        public ShipAPI getDrone() {
            return drone;
        }

        public void setTarget(Vector2f target, WeaponSide side) {
            if (target == null) {
                return;
            }

            // 随机选择攻击方向，不再依赖武器位置
            int facing = (Math.random() > 0.5) ? 1 : -1;

            float attackRange = mainWeapon != null ? mainWeapon.getRange() : 300f;
            Vector2f calculatedTarget = MathUtils.getPointOnCircumference(target,
                    facing * 60 * point, attackRange);

            if (drone != null && MathUtils.getDistanceSquared(drone.getLocation(), calculatedTarget) < 10f * 10f) {
                calculatedTarget = MathUtils.getPointOnCircumference(target,
                        facing * 60 * point, attackRange + 100f);
            }

            this.target = calculatedTarget;
        }
    }

    private static class ammoTracker implements AmmoTrackerAPI {

        private float ammoPerSecond;

        private ammoTracker(float ammoPerSecond, int maxAmmo, float reloadSize) {
            this.ammoPerSecond = ammoPerSecond;
            this.maxAmmo = maxAmmo;
            this.ammo = maxAmmo;
            this.reloadSize = reloadSize;
        }

        public void advance(float amount, int droneNum) {
            int max = maxAmmo - droneNum;
            if (ammo < max) {
                reloadProgress += ammoPerSecond * amount;
                while (reloadProgress >= reloadSize) {
                    reloadProgress -= (int) reloadSize;
                    ammo += (int) reloadSize;
                }
                if (ammo > max) {
                    ammo = max;
                }
            } else {
                reloadProgress = 0;
            }
        }

        @Override
        public void setAmmoPerSecond(float ammoPerSecond) {
            this.ammoPerSecond = ammoPerSecond;
        }

        float reloadProgress = 0;

        @Override
        public float getReloadProgress() {
            return reloadProgress / reloadSize;
        }

        int ammo = 0;

        @Override
        public void setAmmo(int ammo) {
            this.ammo = ammo;
        }

        @Override
        public boolean usesAmmo() {
            return true;
        }

        @Override
        public void addOneAmmo() {
            if (ammo < maxAmmo) {
                ammo += 1;
            }
        }

        @Override
        public boolean deductOneAmmo() {
            if (ammo >= 1) {
                ammo -= 1;
                return true;
            }
            return false;
        }

        @Override
        public int getAmmo() {
            return ammo;
        }

        @Override
        public float getAmmoPerSecond() {
            return ammoPerSecond;
        }

        private int maxAmmo;

        @Override
        public int getMaxAmmo() {
            return maxAmmo;
        }

        @Override
        public void resetAmmo() {
            this.ammo = maxAmmo;
        }

        @Override
        public void setMaxAmmo(int maxAmmo) {
            this.maxAmmo = maxAmmo;
        }

        private float reloadSize;

        @Override
        public float getReloadSize() {
            return reloadSize;
        }

        @Override
        public void setReloadSize(float reloadSize) {
            this.reloadSize = reloadSize;
        }

        @Override
        public void setReloadProgress(float progress) {
            this.reloadProgress = progress * reloadSize;
        }
    }

    /**
     * 将相对位置转化为绝对位置
     */
    public static Vector2f OffSetToLocation(Vector2f sLoc, float sFac, Vector2f offset) {
        Vector2f loc = new Vector2f(offset);
        VectorUtils.rotate(loc, sFac, loc);
        Vector2f.add(loc, sLoc, loc);
        return loc;
    }
}
