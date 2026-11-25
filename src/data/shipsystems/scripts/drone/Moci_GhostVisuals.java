package data.shipsystems.scripts.drone;

import data.scripts.util.Moci_Util.ShipCopyRenderer.ShipCopy;
import java.util.concurrent.ThreadLocalRandom;
import org.dark.shaders.distortion.DistortionShader;
import org.dark.shaders.distortion.WaveDistortion;
import org.lwjgl.util.vector.Vector2f;

/** 暴走亡灵两个系统共用的退场开关、径向波和落点轨迹。 */
public final class Moci_GhostVisuals {
    /** true：朝下一落点移动并留下风痕；false：沿机尾退开，画面径向内收。改后重新构建。 */
    public static final boolean USE_GHOST_WIND_EXIT = true;
    private Moci_GhostVisuals() {}

    public static final class Pulse {
        public final float strength = 2f * (.8f + .4f * ThreadLocalRandom.current().nextFloat());
        public final float speed = 2f * (.8f + .4f * ThreadLocalRandom.current().nextFloat());
        private WaveDistortion wave;

        // 现身从最强松弛；退场从开始移动的那一帧起单调收紧。
        public float relax(float elapsed, float duration) {
            return Math.max(0f, Math.min(1f, 1f - elapsed * speed / duration));
        }

        public float contract(float retreatProgress) {
            float p = Math.max(0f, Math.min(1f, retreatProgress));
            return speed * p / (1f + (speed - 1f) * p);
        }

        public void show(Vector2f at, float radius, float level, float lifetime) {
            if (!(level > 0f)) { clear(); return; }
            boolean fresh = wave == null;
            if (fresh) {
                wave = new WaveDistortion(at, new Vector2f());
                wave.flip(true); // GraphicsLib 默认是凸透镜，反转后只向中心收。
                wave.setLifetime(lifetime + .25f);
            }
            wave.setLocation(at);
            wave.setSize(Math.max(1f, radius * (2f - .4f * level)));
            wave.setIntensity(75f * strength * (float) Math.sqrt(level));
            if (fresh) DistortionShader.addDistortion(wave);
        }

        public void clear() {
            if (wave != null) DistortionShader.removeDistortion(wave);
            wave = null;
        }
    }

    public static final class Exit {
        private final Vector2f origin = new Vector2f(), end = new Vector2f();
        private boolean linked;
        private float lastProgress, startProgress;

        public void begin(Vector2f at) { origin.set(at); }

        // 下一道可以先于本次开火生成，也可以在本次淡出途中才出现。
        public void link(Vector2f at) {
            if (linked) return;
            end.set(at);
            linked = true;
            startProgress = lastProgress;
        }

        public void sample(Vector2f position, ShipCopy copy, float progress, float shipRadius) {
            lastProgress = progress;
            position.set(origin);
            if (!linked) return;
            float t = Math.min(1f, Math.max(0f, (progress - startProgress) / Math.max(.0001f, 1f - startProgress)));
            float dx = end.x - origin.x, dy = end.y - origin.y;
            position.translate(dx * t * t, dy * t * t);
            if (copy != null) {
                // 世界坐标曝光随舰体尺度和镜头缩放；纯白骑士半径 59.5，对应原图约 30 像素。
                float length = (float) Math.sqrt(dx * dx + dy * dy);
                float shutter = Math.min(1f, 3f * t);
                float scale = Math.min(.55f, (.5f * shipRadius) / Math.max(1f, length)) * shutter * (2f - shutter);
                copy.wind.set(dx * scale, dy * scale);
            }
        }
    }
}
