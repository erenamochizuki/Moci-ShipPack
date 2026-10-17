package data.scripts.weapons.render;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.io.IOException;

import org.dark.shaders.util.ShaderLib;
import org.dark.shaders.util.TextureData;
import org.dark.shaders.util.TextureData.ObjectType;
import org.dark.shaders.util.TextureData.TextureDataType;
import org.dark.shaders.util.TextureEntry;
import org.lazywizard.lazylib.VectorUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.BufferUtils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.apache.log4j.Logger;

import data.hullmods.Moci_GNShield_Script;
import data.scripts.util.Moci_BoxBasedUtil;

/**
 * 高性能GN护盾渲染器 - 基于着色器技术
 * 结合光环src的性能优势和GN护盾的特色效果
 */
public class Moci_GNShieldRendering_Shader extends BaseCombatLayeredRenderingPlugin {
    private static final Logger LOG = Global.getLogger(Moci_GNShieldRendering_Shader.class);
    private static final float SHADER_RETRY_INTERVAL = 1f;

    public static final String RENDER_KEY = "Moci_GNShieldRendering_Shader";
    private final CombatEngineAPI engine = Global.getCombatEngine();

    // 着色器系统
    private int shader = 0;
    private int[] uniform;
    private boolean shaderInitialized = false;
    private boolean normalUniformsAvailable = false;
    private Moci_ShaderManager shaderManager;
    private float shaderRetryTimer = 0f;

    // 着色器状态优化
    private static int currentShader = -1; // 当前绑定的着色器，避免重复绑定
    private static boolean shaderStateInitialized = false; // 纹理单元是否已初始化

    // 性能统计（可选）
    private static int shaderBindCount = 0; // 着色器绑定次数统计
    private static int uniformUpdateCount = 0; // uniform更新次数统计

    // GPU优化：uniform缓存系统，减少GPU-CPU通信
    private float cachedType = -999f; // uniform[2] 缓存
    private float cachedSpreadProgress = -999f; // uniform[3].x 缓存，记录扩散动画当前进度
    private float cachedSpreadRadius = -999f; // uniform[3].y 缓存，记录当前用于扩散的护盾半径
    private float cachedQuadRadius = -999f; // uniform[3].z 缓存，记录承载护盾贴图的quad半径
    private float cachedCombinedAlpha = -999f; // uniform[3].w 缓存
    private float cachedFluxLevel = -999f; // uniform[5] 缓存
    // uniform[4] (GN护盾颜色) 是固定值，不需要缓存

    // 浮点数比较的精度阈值
    private static final float UNIFORM_EPSILON = 0.0001f;

    // GPU优化：VBO系统，预计算顶点数据
    private int shieldQuadVBO = -1; // 顶点缓冲对象ID
    private boolean vboInitialized = false; // VBO初始化标志
    private static final int VERTICES_PER_QUAD = 4; // 每个四边形4个顶点
    private static final int FLOATS_PER_VERTEX = 4; // 每个顶点4个float (x, y, u, v)

    // 法线合成贴图（用于护盾读取舰船/武器法线高低）
    // 便于对比效果：可直接关闭法线起伏
    private static final boolean ENABLE_NORMAL_RELIEF = true;

    private static final int NORMAL_PASS_TEXTURE_SIZE = 512;
    private static final float NORMAL_RELIEF_STRENGTH = 3f;
    private static final float HIT_NORMAL_RELIEF_STRENGTH = 5f;
    private static final float NORMAL_LIGHT_DIR_X = 0.35f;
    private static final float NORMAL_LIGHT_DIR_Y = -0.75f;
    private static final float NORMAL_LIGHT_DIR_Z = 0.75f;
    private int normalCompositeTexture = -1;
    private int normalCompositeBuffer = -1;
    private boolean normalCompositeInitialized = false;
    private int flatNormalTexture = -1;

    // 着色器文件路径
    private static final String SHADER_VERT_PATH = "data/shaders/Moci_GNShield.vert";
    private static final String SHADER_FRAG_PATH = "data/shaders/Moci_GNShield.frag";
    private static final String SHADER_KEY = "Moci_GNShield";
    private static boolean registeredNormalFallbackEnabled = true;

    // GN护盾扩散效果系统
    private final Map<ShipAPI, Float> shieldSpreadProgress = new HashMap<>(); // 记录每艘船当前扩散动画的归一化进度，范围为0到1
    private final ArrayList<ShipAPI> renderableShieldShips = new ArrayList<>(); // 缓存本帧需要进入护盾渲染流程的舰船列表
    private static final Map<String, SpriteAPI> registeredFallbackNormalSprites = new HashMap<String, SpriteAPI>(); // 已注册的备用法线贴图，避免重复加载
    private static final Set<String> registeredFallbackNormalCsvs = new HashSet<String>(); // 已注册过的备用法线CSV，避免重复解析
    private static final float SPREAD_RADIUS_REFERENCE = 120f; // 护盾半径修正的基准值，半径接近它时按基础周期播放
    private static final float SPREAD_RADIUS_EXPONENT = 0.45f; // 护盾半径对扩散周期的影响指数，值越大大船扩散越慢
    private static final float SPREAD_CYCLE_MIN = 6.5f; // 扩散完整往返周期的最小秒数，防止小船扩散过快
    private static final float SPREAD_CYCLE_MAX = 12.5f; // 扩散完整往返周期的最大秒数，防止大船扩散过慢
    // 轮廓模板透明度阈值：只有接近实色像素才会写入模板
    private static final float STENCIL_ALPHA_THRESHOLD = Moci_BoxBasedUtil.SOLID_ALPHA_THRESHOLD;

    // 性能优化配置（从LunaSettings读取）
    private int currentFighterCount = 0; // 当前战场舰载机数量

    public Moci_GNShieldRendering_Shader() {
        super();
        initializeShader();
    }

    private void initializeShader() {
        shader = 0;
        uniform = null;
        shaderInitialized = false;
        normalUniformsAvailable = false;
        shaderStateInitialized = false;
        currentShader = -1;

        try {
            // 获取着色器管理器实例
            shaderManager = Moci_ShaderManager.getInstance();

            if (!shaderManager.isOpenGL20Supported()) {
                return;
            }

            // 通过着色器管理器创建着色器
            shader = shaderManager.createShader(SHADER_VERT_PATH, SHADER_FRAG_PATH, SHADER_KEY);

            if (shader != 0 && shaderManager.isShaderValid(SHADER_KEY)) {
                GL20.glUseProgram(shader);
                uniform = new int[] {
                        GL20.glGetUniformLocation(shader, "shieldTex"),
                        GL20.glGetUniformLocation(shader, "fxTex"),
                        GL20.glGetUniformLocation(shader, "type"),
                        GL20.glGetUniformLocation(shader, "state"),
                        GL20.glGetUniformLocation(shader, "gnColor"),
                        GL20.glGetUniformLocation(shader, "fluxLevel"),
                        GL20.glGetUniformLocation(shader, "normalTex"),
                        GL20.glGetUniformLocation(shader, "normalCtrl"),
                        GL20.glGetUniformLocation(shader, "normalLightDir")
                };

                // 核心uniform必须可用；法线uniform允许在部分驱动上降级
                boolean baseUniformsValid = true;
                for (int i = 0; i <= 5; i++) {
                    if (uniform[i] == -1) {
                        baseUniformsValid = false;
                        break;
                    }
                }
                normalUniformsAvailable = uniform[6] != -1 && uniform[7] != -1 && uniform[8] != -1;

                if (baseUniformsValid) {
                    // 纹理单元设置将在首次绑定时进行（延迟初始化优化）
                    GL20.glUseProgram(0);

                    shaderInitialized = true;

                    // 着色器预热：触发首次绑定以优化后续性能
                    warmupShader();
                    shaderRetryTimer = 0f;
                } else {
                    shaderInitialized = false;
                    normalUniformsAvailable = false;
                }
            } else {
                shaderInitialized = false;
                normalUniformsAvailable = false;
            }

            initializeFlatNormalTexture();

            // 输出着色器管理器统计信息
            shaderManager.printShaderStats();

        } catch (Exception e) {
            shaderInitialized = false;
            normalUniformsAvailable = false;
            LOG.error("[GN_SHIELD] initializeShader exception", e);
        }
    }

