package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import data.scripts.util.Moci_Util;
import data.scripts.weapons.Moci_MechDecoWeaponMutexControl;

public class Moci_MechProjDecoWeaponEffect implements EveryFrameWeaponEffectPlugin {
    boolean init = false;
    WeaponAPI source;
    WeaponAPI sourceMAIN;
    WeaponAPI sourceFollow; // 新增：FOLLOW武器引用
    private static final float FOLLOW_RATIO = 0.33f; // 新增跟随比例参数
    protected static final float DEFAULT_RECOIL_OFFSET_X = 0f;
    protected static final float DEFAULT_RECOIL_OFFSET_Y = 2f;
    boolean wasFiring = false;
    protected boolean isAnimating = false;
    protected final Moci_Util.SpriteRecoilState recoilState = new Moci_Util.SpriteRecoilState();

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        //初始化
        if (!init) {
            //    Global.getLogger(this.getClass()).info("init");
            init = true;
            Moci_Util.captureSpriteRecoilOrigin(weapon, recoilState);
            ShipAPI ship = weapon.getShip();
            String slotId = weapon.getSlot().getId();
            //通过武器槽添加后缀寻找关联武器
            String sourceId = slotId + "_SOURCE";
            String mainId = slotId + "_MAIN"; // 新增主炮槽位ID
            String followId = slotId + "_FOLLOW"; // 新增：FOLLOW槽位ID

            for (WeaponAPI w : ship.getAllWeapons()) {
                if (sourceId.equals(w.getSlot().getId())) {
                    source = w;
//                    break;
                }
                // 如果_SOURCE槽位不存在，检查_MAIN槽位
                if (source == null && mainId.equals(w.getSlot().getId())) {
                    sourceMAIN = w;
//                    break;
                }
                // 新增：查找FOLLOW武器
                if (followId.equals(w.getSlot().getId())) {
                    sourceFollow = w;
                }
            }
            //关联武器槽安装了武器的情况下
            if (source != null) {
                //计算装饰武器和真武器之间的坐标差
                Vector2f diff = new Vector2f();
                //     Global.getLogger(Moci_MechProjDecoWeaponEffect.class).info("get diff " +weapon.getSlot().getLocation() +" source"+source.getId()+source.getSlot().getLocation());
                Vector2f.sub(weapon.getSlot().getLocation(),source.getSlot().getLocation(), diff);
                //取装饰武器的炮塔模式第一个开火点坐标
                Vector2f firePoint = weapon.getSpec().getTurretFireOffsets().get(0);
                syncFirePoint(source, diff, firePoint);
                // 隐藏SOURCE武器：战斗外用透明颜色，战斗中用尺寸为0
                hideSourceWeapon();
            }

            // 新增：如果找到了SOURCE和FOLLOW武器，初始化FOLLOW武器
            if (source != null && sourceFollow != null) {
                initializeFollowWeapon(weapon);
            }
        }

        // ========== 互斥控制：检查光剑是否正在挥动 ==========
        if (Moci_MechDecoWeaponMutexControl.isBladeSwingActive(weapon)) {
            if (source != null) {
                Moci_MechDecoWeaponMutexControl.syncAngleOnly(weapon, source);
                hideSourceWeapon();
                if (sourceFollow != null) {
                    sourceFollow.setCurrAngle(source.getCurrAngle());
                    hideFollowWeapon();
                }
            }
            Moci_Util.resetSpriteRecoil(weapon, recoilState);
            return; // 重要：直接返回，不执行后续控制逻辑
        }

