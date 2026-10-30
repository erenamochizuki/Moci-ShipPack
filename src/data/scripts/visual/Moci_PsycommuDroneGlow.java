package data.scripts.visual;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * 把赛克缪（Psycho-frame）描边接到“放飞后的浮游炮”身上。
 *
 * 母舰的 halo 由 Moci_PsycommuSystem 按系统效果等级驱动；浮游炮是独立实体，没有那套
 * hullmod 钩子，所以这里每帧镜像母舰当前的描边数值，再整体缩小一档，避免 3px 的描边
 * 在 40px 的浮游炮上显得过厚。
 *
 * 只有母舰本身就带着赛克缪 halo 时才会挂：母舰没装赛克缪，浮游炮这边什么也不会发生。
 */
public final class Moci_PsycommuDroneGlow {

    private static final String MOD_ID = "moci_psycommu_drone_halo";
    private static final String ATTACHED_KEY = "Moci_PsycommuDroneGlow_attached";

    // 浮游炮比母舰小得多，整体收一档；想调手感改这里即可。
    private static final float SIZE_SCALE = 0.55f;
    private static final float INTENSITY_SCALE = 0.85f;
    private static final float ALPHA_SCALE = 0.80f;
    private static final float FLASH_SCALE = 0.80f;
    private static final float SHIFT_SCALE = 0.90f;

    private Moci_PsycommuDroneGlow() {
    }

    /** 给一架刚生成/正在生成的浮游炮挂上描边。母舰没有赛克缪 halo 时直接跳过。 */
    public static void attach(ShipAPI drone, ShipAPI mother) {
        if (drone == null || mother == null) {
            return;
        }
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }
        if (Moci_PsycommuGlowVisual.getInstance(engine, mother, false) == null) {
            return;
        }
        if (Moci_PsycommuGlowVisual.getInstance(engine, drone, false) != null) {
            return;
        }
        Moci_PsycommuGlowVisual visual = Moci_PsycommuGlowVisual.getInstance(engine, drone, true);
        drone.setCustomData(ATTACHED_KEY, Boolean.TRUE);
        // 刚生成时还停在 WAITING_ORDER，先按“未放出”处理。
        apply(visual, drone, mother, false);
    }

    /** 每帧把母舰当前的描边数值镜像到浮游炮上；只有浮游炮已经放出（不在待命/回收冷却）时才画。 */
    public static void update(ShipAPI drone, ShipAPI mother, boolean deployed) {
        if (drone == null) {
            return;
        }
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }
        Moci_PsycommuGlowVisual visual = Moci_PsycommuGlowVisual.getInstance(engine, drone, false);
        if (visual == null) {
            return;
        }
        apply(visual, drone, mother, deployed);
    }

    /** 浮游炮被移除/回收时把渲染插件一起收掉，避免残留插件继续读已失效的 ship。 */
    public static void detach(ShipAPI drone) {
        if (drone == null) {
            return;
        }
        drone.getCustomData().remove(ATTACHED_KEY);
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }
        Moci_PsycommuGlowVisual visual = Moci_PsycommuGlowVisual.getInstance(engine, drone, false);
        if (visual != null) {
            visual.setValid(false);
        }
    }

    private static void apply(Moci_PsycommuGlowVisual visual, ShipAPI drone, ShipAPI mother, boolean deployed) {
        if (visual == null || drone == null) {
            return;
        }
        CombatEngineAPI engine = Global.getCombatEngine();
        Moci_PsycommuGlowVisual motherVisual = null;
        if (engine != null && mother != null && mother.isAlive()) {
            motherVisual = Moci_PsycommuGlowVisual.getInstance(engine, mother, false);
        }
        if (!deployed || motherVisual == null || !drone.isAlive()) {
            // 没放出 / 母舰没了 / 浮游炮已经死了：数值清零，渲染插件下一帧就会早退。
            visual.getOutlineSizeStat().modifyMult(MOD_ID, 0f);
            visual.getOutlineIntensityStat().modifyMult(MOD_ID, 0f);
            visual.getOutlineAlphaMultStat().modifyMult(MOD_ID, 0f);
            visual.getOutlineFlashMultStat().modifyMult(MOD_ID, 0f);
            visual.getOutlineShiftMultStat().modifyMult(MOD_ID, 0f);
            visual.setOutlineColorOverride(null);
            return;
        }

        // 直接镜像母舰的生效值（里面已经含系统效果等级与 NTD 换色），再整体缩放。
        float size = motherVisual.getOutlineSize() * SIZE_SCALE;
        float intensity = motherVisual.getOutlineIntensity() * INTENSITY_SCALE;
        float alpha = motherVisual.getOutlineAlphaMult() * ALPHA_SCALE;
        float flash = motherVisual.getOutlineFlashMult() * FLASH_SCALE;
        float shift = motherVisual.getOutlineShiftMult() * SHIFT_SCALE;

        // StatBonus 的 base 值不是 1，按 base 折算成倍率，才能让浮游炮和母舰视觉一致。
        visual.getOutlineSizeStat().modifyMult(MOD_ID, size / Moci_PsycommuGlowVisual.BASE_OUTLINE_SIZE);
        visual.getOutlineIntensityStat().modifyMult(MOD_ID, intensity / Moci_PsycommuGlowVisual.BASE_OUTLINE_INTENSITY);
        visual.getOutlineAlphaMultStat().modifyMult(MOD_ID, alpha / Moci_PsycommuGlowVisual.BASE_OUTLINE_ALPHA);
        visual.getOutlineFlashMultStat().modifyMult(MOD_ID, flash / Moci_PsycommuGlowVisual.BASE_OUTLINE_FLASH);
        visual.getOutlineShiftMultStat().modifyMult(MOD_ID, shift / Moci_PsycommuGlowVisual.BASE_OUTLINE_SHIFT);
        visual.getColorShiftMultStat().modifyFlat(MOD_ID, motherVisual.getColorShiftMultStat().computeEffective(0f));
        visual.setOutlineColorOverride(motherVisual.getOutlineColor());
    }
}
