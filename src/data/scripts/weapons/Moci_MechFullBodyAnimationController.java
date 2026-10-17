package data.scripts.weapons;

import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 全身动画控制器
 * 
 * 控制机体的腿部、躯干、头部、翅膀和肩膀的联动动画
 * 
 * 动画映射规则：
 * - 躯干（BODY）：4帧动画（0-3）
 * - 头部（HEAD）：4帧动画（0-3）
 * - 腿部（本武器）：8帧动画（0-7）
 * - 翅膀（WING_L、WING_R、WINGS）：8帧动画（0-7）
 * - 肩膀（SHOULDER_L、SHOULDER_R）：8帧动画（0-7）
 * 
 * 映射关系：
 * - 躯干/头部帧0 -> 腿/翅膀/肩膀帧0-1
 * - 躯干/头部帧1 -> 腿/翅膀/肩膀帧2-3
 * - 躯干/头部帧2 -> 腿/翅膀/肩膀帧4-5
 * - 躯干/头部帧3 -> 腿/翅膀/肩膀帧6-7
 */
public class Moci_MechFullBodyAnimationController implements EveryFrameWeaponEffectPlugin {
    private static final Logger log = Global.getLogger(Moci_MechFullBodyAnimationController.class);
    
    private static final Map<String, int[]> SEQUENCE = new HashMap<>();
    private boolean initialized = false;

    private static int maxFrames = 7; // 腿部最大帧数（0-7共8帧）
    private static int defaultFrame = 4; // 默认帧为第4帧
    
    static {
        SEQUENCE.put("FRONT", new int[]{0, 4}); // 前进时从第4帧播放到第0帧
        SEQUENCE.put("BACK", new int[]{4, 7}); // 后退时从第4帧播放到第7帧
    }

    String currentSequence = null;
    int current = 4; // 默认从第4帧开始
    float timer = 0;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon.getAnimation() == null) {
            return;
        }
        
        ShipAPI ship = weapon.getShip();
        if (!ship.isAlive() || ship.isHulk()) {
            weapon.getAnimation().pause();
            return;
        }

        // 初始化动画序列（第一次运行时）
        if (!initialized) {
            if (weapon.getAnimation() != null) {
                maxFrames = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
                defaultFrame = Math.min(4, maxFrames);
            }
            SEQUENCE.put("FRONT", new int[]{0, defaultFrame});
            SEQUENCE.put("BACK", new int[]{defaultFrame, maxFrames});
            initialized = true;
        }

        // 暂停逻辑
        if (currentSequence == null) {
            weapon.getAnimation().pause();
        }

        if (currentSequence != null) {
            int[] sq = SEQUENCE.get(currentSequence);
            
            // 检测引擎状态
            boolean isAccelerating = ship.getEngineController().isAccelerating();
            boolean isDecelerating = ship.getEngineController().isDecelerating();
            boolean isBackwards = ship.getEngineController().isAcceleratingBackwards();

            // 更新帧
            timer += amount * weapon.getAnimation().getFrameRate();
            while (timer >= 1) {
                timer -= 1;
                if ("FRONT".equals(currentSequence)) {
                    if (isAccelerating) {
                        current = Math.max(current - 1, sq[0]);
                    } else {
                        if (current < defaultFrame) {
                            current = Math.min(current + 1, defaultFrame);
                        }
                    }
                } else if ("BACK".equals(currentSequence)) {
                    if (isDecelerating || isBackwards) {
                        current = Math.min(current + 1, sq[1]);
                    } else {
                        if (current > defaultFrame) {
                            current = Math.max(current - 1, defaultFrame);
                        }
                    }
                }
            }

            // 重置条件
            boolean shouldReset = false;
            if ("FRONT".equals(currentSequence)) {
                shouldReset = (current == defaultFrame && !isAccelerating);
            } else if ("BACK".equals(currentSequence)) {
                shouldReset = (current == defaultFrame && !isDecelerating && !isBackwards);
            }
            
            if (shouldReset) {
                currentSequence = null;
                timer = 0;
            }
        } else {
            // 检测前进和后退
            boolean isAccelerating = ship.getEngineController().isAccelerating();
            boolean isDecelerating = ship.getEngineController().isDecelerating();
            boolean isBackwards = ship.getEngineController().isAcceleratingBackwards();
            
            if (isAccelerating) {
                currentSequence = "FRONT";
            }
            if (isDecelerating || isBackwards) {
                currentSequence = "BACK";
            }
            
            if (currentSequence != null) {
                timer = 0;
                weapon.getAnimation().play();
            }
        }
        
        // 如果没有动画序列，确保在默认帧
        if (currentSequence == null) {
            current = defaultFrame;
        }
        
        // 设置腿部动画帧
        current = clampFrame(weapon, current);
        setSafeFrame(weapon, current);
        
        // 同步其他部位的动画
        syncOtherParts(ship, current);
    }
    
    /**
     * 同步其他部位的动画
     * 
     * @param ship 飞船对象
     * @param legFrame 腿部当前帧（0-7）
     */
    private void syncOtherParts(ShipAPI ship, int legFrame) {
        // 计算躯干帧（0-3）
        int bodyFrame = legFrame / 2;
        
        // 更新躯干动画
        updateWeaponAnimation(ship, "BODY", bodyFrame);
        
        // 更新头部动画（使用躯干相同的帧）
        updateWeaponAnimation(ship, "HEAD", bodyFrame);
        
        // 更新翅膀动画（使用腿部相同的帧）
        updateWeaponAnimation(ship, "WING_L", legFrame);
        updateWeaponAnimation(ship, "WING_R", legFrame);
        updateWeaponAnimation(ship, "WINGS", legFrame);
        
        // 更新肩膀动画（使用腿部相同的帧）
        updateWeaponAnimation(ship, "SHOULDER_L", legFrame);
        updateWeaponAnimation(ship, "SHOULDER_R", legFrame);
    }
    
    /**
     * 更新指定槽位武器的动画帧
     * 
     * @param ship 飞船对象
     * @param slotId 槽位ID
     * @param frame 目标帧
     */
    private void updateWeaponAnimation(ShipAPI ship, String slotId, int frame) {
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w.getSlot() != null && slotId.equals(w.getSlot().getId())) {
                setSafeFrame(w, frame);
                break;
            }
        }
    }

    private int clampFrame(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return Math.max(0, frame);
        }
        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        return Math.max(0, Math.min(frame, maxFrame));
    }

    private void setSafeFrame(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return;
        }
        weapon.getAnimation().setFrame(clampFrame(weapon, frame));
    }
}
