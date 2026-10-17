package data.scripts.util;

import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import java.util.List;
import java.util.Random;

import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Pair;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

public class Moci_MathPersonal {
    public static final Vector2f ZERO = new Vector2f();
    public static final Random RANDOM = new Random();
    public static final float DEVIATION = 1.0E-4F;
    public static double RAD_PER_DEG = 0.01745329251;
    public static double DEG_PER_RAD = (180D / Math.PI);
    public static float sgnPos(float x) {
        return x < 0 ? -1f : 1f;
    }

    /** Misc.getAngleDiff is unsigned; this is signed */
    public static float angleDiff(float a, float b) {
        return ((a - b) % 360 + 540) % 360 - 180;
    }
    public static float randBetween(float a, float b) {
        return randBetween(a, b, Misc.random);
    }
    public static float randBetween(float a, float b, Random random) {
        return random.nextFloat() * (b - a) + a;
    }
    public static boolean isClockwise(Vector2f v1, Vector2f v2) {
        return v1.y * v2.x > v1.x * v2.y;
    }

    public Moci_MathPersonal() {
    }


    public static double pow(double a, double b) {
        double r = (double)1.0F;
        int exp = (int)b;

        for(double base = a; exp != 0; exp >>= 1) {
            if ((exp & 1) != 0) {
                r *= base;
            }

            base *= base;
        }

        double b_faction = b - (double)((int)b);
        long tmp = Double.doubleToLongBits(a);
        long tmp2 = (long)(b_faction * (double)(tmp - 4606921280493453312L)) + 4606921280493453312L;
        return r * Double.longBitsToDouble(tmp2);
    }

    public static double toRadians(double angDeg) {
        return RAD_PER_DEG * angDeg;
    }

    public static double toDegrees(double angRad) {
        return DEG_PER_RAD * angRad;
    }

    public static double sqrt(double a) {
        return pow(a, (double)0.5F);
    }

    public static float clamp(float min, float max, float value) {
        return max < min ? clamp(max, min, value) : Math.max(min, Math.min(max, value));
    }

    public static float clamp01(float value) {
        return clamp(0.0F, 1.0F, value);
    }

    public static float getRandomAngle() {
        return RANDOM.nextFloat() * 360.0F;
    }

    public static float getGaussian() {
        float value = (float)RANDOM.nextGaussian();
        value = clamp(-4.0F, 4.0F, value);
        return value;
    }


    public static float getNormalizedGaussian() {
        float value = (float)RANDOM.nextGaussian();
        value = clamp(-1.0F, 1.0F, value / 4.0F);
        return value;
    }

    public static boolean rollChance(float chance) {
        return rollChance(chance, RANDOM);
    }

    public static boolean rollChance(float chance, Random random) {
        if (chance >= 1.0F) {
            return true;
        } else if (chance <= 0.0F) {
            return false;
        } else {
            return random.nextFloat() < chance;
        }
    }

    public static boolean roll() {
        return RANDOM.nextBoolean();
    }

    public static int computeConditionalIncrement(float times) {
        int result = (int)times;
        if (rollChance(times - (float)result)) {
            ++result;
        }

        return result;
    }

    public static float lerp(float from, float to, float factor) {
        return (to - from) * factor + from;
    }

    public static float lerpAndStopWhileClose(float from, float to, float factor) {
        float result = lerp(from, to, factor);
        return Math.abs(to - result) < 1.0E-4F ? to : result;
    }

    public static float getEffectLevel(float elapsed, float in, float full, float out) {
        if (elapsed < in) {
            return elapsed / in;
        } else {
            float inAndFull = in + full;
            if (elapsed >= in && elapsed <= inAndFull) {
                return 1.0F;
            } else {
                return elapsed > inAndFull && elapsed < inAndFull + out ? 1.0F - (elapsed - inAndFull) / out : 0.0F;
            }
        }
    }

    public static Vector2f getRandomPointInFan(Vector2f point, float a, float b, float angle, float minRange) {
        float r = (float)((double)a * Math.sqrt((double)RANDOM.nextFloat()));
        float fi = (float)((Math.PI * 2D) * (double)RANDOM.nextFloat());
        float x = (float)((double)r * Math.cos((double)fi));
        float y = (float)((double)(b / a * r) * Math.sin((double)fi));
        Vector2f result = new Vector2f(x, y);
        VectorUtils.rotate(result, angle);
        return Vector2f.add(point, result, (Vector2f)null);
    }

    public static void main(String[] args) {
        System.out.println("测试从1到1亿，平方根的用时（秒）");
        long startTime = System.currentTimeMillis();
        double[] origin = new double[100000000];
        double[] compared = new double[100000000];
        double total = (double)0.0F;
        double error = (double)0.0F;

        for(int i = 1; i <= 100000000; ++i) {
            origin[i - 1] = Math.sqrt((double)i);
        }

        long endTime = System.currentTimeMillis();
        System.out.println("普通Math.sqrt:" + (double)(endTime - startTime) / (double)1000.0F);
        startTime = System.currentTimeMillis();

        for(int i = 1; i <= 100000000; ++i) {
            compared[i - 1] = sqrt((double)i);
        }

        endTime = System.currentTimeMillis();
        System.out.println("魔法Math.sqrt:" + (double)(endTime - startTime) / (double)1000.0F);

        for(int i = 0; i < origin.length; ++i) {
            total += origin[i];
            double variance = compared[i] - origin[i];
            error += Math.abs(variance / origin[i]);
        }

        System.out.println("误差:" + error / (double)origin.length * (double)100.0F + "%");
    }

