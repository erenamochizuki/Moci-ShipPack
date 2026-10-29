package xi.arsenal;

import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.util.Objects;
import org.lwjgl.util.vector.Vector2f;

/**
 * Measured original-canvas grip anchors for the ordinary Hi-Nu's two hand guns.
 * Pixel coordinates start at the upper left; all returned vectors are copies.
 * Pure geometry: this class never changes a sprite, a slot or a limb location.
 * Evidence and SVG overlays: work/hi-nu-hand-v2/grips/grip-anchors.json.
 */
public final class HiNuGripAnchors {
    private HiNuGripAnchors() { }

    /** The contact point is on the lower grip, covered by the native closed fist.
     * It is deliberately distinct from the visible grip silhouette's midpoint. */
    public record Profile(String weaponId, String spritePath, boolean barrelLayer,
                          float canvasWidth, float canvasHeight, float gripX, float gripY,
                          float visualGripX, float visualGripY) { }

    private static final String ART = "graphics/ships/Mobile_Suit/Moci_RX_93_v2/arms/";
    public static final Profile RIFLE = new Profile("RX_93_v2_arm_right_gun_rifle",
            ART + "RX_93_v2_arm_right_gun_rifle.png", true, 52, 260, 38, 83, 39, 79);
    public static final Profile BAZOOKA = new Profile("RX_93_v2_arm_right_gun_bazooka",
            ART + "RX_93_v2_arm_right_gun_bazooka.png", false, 52, 260, 38, 83, 40, 79);
    /** Art-only palm reference, not a new location or a replacement native carry transform. */
    public static final float ORIGINAL_ART_PALM_X = 39, ORIGINAL_ART_PALM_Y = 85;
    public static final float OWN_ARM_ART_PALM_X = 117, OWN_ARM_ART_PALM_Y = 225;

    public static Profile profile(WeaponAPI held) {
        Objects.requireNonNull(held, "Hi-Nu held weapon");
        return profile(held.getId());
    }

    public static Profile profile(String weaponId) {
        if (RIFLE.weaponId().equals(weaponId)) return RIFLE;
        if (BAZOOKA.weaponId().equals(weaponId)) return BAZOOKA;
        throw new IllegalArgumentException("No measured Hi-Nu grip for " + weaponId);
    }

    /** Rifle PNG is turretGunSprite; bazooka PNG is turretSprite in the native .wpn. */
    public static SpriteAPI nativePart(WeaponAPI held) {
        Profile p = profile(held);
        return Objects.requireNonNull(p.barrelLayer() ? held.getBarrelSpriteAPI() : held.getSprite(),
                "Measured Hi-Nu native gun sprite");
    }

    /** Sprite-local units: x right, y up, relative to the current rotation center.
     * Sprite centers are already in rendered units and must not be scaled again.
     * Native Sprite.render falls back to half size if either center is -1. */
    public static Vector2f pixelLocal(SpriteAPI sprite, float canvasWidth, float canvasHeight,
                                      float pixelX, float pixelY) {
        Objects.requireNonNull(sprite, "Measured sprite");
        finite(canvasWidth, canvasHeight, pixelX, pixelY, sprite.getWidth(), sprite.getHeight(),
                sprite.getCenterX(), sprite.getCenterY());
        if (canvasWidth <= 0 || canvasHeight <= 0 || sprite.getWidth() <= 0 || sprite.getHeight() <= 0)
            throw new IllegalArgumentException("Positive sprite/canvas dimensions required");
        boolean explicitCenter = sprite.getCenterX() != -1 && sprite.getCenterY() != -1;
        float cx = explicitCenter ? sprite.getCenterX() : sprite.getWidth() * .5f;
        float cy = explicitCenter ? sprite.getCenterY() : sprite.getHeight() * .5f;
        return new Vector2f(pixelX * sprite.getWidth() / canvasWidth - cx,
                (canvasHeight - pixelY) * sprite.getHeight() / canvasHeight - cy);
    }

    public static Vector2f gunGripLocal(SpriteAPI nativePart, Profile p) {
        Objects.requireNonNull(p, "Measured grip profile");
        return pixelLocal(nativePart, p.canvasWidth(), p.canvasHeight(), p.gripX(), p.gripY());
    }

    /** World origin is the same point passed to SpriteAPI.renderAtCenter.
     * spriteAngle is a sprite angle; native weapon currAngle needs minus 90 degrees. */
    public static Vector2f pixelToWorld(SpriteAPI sprite, float canvasWidth, float canvasHeight,
                                        float pixelX, float pixelY, Vector2f origin, float spriteAngle) {
        return world(origin, pixelLocal(sprite, canvasWidth, canvasHeight, pixelX, pixelY), spriteAngle);
    }

    public static Vector2f gripWorld(Profile p, SpriteAPI sprite, Vector2f origin, float spriteAngle) {
        return world(origin, gunGripLocal(sprite, p), spriteAngle);
    }

