package data.ai;

import java.util.Random;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.subsystems.drones.PIDController;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

import data.ai.Moci_CombatSquadAIV2._STATE_S;

public class Moci_CombatModule extends Moci_BaseAIModule{

    private _STATE_S state = _STATE_S.TOWARD;
    private IntervalUtil AIInterval = new IntervalUtil(0.2f,0.3f);
    private int index = 0;
    private boolean shouldEvaluate = true;
    private ShipAPI target = null;
    private final Moci_CombatSquadAIV2 mainAI;
    private float _RANGE_MAX = 0;
    private float _RANGE_MIN = 5000;
    private float _WEAPON_RANGE_CHECK = -10;
    private Vector2f expectedLocation;
    private final Random r = new Random();

    private int testTmp = 0;
    public Moci_CombatModule(ShipAPI ship, ShipAPI mothership, PIDController controller, int index,Moci_CombatSquadAIV2 mainAI){
        super(ship,mothership,controller);
        this.index = index;
        this.mainAI = mainAI;
        expectedLocation = ship.getLocation();
    }

    @Override
    public void advance(float amount) {
        if(!shouldEvaluate){
            AIInterval.advance(amount);
            if(AIInterval.intervalElapsed()) shouldEvaluate = true;
        }
        float weapon_integrity = 0;
        //expectedLocation = ship.getLocation();
        if(shouldEvaluate){
            float DFactor = 1;
            if(Global.getCombatEngine()!=null&&Global.getCombatEngine().getPlayerShip()==mothership){
                DFactor = 0.5F;
            }
            if(mothership.getShipTarget() == null){
                target = AIUtils.getNearestEnemy(ship);

                if(target == null||MathUtils.getDistance(ship.getLocation(),target.getLocation())-target.getCollisionRadius()>=Moci_CombatSquadAIV2.getEscortRangeCombat()*DFactor||Misc.getDistance(ship.getLocation(),mothership.getLocation())-ship.getCollisionRadius()-mothership.getCollisionRadius()>=Moci_CombatSquadAIV2.getEscortRangeCombat()*DFactor||ship.getFluxLevel()>=0.85f){
                    state = _STATE_S.TOWARD;
                }else {
                    state = _STATE_S.ATTACK_TARGET;
                }
            }else{
                target = mothership.getShipTarget();
                if(ship.getHullLevel()<=0.6f||Misc.getDistance(ship.getLocation(),mothership.getLocation())-ship.getCollisionRadius()-mothership.getCollisionRadius()>=Moci_CombatSquadAIV2.getEscortRangeCombat()*DFactor||ship.getFluxLevel()>=0.85f){
                    state = _STATE_S.TOWARD;
                }else {
                    state = _STATE_S.COOP_FIGHT;
                }
            }
            if(ship.getMutableStats().getBallisticWeaponRangeBonus().computeEffective(1000f)!=_WEAPON_RANGE_CHECK) {
                float weaponTotalSize = 0;
                float activeWeaponTotalSize = 0;
                for (WeaponAPI weapon : ship.getAllWeapons()) {
                    if (weapon.isDecorative()) continue;
                    if(weapon.getType().equals(WeaponAPI.WeaponType.MISSILE)&&weapon.usesAmmo()) continue;
                    weaponTotalSize+= (float) Math.pow(2,weapon.getSize().ordinal());
                    if (!weapon.isDisabled()&&weapon.getCooldownRemaining()<=10f){
                        activeWeaponTotalSize+=(float) Math.pow(2,weapon.getSize().ordinal());
                        if(weapon.getRange()>_RANGE_MAX) _RANGE_MAX = weapon.getRange();
                        if(weapon.getRange()<_RANGE_MIN) _RANGE_MIN = weapon.getRange();
                    }
                }
                weapon_integrity = (weaponTotalSize!=0)?activeWeaponTotalSize/weaponTotalSize:0;
                _WEAPON_RANGE_CHECK = ship.getMutableStats().getBallisticWeaponRangeBonus().computeEffective(1000f);
            }
            float targetLocalFP = 0;
            float selfLocalFP = 0;

            if(target!=null){
                float dist = MathUtils.getDistance(ship.getLocation(),target.getLocation())-target.getCollisionRadius()*0.8f;
                for(ShipAPI e:AIUtils.getNearbyAllies(target,1500f)){
                    targetLocalFP+=(e.getFleetMember()!=null?e.getFleetMember().getFleetPointCost():e.getHullSpec().getFleetPoints());
                }
                for(ShipAPI e:AIUtils.getNearbyAllies(ship,1500f)){
                    selfLocalFP+=(e.getFleetMember()!=null?e.getFleetMember().getFleetPointCost():e.getHullSpec().getFleetPoints());
                }
                float score = 0;
                int test = 0;
                switch (state){
                    case ATTACK_TARGET:
                    {
                        while (score<=0&&test<11){
                            score = 0;
                            Vector2f tempDest = ship.getLocation();
                            if(test<10&&ship.getHardFluxLevel()<=0.8f) {
                                tempDest = Misc.getPointWithinRadiusUniform(target.getLocation(),_RANGE_MIN-10f,_RANGE_MAX,r);
                            }else{
                                state = _STATE_S.TOWARD;
                            }
                            float distTempDest = MathUtils.getDistance(tempDest,target.getLocation())-target.getCollisionRadius()*0.8f;
                            if(ship.getFluxLevel()>0.8f){
                                if(distTempDest<=Math.max(_RANGE_MAX,2000f)){
                                    score-=0.5f;
                                    if(ship.getFluxLevel()>0.9f){
                                        score -= 0.25f;
                                    }
                                }
                            }else{
                                if(distTempDest>=_RANGE_MAX){
                                    score -= 1f;
                                }else{
                                    score+=0.5f;
                                    if(distTempDest>=_RANGE_MIN){
                                        score-=0.2f;
                                    }
                                }
                            }
                            score+=(weapon_integrity-0.5f);
                            float potential = 0;
                            for(ShipAPI e: CombatUtils.getShipsWithinRange(tempDest,1000f)){
                                if(e.getOwner()!=ship.getOwner()){
                                    for(WeaponAPI ew:e.getAllWeapons()){
                                        if(ew.isDecorative()) continue;
                                        if(ew.isDisabled()) continue;
                                        if(ew.getCooldownRemaining()>=(dist-distTempDest)/ship.getMaxSpeed()+10f) continue;
                                        if(Math.abs(ew.distanceFromArc(tempDest))>5) continue;
                                        float f = 1;
                                        if(!ew.isBeam()) f = 0.5f;
                                        if(ew.getTurnRate()<=10f) f*=0.7f;
                                        if(ew.getTurnRate()>=25f) f*=1.2f;
                                        potential+=ew.getDerivedStats().getDps()*f;
                                    }
                                }
                            }
                            if(potential<400){
                                score+=1;
                            }else if(potential<600){
                                score+=0.6f;
                            }else if(potential<800){
                                score+=0.2f;
                            }else{
                                score-=0.5f;
                            }
                            if(target.getFluxTracker().isOverloadedOrVenting()&&(target.getFluxTracker().getOverloadTimeRemaining()>=5f||target.getFluxTracker().getTimeToVent()>=5f)){
                                score+=0.75f;
                            }
                            if(target.getFluxLevel()>0.8f){
                                score+=0.4f;
                            }
                            score-=((targetLocalFP-selfLocalFP)/10f);
                            if(score>=0){
                                expectedLocation = tempDest;
                                break;
                            }
                            test++;
                        }
                    }
                    break;
                    case COOP_FIGHT:
                    {
                        while (score<=0&&test<13){
                            score = 0;
                            if(Misc.getDistanceSq(mothership.getLocation(),target.getLocation())<=1000f*1000f){
                                score+=0.4f;
                            }
                            Vector2f tempDest = ship.getLocation();
                            if(test<12&&ship.getHardFluxLevel()<=0.8f) {
                                float prefer = -1;
                                if(index*60f<90f||index*60f>270f){
                                    prefer = 1;
                                }
                                if(test<9){
                                    float angleDiff = prefer*140f+prefer*test*20f+(0.5f-r.nextFloat())*10f;
                                    tempDest = Vector2f.add(ship.getLocation(),(Vector2f) Misc.getUnitVectorAtDegreeAngle(Misc.normalizeAngle(Misc.getAngleInDegrees(target.getLocation(),mothership.getLocation())+angleDiff)).scale(_RANGE_MIN-120f+100f*r.nextFloat()),null);
                                }else{
                                    float angleDiff = -prefer*test*60f+(0.5f-r.nextFloat())*20f-prefer*140f;
                                    tempDest = Vector2f.add(ship.getLocation(),(Vector2f) Misc.getUnitVectorAtDegreeAngle(Misc.normalizeAngle(Misc.getAngleInDegrees(target.getLocation(),mothership.getLocation())+angleDiff)).scale(_RANGE_MIN-120f+100f*r.nextFloat()),null);
                                }
                            }else{
                                state = _STATE_S.TOWARD;
                            }
                            float distTempDest = MathUtils.getDistance(tempDest,target.getLocation())-target.getCollisionRadius()*0.8f;
                            if(ship.getFluxLevel()>0.8f){
                                if(distTempDest<=Math.max(_RANGE_MAX,2000f)){
                                    score-=0.5f;
                                    if(ship.getFluxLevel()>0.9f){
                                        score -= 0.25f;
                                    }
                                }
                            }else{
                                if(distTempDest>=_RANGE_MAX){
                                    score -= 1f;
                                }else{
                                    score+=0.5f;
                                    if(distTempDest>=_RANGE_MIN){
                                        score-=0.2f;
                                    }
                                }
                            }
                            score+=(weapon_integrity-0.5f);
                            float potential = 0;
                            for(ShipAPI e: CombatUtils.getShipsWithinRange(tempDest,1000f)){
                                if(e.getOwner()!=ship.getOwner()){
                                    for(WeaponAPI ew:e.getAllWeapons()){
                                        if(ew.isDecorative()) continue;
                                        if(ew.isDisabled()) continue;
                                        if(ew.getCooldownRemaining()>=(dist-distTempDest)/ship.getMaxSpeed()+10f) continue;
                                        if(Math.abs(ew.distanceFromArc(tempDest))>5) continue;
                                        float f = 1;
                                        if(!ew.isBeam()) f = 0.5f;
                                        if(ew.getTurnRate()<=10f) f*=0.7f;
                                        if(ew.getTurnRate()>=25f) f*=1.2f;
                                        potential+=ew.getDerivedStats().getDps()*f;
                                    }
                                }
                            }
                            if(potential<400){
                                score+=1;
                            }else if(potential<600){
                                score+=0.6f;
                            }else if(potential<800){
                                score+=0.2f;
                            }else{
                                score-=0.5f;
                            }
                            if(target.getFluxTracker().isOverloadedOrVenting()&&(target.getFluxTracker().getOverloadTimeRemaining()>=5f||target.getFluxTracker().getTimeToVent()>=5f)){
                                score+=0.75f;
                            }
                            if(target.getFluxLevel()>0.8f){
                                score+=0.4f;
                            }
                            score-=((targetLocalFP-selfLocalFP)/10f);
                            if(score>=0){
                                expectedLocation = tempDest;
                                break;
                            }
                            test++;
                        }
                    }
                }

                testTmp = test;
            }
            shouldEvaluate = false;
        }
        float expectedFacing = ship.getFacing();
        if(target!=null){
            expectedFacing = VectorUtils.getAngle(ship.getLocation(),target.getLocation());
        }
        switch (state){
            case ATTACK_TARGET:
            case COOP_FIGHT:
                controller.move(expectedLocation,ship);
                controller.rotate(expectedFacing,ship);
            break;
            case TOWARD:
                mainAI.getEscort().advance(amount);
                if(target!=null) controller.rotate(expectedFacing,ship);
        }
        // 注释掉状态栏显示 - 显示编队成员舰船名称和状态
        // Global.getCombatEngine().maintainStatusForPlayerShip(ship, Global.getSettings().getSpriteName("ui", "icon_tactical_bdeck"),ship.getName()+ship.getVariant().getDesignation(),"COMBAT"+":"+state.toString()+"||"+testTmp,true);
    }

    @Override
    public void forceEvaluateCircumstance() {
        shouldEvaluate = true;
    }
}
