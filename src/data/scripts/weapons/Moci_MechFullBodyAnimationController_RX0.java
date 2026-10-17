package data.scripts.weapons;

import java.util.List;

import org.apache.log4j.Logger;
import org.magiclib.subsystems.MagicSubsystem;
import org.magiclib.subsystems.MagicSubsystemsManager;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import data.subsystems.Moci_NTDSubsystem;

/**
 * 全身动画控制器 - RX-0 觉醒版本
 *
 * 控制机体的腿部动画，配合NTD子系统实现觉醒变形
 *
 * 动画帧说明：
 * - 腿部：0-15共16帧
 *   - 0-7帧：未觉醒形态的移动动画
 *   - 8-15帧：觉醒形态的移动动画
 *
 * - 其他部位（ARM_L/R、BODY、HEAD、SHOULDER_L/R、BACKPACK）：0-7共8帧
 *   - 0-7帧：觉醒变形动画（由NTD子系统控制）
 *
 * 动画逻辑：
 * 1. 前进/后退命令效果倒置（后退触发前进动画，前进触发后退动画）
 * 2. NTD子系统激活时：
 *    - 其他部位在effectLevel 0.0-0.8播放变形动画（0-7帧）
 *    - 腿部在其他部位完成变形后切换到8-15帧组
 * 3. NTD子系统关闭时：
 *    - 其他部位在effectLevel 1.0-0.2播放还原动画（7-0帧）
 *    - 腿部在其他部位完成还原后切换回0-7帧组
 */
public class Moci_MechFullBodyAnimationController_RX0 implements EveryFrameWeaponEffectPlugin {
    private static final Logger log = Global.getLogger(Moci_MechFullBodyAnimationController_RX0.class);

    private static final String FRONT_SEQUENCE = "FRONT";
    private static final String BACK_SEQUENCE = "BACK";
    private static final int NORMAL_MAX_FRAME_TARGET = 7;
    private static final int NORMAL_DEFAULT_FRAME_TARGET = 4;
    private static final int AWAKENED_MIN_FRAME_TARGET = 8;
    private static final int AWAKENED_MAX_FRAME_TARGET = 15;
    private static final int AWAKENED_DEFAULT_FRAME_TARGET = 12;

    private boolean subsystemInitialized = false;
    private boolean legAnimationInitialized = false;

    private int configuredFrameCount = -1;

    // 未觉醒形态（0-7帧）
    private int normalMaxFrame = NORMAL_MAX_FRAME_TARGET;
    private int normalDefaultFrame = NORMAL_DEFAULT_FRAME_TARGET;

    // 觉醒形态（8-15帧）
    private int awakenedMinFrame = AWAKENED_MIN_FRAME_TARGET;
    private int awakenedMaxFrame = AWAKENED_MAX_FRAME_TARGET;
    private int awakenedDefaultFrame = AWAKENED_DEFAULT_FRAME_TARGET;

    private String currentSequence = null;
    private int currentFrame = NORMAL_DEFAULT_FRAME_TARGET;
    private float timer = 0f;