    public static Vector2f toRelative(CombatEntityAPI entity, Vector2f absolutePoint) {
        Vector2f retVal = new Vector2f(absolutePoint);
        Vector2f.sub(retVal, entity.getLocation(), retVal);
        VectorUtils.rotate(retVal, -entity.getFacing(), retVal);
        return retVal;
    }

    public static Vector2f toRelativeByShield(ShipAPI ship, Vector2f absolutePoint) {
        Vector2f retVal = new Vector2f(absolutePoint);
        Vector2f.sub(retVal, ship.getShieldCenterEvenIfNoShield(), retVal);
        VectorUtils.rotate(retVal, -ship.getShield().getFacing(), retVal);
        return retVal;
    }

    public static Vector2f toAbsolute(CombatEntityAPI entity, Vector2f relativePoint) {
        Vector2f retVal = new Vector2f(relativePoint);
        VectorUtils.rotate(retVal, entity.getFacing(), retVal);
        Vector2f.add(retVal, entity.getLocation(), retVal);
        return retVal;
    }

    public static Vector2f toAbsoluteByShield(ShipAPI ship, Vector2f relativePoint) {
        Vector2f retVal = new Vector2f(relativePoint);
        VectorUtils.rotate(retVal, ship.getShield().getFacing(), retVal);
        Vector2f.add(retVal, ship.getShieldCenterEvenIfNoShield(), retVal);
        return retVal;
    }

    public static void safeNormalize(Vector2f v) {
        if (v.x*v.x + v.y*v.y > 0) {
            v.normalise();
        }
    }

    public static float interpolate(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static float modPositive(float x, float mod) {
        x = x % mod;
        return x < 0 ? x + mod : x;
    }

    /** returns f(b) - f(a) */
    public static float applyAtLimits(Function f, float a, float b) {
        return f.apply(b) - f.apply(a);
    }

    public static Vector2f randomPointInCircle(Vector2f center, float radius) {
        float theta = Misc.random.nextFloat() * 2f *  (float) Math.PI;
        float r = radius * (float) Math.sqrt(Misc.random.nextFloat());
        return new Vector2f(center.x + r*(float)Math.cos(theta), center.y + r*(float)Math.sin(theta));
    }

    public static Vector2f randomPointInRing(Vector2f center, float inRadius, float outRadius) {
        float theta = Misc.random.nextFloat() * 2f * (float) Math.PI;
        float r = (float) Math.sqrt(Misc.random.nextFloat() * (outRadius*outRadius - inRadius*inRadius) + inRadius*inRadius);
        return new Vector2f(center.x + r*(float)Math.cos(theta), center.y + r*(float)Math.sin(theta));
    }

    /** Assumes that the quadratic is concave.
     *  Input the value of the quadratic at T = 0 (start), T = maxTime (end), and the quadratic's peak.
     *  Returns the linear and quadratic coefficients. */
    public static Pair<Float, Float> getRateAndAcceleration(float start, float end, float peak, float maxTime) {
        float sqrtTerm = (float) Math.sqrt((peak - end) * (peak - start));
        float a = 2f * (-2f*sqrtTerm + end - 2f*peak + start) / (maxTime*maxTime);
        float r = 2f * (sqrtTerm + peak - start) / maxTime;
        return new Pair<>(r, a);
    }

    public static Vector2f getVertexCenter(CombatEntityAPI entity) {
        BoundsAPI bounds = entity.getExactBounds();
        if (bounds == null) return entity.getLocation();

        bounds.update(entity.getLocation(), entity.getFacing());
        List<BoundsAPI.SegmentAPI> segments = bounds.getSegments();
        Vector2f sum = new Vector2f();
        for (BoundsAPI.SegmentAPI segment : segments) {
            Vector2f.add(sum, segment.getP2(), sum);
        }

        sum.scale(1f / segments.size());
        return sum;
    }

    public static float[][] clone2DArray(float[][] arr) {
        float[][] res = new float[arr.length][];
        for (int i = 0; i < arr.length; i++) {
            res[i] = arr[i].clone();
        }
        return res;
    }

    public interface Function {
        float apply(float x);
    }

    public static Vector2f getVector(Vector2f point, float angle, float distance) {
        float processed_angle = (float)((double)angle * Math.PI / (double)180.0F);
        float posX = (float)((double)distance * Math.cos((double)processed_angle) + (double)point.x);
        float posY = (float)((double)distance * Math.sin((double)processed_angle) + (double)point.y);
        return new Vector2f(posX, posY);
    }

    public static Vector2f pickLocation(ShipAPI ship) {
        Random rnd = new Random();
        float random = rnd.nextFloat();
        float diffAngle = random * 30.0F + 30.0F;
        random = rnd.nextFloat();
        float angle = random < 0.5F ? ship.getFacing() - diffAngle : ship.getFacing() + diffAngle;
        angle = MathUtils.clampAngle(angle);
        random = rnd.nextFloat();
        float radius = ship.getCollisionRadius() * random + ship.getCollisionRadius();
        return MathUtils.getPointOnCircumference(ship.getLocation(), radius, angle);
    }

    public static float normalize(float value, float min, float max) {
        return (value - min) / (max - min);
    }
}
