package data.ai;

import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.subsystems.drones.PIDController;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

import data.ai.Moci_CombatSquadAIV2._STATE_S;

public class Moci_EscortModule extends Moci_BaseAIModule{

    private _STATE_S state = _STATE_S.TOWARD;
    private IntervalUtil AIInterval = new IntervalUtil(0.2f,0.3f);
    private int index = 0;
    private boolean shouldEvaluate = true;
    
    // 动态机动加成系统
    private static final String ESCORT_BONUS_ID = "moci_escort_repositioning_bonus";
    private static final float SPEED_BONUS = 100f; // 100%速度加成
    private static final float MANEUVER_BONUS = 50f; // 50%机动性加成
    private static final float OPTIMAL_POSITION_TOLERANCE = 80f; // 最佳位置容忍度
    private static final float MAX_BONUS_DISTANCE = 300f; // 最大加成距离
    private boolean bonusActive = false;

    public Moci_EscortModule(ShipAPI ship, ShipAPI mothership, PIDController controller,int index){
        super(ship,mothership,controller);
        this.index = index;
    }

    @Override
    public void advance(float amount) {
        if(!shouldEvaluate){
            AIInterval.advance(amount);
            if(AIInterval.intervalElapsed()) shouldEvaluate = true;
        }
        
        // 计算期望护航位置
        Vector2f expectedLocation = Vector2f.add(mothership.getLocation(),
                (Vector2f) Misc.getUnitVectorAtDegreeAngle(Misc.normalizeAngle(mothership.getFacing()+30f*index-90f))
                        .scale(Moci_CombatSquadAIV2.getEscortIdeal()),null);
        
        // 动态机动加成系统
        manageDynamicEscortBonus(expectedLocation);
        
        float expectedFacing = mothership.getFacing();
        if(shouldEvaluate){
            float dist = Misc.getDistance(ship.getLocation(),mothership.getLocation())-ship.getCollisionRadius()-mothership.getCollisionRadius();
            if(dist<=Moci_CombatSquadAIV2.getEscortMin()){
                state = _STATE_S.LEAVE;
            }else if(dist>=Moci_CombatSquadAIV2.getEscortRangeMax()){
                state = _STATE_S.TOWARD;
            }else{
                state = _STATE_S.MATCH_SPEED;
            }
            shouldEvaluate = false;
        }

        ShipAPI nearestEnemy = AIUtils.getNearestEnemy(ship);
        if(nearestEnemy!=null){
            float enemyFacingRange = Moci_CombatSquadAIV2.ESCORT_RANGE_COMBAT*0.5f;
            if(Misc.getDistanceSq(nearestEnemy.getLocation(),ship.getLocation())<enemyFacingRange*enemyFacingRange) expectedFacing = Misc.getAngleInDegrees(ship.getLocation(),nearestEnemy.getLocation());
        }
        if(mothership.getShipTarget()!=null&&nearestEnemy!=null){
            float targetDistSq = Misc.getDistanceSq(mothership.getShipTarget().getLocation(),ship.getLocation());
            float escortTargetRange = Moci_CombatSquadAIV2.getEscortRangeCombat()*0.75f;
            if(targetDistSq<=escortTargetRange*escortTargetRange&&targetDistSq<=Misc.getDistanceSq(nearestEnemy.getLocation(),ship.getLocation())) expectedFacing = Misc.getAngleInDegrees(ship.getLocation(),mothership.getShipTarget().getLocation());
        }
        
        float expectedDist = 0;
        switch (state){
            case LEAVE:
                expectedDist = Moci_CombatSquadAIV2.getEscortRangeMax();
                break;
            case TOWARD:
                expectedDist = Moci_CombatSquadAIV2.getEscortMin();
                break;
            case MATCH_SPEED:
                expectedDist = Moci_CombatSquadAIV2.getEscortIdeal();
                break;
        }
        expectedLocation = Vector2f.add(mothership.getLocation(),(Vector2f) Misc.getUnitVectorAtDegreeAngle(Misc.normalizeAngle(mothership.getFacing()+30f*index-90f)).scale(expectedDist),null);
        controller.move(expectedLocation,ship);
        controller.rotate(expectedFacing,ship);
        // 注释掉状态栏显示 - 显示编队成员舰船名称和状态
        // Global.getCombatEngine().maintainStatusForPlayerShip(ship, Global.getSettings().getSpriteName("ui", "icon_tactical_bdeck"),ship.getName()+ship.getVariant().getDesignation(),"ESCORT"+":"+state.toString(),true);
    }

