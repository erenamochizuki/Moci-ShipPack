package data.scripts.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.boxutil.base.BaseControlData;
import org.boxutil.base.SimpleParticleControlData;
import org.boxutil.base.api.RenderDataAPI;
import org.boxutil.base.api.InstanceDataAPI;
import org.boxutil.define.BoxDatabase;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.SpriteEntity;
import org.boxutil.util.CommonUtil;
import org.boxutil.util.concurrent.SpinLock;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentMap;

/**
 * BoxUtil 工具类
 */
public class Moci_BoxBasedUtil {

    public static final String KEY_PREFIX = Moci_BoxBasedUtil.class.getSimpleName();
    public static final Random RANDOM = new Random();
    public static final Color EMPTY = new Color(0, 0, 0, 0);
    public static final Vector2f ZERO = new Vector2f();
    // 统一的近实色alpha阈值（250/255）：低于该值的像素不参与轮廓提取
    public static final float SOLID_ALPHA_THRESHOLD = 0.8f;

    public static final int DEFAULT_MAX_PARTICLES = 4096;
    public static final float DEFAULT_MAX_DURATION = 3.2f;
    public static final float DEFAULT_DATA_DUR = -5120f;
    private static final String PARTICLE_LOCK_SUFFIX = "_SyncLock";
    private static final String PARTICLE_ENTITY_SUFFIX = "_RenderEntity";

    /**
     * 添加单个粒子
     */
    public static SpriteEntity addSingleParticle(String key, Vector2f loc, Vector2f vel, float size, float brightness, 
                                                  float in, float full, float out, Color color, String sprite, 
                                                  float facing, float turnRate, float growth) {

        String particleKey = buildParticleKey(key, sprite);
        ParticleControllerRef controllerRef = getOrCreateParticleController(particleKey, sprite);

        if (controllerRef.controller == null || controllerRef.entity == null) {
            return null;
        }

        color = scaleAlpha(color == null ? Color.WHITE : color, brightness);
        size *= 0.5f;
        controllerRef.controller.addParticle(
            loc, facing, turnRate, vel,
            new Vector2f(size, size),
            new Vector2f(growth, growth),
            color, EMPTY, in, full, out
        );

        return controllerRef.entity;
    }

    private static String buildParticleKey(String key, String sprite) {
        if (key == null) {
            return KEY_PREFIX + sprite;
        }
        return KEY_PREFIX + key + sprite;
    }

    private static ParticleControllerRef getOrCreateParticleController(String key, String spritePath) {
        ConcurrentMap<String, Object> customData = CombatRenderingManager.getCustomData();
        if (customData == null) {
            return ParticleControllerRef.EMPTY;
        }

        SpinLock lock = getControllerLock(customData, key);
        lock.lock();
        try {
            Object controllerObj = customData.get(key);
            SimpleParticleControlData controller = controllerObj instanceof SimpleParticleControlData
                ? (SimpleParticleControlData) controllerObj : null;

            Object entityObj = customData.get(key + PARTICLE_ENTITY_SUFFIX);
            SpriteEntity spriteEntity = entityObj instanceof SpriteEntity ? (SpriteEntity) entityObj : null;

            if (controller == null || controller.isEntityExpired() || spriteEntity == null) {
                controller = new SimpleParticleControlData(
                    DEFAULT_MAX_PARTICLES,
                    DEFAULT_MAX_DURATION,
                    DEFAULT_DATA_DUR,
                    false,
                    false
                );

                spriteEntity = buildParticleSpriteEntity(spritePath, controller);
                CombatRenderingManager.addEntity(spriteEntity);

                customData.put(key, controller);
                customData.put(key + PARTICLE_ENTITY_SUFFIX, spriteEntity);

                CombatEngineAPI engine = Global.getCombatEngine();
                if (engine != null) {
                    engine.getCustomData().put(key, spriteEntity);
                }
            }

            return new ParticleControllerRef(controller, spriteEntity);
        } finally {
            lock.unlock();
        }
    }

