package data.scripts.everyframe;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.ProjectileSpawnType;
import com.fs.starfarer.api.util.IntervalUtil;
import java.awt.Color;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.plugins.MagicTrailPlugin;

public class Moci_ExcelsiorTrails extends BaseEveryFrameCombatPlugin {

    private static final String EXCELSIOR_4_PROJECTILE_ID = "Moci_excelsiorcannon_shot_4";
    private static final String EXCELSIOR_12_PROJECTILE_ID = "Moci_excelsiorcannon_shot_12";
    private static final String EXCELSIOR_24_PROJECTILE_ID = "Moci_excelsiorcannon_shot_24";
    private static final String EXCELSIOR_40_PROJECTILE_ID = "Moci_excelsiorcannon_shot_40";
    private static final String EXCELSIOR_60_PROJECTILE_ID = "Moci_excelsiorcannon_shot_60";

    private static final Color EXCELSIOR_TRAIL_COLOR = new Color(255, 100, 150);
    private static final Color EXCELSIOR_TRAIL2_COLOR = new Color(150, 100, 255);
    private static final Color EXCELSIOR_TRAIL3_COLOR_START = new Color(255, 100, 50);
    private static final Color EXCELSIOR_TRAIL3_COLOR_END = new Color(50, 100, 255);

    private static final float SIXTY_FPS = 1f / 60f;
    private static final String DATA_KEY = "Moci_ExcelsiorTrails";

    private CombatEngineAPI engine;

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        LocalData localData = (LocalData) engine.getCustomData().get(DATA_KEY);
        if (localData == null) {
            return;
        }

        Map<DamagingProjectileAPI, TrailData> trailMap = localData.trailMap;
        List<DamagingProjectileAPI> projectiles = engine.getProjectiles();

        for (DamagingProjectileAPI proj : projectiles) {
            String spec = proj.getProjectileSpecId();
            if (!isExcelsiorProjectile(spec)) {
                continue;
            }
            if (!engine.getViewport().isNearViewport(proj.getLocation(), getViewportRange(spec, proj))) {
                continue;
            }

            TrailData data = trailMap.get(proj);
            if (data == null) {
                data = createTrailData(spec);
                trailMap.put(proj, data);
            }

            float fade = computeFade(proj, data);
            if (fade <= 0f) {
                continue;
            }

            Vector2f sidewaysVel = computeSidewaysVelocity(proj);
            Vector2f spawnPosition = new Vector2f(proj.getLocation());
            if (proj.getSpawnType() != ProjectileSpawnType.BALLISTIC_AS_BEAM) {
                spawnPosition.x += sidewaysVel.x * amount * -1.05f;
                spawnPosition.y += sidewaysVel.y * amount * -1.05f;
            }

            if (data.interval == null) {
                data.interval = new IntervalUtil(SIXTY_FPS, SIXTY_FPS);
            }
            data.interval.advance(amount);
            if (!data.interval.intervalElapsed()) {
                continue;
            }

            switch (spec) {
                case EXCELSIOR_4_PROJECTILE_ID -> addTier4Trails(proj, data, spawnPosition, sidewaysVel, fade);
                case EXCELSIOR_12_PROJECTILE_ID -> addTier12Trails(proj, data, spawnPosition, sidewaysVel, fade);
                case EXCELSIOR_24_PROJECTILE_ID -> addTier24Trails(proj, data, spawnPosition, sidewaysVel, fade);
                case EXCELSIOR_40_PROJECTILE_ID -> addTier40Trails(proj, data, spawnPosition, sidewaysVel, fade);
                case EXCELSIOR_60_PROJECTILE_ID -> addTier60Trails(proj, data, spawnPosition, sidewaysVel, fade);
                default -> {
                }
            }
        }

