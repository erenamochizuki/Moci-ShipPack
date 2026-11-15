package data.campaign.intel.ui;

import java.util.List;
import java.util.Set;
import java.awt.Color;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.PersonImportance;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.MutableStat.StatMod;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventIntel.EventStageDisplayData;
import com.fs.starfarer.api.impl.codex.CodexEntryPlugin;
import com.fs.starfarer.api.ui.ButtonAPI.UICheckboxSize;
import com.fs.starfarer.api.ui.*;
import com.fs.starfarer.api.input.InputEventAPI;
import java.util.ArrayList;

/**
 * 普通接口委托：收集技能脚本生成的标签，在脚本完成后统一着色。
 * 不使用动态代理或反射，兼容游戏的脚本类加载限制。
 * 方法签名与本工程绑定的 0.98 TooltipMakerAPI 保持一致；其他控件原样转交。
 */
public final class Moci_UIColoredTooltip implements TooltipMakerAPI {
    private final TooltipMakerAPI delegate;
    private final Color color;
    private final List<LabelAPI> labels;

    public Moci_UIColoredTooltip(TooltipMakerAPI delegate, Color color) {
        this(delegate, color, new ArrayList<LabelAPI>());
    }

    private Moci_UIColoredTooltip(TooltipMakerAPI delegate, Color color, List<LabelAPI> labels) {
        this.delegate = delegate;
        this.color = color;
        this.labels = labels;
    }

    /** 最后执行，以覆盖自定义技能脚本后续对标签高亮颜色的修改。 */
    public void applyColor() {
        for (LabelAPI label : labels) {
            label.setColor(color);
            label.setHighlight();
        }
    }

    private LabelAPI record(LabelAPI label) {
        if (label != null) labels.add(label);
        return label;
    }

    private TooltipMakerAPI wrap(TooltipMakerAPI tooltip) {
        return tooltip == null ? null : new Moci_UIColoredTooltip(tooltip, color, labels);
    }

    @Override
    public LabelAPI addTitle(String arg0) { return record(delegate.addTitle(arg0)); }

    @Override
    public void setTitleFont(String arg0) { delegate.setTitleFont(arg0); }

    @Override
    public void setTitleSmallOrbitron() { delegate.setTitleSmallOrbitron(); }

    @Override
    public void setTitleFontColor(Color arg0) { delegate.setTitleFontColor(arg0); }

    @Override
    public void setParaFont(String arg0) { delegate.setParaFont(arg0); }

    @Override
    public void setParaFontColor(Color arg0) { delegate.setParaFontColor(arg0); }

    @Override
    public void setParaSmallInsignia() { delegate.setParaSmallInsignia(); }

    @Override
    public LabelAPI addPara(String arg0, float arg1, Color arg2, String... arg3) { return record(delegate.addPara(arg0, arg1, arg2, arg3)); }

    @Override
    public LabelAPI addPara(String arg0, float arg1) { return record(delegate.addPara(arg0, arg1)); }

    @Override
    public LabelAPI addPara(String arg0, Color arg1, float arg2) { return record(delegate.addPara(arg0, arg1, arg2)); }

    @Override
    public LabelAPI addSectionHeading(String arg0, Alignment arg1, float arg2) { return record(delegate.addSectionHeading(arg0, arg1, arg2)); }

    @Override
    public LabelAPI addSectionHeading(String arg0, Color arg1, Color arg2, Alignment arg3, float arg4) { return record(delegate.addSectionHeading(arg0, arg1, arg2, arg3, arg4)); }

    @Override
    public void beginGrid(float arg0, int arg1) { delegate.beginGrid(arg0, arg1); }

    @Override
    public void beginGrid(float arg0, int arg1, Color arg2) { delegate.beginGrid(arg0, arg1, arg2); }

    @Override
    public Object addToGrid(int arg0, int arg1, String arg2, String arg3) { return delegate.addToGrid(arg0, arg1, arg2, arg3); }

    @Override
    public Object addToGrid(int arg0, int arg1, String arg2, String arg3, Color arg4) { return delegate.addToGrid(arg0, arg1, arg2, arg3, arg4); }

    @Override
    public void setGridLabelColor(Color arg0) { delegate.setGridLabelColor(arg0); }

