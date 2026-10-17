package data.scripts.weapons;

import java.awt.Color;
import java.util.List;

import org.apache.log4j.Logger;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import data.scripts.util.Moci_ColorData;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import data.scripts.util.Moci_Util;
import data.scripts.weapons.Moci_MechDecoWeaponMutexControl;
import data.subsystems.Moci_NTDSubsystem;
import org.magiclib.subsystems.MagicSubsystem;
import org.magiclib.subsystems.MagicSubsystemsManager;

/**
 * 护盾臂控制器 - RX-0 觉醒版本
 * 
 * 控制护盾臂的角度和COVER武器的动画帧
 * 
 * COVER武器动画帧说明（0-7共8帧）：
 * - 第7帧：护盾不存在或死亡状态
 * - 第0-6帧：护盾存活时的变形动画（响应NTD子系统）
 * 
 * 逻辑：
 * 1. 护盾不存在或死亡：COVER武器固定显示第7帧
 * 2. 护盾存活：COVER武器响应NTD子系统的变形动画（0-6帧）
 */
public class Moci_MechProjDecoWeaponEffect_shield_arm_RX0 implements EveryFrameWeaponEffectPlugin {
    private static final Logger log = Global.getLogger(Moci_MechProjDecoWeaponEffect_shield_arm_RX0.class);
    
    ShipAPI ship;
    boolean init = false;
    private ShipAPI shield = null;
    private final float rotationSpeed = 35f; // 转向速度
    private final float defaultOffset = 0f; // 默认角度
    protected WeaponAPI source; // 关联的_SOURCE武器
    WeaponAPI sourceFollow; // FOLLOW武器引用
    WeaponAPI coverWeapon; // COVER武器引用（用于控制帧动画）
    
    // NTD子系统相关
    private Moci_NTDSubsystem ntdSubsystem = null;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        // 初始化
        if (!init) {
            init = true;
            ship = weapon.getShip();
            String slotId = weapon.getSlot().getId();
            String shieldId = "SHIELD_" + slotId.substring(slotId.lastIndexOf("_") + 1);
            String coverSlotId = shieldId + "_COVER"; // COVER槽位ID

            // 查找对应的盾牌
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (module.getStationSlot() != null && module.getStationSlot().getId().equals(shieldId)) {
                    shield = module;
                    break;
                }
            }

