package data.shipsystems.scripts;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.loading.WeaponGroupType;
import org.lwjgl.util.vector.Vector2f;

import java.util.*;

/**
 * 需要切换的武器的武器槽id需要有共同的关键字
 * 系统激活后，被选中的武器组会切换至列表的下一个武器
 * AI无法使用
 */
public class Moci_System_RX_93_v2 extends BaseShipSystemScript {
    private static final Set<String> SLOT_KEYS = new HashSet<>();
    private final Map<String, List<WeaponAPI>> weapons = new HashMap<>();
    private final Map<WeaponAPI, sizeController> sizeControllers = new HashMap<>();

    static {
        SLOT_KEYS.add("ARM_R_SOURCE");//武器槽关键字 带有该关键字的武器槽上的武器会被归类到同一组 同组武器只应有一个非装饰武器
    }

    private boolean init = false;

    // 初始化方法，收集所有符合条件的武器
    private void init(ShipAPI ship) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            List<WeaponAPI> weaponList = null;
            // 检查武器槽是否包含关键字
            for (String key : SLOT_KEYS) {
                if (weapon.getSlot().getId().contains(key)) {
                    if (weapons.containsKey(key)) {
                        weaponList = weapons.get(key);
                    } else {
                        weaponList = new ArrayList<>();
                        weapons.put(key, weaponList);
                    }
                    break;
                }
            }
            // 如果武器符合条件，加入列表并创建大小控制器
            if (weaponList != null) {
                sizeController c = new sizeController(weapon);
                if (weapon.getSlot().isDecorative()) {
                    weaponList.add(weapon);
                    c.hide();
                } else {
                    weaponList.add(0, weapon);
                }
                sizeControllers.put(weapon,c);
            }
        }

        // 添加武器同步监听器
        ship.addListener(new WeaponSyncListener(weapons,ship));
    }
    private class WeaponSyncListener implements AdvanceableListener {
        private final ShipAPI ship;
        private final Map<String, List<WeaponAPI>> weapons;

        public WeaponSyncListener(Map<String, List<WeaponAPI>> weapons, ShipAPI ship) {
            this.weapons = weapons;
            this.ship = ship;
        }

        @Override
        public void advance(float amount) {
            // 动态查找当前的主武器（属于武器组的武器）
            for (List<WeaponAPI> weaponList : weapons.values()) {
                WeaponAPI masterWeapon = null;
                for (WeaponAPI weapon : weaponList) {
                    if (ship.getWeaponGroupFor(weapon) != null) {
                        masterWeapon = weapon;
                        break;
                    }
                }

                if (masterWeapon != null) {
                    // 同步所有武器方向
                    for (WeaponAPI weapon : weaponList) {
                        if (weapon != masterWeapon) {
                            weapon.setCurrAngle(masterWeapon.getCurrAngle());
                        }
                    }
                }
            }
            
            // 执行武器切换操作
            if (!action.isEmpty()) {
                for (WeaponGroupAPI group : action.keySet()) {
                    WeaponAPI[] ws = action.get(group);
                    group.addWeaponAPI(ws[1]);
                    ship.removeWeaponFromGroups(ws[0]);
                    sizeControllers.get(ws[1]).reset();
                    sizeControllers.get(ws[0]).hide();
                }
                action.clear();
            }
        }

        Map<WeaponGroupAPI, WeaponAPI[]> action = new HashMap<>();
        public void switchWeapon(WeaponGroupAPI group, WeaponAPI remove, WeaponAPI add) {
            action.put(group, new WeaponAPI[]{remove, add});
        }
    }

    private boolean done = false;

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        ShipAPI ship;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
        } else {
            return;
        }

        if (!init) {
            init = true;
            init(ship);
        }
        
        if (weapons.isEmpty()) {
            return;
        }
        
        // 执行武器切换
        if (!done) {
            done = true;
            WeaponGroupAPI g = ship.getSelectedGroupAPI();
            
            if (g != null) {
                if (g.getType() == WeaponGroupType.LINKED) {
                    for (WeaponAPI w : g.getWeaponsCopy()) {
                        WeaponAPI change = null;
                        for (List<WeaponAPI> ws : weapons.values()) {
                            if (ws.contains(w)) {
                                int i = ws.indexOf(w);
                                i++;
                                if (i == ws.size()) {
                                    i = 0;
                                }
                                change = ws.get(i);
                                break;
                            }
                        }
                        if (change != null) {
                            WeaponSyncListener listener = getListener(ship);
                            if (listener != null) {
                                listener.switchWeapon(g, w, change);
                            }
                        }
                    }
                } else {
                    // 处理单个武器
                    WeaponAPI w = g.getActiveWeapon();
                    for (List<WeaponAPI> ws : weapons.values()) {
                        if (ws.contains(w)) {
                            int i = ws.indexOf(w);
                            i++;
                            if (i == ws.size()) {
                                i = 0;
                            }
                            WeaponAPI change = ws.get(i);
                            WeaponSyncListener listener = getListener(ship);
                            if (listener != null) {
                                listener.switchWeapon(g, w, change);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        done = false;
        if (stats.getEntity() instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) stats.getEntity();
            if (!init) {
                init = true;
                init(ship);
            }
        }
    }

    @Override
    public String getInfoText(ShipSystemAPI system, ShipAPI ship) {
        if (!isUsable(system, ship)) {
            return "未选择有效武器";
        } else {
            return "就绪";
        }
    }

    @Override
    public boolean isUsable(ShipSystemAPI system, ShipAPI ship) {

        if (!init) {
            init = true;
            init(ship);
        }

        if (weapons.isEmpty()) {
            return false;
        }
        WeaponGroupAPI g = ship.getSelectedGroupAPI();
        if (g != null) {
            if (g.getType() == WeaponGroupType.LINKED) {
                for (WeaponAPI w : g.getWeaponsCopy()) {
                    for (List<WeaponAPI> ws : weapons.values()) {
                        if (ws.contains(w)) {
                            return true;
                        }
                    }
                }
            } else {
                WeaponAPI w = g.getActiveWeapon();
                for (List<WeaponAPI> ws : weapons.values()) {
                    if (ws.contains(w)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static class sizeController {
        final WeaponAPI weapon;
        //static final Vector2f mainSize = new Vector2f(24, 140); // 根据实际尺寸调整
        //static final Vector2f barrelSize = new Vector2f(24, 140); // 根据实际尺寸调整
         Vector2f mainSize = null;
         Vector2f barrelSize = null;

        private sizeController(WeaponAPI weapon) {
            this.weapon = weapon;
             SpriteAPI sprite = weapon.getSprite();
             if (sprite != null) {
                 this.mainSize = new Vector2f(sprite.getWidth(), sprite.getHeight());
             }
             sprite = weapon.getBarrelSpriteAPI();
             if (sprite != null) {
                 this.barrelSize = new Vector2f(sprite.getWidth(), sprite.getHeight());
             }
        }
        // 重置武器贴图
        public void reset() {
            weapon.getSprite().setWidth(mainSize.x);
            weapon.getSprite().setHeight(mainSize.y);
            if (barrelSize != null) {  // 添加null检查
            weapon.getBarrelSpriteAPI().setWidth(barrelSize.x);
            weapon.getBarrelSpriteAPI().setHeight(barrelSize.y);
            }
        }
        // 隐藏武器贴图
        public void hide(){
            weapon.getSprite().setWidth(0);
            weapon.getSprite().setHeight(0);
            if (barrelSize != null) {  // 添加null检查
            weapon.getBarrelSpriteAPI().setWidth(0);
            weapon.getBarrelSpriteAPI().setHeight(0);
            }
        }
    }private WeaponSyncListener getListener(ShipAPI ship) {
        if (ship.hasListenerOfClass(WeaponSyncListener.class)) {
            return ship.getListeners(WeaponSyncListener.class).get(0);
        }
        return null;
    }
}