    @Override
    public void addGrid(float arg0) { delegate.addGrid(arg0); }

    @Override
    public void beginGridFlipped(float arg0, int arg1, float arg2, float arg3) { delegate.beginGridFlipped(arg0, arg1, arg2, arg3); }

    @Override
    public void beginGridFlipped(float arg0, int arg1, Color arg2, float arg3, float arg4) { delegate.beginGridFlipped(arg0, arg1, arg2, arg3, arg4); }

    @Override
    public void addStatModGrid(float arg0, float arg1, float arg2, float arg3, MutableStat arg4) { delegate.addStatModGrid(arg0, arg1, arg2, arg3, arg4); }

    @Override
    public void addStatModGrid(float arg0, float arg1, float arg2, float arg3, StatBonus arg4) { delegate.addStatModGrid(arg0, arg1, arg2, arg3, arg4); }

    @Override
    public void addStatGridForShips(float arg0, float arg1, float arg2, float arg3, CampaignFleetAPI arg4, int arg5, boolean arg6, TooltipMakerAPI.FleetMemberValueGetter arg7) { delegate.addStatGridForShips(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7); }

    @Override
    public void setGridFontDefault() { delegate.setGridFontDefault(); }

    @Override
    public void setGridFont(String arg0) { delegate.setGridFont(arg0); }

    @Override
    public void addStatModGrid(float arg0, float arg1, float arg2, float arg3, MutableStat arg4, TooltipMakerAPI.StatModValueGetter arg5) { delegate.addStatModGrid(arg0, arg1, arg2, arg3, arg4, arg5); }

    @Override
    public void addStatModGrid(float arg0, float arg1, float arg2, float arg3, StatBonus arg4, TooltipMakerAPI.StatModValueGetter arg5) { delegate.addStatModGrid(arg0, arg1, arg2, arg3, arg4, arg5); }

    @Override
    public void setParaSmallOrbitron() { delegate.setParaSmallOrbitron(); }

    @Override
    public LabelAPI addTitle(String arg0, Color arg1) { return record(delegate.addTitle(arg0, arg1)); }

    @Override
    public void setParaFontVictor14() { delegate.setParaFontVictor14(); }

    @Override
    public void addImage(String arg0, float arg1) { delegate.addImage(arg0, arg1); }

    @Override
    public void addImage(String arg0, float arg1, float arg2) { delegate.addImage(arg0, arg1, arg2); }

    @Override
    public void setParaFontDefault() { delegate.setParaFontDefault(); }

    @Override
    public void setParaOrbitronLarge() { delegate.setParaOrbitronLarge(); }

    @Override
    public void setParaOrbitronVeryLarge() { delegate.setParaOrbitronVeryLarge(); }

    @Override
    public void setParaInsigniaLarge() { delegate.setParaInsigniaLarge(); }

    @Override
    public void setParaInsigniaVeryLarge() { delegate.setParaInsigniaVeryLarge(); }

    @Override
    public void setTitleOrbitronLarge() { delegate.setTitleOrbitronLarge(); }

    @Override
    public void setTitleOrbitronVeryLarge() { delegate.setTitleOrbitronVeryLarge(); }

    @Override
    public void beginIconGroup() { delegate.beginIconGroup(); }

    @Override
    public void setIconSpacingMedium() { delegate.setIconSpacingMedium(); }

    @Override
    public void setIconSpacingWide() { delegate.setIconSpacingWide(); }

    @Override
    public void addIcons(CommodityOnMarketAPI arg0, int arg1, IconRenderMode arg2) { delegate.addIcons(arg0, arg1, arg2); }

    @Override
    public void addIcons(CommoditySpecAPI arg0, int arg1, IconRenderMode arg2) { delegate.addIcons(arg0, arg1, arg2); }

    @Override
    public void addIconGroup(float arg0) { delegate.addIconGroup(arg0); }

    @Override
    public void addIconGroup(float arg0, float arg1) { delegate.addIconGroup(arg0, arg1); }

    @Override
    public void cancelGrid() { delegate.cancelGrid(); }

    @Override
    public void addIconGroupAndCenter(float arg0) { delegate.addIconGroupAndCenter(arg0); }

