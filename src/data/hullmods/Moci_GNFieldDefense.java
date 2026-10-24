package data.hullmods;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import data.scripts.util.Moci_TextLoader;

import data.scripts.weapons.render.Moci_GNShieldRendering_Shader;
import static data.scripts.weapons.render.Moci_GNShieldRendering_Shader.RENDER_KEY;

/**
 * GN力场防御系统 - 高级版本
 * 结合光环src的护盾管理逻辑和GN护盾的视觉效果
 * 
 * 特性：
 * - 独立护盾值系统（不依赖幅能）
 * - 自动回复机制
 * - 受损后冷却时间
 * - 护盾装甲值
 * - 过载时可选保护
 * - 护盾重启机制
 * 
 * 负面效果：
 * - 峰值时间减少至一半
 */
public class Moci_GNFieldDefense extends BaseHullMod {
    private static final String TEXT_ID = "Moci_GNFieldDefenseSystem";

    // 峰值时间倍率（减少至一半）
    private static final float PEAK_MULT = 0.5f;

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 峰值时间减少至一半
        //stats.getPeakCRDuration().modifyMult(id, PEAK_MULT);
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 添加GN力场防御脚本监听器
        if (!ship.hasListenerOfClass(Moci_GNFieldDefense_Script.class)) {
            ship.addListener(new Moci_GNFieldDefense_Script(ship));
        }
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        // 确保高性能渲染器已初始化
        if (!Global.getCombatEngine().getCustomData().containsKey(RENDER_KEY)) {
            Moci_GNShieldRendering_Shader renderer = Moci_GNShieldRendering_Shader.getInstance();

            // Shared renderer allocates shaders later, inside its render callback.

        }

        // 注册UI渲染器（只需注册一次）
        data.scripts.Moci_GNFieldDefenseUI.register();

