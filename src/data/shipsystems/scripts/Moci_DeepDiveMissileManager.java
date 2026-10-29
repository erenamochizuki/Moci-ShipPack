package data.shipsystems.scripts;

import java.awt.Color;
import java.util.Iterator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.boxutil.base.api.RenderDataAPI;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.DistortionEntity;
import org.boxutil.units.standard.entity.SpriteEntity;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;

import data.hullmods.Moci_PhaseSubLaunch;
import data.scripts.util.Moci_BoxBasedUtil;
import data.scripts.util.Moci_Util;

/**
 * TDD 深潜导弹的统一生命周期管理器。
 *
 * 只处理 TDD-1 的内置导弹和隐藏导弹。外置 source 武器仍由
 * Moci_DeepDiveSourceFrameWeaponEffect 在深潜期间禁止开火。
 */
public final class Moci_DeepDiveMissileManager {
    private static final String PHASE_TAG = "moci_tdd_deep_dive_phase_missile";
    private static final String BUILTIN_LEFT = "Moci_TDD_1_MISSILE_L";
    private static final String BUILTIN_RIGHT = "Moci_TDD_1_MISSILE_R";

    private static final float BREAK_RANGE = 600f;
    private static final float BREAK_SEARCH_PADDING = 1000f;
    private static final float TRANSIT_TIME = 0.2f;
    private static final float PHASE_ALPHA = 0.15f;
    private static final float PHASE_HP_MULT = 100f;
    private static final int PHASE_EMP_RESISTANCE = 10000;
    private static final Color FLASH_COLOR = new Color(210, 245, 255, 190);
    private static final Color ENGINE_COLOR = new Color(176, 96, 255, 220);
    private static final Color CONTRAIL_COLOR = new Color(170, 115, 255, 185);
    private static final Color GLOW_COLOR = new Color(170, 115, 255, 110);
    private static final Color GLOW_CORE_COLOR = new Color(225, 205, 255, 145);
    private static final String GLOW_PATH = "graphics/missiles/Moci_TDD_1_Missile_glow.png";

    private static final boolean ENABLE_MANUAL_GLOW_SPRITE = true;
    private static final boolean ENABLE_PHASE_EXIT_DISTORTION = true;

    private final Map<MissileAPI, PhaseData> active = new IdentityHashMap<>();
    // 包含未潜射和已经退出潜射的导弹，防止母舰再次深潜时重复转换。
    private final Map<MissileAPI, Boolean> observed = new WeakHashMap<>();

    Moci_DeepDiveMissileManager() {
    }

    /** 仅允许内置左右导弹和隐藏槽位的导弹进入潜射流程。 */
    public static boolean isAllowedWeapon(WeaponAPI weapon) {
        if (weapon == null || !Moci_DeepDiveWeaponControl.isTdd(weapon.getShip())
                || weapon.isDecorative() || weapon.getType() != WeaponAPI.WeaponType.MISSILE) {
            return false;
        }
        String id = weapon.getId();
        // SOURCE/FOLLOW 即使配置成隐藏槽也属于水面武器。
        if (weapon.getSlot() != null && (weapon.getSlot().getId().endsWith("_SOURCE")
                || weapon.getSlot().getId().endsWith("_FOLLOW"))) {
            return false;
        }
        return BUILTIN_LEFT.equals(id) || BUILTIN_RIGHT.equals(id)
                || (weapon.getSlot() != null && weapon.getSlot().isHidden());
    }

    public static boolean isBuiltinWeapon(WeaponAPI weapon) {
        if (weapon == null) {
            return false;
        }
        return BUILTIN_LEFT.equals(weapon.getId()) || BUILTIN_RIGHT.equals(weapon.getId());
    }

    /** 由内置武器的 onFire 或战斗插件调用；重复调用不会重复登记。 */
    public static void register(CombatEngineAPI engine, MissileAPI missile, boolean builtinVisual) {
        if (engine != null) {
            Moci_DeepDiveEnginePlugin.getInstance(engine).getMissileManager()
                    .registerMissile(engine, missile, builtinVisual);
        }
    }