    private static SpinLock getControllerLock(ConcurrentMap<String, Object> customData, String key) {
        Object lockObj = customData.computeIfAbsent(key + PARTICLE_LOCK_SUFFIX, k -> new SpinLock());
        if (lockObj instanceof SpinLock) {
            return (SpinLock) lockObj;
        }
        SpinLock fallback = new SpinLock();
        customData.put(key + PARTICLE_LOCK_SUFFIX, fallback);
        return fallback;
    }

    private static SpriteEntity buildParticleSpriteEntity(String spritePath, SimpleParticleControlData controller) {
        SpriteAPI emissiveSprite = Global.getSettings().getSprite(spritePath);
        SpriteEntity spriteEntity = new SpriteEntity();

        // 对齐TRC做法：使用发光纹理做粒子并忽略光照
        spriteEntity.setDiffuseSprite(BoxDatabase.BUtil_NONE);
        spriteEntity.setEmissiveSprite(emissiveSprite);
        spriteEntity.getMaterialData().setColorToEmissive(0f);
        spriteEntity.getMaterialData().setAlphaToEmissive(0f);
        spriteEntity.getMaterialData().setIgnoreIllumination(true);
        spriteEntity.setAdditiveBlend();

        spriteEntity.setUVStart(emissiveSprite.getTexX(), emissiveSprite.getTexY());
        spriteEntity.setUVEnd(emissiveSprite.getTexWidth(), emissiveSprite.getTexHeight());
        spriteEntity.setBaseSizePerTiles(0.5f, 0.5f);
        spriteEntity.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
        spriteEntity.setControlData(controller);
        return spriteEntity;
    }

    private static final class ParticleControllerRef {
        private static final ParticleControllerRef EMPTY = new ParticleControllerRef(null, null);
        final SimpleParticleControlData controller;
        final SpriteEntity entity;

        private ParticleControllerRef(SimpleParticleControlData controller, SpriteEntity entity) {
            this.controller = controller;
            this.entity = entity;
        }
    }

    /**
     * 添加星云平滑粒子
     */
    public static SpriteEntity addNebulaSmoothParticle(Vector2f loc, Vector2f vel, float size, float endSizeMult, 
                                                        float rampUpFraction, float fullBrightnessFraction, 
                                                        float totalDuration, Color color) {

        float in = Math.max(totalDuration * rampUpFraction, 0f);
        float out = Math.max(totalDuration * (1f - rampUpFraction), 0f);

        SpriteEntity spriteEntity = addSingleParticle(null, loc, vel, size, fullBrightnessFraction, in, 0f, out, color, 
            "graphics/fx/cleaner_clouds00.png", RANDOM.nextFloat() * 360f, 0f, 
            endSizeMultToInstanceGrowth(size, endSizeMult, totalDuration));
        if (spriteEntity != null) {
            spriteEntity.setTileSize(2, 2);
            spriteEntity.setRandomTile(true);
            spriteEntity.setRandomTileEachInstance(true);
        }

        return spriteEntity;
    }

    /**
     * 计算实例增长率
     */
    public static float endSizeMultToInstanceGrowth(float scale, float endSizeMult, float totalDuration) {
        return scale * (endSizeMult - 1f) / totalDuration;
    }

    /**
     * 缩放颜色透明度
     * 
     * 优化版本的scaleAlpha方法，相比Starsector原生Misc.scaleAlpha()：
     * - 性能更好：mult=1.0时直接返回原对象（零开销）
     * - 更安全：有完整的边界检查，防止无效颜色值
     * - 代码质量更高：逻辑清晰，没有无意义的操作
     * 
     * @param color 原始颜色
     * @param mult 透明度倍数（0.0-1.0+）
     * @return 缩放后的颜色
     */
    public static Color scaleAlpha(Color color, float mult) {
        if (mult >= 1f) return color;
        if (mult <= 0f) return new Color(color.getRed(), color.getGreen(), color.getBlue(), 0);
        int alpha = (int) (color.getAlpha() * mult);
        if (alpha > 255) alpha = 255;
        if (alpha < 0) alpha = 0;
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /**
     * 创建通用矩形VAO
     */
    public static final byte[] BASIC_VERTICES = new byte[]{-128, -128, 127, -128, -128, 127, 127, 127};

    public static int[] createUniversalRectVAO() {

        int vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);

        // vbo create and done
        int vboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, CommonUtil.createByteBuffer(BASIC_VERTICES), GL15.GL_STATIC_DRAW);