        // 新的动画同步控制
        if (source != null) {
            weapon.setCurrAngle(source.getCurrAngle());
            
            // 持续隐藏SOURCE武器
            hideSourceWeapon();

            // 新增：同步FOLLOW武器角度并持续隐藏
            if (sourceFollow != null) {
                sourceFollow.setCurrAngle(source.getCurrAngle());
                hideFollowWeapon();
            }

            if (weapon.getAnimation() != null) {
                boolean isFiring = source.isFiring() &&
                        (source.getCooldownRemaining() == 0 ||
                                (source.getSpec().getBurstSize() > 1 && source.isInBurst()));

                if (isFiring) {
                    // 重置动画到第一帧
                    if (!wasFiring) {
                        weapon.getAnimation().setFrame(0);
                    }
                    weapon.getAnimation().play();
                    wasFiring = true;
                    isAnimating = true;
                } else if (wasFiring) {
                    // 检查是否到达最后一帧
                    if (weapon.getAnimation().getFrame() >= weapon.getAnimation().getNumFrames() - 1) {
                        weapon.getAnimation().pause();
                        weapon.getAnimation().setFrame(0);  // 播放完成后回到第0帧
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
            applyDecorativeRecoil(weapon);
// 旧的动画同步控制
//        if (source != null) {
//            weapon.setCurrAngle(source.getCurrAngle());
//            //基础的开火与动画联动
//            if (weapon.getAnimation() != null) {
//                if (source.isFiring() && (source.getCooldownRemaining() == 0 || (source.getSpec().getBurstSize() > 1 && source.isInBurst()))) {
//                    weapon.getAnimation().play();
//                } else {
//                    weapon.getAnimation().pause();
//                }
//            }

//            if (source.getMissileRenderData() != null) {
//                for (MissileRenderDataAPI render : source.getMissileRenderData()) {
//             //       render.getSprite().setWidth(0);
//               //     render.getSprite().setHeight(0);
//                         Global.getLogger(Moci_MechShoulderDecoWeaponEffect.class).info("output  " +render.getMissileCenterLocation());
//                //    render.getMissileCenterLocation().set(0,0);
//                }
//            }

        }
        else {
            Moci_Util.resetSpriteRecoil(weapon, recoilState);
        }

        if (sourceMAIN != null) {
            // 计算武器相对于飞船的角度
            float shipFacing = weapon.getShip().getFacing();
            float sourceAngle = sourceMAIN.getCurrAngle() - shipFacing;

            // 规范化角度到[-180,180]范围
            while (sourceAngle > 180) sourceAngle -= 360;
            while (sourceAngle < -180) sourceAngle += 360;

            // 应用跟随比例并加回飞船朝向
            float targetAngle = shipFacing + sourceAngle * FOLLOW_RATIO;
            weapon.setCurrAngle(targetAngle);
        }

    }

    protected boolean shouldApplyRecoil(WeaponAPI weapon) {
        return !Moci_Util.shouldDisableRecoil(weapon, source);
    }

    protected float getRecoilOffsetX() {
        return DEFAULT_RECOIL_OFFSET_X;
    }

    protected float getRecoilOffsetY() {
        return DEFAULT_RECOIL_OFFSET_Y;
    }

    protected void applyDecorativeRecoil(WeaponAPI weapon) {
        if (!shouldApplyRecoil(weapon) || source == null) {
            Moci_Util.resetSpriteRecoil(weapon, recoilState);
            return;
        }

        Moci_Util.applyCooldownSpriteRecoil(
                weapon,
                source,
                recoilState,
                getRecoilOffsetX(),
                getRecoilOffsetY()
        );
    }

    /**
     * 新增：初始化FOLLOW武器
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
     * 隐藏SOURCE武器
     * 参考骤雨mod的方法：每帧都设置透明颜色，包括所有贴图组件
     */
    protected void hideSourceWeapon() {
        if (source == null) return;
        
        // 透明颜色，参考骤雨mod的实现
        Color transparent = new Color(0f, 0f, 0f, 0f);
        
        // 设置所有贴图组件为透明，包括发光贴图
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
     * 新增：隐藏FOLLOW武器
     */
    private void hideFollowWeapon() {
        if (sourceFollow == null) return;
        
        // 透明颜色，参考骤雨mod的实现
        Color transparent = new Color(0f, 0f, 0f, 0f);
        
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
   //     Global.getLogger(Moci_MechProjDecoWeaponEffect.class).info(sync.getId()+" change weapon firePoints to" +override);
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
         //       render.getSprite().setHeight(0);
                //     Global.getLogger(Moci_MechShoulderDecoWeaponEffect.class).info("output  " +render.getMissileCenterLocation());
        //        render.getMissileCenterLocation().set(0,0);
            }
        }
    }
}
