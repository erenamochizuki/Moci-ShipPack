package data.ai;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.subsystems.drones.PIDController;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

import data.hullmods.Moci_LandingSequence;

/**
 * 编队AI - 基于MagicLib DroneSubsystem的实现
 * 使用PID控制器进行精确的移动和转向控制
 */
public class Moci_CombatSquadAIV2 implements ShipAIPlugin {

    private final ShipAPI ship;
    private final ShipAPI motherShip;
    private ShipAPI currentTarget;
    private ShipwideAIFlags aiFlags = new ShipwideAIFlags();
    private ShipAIConfig config = new ShipAIConfig();

    public enum _STATE_M {
        ESCORT, COMBAT, RETREAT
    }

    public enum _STATE_S {
        TOWARD, MATCH_SPEED, LEAVE,
        ATTACK_TARGET, COOP_FIGHT,
        RETREAT_FIGHT, DIRECT_RETREAT, RETREAT_LANDING
    }

    // PID控制器参数 - 基于MagicLib的默认值
    private final float KpX = 2f; // 移动比例系数
    private final float KdX = 2f; // 移动微分系数
    private final float KpR = 6f; // 转向比例系数
    private final float KdR = 0.5f; // 转向微分系数

    // 护航距离
    protected static final float ESCORT_RANGE_MAX = 400f;
    protected static final float ESCORT_IDEAL = 200f;
    protected static final float ESCORT_MIN = 100f;
    protected static final float ESCORT_RANGE_COMBAT = 2000f;

    private final PIDController controller = new PIDController(KpX, KdX, KpR, KdR);
    private _STATE_M mainState = _STATE_M.ESCORT;
    private int index = 0;// 从长机朝向开始，逆时针旋转30度的次数，仅对Escort有效

    private final Moci_AIModule escort;
    private final Moci_AIModule combat;
    private final Moci_AIModule retreat;
    private final ShipSystemAIScript systemAI;
    private final ShipSystemAIScript subSystemAI;

    private final IntervalUtil AIInterval = new IntervalUtil(0.1f, 0.2f);

    public Moci_CombatSquadAIV2(ShipAPI ship, ShipAPI motherShip, int index) {
        this.ship = ship;
        this.motherShip = motherShip;
        this.index = index;

        config.turnToFaceWithUndamagedArmor = false;
        config.backingOffWhileNotVentingAllowed = true;

        escort = new Moci_EscortModule(ship, motherShip, controller, index);
        combat = new Moci_CombatModule(ship, motherShip, controller, index, this);
        retreat = new Moci_RetreatModule(ship, motherShip, controller, this);

        if(ship.getSystem()!=null){
            systemAI = ship.getSystem().getSpecAPI().getAIScript();
            if(systemAI != null) {
                systemAI.init(ship, ship.getSystem(),ship.getAIFlags(), Global.getCombatEngine());
            }
        }else{
            systemAI = null;
        }

        if(ship.getPhaseCloak()!=null){
            subSystemAI = ship.getPhaseCloak().getSpecAPI().getAIScript();
            if(subSystemAI != null) {
                subSystemAI.init(ship, ship.getPhaseCloak(),ship.getAIFlags(), Global.getCombatEngine());
            }
        }else{
            subSystemAI = null;
        }

        // 启用所有武器组的自动开火
        for (int i = 0; i < ship.getWeaponGroupsCopy().size(); i++) {
            if (!ship.getWeaponGroupsCopy().get(i).isAutofiring()) {
                ship.giveCommand(ShipCommand.TOGGLE_AUTOFIRE, null, i);
            }
        }
    }