        // vao config
        // 2 number for a point
        byte size = 2;
        // layout (location = 0) is 2 number of byte, which is a point
        GL20.glVertexAttribPointer(0, size, GL11.GL_BYTE, true, 2, 0);
        GL20.glEnableVertexAttribArray(0);

        // after build
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);

        return new int[]{vaoId, vboId};
    }

    /**
     * 创建锐利光斑效果
     * 参考PLSP_BoxBasedUtil的实现
     * 
     * @param location 光斑位置
     * @param facing 光斑朝向
     * @param width 光斑宽度
     * @param height 光斑高度
     * @param coreColor 核心颜色
     * @param fringeColor 边缘颜色
     * @param in 淡入时间
     * @param full 保持时间
     * @param out 淡出时间
     * @param glowPower 光晕强度
     * @param noisePower 噪声强度
     * @return FlareEntity实体
     */
    public static org.boxutil.units.standard.entity.FlareEntity addSharpFlare(
            Vector2f location, float facing, float width, float height,
            Color coreColor, Color fringeColor,
            float in, float full, float out,
            float glowPower, float noisePower) {

        org.boxutil.units.standard.entity.FlareEntity flareEntity = 
                new org.boxutil.units.standard.entity.FlareEntity();
        flareEntity.setLocation(location);
        flareEntity.setSize(width, height);
        flareEntity.setFacingScale(org.lazywizard.lazylib.MathUtils.clampAngle(facing), 1f, 1f);

        flareEntity.setCoreColor(coreColor);
        flareEntity.setFringeColor(fringeColor);
        flareEntity.setAdditiveBlend();

        // 关键设置：使用SharpDisc而不是SmoothDisc
        flareEntity.setSharpDisc();
        flareEntity.autoAspect();
        flareEntity.setNoisePower(noisePower);
        flareEntity.setGlowPower(glowPower);
        flareEntity.setGlobalTimer(in, full, out);

        // 设置渲染层级
        flareEntity.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);

        // 更新：移除BoxEnum参数
        org.boxutil.manager.CombatRenderingManager.addEntity(flareEntity);

        return flareEntity;
    }

    /**
     * 添加精灵到目标实体
     * 
     * @param sprite 精灵图
     * @param target 目标实体
     * @param in 淡入时间
     * @param full 保持时间
     * @param out 淡出时间
     * @param layer 渲染层级
     * @return SpriteEntity实体
     */
    public static SpriteEntity addSpriteToTarget(
            SpriteAPI sprite, CombatEntityAPI target, 
            float in, float full, float out, 
            CombatEngineLayers layer) {

        SpriteEntity spriteEntity = new SpriteEntity(sprite);
        spriteEntity.setControlData(new EntityBasedControlData(target));
        spriteEntity.setLayer(layer);
        spriteEntity.setGlobalTimer(in, full, out);
        
        CombatRenderingManager.addEntity(spriteEntity);

        return spriteEntity;
    }

    /**
     * 添加基础实例数据
     * 
     * @param spriteEntity 精灵实体
     * @param in 淡入时间
     * @param full 保持时间
     * @param out 淡出时间
     * @param facing 朝向
     * @param turnRate 旋转速度
     * @param growth 增长速度
     * @return Instance2Data实例
     */
    public static org.boxutil.units.standard.attribute.Instance2Data addBasicInstanceData(
            SpriteEntity spriteEntity, 
            float in, float full, float out, 
            float facing, float turnRate, float growth) {

        List<InstanceDataAPI> particleList = new ArrayList<>();
        org.boxutil.units.standard.attribute.Instance2Data data = 
                new org.boxutil.units.standard.attribute.Instance2Data();
        data.setFacing(facing);
        data.setTurnRate(turnRate);
        data.setScale(1, 1);
        data.setScaleRate(growth, growth);
        data.setTimer(in, full, out);
        particleList.add(data);

        spriteEntity.setInstanceData(particleList);
        spriteEntity.setInstanceDataRefreshAllFromCurrentIndex();
        spriteEntity.submitInstanceData();
        spriteEntity.setRenderingCount(1);
        spriteEntity.setAlwaysRefreshInstanceData(true);

        return data;
    }

    /**
     * 添加空间扭曲效果
     * 
     * @param location 位置
     * @param range 范围
     * @param intensity 强度
     * @param in 淡入时间
     * @param full 保持时间
     * @param out 淡出时间
     * @param fadeOut 淡出时间（重复参数，保持兼容性）
     */
    public static void addDistortion(Vector2f location, float range, 
                                     float intensity, float in, float full, 
                                     float out, float fadeOut) {
        // 使用原版API添加扭曲效果
        Global.getCombatEngine().addSwirlyNebulaParticle(
            location, 
            ZERO, 
            range, 
            2f, // 持续时间倍数
            0.5f, // 增长速度
            0.5f, // 旋转速度
            in + full + out, 
            new Color(100, 100, 100, (int)(intensity * 255)), 
            true
        );
    }

    /**
     * 创建爆发实例数据
     * 用于创建向外爆发的粒子效果
     * 
     * @param count 粒子数量
     * @param range 范围
     * @param rangeRandom 范围随机值（未使用）
     * @param angle 角度
     * @param speed 速度（未使用）
     * @param size 大小
     * @param sizeMult 大小倍数
     * @param colors 颜色数组
     * @param in 淡入时间
     * @param out 淡出时间
     * @return 实例数据列表
     */
    public static List<InstanceDataAPI> createBurstOutInstanceData(
            int count, float[] range, Object rangeRandom, 
            float[] angle, float[] speed, float[] size, 
            float[] sizeMult, Color[] colors, 
            float in, float out) {

        List<InstanceDataAPI> result = new ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            org.boxutil.units.standard.attribute.Instance2Data data = 
                    new org.boxutil.units.standard.attribute.Instance2Data();
            
            // 随机角度
            float particleAngle = (angle != null && angle.length > 0) 
                    ? angle[0] + RANDOM.nextFloat() * (angle.length > 1 ? angle[1] : 360f)
                    : RANDOM.nextFloat() * 360f;
            
            // 随机大小
            float particleSize = (size != null && size.length > 0)
                    ? size[0] + RANDOM.nextFloat() * (size.length > 1 ? (size[1] - size[0]) : 0f)
                    : 1f;
            
            // 随机大小倍数
            float particleSizeMult = (sizeMult != null && sizeMult.length > 0)
                    ? sizeMult[0] + RANDOM.nextFloat() * (sizeMult.length > 1 ? (sizeMult[1] - sizeMult[0]) : 0f)
                    : 1f;
            
            data.setFacing(particleAngle);
            data.setScale(particleSize, particleSize);
            data.setScaleRate(particleSize * (particleSizeMult - 1f) / (in + out), 
                            particleSize * (particleSizeMult - 1f) / (in + out));
            data.setTimer(in, 0f, out);
            
            // 随机颜色 - 更新：使用setColor而不是setLowColor/setHighColor
            if (colors != null && colors.length > 0) {
                Color color = colors[RANDOM.nextInt(colors.length)];
                data.setColor(color);  // 这会同时设置low和high颜色
            }
            
            result.add(data);
        }
        
        return result;
    }

    /**
     * 添加星云粒子
     * 
     * @param instanceData 实例数据列表
     * @param location 位置
     * @param size 大小
     * @param brightness 亮度
     * @param in 淡入时间
     * @param out 淡出时间
     * @param color 颜色
     * @return SpriteEntity实体
     */
    public static SpriteEntity addNebulaParticle(
            List<InstanceDataAPI> instanceData, 
            Vector2f location, float size, float brightness, 
            float in, float out, Color color) {

        SpriteEntity spriteEntity = new SpriteEntity("graphics/fx/cleaner_clouds00.png");
        spriteEntity.getMaterialData().setColor(color);
        spriteEntity.getMaterialData().setColorAlpha(brightness);
        spriteEntity.getMaterialData().setAlphaToEmissive(0f);

        spriteEntity.setLocation(location);
        spriteEntity.setBaseSizePerTiles(size * 0.5f, size * 0.5f);
        spriteEntity.setAdditiveBlend();
        spriteEntity.setTileSize(2, 2);
        spriteEntity.setRandomTile(true);
        spriteEntity.setRandomTileEachInstance(true);

        spriteEntity.setInstanceData(instanceData);
        spriteEntity.setInstanceDataRefreshAllFromCurrentIndex();
        spriteEntity.submitInstanceData();
        spriteEntity.setRenderingCount(instanceData.size());
        spriteEntity.setAlwaysRefreshInstanceData(true);

        spriteEntity.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
        spriteEntity.setGlobalTimer(in, 0f, out);
        
        // 更新：移除BoxEnum参数
        CombatRenderingManager.addEntity(spriteEntity);

        return spriteEntity;
    }

    /**
     * 将渐变转换为计时器
     * 
     * @param in 淡入时间
     * @param out 淡出时间
     * @return 时间数组 [in, out]
     */
    public static float[] rampToTimer(float in, float out) {
        return new float[]{in, out};
    }

    /**
     * SDF纹理缓存
     * 避免重复生成相同的SDF纹理
     */
    private static final int MAX_SDF_CACHE_SIZE = 256;

    public static final Map<String, SdfRecord> SAVED_SDF = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SdfRecord> eldest) {
            if (size() > MAX_SDF_CACHE_SIZE) {
                releaseSdfRecord(eldest.getValue());
                return true;
            }
            return false;
        }
    };

    /**
     * 生成SDF（Signed Distance Field）纹理
     * 
     * SDF纹理用于高质量的边缘渲染和发光效果
     * 
     * @param sprite 源精灵
     * @param stroke 描边宽度
     * @return int数组 [纹理ID, 宽度, 高度]
     */
    public static int[] genSDF(SpriteAPI sprite, int stroke) {
        return genSDF(sprite, stroke, 0.5f);
    }

    /**
     * 生成SDF（Signed Distance Field）纹理，可指定透明度阈值。
     *
     * @param sprite 源精灵
     * @param stroke 描边宽度
     * @param alphaThreshold 透明度阈值（0-1），低于该阈值的像素不会参与SDF
     * @return int数组 [纹理ID, 宽度, 高度]
     */
    public static int[] genSDF(SpriteAPI sprite, int stroke, float alphaThreshold) {
        float clampedThreshold = alphaThreshold;
        if (clampedThreshold < 0f) clampedThreshold = 0f;
        if (clampedThreshold > 1f) clampedThreshold = 1f;

        // 检查缓存（阈值参与缓存键，避免不同阈值互相覆盖）
        int thresholdKey = Math.round(clampedThreshold * 1000f);
        String key = sprite.getTextureId() + "_" + stroke + "_" + thresholdKey;
        SdfRecord cached = SAVED_SDF.get(key);
        if (cached != null) {
            return cached.toArray();
        }

        // 生成SDF纹理
        float strokeDiv = 1f / stroke;
        int localWidth = (int) sprite.getWidth();
        int localHeight = (int) sprite.getHeight();
        
        int[] sdfs = org.boxutil.util.ShaderUtil.genSDF(
            sprite.getTextureId(), 
            GL11.GL_ALPHA, 
            localWidth, 
            localHeight, 
            stroke, 
            stroke, 
            clampedThreshold,
            org.boxutil.util.CalculateUtil.getExponentPOTMin(Math.max(localWidth, localHeight)), 
            strokeDiv, 
            strokeDiv
        );

        // 缓存结果
        SdfRecord record = new SdfRecord(sdfs[0], localWidth, localHeight);
        SAVED_SDF.put(key, record);

        // 设置纹理参数
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sdfs[0]);
        GL11.glTexParameteri(
            GL11.GL_TEXTURE_2D, 
            GL11.GL_TEXTURE_WRAP_S, 
            GL12.GL_CLAMP_TO_EDGE
        );
        GL11.glTexParameteri(
            GL11.GL_TEXTURE_2D, 
            GL11.GL_TEXTURE_WRAP_T, 
            GL12.GL_CLAMP_TO_EDGE
        );
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        return record.toArray();
    }

    public static void clearSdfCache() {
        for (SdfRecord record : SAVED_SDF.values()) {
            releaseSdfRecord(record);
        }
        SAVED_SDF.clear();
    }

    private static void releaseSdfRecord(SdfRecord record) {
        if (record == null) {
            return;
        }
        int textureId = record.textureId;
        if (textureId > 0) {
            GL11.glDeleteTextures(textureId);
        }
    }

    private static class SdfRecord {
        final int textureId;
        final int width;
        final int height;

        SdfRecord(int textureId, int width, int height) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
        }

        int[] toArray() {
            return new int[]{textureId, width, height};
        }
    }

    /**
     * 添加平滑粒子（简化版本）
     * 
     * @param loc 位置
     * @param vel 速度
     * @param size 大小
     * @param brightness 亮度
     * @param duration 持续时间
     * @param color 颜色
     * @return SpriteEntity实体
     */
    public static SpriteEntity addSmoothParticle(Vector2f loc, Vector2f vel, float size, 
                                                  float brightness, float duration, Color color) {
        return addSingleParticle(null, loc, vel, size, brightness, 0f, 0f, duration, 
                                color, "graphics/fx/hit_glow.png", 0f, 0f, 0f);
    }

    /**
     * 实体基础控制数据类
     * 用于将渲染实体绑定到游戏实体（如舰船、导弹等）
     */
    public static class EntityBasedControlData extends BaseControlData {

        /** 绑定的游戏实体 */
        public final CombatEntityAPI entity;
        
        /** 是否跟随实体朝向 */
        public boolean withFacing = true;

        /**
         * 构造函数
         * 
         * @param entity 要绑定的游戏实体
         */
        public EntityBasedControlData(CombatEntityAPI entity) {
            this.entity = entity;
        }

        /**
         * 每帧更新控制逻辑
         * 将渲染实体的位置和朝向同步到游戏实体
         */
        @Override
        public void controlAdvance(RenderDataAPI renderEntity, float amount) {
            if (entity != null) {
                // 同步位置
                renderEntity.setLocation(entity.getLocation());
                // 同步朝向（如果启用）
                if (withFacing) {
                    renderEntity.setFacingScale(
                        MathUtils.clampAngle(entity.getFacing()), 1f, 1f
                    );
                }

                // 检查实体是否仍然有效
                CombatEngineAPI engine = Global.getCombatEngine();
                if (engine == null || !isEntityValid(engine, entity)) {
                    // 如果实体无效，开始淡出
                    float[] timer = renderEntity.getGlobalTimer();
                    if (timer[0] > 1) {
                        timer[0] = 1;
                    }
                }
            }
        }

        /**
         * 检查实体是否有效
         * 
         * @param engine 战斗引擎
         * @param entity 要检查的实体
         * @return 实体是否有效
         */
        public static boolean isEntityValid(CombatEngineAPI engine, CombatEntityAPI entity) {
            // 检查是否在场
            if (!engine.isEntityInPlay(entity)) return false;
            // 检查是否过期
            if (entity.isExpired()) return false;

            // 舰船特殊检查
            if (entity instanceof ShipAPI) {
                ShipAPI ship = (ShipAPI) entity;
                if (!ship.isAlive()) return false;
            }
            
            // 弹幕特殊检查
            if (entity instanceof DamagingProjectileAPI) {
                DamagingProjectileAPI projectile = (DamagingProjectileAPI) entity;
                if (projectile.isFading()) return false;
                
                // 导弹特殊检查
                if (projectile instanceof MissileAPI) {
                    MissileAPI missile = (MissileAPI) projectile;
                    if (missile.didDamage()) return false;
                }
            }

            return true;
        }

        @Override
        public boolean controlAlphaBasedTimer(RenderDataAPI renderEntity) {
            return false;
        }
    }
}
