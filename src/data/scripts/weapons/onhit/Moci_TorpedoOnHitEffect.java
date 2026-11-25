package data.scripts.weapons.onhit;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.OnHitEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import data.scripts.util.Moci_Util;
import data.scripts.util.Moci_Util.BlastSphereParams;
import data.scripts.util.Moci_Util.BlastWaveParams;
import data.scripts.util.Moci_Util.ShockBlastParams;
import data.scripts.util.Moci_Util.TiltedFront;
import data.scripts.util.Moci_Util.TiltedFronts;
import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.entities.SimpleEntity;
import org.lwjgl.util.vector.Vector2f;

/**
 * 鱼雷命中效果
 *
 * 命中那一帧：
 * 1. 被直接打中的舰船：+1000 幅能（会乘发射舰的能量武器伤害加成），再吃 4 条电弧
 * 2. 爆点附近的其他舰船：每艘吃 4 条电弧（战机/无人机是 1 条电弧 + 3 次直接伤害），按距离打折，友军只吃 25%
 * 3. 画面：组合爆炸（参数全在 createBlastParams 里）+ 6 条不带伤害的装饰电弧 + 音效
 * 之后几秒：
 * 4. 冲击波二次伤害：正面冲击波环扫到谁，谁就吃一次高爆伤害（ShockwaveDamage），每艘船只吃一次
 *
 * 【特效和伤害范围是什么关系】
 * 第 1、2 步的伤害在命中瞬间就算完了，和特效无关。特效尺寸 = AREA_EFFECT × BLAST_VISUAL_SCALE，
 * 所以改 AREA_EFFECT 会让这部分伤害范围和特效一起变；只想改特效大小，就改 BLAST_VISUAL_SCALE。
 * 第 4 步的冲击波伤害是例外：它直接读正面冲击波环（ring）的半径，环画到哪就打到哪。
 * 所以改 ring 的 setRadius / setDuration / setThickness / 半径曲线，或者改 BLAST_VISUAL_SCALE，都会改变冲击波伤害的范围和速度。
 *
 * @author Moci
 */
public class Moci_TorpedoOnHitEffect implements OnHitEffectPlugin {

    // ==================== 伤害参数 ====================

    /**
     * 区域伤害的外圈半径（世界单位）。
     * 判定方式是「爆点到舰船碰撞圆边缘」的距离 ≤ 350，所以船越大，离得越远也能被扫到。
     * 它同时也是特效尺寸的基准，见 BLAST_VISUAL_SCALE。
     */
    private static final float AREA_EFFECT = 350f;

    /**
     * 内圈半径：爆点到舰船碰撞圆边缘 ≤ 150 时吃满额伤害；
     * 150 ～ 350 之间从 100% 线性降到 0%。
     */
    private static final float AREA_EFFECT_INNER = 150f;

    /**
     * 冲击波二次伤害 = 鱼雷基础伤害 × 这个倍率，高爆伤害，不随距离衰减（友军照样只吃 25%）。
     * 0.5 = 半额。填 0 = 关闭冲击波伤害。
     * 范围和速度跟着正面冲击波环走（默认 5 秒内扩到约 493），见类注释。
     */
    private static final float SHOCKWAVE_DAMAGE_MULT = 0.5f;

    /** 直接命中时给目标加的幅能。 */
    private static final float FLUX_DAMAGE = 1000f;

    /** 友军只吃 25% 的区域伤害。 */
    private static final float FRIENDLY_FIRE_MULT = 0.25f;

    // ==================== 电弧颜色（只影响外观） ====================
    // 颜色都是 RGBA（0～255），第四个数越小越淡。
    // 带伤害的电弧：芯 = PARTICLE_COLOR，边 = EXPLOSION_COLOR；装饰电弧反过来用。

    /** 浅蓝 */
    private static final Color EXPLOSION_COLOR = new Color(125, 200, 255, 104);

    /** 亮蓝 */
    private static final Color PARTICLE_COLOR = new Color(150, 215, 255, 170);

    // ==================== 旧版特效（USE_SHOCK_BLAST = false 时才用） ====================

    /** true = 用新的组合爆炸（createBlastParams）；false = 退回旧版 EMP 光环 + 白光闪烁。 */
    private static final boolean USE_SHOCK_BLAST = true;