    @Override
    public void addStatModGrid(float arg0, float arg1, float arg2, float arg3, StatBonus arg4, boolean arg5, TooltipMakerAPI.StatModValueGetter arg6) { delegate.addStatModGrid(arg0, arg1, arg2, arg3, arg4, arg5, arg6); }

    @Override
    public void showCargo(CargoAPI arg0, int arg1, boolean arg2, float arg3) { delegate.showCargo(arg0, arg1, arg2, arg3); }

    @Override
    public void showShips(List<FleetMemberAPI> arg0, int arg1, boolean arg2, float arg3) { delegate.showShips(arg0, arg1, arg2, arg3); }

    @Override
    public UIPanelAPI beginTable(FactionAPI arg0, float arg1, Object... arg2) { return delegate.beginTable(arg0, arg1, arg2); }

    @Override
    public UIPanelAPI beginTable2(FactionAPI arg0, float arg1, boolean arg2, boolean arg3, Object... arg4) { return delegate.beginTable2(arg0, arg1, arg2, arg3, arg4); }

    @Override
    public UIPanelAPI beginTable(Color arg0, Color arg1, Color arg2, float arg3, Object... arg4) { return delegate.beginTable(arg0, arg1, arg2, arg3, arg4); }

    @Override
    public UIPanelAPI beginTable(Color arg0, Color arg1, Color arg2, float arg3, boolean arg4, boolean arg5, Object... arg6) { return delegate.beginTable(arg0, arg1, arg2, arg3, arg4, arg5, arg6); }

    @Override
    public Object addRow(Object... arg0) { return delegate.addRow(arg0); }

    @Override
    public Object addRowWithGlow(Object... arg0) { return delegate.addRowWithGlow(arg0); }

    @Override
    public void addTooltipToAddedRow(TooltipMakerAPI.TooltipCreator arg0, TooltipMakerAPI.TooltipLocation arg1) { delegate.addTooltipToAddedRow(arg0, arg1); }

    @Override
    public void addTooltipToAddedRow(TooltipMakerAPI.TooltipCreator arg0, TooltipMakerAPI.TooltipLocation arg1, boolean arg2) { delegate.addTooltipToAddedRow(arg0, arg1, arg2); }

    @Override
    public void addTable(String arg0, int arg1, float arg2) { delegate.addTable(arg0, arg1, arg2); }

    @Override
    public void setGridValueColor(Color arg0) { delegate.setGridValueColor(arg0); }

    @Override
    public TooltipMakerAPI beginImageWithText(String arg0, float arg1) { return wrap(delegate.beginImageWithText(arg0, arg1)); }

    @Override
    public UIPanelAPI addImageWithText(float arg0) { return delegate.addImageWithText(arg0); }

    @Override
    public void addIconGroup(float arg0, int arg1, float arg2) { delegate.addIconGroup(arg0, arg1, arg2); }

    @Override
    public LabelAPI addPara(String arg0, float arg1, Color arg2, Color arg3, String... arg4) { return record(delegate.addPara(arg0, arg1, arg2, arg3, arg4)); }

    @Override
    public void setButtonFontDefault() { delegate.setButtonFontDefault(); }

    @Override
    public void setButtonFontVictor10() { delegate.setButtonFontVictor10(); }

    @Override
    public void setButtonFontVictor14() { delegate.setButtonFontVictor14(); }

    @Override
    public ButtonAPI addButton(String arg0, Object arg1, float arg2, float arg3, float arg4) { return delegate.addButton(arg0, arg1, arg2, arg3, arg4); }

    @Override
    public ButtonAPI addButton(String arg0, Object arg1, Color arg2, Color arg3, float arg4, float arg5, float arg6) { return delegate.addButton(arg0, arg1, arg2, arg3, arg4, arg5, arg6); }

    @Override
    public ButtonAPI addButton(String arg0, Object arg1, Color arg2, Color arg3, Alignment arg4, CutStyle arg5, float arg6, float arg7, float arg8) { return delegate.addButton(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8); }

    @Override
    public void setBulletedListMode(String arg0) { delegate.setBulletedListMode(arg0); }

    @Override
    public void setBulletWidth(Float arg0) { delegate.setBulletWidth(arg0); }

    @Override
    public UIComponentAPI addCustom(UIComponentAPI arg0, float arg1) { return delegate.addCustom(arg0, arg1); }

