package data.scripts.weapons;

/* 海牛（Hi-v）翼部浮游炮控制器 —— 我方本体代码。
 *
 * 历史：这段逻辑原本就是我们的 Moci_HSITunnelController_new.java；合并版把它 fork 成 xi.arsenal 下的两个类
 * （PsychoframeHiNuController / PsychoframeHiNuHwsController）并让翼武器改指 fork。
 * 本轮已把 fork 完整收编回本类（生成、动画、索敌、传送、母舰时流、回归、船坞冷却等
 * 原逻辑一字未改），同时保留合并版在 fork 里加的四项改进：
 *   1) 精神感应框架加成真正生效：movementMult / turnMult 参与位移与转向计算；
 *   2) 攻击节奏：与 2.3.0 一致（每轮结束把武器冷却直接清零，不再保留 xi 的 ShotGate 惩罚）；
 *   3) 出生来源实体级记账 registerProvenance（替代 native null-ID 共享 AI flags）；
 *   4) 自有适配标记 ADAPTER_KEY（供 PsychoframeEnhancement 识别脚本化移动的浮游炮）。
 * 另加非战斗上下文守卫：改装界面等会推进武器脚本，这里不生成实体。
 */

import java.awt.Color;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import xi.arsenal.PsychoframeHiNuSupport;
import com.fs.starfarer.api.combat.AmmoTrackerAPI;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatAsteroidAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.util.Misc;
import data.scripts.visual.Moci_PsycommuDroneGlow;
import org.magiclib.util.MagicRender;

/**
 * 核心代码来自光环的中帮手
 * 新版本：4次攻击，第一次攻击后使用传送切换位置
 */
public class Moci_HSITunnelController_new implements EveryFrameWeaponEffectPlugin {
    private static final Logger log = Global.getLogger(Moci_HSITunnelController_new.class);


    // 动画播放速度控制变量
    private static final float DECO_ANIM_SPEED = 8f;
    private static final float LAUNCH_ANIM_SPEED = 8f;
    
    // 传送特效相关参数
    private static final float TELEPORT_DELAY = 0.10f; // 传送延迟时间
    
    // 残影效果开关和参数
    private static final boolean ENABLE_AFTERIMAGE = false;   // 残影效果开关（true=开启，false=关闭）
    private static final float AFTERIMAGE_FADE_IN = 0f;      // 残影淡入时间
    private static final float AFTERIMAGE_FADE_OUT = 0.75f;  // 残影淡出时间
    private static final float AFTERIMAGE_ALPHA_START = 0.95f; // 初始透明度
    private static final float AFTERIMAGE_ALPHA_END = 0f;    // 结束透明度
    
    // 击杀奖励参数
    private static final boolean ENABLE_KILL_BONUS = true;   // 击杀奖励开关（true=开启，false=关闭）
    private static final int BONUS_ATTACKS_PER_KILL = 1;     // 每次击杀增加的攻击次数

    // 无人机AI状态枚举
    public enum AIStage {
        WAITING_ORDER, // 等待指令
        MOVE, // 移动向目标
        ATTACK, // 攻击目标
        TELEPORTING, // 传送中
        MANUVER_TO_NEXT_ATTACK_POSITION, // 机动到下一个攻击位置
        END_ATTACK, // 结束攻击
        IN_CD, GO_TO_LAND // 返回母舰
    }

    private List<HSITunnelScript> drones = new ArrayList<>();

    /** Owned controller observations only; does not advance a drone or original callback. */
    public List<Map<String,Object>> psychoframeStatus() {
        return drones.stream().map(HSITunnelScript::psychoframeStatus).toList();
    }


    protected static final int MAX_AMMO = 8;
    private static final Map<Integer, int[]> fireFrame = new HashMap<>();
    private static final Map<Integer, Float> facingMap = new HashMap<>();
    private static final Map<Integer, Vector2f> offsetMap = new HashMap<>();
    int idleFrame = 3;
    private final ammoTracker ammoTracker = new ammoTracker(0.1f, 3, 1);
    private WeaponAPI decoWeapon;

