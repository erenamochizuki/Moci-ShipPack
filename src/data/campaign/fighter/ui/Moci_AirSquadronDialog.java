package data.campaign.fighter.ui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.lwjgl.input.Keyboard;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomVisualDialogDelegate;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.IntelUIAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.util.Misc;

import data.campaign.fighter.Moci_FighterLoadoutService;
import data.campaign.fighter.Moci_FighterLoadoutService.BayEntry;
import data.campaign.fighter.Moci_FighterLoadoutService.RefitPlan;
import data.campaign.fighter.Moci_FighterLoadoutService.WeaponAvailability;
import data.campaign.fighter.Moci_FighterRefitBudget.Budget;
import data.campaign.intel.Moci_CombatSquadManagerIntel;
import data.hullmods.Moci_TacticalHullmods;

/** A carrier-scoped workbench. UI drafts never mutate the carrier or its shared wing spec. */
public final class Moci_AirSquadronDialog {
    private static final Color ACCENT = new Color(112, 228, 207);
    private static final Color DARK = new Color(7, 22, 28);
    private static final Color DIM = new Color(148, 171, 180);
    private static final Color WARNING = new Color(242, 190, 104);
    private static final Color BAD = new Color(241, 127, 119);
    private static final Color STORAGE = new Color(46, 104, 177, 150);
    private static final Color MARKET = new Color(42, 150, 99, 150);
    private static final Color BLACK_MARKET = new Color(173, 54, 54, 165);
    private static final Color DISCARD_BACKGROUND = new Color(92, 28, 34);

    // 调整位置 1：左侧「武器改装」部位按钮高度，单位为 UI 像素。
    // 原高度 40f；20f 为原来的一半。只改此值即可同步调整按钮和列表所需高度；字体不缩放。
    private static final float WEAPON_SLOT_BUTTON_HEIGHT = 20f;
    // 每个部位按钮前的间距，独立于按钮高度；想让列表更紧凑可把 10f 改为 5f。
    private static final float WEAPON_SLOT_BUTTON_GAP = 5f;

    // 调整位置 2：右侧「防御改装 / 火力改装」图标的宽高缩放比例。
    // 基准边长来自 Moci_AirSquadronLayout.topWorkbench().slotSize()，正常为 40f。
    // 0.5f = 20×20，1f = 40×40，0.75f = 30×30；边框、点击区域、行高及内边距同步缩放。
    private static final float TACTICAL_ICON_SCALE = 0.75f;

    private Moci_AirSquadronDialog() {}

    public static void open(IntelUIAPI ui, Moci_CombatSquadManagerIntel owner, String memberId) {
        if (ui == null || owner == null || memberId == null) return;
        InteractionDialogAPI existing = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
        SectorEntityToken target = existing == null ? null : existing.getInteractionTarget();
        ui.showDialog(target, new Wrapper(ui, owner, memberId));
    }

    private static final class Wrapper implements InteractionDialogPlugin {
        private final IntelUIAPI ui;
        private final Moci_CombatSquadManagerIntel owner;
        private final String memberId;
        private InteractionDialogAPI dialog;
        private Workbench workbench;
        private boolean finished;

        private Wrapper(IntelUIAPI ui, Moci_CombatSquadManagerIntel owner, String memberId) {
            this.ui = ui;
            this.owner = owner;
            this.memberId = memberId;
        }

        @Override public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            dialog.hideTextPanel();
            dialog.hideVisualPanel();
            dialog.setOptionOnEscape(null, null);
            workbench = new Workbench(this, memberId);
            float width = Math.max(320f, Global.getSettings().getScreenWidth() - 64f);
            float height = Math.max(300f, Global.getSettings().getScreenHeight() - 96f);
            dialog.showCustomVisualDialog(width, height, workbench);
        }

        private void finish() {
            if (finished) return;
            finished = true;
            if (dialog != null) dialog.dismiss();
            ui.updateUIForItem(owner);
        }