    /**
     * 动态机动加成管理系统
     * 根据僚机与期望护航位置的距离动态调整机动性能
     */
    private void manageDynamicEscortBonus(Vector2f optimalPosition) {
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            removeDynamicBonus();
            return;
        }
        
        // 计算与最佳护航位置的距离
        float distanceFromOptimalSq = Misc.getDistanceSq(ship.getLocation(), optimalPosition);
        
        // 判断是否需要加成
        boolean shouldHaveBonus = distanceFromOptimalSq > OPTIMAL_POSITION_TOLERANCE * OPTIMAL_POSITION_TOLERANCE;
        
        if (shouldHaveBonus && !bonusActive) {
            // 应用动态加成
            applyDynamicBonus((float) Math.sqrt(distanceFromOptimalSq));
        } else if (!shouldHaveBonus && bonusActive) {
            // 移除加成
            removeDynamicBonus();
        } else if (shouldHaveBonus && bonusActive) {
            // 更新加成强度（基于距离）
            updateBonusIntensity((float) Math.sqrt(distanceFromOptimalSq));
        }
    }
    
    /**
     * 应用动态机动加成
     */
    private void applyDynamicBonus(float distance) {
        MutableShipStatsAPI stats = ship.getMutableStats();
        
        // 计算加成强度（距离越远加成越大，但有上限）
        float intensityFactor = Math.min(1f, distance / MAX_BONUS_DISTANCE);
        float actualSpeedBonus = SPEED_BONUS * intensityFactor;
        float actualManeuverBonus = MANEUVER_BONUS * intensityFactor;
        
        // 应用速度加成
        stats.getMaxSpeed().modifyPercent(ESCORT_BONUS_ID, actualSpeedBonus);
        stats.getAcceleration().modifyPercent(ESCORT_BONUS_ID, actualSpeedBonus);
        stats.getDeceleration().modifyPercent(ESCORT_BONUS_ID, actualSpeedBonus);
        
        // 应用机动性加成
        stats.getMaxTurnRate().modifyPercent(ESCORT_BONUS_ID, actualManeuverBonus);
        stats.getTurnAcceleration().modifyPercent(ESCORT_BONUS_ID, actualManeuverBonus);
        
        bonusActive = true;
    }
    
    /**
     * 更新加成强度
     */
    private void updateBonusIntensity(float distance) {
        // 重新计算并应用加成
        removeDynamicBonus();
        applyDynamicBonus(distance);
    }
    
    /**
     * 移除动态加成
     */
    private void removeDynamicBonus() {
        if (!bonusActive) return;
        
        MutableShipStatsAPI stats = ship.getMutableStats();
        
        // 移除所有相关的统计修改
        stats.getMaxSpeed().unmodify(ESCORT_BONUS_ID);
        stats.getAcceleration().unmodify(ESCORT_BONUS_ID);
        stats.getDeceleration().unmodify(ESCORT_BONUS_ID);
        stats.getMaxTurnRate().unmodify(ESCORT_BONUS_ID);
        stats.getTurnAcceleration().unmodify(ESCORT_BONUS_ID);
        
        bonusActive = false;
    }
    
    /**
     * 获取当前是否有动态加成
     */
    public boolean hasDynamicBonus() {
        return bonusActive;
    }
    
    /**
     * 清理方法（当模块被销毁时调用）
     */
    public void cleanup() {
        removeDynamicBonus();
    }

    @Override
    public void forceEvaluateCircumstance() {
        shouldEvaluate = true;
    }
}