        // 处理模块护盾
        if (ship.isShipWithModules()) {
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (!module.hasListenerOfClass(Moci_GNFieldDefense_Script.class)) {
                    module.addListener(new Moci_GNFieldDefense_Script(module));
                }
            }
        }
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return true;
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0)
            return String.format("%.0f", Moci_GNFieldDefense_Config.SHIELD_CAP.get(hullSize) * 100) + "%";
        if (index == 1)
            return String.format("%.0f", Moci_GNFieldDefense_Config.REGEN_MAX_SPEED.get(hullSize) * 100) + "%";
        if (index == 2)
            return String.format("%.1f", Moci_GNFieldDefense_Config.ARMOR_FRAC * 100) + "%";
        if (index == 3)
            return String.format("%.0f", Moci_GNFieldDefense_Config.SHIELD_ARMOR_MAX);
        // if (index == 4)
        //     return String.format("%.0f", (1f - PEAK_MULT) * 100) + "%";
        return null;
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize,
            ShipAPI ship, float width, boolean isForModSpec) {
        float opad = 10f;
        Color h = com.fs.starfarer.api.util.Misc.getHighlightColor();
        Color bg = new Color(100, 255, 227, 200); // GN蓝色背景
        Color b = new Color(154, 255, 208, 255); // GN蓝色文字
        Color c = new Color(150, 255, 199, 225); // GN蓝色表格

        // 系统名称
        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.title"), com.fs.starfarer.api.ui.Alignment.MID, opad);

        // 系统描述
        {
            com.fs.starfarer.api.ui.LabelAPI desc = tooltip.addPara(
                    Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.main",
                            Moci_TextLoader.mapOf(
                                    "shield_cap", getDescriptionParam(0, hullSize),
                                    "regen_speed", getDescriptionParam(1, hullSize),
                                    "armor_frac", getDescriptionParam(2, hullSize),
                                    "armor_max", getDescriptionParam(3, hullSize))),
                    opad, h,
                    Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.main_highlights",
                            Moci_TextLoader.mapOf(
                                    "shield_cap", getDescriptionParam(0, hullSize),
                                    "regen_speed", getDescriptionParam(1, hullSize),
                                    "armor_frac", getDescriptionParam(2, hullSize),
                                    "armor_max", getDescriptionParam(3, hullSize))).toArray(new String[0]));
            desc.setColor(b);

            desc = tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.restart"), opad, h,
                    Moci_TextLoader.getHighlights(TEXT_ID, "description.restart_highlights").toArray(new String[0]));
            desc.setColor(b);

            desc = tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.emp_immunity"), opad, h,
                    Moci_TextLoader.getHighlights(TEXT_ID, "description.emp_immunity_highlights").toArray(new String[0]));
            desc.setColor(b);

            // 负面效果说明
            // Color bad = com.fs.starfarer.api.util.Misc.getNegativeHighlightColor();
            // desc = tooltip.addPara("GN粒子依赖太阳炉的产出，因此舰船的峰值时间减少 %s 。", opad, bad,
            //         getDescriptionParam(4, hullSize));
            // desc.setColor(b);
        }

        // 按住LAlt显示详细信息提示
        if (org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LMENU)) {
            tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.detail_heading"), com.fs.starfarer.api.ui.Alignment.MID, opad);
            {
                com.fs.starfarer.api.ui.LabelAPI desc = tooltip.addPara(
                        Moci_TextLoader.getText(TEXT_ID, "description.detail_text"),
                        opad, h,
                        Moci_TextLoader.getHighlights(TEXT_ID, "description.detail_text_highlights").toArray(new String[0]));
                desc.setColor(b);
            }
        } else {
            tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.detail_hint"), opad, h,
                    Moci_TextLoader.getHighlights(TEXT_ID, "description.detail_hint_highlights").toArray(new String[0]));
        }

        // 如果有ship实例，显示实时数据表格
        if (ship != null && ship.hasListenerOfClass(Moci_GNFieldDefense_Script.class)) {
            Moci_GNFieldDefense_Script script = Moci_GNFieldDefense_Script.getInstance(ship);

            float col1W = 120;
            float colW = (int) ((width - col1W - 12f));

            tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.table_heading"), com.fs.starfarer.api.ui.Alignment.MID, opad + 7f);
            tooltip.beginTable(c, bg, b, 20f, true, true,
                    new Object[] {
                            Moci_TextLoader.getText(TEXT_ID, "description.table_col_attr"), col1W,
                            Moci_TextLoader.getText(TEXT_ID, "description.table_col_value"), colW });

            tooltip.addRow(com.fs.starfarer.api.ui.Alignment.MID, c, Moci_TextLoader.getText(TEXT_ID, "description.table_shield_cap"),
                    com.fs.starfarer.api.ui.Alignment.MID, c,
                    String.format("%.1f", script.getShield().getShieldCap()));

            tooltip.addRow(com.fs.starfarer.api.ui.Alignment.MID, c, Moci_TextLoader.getText(TEXT_ID, "description.table_regen_speed"),
                    com.fs.starfarer.api.ui.Alignment.MID, c,
                    String.format("%.1f", script.getShield().getBaseShieldRegen()) + Moci_TextLoader.getText(TEXT_ID, "description.table_regen_speed_suffix"));

            tooltip.addRow(com.fs.starfarer.api.ui.Alignment.MID, c, Moci_TextLoader.getText(TEXT_ID, "description.table_regen_delay"),
                    com.fs.starfarer.api.ui.Alignment.MID, c,
                    String.format("%.1f", script.getShield().getShieldRegenTime()) + Moci_TextLoader.getText(TEXT_ID, "description.table_seconds_suffix"));

            tooltip.addRow(com.fs.starfarer.api.ui.Alignment.MID, c, Moci_TextLoader.getText(TEXT_ID, "description.table_shield_armor"),
                    com.fs.starfarer.api.ui.Alignment.MID, c,
                    String.format("%.1f", script.getStats().getShieldArmorValue().computeEffective(0f)));

            tooltip.addRow(com.fs.starfarer.api.ui.Alignment.MID, c, Moci_TextLoader.getText(TEXT_ID, "description.table_restart_delay"),
                    com.fs.starfarer.api.ui.Alignment.MID, c,
                    String.format("%.1f", Moci_GNFieldDefense_Config.SHIELD_RESTART_TIME.get(hullSize)) + Moci_TextLoader.getText(TEXT_ID, "description.table_seconds_suffix"));

            tooltip.addTable("", 0, opad / 2f);

            // 按住LAlt显示数据说明
            if (org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LMENU)) {
                tooltip.addSpacer(opad);
                {
                    com.fs.starfarer.api.ui.LabelAPI desc = tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.table_explain_cap"), opad);
                    desc.setColor(b);
                    desc = tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.table_explain_regen"), opad);
                    desc.setColor(b);
                    desc = tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.table_explain_cooldown"), opad);
                    desc.setColor(b);
                    desc = tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.table_explain_armor"), opad);
                    desc.setColor(b);
                    desc = tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.table_explain_restart"), opad);
                    desc.setColor(b);
                }
            }
        }
    }

    @Override
    public boolean shouldAddDescriptionToTooltip(ShipAPI.HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false;
    }
}
