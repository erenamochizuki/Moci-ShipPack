package data.scripts.weapons;

import java.awt.Color;
import java.util.List;

import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import data.scripts.util.Moci_Util;
import data.scripts.util.Moci_ColorData;

public class Moci_MechProjDecoWeaponEffect_shield_arm_keepdef implements EveryFrameWeaponEffectPlugin {
    ShipAPI ship;
    boolean init = false;
    private ShipAPI shield = null;
    private final float rotationSpeed = 35f; // 转向速度
    private final float defaultOffset = 0f; // 默认角度
    protected WeaponAPI source; // 新增：关联的_SOURCE武器
    WeaponAPI sourceFollow; // 新增：FOLLOW武器引用
    private int shieldAnimationFrame = 0; // 护盾动画帧（0:无盾牌, 1:普通护盾, 2:例外护盾）
    
    // 例外护盾船体列表（使用第2帧）
    private static final String[] EXCEPTION_SHIELD_HULLS = {
        "Moci_RX_93_v2_shield_hws"
    };
    


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

            // 查找对应的盾牌
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (module.getStationSlot() != null && module.getStationSlot().getId().equals(shieldId)) {
                    shield = module;
                    // 根据船体列表确定动画帧
                    String hullId = shield.getHullSpec().getHullId();
                    boolean isException = false;
                    for (String exceptionHull : EXCEPTION_SHIELD_HULLS) {
                        if (exceptionHull.equals(hullId)) {
                            isException = true;
                            break;
                        }
                    }
                    shieldAnimationFrame = isException ? 2 : 1; // 例外用第2帧，普通用第1帧
                    break;
                }
            }

            // 查找关联的_SOURCE武器和_FOLLOW武器
            String sourceId = slotId + "_SOURCE";
            String followId = slotId + "_FOLLOW"; // 新增：FOLLOW槽位ID
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (sourceId.equals(w.getSlot().getId())) {
                    source = w;
                }
                // 新增：查找FOLLOW武器
                if (followId.equals(w.getSlot().getId())) {
                    sourceFollow = w;
                }
            }

            // 新增：如果找到了SOURCE和FOLLOW武器，初始化FOLLOW武器
            if (source != null && sourceFollow != null) {
                initializeFollowWeapon(weapon);
            }
        }

        // 控制动画
        if (weapon.getAnimation() != null) {
            if (shield != null && shield.isAlive()) {
                weapon.getAnimation().setFrame(shieldAnimationFrame);
            } else {
                weapon.getAnimation().setFrame(0);
            }
        }

        // 新增：同步FOLLOW武器角度并持续隐藏
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
     * 新增：初始化FOLLOW武器
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
     * 新增：隐藏FOLLOW武器
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

    // 查找最近的威胁目标
    // 【修改】去掉了护盾开启时不查找目标的逻辑，现在即使护盾开启也会继续举盾防御
    private CombatEntityAPI findNearestThreat() {
        // 不在战斗场景中
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) {
            return null;
        }
        // 【修改】去掉了护盾开启时的检查，只保留source武器开火时的检查
        // 如果source武器正在开火，则不查找目标
        if (source != null && source.isFiring()) {
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
}
