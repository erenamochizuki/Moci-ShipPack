package data.shipsystems.scripts;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

public class Moci_System2Effect extends BaseShipSystemScript {
    private boolean effected = true;
    private static final float RANGE = 1200; // 系统最大作用范围
    IntervalUtil interval = new IntervalUtil(0.2f, 0.2f); // 闪现间隔计时器
    Vector2f target = null; // 当前目标位置
    private jitterEffect jitter = null; // 闪现抖动效果实例
    Vector2f startLocation = null; // 系统启动时的初始位置
    float startFacing = 0; // 系统启动时的初始朝向
    float distance = 500; // 闪现随机范围半径
    private static final float MIN_TELEPORT_DISTANCE = 100f; // 最小闪现距离

    // 残影效果参数
    private static final float AFTERIMAGE_INTERVAL = 0.10f; // 残影生成间隔(秒)
    private static final float AFTERIMAGE_FADE_IN = 0f; // 残影淡入时间(秒)
    private static final float AFTERIMAGE_FADE_OUT = 1.0f; // 残影淡出时间(秒)
    private static final float AFTERIMAGE_ALPHA_START = 1f; // 初始透明度(0-1)
    private static final float AFTERIMAGE_ALPHA_END = 0f; // 结束透明度(0-1)
    private float afterimageAccumulator = 0f; // 残影生成计时器

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        ShipAPI ship;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
        } else {
            return;
        }
        // 只在系统激活时添加能量武器伤害debuff
        if (state == State.ACTIVE || state == State.IN || state == State.OUT) {
            stats.getEnergyWeaponDamageMult().modifyMult(id + "_damage_debuff", 0.5f);
        } else {
            // 确保在非激活状态时移除效果
            stats.getEnergyWeaponDamageMult().unmodify(id + "_damage_debuff");
        }

        // 重装填
        if (!effected) {
            effected = true;
            // 一次激活限定一次重装弹药
            for (WeaponAPI weapon : ship.getAllWeapons()) {
                if (weapon.getMaxAmmo() != Integer.MAX_VALUE) {
                    weapon.setAmmo(weapon.getMaxAmmo());
                }
            }
        }

        // 1. 目标查找和位置初始化
        ShipAPI t = findTarget(ship);
        if (t != null) {
            target = t.getLocation();
        }
        if (startLocation == null) {
            startLocation = new Vector2f(ship.getLocation());
            startFacing = ship.getFacing();
            // distance = 200; //默认闪现的活动半径 如果需要随机变化，则额外调整
        }
        if (target == null) { // 如果出现特殊情况丢失了目标，则锁定自身位置
            target = ship.getLocation();
        }
        // 2. 初始化抖动效果
        float amount = Global.getCombatEngine().getElapsedInLastFrame();
        if (jitter == null) { // 闪现的抖动效果
            jitter = new jitterEffect(ship);
        }

        jitter.advance(amount);
        amount *= effectLevel; // 随着系统的chargeup积累，闪现速度越来越快
        interval.advance(amount);

        if (interval.intervalElapsed()) {
            // 播放闪现音效
            Global.getSoundPlayer().playSound("system_phase_skimmer", 1f, 1f, ship.getLocation(), ship.getVelocity());

            // 以目标为中心，距离1000的位置，以distance为随机范围进行胡乱跳跃
            float facing = VectorUtils.getAngle(target, ship.getLocation());
            Vector2f anchor = MathUtils.getPointOnCircumference(target, RANGE, facing);
            // Vector2f newLocation = MathUtils.getRandomPointInCircle(anchor, distance *
            // Misc.random.nextFloat());

            // 计算新位置并确保最小距离
            Vector2f newLocation;
            do {
                newLocation = MathUtils.getRandomPointInCircle(anchor, distance * Misc.random.nextFloat());
            } while (MathUtils.getDistanceSquared(ship.getLocation(), newLocation) < MIN_TELEPORT_DISTANCE * MIN_TELEPORT_DISTANCE);

            // 在闪现前创建残影(留在原位置)
            if (ship.getLocation() != null) {
                createAdvancedAfterimages(ship, ship.getLocation(), ship.getFacing());
            }

            // ===========================
            // 以发动技能的最初位置，进行200范围的胡乱跳跃
            // Vector2f l = MathUtils.getRandomPointInCircle(startLocation,
            // distance*Misc.random.nextFloat());

            // 抖动到真正闪现的延迟 必须小于interval里面的最低数值，避免还没闪就开始进行下次闪现
            jitter.update(0.1f, newLocation, VectorUtils.getAngle(newLocation, target));
        }
        // 4. 持续生成残影效果
        afterimageAccumulator += amount;
        if (afterimageAccumulator > AFTERIMAGE_INTERVAL) {
            createAdvancedAfterimages(ship, ship.getLocation(), ship.getFacing());
            afterimageAccumulator = 0f;
        }
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        // 移除能量武器伤害debuff，使用与apply中相同的ID
        stats.getEnergyWeaponDamageMult().unmodify(id + "_damage_debuff");

        effected = false;
        ShipAPI ship;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
        } else {
            return;
        }
        target = null;

        if (startLocation != null) {// 技能结束后回归初始位置 如果不合适就删掉
            ship.getLocation().set(startLocation);
            ship.setFacing(startFacing);
        }
        if (jitter == null) {
            jitter = new jitterEffect(ship);
        } else {
            // 取消尚未完成的闪现/开火，并清空本次激活的目标历史，避免跨激活使用旧坐标
            jitter.reset();
        }
        startLocation = null;
        startFacing = 0f;
    }

    public static float getMaxRange(ShipAPI ship) {
        // 基础范围
        float baseRange = 1200f;
        // 获取舰船能量武器射程加成
        float energyRangeBonus = ship.getMutableStats().getEnergyWeaponRangeBonus().getPercentMod();
        /*
         * // 在舰船位置显示能量武器射程加成值
         * Global.getCombatEngine().addFloatingText(
         * ship.getLocation(),
         * String.valueOf(energyRangeBonus),
         * 20f,
         * Color.CYAN,
         * ship,
         * 1f,
         * 1f
         * );
         */
        // 计算最终范围 = 基础范围 * (1 + 能量武器射程加成)
        return baseRange * (1f + energyRangeBonus * 0.01f);
    }

    protected ShipAPI findTarget(ShipAPI ship) {
        float range = getMaxRange(ship);
        boolean player = ship == Global.getCombatEngine().getPlayerShip();
        ShipAPI target = ship.getShipTarget();

        if (ship.getShipAI() != null && ship.getAIFlags().hasFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)) {
            target = (ShipAPI) ship.getAIFlags().getCustom(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM);
        }

        if (target != null) {
            float radSum = ship.getCollisionRadius() + target.getCollisionRadius();
            float allowedRange = range + radSum;
            if (Misc.getDistanceSq(ship.getLocation(), target.getLocation()) > allowedRange * allowedRange)
                target = null;
        } else {
            if (target == null || target.getOwner() == ship.getOwner()) {
                if (player) {
                    target = Misc.findClosestShipEnemyOf(ship, ship.getMouseTarget(), ShipAPI.HullSize.FIGHTER, range,
                            true);
                } else {
                    Object test = ship.getAIFlags().getCustom(ShipwideAIFlags.AIFlags.MANEUVER_TARGET);
                    if (test instanceof ShipAPI) {
                        target = (ShipAPI) test;
                        float radSum = ship.getCollisionRadius() + target.getCollisionRadius();
                        float allowedRange = range + radSum;
                        if (Misc.getDistanceSq(ship.getLocation(), target.getLocation()) > allowedRange * allowedRange)
                            target = null;
                    }
                }
            }
            if (target == null) {
                target = Misc.findClosestShipEnemyOf(ship, ship.getLocation(), ShipAPI.HullSize.FIGHTER, range, true);
            }
        }

        return target;
    }

    @Override
    public String getInfoText(ShipSystemAPI system, ShipAPI ship) {
        if (system.isOutOfAmmo())
            return null;
        if (system.getState() != ShipSystemAPI.SystemState.IDLE)
            return null;

        ShipAPI target = findTarget(ship);
        if (target != null && target != ship) {
            return "就绪";
        }
        if ((target == null) && ship.getShipTarget() != null) {
            return "射程外";
        }
        return "没有目标";
    }

    @Override
    public boolean isUsable(ShipSystemAPI system, ShipAPI ship) {
        // if (true) return true;
        ShipAPI target = findTarget(ship);
        return target != null && target != ship;
    }

    // 新增残影创建方法
    private void createAdvancedAfterimages(ShipAPI ship, Vector2f location, float facing) {
        // 获取偏移量
        SpriteAPI sprite = ship.getSpriteAPI();
        float offsetX = sprite.getWidth() / 2 - sprite.getCenterX();
        float offsetY = sprite.getHeight() / 2 - sprite.getCenterY();

        // 计算旋转后的偏移量
        float trueOffsetX = (float) Math.cos(Math.toRadians(facing - 90f)) * offsetX -
                (float) Math.sin(Math.toRadians(facing - 90f)) * offsetY;
        float trueOffsetY = (float) Math.sin(Math.toRadians(facing - 90f)) * offsetX +
                (float) Math.cos(Math.toRadians(facing - 90f)) * offsetY;

        // 创建舰船主体残影
        MagicRender.battlespace(
                ship.getSpriteAPI(),
                new Vector2f(location.x + trueOffsetX, location.y + trueOffsetY),
                new Vector2f(0, 0),
                new Vector2f(sprite.getWidth(), sprite.getHeight()),
                new Vector2f(0, 0),
                facing - 90f,
                0f,
                new Color(255, 255, 255, (int) (AFTERIMAGE_ALPHA_START * 255)),
                false,
                1f,
                1f,
                0f,
                0f,
                0f,
                AFTERIMAGE_FADE_IN,
                AFTERIMAGE_FADE_OUT,
                AFTERIMAGE_ALPHA_END,
                CombatEngineLayers.BELOW_SHIPS_LAYER);

        // 为所有武器创建残影
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSprite() == null)
                continue;

            // 检查武器是否被隐藏
            boolean isHidden = false;
            if (weapon.getSprite() != null && weapon.getSprite().getColor().getAlpha() == 0) {
                isHidden = true;
            }
            if (weapon.getBarrelSpriteAPI() != null && weapon.getBarrelSpriteAPI().getColor().getAlpha() == 0) {
                isHidden = true;
            }
            if (weapon.getUnderSpriteAPI() != null && weapon.getUnderSpriteAPI().getColor().getAlpha() == 0) {
                isHidden = true;
            }
            if (weapon.getGlowSpriteAPI() != null && weapon.getGlowSpriteAPI().getColor().getAlpha() == 0) {
                isHidden = true;
            }
            if (isHidden)
                continue;

            // 获取武器贴图
            SpriteAPI weaponSprite = weapon.getSprite();
            MagicRender.battlespace(
                    weaponSprite,
                    new Vector2f(weapon.getLocation().x + trueOffsetX, weapon.getLocation().y + trueOffsetY),
                    new Vector2f(0, 0),
                    new Vector2f(weaponSprite.getWidth(), weaponSprite.getHeight()),
                    new Vector2f(0, 0),
                    weapon.getCurrAngle() - 90f,
                    0f,
                    new Color(255, 255, 255, (int) (AFTERIMAGE_ALPHA_START * 255)),
                    false,
                    1f,
                    1f,
                    0f,
                    0f,
                    0f,
                    AFTERIMAGE_FADE_IN,
                    AFTERIMAGE_FADE_OUT,
                    AFTERIMAGE_ALPHA_END,
                    CombatEngineLayers.BELOW_SHIPS_LAYER);
        }
    }

    private static class jitterEffect {
        final ShipAPI ship;
        float delay = 0f;
        Object KEY = new Object();
        Vector2f targetLocation = null;
        float facing = 0;
        float moveDelay = 0;
        List<WeaponAPI> fireWeapon = new ArrayList<>();
        Vector2f lastKnownTargetLocation = null; // 添加：记录最后一个目标的位置

        private jitterEffect(ShipAPI ship) {
            this.ship = ship;
            for (WeaponAPI weapon : ship.getAllWeapons()) {
                // 闪现后发动攻击的武器
                if (weapon.getSlot().getId().equals("ARM_R_MAIN")) {
                    fireWeapon.add(weapon);
                }
            }
        }

        public void update(float moveDelay, Vector2f targetLocation, float facing) {
            this.delay = 0.5f; // 每次闪现抖动0.5秒
            this.targetLocation = targetLocation;
            this.moveDelay = moveDelay;
            this.facing = facing;
            // for (WeaponAPI weapon : fireWeapon) {
            // weapon.setRemainingCooldownTo(0); //直接刷新冷却
            // }
        }

        private void reset() {
            delay = 0f;
            targetLocation = null;
            moveDelay = 0f;
            lastKnownTargetLocation = null;
        }

        public void advance(float amount) {
            if (delay > 0) {
                delay -= amount;
                ship.setJitter(KEY, Color.cyan, delay, 10, 0f, 0);
            }
            if (targetLocation != null) {
                if (moveDelay > 0) {
                    moveDelay -= amount;
                } else {
                    ship.getLocation().set(targetLocation);
                    targetLocation = null;
                    for (WeaponAPI weapon : fireWeapon) {
                        // 在解析目标前保留武器当前朝向，作为完全无目标时的后备方向。
                        float weaponFacing = weapon.getCurrAngle();
                        // 播放开火音效
                        Global.getSoundPlayer().playSound("Moci_Weapon3Sound", 1f, 0.8f, weapon.getLocation(),
                                ship.getVelocity());
                        // 计算提前量：预测目标未来位置
                        ShipAPI target = null;
                        if (ship.getShipTarget() != null && ship.getShipTarget().isAlive()) {
                            target = ship.getShipTarget();
                        } else {
                            // 自动寻找最近敌舰
                            target = Misc.findClosestShipEnemyOf(ship, ship.getLocation(), ShipAPI.HullSize.FIGHTER,
                                    2000f, true);
                        }
                        Vector2f firePoint = weapon.getFirePoint(0);
                        float projectileSpeed = getProjectileSpeed(weapon);
                        Vector2f aimPoint = null;

                        if (target != null) {
                            // 记录目标位置，以便在目标消失时使用
                            lastKnownTargetLocation = new Vector2f(target.getLocation());

                            // 计算到目标的距离
                            Vector2f toTarget = Vector2f.sub(target.getLocation(), firePoint, null);
                            float distance = toTarget.length();
                            float timeToHit = distance / projectileSpeed;

                            // 计算相对速度：目标速度 - 舰船速度
                            Vector2f relativeVelocity = Vector2f.sub(target.getVelocity(), ship.getVelocity(), null);

                            // 使用相对速度预测目标未来位置
                            aimPoint = Vector2f.add(target.getLocation(),
                                    (Vector2f) new Vector2f(relativeVelocity).scale(timeToHit), null);
                        } else if (lastKnownTargetLocation != null) {
                            // 如果没有目标但有最后已知目标位置，使用该位置
                            aimPoint = new Vector2f(lastKnownTargetLocation);
                        } else {
                            // 如果既没有目标又没有历史记录，则沿 ARM_R_MAIN 当前朝向开火。
                            aimPoint = MathUtils.getPointOnCircumference(firePoint, 1000f, weaponFacing);
                        }

                        // 舰船、武器和弹体统一朝向实际开火方向（包括预测点/历史位置）。
                        float fireAngle = VectorUtils.getAngle(firePoint, aimPoint);
                        ship.setFacing(fireAngle);
                        weapon.setCurrAngle(fireAngle);
                        Global.getCombatEngine().spawnProjectile(ship, weapon, weapon.getId(), firePoint, fireAngle,
                                ship.getVelocity());
                        Global.getCombatEngine().spawnMuzzleFlashOrSmoke(ship, weapon.getSlot(), weapon.getSpec(), 0,
                                fireAngle);
                    }
                }
            }
        }

        private float getProjectileSpeed(WeaponAPI weapon) {
            MutableShipStatsAPI stats = ship.getMutableStats();
            float speedMultiplier = stats.getProjectileSpeedMult().getMult();
            if (weapon.getType() == WeaponAPI.WeaponType.BALLISTIC) {
                speedMultiplier *= stats.getBallisticProjectileSpeedMult().getMult();
            } else if (weapon.getType() == WeaponAPI.WeaponType.ENERGY) {
                speedMultiplier *= stats.getEnergyProjectileSpeedMult().getMult();
            }
            return 1000f * speedMultiplier;
        }
    }
}
