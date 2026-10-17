package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;

public class Moci_Hiryu_missileAnimEffect implements EveryFrameWeaponEffectPlugin {
    private static final int CHARGE_UP_END_FRAME = 11;
    private static final int CHARGE_DOWN_START_FRAME = 12;

    // 当前动画帧
    int current = 0;
    // 动画帧计时器，用于累积时间以驱动序列动画
    float timer = 0;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 基础检查：确保游戏和武器动画存在
        if (engine == null || weapon.getAnimation() == null) {
            return;
        }

        // 默认暂停动画，脚本将手动控制每一帧的播放
        weapon.getAnimation().pause();

        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        int chargeUpEnd = Math.min(CHARGE_UP_END_FRAME, maxFrame);
        int chargeDownStart = Math.min(CHARGE_DOWN_START_FRAME, maxFrame);
        int chargeDownEnd = maxFrame;

        // 暂停或舰毁时保持当前合法帧，避免渲染阶段访问越界
        if (engine.isPaused() || weapon.getShip() == null || !weapon.getShip().isAlive() || weapon.getShip().isHulk()) {
            current = clampFrame(current, maxFrame);
            weapon.getAnimation().setFrame(current);
            return;
        }

        // 判断武器是否处于"正在主动充能"状态
        // 条件：充能等级大于0，并且武器当前不处于冷却状态
        boolean chargeUp = weapon.getChargeLevel() > 0 && (weapon.getCooldownRemaining() == 0 || weapon.isInBurst());

        if (chargeUp) {
            // --- 武器正在主动充能 ---
            float chargeLevel = weapon.getChargeLevel();
            // 根据当前充能百分比，直接计算并设置对应的充能动画帧
            int to = chargeUpEnd;
            current = (int) (chargeLevel * to);
            current = clampFrame(current, maxFrame);
            // 重置动画计时器，因为充能动画是直接映射的，不是序列播放
            timer = 0;
        } else {
            // --- 武器不处于主动充能状态 (可能在发射后冷却，或从充能中断开始反向播放，或已空闲) ---
            if (current > 0) { // 如果当前帧不是0 (即不是完全空闲状态)
                // 累积时间，用于驱动发射后动画或反向充能动画的帧更新
                timer += amount * weapon.getAnimation().getFrameRate();

                // 当累积的时间足够播放一帧或多帧时
                while (timer >= 1) {
                    // 检查动画是否已播放完毕并应停止
                    // 条件1: 当前帧已经是发射后动画的最后一帧
                    // 条件2: 当前帧已经是0帧 (空闲帧)
                    if (current >= chargeDownEnd || current == 0) {
                        current = 0; // 设置为最终的空闲帧
                        break;       // 结束动画播放
                    }

                    timer--; // 消耗一帧的时间

                    // 判断是播放发射后动画还是反向充能动画
                    if (current >= chargeUpEnd) {
                        // 这意味着之前已满充能或正在播放发射后动画，继续播放发射后动画
                        if (current < chargeDownStart) {
                            current = chargeDownStart;
                        } else {
                            current++;
                        }
                    } else {
                        // 当前帧尚未到满充能，意味着充能未满就被中断，需要反向播放至0帧
                        current--;
                    }

                    current = clampFrame(current, maxFrame);
                }
            }
        }
        // 设置计算出的当前动画帧
        weapon.getAnimation().setFrame(clampFrame(current, maxFrame));
    }

    private int clampFrame(int frame, int maxFrame) {
        if (frame < 0) {
            return 0;
        }
        return Math.min(frame, maxFrame);
    }
}
