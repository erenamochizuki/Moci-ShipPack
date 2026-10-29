package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.awt.Color;
import java.util.EnumSet;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

/** Own rifle only: one visible recoil transform for the barrel, native fire point and attached flash. */
public class BeamRifleMount implements EveryFrameWeaponEffectPluginWithAdvanceAfter, WeaponEffectPluginWithInit {
    // Measured muzzle aperture in RX_105_arm_gun.png, PNG coordinates (top left origin).
    public static final float IMAGE_WIDTH = 102f, IMAGE_HEIGHT = 360f, MUZZLE_U = 37f, MUZZLE_V = 2f;
    // RX_104FF_right_gun_00.png is a separate 56 x 260 image. Its top cap is x12..22, y3.
    public static final float PENELOPE_IMAGE_WIDTH = 56f, PENELOPE_IMAGE_HEIGHT = 260f,
            PENELOPE_MUZZLE_U = 17f, PENELOPE_MUZZLE_V = 3f;
    private static final float MOCI_SOURCE_RECOIL = 6f, FLASH_SECONDS = 0.19f;
    private WeaponAPI bound;
    private float originalCenterX, originalCenterY, flashAge = FLASH_SECONDS;
    private CombatEngineAPI engine;
    private boolean rendererInstalled;

    @Override public void init(WeaponAPI weapon) {
        if (bound != null) { if (bound != weapon) throw new IllegalStateException("Rifle mount shared between instances"); return; }
        if (!BeamRiflePalette.isOwnRifle(weapon.getId())) throw new IllegalArgumentException("Wrong rifle for own muzzle mount");
        bound = weapon;
        SpriteAPI barrel = visibleGunSprite(weapon);
        originalCenterX = barrel.getCenterX(); originalCenterY = barrel.getCenterY();
        weapon.ensureClonedSpec();
        detach(weapon.getSpec().getTurretFireOffsets());
        detach(weapon.getSpec().getHardpointFireOffsets());
        detach(weapon.getSpec().getHiddenFireOffsets());
        synchronize();
    }

