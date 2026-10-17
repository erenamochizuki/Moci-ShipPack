package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;

import data.scripts.util.Moci_Util;

public class Moci_MechProjDecoWeaponEffect_GM implements EveryFrameWeaponEffectPlugin {
    boolean init = false;
    WeaponAPI source;
    WeaponAPI sourceMAIN;
    WeaponAPI typeWeapon;
    WeaponAPI sourceFollow; // 新增：FOLLOW武器引用
    private static final float FOLLOW_RATIO = 0.33f; // 新增跟随比例参数
    boolean wasFiring = false;
    private boolean isAnimating = false;
    private String slotId; // 添加类级别变量
    private ShipAPI ship; // 添加类级别变量
    private static final float ARM_RECOIL_OFFSET_Y = 2f;
    private static final float TYPE_RECOIL_OFFSET_Y = 6f;
    private final Moci_Util.SpriteRecoilState armRecoilState = new Moci_Util.SpriteRecoilState();
    private final Moci_Util.SpriteRecoilState typeRecoilState = new Moci_Util.SpriteRecoilState();

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        //初始化
        if (!init) {
            //    Global.getLogger(this.getClass()).info("init");
            init = true;
            ship = weapon.getShip(); // 初始化ship变量
            slotId = weapon.getSlot().getId(); // 初始化slotId变量
            Moci_Util.captureSpriteRecoilOrigin(weapon, armRecoilState);
            //通过武器槽添加后缀寻找关联武器
            String sourceId = slotId + "_SOURCE";
            String mainId = slotId + "_MAIN"; // 新增主炮槽位ID
            String typeId = slotId + "_TYPE"; // 新增_TYPE槽位ID
            String followId = slotId + "_FOLLOW"; // 新增：FOLLOW槽位ID

            for (WeaponAPI w : ship.getAllWeapons()) {
                if (sourceId.equals(w.getSlot().getId())) {
                    source = w;
                }
                // 如果_SOURCE槽位不存在，检查_MAIN槽位
                if (source == null && mainId.equals(w.getSlot().getId())) {
                    sourceMAIN = w;
                }
                // 检查_TYPE槽位
                if (typeId.equals(w.getSlot().getId())) {
                    typeWeapon = w;
                }
                // 新增：查找FOLLOW武器
                if (followId.equals(w.getSlot().getId())) {
                    sourceFollow = w;
                }
            }
            Moci_Util.captureSpriteRecoilOrigin(typeWeapon, typeRecoilState);
            //关联武器槽安装了武器的情况下
            if (source != null && typeWeapon != null) {
                //计算TYPE武器和SOURCE武器之间的坐标差
                Vector2f diff = new Vector2f();
                //     Global.getLogger(Moci_MechProjDecoWeaponEffect.class).info("get diff " +typeWeapon.getSlot().getLocation() +" source"+source.getId()+source.getSlot().getLocation());
                Vector2f.sub(typeWeapon.getSlot().getLocation(), source.getSlot().getLocation(), diff);
                //取TYPE武器的炮塔模式第一个开火点坐标
                Vector2f firePoint = typeWeapon.getSpec().getTurretFireOffsets().get(0);
                syncFirePoint(source, diff, firePoint);
                // 初始隐藏SOURCE武器
                hideSourceWeapon();
            }

            // 新增：如果找到了TYPE和FOLLOW武器，初始化FOLLOW武器
            if (typeWeapon != null && sourceFollow != null) {
                initializeFollowWeapon(typeWeapon);
            }
        }

        // 根据_SOURCE武器类型切换动画帧
        if (source != null && typeWeapon != null && typeWeapon.getAnimation() != null) {
            int maxFrames = typeWeapon.getAnimation().getNumFrames();
            
            // 根据_SOURCE武器类型设置_TYPE武器的帧
            if (source.getType() == WeaponType.MISSILE) {
                // 如果最大帧数大于1，设置到第1帧
                if (maxFrames > 1) {
                    typeWeapon.getAnimation().setFrame(1);
                } else {
                    typeWeapon.getAnimation().setFrame(0);
                }
            } else {
                // 其他情况设置到第0帧
                typeWeapon.getAnimation().setFrame(0);
            }
        }