    /** 旧版白光闪烁的中心颜色。新版不用它。 */
    private static final Color CORE_FLASH_COLOR = new Color(255, 255, 255, 255);

    /** 旧版白光闪烁的边缘颜色。新版不用它。 */
    private static final Color CORE_FLASH_FRINGE_COLOR = new Color(170, 225, 255, 220);

    /** 零向量（播放音效用：声音不跟着移动） */
    private static final Vector2f ZERO = new Vector2f();

    // ==================== 组合爆炸特效调参 ====================
    //
    // 画面由 5 层叠在一起（按大小从外到内）：
    //   fronts    三道斜波：三个倾斜的椭圆波纹，假装是 3D 冲击波从斜角度看过去的样子，最大的一层
    //   haze      底光：铺得很开的一片淡青色雾光，中间亮、往外慢慢没了
    //   ring      正面冲击波环：从爆点往外扩的发光圆环
    //   shell     云壳：中间一团翻滚的发光云
    //   fireball  火球：最中心的白热小球，最早消失
    //
    // 【想改什么 → 改哪里】
    //   整个特效一起放大/缩小      → BLAST_VISUAL_SCALE（不影响伤害）
    //   单独放大/缩小某一层        → 那一层 setRadius(起始, 最终) 里乘的系数；斜波改 tiltedRadius 或各自的「尺寸」
    //   某一层存在得更久/更短      → 那一层 setDuration(秒)；斜波改各自的「结束」时间
    //   某一层扩散得快/慢          → 球形层改 setExpansionPower；ring 改半径曲线
    //   某一层更早/更晚开始变暗    → 球形层改 setFade 的第 2 个数；ring 改亮度曲线
    //   颜色                       → 只调 BLAST_BASE（主色）和 BLAST_HIGHLIGHT（高光色），各层自动跟随
    //   亮度                       → 颜色常量的第四个数，或 setColors / setColor 最后那个强度数
    //   不要某一层                 → 删掉对应的 p.xxx = ... 那一整句（为 null 就不画；删 ring 会连斜波一起删掉）

    /** 特效尺寸倍率：特效基准半径 R = AREA_EFFECT × 1.3 = 455 世界单位（半径，不是直径）。只影响画面。 */
    private static final float BLAST_VISUAL_SCALE = 1.3f;

    /** 正面冲击波环的终点半径 = R × 这个倍数（≈493）。为什么是 1.083 见 createBlastParams 里 ring 的注释。 */
    private static final float RING_END_SCALE = 1.083f;

    // 新版爆炸的两组 RGBA。主色控制波面与暗部；高光色控制白芯与波峰；云壳亮部同时跟随两者。
    // 保留 new Color 字面量，方便在 IDEA 里点色块；各层的偏移另做限位。
    private static final Color BLAST_BASE = new Color(116, 187, 237, 164);
    private static final Color BLAST_HIGHLIGHT = new Color(244, 252, 255, 170);

    private static Color rgba(long r, long g, long b, long a) {
        return new Color(channel(r), channel(g), channel(b), channel(a));
    }

    private static int channel(long value) {
        return (int) Math.max(0L, Math.min(255L, value));
    }

    private static Color offset(Color c, int dr, int dg, int db, int da) {
        return rgba((long) c.getRed() + dr, (long) c.getGreen() + dg,
                (long) c.getBlue() + db, (long) c.getAlpha() + da);
    }

    private static Color midpoint(Color a, Color b) {
        return rgba((a.getRed() + b.getRed()) / 2, (a.getGreen() + b.getGreen()) / 2,
                (a.getBlue() + b.getBlue()) / 2, (a.getAlpha() + b.getAlpha()) / 2);
    }

