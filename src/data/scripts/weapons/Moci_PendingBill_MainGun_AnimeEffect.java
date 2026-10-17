package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;

public class Moci_PendingBill_MainGun_AnimeEffect implements EveryFrameWeaponEffectPlugin {
    // 当前动画帧
    int current = 0;
    // 充能动画序列的帧范围 [起始帧, 结束帧] (0到6帧)
    int[] chargeUpSequence = new int[]{0, 6};
    // 循环动画序列的帧范围 [起始帧, 结束帧] (7到10帧)
    int[] loopSequence = new int[]{7, 10};
    // 发射后/冷却动画序列的帧范围 [起始帧, 结束帧] (12到17帧)
    int[] chargeDownSequence = new int[]{12, 16};
    // 动画帧计时器，用于累积时间以驱动序列动画
    float timer = 0;
    // 循环动画计时器，用于在7-10帧之间循环
    float loopTimer = 0;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 基础检查：确保游戏和武器状态正常，且飞船存活
        if (engine == null || engine.isPaused() || weapon.getAnimation() == null || !weapon.getShip().isAlive()) {
            return;
        }

        // 默认暂停动画，脚本将手动控制每一帧的播放
        weapon.getAnimation().pause();

        // 搜索MAINGUN槽位上的武器并获取其状态
        WeaponAPI maingunWeapon = findMaingunWeapon(weapon);
        if (maingunWeapon == null) {
            // 如果没有找到MAINGUN武器，保持空闲状态
            weapon.getAnimation().setFrame(0);
            return;
        }

        // 判断MAINGUN武器是否处于"正在主动充能"状态
        // 条件：充能等级大于0，并且武器当前不处于冷却状态
        boolean chargeUp = maingunWeapon.getChargeLevel() > 0 && (maingunWeapon.getCooldownRemaining() == 0 || maingunWeapon.isInBurst());

        if (chargeUp) {
            // --- MAINGUN武器正在主动充能 ---
            float chargeLevel = maingunWeapon.getChargeLevel();
            
            if (chargeLevel <= 0.5f) {
                // 充能等级 <= 0.5时，播放0-6帧
                int to = chargeUpSequence[1]; // 6
                current = (int) (chargeLevel * 2 * to); // 乘以2是因为0.5对应到6帧
                // 重置循环计时器
                loopTimer = 0;
            } else {
                // 充能等级 > 0.5时，先播放到第7帧，然后在7-10帧之间循环
                if (current < loopSequence[0]) {
                    // 如果当前帧还没到循环序列，直接设置为第7帧
                    current = loopSequence[0];
                }
                
                // 在7-10帧之间循环播放
                loopTimer += amount * weapon.getAnimation().getFrameRate();
                
                while (loopTimer >= 1) {
                    current++;
                    if (current > loopSequence[1]) {
                        current = loopSequence[0]; // 从7帧重新开始循环
                    }
                    loopTimer--;
                }
            }
            
            // 重置主计时器，因为充能动画是直接映射或循环的
            timer = 0;
        } else {
            // --- 武器不处于主动充能状态 (可能在发射后冷却，或从充能中断开始反向播放，或已空闲) ---
            if (current > 0) { // 如果当前帧不是0 (即不是完全空闲状态)
                // 累积时间，用于驱动发射后动画或反向充能动画的帧更新
                timer += amount * weapon.getAnimation().getFrameRate();

                // 当累积的时间足够播放一帧或多帧时
                while (timer >= 1) {
                    // 检查动画是否已播放完毕并应停止
                    // 条件1: 当前帧已经是发射后动画的最后一帧 (chargeDownSequence[1]，即17帧)
                    // 条件2: 当前帧已经是0帧 (空闲帧)
                    if (current == chargeDownSequence[1] || current == 0) {
                        current = 0; // 设置为最终的空闲帧
                        break;       // 结束动画播放
                    }

                    timer--; // 消耗一帧的时间

                    // 判断是播放发射后动画还是反向充能动画
                    if (current >= loopSequence[0]) { // 如果当前帧大于等于循环序列的起始帧 (7帧)
                        // 这意味着之前已满充能或正在播放发射后动画，播放发射后动画 (12帧到17帧)
                        if (current < chargeDownSequence[0]) {
                            // 如果还没进入冷却序列，直接跳到冷却序列开始
                            current = chargeDownSequence[0];
                        } else {
                            // 已在冷却序列中，继续播放
                            current++;
                        }
                    } else {
                        // 当前帧小于7帧，意味着充能未满就被中断，需要反向播放充能动画至0帧
                        current--;
                    }
                }
                
                // 重置循环计时器
                loopTimer = 0;
            }
        }
        
        // 设置计算出的当前动画帧
        weapon.getAnimation().setFrame(current);
    }

    /**
     * 搜索舰船上的MAINGUN槽位武器
     * @param weapon 当前武器对象
     * @return 找到的第一个MAINGUN武器，如果没有找到则返回null
     */
    private WeaponAPI findMaingunWeapon(WeaponAPI weapon) {
        // 确保武器有所属舰船
        if (weapon.getShip() == null) {
            return null;
        }
        
        // 遍历舰船上的所有武器，查找MAINGUN_前缀的武器
        for (WeaponAPI shipWeapon : weapon.getShip().getAllWeapons()) {
            if (shipWeapon.getSlot() != null && shipWeapon.getSlot().getId() != null) {
                String slotId = shipWeapon.getSlot().getId();
                
                // 检查是否为MAINGUN_前缀的武器槽位
                if (slotId.startsWith("MAINGUN_")) {
                    return shipWeapon; // 返回找到的第一个MAINGUN武器
                }
            }
        }
        
        return null; // 没有找到MAINGUN武器
    }
}
