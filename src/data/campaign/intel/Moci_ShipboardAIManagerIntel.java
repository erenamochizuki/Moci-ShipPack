package data.campaign.intel;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.IntelUIAPI;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.hullmods.Moci_ShipboardAIAuxEffectRegistry;
import data.hullmods.Moci_ShipboardAIConfigLoader;
import data.hullmods.Moci_ShipboardAIManager;
import data.hullmods.Moci_ShipboardAITextRegistry;
import org.apache.log4j.Logger;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 舰载AI管理情报界面。
 */
public class Moci_ShipboardAIManagerIntel extends BaseIntelPlugin {
    private static final Logger LOG = Global.getLogger(Moci_ShipboardAIManagerIntel.class);
    private static final String AI_MANAGER_LOG_PREFIX = "[MOCI_AI_MANAGER_INTEL]";

    private static final boolean DEBUG_MODE = false;
    private static final float ENTRY_HEIGHT = 176f;
    private static final float PORTRAIT_SIZE = 128f;
    private static final Color SKILL_NORMAL_COLOR = new Color(255, 215, 0);
    private static final Color SKILL_ELITE_COLOR = Misc.getPositiveHighlightColor();
    private static final Color BENEFIT_COLOR = Misc.getPositiveHighlightColor();
    private static final Color NEGATIVE_COLOR = Misc.getNegativeHighlightColor();

    private String selectedMemberId = null;

    public Moci_ShipboardAIManagerIntel() {
        Global.getSector().getIntelManager().addIntel(this);
        Global.getSector().addScript(this);
        setImportant(true);
    }

    @Override
    public String getSmallDescriptionTitle() {
        return getName();
    }

    protected String getName() {
        return uiText("intel_name", "舰载AI管理");
    }

    @Override
    public boolean hasSmallDescription() {
        return false;
    }

    @Override
    public boolean hasLargeDescription() {
        return true;
    }

    @Override
    public boolean isPlayerVisible() {
        return true;
    }

    @Override
    public void createLargeDescription(CustomPanelAPI panel, float width, float height) {
        TooltipMakerAPI info = panel.createUIElement(width, height, true);
        try {
            FactionAPI faction = Global.getSector().getPlayerFaction();
            info.addSectionHeading(uiText("intel_heading", "舰载AI管理系统"),
                    faction.getBaseUIColor(), faction.getDarkUIColor(), Alignment.MID, 10f);

            if (selectedMemberId == null) {
                createListView(panel, info, width);
            } else {
                logAIDetailInfo("render_tab_enter", selectedMemberId, null, null, null,
                        "panelWidth=" + width + " | panelHeight=" + height);
                createDetailView(panel, info, width, height, selectedMemberId);
            }
        } catch (Exception ex) {
            logAIDetailError("large_description_exception", selectedMemberId, null, null, null, ex);
            addDetailRenderError(info, 10f);
        }

        panel.addUIElement(info).inTL(0f, 0f);
    }

