package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

/**
 * 蛇形尾部模块控制器（挂在一个隐藏DECORATIVE武器上）
 *
 * <p>默认控制槽位：TAIL_1 ~ TAIL_6（可改 SEGMENT_COUNT）。</p>
 * <p>会同时尝试驱动：</p>
 * <p>1) 子模块（station slot）</p>
 * <p>2) 武器槽位贴图（通过setCenterX/Y进行位移补偿）</p>
 */
public class Moci_SerpentTailModuleController implements EveryFrameWeaponEffectPlugin {

    // ===== 基础配置（常用调参区）=====
    private static final int SEGMENT_COUNT = 6;
    private static final float DEFAULT_SEGMENT_LENGTH = 42f;
    private static final float OVERLAP_FACTOR = 0.82f;

    // ===== 曲线驱动参数（参考尾巴插件思路）=====
    private static final float ROOT_LERP_SPEED = 8f;
    private static final float FOLLOW_LERP_SPEED = 12f;

    private static final float SWING_FREQUENCY = 0.32f;
    private static final float SWING_AMPLITUDE = 32f;

    private static final float BALANCE_RESPONSE = 0.8f;
    private static final float BALANCE_MAX_ANGLE = 35f;

    private static final float INERTIA_RESPONSE = 0.015f;
    private static final float INERTIA_MAX_ANGLE = 25f;

    private static final float STRAFE_RESPONSE = 0.9f;
    private static final float STRAFE_MAX_ANGLE = 30f;

    // 与舰船后向对齐（通常尾巴朝后）
    private static final float ROOT_BASE_ANGLE_OFFSET = 180f;

    // ===== 模块实体驱动配置 =====
    // true：优先真实移动子模块实体；false：只做贴图偏移。
    private static final boolean FORCE_REAL_MODULE_MOVEMENT = true;
    // true：初始化时将尾巴模块 setStationSlot(null) 脱离母舰绑定，确保可自由运动。
    private static final boolean DETACH_MODULES_ON_INIT = false;
    // 脱离后设为无碰撞，避免尾段与母舰/其他模块碰撞干扰。
    private static final boolean SET_MODULE_COLLISION_NONE = false;

    private static final String CONTROL_MASTER_KEY = "MOCI_SERPENT_TAIL_MASTER";

    private boolean initialized = false;
    private boolean warnedMissingSegments = false;
    private float swingPhase = 0f;

    private final Vector2f prevShipVelocity = new Vector2f();
    private boolean hasPrevVelocity = false;

    private final List<SegmentBinding> segments = new ArrayList<SegmentBinding>();

    private static final class SpriteCenter {
        float x;
        float y;
        boolean valid = false;
    }

    private static final class SegmentBinding {
        int index = 0;
        WeaponAPI weapon = null;
        ShipAPI module = null;
        float length = DEFAULT_SEGMENT_LENGTH;

        final Vector2f position = new Vector2f();
        float angle = 0f;
        boolean poseInitialized = false;