        Iterator<DamagingProjectileAPI> iter = trailMap.keySet().iterator();
        while (iter.hasNext()) {
            DamagingProjectileAPI proj = iter.next();
            if (!engine.isEntityInPlay(proj)) {
                iter.remove();
            }
        }
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    public static void createIfNeeded() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }
        if (!engine.getCustomData().containsKey(DATA_KEY)) {
            engine.getCustomData().put(DATA_KEY, new LocalData());
            engine.addPlugin(new Moci_ExcelsiorTrails());
        }
    }

    private static boolean isExcelsiorProjectile(String spec) {
        return EXCELSIOR_4_PROJECTILE_ID.equals(spec)
                || EXCELSIOR_12_PROJECTILE_ID.equals(spec)
                || EXCELSIOR_24_PROJECTILE_ID.equals(spec)
                || EXCELSIOR_40_PROJECTILE_ID.equals(spec)
                || EXCELSIOR_60_PROJECTILE_ID.equals(spec);
    }

    private static float getViewportRange(String spec, DamagingProjectileAPI proj) {
        float velocity = proj.getVelocity().length();
        return switch (spec) {
            case EXCELSIOR_4_PROJECTILE_ID -> velocity * 0.2f;
            case EXCELSIOR_12_PROJECTILE_ID -> velocity * 0.25f;
            case EXCELSIOR_24_PROJECTILE_ID, EXCELSIOR_40_PROJECTILE_ID, EXCELSIOR_60_PROJECTILE_ID -> velocity * 0.3f;
            default -> velocity * 0.2f;
        };
    }

    private static TrailData createTrailData(String spec) {
        TrailData data = new TrailData();
        data.id = MagicTrailPlugin.getUniqueID();
        switch (spec) {
            case EXCELSIOR_12_PROJECTILE_ID -> data.id2 = MagicTrailPlugin.getUniqueID();
            case EXCELSIOR_24_PROJECTILE_ID -> {
                data.id2 = MagicTrailPlugin.getUniqueID();
                data.id3 = MagicTrailPlugin.getUniqueID();
            }
            case EXCELSIOR_40_PROJECTILE_ID -> {
                data.id2 = MagicTrailPlugin.getUniqueID();
                data.id3 = MagicTrailPlugin.getUniqueID();
                data.id4 = MagicTrailPlugin.getUniqueID();
            }
            case EXCELSIOR_60_PROJECTILE_ID -> {
                data.id2 = MagicTrailPlugin.getUniqueID();
                data.id3 = MagicTrailPlugin.getUniqueID();
                data.id4 = MagicTrailPlugin.getUniqueID();
                data.id5 = MagicTrailPlugin.getUniqueID();
            }
            default -> {
            }
        }
        return data;
    }

    private static float computeFade(DamagingProjectileAPI proj, TrailData data) {
        float fade = 1f;
        if (proj.getBaseDamageAmount() > 0f) {
            fade = proj.getDamageAmount() / proj.getBaseDamageAmount();
        }

        float velFacing = VectorUtils.getFacing(proj.getVelocity());
        float angleError = Math.abs(MathUtils.getShortestRotation(proj.getFacing(), velFacing));
        float angleFade = 1f - Math.min(Math.max(angleError - 45f, 0f) / 45f, 1f);
        fade *= angleFade;

        if (angleFade <= 0f) {
            if (!data.cut) {
                MagicTrailPlugin.cutTrailsOnEntity(proj);
                data.cut = true;
            }
        } else {
            data.cut = false;
        }

        return Math.max(0f, Math.min(1f, fade));
    }

    private static Vector2f computeSidewaysVelocity(DamagingProjectileAPI proj) {
        Vector2f projVel = new Vector2f(proj.getVelocity());
        Vector2f projBodyVel = VectorUtils.rotate(new Vector2f(projVel), -proj.getFacing());
        Vector2f projLateralBodyVel = new Vector2f(0f, projBodyVel.getY());
        return VectorUtils.rotate(new Vector2f(projLateralBodyVel), proj.getFacing());
    }

    private static void addTier4Trails(DamagingProjectileAPI proj, TrailData data, Vector2f spawnPosition, Vector2f sidewaysVel, float fade) {
        Vector2f offsetPosition = offsetAlongFacing(spawnPosition, proj.getFacing(), 6f);
        addTrail(proj, data.id, "Moci_trails_contrail", offsetPosition, sidewaysVel, proj.getFacing() - 180f,
                18f, 9f, EXCELSIOR_TRAIL_COLOR, EXCELSIOR_TRAIL_COLOR, fade * 0.4f,
                0f, 0f, 0.2f, 0f, 0f, 0f, -1f, 0f, -1);
    }

    private static void addTier12Trails(DamagingProjectileAPI proj, TrailData data, Vector2f spawnPosition, Vector2f sidewaysVel, float fade) {
        Vector2f offsetPosition = offsetAlongFacing(spawnPosition, proj.getFacing(), 8f);
        addTrail(proj, data.id, "Moci_trails_contrail", offsetPosition, sidewaysVel, proj.getFacing() - 180f,
                24f, 12f, EXCELSIOR_TRAIL_COLOR, EXCELSIOR_TRAIL_COLOR, fade * 0.55f,
                0f, 0f, 0.2f, 0f, 0f, 0f, -1f, 0f, -1);
        addTrail(proj, data.id2, "Moci_trails_smooth", offsetPosition, sidewaysVel, proj.getFacing() - 180f,
                20f, 30f, EXCELSIOR_TRAIL2_COLOR, EXCELSIOR_TRAIL2_COLOR, fade * 0.3f,
                0.05f, 0f, 0.2f, 0f, 0f, 0f, -1f, 0f, -1);
    }

    private static void addTier24Trails(DamagingProjectileAPI proj, TrailData data, Vector2f spawnPosition, Vector2f sidewaysVel, float fade) {
        Vector2f offsetPosition = offsetAlongFacing(spawnPosition, proj.getFacing(), 10f);
        addTrail(proj, data.id, "Moci_trails_contrail", offsetPosition, sidewaysVel, proj.getFacing() - 180f,
                30f, 15f, EXCELSIOR_TRAIL_COLOR, EXCELSIOR_TRAIL_COLOR, fade * 0.7f,
                0f, 0f, 0.2f, 0f, 0f, 0f, -1f, 0f, -1);
        addTrail(proj, data.id2, "Moci_trails_smooth", offsetPosition, sidewaysVel, proj.getFacing() - 180f,
                25f, 37.5f, EXCELSIOR_TRAIL2_COLOR, EXCELSIOR_TRAIL2_COLOR, fade * 0.45f,
                0.05f, 0f, 0.2f, 0f, 0f, 0f, -1f, 0f, -1);
        addTrail(proj, data.id3, "Moci_trail_zappy", offsetPosition, sidewaysVel, proj.getFacing() - 180f,
                12.5f, 6.25f, EXCELSIOR_TRAIL3_COLOR_START, EXCELSIOR_TRAIL3_COLOR_END, fade * 0.5f,
                0.05f, 0.05f, 0.2f, 0f, MathUtils.getRandomNumberInRange(0f, 600f),
                MathUtils.getRandomNumberInRange(-300f, 300f), -1f, 0f, -1);
    }

    private static void addTier40Trails(DamagingProjectileAPI proj, TrailData data, Vector2f spawnPosition, Vector2f sidewaysVel, float fade) {
        Vector2f offsetPosition = offsetAlongFacing(spawnPosition, proj.getFacing(), 12f);
        addTrail(proj, data.id, "Moci_trails_contrail", offsetPosition, sidewaysVel, proj.getFacing() - 180f,
                36f, 18f, EXCELSIOR_TRAIL_COLOR, EXCELSIOR_TRAIL_COLOR, fade * 0.85f,
                0f, 0f, 0.2f, 0f, 0f, 0f, -1f, 0f, -1);
        addTrail(proj, data.id2, "Moci_trails_smooth", offsetPosition, sidewaysVel, proj.getFacing() - 180f,
                30f, 45f, EXCELSIOR_TRAIL2_COLOR, EXCELSIOR_TRAIL2_COLOR, fade * 0.6f,
                0.05f, 0f, 0.2f, 0f, 0f, 0f, -1f, 0f, -1);
        addTrail(proj, data.id3, "Moci_trail_zappy", offsetPosition, sidewaysVel, proj.getFacing() - 180f,
                20f, 10f, EXCELSIOR_TRAIL3_COLOR_START, EXCELSIOR_TRAIL3_COLOR_END, fade * 0.6f,
                0.075f, 0.025f, 0.2f, 0f, MathUtils.getRandomNumberInRange(0f, 600f),
                MathUtils.getRandomNumberInRange(-300f, 300f), -1f, 0f, -1);
        addTrail(proj, data.id4, "Moci_trail_zappy", offsetPosition, sidewaysVel, proj.getFacing() - 180f,
                10f, 5f, EXCELSIOR_TRAIL3_COLOR_START, EXCELSIOR_TRAIL3_COLOR_END, fade * 0.6f,
                0.05f, 0.05f, 0.2f, 0f, MathUtils.getRandomNumberInRange(0f, 800f),
                MathUtils.getRandomNumberInRange(-400f, 400f), -1f, 0f, -1);
    }

    private static void addTier60Trails(DamagingProjectileAPI proj, TrailData data, Vector2f spawnPosition, Vector2f sidewaysVel, float fade) {
        Vector2f offsetPosition = offsetAlongFacing(spawnPosition, proj.getFacing(), 14f);
        addTrail(proj, data.id, "Moci_trails_contrail", offsetPosition, sidewaysVel, proj.getFacing() - 180f,
                42f, 21f, EXCELSIOR_TRAIL_COLOR, EXCELSIOR_TRAIL_COLOR, fade,
                0f, 0f, 0.2f, 0f, 0f, 0f, -1f, 0f, -1);
        addTrail(proj, data.id2, "Moci_trails_smooth", offsetPosition, sidewaysVel, proj.getFacing() - 180f,
                35f, 52.5f, EXCELSIOR_TRAIL2_COLOR, EXCELSIOR_TRAIL2_COLOR, fade * 0.85f,
                0.05f, 0f, 0.2f, 0f, 0f, 0f, -1f, 0f, -1);
        addTrail(proj, data.id3, "Moci_trail_zappy", offsetPosition, sidewaysVel, proj.getFacing() - 180f,
                26.25f, 13.125f, EXCELSIOR_TRAIL3_COLOR_START, EXCELSIOR_TRAIL3_COLOR_END, fade * 0.7f,
                0.1f, 0f, 0.2f, 0f, MathUtils.getRandomNumberInRange(0f, 600f),
                MathUtils.getRandomNumberInRange(-300f, 300f), -1f, 0f, -1);
        addTrail(proj, data.id4, "Moci_trail_zappy", offsetPosition, sidewaysVel, proj.getFacing() - 180f,
                17.5f, 8.75f, EXCELSIOR_TRAIL3_COLOR_START, EXCELSIOR_TRAIL3_COLOR_END, fade * 0.7f,
                0.075f, 0.025f, 0.2f, 0f, MathUtils.getRandomNumberInRange(0f, 800f),
                MathUtils.getRandomNumberInRange(-400f, 400f), -1f, 0f, -1);
        addTrail(proj, data.id5, "Moci_trail_zappy", offsetPosition, sidewaysVel, proj.getFacing() - 180f,
                8.75f, 4.375f, EXCELSIOR_TRAIL3_COLOR_START, EXCELSIOR_TRAIL3_COLOR_END, fade * 0.7f,
                0.05f, 0.05f, 0.2f, 0f, MathUtils.getRandomNumberInRange(0f, 1000f),
                MathUtils.getRandomNumberInRange(-500f, 500f), -1f, 0f, -1);
    }

    private static void addTrail(DamagingProjectileAPI proj, Float id, String spriteId, Vector2f position, Vector2f offsetVelocity,
                                 float angle, float startSize, float endSize, Color startColor, Color endColor, float opacity,
                                 float inDuration, float mainDuration, float outDuration,
                                 float startSpeed, float endSpeed, float endAngularVelocity,
                                 float textureLoopLength, float textureScrollSpeed, int textureOffset) {
        if (id == null) {
            return;
        }

        SpriteAPI sprite = Global.getSettings().getSprite("fx", spriteId);
        MagicTrailPlugin.addTrailMemberAdvanced(
                proj,
                id,
                sprite,
                position,
                startSpeed,
                endSpeed,
                angle,
                0f,
                endAngularVelocity,
                startSize,
                endSize,
                startColor,
                endColor,
                opacity,
                inDuration,
                mainDuration,
                outDuration,
                GL11.GL_SRC_ALPHA,
                GL11.GL_ONE,
                textureLoopLength,
                textureScrollSpeed,
                textureOffset,
                offsetVelocity,
                null,
                CombatEngineLayers.CONTRAILS_LAYER,
                1f
        );
    }

    private static Vector2f offsetAlongFacing(Vector2f position, float facing, float distance) {
        Vector2f result = new Vector2f(position);
        result.x += (float) Math.cos(Math.toRadians(facing)) * distance;
        result.y += (float) Math.sin(Math.toRadians(facing)) * distance;
        return result;
    }

    private static final class LocalData {

        final Map<DamagingProjectileAPI, TrailData> trailMap = new LinkedHashMap<>(100);
    }

    private static final class TrailData {

        Float id;
        Float id2;
        Float id3;
        Float id4;
        Float id5;
        IntervalUtil interval;
        boolean cut;
    }
}