    @Override
    public String getBulletedListPrefix() { return delegate.getBulletedListPrefix(); }

    @Override
    public void addImage(String arg0, float arg1, float arg2, float arg3) { delegate.addImage(arg0, arg1, arg2, arg3); }

    @Override
    public float getTextWidthOverride() { return delegate.getTextWidthOverride(); }

    @Override
    public void setTextWidthOverride(float arg0) { delegate.setTextWidthOverride(arg0); }

    @Override
    public void addImages(float arg0, float arg1, float arg2, float arg3, String... arg4) { delegate.addImages(arg0, arg1, arg2, arg3, arg4); }

    @Override
    public void resetGridRowHeight() { delegate.resetGridRowHeight(); }

    @Override
    public void setLowGridRowHeight() { delegate.setLowGridRowHeight(); }

    @Override
    public void setGridRowHeight(float arg0) { delegate.setGridRowHeight(arg0); }

    @Override
    public String shortenString(String arg0, float arg1) { return delegate.shortenString(arg0, arg1); }

    @Override
    public void addStatModGrid(float arg0, float arg1, float arg2, float arg3, MutableStat arg4, boolean arg5, TooltipMakerAPI.StatModValueGetter arg6) { delegate.addStatModGrid(arg0, arg1, arg2, arg3, arg4, arg5, arg6); }

    @Override
    public void addShipList(int arg0, int arg1, float arg2, Color arg3, List<FleetMemberAPI> arg4, float arg5) { delegate.addShipList(arg0, arg1, arg2, arg3, arg4, arg5); }

    @Override
    public void setParaFontOrbitron() { delegate.setParaFontOrbitron(); }

    @Override
    public LabelAPI addPara(String arg0, float arg1, Color[] arg2, String... arg3) { return record(delegate.addPara(arg0, arg1, arg2, arg3)); }

    @Override
    public UIComponentAPI addSpacer(float arg0) { return delegate.addSpacer(arg0); }

    @Override
    public void addStoryPointUseInfo(float arg0, float arg1, boolean arg2) { delegate.addStoryPointUseInfo(arg0, arg1, arg2); }

    @Override
    public void addStoryPointUseInfo(float arg0, int arg1, float arg2, boolean arg3) { delegate.addStoryPointUseInfo(arg0, arg1, arg2, arg3); }

    @Override
    public void setForceProcessInput(boolean arg0) { delegate.setForceProcessInput(arg0); }

    @Override
    public void addPlaythroughDataPanel(float arg0, float arg1) { delegate.addPlaythroughDataPanel(arg0, arg1); }

    @Override
    public void setBulletColor(Color arg0) { delegate.setBulletColor(arg0); }

    @Override
    public void addRelationshipBar(PersonAPI arg0, float arg1) { delegate.addRelationshipBar(arg0, arg1); }

    @Override
    public void addRelationshipBar(PersonAPI arg0, float arg1, float arg2) { delegate.addRelationshipBar(arg0, arg1, arg2); }

    @Override
    public void addRelationshipBar(FactionAPI arg0, float arg1) { delegate.addRelationshipBar(arg0, arg1); }

    @Override
    public void addRelationshipBar(FactionAPI arg0, float arg1, float arg2) { delegate.addRelationshipBar(arg0, arg1, arg2); }

    @Override
    public void addRelationshipBar(float arg0, float arg1) { delegate.addRelationshipBar(arg0, arg1); }

    @Override
    public void addRelationshipBar(float arg0, float arg1, float arg2) { delegate.addRelationshipBar(arg0, arg1, arg2); }

    @Override
    public void addImportanceIndicator(PersonImportance arg0, float arg1, float arg2) { delegate.addImportanceIndicator(arg0, arg1, arg2); }

    @Override
    public void addTooltipToPrevious(TooltipMakerAPI.TooltipCreator arg0, TooltipMakerAPI.TooltipLocation arg1) { delegate.addTooltipToPrevious(arg0, arg1); }

    @Override
    public ButtonAPI addAreaCheckbox(String arg0, Object arg1, Color arg2, Color arg3, Color arg4, float arg5, float arg6, float arg7) { return delegate.addAreaCheckbox(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7); }