    /** 每次命中都新建一份参数（参数对象会被特效持有，不能共用）。下面所有半径都是世界单位。 */
    public static ShockBlastParams createBlastParams() {
        ShockBlastParams p = new ShockBlastParams();
        float r = AREA_EFFECT * BLAST_VISUAL_SCALE; // 基准半径 R = 455，下面的尺寸都写成 R 的倍数

        // ---------- 三个球形层（haze / fireball / shell）的参数含义 ----------
        // setRadius(起始半径, 最终半径)
        //     球从多大长到多大。注意这是球的外框，肉眼看到的亮边大约在它的 0.9 倍处；
        //     haze 是一团往外渐隐的雾，外面那一半基本看不见。
        // setDuration(秒)
        //     这一层一共存在多久，时间一到直接消失。
        // setExpansionPower(n)
        //     扩张的「先快后慢」程度：1 = 匀速变大；数越大，越是一开始几乎就涨到位、后面慢慢挪。
        // setFade(淡入秒, 开始变暗的时间点, 变暗曲线)
        //     淡入秒：从全黑亮到最亮用几秒。
        //     开始变暗的时间点：注意是「占总时长的比例」，不是秒！0.16 = 播到 16% 时开始变暗；0 = 一出现就开始变暗。
        //     变暗曲线：1 = 匀速变暗；大于 1 = 一开始暗得快、后面拖个长尾巴；小于 1 = 一直挺亮、快结束时才猛地消失。
        //     换算：满亮停留 = 时长 × 比例 - 淡入秒；淡出用时 = 时长 × (1 - 比例)。
        // setColors(暗部颜色, 亮部颜色, 强度)
        //     强度 = 这一层的整体亮度倍率。haze 只用第二个颜色。

        // 底光：810 → 928（雾状，肉眼可见的大约在 400～500 以内）；时长 3.65 秒；一出现就开始慢慢变暗。
        Color haze = offset(BLAST_BASE, -54, -69, -54, -32);
        p.haze = new BlastSphereParams().setRadius(r*1.78f,r*2.04f).setDuration(3.65f)
                .setExpansionPower(2f).setFade(.035f,0f,1.37f).setColors(haze,haze,.94f);
        // 火球：68 → 123；时长 1.31 秒；淡入 0.035 秒，满亮约 0.08 秒，之后 1.19 秒内很快变暗。
        p.fireball = new BlastSphereParams().setRadius(r*.15f,r*.27f).setDuration(1.31f)
                .setExpansionPower(4.5f).setFade(.035f,.09f,2.5f)
                .setColors(offset(BLAST_BASE, -7, 5, 13, -4), BLAST_HIGHLIGHT, 2.65f);
        // 云壳：146 → 228（看到的云边大约 130 → 200）；时长 3.05 秒；淡入 0.075 秒，满亮约 0.41 秒，之后 2.56 秒淡出。
        p.shell = new BlastSphereParams().setRadius(r*.32f,r*.50f).setDuration(3.05f)
                .setExpansionPower(3.2f).setFade(.075f,.16f,1.24f)
                .setColors(offset(BLAST_BASE, -62, -62, -49, -4),
                        offset(midpoint(BLAST_BASE, BLAST_HIGHLIGHT), -8, 9, 8, -1), 1.13f);

        // ---------- ring 正面冲击波环 ----------
        // 注意：冲击波二次伤害（ShockwaveDamage）读的就是这个环的半径。
        // 改下面的 setRadius / setDuration / setThickness / 半径曲线，伤害范围和推进速度也会跟着变；颜色、亮度、柔光不影响伤害。
        // setRadius(0, ringEnd)    环的外边缘从 0 扩到 ringEnd（见下）。不是匀速扩的，快慢由下面的半径曲线决定。
        // setDuration(5)            整个环存在 5 秒，到点移除。
        // setThickness(.36)         环宽 = 外半径 × 0.36（内边缘在外半径的 64% 处）。
        // setColor(BLAST_BASE, 3.65) 主色 + 亮度倍率。
        // setBloom(.66, 2.7, .28)   柔光：强度、模糊程度、往外多溢出多宽（单位是环宽，0.28 = 多溢出 28% 个环宽）。
        // setRefraction(0, 0)       扭曲背后画面的强度，0 = 关闭。
        //
        // setCurves(半径曲线, 亮度曲线)：每一行是 {时间, 值, 入切线, 出切线}，按时间从小到大排。
        //   时间：「曲线秒」。程序把亮度曲线最后一行的时间（这里是 5.0）当作终点，
        //         把整条曲线等比拉伸到 setDuration 的秒数里。现在两者都是 5，所以曲线秒 = 真实秒。
        //         如果只把 setDuration 改成 2.5，所有曲线（包括下面的斜波时间）都会快一倍。
        //   值：相对值，不是世界单位。半径曲线以「终点那一刻的值」为 100%（= ringEnd）；亮度曲线以「最大值」为 100%。
        //   切线：曲线在这个点的斜率，0 = 在这一点放平。不想研究可以都填 0（每两个点之间会变成两头慢中间快的 S 形）。
        //         注意：下降段的出切线太陡会让曲线冲到 0 以下（提前熄灭），想要平滑归零，最后一行的切线填 0。
        //
        //   现在这条半径曲线：1 秒 ≈229，2 秒 ≈355（正好是伤害外圈 350 左右），3 秒 ≈408，4 秒 ≈455，5 秒 ≈493。
        //   现在这条亮度曲线：0.06 秒前不亮 → 0.08 秒瞬间最亮 → 1.8 秒降到 26% → 2.0 秒回升到 49%（第二次闪）
        //                     → 2.5 秒 20% → 3 秒 8.5% → 4 秒 1% → 约 4.8 秒完全熄灭，边淡出边继续往外扩。
        //
        // ringEnd = R × 1.083：半径曲线的值在第 5 秒比第 4 秒多 8.3%。乘这个数是为了让前 4 秒的大小和原来（4 秒到 455）
        // 一模一样，第 4～5 秒再往外多扩约 38。想扩得更远就改 setDuration 和亮度曲线最后一行的时间（两个保持一致），
        // 再按新的终点重新算这个倍数；或者不在乎前 4 秒变不变，直接把 1.083 调大。
        float ringEnd = r * RING_END_SCALE;
        p.ring = new BlastWaveParams().setRadius(0f,ringEnd).setDuration(5f).setThickness(.36f)
                .setColor(BLAST_BASE,3.65f).setBloom(.66f,2.7f,.28f).setRefraction(0f,0f)
                .setCurves(new float[][]{
                // 半径曲线
                { -0.0041f, 0f, 0f, 3.3753f },
                { 2.0f, 4.6315f, 0.7119f, 0.7201f },
                { 6.9763f, 6.8589f, 0f, 0f } }, new float[][]{
                // 亮度曲线
                { -0.0025f, 0f, 0f, 0f },
                { 0.0572f, 0f, 0f, 0f },
                { 0.0784f, 2.0f, 0f, 0f },
                { 1.7960f, 0.5160f, 0f, 0f },
                { 1.9999f, 0.9841f, 0f, -1.6f },   // 第二次闪之后开始往下掉
                { 3.0f, 0.17f, -0.25f, -0.25f },   // 3 秒时还剩 8.5%，之后放慢，拖一条长尾巴
                { 5.0f, 0f, 0f, 0f } });           // 5 秒归零；切线 0 = 平滑落到 0，不会突然消失
        p.ring.distortOwnLight = true;      // 让环自己的发光纹理带一点热浪般的抖动（不扭曲背景）
        p.ring.tiltedFrontStrength = 1.32f; // 三道斜波的整体亮度，0 = 不画斜波（不影响正面环）

        // ---------- fronts 三道斜波（挂在 ring 上，时间跟着 ring 的曲线秒走） ----------
        // tiltedRadius：三道斜波共用的「组半径」随时间的变化，格式同上 {曲线秒, 值, 入切线, 出切线}。
        //   这里的值写成 R 的倍数，例如 2.02 = 2.02 × 455 ≈ 919 世界单位。
        //   当前：0 秒几乎为 0 → 1.3 秒 0.95R → 1.95 秒 1.68R → 3.2 秒 2.02R。斜波比正面环大很多就是因为这里涨到了 2 倍多。
        float[][] tiltedRadius = {
                {0f, .015f, 0f, .08f}, {.55f, .18f, .58f, .58f}, {1.30f, .95f, 1.24f, 1.24f},
                {1.95f, 1.68f, .80f, .80f}, {2.50f, 1.94f, .25f, .25f}, {3.20f, 2.02f, 0f, 0f} };
        // 程序实际是拿这些值去乘 ring 的终点半径（ringEnd），不是 R。
        // ringEnd 比 R 大了 RING_END_SCALE 倍，这里除回去，保证斜波大小和原来一样、上面的数可以继续按「R 的倍数」来读。
        for (float[] key : tiltedRadius) {
            key[1] /= RING_END_SCALE;
            key[2] /= RING_END_SCALE;
            key[3] /= RING_END_SCALE;
        }
        // TiltedFront(颜色, 朝向, 开口, 尺寸, 出现, 结束, 淡入, 淡出)
        //   朝向：椭圆长轴在屏幕上的方向，单位弧度（0 = 水平，1.57 ≈ 竖直）。每次爆炸还会随机再偏 ±0.18 左右。
        //   开口：0～1，椭圆有多「圆」。1 = 正圆；越小越扁，越像侧着看到的一道弧线。
        //   尺寸：这一道相对组半径的大小。亮边大约在「组半径 × 尺寸 × 0.89」处；刚出现时只有七成大，约 1 秒内长满。
        //   出现 / 结束：曲线秒（≈ 真实秒），这段时间以外不画。
        //   淡入 / 淡出：秒。满亮停留 = 结束 - 出现 - 淡入 - 淡出。
        p.ring.fronts = new TiltedFronts(tiltedRadius, offset(BLAST_HIGHLIGHT, -5, 0, -3, 11),
                new TiltedFront(offset(BLAST_BASE, 2, 5, 5, 11),     .12f,.26f,.91f, .06f,3.02f,.17f,.92f), // 近水平、较扁；0.06～3.02 秒，满亮 1.87 秒
                new TiltedFront(offset(BLAST_BASE, 17, -1, 27, 7),  1.31f,.20f,.79f, .17f,3.20f,.17f,.92f), // 近竖直、最扁；0.17～3.20 秒，满亮 1.94 秒
                new TiltedFront(offset(BLAST_BASE, -10, -12, -6, 16), -.81f,.38f,.67f, .28f,3.12f,.17f,.92f)); // 反向斜切、较圆；0.28～3.12 秒，满亮 1.75 秒
        return p;
    }

