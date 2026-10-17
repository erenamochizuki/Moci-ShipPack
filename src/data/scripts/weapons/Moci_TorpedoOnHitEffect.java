package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.OnHitEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import data.scripts.util.Moci_Util;
import java.awt.Color;
import java.util.List;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.entities.SimpleEntity;
import org.lwjgl.util.vector.Vector2f;

/**
 * 鱼雷命中效果
 * 
 * 功能：
 * 1. 对直接命中目标造成大量幅能伤害
 * 2. 对周围区域内所有舰船造成EMP伤害
 * 3. 伤害随距离衰减，友军伤害降低
 * 4. 生成视觉特效（电弧、爆炸光环）
 * 
 * @author Moci
 */
public class Moci_TorpedoOnHitEffect implements OnHitEffectPlugin {

    // ==================== 配置参数 ====================
    
    /** 区域效果外圈半径 */
    private static final float AREA_EFFECT = 350f;
    
    /** 区域效果内圈半径（内圈范围内无衰减） */
    private static final float AREA_EFFECT_INNER = 150f;

    /** 爆炸颜色（浅蓝色半透明） */
    private static final Color EXPLOSION_COLOR = new Color(125, 200, 255, 155);
    
    /** 直接命中时增加的幅能值 */
    private static final float FLUX_DAMAGE = 1000f;
    
    /** 友军伤害倍率（25%） */
    private static final float FRIENDLY_FIRE_MULT = 0.25f;
    
    /** 粒子/电弧颜色（亮蓝色） */
    private static final Color PARTICLE_COLOR = new Color(150, 215, 255, 255);

    /** 爆心白光核心颜色 */
    private static final Color CORE_FLASH_COLOR = new Color(255, 255, 255, 255);

    /** 爆心白光边缘颜色 */
    private static final Color CORE_FLASH_FRINGE_COLOR = new Color(170, 225, 255, 220);
    
    /** 零向量（用于音效） */
    private static final Vector2f ZERO = new Vector2f();

    // ==================== 核心逻辑 ====================

    @Override
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point, 
                      boolean shieldHit, ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
        
        // 安全检查：命中点不能为空
        if (point == null) {
            return;
        }

        // 计算区域伤害值（使用弹药基础伤害的25%）
        float emp = projectile.getEmpAmount() * 0.25f;
        float dam = projectile.getDamageAmount() * 0.25f;

        // ==================== 第一步：查找范围内所有目标 ====================
        
        // 获取区域内所有舰船
        List<ShipAPI> targets = Moci_Util.getShipsWithinRange(point, AREA_EFFECT);

        // 过滤掉被遮挡的目标（视线检测）
        Moci_Util.filterObscuredTargets(target, point, targets, true, true, false);

        // ==================== 第二步：处理直接命中目标 ====================
        
        if (target instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) target;
            
            // 从范围伤害列表中移除（避免重复伤害）
            targets.remove(ship);

            // 增加幅能（考虑能量武器伤害加成）
            if (projectile.getSource() == null) {
                ship.getFluxTracker().increaseFlux(FLUX_DAMAGE, shieldHit);
            } else {
                float weaponDamageMult = projectile.getSource().getMutableStats()
                    .getEnergyWeaponDamageMult().getModifiedValue();
                ship.getFluxTracker().increaseFlux(FLUX_DAMAGE * weaponDamageMult, shieldHit);
            }
            