    private void createListView(CustomPanelAPI panel, TooltipMakerAPI info, float width) {
        List<FleetMemberAPI> members = Moci_ShipboardAIManager.getPlayerFleetMembersWithAI();
        if (members.isEmpty()) {
            info.addPara(uiText("intel_no_ships", "当前没有安装舰载AI系统的舰船。"), 8f, Misc.getGrayColor());
            return;
        }

        float panelHeight = members.size() * (ENTRY_HEIGHT + 10f);
        CustomPanelAPI listPanel = panel.createCustomPanel(width - 8f, panelHeight, null);

        float y = 0f;
        for (FleetMemberAPI member : members) {
            Moci_ShipboardAIManager.ShipboardAIData data = Moci_ShipboardAIManager.getOrCreateData(member);
            if (data == null) {
                continue;
            }

            Moci_ShipboardAIConfigLoader.AITypeDefinition type =
                    Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
            if (type == null) {
                continue;
            }

            float shipIconWidth = PORTRAIT_SIZE + 8f;
            TooltipMakerAPI shipImage = listPanel.createUIElement(shipIconWidth, ENTRY_HEIGHT, false);
            List<FleetMemberAPI> single = new ArrayList<>();
            single.add(member);
            shipImage.addShipList(1, 1, (int) PORTRAIT_SIZE, Misc.getButtonTextColor(), single, 0f);
            listPanel.addUIElement(shipImage).inTL(0f, y + 8f);

            float shipTextX = shipIconWidth + 12f;
            float shipTextWidth = 220f;
            float aiPortraitX = shipTextX + shipTextWidth + 12f;
            float aiTextX = aiPortraitX + PORTRAIT_SIZE + 12f;
            float aiTextWidth = Math.max(120f, width - aiTextX - 24f);

            TooltipMakerAPI shipText = listPanel.createUIElement(shipTextWidth, ENTRY_HEIGHT, true);
            String safeShipName = escapeTooltipFormat(member.getShipName());
            String safeHullName = escapeTooltipFormat(member.getHullSpec().getHullNameWithDashClass());
            String displayName = data.customName != null && !data.customName.trim().isEmpty() ? data.customName : type.name;
            int level = Moci_ShipboardAIManager.getCurrentLevel(data);
            int levelCap = type.levelCap;
            int currExp = (int) Moci_ShipboardAIManager.getCurrentExp(data);
            int nextExp = (int) Moci_ShipboardAIManager.getNextLevelExp(data);
            String safeDisplayName = escapeTooltipFormat(displayName);

            shipText.addPara(formatUi("intel_ship_label", "舰船: {value}", "value", safeShipName),
                    6f, Misc.getTextColor(), Misc.getHighlightColor(), safeShipName);
            shipText.addPara(formatUi("intel_hull_label", "舰体: {value}", "value", safeHullName),
                    4f, Misc.getTextColor(), Misc.getHighlightColor(), safeHullName);
            listPanel.addUIElement(shipText).inTL(shipTextX, y + 2f);

            TooltipMakerAPI portrait = listPanel.createUIElement(PORTRAIT_SIZE, PORTRAIT_SIZE, false);
            portrait.addImage(type.portrait, PORTRAIT_SIZE, 0f);
            listPanel.addUIElement(portrait).inTL(aiPortraitX, y + 8f);

            TooltipMakerAPI btn = listPanel.createUIElement(PORTRAIT_SIZE, PORTRAIT_SIZE, false);
            List<Object> params = new ArrayList<>();
            params.add("open_detail");
            params.add(member.getId());
            btn.addAreaCheckbox("",
                    params,
                    Misc.getBasePlayerColor(),
                    Misc.getDarkPlayerColor(),
                    Misc.getBrightPlayerColor(),
                    (int) PORTRAIT_SIZE,
                    (int) PORTRAIT_SIZE,
                    0f);
            listPanel.addUIElement(btn).inTL(aiPortraitX, y + 8f);

            TooltipMakerAPI aiText = listPanel.createUIElement(aiTextWidth, ENTRY_HEIGHT, true);
            aiText.addPara(formatUi("intel_ai_name_label", "AI名称: {value}", "value", safeDisplayName),
                    6f, Misc.getTextColor(), Misc.getHighlightColor(), safeDisplayName);
            aiText.addPara(uiText("intel_level_label", "等级: %s/%s"), 4f,
                    new Color[]{BENEFIT_COLOR, Misc.getHighlightColor()},
                    String.valueOf(level), String.valueOf(levelCap));
            aiText.addPara(uiText("intel_exp_label", "经验: %s/%s"), 4f,
                    new Color[]{BENEFIT_COLOR, Misc.getHighlightColor()},
                    String.valueOf(currExp), String.valueOf(nextExp));
            listPanel.addUIElement(aiText).inTL(aiTextX, y + 2f);

            y += ENTRY_HEIGHT + 10f;
        }

        info.addCustom(listPanel, 6f);
    }