    // ==================== 核心逻辑 ====================

    @Override
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point,
                      boolean shieldHit, ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {

        // 安全检查：命中点不能为空
        if (point == null) {
            return;
        }

        // 每条电弧 / 每次直接伤害 = 鱼雷基础伤害的 25%（一艘船吃 4 次，合计约等于 100%）
        float emp = projectile.getEmpAmount() * 0.25f;
        float dam = projectile.getDamageAmount() * 0.25f;

        // ==================== 第一步：找出范围内的舰船 ====================

        // 爆点到舰船碰撞圆边缘 ≤ AREA_EFFECT 的舰船（不含穿梭机）
        List<ShipAPI> targets = Moci_Util.getShipsWithinRange(point, AREA_EFFECT);

        // 剔除：无碰撞的船、被直接打中那艘船的模块、
        // 以及「离爆点最近的那个点」正好落在同阵营其他船护盾里的船（被队友的盾挡住了）
        Moci_Util.filterObscuredTargets(target, point, targets, true, true, false);

        // ==================== 第二步：处理被直接打中的船 ====================

        if (target instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) target;

            // 从区域伤害名单里拿掉，避免它再吃一遍区域伤害
            targets.remove(ship);

            // 加幅能（乘发射舰的能量武器伤害加成）
            if (projectile.getSource() == null) {
                ship.getFluxTracker().increaseFlux(FLUX_DAMAGE, shieldHit);
            } else {
                float weaponDamageMult = projectile.getSource().getMutableStats()
                    .getEnergyWeaponDamageMult().getModifiedValue();
                ship.getFluxTracker().increaseFlux(FLUX_DAMAGE * weaponDamageMult, shieldHit);
            }

            // 4 条带伤害的电弧，不打折
            for (int x = 0; x < 4; x++) {
                ShipAPI empTarget = ship;
                engine.spawnEmpArc(
                    projectile.getSource(),  // 伤害来源
                    point,                   // 起始点
                    empTarget,               // 起始实体
                    empTarget,               // 目标实体
                    DamageType.ENERGY,       // 伤害类型
                    dam,                     // 伤害值
                    emp,                     // EMP伤害
                    100000f,                 // 最大范围
                    null,                    // 音效ID（null使用默认）
                    20f,                     // 电弧粗细
                    PARTICLE_COLOR,          // 芯颜色
                    EXPLOSION_COLOR          // 边颜色
                );
            }
        }