    @Override
    public void showShips(List<FleetMemberAPI> arg0, int arg1, boolean arg2, boolean arg3, float arg4) { delegate.showShips(arg0, arg1, arg2, arg3, arg4); }

    @Override
    public void setGridFontSmallInsignia() { delegate.setGridFontSmallInsignia(); }

    @Override
    public void showFullSurveyReqs(PlanetAPI arg0, boolean arg1, float arg2) { delegate.showFullSurveyReqs(arg0, arg1, arg2); }

    @Override
    public void showCost(String arg0, boolean arg1, float arg2, Color arg3, Color arg4, float arg5, String[] arg6, int[] arg7, boolean[] arg8) { delegate.showCost(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8); }

    @Override
    public void showCost(String arg0, boolean arg1, Color arg2, Color arg3, float arg4, String[] arg5, int[] arg6) { delegate.showCost(arg0, arg1, arg2, arg3, arg4, arg5, arg6); }

    @Override
    public void showCost(Color arg0, Color arg1, float arg2, String[] arg3, int[] arg4) { delegate.showCost(arg0, arg1, arg2, arg3, arg4); }

    @Override
    public void showCost(String arg0, boolean arg1, float arg2, float arg3, Color arg4, Color arg5, float arg6, String[] arg7, int[] arg8, boolean[] arg9) { delegate.showCost(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9); }

    @Override
    public UIComponentAPI getPrev() { return delegate.getPrev(); }

    @Override
    public ButtonAPI addAreaCheckbox(String arg0, Object arg1, Color arg2, Color arg3, Color arg4, float arg5, float arg6, float arg7, boolean arg8) { return delegate.addAreaCheckbox(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8); }

    @Override
    public UIComponentAPI addSkillPanel(PersonAPI arg0, float arg1) { return delegate.addSkillPanel(arg0, arg1); }

    @Override
    public UIComponentAPI addSkillPanelOneColumn(PersonAPI arg0, float arg1) { return delegate.addSkillPanelOneColumn(arg0, arg1); }

    @Override
    public float computeStringWidth(String arg0) { return delegate.computeStringWidth(arg0); }

    @Override
    public TextFieldAPI addTextField(float arg0, float arg1) { return delegate.addTextField(arg0, arg1); }

    @Override
    public TextFieldAPI addTextField(float arg0, String arg1, float arg2) { return delegate.addTextField(arg0, arg1, arg2); }

    @Override
    public TextFieldAPI addTextField(float arg0, float arg1, String arg2, float arg3) { return delegate.addTextField(arg0, arg1, arg2, arg3); }

    @Override
    public ButtonAPI addCheckbox(float arg0, float arg1, String arg2, ButtonAPI.UICheckboxSize arg3, float arg4) { return delegate.addCheckbox(arg0, arg1, arg2, arg3, arg4); }

    @Override
    public ButtonAPI addCheckbox(float arg0, float arg1, String arg2, String arg3, Color arg4, ButtonAPI.UICheckboxSize arg5, float arg6) { return delegate.addCheckbox(arg0, arg1, arg2, arg3, arg4, arg5, arg6); }

    @Override
    public void setAreaCheckboxFont(String arg0) { delegate.setAreaCheckboxFont(arg0); }

    @Override
    public void setAreaCheckboxFontDefault() { delegate.setAreaCheckboxFontDefault(); }

    @Override
    public UIComponentAPI addLabelledValue(String arg0, String arg1, Color arg2, Color arg3, float arg4, float arg5) { return delegate.addLabelledValue(arg0, arg1, arg2, arg3, arg4, arg5); }

    @Override
    public float getHeightSoFar() { return delegate.getHeightSoFar(); }

    @Override
    public IntelUIAPI getIntelUI() { return delegate.getIntelUI(); }

    @Override
    public EventProgressBarAPI addEventProgressBar(BaseEventIntel arg0, float arg1) { return delegate.addEventProgressBar(arg0, arg1); }

    @Override
    public UIComponentAPI addCustomDoNotSetPosition(UIComponentAPI arg0) { return delegate.addCustomDoNotSetPosition(arg0); }

    @Override
    public UIComponentAPI addEventStageMarker(BaseEventIntel.EventStageDisplayData arg0) { return delegate.addEventStageMarker(arg0); }

