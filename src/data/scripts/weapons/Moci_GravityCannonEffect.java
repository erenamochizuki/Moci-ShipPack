package data.scripts.weapons;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.OnHitEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;

import data.scripts.weapons.render.Moci_SingularityRenderer;
import org.apache.log4j.Logger;

/**
 * 引力炮效果实现。
 * - 负责在开火时注册/配置奇点渲染器，并为每枚投射物附加充能/坍塌期行为插件
 * - 主要的引力牵引、潮汐伤害与可视效果由 {@link Moci_GravityCannonChargeGlow} 协同 {@link data.scripts.weapons.render.Moci_SingularityRenderer} 执行
 */
public class Moci_GravityCannonEffect implements OnFireEffectPlugin, OnHitEffectPlugin, EveryFrameWeaponEffectPlugin {
    private static final Logger LOG = Global.getLogger(Moci_GravityCannonEffect.class);
    
    // 武器实例
    private WeaponAPI weapon;
    private boolean initialized = false;
    
    protected CombatEntityAPI chargeGlowEntity;
    protected Moci_GravityCannonChargeGlow chargeGlowPlugin;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) return;
        
        if (!initialized) {
            this.weapon = weapon;
            initialized = true;
            LOG.info("[MOCI_SINGULARITY] effect initialized for weapon id=" + weapon.getId());
        }
        
        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) return;

        // 充能阶段不附加可视插件；在 onFire 为每枚投射物创建独立插件
    }

    @Override
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point, boolean shieldHit, ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
        // 命中时不额外触发效果；伤害与视觉由 ChargeGlow/渲染器处理
    }

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        // 接入通用奇点渲染器：注册该投射物以渲染事件视界与引力透镜
        Moci_SingularityRenderer renderer = Moci_SingularityRenderer.getOrCreate();
        if (renderer != null) {
            // 若此前仍在坍塌收尾的老条目未清理完，先不重复track
            if (!renderer.isTracked(projectile)) {
                renderer.trackProjectile(projectile);
            }
            // 初始半径10px，每秒增长2px（像素制）
            renderer.configurePixelRadius(projectile, 10f, 2f);
            LOG.info("[MOCI_SINGULARITY] projectile fired & tracked id=" + projectile.getProjectileSpecId());
        }

        // 为每枚投射物创建独立的 Glow 插件，避免只对第一个投射物生效
        Moci_GravityCannonChargeGlow glow = new Moci_GravityCannonChargeGlow(weapon);
        engine.addLayeredRenderingPlugin(glow);
        glow.attachToProjectile(projectile);
        if (projectile instanceof MissileAPI) {
            MissileAPI missile = (MissileAPI)projectile;
            missile.setMine(true);
            missile.setNoMineFFConcerns(true);
            missile.setMineExplosionRange(Moci_GravityCannonChargeGlow.MAX_ARC_RANGE + 50.0F);
            missile.setMinePrimed(true);
            missile.setUntilMineExplosion(0.0F);
        }
    }
} 