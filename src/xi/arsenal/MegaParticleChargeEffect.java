package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.awt.Color;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;

/**
 * Xi's native beam charging visuals, adapted from Moci_Magnum_EveryFrame.
 * Uses the original Unicorn textures, three charge layers, smoke and firing sound.
 * The beam itself and damage remain owned by the native beam weapon and the
 * this pack's sustained MegaParticleBeamEffect. Never invokes another callback.
 *
 * Each weapon instance owns its three Sprite wrappers and original dimensions.
 * getSprite(filename) creates a new underlying Sprite, whereas category/key
 * lookup wraps the registered shared Sprite. MagicRender mutates its argument.
 */
public final class MegaParticleChargeEffect implements EveryFrameWeaponEffectPlugin {
    // Two simultaneous emitters; Penelope's three short cycles also overlap the
    // original 4.3-second sound tail. Keep this pack's gain below the original.
    public static final float XI_FIRE_VOLUME = 0.30f;
    public static final float PENELOPE_FIRE_VOLUME = 0.25f;
    private static final float INTERVAL = 0.05f;
    private static final Color SMOKE = new Color(120, 120, 120, 100);
    private static final Color CORE = new Color(255, 255, 255, 220);
    private static final Color FRINGE = new Color(53, 208, 255, 180);
    private static final String[] TEXTURES = {
        "graphics/fx/Moci_magnum_fx_blink.png",
        "graphics/fx/Moci_magnum_fx_core.png",
        "graphics/fx/Moci_magnum_fx_fringe.png"
    };

