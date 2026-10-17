package data.hullmods;

import java.awt.Color;
import java.util.List;
import java.util.Random;

import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import data.hullmods.Moci_VolatileAmmunition.VolatileAmmunitionScript;

import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import com.fs.starfarer.api.combat.CollisionClass;

/**
 * 易爆弹药 - "Volatile Ammunition"船体插件
 * 功能：增加殉爆伤害和范围，受到高伤害时导弹武器有几率发生殉爆
 * 特性：单次伤害超过500时，50%几率触发最近导弹武器殉爆
 */
public class Moci_VolatileAmmunition extends BaseHullMod {

    // 殉爆伤害倍数（增加）
    public static final float DAMAGE_MULT = 2f;

    // 殉爆范围倍数（增加）
    public static final float RADIUS_MULT = 2f;

    // 触发导弹殉爆的最小伤害阈值
    private static final float DAMAGE_THRESHOLD = 500f;

    // 导弹殉爆触发几率
    private static final float EXPLOSION_CHANCE = 0.5f;

    // 导弹殉爆伤害比例（武器伤害的50%）
    private static final float MISSILE_EXPLOSION_DAMAGE_RATIO = 0.5f;

    // 导弹殉爆范围（随机50-150）
    private static final float MIN_EXPLOSION_RADIUS = 50f;
    private static final float MAX_EXPLOSION_RADIUS = 150f;

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize,
            com.fs.starfarer.api.combat.MutableShipStatsAPI stats,
            String id) {
        // 增加殉爆伤害和范围
        stats.getDynamic().getStat(Stats.EXPLOSION_DAMAGE_MULT).modifyMult(id, DAMAGE_MULT);
        stats.getDynamic().getStat(Stats.EXPLOSION_RADIUS_MULT).modifyMult(id, RADIUS_MULT);
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 注册易爆弹药监听器
        ship.addListener(new VolatileAmmunitionScript(ship));
    }

    // 易爆弹药监听器，负责处理受伤时的导弹殉爆
    public static class VolatileAmmunitionScript implements AdvanceableListener, DamageTakenModifier {

        private final ShipAPI ship;
        private final Random random;

        public VolatileAmmunitionScript(ShipAPI ship) {
            this.ship = ship;
            this.random = new Random();
        }

        @Override
        public void advance(float amount) {
            // 安全检查：确保舰船不为null
            if (ship == null) {
                return;
            }

            // 死亡时移除监听器
            if (!ship.isAlive()) {
                ship.removeListener(this);
                return;
            }
        }

        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage,
                Vector2f point, boolean shieldHit) {
            // 只有在舰船受到结构伤害时才触发
            if (shieldHit || ship == null || !ship.isAlive() || ship.isHulk()) {
                return null;
            }

            // 检查伤害是否超过阈值
            float damageAmount = damage.getDamage();
            if (damageAmount < DAMAGE_THRESHOLD) {
                return null;
            }

            // 50%几率触发导弹殉爆
            if (random.nextFloat() > EXPLOSION_CHANCE) {
                return null;
            }

            // 寻找距离受伤点最近的导弹武器
            WeaponAPI closestMissileWeapon = findClosestMissileWeapon(point);
            if (closestMissileWeapon == null) {
                return null;
            }

            // 触发导弹武器殉爆
            triggerMissileExplosion(closestMissileWeapon);

            return "moci_volatile_ammunition";
        }

        // 寻找距离指定点最近的导弹武器
        private WeaponAPI findClosestMissileWeapon(Vector2f damagePoint) {
            WeaponAPI closestWeapon = null;
            float closestDistance = Float.MAX_VALUE;

            List<WeaponAPI> weapons = ship.getAllWeapons();
            for (WeaponAPI weapon : weapons) {
                // 只考虑导弹武器
                if (!isMissileWeapon(weapon)) {
                    continue;
                }

                // 计算武器到受伤点的距离
                Vector2f weaponLocation = weapon.getLocation();
                if (weaponLocation == null) {
                    continue;
                }

                float distance = MathUtils.getDistance(weaponLocation, damagePoint);
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closestWeapon = weapon;
                }
            }

            return closestWeapon;
        }

        // 判断武器是否为导弹武器
        private boolean isMissileWeapon(WeaponAPI weapon) {
            if (weapon == null || weapon.getSpec() == null) {
                return false;
            }

            WeaponSpecAPI spec = weapon.getSpec();
            return spec.getType() == WeaponAPI.WeaponType.MISSILE;
        }

        // 触发导弹武器殉爆
        private void triggerMissileExplosion(WeaponAPI weapon) {
            if (weapon == null || weapon.getLocation() == null) {
                return;
            }

            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null) {
                return;
            }

            // 计算殉爆伤害（武器伤害的50%）
            float explosionDamage = calculateWeaponDamage(weapon) * MISSILE_EXPLOSION_DAMAGE_RATIO;

            // 随机殉爆范围（50-150）
            float explosionRadius = MIN_EXPLOSION_RADIUS +
                    random.nextFloat() * (MAX_EXPLOSION_RADIUS - MIN_EXPLOSION_RADIUS);

            Vector2f explosionLocation = new Vector2f(weapon.getLocation());

            // 创建带伤害的爆炸效果
            DamagingExplosionSpec explosionSpec = new DamagingExplosionSpec(
                    0.5f, // 持续时间
                    explosionRadius * 1.5f, // 爆炸半径
                    explosionRadius, // 核心半径
                    explosionDamage, // 最大伤害
                    explosionDamage * 0.3f, // 最小伤害
                    CollisionClass.PROJECTILE_FF,
                    CollisionClass.PROJECTILE_FF,
                    3f, // 粒子最小尺寸
                    5f, // 粒子尺寸范围
                    0.8f, // 粒子持续时间
                    80, // 粒子数量
                    Color.ORANGE, // 粒子颜色
                    new Color(255, 207, 121, 255) // 爆炸颜色
            );

            // 设置伤害类型为爆炸伤害
            explosionSpec.setDamageType(DamageType.HIGH_EXPLOSIVE);
            explosionSpec.setUseDetailedExplosion(true);
            explosionSpec.setSoundSetId("explosion_ship");

            // 生成带伤害的爆炸
            engine.spawnDamagingExplosion(explosionSpec, ship, explosionLocation);

            // 降低武器备弹（如果有的话）
            reduceWeaponAmmo(weapon);
        }

        // 计算武器伤害
        private float calculateWeaponDamage(WeaponAPI weapon) {
            if (weapon == null) {
                return 0f;
            }

            // 直接从武器获取伤害信息
            if (weapon.getDamage() != null) {
                return weapon.getDamage().getDamage();
            }

            // 如果无法获取伤害，返回一个合理的默认值
            return 100f;
        }

        // 降低武器备弹
        private void reduceWeaponAmmo(WeaponAPI weapon) {
            if (weapon == null || weapon.getAmmo() <= 0) {
                return;
            }

            // 减少1发备弹
            weapon.setAmmo(weapon.getAmmo() - 1);
        }

    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        switch (index) {
            case 0:
                return "+" + (int) ((DAMAGE_MULT - 1f) * 100) + "%";
            case 1:
                return "+" + (int) ((RADIUS_MULT - 1f) * 100) + "%";
            case 2:
                return (int) DAMAGE_THRESHOLD + "";
            case 3:
                return (int) (EXPLOSION_CHANCE * 100) + "%";
            case 4:
                return (int) (MISSILE_EXPLOSION_DAMAGE_RATIO * 100) + "%";
            case 5:
                return (int) MIN_EXPLOSION_RADIUS + "-" + (int) MAX_EXPLOSION_RADIUS;
            default:
                return null;
        }
    }
}