    private void createDetailView(CustomPanelAPI panel, TooltipMakerAPI info, float width, float height, String memberId) {
        FleetMemberAPI member = null;
        Moci_ShipboardAIManager.ShipboardAIData data = null;
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = null;

        try {
            logAIDetailInfo("render_start", memberId, null, null, null,
                    "panelWidth=" + width + " | panelHeight=" + height);
            member = findMemberById(memberId);
            if (member == null) {
                selectedMemberId = null;
                logAIDetailWarn("render_missing_ship", memberId, null, null, null);
                info.addPara(uiText("intel_missing_ship", "目标舰船不存在，可能已离开舰队。"), 8f, Misc.getNegativeHighlightColor());
                info.addButton(uiText("intel_button_back", "返回"), "back_to_list", 120, 24, 10f);
                return;
            }

            data = Moci_ShipboardAIManager.getOrCreateData(member);
            if (data == null) {
                selectedMemberId = null;
                logAIDetailWarn("render_missing_data", memberId, member, null,
                        "hasShipboardAISystem=" + Moci_ShipboardAIConfigLoader.hasShipboardAISystem(member));
                info.addPara(uiText("intel_missing_data", "该舰船未初始化舰载AI数据。"), 8f, Misc.getNegativeHighlightColor());
                info.addButton(uiText("intel_button_back", "返回"), "back_to_list", 120, 24, 10f);
                return;
            }

            type = Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
            if (type == null) {
                selectedMemberId = null;
                logAIDetailWarn("render_missing_type", memberId, member, data, null);
                info.addPara(uiText("intel_missing_type", "舰载AI类型配置缺失。"), 8f, Misc.getNegativeHighlightColor());
                info.addButton(uiText("intel_button_back", "返回"), "back_to_list", 120, 24, 10f);
                return;
            }

            String displayName = data.customName != null && !data.customName.trim().isEmpty() ? data.customName : type.name;
            int level = Moci_ShipboardAIManager.getCurrentLevel(data);
            float exp = Moci_ShipboardAIManager.getCurrentExp(data);
            float nextExp = Moci_ShipboardAIManager.getNextLevelExp(data);
            String safeShipName = escapeTooltipFormat(member.getShipName());
            String safeDisplayName = escapeTooltipFormat(displayName);
            String safePersonality = escapeTooltipFormat(Moci_ShipboardAIManager.getPersonalityLabel(type.personality));
            float sectionPad = 10f;
            float sectionHeaderPad = 6f;
            float sectionContentPad = 6f;

            List<String> normalOptions = Moci_ShipboardAIManager.getAvailableSkillOptions(member);
            List<String> eliteOptions = Moci_ShipboardAIManager.getAvailableEliteSkillOptions(member);
            List<String> auxOptions = Moci_ShipboardAIManager.getAvailableAuxOptions(member);
            List<String> evolutionOptions = Moci_ShipboardAIManager.getAvailableEvolutionOptions(member);
            int normalPick = Moci_ShipboardAIManager.getAvailableSkillPickCount(data, type);
            int elitePick = Moci_ShipboardAIManager.getAvailableElitePickCount(data, type);
            int auxPick = Moci_ShipboardAIManager.getAvailableAuxPickCount(data, type);
            logAIDetailInfo("render_context_ready", memberId, member, data, type,
                    "normalPick=" + normalPick
                            + " | elitePick=" + elitePick
                            + " | auxPick=" + auxPick
                            + " | normalOptions=" + normalOptions.size()
                            + " | eliteOptions=" + eliteOptions.size()
                            + " | auxOptions=" + auxOptions.size()
                            + " | evolutionOptions=" + evolutionOptions.size());

            float columnsWidth = Math.max(320f, width - 24f);
            float columnGap = 12f;
            float preferredRightColumnWidth = 236f;
            float preferredLeftColumnWidth = 420f;
            float minWidthForFixedColumns = 252f + preferredRightColumnWidth + columnGap;
            float rightColumnWidth;
            float leftColumnWidth;
            if (columnsWidth >= minWidthForFixedColumns) {
                // Keep the action column close to the info column and leave room for future content on the right.
                rightColumnWidth = preferredRightColumnWidth;
                leftColumnWidth = Math.min(preferredLeftColumnWidth, columnsWidth - rightColumnWidth - columnGap);
            } else {
                rightColumnWidth = Math.min(340f, columnsWidth * 0.38f);
                leftColumnWidth = columnsWidth - rightColumnWidth - columnGap;
            }
            CustomPanelAPI columnsPanel = panel.createCustomPanel(columnsWidth, 1f, null);
            TooltipMakerAPI leftColumn = columnsPanel.createUIElement(leftColumnWidth, 0f, false);
            TooltipMakerAPI rightColumn = columnsPanel.createUIElement(rightColumnWidth, 0f, false);

            leftColumn.addPara(formatUi("intel_ship_label", "舰船: {value}", "value", safeShipName),
                    8f, Misc.getHighlightColor(), safeShipName);
            TooltipMakerAPI head = leftColumn.beginImageWithText(type.portrait, 80f);
            head.addPara(formatUi("intel_detail_title_name", "名称: {value}", "value", safeDisplayName),
                    0f, Misc.getTextColor(), Misc.getHighlightColor(), safeDisplayName);
            head.addPara(uiText("intel_detail_title_level", "等级: %s/%s"), 2f,
                    new Color[]{BENEFIT_COLOR, Misc.getHighlightColor()},
                    String.valueOf(level), String.valueOf(type.levelCap));
            head.addPara(uiText("intel_detail_title_exp", "经验: %s/%s"), 2f,
                    new Color[]{BENEFIT_COLOR, Misc.getHighlightColor()},
                    String.valueOf((int) exp), String.valueOf((int) nextExp));
            head.addPara(formatUi("intel_detail_title_personality", "性格: {value}", "value", safePersonality),
                    2f, Misc.getTextColor(), Misc.getHighlightColor(), safePersonality);
            leftColumn.addImageWithText(8f);

            if (type.canRename) {
                TextFieldAPI textField = leftColumn.addTextField(240f, 4f);
                textField.setText(displayName);
                List<Object> renameParams = new ArrayList<>();
                renameParams.add("rename");
                renameParams.add(member.getId());
                renameParams.add(textField);
                leftColumn.addButton(uiText("intel_button_rename", "修改名称"), renameParams, 120, 22, 4f);
            }

            leftColumn.addPara("----------------------------------------", sectionPad, Misc.getGrayColor());
            leftColumn.addPara(uiText("intel_section_skills", "技能"), sectionHeaderPad, Misc.getHighlightColor(),
                    uiText("intel_section_skills", "技能"));
            if (data.learnedSkills.isEmpty()) {
                leftColumn.addPara(uiText("intel_none", "无"), sectionContentPad, Misc.getGrayColor());
            } else {
                for (String skillId : data.learnedSkills) {
                    String name = getSkillName(skillId);
                    boolean elite = data.eliteSkills.contains(skillId);
                    Color c = elite ? SKILL_ELITE_COLOR : SKILL_NORMAL_COLOR;
                    String safeName = escapeTooltipFormat(name);
                    leftColumn.addPara(safeName + (elite ? uiText("tooltip_elite_suffix", " [精英]") : ""),
                            sectionContentPad, c, safeName);
                }
            }

            leftColumn.addPara("----------------------------------------", sectionPad, Misc.getGrayColor());
            leftColumn.addPara(uiText("intel_section_aux", "辅助效果"), sectionHeaderPad, Misc.getHighlightColor(),
                    uiText("intel_section_aux", "辅助效果"));
            if (data.learnedAuxSkills.isEmpty()) {
                leftColumn.addPara(uiText("intel_none", "无"), sectionContentPad, Misc.getGrayColor());
            } else {
                for (String auxId : data.learnedAuxSkills) {
                    String auxName = getAuxEffectDisplayName(auxId);
                    float auxLevel = (data.eliteSkills != null && data.eliteSkills.contains(auxId)) ? 2f : 1f;
                    addAuxEffectLine(leftColumn, auxId, auxName, auxLevel, sectionContentPad);
                }
            }

            rightColumn.addPara(formatUi("intel_available_points", "可用点数: 技能 {normal} / 精英 {elite} / 辅助 {aux}",
                    "normal", String.valueOf(normalPick),
                    "elite", String.valueOf(elitePick),
                    "aux", String.valueOf(auxPick)),
                    8f, Misc.getTextColor(), Misc.getHighlightColor(),
                    String.valueOf(normalPick), String.valueOf(elitePick), String.valueOf(auxPick));

            boolean hasActionSection = false;
            if (DEBUG_MODE) {
                rightColumn.addPara(uiText("intel_debug_button_hint", "学习/精英化/辅助按钮仅在有剩余次数且有可选项时显示。"),
                        4f, Misc.getGrayColor());
            }

            if (DEBUG_MODE || (normalPick > 0 && !normalOptions.isEmpty())) {
                hasActionSection = true;
                rightColumn.addPara("----------------------------------------", sectionPad, Misc.getGrayColor());
                rightColumn.addPara(formatUi("intel_available_skill_heading", "可学习技能（剩余 {count} 次）",
                        "count", String.valueOf(normalPick)), sectionHeaderPad, Misc.getHighlightColor(), "" + normalPick);
                if (normalPick > 0 && !normalOptions.isEmpty()) {
                    addSkillButtons(rightColumn, "learn", member.getId(), normalOptions);
                } else if (DEBUG_MODE) {
                    rightColumn.addPara(uiText("intel_available_skill_empty", "当前暂无可学习技能。"),
                            sectionContentPad, Misc.getGrayColor());
                }
            }

            if (DEBUG_MODE || (elitePick > 0 && !eliteOptions.isEmpty())) {
                hasActionSection = true;
                rightColumn.addPara("----------------------------------------", sectionPad, Misc.getGrayColor());
                rightColumn.addPara(formatUi("intel_available_elite_heading", "可精英化技能（剩余 {count} 次）",
                        "count", String.valueOf(elitePick)), sectionHeaderPad, Misc.getHighlightColor(), "" + elitePick);
                if (elitePick > 0 && !eliteOptions.isEmpty()) {
                    addSkillButtons(rightColumn, "elite", member.getId(), eliteOptions);
                } else if (DEBUG_MODE) {
                    rightColumn.addPara(uiText("intel_available_elite_empty", "当前暂无可精英化技能。"),
                            sectionContentPad, Misc.getGrayColor());
                }
            }

            if (DEBUG_MODE || (auxPick > 0 && !auxOptions.isEmpty())) {
                hasActionSection = true;
                rightColumn.addPara("----------------------------------------", sectionPad, Misc.getGrayColor());
                rightColumn.addPara(formatUi("intel_available_aux_heading", "可学习辅助效果（剩余 {count} 次）",
                        "count", String.valueOf(auxPick)), sectionHeaderPad, Misc.getHighlightColor(), "" + auxPick);
                if (auxPick > 0 && !auxOptions.isEmpty()) {
                    addSkillButtons(rightColumn, "aux", member.getId(), auxOptions);
                } else if (DEBUG_MODE) {
                    rightColumn.addPara(uiText("intel_available_aux_empty", "当前暂无可学习辅助效果。"),
                            sectionContentPad, Misc.getGrayColor());
                }
            }

            if (DEBUG_MODE) {
                hasActionSection = true;
                rightColumn.addPara("----------------------------------------", sectionPad, Misc.getGrayColor());
                List<Object> debugParams = new ArrayList<>();
                debugParams.add("debug_add_exp");
                debugParams.add(member.getId());
                rightColumn.addButton(uiText("intel_debug_add_exp_button", "DEBUG: +1000经验"),
                        debugParams, 170, 24, 4f);
            }

            if (!evolutionOptions.isEmpty()) {
                hasActionSection = true;
                rightColumn.addPara("----------------------------------------", sectionPad, Misc.getGrayColor());
                rightColumn.addPara(uiText("intel_evolution_options", "可选进化路线"), 4f, Misc.getHighlightColor(),
                        uiText("intel_evolution_options", "可选进化路线"));
                for (String targetId : evolutionOptions) {
                    Moci_ShipboardAIConfigLoader.AITypeDefinition evolveType =
                            Moci_ShipboardAIConfigLoader.getAiType(targetId);
                    if (evolveType == null) {
                        continue;
                    }
                    List<Object> evolveParams = new ArrayList<>();
                    evolveParams.add("evolve");
                    evolveParams.add(member.getId());
                    evolveParams.add(targetId);
                    rightColumn.addButton(formatUi("intel_evolution_button", "进化为 {name}",
                            "name", escapeTooltipFormat(evolveType.name)), evolveParams, 220, 24, 4f);
                }
            } else if (type.hasEvolutionRoute) {
                hasActionSection = true;
                rightColumn.addPara("----------------------------------------", sectionPad, Misc.getGrayColor());
                rightColumn.addPara(formatUi("intel_evolution_requirement", "进化条件：达到等级 {level}",
                        "level", String.valueOf(type.evolutionLevel)), 4f, Misc.getGrayColor());
            }

            if (!hasActionSection) {
                rightColumn.addPara(uiText("intel_no_actions", "当前暂无可执行操作。"), sectionPad, Misc.getGrayColor());
            }

            rightColumn.addButton(uiText("intel_button_back", "返回"), "back_to_list", 120, 24, sectionPad);
            float columnsHeight = Math.max(420f,
                    Math.max(leftColumn.getHeightSoFar(), rightColumn.getHeightSoFar()) + 8f);
            columnsPanel.getPosition().setSize(columnsWidth, columnsHeight);
            leftColumn.setHeightSoFar(columnsHeight);
            rightColumn.setHeightSoFar(columnsHeight);
            columnsPanel.addUIElement(leftColumn).inTL(0f, 0f);
            columnsPanel.addUIElement(rightColumn).inTL(leftColumnWidth + columnGap, 0f);
            info.addCustom(columnsPanel, sectionPad);
            logAIDetailInfo("render_success", memberId, member, data, type,
                    "columnsWidth=" + columnsWidth
                            + " | columnsHeight=" + columnsHeight
                            + " | hasActionSection=" + hasActionSection);
        } catch (Exception ex) {
            logAIDetailError("render_exception", memberId, member, data, type, ex);
            addDetailRenderError(info, 8f);
        }
    }

