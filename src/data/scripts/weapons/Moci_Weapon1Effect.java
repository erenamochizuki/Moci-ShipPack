package data.scripts.weapons;

import java.awt.Color;
import java.util.HashSet;
import java.util.Set;

import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import com.fs.starfarer.api.util.Misc;

/**
 * 武器应当是射程很短，颜色alpha为0的类似速子长矛的爆发射击光束
 */
public class Moci_Weapon1Effect implements EveryFrameWeaponEffectPlugin {
    private boolean init = false;
    private static final String OTHER_WEAPON_ID = "Moci_Weapon2"; // 互斥武器ID

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (!init) {
            init = true;
            // weapon.getShip().addListener(new listener(weapon));
            Global.getCombatEngine().addLayeredRenderingPlugin(new listener(weapon));
        }

    }

    private static class listener extends BaseCombatLayeredRenderingPlugin {
        final ShipAPI ship;
        final WeaponAPI weapon;
        float alpha = 1;
        // 移除原始碰撞类型存储相关变量
        // CollisionClass originalCollision; // 存储原始碰撞类型
        // boolean collisionModified = false; // 标记是否已修改碰撞类型
        // float collisionModifyTime = 0f; // 记录碰撞修改时间
        // private static final float MAX_COLLISION_TIME = 10f; // 最大碰撞修改时间（秒）
        Set<Vector2f> mineLoc = new HashSet<>();
        Set<ShipAPI> targets = new HashSet<>();
        Vector2f startPos; // 新增：记录武器启动时的位置

        // 新增：记录武器是否正在释放
        boolean isWeaponActive = false;
        private static final String WEAPON_ID = "Moci_Weapon1";

        private listener(WeaponAPI weapon) {
            this.ship = weapon.getShip();
            this.weapon = weapon;
            // 移除原始碰撞类型存储
            // this.originalCollision = ship.getCollisionClass(); // 存储原始碰撞类型
            this.layer = CombatEngineLayers.ABOVE_SHIPS_LAYER;
            this.sprite = Global.getSettings().getSprite("fx", "Moci_engine_partical");
            this.startPos = new Vector2f(ship.getLocation()); // 初始化时记录起始位置
        }

        // 移除恢复碰撞类型的安全方法
        // private void restoreCollision() {
        // if (collisionModified && originalCollision != null) {
        // ship.setCollisionClass(originalCollision);
        // collisionModified = false;
        // collisionModifyTime = 0f;
        // }
        // }

        @Override
        public float getRenderRadius() {
            return Float.MAX_VALUE;
        }

        @Override
        public void advance(float amount) {
            CombatEngineAPI engine = Global.getCombatEngine();

            if (engine == null || engine.isPaused()) {
                return;
            }

            // 飞船停机则移除，但要先恢复状态
            if (!ship.isAlive() || ship.isHulk()) {
                // 死亡前恢复状态
                if (isWeaponActive) {
                    restoreShipState();
                }
                isExpired = true;
                return;
            }

            // 检查各种可能的中断条件
            boolean shouldInterrupt = false;
            if (isWeaponActive) {
                // 检查舰船是否过载
                if (ship.getFluxTracker().isOverloaded()) {
                    shouldInterrupt = true;
                }
                // 检查舰船是否被强制排幅（虽然我们禁用了强制排幅，但可能被其他效果触发）
                else if (ship.getFluxTracker().isVenting()) {
                    shouldInterrupt = true;
                }
                // 检查武器是否被禁用或损坏
                else if (weapon.isDisabled() || weapon.isPermanentlyDisabled()) {
                    shouldInterrupt = true;
                }
                // 检查舰船是否被EMP（通过检查引擎是否被禁用）
                else if (ship.getEngineController().isDisabled()) {
                    shouldInterrupt = true;
                }
                // 检查舰船是否处于相位状态
                else if (ship.isPhased()) {
                    shouldInterrupt = true;
                }

                // 如果检测到中断条件，立即恢复状态
                if (shouldInterrupt) {
                    restoreShipState();
                    isWeaponActive = false;
                    // 清理速度和加速度修正
                    ship.getMutableStats().getMaxSpeed().unmodify(WEAPON_ID);
                    ship.getMutableStats().getAcceleration().unmodify(WEAPON_ID);
                    // 清空目标记录
                    if (!mineLoc.isEmpty()) {
                        mineLoc.clear();
                        targets.clear();
                    }
                    // 重置透明度
                    alpha = 1f;
                    ship.setExtraAlphaMult2(alpha);
                    ship.setApplyExtraAlphaToEngines(true);
                    return; // 提前退出，避免后续逻辑执行
                }
            }

            // 移除超时保护逻辑
            // if (collisionModified) {
            // collisionModifyTime += amount;
            // if (collisionModifyTime > MAX_COLLISION_TIME) {
            // restoreCollision();
            // // 可选：添加日志警告
            // Global.getLogger(this.getClass()).warn("武器 " + weapon.getId() + "
            // 碰撞类型修改超时，强制恢复");
            // }
            // }

            float a; // 目标透明度
            // 当武器启动时更新起始位置
            if (weapon.isFiring() && weapon.getChargeLevel() < 0.1f) {
                startPos.set(ship.getLocation());
            }
            // 额外的安全检查：如果武器不再开火但状态仍然活跃，强制恢复
            // 注意：不在这里处理爆炸，让正常的else分支处理

            // 当武器处于开火状态时，glow贴图显现
            if (weapon.isFiring() && weapon.getCooldownRemaining() == 0 && !shouldInterrupt) {
                // 如果武器刚开始激活，应用特殊状态
                if (!isWeaponActive) {
                    isWeaponActive = true;
                    // 设置碰撞类型为NONE，无碰撞而不是相位，这样飞船才能维持武器开火
                    ship.setCollisionClass(CollisionClass.NONE);
                    // 禁用强制排幅，防止打断武器
                    ship.getMutableStats().getVentRateMult().modifyMult(WEAPON_ID, 0f);
                }

                // 禁用互斥武器
                for (WeaponAPI w : weapon.getShip().getAllWeapons()) {
                    if (w.getId().equals(OTHER_WEAPON_ID)) {
                        w.setForceNoFireOneFrame(true);
                    }
                }

                // 舰船加速
                ship.getMutableStats().getMaxSpeed().modifyFlat(WEAPON_ID, 600);
                ship.getMutableStats().getAcceleration().modifyFlat(WEAPON_ID, 3000);
                // 禁用停止，后退指令
                ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
                ship.blockCommandForOneFrame(ShipCommand.DECELERATE);
                // 持续加速
                ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
                // 透明度同步武器的chargeup进程 最低0.25
                a = Math.max(0.25f, 1 - weapon.getChargeLevel());

                //
                // 也许有必要禁用其它武器的开火
                //

                // 最近的敌人距离小于双方碰撞半径和 视作进入范围，添加炸弹猫点
                ShipAPI nearest = AIUtils.getNearestEnemy(ship);
                if (nearest != null && !targets.contains(nearest)) {
                    float rangeSQ = ship.getCollisionRadius() + nearest.getCollisionRadius();
                    rangeSQ *= rangeSQ;
                    if (Misc.getDistanceSq(nearest.getLocation(), ship.getLocation()) < rangeSQ) {
                        // 纪录位置
                        mineLoc.add(new Vector2f(nearest.getLocation()));
                        targets.add(nearest);
                    }
                }
            } else {
                a = 1;

                // 处理爆炸效果（当武器停止开火且有地雷位置时）
                if (isWeaponActive && !mineLoc.isEmpty()) {
                    // 创建从起始位置到当前位置的电弧
                    // 计算伤害值
                    float dmg = Math.max(
                            weapon.getDamage().getDamage(),
                            weapon.getDamage().getDamage() *
                                    weapon.getSpec().getBurstDuration() *
                                    (weapon.getShip().getMutableStats().getEnergyWeaponDamageMult().computeMultMod() +
                                            weapon.getShip().getMutableStats().getBeamWeaponDamageMult().getPercentMod()
                                                    / 100f));

                    engine.spawnEmpArcVisual(
                            startPos, ship, ship.getLocation(), ship,
                            45f, // 电弧宽度
                            new Color(100, 100, 255, 255), // 电弧颜色
                            new Color(255, 255, 255, 255) // 边缘颜色
                    );
                    // 遍历坐标并释放爆炸
                    for (Vector2f loc : mineLoc) {
                        DamagingExplosionSpec spec = new DamagingExplosionSpec(
                                0.1f, // 爆炸持续时间
                                200f, // 伤害半径
                                100, // 核心伤害半径
                                dmg, // 最大伤害
                                dmg * 0.5f, // 最小伤害
                                CollisionClass.MISSILE_FF, // 碰撞类型
                                CollisionClass.MISSILE_FF, // 对战机碰撞类型
                                10, // 粒子尺寸
                                15, // 粒子尺寸随机数
                                1.5f, // 粒子持续时间
                                30, // 粒子数量
                                new Color(26, 125, 255, 255), // 粒子颜色
                                new Color(138, 209, 255, 255) // 爆炸颜色
                        );
                        engine.spawnDamagingExplosion(spec, ship, loc);
                        Global.getSoundPlayer().playSound("Moci_Light_1", 1f, 1f, loc, new Vector2f());
                    }
                    mineLoc.clear();
                    targets.clear();
                }

                // 还原状态
                if (isWeaponActive) {
                    restoreShipState();
                    isWeaponActive = false;
                    // 清理速度和加速度修正
                    ship.getMutableStats().getMaxSpeed().unmodify(WEAPON_ID);
                    ship.getMutableStats().getAcceleration().unmodify(WEAPON_ID);
                    // 直接设置速度为默认最大速度
                    float defaultMaxSpeed = ship.getMaxSpeed();
                    if (ship.getVelocity().length() > defaultMaxSpeed) {
                        Vector2f velocity = ship.getVelocity();
                        velocity.normalise();
                        velocity.scale(defaultMaxSpeed);
                        ship.getVelocity().set(velocity);
                    }
                }
            }
            // 透明度平滑变化
            if (a < 1) {
                if (alpha >= a) {
                    alpha = a;
                } else {
                    alpha = Math.min(1, alpha + amount);
                }
            } else {
                alpha = 1;
            }
            ship.setExtraAlphaMult2(alpha);
            ship.setApplyExtraAlphaToEngines(true);
        }

        // 新增：恢复舰船状态的方法
        private void restoreShipState() {
            // 直接还原为ship类型
            ship.setCollisionClass(CollisionClass.SHIP);
            // 恢复强制排幅
            ship.getMutableStats().getVentRateMult().unmodify(WEAPON_ID);
            // 确保清理所有相关的状态修正
            ship.getMutableStats().getMaxSpeed().unmodify(WEAPON_ID);
            ship.getMutableStats().getAcceleration().unmodify(WEAPON_ID);

            // 规范化舰船速度
            float defaultMaxSpeed = ship.getMaxSpeed();
            if (ship.getVelocity().length() > defaultMaxSpeed) {
                Vector2f velocity = ship.getVelocity();
                velocity.normalise();
                velocity.scale(defaultMaxSpeed);
                ship.getVelocity().set(velocity);
            }
        }

        private SpriteAPI sprite;

        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            if (Global.getCombatEngine().isPaused()) {
                return;
            }

            if (!mineLoc.isEmpty()) {
                for (Vector2f loc : mineLoc) {
                    sprite.renderAtCenter(loc.x, loc.y);
                }
            }

        }

        private boolean isExpired = false;

        @Override
        public boolean isExpired() {
            return isExpired;
        }
    }

}