    @Override
    public UIComponentAPI addEventProgressMarker(BaseEventIntel arg0) { return delegate.addEventProgressMarker(arg0); }

    @Override
    public TooltipMakerAPI beginImageWithText(String arg0, float arg1, float arg2, boolean arg3) { return wrap(delegate.beginImageWithText(arg0, arg1, arg2, arg3)); }

    @Override
    public LabelAPI addSectionHeading(String arg0, Color arg1, Color arg2, Alignment arg3, float arg4, float arg5) { return record(delegate.addSectionHeading(arg0, arg1, arg2, arg3, arg4, arg5)); }

    @Override
    public TooltipMakerAPI beginSubTooltip(float arg0) { return wrap(delegate.beginSubTooltip(arg0)); }

    @Override
    public void endSubTooltip() { delegate.endSubTooltip(); }

    @Override
    public void setHeightSoFar(float arg0) { delegate.setHeightSoFar(arg0); }

    @Override
    public UIPanelAPI createSectorMap(float arg0, float arg1, MapParams arg2, String arg3) { return delegate.createSectorMap(arg0, arg1, arg2, arg3); }

    @Override
    public UIPanelAPI createSectorMap(float arg0, float arg1, MapParams arg2, String arg3, Color arg4, Color arg5) { return delegate.createSectorMap(arg0, arg1, arg2, arg3, arg4, arg5); }

    @Override
    public float getWidthSoFar() { return delegate.getWidthSoFar(); }

    @Override
    public void addTooltipToPrevious(TooltipMakerAPI.TooltipCreator arg0, TooltipMakerAPI.TooltipLocation arg1, boolean arg2) { delegate.addTooltipToPrevious(arg0, arg1, arg2); }

    @Override
    public LabelAPI createLabel(String arg0, Color arg1) { return record(delegate.createLabel(arg0, arg1)); }

    @Override
    public LabelAPI createLabel(String arg0, Color arg1, float arg2) { return record(delegate.createLabel(arg0, arg1, arg2)); }

    @Override
    public void addTableHeaderTooltip(int arg0, TooltipMakerAPI.TooltipCreator arg1) { delegate.addTableHeaderTooltip(arg0, arg1); }

    @Override
    public void addTableHeaderTooltip(int arg0, String arg1) { delegate.addTableHeaderTooltip(arg0, arg1); }

    @Override
    public UIPanelAPI addSectorMap(float arg0, float arg1, StarSystemAPI arg2, float arg3) { return delegate.addSectorMap(arg0, arg1, arg2, arg3); }

    @Override
    public void addTooltipTo(TooltipMakerAPI.TooltipCreator arg0, UIComponentAPI arg1, TooltipMakerAPI.TooltipLocation arg2) { delegate.addTooltipTo(arg0, arg1, arg2); }

    @Override
    public void addTooltipTo(TooltipMakerAPI.TooltipCreator arg0, UIComponentAPI arg1, TooltipMakerAPI.TooltipLocation arg2, boolean arg3) { delegate.addTooltipTo(arg0, arg1, arg2, arg3); }

    @Override
    public UIComponentAPI createRect(Color arg0, float arg1) { return delegate.createRect(arg0, arg1); }

    @Override
    public void makeTableItemsClickable() { delegate.makeTableItemsClickable(); }

    @Override
    public void setIdForAddedRow(Object arg0) { delegate.setIdForAddedRow(arg0); }

    @Override
    public void setExternalScroller(ScrollPanelAPI arg0) { delegate.setExternalScroller(arg0); }

    @Override
    public ScrollPanelAPI getExternalScroller() { return delegate.getExternalScroller(); }

    @Override
    public void setBgAlpha(float arg0) { delegate.setBgAlpha(arg0); }

    @Override
    public void setButtonFontOrbitron20() { delegate.setButtonFontOrbitron20(); }

    @Override
    public void setButtonFontOrbitron20Bold() { delegate.setButtonFontOrbitron20Bold(); }

    @Override
    public void setButtonFontOrbitron24() { delegate.setButtonFontOrbitron24(); }

    @Override
    public void setButtonFontOrbitron24Bold() { delegate.setButtonFontOrbitron24Bold(); }