    private void addSkillButtons(TooltipMakerAPI column, String action, String memberId, List<String> skillIds) {
        int count = 0;
        for (String skillId : skillIds) {
            if (count >= 18) {
                break;
            }
            List<Object> params = new ArrayList<>();
            params.add(action);
            params.add(memberId);
            params.add(skillId);
            String displayName = "aux".equals(action) ? getAuxEffectDisplayName(skillId) : getSkillName(skillId);
            column.addButton(escapeTooltipFormat(displayName), params, 220, 20, 2f);
            if ("aux".equals(action)) {
                String safeDesc = escapeTooltipFormat(Moci_ShipboardAIAuxEffectRegistry.getEffectDescription(skillId, 1f));
                column.addPara(safeDesc,
                        2f, Misc.getGrayColor());
            }
            count++;
        }
    }

    private void addAuxEffectLine(TooltipMakerAPI info, String auxId, String auxName, float level, float pad) {
        String visibleAuxName = auxName == null ? "" : auxName;
        String desc = Moci_ShipboardAIAuxEffectRegistry.getEffectDescription(auxId, level);
        info.addPara(uiText("intel_aux_effect_line", "%s %s：%s"), pad,
                new Color[]{BENEFIT_COLOR, Misc.getHighlightColor(), Misc.getTextColor()},
                "#", visibleAuxName, desc);
    }