    /** Current, already-installed native gun's actual lower-grip contact point. */
    public static Vector2f gripWorld(WeaponAPI held) {
        return gripWorld(profile(held), nativePart(held), renderOrigin(held), held.getCurrAngle() - 90f);
    }

    /** Inverse of gripWorld for positioning an owned free-gun sprite continuously. */
    public static Vector2f originForGrip(Profile p, SpriteAPI sprite, Vector2f worldGrip, float spriteAngle) {
        Objects.requireNonNull(worldGrip, "World grip"); finite(worldGrip.x, worldGrip.y);
        Vector2f offset = rotate(gunGripLocal(sprite, p), spriteAngle);
        return new Vector2f(worldGrip.x - offset.x, worldGrip.y - offset.y);
    }

    /** Capture once before pickup writes either joint angle. The result is the
     * approved carry grip in arm-local axes: x forward, y left. This retains the
     * actual native layer's center, size and the arm/source pivot relationship. */
    public static Vector2f palmLocal(WeaponAPI arm, WeaponAPI held) {
        Objects.requireNonNull(arm, "Hi-Nu right arm");
        Vector2f grip = gripWorld(held);
        Vector2f origin = renderOrigin(arm);
        return rotate(new Vector2f(grip.x - origin.x, grip.y - origin.y),
                -arm.getCurrAngle());
    }

    /** Registered palm follows rotation only; no limb location/scale/center changes. */
    public static Vector2f handWorld(WeaponAPI arm, Vector2f capturedPalmLocal) {
        Objects.requireNonNull(arm, "Hi-Nu right arm");
        return world(renderOrigin(arm), capturedPalmLocal, arm.getCurrAngle());
    }

    /** Convenience sample while held and arm still have the normal carry relation.
     * Animated pickup should retain the palmLocal captured at entry instead. */
    public static Vector2f handWorld(WeaponAPI arm, WeaponAPI held) {
        return handWorld(arm, palmLocal(arm, held));
    }

    /** Rotation-only solution points the captured palm vector toward a world grip.
     * It does not make an unreachable target reachable; caller checks radial error. */
    public static float solveArmAngle(Vector2f pivot, Vector2f capturedPalmLocal, Vector2f target) {
        Objects.requireNonNull(pivot); Objects.requireNonNull(capturedPalmLocal); Objects.requireNonNull(target);
        finite(pivot.x, pivot.y, capturedPalmLocal.x, capturedPalmLocal.y, target.x, target.y);
        if (capturedPalmLocal.lengthSquared() < .0001f)
            throw new IllegalArgumentException("Nonzero registered palm radius required");
        double targetAngle = Math.atan2(target.y - pivot.y, target.x - pivot.x);
        double localAngle = Math.atan2(capturedPalmLocal.y, capturedPalmLocal.x);
        return (float) Math.toDegrees(targetAngle - localAngle);
    }

    public static float solveArmAngle(WeaponAPI arm, WeaponAPI held, Vector2f target) {
        return solveArmAngle(renderOrigin(arm), palmLocal(arm, held), target);
    }

    /** Current native render origin, retaining the engine's decorative-beam offset.
     * That offset is in ship-local forward/left axes, not sprite pixel axes. */
    public static Vector2f renderOrigin(WeaponAPI weapon) {
        Objects.requireNonNull(weapon, "Hi-Nu weapon");
        Vector2f origin = new Vector2f(weapon.getLocation());
        Vector2f offset = weapon.isDecorative()
                ? weapon.getRenderOffsetForDecorativeBeamWeaponsOnly() : null;
        if (offset != null && weapon.getShip() != null) {
            Vector2f rotated = rotate(offset, weapon.getShip().getFacing());
            origin.x += rotated.x; origin.y += rotated.y;
        }
        return origin;
    }

    /** Original art palm converted through the actual own-arm rest-frame sprite.
     * This reference is valid at frame zero; cannon frames have a different silhouette. */
    public static Vector2f artPalmWorld(WeaponAPI arm) {
        return pixelToWorld(arm.getSprite(), 208, 540, OWN_ARM_ART_PALM_X, OWN_ARM_ART_PALM_Y,
                renderOrigin(arm), arm.getCurrAngle() - 90f);
    }

    private static Vector2f world(Vector2f origin, Vector2f local, float degrees) {
        Objects.requireNonNull(origin, "World sprite origin");
        finite(origin.x, origin.y, degrees);
        Vector2f offset = rotate(local, degrees);
        return new Vector2f(origin.x + offset.x, origin.y + offset.y);
    }

    private static Vector2f rotate(Vector2f local, float degrees) {
        Objects.requireNonNull(local, "Local anchor"); finite(local.x, local.y, degrees);
        double a = Math.toRadians(degrees), c = Math.cos(a), s = Math.sin(a);
        return new Vector2f((float) (local.x * c - local.y * s), (float) (local.x * s + local.y * c));
    }

    private static void finite(float... values) {
        for (float value : values) if (!Float.isFinite(value))
            throw new IllegalArgumentException("Finite Hi-Nu grip geometry required");
    }
}
