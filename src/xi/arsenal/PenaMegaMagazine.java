package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Penelope-only native burst-beam magazine. The native tracker spends one of five charges at
 * charge start and owns every beam, flux deduction and hit. This plugin skips the configured
 * long cooldown after the first four charges, retaining a 1.2-second gap after full fade; the fifth finishes its full native fade before the
 * ship's two owned cannons share a ten ship-local-second empty-magazine cooldown.
 */
public final class PenaMegaMagazine implements EveryFrameWeaponEffectPluginWithAdvanceAfter, WeaponEffectPluginWithInit {
    public static final String WEAPON_ID = "Moci_fw_penelope_mega_particle";
    public static final String KEY = "xi.arsenal.penelopeMega";
    public static final int MAGAZINE_SIZE = 5;
    public static final float GROUP_COOLDOWN = 10f;
    public static final float INTER_SHOT_COOLDOWN = 1.2f;
    private static final String CONTROLLER_KEY = KEY + ".controller";
    private static final float EPSILON = 0.0001f;
    /** 蓄力视觉改用我方原有的马格南特效（xi 的 MegaParticleChargeEffect 已按用户要求复原为旧实现）。 */
    private final com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin chargeVisual = new MegaParticleChargeEffect();
    private WeaponAPI bound;
    private Group group;

    @Override public void init(WeaponAPI weapon) {
        if (bound != null) {
            if (bound != weapon) throw new IllegalStateException("Penelope beam effect shared between instances");
            return;
        }
        if (!WEAPON_ID.equals(weapon.getId()) || !weapon.isBeam() || !weapon.isBurstBeam())
            throw new IllegalArgumentException("PenaMegaMagazine requires its own native burst beam");
        bound = weapon;
        // This pack promises exactly five uses, even if a generic ammo-capacity modifier would
        // otherwise grant more. The native HUD reads this real ammo tracker directly.
        weapon.setMaxAmmo(MAGAZINE_SIZE);
        weapon.setAmmo(Math.min(MAGAZINE_SIZE, weapon.getAmmo()));
        AmmoTrackerAPI tracker = weapon.getAmmoTracker();
        tracker.setAmmoPerSecond(0f); tracker.setReloadSize(MAGAZINE_SIZE); tracker.setReloadProgress(0f);
    }

