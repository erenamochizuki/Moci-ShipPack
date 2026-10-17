package data.scripts.weapons.ai;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.Misc;

import data.scripts.weapons.Moci_Weapon2Effect;
import data.scripts.weapons.Moci_Weapon2Effect.Moci_EffectScript;

public class Moci_Weapon1AI implements AutofireAIPlugin {
    private static final float RANGE = 700; //激活条件 距离低于该值
    private static final float ARC = 10; //激活条件 位于前方该角度范围内
    private final WeaponAPI weapon;
    private final ShipAPI ship;
    private List<WeaponAPI> checkWeapon = new ArrayList<>();
    private WeaponAPI weapon2 = null; // 存储Moci_Weapon2的引用

    public Moci_Weapon1AI(WeaponAPI weapon) {
        this.weapon = weapon;
        this.ship = weapon.getShip();
        // 初始化时查找相关武器
        for(WeaponAPI w : ship.getAllWeapons()){
            // 检查需要监控弹药的武器
            if(w.getId().equals("Moci_heavry_vulcan") || w.getId().equals("Moci_heavry_phasecl")){
                checkWeapon.add(w);
            }
            
            // 保存对Moci_Weapon2的引用
            if(w.getId().equals("Moci_Weapon2")){
                weapon2 = w;
            }
        }
    }

    @Override
    public void advance(float amount) {
        shouldFire = false;
        
        // 如果船没有AI，则不触发
        if(ship.getShipAI() == null){
            return;
        }

        // 检查两个触发条件：
        // 1. 原有条件：任意监控武器弹药为0
        boolean oldConditionMet = false;
        for(WeaponAPI w : checkWeapon){
            if(w.getAmmo() == 0){
                oldConditionMet = true;
                break;
            }
        }
        
        // 2. 新增条件：Moci_Weapon2已经使用过且冷却中，同时不在冲锋状态
        boolean newConditionMet = false;
        boolean weapon2InCooldown = weapon2 != null && weapon2.getCooldownRemaining() > 0;
        boolean notInChargingState = true;
        
        // 获取Moci_Weapon2Effect的效果脚本实例
        Moci_EffectScript effectScript = Moci_Weapon2Effect.getEffectScriptInstance(ship);
        if (effectScript != null) {
            // 现在可以直接访问状态
            notInChargingState = !effectScript.isCharging();
        }
        
        // 新条件：weapon2冷却中 且 不在充能状态
        newConditionMet = weapon2InCooldown && notInChargingState;
        
        // 任一条件满足都可触发武器AI
        if(!oldConditionMet && !newConditionMet) {
            return;
        }

        // 武器冷却检查和目标搜索
        if (!weapon.isFiring() && weapon.getCooldownRemaining() == 0) {
            List<ShipAPI> ships = new ArrayList<>(Global.getCombatEngine().getShips());
            for(ShipAPI target : ships){
                // 目标存活检查
                if(!target.isAlive() || target.isHulk()) {
                    continue;
                }
                if(target == ship || target.getOwner() == ship.getOwner()) {
                    continue;
                }
                
                // 距离和角度检查
                float rangeSQ = target.getCollisionRadius() + RANGE;
                rangeSQ *= rangeSQ; // 使用平方距离比较以提高性能
                if(Misc.getDistanceSq(target.getLocation(), ship.getLocation()) < rangeSQ
                        && Misc.isInArc(ship.getFacing(), ARC, ship.getLocation(), target.getLocation())){
                    shouldFire = true;
                    break;
                }
            }
        }
    }

    private boolean shouldFire = false;

    @Override
    public boolean shouldFire() {
        return shouldFire;
    }

    @Override
    public void forceOff() {

    }

    private Vector2f target = null;

    @Override
    public Vector2f getTarget() {
        return target;
    }

    private ShipAPI targetShip = null;

    @Override
    public ShipAPI getTargetShip() {
        return targetShip;
    }

    @Override
    public WeaponAPI getWeapon() {
        return weapon;
    }

    private MissileAPI targetMissile = null;

    @Override
    public MissileAPI getTargetMissile() {
        return targetMissile;
    }
}