    private void ensureShaderInitializedFromRender(float elapsedForRetry) {
        if (shaderInitialized) {
            shaderRetryTimer = 0f;
            return;
        }

        shaderRetryTimer += elapsedForRetry;
        if (shaderRetryTimer < SHADER_RETRY_INTERVAL) {
            return;
        }
        shaderRetryTimer = 0f;

        initializeShader();
    }

    public static Moci_GNShieldRendering_Shader getInstance() {
        if (Global.getCombatEngine() != null &&
                Global.getCombatEngine().getCustomData().containsKey(RENDER_KEY)) {
            return (Moci_GNShieldRendering_Shader) Global.getCombatEngine().getCustomData().get(RENDER_KEY);
        } else {
            Moci_GNShieldRendering_Shader renderer = new Moci_GNShieldRendering_Shader();
            if (Global.getCombatEngine() != null) {
                Global.getCombatEngine().addLayeredRenderingPlugin(renderer);
                Global.getCombatEngine().getCustomData().put(RENDER_KEY, renderer);
            }
            return renderer;
        }
    }

    @Override
    public void init(CombatEntityAPI entity) {
    }

    @Override
    public void cleanup() {
        // 清理VBO资源，防止内存泄漏
        if (vboInitialized && shieldQuadVBO != -1) {
            GL15.glDeleteBuffers(shieldQuadVBO);
            shieldQuadVBO = -1;
            vboInitialized = false;
        }
        cleanupNormalResources();
    }

    @Override
    public boolean isExpired() {
        return false;
    }

    @Override
    public void advance(float amount) {
        if (Global.getCombatEngine().isPaused())
            return;
        updateShieldStateCache(amount);
    }

    private void updateShieldStateCache(float amount) {
        currentFighterCount = 0;
        renderableShieldShips.clear();

        Set<ShipAPI> shieldShipsThisFrame = new HashSet<ShipAPI>();
        Set<ShipAPI> activeShieldShipsThisFrame = new HashSet<ShipAPI>();

        for (ShipAPI ship : engine.getShips()) {
            if (ship.isFighter()) {
                currentFighterCount++;
            }

            if (!hasGNShield(ship)) {
                continue;
            }

            shieldShipsThisFrame.add(ship);
            if (!ship.isStationModule()) {
                renderableShieldShips.add(ship);
            }

            if (isShieldActive(ship)) {
                activeShieldShipsThisFrame.add(ship);
                Float progressObj = shieldSpreadProgress.get(ship);
                float progress = progressObj != null ? progressObj : 0f;
                // 统一存储0..1的进度，便于按舰体级别和半径灵活调整周期。
                progress += amount / getSpreadCycleTime(ship);
                if (progress >= 1f) {
                    progress -= (float) Math.floor(progress);
                }
                shieldSpreadProgress.put(ship, progress);
            }
        }

        for (ShipAPI ship : new ArrayList<ShipAPI>(shieldSpreadProgress.keySet())) {
            if (!shieldShipsThisFrame.contains(ship) || !activeShieldShipsThisFrame.contains(ship)) {
                shieldSpreadProgress.remove(ship);
            }
        }
    }

    private boolean hasGNShield(ShipAPI ship) {
        return Moci_GNShield_Script.hasShield(ship) ||
                data.hullmods.Moci_GNFieldDefense_Script.hasShield(ship);
    }

    private boolean isShieldActive(ShipAPI ship) {
        if (ship.getShield() != null && ship.getShield().isOn()) {
            return true;
        }

        if (data.hullmods.Moci_GNFieldDefense_Script.hasShield(ship)) {
            data.hullmods.Moci_GNFieldDefense_Script script = data.hullmods.Moci_GNFieldDefense_Script.getInstance(ship);
            return script != null && !script.getShield().isShieldBroken();
        }

        return false;
    }

    // 读取当前舰船的扩散动画进度，供shader决定扩散带的位置。
    private float getShieldSpreadLevel(ShipAPI ship) {
        Float progress = shieldSpreadProgress.get(ship);
        if (progress == null) {
            return 0f;
        }
        return progress;
    }

    private static final float SPREAD_RADIUS_PADDING = 50f; // 标准扩散半径额外增加的边距
    private static final float SPREAD_RADIUS_MULT = 1.2f; // 标准扩散半径额外的倍率

    // 统一取得护盾视觉半径，原版护盾优先，独立GN力场退回碰撞半径。
    private float getSpreadRadius(ShipAPI ship) {
        if (ship == null) {
            return 1f;
        }
        if (ship.getShield() != null) {
            return Math.max(1f, ship.getShield().getRadius() * SPREAD_RADIUS_MULT + SPREAD_RADIUS_PADDING);
        }
        // 独立GN力场没有单独半径接口时，退回碰撞半径作为视觉半径。
        return Math.max(1f, ship.getCollisionRadius() * SPREAD_RADIUS_MULT + SPREAD_RADIUS_PADDING);
    }

    // 给不同舰体级别一个基础扩散周期，先拉开大体节奏差异。
    private float getBaseSpreadCycleTime(ShipAPI ship) {
        switch (ship.getHullSize()) {
            case FIGHTER:
                return 6.5f;
            case FRIGATE:
                return 7.25f;
            case DESTROYER:
                return 8.5f;
            case CRUISER:
                return 9.75f;
            case CAPITAL_SHIP:
                return 11.0f;
            case DEFAULT:
            default:
                return 8.5f;
        }
    }

    // 在舰体级别基础上，再按护盾半径做温和修正，避免大船看起来扩散过快。
    private float getSpreadCycleTime(ShipAPI ship) {
        float radius = getSpreadRadius(ship);
        float baseCycle = getBaseSpreadCycleTime(ship); // 当前舰体级别对应的基础周期
        // 大护盾适度拉长周期，缩小不同尺寸舰船在世界空间里的扩散速度差异。
        float radiusScale = (float) Math.pow(radius / SPREAD_RADIUS_REFERENCE, SPREAD_RADIUS_EXPONENT); // 半径修正系数
        float cycleTime = baseCycle * radiusScale; // 修正后的完整往返周期
        return Math.max(SPREAD_CYCLE_MIN, Math.min(SPREAD_CYCLE_MAX, cycleTime));
    }

    private static final class ShieldRenderData {
        final Vector2f center; // 护盾渲染中心
        final float renderSize; // 护盾外层quad边长
        final float spreadRadius; // shader实际用于推进扩散的护盾半径

        ShieldRenderData(Vector2f center, float renderSize, float spreadRadius) {
            this.center = center;
            this.renderSize = renderSize;
            this.spreadRadius = spreadRadius;
        }
    }