    private void addDetailRenderError(TooltipMakerAPI info, float pad) {
        info.addPara(uiText("intel_render_error", "舰载AI详情页渲染失败，请查看日志。"),
                pad, Misc.getNegativeHighlightColor());
        info.addButton(uiText("intel_button_back", "返回"), "back_to_list", 120, 24, 10f);
    }

    private void logAIDetailInfo(String stage, String memberId, FleetMemberAPI member,
            Moci_ShipboardAIManager.ShipboardAIData data,
            Moci_ShipboardAIConfigLoader.AITypeDefinition type,
            String extra) {
        safeLogAIDetail("info", stage, memberId, member, data, type, null, extra);
    }

    private void logAIDetailWarn(String stage, String memberId, FleetMemberAPI member,
            Moci_ShipboardAIManager.ShipboardAIData data,
            String extra) {
        safeLogAIDetail("warn", stage, memberId, member, data, null, null, extra);
    }

    private void logAIDetailError(String stage, String memberId, FleetMemberAPI member,
            Moci_ShipboardAIManager.ShipboardAIData data,
            Moci_ShipboardAIConfigLoader.AITypeDefinition type,
            Throwable ex) {
        safeLogAIDetail("error", stage, memberId, member, data, type, ex, null);
    }

    private void safeLogAIDetail(String level, String memberId, FleetMemberAPI member,
            Moci_ShipboardAIManager.ShipboardAIData data,
            Moci_ShipboardAIConfigLoader.AITypeDefinition type,
            Throwable ex,
            String extra) {
        safeLogAIDetail(level, "<unspecified>", memberId, member, data, type, ex, extra);
    }

