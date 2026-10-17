package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import data.scripts.visual.Moci_CloakClothRenderer;
import java.util.concurrent.ConcurrentHashMap;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

/**
 * 斗篷布料控制器（全新实现）
 *
 * <p>算法核心：</p>
 * <p>1) Verlet 节点积分</p>
 * <p>2) 距离约束 + 弯曲约束 + 船体碰撞约束</p>
 * <p>3) OpenGL 三角带贴图渲染</p>
 */
public class Moci_CloakClothWeaponEffect implements EveryFrameWeaponEffectPlugin {

    private static final String KEY_PREFIX = "MOCI_CLOAK_SIM_";

    // ===== 默认外观参数 =====
    protected String texturePath = "graphics/weapons/Moci_cloak.png";
    protected int nodeCount = 18;
    protected float cloakLength = 260f;
    protected float frontWidth = 64f;
    protected float endWidth = 160f;
    protected float angleOffset = 180f;

    // ===== 默认物理参数 =====
    protected int constraintIterations = 5;
    protected float damping = 0.055f;
    protected float gravity = 35f;
    protected float baselineBackPull = 95f;
    protected float velocityInfluence = 0.32f;
    protected float flutterAmplitude = 28f;
    protected float flutterFrequency = 5.4f;
    protected float bendRelax = 0.28f;
    protected float hullCollisionPadding = 10f;

    private static CombatEngineAPI cachedEngine = null;
    private static Moci_CloakClothRenderer renderer = null;
    private static final ConcurrentHashMap<String, Moci_CloakClothRenderer.CloakState> STATES =
            new ConcurrentHashMap<String, Moci_CloakClothRenderer.CloakState>();

    private boolean initialized = false;
    private String key = null;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || weapon == null || engine.isPaused()) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null) {
            return;
        }

        ensureRenderer(engine);

        if (!initialized) {
            key = buildKey(ship, weapon);
            Moci_CloakClothRenderer.CloakState state = createState(ship);
            if (state != null) {
                STATES.put(key, state);
            }
            initialized = true;
        }

        if (key == null) {
            return;
        }
        Moci_CloakClothRenderer.CloakState state = STATES.get(key);
        if (state == null) {
            return;
        }

        if (!ship.isAlive() || ship.isHulk()) {
            state.valid = false;
            STATES.remove(key);
            return;
        }

        // 每帧同步参数（支持子类实时改参数）
        state.length = cloakLength;
        state.frontWidth = frontWidth;
        state.endWidth = endWidth;
        state.constraintIterations = constraintIterations;
        state.damping = damping;
        state.gravity = gravity;
        state.baselineBackPull = baselineBackPull;
        state.velocityInfluence = velocityInfluence;
        state.flutterAmplitude = flutterAmplitude;
        state.flutterFrequency = flutterFrequency;
        state.bendRelax = bendRelax;
        state.hullCollisionPadding = hullCollisionPadding;

        float alpha = 1f;
        if (ship.isPhased()) {
            alpha *= 0.35f;
        }
        if (ship.getFluxTracker().isOverloadedOrVenting()) {
            alpha *= 0.65f;
        }
        state.alphaMult = alpha;

        Vector2f anchor = weapon.getLocation();
        float backFacing = MathUtils.clampAngle(weapon.getCurrAngle() + angleOffset);
        state.simulate(amount, anchor, backFacing);
    }

    private void ensureRenderer(CombatEngineAPI engine) {
        if (cachedEngine != engine || renderer == null) {
            STATES.clear();
            renderer = new Moci_CloakClothRenderer(STATES);
            engine.addLayeredRenderingPlugin(renderer);
            cachedEngine = engine;
        }
    }

    private String buildKey(ShipAPI ship, WeaponAPI weapon) {
        String slot = weapon.getSlot() != null ? weapon.getSlot().getId() : weapon.getId();
        return KEY_PREFIX + ship.getId() + "_" + slot;
    }

    private Moci_CloakClothRenderer.CloakState createState(ShipAPI ship) {
        SpriteAPI tex = null;
        try {
            tex = Global.getSettings().getSprite(texturePath);
        } catch (Throwable ignored) {
        }
        if (tex == null) {
            return null;
        }

        Moci_CloakClothRenderer.CloakState state = new Moci_CloakClothRenderer.CloakState();
        state.ship = ship;
        state.texture = tex;
        state.length = cloakLength;
        state.frontWidth = frontWidth;
        state.endWidth = endWidth;
        state.alphaMult = 1f;
        state.valid = true;

        int count = Math.max(4, nodeCount);
        for (int i = 0; i < count; i++) {
            state.points.add(new Moci_CloakClothRenderer.ClothPoint());
        }
        return state;
    }
}

