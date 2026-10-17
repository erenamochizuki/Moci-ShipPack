package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.util.Pair;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector3f;
import org.boxutil.define.BoxEnum;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.attribute.NodeData;
import org.boxutil.units.standard.entity.FlareEntity;
import org.boxutil.units.standard.entity.SegmentEntity;
import org.boxutil.util.CalculateUtil;
import org.boxutil.util.CurveUtil;

import java.awt.Color;

/**
 * BoxUtil曲线光束武器效果
 * 演示如何使用CurveUtil创建带碰撞检测的曲线光束
 */
public class Moci_CurveBeamWeapon_Effect implements EveryFrameWeaponEffectPlugin {
    
    private boolean hasFired = false;
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine.isPaused()) {
            return;
        }
        
        ShipAPI ship = weapon.getShip();
        if (ship == null) {
            return;
        }
        
        // 检测武器开火
        if (weapon.isFiring() && !hasFired) {
            hasFired = true;
            spawnCurveBeam(engine, weapon, ship);
            spawnMuzzleFlare(engine, weapon);  // 添加枪口光晕
        }
        
        if (!weapon.isFiring()) {
            hasFired = false;
        }
    }
    
    /**
     * 生成枪口光晕特效
     */
    private void spawnMuzzleFlare(CombatEngineAPI engine, WeaponAPI weapon) {
        Vector2f muzzleLoc = weapon.getLocation();
        
        // 创建FlareEntity
        FlareEntity flare = new FlareEntity();
        
        // 尺寸设置 - 枪口光晕
        flare.setSize(50.0f, 50.0f);  // 宽度, 高度
        
        // 形状设置 - 平滑圆盘
        flare.setSmoothDisc();
        
        // 颜色设置 - 金黄色核心，红色边缘
        flare.setCoreColor(new Color(255, 210, 80, 255));
        flare.setFringeColor(new Color(255, 30, 80, 255));
        flare.setCoreAlpha(1.0f);
        flare.setFringeAlpha(0.6f);
        
        // 混合模式 - 加法混合产生发光效果
        flare.setAdditiveBlend();
        
        // 效果设置
        flare.setFlick(true);           // 启用闪烁
        flare.setFlickWhenPaused(false); // 暂停时不闪烁
        flare.setNoisePower(0.33f);     // 噪声强度
        flare.setGlowPower(0.8f);       // 发光强度
        
        // 渲染层级 - 在粒子层之上
        flare.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
        
        // 位置
        flare.setLocation(muzzleLoc);
        
        // 定时器 - 快速淡入，短暂持续，快速淡出
        flare.setGlobalTimer(0.05f, 0.1f, 0.15f);  // 淡入0.05s, 持续0.1s, 淡出0.15s
        
        // 添加到渲染管理器
        CombatRenderingManager.addEntity(flare);
    }
    
    /**
     * 生成曲线光束
     */
    private void spawnCurveBeam(CombatEngineAPI engine, WeaponAPI weapon, ShipAPI source) {
        // 曲线光束的所属船与伤害
        float damage = 100.0f;
        
        // 曲线姿态配置
        float range = 1200.0f;
        float facing = weapon.getCurrAngle();
        
        NodeData start = new NodeData();
        NodeData end = new NodeData();
        
        // 起始节点：右切线随机
        start.setTangentRight(
            MathUtils.getRandomNumberInRange(range * 0.1f, range * 0.3f), 
            0.0f
        );
        
        // 结束节点：位置和左切线
        end.setLocation(range, 0.0f);
        float t2 = range * 0.23f;
        end.setTangentLeft(
            -MathUtils.getRandomNumberInRange(0.0f, range * 0.7f),
            MathUtils.getRandomNumberInRange(-t2, t2)
        );
        
        // 曲线光束开火位置与朝向
        Vector2f fireLoc = weapon.getLocation();
        
        // 计算生成曲线光束
        Pair<SegmentEntity, Vector3f> pair = CurveUtil.spawnCurveBeam(
            engine,
            new Vector3f(fireLoc.x, fireLoc.y, facing),
            start,
            end,
            range + 64.0f,
            new BeamDealt(engine, source, damage)
        );
        
        if (pair == null || pair.one == null) {
            return;
        }
        
        // 调整曲线的视觉部分
        // 调整节点数据
        start = pair.one.getNodes().get(0);
        start.setEmissiveColor(new Color(255, 210, 80, 255));
        start.setWidth(24.0f);
        
        end = pair.one.getNodes().get(1);
        float curveT = pair.two == null ? 1.0f : pair.two.z;
        end.setColor(CalculateUtil.mix(Color.WHITE, new Color(240, 200, 255, 255), false, curveT));
        end.setEmissiveColor(CalculateUtil.mix(
            new Color(255, 210, 80, 255), 
            new Color(255, 30, 80, 255), 
            false, 
            curveT
        ));
        end.setWidth(CalculateUtil.mix(24.0f, 8.0f, curveT));
        
        // 渲染类的调整
        // 材质部分
        SpriteAPI coreSprite = Global.getSettings().getSprite("graphics/fx/beam_rough2_core.png");
        SpriteAPI fringeSprite = Global.getSettings().getSprite("graphics/fx/beam_rough2_fringe.png");
        
        pair.one.getMaterialData().setDiffuse(coreSprite);
        pair.one.getMaterialData().setEmissive(fringeSprite);
        pair.one.getMaterialData().setColorAlpha(0.75f);
        pair.one.getMaterialData().setEmissiveColorAlpha(0.8f);
        
        // 设置Color的透明度对Emissive影响为0
        pair.one.getMaterialData().setAlphaToEmissive(0.0f);
        
        // 主要方法部分
        pair.one.setInterpolation((short) 32);
        pair.one.setTexturePixels(256.0f);
        pair.one.setTextureSpeed(512.0f);
        pair.one.setAdditiveBlend();
        
        // 使光束两端平滑淡出
        float smoothFactor = CurveUtil.getCurveLength(start, end, 8);
        smoothFactor = Math.max(smoothFactor - 4.0f, 0.0f) / smoothFactor;
        pair.one.setFillStartAlpha(0.0f);
        pair.one.setFillStartFactor(smoothFactor);
        pair.one.setFillEndAlpha(0.0f);
        pair.one.setFillEndFactor(smoothFactor);
        
        // 提交节点数据
        pair.one.setNodeRefreshAllFromCurrentIndex();
        pair.one.submitNodes();
        
        // 设定渲染层级
        pair.one.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
        
        // 设置生命周期并加入渲染管理器
        pair.one.setGlobalTimer(0.05f, 0.4f, 0.2f);
        CombatRenderingManager.addEntity(pair.one);
    }
    
    /**
     * 控制曲线光束效果的部分
     */
    private static class BeamDealt implements CurveUtil.DealtController {
        private final CombatEngineAPI engine;
        private final ShipAPI source;
        private final float maxDamage;
        private final float minDamage;
        private final Color coreColor;
        private final Color fringeColor;
        private final float hitParticleDuration;
        private final float hitParticleSize;
        private final float hitParticleSize2;
        
        public BeamDealt(CombatEngineAPI engine, ShipAPI source, float damage) {
            this.engine = engine;
            this.source = source;
            this.maxDamage = damage;
            this.minDamage = this.maxDamage * 0.5f;
            
            // 命中特效配置
            this.coreColor = new Color(255, 210, 80, 255);
            this.fringeColor = new Color(255, 30, 80, 255);
            this.hitParticleDuration = 0.9f;  // (0.4 + 0.2) * 1.5
            this.hitParticleSize = 24.0f * 3.0f;  // width * 3
            this.hitParticleSize2 = this.hitParticleSize * 2.0f;  // width * 6
        }
        
        /**
         * 根据击中点对应的beamT造成浮动伤害，越近越高，100% -> 50%
         */
        @Override
        public void applyEffect(CombatEntityAPI combatEntityAPI, Vector2f point, float beamT, boolean isShieldHit) {
            // 应用伤害
            float damage = CalculateUtil.mix(this.maxDamage, this.minDamage, beamT);
            this.engine.applyDamage(
                combatEntityAPI, 
                point, 
                damage, 
                DamageType.ENERGY, 
                0.0f, 
                false, 
                true, 
                this.source,
                true
            );
            
            // 添加命中粒子特效（双层）
            // 外层 - 边缘色，较大，较透明
            this.engine.addHitParticle(
                point, 
                new Vector2f(), 
                this.hitParticleSize2, 
                0.6666667f, 
                this.hitParticleDuration, 
                this.fringeColor
            );
            
            // 内层 - 核心色，较小，不透明
            this.engine.addHitParticle(
                point, 
                new Vector2f(), 
                this.hitParticleSize, 
                1.0f, 
                this.hitParticleDuration, 
                this.coreColor
            );
            
            // 添加飞溅粒子效果
            spawnSplashParticles(point, combatEntityAPI);
        }
        
        /**
         * 生成飞溅粒子效果
         */
        private void spawnSplashParticles(Vector2f hitPoint, CombatEntityAPI target) {
            // 计算飞溅方向（远离目标中心）
            Vector2f splashDir = new Vector2f();
            if (target != null && target.getLocation() != null) {
                Vector2f.sub(hitPoint, target.getLocation(), splashDir);
                float length = splashDir.length();
                if (length > 0) {
                    splashDir.scale(1.0f / length);  // 归一化
                } else {
                    // 如果击中点就是中心，随机方向
                    float angle = (float) (Math.random() * Math.PI * 2.0);
                    splashDir.x = (float) Math.cos(angle);
                    splashDir.y = (float) Math.sin(angle);
                }
            } else {
                // 没有目标，随机方向
                float angle = (float) (Math.random() * Math.PI * 2.0);
                splashDir.x = (float) Math.cos(angle);
                splashDir.y = (float) Math.sin(angle);
            }
            
            // 生成8条飞溅线
            int splashCount = 8;
            float angleSpread = 60.0f;  // 扇形角度范围
            float baseAngle = (float) Math.atan2(splashDir.y, splashDir.x);
            
            for (int i = 0; i < splashCount; i++) {
                // 计算每条线的角度
                float angleOffset = (i - splashCount * 0.5f) / splashCount * angleSpread;
                float angle = baseAngle + (float) Math.toRadians(angleOffset);
                
                // 随机长度
                float length = 30.0f + (float) Math.random() * 40.0f;  // 30-70像素
                
                // 计算终点
                Vector2f endPoint = new Vector2f(
                    hitPoint.x + (float) Math.cos(angle) * length,
                    hitPoint.y + (float) Math.sin(angle) * length
                );
                
                // 创建飞溅线段
                SegmentEntity splash = new SegmentEntity();
                
                // 添加两个节点（起点和终点）
                NodeData startNode = new NodeData(hitPoint);
                startNode.setColor(this.coreColor);
                startNode.setEmissiveColor(this.coreColor);
                startNode.setWidth(4.0f);
                
                NodeData endNode = new NodeData(endPoint);
                endNode.setColor(this.fringeColor);
                endNode.setEmissiveColor(this.fringeColor);
                endNode.setWidth(1.0f);
                
                splash.addNode(startNode);
                splash.addNode(endNode);
                
                // 材质设置
                SpriteAPI sprite = Global.getSettings().getSprite("graphics/fx/beam_rough2_core.png");
                splash.getMaterialData().setDiffuse(sprite);
                splash.getMaterialData().setEmissive(sprite);
                splash.getMaterialData().setColorAlpha(0.8f);
                splash.getMaterialData().setEmissiveColorAlpha(0.9f);
                
                // 渲染设置
                splash.setAdditiveBlend();
                splash.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
                
                // 提交节点
                splash.setNodeRefreshAllFromCurrentIndex();
                splash.submitNodes();
                
                // 生命周期 - 快速淡出
                splash.setGlobalTimer(0.0f, 0.1f, 0.2f);
                
                // 添加到渲染管理器
                CombatRenderingManager.addEntity(splash);
            }
        }
        
        /**
         * 剔除不应该被波及的目标
         */
        @Override
        public boolean isIgnore(CombatEntityAPI target) {
            boolean isFighter = false;
            boolean parentIsSource = false;
            boolean inPhased = false;
            
            if (target instanceof ShipAPI) {
                ShipAPI shipTarget = (ShipAPI) target;
                isFighter = shipTarget.isFighter();
                parentIsSource = shipTarget.getParentStation() == this.source;
                inPhased = shipTarget.isPhased();
            }
            
            return target == this.source 
                || target.getCollisionClass() == CollisionClass.NONE 
                || inPhased 
                || (target instanceof MissileAPI || isFighter || parentIsSource) 
                    && target.getOwner() == source.getOwner();
        }
        
        /**
         * 设定永不穿过护盾
         */
        @Override
        public boolean isPierceShield(ShipAPI target) {
            return false;
        }
        
        /**
         * 设定曲线光束可以穿过 陨石、导弹、战机
         */
        @Override
        public boolean isPierce(CombatEntityAPI target, Vector2f vector2f, float beamT, boolean isShieldHit) {
            boolean isFighter = target instanceof ShipAPI && ((ShipAPI) target).isFighter();
            return target instanceof CombatAsteroidAPI 
                || target instanceof MissileAPI 
                || isFighter;
        }
    }
}
