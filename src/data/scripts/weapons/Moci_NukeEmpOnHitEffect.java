package data.scripts.weapons;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lazywizard.lazylib.combat.entities.SimpleEntity;
import org.lwjgl.util.vector.Vector2f;

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

/**
 * Moci_NukeEmpOnHitEffect - 核弹级EMP弹药命中效果插件
 * 
 * 功能概述：
 * 这个插件实现了一个超强力的核弹级EMP范围伤害效果，当弹药命中目标时会：
 * 1. 对命中目标造成直接EMP伤害
 * 2. 对2500单位范围内的所有敌方舰船造成大范围EMP伤害
 * 3. 生成壮观的橙红色EMP爆炸视觉效果
 * 4. 播放相应的音效
 * 
 * 技术特点：
 * - 使用分层距离衰减算法计算范围伤害
 * - 友军伤害减免机制（25%友伤）
 * - 自定义渲染系统实现爆炸动画
 * - 大量装饰性EMP电弧效果增强视觉冲击（30条电弧）
 * - 橙红色主题色彩设计，营造核爆效果
 */
public class Moci_NukeEmpOnHitEffect implements OnHitEffectPlugin {

    // ==================== 效果参数配置 ====================
    
    /** EMP效果最大范围（单位：游戏单位） - 核弹级超大范围 */
    private static final float AREA_EFFECT = 2500f;
    
    /** EMP效果内圈范围，内圈内无衰减（单位：游戏单位） */
    private static final float AREA_EFFECT_INNER = 1250f;
    
    /** 友军伤害倍数（75%友伤） */
    private static final float FRIENDLY_FIRE_MULT = 0.75f;

    // ==================== 视觉效果配置 ====================
    
    /** EMP电弧主色调（橙红色 - 核爆风格） */
    private static final Color COLOR1 = new Color(255, 120, 50);
    
    /** EMP电弧高亮色（亮橙色） */
    private static final Color COLOR2 = new Color(255, 200, 100);

    /** 零向量常量，用于音效播放 */
    private static final Vector2f ZERO = new Vector2f();