    private void safeLogAIDetail(String level, String stage, String memberId, FleetMemberAPI member,
            Moci_ShipboardAIManager.ShipboardAIData data,
            Moci_ShipboardAIConfigLoader.AITypeDefinition type,
            Throwable ex,
            String extra) {
        String context = safeBuildAIDetailContext(stage, memberId, member, data, type, extra);
        try {
            if ("error".equals(level)) {
                LOG.error(context, ex);
            } else if ("warn".equals(level)) {
                LOG.warn(context);
                if (ex != null) {
                    LOG.warn(context, ex);
                }
            } else {
                LOG.info(context);
                if (ex != null) {
                    LOG.info(context, ex);
                }
            }
        } catch (Exception logEx) {
            try {
                LOG.error(AI_MANAGER_LOG_PREFIX
                        + " logging_failure"
                        + " | level=" + safeLogValue(level)
                        + " | stage=" + safeLogValue(stage)
                        + " | requestedMemberId=" + safeLogValue(memberId),
                        logEx);
            } catch (Exception ignored) {
                // Never let logging failures break the intel page itself.
            }
        }
    }

    private String safeBuildAIDetailContext(String stage, String memberId, FleetMemberAPI member,
            Moci_ShipboardAIManager.ShipboardAIData data,
            Moci_ShipboardAIConfigLoader.AITypeDefinition type,
            String extra) {
        try {
            StringBuilder sb = new StringBuilder(AI_MANAGER_LOG_PREFIX)
                    .append(' ')
                    .append(stage)
                    .append(" | requestedMemberId=").append(safeLogValue(memberId))
                    .append(" | selectedMemberId=").append(safeLogValue(selectedMemberId));

            if (member != null) {
                sb.append(" | memberId=").append(safeLogValue(member.getId()))
                        .append(" | shipName=").append(safeLogValue(member.getShipName()))
                        .append(" | hasShipboardAISystem=")
                        .append(Moci_ShipboardAIConfigLoader.hasShipboardAISystem(member));
                if (member.getHullSpec() != null) {
                    sb.append(" | hullId=").append(safeLogValue(member.getHullSpec().getHullId()));
                }
            } else {
                sb.append(" | member=<null>");
            }

            if (data != null) {
                sb.append(" | data.memberId=").append(safeLogValue(data.memberId))
                        .append(" | data.baseAiTypeId=").append(safeLogValue(data.baseAiTypeId))
                        .append(" | data.currentAiTypeId=").append(safeLogValue(data.currentAiTypeId))
                        .append(" | canonicalCurrentAiTypeId=")
                        .append(safeLogValue(Moci_ShipboardAIConfigLoader.getCanonicalAiTypeId(data.currentAiTypeId)))
                        .append(" | customName=").append(safeLogValue(data.customName))
                        .append(" | experience=").append((int) data.experience)
                        .append(" | learnedSkills=").append(data.learnedSkills != null ? data.learnedSkills.size() : -1)
                        .append(" | eliteSkills=").append(data.eliteSkills != null ? data.eliteSkills.size() : -1)
                        .append(" | learnedAux=").append(data.learnedAuxSkills != null ? data.learnedAuxSkills.size() : -1)
                        .append(" | aiPersonId=")
                        .append(safeLogValue(data.aiPerson != null ? data.aiPerson.getId() : null));
            } else {
                sb.append(" | data=<null>");
            }

            if (type != null) {
                sb.append(" | type.aiId=").append(safeLogValue(type.aiId))
                        .append(" | type.name=").append(safeLogValue(type.name))
                        .append(" | type.portrait=").append(safeLogValue(type.portrait))
                        .append(" | type.levelCap=").append(type.levelCap)
                        .append(" | type.canRename=").append(type.canRename)
                        .append(" | type.personality=").append(safeLogValue(type.personality));
            } else {
                sb.append(" | type=<null>");
            }

            if (extra != null && !extra.trim().isEmpty()) {
                sb.append(" | ").append(extra.trim());
            }

            return sb.toString();
        } catch (Exception ex) {
            return new StringBuilder(AI_MANAGER_LOG_PREFIX)
                    .append(' ')
                    .append(safeLogValue(stage))
                    .append(" | context_build_failed=true")
                    .append(" | requestedMemberId=").append(safeLogValue(memberId))
                    .append(" | selectedMemberId=").append(safeLogValue(selectedMemberId))
                    .append(" | contextError=").append(safeLogValue(ex.toString()))
                    .toString();
        }
    }

