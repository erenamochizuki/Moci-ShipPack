package data.scripts.weapons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.combat.*;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.util.Misc;

/**
 * 核心代码来自光环的中帮手
 */
public class Moci_HSITunnelController_backup implements EveryFrameWeaponEffectPlugin {

    // 新增：动画播放速度控制变量
    private static final float DECO_ANIM_SPEED = 8f; // 装饰武器动画播放速度
    private static final float LAUNCH_ANIM_SPEED = 8f; // 发射动画播放速度

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

    protected static final int MAX_AMMO = 8; // 最大弹药/无人机数量
    // 不同点位无人机发射动画帧
    private static final Map<Integer, int[]> fireFrame = new HashMap<>();
    // 不同点位无人机朝向帧
    private static final Map<Integer, Float> facingMap = new HashMap<>();
    // 不同点位无人机偏移位置
    private static final Map<Integer, Vector2f> offsetMap = new HashMap<>();
    int idleFrame = 3; // 0帧为搭载所有无人机的帧，1帧为没有无人机的帧
    private final ammoTracker ammoTracker = new ammoTracker(
            0.1f // 每秒无人机回复速度
            , 3, // 最大弹药量
            1 // 每次装填数量
    );
    private WeaponAPI decoWeapon; // 新增：保存装饰武器的引用

    // 新增枚举表示武器位置
    public enum WeaponSide {
        LEFT, RIGHT, NONE
    }

    static WeaponSide weaponSide = WeaponSide.NONE;

