package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import org.lwjgl.input.Keyboard;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Moci_TempHullMod extends BaseHullMod {
    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        ship.addListener(new effectListener(ship));
    }

    private static class effectListener implements AdvanceableListener {
        final ShipAPI ship;
        private final Map<WeaponAPI, WeaponAPI> weaponSwitch = new HashMap<>();
        Set<ShipAPI> modules = new HashSet<>();

        private effectListener(ShipAPI ship) {
            this.ship = ship;
            Set<WeaponAPI> group1 = new HashSet<>();
            for (WeaponAPI weapon : ship.getAllWeapons()) {
//                if (符合默认武器的判定条件 即模块不脱落的时候使用的武器){
//                    group1.add(weapon);
//                }
            }
            if (!group1.isEmpty()) {
                for (WeaponAPI weapon : group1) {
                    for (WeaponAPI weapon_ : ship.getAllWeapons()) {
//                        if (通过跟weapon互相对照，通过判定的武器被识别和group1中的武器对应){
//                            weaponSwitch.put(weapon, weapon_);
//                        }
                    }
                }
            }
            
            // 初始化时移除武器（通过临时全局插件）
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine != null) {
                for (WeaponAPI weapon : weaponSwitch.values()) {
                    // 把脱掉护甲后才使用的武器移出武器组（使用安全方法）
                    data.scripts.util.Moci_WeaponGroupSafetyUtil.safelyRemoveWeaponFromGroups(
                        engine, ship, weapon, true
                    );
                }
            }
            
            for (ShipAPI module : ship.getChildModulesCopy()) {
//                if (判定是可以脱落的模块) {
//                    modules.add(module);
//                }
            }
        }

        float timer = 0;
        final float maxTimer = 1;//按住Z一秒后脱落
        int key = Keyboard.KEY_Z; //键盘的Z键用来判定玩家执行模块脱落指令

        @Override
        public void advance(float amount) {
            if (modules.isEmpty()) {
                switchAction();
                ship.removeListener(this);
            }
            //当飞船是玩家手操
            if(ship.getShipAI()==null && ship== Global.getCombatEngine().getPlayerShip()){
                if(Keyboard.isKeyDown(key)){
                    timer+=amount;
                    if(timer>=maxTimer){
                        for(ShipAPI module : modules){
                            module.setParentStation(null);
                        }
                        switchAction();
                        ship.removeListener(this);
                    }
                }else{
                    timer=0;
                }
            }else{
//                if(AI驾驶的时候进行决定抛弃模块的判定){
//                    for(ShipAPI module : modules){
//                        module.setParentStation(null);
//                    }
//                    switchAction();
//                    ship.removeListener(this);
//                }
            }
        }

        private void switchAction() {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null) {
                return;
            }
            
            for (WeaponAPI weapon : weaponSwitch.keySet()) {
                WeaponGroupAPI g = ship.getWeaponGroupFor(weapon);
                if (g.isAutofiring()) {
                    int index = ship.getWeaponGroupsCopy().indexOf(g);
                    ship.giveCommand(ShipCommand.TOGGLE_AUTOFIRE, index, index);
                }
                g.addWeaponAPI(weaponSwitch.get(weapon));
                // 使用安全方法移除武器（通过临时全局插件）
                data.scripts.util.Moci_WeaponGroupSafetyUtil.safelyRemoveWeaponFromGroups(
                    engine, ship, weapon, true
                );
            }
        }
    }
}