    @Override
    public void showPlanetInfo(PlanetAPI arg0, float arg1) { delegate.showPlanetInfo(arg0, arg1); }

    @Override
    public void showPlanetInfo(PlanetAPI arg0, float arg1, float arg2, boolean arg3, float arg4) { delegate.showPlanetInfo(arg0, arg1, arg2, arg3, arg4); }

    @Override
    public void showPlanetInfo(PlanetAPI arg0, float arg1, float arg2, TooltipMakerAPI.PlanetInfoParams arg3, float arg4) { delegate.showPlanetInfo(arg0, arg1, arg2, arg3, arg4); }

    @Override
    public ButtonAPI addCheckbox(float arg0, float arg1, String arg2, Object arg3, ButtonAPI.UICheckboxSize arg4, float arg5) { return delegate.addCheckbox(arg0, arg1, arg2, arg3, arg4, arg5); }

    @Override
    public ButtonAPI addCheckbox(float arg0, float arg1, String arg2, Object arg3, String arg4, Color arg5, ButtonAPI.UICheckboxSize arg6, float arg7) { return delegate.addCheckbox(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7); }

    @Override
    public UIComponentAPI addSkillPanel(PersonAPI arg0, boolean arg1, float arg2) { return delegate.addSkillPanel(arg0, arg1, arg2); }

    @Override
    public UIComponentAPI addSkillPanelOneColumn(PersonAPI arg0, boolean arg1, float arg2) { return delegate.addSkillPanelOneColumn(arg0, arg1, arg2); }

    @Override
    public void addCodexEntries(String arg0, Set<String> arg1, boolean arg2, float arg3) { delegate.addCodexEntries(arg0, arg1, arg2, arg3); }

    @Override
    public void setCodexEntryId(String arg0) { delegate.setCodexEntryId(arg0); }

    @Override
    public String getCodexEntryId() { return delegate.getCodexEntryId(); }

    @Override
    public void setCodexEntryFleetMember(FleetMemberAPI arg0) { delegate.setCodexEntryFleetMember(arg0); }

    @Override
    public void setCodexTempEntry(CodexEntryPlugin arg0) { delegate.setCodexTempEntry(arg0); }

    @Override
    public LabelAPI addParaWithMarkup(String arg0, float arg1) { return record(delegate.addParaWithMarkup(arg0, arg1)); }

    @Override
    public LabelAPI addParaWithMarkup(String arg0, Color arg1, float arg2) { return record(delegate.addParaWithMarkup(arg0, arg1, arg2)); }

    @Override
    public LabelAPI addParaWithMarkup(String arg0, float arg1, String... arg2) { return record(delegate.addParaWithMarkup(arg0, arg1, arg2)); }

    @Override
    public LabelAPI addParaWithMarkup(String arg0, Color arg1, float arg2, String... arg3) { return record(delegate.addParaWithMarkup(arg0, arg1, arg2, arg3)); }

    @Override
    public void showCargo(CargoAPI arg0, int arg1, boolean arg2, float arg3, float arg4, float arg5) { delegate.showCargo(arg0, arg1, arg2, arg3, arg4, arg5); }

    @Override
    public void setActionListenerDelegate(TooltipMakerAPI.ActionListenerDelegate arg0) { delegate.setActionListenerDelegate(arg0); }

    @Override
    public PositionAPI addComponent(UIComponentAPI arg0) { return delegate.addComponent(arg0); }

    @Override
    public void removeComponent(UIComponentAPI arg0) { delegate.removeComponent(arg0); }

    @Override
    public void bringComponentToTop(UIComponentAPI arg0) { delegate.bringComponentToTop(arg0); }

    @Override
    public void sendToBottom(UIComponentAPI arg0) { delegate.sendToBottom(arg0); }

    @Override
    public PositionAPI getPosition() { return delegate.getPosition(); }

    @Override
    public void render(float arg0) { delegate.render(arg0); }

    @Override
    public void processInput(List<InputEventAPI> arg0) { delegate.processInput(arg0); }

    @Override
    public void advance(float arg0) { delegate.advance(arg0); }

    @Override
    public void setOpacity(float arg0) { delegate.setOpacity(arg0); }

    @Override
    public float getOpacity() { return delegate.getOpacity(); }
}