    static {
        fireFrame.put(0, new int[] { 4, 7 }); // 1号位置的无人机发射动画帧范围
        fireFrame.put(1, new int[] { 8, 11 }); // 2号位置的无人机发射动画帧范围
        fireFrame.put(2, new int[] { 12, 16 }); // 3号位置的无人机发射动画帧范围
        facingMap.put(0, 180f); // 1号位置无人机朝向偏移
        facingMap.put(1, 180f); // 2号位置无人机朝向偏移
        facingMap.put(2, 180f); // 3号位置无人机朝向偏移
        offsetMap.put(0, new Vector2f(-56f, -46f)); // 1号位置偏移
        offsetMap.put(1, new Vector2f(-58f, -26f)); // 2号位置偏移
        offsetMap.put(2, new Vector2f(-44f, -10f)); // 3号位置偏移
        DamagingProjectileAPI p;
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
                targetLoc = ship.getMouseTarget();// Misc.findClosestShipEnemyOf(ship, ship.getMouseTarget(),
                                                  // ShipAPI.HullSize.FIGHTER, range, true);
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

    private int launching = -1; // 当前正在发射的无人机点位(-1表示没有在发射)
    Vector2f targetLocation = null;

    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {

        // 初始化武器位置判断
        String slotId = weapon.getSlot().getId();
        if (slotId.endsWith("_L")) {
            weaponSide = WeaponSide.LEFT;
        } else if (slotId.endsWith("_R")) {
            weaponSide = WeaponSide.RIGHT;
        }

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
        // 动画控制逻辑
        if (launching >= 0) {
            // 正在发射无人机
            weapon.getAnimation().setFrameRate(LAUNCH_ANIM_SPEED);
            weapon.getAnimation().play();
            int[] frames = fireFrame.get(launching);
            if (weapon.getAnimation().getFrame() >= frames[1]) {
                launching = -1;
                weapon.getAnimation().setFrame(idleFrame); // 发射完成后暂停在第1帧
            }
        } else {
            // 根据待命无人机数量设置帧
            switch (idleDrones) {
                case 3:
                    weapon.getAnimation().setFrame(0); // 所有无人机待命
                    break;
                case 2:
                    weapon.getAnimation().setFrame(1); // 2架无人机待命
                    break;
                case 1:
                    weapon.getAnimation().setFrame(2); // 1架无人机待命
                    break;
                default:
                    if (anyDroneActive) {
                        // 有无人机处于活动状态
                        weapon.getAnimation().setFrame(idleFrame); // 保持在空置帧
                        weapon.getAnimation().pause();
                    } else {
                        // 所有无人机都处于待命状态
                        weapon.getAnimation().setFrame(0); // 回到第0帧
                        weapon.getAnimation().pause();
                    }
                    break;
            }
            weapon.getAnimation().pause();
        }

        // 新增：当所有无人机回归时重置武器状态
        if (idleDrones == 3) {
            // 重置所有无人机武器冷却
            boolean inCD = false;
            for (HSITunnelScript drone : drones) {
                drone.mainWeapon.setRemainingCooldownTo(0f);
                if (drone.getStage() == AIStage.IN_CD) {
                    inCD = true;
                }
            }
            if (!inCD) {
                weapon.setRemainingCooldownTo(0f); // 重置母舰武器冷却
                weapon.setAmmo(weapon.getMaxAmmo()); // 重置母舰武器弹药
            }
        }

        /*
         * } else if (anyDroneActive) {
         * // 有无人机处于活动状态
         * weapon.getAnimation().setFrame(idleFrame); // 保持在第1帧
         * weapon.getAnimation().pause();
         * } else {
         * // 所有无人机都处于待命状态
         * weapon.getAnimation().setFrame(0); // 回到第0帧
         * weapon.getAnimation().pause();
         * }
         */

        /*
         * if (launching >= 0) {
         * weapon.getAnimation().setFrameRate(LAUNCH_ANIM_SPEED); // 设置动画速度
         * weapon.getAnimation().play();
         * int[] frames = fireFrame.get(launching);
         * if (weapon.getAnimation().getFrame() >= frames[1]) {
         * launching = -1;
         * weapon.getAnimation().setFrame(idleFrame);
         * }
         * } else {
         * weapon.getAnimation().setFrame(idleFrame);
         * }
         * 
         */

        // 检查母舰是否被摧毁
        boolean end = weapon.getShip() != null && !weapon.getShip().isAlive();
        if (end) {
            // 对每个存活的无人机造成100000能量伤害
            for (HSITunnelScript drone : drones) {
                if (drone.getDrone().isAlive()) {
                    // drone.getDrone().setExtraAlphaMult2(1f);
                    drone.getDrone().getMutableStats().getHullDamageTakenMult().unmodify("RX_93_BIT");
                    engine.applyDamage(drone.getDrone(), drone.getDrone().getLocation(),
                            100000f, DamageType.ENERGY, 0f, true, false, weapon.getShip());
                }
            }
        }

        // 管理无人机数量和状态
        int droneNum = 0;
        // if (weapon.isFiring()) {
        for (int i = 0; i < 3; i++) {
            if (drones.size() < i + 1) {
                if (ammoTracker.deductOneAmmo()) { // 消耗弹药并生成新无人机
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
        // }

        ammoTracker.advance(amount, droneNum);
        if (!weapon.isFiring()) {
            targetLocation = null;
        } else {
            if (targetLocation == null) {
                targetLocation = findTarget(weapon);
            }
        }
        boolean hasTarget = targetLocation != null;// !weapon.getBeams().isEmpty() &&
                                                   // weapon.getBeams().get(0).getDamageTarget() != null;

        // 检测母舰武器光束命中陨石并减速
        checkAsteroidHitAndStop(weapon);

        // int i = 0;
        for (HSITunnelScript drone : drones) {
            drone.advance(amount);
            if (end) {
                drone.notifyEnd();
                continue;
            }
            if (hasTarget) {
                if (drone.getStage() == AIStage.WAITING_ORDER) {
                    if (launching < 0)
                    // {
                    // continue;
                    // } else
                    {
                        launching = drone.point;
                        int[] frames = fireFrame.get(launching);
                        weapon.getAnimation().setFrame(frames[0]);
                        drone.setStage(AIStage.MOVE);
                        drone.setTarget(targetLocation, weaponSide);
                    }
                } else {
                    if (drone.getStage() != AIStage.IN_CD) {
                        drone.setStage(AIStage.MOVE);
                        drone.setTarget(targetLocation, weaponSide);
                    }
                }
            }
            // i++;
        }

    }

    private static Vector2f getLocation(WeaponAPI weapon, int i) {
        Vector2f offset = offsetMap.get(i);
        if (offset == null) {
            return weapon.getLocation();
        } else {
            // 如果是左侧武器，反转X轴偏移
            if (weaponSide == WeaponSide.LEFT) {
                offset = new Vector2f(offset.x, -offset.y);
            }
            return OffSetToLocation(weapon.getLocation(), weapon.getCurrAngle(), offset);
        }
    }

    private static float getFacing(WeaponAPI weapon, int i) {
        if (facingMap.containsKey(i)) {
            return weapon.getCurrAngle() + facingMap.get(i);
        }
        return weapon.getCurrAngle();
    }

    public void spawnAndBindScript(CombatEngineAPI engine, WeaponAPI weapon, int point) {
        ShipHullSpecAPI spec = Global.getSettings().getHullSpec("Moci_RX_93_v2_bit");
        ShipVariantAPI v = Global.getSettings().createEmptyVariant("Moci_RX_93_v2_bit_Trail", spec);
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

        // 新增：查找无人机上的装饰武器
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
        self.getEnergyWeaponFluxCostMod().modifyMult("RX_93_BIT", 0f);
        self.getHardFluxDissipationFraction().modifyFlat("RX_93_BIT", 1f);
        self.getHullDamageTakenMult().modifyMult("RX_93_BIT", 0f);
        self.getTimeMult().modifyMult("RX_93_BIT", 1f);
        self.getEnergyRoFMult().modifyMult("RX_93_BIT", 0.5f);
        self.getCRLossPerSecondPercent().modifyMult("RX_93_BIT", 0f);
        self.getEnergyWeaponDamageMult().modifyMult("RX_93_BIT", 0.5f);
        w.setDoNotFlareEnginesWhenStrafingOrDecelerating(true);
        w.setCollisionClass(CollisionClass.NONE);
        w.setFacing(weapon.getCurrAngle());
        Global.getCombatEngine().addEntity(w);
        w.getLocation().set(getLocation(weapon, point));
        w.setShipAI(null);

        // 创建无人机脚本时传入装饰武器
        drones.add(new HSITunnelScript(w, weapon, point, droneDecoWeapon));
    }

    /**
     * 检测母舰武器光束命中陨石并使其停止
     * 当母舰的目标指示装置命中陨石时，将陨石的速度和角速度归零
     * 用于解决无人机无法追上快速旋转陨石的问题
     */
    private void checkAsteroidHitAndStop(WeaponAPI weapon) {
        if (weapon == null || weapon.getBeams().isEmpty()) {
            return;
        }

        // 遍历母舰武器的所有光束
        for (Object beam : weapon.getBeams()) {
            CombatEntityAPI hitTarget = ((com.fs.starfarer.api.combat.BeamAPI) beam).getDamageTarget();

            // 检查是否命中目标且目标是陨石
            if (hitTarget != null && isAsteroid(hitTarget)) {
                // 将陨石的速度和角速度设为零
                hitTarget.getVelocity().set(0f, 0f);
                hitTarget.setAngularVelocity(0f);
            }
        }
    }

    /**
     * 判断目标是否为陨石
     *
     * @param entity 要检查的实体
     * @return 如果是陨石返回true，否则返回false
     */
    private boolean isAsteroid(CombatEntityAPI entity) {
        // 使用CombatAsteroidAPI进行精确的陨石检测
        if (entity instanceof CombatAsteroidAPI) {
            return true;
        }

        // 备用检测：检查碰撞类型是否为陨石
        return entity.getCollisionClass() == CollisionClass.ASTEROID;
    }

    public static class HSITunnelScript {
        private ShipAPI drone;
        private AIStage stage;

        private WeaponAPI source;
        private WeaponAPI mainWeapon; // 新增：保存主武器的引用

        private Vector2f target = null;

        private Vector2f expectedLocation = new Vector2f();

        private final float turnRate, maxSpeed;

        private boolean ended = false;

        private int point = 0; // 无人机编号 (0,1,2)

        private boolean fired = false;

        private WeaponAPI decoWeapon; // 新增：关联的装饰武器
        // 新增变量
        private int attackCount = 0; // 当前攻击次数
        private final int maxAttackCount = 3; // 最大攻击次数
        private float attackAngleOffset = 0f; // 新增：攻击角度偏移量

        private Vector2f controlPoint1 = null; // 贝塞尔曲线控制点1
        private Vector2f controlPoint2 = null; // 贝塞尔曲线控制点2
        private Vector2f bezierStartPoint = null; // 贝塞尔曲线固定起点
        private float bezierProgress = 0f; // 贝塞尔曲线进度(0-1)
        private final float moveSpeedFactor = 0.7f; // 曲线移动速度因子(0.1-1.0)

        public void resetPoint(int point) {
            this.point = point;
        }

        public HSITunnelScript(ShipAPI drone, WeaponAPI weapon, int point, WeaponAPI decoWeapon) {
            this.drone = drone;
            stage = AIStage.WAITING_ORDER;
            source = weapon;
            this.point = point; // 保存无人机编号
            this.decoWeapon = decoWeapon; // 初始化装饰武器
            // 初始化时随机生成攻击角度偏移
            this.attackAngleOffset = (float) (Math.random() * 120f - 60f); // -60到+60度随机偏移
            turnRate = // drone.getMaxTurnRate();
                    720f;
            maxSpeed = // drone.getMaxSpeed();
                    600f;
            this.point = point;

            // 新增：在构造方法中查找主武器
            for (WeaponAPI w : drone.getAllWeapons()) {
                if (!w.isDecorative()) { // 跳过装饰武器
                    this.mainWeapon = w;
                    break;
                }
            }
        }

        // 新增方法：更新无人机装饰武器动画
        private void updateDecoWeaponAnimation() {
            if (decoWeapon == null || decoWeapon.getAnimation() == null)
                return;

            if (stage != AIStage.WAITING_ORDER && stage != AIStage.IN_CD) {
                // 无人机处于活动状态，播放动画(7-10帧)
                if (decoWeapon.getAnimation().getFrame() < 7 ||
                        decoWeapon.getAnimation().getFrame() > 12) {
                    decoWeapon.getAnimation().setFrame(7);
                }
                decoWeapon.getAnimation().setFrameRate(DECO_ANIM_SPEED); // 设置动画速度
                decoWeapon.getAnimation().play();
            } else {
                // 无人机处于待命状态，设置静态帧
                int baseFrame = (weaponSide == WeaponSide.RIGHT) ? 1 : 4;
                decoWeapon.getAnimation().setFrame(baseFrame + point);
                decoWeapon.getAnimation().pause();
            }
        }

        ShipAPI shipTarget = null;
        float CD = 0;

        public void advance(float amount) {
            if (ended)
                return;

            // 更新装饰武器动画
            updateDecoWeaponAnimation();

            /*
             * // 调试信息：显示无人机偏移坐标和武器位置
             * String debugText = String.format("Offset: (%.1f, %.1f)\nSide: %s",
             * offsetMap.get(point).x, offsetMap.get(point).y, weaponSide);
             * Global.getCombatEngine().addFloatingText(
             * drone.getLocation(),
             * debugText,
             * 20f,
             * Color.WHITE,
             * drone,
             * 1f,
             * 0f
             * );
             * 
             */

            // 同步母舰状态
            if (drone != null && drone.isAlive() && source != null && source.getShip().isAlive()) {
                MutableShipStatsAPI self = drone.getMutableStats();
                MutableShipStatsAPI mothership = source.getShip().getMutableStats();

                // 同步时间流速
                self.getTimeMult().modifyMult("RX_93_BIT", mothership.getTimeMult().getMult());

                // 同步能量武器伤害
                self.getEnergyWeaponDamageMult().modifyMult("RX_93_BIT",
                        mothership.getEnergyWeaponDamageMult().getMult());

                // 同步能量武器射程
                self.getEnergyWeaponRangeBonus().modifyMult("RX_93_BIT",
                        mothership.getEnergyWeaponRangeBonus().getMult());

                // 同步对不同舰船类型的伤害加成
                self.getDamageToFighters().modifyMult("RX_93_BIT", mothership.getDamageToFighters().getMult());
                self.getDamageToFrigates().modifyMult("RX_93_BIT", mothership.getDamageToFrigates().getMult());
                self.getDamageToDestroyers().modifyMult("RX_93_BIT", mothership.getDamageToDestroyers().getMult());
                self.getDamageToCruisers().modifyMult("RX_93_BIT", mothership.getDamageToCruisers().getMult());
                self.getDamageToCapital().modifyMult("RX_93_BIT", mothership.getDamageToCapital().getMult());

                // 获取母舰的时流倍率
                float timeMult = mothership.getTimeMult().getMult();
                // 调整amount以反映时流倍率
                amount *= timeMult;
            }

            // 根据状态调整引擎火焰
            if (stage == AIStage.WAITING_ORDER || stage == AIStage.IN_CD) {
                // 设置无人机透明度
                drone.setExtraAlphaMult2(0f);
                // 设置无人机渲染层级
                drone.setLayer(CombatEngineLayers.BELOW_SHIPS_LAYER);
                // 设置无人机引擎渲染为关闭
                drone.setRenderEngines(false);
                // 设置无人机无敌
                // drone.getMutableStats().getHullDamageTakenMult().modifyMult("RX_93_BIT", 0f);
                // drone.setCollisionClass(CollisionClass.NONE);
                mainWeapon.setRemainingCooldownTo(0f);

            } else {
                // 设置无人机透明度
                drone.setExtraAlphaMult2(1f);
                // 设置无人机渲染层级
                drone.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
                // 设置无人机引擎渲染为开启
                drone.setRenderEngines(true);
                // 解除无人机无敌
                // drone.getMutableStats().getHullDamageTakenMult().unmodify("RX_93_BIT");
                // drone.setCollisionClass(CollisionClass.FIGHTER);
            }

            // 如果目标为空，设置状态为返回母舰
            // if (target == null) setStage(AIStage.GO_TO_LAND);
            // 如果有目标且当前状态是等待指令，设置为移动状态
            // if (target != null && stage.equals(AIStage.WAITING_ORDER))
            // setStage(AIStage.MOVE);
            float toFace = drone.getFacing();
            Vector2f speed = new Vector2f();
            float mult = 1f;
            switch (stage) {
                case IN_CD:
                    if (CD > 0) {
                        CD -= amount;
                    } else {
                        setStage(AIStage.WAITING_ORDER);
                    }
                case WAITING_ORDER:
                    // 等待指令状态：保持在武器指定位置
                    expectedLocation = getLocation(source, point);
                    break;
                case MOVE:
                    // 如果是刚开始移动，初始化贝塞尔曲线控制点
                    if (controlPoint1 == null || controlPoint2 == null || bezierStartPoint == null) {
                        bezierStartPoint = new Vector2f(drone.getLocation()); // 固定起点
                        Vector2f startPos = bezierStartPoint;
                        Vector2f endPos = target;
                        Vector2f direction = Vector2f.sub(endPos, startPos, null);
                        float distance = direction.length();
                        direction.normalise();

                        // 计算垂直于移动方向的向量
                        Vector2f perpendicular = new Vector2f(-direction.y, direction.x);

                        // 根据武器位置决定心形曲线的左右半部分
                        if (weaponSide == WeaponSide.RIGHT) {
                            // 右侧武器 - 绘制心形右半部分(凹陷曲线)
                            controlPoint1 = new Vector2f(
                                    startPos.x - direction.x * distance * 0.5f + perpendicular.x * distance * 0.8f,
                                    startPos.y - direction.y * distance * 0.5f + perpendicular.y * distance * 0.8f);
                            controlPoint2 = new Vector2f(
                                    endPos.x - direction.x * distance * 0.3f + perpendicular.x * distance * 0.6f,
                                    endPos.y - direction.y * distance * 0.3f + perpendicular.y * distance * 0.6f);
                        } else {
                            // 左侧武器 - 绘制心形左半部分(凹陷曲线)
                            controlPoint1 = new Vector2f(
                                    startPos.x - direction.x * distance * 0.5f - perpendicular.x * distance * 0.8f,
                                    startPos.y - direction.y * distance * 0.5f - perpendicular.y * distance * 0.8f);
                            controlPoint2 = new Vector2f(
                                    endPos.x - direction.x * distance * 0.3f - perpendicular.x * distance * 0.6f,
                                    endPos.y - direction.y * distance * 0.3f - perpendicular.y * distance * 0.6f);
                        }
                        bezierProgress = 0f;
                    }

                    // 更平滑的速度变化，避免中距离抖动
                    float currentDistance = MathUtils.getDistance(drone.getLocation(), target);
                    float speedMultiplier;
                    if (currentDistance >= 2000f) {
                        speedMultiplier = 1.0f; // 1000距离以上保持1倍速
                    } else if (currentDistance >= 600f) {
                        // 1200到600距离之间，速度从1f到1.3f缓慢增加
                        float ratio = (1200f - currentDistance) / (1200f - 600f);
                        speedMultiplier = 1.0f + ratio * 0.3f; // 1f~1.3f
                    } else if (currentDistance >= 300f) {
                        // 600到300距离之间，速度从1.3f到1.8f
                        float ratio = (600f - currentDistance) / (600f - 300f);
                        speedMultiplier = 1.3f + ratio * 0.5f; // 1.3f~1.8f
                    } else if (currentDistance >= 150f) {
                        // 300到150距离之间，速度从1.8f到2.2f
                        float ratio = (300f - currentDistance) / (300f - 150f);
                        speedMultiplier = 1.8f + ratio * 0.4f; // 1.8f~2.2f
                    } else {
                        speedMultiplier = 2.2f; // 150距离以下使用2.2倍速，避免过快
                    }

                    // 更新贝塞尔曲线进度
                    bezierProgress += amount * 0.5f * moveSpeedFactor * speedMultiplier;
                    if (bezierProgress > 1f)
                        bezierProgress = 1f;

                    // 计算贝塞尔曲线上的位置
                    expectedLocation = calculateBezierPoint(
                            bezierStartPoint,
                            controlPoint1,
                            controlPoint2,
                            target,
                            bezierProgress);

                    // 让实际移动速度与贝塞尔进度完全同步
                    mult = 2.5f * speedMultiplier;

                    // 如果贝塞尔进度完成，切换到攻击状态
                    if (bezierProgress >= 1.0f) {
                        setStage(AIStage.ATTACK);
                        controlPoint1 = null;
                        controlPoint2 = null;
                        bezierStartPoint = null;
                    }
                    break;
                // 旧的攻击指令
                /*
                 * 
                 * case ATTACK:
                 * expectedLocation = Vector2f.add(target.getLocation(),
                 * (Vector2f)
                 * Misc.getUnitVectorAtDegreeAngle(MathUtils.clampAngle(target.getFacing() +
                 * point * 60f) + 120f).scale(target.getCollisionRadius() + 50f), null
                 * );
                 * if (Math.abs(MathUtils.getShortestRotation(drone.getFacing(),
                 * VectorUtils.getAngle(drone.getLocation(), target.getLocation()))) <= 1f &&
                 * MathUtils.getDistance(drone.getLocation(), target.getLocation()) <=
                 * target.getCollisionRadius() + 200f) {
                 * drone.giveCommand(ShipCommand.FIRE, target.getLocation(), 0);
                 * fired = true;
                 * }
                 * if (fired && mainWeapon != null && mainWeapon.getCooldownRemaining() > 0 &&
                 * mainWeapon.getBeams().isEmpty()) {
                 * setStage(AIStage.END_ATTACK);
                 * target = null;
                 * fired = false;
                 * }
                 * break;
                 * 
                 */

                case ATTACK:
                    // 计算攻击位置，加入随机角度偏移
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
                    expectedLocation = Vector2f.add(shipTarget.getLocation(),
                            (Vector2f) Misc.getUnitVectorAtDegreeAngle(
                                    MathUtils.clampAngle(shipTarget.getFacing() + point * 60f + attackAngleOffset)
                                            + 120f)
                                    .scale(shipTarget.getCollisionRadius() + 300f),
                            null);

                    // 如果朝向正确且距离足够近，开火（加入武器范围判断）
                    float attackRange = mainWeapon != null ? mainWeapon.getRange() : 300f;
                    if (Math.abs(MathUtils.getShortestRotation(drone.getFacing(),
                            VectorUtils.getAngle(drone.getLocation(), shipTarget.getLocation()))) <= 1f &&
                            MathUtils.getDistanceSquared(drone.getLocation(), shipTarget.getLocation()) <= attackRange * attackRange) {

                        drone.giveCommand(ShipCommand.FIRE, shipTarget.getLocation(), 0);
                        fired = true;
                    }
                    if (fired && mainWeapon != null && mainWeapon.getCooldownRemaining() > 0 &&
                            mainWeapon.getBeams().isEmpty()) {

                        attackCount++;
                        if (attackCount < maxAttackCount) {
                            // 重置武器冷却
                            mainWeapon.setRemainingCooldownTo(0f);
                            // 随机选择下一个攻击角度偏移方向
                            attackAngleOffset += (Math.random() > 0.5 ? 30f : -30f);
                            fired = false;
                        } else {
                            // 攻击次数达到上限，结束攻击
                            // attackCount = 0;
                            // if(attackCount>0){
                            CD = attackCount * ((float) 5 / maxAttackCount);
                            attackCount = 0;
                            // }
                            shipTarget = null;
                            attackAngleOffset = 0f;
                            setStage(AIStage.GO_TO_LAND);
                            target = null;
                            fired = false;
                        }
                    }
                    break;

                case MANUVER_TO_NEXT_ATTACK_POSITION:
                    // 机动状态：移动到攻击位置
                    // expectedLocation = Vector2f.add(target,
                    // (Vector2f)
                    // Misc.getUnitVectorAtDegreeAngle(MathUtils.clampAngle(target.getFacing() +
                    // point * 60f) + 120f).scale(target.getCollisionRadius() + 400f), null
                    // );
                    // 如果到达位置，切换到攻击状态
                    // if (MathUtils.getDistanceSquared(expectedLocation, drone.getLocation()) <=
                    // 900f) {
                    setStage(AIStage.ATTACK);
                    // }
                    break;
                case END_ATTACK:
                    // 结束攻击状态：移动到撤离位置
                    // expectedLocation = Vector2f.add(target.getLocation(),
                    // (Vector2f)
                    // Misc.getUnitVectorAtDegreeAngle(MathUtils.clampAngle(target.getFacing() + (4
                    // - point) * 90f)).scale(target.getCollisionRadius() + 150f), null
                    // );
                    // 如果到达位置，返回母舰
                    // if (MathUtils.getDistanceSquared(expectedLocation, drone.getLocation()) <=
                    // 900f) {
                    setStage(AIStage.GO_TO_LAND);
                    // }
                    break;
                case GO_TO_LAND:
                    // 返回母舰状态：加速返回
                    mult = 1.5f;
                    expectedLocation = getLocation(source, point);
                    // 如果到达母舰位置，重置为等待状态
                    if (MathUtils.getDistanceSquared(expectedLocation, drone.getLocation()) <= 900f) {
                        setStage(AIStage.IN_CD);
                        // 重置母舰武器的冷却时间和弹药
                        // source.setRemainingCooldownTo(0f);
                        // source.setAmmo(source.getMaxAmmo());
                        // mainWeapon.setRemainingCooldownTo(0f);
                    }
                    break;
            }

            // 计算期望朝向
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

            // 平滑转向
            float rotation = MathUtils.getShortestRotation(drone.getFacing(), expectedFacing);
            if (Math.abs(rotation) > turnRate * amount) {
                toFace = drone.getFacing() + Math.signum(rotation) * turnRate * amount;
            } else {
                toFace = drone.getFacing() + rotation;
            }
            drone.setFacing(toFace);
            // drone.getVelocity().set(new Vector2f(0,0));

            // 计算移动向量
            Vector2f diff = Vector2f.sub(expectedLocation, drone.getLocation(), null);

            if (diff.length() != 0) {
                if (!stage.equals(AIStage.WAITING_ORDER) && !stage.equals(AIStage.IN_CD)) {
                    if (diff.length() > maxSpeed * amount) {
                        diff.scale(maxSpeed * mult * amount / diff.length());
                    }
                    drone.getEngineController().forceShowAccelerating();
                    drone.giveCommand(ShipCommand.ACCELERATE, drone.getMouseTarget(), 0);
                    for (ShipEngineControllerAPI.ShipEngineAPI e : drone.getEngineController().getShipEngines()) {
                        drone.getEngineController().setFlameLevel(e.getEngineSlot(), 2f);
                    }
                }
            }
            // 应用移动
            Vector2f shouldMove = Vector2f.add(drone.getLocation(), diff, null);
            drone.getLocation().set(shouldMove);
        }

        // 新增方法：计算三次贝塞尔曲线上的点
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
            int facing = 1;
            if (side == WeaponSide.RIGHT) {
                facing = -1;
            }
            this.target = MathUtils.getPointOnCircumference(target, facing * 60 * point, 1000);
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
     *
     * @param sLoc   目标位置
     * @param sFac   目标朝向
     * @param offset 相对坐标
     * @return
     */
    public static Vector2f OffSetToLocation(Vector2f sLoc, float sFac, Vector2f offset) {
        Vector2f loc = new Vector2f(offset);
        VectorUtils.rotate(loc, sFac, loc);
        Vector2f.add(loc, sLoc, loc);
        return loc;
    }
}
