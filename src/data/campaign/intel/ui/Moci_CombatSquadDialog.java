package data.campaign.intel.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomVisualDialogDelegate;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.characters.SkillSpecAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.IntelUIAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.util.Misc;
import data.campaign.intel.Moci_CombatSquadManagerIntel;
import data.campaign.intel.Moci_ShipboardAIManagerIntel;
import data.campaign.fighter.ui.Moci_AirSquadronArt;
import data.hullmods.Moci_CombatSquadTextRegistry;
import data.hullmods.Moci_PilotManager;
import data.hullmods.Moci_PilotTraining;
import data.hullmods.Moci_ShipboardAIAuxEffectRegistry;
import data.hullmods.Moci_ShipboardAIConfigLoader;
import data.hullmods.Moci_ShipboardAIManager;
import org.apache.log4j.Logger;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 作战小队管理弹窗（编队页 + 舰载 AI 页），写法对齐 Moci_AirSquadronDialog：open → Wrapper(InteractionDialogPlugin)
 * → Workbench(BaseCustomUIPanelPlugin + CustomVisualDialogDelegate)。
 * <p>
 * 所有位置和尺寸来自 data/config/Moci_UILayout/combat_squad_dialog.json（缺失/损坏时用本类注册的默认值），
 * 每个看得见的块都经 Moci_UILayout.place 放置，创建尺寸取同一个 rect 的 w/h，行/格子间距只取 pitchX/pitchY/cols。
 * 业务规则一律调用 Moci_CombatSquadManagerIntel / Moci_ShipboardAIManagerIntel 上的共用方法，这里不复制规则。
 * 点按钮只记动作，在 advance 里处理并重建内容面板；弹出菜单是模态的（半透明底板 + 底下按钮全部禁用）。
 */
public final class Moci_CombatSquadDialog {

    private static final Logger LOG = Global.getLogger(Moci_CombatSquadDialog.class);
    private static final String P = "[Moci_CombatSquadDialog] ";
    public static final String LAYOUT_ID = "combat_squad_dialog";

    public enum Tab { SQUAD, AI }

    public enum Popup { PORTRAIT, ELITE, SHIP, RENAME }

    private static final int MAX_PORTRAITS = 160;
    private static final int MAX_SHIPS = 60;
    private static final int MAX_AI_OPTIONS = 18;
    private static final int MAX_EVOLUTIONS = 4;
    private static final int MAX_LEVEL = 7;
    private static final String FIELD_FONT = "graphics/fonts/insignia21LTaa.fnt";

    private static final Color C_CARD = new Color(10, 20, 28, 210);
    private static final Color C_ROW = new Color(255, 255, 255, 14);
    private static final Color C_POPUP = new Color(8, 16, 22, 255);
    private static final Color C_BACKDROP = new Color(0, 0, 0, 200);
    private static final Color C_BORDER = new Color(90, 160, 200, 170);
    private static final Color C_GOLD = new Color(255, 215, 0);
    private static final float NINE_FIGURE_SCALE = 1.2f;
    private static final Color[] AI_FX = {
            new Color(170, 222, 255), new Color(100, 225, 255),
            new Color(195, 170, 255), new Color(255, 215, 135)};

    static {
        // 代码内置默认值；JSON 同名槽位逐字段覆盖。坐标相对父槽位（in），y 向下，逻辑屏 1280×800。
        // 第 3 稿：弹窗 1216×704；页签是 repeat 槽位 tab（按可见顺序占位）；编队页 = 顶部横条 + 两列成员卡片；
        // AI 页 = TacP 风格舞台（脉冲圆环 + 立绘 + 引线 + 四个信息签）+ 右侧技能 / 辅助两列。
        Moci_UILayout.registerDefaults(LAYOUT_ID, 1216f, 704f)
                .slot("header", null, 0, 0, 1216, 36)
                .slot("title", "header", 16, 8, 600, 22)
                .slot("notice", "header", 630, 10, 440, 20)
                .slot("closeButton", "header", 1100, 7, 100, 22)
                .repeat("tab", null, 16, 40, 160, 22, 168, 0, 0)
                .slot("body", null, 16, 70, 1184, 618)
                // 编队页：memberList 内宽 = 1184 − 15（滚动条）= 1169 = 577 + 15 + 577，两张卡片正好填满
                .slot("squadBar", "body", 0, 0, 1184, 104)
                .slot("captainPortrait", "squadBar", 12, 12, 80, 80)
                .slot("squadInfo", "squadBar", 104, 10, 420, 84)
                .slot("squadNameField", "squadBar", 540, 10, 300, 28)
                .slot("squadRenameButton", "squadBar", 850, 14, 90, 20)
                .slot("squadStats", "squadBar", 540, 44, 632, 52)
                .slot("memberList", "body", 0, 116, 1184, 502)
                .repeat("memberCard", "memberList", 0, 0, 577, 490, 592, 502, 2)
                .slot("memberPortrait", "memberCard", 16, 16, 180, 180)
                .slot("memberShip", "memberCard", 212, 16, 349, 180)
                .slot("memberShipName", "memberCard", 212, 200, 349, 20)
                .slot("memberInfo", "memberCard", 16, 232, 264, 190)
                .slot("memberSkills", "memberCard", 297, 232, 264, 190)
                .slot("memberActions", "memberCard", 16, 438, 545, 36)
                .repeat("memberAction", "memberActions", 0, 8, 125, 22, 140, 0, 0)
                // 舰载 AI 页
                .slot("aiCard", "body", 0, 0, 760, 618)
                .slot("aiStage", "aiCard", 0, 0, 760, 440)
                .slot("aiBackdrop", "aiStage", 230, 70, 300, 300)
                .slot("aiFigure", "aiStage", 260, 40, 240, 360)
                .slot("aiLines", "aiStage", 0, 0, 760, 440)
                .slot("aiTagName", "aiStage", 16, 60, 220, 44)
                .slot("aiTagLevel", "aiStage", 16, 250, 220, 44)
                .slot("aiTagExp", "aiStage", 524, 60, 220, 44)
                .slot("aiTagPersonality", "aiStage", 524, 250, 220, 44)
                .slot("aiNameField", "aiCard", 16, 456, 300, 28)
                .slot("aiRenameButton", "aiCard", 326, 460, 90, 20)
                .slot("aiPoints", "aiCard", 440, 452, 304, 36)
                .slot("aiEvolve", "aiCard", 16, 500, 728, 102)
                .slot("aiSkills", "body", 776, 0, 200, 618)
                .slot("aiAux", "body", 984, 0, 200, 618)
                // 弹出菜单：外框按 1216×704 居中，内部子槽位不变
                .slot("popupBackdrop", null, 0, 0, 1216, 704)
                .slot("popupPortrait", null, 208, 92, 800, 520)
                .slot("popupPortraitTitle", "popupPortrait", 16, 12, 600, 24)
                .slot("popupPortraitGrid", "popupPortrait", 16, 44, 768, 432)
                .repeat("popupPortraitCell", "popupPortraitGrid", 0, 0, 84, 84, 92, 92, 8)
                .slot("popupPortraitCancel", "popupPortrait", 684, 486, 100, 22)
                .slot("popupElite", null, 308, 152, 600, 400)
                .slot("popupEliteTitle", "popupElite", 16, 12, 568, 24)
                .slot("popupEliteList", "popupElite", 16, 44, 568, 300)
                .repeat("popupEliteRow", "popupEliteList", 0, 0, 552, 40, 0, 46, 0)
                .slot("popupEliteCancel", "popupElite", 484, 366, 100, 22)
                .slot("popupShip", null, 208, 92, 800, 520)
                .slot("popupShipTitle", "popupShip", 16, 12, 600, 24)
                .slot("popupShipGrid", "popupShip", 16, 44, 768, 432)
                .repeat("popupShipCell", "popupShipGrid", 0, 0, 180, 120, 188, 128, 4)
                .slot("popupShipCellImage", "popupShipCell", 8, 8, 64, 64)
                .slot("popupShipCellInfo", "popupShipCell", 80, 8, 92, 104)
                .slot("popupShipRestore", "popupShip", 16, 486, 140, 22)
                .slot("popupShipCancel", "popupShip", 684, 486, 100, 22)
                .slot("popupRename", null, 358, 232, 500, 240)
                .slot("popupRenameTitle", "popupRename", 16, 12, 468, 24)
                .repeat("popupRenameLabel", "popupRename", 16, 48, 100, 20, 0, 40, 0)
                .repeat("popupRenameField", "popupRename", 124, 44, 240, 28, 0, 40, 0)
                .slot("popupRenameOk", "popupRename", 276, 206, 100, 22)
                .slot("popupRenameCancel", "popupRename", 384, 206, 100, 22);
    }

    private Moci_CombatSquadDialog() {
    }

    public static void open(IntelUIAPI ui, IntelInfoPlugin owner, String memberId, Tab tab) {
        open(ui, owner, memberId, tab, null);
    }

    /**
     * 打开弹窗。同步检查失败直接抛 RuntimeException（调用方打日志并退回旧内嵌页）；
     * 弹窗异步初始化失败时关闭弹窗并调用 fallback（同样退回旧页面）。
     */
    public static void open(IntelUIAPI ui, IntelInfoPlugin owner, String memberId, Tab tab, Runnable fallback) {
        if (ui == null || owner == null || memberId == null) {
            throw new IllegalArgumentException("ui/owner/memberId 不能为空");
        }
        FleetMemberAPI ship = Moci_ShipboardAIManagerIntel.findMemberById(memberId);
        if (ship == null) {
            throw new IllegalStateException("舰队里找不到舰船 " + memberId);
        }
        Moci_CombatSquadManagerIntel squad = findSquadIntel(owner);
        boolean squadOk = squad != null && squad.shipHasCombatSquad(ship);
        boolean aiOk = Moci_ShipboardAIConfigLoader.hasShipboardAISystem(ship);
        Tab start = tab == null ? Tab.SQUAD : tab;
        if (start == Tab.SQUAD && !squadOk) start = Tab.AI;
        if (start == Tab.AI && !aiOk) start = squadOk ? Tab.SQUAD : null;
        if (start == null) {
            throw new IllegalStateException("舰船既没有作战小队也没有舰载 AI: " + memberId);
        }
        InteractionDialogAPI existing = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
        SectorEntityToken target = existing == null ? null : existing.getInteractionTarget();
        LOG.info(P + "open member=" + memberId + " tab=" + start + " squad=" + squadOk + " ai=" + aiOk);
        ui.showDialog(target, new Wrapper(ui, owner, squad, memberId, start, fallback));
    }