    /**
     * 弹药命中效果主处理方法
     * 当核弹级EMP弹药命中任何目标时调用此方法
     * 
     * @param projectile 命中的弹药实体
     * @param target 被命中的目标（可能是舰船、小行星等）
     * @param point 命中点的世界坐标
     * @param shieldHit 是否命中护盾
     * @param damageResult 伤害应用结果
     * @param engine 战斗引擎实例
     */
    @Override
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point, boolean shieldHit, ApplyDamageResultAPI damageResult,
            CombatEngineAPI engine) {
        // 安全检查：确保目标和命中点有效
        if (target == null || point == null) {
            return;
        }
        
        // 从弹药中提取伤害数据
        float emp = projectile.getEmpAmount();    // EMP伤害值
        float dam = projectile.getDamageAmount(); // 物理伤害值

        // ==================== 范围目标搜索 ====================
        // 获取EMP效果范围内的所有舰船
        List<ShipAPI> targets = getShipsWithinRange(point, AREA_EFFECT);

        // 过滤被遮挡的目标（简化版本，核弹级EMP通常穿透所有遮挡）
        targets = filterVisibleTargets(target, point, targets);

        // ==================== 直接命中目标处理 ====================
        if (target instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) target;
            targets.remove(ship); // 从范围目标列表中移除直接命中的目标

            // 对直接命中目标生成主EMP电弧效果
            // 参数：发射者，起点，路径点，目标，伤害类型，物理伤害，EMP伤害，电弧持续时间，音效ID，厚度，起始色，结束色
            engine.spawnEmpArc(projectile.getSource(), point, null, target, DamageType.ENERGY, dam, emp, 100000f,
                    "tachyon_lance_emp_impact", 50f, COLOR1, COLOR2);
        }

        // ==================== 范围EMP伤害处理 ====================
        for (ShipAPI ship : targets) {
            // 计算目标距离爆炸中心的距离
            float distance = MathUtils.getDistance(ship.getLocation(), point);
            
            // 初始化伤害衰减系数
            float reduction = 1f;
            
            // 距离衰减算法：
            // 如果目标在内圈范围外，则应用线性距离衰减
            if (distance > AREA_EFFECT_INNER + ship.getCollisionRadius()) {
                // 线性衰减公式：(最大范围 - 距离/2) / (最大范围 - 内圈范围)
                reduction = (AREA_EFFECT - distance / 2f) / (AREA_EFFECT - AREA_EFFECT_INNER);
            }
            
            // 友军伤害减免：如果目标与发射者属于同一阵营，应用友伤倍数
            if (ship.getOwner() == projectile.getOwner()) {
                reduction *= FRIENDLY_FIRE_MULT;
            }

            // 生成范围EMP电弧效果，伤害按衰减系数计算
            engine.spawnEmpArc(projectile.getSource(), point, null, ship, DamageType.ENERGY, 
                    dam * reduction, emp * reduction, 800f, "tachyon_lance_emp_impact",
                    45f, COLOR1, COLOR2);
        }

        // ==================== 装饰性EMP电弧效果 ====================
        // 生成30条随机方向的装饰性电弧，增强核弹级视觉效果（原本3条的10倍）
        for (int i = 0; i < 30; i++) {
            // 在弹药位置周围更大范围随机生成电弧终点（适应核弹级范围）
            Vector2f location = new Vector2f(
                    projectile.getLocation().x + (float) Math.random() * 600.0f + 2000.0f,
                    projectile.getLocation().y
            );
            // 围绕弹药位置随机旋转电弧终点
            location = VectorUtils.rotateAroundPivot(location, projectile.getLocation(), 
                    (float) Math.random() * 360f, location);
            
            // 生成装饰性电弧（无伤害，仅视觉效果）
            engine.spawnEmpArc(projectile.getSource(), point, null, new SimpleEntity(location), 
                    DamageType.ENERGY, 0.0f, 0.0f, 120000f, "tachyon_lance_emp_impact", 
                    35f + (float) Math.random() * 15f, COLOR1, COLOR2);
        }

        // ==================== 爆炸视觉效果 ====================
        // 创建自定义核弹级EMP爆炸渲染效果
        // 参数：位置，亮度，半径，持续时间，旋转速度，核心起始尺寸比例，核心结束尺寸比例
        NukeEMPBlast empBlast = new NukeEMPBlast(point, 1.2f, AREA_EFFECT * 1.15f, 1.0f, 45f, 0.1f, 1.2f);
        empBlast.init(Global.getCombatEngine().addLayeredRenderingPlugin(empBlast));

        // ==================== 音效播放 ====================
        // 播放大型设备失效音效，增强沉浸感
        Global.getSoundPlayer().playSound("disabled_large", 1.0f, 0.6f, point, ZERO);
    }

    // ==================== 辅助方法 ====================
    
    /**
     * 获取指定范围内的所有舰船
     * 
     * @param center 搜索中心点
     * @param range 搜索范围
     * @return 范围内的舰船列表
     */
    private List<ShipAPI> getShipsWithinRange(Vector2f center, float range) {
        List<ShipAPI> result = new ArrayList<>();
        
        // 使用LazyLib的CombatUtils获取范围内所有舰船
        for (ShipAPI ship : CombatUtils.getShipsWithinRange(center, range)) {
            if (ship != null && ship.isAlive()) {
                result.add(ship);
            }
        }
        
        return result;
    }
    
    /**
     * 过滤可见目标（简化版本，核弹级EMP穿透所有遮挡）
     * 
     * @param origin 起始目标
     * @param point 参考点
     * @param targets 待过滤的目标列表
     * @return 过滤后的可见目标列表
     */
    private List<ShipAPI> filterVisibleTargets(CombatEntityAPI origin, Vector2f point, List<ShipAPI> targets) {
        // 核弹级EMP效果不受遮挡影响，直接返回所有目标
        return targets;
    }
    
    /**
     * 线性插值工具方法
     * 
     * @param start 起始值
     * @param end 结束值
     * @param t 插值参数（0-1）
     * @return 插值结果
     */
    private static float lerp(float start, float end, float t) {
        return start + t * (end - start);
    }

    /**
     * NukeEMPBlast - 核弹级EMP爆炸视觉效果渲染器
     * 
     * 这个内部类继承自BaseCombatLayeredRenderingPlugin，负责渲染核弹级EMP爆炸的视觉效果。
     * 
     * 技术特点：
     * 1. 双层贴图渲染：核心层和边缘层
     * 2. 动态尺寸变化：从小核心扩展到超大范围效果
     * 3. 旋转动画：核心和边缘以不同速度反向旋转
     * 4. Alpha淡出：随时间进行透明度衰减
     * 5. 可配置参数：支持自定义大小、持续时间、旋转速度等
     * 6. 橙红色主题：营造核爆风格的视觉效果
     */
    public static final class NukeEMPBlast extends BaseCombatLayeredRenderingPlugin {

        // ==================== 爆炸效果参数 ====================
        
        private final Vector2f point;                    // 爆炸中心点
        private final float brightness;                  // 初始亮度
        private final float radius;                      // 最大半径
        private final float duration;                    // 持续时间
        private final float rotationRate;                // 旋转速度（度/秒）
        private final float coreSizeFractionStart;       // 核心起始尺寸比例
        private final float coreSizeFractionEnd;         // 核心结束尺寸比例
        
        // ==================== 渲染资源 ====================
        
        private final SpriteAPI core;                    // 核心贴图
        private final SpriteAPI fringe;                  // 边缘贴图
        private final boolean flip;                      // 是否翻转旋转方向

        // ==================== 动画状态 ====================
        
        private float coreAngle;                         // 核心当前角度
        private float fringeAngle;                       // 边缘当前角度
        private float elapsed = 0f;                      // 已过时间

        /**
         * 核弹级EMP爆炸效果构造函数
         * 
         * @param point 爆炸中心点
         * @param brightness 初始亮度（0-1）
         * @param radius 最大爆炸半径
         * @param duration 爆炸持续时间（秒）
         * @param rotationRate 旋转速度（度/秒）
         * @param coreSizeFractionStart 核心起始尺寸比例（相对于半径）
         * @param coreSizeFractionEnd 核心结束尺寸比例（相对于半径）
         */
        @SuppressWarnings("LeakingThisInConstructor")
        public NukeEMPBlast(Vector2f point, float brightness, float radius, float duration, float rotationRate, 
                           float coreSizeFractionStart, float coreSizeFractionEnd) {
            // 保存爆炸参数
            this.point = new Vector2f(point);
            this.brightness = brightness;
            this.radius = radius;
            this.duration = duration;
            this.rotationRate = rotationRate;
            this.coreSizeFractionStart = coreSizeFractionStart;
            this.coreSizeFractionEnd = coreSizeFractionEnd;

            // 设置渲染层级：在舰船和导弹上方
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER;
            
            // 加载爆炸贴图资源
            core = Global.getSettings().getSprite("fx", "Moci_exp_core");      // 核心贴图
            fringe = Global.getSettings().getSprite("fx", "Moci_exp_fringe");  // 边缘贴图
            
            // 设置加法混合模式，产生发光效果
            core.setAdditiveBlend();
            fringe.setAdditiveBlend();
            
            // 随机决定旋转方向
            flip = Math.random() > 0.5;

            // 随机初始化旋转角度
            coreAngle = MathUtils.getRandomNumberInRange(-180f, 180f);
            fringeAngle = MathUtils.getRandomNumberInRange(-180f, 180f);
        }

        /**
         * 爆炸动画推进方法
         * 每帧调用，更新爆炸的动画状态
         * 
         * @param amount 时间增量（秒）
         */
        @Override
        public void advance(float amount) {
            // 只在游戏未暂停时推进动画
            if (!Global.getCombatEngine().isPaused()) {
                elapsed += amount;
            }

            // ==================== 旋转动画更新 ====================
            
            float sign = flip ? 1f : -1f;  // 旋转方向
            coreAngle += sign * amount * rotationRate;      // 核心旋转
            fringeAngle -= sign * amount * rotationRate;    // 边缘反向旋转
            
            // ==================== 尺寸和透明度计算 ====================
            
            // 计算动画进度（0-1）
            float level = Math.min(1f, Math.max(0f, elapsed / duration));
            
            // 核心尺寸：从小比例线性增长到大比例
            float coreSize = lerp(radius * coreSizeFractionStart, radius * coreSizeFractionEnd, level) * 2f;
            
            // 边缘尺寸：从0线性增长到最大半径
            float fringeSize = lerp(0f, radius, level) * 2f;
            
            // 透明度：使用幂函数进行非线性衰减，产生更自然的淡出效果
            float alpha = Math.min(1f, Math.max(0f, lerp(brightness, 0f, (float) Math.pow(level, 1.5))));

            // ==================== 应用渲染参数 ====================
            
            // 为核弹效果设置橙红色调
            Color coreColor = new Color(255, 120, 50, (int)(alpha * 255));
            Color fringeColor = new Color(255, 200, 100, (int)(alpha * 255));
            
            // 更新核心贴图参数
            core.setAlphaMult(alpha);     // 设置透明度
            core.setAngle(coreAngle);     // 设置旋转角度
            core.setSize(coreSize, coreSize);  // 设置尺寸
            core.setColor(coreColor);     // 设置橙红色调
            
            // 更新边缘贴图参数
            fringe.setAlphaMult(alpha);   // 设置透明度
            fringe.setAngle(fringeAngle); // 设置旋转角度
            fringe.setSize(fringeSize, fringeSize);  // 设置尺寸
            fringe.setColor(fringeColor); // 设置亮橙色调
        }

        /**
         * 渲染方法
         * 在指定的渲染层绘制爆炸效果
         * 
         * @param layer 当前渲染层
         * @param viewport 视口信息
         */
        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            // 在爆炸中心点渲染核心和边缘贴图
            core.renderAtCenter(point.x, point.y);      // 渲染核心层
            fringe.renderAtCenter(point.x, point.y);    // 渲染边缘层
        }

        /**
         * 获取渲染半径
         * 用于渲染系统的剔除优化
         * 
         * @return 渲染半径
         */
        @Override
        public float getRenderRadius() {
            return radius;
        }

        /**
         * 检查爆炸效果是否已过期
         * 
         * @return 如果动画时间超过持续时间则返回true
         */
        @Override
        public boolean isExpired() {
            return elapsed >= duration;
        }

        /**
         * 初始化方法
         * 设置渲染实体的位置并进行首次更新
         * 
         * @param entity 关联的战斗实体
         */
        @Override
        public void init(CombatEntityAPI entity) {
            this.entity = entity;
            entity.getLocation().set(point.x, point.y);  // 设置实体位置
            advance(0f);  // 进行首次动画更新
        }
    }
} 
