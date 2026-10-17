package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import data.scripts.util.Moci_Util;

/**
 * 机甲投射装饰武器效果 - 充能动画版本
 * 功能与Moci_MechProjDecoWeaponEffect相同，但使用充能动画替代开火动画
 * 动画效果：根据武器ChargeLevel线性播放动画（无跳帧）
 * - ChargeLevel 0.0 → 第0帧
 * - ChargeLevel 1.0 → 最后一帧
 * - 中间值线性插值
 */
public class Moci_MechProjDecoWeaponEffect_Charge implements EveryFrameWeaponEffectPlugin {
    boolean init = false;
    WeaponAPI source;
    
    // 充能动画相关变量
    private int maxFrame = 0;                           // 最大帧（最后一帧）
    private static final int MIN_CHARGE_FRAME = 0;      // 最小充能时第0帧
    
    // 帧切换参数
    private int currentFrame = 0;                       // 当前帧（整数）
    private float frameTimer = 0f;                      // 帧切换计时器
    
    // 状态变量
    private boolean animationInitialized = false;
    private static final float DEFAULT_RECOIL_OFFSET_X = 0f;
    private static final float DEFAULT_RECOIL_OFFSET_Y = 2f;
    private final Moci_Util.SpriteRecoilState recoilState = new Moci_Util.SpriteRecoilState();

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null) {
            return;
        }
        
        // 舰船死亡时不处理
        if (weapon.getShip() == null || !weapon.getShip().isAlive()) {
            return;
        }
        
        // 即使暂停也要持续隐藏SOURCE武器
        if (source != null) {
            hideSourceWeapon();
        }
        
        // 暂停时不更新其他逻辑
        if (engine.isPaused()) {
            return;
        }
        
        //初始化
        if (!init) {
            init = true;
            Moci_Util.captureSpriteRecoilOrigin(weapon, recoilState);
            ShipAPI ship = weapon.getShip();
            String slotId = weapon.getSlot().getId();
            //通过武器槽添加后缀寻找关联武器
            String sourceId = slotId + "_SOURCE";

            for (WeaponAPI w : ship.getAllWeapons()) {
                if (sourceId.equals(w.getSlot().getId())) {
                    source = w;
                    break;
                }
            }
            //关联武器槽安装了武器的情况下
            if (source != null) {
                //计算装饰武器和真武器之间的坐标差
                Vector2f diff = new Vector2f();
                Vector2f.sub(weapon.getSlot().getLocation(), source.getSlot().getLocation(), diff);
                //取装饰武器的炮塔模式第一个开火点坐标
                Vector2f firePoint = weapon.getSpec().getTurretFireOffsets().get(0);
                syncFirePoint(source, diff, firePoint);
                // 隐藏SOURCE武器：战斗外用透明颜色，战斗中用尺寸为0
                hideSourceWeapon();
            }
        }

        // 角度同步控制
        if (source != null) {
            weapon.setCurrAngle(source.getCurrAngle());
            if (Moci_Util.shouldDisableRecoil(weapon, source)) {
                Moci_Util.resetSpriteRecoil(weapon, recoilState);
            } else {
                Moci_Util.applyCooldownSpriteRecoil(
                        weapon,
                        source,
                        recoilState,
                        DEFAULT_RECOIL_OFFSET_X,
                        DEFAULT_RECOIL_OFFSET_Y
                );
            }
        }
        else {
            Moci_Util.resetSpriteRecoil(weapon, recoilState);
        }
        
        // ========== 充能动画逻辑 ==========
        // 初始化动画参数：获取武器的总帧数
        if (!animationInitialized) {
            if (weapon.getAnimation() != null) {
                int numFrames = weapon.getAnimation().getNumFrames();
                
                // 如果只有1帧或没有帧，不需要播放动画
                if (numFrames <= 1) {
                    return;
                }
                
                maxFrame = numFrames - 1; // 最大帧为最后一帧
                currentFrame = MIN_CHARGE_FRAME; // 初始帧为第0帧
                animationInitialized = true;
            } else {
                // 如果没有动画，直接返回
                return;
            }
        }
        
        // 如果帧数无效，不播放
        if (maxFrame <= 0 || weapon.getAnimation() == null) {
            return;
        }
        
        // 暂停动画自动播放，由脚本手动控制
        weapon.getAnimation().pause();
        
        // 获取当前武器充能等级（0.0 - 1.0）
        // 如果有SOURCE武器，使用SOURCE的充能等级，否则使用装饰武器自己的
        float chargeLevel = (source != null) ? source.getChargeLevel() : weapon.getChargeLevel();
        
        // 计算目标帧
        int targetFrame = calculateTargetFrame(chargeLevel);
        
        // 逐帧过渡到目标帧
        transitionToFrame(targetFrame, amount, weapon);
        
        // 设置当前动画帧
        weapon.getAnimation().setFrame(currentFrame);
    }
    
    /**
     * 根据充能等级计算目标帧（完全线性插值，无跳帧）
     * @param chargeLevel 武器充能等级 (0.0 - 1.0)
     * @return 目标帧数（整数）
     */
    private int calculateTargetFrame(float chargeLevel) {
        // 完全线性插值计算帧数，无阈值跳帧
        // chargeLevel 0.0 -> MIN_CHARGE_FRAME (0)
        // chargeLevel 1.0 -> maxFrame (最后一帧)
        int frame = Math.round(MIN_CHARGE_FRAME + chargeLevel * (maxFrame - MIN_CHARGE_FRAME));
        // 确保帧数在有效范围内
        return Math.max(MIN_CHARGE_FRAME, Math.min(frame, maxFrame));
    }
    
    /**
     * 逐帧过渡到目标帧
     * @param targetFrame 目标帧数
     * @param amount 时间增量
     * @param weapon 武器对象
     */
    private void transitionToFrame(int targetFrame, float amount, WeaponAPI weapon) {
        // 如果已经在目标帧，直接返回
        if (currentFrame == targetFrame) {
            frameTimer = 0f;
            return;
        }
        
        // 累积帧切换计时器，使用武器自身的帧率
        frameTimer += amount * weapon.getAnimation().getFrameRate();
        
        // 每当计时器达到1，切换一帧
        while (frameTimer >= 1f) {
            frameTimer -= 1f;
            
            // 逐帧向目标帧移动
            if (currentFrame < targetFrame) {
                currentFrame++;
            } else if (currentFrame > targetFrame) {
                currentFrame--;
            }
            
            // 确保不超过目标帧
            if (currentFrame == targetFrame) {
                break;
            }
        }
        
        // 确保帧数在有效范围内
        currentFrame = Math.max(MIN_CHARGE_FRAME, Math.min(currentFrame, maxFrame));
    }

    /**
     * 隐藏SOURCE武器
     * 参考骤雨mod的方法：每帧都设置透明颜色，包括所有贴图组件
     */
    private void hideSourceWeapon() {
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