    private ShieldRenderData computeShieldRenderData(ShipAPI ship) {
        if (ship == null || ship.getSpriteAPI() == null) {
            return null;
        }

        boolean hasVanillaShield = ship.getShield() != null;
        boolean hasGNFieldDefense = data.hullmods.Moci_GNFieldDefense_Script.hasShield(ship);

        if (hasVanillaShield && !ship.getShield().isOn()) {
            return null;
        }

        if (hasGNFieldDefense) {
            data.hullmods.Moci_GNFieldDefense_Script script = data.hullmods.Moci_GNFieldDefense_Script.getInstance(ship);
            if (script == null || script.getShield().isShieldBroken()) {
                return null;
            }
        }

        float spreadRadius = getSpreadRadius(ship);
        Vector2f shieldCenter;
        float offsetDistance = 0f;
        if (hasVanillaShield) {
            shieldCenter = new Vector2f(ship.getShield().getLocation());
            offsetDistance = Vector2f.sub(shieldCenter, ship.getLocation(), null).length();
        } else {
            shieldCenter = new Vector2f(ship.getLocation());
        }

        // 外层quad按护盾直径构建，只额外留少量边距给羽化和法线起伏。
        float renderSize = Math.max(512f, spreadRadius * 2f + offsetDistance * 2f);
        renderSize *= 1.1f;
        return new ShieldRenderData(shieldCenter, renderSize, spreadRadius);
    }

    private Vector2f computeShieldCenterRelativeToShip(ShipAPI ship, Vector2f shieldCenter) {
        if (ship == null || shieldCenter == null) {
            return new Vector2f();
        }

        Vector2f shieldCenterRel = Vector2f.sub(shieldCenter, ship.getLocation(), null);
        VectorUtils.rotate(shieldCenterRel, -ship.getFacing());
        return shieldCenterRel;
    }

    /**
     * 智能着色器绑定，避免重复的状态切换
     */
    private void bindShaderOptimized() {
        if (!shaderInitialized)
            return;

        // 外部渲染器可能修改当前program，因此这里直接绑定本shader，
        // 避免通过glGetInteger读取GPU状态触发异步渲染管线阻塞。
        if (currentShader != shader) {
            shaderBindCount++; // 统计绑定次数
        }
        GL20.glUseProgram(shader);
        currentShader = shader;

        // 只在首次绑定时设置纹理单元
        if (!shaderStateInitialized) {
            GL20.glUniform1i(uniform[0], 0); // shieldTex -> GL_TEXTURE0
            GL20.glUniform1i(uniform[1], 1); // fxTex -> GL_TEXTURE1
            if (normalUniformsAvailable) {
                GL20.glUniform1i(uniform[6], 2); // normalTex -> GL_TEXTURE2
            }
            shaderStateInitialized = true;
        }
    }

    /**
     * 解绑着色器，恢复固定管线
     */
    private static void unbindShader() {
        if (currentShader != 0) {
            GL20.glUseProgram(0);
            currentShader = 0;
        }
    }

    /**
     * 着色器预热：初始化时触发首次绑定，优化后续性能
     */
    private void warmupShader() {
        if (!shaderInitialized)
            return;

        try {
            // 触发首次绑定，初始化纹理单元
            bindShaderOptimized();

            // 设置一些默认uniform值，让GPU预热着色器
            if (shaderStateInitialized) {
                GL20.glUniform1f(uniform[2], 0.0f); // type
                GL20.glUniform4f(uniform[3], 0.0f, 256.0f, 256.0f, 1.0f); // state
                GL20.glUniform3f(uniform[4], 0.2f, 0.4f, 1.0f); // gnColor
                GL20.glUniform1f(uniform[5], 0.0f); // fluxLevel
                if (normalUniformsAvailable) {
                    GL20.glUniform4f(uniform[7], 0.0f, NORMAL_RELIEF_STRENGTH, 0.0f, 0.0f); // normalCtrl
                    GL20.glUniform3f(uniform[8], NORMAL_LIGHT_DIR_X, NORMAL_LIGHT_DIR_Y, NORMAL_LIGHT_DIR_Z); // normalLightDir
                }
            }

            // 解绑
            unbindShader();

        } catch (Exception e) {
            // Shader warmup failed, continue with fallback
        }
    }

    private float calculateTotalShipSize(ShipAPI ship) {
        // 计算包含模块的总尺寸
        float maxSize = Math.max(ship.getSpriteAPI().getHeight(), ship.getSpriteAPI().getWidth());

        if (ship.getChildModulesCopy() != null) {
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (module.getSpriteAPI() != null) {
                    float moduleSize = Math.max(module.getSpriteAPI().getHeight(), module.getSpriteAPI().getWidth());
                    float distance = (float) Math.sqrt(
                            Math.pow(module.getLocation().x - ship.getLocation().x, 2) +
                                    Math.pow(module.getLocation().y - ship.getLocation().y, 2));
                    maxSize = Math.max(maxSize, (distance + moduleSize) * 1.2f);
                }
            }
        }

