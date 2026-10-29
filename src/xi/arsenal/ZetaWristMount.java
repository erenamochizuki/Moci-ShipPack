package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/** Two new wrist apertures; follows the original visible arm without advancing or steering it. */
public final class ZetaWristMount implements EveryFrameWeaponEffectPluginWithAdvanceAfter, WeaponEffectPluginWithInit {
    public static final String WEAPON_ID = "Moci_fw_zeta_wrist_grenade";
    public static final String LEFT_SLOT = "ZETA_WRIST_L", RIGHT_SLOT = "ZETA_WRIST_R";
    public static final String KEY = "xi.arsenal.zeta.wrist";
    public static final float IMAGE_WIDTH = 48f, IMAGE_HEIGHT = 206f;
    // New barrel apertures declared on the visible front wrist face, PNG top-left coordinates.
    // These are not engine/thruster positions or the original decorative arm's generic offsets.
    private static final float[][] LEFT_PIXELS = {{22f, 78f}, {25f, 78f}};
    private static final float[][] RIGHT_PIXELS = {{24f, 77f}, {27f, 77f}};
    private WeaponAPI bound, arm;
    private String slot, reason = "awaiting visible arm";
    private boolean left, ready;
    private long shots;
    private final ArrayDeque<Map<String,Object>> recentShots = new ArrayDeque<>();

    @Override public void init(WeaponAPI weapon) {
        if (bound != null) {
            if (bound != weapon) throw new IllegalStateException("Wrist callback shared between weapon instances");
            return;
        }
        if (weapon == null || !WEAPON_ID.equals(weapon.getId()) || weapon.getSlot() == null)
            throw new IllegalArgumentException("Zeta wrist callback requires its own weapon and slot");
        slot = weapon.getSlot().getId();
        if (!LEFT_SLOT.equals(slot) && !RIGHT_SLOT.equals(slot))
            throw new IllegalArgumentException("Unknown Zeta wrist slot: " + slot);
        left = LEFT_SLOT.equals(slot);
        bound = weapon;
        // Native BaseWeaponSpec.clone creates new lists but shares their Vector2f entries.
        // Replace entries on the instance clone before changing any coordinate.
        weapon.ensureClonedSpec();
        detach(weapon.getSpec().getTurretFireOffsets());
        detach(weapon.getSpec().getHardpointFireOffsets());
        detach(weapon.getSpec().getHiddenFireOffsets());
        // init runs inside the native weapon constructor: other body weapons may not exist yet.
    }