    @Override
    public void advance(float amount) {
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            // 清理护航模块的动态加成
            if (escort instanceof Moci_EscortModule) {
                ((Moci_EscortModule) escort).cleanup();
            }
            return;
        }
        AIInterval.advance(amount);
        if (systemAI != null)
            systemAI.advance(amount, new Vector2f(), new Vector2f(), ship.getShipTarget());
        if (subSystemAI != null)
            subSystemAI.advance(amount, new Vector2f(), new Vector2f(), ship.getShipTarget());
        manageShield();
        _STATE_M Lstate = mainState;
        if (AIInterval.intervalElapsed()) {
            ShipAPI closestE = AIUtils.getNearestEnemy(ship);
            if (closestE == null || MathUtils.getDistanceSquared(closestE.getLocation(),
                    ship.getLocation()) >= ESCORT_RANGE_COMBAT * ESCORT_RANGE_COMBAT) {
                mainState = _STATE_M.ESCORT;
            } else {
                mainState = _STATE_M.COMBAT;
            }
            if (Moci_LandingSequence.findAutomaticRefitTarget(ship) != null) {
                mainState = _STATE_M.RETREAT;
            }
            if (!isMotherShipAvailable()) {
                mainState = _STATE_M.RETREAT;
            }
        }
        boolean switched = Lstate != mainState;
        switch (mainState) {
            case ESCORT:
                ship.setRetreating(false, false);
                escort.advance(amount);
                if (switched)
                    escort.forceEvaluateCircumstance();
                break;
            case COMBAT:
                ship.setRetreating(false, false);
                // 切换到战斗模式时清理护航加成
                if (switched && escort instanceof Moci_EscortModule) {
                    ((Moci_EscortModule) escort).cleanup();
                }
                combat.advance(amount);
                if (switched)
                    combat.forceEvaluateCircumstance();
                break;
            case RETREAT:
                // 切换到撤退模式时清理护航加成
                if (switched && escort instanceof Moci_EscortModule) {
                    ((Moci_EscortModule) escort).cleanup();
                }
                retreat.advance(amount);
                if (switched)
                    retreat.forceEvaluateCircumstance();
                break;
        }
    }

    /**
     * 护盾管理
     */
    private void manageShield() {
        if (ship.getShield() == null)
            return;
        if (ship.getFluxLevel() >= 0.9f && ship.getHullLevel() > 0.2f && ship.getShield().isOn()) {
            ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, ship.getLocation(), 0);
        }
        if (AIInterval.intervalElapsed()) {
            if (ship.getShield().getType().equals(ShieldAPI.ShieldType.FRONT)) {
                boolean shouldOn = false;

                // 优先检查：如果有近距离导弹或抛射物威胁，立即开盾
                if (!AIUtils.getNearbyEnemyMissiles(ship, 300f).isEmpty() || hasNearbyProjectileThreat(300f)) {
                    shouldOn = true;
                } else {
                    // 检查潜在威胁：只考虑锁定我们的敌舰
                    for (ShipAPI e : AIUtils.getNearbyEnemies(ship, 2500f)) {
                        // 忽略过载的敌人
                        if (e.getFluxTracker().isOverloaded())
                            continue;

                        // 只处理锁定我们的敌舰
                        if (e.getShipTarget() != ship)
                            continue;

                        float dist = MathUtils.getDistance(ship.getLocation(), e.getLocation())
                                - e.getCollisionRadius();

                        for (WeaponAPI w : e.getAllWeapons()) {
                            if (w.isDisabled() || w.isDecorative())
                                continue;

                            // 简化：锁定我们的敌舰统一使用0.9安全系数
                            if (w.getRange() * 0.9f > dist) {
                                shouldOn = true;
                                break;
                            }
                        }
                        if (shouldOn)
                            break;
                    }
                }

                if ((ship.getShield().isOn() && !shouldOn) || (shouldOn && ship.getShield().isOff())) {
                    ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, ship.getLocation(), 0);
                }
            } else if (ship.getShield().getType().equals(ShieldAPI.ShieldType.OMNI)) {
                boolean shouldOn = false;
                float score = 0;
                Vector2f dangerLoc = ship.getLocation();

                // 优先检查：如果有近距离威胁，立即开盾并朝向威胁
                MissileAPI nearestMissile = AIUtils.getNearestEnemyMissile(ship);
                boolean hasProjectileThreat = hasNearbyProjectileThreat(300f);

                if ((nearestMissile != null
                        && Misc.getDistanceSq(nearestMissile.getLocation(), ship.getLocation()) < 300f * 300f)
                        || hasProjectileThreat) {
                    shouldOn = true;
                    if (nearestMissile != null) {
                        dangerLoc = nearestMissile.getLocation();
                    } else {
                        // 如果是抛射物威胁，朝向最近的敌人
                        ShipAPI nearestEnemy = AIUtils.getNearestEnemy(ship);
                        if (nearestEnemy != null) {
                            dangerLoc = nearestEnemy.getLocation();
                        }
                    }
                } else {
                    // 检查潜在威胁：只考虑锁定我们的敌舰
                    for (ShipAPI e : AIUtils.getNearbyEnemies(ship, 2500f)) {
                        // 忽略过载的敌人
                        if (e.getFluxTracker().isOverloaded())
                            continue;

                        // 只处理锁定我们的敌舰
                        if (e.getShipTarget() != ship)
                            continue;

                        float tempScore = 0;
                        for (WeaponAPI w : e.getAllWeapons()) {
                            if (w.isDisabled() || w.isDecorative())
                                continue;

                            // 简化威胁评分：只计算锁定我们的敌舰武器
                            float weaponThreat = w.getSize().ordinal() * (w.isBeam() ? 1.0f : 0.5f);
                            tempScore += weaponThreat;
                        }

                        if (tempScore > score) {
                            dangerLoc = e.getLocation();
                            score = tempScore;
                        }
                    }
                    shouldOn = score > 0.5f; // 降低阈值，因为只计算锁定我们的敌舰
                }

                if (ship.getShieldTarget() != null) {
                    ship.getShieldTarget().set(dangerLoc);
                }
                if ((ship.getShield().isOn() && !shouldOn) || (shouldOn && ship.getShield().isOff())) {
                    ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, ship.getLocation(), 0);
                }
            }
        }
    }

    private boolean isMotherShipAvailable() {
        return !(motherShip == null || !motherShip.isAlive() || motherShip.isHulk());
    }

    /**
     * 检查附近是否有敌方抛射物威胁
     */
    private boolean hasNearbyProjectileThreat(float range) {
        for (DamagingProjectileAPI proj : Global.getCombatEngine().getProjectiles()) {
            if (proj.getOwner() == ship.getOwner())
                continue; // 跳过友方抛射物

            float distSq = Misc.getDistanceSq(proj.getLocation(), ship.getLocation());
            if (distSq <= range * range) {
                return true;
            }
        }
        return false;
    }

    // 以下是ShipAIPlugin接口的其他必需方法
    @Override
    public void setDoNotFireDelay(float amount) {
    }

    @Override
    public void forceCircumstanceEvaluation() {
        AIInterval.forceIntervalElapsed();
    }

    @Override
    public boolean needsRefit() {
        return false;
    }

    @Override
    public ShipwideAIFlags getAIFlags() {
        return aiFlags;
    }

    @Override
    public void cancelCurrentManeuver() {
    }

    @Override
    public ShipAIConfig getConfig() {
        return config;
    }

    public static float getEscortMin() {
        return ESCORT_MIN;
    }

    public static float getEscortIdeal() {
        return ESCORT_IDEAL;
    }

    public static float getEscortRangeMax() {
        return ESCORT_RANGE_MAX;
    }

    public static float getEscortRangeCombat() {
        return ESCORT_RANGE_COMBAT;
    }

    public Moci_AIModule getCombat() {
        return combat;
    }

    public Moci_AIModule getEscort() {
        return escort;
    }

    public Moci_AIModule getRetreat() {
        return retreat;
    }
}