    private static Moci_CombatSquadManagerIntel findSquadIntel(IntelInfoPlugin owner) {
        if (owner instanceof Moci_CombatSquadManagerIntel) return (Moci_CombatSquadManagerIntel) owner;
        try {
            Object intel = Global.getSector().getIntelManager().getFirstIntel(Moci_CombatSquadManagerIntel.class);
            return intel instanceof Moci_CombatSquadManagerIntel ? (Moci_CombatSquadManagerIntel) intel : null;
        } catch (RuntimeException ex) {
            LOG.warn(P + "查找作战小队情报失败，编队页不可用", ex);
            return null;
        }
    }

    // ============================== 对话框外壳 ==============================

    private static final class Wrapper implements InteractionDialogPlugin {
        private final IntelUIAPI ui;
        private final IntelInfoPlugin owner;
        private final Moci_CombatSquadManagerIntel squad;
        private final String memberId;
        private final Tab startTab;
        private final Runnable fallback;
        private InteractionDialogAPI dialog;
        private Workbench workbench;
        private boolean finished;
        private boolean failed;

        Wrapper(IntelUIAPI ui, IntelInfoPlugin owner, Moci_CombatSquadManagerIntel squad, String memberId,
                Tab startTab, Runnable fallback) {
            this.ui = ui;
            this.owner = owner;
            this.squad = squad;
            this.memberId = memberId;
            this.startTab = startTab;
            this.fallback = fallback;
        }

        @Override
        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            try {
                dialog.hideTextPanel();
                dialog.hideVisualPanel();
                dialog.setOptionOnEscape(null, null);
                Moci_UILayout layout = Moci_UILayout.get(LAYOUT_ID);
                // 第 3 稿：尺寸取布局 dialog{w,h}，屏幕放不下时按屏幕留 16 像素边距夹紧
                float screenW = Global.getSettings().getScreenWidth();
                float screenH = Global.getSettings().getScreenHeight();
                float w = Math.min(layout.dialogW(), screenW - 32f);
                float h = Math.min(layout.dialogH(), screenH - 32f);
                if (w < layout.dialogW() || h < layout.dialogH()) {
                    LOG.warn(P + "屏幕放不下弹窗，已夹紧：布局 " + (int) layout.dialogW() + "×" + (int) layout.dialogH()
                            + " → " + (int) w + "×" + (int) h + "（屏幕 " + (int) screenW + "×" + (int) screenH + "）");
                }
                workbench = new Workbench(this, squad, memberId, startTab);
                dialog.showCustomVisualDialog(w, h, workbench);
            } catch (RuntimeException ex) {
                LOG.error(P + "弹窗创建失败，退回旧页面 member=" + memberId, ex);
                failed = true;
                finish();
            }
        }

        void markFailed() {
            failed = true;
        }

        void finish() {
            if (finished) return;
            finished = true;
            try {
                if (dialog != null) dialog.dismiss();
            } catch (RuntimeException ex) {
                LOG.warn(P + "dismiss 失败 member=" + memberId, ex);
            }
            if (failed && fallback != null) {
                try {
                    fallback.run();
                    return;
                } catch (RuntimeException ex) {
                    LOG.error(P + "退回旧页面失败 member=" + memberId, ex);
                }
            }
            ui.updateUIForItem(owner);
        }

        @Override
        public void optionSelected(String optionText, Object optionData) {
            if (workbench != null) workbench.requestClose();
        }

        @Override
        public void optionMousedOver(String optionText, Object optionData) {
        }

        @Override
        public void advance(float amount) {
        }

        @Override
        public void backFromEngagement(EngagementResultAPI battleResult) {
        }

        @Override
        public Object getContext() {
            return null;
        }