            // 查找关联的_SOURCE武器、_FOLLOW武器和_COVER武器
            String sourceId = slotId + "_SOURCE";
            String followId = slotId + "_FOLLOW";
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (sourceId.equals(w.getSlot().getId())) {
                    source = w;
                }
                if (followId.equals(w.getSlot().getId())) {
                    sourceFollow = w;
                }
                if (coverSlotId.equals(w.getSlot().getId())) {
                    coverWeapon = w;
                }
            }

            // 如果找到了SOURCE和FOLLOW武器，初始化FOLLOW武器
            if (source != null && sourceFollow != null) {
                initializeFollowWeapon(weapon);
            }
            
            // 获取NTD子系统
            List<MagicSubsystem> subsystems = MagicSubsystemsManager.getSubsystemsForShipCopy(ship);
            if (subsystems != null) {
                for (MagicSubsystem subsystem : subsystems) {
                    if (subsystem instanceof Moci_NTDSubsystem) {
                        ntdSubsystem = (Moci_NTDSubsystem) subsystem;
                        log.info("护盾臂控制器(RX0)：成功找到NTD子系统");
                        break;
                    }
                }
            }
            
            if (ntdSubsystem == null) {
                log.info("护盾臂控制器(RX0)：未找到NTD子系统");
            }
        }

        // 控制COVER武器的动画帧和角度
        if (coverWeapon != null) {
            // 同步角度到当前武器
            coverWeapon.setCurrAngle(weapon.getCurrAngle());
            
            // 控制动画帧
            if (coverWeapon.getAnimation() != null) {
                if (shield == null || shield.isHulk() || !shield.isAlive()) {
                    // 护盾不存在或死亡：固定显示第7帧
                    coverWeapon.getAnimation().setFrame(7);
                } else {
                    // 护盾存活：响应NTD子系统的变形动画
                    int animFrame = 0;
                    
                    if (ntdSubsystem != null) {
                        // 获取NTD子系统的动画帧（0-6帧）
                        float effectLevel = ntdSubsystem.getCurrentEffectLevel();
                        Moci_NTDSubsystem.State state = ntdSubsystem.getCurrentState();
                        
                        if (state == Moci_NTDSubsystem.State.IN) {
                            // 启动阶段：0.0-0.8 播放 0-6 帧
                            float animProgress = Math.min(effectLevel / 0.8f, 1.0f);
                            animFrame = Math.round(animProgress * 6);
                        } else if (state == Moci_NTDSubsystem.State.ACTIVE) {
                            // 激活阶段：保持第6帧
                            animFrame = 6;
                        } else if (state == Moci_NTDSubsystem.State.OUT) {
                            // 结束阶段：1.0-0.2 播放 6-0 帧
                            float animProgress = Math.max((effectLevel - 0.2f) / 0.8f, 0.0f);
                            animFrame = Math.round(animProgress * 6);
                        }
                        // 其他状态（READY、COOLDOWN）保持第0帧
                    }
                    
                    // 确保帧数在有效范围内（0-6）
                    animFrame = Math.max(0, Math.min(6, animFrame));
                    coverWeapon.getAnimation().setFrame(animFrame);
                }
            }
        }

        // ========== 互斥控制：检查光剑是否正在挥动 ==========
        if (Moci_MechDecoWeaponMutexControl.isBladeSwingActive(weapon)) {
            if (source != null) {
                Moci_MechDecoWeaponMutexControl.syncAngleOnly(weapon, source);
                if (sourceFollow != null) {
                    sourceFollow.setCurrAngle(source.getCurrAngle());
                    hideFollowWeapon();
                }
            }
            return; // 重要：直接返回，不执行后续控制逻辑
        }

        // 玩家手操优先于自动顶盾
        WeaponAPI manualControlWeapon = getManualControlWeapon();
        if (manualControlWeapon != null) {
            float manualAngle = manualControlWeapon.getCurrAngle();
            weapon.setCurrAngle(manualAngle);
            if (source != null && source != manualControlWeapon) {
                source.setCurrAngle(manualAngle);
            }
            if (sourceFollow != null && sourceFollow != manualControlWeapon) {
                sourceFollow.setCurrAngle(manualAngle);
                hideFollowWeapon();
            }
            if (shield != null && shield.isAlive()) {
                shield.setFacing(manualAngle);
            }
            return;
        }

        // 非手操状态下，FOLLOW武器始终跟随SOURCE并保持隐藏
        if (sourceFollow != null && source != null) {
            sourceFollow.setCurrAngle(source.getCurrAngle());
            hideFollowWeapon();
        }

        // 检查是否有威胁目标
        CombatEntityAPI target = findNearestThreat();
        
        // 如果有威胁，执行护盾防御逻辑
        if (target != null) {
            // 固定防御角度-50f
            float targetOffset = -50f;
            
            // 平滑转向
            float currentOffset = weapon.getCurrAngle() - ship.getFacing();
            while (currentOffset > 180) currentOffset -= 360;
            while (currentOffset < -180) currentOffset += 360;

            if (currentOffset != targetOffset) {
                if (currentOffset < targetOffset) {
                    currentOffset = Math.min(currentOffset + rotationSpeed * amount, targetOffset);
                } else {
                    currentOffset = Math.max(currentOffset - rotationSpeed * amount, targetOffset);
                }
            }

            // 设置最终角度
            float finalAngle = ship.getFacing() + currentOffset;
            weapon.setCurrAngle(finalAngle);
            // 让_SOURCE武器跟随护盾臂角度
            if (source != null) {
                source.setCurrAngle(finalAngle);
            }
        }
        // 无威胁时，护盾臂跟随SOURCE武器（让SOURCE自由控制）
        else if (source != null) {
            // 计算武器相对于飞船的角度
            float shipFacing = weapon.getShip().getFacing();
            float sourceAngle = source.getCurrAngle() - shipFacing;

            // 规范化角度到[-180,180]范围
            while (sourceAngle > 180) sourceAngle -= 360;
            while (sourceAngle < -180) sourceAngle += 360;

            // 护盾臂跟随SOURCE武器
            float targetAngle = shipFacing + sourceAngle;
            weapon.setCurrAngle(targetAngle);
        }

        // 保底逻辑：如果护盾存活且其他条件都不满足，让护盾跟随手臂（SOURCE）的角度
        if (shield != null && shield.isAlive() && source != null) {
            shield.setFacing(source.getCurrAngle());
        }
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
     * 隐藏FOLLOW武器
     */
    private void hideFollowWeapon() {
        if (sourceFollow == null) return;
        
        // 透明颜色，参考骤雨mod的实现
        Color transparent = Moci_ColorData.NONE;
        
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
                render.getSprite().setHeight(0);
            }
        }
    }

    /**
     * 查找最近的威胁目标
     */
    private CombatEntityAPI findNearestThreat() {
        // 不在战斗场景中
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) {
            return null;
        }
        // 如果护盾开启或source武器正在开火，则不查找目标
        if ((ship.getShield() != null && ship.getShield().isOn()) || (source != null && source.isFiring())) {
            return null;
        }
        // 如果护盾死了，则不举盾
        if (shield == null || !shield.isAlive()) {
            return null;
        }

        CombatEntityAPI nearestThreat = null;
        float nearestDistance = Float.MAX_VALUE;

        // 检查敌方舰船
        for (ShipAPI enemy : Global.getCombatEngine().getShips()) {
            if (enemy.isAlive() && enemy.getOwner() != ship.getOwner()) {
                float distance = MathUtils.getDistance(ship, enemy);
                if (distance < 1000f && distance < nearestDistance) {
                    nearestThreat = enemy;
                    nearestDistance = distance;
                }
            }
        }

        // 使用工具类获取范围内的敌方弹幕和导弹
        List<DamagingProjectileAPI> threats = Moci_Util.getEnemyProjectilesAndMissilesWithinRange(
            ship.getLocation(), 600f, ship.getOwner());
        
        for (DamagingProjectileAPI threat : threats) {
            float distance = MathUtils.getDistance(ship, threat);
            if (distance < nearestDistance) {
                nearestThreat = threat;
                nearestDistance = distance;
            }
        }

        return nearestThreat;
    }

    private WeaponAPI getManualControlWeapon() {
        if (ship == null || ship.getSelectedGroupAPI() == null) {
            return null;
        }

        WeaponAPI activeWeapon = ship.getSelectedGroupAPI().getActiveWeapon();
        if (activeWeapon == sourceFollow || activeWeapon == source) {
            return activeWeapon;
        }
        if (sourceFollow != null && ship.getSelectedGroupAPI().getWeaponsCopy().contains(sourceFollow)) {
            return sourceFollow;
        }
        if (source != null && ship.getSelectedGroupAPI().getWeaponsCopy().contains(source)) {
            return source;
        }

        return null;
    }
}