        // ==================== 第三步：处理范围内的其他船 ====================

        for (ShipAPI ship : targets) {
            // 爆点到这艘船碰撞圆边缘的距离（爆点在碰撞圆里面时算 0）
            float edgeDistance = Math.max(0f,
                    MathUtils.getDistance(ship.getLocation(), point) - ship.getCollisionRadius());

            // 伤害系数，1 = 满额：
            //   边缘距离 ≤ 150         → 1
            //   150 ～ 350 之间        → 从 1 线性降到 0（例：250 → 0.5）
            //   超过 350               → 0（正常不会出现，第一步已经按 350 筛过了）
            float reduction = 1f;
            if (edgeDistance > AREA_EFFECT_INNER) {
                reduction = (AREA_EFFECT - edgeDistance) / (AREA_EFFECT - AREA_EFFECT_INNER);
                reduction = Math.max(0f, Math.min(1f, reduction));
            }

            // 友军再乘 25%
            if (ship.getOwner() == projectile.getOwner()) {
                reduction *= FRIENDLY_FIRE_MULT;
            }

            // 战机 / 无人机：1 条电弧 + 在船身附近随机 3 个点各打一次直接伤害
            if (ship.isFighter() || ship.isDrone()) {
                engine.spawnEmpArc(
                    projectile.getSource(),
                    point,
                    null,  // 起始实体为空
                    ship,
                    DamageType.ENERGY,
                    dam * reduction,
                    emp * reduction,
                    500f,  // 范围
                    null,
                    40f,   // 电弧粗细
                    PARTICLE_COLOR,
                    EXPLOSION_COLOR
                );

                for (int x = 0; x < 3; x++) {
                    Vector2f point1 = MathUtils.getRandomPointInCircle(point, ship.getCollisionRadius());
                    engine.applyDamage(
                        ship,
                        point1,
                        dam * reduction,      // 伤害值
                        DamageType.ENERGY,    // 伤害类型
                        emp * reduction,      // EMP伤害
                        false,                // 不绕过护盾
                        false,                // 不是纯护盾伤害
                        projectile.getSource(),
                        false                 // 不播放命中音效
                    );
                }
            }
            // 普通舰船：4 条电弧
            else {
                for (int x = 0; x < 4; x++) {
                    engine.spawnEmpArc(
                        projectile.getSource(),
                        point,
                        null,
                        ship,
                        DamageType.ENERGY,
                        dam * reduction,
                        emp * reduction,
                        500f,
                        null,
                        40f,
                        PARTICLE_COLOR,
                        EXPLOSION_COLOR
                    );
                }
            }
        }

