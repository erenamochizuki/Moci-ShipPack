package data.ai;

import com.fs.starfarer.api.Global;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.subsystems.drones.PIDController;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

import data.ai.Moci_CombatSquadAIV2._STATE_S;
import data.hullmods.Moci_LandingAI;
import data.hullmods.Moci_LandingSequence;
import data.hullmods.Moci_RepairBayScript;

public class Moci_RetreatModule extends Moci_BaseAIModule{

    private _STATE_S state = _STATE_S.RETREAT_FIGHT;
    private IntervalUtil AIInterval = new IntervalUtil(0.2f,0.3f);
    private Moci_CombatSquadAIV2 mainAI;
    private boolean shouldEvaluate = true;
    private float YLim = 0;

    public Moci_RetreatModule(ShipAPI ship, ShipAPI mothership, PIDController controller,Moci_CombatSquadAIV2 mainAI){
        super(ship,mothership,controller);
        this.mainAI = mainAI;
        YLim = (ship.getOwner()==0)?(-Global.getCombatEngine().getMapHeight()/2f+1500f):Global.getCombatEngine().getMapHeight()/2f-1500f;
    }

    @Override
    public void advance(float amount) {
        if(!shouldEvaluate){
            AIInterval.advance(amount);
            if(AIInterval.intervalElapsed()) shouldEvaluate = true;
        }
        if(shouldEvaluate){
            Moci_RepairBayScript.Moci_RepairBay bay = Moci_LandingSequence.findAutomaticRefitTarget(ship);
            if(bay != null) {
                state = _STATE_S.RETREAT_LANDING;
            } else if(Moci_LandingSequence.shouldTriggerRefit(ship)){
                state = _STATE_S.TOWARD;
            }
            if(mothership == null || !mothership.isAlive()||mothership.isHulk()){
                if(!AIUtils.getNearbyEnemies(ship, 1000f).isEmpty()||
                        (ship.getOwner() == 0?ship.getLocation().getY()>YLim:ship.getLocation().getY()<YLim)){
                    state = _STATE_S.RETREAT_FIGHT;
                }else{
                    state = _STATE_S.DIRECT_RETREAT;
                }
            }
            shouldEvaluate = false;
        }
        Vector2f expectedLocation = new Vector2f(ship.getLocation());
        float expectedFacing = 90f;
        if(ship.getOwner() == 0){
            expectedFacing = 270f;
        }
        Vector2f retreatDir;
        if(ship.getOwner() == 0){
            retreatDir = new Vector2f(0,-100f);
        }else{
            retreatDir = new Vector2f(0,100f);
        }

        switch (state){
            case RETREAT_FIGHT:
                Vector2f avoidDir = new Vector2f();
                for(ShipAPI e:AIUtils.getNearbyEnemies(ship,1000f)){
                    Vector2f.add(avoidDir,(Vector2f) Misc.getUnitVector(e.getLocation(),ship.getLocation()).scale(10f*(e.getHullSize().ordinal()-1)),avoidDir);
                }
                Vector2f.add(retreatDir,avoidDir,retreatDir);
                Vector2f.add(expectedLocation,retreatDir,expectedLocation);
                ShipAPI nearest = AIUtils.getNearestEnemy(ship);
                if(nearest!=null){
                    float dangerRange = 600f + nearest.getCollisionRadius();
                    if(Misc.getDistanceSq(ship.getLocation(),nearest.getLocation())<dangerRange*dangerRange){
                    expectedFacing = Misc.getAngleInDegrees(ship.getLocation(),nearest.getLocation());
                    }
                }
                controller.move(expectedLocation,ship);
                controller.rotate(expectedFacing,ship);
                ship.setRetreating(true,false);
                break;
            case DIRECT_RETREAT:
                boolean travelDriveOn = ship.getTravelDrive() != null && ship.getTravelDrive().isOn();
                
                // 如果旅行驱动已经开启，不再使用PID控制器
                if (!travelDriveOn) {
                    Vector2f.add(expectedLocation,retreatDir,expectedLocation);
                    controller.move(expectedLocation,ship);
                    controller.rotate(expectedFacing,ship);
                    if(ship.getVelocity().getX()>0){
                        ship.getVelocity().set(ship.getVelocity().getX()*0.99f,ship.getVelocity().getY());
                    }
                }
                
                ship.setRetreating(true,true);
                
                float facingError = Math.abs(MathUtils.getShortestRotation(ship.getFacing(),expectedFacing));
                
                // 调试日志
                if (Global.getCombatEngine().getPlayerShip() == ship || 
                    (Global.getCombatEngine().getPlayerShip() != null && 
                     MathUtils.getDistanceSquared(ship.getLocation(), Global.getCombatEngine().getPlayerShip().getLocation()) < 2000f * 2000f)) {
                    Global.getCombatEngine().maintainStatusForPlayerShip(
                        "retreat_debug_" + ship.getId(),
                        Global.getSettings().getSpriteName("ui", "icon_tactical_cr_penalty"),
                        "撤退调试",
                        "朝向误差: " + String.format("%.1f", facingError) + "° | 旅行驱动: " + (travelDriveOn ? "开启" : "关闭") + " | Y: " + String.format("%.0f", ship.getLocation().getY()),
                        false
                    );
                }
                
                // 尝试激活旅行驱动
                if(facingError <= 1f && !travelDriveOn){
                    ship.turnOnTravelDrive();
                    Global.getLogger(Moci_RetreatModule.class).info(
                        "僚机 " + ship.getName() + " 激活旅行驱动，当前Y坐标: " + ship.getLocation().getY()
                    );
                }
                break;
            case RETREAT_LANDING:
                Moci_RepairBayScript.Moci_RepairBay landingBay = Moci_LandingSequence.findAutomaticRefitTarget(ship);
                if (landingBay != null) {
                    // The same session owns every landing, including the optional squad AI path.
                    Moci_LandingAI.startLanding(ship, landingBay);
                }
                break;
            case TOWARD:
                if (mainAI != null) {
                    mainAI.getEscort().advance(amount);
                }
                break;

        }
        // 注释掉状态栏显示 - 显示编队成员舰船名称和状态
        // Global.getCombatEngine().maintainStatusForPlayerShip(ship, Global.getSettings().getSpriteName("ui", "icon_tactical_bdeck"),ship.getName()+ship.getVariant().getDesignation(),"RETREAT"+":"+state.toString(),true);

    }

    @Override
    public void forceEvaluateCircumstance() {
        shouldEvaluate = true;
    }
}