        // ========== 互斥控制：检查光剑是否正在挥动 ==========
        if (Moci_MechDecoWeaponMutexControl.isBladeSwingActive(weapon)) {
            if (source != null) {
                Moci_MechDecoWeaponMutexControl.syncAngleOnly(weapon, source);
                if (typeWeapon != null) {
                    typeWeapon.setCurrAngle(weapon.getCurrAngle());
                }
                if (sourceFollow != null) {
                    sourceFollow.setCurrAngle(source.getCurrAngle());
                    hideFollowWeapon();
                }
                hideSourceWeapon();
            }
            Moci_Util.resetSpriteRecoil(weapon, armRecoilState);
            Moci_Util.resetSpriteRecoil(typeWeapon, typeRecoilState);
            return; // 重要：直接返回，不执行后续控制逻辑
        }

        // 新的动画同步控制
        if (source != null) {
            weapon.setCurrAngle(source.getCurrAngle());
            
            // 如果存在_TYPE槽位武器，同步其角度
            if (typeWeapon != null) {
                typeWeapon.setCurrAngle(weapon.getCurrAngle());
            }

            // 新增：同步FOLLOW武器角度并持续隐藏
            if (sourceFollow != null) {
                sourceFollow.setCurrAngle(source.getCurrAngle());
                hideFollowWeapon();
            }
            
            // 持续隐藏SOURCE武器
            hideSourceWeapon();
            if (Moci_Util.shouldDisableRecoil(weapon, source, typeWeapon)) {
                Moci_Util.resetSpriteRecoil(weapon, armRecoilState);
                Moci_Util.resetSpriteRecoil(typeWeapon, typeRecoilState);
            } else {
                Moci_Util.applyCooldownSpriteRecoil(weapon, source, armRecoilState, 0f, ARM_RECOIL_OFFSET_Y);
                Moci_Util.applyCooldownSpriteRecoil(typeWeapon, source, typeRecoilState, 0f, TYPE_RECOIL_OFFSET_Y);
            }

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
        else {
            Moci_Util.resetSpriteRecoil(weapon, armRecoilState);
            Moci_Util.resetSpriteRecoil(typeWeapon, typeRecoilState);
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

    /**
     * 新增：初始化FOLLOW武器
     * FOLLOW武器同步到TYPE武器的位置
     */
    private void initializeFollowWeapon(WeaponAPI typeWeapon) {
        if (typeWeapon == null || sourceFollow == null) return;

        // FOLLOW武器同步到TYPE武器的位置
        Vector2f diffFollow = new Vector2f();
        Vector2f.sub(typeWeapon.getSlot().getLocation(), sourceFollow.getSlot().getLocation(), diffFollow);
        
        // 获取TYPE武器的开火点作为FOLLOW武器的目标开火点
        Vector2f typeFirePoint = typeWeapon.getSpec().getTurretFireOffsets().get(0);
        syncFirePoint(sourceFollow, diffFollow, typeFirePoint);
        
        // 初始隐藏FOLLOW武器
        hideFollowWeapon();
    }

    /**
     * 新增：隐藏SOURCE武器
     */
    private void hideSourceWeapon() {
        if (source == null) return;
        
        Color transparent = new Color(0f, 0f, 0f, 0f);
        if(source.getSprite()!=null){
            source.getSprite().setColor(transparent);
        }
        if(source.getBarrelSpriteAPI()!=null){
            source.getBarrelSpriteAPI().setColor(transparent);
        }
        if(source.getUnderSpriteAPI()!=null){
            source.getUnderSpriteAPI().setColor(transparent);
        }
        if(source.getGlowSpriteAPI()!=null){
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