    @Override public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (bound == null) init(weapon);
        if (bound != weapon) throw new IllegalStateException("Rifle mount instance mismatch");
        this.engine = engine;
        if (!rendererInstalled) { engine.addLayeredRenderingPlugin(new AttachedVisuals()); rendererInstalled = true; }
        if (engine.isPaused() || amount <= 0f) return;
        // 步枪模式的所有者由这把武器自己创建；必须在 beforeWeapon 之前，否则首帧会被强制禁火。
        BeamRifleHost.ensure(weapon.getShip());
        BeamRifleModes.beforeWeapon(weapon);
        FullSalvoPose.beforeWeaponUpdate(weapon);
        flashAge = Math.min(FLASH_SECONDS, flashAge + amount);
        synchronize(); // Before the native trigger: the projectile is born at the visible pre-shot aperture.
    }

    @Override public void advanceAfter(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine.isPaused()) return;
        synchronize(); // Native cooldown and aiming have now advanced; match the displayed recoil pose.
        FullSalvoPose.applyWeaponPose(weapon);
        RifleFiringPose.apply(weapon);
        synchronize();
        BeamRifleModes.afterWeapon(weapon);
    }

    private void synchronize() {
        SpriteAPI barrel = visibleGunSprite(bound);
        // SUSPEND_RECOIL only stops the native renderer overwriting this centre.
        // Keep the original Moci noHide SOURCE cooldown recoil; both writers use
        // the same pure formula, so their order does not change the final pose.
        float recoil = bound.getCooldown() > 0f ? clamp(bound.getCooldownRemaining() / bound.getCooldown()) : 0f;
        if (bound.getSpec().hasTag("NO_RECOIL") || Boolean.TRUE.equals(bound.getShip().getCustomData().get("MOCI_BLADE_SWING_ACTIVE_RIGHT"))) recoil = 0f;
        Vector2f brace=RifleBracedPose.gunTranslation(bound);
        Vector2f shift = brace==null?RifleFiringPose.gunTranslation(bound):brace;
        double angle = Math.toRadians(bound.getCurrAngle());
        float forward = shift.x * (float)Math.cos(angle) + shift.y * (float)Math.sin(angle);
        float left = -shift.x * (float)Math.sin(angle) + shift.y * (float)Math.cos(angle);
        barrel.setCenter(originalCenterX + left, originalCenterY + MOCI_SOURCE_RECOIL * recoil - forward);
        Vector2f local = localMuzzle(bound, barrel);
        setOffsets(bound.getSpec().getTurretFireOffsets(), local);
        setOffsets(bound.getSpec().getHardpointFireOffsets(), local);
        setOffsets(bound.getSpec().getHiddenFireOffsets(), local);
    }

    /**
     * Idempotent end-of-bone reconciliation. Moci's ARM_R noHide callback also
     * owns the SOURCE recoil and can run after this gun's advanceAfter; repeat
     * only our pure sprite/offset transform, never the original callback.
     */
    public static void reconcileNativePose(WeaponAPI weapon) {
        if (!(weapon.getEffectPlugin() instanceof BeamRifleMount mount))
            throw new IllegalStateException("Owned visible rifle mount is missing");
        mount.synchronize();
    }
    public static Vector2f baseGunCenter(WeaponAPI weapon){if(!(weapon.getEffectPlugin() instanceof BeamRifleMount mount))throw new IllegalStateException("Rifle render adapter missing");return new Vector2f(mount.originalCenterX,mount.originalCenterY);}
    public static float nativeRecoilPixels(WeaponAPI weapon){return weapon.getSpec().hasTag("NO_RECOIL")||Boolean.TRUE.equals(weapon.getShip().getCustomData().get("MOCI_BLADE_SWING_ACTIVE_RIGHT"))?0:MOCI_SOURCE_RECOIL*(weapon.getCooldown()>0?clamp(weapon.getCooldownRemaining()/weapon.getCooldown()):0);}

    private static void setOffsets(java.util.List<Vector2f> offsets, Vector2f local) {
        for (Vector2f offset : offsets) offset.set(local);
    }
    private static void detach(java.util.List<Vector2f> offsets) {
        for (int i = 0; i < offsets.size(); i++) offsets.set(i, new Vector2f(offsets.get(i)));
    }

    private static Vector2f localMuzzle(WeaponAPI weapon, SpriteAPI barrel) {
        boolean penelope = BeamRiflePalette.PENELOPE_WEAPON.equals(weapon.getId());
        float imageWidth = penelope ? PENELOPE_IMAGE_WIDTH : IMAGE_WIDTH;
        float imageHeight = penelope ? PENELOPE_IMAGE_HEIGHT : IMAGE_HEIGHT;
        float muzzleU = penelope ? PENELOPE_MUZZLE_U : MUZZLE_U;
        float muzzleV = penelope ? PENELOPE_MUZZLE_V : MUZZLE_V;
        float right = muzzleU * barrel.getWidth() / imageWidth - barrel.getCenterX();
        float up = (imageHeight - muzzleV) * barrel.getHeight() / imageHeight - barrel.getCenterY();
        return new Vector2f(up, -right);
    }

    /** Independent PNG-to-world transform of the actual rendered gun layer, not getFirePoint(). */
    public static Vector2f visibleMuzzleWorld(WeaponAPI weapon) {
        Vector2f local = localMuzzle(weapon, visibleGunSprite(weapon));
        double angle = Math.toRadians(weapon.getCurrAngle());
        Vector2f pivot = weapon.getLocation();
        return new Vector2f(pivot.x + local.x * (float) Math.cos(angle) - local.y * (float) Math.sin(angle),
                pivot.y + local.x * (float) Math.sin(angle) + local.y * (float) Math.cos(angle));
    }

    public static void nativeShot(WeaponAPI weapon) {
        if (!(weapon.getEffectPlugin() instanceof BeamRifleMount mount)) throw new IllegalStateException("Own rifle muzzle mount is not installed");
        mount.flashAge = 0f;
    }

    /** Xi has a gun/barrel layer; Penelope's two native animation frames are on the main layer. */
    public static SpriteAPI visibleGunSprite(WeaponAPI weapon) {
        SpriteAPI barrel = BeamRiflePalette.PENELOPE_WEAPON.equals(weapon.getId())
                ? weapon.getSprite() : weapon.getBarrelSpriteAPI();
        if (barrel == null || barrel.getWidth() <= 0f || barrel.getHeight() <= 0f) throw new IllegalStateException("Own rifle gun sprite unavailable: " + weapon.getId());
        return barrel;
    }
    private static float clamp(float f) { return Math.max(0f, Math.min(1f, f)); }

    private final class AttachedVisuals extends BaseCombatLayeredRenderingPlugin {
        private final SpriteAPI core = Global.getSettings().getSprite("graphics/fx/Moci_magnum_fx_core.png");
        private final SpriteAPI fringe = Global.getSettings().getSprite("graphics/fx/Moci_magnum_fx_fringe.png");
        private final SpriteAPI bloom = Global.getSettings().getSprite("graphics/fx/hit_glow.png");
        @Override public EnumSet<CombatEngineLayers> getActiveLayers() { return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER); }
        @Override public float getRenderRadius() { return 4000f; }
        @Override public boolean isExpired() { return bound.getShip() == null || !bound.getShip().isAlive() || bound.getShip().wasRemoved(); }
        @Override public void advance(float amount) { if (entity != null) entity.getLocation().set(bound.getLocation()); }
        @Override public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            if (isExpired() || bound.getShip().isPhased()) return;
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            try {
                BeamRifleTrail.render(engine, bound, viewport);
                if (flashAge >= FLASH_SECONDS) return;
                Vector2f muzzle = visibleMuzzleWorld(bound);
                if (!viewport.isNearViewport(muzzle, 120f)) return;
                float alpha = clamp((FLASH_SECONDS - flashAge) / 0.16f);
                BeamRiflePalette palette = BeamRiflePalette.forWeapon(bound.getId());
                draw(bloom, muzzle, 118f, 0f, .60f * alpha, palette.primary);
                draw(fringe, muzzle, 94f, bound.getCurrAngle(), alpha, palette.inner);
                draw(fringe, muzzle, 112f, bound.getCurrAngle() + 90f, .35f * alpha, palette.spark);
                draw(core, muzzle, 58f, 0f, .75f * alpha, palette.inner);
                draw(bloom, muzzle, 32f, 0f, alpha, Color.WHITE);
            } finally { GL11.glPopAttrib(); }
        }
        private void draw(SpriteAPI sprite, Vector2f at, float size, float angle, float alpha, Color color) {
            sprite.setSize(size, size); sprite.setColor(color); sprite.setAlphaMult(alpha);
            sprite.setAngle(angle); sprite.setAdditiveBlend(); sprite.renderAtCenter(at.x, at.y);
        }
    }
}
