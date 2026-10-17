package data.scripts.weapons;

import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.util.Misc;
import java.awt.Color;

public class Moci_heavry_vulcan_OnHitEffect implements OnHitEffectPlugin {

    // 范围伤害参数
    private static final float FULL_DAMAGE_RADIUS = 50f;
    private static final float FALLOFF_RADIUS = 75f;
    private static final float DAMAGE_MULTIPLIER = 1f;

    // 视觉效果参数
    private static final Color EXPLOSION_COLOR = new Color(255, 100, 50, 255);
    private static final float EXPLOSION_DURATION = 0.5f;
    private static final float EXPLOSION_SIZE = 100f;
    private static final String SOUND_ID = "hit_heavy";

    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target,
                      Vector2f point, boolean shieldHit, ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {

        // 射程检测
        boolean outOfRange = false;
        if (projectile.getWeapon() != null) {
            float range = projectile.getWeapon().getRange();
            float distance = Misc.getDistance(projectile.getSource().getLocation(), point);
            outOfRange = distance > range;
        }

        // 伤害触发条件：命中护盾或超出射程，且目标是飞船或导弹
        if ((shieldHit || outOfRange) &&
                (target instanceof ShipAPI || target instanceof MissileAPI)) {

            float damage = projectile.getDamageAmount();

            // 对主目标造成伤害
            engine.applyDamage(
                    target,
                    point,
                    damage,
                    DamageType.FRAGMENTATION,
                    0f,
                    false,
                    false,
                    projectile.getSource()
            );

            // 爆炸视觉效果
            engine.spawnExplosion(
                    point,
                    new Vector2f(),
                    EXPLOSION_COLOR,
                    EXPLOSION_SIZE,
                    EXPLOSION_DURATION
            );

            // 粒子效果
            engine.addNebulaParticle(
                    point,
                    new Vector2f(),
                    EXPLOSION_SIZE * 0.5f,
                    2f,
                    0.1f,
                    0f,
                    EXPLOSION_DURATION * 2f,
                    EXPLOSION_COLOR
            );

            // 播放音效
            Global.getSoundPlayer().playSound(
                    SOUND_ID,
                    1f,
                    1f,
                    point,
                    new Vector2f()
            );

            // 范围伤害效果 - 处理飞船
            for (ShipAPI ship : engine.getShips()) {
                if (ship.isHulk()) continue;
                if (ship == target) continue;

                float dist = Misc.getDistance(point, ship.getLocation());
                if (dist < FALLOFF_RADIUS) {
                    float damageMult = DAMAGE_MULTIPLIER;
                    if (dist > FULL_DAMAGE_RADIUS) {
                        damageMult *= (1f - (dist - FULL_DAMAGE_RADIUS) / (FALLOFF_RADIUS - FULL_DAMAGE_RADIUS));
                    }

                    engine.applyDamage(
                            ship,
                            ship.getLocation(),
                            damage * damageMult,
                            DamageType.FRAGMENTATION,
                            0f,
                            false,
                            false,
                            projectile.getSource()
                    );
                }
            }

            // 范围伤害效果 - 处理导弹
            for (MissileAPI missile : engine.getMissiles()) {
                if (missile == target) continue;

                float dist = Misc.getDistance(point, missile.getLocation());
                if (dist < FALLOFF_RADIUS) {
                    float damageMult = DAMAGE_MULTIPLIER;
                    if (dist > FULL_DAMAGE_RADIUS) {
                        damageMult *= (1f - (dist - FULL_DAMAGE_RADIUS) / (FALLOFF_RADIUS - FULL_DAMAGE_RADIUS));
                    }

                    engine.applyDamage(
                            missile,
                            missile.getLocation(),
                            damage * damageMult,
                            DamageType.FRAGMENTATION,
                            0f,
                            false,
                            false,
                            projectile.getSource()
                    );
                }
            }
        }
    }
}