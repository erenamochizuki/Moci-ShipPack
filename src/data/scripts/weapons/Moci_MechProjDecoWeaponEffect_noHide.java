package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import data.scripts.util.Moci_Util;

public class Moci_MechProjDecoWeaponEffect_noHide implements EveryFrameWeaponEffectPlugin {
    boolean init = false;
    WeaponAPI source;
    WeaponAPI sourceMAIN;
    WeaponAPI sourceFollow; // 新增：FOLLOW武器引用
    private static final float FOLLOW_RATIO = 0.33f; // 新增跟随比例参数
    protected static final float DEFAULT_RECOIL_OFFSET_X = 0f;
    protected static final float DEFAULT_RECOIL_OFFSET_Y = 2f;
    protected static final float HIDE_SOURCE_RECOIL_OFFSET_X = 0f;
    protected static final float HIDE_SOURCE_RECOIL_OFFSET_Y = 6f;
    boolean wasFiring = false;
    private boolean isAnimating = false;
    private ShipAPI ship; // 新增：舰船引用
    protected final Moci_Util.SpriteRecoilState recoilState = new Moci_Util.SpriteRecoilState();
    protected final Moci_Util.SpriteRecoilState sourceRecoilState = new Moci_Util.SpriteRecoilState();
    
    // ========== 互斥控制标记（根据槽位动态判断） ==========
    private String bladeSwingActiveKey = null; // 根据槽位ID动态设置

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        //初始化
        if (!init) {
            //    Global.getLogger(this.getClass()).info("init");
            init = true;
            Moci_Util.captureSpriteRecoilOrigin(weapon, recoilState);
            ship = weapon.getShip();
            String slotId = weapon.getSlot().getId();
            
            // 根据槽位ID动态判断是左手还是右手
            if (slotId.contains("_L")) {
                bladeSwingActiveKey = "MOCI_BLADE_SWING_ACTIVE_LEFT";
            } else if (slotId.contains("_R")) {
                bladeSwingActiveKey = "MOCI_BLADE_SWING_ACTIVE_RIGHT";
            } else {
                // 默认为右手（如果槽位名称不包含L或R）
                bladeSwingActiveKey = "MOCI_BLADE_SWING_ACTIVE_RIGHT";
            }
            
            //通过武器槽添加后缀寻找关联武器
            String sourceId = slotId + "_SOURCE";
            String mainId = slotId + "_MAIN"; // 主炮槽位ID
            String followId = slotId + "_FOLLOW"; // 新增：FOLLOW槽位ID

            for (WeaponAPI w : ship.getAllWeapons()) {
                if (sourceId.equals(w.getSlot().getId())) {
                    source = w;
                }
                // 如果_SOURCE槽位不存在，检查_MAIN槽位
                if (source == null && mainId.equals(w.getSlot().getId())) {
                    sourceMAIN = w;
                }
                // 新增：查找FOLLOW武器
                if (followId.equals(w.getSlot().getId())) {
                    sourceFollow = w;
                }
            }
            Moci_Util.captureSpriteRecoilOrigin(source, sourceRecoilState);

            // 新增：如果找到了SOURCE和FOLLOW武器，初始化FOLLOW武器
            if (source != null && sourceFollow != null) {
                initializeFollowWeapon(weapon);
            }
        }

        // ========== 互斥控制：如果光剑正在挥动，暂停控制 ==========
        if (isBladeSwingActive()) {
            // 光剑挥动时，不控制手臂角度，让光剑脚本控制
            // 但仍然处理SOURCE武器的同步和动画
            if (source != null) {
                weapon.setCurrAngle(source.getCurrAngle());
                
                if (sourceFollow != null) {
                    sourceFollow.setCurrAngle(source.getCurrAngle());
                    hideFollowWeapon();
                }
                
                if (weapon.getAnimation() != null) {
                    boolean isFiring = source.isFiring() &&
                            (source.getCooldownRemaining() == 0 ||
                                    (source.getSpec().getBurstSize() > 1 && source.isInBurst()));

                if (isFiring) {
                    if (!wasFiring) {
                            setSafeFrame(weapon, 0);
                    }
                    weapon.getAnimation().play();
                    wasFiring = true;
                    isAnimating = true;
                } else if (wasFiring) {
                    if (weapon.getAnimation().getFrame() >= weapon.getAnimation().getNumFrames() - 1) {
                            weapon.getAnimation().pause();
                            setSafeFrame(weapon, 0);
                            wasFiring = false;
                            isAnimating = false;
                        } else if (isAnimating) {
                            weapon.getAnimation().play();
                        }
                    } else {
                        weapon.getAnimation().pause();
                        isAnimating = false;
                    }
                }
            }
            Moci_Util.resetSpriteRecoil(weapon, recoilState);
            Moci_Util.resetSpriteRecoil(source, sourceRecoilState);
            return;
        }

