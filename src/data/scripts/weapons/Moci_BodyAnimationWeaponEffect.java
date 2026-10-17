package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 武器动画播放效果
 * 实现从第0帧播放到最后一帧，然后反向播放回第0帧的循环动画
 * 播放速率使用武器配置的frameRate
 */
public class Moci_BodyAnimationWeaponEffect implements EveryFrameWeaponEffectPlugin {
    
    // 当前帧索引
    private float currentFrame = 0f;
    
    // 播放方向：true为正向，false为反向
    private boolean playingForward = true;
    
    // 是否已初始化
    private boolean initialized = false;
    
    // 武器总帧数
    private int numFrames = 0;
    
    // 帧率（每秒播放多少帧）
    private float frameRate = 30f;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || weapon == null) {
            return;
        }
        
        // 暂停时不更新
        if (engine.isPaused()) {
            return;
        }
        
        // 舰船死亡时不播放
        if (weapon.getShip() == null || !weapon.getShip().isAlive() || weapon.getShip().isHulk()) {
            return;
        }
        
        // 初始化：获取武器的总帧数和帧率
        if (!initialized) {
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
                
                initialized = true;
            } else {
                // 如果没有动画，直接返回
                return;
            }
        }
        
        // 如果帧数无效，不播放
        if (numFrames <= 1) {
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
            
            // 如果回到第0帧，切换为正向播放
            if (currentFrame <= 0) {
                currentFrame = 0;
                playingForward = true;
            }
        }
        
        // 设置武器动画帧
        weapon.getAnimation().setFrame((int) currentFrame);
    }
}
