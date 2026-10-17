package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;

/**
 * PsyZaku简化版装饰武器效果
 * 只支持_SOURCE槽位，根据SOURCE武器类型切换当前装饰武器的帧
 */
public class Moci_MechProjDecoWeaponEffect_PsyZaku implements EveryFrameWeaponEffectPlugin {
    boolean init = false;
    WeaponAPI source;
    boolean wasFiring = false;
    private boolean isAnimating = false;
    private String slotId;
    private ShipAPI ship;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        
        // 初始化
        if (!init) {
            init = true;
            ship = weapon.getShip();
            slotId = weapon.getSlot().getId();
            
            // 通过武器槽添加后缀寻找关联武器
            String sourceId = slotId + "_SOURCE";

            for (WeaponAPI w : ship.getAllWeapons()) {
                if (sourceId.equals(w.getSlot().getId())) {
                    source = w;
                    break;
                }
            }
            
            // 如果找到了SOURCE武器，进行初始化设置
            if (source != null) {
                // 计算当前装饰武器和SOURCE武器之间的坐标差
                Vector2f diff = new Vector2f();
                Vector2f.sub(weapon.getSlot().getLocation(), source.getSlot().getLocation(), diff);
                
                // 取当前装饰武器的炮塔模式第一个开火点坐标
                Vector2f firePoint = weapon.getSpec().getTurretFireOffsets().get(0);
                syncFirePoint(source, diff, firePoint);
                
                // 隐藏SOURCE武器
                hideSourceWeapon();
            }
        }

        // 根据_SOURCE武器类型切换当前装饰武器的动画帧
        if (source != null && weapon.getAnimation() != null) {
            int maxFrames = weapon.getAnimation().getNumFrames();
            
            // 根据_SOURCE武器类型设置当前装饰武器的帧
            if (source.getType() == WeaponType.MISSILE || 
                source.getType() == WeaponType.COMPOSITE || 
                source.getType() == WeaponType.SYNERGY) {
                // 导弹类型：如果最大帧数大于1，设置到第1帧
                if (maxFrames > 1) {
                    weapon.getAnimation().setFrame(1);
                } else {
                    weapon.getAnimation().setFrame(0);
                }
            } else if ((source.getType() == WeaponType.ENERGY || 
                      source.getType() == WeaponType.HYBRID) && 
                      maxFrames > 2) {
                // 能量/混合类型：如果最大帧数大于2，设置到第2帧
                weapon.getAnimation().setFrame(2);
            } else {
                // 其他情况设置到第0帧
                weapon.getAnimation().setFrame(0);
            }
        }

        // 角度同步和开火动画控制
        if (source != null) {
            // 同步角度
            weapon.setCurrAngle(source.getCurrAngle());
            
            // 持续隐藏SOURCE武器
            hideSourceWeapon();

            // 开火动画控制
            if (weapon.getAnimation() != null) {
                boolean isFiring = source.isFiring() &&
                        (source.getCooldownRemaining() == 0 ||
                                (source.getSpec().getBurstSize() > 1 && source.isInBurst()));

                if (isFiring) {
                    weapon.getAnimation().play();
                    wasFiring = true;
                    isAnimating = true;
                } else if (wasFiring) {
                    // 检查是否到达最后一帧
                    if (weapon.getAnimation().getFrame() >= weapon.getAnimation().getNumFrames() - 1) {
                        weapon.getAnimation().pause();
                        wasFiring = false;
                        isAnimating = false;
                    } else if (isAnimating) {
                        // 继续播放直到完成
                        weapon.getAnimation().play();
                    }
                } else {
                    weapon.getAnimation().pause();
                    isAnimating = false;
                }
            }
        }
    }

    /**
     * 隐藏SOURCE武器
     */
    private void hideSourceWeapon() {
        if (source == null) return;
        
        Color transparent = new Color(0f, 0f, 0f, 0f);
        if (source.getSprite() != null) {
            source.getSprite().setColor(transparent);
        }
        if (source.getBarrelSpriteAPI() != null) {
            source.getBarrelSpriteAPI().setColor(transparent);
        }
        if (source.getUnderSpriteAPI() != null) {
            source.getUnderSpriteAPI().setColor(transparent);
        }
        if (source.getGlowSpriteAPI() != null) {
            source.getGlowSpriteAPI().setColor(transparent);
        }

        // 隐藏导弹渲染数据
        if (source.getMissileRenderData() != null) {
            for (MissileRenderDataAPI render : source.getMissileRenderData()) {
                render.getSprite().setWidth(0);
                render.getSprite().setHeight(0);
            }
        }
    }

    /**
     * 批量修改开火点
     *
     * @param sync     关联武器的开火点V2的list
     * @param diff     武器槽之间的坐标差值
     * @param override 装饰武器的开火点
     */
    public static void syncFirePoint(WeaponAPI sync, Vector2f diff, Vector2f override) {
        if (override == null) {
            override = new Vector2f();
        }

        Vector2f.add(override, diff, diff);
        sync.ensureClonedSpec();
        int t = sync.getSpec().getTurretFireOffsets().size();
        int h = sync.getSpec().getHardpointFireOffsets().size();
        int hd = sync.getSpec().getHiddenFireOffsets().size();

        sync.getSpec().getTurretFireOffsets().clear();
        sync.getSpec().getHardpointFireOffsets().clear();
        sync.getSpec().getHiddenFireOffsets().clear();
        for (int i = 0; i < t; i++) {
            sync.getSpec().getTurretFireOffsets().add(new Vector2f(diff));
        }
        for (int i = 0; i < h; i++) {
            sync.getSpec().getHardpointFireOffsets().add(new Vector2f(diff));
        }
        for (int i = 0; i < hd; i++) {
            sync.getSpec().getHiddenFireOffsets().add(new Vector2f(diff));
        }

        if (sync.getMissileRenderData() != null) {
            for (MissileRenderDataAPI render : sync.getMissileRenderData()) {
                render.getSprite().setWidth(0);
            }
        }
    }
}