    public enum WeaponSide {
        LEFT, RIGHT, NONE
    }

    static WeaponSide weaponSide = WeaponSide.NONE;

    static {
        fireFrame.put(0, new int[] { 4, 7 });
        fireFrame.put(1, new int[] { 8, 11 });
        fireFrame.put(2, new int[] { 12, 16 });
        facingMap.put(0, 180f);
        facingMap.put(1, 180f);
        facingMap.put(2, 180f);
        offsetMap.put(0, new Vector2f(-56f, -46f));
        offsetMap.put(1, new Vector2f(-58f, -26f));
        offsetMap.put(2, new Vector2f(-44f, -10f));
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
        // 浮游炮是独立机动的无人机，母舰这门口径武器只当触发器用。原实现拿 weapon.getRange() 过滤
        // 锁定目标：目标一旦超出这把装饰武器的射程就被清空，于是"锁定后按住开火"毫无反应；而鼠标
        // 目标走的是硬指定分支、没有这道射程门，所以同一位置用鼠标反而能出击。
        // 现在明确锁定/指定的目标一律接受，只有最后"就近找战机"的兜底仍按 range 收敛。
        if (target != null && target.getOwner() != ship.getOwner()) {
            targetLoc = target.getLocation();
        } else if (player) {
            targetLoc = ship.getMouseTarget();
        } else {
            Object test = ship.getAIFlags().getCustom(ShipwideAIFlags.AIFlags.MANEUVER_TARGET);
            if (test instanceof ShipAPI) {
                target = (ShipAPI) test;
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

    private int launching = -1;
    Vector2f targetLocation = null;

    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 改装界面等非战斗上下文也会推进武器脚本：那里生成的无人机没有真实出生来源，
        // 会被出生来源校验拒绝并直接中断游戏。只允许在真实战斗里运行本脚本。
        if (engine == null || weapon == null || weapon.getShip() == null
                || Global.getCurrentState() != com.fs.starfarer.api.GameState.COMBAT
                || Global.getCombatEngine() != engine) {
            return;
        }
        String slotId = weapon.getSlot().getId();
        if (slotId.endsWith("_L")) {
            weaponSide = WeaponSide.LEFT;
        } else if (slotId.endsWith("_R")) {
            weaponSide = WeaponSide.RIGHT;
        }

        weapon.getAnimation().pause();

        boolean anyDroneActive = false;
        for (HSITunnelScript drone : drones) {
            if (drone.getStage() != AIStage.WAITING_ORDER && drone.getStage() != AIStage.IN_CD) {
                anyDroneActive = true;
                break;
            }
        }
        int idleDrones = 0;
        for (HSITunnelScript drone : drones) {
            if (drone.getStage() == AIStage.WAITING_ORDER || drone.getStage() == AIStage.IN_CD) {
                idleDrones++;
            }
        }
        
        if (launching >= 0) {
            weapon.getAnimation().setFrameRate(LAUNCH_ANIM_SPEED);
            weapon.getAnimation().play();
            int[] frames = fireFrame.get(launching);
            if (weapon.getAnimation().getFrame() >= frames[1]) {
                launching = -1;
                weapon.getAnimation().setFrame(idleFrame);
            }
        } else {
            switch (idleDrones) {
                case 3:
                    weapon.getAnimation().setFrame(0);
                    break;
                case 2:
                    weapon.getAnimation().setFrame(1);
                    break;
                case 1:
                    weapon.getAnimation().setFrame(2);
                    break;
                default:
                    if (anyDroneActive) {
                        weapon.getAnimation().setFrame(idleFrame);
                        weapon.getAnimation().pause();
                    } else {
                        weapon.getAnimation().setFrame(0);
                        weapon.getAnimation().pause();
                    }
                    break;
            }
            weapon.getAnimation().pause();
        }

        if (idleDrones == 3) {
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

        boolean end = weapon.getShip() != null && !weapon.getShip().isAlive();
        if (end) {
            for (HSITunnelScript drone : drones) {
                if (drone.getDrone().isAlive()) {
                    drone.getDrone().getMutableStats().getHullDamageTakenMult().unmodify("RX_93_BIT");
                    engine.applyDamage(drone.getDrone(), drone.getDrone().getLocation(),
                            100000f, DamageType.ENERGY, 0f, true, false, weapon.getShip());
                }
            }
        }

        int droneNum = 0;
        for (int i = 0; i < 3; i++) {
            if (drones.size() < i + 1) {
                if (ammoTracker.deductOneAmmo()) {
                    spawnAndBindScript(engine, weapon, i);
                    droneNum++;
                } else {
                    break;
                }
            } else {
                if (!drones.get(i).drone.isAlive()) {
                    Moci_PsycommuDroneGlow.detach(drones.get(i).drone);
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

        checkAsteroidHitAndStop(weapon);

        for (HSITunnelScript drone : drones) {
            drone.advance(amount);
            drone.recordPsychoframe();
            if (end) {
                drone.notifyEnd();
                continue;
            }
            if (hasTarget) {
                if (drone.getStage() == AIStage.WAITING_ORDER) {
                    if (launching < 0) {
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
        }
    }

    private static Vector2f getLocation(WeaponAPI weapon, int i) {
        Vector2f offset = offsetMap.get(i);
        if (offset == null) {
            return weapon.getLocation();
        } else {
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
        ShipVariantAPI v = Global.getSettings().createEmptyVariant("Moci_RX_93_v2_bit_ShortTrail", spec);
        v.addWeapon("WS0001", "Moci_RX_93_v2_bit_shortbeam");
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
        // Owned per-entity source provenance is attached after native AI removal below.
        // Native null-ID/no-AI flags are shared and must not store a Hi-Nu mother link.

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
        try {
            PsychoframeHiNuSupport.registerProvenance(w, weapon);
        } catch (RuntimeException rejected) {
            // 出生来源校验失败时宁可丢掉这一架无人机，也不让异常穿到引擎把整局游戏打崩。
            log.warn("Hi-Nu drone birth rejected: " + rejected.getMessage(), rejected);
            engine.removeEntity(w);
            return;
        }

        drones.add(new HSITunnelScript(w, weapon, point, droneDecoWeapon));
        Moci_PsycommuDroneGlow.attach(w, weapon.getShip());
        w.setCustomData(PsychoframeHiNuSupport.ADAPTER_KEY, PsychoframeHiNuSupport.adapterMarker(getClass().getName()));
    }

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
        private int point = 0;
        private boolean fired = false;
        private final PsychoframeHiNuSupport.Probe psychoframeProbe = new PsychoframeHiNuSupport.Probe();
        public Map<String,Object> psychoframeStatus() { return psychoframeProbe.status(); }
        private void recordPsychoframe() {
            psychoframeProbe.record(drone, source, mainWeapon, stage.name(), point, attackCount, CD);
        }

        private WeaponAPI decoWeapon;
        
        // 攻击次数
        private int attackCount = 0;
        private final int maxAttackCount = 3;
        private float attackAngleOffset = 0f;
        
        // 传送相关变量
        private boolean teleporting = false;
        private float teleportTimer = 0f;
        private Vector2f teleportTarget = null;
        private float teleportFacing = 0f;
        private boolean teleportDone = false;

        private Vector2f controlPoint1 = null;
        private Vector2f controlPoint2 = null;
        private float bezierProgress = 0f;
        private Vector2f bezierStartPoint = null;
        private float totalBezierDistance = 0f;
        private float accumulatedDistance = 0f;

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
            if (decoWeapon == null || decoWeapon.getAnimation() == null)
                return;

            if (stage != AIStage.WAITING_ORDER && stage != AIStage.IN_CD) {
                if (decoWeapon.getAnimation().getFrame() < 7 ||
                        decoWeapon.getAnimation().getFrame() > 12) {
                    decoWeapon.getAnimation().setFrame(7);
                }
                decoWeapon.getAnimation().setFrameRate(DECO_ANIM_SPEED);
                decoWeapon.getAnimation().play();
            } else {
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

            boolean droneDeployed = stage != AIStage.WAITING_ORDER && stage != AIStage.IN_CD;
            Moci_PsycommuDroneGlow.update(drone, source == null ? null : source.getShip(), droneDeployed);

            updateDecoWeaponAnimation();

            float timeMult = 1f;

            if (drone != null && drone.isAlive() && source != null && source.getShip().isAlive()) {
                MutableShipStatsAPI self = drone.getMutableStats();
                MutableShipStatsAPI mothership = source.getShip().getMutableStats();

                timeMult = mothership.getTimeMult().getMult();
                self.getTimeMult().modifyMult("RX_93_BIT", timeMult);

                if (timeMult != 1f) {
                    float flameAdjust = Math.max(-0.1f, Math.min(0.1f, (timeMult - 1f) * -0.05f));
                    drone.getEngineController().extendFlame(this, flameAdjust, flameAdjust, flameAdjust);
                }

                self.getEnergyWeaponDamageMult().modifyMult("RX_93_BIT",
                        mothership.getEnergyWeaponDamageMult().getMult());
                self.getEnergyWeaponRangeBonus().modifyMult("RX_93_BIT",
                        mothership.getEnergyWeaponRangeBonus().getMult());
                self.getDamageToFighters().modifyMult("RX_93_BIT", mothership.getDamageToFighters().getMult());
                self.getDamageToFrigates().modifyMult("RX_93_BIT", mothership.getDamageToFrigates().getMult());
                self.getDamageToDestroyers().modifyMult("RX_93_BIT", mothership.getDamageToDestroyers().getMult());
                self.getDamageToCruisers().modifyMult("RX_93_BIT", mothership.getDamageToCruisers().getMult());
                self.getDamageToCapital().modifyMult("RX_93_BIT", mothership.getDamageToCapital().getMult());
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
            float effectiveMaxSpeed = maxSpeed * PsychoframeHiNuSupport.movementMult(source.getShip());
            float effectiveTurnRate = turnRate * PsychoframeHiNuSupport.turnMult(source.getShip());

            
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
                            break;
                        }

                        direction.normalise();
                        Vector2f perpendicular = new Vector2f(-direction.y, direction.x);

                        if (weaponSide == WeaponSide.RIGHT) {
                            controlPoint1 = new Vector2f(
                                    startPos.x - direction.x * distance * 0.5f + perpendicular.x * distance * 0.8f,
                                    startPos.y - direction.y * distance * 0.5f + perpendicular.y * distance * 0.8f);
                            controlPoint2 = new Vector2f(
                                    endPos.x - direction.x * distance * 0.3f + perpendicular.x * distance * 0.6f,
                                    endPos.y - direction.y * distance * 0.3f + perpendicular.y * distance * 0.6f);
                        } else {
                            controlPoint1 = new Vector2f(
                                    startPos.x - direction.x * distance * 0.5f - perpendicular.x * distance * 0.8f,
                                    startPos.y - direction.y * distance * 0.5f - perpendicular.y * distance * 0.8f);
                            controlPoint2 = new Vector2f(
                                    endPos.x - direction.x * distance * 0.3f - perpendicular.x * distance * 0.6f,
                                    endPos.y - direction.y * distance * 0.3f - perpendicular.y * distance * 0.6f);
                        }

                        totalBezierDistance = calculateBezierLength(bezierStartPoint, controlPoint1, controlPoint2,
                                target);
                        bezierProgress = 0f;
                        accumulatedDistance = 0f;
                    }

                    mult = 2.5f;
                    float moveDistance = effectiveMaxSpeed * mult * amount * timeMult;
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
                    }
                    break;

                case TELEPORTING:
                    // 传送状态：参考CA_SlashMissileAI的传送特效
                    drone.setJitter(drone, Color.cyan, 2f, 5, 2f, 3f);
                    teleportTimer += amount * timeMult;
                    
                    if (teleportTimer > TELEPORT_DELAY) {
                        if (!teleportDone) {
                            // 在传送前创建残影（留在原地）
                            if (ENABLE_AFTERIMAGE) {
                                createAfterimage(drone);
                            }
                            
                            // 执行传送
                            teleportDone = true;
                            drone.getLocation().set(teleportTarget);
                            drone.setFacing(teleportFacing);
                            
                            // 播放传送音效
                            /*
                            Global.getSoundPlayer().playSound("Moci_bit_blink", 1f, 0.7f,
                                    drone.getLocation(), drone.getVelocity());

                             */
                        } else {
                            // 传送完成，直接进入攻击状态
                            teleporting = false;
                            teleportTimer = 0f;
                            teleportDone = false;
                            setStage(AIStage.ATTACK);
                        }
                    }
                    break;

                case ATTACK:
                    // 转火逻辑：如果目标死亡或不存在，寻找新目标
                    if (shipTarget == null || !shipTarget.isAlive()) {
                        // 击杀奖励：如果击杀了目标且开启了击杀奖励，增加攻击次数
                        if (shipTarget != null && !shipTarget.isAlive() && ENABLE_KILL_BONUS && attackCount > 0) {
                            attackCount = Math.max(0, attackCount - BONUS_ATTACKS_PER_KILL);
                        }
                        
                        // 寻找新目标
                        shipTarget = AIUtils.getNearestEnemy(drone);
                        if (shipTarget != null
                                && Misc.getDistance(drone.getLocation(), shipTarget.getLocation())
                                        - shipTarget.getCollisionRadius() > mainWeapon.getRange()) {
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
                    // 站位半径 = 目标碰撞半径 + 射程的 30%~55%，但必须封顶在射程的 92% 以内：
                    // 大碰撞半径目标（R>300）会让“R + 0.3*射程”直接越过 700px 射程，
                    // 浮游炮就会卡在边缘永远够不着、不开火；同时用 attackCount/point 取确定值，
                    // 避免每帧重新随机导致浮游炮在原地抖。
                    float rangeMultiplier = 0.3f + ((attackCount * 7 + point * 3) % 25) * 0.01f;
                    float dynamicAttackRange = mainWeapon != null ? mainWeapon.getRange() * rangeMultiplier : 240f;
                    float attackStandoff = Math.min(shipTarget.getCollisionRadius() + dynamicAttackRange,
                            attackRange * 0.92f);
                    expectedLocation = Vector2f.add(shipTarget.getLocation(),
                            (Vector2f) Misc.getUnitVectorAtDegreeAngle(
                                    MathUtils.clampAngle(shipTarget.getFacing() + point * 60f + attackAngleOffset)
                                            + 120f)
                                    .scale(attackStandoff),
                            null);
                    float rotationDiff = Math.abs(MathUtils.getShortestRotation(drone.getFacing(),
                            VectorUtils.getAngle(drone.getLocation(), shipTarget.getLocation())));
                    float distanceToTarget = MathUtils.getDistance(drone.getLocation(), shipTarget.getLocation());

                    // 光束按命中目标外缘结算，射程判定要把目标碰撞半径算进去；
                    // 否则贴着大舰边缘停住时中心距永远 > 射程，浮游炮就会一直不开火。
                    if (rotationDiff <= 1f && distanceToTarget <= attackRange + shipTarget.getCollisionRadius()) {
                        drone.giveCommand(ShipCommand.FIRE, shipTarget.getLocation(), 0);
                        psychoframeProbe.fireRequested();
                        fired = true;
                    }
                    if (fired && mainWeapon != null && mainWeapon.getCooldownRemaining() > 0 &&
                            mainWeapon.getBeams().isEmpty()) {

                        attackCount++;
                        
                        // 关键修改：第一次攻击后使用传送，之后的攻击也使用传送
                        if (attackCount < maxAttackCount) {
                            // 重置武器冷却：回到 2.3.0 行为，直接清零（xi 的 ShotGate 连发惩罚已移除）
                            mainWeapon.setRemainingCooldownTo(0f);
                            
                            // 计算下一个攻击位置的角度偏移
                            attackAngleOffset += 180f + (Math.random() > 0.5 ? 45f : -45f);
                            
                            // 使用原有的攻击位置计算逻辑，距离在40%-60%之间随机浮动
                            float rangeMultiplier2 = 0.4f + ((attackCount * 11 + point * 5) % 20) * 0.01f;
                            float dynamicAttackRange2 = mainWeapon != null ? mainWeapon.getRange() * rangeMultiplier2 : 240f;
                            teleportTarget = Vector2f.add(shipTarget.getLocation(),
                                    (Vector2f) Misc.getUnitVectorAtDegreeAngle(
                                            MathUtils.clampAngle(shipTarget.getFacing() + point * 60f + attackAngleOffset)
                                                    + 120f)
                                            .scale(Math.min(shipTarget.getCollisionRadius() + dynamicAttackRange2,
                                                    attackRange * 0.92f)),
                                    null);
                            
                            // 计算传送后的朝向（直接朝向目标）
                            teleportFacing = VectorUtils.getAngle(teleportTarget, shipTarget.getLocation());
                            
                            // 进入传送状态
                            teleporting = true;
                            teleportTimer = 0f;
                            teleportDone = false;
                            setStage(AIStage.TELEPORTING);
                            
                            fired = false;
                        } else {
                            // 攻击次数达到上限，结束攻击
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
                    mult = 1.5f * timeMult;
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
            
            // 传送状态下不进行转向和移动
            if (!stage.equals(AIStage.TELEPORTING)) {
                float rotation = MathUtils.getShortestRotation(drone.getFacing(), expectedFacing);
                float effectiveTurnAmount = effectiveTurnRate * amount * timeMult;
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
                        if (diff.length() > effectiveMaxSpeed * effectiveAmount) {
                            diff.scale(effectiveMaxSpeed * mult * effectiveAmount / diff.length());
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
        
        /**
         * 为无人机创建残影效果
         * 只为船体和装饰武器创建残影，跳过主武器
         */
        private void createAfterimage(ShipAPI ship) {
            // 获取船体精灵的偏移量
            SpriteAPI sprite = ship.getSpriteAPI();
            float offsetX = sprite.getWidth() / 2 - sprite.getCenterX();
            float offsetY = sprite.getHeight() / 2 - sprite.getCenterY();

            // 计算旋转后的偏移量
            float trueOffsetX = (float) Math.cos(Math.toRadians(ship.getFacing() - 90f)) * offsetX -
                    (float) Math.sin(Math.toRadians(ship.getFacing() - 90f)) * offsetY;
            float trueOffsetY = (float) Math.sin(Math.toRadians(ship.getFacing() - 90f)) * offsetX +
                    (float) Math.cos(Math.toRadians(ship.getFacing() - 90f)) * offsetY;

            // 只为装饰武器创建残影
            for (WeaponAPI weapon : ship.getAllWeapons()) {
                // 跳过非装饰武器（主武器）
                if (!weapon.isDecorative()) continue;
                
                if (weapon.getSprite() == null) continue;

                // 检查武器是否被设置为完全透明
                boolean isHidden = false;
                if (weapon.getSprite() != null && weapon.getSprite().getColor().getAlpha() == 0) {
                    isHidden = true;
                }
                if (weapon.getBarrelSpriteAPI() != null && weapon.getBarrelSpriteAPI().getColor().getAlpha() == 0) {
                    isHidden = true;
                }
                if (weapon.getUnderSpriteAPI() != null && weapon.getUnderSpriteAPI().getColor().getAlpha() == 0) {
                    isHidden = true;
                }
                if (weapon.getGlowSpriteAPI() != null && weapon.getGlowSpriteAPI().getColor().getAlpha() == 0) {
                    isHidden = true;
                }

                if (isHidden) continue;

                // 获取武器贴图路径
                String spritePath;
                if (weapon.getAnimation() != null) {
                    // 对于动画武器，获取当前帧的贴图路径
                    int frame = weapon.getAnimation().getFrame();
                    String basePath = weapon.getSlot().isTurret() ?
                            weapon.getSpec().getTurretSpriteName() :
                            weapon.getSpec().getHardpointSpriteName();
                    // 移除_XX.png后缀（下划线+两位数字+.png = 7个字符）
                    if (basePath.endsWith(".png")) {
                        // 检查是否已经包含帧号
                        if (basePath.matches(".*_\\d{2}\\.png$")) {
                            basePath = basePath.substring(0, basePath.length() - 7);
                        } else {
                            basePath = basePath.substring(0, basePath.length() - 4);
                        }
                    }
                    // 添加帧号和后缀
                    spritePath = String.format("%s_%02d.png", basePath, frame);
                } else {
                    // 无动画的武器使用默认贴图
                    spritePath = weapon.getSlot().isTurret() ?
                            weapon.getSpec().getTurretSpriteName() :
                            weapon.getSpec().getHardpointSpriteName();
                }

                // 获取新的Sprite实例
                SpriteAPI weaponSprite = Global.getSettings().getSprite(spritePath);
                weaponSprite.setSize(weapon.getSprite().getWidth(), weapon.getSprite().getHeight());

                // 创建残影 - 使用白色(255,255,255)即为原色半透明
                MagicRender.battlespace(
                        weaponSprite,
                        new Vector2f(weapon.getLocation().x + trueOffsetX, weapon.getLocation().y + trueOffsetY),
                        new Vector2f(0, 0),
                        new Vector2f(weapon.getSprite().getWidth(), weapon.getSprite().getHeight()),
                        new Vector2f(0, 0),
                        weapon.getCurrAngle() - 90f,
                        0f,
                        new Color(255, 255, 255, (int) (AFTERIMAGE_ALPHA_START * 255)),
                        true,
                        0f,  // jitterRange
                        0f,  // jitterTilt
                        0f,  // flickerRange
                        0f,  // flickerMedian
                        0f,  // maxDelay
                        AFTERIMAGE_FADE_IN,   // fadein
                        0f,  // full - 完全显示时间
                        AFTERIMAGE_FADE_OUT,  // fadeout
                        CombatEngineLayers.BELOW_SHIPS_LAYER
                );
            }
        }

        public void setTarget(Vector2f target, WeaponSide side) {
            if (target == null) {
                return;
            }

            int facing = 1;
            if (side == WeaponSide.RIGHT) {
                facing = -1;
            }

            float attackRange = mainWeapon != null ? mainWeapon.getRange() : 300f;
            Vector2f calculatedTarget = MathUtils.getPointOnCircumference(target, facing * 60 * point, attackRange);

            if (drone != null && MathUtils.getDistance(drone.getLocation(), calculatedTarget) < 10f) {
                calculatedTarget = MathUtils.getPointOnCircumference(target, facing * 60 * point, attackRange + 100f);
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

    public static Vector2f OffSetToLocation(Vector2f sLoc, float sFac, Vector2f offset) {
        Vector2f loc = new Vector2f(offset);
        VectorUtils.rotate(loc, sFac, loc);
        Vector2f.add(loc, sLoc, loc);
        return loc;
    }
}