    // NTD子系统相关
    private Moci_NTDSubsystem ntdSubsystem = null;
    private boolean wasAwakened = false;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null || weapon.getAnimation() == null) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null) {
            return;
        }

        syncLegAnimationState(weapon);

        // 腿部帧由脚本手动控制，不能和内置动画自动播放并用。
        weapon.getAnimation().pause();

        if (!ship.isAlive() || ship.isHulk()) {
            resetLegAnimation(weapon, wasAwakened);
            return;
        }

        if (!subsystemInitialized) {
            initializeSubsystem(ship);
            subsystemInitialized = true;
        }

        boolean isAwakened = ntdSubsystem != null && ntdSubsystem.isAwakened();
        if (isAwakened != wasAwakened) {
            remapFrameForAwakenState(isAwakened);
            currentSequence = null;
            timer = 0f;
            wasAwakened = isAwakened;
        }

        int currentDefaultFrame = getDefaultFrame(isAwakened);
        boolean isAccelerating = ship.getEngineController().isAccelerating();
        boolean isDecelerating = ship.getEngineController().isDecelerating();
        boolean isBackwards = ship.getEngineController().isAcceleratingBackwards();
        String desiredSequence = getDesiredSequence(isAccelerating, isDecelerating, isBackwards);

        if (desiredSequence == null) {
            resetLegAnimation(weapon, isAwakened);
            return;
        }

        if (!desiredSequence.equals(currentSequence)) {
            currentSequence = desiredSequence;
            timer = 0f;
            currentFrame = currentDefaultFrame;
        }

        currentFrame = clampFrameForState(currentFrame, isAwakened);

        float frameRate = Math.max(0f, weapon.getAnimation().getFrameRate());
        timer += amount * frameRate;
        while (timer >= 1f) {
            timer -= 1f;
            stepCurrentFrame(isAwakened, currentDefaultFrame, isAccelerating, isDecelerating, isBackwards);
        }

        if (shouldResetSequence(currentDefaultFrame, isAccelerating, isDecelerating, isBackwards)) {
            currentSequence = null;
            timer = 0f;
            currentFrame = currentDefaultFrame;
        }

        setSafeFrame(weapon, currentFrame, isAwakened);
    }

    private void initializeSubsystem(ShipAPI ship) {
        List<MagicSubsystem> subsystems = MagicSubsystemsManager.getSubsystemsForShipCopy(ship);
        if (subsystems != null) {
            for (MagicSubsystem subsystem : subsystems) {
                if (subsystem instanceof Moci_NTDSubsystem) {
                    ntdSubsystem = (Moci_NTDSubsystem) subsystem;
                    break;
                }
            }
        }

        if (ntdSubsystem == null) {
            log.info("RX-0动画控制器：未找到NTD子系统，将使用正常形态");
        } else {
            log.info("RX-0动画控制器：成功找到NTD子系统");
        }
    }

    private void syncLegAnimationState(WeaponAPI weapon) {
        int totalFrames = weapon.getAnimation().getNumFrames();
        if (totalFrames == configuredFrameCount) {
            return;
        }

        configuredFrameCount = totalFrames;
        int maxAvailableFrame = Math.max(0, totalFrames - 1);

        normalMaxFrame = Math.min(NORMAL_MAX_FRAME_TARGET, maxAvailableFrame);
        normalDefaultFrame = Math.min(NORMAL_DEFAULT_FRAME_TARGET, normalMaxFrame);

        awakenedMaxFrame = totalFrames < 16 ? maxAvailableFrame : AWAKENED_MAX_FRAME_TARGET;
        awakenedDefaultFrame = Math.min(AWAKENED_DEFAULT_FRAME_TARGET, awakenedMaxFrame);
        awakenedMinFrame = Math.min(AWAKENED_MIN_FRAME_TARGET, awakenedDefaultFrame);

        if (totalFrames < 16) {
            log.warn("RX-0动画控制器：腿部动画帧数不足16帧，当前为" + totalFrames + "帧，将使用第" + awakenedMaxFrame + "帧作为最后一帧");
        }

        if (!legAnimationInitialized) {
            currentFrame = normalDefaultFrame;
            legAnimationInitialized = true;
            return;
        }

        currentFrame = clampFrameForState(currentFrame, wasAwakened);
    }

    private void remapFrameForAwakenState(boolean isAwakened) {
        if (isAwakened) {
            int relativeFrame = clampFrameForState(currentFrame, false);
            currentFrame = awakenedMinFrame + relativeFrame;
        } else {
            int relativeFrame = clampFrameForState(currentFrame, true) - awakenedMinFrame;
            currentFrame = Math.max(0, relativeFrame);
        }
        currentFrame = clampFrameForState(currentFrame, isAwakened);
    }

    private String getDesiredSequence(boolean isAccelerating, boolean isDecelerating, boolean isBackwards) {
        // 注意：前后命令效果倒置
        if (isDecelerating || isBackwards) {
            return FRONT_SEQUENCE;
        }
        if (isAccelerating) {
            return BACK_SEQUENCE;
        }
        return null;
    }

    private void stepCurrentFrame(
            boolean isAwakened,
            int currentDefaultFrame,
            boolean isAccelerating,
            boolean isDecelerating,
            boolean isBackwards) {
        if (FRONT_SEQUENCE.equals(currentSequence)) {
            // 后退命令触发：从默认帧向最小帧播放
            if (isDecelerating || isBackwards) {
                currentFrame--;
            } else if (currentFrame < currentDefaultFrame) {
                currentFrame++;
            }
        } else if (BACK_SEQUENCE.equals(currentSequence)) {
            // 前进命令触发：从默认帧向最大帧播放
            if (isAccelerating) {
                currentFrame++;
            } else if (currentFrame > currentDefaultFrame) {
                currentFrame--;
            }
        }

        currentFrame = clampFrameForState(currentFrame, isAwakened);
    }

    private boolean shouldResetSequence(
            int currentDefaultFrame,
            boolean isAccelerating,
            boolean isDecelerating,
            boolean isBackwards) {
        if (FRONT_SEQUENCE.equals(currentSequence)) {
            return currentFrame == currentDefaultFrame && !isDecelerating && !isBackwards;
        }
        if (BACK_SEQUENCE.equals(currentSequence)) {
            return currentFrame == currentDefaultFrame && !isAccelerating;
        }
        return true;
    }

    private void resetLegAnimation(WeaponAPI weapon, boolean isAwakened) {
        currentSequence = null;
        timer = 0f;
        currentFrame = getDefaultFrame(isAwakened);
        setSafeFrame(weapon, currentFrame, isAwakened);
    }

    private int getMinFrame(boolean isAwakened) {
        return isAwakened ? awakenedMinFrame : 0;
    }

    private int getMaxFrame(boolean isAwakened) {
        return isAwakened ? awakenedMaxFrame : normalMaxFrame;
    }

    private int getDefaultFrame(boolean isAwakened) {
        return isAwakened ? awakenedDefaultFrame : normalDefaultFrame;
    }

    private int clampFrameForState(int frame, boolean isAwakened) {
        int minFrame = getMinFrame(isAwakened);
        int maxFrame = getMaxFrame(isAwakened);
        return Math.max(minFrame, Math.min(frame, maxFrame));
    }

    private void setSafeFrame(WeaponAPI weapon, int frame, boolean isAwakened) {
        if (weapon == null || weapon.getAnimation() == null) {
            return;
        }
        weapon.getAnimation().setFrame(clampFrameForState(frame, isAwakened));
    }
}
