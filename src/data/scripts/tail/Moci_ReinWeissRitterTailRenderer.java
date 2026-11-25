package data.scripts.tail;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.lwjgl.util.vector.Vector2f;

import data.scripts.util.Moci_Util;

// 代码作者 原型系统-alpha

public class Moci_ReinWeissRitterTailRenderer implements CombatLayeredRenderingPlugin {
  private static final int NUM_S_SEGMENTS = 18;
  private SpriteAPI spriteS, spriteM, spriteL;
  private float lengthS, lengthM, lengthL;
  private Map<ShipAPI, TailState> tailStates;
  /** 每帧复用：这艘船当前显示的残影 */
  private final List<Moci_Util.ShipCopyRenderer.ShipCopy> shipCopies = new ArrayList<>();
  private final Vector2f copyPosition = new Vector2f();

  public Moci_ReinWeissRitterTailRenderer(
      SpriteAPI spriteS,
      SpriteAPI spriteM,
      SpriteAPI spriteL,
      float lengthS,
      float lengthM,
      float lengthL,
      Map<ShipAPI, TailState> tailStates) {
    this.spriteS = spriteS;
    this.spriteM = spriteM;
    this.spriteL = spriteL;
    this.lengthS = lengthS;
    this.lengthM = lengthM;
    this.lengthL = lengthL;
    this.tailStates = tailStates;
  }

  public static class SegmentData {
    public Vector2f position = new Vector2f();
    public float angle = 0f;
  }

  public static class TailState {
    public List<SegmentData> segments = new ArrayList<>();
  }

  @Override
  public void init(CombatEntityAPI entity) {}

  @Override
  public void advance(float amount) {}

  private float getSegmentLength(int index) {
    if (index < NUM_S_SEGMENTS) return this.lengthS;
    if (index == NUM_S_SEGMENTS) return this.lengthM;
    if (index == NUM_S_SEGMENTS + 1) return this.lengthL;
    return 0f;
  }

  private SpriteAPI getSegmentSprite(int index) {
    if (index < NUM_S_SEGMENTS) return this.spriteS;
    if (index == NUM_S_SEGMENTS) return this.spriteM;
    if (index == NUM_S_SEGMENTS + 1) return this.spriteL;
    return null;
  }

  @Override
  public void render(CombatEngineLayers layer, ViewportAPI viewport) {
    if (this.spriteS == null || this.tailStates == null) {
      return;
    }
    if (this.tailStates.isEmpty()) {
      return;
    }
    if (getActiveLayers().contains(layer)) {
      for (Map.Entry<ShipAPI, TailState> entry : this.tailStates.entrySet()) {
        ShipAPI ship = entry.getKey();
        TailState state = entry.getValue();
        if (!ship.isAlive() || ship.isHulk() || state == null || state.segments == null) {
          continue;
        }
        if (state.segments.isEmpty()) {
          continue;
        }
        float baseAlpha = viewport.getAlphaMult() * ship.getSpriteAPI().getAlphaMult();
        // 本体的尾巴跟随本体的透明度：本体隐匿(extraAlphaMult = 0)时不能留在原地
        renderTail(ship, state, null, baseAlpha * ship.getExtraAlphaMult() * ship.getExtraAlphaMult2());
        // 残影(闪现的副本、列阵的残影)各画一条尾巴，透明度用残影自己的
        shipCopies.clear();
        Moci_Util.collectShipCopies(ship, shipCopies);
        float captureRadius = 0f;
        for (int c = 0; c < shipCopies.size(); c++) {
          Moci_Util.ShipCopyRenderer.ShipCopy copy = shipCopies.get(c);
          float alpha = baseAlpha * Math.min(1f, copy.alpha);
          if (alpha > 0f && copy.wind.lengthSquared() > .01f && Moci_Util.isLocalWarpAvailable()) {
            // 尾巴是单独的图层；捕获整条尾巴一次，共用舰体的曝光位移。
            if (captureRadius == 0f) {
              float farthest = 0f;
              for (SegmentData segment : state.segments) {
                if (segment == null) continue;
                float dx = segment.position.x - ship.getLocation().x, dy = segment.position.y - ship.getLocation().y;
                farthest = Math.max(farthest, dx * dx + dy * dy);
              }
              captureRadius = (float)Math.sqrt(farthest) + Math.max(lengthS, Math.max(lengthM, lengthL));
            }
            try {
              if (Moci_Util.renderLocalWind(() -> renderTail(ship, state, copy, 1f), copy.location, captureRadius, alpha, copy.wind)) continue;
            } catch (RuntimeException | Error error) { throw error; }
            catch (Throwable error) { throw new IllegalStateException(error); }
          }
          renderTail(ship, state, copy, alpha);
        }
        shipCopies.clear();
      }
    }
  }

  /** 画一条尾巴。copy 为 null 时画在本体上，否则把每一节换算到该残影的位置和朝向上 */
  private void renderTail(
      ShipAPI ship, TailState state, Moci_Util.ShipCopyRenderer.ShipCopy copy, float alpha) {
    if (!(alpha > 0f)) return;
    float angleOffset = copy != null ? copy.facing - ship.getFacing() : 0f;
    for (int i = state.segments.size() - 1; i >= 0; i--) {
      SegmentData segment = state.segments.get(i);
      if (segment == null) continue;
      SpriteAPI sprite = getSegmentSprite(i);
      if (sprite == null) continue;
      float x = segment.position.x;
      float y = segment.position.y;
      if (copy != null) {
        Moci_Util.transformToShipCopy(ship, copy, segment.position, copyPosition);
        x = copyPosition.x;
        y = copyPosition.y;
      }
      sprite.setAngle(segment.angle + angleOffset - 90f);
      sprite.setColor(ship.getSpriteAPI().getColor());
      sprite.setAlphaMult(alpha);
      sprite.renderAtCenter(x, y);
    }
  }

  @Override
  public float getRenderRadius() {
    return Float.MAX_VALUE;
  }

  @Override
  public EnumSet<CombatEngineLayers> getActiveLayers() {
    return EnumSet.of(CombatEngineLayers.BELOW_SHIPS_LAYER);
  }

  @Override
  public boolean isExpired() {
    return false;
  }

  @Override
  public void cleanup() {
    this.spriteS = null;
    this.spriteM = null;
    this.spriteL = null;
    this.tailStates = null;
  }
}