    private String safeLogValue(String value) {
        return value == null ? "<null>" : value;
    }

    private FleetMemberAPI findMemberById(String memberId) {
        if (memberId == null || Global.getSector() == null || Global.getSector().getPlayerFleet() == null) {
            return null;
        }
        for (FleetMemberAPI member : Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy()) {
            if (memberId.equals(member.getId())) {
                return member;
            }
        }
        return null;
    }

    private String getSkillName(String skillId) {
        try {
            return Global.getSettings().getSkillSpec(skillId).getName();
        } catch (Exception ex) {
            return skillId;
        }
    }

    private String getAuxEffectDisplayName(String skillId) {
        return Moci_ShipboardAIAuxEffectRegistry.getDisplayName(skillId);
    }

    private String escapeTooltipFormat(String text) {
        return text == null ? "" : text.replace("%", "%%");
    }

    @Override
    public void buttonPressConfirmed(Object buttonId, IntelUIAPI ui) {
        if ("back_to_list".equals(buttonId)) {
            selectedMemberId = null;
            ui.updateUIForItem(this);
            return;
        }

        if (!(buttonId instanceof List)) {
            return;
        }
        List<?> params = (List<?>) buttonId;
        if (params.isEmpty() || !(params.get(0) instanceof String)) {
            return;
        }
        String action = (String) params.get(0);

        if ("open_detail".equals(action) && params.size() >= 2 && params.get(1) instanceof String) {
            String targetMemberId = (String) params.get(1);
            FleetMemberAPI member = null;
            Moci_ShipboardAIManager.ShipboardAIData data = null;
            Moci_ShipboardAIConfigLoader.AITypeDefinition type = null;
            logAIDetailInfo("open_click", targetMemberId, null, null, null,
                    "buttonAction=open_detail");
            try {
                member = findMemberById(targetMemberId);
                data = member != null ? Moci_ShipboardAIManager.getOrCreateData(member) : null;
                type = data != null ? Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId) : null;
                if (member == null) {
                    logAIDetailWarn("open_click_missing_ship", targetMemberId, null, null,
                            "buttonAction=open_detail");
                } else if (data == null) {
                    logAIDetailWarn("open_click_missing_data", targetMemberId, member, null,
                            "buttonAction=open_detail");
                } else if (type == null) {
                    logAIDetailWarn("open_click_missing_type", targetMemberId, member, data,
                            "buttonAction=open_detail");
                } else {
                    logAIDetailInfo("open_click_resolved", targetMemberId, member, data, type,
                            "buttonAction=open_detail");
                }
            } catch (Exception ex) {
                logAIDetailError("open_click_exception", targetMemberId, member, data, type, ex);
            }
            if (member == null || data == null || type == null) {
                selectedMemberId = null;
                Global.getSector().getCampaignUI().addMessage(
                        uiText("intel_msg_open_detail_fail", "Cannot open shipboard AI detail."), NEGATIVE_COLOR);
                ui.updateUIForItem(this);
                return;
            }
            selectedMemberId = targetMemberId;
            ui.updateUIForItem(this);
            return;
        }

