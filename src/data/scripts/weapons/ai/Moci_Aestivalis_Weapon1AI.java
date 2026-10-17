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

public class Moci_Aestivalis_Weapon1AI implements AutofireAIPlugin {
    private static final float RANGE = 600; //激活条件 距离低于该值
    private static final float ARC = 5; //激活条件 位于前方该角度范围内
    private final WeaponAPI weapon;
    private final ShipAPI ship;
    public Moci_Aestivalis_Weapon1AI(WeaponAPI weapon) {
        this.weapon = weapon;
        this.ship = weapon.getShip();
    }

    @Override
    public void advance(float amount) {
        shouldFire = false;
        
        // 如果船没有AI，则不触发
        if(ship.getShipAI() == null){
            return;
        }

        // 武器冷却检查和目标搜索
        if (!weapon.isFiring() && weapon.getCooldownRemaining() == 0) {
            List<ShipAPI> ships = new ArrayList<>(Global.getCombatEngine().getShips());
            for(ShipAPI enemyShip : ships){
                // 目标存活检查
                if(!enemyShip.isAlive() || enemyShip.isHulk()) {
                    continue;
                }
                if(enemyShip == ship || enemyShip.getOwner() == ship.getOwner()) {
                    continue;
                }
                
                // 距离和角度检查
                float rangeSQ = enemyShip.getCollisionRadius() + RANGE;
                rangeSQ *= rangeSQ; // 使用平方距离比较以提高性能
                if(Misc.getDistanceSq(enemyShip.getLocation(), ship.getLocation()) < rangeSQ
                        && Misc.isInArc(ship.getFacing(), ARC, ship.getLocation(), enemyShip.getLocation())){
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

    private final Vector2f target = null;

    @Override
    public Vector2f getTarget() {
        return target;
    }

    private final ShipAPI targetShip = null;

    @Override
    public ShipAPI getTargetShip() {
        return targetShip;
    }

    @Override
    public WeaponAPI getWeapon() {
        return weapon;
    }

    private final MissileAPI targetMissile = null;

    @Override
    public MissileAPI getTargetMissile() {
        return targetMissile;
    }
}