    private final SpriteAPI[] sprites = new SpriteAPI[3];
    private final float[] spriteWidths = new float[3];
    private final float[] spriteHeights = new float[3];
    private float lastCharge;
    private float lastBlinkLevel;
    private float blinkTimer = -1f;
    private float blinkSize = 1f;
    private float coreRotation;
    private float fringeRotation;
    private float breathePhase;
    private float smokeTimer;
    private float particleTimer;
    private float cooldownSmoke;
    private boolean wasPeak;
    private boolean soundPlayed;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || amount <= 0f) return;
        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            resetChargeSprites(); soundPlayed = false; wasPeak = false;
            lastCharge = 0f; smokeTimer = 0f; particleTimer = 0f; cooldownSmoke = 0f;
            return;
        }

        float charge = weapon.getChargeLevel();
        float deltaCharge = charge - lastCharge;
        boolean firing = weapon.isFiring();
        boolean nativeBeamVisible = weapon.getBeams().stream().anyMatch(b -> b.getBrightness() > 0.01f);
        boolean peak = charge >= 1f && firing;
        boolean finished = wasPeak && charge < 1f;
        if (finished) cooldownSmoke = 1f;
        wasPeak = peak;

        // Spatialized original sound, once per real fully charged firing cycle.
        if (peak && !soundPlayed) {
            float volume = "Moci_fw_penelope_mega_particle".equals(weapon.getId())
                    ? PENELOPE_FIRE_VOLUME : XI_FIRE_VOLUME;
            Global.getSoundPlayer().playSound("Moci_Magnum_03", 1f, volume,
                    weapon.getLocation(), ship.getVelocity());
            soundPlayed = true;
        }
        // A sustained beam may briefly report !isFiring around native stage
        // transitions. Rearm the sound only when the whole cycle is fully idle.
        if (charge <= 0.0001f && !nativeBeamVisible) soundPlayed = false;

        cooldownSmoke = Math.max(0f, cooldownSmoke - amount * 1.5f);
        if (!engine.getViewport().isNearViewport(weapon.getLocation(), 400f)) {
            resetChargeSprites();
            smokeTimer = 0f;
            particleTimer = 0f;
            lastCharge = charge;
            return;
        }

        // FirePoint is already in world space; do not rotate or add ship position.
        Vector2f muzzle = new Vector2f(weapon.getFirePoint(0));
        boolean showCharge = charge > 0f && firing && deltaCharge >= -0.0001f && !finished;
        if (showCharge) chargeSprites(amount, muzzle, charge, deltaCharge);
        else resetChargeSprites();

        if (firing && charge >= 0.9f) {
            smokeTimer += amount;
            particleTimer += amount;
            // At most one bounded visual batch per tick, including a long frame.
            if (smokeTimer >= INTERVAL) {
                smokeTimer %= INTERVAL;
                smoke(engine, weapon, muzzle, 1f);
            }
            if (particleTimer >= INTERVAL) {
                particleTimer %= INTERVAL;
                muzzleParticles(engine, ship, muzzle);
            }
        } else {
            particleTimer = 0f;
            if (cooldownSmoke > 0f) {
                smokeTimer += amount;
                if (smokeTimer >= INTERVAL) {
                    smokeTimer %= INTERVAL;
                    smoke(engine, weapon, muzzle, cooldownSmoke);
                }
            } else smokeTimer = 0f;
        }
        lastCharge = charge;
    }

    private void chargeSprites(float amount, Vector2f muzzle, float charge, float deltaCharge) {
        float blinkDriver = charge;
        if (charge >= 1f && Math.abs(deltaCharge) < 0.0001f) {
            blinkDriver = lastBlinkLevel + amount;
        }
        if (Math.floor(blinkDriver / 0.1f) > Math.floor(lastBlinkLevel / 0.1f)) {
            blinkTimer = 0.05f;
            blinkSize = random(0.8f, 1f);
        }
        lastBlinkLevel = blinkDriver;
        blinkTimer -= amount;
        if (blinkTimer >= 0f) draw(0, muzzle, blinkSize, 0f);

        float step = deltaCharge / 0.1f;
        if (charge >= 1f && Math.abs(step) < 0.0001f) step = 4f * amount;
        coreRotation = (coreRotation - 90f * step) % 360f;
        fringeRotation = (fringeRotation + 60f * step) % 360f;
        breathePhase = (breathePhase + (float) Math.PI * step) % ((float) Math.PI * 2f);
        float coreSize = charge <= 0.6f ? 0f : charge >= 0.8f ? 1f : (charge - 0.6f) / 0.3f;
        if (coreSize > 0f) {
            draw(1, muzzle, coreSize, coreRotation);
            draw(2, muzzle, coreSize + 0.2f + (float) Math.sin(breathePhase) * 0.1f,
                    fringeRotation);
        }
    }

    private void draw(int layer, Vector2f muzzle, float scale, float rotation) {
        if (sprites[layer] == null) {
            // New underlying Sprite per weapon; textures themselves stay shared.
            sprites[layer] = Global.getSettings().getSprite(TEXTURES[layer]);
            spriteWidths[layer] = sprites[layer].getWidth();
            spriteHeights[layer] = sprites[layer].getHeight();
            if (spriteWidths[layer] <= 0f || spriteHeights[layer] <= 0f) {
                throw new IllegalStateException("Xi mega-particle charge texture unavailable: "
                        + TEXTURES[layer]);
            }
        }
        MagicRender.singleframe(sprites[layer], new Vector2f(muzzle),
                new Vector2f(spriteWidths[layer] * scale, spriteHeights[layer] * scale),
                rotation, Color.WHITE, true);
    }

    private void resetChargeSprites() {
        lastBlinkLevel = 0f;
        blinkTimer = -1f;
        coreRotation = 0f;
        fringeRotation = 0f;
        breathePhase = 0f;
    }

    private static void smoke(CombatEngineAPI engine, WeaponAPI weapon,
            Vector2f muzzle, float intensity) {
        int count = Math.max(1, Math.round(2f * intensity));
        float sizeScale = 0.5f + 0.5f * intensity;
        for (int i = 0; i < count; i++) {
            Vector2f location = new Vector2f(muzzle.x + random(-5f, 5f), muzzle.y + random(-5f, 5f));
            float radians = (float) Math.toRadians(weapon.getCurrAngle() + 180f + random(-30f, 30f));
            Vector2f velocity = new Vector2f((float) Math.cos(radians) * 25f,
                    (float) Math.sin(radians) * 25f);
            Vector2f.add(velocity, weapon.getShip().getVelocity(), velocity);
            engine.addNebulaSmokeParticle(location, velocity, 15f * sizeScale,
                    2.5f, 0.1f, 0.1f, 3f, SMOKE);
        }
    }

    private static void muzzleParticles(CombatEngineAPI engine, ShipAPI ship, Vector2f muzzle) {
        float size = random(15f, 25f);
        float duration = random(0.2f, 0.25f);
        engine.addSmoothParticle(muzzle, ship.getVelocity(), size * random(0.5f, 0.7f),
                20f, duration, CORE);
        engine.addSmoothParticle(muzzle, ship.getVelocity(), size, 20f, duration, FRINGE);
    }

    private static float random(float min, float max) {
        return min + (float) Math.random() * (max - min);
    }
}