    @Override public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (bound == null) init(weapon);
        if (bound != weapon) throw new IllegalStateException("Zeta wrist instance mismatch");
        if (engine.isPaused()) return;
        synchronize();
        if (!ready) weapon.setForceNoFireOneFrame(true);
        publish();
    }

    @Override public void advanceAfter(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (bound == null) init(weapon);
        if (engine.isPaused()) return;
        synchronize();
        if (!ready) weapon.setForceNoFireOneFrame(true);
        publish();
    }

    private void synchronize() {
        ShipAPI ship = bound.getShip();
        arm = findArm(ship, left ? "ARM_L" : "ARM_R");
        ready = false;
        if (arm == null || arm.getSprite() == null) { reason = "visible arm not attached"; return; }
        Object state = ship.getCustomData().get("MOCI_MSZ_006_TRANSFORM_STATE");
        if (state != null && !"NORMAL".equals(state)) { reason = "waverider or transform transition"; return; }
        if (Boolean.TRUE.equals(ship.getCustomData().get("MOCI_MSZ_006_PARTS_HIDDEN"))) {
            reason = "body parts hidden"; return;
        }
        SpriteAPI sprite = arm.getSprite();
        if (sprite.getWidth() <= 0f || sprite.getHeight() <= 0f) { reason = "visible arm sprite unavailable"; return; }
        for (int barrel = 0; barrel < 2; barrel++) {
            Vector2f point = visibleMuzzleWorld(arm, left, barrel);
            Vector2f local = worldToWeapon(bound, point);
            setOffset(bound.getSpec().getTurretFireOffsets(), barrel, local);
            setOffset(bound.getSpec().getHardpointFireOffsets(), barrel, local);
            setOffset(bound.getSpec().getHiddenFireOffsets(), barrel, local);
        }
        ready = ship.isAlive() && !ship.isHulk() && !ship.isPhased();
        reason = ready ? "MS wrist ready; native aiming" : "source unavailable";
    }

    private static WeaponAPI findArm(ShipAPI ship, String armSlot) {
        if (ship == null) return null;
        for (WeaponAPI candidate : ship.getAllWeapons())
            if (candidate != null && candidate.getSlot() != null && armSlot.equals(candidate.getSlot().getId())) return candidate;
        return null;
    }
    private static void detach(List<Vector2f> offsets) {
        for (int i = 0; i < offsets.size(); i++) offsets.set(i, new Vector2f(offsets.get(i)));
        // Our data declares two barrels on every mounting type, including HIDDEN.
        if (offsets.size() != 2) throw new IllegalStateException("Zeta wrist requires exactly two native fire offsets");
    }
    private static void setOffset(List<Vector2f> offsets, int barrel, Vector2f value) {
        offsets.get(barrel).set(value);
    }

    /** Read-only transform of the actual arm sprite, including current recoil centre and arm angle. */
    public static Vector2f visibleMuzzleWorld(WeaponAPI arm, boolean left, int barrel) {
        if (barrel < 0 || barrel > 1) throw new IllegalArgumentException("Wrist barrel must be 0 or 1");
        float[] pixel = (left ? LEFT_PIXELS : RIGHT_PIXELS)[barrel];
        SpriteAPI sprite = arm.getSprite();
        float right = pixel[0] * sprite.getWidth() / IMAGE_WIDTH - sprite.getCenterX();
        float up = (IMAGE_HEIGHT - pixel[1]) * sprite.getHeight() / IMAGE_HEIGHT - sprite.getCenterY();
        return rotatedAt(arm.getLocation(), up, -right, arm.getCurrAngle());
    }
    private static Vector2f worldToWeapon(WeaponAPI weapon, Vector2f world) {
        Vector2f pivot = weapon.getLocation();
        return rotatedAt(new Vector2f(), world.x - pivot.x, world.y - pivot.y, -weapon.getCurrAngle());
    }
    private static Vector2f rotatedAt(Vector2f origin, float x, float y, float degrees) {
        double a = Math.toRadians(degrees);
        float c = (float)Math.cos(a), s = (float)Math.sin(a);
        return new Vector2f(origin.x + x*c - y*s, origin.y + x*s + y*c);
    }

    void nativeShot(DamagingProjectileAPI projectile) {
        shots++;
        Vector2f actual = new Vector2f(projectile.getLocation());
        float best = Float.POSITIVE_INFINITY;
        int barrel = -1;
        if (arm != null) for (int i = 0; i < 2; i++) {
            Vector2f expected = visibleMuzzleWorld(arm, left, i);
            float error = Vector2f.sub(actual, expected, null).length();
            if (error < best) { best = error; barrel = i; }
        }
        Map<String,Object> shot = new LinkedHashMap<>();
        shot.put("sequence", shots); shot.put("x", actual.x); shot.put("y", actual.y);
        shot.put("closestBarrel", barrel); shot.put("muzzleError", best);
        shot.put("nativeAmmoRemaining", bound.getAmmo()); shot.put("nativeProjectileId", projectile.getProjectileSpecId());
        recentShots.addLast(Collections.unmodifiableMap(shot));
        while (recentShots.size() > 32) recentShots.removeFirst();
        publish();
    }
    public Map<String,Object> status() {
        Map<String,Object> status = new LinkedHashMap<>();
        status.put("slot", slot == null ? "" : slot); status.put("ready", ready); status.put("reason", reason);
        status.put("armSlot", left ? "ARM_L" : "ARM_R"); status.put("nativeShots", shots);
        status.put("recentShots", List.copyOf(recentShots));
        if (bound != null) {
            status.put("nativeAngle", bound.getCurrAngle()); status.put("nativeAmmo", bound.getAmmo());
            status.put("nativeCooldownRemaining", bound.getCooldownRemaining());
        }
        if (arm != null) {
            List<Map<String,Object>> muzzles = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Vector2f point = visibleMuzzleWorld(arm, left, i);
                muzzles.add(Map.of("barrel", i, "x", point.x, "y", point.y));
            }
            status.put("visibleMuzzles", List.copyOf(muzzles));
        }
        return Collections.unmodifiableMap(status);
    }
    private void publish() {
        if (bound.getShip() != null) bound.getShip().setCustomData(KEY + "." + slot, status());
    }
}
