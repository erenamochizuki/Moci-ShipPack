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
 * 机甲投射装饰武器效果 - 带循环动画版本
 * 功能与Moci_MechProjDecoWeaponEffect相同，但使用循环动画替代开火动画
 * 动画效果：从第0帧播放到最后一帧，然后反向播放回第0帧，与开火状态无关
 */
public class Moci_MechProjDecoWeaponEffect_Animation implements EveryFrameWeaponEffectPlugin {
    boolean init = false;
    WeaponAPI source;
    WeaponAPI sourceMAIN;
    WeaponAPI sourceFollow; // FOLLOW武器引用
    private static final float FOLLOW_RATIO = 0.33f; // 跟随比例参数
    
    // 循环动画相关变量
    private float currentFrame = 0f;
    private boolean playingForward = true;
    private boolean animationInitialized = false;
    private int numFrames = 0;
    private float frameRate = 30f;
    
    // 暂停控制变量
    private boolean isPaused = false; // 是否处于暂停状态
    private float pauseTimer = 0f; // 暂停计时器
    private float pauseDuration = 0f; // 暂停持续时间（一轮动画的时长）
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
            String mainId = slotId + "_MAIN"; // 主炮槽位ID
            String followId = slotId + "_FOLLOW"; // FOLLOW槽位ID

            for (WeaponAPI w : ship.getAllWeapons()) {
                if (sourceId.equals(w.getSlot().getId())) {
                    source = w;
                }
                // 如果_SOURCE槽位不存在，检查_MAIN槽位
                if (source == null && mainId.equals(w.getSlot().getId())) {
                    sourceMAIN = w;
                }
                // 查找FOLLOW武器
                if (followId.equals(w.getSlot().getId())) {
                    sourceFollow = w;
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

            // 如果找到了SOURCE和FOLLOW武器，初始化FOLLOW武器
            if (source != null && sourceFollow != null) {
                initializeFollowWeapon(weapon);
            }
        }

        // 角度同步控制
        if (source != null) {
            weapon.setCurrAngle(source.getCurrAngle());

            // 同步FOLLOW武器角度并持续隐藏
            if (sourceFollow != null) {
                sourceFollow.setCurrAngle(source.getCurrAngle());
                hideFollowWeapon();
            }

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
        
        // ========== 循环动画逻辑 ==========
        // 初始化动画参数：获取武器的总帧数和帧率
        if (!animationInitialized) {
            if (weapon.getAnimation() != null) {
                numFrames = weapon.getAnimation().getNumFrames();
                frameRate = weapon.getAnimation().getFrameRate();
                
                // 如果帧率为0或负数，使用默认值
                if (frameRate <= 0) {
                    frameRate = 30f;
                }
                
                // 如果只有1帧或没有帧，不需要播放动画
                if (numFrames <= 1) {
                    return;
                }
                
                // 计算一轮动画的持续时间（正向+反向）
                pauseDuration = (numFrames - 1) * 2f / frameRate;
                
                animationInitialized = true;
            } else {
                // 如果没有动画，直接返回
                return;
            }
        }
        
        // 如果帧数无效，不播放
        if (numFrames <= 1 || weapon.getAnimation() == null) {
            return;
        }
        
        // 处理暂停状态
        if (isPaused) {
            pauseTimer += amount;
            
            // 暂停时间到，恢复播放
            if (pauseTimer >= pauseDuration) {
                isPaused = false;
                pauseTimer = 0f;
            }
            
            // 暂停期间保持在第0帧
            weapon.getAnimation().setFrame(0);
            return;
        }
        
        // 计算帧增量（根据帧率和时间增量）
        float frameDelta = frameRate * amount;
        
        // 更新当前帧
        if (playingForward) {
            // 正向播放
            currentFrame += frameDelta;
            
            // 如果到达最后一帧，切换为反向播放
            if (currentFrame >= numFrames - 1) {
                currentFrame = numFrames - 1;
                playingForward = false;
            }
        } else {
            // 反向播放
            currentFrame -= frameDelta;
            
            // 如果回到第0帧，进入暂停状态
            if (currentFrame <= 0) {
                currentFrame = 0;
                playingForward = true;
                isPaused = true; // 进入暂停状态
                pauseTimer = 0f; // 重置暂停计时器
            }
        }
        
        // 设置武器动画帧
        weapon.getAnimation().setFrame((int) currentFrame);
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
     * 隐藏FOLLOW武器
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