        @Override
        public Map<String, MemoryAPI> getMemoryMap() {
            return Collections.emptyMap();
        }
    }

    /** 按钮携带的动作。modal=true 的动作在弹出菜单打开时仍可执行（弹出菜单自己的按钮）。 */
    static final class Action {
        final String kind;
        final Object a;
        final Object b;
        final int gen;
        final boolean modal;

        Action(String kind, Object a, Object b, int gen, boolean modal) {
            this.kind = kind;
            this.a = a;
            this.b = b;
            this.gen = gen;
            this.modal = modal;
        }

        @Override
        public String toString() {
            Object v = a instanceof FleetMemberAPI ? ((FleetMemberAPI) a).getId() : a;
            return "Action(" + kind + (v == null ? "" : ", " + v) + (b == null ? "" : ", " + b) + ")";
        }
    }

    // ============================== 弹窗本体 ==============================

    static final class Workbench extends BaseCustomUIPanelPlugin implements CustomVisualDialogDelegate {
        private final Wrapper wrapper;
        private final Moci_CombatSquadManagerIntel squad;
        private final String memberId;

        /** 录制器按字段名 tab / popup 生成 screenKey，不要改名。 */
        Tab tab;
        Popup popup;

        private int popupPilot = -1;
        private boolean learnMode;
        private String notice;
        private Color noticeColor;

        private CustomPanelAPI root;
        private DialogCallbacks callbacks;
        private CustomPanelAPI content;
        private Moci_UILayout layout;
        private int gen;
        private Action pending;
        private boolean handling;
        private boolean closing;
        private boolean closeRequested;
        private boolean dismissed;
        private final List<ButtonAPI> buttons = new ArrayList<>();
        private final List<Action> buttonActions = new ArrayList<>();
        private final List<Moci_UIHoverFrame> frames = new ArrayList<>();
        private final Map<String, TextFieldAPI> fields = new HashMap<>();
        private final Moci_AirSquadronArt shipArt = new Moci_AirSquadronArt();

        Workbench(Wrapper wrapper, Moci_CombatSquadManagerIntel squad, String memberId, Tab tab) {
            this.wrapper = wrapper;
            this.squad = squad;
            this.memberId = memberId;
            this.tab = tab;
        }

        @Override
        public void init(CustomPanelAPI panel, DialogCallbacks callbacks) {
            this.root = panel;
            this.callbacks = callbacks;
            try {
                rebuild();
            } catch (RuntimeException ex) {
                LOG.error(P + "首次构建失败，关闭弹窗并退回旧页面 member=" + memberId, ex);
                wrapper.markFailed();
                closeRequested = true;
            }
        }

        @Override
        public CustomUIPanelPlugin getCustomPanelPlugin() {
            return this;
        }

        @Override
        public float getNoiseAlpha() {
            return 0f;
        }

        @Override
        public void reportDismissed(int option) {
            dismissed = true;
            closing = true;
            wrapper.finish();
        }

        void requestClose() {
            closeRequested = true;
        }

        @Override
        public void advance(float amount) {
            if (dismissed) return;
            if (closeRequested) {
                closeDialog();
                return;
            }
            pollButtons();
            final Action action = pending;
            pending = null;
            if (action != null && action.gen == gen) {
                guarded("动作 " + action, () -> handle(action));
            }
        }

        @Override
        public void processInput(List<InputEventAPI> events) {
            if (events == null || closing || dismissed) return;
            for (InputEventAPI e : events) {
                if (e == null || e.isConsumed()) continue;
                if (e.isKeyDownEvent() && e.getEventValue() == Keyboard.KEY_ESCAPE) {
                    // 有弹出菜单时 Esc 只关弹出菜单，没有时关弹窗
                    request(new Action(popup != null ? "popup_cancel" : "close", null, null, gen, true));
                    e.consume();
                    return;
                }
            }
        }

        @Override
        public void buttonPressed(Object buttonId) {
            if (buttonId instanceof Action) request((Action) buttonId);
        }

        private void request(Action a) {
            if (a == null || closing || dismissed || handling || pending != null) return;
            if (a.gen != gen) return;
            if (popup != null && !a.modal) return;
            pending = a;
        }

        /** 游戏里原生按钮点击后处于 checked 状态：轮询取第一个，清掉 checked 再记动作（同 Moci_AirSquadronDialog）。 */
        private void pollButtons() {
            for (int i = 0; i < buttons.size(); i++) {
                ButtonAPI b = buttons.get(i);
                if (b == null || !b.isChecked()) continue;
                b.setChecked(false);
                if (b.isEnabled()) request(buttonActions.get(i));
            }
        }

        private void guarded(String step, Runnable r) {
            handling = true;
            try {
                r.run();
            } catch (RuntimeException ex) {
                LOG.error(P + step + " 失败 member=" + memberId + " tab=" + tab + " popup=" + popup, ex);
                popup = null;
                notice = csText("dialog_action_error", "操作出错，详情见日志");
                noticeColor = Misc.getNegativeHighlightColor();
                try {
                    rebuild();
                } catch (RuntimeException again) {
                    LOG.error(P + "重建失败，关闭弹窗 member=" + memberId, again);
                    closeDialog();
                }
            } finally {
                handling = false;
            }
        }

        private void closeDialog() {
            if (dismissed) return;
            dismissed = true;
            closing = true;
            try {
                if (callbacks != null) callbacks.dismissDialog();
            } catch (RuntimeException ex) {
                LOG.warn(P + "dismissDialog 失败 member=" + memberId, ex);
            }
            wrapper.finish();
        }

        // ------------------------------ 动作处理 ------------------------------

        private void handle(Action x) {
            FleetMemberAPI ship = Moci_ShipboardAIManagerIntel.findMemberById(memberId);
            PersonAPI leader = ship == null ? null : ship.getCaptain();
            String cid = leader == null ? null : leader.getId();
            LOG.info(P + "处理 " + x + " member=" + memberId + " tab=" + tab + " popup=" + popup + " pilot=" + popupPilot);
            switch (x.kind) {
                case "close":
                    closeDialog();
                    return;
                case "tab":
                    tab = (Tab) x.a;
                    popup = null;
                    notice = null;
                    break;
                case "popup_cancel":
                    popup = null;
                    break;
                case "portrait_open":
                    openPopup(Popup.PORTRAIT, (Integer) x.a, false);
                    break;
                case "rename_open":
                    openPopup(Popup.RENAME, (Integer) x.a, false);
                    break;
                case "ship_open":
                    openPopup(Popup.SHIP, (Integer) x.a, false);
                    break;
                case "upgrade_open":
                    openPopup(Popup.ELITE, (Integer) x.a, true);
                    break;
                case "elite_open":
                    openPopup(Popup.ELITE, (Integer) x.a, false);
                    break;
                case "portrait_pick":
                    if (canSquadOp(cid)) result(squad.applyPilotPortrait(cid, popupPilot, (String) x.a));
                    popup = null;
                    break;
                case "learn_pick":
                    if (canSquadOp(cid)) result(squad.applyLearnSkill(cid, popupPilot, (String) x.a));
                    popup = null;
                    break;
                case "upgrade_elite_pick":
                    if (canSquadOp(cid)) result(squad.applyUpgradeEliteSkill(cid, popupPilot, (String) x.a));
                    popup = null;
                    break;
                case "elite_pick":
                    if (canSquadOp(cid)) result(squad.applyEliteOnly(cid, popupPilot, (String) x.a));
                    popup = null;
                    break;
                case "ship_pick":
                    if (canSquadOp(cid)) result(squad.applyAssignShip(cid, popupPilot, (FleetMemberAPI) x.a));
                    popup = null;
                    break;
                case "ship_restore":
                    if (canSquadOp(cid)) result(squad.applyRestoreShip(cid, popupPilot));
                    popup = null;
                    break;
                case "rename_ok":
                    if (canSquadOp(cid)) {
                        boolean nameOk = squad.applyPilotName(cid, popupPilot, fieldText("last"), fieldText("first"));
                        boolean signOk = squad.applyPilotCallsign(leader, popupPilot, fieldText("callsign"));
                        result(nameOk || signOk);
                    }
                    popup = null;
                    break;
                case "squad_rename":
                    if (squad != null && leader != null) result(squad.applySquadName(leader, fieldText("squadName")));
                    else result(false);
                    break;
                case "ai_learn":
                    aiAction(ship, "learn", (String) x.a);
                    break;
                case "ai_elite":
                    aiAction(ship, "elite", (String) x.a);
                    break;
                case "ai_aux":
                    aiAction(ship, "aux", (String) x.a);
                    break;
                case "ai_evolve":
                    aiAction(ship, "evolve", (String) x.a);
                    break;
                case "ai_rename":
                    aiAction(ship, "rename", fieldText("aiName"));
                    break;
                default:
                    LOG.warn(P + "未处理的动作 " + x);
                    break;
            }
            rebuild();
        }

        private void openPopup(Popup p, Integer pilot, boolean learn) {
            popup = p;
            popupPilot = pilot == null ? -1 : pilot;
            learnMode = learn;
            notice = null;
        }

        private boolean canSquadOp(String cid) {
            if (squad != null && cid != null && popupPilot >= 0) return true;
            LOG.warn(P + "编队操作缺少上下文 member=" + memberId + " cid=" + cid + " pilot=" + popupPilot);
            result(false);
            return false;
        }

        private void aiAction(FleetMemberAPI ship, String action, String value) {
            if (ship == null) {
                result(false);
                return;
            }
            result(Moci_ShipboardAIManagerIntel.performAIAction(action, ship, value));
        }

        private void result(boolean ok) {
            notice = ok ? csText("dialog_action_done", "操作已完成") : csText("dialog_action_failed", "操作未生效");
            noticeColor = ok ? Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor();
        }

        private String fieldText(String key) {
            TextFieldAPI f = fields.get(key);
            String s = f == null ? null : f.getText();
            return s == null ? "" : s.trim();
        }

        // ------------------------------ 构建 ------------------------------

        private void rebuild() {
            gen++;
            buttons.clear();
            buttonActions.clear();
            frames.clear();
            fields.clear();
            layout = Moci_UILayout.get(LAYOUT_ID);
            if (content != null) root.removeComponent(content);
            PositionAPI rp = root.getPosition();
            content = root.createCustomPanel(rp.getWidth(), rp.getHeight(), null);
            root.addComponent(content).inTL(0f, 0f);

            FleetMemberAPI ship = Moci_ShipboardAIManagerIntel.findMemberById(memberId);
            boolean squadOk = ship != null && squad != null && squad.shipHasCombatSquad(ship);
            boolean aiOk = ship != null && Moci_ShipboardAIConfigLoader.hasShipboardAISystem(ship);
            if (tab == Tab.SQUAD && !squadOk && aiOk) tab = Tab.AI;
            if (tab == Tab.AI && !aiOk && squadOk) tab = Tab.SQUAD;
            if (ship == null) {
                popup = null;
                notice = csText("detail_missing_ship", "目标舰船不存在，可能已离开舰队。");
                noticeColor = Misc.getNegativeHighlightColor();
            }
            PersonAPI leader = ship == null ? null : ship.getCaptain();
            if (popup != null && (squad == null || leader == null || popupPilot < 0)) popup = null;

            buildHeader(ship);
            buildTabs(squadOk, aiOk);
            CustomPanelAPI body = panel(content, "body", 0, null);
            if (ship != null) {
                if (tab == Tab.SQUAD && squadOk) buildSquad(body, ship);
                else if (tab == Tab.AI && aiOk) buildAI(body, ship);
            }
            if (popup != null) {
                // 模态：底下的按钮和悬停框全部禁用，弹出菜单自己的按钮在这之后创建，不受影响
                for (ButtonAPI b : buttons) b.setEnabled(false);
                for (Moci_UIHoverFrame f : frames) f.setEnabled(false);
                panel(content, "popupBackdrop", 0, new Fill(C_BACKDROP, null, this));
                switch (popup) {
                    case PORTRAIT:
                        buildPortraitPopup(leader);
                        break;
                    case ELITE:
                        buildElitePopup(leader);
                        break;
                    case SHIP:
                        buildShipPopup(leader);
                        break;
                    case RENAME:
                        buildRenamePopup(leader);
                        break;
                    default:
                        break;
                }
            }
        }

        private void buildHeader(FleetMemberAPI ship) {
            CustomPanelAPI header = panel(content, "header", 0, new Fill(C_CARD, null, this));
            String unnamed = csText("detail_unnamed_ship", "未命名舰船");
            String shipName = ship == null ? unnamed : safe(ship.getShipName(), unnamed);
            TooltipMakerAPI t = text(header, "title", 0);
            t.addPara(fit(csFormat("dialog_title", "作战小队管理 · {name}", "name", shipName), width("title", 0)),
                    Misc.getHighlightColor(), 0f);
            place(header, t, "title", 0);
            if (notice != null && !notice.isEmpty()) {
                TooltipMakerAPI n = text(header, "notice", 0);
                n.addPara(fit(notice, width("notice", 0)), noticeColor == null ? Misc.getTextColor() : noticeColor, 0f);
                place(header, n, "notice", 0);
            }
            button(header, "closeButton", 0, csText("dialog_close", "关闭"), act("close", null, null, false));
        }

        /** 页签：repeat 槽位 tab，按可见顺序占位（有编队页时 编队、AI；只有 AI 页时 AI 排第一个）。 */
        private void buildTabs(boolean squadOk, boolean aiOk) {
            int k = 0;
            if (squadOk) {
                ButtonAPI b = button(content, "tab", k++, csText("dialog_tab_squad", "编队"), act("tab", Tab.SQUAD, null, false));
                if (tab == Tab.SQUAD) b.highlight();
            }
            if (aiOk) {
                ButtonAPI b = button(content, "tab", k, csText("dialog_tab_ai", "舰载 AI"), act("tab", Tab.AI, null, false));
                if (tab == Tab.AI) b.highlight();
            }
        }

        // ------------------------------ 编队页 ------------------------------

        /** 编队页：顶部横条 squadBar（舰长小头像 + 舰船/舰长/编队信息 + 编队改名 + 统计）+ 下方两列成员卡片。 */
        private void buildSquad(CustomPanelAPI body, FleetMemberAPI ship) {
            PersonAPI leader = ship.getCaptain();
            boolean hasLeader = leader != null && !leader.isDefault();
            boolean established = hasLeader && squad.squadEstablished(leader);
            CustomPanelAPI bar = panel(body, "squadBar", 0, new Fill(C_CARD, C_BORDER, this));
            image(bar, "captainPortrait", 0, hasLeader ? leader.getPortraitSprite() : null);

            TooltipMakerAPI info = text(bar, "squadInfo", 0);
            float iw = width("squadInfo", 0);
            String shipName = safe(ship.getShipName(), csText("detail_unnamed_ship", "未命名舰船"));
            info.addPara(fit(shipName + " · " + ship.getHullSpec().getHullName(), iw), Misc.getHighlightColor(), 0f);
            if (hasLeader) {
                info.addPara(fit(csText("dialog_captain", "舰长：") + leader.getNameString() + " · " + safe(leader.getRank(), ""), iw),
                        Misc.getTextColor(), 4f);
            }
            if (established) {
                String cid = leader.getId();
                String squadName = safe(Moci_PilotManager.getSquadName(cid, true), csText("dialog_unnamed_squad", "未命名编队"));
                info.addPara(fit(csText("dialog_squad_name", "编队：") + squadName, iw), Misc.getTextColor(), 4f);
                info.addPara(fit(csFormat("dialog_squad_size", "机师人数：{count}", "count",
                        String.valueOf(Moci_PilotManager.getSquadSize(cid))), iw), Misc.getTextColor(), 4f);
            } else {
                info.addPara(fit(csText("detail_no_squad", "该舰船当前未成立小队。"), iw), Misc.getNegativeHighlightColor(), 4f);
            }
            place(bar, info, "squadInfo", 0);

            if (!established) {
                TooltipMakerAPI hint = text(bar, "squadStats", 0);
                hint.addPara(csFormat("detail_no_squad_hint", "请先安装 {mod} 并分配非默认军官，随后在战役中完成小队初始化。",
                        "mod", csText("detail_no_squad_mod", "CombatSquad 船插")), Misc.getGrayColor(), 0f);
                place(bar, hint, "squadStats", 0);
                return;
            }

            String cid = leader.getId();
            field(bar, "squadNameField", 0, "squadName", safe(Moci_PilotManager.getSquadName(cid, true), ""));
            button(bar, "squadRenameButton", 0, csText("dialog_squad_rename", "重命名"), act("squad_rename", null, null, false));

            int n = Math.max(0, Moci_PilotManager.getSquadSize(cid));
            int canUp = 0;
            int canEl = 0;
            for (int i = 0; i < n; i++) {
                PersonAPI p = Moci_PilotManager.getPilot(cid, i, true);
                if (p == null) continue;
                if (canUpgrade(cid, i, p)) canUp++;
                if (canElite(p)) canEl++;
            }
            // 统计压成两行：第一行两个计数，第二行灰色操作提示（squadStats 只有 52 高）
            TooltipMakerAPI stats = text(bar, "squadStats", 0);
            float sw = width("squadStats", 0);
            String counts = csFormat("dialog_stats_upgrade", "可升级机师：{count}", "count", String.valueOf(canUp)) + "　　"
                    + csFormat("dialog_stats_elite", "可精英化机师：{count}", "count", String.valueOf(canEl));
            stats.addPara(fit(counts, sw), canUp + canEl > 0 ? Misc.getPositiveHighlightColor() : Misc.getTextColor(), 0f);
            stats.addPara(fit(csText("dialog_stats_hint", "点击机师头像可更换头像；卡片下方按钮可改名、指派舰船、升级与精英化。"), sw),
                    Misc.getGrayColor(), 6f);
            place(bar, stats, "squadStats", 0);

            CustomPanelAPI list = scrollArea(body, "memberList", "memberCard", n);
            for (int i = 0; i < n; i++) buildMemberCard(list, leader, i);
        }

        /**
         * 成员卡片（repeat 槽位 memberCard，cols 2）：左上可点头像（扫描框 + 悬停框），右上僚机机体图（按比例放进框）+ 舰名，
         * 中部机师信息 / 技能两列，底部按钮横排（repeat 槽位 memberAction）。
         */
        private void buildMemberCard(CustomPanelAPI list, PersonAPI leader, int i) {
            String cid = leader.getId();
            CustomPanelAPI card = panel(list, "memberCard", i, new Fill(C_CARD, C_BORDER, this));
            PersonAPI pilot = Moci_PilotManager.getPilot(cid, i, true);

            List<FleetMemberAPI> ships = safeList(squad.wingmanDisplayShips(leader, i));
            shipSprite(card, "memberShip", i, ships.isEmpty() ? null : ships.get(0));
            boolean custom = squad.wingmanIsCustom(leader, i);
            boolean over = custom && squad.wingmanDpExceeded(leader, i);
            String shipName = over ? csText("ship_dp_exceeded", "DP超出！")
                    : safe(squad.wingmanDisplayName(leader, i), csText("ship_name_unknown", "未知舰船"));
            TooltipMakerAPI sn = text(card, "memberShipName", i);
            LabelAPI snl = sn.addPara(fit(shipName, width("memberShipName", i)),
                    over ? Misc.getNegativeHighlightColor() : custom ? Misc.getHighlightColor() : Misc.getGrayColor(), 0f);
            snl.setAlignment(Alignment.MID);
            place(card, sn, "memberShipName", i);

            if (pilot == null) {
                TooltipMakerAPI miss = text(card, "memberInfo", i);
                miss.addPara(csText("msg_missing_pilot", "错误：无法找到机师信息"), Misc.getNegativeHighlightColor(), 0f);
                place(card, miss, "memberInfo", i);
                return;
            }

            // 头像容器的插件是扫描框：网格画在头像下面，扫描线 / 角标画在头像上面；悬停框照旧最后加，叠在最上层
            clickImage(card, "memberPortrait", i, pilot.getPortraitSprite(), act("portrait_open", i, null, false),
                    new Moci_UIScanFrame(Misc.getBasePlayerColor()));

            int level = pilot.getStats().getLevel();
            float exp = Moci_PilotManager.getExperience(cid, i, true);
            float next = squad.experienceForLevel(level + 1);
            boolean up = canUpgrade(cid, i, pilot);
            TooltipMakerAPI info = text(card, "memberInfo", i);
            float iw = width("memberInfo", i);
            info.setParaInsigniaLarge();
            // 大字号拉丁字母比 fit 的估算宽，留 20% 余量
            info.addPara(fit(pilot.getNameString(), iw * 0.8f), Misc.getHighlightColor(), 0f);
            info.setParaFontDefault();
            info.addPara(fit(csText("dialog_callsign", "呼号：") + callsignOf(cid, i), iw), Misc.getTextColor(), 6f);
            info.addPara(fit(csFormat("dialog_level", "等级 {level}", "level", String.valueOf(level)), iw), Misc.getTextColor(), 4f);
            String expText = level >= MAX_LEVEL ? csText("dialog_exp_max", "经验 已满级")
                    : csFormat("dialog_exp", "经验 {exp}/{next}", "exp", String.valueOf((int) exp), "next", String.valueOf((int) next));
            info.addPara(fit(expText, iw), up ? Misc.getPositiveHighlightColor() : Misc.getGrayColor(), 4f);
            place(card, info, "memberInfo", i);

            int elite = Misc.getNumEliteSkills(pilot);
            int maxElite = Moci_PilotTraining.calculatePilotMaxEliteSkills(pilot);
            Moci_UILayout.Rect kr = layout.rect("memberSkills", i);
            TooltipMakerAPI sk = text(card, "memberSkills", i);
            float kw = width("memberSkills", i);
            sk.addPara(fit(csFormat("dialog_elite_count", "精英 {count}/{max}", "count", String.valueOf(elite), "max",
                    String.valueOf(maxElite)), kw), elite >= maxElite ? Misc.getPositiveHighlightColor() : Misc.getHighlightColor(), 0f);
            List<String> names = skillNames(pilot);
            if (names.isEmpty()) {
                sk.addPara(fit(csText("dialog_no_skills", "尚未学习技能"), kw), Misc.getGrayColor(), 6f);
            } else {
                // 一行一个技能，行数按槽位高度算（首行 15 + 间距 6 + 每行 15，之后每行 4 + 15）；放不下时最后一行改成「等 N 项」
                int maxLines = Math.max(1, (int) ((kr.h - 17f) / 19f));
                int shown = names.size() <= maxLines ? names.size() : maxLines - 1;
                for (int s = 0; s < shown; s++) sk.addPara(fit(names.get(s), kw), Misc.getTextColor(), s == 0 ? 6f : 4f);
                if (shown < names.size()) {
                    sk.addPara(fit(csFormat("dialog_more_skills", "等 {count} 项", "count", String.valueOf(names.size() - shown)), kw),
                            Misc.getGrayColor(), shown == 0 ? 6f : 4f);
                }
            }
            place(card, sk, "memberSkills", i);

            CustomPanelAPI acts = panel(card, "memberActions", i, null);
            int k = 0;
            button(acts, "memberAction", k++, csText("dialog_btn_rename", "改名"), act("rename_open", i, null, false));
            button(acts, "memberAction", k++, csText("dialog_btn_assign", "指派舰船"), act("ship_open", i, null, false));
            if (up) button(acts, "memberAction", k++, csText("dialog_btn_upgrade", "升级"), act("upgrade_open", i, null, false));
            if (canElite(pilot)) button(acts, "memberAction", k, csText("dialog_btn_elite", "精英化"), act("elite_open", i, null, false));
        }

        /** 已学技能名（精英技能带「(精)」）；跳过未学和天赋类技能（与旧页面技能摘要的规则相同）。 */
        private List<String> skillNames(PersonAPI pilot) {
            List<String> out = new ArrayList<>();
            for (MutableCharacterStatsAPI.SkillLevelAPI s : pilot.getStats().getSkillsCopy()) {
                if (s == null || s.getSkill() == null || s.getLevel() <= 0f || s.getSkill().isAptitudeEffect()) continue;
                out.add(s.getSkill().getName() + (s.getLevel() >= 2f ? csText("dialog_elite_mark", "(精)") : ""));
            }
            return out;
        }

        /** 按船体、装饰武器、内置武器与模块的完整外缘等比居中，扫描框叠在模型上。 */
        private void shipSprite(CustomPanelAPI parent, String slot, int index, FleetMemberAPI ship) {
            Moci_UILayout.Rect r = layout.rect(slot, index);
            CustomPanelAPI box = parent.createCustomPanel(r.w, r.h, new Moci_UIScanFrame(Misc.getBasePlayerColor()));
            layout.place(parent.addComponent(box), slot, index);
            if (ship != null) shipArt.buildShipPreview(ship.getVariant()).addToPanel(box, r.w, r.h, 0.8f);
        }

        /** 按贴图原始宽高等比放进 maxW×maxH，放大倍数不超过 maxUp；取不到尺寸时退回边长 min(maxW,maxH) 的正方形并警告。 */
        private float[] fitSize(String sprite, float maxW, float maxH, float maxUp) {
            float sw = 0f;
            float sh = 0f;
            try {
                SpriteAPI s = Global.getSettings().getSprite(sprite);
                if (s != null) {
                    sw = s.getWidth();
                    sh = s.getHeight();
                }
            } catch (RuntimeException ex) {
                LOG.warn(P + "读取贴图尺寸失败 sprite=" + sprite, ex);
            }
            if (sw <= 0f || sh <= 0f) {
                LOG.warn(P + "贴图尺寸未知，按正方形放置 sprite=" + sprite);
                float side = Math.max(8f, Math.min(maxW, maxH));
                return new float[]{side, side};
            }
            float scale = Math.min(maxUp, Math.min(maxW / sw, maxH / sh));
            return new float[]{Math.max(8f, sw * scale), Math.max(8f, sh * scale)};
        }

        private boolean canUpgrade(String cid, int idx, PersonAPI pilot) {
            int level = pilot.getStats().getLevel();
            return level < MAX_LEVEL && Moci_PilotManager.getExperience(cid, idx, true) >= squad.experienceForLevel(level + 1);
        }

        /** 与旧页面 createSquadView 的「精英化」按钮条件一致：精英数未满就显示；没有可选技能时弹出菜单里说明。 */
        private boolean canElite(PersonAPI pilot) {
            return Misc.getNumEliteSkills(pilot) < Moci_PilotTraining.calculatePilotMaxEliteSkills(pilot);
        }

        // ------------------------------ 舰载 AI 页 ------------------------------

        /**
         * 舰载 AI 页（TacP 风格）：aiCard 里的舞台 aiStage 以立绘为中心画脉冲圆环，立绘左右边缘引线连到四个信息签；
         * 舞台下方是改名 / 可用点数 / 进化；右侧技能、辅助两列各自滚动。
         */
        private void buildAI(CustomPanelAPI body, FleetMemberAPI ship) {
            Moci_ShipboardAIManager.ShipboardAIData data = Moci_ShipboardAIManager.getOrCreateData(ship);
            Moci_ShipboardAIConfigLoader.AITypeDefinition type = data == null ? null
                    : Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
            CustomPanelAPI card = panel(body, "aiCard", 0, new Fill(C_CARD, C_BORDER, this));
            if (data == null || type == null) {
                TooltipMakerAPI t = text(card, "aiEvolve", 0);
                t.addPara(data == null ? aiText("intel_missing_data", "舰载AI数据缺失") : aiText("intel_missing_type", "舰载AI类型缺失"),
                        Misc.getNegativeHighlightColor(), 0f);
                place(card, t, "aiEvolve", 0);
                return;
            }
            String displayName = safe(data.customName, type.name);
            int level = Moci_ShipboardAIManager.getCurrentLevel(data);
            int cap = Moci_ShipboardAIManager.getLevelCap(data);
            float exp = Moci_ShipboardAIManager.getCurrentExp(data);
            float next = Moci_ShipboardAIManager.getNextLevelExp(data);
            String typeName = safe(type.name, "");
            // 自定义名时在名称后补类型名
            String nameValue = typeName.isEmpty() || typeName.equals(displayName) ? displayName : displayName + " · " + typeName;
            String expValue = level >= cap ? aiText("dialog_ai_exp_full", "已满级") : (int) exp + " / " + (int) next;
            String personality = safe(Moci_ShipboardAIManager.getPersonalityLabel(type.personality), "-");
            buildAIStage(card, data.currentAiTypeId, level, type, new String[][]{
                    {"aiTagName", aiText("dialog_ai_tag_name", "名称"), nameValue},
                    {"aiTagLevel", aiText("dialog_ai_tag_level", "等级"), level + " / " + cap},
                    {"aiTagExp", aiText("dialog_ai_tag_exp", "经验"), expValue},
                    {"aiTagPersonality", aiText("dialog_ai_tag_personality", "性格"), personality}});

            if (type.canRename) {
                field(card, "aiNameField", 0, "aiName", displayName);
                button(card, "aiRenameButton", 0, aiText("intel_button_rename", "修改名称"), act("ai_rename", null, null, false));
            }

            int skillPicks = Moci_ShipboardAIManager.getAvailableSkillPickCount(data, type);
            int elitePicks = Moci_ShipboardAIManager.getAvailableElitePickCount(data, type);
            int auxPicks = Moci_ShipboardAIManager.getAvailableAuxPickCount(data, type);
            // 可用点数拆成两行（灰色标题 + 三个数），aiPoints 只有 304 宽
            TooltipMakerAPI pts = text(card, "aiPoints", 0);
            float pw = width("aiPoints", 0);
            pts.addPara(fit(aiText("dialog_ai_points_title", "可用点数"), pw), Misc.getGrayColor(), 0f);
            pts.addPara(fit(aiFormat("dialog_ai_points", "技能 {normal} / 精英 {elite} / 辅助 {aux}", "normal",
                    String.valueOf(skillPicks), "elite", String.valueOf(elitePicks), "aux", String.valueOf(auxPicks)), pw),
                    Misc.getHighlightColor(), 2f);
            place(card, pts, "aiPoints", 0);

            List<String> evolutions = safeList(Moci_ShipboardAIManager.getAvailableEvolutionOptions(ship));
            Moci_UILayout.Rect er = layout.rect("aiEvolve");
            TooltipMakerAPI ev = text(card, "aiEvolve", 0);
            float ew = width("aiEvolve", 0);
            ev.addPara(evolutions.isEmpty() ? aiText("dialog_ai_evolution", "进化")
                    : aiText("intel_evolution_options", "可选进化路线"), Misc.getHighlightColor(), 0f);
            if (!evolutions.isEmpty()) {
                // 标题 15 + 每个按钮（间距 4 + 高 22），按槽位高度决定最多放几个
                int limit = Math.min(MAX_EVOLUTIONS, Math.max(1, (int) ((er.h - 15f) / 26f)));
                if (evolutions.size() > limit) {
                    LOG.warn(P + "进化选项 " + evolutions.size() + " 个，aiEvolve 只放得下 " + limit + " 个 member=" + memberId);
                }
                int shown = 0;
                for (String targetId : evolutions) {
                    if (shown++ >= limit) break;
                    Moci_ShipboardAIConfigLoader.AITypeDefinition t = Moci_ShipboardAIConfigLoader.getAiType(targetId);
                    String name = t == null ? targetId : t.name;
                    Action a = act("ai_evolve", targetId, null, false);
                    ButtonAPI b = ev.addButton(fit(aiFormat("intel_evolution_button", "进化为 {name}", "name", name), ew - 16f),
                            a, ew, 22f, 4f);
                    track(b, a);
                }
            } else if (type.hasEvolutionRoute) {
                ev.addPara(fit(aiFormat("intel_evolution_requirement", "进化条件：达到等级 {level}", "level",
                        String.valueOf(type.evolutionLevel)), ew), Misc.getGrayColor(), 4f);
            } else {
                ev.addPara(fit(aiText("dialog_ai_no_evolution", "没有进化路线"), ew), Misc.getGrayColor(), 4f);
            }
            place(card, ev, "aiEvolve", 0);

            Moci_UILayout.Rect sr = layout.rect("aiSkills");
            TooltipMakerAPI sk = body.createUIElement(sr.w, sr.h, true);
            sk.addSectionHeading(aiText("intel_section_skills", "技能"), Alignment.MID, 0f);
            if (data.learnedSkills.isEmpty()) sk.addPara(aiText("dialog_ai_no_skills", "尚未学习技能"), Misc.getGrayColor(), 6f);
            for (String id : data.learnedSkills) {
                boolean elite = data.eliteSkills.contains(id);
                sk.addPara(Moci_ShipboardAIManagerIntel.getSkillName(id) + (elite ? aiText("tooltip_elite_suffix", " [精英]") : ""),
                        elite ? Misc.getPositiveHighlightColor() : C_GOLD, 4f);
            }
            if (skillPicks > 0) {
                aiButtons(sk, "ai_learn", aiFormat("intel_available_skill_heading", "可学习技能（剩余 {count} 次）", "count",
                        String.valueOf(skillPicks)), Moci_ShipboardAIManager.getAvailableSkillOptions(ship), sr.w - 25f);
            }
            if (elitePicks > 0) {
                aiButtons(sk, "ai_elite", aiFormat("intel_available_elite_heading", "可精英化技能（剩余 {count} 次）", "count",
                        String.valueOf(elitePicks)), Moci_ShipboardAIManager.getAvailableEliteSkillOptions(ship), sr.w - 25f);
            }
            place(body, sk, "aiSkills", 0);

            Moci_UILayout.Rect ar = layout.rect("aiAux");
            TooltipMakerAPI ax = body.createUIElement(ar.w, ar.h, true);
            ax.addSectionHeading(aiText("intel_section_aux", "辅助效果"), Alignment.MID, 0f);
            if (data.learnedAuxSkills.isEmpty()) ax.addPara(aiText("dialog_ai_no_aux", "尚未学习辅助效果"), Misc.getGrayColor(), 6f);
            for (String id : data.learnedAuxSkills) {
                float lvl = data.eliteSkills.contains(id) ? 2f : 1f;
                ax.addPara(Moci_ShipboardAIManagerIntel.getAuxEffectDisplayName(id), Misc.getPositiveHighlightColor(), 4f);
                String desc = Moci_ShipboardAIAuxEffectRegistry.getEffectDescription(id, lvl);
                if (desc != null && !desc.isEmpty()) ax.addPara(desc, Misc.getTextColor(), 2f);
            }
            if (auxPicks > 0) {
                aiButtons(ax, "ai_aux", aiFormat("intel_available_aux_heading", "可学习辅助效果（剩余 {count} 次）", "count",
                        String.valueOf(auxPicks)), Moci_ShipboardAIManager.getAvailableAuxOptions(ship), ar.w - 25f);
            }
            if (skillPicks <= 0 && elitePicks <= 0 && auxPicks <= 0 && evolutions.isEmpty()) {
                ax.addPara(aiText("intel_no_actions", "当前暂无可执行操作。"), Misc.getGrayColor(), 10f);
            }
            place(body, ax, "aiAux", 0);
        }

        /**
         * 舞台：脉冲圆环（aiStage 的插件，renderBelow 画在所有子组件下面，圆心 = aiFigure 中心）→ 立绘 → 引线 → 四个信息签，
         * 按添加顺序从下往上画；引线在立绘之后加，起点圆点不会被立绘盖住。
         * 圆心、半径、引线端点全部从槽位 rect 算；aiFigure / 四个签 / aiLines 都以 aiStage 为父槽位。
         */
        private void buildAIStage(CustomPanelAPI card, String aiTypeId, int level, Moci_ShipboardAIConfigLoader.AITypeDefinition type,
                                  String[][] tags) {
            boolean nine = "Moci_Nine".equals(aiTypeId);
            int grade = level >= 10 ? 3 : level >= 7 ? 2 : level >= 4 ? 1 : 0;
            Color fx = AI_FX[grade];
            float figureScale = nine ? NINE_FIGURE_SCALE : 1f;
            Moci_UILayout.Rect sr = layout.rect("aiStage");
            Moci_UILayout.Rect fr = layout.rect("aiFigure");
            float cx = fr.x + fr.w / 2f;
            float cy = fr.y + fr.h / 2f;
            float inner = Math.min(fr.w, fr.h) * figureScale / 2f + 8f;
            float outer = Math.max(inner + 8f, Math.min(Math.min(cx, sr.w - cx), Math.min(cy, sr.h - cy)) - 4f);
            CustomPanelAPI stage = panel(card, "aiStage", 0, new Moci_UIPulseRings(fx)
                    .setCenter(cx, cy).setRadius(inner, outer).setDetail(grade + 1));
            Moci_ShipboardAIConfigLoader.IllustrationSpec art = illustrationOf(aiTypeId, level);
            if (art != null && art.hasBackdrop()) aiBackdrop(stage, art);
            aiFigure(stage, art, type, figureScale);

            Moci_UILayout.Rect lr = layout.rect("aiLines");
            Moci_UILeaderLines lines = new Moci_UILeaderLines(fx);
            for (String[] tg : tags) {
                Moci_UILayout.Rect tr = layout.rect(tg[0]);
                // 签在立绘左边：立绘左缘 → 签右缘；在右边：立绘右缘 → 签左缘。
                // 起点纵坐标 = 签中线（夹进立绘范围），终点落在签下沿外 2px：斜线段之后的短横线就成了签的下划线，不划过文字
                float tagY = tr.y + tr.h / 2f;
                float figY = Math.max(fr.y, Math.min(fr.y + fr.h, tagY));
                float endY = tr.y + tr.h + 2f;
                boolean left = tr.x + tr.w / 2f < cx;
                float x1 = left ? fr.x : fr.x + fr.w;
                float x2 = left ? tr.x + tr.w : tr.x;
                lines.addLine(x1 - lr.x, figY - lr.y, x2 - lr.x, endY - lr.y);
            }
            panel(stage, "aiLines", 0, lines);
            for (String[] tg : tags) aiTag(stage, tg[0], tg[1], tg[2], cx);
        }

        /**
         * 立绘：有插画时按原始宽高比放进框里居中；没有就用 AI 头像，正方形边长 min(w,h) 居中。
         * 配了序列帧时，aiFigure 容器的插件按帧播放动画，画在静态图的位置上并在游戏里隐藏静态图；
         * 录制器不调 renderBelow，反馈页面上看到的是静态首帧。
         */
        private void aiFigure(CustomPanelAPI stage, Moci_ShipboardAIConfigLoader.IllustrationSpec art,
                              Moci_ShipboardAIConfigLoader.AITypeDefinition type, float scale) {
            Moci_UILayout.Rect r = layout.rect("aiFigure");
            Moci_UIAnimatedSprite anim = art != null && art.hasSheet()
                    ? Moci_UIAnimatedSprite.sheet(art.sheet, art.cols, art.rows, art.frames, art.fps) : null;
            CustomPanelAPI box = panel(stage, "aiFigure", 0, anim);
            if (scale != 1f) {
                box.getPosition().setSize(r.w * scale, r.h * scale)
                        .inTL(r.x + r.w * (1f - scale) / 2f, r.y + r.h * (1f - scale) / 2f);
            }
            String sprite = art != null ? art.image : type.portrait;
            if (sprite == null || sprite.isEmpty()) return;
            float[] wh;
            if (art != null) {
                wh = fitSize(sprite, r.w, r.h, 8f);
            } else {
                float side = Math.min(r.w, r.h);
                wh = new float[]{side, side};
            }
            wh[0] *= scale;
            wh[1] *= scale;
            TooltipMakerAPI t = box.createUIElement(wh[0], wh[1], false);
            t.addImage(sprite, wh[0], wh[1], 0f);
            UIComponentAPI still = t.getPrev();
            pin(still);
            box.addUIElement(t).inTL((r.w * scale - wh[0]) / 2f, (r.h * scale - wh[1]) / 2f);
            if (anim != null) anim.follow(still);
        }

        /**
         * 立绘背后的旋转背景（TacP 齿轮环）：画在脉冲圆环之上、立绘和引线之下；aiBackdrop 是独立槽位，可以单独拖动 / 改大小。
         * 颜色和透明度已烤进贴图（反馈页面上的静态图和游戏里一样），这里只乘配置里的 backdropAlpha 并旋转。
         */
        private void aiBackdrop(CustomPanelAPI stage, Moci_ShipboardAIConfigLoader.IllustrationSpec art) {
            Moci_UILayout.Rect r = layout.rect("aiBackdrop");
            Moci_UIAnimatedSprite spin = Moci_UIAnimatedSprite.spin(art.backdrop, art.backdropSpin)
                    .setAlpha(art.backdropAlpha);
            CustomPanelAPI box = panel(stage, "aiBackdrop", 0, spin);
            float[] wh = fitSize(art.backdrop, r.w, r.h, 4f);
            TooltipMakerAPI t = box.createUIElement(wh[0], wh[1], false);
            t.addImage(art.backdrop, wh[0], wh[1], 0f);
            UIComponentAPI still = t.getPrev();
            pin(still);
            box.addUIElement(t).inTL((r.w - wh[0]) / 2f, (r.h - wh[1]) / 2f);
            spin.follow(still);
        }

        /**
         * 立绘配置（Moci_ShipboardAIConfigLoader.getIllustrationSpec 已负责 loadTexture，失败返回 null）；null 时改用头像。
         * 配置里按等级分了阶段的（例如奈因 7 级初始、8–11 级阶段 1–4），按当前 AI 等级取对应阶段。
         */
        private Moci_ShipboardAIConfigLoader.IllustrationSpec illustrationOf(String aiTypeId, int level) {
            try {
                return Moci_ShipboardAIConfigLoader.getIllustrationSpec(aiTypeId, level);
            } catch (RuntimeException ex) {
                LOG.warn(P + "读取舰载 AI 插画失败，改用头像 type=" + aiTypeId + ", level=" + level, ex);
                return null;
            }
        }

        /** 信息签：上行灰色小字标签（orbitron12condensed），下行亮色数值（insignia21）；立绘左边的签右对齐，贴着引线。 */
        private void aiTag(CustomPanelAPI stage, String slot, String label, String value, float figureCx) {
            Moci_UILayout.Rect r = layout.rect(slot);
            boolean left = r.x + r.w / 2f < figureCx;
            float tw = width(slot, 0);
            TooltipMakerAPI t = text(stage, slot, 0);
            t.setParaFontOrbitron();
            LabelAPI l1 = t.addPara(fit(label, tw), Misc.getGrayColor(), 0f);
            t.setParaInsigniaLarge();
            // 大字号拉丁字母比 fit 的估算宽，留 20% 余量
            LabelAPI l2 = t.addPara(fit(value, tw * 0.8f), Misc.getHighlightColor(), 4f);
            t.setParaFontDefault();
            if (left) {
                l1.setAlignment(Alignment.RMID);
                l2.setAlignment(Alignment.RMID);
            }
            place(stage, t, slot, 0);
        }

        private void aiButtons(TooltipMakerAPI t, String kind, String heading, List<String> ids, float bw) {
            List<String> list = safeList(ids);
            if (list.isEmpty()) return;
            t.addPara(heading, Misc.getHighlightColor(), 10f);
            boolean aux = "ai_aux".equals(kind);
            int n = 0;
            for (String id : list) {
                if (n++ >= MAX_AI_OPTIONS) break;
                String name = aux ? Moci_ShipboardAIManagerIntel.getAuxEffectDisplayName(id) : Moci_ShipboardAIManagerIntel.getSkillName(id);
                Action a = act(kind, id, null, false);
                ButtonAPI b = t.addButton(Moci_ShipboardAIManagerIntel.escapeTooltipFormat(fit(name, bw - 16f)), a, bw, 20f, 4f);
                track(b, a);
                if (aux) {
                    String desc = Moci_ShipboardAIAuxEffectRegistry.getEffectDescription(id, 1f);
                    if (desc != null && !desc.isEmpty()) t.addPara(desc, Misc.getGrayColor(), 2f);
                }
            }
        }

        // ------------------------------ 弹出菜单 ------------------------------

        private void popupTitle(CustomPanelAPI box, String slot, String textValue) {
            TooltipMakerAPI t = text(box, slot, 0);
            t.addPara(fit(textValue, width(slot, 0)), Misc.getHighlightColor(), 0f);
            place(box, t, slot, 0);
        }

        private void buildPortraitPopup(PersonAPI leader) {
            CustomPanelAPI box = panel(content, "popupPortrait", 0, new Fill(C_POPUP, C_BORDER, this));
            PersonAPI pilot = Moci_PilotManager.getPilot(leader.getId(), popupPilot, true);
            List<String> options = safeList(squad.listPortraitOptions(leader));
            int n = Math.min(options.size(), MAX_PORTRAITS);
            if (options.size() > MAX_PORTRAITS) {
                LOG.warn(P + "头像选项 " + options.size() + " 个，只显示前 " + MAX_PORTRAITS + " 个");
            }
            popupTitle(box, "popupPortraitTitle", n == 0 ? csText("dialog_portrait_empty", "没有可用的头像")
                    : csFormat("dialog_portrait_title", "为 {name} 选择头像", "name", pilotName(pilot)));
            CustomPanelAPI grid = scrollArea(box, "popupPortraitGrid", "popupPortraitCell", n);
            String current = pilot == null ? null : pilot.getPortraitSprite();
            for (int i = 0; i < n; i++) {
                String sprite = options.get(i);
                ButtonAPI cb = clickImage(grid, "popupPortraitCell", i, sprite, act("portrait_pick", sprite, null, true));
                if (sprite != null && sprite.equals(current)) cb.highlight();
            }
            button(box, "popupPortraitCancel", 0, csText("common_cancel", "取消"), act("popup_cancel", null, null, true));
        }

        private void buildElitePopup(PersonAPI leader) {
            CustomPanelAPI box = panel(content, "popupElite", 0, new Fill(C_POPUP, C_BORDER, this));
            PersonAPI pilot = Moci_PilotManager.getPilot(leader.getId(), popupPilot, true);
            List<String[]> rows = new ArrayList<>();
            if (pilot != null) {
                if (learnMode) {
                    for (String s : safeList(squad.listLearnableSkills(pilot))) {
                        rows.add(new String[]{"learn_pick", s, csText("dialog_tag_learn", "升级并学习")});
                    }
                    for (String s : safeList(squad.listEliteableSkills(pilot))) {
                        rows.add(new String[]{"upgrade_elite_pick", s, csText("dialog_tag_upgrade_elite", "升级并精英化")});
                    }
                } else {
                    for (String s : safeList(squad.listEliteableSkills(pilot))) {
                        rows.add(new String[]{"elite_pick", s, csText("dialog_tag_elite", "精英化")});
                    }
                }
            }
            String title;
            if (pilot == null) {
                title = csText("msg_missing_pilot", "错误：无法找到机师信息");
            } else if (rows.isEmpty()) {
                title = learnMode ? csText("dialog_skill_empty", "没有可选的技能")
                        : csText("upgrade_no_elite", "该机师暂无可精英化的技能");
            } else if (learnMode) {
                title = csFormat("dialog_upgrade_title", "{name} 升级：选择一项技能", "name", pilotName(pilot));
            } else {
                title = csFormat("dialog_elite_title", "{name} 精英化：选择一项技能（{count}/{max}）", "name", pilotName(pilot),
                        "count", String.valueOf(Misc.getNumEliteSkills(pilot)),
                        "max", String.valueOf(Moci_PilotTraining.calculatePilotMaxEliteSkills(pilot)));
            }
            popupTitle(box, "popupEliteTitle", title);
            CustomPanelAPI list = scrollArea(box, "popupEliteList", "popupEliteRow", rows.size());
            for (int i = 0; i < rows.size(); i++) {
                String[] row = rows.get(i);
                String label = row[2] + "：" + skillName(row[1]);
                button(list, "popupEliteRow", i, fit(label, layout.rect("popupEliteRow", i).w - 16f), act(row[0], row[1], null, true));
            }
            button(box, "popupEliteCancel", 0, csText("common_cancel", "取消"), act("popup_cancel", null, null, true));
        }

        private void buildShipPopup(PersonAPI leader) {
            CustomPanelAPI box = panel(content, "popupShip", 0, new Fill(C_POPUP, C_BORDER, this));
            String cid = leader.getId();
            PersonAPI pilot = Moci_PilotManager.getPilot(cid, popupPilot, true);
            List<FleetMemberAPI> ships = safeList(squad.listAssignableShips(cid));
            int n = Math.min(ships.size(), MAX_SHIPS);
            popupTitle(box, "popupShipTitle", n == 0
                    ? csText("ship_select_empty", "没有可指派的舰船。舰船必须是护卫舰且部署点低于队长机。")
                    : csFormat("ship_select_prompt", "为机师 {name} 选择舰船：", "name", pilotName(pilot)));
            CustomPanelAPI grid = scrollArea(box, "popupShipGrid", "popupShipCell", n);
            for (int i = 0; i < n; i++) shipCell(grid, i, ships.get(i));
            if (squad.wingmanIsCustom(leader, popupPilot)) {
                button(box, "popupShipRestore", 0, csText("dialog_ship_restore", "还原默认配置"), act("ship_restore", null, null, true));
            }
            button(box, "popupShipCancel", 0, csText("common_cancel", "取消"), act("popup_cancel", null, null, true));
        }

        private void shipCell(CustomPanelAPI grid, int i, FleetMemberAPI s) {
            Moci_UILayout.Rect r = layout.rect("popupShipCell", i);
            CustomPanelAPI cell = grid.createCustomPanel(r.w, r.h, null);
            layout.place(grid.addComponent(cell), "popupShipCell", i);
            Action pick = act("ship_pick", s, null, true);
            TooltipMakerAPI hit = cell.createUIElement(r.w, r.h, false);
            ButtonAPI cb = hit.addAreaCheckbox("", pick, Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                    Misc.getBrightPlayerColor(), r.w, r.h, 0f);
            pin(cb);
            cell.addUIElement(hit).inTL(0f, 0f);
            track(cb, pick);
            shipImage(cell, "popupShipCellImage", i, s);
            TooltipMakerAPI info = text(cell, "popupShipCellInfo", i);
            float iw = width("popupShipCellInfo", i);
            info.addPara(fit(safe(s.getShipName(), csText("ship_name_unnamed", "未命名")), iw), Misc.getHighlightColor(), 0f);
            info.addPara(fit(s.getHullSpec().getHullName(), iw), Misc.getTextColor(), 4f);
            info.addPara(fit(csText("ship_dp_label", "DP:") + " " + (int) s.getDeploymentPointsCost(), iw), Misc.getGrayColor(), 4f);
            place(cell, info, "popupShipCellInfo", i);
            addFrame(Moci_UIHoverFrame.attach(cell, 0f, 0f, r.w, r.h));
        }

        private void buildRenamePopup(PersonAPI leader) {
            CustomPanelAPI box = panel(content, "popupRename", 0, new Fill(C_POPUP, C_BORDER, this));
            String cid = leader.getId();
            PersonAPI pilot = Moci_PilotManager.getPilot(cid, popupPilot, true);
            FullName name = pilot == null ? null : pilot.getName();
            popupTitle(box, "popupRenameTitle", csFormat("dialog_rename_title", "修改机师 {name} 的姓名与呼号", "name", pilotName(pilot)));
            String rawSign = Moci_PilotManager.getCallsign(cid, popupPilot, true);
            String[] labels = {csText("dialog_label_last", "姓"), csText("dialog_label_first", "名"), csText("dialog_label_callsign", "呼号")};
            String[] keys = {"last", "first", "callsign"};
            String[] values = {
                    name == null ? "" : safe(name.getLast(), ""),
                    name == null ? "" : safe(name.getFirst(), ""),
                    safe(rawSign, "")
            };
            for (int i = 0; i < keys.length; i++) {
                TooltipMakerAPI l = text(box, "popupRenameLabel", i);
                l.addPara(fit(labels[i], width("popupRenameLabel", i)), Misc.getTextColor(), 0f);
                place(box, l, "popupRenameLabel", i);
                field(box, "popupRenameField", i, keys[i], values[i]);
            }
            button(box, "popupRenameOk", 0, csText("dialog_ok", "确定"), act("rename_ok", null, null, true));
            button(box, "popupRenameCancel", 0, csText("common_cancel", "取消"), act("popup_cancel", null, null, true));
        }

        // ------------------------------ 放置小工具 ------------------------------

        private Action act(String kind, Object a, Object b, boolean modal) {
            return new Action(kind, a, b, gen, modal);
        }

        private float width(String slot, int index) {
            return Math.max(8f, layout.rect(slot, index).w - 10f);
        }

        /** 槽位面板：尺寸 = rect 的 w/h，经 place 定位。 */
        private CustomPanelAPI panel(CustomPanelAPI parent, String slot, int index, CustomUIPanelPlugin plugin) {
            Moci_UILayout.Rect r = layout.rect(slot, index);
            CustomPanelAPI p = parent.createCustomPanel(r.w, r.h, plugin);
            layout.place(parent.addComponent(p), slot, index);
            return p;
        }

        /** 槽位文字块：创建尺寸 = rect 的 w/h；调用方填完内容后再 place。 */
        private TooltipMakerAPI text(CustomPanelAPI parent, String slot, int index) {
            Moci_UILayout.Rect r = layout.rect(slot, index);
            return parent.createUIElement(r.w, r.h, false);
        }

        private PositionAPI place(CustomPanelAPI parent, TooltipMakerAPI t, String slot, int index) {
            return layout.place(parent.addUIElement(t), slot, index);
        }

        private ButtonAPI button(CustomPanelAPI parent, String slot, int index, String label, Action action) {
            Moci_UILayout.Rect r = layout.rect(slot, index);
            TooltipMakerAPI t = parent.createUIElement(r.w, r.h, false);
            ButtonAPI b = t.addButton(fit(label, r.w - 8f), action, r.w, r.h, 0f);
            pin(b);
            place(parent, t, slot, index);
            track(b, action);
            return b;
        }

        private void image(CustomPanelAPI parent, String slot, int index, String sprite) {
            Moci_UILayout.Rect r = layout.rect(slot, index);
            TooltipMakerAPI t = parent.createUIElement(r.w, r.h, false);
            if (sprite != null && !sprite.isEmpty()) {
                t.addImage(sprite, r.w, r.h, 0f);
                pin(t.getPrev());
            }
            place(parent, t, slot, index);
        }

        /** 可点击头像：槽位容器里依次放点击区（areaCheckbox）、图片、悬停框，悬停框最后加才画在最上面。 */
        private ButtonAPI clickImage(CustomPanelAPI parent, String slot, int index, String sprite, Action action) {
            return clickImage(parent, slot, index, sprite, action, null);
        }

        /** 同上；boxPlugin 不为空时作为槽位容器的插件（如扫描框：renderBelow 画在头像下，render 画在头像上），点击仍靠轮询。 */
        private ButtonAPI clickImage(CustomPanelAPI parent, String slot, int index, String sprite, Action action,
                                     CustomUIPanelPlugin boxPlugin) {
            Moci_UILayout.Rect r = layout.rect(slot, index);
            CustomPanelAPI box = parent.createCustomPanel(r.w, r.h, boxPlugin);
            layout.place(parent.addComponent(box), slot, index);
            TooltipMakerAPI hit = box.createUIElement(r.w, r.h, false);
            ButtonAPI cb = hit.addAreaCheckbox("", action, Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                    Misc.getBrightPlayerColor(), r.w, r.h, 0f);
            pin(cb);
            box.addUIElement(hit).inTL(0f, 0f);
            track(cb, action);
            if (sprite != null && !sprite.isEmpty()) {
                TooltipMakerAPI img = box.createUIElement(r.w, r.h, false);
                img.addImage(sprite, r.w, r.h, 0f);
                pin(img.getPrev());
                box.addUIElement(img).inTL(0f, 0f);
            }
            addFrame(Moci_UIHoverFrame.attach(box, 0f, 0f, r.w, r.h));
            return cb;
        }

        private void shipImage(CustomPanelAPI parent, String slot, int index, FleetMemberAPI ship) {
            Moci_UILayout.Rect r = layout.rect(slot, index);
            TooltipMakerAPI t = parent.createUIElement(r.w, r.h, false);
            if (ship != null) {
                List<FleetMemberAPI> one = new ArrayList<>();
                one.add(ship);
                t.addShipList(1, 1, Math.min(r.w, r.h), Misc.getBasePlayerColor(), one, 0f);
                pin(t.getPrev());
            }
            place(parent, t, slot, index);
        }

        private TextFieldAPI field(CustomPanelAPI parent, String slot, int index, String key, String value) {
            Moci_UILayout.Rect r = layout.rect(slot, index);
            TooltipMakerAPI t = parent.createUIElement(r.w, r.h, false);
            TextFieldAPI f = t.addTextField(r.w, r.h, FIELD_FONT, 0f);
            pin(f);
            if (value != null) f.setText(value);
            place(parent, t, slot, index);
            fields.put(key, f);
            return f;
        }

        /**
         * 可滚动区域：slot 是视口（滚动 tooltip，rect 的 w/h），里面放一个内容面板；
         * 行/格子按 rowSlot 的重复规则（pitchX/pitchY/cols）排进这个内容面板。
         */
        private CustomPanelAPI scrollArea(CustomPanelAPI parent, String slot, String rowSlot, int count) {
            Moci_UILayout.Rect vr = layout.rect(slot);
            float innerW = Math.max(10f, vr.w - 15f);
            float innerH = Math.max(10f, extent(rowSlot, count));
            TooltipMakerAPI t = parent.createUIElement(vr.w, vr.h, true);
            CustomPanelAPI inner = parent.createCustomPanel(innerW, innerH, null);
            t.addCustom(inner, 0f);
            pin(inner);
            place(parent, t, slot, 0);
            return inner;
        }

        private float extent(String rowSlot, int count) {
            float max = 0f;
            for (int i = 0; i < count; i++) {
                Moci_UILayout.Rect r = layout.rect(rowSlot, i);
                max = Math.max(max, r.y + r.h);
            }
            return max;
        }

        private void track(ButtonAPI b, Action a) {
            if (b == null) return;
            buttons.add(b);
            buttonActions.add(a);
        }

        private void addFrame(CustomPanelAPI framePanel) {
            Moci_UIHoverFrame f = Moci_UIHoverFrame.of(framePanel);
            if (f != null) frames.add(f);
        }
    }

    // ============================== 背景填充 ==============================

    /** 卡片 / 行 / 弹出菜单 / 半透明底板的背景；不处理输入，按钮事件转交 Workbench。 */
    private static final class Fill extends BaseCustomUIPanelPlugin {
        private final Color fill;
        private final Color border;
        private final Workbench target;
        private PositionAPI position;

        Fill(Color fill, Color border, Workbench target) {
            this.fill = fill;
            this.border = border;
            this.target = target;
        }

        @Override
        public void positionChanged(PositionAPI position) {
            this.position = position;
        }

        @Override
        public void renderBelow(float alphaMult) {
            if (position == null) return;
            float x = position.getX();
            float y = position.getY();
            float w = position.getWidth();
            float h = position.getHeight();
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT);
            try {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                if (fill != null) {
                    color(fill, alphaMult);
                    GL11.glBegin(GL11.GL_QUADS);
                    GL11.glVertex2f(x, y);
                    GL11.glVertex2f(x + w, y);
                    GL11.glVertex2f(x + w, y + h);
                    GL11.glVertex2f(x, y + h);
                    GL11.glEnd();
                }
                if (border != null) {
                    color(border, alphaMult);
                    GL11.glLineWidth(1f);
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2f(x + 0.5f, y + 0.5f);
                    GL11.glVertex2f(x + w - 0.5f, y + 0.5f);
                    GL11.glVertex2f(x + w - 0.5f, y + h - 0.5f);
                    GL11.glVertex2f(x + 0.5f, y + h - 0.5f);
                    GL11.glEnd();
                }
            } finally {
                GL11.glPopAttrib();
            }
        }

        private static void color(Color c, float alphaMult) {
            GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, c.getAlpha() / 255f * alphaMult);
        }

        @Override
        public void buttonPressed(Object buttonId) {
            if (target != null) target.buttonPressed(buttonId);
        }
    }

    /** 把 tooltip 里的单个元素钉到 (0,0)：覆盖流式布局给第一个元素加的 x+5（setXAlignOffset 是覆盖不是累加）。 */
    private static void pin(UIComponentAPI c) {
        if (c == null) return;
        c.getPosition().inTL(0f, 0f).setXAlignOffset(0f).setYAlignOffset(0f);
    }

    // ============================== 文本小工具 ==============================

    static String csText(String key, String fallback) {
        return Moci_CombatSquadTextRegistry.getText(key, fallback);
    }

    static String csFormat(String key, String fallback, String... replacements) {
        return Moci_CombatSquadTextRegistry.format(key, fallback, replacements);
    }

    static String aiText(String key, String fallback) {
        return Moci_ShipboardAIManagerIntel.uiText(key, fallback);
    }

    static String aiFormat(String key, String fallback, String... replacements) {
        return Moci_ShipboardAIManagerIntel.formatUi(key, fallback, replacements);
    }

    static String safe(String s, String fallback) {
        return s == null || s.trim().isEmpty() ? fallback : s;
    }

    static <T> List<T> safeList(List<T> list) {
        return list == null ? Collections.<T>emptyList() : list;
    }

    static String pilotName(PersonAPI pilot) {
        return pilot == null ? "?" : safe(pilot.getNameString(), "?");
    }

    static String callsignOf(String cid, int index) {
        return safe(Moci_PilotManager.getCallsign(cid, index, true),
                csFormat("pilot_default_callsign", "机师-{index}", "index", String.valueOf(index + 1)));
    }

    static String skillName(String id) {
        try {
            SkillSpecAPI spec = Global.getSettings().getSkillSpec(id);
            if (spec != null && spec.getName() != null) return spec.getName();
        } catch (RuntimeException ex) {
            LOG.warn(P + "找不到技能 " + id);
        }
        return id;
    }

    /**
     * 按估算字宽截断成一行，避免文字换行撑破槽位；超出时以 ".." 结尾。
     * 估算偏保守：游戏字体 insignia15LTaa / orbitron12condensed 的 CJK xadvance 都是 13，拉丁字母/数字 8~11。
     */
    static String fit(String s, float width) {
        if (s == null) return "";
        float limit = Math.max(16f, width);
        float used = 0f;
        for (int i = 0; i < s.length(); i++) {
            float cw = charW(s.charAt(i));
            if (used + cw > limit) {
                int cut = i;
                float back = used;
                while (cut > 0 && back + 12f > limit) {
                    cut--;
                    back -= charW(s.charAt(cut));
                }
                return s.substring(0, cut) + "..";
            }
            used += cw;
        }
        return s;
    }

    private static float charW(char c) {
        return c < 0x2E80 ? 11f : 14f;
    }
}
