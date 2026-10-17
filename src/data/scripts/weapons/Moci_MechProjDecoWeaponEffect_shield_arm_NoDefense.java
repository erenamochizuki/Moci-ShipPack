package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import data.scripts.util.Moci_ColorData;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import data.scripts.weapons.Moci_MechDecoWeaponMutexControl;

/**
 * 机甲护盾臂控制器 - 无防御版本
 * 移除了智能防御逻辑，护盾臂始终跟随SOURCE武器角度
 */
public class Moci_MechProjDecoWeaponEffect_shield_arm_NoDefense implements EveryFrameWeaponEffectPlugin {
    ShipAPI ship;
    boolean init = false;
    private ShipAPI shield = null;
    protected WeaponAPI source; // 关联的_SOURCE武器
    WeaponAPI sourceFollow; // FOLLOW武器引用
    private int shieldAnimationFrame = 0; // 护盾动画帧（0:无盾牌, 1:普通护盾, 2:例外护盾）
    
    // 例外护盾船体列表（使用第2帧）
    private static final String[] EXCEPTION_SHIELD_HULLS = {
        "Moci_RX_93_v2_shield_hws"
    };

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        // 初始化
        if (!init) {
            init = true;
            ship = weapon.getShip();
            String slotId = weapon.getSlot().getId();
            String shieldId = "SHIELD_" + slotId.substring(slotId.lastIndexOf("_") + 1);

            // 查找对应的盾牌
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (module.getStationSlot() != null && module.getStationSlot().getId().equals(shieldId)) {
                    shield = module;
                    // 根据船体列表确定动画帧
                    String hullId = shield.getHullSpec().getHullId();
                    boolean isException = false;
                    for (String exceptionHull : EXCEPTION_SHIELD_HULLS) {
                        if (exceptionHull.equals(hullId)) {
                            isException = true;
                            break;
                        }
                    }
                    shieldAnimationFrame = isException ? 2 : 1; // 例外用第2帧，普通用第1帧
                    break;
                }
            }

            // 查找关联的_SOURCE武器和_FOLLOW武器
            String sourceId = slotId + "_SOURCE";
            String followId = slotId + "_FOLLOW";
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (sourceId.equals(w.getSlot().getId())) {
                    source = w;
                }
                if (followId.equals(w.getSlot().getId())) {
                    sourceFollow = w;
                }
            }

            // 如果找到了SOURCE和FOLLOW武器，初始化FOLLOW武器
            if (source != null && sourceFollow != null) {
                initializeFollowWeapon(weapon);
            }
        }

        // 控制动画
        if (weapon.getAnimation() != null) {
            if (shield != null && shield.isAlive()) {
                weapon.getAnimation().setFrame(shieldAnimationFrame);
            } else {
                weapon.getAnimation().setFrame(0);
            }
        }

        // 同步FOLLOW武器角度并持续隐藏
        if (sourceFollow != null && source != null) {
            sourceFollow.setCurrAngle(source.getCurrAngle());
            hideFollowWeapon();
        }

        // ========== 互斥控制：检查光剑是否正在挥动 ==========
        if (Moci_MechDecoWeaponMutexControl.isBladeSwingActive(weapon)) {
            if (source != null) {
                Moci_MechDecoWeaponMutexControl.syncAngleOnly(weapon, source);
                if (sourceFollow != null) {
                    sourceFollow.setCurrAngle(source.getCurrAngle());
                    hideFollowWeapon();
                }
            }
            return; // 重要：直接返回，不执行后续控制逻辑
        }

        // ========== 简化逻辑：护盾臂始终跟随SOURCE武器 ==========
        if (source != null) {
            // 计算武器相对于飞船的角度
            float shipFacing = weapon.getShip().getFacing();
            float sourceAngle = source.getCurrAngle() - shipFacing;

            // 规范化角度到[-180,180]范围
            while (sourceAngle > 180) sourceAngle -= 360;
            while (sourceAngle < -180) sourceAngle += 360;

            // 护盾臂跟随SOURCE武器
            float targetAngle = shipFacing + sourceAngle;
            weapon.setCurrAngle(targetAngle);
        }

        // 保底逻辑：如果护盾存活，让护盾跟随手臂（SOURCE）的角度
        if (shield != null && shield.isAlive() && source != null) {
            shield.setFacing(source.getCurrAngle());
        }
    }

    /**
     * 初始化FOLLOW武器
     * FOLLOW武器同步到装饰武器的位置
     */
    private void initializeFollowWeapon(WeaponAPI weapon) {
        if (source == null || sourceFollow == null) return;

        // FOLLOW武器同步到装饰武器的位置（与SOURCE武器保持一致）
        Vector2f diffFollow = new Vector2f();
        Vector2f.sub(weapon.getSlot().getLocation(), sourceFollow.getSlot().getLocation(), diffFollow);
        
        // 获取装饰武器的开火点作为FOLLOW武器的目标开火点
        Vector2f decorativeFirePoint = weapon.getSpec().getTurretFireOffsets().get(0);
        syncFirePoint(sourceFollow, diffFollow, decorativeFirePoint);
        
        // 初始隐藏FOLLOW武器
        hideFollowWeapon();
    }

    /**
     * 隐藏FOLLOW武器
     */
    private void hideFollowWeapon() {
        if (sourceFollow == null) return;
        
        // 透明颜色
        Color transparent = Moci_ColorData.NONE;
        
        // 设置所有贴图组件为透明，包括发光贴图
        if (sourceFollow.getSprite() != null) {
            sourceFollow.getSprite().setColor(transparent);
        }
        if (sourceFollow.getBarrelSpriteAPI() != null) {
            sourceFollow.getBarrelSpriteAPI().setColor(transparent);
        }
        if (sourceFollow.getUnderSpriteAPI() != null) {
            sourceFollow.getUnderSpriteAPI().setColor(transparent);
        }
        if (sourceFollow.getGlowSpriteAPI() != null) {
            sourceFollow.getGlowSpriteAPI().setColor(transparent);
        }

        // 隐藏导弹渲染数据
        if (sourceFollow.getMissileRenderData() != null) {
            for (MissileRenderDataAPI render : sourceFollow.getMissileRenderData()) {
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
                render.getSprite().setHeight(0);
            }
        }
    }
}
