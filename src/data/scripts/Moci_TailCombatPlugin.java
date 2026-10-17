package data.scripts;

import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
// 代码作者 原型系统-alpha
public class Moci_TailCombatPlugin extends BaseEveryFrameCombatPlugin {
  private static final String SHIP_ID = "Moci_ReinWeissRitter";
  private static final String TAIL_WEAPON_ID = "TAIL";
  private static final String TAIL_S_SPRITE_PATH =
      "graphics/ships/Mobile_Suit/Moci_ReinWeissRitter/tail/ReinWeissRitter_tail_s.png";
  private static final String TAIL_M_SPRITE_PATH =
      "graphics/ships/Mobile_Suit/Moci_ReinWeissRitter/tail/ReinWeissRitter_tail_m.png";
  private static final String TAIL_L_SPRITE_PATH =
      "graphics/ships/Mobile_Suit/Moci_ReinWeissRitter/tail/ReinWeissRitter_tail_l.png";
  private static final int NUM_S_SEGMENTS = 18;
  private static final float SMOOTHING_FACTOR = 6f;
  private static final float INERTIA_FACTOR = 0.5f;
  private static final Vector2f MOUNT_LOCATION = new Vector2f(-46, -34);
  private static final String FOLLOW_WEAPON_ID = "ARM_R_MAIN";
  private CombatEngineAPI engine;
  private SpriteAPI spriteS, spriteM, spriteL;
  private float lengthS = 0f, lengthM = 0f, lengthL = 0f;
  private Map<ShipAPI, Moci_ReinWeissRitterTailRenderer.TailState> tailStates = new HashMap<>();
  private Map<ShipAPI, Boolean> originalTailHidden = new HashMap<>();
  private boolean rendererAdded = false;
  private Moci_ReinWeissRitterTailRenderer rendererInstance;
  private boolean initCalled = false;

  @Override
  public void init(CombatEngineAPI engine) {
    if (initCalled) {}
    initCalled = true;
    this.engine = engine;
    tailStates.clear();
    originalTailHidden.clear();
    rendererAdded = false;
    try {
      spriteS = Global.getSettings().getSprite(TAIL_S_SPRITE_PATH);
      spriteM = Global.getSettings().getSprite(TAIL_M_SPRITE_PATH);
      spriteL = Global.getSettings().getSprite(TAIL_L_SPRITE_PATH);
      lengthS = (spriteS != null) ? spriteS.getHeight() : 0f;
      lengthM = (spriteM != null) ? spriteM.getHeight() : 0f;
      lengthL = (spriteL != null) ? spriteL.getHeight() : 0f;
      if (lengthS <= 0f || lengthM <= 0f || lengthL <= 0f) {}
    } catch (Exception e) {
      spriteS = spriteM = spriteL = null;
    }
    if (!rendererAdded && engine != null) {
      try {
        rendererInstance =
            new Moci_ReinWeissRitterTailRenderer(
                spriteS, spriteM, spriteL, lengthS, lengthM, lengthL, tailStates);
        engine.addLayeredRenderingPlugin(rendererInstance);
        rendererAdded = true;
      } catch (Exception e) {
      }
    }
  }