        // ==================== 第四步：冲击波二次伤害 + 画面 ====================

        ShockBlastParams blast = createBlastParams();

        // 冲击波伤害跟着正面环（blast.ring）的半径走。
        // 即使 USE_SHOCK_BLAST = false（不画新版特效），也照样按 ring 的参数结算，只是看不到环。
        if (SHOCKWAVE_DAMAGE_MULT > 0f && blast.ring != null) {
            engine.addPlugin(new ShockwaveDamage(point, blast.ring, projectile.getSource(),
                    projectile.getOwner(), projectile.getDamageAmount() * SHOCKWAVE_DAMAGE_MULT));
        }

        if (USE_SHOCK_BLAST) {
            // 新版组合爆炸，参数见 createBlastParams
            Moci_Util.spawnShockBlast(point, blast);
        } else {
            // 旧版：EMP 光环 + 白光闪烁
            // 参数：位置, 亮度, 最大半径, 持续秒数, 旋转速度(度/秒), 起始大小比例, 结束大小比例
            Moci_EMPBlast empBlast = new Moci_EMPBlast(point, 1.25f, AREA_EFFECT * 1.15f, 0.65f, 25f, 0.2f, 0.75f);
            empBlast.init(Global.getCombatEngine().addLayeredRenderingPlugin(empBlast));

            WhiteCoreFlash coreFlash = new WhiteCoreFlash(point, 1.15f, AREA_EFFECT * 0.9f, 0.75f, 40f, 0.2f, 0.8f);
            coreFlash.init(Global.getCombatEngine().addLayeredRenderingPlugin(coreFlash));
        }