            // 生成4条电弧特效（直接命中效果）
            for (int x = 0; x < 4; x++) {
                ShipAPI empTarget = ship;
                engine.spawnEmpArc(
                    projectile.getSource(),  // 伤害来源
                    point,                   // 起始点
                    empTarget,               // 起始实体
                    empTarget,               // 目标实体
                    DamageType.ENERGY,       // 伤害类型
                    dam,                     // 伤害值
                    emp,                     // EMP伤害
                    100000f,                 // 最大范围
                    null,                    // 音效ID（null使用默认）
                    20f,                     // 电弧厚度
                    PARTICLE_COLOR,          // 核心颜色
                    EXPLOSION_COLOR          // 边缘颜色
                );
            }
        }

        // ==================== 第三步：处理范围内其他目标 ====================
        
        for (ShipAPI ship : targets) {
            // 计算距离
            float distance = MathUtils.getDistance(ship.getLocation(), point);
            
            // 计算伤害衰减系数
            float reduction = 1;
            
            // 如果在外圈范围（超过内圈半径+碰撞半径）
            if (distance > (AREA_EFFECT_INNER + ship.getCollisionRadius())) {
                // 线性衰减公式：从内圈边缘到外圈边缘，伤害从100%降到0%
                reduction = (AREA_EFFECT - distance / 2f) / (AREA_EFFECT - AREA_EFFECT_INNER);
            }
            
            // 友军伤害降低
            if (ship.getOwner() == projectile.getOwner()) {
                reduction *= FRIENDLY_FIRE_MULT;
            }

            // 对战机/无人机的特殊处理（单条电弧+3次直接伤害）
            if (ship.isFighter() || ship.isDrone()) {
                engine.spawnEmpArc(
                    projectile.getSource(), 
                    point, 
                    null,  // 起始实体为空
                    ship,
                    DamageType.ENERGY, 
                    dam * reduction, 
                    emp * reduction, 
                    500f,  // 范围
                    null, 
                    40f,   // 电弧厚度
                    PARTICLE_COLOR, 
                    EXPLOSION_COLOR
                );
                
                // 在目标碰撞范围内随机3个点造成直接伤害
                for (int x = 0; x < 3; x++) {
                    Vector2f point1 = MathUtils.getRandomPointInCircle(point, ship.getCollisionRadius());
                    engine.applyDamage(
                        ship, 
                        point1, 
                        dam * reduction,      // 伤害值
                        DamageType.ENERGY,    // 伤害类型
                        emp * reduction,      // EMP伤害
                        false,                // 不绕过护盾
                        false,                // 不是纯护盾伤害
                        projectile.getSource(), 
                        false                 // 不播放命中音效
                    );
                }
            } 
            // 对大型舰船的处理（4条电弧）
            else {
                for (int x = 0; x < 4; x++) {
                    engine.spawnEmpArc(
                        projectile.getSource(), 
                        point, 
                        null, 
                        ship,
                        DamageType.ENERGY, 
                        dam * reduction, 
                        emp * reduction, 
                        500f, 
                        null, 
                        40f,
                        PARTICLE_COLOR, 
                        EXPLOSION_COLOR
                    );
                }
            }
        }

        // ==================== 第四步：生成视觉特效 ====================
        
        // 生成EMP爆炸光环特效
        // 参数：位置, 淡入时间, 最大半径, 淡出时间, 厚度, 内圈比例, 外圈比例
        Moci_EMPBlast empBlast = new Moci_EMPBlast(point, 1.25f, AREA_EFFECT * 1.15f, 0.65f, 25f, 0.2f, 0.75f);
        empBlast.init(Global.getCombatEngine().addLayeredRenderingPlugin(empBlast));

        // 叠加一层短时的大号白光爆心，让命中瞬间更像核闪
        WhiteCoreFlash coreFlash = new WhiteCoreFlash(point, 1.15f, AREA_EFFECT * 0.9f, 0.75f, 40f, 0.2f, 0.8f);
        coreFlash.init(Global.getCombatEngine().addLayeredRenderingPlugin(coreFlash));

        // 播放音效（根据是否命中护盾调整音调和音量）
        if (shieldHit) {
            Global.getSoundPlayer().playSound("Moci_Heary_Hit", 1.1f, 1f, point, ZERO);
        } else {
            Global.getSoundPlayer().playSound("Moci_Heary_Hit", 0.9f, 0.9f, point, ZERO);
        }
        
        // 生成6条装饰性电弧（从爆炸中心向外辐射）
        for (int x = 0; x < 6; x++) {
            // 随机角度和距离
            float angle = (float) Math.random() * 360f;
            float distance = (float) Math.random() * 100f + 150f;
            
            // 计算外圈点
            Vector2f point1 = MathUtils.getPointOnCircumference(point, distance, angle);
            Vector2f point2 = new Vector2f(point);
            
            // 生成装饰电弧（无伤害）
            engine.spawnEmpArc(
                projectile.getSource(), 
                point1,                      // 起始点（外圈）
                new SimpleEntity(point1),    // 起始实体
                new SimpleEntity(point2),    // 目标实体（中心）
                DamageType.ENERGY, 
                0f,                          // 无伤害
                0f,                          // 无EMP
                1000f,                       // 范围
                null, 
                15f,                         // 电弧厚度
                EXPLOSION_COLOR,             // 核心颜色
                PARTICLE_COLOR               // 边缘颜色
            );
        }
    }

    /**
     * 命中瞬间的白核闪光。
     * 使用现有爆炸核心/边缘贴图做一个短时的加法混合爆心层。
     */
    private static final class WhiteCoreFlash extends BaseCombatLayeredRenderingPlugin {
        private final Vector2f point;
        private final float brightness;
        private final float radius;
        private final float duration;
        private final float rotationRate;
        private final float coreSizeFractionStart;
        private final float coreSizeFractionEnd;
        private final SpriteAPI core;
        private final SpriteAPI fringe;
        private final boolean flip;

        private float coreAngle;
        private float fringeAngle;
        private float elapsed = 0f;

        private WhiteCoreFlash(Vector2f point, float brightness, float radius, float duration,
                               float rotationRate, float coreSizeFractionStart, float coreSizeFractionEnd) {
            this.point = new Vector2f(point);
            this.brightness = brightness;
            this.radius = radius;
            this.duration = duration;
            this.rotationRate = rotationRate;
            this.coreSizeFractionStart = coreSizeFractionStart;
            this.coreSizeFractionEnd = coreSizeFractionEnd;

            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER;

            core = Global.getSettings().getSprite("fx", "Moci_exp_core");
            fringe = Global.getSettings().getSprite("fx", "Moci_exp_fringe");
            core.setAdditiveBlend();
            fringe.setAdditiveBlend();

            flip = Math.random() > 0.5;
            coreAngle = MathUtils.getRandomNumberInRange(-180f, 180f);
            fringeAngle = MathUtils.getRandomNumberInRange(-180f, 180f);
        }

        @Override
        public void advance(float amount) {
            if (!Global.getCombatEngine().isPaused()) {
                elapsed += amount;
            }

            float sign = flip ? 1f : -1f;
            coreAngle += sign * amount * rotationRate;
            fringeAngle -= sign * amount * rotationRate;

            float level = Math.min(1f, Math.max(0f, elapsed / duration));
            float coreSize = lerp(radius * coreSizeFractionStart, radius * coreSizeFractionEnd, level) * 2f;
            float fringeSize = coreSize * 2f;
            float alpha = Math.min(1f, Math.max(0f, lerp(brightness, 0f, (float) Math.pow(level, 1.3f))));

            core.setAlphaMult(alpha);
            core.setAngle(coreAngle);
            core.setSize(coreSize, coreSize);
            core.setColor(new Color(
                    CORE_FLASH_COLOR.getRed(),
                    CORE_FLASH_COLOR.getGreen(),
                    CORE_FLASH_COLOR.getBlue(),
                    Math.min(255, (int) (alpha * CORE_FLASH_COLOR.getAlpha()))
            ));

            fringe.setAlphaMult(alpha * 0.85f);
            fringe.setAngle(fringeAngle);
            fringe.setSize(fringeSize, fringeSize);
            fringe.setColor(new Color(
                    CORE_FLASH_FRINGE_COLOR.getRed(),
                    CORE_FLASH_FRINGE_COLOR.getGreen(),
                    CORE_FLASH_FRINGE_COLOR.getBlue(),
                    Math.min(255, (int) (alpha * CORE_FLASH_FRINGE_COLOR.getAlpha()))
            ));
        }

        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            core.renderAtCenter(point.x, point.y);
            fringe.renderAtCenter(point.x, point.y);
        }

        @Override
        public float getRenderRadius() {
            return radius;
        }

        @Override
        public boolean isExpired() {
            return elapsed >= duration;
        }

        @Override
        public void init(CombatEntityAPI entity) {
            this.entity = entity;
            entity.getLocation().set(point.x, point.y);
            advance(0f);
        }

        private static float lerp(float start, float end, float t) {
            return start + t * (end - start);
        }
    }
}