        @Override public void optionSelected(String text, Object data) {
            if (workbench != null) workbench.request(new Action("close", null));
        }
        @Override public void optionMousedOver(String text, Object data) {}
        @Override public void advance(float amount) {}
        @Override public void backFromEngagement(EngagementResultAPI result) {}
        @Override public Object getContext() { return null; }
        @Override public Map<String, MemoryAPI> getMemoryMap() { return Collections.emptyMap(); }
    }

    private record Action(String kind, Object value) {}
    private record WeaponChoice(String slotId, String weaponId) {}

    private static final class Workbench extends BaseCustomUIPanelPlugin implements CustomVisualDialogDelegate {
        private final Wrapper wrapper;
        private final String memberId;
        private CustomPanelAPI panel;
        private CustomPanelAPI content;
        private DialogCallbacks callbacks;
        private FleetMemberAPI carrier;
        private List<BayEntry> bays = List.of();
        private BayEntry selected;
        private List<WeaponSlotAPI> slots = List.of();
        private final Moci_AirSquadronArt art = new Moci_AirSquadronArt();
        private final Map<String, Moci_AirSquadronArt.Model> bayPreviews = new LinkedHashMap<>();
        private final Map<String, Moci_AirSquadronArt.Model> weaponIcons = new LinkedHashMap<>();
        /** 「战术改装」图标缓存：id -> 由船插贴图生成的单层模型。 */
        private final Map<String, Moci_AirSquadronArt.Model> tacticalIcons = new LinkedHashMap<>();
        private final Moci_AirSquadronInput<Action> controls = new Moci_AirSquadronInput<>();
        private final Moci_AirSquadronInput<Action> weaponControls = new Moci_AirSquadronInput<>();
        private final Map<CustomPanelAPI, TooltipMakerAPI> columnViewports = new LinkedHashMap<>();
        private final Map<String, ButtonAPI> slotButtons = new LinkedHashMap<>();
        private final Map<String, List<WeaponAvailability>> fittingWeapons = new LinkedHashMap<>();
        private Moci_AirSquadronCanvas fighterCanvas;
        private CustomPanelAPI weaponsParent;
        private CustomPanelAPI weaponsContent;
        private TooltipMakerAPI weaponsList;
        private float weaponsX, weaponsY, weaponsWidth, weaponsHeight;
        private Moci_AirSquadronArt.Model carrierPreview;
        private Moci_AirSquadronArt.Model draftPreview;
        private String previewWingId;
        private Map<String, String> previewLoadout = Map.of();
        private Map<String, String> installed = new LinkedHashMap<>();
        private Map<String, String> draft = new LinkedHashMap<>();
        private String selectedSlot;
        private String hoveredSlot;
        private String displayedWeaponSlot;
        private boolean hoverFailed;
        /** 当前联队（源）已安装的战术改装，空列表 = 未安装。只读，来自服务层。 */
        private List<String> tacticalInstalled = List.of();
        /** 「战术改装」分组草稿，空列表 = 不装。应用装配时随新联队定义一起写入。 */
        private List<String> tacticalDraft = List.of();
        private String notice = "";
        private Action pending;
        private final Moci_AirSquadronInput.Confirmation<Action> confirmation = new Moci_AirSquadronInput.Confirmation<>();
        private boolean closing;
        private boolean dismissed;
        private boolean applying;
        private RefitPlan plan;
        private Budget refitBudget;
        private float width;
        private float height;
        private Workbench(Wrapper wrapper, String memberId) {
            this.wrapper = wrapper;
            this.memberId = memberId;
        }

        @Override public void init(CustomPanelAPI panel, DialogCallbacks callbacks) {
            this.panel = panel;
            this.callbacks = callbacks;
            width = panel.getPosition().getWidth();
            height = panel.getPosition().getHeight();
            callbacks.getPanelFader().setDurationOut(0.12f);
            refreshCarrier(-1);
            rebuild();
        }

        @Override public CustomUIPanelPlugin getCustomPanelPlugin() { return this; }
        @Override public float getNoiseAlpha() { return 0f; }

        private void request(Action action) {
            // Native controls can report more than one click during a frame. Handle one transaction at a time.
            if (pending == null && !closing && !applying) pending = action;
        }

        @Override public void advance(float amount) {
            if (closing) {
                if (!dismissed && callbacks.getPanelFader().isFadedOut()) {
                    dismissed = true;
                    callbacks.dismissDialog();
                }
                return;
            }
            // Ordinary native buttons toggle their checked state; delegate arguments are engine UI objects.
            // Dispatch native clicks before changing a hover preview. A candidate action carries
            // the slot it was drawn for, even when the pointer leaves that slot in this frame.
            Action nativeAction = controls.poll();
            Action weaponAction = weaponControls.poll();
            if (nativeAction != null) request(nativeAction);
            if (weaponAction != null) request(weaponAction);
            if (pending == null) {
                if (!hoverFailed && fighterCanvas != null && slotInteractionEnabled()) {
                    // SettingsAPI already returns virtual UI coordinates, including screen scaling.
                    try {
                        updateHoveredSlot(Global.getSettings().getMouseX(), Global.getSettings().getMouseY());
                    } catch (RuntimeException ex) {
                        // Disable automatic retry until the next explicit action: never log a
                        // broken preview or repeat an expensive failed refresh every frame.
                        hoverFailed = true;
                        notice = "悬停预览暂不可用，请点击部位重试。";
                        Global.getLogger(Moci_AirSquadronDialog.class).warn("航空中队悬停预览失败", ex);
                    }
                }
                return;
            }
            Action action = pending;
            pending = null;
            try {
                hoverFailed = false;
                handle(action);
            } catch (RuntimeException ex) {
                Global.getLogger(Moci_AirSquadronDialog.class).error("航空中队管理界面操作失败", ex);
                notice = "操作未完成：" + Objects.toString(ex.getMessage(), "请关闭窗口后重新打开");
                rebuild();
            }
        }

        @Override public void reportDismissed(int option) { wrapper.finish(); }

        @Override public void processInput(List<InputEventAPI> events) {
            for (InputEventAPI event : events) {
                if (event.isConsumed()) continue;
                if (event.isKeyDownEvent() && event.getEventValue() == Keyboard.KEY_ESCAPE) {
                    request(new Action(!confirmation.isPending() ? "close" : "keep", null));
                    event.consume();
                }
            }
        }

        private boolean dirty() {
            // 武器草稿与战术改装草稿任意一项偏离当前装配，都算有未应用改动。
            return !draft.equals(installed) || !Objects.equals(tacticalDraft, tacticalInstalled);
        }

        private void resetDraft() {
            draft = new LinkedHashMap<>(installed);
            tacticalDraft = tacticalInstalled;
            plan = null;
        }

        private void refreshCarrier(int preferredBay) {
            carrier = Moci_FighterLoadoutService.findMember(memberId);
            carrierPreview = carrier == null ? null : art.build(carrier.getVariant());
            bays = carrier == null ? List.of() : Moci_FighterLoadoutService.collectBays(carrier);
            bayPreviews.keySet().removeIf(id -> bays.stream().noneMatch(bay -> Objects.equals(id, bay.wingId())));
            BayEntry next = null;
            for (BayEntry bay : bays) if (bay.index() == preferredBay) next = bay;
            if (next == null && !bays.isEmpty()) next = bays.get(0);
            select(next);
        }

        private void select(BayEntry bay) {
            selected = bay;
            installed = bay == null || bay.wing() == null ? new LinkedHashMap<>()
                    : Moci_FighterLoadoutService.readLoadout(bay.wingId());
            draft = new LinkedHashMap<>(installed);
            slots = bay == null || bay.wing() == null ? List.of()
                    : Moci_FighterLoadoutService.getEditableSlots(bay.wing());
            for (WeaponSlotAPI slot : slots) installed.putIfAbsent(slot.getId(), "");
            draft = new LinkedHashMap<>(installed);
            selectedSlot = null;
            hoveredSlot = null;
            // 切换机库时战术改装草稿跟随当前联队（读取失败/未安装都视为「不装」）。
            tacticalInstalled = bay == null || bay.wing() == null ? List.of()
                    : Moci_FighterLoadoutService.getTacticalHullmods(bay.wingId());
            tacticalDraft = tacticalInstalled;
            plan = null;
        }

        private void handle(Action action) {
            if (confirmation.isPending()) {
                if ("keep".equals(action.kind())) { confirmation.keep(); rebuild(); return; }
                if ("apply".equals(action.kind())) { confirmation.keep(); apply(); return; }
                if (!"discard".equals(action.kind())) return;
                Action confirmed = confirmation.discard();
                resetDraft();
                notice = "";
                handle(confirmed);
                return;
            }
            switch (action.kind()) {
                case "close" -> {
                    if (!confirmation.request(action, dirty())) { rebuild(); }
                    else { closing = true; callbacks.getPanelFader().fadeOut(); }
                }
                case "bay" -> {
                    BayEntry bay = (BayEntry) action.value();
                    if (selected != null && bay.index() == selected.index()) return;
                    if (!confirmation.request(action, dirty())) { rebuild(); }
                    else { select(bay); notice = ""; rebuild(); }
                }
                case "slot" -> {
                    String slotId = (String) action.value();
                    if (!slotInteractionEnabled() || findSlot(slotId) == null) return;
                    selectedSlot = slotId;
                    hoveredSlot = null;
                    refreshSlotFocus();
                    refreshWeapons();
                }
                case "weapon" -> {
                    if (!slotInteractionEnabled() || !(action.value() instanceof WeaponChoice choice)
                            || findSlot(choice.slotId()) == null) return;
                    selectedSlot = choice.slotId();
                    hoveredSlot = null;
                    draft.put(choice.slotId(), choice.weaponId() == null ? "" : choice.weaponId());
                    notice = "草稿尚未应用。";
                    rebuild();
                }
                case "tactical" -> {
                    // 每组独立单选；再次点击取消该组。
                    if (!tacticalInteractionEnabled()) return;
                    String choice = (String) action.value();
                    if (!tacticalDraft.contains(choice) && Moci_FighterLoadoutService.tacticalUnavailableReason(
                            choice, selected.wing().getVariant()) != null) return;
                    tacticalDraft = Moci_TacticalHullmods.toggle(tacticalDraft, choice);
                    notice = "草稿尚未应用。";
                    rebuild();
                }
                case "reset" -> {
                    resetDraft();
                    notice = "已恢复当前装配。";
                    rebuild();
                }
                case "discard" -> {
                    if (!dirty()) return;
                    resetDraft();
                    notice = "已放弃草稿。";
                    rebuild();
                }
                case "keep" -> rebuild();
                case "prototype" -> {
                    if (!editable()) return;
                    if (weaponEditable()) draft = Moci_FighterLoadoutService.readPrototypeLoadout(selected.wingId());
                    for (WeaponSlotAPI slot : slots) draft.putIfAbsent(slot.getId(), "");
                    // 原型联队本身不带战术改装，载入原型时同步清空战术改装草稿。
                    if (selected.tacticalEligible()) tacticalDraft = List.of();
                    notice = "已载入原型草稿，点击应用后结算。";
                    rebuild();
                }
                case "apply" -> apply();
                default -> { }
            }
        }

        private boolean editable() {
            return selected != null && selected.wing() != null && selected.eligible() && !selected.builtIn()
                    && Moci_FighterLoadoutService.isAvailable();
        }

        private boolean slotInteractionEnabled() {
            return !closing && !applying && !confirmation.isPending() && weaponEditable();
        }

        private boolean weaponEditable() {
            return editable() && selected.weaponEligible();
        }

        private boolean tacticalInteractionEnabled() {
            return !closing && !applying && !confirmation.isPending()
                    && editable() && selected.tacticalEligible();
        }

        private WeaponSlotAPI findSlot(String slotId) {
            if (slotId == null) return null;
            for (WeaponSlotAPI slot : slots) if (slotId.equals(slot.getId())) return slot;
            return null;
        }

        private String effectiveSlot() { return hoveredSlot == null ? selectedSlot : hoveredSlot; }

        private void updateHoveredSlot(float mouseX, float mouseY) {
            if (!slotInteractionEnabled() || fighterCanvas == null) return;
            String next = fighterCanvas.hitMarker(mouseX, mouseY);
            if (Objects.equals(next, hoveredSlot)) return;
            hoveredSlot = next;
            refreshSlotFocus();
            refreshWeapons();
        }

        private void refreshSlotFocus() {
            if (fighterCanvas != null) fighterCanvas.focus(selectedSlot, hoveredSlot);
            for (Map.Entry<String, ButtonAPI> entry : slotButtons.entrySet()) {
                if (entry.getKey().equals(selectedSlot)) entry.getValue().highlight();
                else entry.getValue().unhighlight();
            }
        }

        private void apply() {
            if (!dirty() || !editable() || applying) return;
            applying = true;
            try {
                RefitPlan fresh = Moci_FighterLoadoutService.previewTactical(memberId, selected.index(),
                        selected.wingId(), draft, tacticalDraft);
                if (!fresh.hasChanges || fresh.blockReason != null) {
                    notice = fresh.blockReason == null ? "装配没有变化。" : fresh.blockReason;
                } else {
                    String failure = Moci_FighterLoadoutService.applyRefit(fresh);
                    if (failure == null) {
                        int bay = selected.index();
                        refreshCarrier(bay);
                        notice = "装配已应用，武器已结算。";
                    } else notice = failure;
                }
            } finally { applying = false; }
            rebuild();
        }

        private TooltipMakerAPI text(CustomPanelAPI parent, float w, float h, boolean scroll) {
            TooltipMakerAPI text = parent.createUIElement(Math.max(20f, w), Math.max(1f, h), scroll);
            text.setParaFont("graphics/fonts/insignia15LTaa.fnt");
            text.setButtonFontDefault();
            return text;
        }

        private ButtonAPI button(TooltipMakerAPI text, String label, Action action, float w, float h, float pad, boolean enabled) {
            ButtonAPI button = text.addButton(label, action, ACCENT, DARK, w, h, pad);
            button.setEnabled(enabled && !confirmation.isPending());
            registerButton(button, action);
            return button;
        }

        private void registerButton(ButtonAPI button, Action action) {
            controls.add(button::isChecked, () -> button.setChecked(false), button::isEnabled, action);
        }

        private void add(CustomPanelAPI parent, TooltipMakerAPI text, float x, float y) {
            parent.addUIElement(text).inTL(x, y);
        }

        private CustomPanelAPI column(float x, float y, float w, float viewportHeight, float minimumHeight, boolean scanning) {
            float actualHeight = Math.max(viewportHeight, minimumHeight);
            float actualWidth = actualHeight > viewportHeight ? Math.max(30f, w - 18f) : w;
            CustomPanelAPI result = content.createCustomPanel(actualWidth, actualHeight, new Moci_AirSquadronScanPanel(scanning));
            if (actualHeight > viewportHeight) {
                TooltipMakerAPI viewport = text(content, w, viewportHeight, true);
                viewport.addCustom(result, 0);
                add(content, viewport, x, y);
                columnViewports.put(result, viewport);
            } else content.addComponent(result).inTL(x, y);
            return result;
        }

        private void rebuild() {
            if (panel == null || closing) return;
            if (content != null) panel.removeComponent(content);
            content = panel.createCustomPanel(width, height, new Moci_AirSquadronScanPanel(false));
            panel.addComponent(content).inTL(0, 0);
            controls.clear();
            weaponControls.clear();
            columnViewports.clear();
            slotButtons.clear();
            fittingWeapons.clear();
            fighterCanvas = null;
            weaponsParent = null;
            weaponsContent = null;
            weaponsList = null;
            displayedWeaponSlot = null;
            float gap = Moci_AirSquadronLayout.COLUMN_GAP;
            float bodyY = buildHeader();
            float bodyHeight = Math.max(160f, height - bodyY - 56f);
            float usable = width - 40f;
            // 三栏宽度比例统一由 Moci_AirSquadronLayout 的三个常量控制
            // （CARRIER_RATIO / MIDDLE_RATIO / EDITOR_RATIO = 0.23 / 0.22 / 0.55）：
            // 左栏母舰、中栏机库联队、右栏装配工作台。右栏用「总可用宽度减前两栏」算，
            // 保证三栏之和恒等于 usable，手改常量也不会横向溢出。
            float left = usable * Moci_AirSquadronLayout.CARRIER_RATIO;
            float middle = usable * Moci_AirSquadronLayout.MIDDLE_RATIO;
            float right = usable - left - middle;
            buildCarrier(10f, bodyY, left, bodyHeight);
            buildBays(10f + left + gap, bodyY, middle, bodyHeight);
            buildEditor(10f + left + middle + 2f * gap, bodyY, right, bodyHeight);
            buildFooter(height - 48f);
        }

        /** Display the selected wing's current draft; merely inspecting it never changes live OP. */
        private float buildHeader() {
            Moci_AirSquadronLayout.Header layout = Moci_AirSquadronLayout.header(width);
            TooltipMakerAPI header = text(content, layout.titleWidth(), 38f, false);
            header.addTitle("航空中队管理", ACCENT);
            add(content, header, 12, 8);
            refitBudget = null;
            String caption = "改造值：请选择联队";
            String op = ""; // 未选中有效联队时不显示 OP 行。
            if (selected != null && selected.wing() != null) {
                try {
                    refitBudget = Moci_FighterLoadoutService.refitBudgetForTacticals(selected.wingId(), draft, tacticalDraft);
                    String percent = refitBudget.baseOp() > 0f ? number(refitBudget.usageRatio() * 100f) + "%" : "无基础容量";
                    caption = "改造值 " + refitBudget.usedPoints() + " / " + number(refitBudget.baseOp()) + " · " + percent;
                    op = "联队当前 OP：" + number(refitBudget.opCost());
                    if (width < 540f) {
                        caption = refitBudget.usedPoints() + " / " + number(refitBudget.baseOp()) + " · " + percent;
                    }
                } catch (RuntimeException ex) {
                    caption = "改造值暂不可用";
                    op = "请检查原型与武器规格";
                    Global.getLogger(Moci_AirSquadronDialog.class).warn("航空中队能量计算失败", ex);
                }
            }
            Moci_AirSquadronEnergyBar bar = new Moci_AirSquadronEnergyBar(refitBudget);
            CustomPanelAPI gauge = content.createCustomPanel(layout.gaugeWidth(), Moci_AirSquadronLayout.ENERGY_BAR_HEIGHT, bar);
            content.addComponent(gauge).inTL(layout.gaugeX(), layout.gaugeY());
            TooltipMakerAPI details = text(content, layout.textWidth(), 36f, false);
            LabelAPI label = details.addPara(caption, 0f);
            // 第一行的固定文字（「改造值」、基础 OP 等）保持原本的蓝色；只有当前值和百分比动态染色。
            label.setColor(Moci_AirSquadronEnergyBar.BLUE);
            if (refitBudget != null) {
                String used = Integer.toString(refitBudget.usedPoints());
                String percent = number(refitBudget.usageRatio() * 100f) + "%";
                label.setHighlight(refitBudget.baseOp() > 0f ? new String[]{used, percent} : new String[]{used});
                label.setHighlightColor(bar.color());
            }
            if (!op.isEmpty()) {
                LabelAPI opLabel = details.addPara(op, 2f);
                // 第二行固定文字保持原本的青绿色；只有「联队当前 OP：」后面的数字动态染色。
                opLabel.setColor(refitBudget == null ? WARNING : ACCENT);
                if (refitBudget != null) {
                    // 用精确字符区间染色，避免 LabelAPI 的字符串匹配在中文前缀后漏掉该数字。
                    int valueStart = "联队当前 OP：".length();
                    opLabel.setHighlight(valueStart, op.length());
                    opLabel.setHighlightColor(refitBudget.overLimit() ? Moci_AirSquadronEnergyBar.RED : ACCENT);
                }
            }
            add(content, details, layout.textX(), layout.textY());
            return layout.bodyY();
        }

        private static String number(float value) {
            return value == (long) value ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value);
        }

        private void buildCarrier(float x, float y, float w, float h) {
            CustomPanelAPI column = column(x, y, w, h, 280f, true);
            w = column.getPosition().getWidth();
            Moci_AirSquadronLayout.Carrier layout = Moci_AirSquadronLayout.carrier(h, w);
            h = layout.height();
            TooltipMakerAPI title = text(column, w - 16f, 66f, true);
            title.addSectionHeading("母舰", ACCENT, DARK, Alignment.MID, 0);
            if (carrier == null) {
                title.addPara("母舰已不在玩家舰队中。", BAD, 8f);
                add(column, title, 8, 8);
                return;
            }
            title.addPara(carrier.getShipName(), ACCENT, 8f);
            title.addPara(carrier.getHullSpec().getHullNameWithDashClass(), DIM, 4f);
            add(column, title, 8, 8);
            float icon = layout.iconSize();
            if (carrierPreview != null) {
                // Keep the carrier on the scan grid without adding a second
                // rectangle around the sprite itself.
                Moci_AirSquadronCanvas preview = new Moci_AirSquadronCanvas(carrierPreview, false, false, 4f, 0, null, false);
                CustomPanelAPI image = column.createCustomPanel(w - 16f, icon + 12f, preview);
                column.addComponent(image).inTL(8, layout.imageTop());
            }
            TooltipMakerAPI info = text(column, w - 16f, 100f, true);
            PersonAPI captain = carrier.getCaptain();
            if (captain != null && !captain.isDefault()) {
                TooltipMakerAPI portrait = info.beginImageWithText(captain.getPortraitSprite(), 44f);
                portrait.setParaFont("graphics/fonts/insignia15LTaa.fnt");
                portrait.addPara("舰长：" + captain.getNameString(), ACCENT, 0f);
                info.addImageWithText(0);
            } else info.addPara("舰长：未分配军官", DIM, 0f);
            info.addPara("已安装联队：" + bays.stream().filter(b -> b.wing() != null).count() + " / " + bays.size(), DIM, 8f);
            info.addPara("按母舰与机库独立管理", ACCENT, 6f);
            add(column, info, 8, layout.infoTop());
        }

        private void buildBays(float x, float y, float w, float h) {
            CustomPanelAPI column = content.createCustomPanel(w, h, new Moci_AirSquadronScanPanel(false));
            content.addComponent(column).inTL(x, y);
            TooltipMakerAPI list = text(column, w - 18f, h - 16f, true);
            list.addSectionHeading("机库联队", ACCENT, DARK, Alignment.MID, 0);
            if (bays.isEmpty()) list.addPara("没有可显示的机库。", DIM, 12f);
            for (BayEntry bay : bays) {
                boolean chosen = selected != null && selected.index() == bay.index();
                String name = bay.wing() == null ? "空机库" : bay.wing().getWingName();
                float cardWidth = w - 24f;
                float iconWidth = Math.min(94f, cardWidth * 0.40f);
                Moci_AirSquadronCanvas cardPlugin = new Moci_AirSquadronCanvas(art.weaponIcon(null), false, chosen,
                        0, 0, () -> { if (!confirmation.isPending()) request(new Action("bay", bay)); });
                cardPlugin.clipViewport(() -> list.getExternalScroller() == null ? column.getPosition()
                        : list.getExternalScroller().getPosition());
                CustomPanelAPI card = column.createCustomPanel(cardWidth, 100f, cardPlugin);
                TooltipMakerAPI label = text(card, Math.max(30f, cardWidth - iconWidth - 20f), 132f, false);
                label.addSectionHeading(chosen ? "已选中联队" : "点击选择联队", chosen ? ACCENT : DIM, DARK, Alignment.MID, 0);
                label.addPara("机库 " + (bay.index() + 1) + " " + name, chosen ? ACCENT : Color.LIGHT_GRAY, 6f);
                if (bay.wing() != null) {
                    try {
                        Moci_AirSquadronArt.Model icon = bayPreviews.computeIfAbsent(bay.wingId(), id -> art.build(bay.wing().getVariant()));
                        Moci_AirSquadronCanvas iconPlugin = new Moci_AirSquadronCanvas(icon, false, chosen,
                                5, chosen ? 10f : 0, null);
                        CustomPanelAPI image = card.createCustomPanel(iconWidth, 75f, iconPlugin);
                        card.addComponent(image).inTL(8, 14);
                    } catch (RuntimeException ex) {
                        label.addPara("预览不可用", DIM, 4f);
                    }
                }
                if (bay.wing() != null) {
                    // 自定义联队额外标注战术改装名称：让「战术改装是否真的装上了」在列表里一眼可见。
                    List<String> tactical = bay.wingId() == null ? List.of()
                            : Moci_FighterLoadoutService.getTacticalHullmods(bay.wingId());
                    String suffix = Moci_FighterLoadoutService.isCustomWingId(bay.wingId())
                            ? " 已改装" + (tactical.isEmpty() ? "" : " " + tacticalNames(tactical))
                            : " 原型";
                    label.addPara("数量 " + bay.wing().getNumFighters() + suffix, DIM, 4f);
                }
                if (bay.builtIn() || !bay.eligible()) {
                    label.addPara(bay.builtIn() ? "内置联队 只读" : Objects.toString(bay.lockReason(), "不可装配"), WARNING, 4f);
                }
                add(card, label, iconWidth + 12f, 6f);
                list.addCustom(card, 8f);
            }
            add(column, list, 8, 8);
        }

        private void buildEditor(float x, float y, float w, float h) {
            CustomPanelAPI column = column(x, y, w, h, Moci_AirSquadronLayout.MIN_EDITOR_HEIGHT, false);
            w = column.getPosition().getWidth();
            Moci_AirSquadronLayout.Editor layout = Moci_AirSquadronLayout.editor(h);
            h = layout.height();
            if (selected == null || selected.wing() == null) {
                TooltipMakerAPI empty = text(column, w - 20f, h - 16f, true);
                empty.addSectionHeading("舰载机装配", ACCENT, DARK, Alignment.MID, 0);
                empty.addPara("从中间选择一个已安装的舰载机联队。", DIM, 12f);
                if (!Moci_FighterLoadoutService.isAvailable()) empty.addPara(Moci_FighterLoadoutService.getUnavailableReason(), WARNING, 8f);
                add(column, empty, 8, 8);
                return;
            }
            float topHeight = layout.topHeight();
            buildFighterWorkbench(column, w - 12f, topHeight);
            float summaryH = layout.summaryHeight();
            float weaponsY = topHeight + 10f;
            float weaponsH = layout.weaponsHeight();
            buildWeapons(column, 8, weaponsY, w - 18f, weaponsH);
            buildSummary(column, 8, weaponsY + weaponsH + 5f, w - 18f, summaryH);
            TooltipMakerAPI commands = text(column, w - 16f, 36f, false);
            float buttonWidth = (w - 28f) / 3f;
            ButtonAPI apply = button(commands, "应用装配", new Action("apply", null), buttonWidth, 30f, 0,
                    dirty() && editable() && plan != null && plan.hasChanges && plan.blockReason == null);
            ButtonAPI reset = button(commands, "恢复装配", new Action("reset", null), buttonWidth, 30f, 0, dirty());
            reset.getPosition().rightOfTop(apply, 4f);
            ButtonAPI prototype = button(commands, "载入原型", new Action("prototype", null), buttonWidth, 30f, 0, editable());
            prototype.getPosition().rightOfTop(reset, 4f);
            add(column, commands, 8, h - 36f);
        }

        private void buildFighterWorkbench(CustomPanelAPI parent, float w, float viewportHeight) {
            TooltipMakerAPI viewport = text(parent, w, viewportHeight, true);
            float workWidth = w - 14f;
            List<String> tacticalOptions = Moci_FighterLoadoutService.getTacticalHullmodOptions();
            float previewHeight = Math.max(viewportHeight - 4f, 52f + slots.size() * (WEAPON_SLOT_BUTTON_HEIGHT + WEAPON_SLOT_BUTTON_GAP + 2f));
            float workHeight = Math.max(previewHeight, 64f + tacticalOptions.size() * (Moci_AirSquadronLayout.TACTICAL_SLOT * TACTICAL_ICON_SCALE + 10f));
            CustomPanelAPI work = parent.createCustomPanel(workWidth, workHeight, null);
            // 右列顶部一行的水平划分交给 Moci_AirSquadronLayout.topWorkbench()：
            // 舰载机预览保持原来的 42% 宽度（尺寸/位置都不动），剩下的宽度对半分成
            // 「武器改装」武器名列表（由原来占满剩余宽度缩减为一半）和新增的「战术改装」图标区。
            Moci_AirSquadronLayout.TopWorkbench top = Moci_AirSquadronLayout.topWorkbench(workWidth);
            float previewWidth = top.previewWidth();
            float namesWidth = top.weaponWidth();
            try {
                if (draftPreview == null || !selected.wingId().equals(previewWingId) || !draft.equals(previewLoadout)) {
                    ShipVariantAPI variant = selected.wing().getVariant().clone();
                    for (WeaponSlotAPI slot : slots) {
                        variant.clearSlot(slot.getId());
                        String id = draft.get(slot.getId());
                        if (id != null && !id.isEmpty()) variant.addWeapon(slot.getId(), id);
                    }
                    draftPreview = art.build(variant);
                    previewWingId = selected.wingId();
                    previewLoadout = new LinkedHashMap<>(draft);
                }
                Moci_AirSquadronCanvas preview = new Moci_AirSquadronCanvas(draftPreview, true, false, 12f, 0, null);
                fighterCanvas = preview;
                preview.focus(selectedSlot, hoveredSlot);
                preview.clipViewport(() -> viewport.getExternalScroller() == null ? parent.getPosition()
                        : viewport.getExternalScroller().getPosition());
                TooltipMakerAPI outerViewport = columnViewports.get(parent);
                if (outerViewport != null) preview.clipViewport(() -> outerViewport.getExternalScroller() == null
                        ? parent.getPosition() : outerViewport.getExternalScroller().getPosition());
                CustomPanelAPI image = work.createCustomPanel(previewWidth, Math.max(40f, previewHeight - 24f), preview);
                work.addComponent(image).inTL(0, 0);
                TooltipMakerAPI caption = text(work, previewWidth, 22f, false);
                caption.addPara(dirty() ? "装配草稿预览" : "当前装配", ACCENT, 0f);
                add(work, caption, 4, previewHeight - 22f);
                TooltipMakerAPI boxes = text(work, namesWidth, workHeight - 4f, false);
                boxes.addSectionHeading("武器改装", ACCENT, DARK, Alignment.MID, 0);
                for (WeaponSlotAPI slot : slots) {
                    boolean chosen = slot.getId().equals(selectedSlot);
                    String id = draft.get(slot.getId());
                    ButtonAPI box = button(boxes, clipWeaponName(id, namesWidth),
                            new Action("slot", slot.getId()), Math.max(40f, namesWidth - 8f),
                            // 高度、间距：在类顶部的 WEAPON_SLOT_BUTTON_HEIGHT / GAP 调整。
                            WEAPON_SLOT_BUTTON_HEIGHT, WEAPON_SLOT_BUTTON_GAP, weaponEditable());
                    slotButtons.put(slot.getId(), box);
                    if (chosen) box.highlight();
                    preview.marker(slot.getId(), slot.getLocation().x, slot.getLocation().y, box.getPosition(),
                            () -> { if (slotInteractionEnabled()) request(new Action("slot", slot.getId())); });
                }
                // getEditableSlots() already filters fixed, system, and decorative slots.
                if (!weaponEditable()) boxes.addPara(selected.builtIn() ? "内置联队只能查看。"
                        : Objects.toString(selected.lockReason(), "该联队不支持武器换装。"), WARNING, 4f);
                add(work, boxes, previewWidth + Moci_AirSquadronLayout.WORKBENCH_GAP, 0);
                buildTactical(work, top, workHeight, tacticalOptions, viewport, parent);
            } catch (RuntimeException ex) {
                TooltipMakerAPI failure = text(work, workWidth, workHeight, true);
                failure.addPara("预览不可用；请重新选择联队。", WARNING, 4f);
                add(work, failure, 0, 0);
                Global.getLogger(Moci_AirSquadronDialog.class).warn("航空中队预览生成失败", ex);
            }
            viewport.addCustom(work, 0);
            add(parent, viewport, 4, 4);
        }

        /** 按 tag 动态列出战术改装；图标直接处理点击，单选结果只写入草稿。 */
        private void buildTactical(CustomPanelAPI work, Moci_AirSquadronLayout.TopWorkbench top,
                                   float workHeight, List<String> options, TooltipMakerAPI viewport,
                                   CustomPanelAPI parent) {
            float x = top.tacticalX();
            float w = top.tacticalWidth();
            // 图标尺寸：在类顶部的 TACTICAL_ICON_SCALE 调整，下面的方框和点击区域共用此边长。
            float slot = top.slotSize() * TACTICAL_ICON_SCALE;
            float rowWidth = Math.max(40f, w - 8f);
            TooltipMakerAPI column = text(work, rowWidth, workHeight - 4f, false);
            if (options.isEmpty()) {
                column.addPara("战术改装不可用。", WARNING, 4f);
                add(work, column, x, 0);
                return;
            }
            float nameWidth = Math.max(24f, rowWidth - slot - 6f);
            for (Moci_TacticalHullmods.Group group : Moci_TacticalHullmods.Group.values()) {
                Color groupColor = group == Moci_TacticalHullmods.Group.DEFENSE
                        ? new Color(100, 170, 255) : new Color(255, 170, 75);
                column.addSectionHeading(group == Moci_TacticalHullmods.Group.DEFENSE
                        ? "防御改装" : "火力改装", groupColor, DARK, Alignment.MID,
                        group == Moci_TacticalHullmods.Group.DEFENSE ? 0f : 8f);
                for (String id : options) {
                    if (Moci_TacticalHullmods.group(id) != group) continue;
                    boolean chosen = tacticalDraft.contains(id);
                    String unavailableReason = Moci_FighterLoadoutService.tacticalUnavailableReason(id,
                            selected == null || selected.wing() == null ? null : selected.wing().getVariant());
                    boolean available = unavailableReason == null;
                    Moci_AirSquadronArt.Model icon = tacticalIcons.computeIfAbsent(id,
                            key -> art.spriteModel(Moci_TacticalHullmods.sprite(key)));
                    CustomPanelAPI row = work.createCustomPanel(rowWidth, slot, null);
                    Action action = new Action("tactical", id);
                    Moci_AirSquadronWeaponTile tile = new Moci_AirSquadronWeaponTile(icon, null, chosen, 2f * TACTICAL_ICON_SCALE,
                            () -> available && tacticalInteractionEnabled(), () -> request(action), groupColor);
                    clipIcon(tile, viewport, parent);
                    CustomPanelAPI iconPanel = row.createCustomPanel(slot, slot, tile);
                    // Direct child: the native tooltip attaches to this same icon, with no overlay or extra inset.
                    row.addComponent(iconPanel).inTL(0, 0);
                    column.addTooltipTo(new TacticalTooltip(id, unavailableReason), iconPanel, TooltipMakerAPI.TooltipLocation.LEFT, false);
                    TooltipMakerAPI name = text(row, nameWidth, slot, false);
                    name.addPara(Moci_TacticalHullmods.displayName(id), !available ? Color.GRAY : chosen ? groupColor : DIM, 0f);
                    row.addUIElement(name).inTL(slot + 6f, Math.max(0f, (slot - 16f) / 2f));
                    // 战术改装图标行之间的间距（UI 像素），与左侧部位按钮间距独立。
                    column.addCustom(row, 10f);
                }
            }
            add(work, column, x, 0);
        }

        private void buildWeapons(CustomPanelAPI parent, float x, float y, float w, float h) {
            weaponsParent = parent;
            weaponsX = x;
            weaponsY = y;
            weaponsWidth = w;
            weaponsHeight = h;
            refreshWeapons();
        }

        /** Replace only the candidates; hovering must not reset either workbench scroller. */
        private void refreshWeapons() {
            if (weaponsParent == null) return;
            String slotId = effectiveSlot();
            if (weaponsList != null && Objects.equals(slotId, displayedWeaponSlot)) return;
            // A scrollable UI element is wrapped by a native ScrollPanel, and its creating
            // panel retains layout metadata. Discard the complete candidates-only child
            // so neither an orphan scroller nor an ever-growing metadata map survives.
            if (weaponsContent != null) weaponsParent.removeComponent(weaponsContent);
            weaponControls.clear();
            weaponsList = null;
            float w = weaponsWidth, h = weaponsHeight;
            CustomPanelAPI parent = weaponsParent.createCustomPanel(w, h, null);
            weaponsContent = parent;
            TooltipMakerAPI list = text(parent, w, h, true);
            WeaponSlotAPI slot = findSlot(slotId);
            list.addSectionHeading("适配武器", ACCENT, DARK, Alignment.MID, 0);
            if (slot == null) list.addPara(slots.isEmpty() ? "没有可编辑的武器槽。"
                    : "悬停部位可预览适配武器，点击部位后固定显示。", DIM, 6f);
            else {
                Action removeAction = new Action("weapon", new WeaponChoice(slotId, null));
                ButtonAPI remove = list.addButton("留空 / 拆下武器", removeAction, ACCENT, DARK,
                        w - 8f, 28f, 6f);
                remove.setEnabled(slotInteractionEnabled());
                registerWeaponButton(remove, removeAction);
                // Inventory/source enumeration is cached for this UI build, never repeated per frame.
                List<WeaponAvailability> weapons = fittingWeapons.computeIfAbsent(slotId,
                        key -> Moci_FighterLoadoutService.listAvailableFittingWeapons(slot));
                if (weapons.isEmpty()) {
                    list.addPara("当前货舱、仓库和市场没有匹配武器。", DIM, 8f);
                } else {
                    final float tileSize = 48f;
                    final float gap = 4f;
                    float gridWidth = Math.max(48f, w - 8f);
                    int columns = Math.max(1, Math.min(10, (int) ((gridWidth + gap) / (tileSize + gap))));
                    int rows = (weapons.size() + columns - 1) / columns;
                    float gridHeight = Math.max(tileSize, rows * (tileSize + gap));
                    CustomPanelAPI grid = parent.createCustomPanel(gridWidth, gridHeight, null);
                    for (int index = 0; index < weapons.size(); index++) {
                        WeaponAvailability availability = weapons.get(index);
                        String id = availability.spec().getWeaponId();
                        int column = index % columns;
                        int row = index / columns;
                        float tileX = column * (tileSize + gap);
                        float tileY = row * (tileSize + gap);
                        boolean chosen = id.equals(draft.get(slotId));
                        // Source colors are an implementation cue only: cargo stays
                        // transparent, market green, storage blue, and black market red.
                        Color fill = weaponTileFill(availability);
                        Action tileAction = new Action("weapon", new WeaponChoice(slotId, id));
                        Moci_AirSquadronWeaponTile tilePlugin = new Moci_AirSquadronWeaponTile(
                                weaponIcons.computeIfAbsent(id, art::weaponIcon), fill, chosen, 4f,
                                () -> weaponsContent == parent && slotInteractionEnabled(), () -> request(tileAction));
                        clipIcon(tilePlugin, list, weaponsParent);
                        CustomPanelAPI image = grid.createCustomPanel(tileSize, tileSize, tilePlugin);
                        // One 48 x 48 component owns background, border, tooltip and click bounds.
                        grid.addComponent(image).inTL(tileX, tileY);
                        list.addTooltipTo(new WeaponTooltip(availability), image, TooltipMakerAPI.TooltipLocation.LEFT, false);
                    }
                    list.addCustom(grid, 5f);
                }
            }
            add(parent, list, 0, 0);
            weaponsParent.addComponent(parent).inTL(weaponsX, weaponsY);
            weaponsList = list;
            displayedWeaponSlot = slotId;
        }

        private void clipIcon(Moci_AirSquadronWeaponTile tile, TooltipMakerAPI viewport, CustomPanelAPI column) {
            tile.clipViewport(() -> viewport.getExternalScroller() == null ? viewport.getPosition()
                    : viewport.getExternalScroller().getPosition());
            TooltipMakerAPI outer = columnViewports.get(column);
            if (outer != null) tile.clipViewport(() -> outer.getExternalScroller() == null ? outer.getPosition()
                    : outer.getExternalScroller().getPosition());
        }

        private void registerWeaponButton(ButtonAPI button, Action action) {
            weaponControls.add(button::isChecked, () -> button.setChecked(false), button::isEnabled, action);
        }

        private void buildSummary(CustomPanelAPI parent, float x, float y, float w, float h) {
            TooltipMakerAPI summary = text(parent, w, h, true);
            summary.addSectionHeading("改装结果", ACCENT, DARK, Alignment.MID, 0);
            plan = Moci_FighterLoadoutService.previewTactical(memberId, selected.index(), selected.wingId(), draft, tacticalDraft);
            if (!Moci_FighterLoadoutService.isAvailable()) summary.addPara(Moci_FighterLoadoutService.getUnavailableReason(), BAD, 4f);
            else if (plan.blockReason != null) summary.addPara(plan.blockReason, BAD, 4f);
            // 战术改装结算：只报最终生效值。字体不含「→」这类符号，也不写前后关系/未改动说明。
            List<String> targetTactical = plan.tacticalHullmods;
            if (!targetTactical.isEmpty()) summary.addPara("战术改装：" + tacticalNames(targetTactical), ACCENT, 4f);
            else summary.addPara("战术改装：未选择", DIM, 4f);
            if (plan.consume.isEmpty() && plan.returns.isEmpty()) summary.addPara("无武器增减。原型自带武器额度免费。", DIM, 4f);
            for (Map.Entry<String, Integer> item : plan.consume.entrySet()) {
                summary.addPara("消耗：" + weaponName(item.getKey()) + " × " + item.getValue(), WARNING, 4f);
            }
            for (Map.Entry<String, Integer> item : plan.returns.entrySet()) {
                summary.addPara("返还货舱：" + weaponName(item.getKey()) + " × " + item.getValue(), ACCENT, 4f);
            }
            // 市场购买结算：货舱/仓库不足的部分会在「应用装配」时自动从当前市场买入。
            if (!plan.purchases.isEmpty()) {
                Map<String, Integer> buying = new LinkedHashMap<>();
                for (Moci_FighterLoadoutService.Purchase purchase : plan.purchases) {
                    buying.merge(purchase.weaponId(), purchase.count(), Math::addExact);
                }
                for (Map.Entry<String, Integer> item : buying.entrySet()) {
                    summary.addPara("将购买：" + weaponName(item.getKey()) + " × " + item.getValue(), ACCENT, 4f);
                }
                summary.addPara("购买花费：" + plan.purchaseCost + " 信用点（应用时扣除）", WARNING, 4f);
            }
            if (!notice.isEmpty()) summary.addPara(notice, WARNING, 5f);
            add(parent, summary, x, y);
        }

        private void buildFooter(float y) {
            TooltipMakerAPI footer = text(content, width - 24f, 42f, false);
            if (dirty()) {
                footer.addPara(confirmation.isPending() ? "关闭前处理未应用草稿" : "存在未应用草稿", WARNING, 0);
                float buttonWidth = Math.min(128f, Math.max(92f, (width - 48f) / 3f));
                Action discardAction = new Action("discard", null);
                ButtonAPI discard = footer.addButton("放弃草稿", discardAction, BAD, DISCARD_BACKGROUND,
                        buttonWidth, 26f, 0);
                discard.setEnabled(true);
                registerButton(discard, discardAction);
                discard.getPosition().inTL(0, 16f);

                Action applyAction = new Action("apply", null);
                ButtonAPI apply = footer.addButton("应用草稿", applyAction, ACCENT, DARK,
                        buttonWidth, 26f, 0);
                apply.setEnabled(dirty() && editable() && plan != null && plan.hasChanges && plan.blockReason == null);
                registerButton(apply, applyAction);
                apply.getPosition().rightOfTop(discard, 4f);

                Action keepAction = new Action("keep", null);
                ButtonAPI keep = footer.addButton("继续编辑", keepAction, ACCENT, DARK,
                        buttonWidth, 26f, 0);
                keep.setEnabled(true);
                registerButton(keep, keepAction);
                keep.getPosition().rightOfTop(apply, 4f);
            } else {
                footer.addPara("草稿仅在应用后生效。关闭返回情报页。", DIM, 4f);
                ButtonAPI close = button(footer, "关闭", new Action("close", null), 112f, 32f, 0, true);
                close.getPosition().inTR(4f, 0);
            }
            add(content, footer, 12, y);
        }
    }

    /** 武器名列表宽度缩减一半后，长武器名必须按宽度截断，否则会撑出按钮、与战术改装区重叠。 */
    private static String clipWeaponName(String id, float width) {
        String name = weaponName(id);
        // 文本在按钮内居中显示，两侧只留少量内边距；按每个字符 16px 粗略估算可容纳的字符数。
        int max = Math.max(2, (int) ((width - 16f) / 16f));
        if (name.length() <= max) return name;
        return name.substring(0, Math.max(1, max - 1)) + "…";
    }

    /** 战术改装的展示名：优先运行时船插规格，规格/CSV 缺失时退回 T1 helper 的兜底名称。 */
    private static String tacticalNames(List<String> ids) {
        return ids.stream().map(Moci_AirSquadronDialog::tacticalName).collect(java.util.stream.Collectors.joining("、"));
    }

    private static String tacticalName(String id) {
        if (id == null || id.isEmpty()) return "不装";
        String name = Moci_TacticalHullmods.displayName(id);
        return name == null || name.isEmpty() ? id : name;
    }

    private static String weaponName(String id) {
        if (id == null || id.isEmpty()) return "空槽";
        try { return Global.getSettings().getWeaponSpec(id).getWeaponName(); }
        catch (RuntimeException ex) { return id; }
    }

    private static Color weaponTileFill(WeaponAvailability availability) {
        // 来源优先级与「可用性优先顺序」一致：货舱 > 仓库 > 市场 > 黑市。
        // 货舱里的同型号武器才是真正会被装配消耗的那份，因此必须最先判定；
        // 否则玩家看到的是市场/黑市的底色，会误以为买的是市场货。
        // 货舱来源保持透明底色（与周围扫描网格一致），其余三种给各自的提示色。
        if (availability.cargo() > 0) return new Color(0, 0, 0, 0);
        if (availability.storage() > 0) return STORAGE;
        if (availability.market() > 0) return MARKET;
        if (availability.blackMarket() > 0) return BLACK_MARKET;
        return new Color(0, 0, 0, 0);
    }

    /** 「战术改装」图标悬停提示：名称、船插说明、当前选择状态。 */
    /** 取船插说明里会被替换进文本的参数（如「20%」「×2」），用于与原版一样做金色高亮。 */
    private static List<String> descriptionParams(HullModSpecAPI spec) {
        List<String> params = new ArrayList<>();
        if (spec == null) return params;
        try {
            com.fs.starfarer.api.combat.HullModEffect effect = spec.getEffect();
            if (effect == null) return params;
            for (int index = 0; index < 8; index++) {
                String value = effect.getDescriptionParam(index, ShipAPI.HullSize.FIGHTER);
                if (value == null) break;
                if (!value.isEmpty() && !params.contains(value)) params.add(value);
            }
        } catch (RuntimeException ex) {
            params.clear();
        }
        return params;
    }

    private static final class TacticalTooltip implements TooltipMakerAPI.TooltipCreator {
        private final String id;
        private final String unavailableReason;
        private TacticalTooltip(String id) { this(id, null); }
        private TacticalTooltip(String id, String unavailableReason) {
            this.id = id;
            this.unavailableReason = unavailableReason;
        }
        @Override public boolean isTooltipExpandable(Object param) { return false; }
        @Override public float getTooltipWidth(Object param) { return 320f; }
        @Override public void createTooltip(TooltipMakerAPI tip, boolean expanded, Object param) {
            tip.addTitle(tacticalName(id));
            HullModSpecAPI spec = Moci_TacticalHullmods.spec(id);
            String description = null;
            if (spec != null) {
                try { description = spec.getDescription(ShipAPI.HullSize.FIGHTER); }
                catch (RuntimeException ex) { description = null; }
                if (description == null || description.isBlank()) {
                    try { description = spec.getDescriptionFormat(); }
                    catch (RuntimeException ex) { description = null; }
                }
            }
            // 只展示船插本身的名称与说明（与原版改装页一致），不写任何实现细节或操作提示。
            // 说明里的数值参数按原版改装页的样式高亮成金色，避免玩家读到一串纯白文本。
            if (description != null && !description.isBlank()) {
                List<String> params = descriptionParams(spec);
                // 已展开的说明可能含有百分号，不能再交给格式化重载处理。
                LabelAPI label = tip.addPara(description, 6f);
                if (!params.isEmpty()) {
                    label.setHighlight(params.toArray(new String[0]));
                    label.setHighlightColor(Misc.getHighlightColor());
                }
            }
            if (unavailableReason != null || !Moci_FighterLoadoutService.isTacticalHullmodAvailable(id)) {
                tip.addPara(unavailableReason == null ? "当前不可用。" : unavailableReason, WARNING, 6f);
            }
        }
    }

    /** 非 0 数值用原版高亮金；数值为 0 也用蓝色标出，提示「这里是 0」。 */
    private static Color numberColor(long value) {
        return value != 0 ? Misc.getHighlightColor() : ACCENT;
    }

    /** 把「标签 + 数值」写进正文，并登记同一串的数值高亮（颜色按是否为 0 区分）。 */
    private static void number(boolean[] first, StringBuilder text, List<String> highlights,
            List<Color> colors, String label, long value) {
        if (!first[0]) text.append(" · ");
        first[0] = false;
        text.append(label).append(value);
        highlights.add(label + value);
        colors.add(numberColor(value));
    }

    /** 单个数值按指定颜色高亮（用于「市场单价」这类固定用蓝色标注的成本类数字）。 */
    private static void paraAccent(TooltipMakerAPI tip, String label, long value, float pad) {
        String text = label + value;
        tip.addPara(text, pad, ACCENT, text);
    }

    private static void para(TooltipMakerAPI tip, String label, long value) {
        para(tip, 6f, label, value);
    }
    private static void para(TooltipMakerAPI tip, String label, long value, float pad) {
        para(tip, pad, label, value);
    }
    private static void para(TooltipMakerAPI tip, float pad, String label, long value) {
        StringBuilder text = new StringBuilder(label).append(value);
        List<String> highlights = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        highlights.add(label + value);
        colors.add(numberColor(value));
        tip.addPara(text.toString(), pad, colors.toArray(new Color[0]), highlights.toArray(new String[0]));
    }
    /** 「标签 值 · 标签 值 …」一行，每个数值都高亮（非 0 金、0 蓝）。 */
    private static void para(TooltipMakerAPI tip, String labelA, long valueA, String labelB, long valueB) {
        para(tip, 6f, labelA, valueA, labelB, valueB);
    }
    private static void para(TooltipMakerAPI tip, String labelA, long valueA, String labelB, long valueB,
            String labelC, long valueC, String labelD, long valueD) {
        para(tip, 6f, labelA, valueA, labelB, valueB, labelC, valueC, labelD, valueD);
    }
    private static void para(TooltipMakerAPI tip, float pad, String labelA, long valueA, String labelB, long valueB) {
        StringBuilder text = new StringBuilder();
        List<String> highlights = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        boolean[] first = {true};
        number(first, text, highlights, colors, labelA, valueA);
        number(first, text, highlights, colors, labelB, valueB);
        tip.addPara(text.toString(), pad, colors.toArray(new Color[0]), highlights.toArray(new String[0]));
    }
    private static void para(TooltipMakerAPI tip, float pad, String labelA, long valueA, String labelB, long valueB,
            String labelC, long valueC, String labelD, long valueD) {
        StringBuilder text = new StringBuilder();
        List<String> highlights = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        boolean[] first = {true};
        number(first, text, highlights, colors, labelA, valueA);
        number(first, text, highlights, colors, labelB, valueB);
        number(first, text, highlights, colors, labelC, valueC);
        number(first, text, highlights, colors, labelD, valueD);
        tip.addPara(text.toString(), pad, colors.toArray(new Color[0]), highlights.toArray(new String[0]));
    }

    private static final class WeaponTooltip implements TooltipMakerAPI.TooltipCreator {
        private final WeaponAvailability availability;
        private WeaponTooltip(WeaponAvailability availability) { this.availability = availability; }
        @Override public boolean isTooltipExpandable(Object param) { return false; }
        @Override public float getTooltipWidth(Object param) { return 310f; }
        @Override public void createTooltip(TooltipMakerAPI tip, boolean expanded, Object param) {
            WeaponSpecAPI spec = availability.spec();
            tip.addTitle(spec.getWeaponName());
            tip.addPara(spec.getWeaponId(), DIM, 4f);
            tip.addPara(spec.getSize() + " / " + spec.getType() + " / " + spec.getDamageType(), 6f);
            // 说明里的每个数字都要可读：非 0 用高亮金，0 用蓝色，避免在同一行里混色。
            para(tip, "射程 ", Math.round(spec.getMaxRange()));
            para(tip, "货舱 ", availability.cargo(), "仓库 ", availability.storage(),
                    "市场 ", availability.market(), "黑市 ", availability.blackMarket());
            if (availability.market() > 0 || availability.blackMarket() > 0) {
                // 价格保持蓝色：这是买入成本提示，和「数量/性能」类数字的金色高亮区分开。
                paraAccent(tip, "市场单价：", Moci_FighterLoadoutService.bestMarketUnitPrice(spec), 5f);
                tip.addPara("应用装配时自动购买。", DIM, 5f);
            }
            if (spec.getDerivedStats() != null) {
                para(tip, "每秒伤害 ", Math.round(spec.getDerivedStats().getDps()),
                        " · 每秒幅能 ", Math.round(spec.getDerivedStats().getFluxPerSecond()));
            }
            if (spec.usesAmmo()) para(tip, "弹药 ", spec.getMaxAmmo());
        }
    }
}