        // 新的动画同步控制
        if (source != null) {
            weapon.setCurrAngle(source.getCurrAngle());

            // 新增：同步FOLLOW武器角度并持续隐藏
            if (sourceFollow != null) {
                sourceFollow.setCurrAngle(source.getCurrAngle());
                hideFollowWeapon();
            }

            if (weapon.getAnimation() != null) {
                boolean isFiring = source.isFiring() &&
                        (source.getCooldownRemaining() == 0 ||
                                (source.getSpec().getBurstSize() > 1 && source.isInBurst()));

                if (isFiring) {
                    // 重置动画到第一帧
                    if (!wasFiring) {
                        setSafeFrame(weapon, 0);
                    }
                    weapon.getAnimation().play();
                    wasFiring = true;
                    isAnimating = true;
                } else if (wasFiring) {
                    // 检查是否到达最后一帧
                    if (weapon.getAnimation().getFrame() >= weapon.getAnimation().getNumFrames() - 1) {
                        weapon.getAnimation().pause();
                        setSafeFrame(weapon, 0);  // 播放完成后回到第0帧
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
            applyDecorativeRecoil(weapon);
            applySourceRecoil();

        }
        else {
            Moci_Util.resetSpriteRecoil(weapon, recoilState);
            Moci_Util.resetSpriteRecoil(source, sourceRecoilState);
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

    protected boolean shouldApplyRecoil(WeaponAPI weapon) {
        return !Moci_Util.shouldDisableRecoil(weapon, source);
    }

    protected float getRecoilOffsetX() {
        return DEFAULT_RECOIL_OFFSET_X;
    }

    protected float getRecoilOffsetY() {
        return DEFAULT_RECOIL_OFFSET_Y;
    }

    protected void applyDecorativeRecoil(WeaponAPI weapon) {
        if (!shouldApplyRecoil(weapon) || source == null) {
            Moci_Util.resetSpriteRecoil(weapon, recoilState);
            return;
        }

        Moci_Util.applyCooldownSpriteRecoil(
                weapon,
                source,
                recoilState,
                getRecoilOffsetX(),
                getRecoilOffsetY()
        );
    }

    protected boolean shouldApplySourceRecoil() {
        if (source == null || !shouldApplyRecoil(source)) {
            return false;
        }

        // noHide 场景下，SOURCE 是可见武器；BUILT_IN 槽位默认跟随后坐，避免和装饰层脱节。
        if (source.getSlot() != null && source.getSlot().isBuiltIn()) {
            return true;
        }

        return Moci_Util.shouldApplyHiddenSourceRecoil(source, source);
    }

    protected void applySourceRecoil() {
        if (!shouldApplySourceRecoil()) {
            Moci_Util.resetSpriteRecoil(source, sourceRecoilState);
            return;
        }

        Moci_Util.applyCooldownSpriteRecoil(
                source,
                source,
                sourceRecoilState,
                HIDE_SOURCE_RECOIL_OFFSET_X,
                HIDE_SOURCE_RECOIL_OFFSET_Y
        );
    }

    protected void setSafeFrame(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return;
        }
        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        int safeFrame = Math.max(0, Math.min(frame, maxFrame));
        weapon.getAnimation().setFrame(safeFrame);
    }
    
    /**
     * 新增：初始化FOLLOW武器
     * FOLLOW武器同步到SOURCE武器的位置
     */
    private void initializeFollowWeapon(WeaponAPI weapon) {
        if (source == null || sourceFollow == null) return;

        // SOURCE武器保持不变，不需要重新同步开火点
        // （因为原有代码中没有对SOURCE武器做开火点同步）

        // FOLLOW武器同步到SOURCE武器的位置
        Vector2f diffFollow = new Vector2f();
        Vector2f.sub(source.getSlot().getLocation(), sourceFollow.getSlot().getLocation(), diffFollow);
        
        // 获取SOURCE武器的开火点作为FOLLOW武器的目标开火点
        Vector2f sourceFirePoint = source.getSpec().getTurretFireOffsets().get(0);
        syncFirePoint(sourceFollow, diffFollow, sourceFirePoint);
        
        // 初始隐藏FOLLOW武器
        hideFollowWeapon();
    }

    /**
     * 新增：隐藏FOLLOW武器
     * 使用和Moci_MechProjDecoWeaponEffect相同的隐藏方法
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
     * 新增：批量修改开火点
     * 从Moci_MechProjDecoWeaponEffect复制的方法
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
    
    /**
     * 检查光剑是否正在挥动（用于互斥控制）
     */
    private boolean isBladeSwingActive() {
        if (ship == null || bladeSwingActiveKey == null) return false;
        Boolean active = (Boolean) ship.getCustomData().get(bladeSwingActiveKey);
        return active != null && active;
    }
}