    private void registerMissile(CombatEngineAPI engine, MissileAPI missile, boolean builtinVisual) {
        if (engine == null || missile == null || missile.getSourceAPI() == null
                || !Moci_DeepDive.isDeepDiveActive(missile.getSourceAPI())
                || !isAllowedWeapon(missile.getWeapon()) || missile.isFlare()
                || missile.isDecoyFlare() || missile.isExpired() || missile.isFizzling()
                || missile.didDamage() || missile.getHitpoints() <= 0f) {
            return;
        }
        if (missile.hasTag(PHASE_TAG)) {
            return;
        }

        missile.addTag(PHASE_TAG);
        Moci_PhaseSubLaunch.applySubLaunchBonus(missile);
        PhaseData data = new PhaseData(missile, builtinVisual);
        observed.put(missile, Boolean.TRUE);
        active.put(missile, data);
        applyPhasedState(engine, missile, data, 0f);
        engine.addHitParticle(missile.getLocation(), new Vector2f(), 42f, 1f, 0.16f, FLASH_COLOR);
        Global.getSoundPlayer().playSound("system_phase_cloak_activate", 1f, 1f,
                missile.getLocation(), missile.getVelocity());
    }

    /** 每帧由深潜战斗插件调用。会发现没有专用 onFire 脚本的隐藏导弹。 */
    public void advance(CombatEngineAPI engine, float amount) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        if (engine.isCombatOver()) {
            for (Map.Entry<MissileAPI, PhaseData> entry : active.entrySet()) {
                restore(entry.getKey(), entry.getValue());
                cleanup(entry.getValue());
            }
            active.clear();
            observed.clear();
            return;
        }

        for (MissileAPI missile : engine.getMissiles()) {
            if (missile == null || missile.hasTag(PHASE_TAG) || missile.getSourceAPI() == null
                    || !isAllowedWeapon(missile.getWeapon()) || observed.containsKey(missile)) {
                continue;
            }
            observed.put(missile, Boolean.TRUE);
            ShipAPI source = missile.getSourceAPI();
            // 插件延迟注册时也不收编已经飞行多帧的弹体；兼容母舰的局部时流。
            float spawnWindow = amount * Math.max(1f, source.getMutableStats().getTimeMult().getModifiedValue()) + 0.001f;
            if (missile.getElapsed() <= spawnWindow && Moci_DeepDive.isDeepDiveActive(source)) {
                registerMissile(engine, missile, isBuiltinWeapon(missile.getWeapon()));
            }
        }

        Iterator<Map.Entry<MissileAPI, PhaseData>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<MissileAPI, PhaseData> entry = iterator.next();
            MissileAPI missile = entry.getKey();
            PhaseData data = entry.getValue();
            if (missile.didDamage() || missile.isExpired() || missile.isFizzling() || missile.getHitpoints() <= 0f
                    || !engine.isInPlay(missile)) {
                restore(missile, data);
                cleanup(data);
                iterator.remove();
                continue;
            }

            if (!data.emerging && shouldBreakPhase(missile)) {
                data.emerging = true;
                data.emergeElapsed = 0f;
                if (data.builtinVisual) {
                    missile.fadeOutThenIn(TRANSIT_TIME);
                }
                if (data.builtinVisual && ENABLE_PHASE_EXIT_DISTORTION) {
                    createExitDistortion(missile);
                }
                engine.addHitParticle(missile.getLocation(), new Vector2f(),
                        data.builtinVisual ? 52f : 42f, 1f, data.builtinVisual ? 0.15f : 0.16f, FLASH_COLOR);
                Global.getSoundPlayer().playSound("system_phase_cloak_deactivate", 1f, 1f,
                        missile.getLocation(), missile.getVelocity());
            }