  @Override
  public void advance(float amount, List<InputEventAPI> events) {
    if (engine == null || engine.isPaused()) {
      return;
    }
    java.util.Iterator<ShipAPI> tailIter = tailStates.keySet().iterator();
    while (tailIter.hasNext()) {
      ShipAPI ship = tailIter.next();
      if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) {
        tailIter.remove();
      }
    }
    java.util.Iterator<ShipAPI> hiddenIter = originalTailHidden.keySet().iterator();
    while (hiddenIter.hasNext()) {
      ShipAPI ship = hiddenIter.next();
      if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) {
        hiddenIter.remove();
      }
    }
    for (ShipAPI ship : engine.getShips()) {
      if (!ship.isAlive() || ship.isHulk() || !ship.getHullSpec().getHullId().equals(SHIP_ID)) {
        continue;
      }
      boolean foundTail = false;
      for (WeaponAPI w : ship.getAllWeapons()) {
        if (TAIL_WEAPON_ID.equals(w.getSlot().getId())) {
          foundTail = true;
          try {
            Color transparent = new Color(0, 0, 0, 0);
            if (w.getSprite() != null) {
              w.getSprite().setAlphaMult(0f);
              w.getSprite().setColor(transparent);
            }
            if (w.getBarrelSpriteAPI() != null) {
              w.getBarrelSpriteAPI().setAlphaMult(0f);
              w.getBarrelSpriteAPI().setColor(transparent);
            }
            if (w.getUnderSpriteAPI() != null) {
              w.getUnderSpriteAPI().setAlphaMult(0f);
              w.getUnderSpriteAPI().setColor(transparent);
            }
            if (w.getGlowSpriteAPI() != null) {
              w.getGlowSpriteAPI().setAlphaMult(0f);
              w.getGlowSpriteAPI().setColor(transparent);
            }
          } catch (Exception e) {
            Boolean hidden113 = originalTailHidden.get(ship);
            if (hidden113 == null || !hidden113) {}
          }
          break;
        }
      }
      Boolean hidden118 = originalTailHidden.get(ship);
      if (!foundTail && (hidden118 == null || !hidden118)) {}
      originalTailHidden.put(ship, true);
      Moci_ReinWeissRitterTailRenderer.TailState state = tailStates.get(ship);
      if (state == null) {
        state = new Moci_ReinWeissRitterTailRenderer.TailState();
        tailStates.put(ship, state);
      }
      ensureSegmentListSize(state);
      calculateTailSegments(ship, state, amount);
    }
  }

  private void ensureSegmentListSize(Moci_ReinWeissRitterTailRenderer.TailState state) {
    int totalSegments = NUM_S_SEGMENTS + 2;
    while (state.segments.size() < totalSegments) {
      state.segments.add(new Moci_ReinWeissRitterTailRenderer.SegmentData());
    }
    while (state.segments.size() > totalSegments) {
      state.segments.remove(state.segments.size() - 1);
    }
  }

  private void calculateTailSegments(
      ShipAPI ship, Moci_ReinWeissRitterTailRenderer.TailState state, float amount) {
    Vector2f shipLocation = ship.getLocation();
    float shipFacing = ship.getFacing();
    float shipAngularVelocity = ship.getAngularVelocity();
    WeaponAPI followWeapon = null;
    for (WeaponAPI w : ship.getAllWeapons()) {
      if (w.getSlot().getId().equals(FOLLOW_WEAPON_ID)) {
        followWeapon = w;
        break;
      }
    }
    Vector2f baseMountPoint;
    float targetWeaponAngle;
    if (followWeapon != null) {
      baseMountPoint = followWeapon.getLocation();
      targetWeaponAngle = MathUtils.clampAngle(followWeapon.getCurrAngle() + 180f);
    } else {
      baseMountPoint = VectorUtils.rotateAroundPivot(MOUNT_LOCATION, new Vector2f(), shipFacing);
      Vector2f.add(baseMountPoint, shipLocation, baseMountPoint);
      targetWeaponAngle = MathUtils.clampAngle(shipFacing + 180f);
    }
    Vector2f currentAttachPoint = baseMountPoint;
    for (int i = 0; i < state.segments.size(); i++) {
      Moci_ReinWeissRitterTailRenderer.SegmentData currentSegment = state.segments.get(i);
      float segmentLength = getSegmentLength(i);
      if (segmentLength <= 0f) {
        segmentLength = 1f;
      }
      float currentTargetAngle;
      if (i == 0) {
        float currentAngle = currentSegment.angle;
        if (currentAngle == 0f && currentSegment.position.lengthSquared() == 0f) {
          currentAngle = targetWeaponAngle;
        }
        float angleDiff = MathUtils.getShortestRotation(currentAngle, targetWeaponAngle);
        float smoothedAngle =
            MathUtils.clampAngle(currentAngle + angleDiff * SMOOTHING_FACTOR * amount);
        float inertiaOffset = -shipAngularVelocity * INERTIA_FACTOR;
        currentTargetAngle = smoothedAngle + inertiaOffset;
      } else {
        Moci_ReinWeissRitterTailRenderer.SegmentData prevSegment = state.segments.get(i - 1);
        if (currentSegment.angle == 0f && currentSegment.position.lengthSquared() == 0f) {
          currentSegment.angle = prevSegment.angle;
        }
        currentTargetAngle = prevSegment.angle;
      }
      float angleDiffToTarget =
          MathUtils.getShortestRotation(currentSegment.angle, currentTargetAngle);
      float damping = 0.1f;
      float effectiveSmoothing = SMOOTHING_FACTOR * (1f - damping);
      float finalAngle =
          MathUtils.clampAngle(
              currentSegment.angle + angleDiffToTarget * effectiveSmoothing * amount);
      if (i == 0) {
        float maxSwingAngle = 15f;
        float swingDiff = MathUtils.getShortestRotation(targetWeaponAngle, finalAngle);
        if (Math.abs(swingDiff) > maxSwingAngle) {
          finalAngle =
              MathUtils.clampAngle(targetWeaponAngle + Math.signum(swingDiff) * maxSwingAngle);
        }
      }
      Vector2f centerOffset =
          MathUtils.getPointOnCircumference(null, segmentLength / 2f, finalAngle);
      Vector2f currentPosition = Vector2f.add(currentAttachPoint, centerOffset, null);
      currentSegment.angle = finalAngle;
      currentSegment.position.set(currentPosition);
      if (i == 0 || i == state.segments.size() - 1) {}
      float overlapFactor = 0.50f;
      Vector2f nextBaseOffset =
          MathUtils.getPointOnCircumference(null, segmentLength * overlapFactor, finalAngle);
      currentAttachPoint = Vector2f.add(currentAttachPoint, nextBaseOffset, null);
    }
  }

  private float getSegmentLength(int index) {
    if (index < NUM_S_SEGMENTS) return lengthS;
    if (index == NUM_S_SEGMENTS) return lengthM;
    if (index == NUM_S_SEGMENTS + 1) return lengthL;
    return 0f;
  }
}
