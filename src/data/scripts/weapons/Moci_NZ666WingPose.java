package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;

final class Moci_NZ666WingPose {
    private static final int LIVE_FRAMES = 9;

    private enum Outline {
        // Frames 01-08 of the left wings, in (forward, left) pairs. Frame 00 comes from the module.
        FRONT(new float[][] {
                {28.5f, -11.5f, 87.5f, 57.5f, 39, 42.5f, 7.5f, 27.5f, -1.5f, 4.5f, 1.5f, -2.5f},
                {29.5f, -11.5f, 80.5f, 56.5f, 22.5f, 41.5f, 0.5f, 24, -2.5f, 6, 5.5f, -3.5f},
                {30, -9.5f, 72.5f, 65, 8.5f, 37, -0.5f, 27.5f, -3.5f, 5.5f, 5.5f, -3.5f},
                {44.5f, 12.5f, 60.5f, 76.5f, 4.5f, 50.5f, -8.5f, 34.5f, -4.5f, 7.5f, 7.5f, -3.5f},
                {42.5f, 18.5f, 47.5f, 83.5f, -7.5f, 44.5f, -13.5f, 30.5f, -4.5f, 4.5f, 29.5f, -2.5f},
                {33.5f, 21.5f, 30.5f, 91.5f, -7.5f, 46.5f, -17.5f, 19.5f, -3.5f, 2.5f, 11.5f, -2.5f},
                {28.5f, 28.5f, 11.5f, 96.5f, -17.5f, 43.5f, -20.5f, 17.5f, -0.5f, 1.5f, 10.5f, -0.5f},
                {19.5f, 33.5f, -11.5f, 103.5f, -26.5f, 38.5f, -22.5f, 14.5f, 1, 0.5f, 11.5f, 2}
        }),
        BACK(new float[][] {
                {26.5f, 15.5f, 22.5f, 42.5f, -6.5f, 94.5f, -21.5f, 19.5f, -5.5f, -1.5f, 6.5f, -0.5f},
                {23.5f, 17.5f, 7.5f, 48.5f, -24.5f, 89.5f, -24.5f, 11.5f, -2.5f, -4.5f, 8, -0.5f},
                {19.5f, 25.5f, 9.5f, 45.5f, -43.5f, 80.5f, -23.5f, -4.5f, -1.5f, -4.5f, 6.5f, 1.5f},
                {14.5f, 32.5f, -2.5f, 48.5f, -57.5f, 71.5f, -22.5f, -7.5f, 0.5f, -4.5f, 8.5f, 3.5f},
                {7.5f, 22.5f, -8.5f, 38.5f, -68.5f, 61.5f, -23.5f, -11.5f, 0.5f, -6.5f, 9.5f, 3.5f},
                {3.5f, 25.5f, -17.5f, 39.5f, -75.5f, 53.5f, -22.5f, -13.5f, 0.5f, -5.5f, 8.5f, 4.5f},
                {0.5f, 23.5f, -32.5f, 40.5f, -82.5f, 53.5f, -22.5f, -13.5f, 3.5f, -5.5f, 8.5f, 3.5f},
                {-5.5f, 26.5f, -38.5f, 38.5f, -86.5f, 46.5f, -43.5f, -4.5f, 2.5f, -5.5f, 6.5f, 1.5f}
        });

        private final float[][] frames;

        Outline(float[][] frames) { this.frames = frames; }

        float[] points(int frame) { return frames[frame - 1]; }
    }

    private final Outline outline;
    private final boolean mirrored;
    private final String spritePrefix;
    private final SpriteAPI[] sprites = new SpriteAPI[LIVE_FRAMES];
    private List<BoundsAPI.SegmentAPI> edges;
    private float[] restBounds;
    private int appliedFrame = 0;

    static Moci_NZ666WingPose forModule(String slotId, String hullId) {
        return switch (slotId) {
            case "WING_LF" -> bind(hullId, "left_front", Outline.FRONT, false);
            case "WING_RF" -> bind(hullId, "right_front", Outline.FRONT, true);
            case "WING_LB" -> bind(hullId, "left_back", Outline.BACK, false);
            case "WING_RB" -> bind(hullId, "right_back", Outline.BACK, true);
            default -> null;
        };
    }

    private static Moci_NZ666WingPose bind(String hullId, String part, Outline outline, boolean mirrored) {
        return ("Moci_NZ_666_wing_" + part).equals(hullId) ? new Moci_NZ666WingPose(part, outline, mirrored) : null;
    }

    private Moci_NZ666WingPose(String part, Outline outline, boolean mirrored) {
        this.outline = outline;
        this.mirrored = mirrored;
        spritePrefix = "graphics/ships/Mobile_Suit/NZ_666/wings/NZ_666_wing_" + part + "_0";
    }

    int apply(ShipAPI module, int frame) {
        if (frame == appliedFrame || frame < 0 || frame >= LIVE_FRAMES) return appliedFrame;
        BoundsAPI bounds = module.getExactBounds();
        if (bounds == null) return appliedFrame;

        if (edges == null) {
            sprites[0] = module.getSpriteAPI();
            List<BoundsAPI.SegmentAPI> original = bounds.getOrigSegments();
            if (sprites[0] == null || original.size() < 3) return appliedFrame;
            restBounds = new float[original.size() * 2];
            for (int index = 0; index < original.size(); index++) {
                Vector2f point = original.get(index).getP1();
                restBounds[2 * index] = point.x;
                restBounds[2 * index + 1] = point.y;
            }
            edges = original;
        }
        float[] points = frame == 0 ? restBounds : outline.points(frame);

        SpriteAPI sprite = sprites[frame];
        if (sprite == null) {
            sprite = Global.getSettings().getSprite(spritePrefix + frame + ".png");
            if (sprite == null) return appliedFrame;
            sprite.setSize(sprites[0].getWidth(), sprites[0].getHeight());
            sprites[frame] = sprite;
        }

        boolean rebuild = edges.size() * 2 != points.length;
        if (rebuild) bounds.clear();
        float lateralSign = mirrored && frame != 0 ? -1f : 1f;
        for (int start = 0; start < points.length; start += 2) {
            int end = (start + 2) % points.length;
            float startY = lateralSign * points[start + 1], endY = lateralSign * points[end + 1];
            if (rebuild) {
                bounds.addSegment(points[start], startY, points[end], endY);
            } else {
                edges.get(start / 2).set(points[start], startY, points[end], endY);
            }
        }
        if (rebuild) edges = bounds.getOrigSegments();
        bounds.update(module.getLocation(), module.getFacing());
        module.setSprite(sprite);
        appliedFrame = frame;
        return appliedFrame;
    }
}