            if (!data.emerging) {
                applyPhasedState(engine, missile, data, amount);
            } else {
                data.emergeElapsed += amount;
                float progress = Math.min(1f, data.emergeElapsed / TRANSIT_TIME);
                applyEmergingState(engine, missile, data, progress, amount);
                if (progress >= 1f) {
                    restore(missile, data);
                    cleanup(data);
                    iterator.remove();
                }
            }
        }
    }

    private static final class PhaseData {
        private final CollisionClass collision;
        private final int empResistance;
        private final float hitpoints;
        private final float alphaOverride;
        private final boolean builtinVisual;
        private SpriteEntity glow;
        private float pulse;
        private boolean emerging;
        private float emergeElapsed;
        private float particleElapsed;

        private PhaseData(MissileAPI missile, boolean builtinVisual) {
            collision = missile.getCollisionClass();
            empResistance = missile.getEmpResistance();
            hitpoints = missile.getHitpoints();
            alphaOverride = missile.getSpriteAlphaOverride();
            this.builtinVisual = builtinVisual;
            pulse = (float) (Math.random() * Math.PI * 2.0);
            if (builtinVisual && ENABLE_MANUAL_GLOW_SPRITE) {
                glow = createGlow(missile);
            }
        }
    }

    private static void applyPhasedState(CombatEngineAPI engine, MissileAPI missile, PhaseData data, float amount) {
        missile.setCollisionClass(CollisionClass.NONE);
        missile.setEmpResistance(PHASE_EMP_RESISTANCE);
        missile.setHitpoints(Math.max(missile.getHitpoints(), missile.getMaxHitpoints() * PHASE_HP_MULT));
        missile.setSpriteAlphaOverride(PHASE_ALPHA * missile.getCurrentBaseAlpha());
        tintEngine(missile, 1f);
        if (data.glow != null) {
            updateGlow(data, amount, 1f);
        }
        if (!data.builtinVisual) {
            data.particleElapsed += amount;
            if (data.particleElapsed >= 0.08f) {
                data.particleElapsed %= 0.08f;
                engine.addSmoothParticle(missile.getLocation(), missile.getVelocity(),
                        Math.max(6f, missile.getCollisionRadius() * 2f), 0.45f, 0.2f, CONTRAIL_COLOR);
            }
        }
    }

    private static void applyEmergingState(CombatEngineAPI engine, MissileAPI missile, PhaseData data,
            float progress, float amount) {
        float strength = Math.max(0f, 1f - progress);
        missile.setCollisionClass(CollisionClass.NONE);
        missile.setEmpResistance(PHASE_EMP_RESISTANCE);
        missile.setHitpoints(Math.max(missile.getHitpoints(), missile.getMaxHitpoints() * PHASE_HP_MULT));
        missile.setSpriteAlphaOverride(Math.max(PHASE_ALPHA, progress) * missile.getCurrentBaseAlpha());
        tintEngine(missile, strength);
        if (data.glow != null) {
            updateGlow(data, amount, strength);
        }
    }

    private static void tintEngine(MissileAPI missile, float strength) {
        if (missile.getEngineController() == null) {
            return;
        }
        missile.getEngineController().fadeToOtherColor(Moci_DeepDiveMissileManager.class,
                ENGINE_COLOR, CONTRAIL_COLOR, strength, strength);
        missile.getEngineController().extendFlame(Moci_DeepDiveMissileManager.class,
                -0.75f * strength, -0.18f * strength, -0.55f * strength);
    }

    private static boolean shouldBreakPhase(MissileAPI missile) {
        List<ShipAPI> enemies = Moci_Util.getEnemyShipsInRange(
                missile.getLocation(), BREAK_RANGE + BREAK_SEARCH_PADDING, missile.getOwner());
        for (ShipAPI enemy : enemies) {
            if (enemy == null || enemy.isHulk() || enemy.isFighter() || enemy.isDrone()) {
                continue;
            }
            float range = BREAK_RANGE + enemy.getCollisionRadius();
            if (MathUtils.getDistanceSquared(missile.getLocation(), enemy.getLocation()) <= range * range) {
                return true;
            }
        }
        return false;
    }

    private static void restore(MissileAPI missile, PhaseData data) {
        missile.setCollisionClass(data.collision);
        missile.setEmpResistance(data.empResistance);
        missile.setHitpoints(Math.min(missile.getHitpoints(), Math.min(data.hitpoints, missile.getMaxHitpoints())));
        missile.setSpriteAlphaOverride(data.alphaOverride);
        tintEngine(missile, 0f);
    }

    private static void cleanup(PhaseData data) {
        if (data != null && data.glow != null && !data.glow.hasDelete()) {
            data.glow.delete();
        }
        if (data != null) {
            data.glow = null;
        }
    }

    private static void createExitDistortion(MissileAPI missile) {
        DistortionEntity distortion = new DistortionEntity();
        distortion.setGlobalTimer(0f, 0f, 0.25f);
        distortion.setInnerFull(0.2f, 0.2f);
        distortion.setInnerHardness(0.5f);
        distortion.setRingHardness(0.5f);
        distortion.setSizeIn(0f, 0f);
        distortion.setSizeFull(120f, 120f);
        distortion.setSizeOut(240f, 240f);
        distortion.setPowerIn(0f);
        distortion.setPowerFull(0.75f);
        distortion.setPowerOut(0f);
        distortion.setLocation(new Vector2f(missile.getLocation()));
        CombatRenderingManager.addEntity(distortion);
    }

    private static SpriteEntity createGlow(MissileAPI missile) {
        SpriteAPI sprite = Global.getSettings().getSprite(GLOW_PATH);
        if (sprite == null) {
            return null;
        }
        SpriteEntity entity = Moci_BoxBasedUtil.addSpriteToTarget(sprite, missile, 0f, 9999f,
                0.2f, CombatEngineLayers.ABOVE_PARTICLES_LOWER);
        entity.setControlData(new GlowControlData(missile));
        entity.setAdditiveBlend();
        entity.setEmissiveSprite(sprite);
        entity.setBaseSizePerTiles(12f, 57f);
        entity.getMaterialData().setColor(GLOW_COLOR);
        entity.getMaterialData().setColorAlpha(0.55f);
        entity.getMaterialData().setEmissiveColor(GLOW_CORE_COLOR);
        entity.getMaterialData().setEmissiveColorAlpha(0.73f);
        entity.getMaterialData().setColorToEmissive(1f);
        entity.getMaterialData().setAlphaToEmissive(1f);
        entity.getMaterialData().setIgnoreIllumination(true);
        return entity;
    }

    private static void updateGlow(PhaseData data, float amount, float strength) {
        if (data.glow == null || data.glow.hasDelete()) {
            return;
        }
        data.pulse += amount * 4f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(data.pulse);
        float alpha = 0.55f * Math.max(0.15f, strength);
        data.glow.setBaseSizePerTiles(12f + 1.25f * pulse * strength,
                57f + 1.25f * pulse * strength);
        data.glow.getMaterialData().setColorAlpha(alpha);
        data.glow.getMaterialData().setEmissiveColorAlpha(Math.min(1f, alpha + 0.18f * strength));
    }

    private static final class GlowControlData extends Moci_BoxBasedUtil.EntityBasedControlData {
        private GlowControlData(MissileAPI missile) {
            super(missile);
        }

        @Override
        public void controlAdvance(RenderDataAPI renderEntity, float amount) {
            if (entity == null) {
                return;
            }
            renderEntity.setLocation(entity.getLocation());
            if (withFacing) {
                renderEntity.setFacingScale(MathUtils.clampAngle(entity.getFacing() - 90f), 1f, 1f);
            }
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null || !isEntityValid(engine, entity)) {
                renderEntity.getGlobalTimer()[0] = Math.min(1f, renderEntity.getGlobalTimer()[0]);
            }
        }
    }
}
