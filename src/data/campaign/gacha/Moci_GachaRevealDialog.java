package data.campaign.gacha;

import data.scripts.util.Moci_TextLoader;
import java.awt.Color;
import java.util.List;
import java.util.ArrayList;

import com.fs.starfarer.api.Global;
import data.campaign.fighter.ui.Moci_AirSquadronArt;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomDialogDelegate;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.VisualPanelAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.opengl.GL11;

/**
 * 单抽展示窗口：接收已结算奖励，连接按钮、时间轴、渲染器和声音路由。
 * 改停留时间看 Moci_GachaRevealSequence，改音效看 Moci_GachaAudio；
 * 本文件主要用于调整“哪个操作触发继续／汇总”以及音画更新顺序。
 * 关闭、跳过、回看只影响展示，不再次扣费或发奖。
 */
public final class Moci_GachaRevealDialog extends BaseCustomUIPanelPlugin implements CustomDialogDelegate {
    private static final Color MINT = new Color(190, 247, 166);
    private static final Color DIM = new Color(164, 183, 176);
    private final List<Moci_GachaReward> rewards, bonuses;
    private final int draws;
    private final Moci_GachaRevealSequence sequence;
    private final List<Asset> assets = new ArrayList<>();
    private final Moci_AirSquadronArt shipArt = new Moci_AirSquadronArt();
    private Moci_GachaPortalRenderer renderer;
    private CustomPanelAPI panel;
    private CustomPanelAPI stagePanel;
    private CustomDialogCallback callback;
    private LabelAPI rewardName, detail;
    private ButtonAPI next, summaryButton, automatic;
    private float width, height, stageWidth, stageHeight, scale;
    private boolean disposed, summaryBuilt, artFailed;
    private int displayKey = -1;
    private PositionAPI panelPosition;
    // 窗口打开期间收起底层交互对话框的插画，关闭时恢复；见 hideVisual()。
    private Runnable restoreVisual = () -> { };
    // 接收 Audio 发出的声音事件；第二个参数 1f 是音高，第三个参数才是调用音量。
    // 不在窗口里另乘音量，保持原生窗口和视频预览共用同一套 Audio 数值。
    private final Moci_GachaAudio audio = new Moci_GachaAudio((id,volume) -> Global.getSoundPlayer().playUISound(id,1f,volume));

    static final String CREDITS_ICON = "graphics/icons/cargo/Moci_coin.png";

    static record Asset(String path, String name, SpriteAPI sprite, Moci_AirSquadronArt.Model model, float displayScale) {
        Asset(String path, String name, SpriteAPI sprite, Moci_AirSquadronArt.Model model) { this(path, name, sprite, model, 1f); }
    }

    private Moci_GachaRevealDialog(List<Moci_GachaReward> rewards, int draws) {
        // 附赠兑奖券（BONUS）不单独走一遍揭晓动画，只在下方文字和汇总清单里出现；
        // 只有附赠、没有正式奖励时才把附赠当作揭晓内容。
        List<Moci_GachaReward> shown = rewards.stream().filter(r -> !"BONUS".equals(r.rarity())).toList();
        this.bonuses = rewards.stream().filter(r -> "BONUS".equals(r.rarity())).toList();
        this.rewards = Moci_GachaReward.presentationOrder(shown.isEmpty() ? rewards : shown);
        this.draws = draws;
        this.sequence = new Moci_GachaRevealSequence(this.rewards.size(), this.rewards.get(0).rank());
    }