        // 音效：打在护盾上音调高一点
        if (shieldHit) {
            Global.getSoundPlayer().playSound("Moci_Heary_Hit", 1.1f, 1f, point, ZERO);
        } else {
            Global.getSoundPlayer().playSound("Moci_Heary_Hit", 0.9f, 0.9f, point, ZERO);
        }

        // 6 条装饰电弧：从离爆点 150～250 的随机位置连回爆点，不带伤害
        for (int x = 0; x < 6; x++) {
            float angle = (float) Math.random() * 360f;
            float distance = (float) Math.random() * 100f + 150f;

            Vector2f point1 = MathUtils.getPointOnCircumference(point, distance, angle);
            Vector2f point2 = new Vector2f(point);

            engine.spawnEmpArc(
                projectile.getSource(),
                point1,                      // 起始点（外圈）
                new SimpleEntity(point1),    // 起始实体
                new SimpleEntity(point2),    // 目标实体（爆点）
                DamageType.ENERGY,
                0f,                          // 无伤害
                0f,                          // 无EMP
                1000f,                       // 范围
                null,
                15f,                         // 电弧粗细
                EXPLOSION_COLOR,             // 芯颜色
                PARTICLE_COLOR               // 边颜色
            );
        }
    }

    /**
     * 冲击波二次伤害：每帧读一次正面冲击波环的内外半径，
     * 环带（内半径 ～ 外半径）碰到哪艘船的碰撞圆，就给那艘船打一次高爆伤害，每艘船只打一次。
     * 环扫过去之后才飞进环中间空心区的船不会挨打；相位中的船不挨打，但出相位时环还压在它身上就会补上。
     * 残骸也会挨打（能把残骸推开/打碎）。
     * 环的时长（默认 5 秒）一到，插件自己移除。注意环淡出后（约 4.8 秒）到 5 秒之间伤害仍然有效，只是看不见了。
     */
    private static final class ShockwaveDamage extends BaseEveryFrameCombatPlugin {
        private final Vector2f center;
        private final BlastWaveParams wave;
        private final ShipAPI source;
        private final int owner;
        private final float damage;
        private final Set<ShipAPI> alreadyHit = new HashSet<>();
        private float elapsed = 0f;

        private ShockwaveDamage(Vector2f center, BlastWaveParams wave, ShipAPI source, int owner, float damage) {
            this.center = new Vector2f(center);
            this.wave = wave;
            this.source = source;
            this.owner = owner;
            this.damage = damage;
        }

        @Override
        public void advance(float amount, List<InputEventAPI> events) {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null || engine.isPaused()) {
                return;
            }

            elapsed += amount;
            if (elapsed >= wave.getEffectiveDuration()) {
                engine.removePlugin(this);
                return;
            }

            // 和画面用同一套算法，环画到哪伤害就到哪
            float outer = wave.getOuterRadiusAt(elapsed);
            float inner = wave.getInnerRadiusAt(elapsed);
            if (outer <= 0f) {
                return;
            }

            for (ShipAPI ship : engine.getShips()) {
                if (alreadyHit.contains(ship)) continue;
                if (ship.isShuttlePod() || ship.isPhased()) continue;
                if (ship.getCollisionClass() == CollisionClass.NONE) continue;
                if (!engine.isEntityInPlay(ship)) continue;

                // 环带和船的碰撞圆有重叠才算扫到
                float distance = MathUtils.getDistance(ship.getLocation(), center);
                float radius = ship.getCollisionRadius();
                if (distance - radius > outer || distance + radius < inner) continue;

                alreadyHit.add(ship);
                float dealt = damage * (ship.getOwner() == owner ? FRIENDLY_FIRE_MULT : 1f);
                Vector2f hitPoint = Moci_Util.getNearestPointForDamage(center, ship);
                engine.applyDamage(
                    ship,
                    hitPoint,                        // 打在船身离爆点最近的位置，护盾挡着就打在盾上
                    dealt,
                    DamageType.HIGH_EXPLOSIVE,
                    0f,                              // 无 EMP
                    false,                           // 不绕过护盾
                    false,                           // 不是软幅能
                    source,
                    false                            // 不播放命中音效
                );
            }
        }
    }

    /**
     * 旧版特效用的白光闪烁（USE_SHOCK_BLAST = false 时才会出现）。
     * 两张爆炸贴图（芯 + 边）叠加发光，反向旋转、逐渐变大、逐渐变暗。
     */
    private static final class WhiteCoreFlash extends BaseCombatLayeredRenderingPlugin {
        private final Vector2f point;
        private final float brightness;
        private final float radius;
        private final float duration;
        private final float rotationRate;
        private final float coreSizeFractionStart;
        private final float coreSizeFractionEnd;
        private final SpriteAPI core;
        private final SpriteAPI fringe;
        private final boolean flip;

        private float coreAngle;
        private float fringeAngle;
        private float elapsed = 0f;

        private WhiteCoreFlash(Vector2f point, float brightness, float radius, float duration,
                               float rotationRate, float coreSizeFractionStart, float coreSizeFractionEnd) {
            this.point = new Vector2f(point);
            this.brightness = brightness;
            this.radius = radius;
            this.duration = duration;
            this.rotationRate = rotationRate;
            this.coreSizeFractionStart = coreSizeFractionStart;
            this.coreSizeFractionEnd = coreSizeFractionEnd;

            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER;

            core = Global.getSettings().getSprite("fx", "Moci_exp_core");
            fringe = Global.getSettings().getSprite("fx", "Moci_exp_fringe");
            core.setAdditiveBlend();
            fringe.setAdditiveBlend();

            flip = Math.random() > 0.5;
            coreAngle = MathUtils.getRandomNumberInRange(-180f, 180f);
            fringeAngle = MathUtils.getRandomNumberInRange(-180f, 180f);
        }

        @Override
        public void advance(float amount) {
            if (!Global.getCombatEngine().isPaused()) {
                elapsed += amount;
            }

            float sign = flip ? 1f : -1f;
            coreAngle += sign * amount * rotationRate;
            fringeAngle -= sign * amount * rotationRate;

            float level = Math.min(1f, Math.max(0f, elapsed / duration));
            float coreSize = lerp(radius * coreSizeFractionStart, radius * coreSizeFractionEnd, level) * 2f;
            float fringeSize = coreSize * 2f;
            float alpha = Math.min(1f, Math.max(0f, lerp(brightness, 0f, (float) Math.pow(level, 1.3f))));

            core.setAlphaMult(alpha);
            core.setAngle(coreAngle);
            core.setSize(coreSize, coreSize);
            core.setColor(new Color(
                    CORE_FLASH_COLOR.getRed(),
                    CORE_FLASH_COLOR.getGreen(),
                    CORE_FLASH_COLOR.getBlue(),
                    Math.min(255, (int) (alpha * CORE_FLASH_COLOR.getAlpha()))
            ));

            fringe.setAlphaMult(alpha * 0.85f);
            fringe.setAngle(fringeAngle);
            fringe.setSize(fringeSize, fringeSize);
            fringe.setColor(new Color(
                    CORE_FLASH_FRINGE_COLOR.getRed(),
                    CORE_FLASH_FRINGE_COLOR.getGreen(),
                    CORE_FLASH_FRINGE_COLOR.getBlue(),
                    Math.min(255, (int) (alpha * CORE_FLASH_FRINGE_COLOR.getAlpha()))
            ));
        }

        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            core.renderAtCenter(point.x, point.y);
            fringe.renderAtCenter(point.x, point.y);
        }

        @Override
        public float getRenderRadius() {
            return radius;
        }

        @Override
        public boolean isExpired() {
            return elapsed >= duration;
        }

        @Override
        public void init(CombatEntityAPI entity) {
            this.entity = entity;
            entity.getLocation().set(point.x, point.y);
            advance(0f);
        }

        private static float lerp(float start, float end, float t) {
            return start + t * (end - start);
        }
    }
}
