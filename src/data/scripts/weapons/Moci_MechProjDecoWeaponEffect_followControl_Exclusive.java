package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 带有互斥开火控制的机甲装饰武器效果
 * 
 * 开火互斥规则：
 * - 选中MAIN武器组时：禁止FOLLOW武器开火
 * - 选中FOLLOW武器组时：禁止MAIN武器开火
 * - 同时选中两个武器组时：
 *   - 如果MAIN在冷却中：允许FOLLOW开火
 *   - 如果MAIN不在冷却中：优先MAIN开火
 * - 都未选中时：默认只允许MAIN开火
 */
public class Moci_MechProjDecoWeaponEffect_followControl_Exclusive implements EveryFrameWeaponEffectPlugin {
    boolean init = false;
    WeaponAPI source;
    WeaponAPI sourceMAIN;
    WeaponAPI sourceFollow; // FOLLOW武器引用
    WeaponAPI rightHandWeapon; // 右手武器引用
    private static final float FOLLOW_RATIO = 0.33f; // 跟随比例参数
    boolean wasFiring = false;
    private boolean isAnimating = false;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        // 初始化
        if (!init) {
            init = true;
            ShipAPI ship = weapon.getShip();
            String slotId = weapon.getSlot().getId();
            // 通过武器槽添加后缀寻找关联武器
            String sourceId = slotId + "_SOURCE";
            String mainId = slotId + "_MAIN"; // 主炮槽位ID
            String followId = slotId + "_FOLLOW"; // FOLLOW槽位ID
            String rightHandId = slotId + "_RIGHT"; // 右手武器槽位ID

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
                // 查找右手武器
                if (rightHandId.equals(w.getSlot().getId())) {
                    rightHandWeapon = w;
                }
            }
        }

        // 检查武器组选中状态
        boolean mainWeaponSelected = false;
        boolean followWeaponSelected = false;

        // 检查MAIN武器是否被选中（优先检查sourceMAIN，其次是source）
        WeaponAPI mainWeapon = sourceMAIN != null ? sourceMAIN : source;
        if (mainWeapon != null && mainWeapon.getShip().getSelectedGroupAPI() != null) {
            mainWeaponSelected = mainWeapon.getShip().getSelectedGroupAPI().getWeaponsCopy().contains(mainWeapon);
        }

        // 检查FOLLOW武器是否被选中
        if (sourceFollow != null && sourceFollow.getShip().getSelectedGroupAPI() != null) {
            followWeaponSelected = sourceFollow.getShip().getSelectedGroupAPI().getWeaponsCopy().contains(sourceFollow);
        }

        // 应用互斥开火控制
        applyExclusiveFiringControl(mainWeaponSelected, followWeaponSelected);

        // 如果选中了FOLLOW武器组，让右手和右手武器跟随FOLLOW武器
        if (followWeaponSelected) {
            // 让当前装饰武器跟随FOLLOW武器
            weapon.setCurrAngle(sourceFollow.getCurrAngle());

            // 让右手武器跟随FOLLOW武器
            if (rightHandWeapon != null) {
                rightHandWeapon.setCurrAngle(sourceFollow.getCurrAngle());
            }

            // 让SOURCE武器跟随FOLLOW武器
            if (source != null) {
                source.setCurrAngle(sourceFollow.getCurrAngle());
            }
        }
        // 否则执行原有的同步控制
        else {
            // 原有的SOURCE武器同步控制
            if (source != null) {
                weapon.setCurrAngle(source.getCurrAngle());

                // 同步FOLLOW武器角度（但不隐藏）
                if (sourceFollow != null) {
                    sourceFollow.setCurrAngle(source.getCurrAngle());
                }

                // 动画同步控制
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
                            weapon.getAnimation().setFrame(0); // 播放完成后回到第0帧
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

            // 原有的MAIN武器比例跟随控制
            if (sourceMAIN != null) {
                // 计算武器相对于飞船的角度
                float shipFacing = weapon.getShip().getFacing();
                float sourceAngle = sourceMAIN.getCurrAngle() - shipFacing;

                // 规范化角度到[-180,180]范围
                while (sourceAngle > 180)
                    sourceAngle -= 360;
                while (sourceAngle < -180)
                    sourceAngle += 360;

                // 应用跟随比例并加回飞船朝向
                float targetAngle = shipFacing + sourceAngle * FOLLOW_RATIO;
                weapon.setCurrAngle(targetAngle);
            }
        }
    }

    /**
     * 应用互斥开火控制
     * 
     * @param mainWeaponSelected MAIN武器是否被选中
     * @param followWeaponSelected FOLLOW武器是否被选中
     */
    private void applyExclusiveFiringControl(boolean mainWeaponSelected, boolean followWeaponSelected) {
        WeaponAPI mainWeapon = sourceMAIN != null ? sourceMAIN : source;
        
        // 规则1: 同时选中两个武器组时，根据MAIN冷却状态决定
        if (mainWeaponSelected && followWeaponSelected) {
            // 检查MAIN武器是否在冷却中
            boolean mainInCooldown = mainWeapon != null && mainWeapon.getCooldownRemaining() > 0;
            
            if (mainInCooldown) {
                // MAIN在冷却中，允许FOLLOW开火
                disableWeapon(mainWeapon);
                enableWeapon(sourceFollow);
            } else {
                // MAIN不在冷却中，优先MAIN开火
                disableWeapon(sourceFollow);
                enableWeapon(mainWeapon);
            }
        }
        // 规则2: 只选中MAIN武器组时，禁止FOLLOW开火
        else if (mainWeaponSelected && !followWeaponSelected) {
            disableWeapon(sourceFollow);
            enableWeapon(mainWeapon);
        }
        // 规则3: 只选中FOLLOW武器组时，禁止MAIN开火
        else if (!mainWeaponSelected && followWeaponSelected) {
            disableWeapon(mainWeapon);
            enableWeapon(sourceFollow);
        }
        // 规则4: 都未选中时，默认只允许MAIN开火
        else {
            enableWeapon(mainWeapon);
            disableWeapon(sourceFollow);
        }
    }

    /**
     * 禁用武器开火
     * 
     * @param weapon 要禁用的武器
     */
    private void disableWeapon(WeaponAPI weapon) {
        if (weapon == null) {
            return;
        }
        weapon.setForceNoFireOneFrame(true);
    }

    /**
     * 启用武器开火（取消禁用）
     * 
     * @param weapon 要启用的武器
     */
    private void enableWeapon(WeaponAPI weapon) {
        if (weapon == null) {
            return;
        }
        // 不需要显式启用，只要不调用setForceNoFireOneFrame即可
        // 这里留空是为了代码可读性
    }

    /**
     * 批量修改开火点
     * 从原版复制的方法
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