    public static void open(InteractionDialogAPI dialog, List<Moci_GachaReward> receipt, int draws) {
        if (dialog == null || receipt == null || receipt.isEmpty()) return;
        try {
            float[] size = dialogSize();
            float width = size[0], height = size[1];
            Moci_GachaRevealDialog window = new Moci_GachaRevealDialog(receipt, draws);
            window.restoreVisual = hideVisual(dialog);
            try { dialog.showCustomDialog(width, height, window); }
            catch (RuntimeException ex) { window.restoreVisual.run(); throw ex; }
        } catch (RuntimeException ex) {
            Global.getLogger(Moci_GachaRevealDialog.class).error("抽奖反馈窗口打开失败；奖励已经按原流程结算", ex);
            dialog.getTextPanel().addPara(Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t001", "反馈窗口暂时无法打开，奖励已到账，可在此查看文字记录。"), Misc.getNegativeHighlightColor());
        }
    }

    @Override public void createCustomDialog(CustomPanelAPI panel, CustomDialogCallback callback) {
        this.panel = panel;
        this.callback = callback;
        width = panel.getPosition().getWidth(); height = panel.getPosition().getHeight();
        // 舞台保持 16:9，尽量占满窗口：上方标题 40，下方两行结果文字 50，底部按钮 40。
        stageWidth = Math.min(width - 40, (height - STAGE_RESERVED) * 16f / 9f);
        stageHeight = stageWidth * 9f / 16f;
        scale = Global.getSettings().getScreenScaleMult();
        for (Moci_GachaReward reward : rewards) assets.add(resolve(reward, shipArt));
        SpriteAPI background = loadSprite(Moci_GachaPortalRenderer.BACKGROUND);
        boolean[] faceDown = new boolean[rewards.size()];
        float[] displayScales = new float[rewards.size()];
        for (int i = 0; i < rewards.size(); i++) {
            String type = rewards.get(i).type();
            displayScales[i] = assets.get(i).displayScale();
            faceDown[i] = "ShipID".equals(type) || "Fighter".equals(type);
        }
        renderer = new Moci_GachaPortalRenderer(background, assets.stream().map(Asset::sprite).toArray(SpriteAPI[]::new),
                assets.stream().map(Asset::model).toArray(Moci_AirSquadronArt.Model[]::new), faceDown, displayScales);
        // 抽到的军官头像固定按 128×128 显示。
        for (int i = 0; i < rewards.size(); i++) {
            if ("OFFICER".equals(rewards.get(i).type())) renderer.setFixedUiSize(i, Moci_GachaPortalRenderer.PORTRAIT_UI_SIZE);
        }
        stagePanel = panel.createCustomPanel(stageWidth, stageHeight, new Stage());
        panel.addComponent(stagePanel).inTL((width - stageWidth) / 2, STAGE_TOP);
        TooltipMakerAPI result = panel.createUIElement(width - 40, 48, false);
        rewardName = result.addPara("", MINT, 0); rewardName.setAlignment(Alignment.MID);
        detail = result.addPara("", DIM, 8); detail.setAlignment(Alignment.MID);
        panel.addUIElement(result).inTL(20, STAGE_TOP + 6 + stageHeight);
        TooltipMakerAPI controls = panel.createUIElement(width - 40, 34, false);
        float buttonWidth = (width - 64) / 3f;
        // 按钮放在窗口最底部一行。
        next = controls.addButton(Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t003", "揭晓"), "reveal", buttonWidth, 28, 0);
        summaryButton = controls.addButton(Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t004", "全部显示"), "summary", buttonWidth, 28, 0);
        summaryButton.getPosition().rightOfTop(next, 12);
        automatic = controls.addButton(Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t005", "暂停自动播放"), "automatic", buttonWidth, 28, 0);
        automatic.getPosition().rightOfTop(summaryButton, 12);
        panel.addUIElement(controls).inTL(20, height - 32);
        updateLabels();
        // 窗口创建完成后播放一次开窗音；不要放到每帧 advance/render 中重复播放。
        audio.open();
    }

    // 每帧先处理按钮请求，再推进时间轴，最后按新的显露状态更新文字与音效。
    // “揭晓／下一项”“全部显示”都立即生效；forceSummary() 用于 artFailed 异常兜底。
    @Override public void advance(float amount) {
        if (disposed || panel == null) return;
        try {
            if (next.isChecked()) { next.setChecked(false); actNext(); }
            if (disposed) return;
            if (summaryButton.isChecked()) { summaryButton.setChecked(false); sequence.showSummary(); }
            if (automatic.isChecked()) { automatic.setChecked(false); sequence.toggleAutomatic(); displayKey = -1; }
            if (artFailed) sequence.forceSummary();
            sequence.setRank(rewards.get(sequence.index()).rank());
            sequence.advance(amount, rewards.get(sequence.index()).rank());
            sequence.setRank(rewards.get(sequence.index()).rank());
            if (sequence.summary() && !summaryBuilt) buildSummary();
            audio.updateSingle(sequence,rewards);
            updateLabels();
        } catch (RuntimeException ex) {
            disposed = true;
            Global.getLogger(Moci_GachaRevealDialog.class).error("抽奖反馈窗口更新失败；保留已结算奖励和原文字记录", ex);
            restoreVisual.run();
            callback.dismissCustomDialog(0);
        }
    }

    private void actNext() {
        if (sequence.summary()) { disposed = true; restoreVisual.run(); callback.dismissCustomDialog(0); }
        else sequence.next();
    }

    private void updateLabels() {
        int key = sequence.index() * 16 + (sequence.summary() ? 8 : sequence.revealed() ? 4 : sequence.silhouette() ? 2 : sequence.charge() >= 0.55f ? 1 : 0)
                + (sequence.automatic() ? 0 : 4096);
        if (displayKey == key) return;
        displayKey = key;
        automatic.setText(sequence.automatic() ? Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t005", "暂停自动播放") : Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t006", "继续自动播放"));
        if (sequence.summary()) {
            rewardName.setText(""); detail.setText("");
            next.setText(Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t010", "关闭")); summaryButton.setEnabled(false); automatic.setEnabled(false);
            return;
        }
        Moci_GachaReward r = rewards.get(sequence.index());
        if (sequence.revealed()) {
            rewardName.setText(r.rarity() + "  /  " + assets.get(sequence.index()).name() + "  ×" + Misc.getWithDGS(r.quantity()));
            rewardName.setColor(Moci_GachaPortalRenderer.rarityColor(r.rank()));
            detail.setText(typeLabel(r.type()) + Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t012", " · 已到账") + bonusSuffix());
        } else {
            rewardName.setColor(MINT);
            rewardName.setText(sequence.silhouette() ? Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t013", "轮廓已确认") : sequence.charge() >= 0.55f ? Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t014", "信号汇聚中…") : Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t015", "通道连接中…"));
            detail.setText(Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t016", "奖励已到账") + bonusSuffix());
        }
        next.setText(sequence.revealed() ? sequence.index() + 1 == sequence.count() ? Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t018", "查看汇总") : Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t019", "下一项") : Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t003", "揭晓"));
    }

    /** 附赠兑奖券显示在说明行末尾，例如“    附赠兑奖券 ×10”。 */
    private String bonusSuffix() {
        if (bonuses.isEmpty() || rewards.stream().anyMatch(r -> "BONUS".equals(r.rarity()))) return "";
        StringBuilder text = new StringBuilder();
        for (Moci_GachaReward b : bonuses) text.append("    ").append(b.name()).append(" ×").append(Misc.getWithDGS(b.quantity()));
        return text.toString();
    }

    private void buildSummary() {
        summaryBuilt = true;
        panel.removeComponent(stagePanel);
        // 汇总清单包含附赠兑奖券。
        List<Moci_GachaReward> all = new ArrayList<>(rewards);
        List<Asset> allAssets = new ArrayList<>(assets);
        for (Moci_GachaReward b : bonuses) {
            if (all.contains(b)) continue;
            all.add(b); allAssets.add(resolve(b, shipArt));
        }
        TooltipMakerAPI list = Moci_GachaRewardList.build(panel, width - 40, height - STAGE_TOP - 40,
                Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t020", "全部奖励 · 已到账"), MINT, all, allAssets);
        panel.addUIElement(list).inTL(20, STAGE_TOP);
        // 单抽在真正进入汇总时播放一次总结音；此处没有十连 COMPLETE_DELAY 的额外等待。
        audio.completeSingle(rewards);
    }

    @Override public void processInput(List<InputEventAPI> events) {
        if (disposed) return;
        // Native buttons and the native dialog own clicks, Enter and Esc. No global input capture.
    }
    @Override public boolean hasCancelButton() { return false; }
    @Override public String getConfirmText() { return Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t021", "关闭窗口"); }
    @Override public String getCancelText() { return null; }
    @Override public void customDialogConfirm() { disposed = true; restoreVisual.run(); }
    @Override public void customDialogCancel() { disposed = true; restoreVisual.run(); }
    @Override public void positionChanged(PositionAPI position) { panelPosition = position; }
    @Override public void renderBelow(float alphaMult) { drawBackdrop(panelPosition, alphaMult); }
    @Override public CustomUIPanelPlugin getCustomPanelPlugin() { return this; }

    private final class Stage extends BaseCustomUIPanelPlugin {
        private PositionAPI position;
        @Override public void positionChanged(PositionAPI position) { this.position = position; }
        @Override public void renderBelow(float alphaMult) {
            if (position == null || disposed || artFailed || sequence.summary()) return;
            try {
                renderer.render(position.getX(), position.getY(), position.getWidth(), position.getHeight(),
                        scale, alphaMult, sequence, rewards.get(sequence.index()).rank());
            } catch (RuntimeException ex) {
                artFailed = true;
                Global.getLogger(Moci_GachaRevealDialog.class).error("召唤通道渲染失败，切换到奖励清单", ex);
            }
        }
    }

    // 不显示标题行（“MOCI / 召唤回响”之类会让人出戏）。舞台离窗口顶部 STAGE_TOP；
    // 舞台以外需要预留的高度：顶部 12 + 结果文字 50 + 按钮 40。
    static final float STAGE_TOP = 12f, STAGE_RESERVED = 102f;

    /**
     * 抽卡窗口尺寸（UI 像素）：几乎占满屏幕，只留出原生窗口边框和底部“关闭窗口”按钮的位置。
     * 单抽和连抽共用，改窗口大小只改这里。
     */
    static float[] dialogSize() {
        float w = Global.getSettings().getScreenWidth() - 60, h = Global.getSettings().getScreenHeight() - 100;
        return new float[]{Math.max(400, Math.min(1700, w)), Math.max(380, Math.min(1000, h))};
    }

    /**
     * 交互对话框右上角的插画（rules.csv 里的 ShowImageVisual）会画在自定义窗口上面，
     * 盖住抽卡画面。窗口打开前先保存并收起插画，返回的 Runnable 在窗口关闭时恢复，重复调用无副作用。
     * 注意 rules.csv 里 ShowImageVisual 必须写在 Moci_GachaCommand 之前，否则会在窗口打开后重新显示。
     */
    static Runnable hideVisual(InteractionDialogAPI dialog) {
        VisualPanelAPI visual = dialog == null ? null : dialog.getVisualPanel();
        if (visual == null) return () -> { };
        try { visual.saveCurrentVisual(); visual.fadeVisualOut(); }
        catch (RuntimeException ex) {
            Global.getLogger(Moci_GachaRevealDialog.class).warn("收起对话框插画失败，保持原样", ex);
            return () -> { };
        }
        boolean[] restored = {false};
        return () -> {
            if (restored[0]) return;
            restored[0] = true;
            try { visual.restoreSavedVisual(); }
            catch (RuntimeException ex) { Global.getLogger(Moci_GachaRevealDialog.class).warn("恢复对话框插画失败", ex); }
        };
    }

    /** 原生自定义窗口的底色是半透明的，底层对话框的文字会透出来；在整个窗口内先铺一层不透明底板。 */
    static void drawBackdrop(PositionAPI position, float alphaMult) {
        if (position == null || alphaMult <= 0) return;
        float x = position.getX(), y = position.getY(), w = position.getWidth(), h = position.getHeight();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(6 / 255f, 10 / 255f, 13 / 255f, Math.min(1f, alphaMult));
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x, y); GL11.glVertex2f(x + w, y);
            GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x, y + h);
            GL11.glEnd();
        } finally { GL11.glPopAttrib(); }
    }

    static String typeLabel(String type) {
        return switch (type) {
            case "ShipID" -> Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t022", "机体/舰船"); case "Weapon" -> Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t023", "武器"); case "Fighter" -> Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t024", "舰载机联队");
            case "OFFICER" -> Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t025", "军官"); case "Credits" -> Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t026", "星币"); case "SpecialItem" -> Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t027", "特殊物品");
            default -> Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t028", "物资");
        };
    }

    static Asset resolve(Moci_GachaReward r, Moci_AirSquadronArt shipArt) {
        String path = r.portrait(), name = r.name();
        Moci_AirSquadronArt.Model model = null;
        try {
            switch (r.type()) {
                case "Credits" -> { path = CREDITS_ICON; name = Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t026", "星币"); }
                case "Commodity" -> { var spec = Global.getSettings().getCommoditySpec(r.itemId()); path = spec.getIconName(); if (spec.getName() != null && !spec.getName().isBlank()) name = spec.getName(); }
                case "SpecialItem" -> { var spec = Global.getSettings().getSpecialItemSpec(r.itemId()); path = spec.getIconName(); if (name == null || name.equals(r.itemId())) name = spec.getName(); }
                case "Weapon" -> { var spec = Global.getSettings().getWeaponSpec(r.itemId()); path = spec.getTurretSpriteName(); if (spec.getWeaponName() != null && !spec.getWeaponName().isBlank()) name = spec.getWeaponName(); }
                case "ShipID" -> { if (r.param() != null) model = shipArt.buildRewardPreview(Global.getSettings().getVariant(r.param())); var spec = Global.getSettings().getHullSpec(r.itemId()); path = spec.getSpriteName(); if (spec.getHullName() != null && !spec.getHullName().isBlank()) name = spec.getHullName(); }
                case "Fighter" -> { var spec = Global.getSettings().getFighterWingSpec(r.itemId()); model = shipArt.buildRewardPreview(spec.getVariant()); path = spec.getVariant().getHullSpec().getSpriteName(); if (name == null || name.equals(r.itemId())) name = spec.getWingName(); }
                default -> { }
            }
            return new Asset(path, name, loadSprite(path), model, "Credits".equals(r.type()) ? .5f : 1f);
        } catch (RuntimeException ex) {
            Global.getLogger(Moci_GachaRevealDialog.class).warn("奖励图标不可用: " + r.id() + "; 保留名称和数量", ex);
            return new Asset(null, name == null ? r.itemId() : name, null, null);
        }
    }

    static SpriteAPI loadSprite(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            Global.getSettings().loadTexture(path);
            return Global.getSettings().getSprite(path);
        } catch (java.io.IOException | RuntimeException ex) {
            Global.getLogger(Moci_GachaRevealDialog.class).warn("召唤窗口贴图加载失败: " + path, ex);
            return null;
        }
    }
}
