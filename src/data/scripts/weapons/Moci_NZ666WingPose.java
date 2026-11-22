package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

import java.io.IOException;
import java.util.List;

final class Moci_NZ666WingPose {
    private static final int LIVE_FRAMES = 9;

    private enum Outline {
        FRONT("data/config/Moci_NZ666WingLeftFrontFrames.json"),
        BACK("data/config/Moci_NZ666WingLeftBackFrames.json");

        final String config;
        private float[][] frames;

        Outline(String config) { this.config = config; }

        float[] points(int frame) {
            if (frames == null) frames = loadMovementBounds(config);
            return frames[frame];
        }
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
        if (points == null) return appliedFrame;

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

    private static float[][] loadMovementBounds(String configPath) {
        try {
            JSONObject config = Global.getSettings().loadJSON(configPath);
            float[][] result = new float[LIVE_FRAMES][];
            for (int frame = 1; frame < LIVE_FRAMES; frame++) {
                JSONArray values = config.getJSONArray("0" + frame);
                if (values.length() < 6 || values.length() % 2 != 0) {
                    throw new JSONException("Invalid polygon for wing frame " + frame);
                }
                float[] points = new float[values.length()];
                for (int index = 0; index < points.length; index++) {
                    points[index] = (float) values.getDouble(index);
                    if (!Float.isFinite(points[index])) throw new JSONException("Non-finite coordinate in wing frame " + frame);
                }
                result[frame] = points;
            }
            return result;
        } catch (IOException | JSONException failure) {
            Global.getLogger(Moci_NZ666WingPose.class).error("Cannot load " + configPath + "; keeping the affected wings in their rest pose", failure);
            return new float[LIVE_FRAMES][];
        }
    }
}