        if (DEBUG_MODE && "debug_add_exp".equals(action) && params.size() >= 2 && params.get(1) instanceof String) {
            FleetMemberAPI member = findMemberById((String) params.get(1));
            Moci_ShipboardAIManager.ExperienceGainResult result = Moci_ShipboardAIManager.addExperience(member, 1000f);
            if (result.applied) {
                Global.getSector().getCampaignUI().addMessage(
                        formatUi("intel_msg_debug_exp_gain", "DEBUG: 经验 +1000 (Lv {before} -> {after})",
                                "before", String.valueOf(result.levelBefore),
                                "after", String.valueOf(result.levelAfter)),
                        BENEFIT_COLOR);
            } else {
                Global.getSector().getCampaignUI().addMessage(
                        uiText("intel_msg_debug_exp_fail", "DEBUG: 增加经验失败"), NEGATIVE_COLOR);
            }
            ui.updateUIForItem(this);
            return;
        }

        if (("learn".equals(action) || "elite".equals(action) || "aux".equals(action))
                && params.size() >= 3
                && params.get(1) instanceof String
                && params.get(2) instanceof String) {
            FleetMemberAPI member = findMemberById((String) params.get(1));
            String skillId = (String) params.get(2);
            boolean success = false;
            if ("learn".equals(action)) {
                success = Moci_ShipboardAIManager.learnSkill(member, skillId);
            } else if ("elite".equals(action)) {
                success = Moci_ShipboardAIManager.eliteSkill(member, skillId);
            } else if ("aux".equals(action)) {
                success = Moci_ShipboardAIManager.learnAuxEffect(member, skillId);
            }
            if (success) {
                String displayName = "aux".equals(action) ? getAuxEffectDisplayName(skillId) : getSkillName(skillId);
                Global.getSector().getCampaignUI().addMessage(
                        formatUi("intel_msg_action_success", "舰载AI操作成功: {value}", "value", displayName),
                        Misc.getPositiveHighlightColor());
            } else {
                Global.getSector().getCampaignUI().addMessage(
                        uiText("intel_msg_action_fail", "舰载AI操作失败"), Misc.getNegativeHighlightColor());
            }
            ui.updateUIForItem(this);
            return;
        }

        if ("rename".equals(action) && params.size() >= 3
                && params.get(1) instanceof String
                && params.get(2) instanceof TextFieldAPI) {
            FleetMemberAPI member = findMemberById((String) params.get(1));
            TextFieldAPI textField = (TextFieldAPI) params.get(2);
            boolean success = Moci_ShipboardAIManager.renameAI(member, textField.getText());
            if (success) {
                Global.getSector().getCampaignUI().addMessage(
                        uiText("intel_msg_rename_success", "舰载AI名称已更新"), Misc.getPositiveHighlightColor());
            } else {
                Global.getSector().getCampaignUI().addMessage(
                        uiText("intel_msg_rename_fail", "舰载AI名称更新失败"), Misc.getNegativeHighlightColor());
            }
            ui.updateUIForItem(this);
            return;
        }

        if ("evolve".equals(action) && params.size() >= 2 && params.get(1) instanceof String) {
            FleetMemberAPI member = findMemberById((String) params.get(1));
            String targetAiId = params.size() >= 3 && params.get(2) instanceof String ? (String) params.get(2) : null;
            boolean success = targetAiId == null
                    ? Moci_ShipboardAIManager.evolveAI(member)
                    : Moci_ShipboardAIManager.evolveAI(member, targetAiId);
            if (success) {
                Global.getSector().getCampaignUI().addMessage(
                        uiText("intel_msg_evolve_success", "舰载AI进化完成"), Misc.getHighlightColor());
            } else {
                Global.getSector().getCampaignUI().addMessage(
                        uiText("intel_msg_evolve_fail", "舰载AI进化失败"), Misc.getNegativeHighlightColor());
            }
            ui.updateUIForItem(this);
        }
    }

    @Override
    public String getIcon() {
        return "graphics/portraits/ai_pilot/Moci_system99.png";
    }

    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add(uiText("intel_tag_management", "中队管理"));
        return tags;
    }

    @Override
    public boolean isHidden() {
        return true;
    }

    private String uiText(String key, String fallback) {
        return Moci_ShipboardAITextRegistry.getUiText(key, fallback);
    }

    private String formatUi(String key, String fallback, String... replacements) {
        return Moci_ShipboardAITextRegistry.formatTemplate(uiText(key, fallback),
                Moci_ShipboardAITextRegistry.mapOf(replacements));
    }
}
