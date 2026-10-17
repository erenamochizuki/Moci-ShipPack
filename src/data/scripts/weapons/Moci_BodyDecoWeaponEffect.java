package data.scripts.weapons;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

public class Moci_BodyDecoWeaponEffect implements EveryFrameWeaponEffectPlugin {
    boolean init = false;
    private List<WeaponAPI> linkedWeapons = new ArrayList<>();
    private ShipAPI armor = null;
    private static final Set<String> LINKED_SLOT_IDS = new HashSet<>(Arrays.asList("WS0001", "WS0002"));
    
    // 添加弹药回复相关变量
    private static final float AMMO_RELOAD_INTERVAL = 12.5f; // 弹药回复间隔（秒）
    private float ammoReloadTimer = 0f; // 弹药回复计时器
    
    // 添加武器组状态跟踪
    private boolean weaponsRemovedFromGroups = false; // 武器是否已从武器组中移除

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        // 初始化
        if (!init) {
            init = true;
            ShipAPI ship = weapon.getShip();
            
            // 查找BODY_ARMOR_HWS槽位的模块
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (module.getStationSlot() != null && module.getStationSlot().getId().equals("BODY_ARMOR_HWS")) {
                    armor = module;
                    break;
                }
            }
            
            // 查找所有指定的武器槽位
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (w.getSlot() != null && LINKED_SLOT_IDS.contains(w.getSlot().getId())) {
                    linkedWeapons.add(w);
                    
                    // 隐藏源武器的所有贴图
                    Color transparentColor = new Color(0, 0, 0, 0);
                    if (w.getSprite() != null) {
                        w.getSprite().setColor(transparentColor);
                    }
                    if (w.getBarrelSpriteAPI() != null) {
                        w.getBarrelSpriteAPI().setColor(transparentColor);
                    }
                    if (w.getUnderSpriteAPI() != null) {
                        w.getUnderSpriteAPI().setColor(transparentColor);
                    }
                    if (w.getGlowSpriteAPI() != null) {
                        w.getGlowSpriteAPI().setColor(transparentColor);
                    }

                    // 隐藏导弹贴图
                    if (w.getMissileRenderData() != null) {
                        for (MissileRenderDataAPI render : w.getMissileRenderData()) {
                            render.getSprite().setWidth(0);
                            render.getSprite().setHeight(0);
                        }
                    }
                }
            }
        }

        // 控制动画帧
        if (weapon.getAnimation() != null) {
            if (armor != null && armor.isAlive()) {
                weapon.getAnimation().setFrame(0);
            } else {
                weapon.getAnimation().setFrame(1);
            }
        }

        // 控制武器开火状态和弹药回复
        if (armor != null && armor.isAlive()) {
            // 装甲模块存在且存活，确保武器在武器组中
            if (weaponsRemovedFromGroups) {
                // 如果之前移除了武器组，现在需要重新添加
                // 注意：这里无法直接重新添加到原武器组，只能等玩家手动重新分配
                weaponsRemovedFromGroups = false;
            }
            
            // 更新弹药回复计时器
            ammoReloadTimer += amount;
            
            // 当计时器达到间隔时，回复所有链接武器的弹药
            if (ammoReloadTimer >= AMMO_RELOAD_INTERVAL) {
                for (WeaponAPI w : linkedWeapons) {
                    if (w != null && w.getMaxAmmo() > 0) {
                        w.setAmmo(w.getMaxAmmo());
                    }
                }
                ammoReloadTimer = 0f; // 重置计时器
            }
        } else {
            // 如果装甲模块不存在或已损坏，禁用武器开火并从武器组中移除
            for (WeaponAPI w : linkedWeapons) {
                if (w != null) {
                    w.setForceNoFireOneFrame(true);
                }
            }
            
            // 如果还没有移除武器组，则移除
            if (!weaponsRemovedFromGroups) {
                ShipAPI ship = weapon.getShip();
                if (ship != null) {
                    for (WeaponAPI w : linkedWeapons) {
                        if (w != null) {
                            // 使用安全方法移除武器（通过临时全局插件）
                            data.scripts.util.Moci_WeaponGroupSafetyUtil.safelyRemoveWeaponFromGroups(
                                engine, ship, w, true
                            );
                        }
                    }
                    weaponsRemovedFromGroups = true;
                }
            }
        }
    }
} 