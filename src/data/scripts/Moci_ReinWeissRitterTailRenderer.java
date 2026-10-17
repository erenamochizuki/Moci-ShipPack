package data.scripts;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.lwjgl.util.vector.Vector2f;

// 代码作者 原型系统-alpha

public class Moci_ReinWeissRitterTailRenderer implements CombatLayeredRenderingPlugin {
  private static final int NUM_S_SEGMENTS = 18;
  private SpriteAPI spriteS, spriteM, spriteL;
  private float lengthS, lengthM, lengthL;
  private Map<ShipAPI, TailState> tailStates;

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
        for (int i = state.segments.size() - 1; i >= 0; i--) {
          SegmentData segment = state.segments.get(i);
          if (segment == null) continue;
          SpriteAPI sprite = getSegmentSprite(i);
          if (sprite == null) continue;
          sprite.setAngle(segment.angle - 90f);
          sprite.setColor(ship.getSpriteAPI().getColor());
          float alpha = viewport.getAlphaMult() * ship.getSpriteAPI().getAlphaMult();
          sprite.setAlphaMult(alpha);
          sprite.renderAtCenter(segment.position.x, segment.position.y);
          if (i == 0 || i == state.segments.size() - 1) {}
        }
      }
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