        return maxSize;
    }

    private boolean shouldRenderWeapon(WeaponAPI weapon) {
        // 优化的武器过滤逻辑
        if (weapon.getSprite() == null)
            return false;
        if (weapon.isDisabled())
            return false;

        // 只渲染可见的硬点和炮塔武器
        return !weapon.getSlot().isHidden() &&
                (weapon.getSlot().isBuiltIn() || weapon.getSlot().isDecorative());
    }

    /**
     * GPU优化：按纹理ID分组武器，减少纹理绑定次数
     * 注意：保持Java7兼容性，不使用diamond运算符
     */
    private Map<Integer, ArrayList<WeaponAPI>> groupWeaponsByTexture(ShipAPI ship) {
        Map<Integer, ArrayList<WeaponAPI>> weaponsByTexture = new HashMap<Integer, ArrayList<WeaponAPI>>();

        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSprite() != null && shouldRenderWeapon(weapon)) {
                int textureId = weapon.getSprite().getTextureId();

                ArrayList<WeaponAPI> weaponList = weaponsByTexture.get(textureId);
                if (weaponList == null) {
                    weaponList = new ArrayList<WeaponAPI>();
                    weaponsByTexture.put(textureId, weaponList);
                }
                weaponList.add(weapon);
            }
        }

        return weaponsByTexture;
    }

    /**
     * GPU优化：按纹理ID分组模块，减少纹理绑定次数
     * 注意：保持Java7兼容性，不使用diamond运算符
     */
    private Map<Integer, ArrayList<ShipAPI>> groupModulesByTexture(ShipAPI ship) {
        Map<Integer, ArrayList<ShipAPI>> modulesByTexture = new HashMap<Integer, ArrayList<ShipAPI>>();

        if (ship.getChildModulesCopy() != null) {
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (module.getSpriteAPI() != null) {
                    int textureId = module.getSpriteAPI().getTextureId();

                    ArrayList<ShipAPI> moduleList = modulesByTexture.get(textureId);
                    if (moduleList == null) {
                        moduleList = new ArrayList<ShipAPI>();
                        modulesByTexture.put(textureId, moduleList);
                    }
                    moduleList.add(module);
                }
            }
        }

        return modulesByTexture;
    }

    /**
     * GPU优化：智能uniform设置，只在值真正改变时才发送到GPU
     */
    private void setUniform1fCached(int location, float value, float cachedValue) {
        if (Math.abs(value - cachedValue) > UNIFORM_EPSILON) {
            GL20.glUniform1f(location, value);
            uniformUpdateCount++; // 统计uniform更新次数
            // 注意：调用方需要自己更新缓存值
        }
    }

    private void setUniform4fCached(int location, float x, float y, float z, float w,
            float cachedX, float cachedY, float cachedZ, float cachedW) {
        if (Math.abs(x - cachedX) > UNIFORM_EPSILON ||
                Math.abs(y - cachedY) > UNIFORM_EPSILON ||
                Math.abs(z - cachedZ) > UNIFORM_EPSILON ||
                Math.abs(w - cachedW) > UNIFORM_EPSILON) {
            GL20.glUniform4f(location, x, y, z, w);
            uniformUpdateCount++; // 统计uniform更新次数
            // 注意：调用方需要自己更新缓存值
        }
    }

    /**
     * GPU优化：初始化VBO，创建标准化的护盾四边形
     */
    private void initShieldQuadVBO() {
        if (vboInitialized)
            return;

        try {
            // 创建标准化的四边形顶点数据 (位置 + 纹理坐标)
            // 格式：每个顶点 = (x, y, u, v)
            float[] quadVertices = {
                    // 左下角
                    -0.5f, -0.5f, 0.0f, 0.0f,
                    // 左上角
                    -0.5f, 0.5f, 0.0f, 1.0f,
                    // 右上角
                    0.5f, 0.5f, 1.0f, 1.0f,
                    // 右下角
                    0.5f, -0.5f, 1.0f, 0.0f
            };

            // 创建FloatBuffer
            FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(quadVertices.length);
            vertexBuffer.put(quadVertices);
            vertexBuffer.flip();

            // 生成并绑定VBO
            shieldQuadVBO = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, shieldQuadVBO);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0); // 解绑

            vboInitialized = true;

        } catch (Exception e) {
            vboInitialized = false;
        }
    }

    /**
     * GPU优化：使用VBO渲染护盾四边形
     */
    private void renderShieldQuadVBO(float scaleX, float scaleY) {
        if (!vboInitialized) {
            initShieldQuadVBO();
            if (!vboInitialized)
                return; // 初始化失败，回退到立即模式
        }

        // 绑定VBO
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, shieldQuadVBO);

        // 启用顶点数组
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

        // 设置顶点指针 (stride = 4 floats per vertex)
        GL11.glVertexPointer(2, GL11.GL_FLOAT, FLOATS_PER_VERTEX * 4, 0); // 位置 (x, y)
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, FLOATS_PER_VERTEX * 4, 2 * 4); // 纹理坐标 (u, v)

        // 应用缩放
        GL11.glScalef(scaleX, scaleY, 1.0f);

        // 绘制四边形 (4个顶点)
        GL11.glDrawArrays(GL11.GL_QUADS, 0, VERTICES_PER_QUAD);

        // 禁用顶点数组
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);

        // 解绑VBO
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private void initializeFlatNormalTexture() {
        if (flatNormalTexture != -1) {
            return;
        }

        try {
            flatNormalTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, flatNormalTexture);

            ByteBuffer normalPixel = BufferUtils.createByteBuffer(3);
            normalPixel.put((byte) 128).put((byte) 128).put((byte) 255);
            normalPixel.flip();

            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, 1, 1, 0,
                    GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, normalPixel);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        } catch (Exception e) {
            flatNormalTexture = -1;
        }
    }

    private void initializeNormalCompositeBuffer() {
        if (normalCompositeInitialized) {
            return;
        }
        if (!ShaderLib.areBuffersAllowed()) {
            return;
        }

        try {
            normalCompositeTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, normalCompositeTexture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, NORMAL_PASS_TEXTURE_SIZE, NORMAL_PASS_TEXTURE_SIZE, 0,
                    GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);

            if (ShaderLib.useBufferCore()) {
                normalCompositeBuffer = ShaderLib.makeFramebuffer(GL30.GL_COLOR_ATTACHMENT0, normalCompositeTexture,
                        NORMAL_PASS_TEXTURE_SIZE, NORMAL_PASS_TEXTURE_SIZE, 0);
            } else if (ShaderLib.useBufferARB()) {
                normalCompositeBuffer = ShaderLib.makeFramebuffer(ARBFramebufferObject.GL_COLOR_ATTACHMENT0,
                        normalCompositeTexture, NORMAL_PASS_TEXTURE_SIZE, NORMAL_PASS_TEXTURE_SIZE, 0);
            } else {
                normalCompositeBuffer = ShaderLib.makeFramebuffer(EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT,
                        normalCompositeTexture, NORMAL_PASS_TEXTURE_SIZE, NORMAL_PASS_TEXTURE_SIZE, 0);
            }

            normalCompositeInitialized = normalCompositeBuffer != 0;
            if (!normalCompositeInitialized) {
                if (normalCompositeTexture != -1) {
                    GL11.glDeleteTextures(normalCompositeTexture);
                    normalCompositeTexture = -1;
                }
            }
        } catch (Exception e) {
            normalCompositeInitialized = false;
            normalCompositeBuffer = -1;
            if (normalCompositeTexture != -1) {
                GL11.glDeleteTextures(normalCompositeTexture);
                normalCompositeTexture = -1;
            }
        }
    }

    private void cleanupNormalResources() {
        if (normalCompositeBuffer != -1) {
            try {
                if (ShaderLib.useBufferCore()) {
                    GL30.glDeleteFramebuffers(normalCompositeBuffer);
                } else if (ShaderLib.useBufferARB()) {
                    ARBFramebufferObject.glDeleteFramebuffers(normalCompositeBuffer);
                } else {
                    EXTFramebufferObject.glDeleteFramebuffersEXT(normalCompositeBuffer);
                }
            } catch (Exception e) {
                // Ignore cleanup errors
            }
            normalCompositeBuffer = -1;
        }

        if (normalCompositeTexture != -1) {
            try {
                GL11.glDeleteTextures(normalCompositeTexture);
            } catch (Exception e) {
                // Ignore cleanup errors
            }
            normalCompositeTexture = -1;
        }

        if (flatNormalTexture != -1) {
            try {
                GL11.glDeleteTextures(flatNormalTexture);
            } catch (Exception e) {
                // Ignore cleanup errors
            }
            flatNormalTexture = -1;
        }

        normalCompositeInitialized = false;
    }

    private void bindFramebuffer(int framebuffer) {
        if (ShaderLib.useBufferCore()) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        } else if (ShaderLib.useBufferARB()) {
            ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_FRAMEBUFFER, framebuffer);
        } else {
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, framebuffer);
        }
    }

    private void unbindFramebuffer() {
        bindFramebuffer(0);
    }

    private float getShipCombinedAlpha(ShipAPI ship) {
        return ship.getAlphaMult() * ship.getExtraAlphaMult() * ship.getExtraAlphaMult2();
    }

    private void renderMatchedNormalSprite(SpriteAPI normalSprite, SpriteAPI originalSprite, Vector2f location, float alpha) {
        if (normalSprite == null || originalSprite == null || location == null) {
            return;
        }

        normalSprite.setAngle(originalSprite.getAngle());
        normalSprite.setSize(originalSprite.getWidth(), originalSprite.getHeight());
        normalSprite.setCenter(originalSprite.getCenterX(), originalSprite.getCenterY());
        normalSprite.setAlphaMult(alpha);
        normalSprite.renderAtCenter(location.x, location.y);
    }

    private String getTextureObjectKey(ObjectType type, int frame) {
        String typeStr;
        switch (type) {
            case SHIP:
                typeStr = "$$$ship";
                break;
            case TURRET:
                typeStr = "$$$turret";
                break;
            case TURRET_BARREL:
                typeStr = "$$$turretbarrel";
                break;
            case TURRET_UNDER:
                typeStr = "$$$turretunder";
                break;
            case HARDPOINT:
                typeStr = "$$$hardpoint";
                break;
            case HARDPOINT_BARREL:
                typeStr = "$$$hardpointbarrel";
                break;
            case HARDPOINT_UNDER:
                typeStr = "$$$hardpointunder";
                break;
            default:
                return null;
        }
        return typeStr + frame;
    }

    public static boolean isRegisteredNormalFallbackEnabled() {
        return registeredNormalFallbackEnabled;
    }

    public static void setRegisteredNormalFallbackEnabled(boolean enabled) {
        registeredNormalFallbackEnabled = enabled;
    }

    /**
     * Optional explicit fallback registry for mods that want GN shield relief without relying on
     * GraphicsLib's global normal-map table. The CSV format matches GraphicsLib texture data CSVs,
     * but only rows with map=normal are consumed here.
     */
    public static void registerNormalTextureDataCSV(String localPath) {
        if (localPath == null || localPath.isEmpty()) {
            return;
        }
        registeredNormalFallbackEnabled = true;
        if (!registeredFallbackNormalCsvs.add(localPath)) {
            return;
        }

        try {
            JSONArray textureData = Global.getSettings().loadCSV(localPath);
            for (int i = 0; i < textureData.length(); i++) {
                try {
                    JSONObject entry = textureData.getJSONObject(i);
                    if (entry.optString("id").isEmpty() ||
                            entry.optString("type").isEmpty() ||
                            entry.optString("map").isEmpty() ||
                            entry.optString("path").isEmpty()) {
                        continue;
                    }
                    if (!"normal".equals(entry.optString("map"))) {
                        continue;
                    }

                    ObjectType objectType;
                    switch (entry.optString("type")) {
                        case "ship":
                            objectType = ObjectType.SHIP;
                            break;
                        case "turret":
                            objectType = ObjectType.TURRET;
                            break;
                        case "turretbarrel":
                            objectType = ObjectType.TURRET_BARREL;
                            break;
                        case "turretunder":
                            objectType = ObjectType.TURRET_UNDER;
                            break;
                        case "hardpoint":
                            objectType = ObjectType.HARDPOINT;
                            break;
                        case "hardpointbarrel":
                            objectType = ObjectType.HARDPOINT_BARREL;
                            break;
                        case "hardpointunder":
                            objectType = ObjectType.HARDPOINT_UNDER;
                            break;
                        default:
                            continue;
                    }

                    String keySuffix = getStaticTextureObjectKey(objectType, entry.optInt("frame", 0));
                    if (keySuffix == null) {
                        continue;
                    }

                    String path = entry.optString("path");
                    SpriteAPI sprite = Global.getSettings().getSprite(path);
                    if (sprite == null || sprite.getHeight() < 1f) {
                        Global.getSettings().loadTexture(path);
                        sprite = Global.getSettings().getSprite(path);
                    }
                    if (sprite == null || sprite.getHeight() < 1f) {
                        continue;
                    }

                    registeredFallbackNormalSprites.put(entry.optString("id") + keySuffix, sprite);
                } catch (Exception ex) {
                    // Skip malformed rows so one bad entry does not break the registry.
                }
            }
        } catch (IOException | JSONException e) {
            LOG.warn("[GN_SHIELD] Failed to register fallback normal CSV: " + localPath, e);
        }
    }

    private static String getStaticTextureObjectKey(ObjectType type, int frame) {
        String typeStr;
        switch (type) {
            case SHIP:
                typeStr = "$$$ship";
                break;
            case TURRET:
                typeStr = "$$$turret";
                break;
            case TURRET_BARREL:
                typeStr = "$$$turretbarrel";
                break;
            case TURRET_UNDER:
                typeStr = "$$$turretunder";
                break;
            case HARDPOINT:
                typeStr = "$$$hardpoint";
                break;
            case HARDPOINT_BARREL:
                typeStr = "$$$hardpointbarrel";
                break;
            case HARDPOINT_UNDER:
                typeStr = "$$$hardpointunder";
                break;
            default:
                return null;
        }
        return typeStr + frame;
    }

    private SpriteAPI getRegisteredFallbackNormalSprite(String id, ObjectType type, int frame) {
        if (!registeredNormalFallbackEnabled || id == null || id.isEmpty()) {
            return null;
        }

        String keySuffix = getTextureObjectKey(type, frame);
        if (keySuffix == null) {
            return null;
        }
        return registeredFallbackNormalSprites.get(id + keySuffix);
    }

    @SuppressWarnings("unchecked")
    private ArrayList<String> getShipNormalFallbackIds(ShipAPI ship) {
        ArrayList<String> ids = new ArrayList<String>();
        if (ship == null || ship.getHullSpec() == null) {
            return ids;
        }

        if (engine != null && engine.getCustomData() != null) {
            Object overrideData = engine.getCustomData().get("SL_shipTexOvd");
            if (overrideData instanceof Map) {
                Map<ShipAPI, String> shipTextureOverrides = (Map<ShipAPI, String>) overrideData;
                String overrideId = shipTextureOverrides.get(ship);
                if (overrideId != null && !overrideId.isEmpty()) {
                    ids.add(overrideId);
                }
            }
        }

        String hullId = ship.getHullSpec().getHullId();
        if (hullId != null && !hullId.isEmpty() && !ids.contains(hullId)) {
            ids.add(hullId);
        }

        String parentHullId = ship.getHullSpec().getDParentHullId();
        if (parentHullId != null && !parentHullId.isEmpty() && !ids.contains(parentHullId)) {
            ids.add(parentHullId);
        }

        String baseHullId = ship.getHullSpec().getBaseHullId();
        if (baseHullId != null && !baseHullId.isEmpty() && !ids.contains(baseHullId)) {
            ids.add(baseHullId);
        }

        return ids;
    }

    private SpriteAPI getShipNormalSprite(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null) {
            return null;
        }

        TextureEntry entry = ShaderLib.getShipTexture(ship, TextureDataType.NORMAL_MAP);
        if (entry != null && entry.sprite != null) {
            return entry.sprite;
        }

        for (String candidateId : getShipNormalFallbackIds(ship)) {
            SpriteAPI sprite = getRegisteredFallbackNormalSprite(candidateId, ObjectType.SHIP, 0);
            if (sprite != null) {
                return sprite;
            }
        }
        return null;
    }

    private SpriteAPI getWeaponNormalSprite(WeaponAPI weapon, ObjectType hardpointType, ObjectType turretType) {
        if (weapon == null || weapon.getSlot() == null) {
            return null;
        }
        ObjectType objectType = weapon.getSlot().isHardpoint() ? hardpointType : turretType;
        int frame = 0;
        if (weapon.getAnimation() != null) {
            frame = weapon.getAnimation().getFrame();
        }

        TextureEntry entry = TextureData.getTextureData(weapon.getId(), TextureDataType.NORMAL_MAP, objectType, frame);
        if (entry != null && entry.sprite != null) {
            return entry.sprite;
        }
        if (frame != 0) {
            entry = TextureData.getTextureData(weapon.getId(), TextureDataType.NORMAL_MAP, objectType, 0);
            if (entry != null && entry.sprite != null) {
                return entry.sprite;
            }
        }

        SpriteAPI sprite = getRegisteredFallbackNormalSprite(weapon.getId(), objectType, frame);
        if (sprite == null && frame != 0) {
            sprite = getRegisteredFallbackNormalSprite(weapon.getId(), objectType, 0);
        }
        return sprite;
    }

    private boolean renderWeaponNormal(ShipAPI ship, WeaponAPI weapon) {
        if (weapon == null || ship == null || !shouldRenderWeapon(weapon)) {
            return false;
        }

        boolean rendered = false;
        float shipAlpha = getShipCombinedAlpha(ship);
        Vector2f weaponLocation = new Vector2f(weapon.getLocation());
        if (weapon.isDecorative() && weapon.isBeam() && weapon.getRenderOffsetForDecorativeBeamWeaponsOnly() != null) {
            Vector2f additionalOffset = VectorUtils.rotate(weapon.getRenderOffsetForDecorativeBeamWeaponsOnly(),
                    ship.getFacing(), new Vector2f());
            weaponLocation = Vector2f.add(weaponLocation, additionalOffset, weaponLocation);
        }

        SpriteAPI originalUnder = weapon.getUnderSpriteAPI();
        SpriteAPI underNormalSprite = getWeaponNormalSprite(weapon, ObjectType.HARDPOINT_UNDER, ObjectType.TURRET_UNDER);
        if (originalUnder != null && underNormalSprite != null) {
            renderMatchedNormalSprite(underNormalSprite, originalUnder, weaponLocation,
                    Math.min(shipAlpha, originalUnder.getAlphaMult()));
            rendered = true;
        }

        SpriteAPI originalBarrel = weapon.getBarrelSpriteAPI();
        SpriteAPI barrelNormalSprite = getWeaponNormalSprite(weapon, ObjectType.HARDPOINT_BARREL, ObjectType.TURRET_BARREL);
        if (originalBarrel != null && barrelNormalSprite != null && weapon.isRenderBarrelBelow()) {
            SpriteAPI barrelNormal = barrelNormalSprite;
            barrelNormal.setSize(originalBarrel.getWidth(), originalBarrel.getHeight());
            barrelNormal.setCenter(originalBarrel.getCenterX(), originalBarrel.getCenterY());
            weapon.renderBarrel(barrelNormal, weaponLocation, Math.min(shipAlpha, originalBarrel.getAlphaMult()));
            rendered = true;
        }

        SpriteAPI originalMain = weapon.getSprite();
        SpriteAPI mainNormalSprite = getWeaponNormalSprite(weapon, ObjectType.HARDPOINT, ObjectType.TURRET);
        if (originalMain != null && mainNormalSprite != null) {
            renderMatchedNormalSprite(mainNormalSprite, originalMain, weaponLocation,
                    Math.min(shipAlpha, originalMain.getAlphaMult()));
            rendered = true;
        }

        if (originalBarrel != null && barrelNormalSprite != null && !weapon.isRenderBarrelBelow()) {
            SpriteAPI barrelNormal = barrelNormalSprite;
            barrelNormal.setSize(originalBarrel.getWidth(), originalBarrel.getHeight());
            barrelNormal.setCenter(originalBarrel.getCenterX(), originalBarrel.getCenterY());
            weapon.renderBarrel(barrelNormal, weaponLocation, Math.min(shipAlpha, originalBarrel.getAlphaMult()));
            rendered = true;
        }

        return rendered;
    }

    private boolean renderNormalCompositeTexture(ShipAPI ship, Vector2f shieldCenter, float shieldSize) {
        if (!ShaderLib.areBuffersAllowed()) {
            return false;
        }
        if (ship == null || shieldCenter == null || shieldSize <= 0f) {
            return false;
        }

        initializeNormalCompositeBuffer();
        if (!normalCompositeInitialized || normalCompositeTexture == -1 || normalCompositeBuffer == -1) {
            return false;
        }

        boolean renderedAny = false;
        boolean attribPushed = false;
        boolean projectionPushed = false;
        boolean modelViewPushed = false;
        try {
            // 法线合成pass使用固定管线绘制，结束后由主护盾pass重新绑定shader。
            GL20.glUseProgram(0);
            currentShader = 0;

            // 在修改viewport前保存状态，确保退出时无需从GPU读取原始viewport。
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            attribPushed = true;

            bindFramebuffer(normalCompositeBuffer);
            GL11.glViewport(0, 0, NORMAL_PASS_TEXTURE_SIZE, NORMAL_PASS_TEXTURE_SIZE);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glClearColor(0.5f, 0.5f, 1.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            projectionPushed = true;
            GL11.glLoadIdentity();
            GL11.glOrtho(0, NORMAL_PASS_TEXTURE_SIZE, 0, NORMAL_PASS_TEXTURE_SIZE, -1, 1);

            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            modelViewPushed = true;
            GL11.glLoadIdentity();

            GL11.glTranslatef(NORMAL_PASS_TEXTURE_SIZE * 0.5f, NORMAL_PASS_TEXTURE_SIZE * 0.5f, 0.0f);
            float scale = NORMAL_PASS_TEXTURE_SIZE / Math.max(1f, shieldSize);
            GL11.glScalef(scale, scale, 1.0f);
            GL11.glRotatef(-ship.getFacing(), 0.0f, 0.0f, 1.0f);
            GL11.glTranslatef(-shieldCenter.x, -shieldCenter.y, 0.0f);

            SpriteAPI shipNormalSprite = getShipNormalSprite(ship);
            if (shipNormalSprite != null && ship.getSpriteAPI() != null) {
                renderMatchedNormalSprite(shipNormalSprite, ship.getSpriteAPI(), ship.getLocation(),
                        getShipCombinedAlpha(ship));
                renderedAny = true;
            }

            if (ship.getChildModulesCopy() != null) {
                for (ShipAPI module : ship.getChildModulesCopy()) {
                    if (module == null || module.getSpriteAPI() == null) {
                        continue;
                    }
                    SpriteAPI moduleNormalSprite = getShipNormalSprite(module);
                    if (moduleNormalSprite != null) {
                        renderMatchedNormalSprite(moduleNormalSprite, module.getSpriteAPI(), module.getLocation(),
                                getShipCombinedAlpha(module));
                        renderedAny = true;
                    }
                }
            }

            for (WeaponAPI weapon : ship.getAllWeapons()) {
                if (renderWeaponNormal(ship, weapon)) {
                    renderedAny = true;
                }
            }
        } catch (Exception e) {
            renderedAny = false;
        } finally {
            if (modelViewPushed) {
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
            }
            if (projectionPushed) {
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
            }
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            unbindFramebuffer();
            if (attribPushed) {
                GL11.glPopAttrib();
            }
        }

        return renderedAny;
    }

    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
    }

    @Override
    public float getRenderRadius() {
        return 1000000f;
    }

    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        if (layer == CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) {
            float elapsedForRetry = 0.1f;
            if (engine != null) {
                elapsedForRetry = Math.max(0.016f, engine.getElapsedInLastFrame());
            }
            ensureShaderInitializedFromRender(elapsedForRetry);

            // 智能着色器绑定优化
            bindShaderOptimized();

            for (ShipAPI ship : renderableShieldShips) {
                // 性能优化：跳过舰载机渲染
                if (shouldSkipFighterRendering(ship)) {
                    continue;
                }

                // 计算护盾中心偏移距离，扩大可见性检查范围
                if (ship.getShield() != null) {
                    Vector2f shieldCenter = ship.getShield().getLocation();
                    float offsetDistance = Vector2f.sub(shieldCenter, ship.getLocation(), null).length();
                    float extendedRadius = Math.max(ship.getCollisionRadius(), getSpreadRadius(ship)) + offsetDistance; // 屏幕裁剪时使用的扩展半径
                    if (ShaderLib.isOnScreen(ship.getLocation(), extendedRadius * 2f)) {
                        processShield(ship);
                    }
                } else {
                    float extendedRadius = Math.max(ship.getCollisionRadius(), getSpreadRadius(ship)); // 无原版护盾时直接按视觉半径裁剪
                    if (ShaderLib.isOnScreen(ship.getLocation(), extendedRadius * 2f)) {
                        processShield(ship);
                    }
                }
            }

            // 解绑着色器（在所有渲染完成后）
            unbindShader();
        }
    }

    protected void processShield(ShipAPI ship) {
        ShieldRenderData shieldData = computeShieldRenderData(ship);
        if (shieldData == null) {
            return;
        }

        SpriteAPI shield = Global.getSettings().getSprite("fx", "Moci_GNShield");
        SpriteAPI hitFx = Global.getSettings().getSprite("fx", "Moci_GNShield_fog");

        float max = shieldData.renderSize;
        float spreadRadius = shieldData.spreadRadius; // 当前护盾的视觉半径，控制扩散带最远跑到哪里
        float quadRadius = max * 0.5f; // 承载护盾的绘制quad半径，负责把UV映射回世界距离
        Vector2f shieldCenter = shieldData.center;
        Vector2f size = new Vector2f(max, max);
        Vector2f uv = new Vector2f(1.0f, 1.0f);

        // 计算透明度和效果参数
        float combinedShieldAlphaBase = ship.getAlphaMult() * ship.getExtraAlphaMult() * ship.getExtraAlphaMult2();
        float combinedShieldAlpha = combinedShieldAlphaBase * 1.0f; // 恢复简单透明度
        float spreadLevel = getShieldSpreadLevel(ship);
        float fluxLevel = ship.getFluxLevel();
        boolean hasNormalRelief = false;

        // OpenGL状态设置
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        // 第一步：绘制船只轮廓到模板缓冲
        GL11.glColorMask(false, false, false, false);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 255);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, STENCIL_ALPHA_THRESHOLD);

        if (shaderInitialized) {
            bindShaderOptimized();
            // GPU优化：缓存uniform设置 - 模板渲染模式
            setUniform1fCached(uniform[2], 3.0f, cachedType);
            if (Math.abs(3.0f - cachedType) > UNIFORM_EPSILON) {
                cachedType = 3.0f;
            }
        }

        // 渲染主舰船
        SpriteAPI ss = ship.getSpriteAPI();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, ss.getTextureId());
        ss.renderAtCenter(ship.getLocation().x, ship.getLocation().y);

        // GPU优化：批处理渲染模块（按纹理分组）
        Map<Integer, ArrayList<ShipAPI>> modulesByTexture = groupModulesByTexture(ship);
        for (Map.Entry<Integer, ArrayList<ShipAPI>> entry : modulesByTexture.entrySet()) {
            // 每个纹理只绑定一次
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, entry.getKey());

            // 批量渲染所有使用此纹理的模块
            for (ShipAPI module : entry.getValue()) {
                SpriteAPI moduleSpr = module.getSpriteAPI();
                moduleSpr.renderAtCenter(module.getLocation().x, module.getLocation().y);
            }
        }

        // GPU优化：批处理渲染武器（按纹理分组）
        Map<Integer, ArrayList<WeaponAPI>> weaponsByTexture = groupWeaponsByTexture(ship);
        for (Map.Entry<Integer, ArrayList<WeaponAPI>> entry : weaponsByTexture.entrySet()) {
            // 每个纹理只绑定一次
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, entry.getKey());

            // 批量渲染所有使用此纹理的武器
            for (WeaponAPI weapon : entry.getValue()) {
                SpriteAPI weaponSpr = weapon.getSprite();
                weaponSpr.renderAtCenter(weapon.getLocation().x, weapon.getLocation().y);
            }
        }

        // 额外法线合成贴图：读取舰船/武器法线，供护盾shader做起伏光照
        hasNormalRelief = ENABLE_NORMAL_RELIEF && normalUniformsAvailable
                && renderNormalCompositeTexture(ship, shieldCenter, max);
        if (shaderInitialized) {
            bindShaderOptimized();
        }

        // 第二步：在船只轮廓内渲染护盾
        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 255);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GL11.glColorMask(true, true, true, true);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.0f);
        GL11.glDisable(GL11.GL_ALPHA_TEST);

        // 渲染护盾效果
        GL11.glPushMatrix();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shield.getTextureId());
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, hitFx.getTextureId());
        if (normalUniformsAvailable) {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            int normalTexture = hasNormalRelief ? normalCompositeTexture : flatNormalTexture;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, normalTexture >= 0 ? normalTexture : 0);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        // 使用护盾中心位置而不是舰船中心位置
        GL11.glTranslatef(shieldCenter.x, shieldCenter.y, 0.0f);
        GL11.glRotatef(ship.getFacing(), 0.0f, 0.0f, 1.0f);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // 设置着色器参数（GN护盾定制版）- GPU优化版本
        if (shaderInitialized) {
            // type = 0: 普通扩散模式 - 缓存优化
            setUniform1fCached(uniform[2], 0.0f, cachedType);
            if (Math.abs(0.0f - cachedType) > UNIFORM_EPSILON) {
                cachedType = 0.0f;
            }

            // state: (spreadProgress, shieldRadius, quadRadius, combinedAlpha)
            // 扩散距离按护盾半径推进，quad半径只负责把fragUV换算回世界距离。
            setUniform4fCached(uniform[3], spreadLevel, spreadRadius, quadRadius, combinedShieldAlpha,
                    cachedSpreadProgress, cachedSpreadRadius, cachedQuadRadius, cachedCombinedAlpha);
            if (Math.abs(spreadLevel - cachedSpreadProgress) > UNIFORM_EPSILON ||
                    Math.abs(spreadRadius - cachedSpreadRadius) > UNIFORM_EPSILON ||
                    Math.abs(quadRadius - cachedQuadRadius) > UNIFORM_EPSILON ||
                    Math.abs(combinedShieldAlpha - cachedCombinedAlpha) > UNIFORM_EPSILON) {
                cachedSpreadProgress = spreadLevel;
                cachedSpreadRadius = spreadRadius;
                cachedQuadRadius = quadRadius;
                cachedCombinedAlpha = combinedShieldAlpha;
            }

            // GN护盾颜色（基础蓝色）- 固定值，每次都设置（但GPU驱动会优化）
            GL20.glUniform3f(uniform[4], 0.2f, 0.4f, 1.0f);

            // 幅能等级（用于颜色混合）- 缓存优化
            setUniform1fCached(uniform[5], fluxLevel, cachedFluxLevel);
            if (Math.abs(fluxLevel - cachedFluxLevel) > UNIFORM_EPSILON) {
                cachedFluxLevel = fluxLevel;
            }

            // 法线起伏控制：读取舰船/武器normal并调制护盾亮度
            if (normalUniformsAvailable) {
                GL20.glUniform4f(uniform[7], hasNormalRelief ? 1.0f : 0.0f, NORMAL_RELIEF_STRENGTH, 0.0f, 0.0f);
                GL20.glUniform3f(uniform[8], NORMAL_LIGHT_DIR_X, NORMAL_LIGHT_DIR_Y, NORMAL_LIGHT_DIR_Z);
            }

        } else {
            // 如果着色器未初始化，设置基础颜色（蓝色到红色渐变）
            float r = 0.2f + fluxLevel * 0.8f;
            float g = 0.4f - fluxLevel * 0.1f;
            float b = 1.0f - fluxLevel * 0.8f;
            GL11.glColor4f(r, g, b, combinedShieldAlpha);
        }

        // GPU优化：使用VBO渲染护盾四边形（替代立即模式）
        if (!vboInitialized) {
            initShieldQuadVBO(); // 尝试初始化VBO
        }

        if (vboInitialized) {
            // VBO渲染：预计算顶点，GPU缓存，高性能
            renderShieldQuadVBO(size.x, size.y);
        } else {
            // 回退到立即模式（VBO初始化失败时）
            float halfX = size.x * 0.5f;
            float halfY = size.y * 0.5f;
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0.0f, 0.0f);
            GL11.glVertex2f(-halfX, -halfY);
            GL11.glTexCoord2f(0.0f, uv.y);
            GL11.glVertex2f(-halfX, halfY);
            GL11.glTexCoord2f(uv.x, uv.y);
            GL11.glVertex2f(halfX, halfY);
            GL11.glTexCoord2f(uv.x, 0.0f);
            GL11.glVertex2f(halfX, -halfY);
            GL11.glEnd();
        }

        GL11.glPopMatrix();

        // 渲染击中效果
        renderHitEffects(ship, shield, hitFx, combinedShieldAlphaBase, shieldData, hasNormalRelief);

        // 清理OpenGL状态
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 255);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glPopAttrib();
    }

    private void renderHitEffects(ShipAPI ship, SpriteAPI shield, SpriteAPI hitFx, float combinedShieldAlphaBase,
            ShieldRenderData shieldData, boolean hasNormalRelief) {
        // 获取击中效果数据 - 支持两种护盾系统
        java.util.List hitDatas = null;

        if (Moci_GNShield_Script.hasShield(ship)) {
            // 原版GN护盾
            Moci_GNShield_Script script = Moci_GNShield_Script.getInstance(ship);
            if (script != null) {
                hitDatas = script.getHitDatas();
            }
        } else if (data.hullmods.Moci_GNFieldDefense_Script.hasShield(ship)) {
            // GN力场防御系统
            data.hullmods.Moci_GNFieldDefense_Script script = data.hullmods.Moci_GNFieldDefense_Script
                    .getInstance(ship);
            if (script != null) {
                hitDatas = script.getHitDatas();
            }
        }

        if (hitDatas == null || hitDatas.isEmpty()) {
            return;
        }

        Vector2f shieldCenterRel = computeShieldCenterRelativeToShip(ship, shieldData != null ? shieldData.center : null);
        float invShieldRenderSize = shieldData != null && shieldData.renderSize > 0f ? 1f / shieldData.renderSize : 0f;

        // 击中特效开始渲染

        GL11.glPushMatrix();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shield.getTextureId());
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, hitFx.getTextureId());
        if (normalUniformsAvailable) {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            int normalTexture = hasNormalRelief ? normalCompositeTexture : flatNormalTexture;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, normalTexture >= 0 ? normalTexture : 0);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        GL11.glTranslatef(ship.getLocation().x, ship.getLocation().y, 0.0f);
        GL11.glRotatef(ship.getFacing(), 0.0f, 0.0f, 1.0f);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        if (shaderInitialized && normalUniformsAvailable) {
            GL20.glUniform4f(uniform[7], hasNormalRelief ? 1.0f : 0.0f, HIT_NORMAL_RELIEF_STRENGTH, 0.0f, 0.0f);
            GL20.glUniform3f(uniform[8], NORMAL_LIGHT_DIR_X, NORMAL_LIGHT_DIR_Y, NORMAL_LIGHT_DIR_Z);
        }

        // 遍历击中数据（使用通用接口）
        for (Object hitObj : hitDatas) {
            // 两个系统使用相同的击中数据接口
            float hitLevel, hitSize;
            Vector2f rel;

            if (hitObj instanceof Moci_GNShield_Script.Moci_GNShieldHitData) {
                Moci_GNShield_Script.Moci_GNShieldHitData hit = (Moci_GNShield_Script.Moci_GNShieldHitData) hitObj;
                hitLevel = hit.getLevel();
                hitSize = hit.getSize();
                rel = hit.getRel();
            } else if (hitObj instanceof data.hullmods.Moci_GNFieldDefense_Script.Moci_GNShieldHitData) {
                data.hullmods.Moci_GNFieldDefense_Script.Moci_GNShieldHitData hit = (data.hullmods.Moci_GNFieldDefense_Script.Moci_GNShieldHitData) hitObj;
                hitLevel = hit.getLevel();
                hitSize = hit.getSize();
                rel = hit.getRel();
            } else {
                continue; // 未知类型，跳过
            }

            if (shaderInitialized) {
                // type = 1: 击中效果模式
                GL20.glUniform1f(uniform[2], 1.0f + combinedShieldAlphaBase);

                // state: (life, size, relX, relY)
                GL20.glUniform4f(uniform[3], hitLevel, hitSize,
                        rel.x / 256.0f, rel.y / 256.0f);

                // hit 分支会把 gnColor 临时当作法线映射参数读取。
                GL20.glUniform3f(uniform[4], shieldCenterRel.x, shieldCenterRel.y, invShieldRenderSize);
                GL20.glUniform1f(uniform[5], ship.getFluxLevel());
            }

            Vector2f size = new Vector2f(hitSize, hitSize);
            Vector2f relPos = rel;

            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0.0f, 0.0f);
            GL11.glVertex2f(-size.x + relPos.x, -size.y + relPos.y);
            GL11.glTexCoord2f(0.0f, 1.0f);
            GL11.glVertex2f(-size.x + relPos.x, size.y + relPos.y);
            GL11.glTexCoord2f(1.0f, 1.0f);
            GL11.glVertex2f(size.x + relPos.x, size.y + relPos.y);
            GL11.glTexCoord2f(1.0f, 0.0f);
            GL11.glVertex2f(size.x + relPos.x, -size.y + relPos.y);
            GL11.glEnd();
        }

        GL11.glPopMatrix();
    }

    // 着色器创建方法已移至Moci_ShaderManager

    public boolean isShaderInitialized() {
        return shaderInitialized;
    }

    /**
     * 判断是否应该跳过舰载机的护盾渲染
     * 两种情况会跳过：
     * 1. LunaSettings中"禁用舰载机特效"开关启用
     * 2. LunaSettings中"启用数量限制"开关启用且战场舰载机数量超过阈值
     */
    private boolean shouldSkipFighterRendering(ShipAPI ship) {
        if (!ship.isFighter()) {
            return false; // 不是舰载机，不跳过
        }

        // 从LunaSettings读取配置
        boolean skipFighter = getLunaSettingBoolean("Moci_gnShieldSkipFighter", true);
        boolean enableLimit = getLunaSettingBoolean("Moci_gnShieldEnableLimit", false);
        int fighterLimit = getLunaSettingInt("Moci_gnShieldFighterLimit", 150);

        // 情况1：全局开关启用
        if (skipFighter) {
            return true;
        }

        // 情况2：数量限制启用且超过阈值
        if (enableLimit && currentFighterCount > fighterLimit) {
            return true;
        }

        return false;
    }

    /**
     * 从LunaSettings读取布尔值配置
     */
    private boolean getLunaSettingBoolean(String key, boolean defaultValue) {
        try {
            if (Global.getSettings().getModManager().isModEnabled("lunalib")) {
                return lunalib.lunaSettings.LunaSettings.getBoolean("moci_shippack", key);
            }
        } catch (Exception e) {
            // LunaLib未安装或读取失败，使用默认值
        }
        return defaultValue;
    }

    /**
     * 从LunaSettings读取整数配置
     */
    private int getLunaSettingInt(String key, int defaultValue) {
        try {
            if (Global.getSettings().getModManager().isModEnabled("lunalib")) {
                return lunalib.lunaSettings.LunaSettings.getInt("moci_shippack", key);
            }
        } catch (Exception e) {
            // LunaLib未安装或读取失败，使用默认值
        }
        return defaultValue;
    }

    /**
     * 获取性能统计报告（可用于调试）
     */
    public static String getPerformanceStats() {
        return String.format("Shader Performance - Binds: %d, Uniform Updates: %d",
                shaderBindCount, uniformUpdateCount);
    }

    /**
     * 重置性能统计
     */
    public static void resetPerformanceStats() {
        shaderBindCount = 0;
        uniformUpdateCount = 0;
    }
}
