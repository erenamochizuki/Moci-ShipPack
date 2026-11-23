package data.subsystems;

import java.util.Locale;

import com.fs.starfarer.api.combat.ShipAPI;

import data.scripts.util.Moci_PilotAura;
import data.scripts.util.Moci_TextLoader;

/**
 * 强化人（Moci_AugmentedHumanSkill）的手动主动能力：新人类主动能力的 75% 版本。
 *
 * 时流加成按 {@link #STRENGTH} 缩放：本舰时流 ×1.375（新人类 ×1.5），世界时流 ×1/1.375；
 * 持续时间、软幅能消耗、按键设置（LunaLib「新人类主动键」）都与新人类相同。
 * 周身极光为紫色，尺寸是新人类的 75%（见 {@link Moci_PilotAura.Style#AUGMENTED}）。
 *
 * 其余逻辑（只给玩家手动用、让位、离场兜底等）全部继承自 {@link Moci_NewtypeSubsystem}。
 * MagicSubsystemsManager 按类登记子系统，所以需要独立的子类。
 */
public class Moci_AugmentedHumanSubsystem extends Moci_NewtypeSubsystem {
    private static final String TEXT_ID = "Moci_AugmentedHumanSkill";

    /** 相对新人类的强度比例。 */
    public static final float STRENGTH = 0.75f;
    public static final float SHIP_TIME_MULT = scaledShipTimeMult(STRENGTH);
    public static final float WORLD_TIME_MULT = 1f / SHIP_TIME_MULT;

    private static final String BRIEF_FALLBACK = String.format(Locale.ROOT,
            Moci_TextLoader.getText("code_Moci_AugmentedHumanSubsystem", "t001", "消耗 %.0f 软幅能：%.0f 秒内本舰时流 ×%.3f，世界时流 ×%.2f"),
            SOFT_FLUX_COST, ACTIVE_SECONDS, SHIP_TIME_MULT, WORLD_TIME_MULT);

    public Moci_AugmentedHumanSubsystem(ShipAPI ship) {
        super(ship, "Moci_AugmentedHuman_", Moci_TextLoader.getText("code_Moci_AugmentedHumanSubsystem", "t002", "强化人"), STRENGTH, Moci_PilotAura.Style.AUGMENTED);
    }

    /** 由强化人技能在本舰成为玩家座舰时调用；船上已有新人类/强化人子系统时不再挂。 */
    public static void attachAugmented(ShipAPI ship) {
        attachWith(ship, Moci_AugmentedHumanSubsystem::new);
    }

    @Override
    public String getDisplayText() {
        return Moci_TextLoader.getText(TEXT_ID, "name", "强化人");
    }

    @Override
    public String getBriefText() {
        return Moci_TextLoader.getText(TEXT_ID, "subsystem.brief", BRIEF_FALLBACK);
    }
}
