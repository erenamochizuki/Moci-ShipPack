package data.scripts.weapons;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import data.scripts.util.Moci_Util;

public class Moci_MechProjDecoWeaponEffect_followControl implements EveryFrameWeaponEffectPlugin {
    boolean init = false;
    WeaponAPI source;
    WeaponAPI sourceMAIN;
    WeaponAPI sourceFollow; // FOLLOW武器引用
    WeaponAPI rightHandWeapon; // 右手武器引用
    private static final float FOLLOW_RATIO = 0.33f; // 跟随比例参数
    private static final float FOLLOW_RECOIL_OFFSET_X = 0f;
    private static final float FOLLOW_RECOIL_OFFSET_Y = 6f;
    boolean wasFiring = false;
    private boolean isAnimating = false;
    private final Moci_Util.SpriteRecoilState followRecoilState = new Moci_Util.SpriteRecoilState();

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
            Moci_Util.captureSpriteRecoilOrigin(sourceFollow, followRecoilState);

            // 不对FOLLOW武器做任何spec处理，只进行角度同步
        }

        // 检查是否选中了FOLLOW武器组
        boolean followWeaponSelected = sourceFollow != null &&
                sourceFollow.getShip().getSelectedGroupAPI() != null &&
                sourceFollow.getShip().getSelectedGroupAPI().getWeaponsCopy().contains(sourceFollow);

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

        applyFollowWeaponRecoil();
    }

    private void applyFollowWeaponRecoil() {
        if (sourceFollow == null) {
            return;
        }

        if (Moci_Util.shouldDisableRecoil(sourceFollow) ||
                !Moci_Util.hasWeaponTag(sourceFollow, Moci_Util.TAG_FOLLOW_RECOIL)) {
            Moci_Util.resetSpriteRecoil(sourceFollow, followRecoilState);
            return;
        }

        Moci_Util.applyCooldownSpriteRecoil(
                sourceFollow,
                sourceFollow,
                followRecoilState,
                FOLLOW_RECOIL_OFFSET_X,
                FOLLOW_RECOIL_OFFSET_Y
        );
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