    @Override public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (bound == null) init(weapon);
        if (engine == null || engine.isPaused() || !Float.isFinite(amount) || amount <= 0f) return;
        bindGroup(engine, weapon);
        group.advance(amount);
        group.prepare(weapon);
        // Composition invokes only this pack's own visual component once per native weapon
        // update. It never invokes an original-mod callback or applies a second beam hit.
        chargeVisual.advance(amount, engine, weapon);
    }

    @Override public void advanceAfter(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || !Float.isFinite(amount) || amount <= 0f) return;
        bindGroup(engine, weapon);
        group.observe(weapon, amount);
        group.prepare(weapon); // Native consumes forced-fire flags before the next advance callback.
    }

    private void bindGroup(CombatEngineAPI engine, WeaponAPI weapon) {
        if (bound == null) init(weapon);
        if (bound != weapon) throw new IllegalStateException("Penelope cannon binding mismatch");
        if (group != null) return;
        ShipAPI ship = weapon.getShip();
        Object existing = ship.getCustomData().get(CONTROLLER_KEY);
        if (existing instanceof Group owned) group = owned;
        else {
            group = new Group(engine, ship);
            ship.setCustomData(CONTROLLER_KEY, group);
            ship.setCustomData(KEY, group.facts);
        }
    }

    /** Contains only bounded telemetry, no combat entities; do not mutate from an extension. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> status(ShipAPI ship) {
        Object value = ship == null ? null : ship.getCustomData().get(KEY);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static final class Mount {
        final WeaponAPI weapon;
        final Map<String, Object> facts = new LinkedHashMap<>();
        final List<Map<String, Object>> cycles = new ArrayList<>();
        int previousAmmo, nativeCycles;
        boolean activeCycle;
        float nextChargeNotBefore;
        Map<String, Object> currentCycle;
        Mount(WeaponAPI weapon) {
            this.weapon = weapon; previousAmmo = weapon.getAmmo();
            facts.put("nativeCycles", 0); facts.put("cycles", cycles);
        }
    }

    private static final class Group {
        final CombatEngineAPI engine;
        final ShipAPI ship;
        final List<Mount> mounts = new ArrayList<>();
        final Map<String, Object> facts = new LinkedHashMap<>();
        String state = "ready";
        float localClock, cooldownRemaining;
        int lastFrame = Integer.MIN_VALUE;
        long nativeAmmoSpent, skippedCooldowns, cooldownStarts, reloadBatches;
        boolean discovered;

        Group(CombatEngineAPI engine, ShipAPI ship) {
            this.engine = engine; this.ship = ship;
            facts.put("schema", 2); facts.put("state", state); facts.put("magazineSize", MAGAZINE_SIZE);
            facts.put("sharedCooldownSeconds", GROUP_COOLDOWN); facts.put("nativeAmmoSpent", 0L);
            facts.put("interShotCooldownSeconds", INTER_SHOT_COOLDOWN);
            facts.put("cooldownStarts", 0L); facts.put("reloadBatches", 0L);
            facts.put("nativeDamageOwner", "native-burst-beam-only");
        }

        void discover() {
            if (discovered) return;
            Map<String, Map<String, Object>> slots = new LinkedHashMap<>();
            for (WeaponAPI weapon : ship.getAllWeapons()) {
                if (!WEAPON_ID.equals(weapon.getId())) continue;
                if (mounts.size() >= 8) throw new IllegalStateException("Too many Penelope mega cannons on one ship");
                mounts.add(new Mount(weapon));
            }
            mounts.sort(Comparator.comparing(m -> m.weapon.getSlot().getId()));
            for (Mount mount : mounts) slots.put(mount.weapon.getSlot().getId(), mount.facts);
            facts.put("slots", slots); discovered = true;
        }

        void advance(float amount) {
            discover();
            int frame = Float.floatToIntBits(engine.getTotalElapsedTime(false));
            if (frame == lastFrame) return;
            lastFrame = frame; localClock += amount;
            if ("cooling".equals(state)) {
                cooldownRemaining = Math.max(0f, cooldownRemaining - amount);
                if (cooldownRemaining <= EPSILON) refill();
            }
            publish();
            if (engine.getPlayerShip() == ship && ship.isAlive() && !ship.isHulk()) {
                String detail = "cooling".equals(state)
                        ? String.format(Locale.ROOT, "整组冷却 %.1f 秒", cooldownRemaining)
                        : "finishing-magazine".equals(state) ? "第五次照射完成后整组冷却"
                        : "每次照射后间隔 1.2 秒 · 每门 5 次后整组冷却 10 秒";
                engine.maintainStatusForPlayerShip(KEY, null, "米加粒子炮弹匣", detail, !"ready".equals(state));
            }
        }

        void observe(WeaponAPI weapon, float amount) {
            Mount mount = find(weapon);
            if (mount == null) return;
            int ammo = weapon.getAmmo();
            if (ammo < mount.previousAmmo) {
                int spent = mount.previousAmmo - ammo;
                nativeAmmoSpent += spent; mount.nativeCycles += spent;
                mount.activeCycle = true;
                mount.currentCycle = new LinkedHashMap<>();
                mount.currentCycle.put("startEngineTime", (double) engine.getTotalElapsedTime(false));
                mount.currentCycle.put("startLocalTime", localClock);
                mount.currentCycle.put("earliestChargeLocalTime", mount.nextChargeNotBefore);
                mount.currentCycle.put("ammoAfterNativeCharge", ammo);
                mount.currentCycle.put("nativeAmmoSpent", spent);
                mount.currentCycle.put("beamSeen", false); mount.currentCycle.put("peakBrightness", 0f);
                mount.currentCycle.put("beamVisibleLocalSeconds", 0f);
                mount.currentCycle.put("complete", false);
                if (mount.cycles.size() >= 12) mount.cycles.remove(0);
                mount.cycles.add(mount.currentCycle);
                if (ammo <= 0 && "ready".equals(state)) {
                    state = "finishing-magazine";
                    facts.put("finalChargeEngineTime", (double) engine.getTotalElapsedTime(false));
                    facts.put("finalChargeLocalTime", localClock);
                }
            }
            mount.previousAmmo = ammo;
            float brightness = 0f;
            for (BeamAPI beam : weapon.getBeams()) {
                brightness = Math.max(brightness, beam.getBrightness());
                if (mount.currentCycle != null && beam.getBrightness() > EPSILON) {
                    mount.currentCycle.put("beamSeen", true);
                    mount.currentCycle.put("nativeBeamClass", beam.getClass().getName());
                }
            }
            if (mount.currentCycle != null && mount.activeCycle) {
                mount.currentCycle.put("peakBrightness", Math.max(brightness,
                        ((Number) mount.currentCycle.get("peakBrightness")).floatValue()));
                if (brightness > EPSILON) mount.currentCycle.put("beamVisibleLocalSeconds", amount
                        + ((Number) mount.currentCycle.get("beamVisibleLocalSeconds")).floatValue());
            }
            boolean nativeActive = weapon.isFiring() || brightness > EPSILON;
            if (mount.activeCycle && !nativeActive) {
                mount.activeCycle = false;
                mount.currentCycle.put("complete", true);
                mount.currentCycle.put("endEngineTime", (double) engine.getTotalElapsedTime(false));
                mount.currentCycle.put("endLocalTime", localClock);
                if (!"cooling".equals(state) && ammo > 0) {
                    // Native D's public setter can skip a configured cooldown, but cannot
                    // extend one beyond its configured length. CSV therefore stays at 10s.
                    mount.nextChargeNotBefore = localClock + INTER_SHOT_COOLDOWN;
                    mount.currentCycle.put("nextChargeNotBeforeLocalTime", mount.nextChargeNotBefore);
                    weapon.setRemainingCooldownTo(INTER_SHOT_COOLDOWN); skippedCooldowns++;
                }
            }
            mount.facts.put("ammo", ammo); mount.facts.put("maxAmmo", weapon.getMaxAmmo());
            mount.facts.put("nativeCycles", mount.nativeCycles); mount.facts.put("cycleActive", mount.activeCycle);
            mount.facts.put("lastNativeCharge", weapon.getChargeLevel());
            mount.facts.put("nativeFiring", weapon.isFiring()); mount.facts.put("beamBrightness", brightness);
            mount.facts.put("nativeCooldownRemaining", weapon.getCooldownRemaining());
            mount.facts.put("nextChargeNotBeforeLocalTime", mount.nextChargeNotBefore);
            mount.facts.put("interShotCooldownRemaining", Math.max(0f, mount.nextChargeNotBefore - localClock));
            if ("finishing-magazine".equals(state) && allAvailableMountsEmpty()
                    && mounts.stream().noneMatch(m -> m.activeCycle || nativeActive(m.weapon)))
                beginCooldown();
            publish();
        }

        private boolean nativeActive(WeaponAPI weapon) {
            if (weapon.isFiring()) return true;
            for (BeamAPI beam : weapon.getBeams()) if (beam.getBrightness() > EPSILON) return true;
            return false;
        }

        private boolean allAvailableMountsEmpty() {
            for (Mount mount : mounts) {
                WeaponAPI weapon = mount.weapon;
                if (weapon.getAmmo() > 0 && !weapon.isDisabled()
                        && !weapon.isPermanentlyDisabled() && !weapon.isForceDisabled()) return false;
            }
            return true;
        }

        private void beginCooldown() {
            state = "cooling"; cooldownRemaining = GROUP_COOLDOWN; cooldownStarts++;
            facts.put("cooldownStartEngineTime", (double) engine.getTotalElapsedTime(false));
            facts.put("cooldownStartLocalTime", localClock);
            for (Mount mount : mounts) {
                // Only after all native irradiation/fade has ended: never cut a fifth beam.
                mount.weapon.setAmmo(0); mount.previousAmmo = 0;
                mount.nextChargeNotBefore = 0f;
                mount.weapon.getAmmoTracker().setReloadProgress(0f);
                mount.weapon.setRemainingCooldownTo(GROUP_COOLDOWN);
                mount.weapon.setForceNoFireOneFrame(true);
            }
        }

        void prepare(WeaponAPI weapon) {
            Mount mount = find(weapon);
            if (mount != null && !mount.activeCycle && mount.nextChargeNotBefore > localClock) {
                // Native force flags are consumed before advance(), so also keep the guard
                // after advanceAfter(). The deadline is ship-local, owned by this group once
                // per engine frame; native charge/beam/fade remain the native tracker's work.
                weapon.setForceNoFireOneFrame(true);
                // The full1.2-second native cooldown was set at the dark edge. Do not
                // shorten it again before native processing, which would count this
                // frame twice and let external trigger pressure shave off one frame.
                return;
            }
            if ("ready".equals(state)) return;
            // A peer can be one or more native updates behind the first empty mount. Its
            // remaining fifth charge must still be allowed to start and finish; otherwise
            // weapon iteration order would silently discard that last real beam.
            if ("finishing-magazine".equals(state) && weapon.getAmmo() > 0) return;
            // The burst tracker finishes an already committed beam without fresh fire input.
            // These flags prevent a pending/new trigger; no stopFiring or beam deletion occurs.
            weapon.setForceNoFireOneFrame(true);
            if ("cooling".equals(state)) {
                weapon.setAmmo(0);
                weapon.setRemainingCooldownTo(cooldownRemaining);
            }
        }

        private void refill() {
            Map<String, Integer> restored = new LinkedHashMap<>();
            for (Mount mount : mounts) {
                mount.weapon.setAmmo(MAGAZINE_SIZE); mount.previousAmmo = MAGAZINE_SIZE;
                mount.nextChargeNotBefore = 0f;
                mount.weapon.getAmmoTracker().setReloadProgress(0f);
                mount.weapon.setRemainingCooldownTo(0f);
                mount.facts.put("ammo", MAGAZINE_SIZE);
                restored.put(mount.weapon.getSlot().getId(), mount.weapon.getAmmo());
            }
            state = "ready"; reloadBatches++;
            facts.put("reloadEngineTime", (double) engine.getTotalElapsedTime(false));
            facts.put("reloadLocalTime", localClock);
            facts.put("lastReloadAmmoBySlot", restored);
        }

        private Mount find(WeaponAPI weapon) {
            for (Mount mount : mounts) if (mount.weapon == weapon) return mount;
            return null;
        }

        private void publish() {
            facts.put("state", state); facts.put("localClock", localClock);
            facts.put("cooldownRemaining", cooldownRemaining); facts.put("cooldownStarts", cooldownStarts);
            facts.put("reloadBatches", reloadBatches); facts.put("nativeAmmoSpent", nativeAmmoSpent);
            facts.put("skippedCooldowns", skippedCooldowns);
        }
    }
}