        final SpriteCenter weaponSprite = new SpriteCenter();
        final SpriteCenter weaponBarrel = new SpriteCenter();
        final SpriteCenter weaponUnder = new SpriteCenter();
        final SpriteCenter weaponGlow = new SpriteCenter();
        final SpriteCenter moduleSprite = new SpriteCenter();
        boolean moduleDetached = false;
    }

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || weapon == null || engine.isPaused()) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        // 同舰只允许一个控制器实例生效，避免多个武器重复驱动。
        Object currentMaster = ship.getCustomData().get(CONTROL_MASTER_KEY);
        if (currentMaster == null) {
            ship.setCustomData(CONTROL_MASTER_KEY, weapon);
        } else if (currentMaster != weapon) {
            return;
        }

        if (!initialized) {
            initializeSegments(ship);
            initialized = true;
        }

        if (segments.isEmpty()) {
            if (!warnedMissingSegments) {
                warnedMissingSegments = true;
            }
            return;
        }

        updateTailKinematics(ship, weapon, amount);
        applyToModulesAndWeapons(ship);
    }

    private void initializeSegments(ShipAPI ship) {
        segments.clear();

        Map<String, WeaponAPI> weaponBySlot = new HashMap<String, WeaponAPI>();
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w != null && w.getSlot() != null) {
                weaponBySlot.put(w.getSlot().getId(), w);
            }
        }

        Map<String, ShipAPI> moduleBySlot = new HashMap<String, ShipAPI>();
        for (ShipAPI module : ship.getChildModulesCopy()) {
            if (module != null && module.getStationSlot() != null) {
                moduleBySlot.put(module.getStationSlot().getId(), module);
            }
        }

        for (int i = 1; i <= SEGMENT_COUNT; i++) {
            String slotUnderscore = "TAIL_" + i;
            String slotCompact = "TAIL" + i;

            WeaponAPI segmentWeapon = weaponBySlot.get(slotUnderscore);
            if (segmentWeapon == null) {
                segmentWeapon = weaponBySlot.get(slotCompact);
            }

            ShipAPI segmentModule = moduleBySlot.get(slotUnderscore);
            if (segmentModule == null) {
                segmentModule = moduleBySlot.get(slotCompact);
            }

            if (segmentWeapon == null && segmentModule == null) {
                continue;
            }

            SegmentBinding binding = new SegmentBinding();
            binding.index = i;
            binding.weapon = segmentWeapon;
            binding.module = segmentModule;
            captureSpriteCenters(binding);
            binding.length = resolveSegmentLength(binding);
            segments.add(binding);

            if (binding.module != null && FORCE_REAL_MODULE_MOVEMENT && DETACH_MODULES_ON_INIT) {
                prepareDetachedModule(binding.module);
                binding.moduleDetached = true;
            }
        }
    }

    private void prepareDetachedModule(ShipAPI module) {
        if (module == null) {
            return;
        }
        // 参考 Moci_Hidden_module：真实实体控制。
        module.setStationSlot(null);
        module.setShipAI(null);
        module.setControlsLocked(true);
        if (SET_MODULE_COLLISION_NONE) {
            module.setCollisionClass(CollisionClass.NONE);
        }
    }

    private void captureSpriteCenters(SegmentBinding binding) {
        captureCenter(binding.weapon != null ? binding.weapon.getSprite() : null, binding.weaponSprite);
        captureCenter(binding.weapon != null ? binding.weapon.getBarrelSpriteAPI() : null, binding.weaponBarrel);
        captureCenter(binding.weapon != null ? binding.weapon.getUnderSpriteAPI() : null, binding.weaponUnder);
        captureCenter(binding.weapon != null ? binding.weapon.getGlowSpriteAPI() : null, binding.weaponGlow);
        captureCenter(binding.module != null ? binding.module.getSpriteAPI() : null, binding.moduleSprite);
    }

    private void captureCenter(SpriteAPI sprite, SpriteCenter center) {
        if (sprite == null || center == null) {
            return;
        }
        center.x = sprite.getCenterX();
        center.y = sprite.getCenterY();
        center.valid = true;
    }

    private float resolveSegmentLength(SegmentBinding binding) {
        if (binding.weapon != null && binding.weapon.getSprite() != null) {
            float len = binding.weapon.getSprite().getHeight();
            if (len > 1f) {
                return len;
            }
        }
        if (binding.module != null && binding.module.getSpriteAPI() != null) {
            float len = binding.module.getSpriteAPI().getHeight();
            if (len > 1f) {
                return len;
            }
        }
        if (binding.module != null) {
            float fromRadius = binding.module.getCollisionRadius() * 0.9f;
            if (fromRadius > 1f) {
                return fromRadius;
            }
        }
        return DEFAULT_SEGMENT_LENGTH;
    }

    private void updateTailKinematics(ShipAPI ship, WeaponAPI controlWeapon, float amount) {
        float shipFacing = ship.getFacing();
        float shipAngularVel = ship.getAngularVelocity();
        Vector2f shipVelocity = ship.getVelocity();

        float baseAngle = MathUtils.clampAngle(shipFacing + ROOT_BASE_ANGLE_OFFSET);
        Vector2f baseMountPoint = new Vector2f(controlWeapon.getLocation());

        float balanceBend = -shipAngularVel * BALANCE_RESPONSE;
        balanceBend = clamp(balanceBend, -BALANCE_MAX_ANGLE, BALANCE_MAX_ANGLE);

        float facingRad = (float) Math.toRadians(shipFacing);
        float sideX = -(float) Math.sin(facingRad);
        float sideY = (float) Math.cos(facingRad);

        float inertiaBend = 0f;
        if (hasPrevVelocity && amount > 0.0001f) {
            float accelX = (shipVelocity.x - prevShipVelocity.x) / amount;
            float accelY = (shipVelocity.y - prevShipVelocity.y) / amount;
            float sideAccel = accelX * sideX + accelY * sideY;
            inertiaBend = -sideAccel * INERTIA_RESPONSE;
            inertiaBend = clamp(inertiaBend, -INERTIA_MAX_ANGLE, INERTIA_MAX_ANGLE);
        }
        prevShipVelocity.set(shipVelocity);
        hasPrevVelocity = true;

        float strafeBend = 0f;
        float maxSpeed = ship.getMaxSpeed();
        if (maxSpeed > 0.01f) {
            float sideVelocity = shipVelocity.x * sideX + shipVelocity.y * sideY;
            float strafePct = sideVelocity / maxSpeed;
            strafeBend = strafePct * STRAFE_MAX_ANGLE * STRAFE_RESPONSE;
            strafeBend = clamp(strafeBend, -STRAFE_MAX_ANGLE, STRAFE_MAX_ANGLE);
        }

        swingPhase += amount * SWING_FREQUENCY * 2f * (float) Math.PI;
        float speedPct = maxSpeed > 0.01f ? Math.min(shipVelocity.length() / maxSpeed, 1f) : 0f;
        float maxTurnRate = ship.getMaxTurnRate();
        float turnPct = maxTurnRate > 0.01f ? Math.min(Math.abs(shipAngularVel) / maxTurnRate, 1f) : 0f;
        float maneuverSuppress = 1f - Math.min(0.85f, speedPct * 0.45f + turnPct * 0.6f);

        float swingBend = SWING_AMPLITUDE * maneuverSuppress * (float) Math.sin(swingPhase);
        float rootTargetBend = swingBend + balanceBend + inertiaBend + strafeBend;

        Vector2f attachPoint = new Vector2f(baseMountPoint);

        for (int i = 0; i < segments.size(); i++) {
            SegmentBinding seg = segments.get(i);
            float parentAngle = (i == 0) ? baseAngle : segments.get(i - 1).angle;

            if (!seg.poseInitialized) {
                seg.angle = parentAngle;
                seg.poseInitialized = true;
            }

            float currentBend = MathUtils.getShortestRotation(parentAngle, seg.angle);
            float targetBend = (i == 0) ? rootTargetBend : 0f;
            float lerpSpeed = (i == 0) ? ROOT_LERP_SPEED : FOLLOW_LERP_SPEED;
            float lerp = 1f - (float) Math.exp(-lerpSpeed * amount);
            float newBend = currentBend + (targetBend - currentBend) * lerp;

            float maxBend = getSegmentMaxBend(i);
            newBend = clamp(newBend, -maxBend, maxBend);

            seg.angle = MathUtils.clampAngle(parentAngle + newBend);

            float segLen = Math.max(1f, seg.length);
            Vector2f centerOffset = MathUtils.getPointOnCircumference(null, segLen * 0.5f, seg.angle);
            Vector2f.add(attachPoint, centerOffset, seg.position);

            Vector2f nextAttachOffset =
                    MathUtils.getPointOnCircumference(null, segLen * OVERLAP_FACTOR, seg.angle);
            Vector2f.add(attachPoint, nextAttachOffset, attachPoint);
        }
    }

    private float getSegmentMaxBend(int segmentIndex) {
        if (segmentIndex == 0) {
            return 45f;
        }
        float base = 12f + (segmentIndex - 1) * 4f;
        if (segmentIndex >= 2 && segmentIndex <= 4) {
            base += 10f;
        }
        return base;
    }

    private void applyToModulesAndWeapons(ShipAPI ship) {
        for (SegmentBinding seg : segments) {
            if (seg.module != null && seg.module.isAlive()) {
                if (FORCE_REAL_MODULE_MOVEMENT && DETACH_MODULES_ON_INIT && !seg.moduleDetached) {
                    prepareDetachedModule(seg.module);
                    seg.moduleDetached = true;
                }
                seg.module.setFacing(seg.angle);
                if (FORCE_REAL_MODULE_MOVEMENT) {
                    seg.module.getLocation().set(seg.position);
                    seg.module.getVelocity().set(ship.getVelocity());
                } else if (seg.module.getSpriteAPI() != null && seg.moduleSprite.valid) {
                    Vector2f worldDelta = Vector2f.sub(seg.position, seg.module.getLocation(), null);
                    Vector2f localDelta = toLocalOffset(worldDelta, seg.angle);
                    seg.module.getSpriteAPI().setCenter(seg.moduleSprite.x + localDelta.x, seg.moduleSprite.y + localDelta.y);
                }
            }

            if (seg.weapon != null) {
                seg.weapon.setCurrAngle(seg.angle);
                Vector2f worldDelta = Vector2f.sub(seg.position, seg.weapon.getLocation(), null);
                Vector2f localDelta = toLocalOffset(worldDelta, seg.angle);
                applySpriteOffset(seg.weapon.getSprite(), seg.weaponSprite, localDelta);
                applySpriteOffset(seg.weapon.getBarrelSpriteAPI(), seg.weaponBarrel, localDelta);
                applySpriteOffset(seg.weapon.getUnderSpriteAPI(), seg.weaponUnder, localDelta);
                applySpriteOffset(seg.weapon.getGlowSpriteAPI(), seg.weaponGlow, localDelta);
            }
        }
    }

    private void applySpriteOffset(SpriteAPI sprite, SpriteCenter base, Vector2f localDelta) {
        if (sprite == null || base == null || !base.valid) {
            return;
        }
        sprite.setCenter(base.x + localDelta.x, base.y + localDelta.y);
    }

    private Vector2f toLocalOffset(Vector2f worldDelta, float angle) {
        float rad = (float) Math.toRadians(angle);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        return new Vector2f(
                worldDelta.x * cos + worldDelta.y * sin,
                -worldDelta.x * sin + worldDelta.y * cos);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
