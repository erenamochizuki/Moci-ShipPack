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
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * 单抽展示窗口：接收已结算奖励，连接按钮、时间轴、渲染器和声音路由。
 * 改停留时间看 Moci_GachaRevealSequence，改音效看 Moci_GachaAudio；
 * 本文件主要用于调整“哪个操作触发继续／汇总”以及音画更新顺序。
 * 关闭、跳过、回看只影响展示，不再次扣费或发奖。
 */
public final class Moci_GachaRevealDialog extends BaseCustomUIPanelPlugin implements CustomDialogDelegate {
    private static final Color MINT = new Color(190, 247, 166);
    private static final Color DIM = new Color(164, 183, 176);
    private final List<Moci_GachaReward> rewards;
    private final int draws;
    private final Moci_GachaRevealSequence sequence;
    private final List<Asset> assets = new ArrayList<>();
    private final Moci_AirSquadronArt shipArt = new Moci_AirSquadronArt();
    private Moci_GachaPortalRenderer renderer;
    private CustomPanelAPI panel;
    private CustomPanelAPI stagePanel;
    private CustomDialogCallback callback;
    private LabelAPI header, rewardName, detail;
    private ButtonAPI next, summaryButton, automatic;
    private float width, height, stageWidth, stageHeight, scale;
    private boolean disposed, summaryBuilt, artFailed;
    private int displayKey = -1;
    // 接收 Audio 发出的声音事件；第二个参数 1f 是音高，第三个参数才是调用音量。
    // 不在窗口里另乘音量，保持原生窗口和视频预览共用同一套 Audio 数值。
    private final Moci_GachaAudio audio = new Moci_GachaAudio((id,volume) -> Global.getSoundPlayer().playUISound(id,1f,volume));

    static final String CREDITS_ICON = "graphics/icons/cargo/Moci_coin.png";

    static record Asset(String path, String name, SpriteAPI sprite, Moci_AirSquadronArt.Model model, float displayScale) {
        Asset(String path, String name, SpriteAPI sprite, Moci_AirSquadronArt.Model model) { this(path, name, sprite, model, 1f); }
    }

    private Moci_GachaRevealDialog(List<Moci_GachaReward> rewards, int draws) {
        this.rewards = Moci_GachaReward.presentationOrder(rewards);
        this.draws = draws;
        this.sequence = new Moci_GachaRevealSequence(rewards.size(), this.rewards.get(0).rank());
    }

    public static void open(InteractionDialogAPI dialog, List<Moci_GachaReward> receipt, int draws) {
        if (dialog == null || receipt == null || receipt.isEmpty()) return;
        try {
            float width = Math.max(400, Math.min(1100, Global.getSettings().getScreenWidth() - 100));
            float height = Math.max(380, Math.min(780, Global.getSettings().getScreenHeight() - 160));
            dialog.showCustomDialog(width, height, new Moci_GachaRevealDialog(receipt, draws));
        } catch (RuntimeException ex) {
            Global.getLogger(Moci_GachaRevealDialog.class).error("抽奖反馈窗口打开失败；奖励已经按原流程结算", ex);
            dialog.getTextPanel().addPara(Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t001", "反馈窗口暂时无法打开，奖励已到账，可在此查看文字记录。"), Misc.getNegativeHighlightColor());
        }
    }

    @Override public void createCustomDialog(CustomPanelAPI panel, CustomDialogCallback callback) {
        this.panel = panel;
        this.callback = callback;
        width = panel.getPosition().getWidth(); height = panel.getPosition().getHeight();
        stageWidth = Math.min(width - 40, (height - 180) * 16f / 9f);
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
        TooltipMakerAPI title = panel.createUIElement(width - 40, 38, false);
        title.setParaFontOrbitron();
        header = title.addPara(Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t002", "MOCI / 召唤回响"), MINT, 0);
        panel.addUIElement(title).inTL(20, 8);
        stagePanel = panel.createCustomPanel(stageWidth, stageHeight, new Stage());
        panel.addComponent(stagePanel).inTL((width - stageWidth) / 2, 46);
        TooltipMakerAPI result = panel.createUIElement(width - 40, 64, false);
        rewardName = result.addPara("", MINT, 0); rewardName.setAlignment(Alignment.MID);
        detail = result.addPara("", DIM, 8); detail.setAlignment(Alignment.MID);
        panel.addUIElement(result).inTL(20, 54 + stageHeight);
        TooltipMakerAPI controls = panel.createUIElement(width - 40, 34, false);
        float buttonWidth = (width - 64) / 3f;
        next = controls.addButton(Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t003", "揭晓"), "reveal", buttonWidth, 28, 0);
        summaryButton = controls.addButton(Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t004", "全部显示"), "summary", buttonWidth, 28, 0);
        summaryButton.getPosition().rightOfTop(next, 12);
        automatic = controls.addButton(Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t005", "暂停自动播放"), "automatic", buttonWidth, 28, 0);
        automatic.getPosition().rightOfTop(summaryButton, 12);
        panel.addUIElement(controls).inTL(20, height - 36);
        updateLabels();
        // 窗口创建完成后播放一次开窗音；不要放到每帧 advance/render 中重复播放。
        audio.open();
    }

    // 每帧先处理按钮请求，再推进时间轴，最后按新的显露状态更新文字与音效。
    // showSummary() 遵守最短停留；forceSummary() 仅用于 artFailed 异常兜底。
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
            callback.dismissCustomDialog(0);
        }
    }

    private void actNext() {
        if (sequence.summary()) { disposed = true; callback.dismissCustomDialog(0); }
        else sequence.next();
    }

    private void updateLabels() {
        int key = sequence.index() * 16 + (sequence.summary() ? 8 : sequence.revealed() ? 4 : sequence.silhouette() ? 2 : sequence.charge() >= 0.55f ? 1 : 0)
                + (sequence.automatic() ? 0 : 4096) + (sequence.fastForwarding() ? 8192 : 0);
        if (displayKey == key) return;
        displayKey = key;
        automatic.setText(sequence.automatic() ? Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t005", "暂停自动播放") : Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t006", "继续自动播放"));
        if (sequence.summary()) {
            header.setText(Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t007", "MOCI / 召唤完成 · ") + draws + Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t008", " 抽 · ") + rewards.size() + Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t009", " 类奖励"));
            rewardName.setText(""); detail.setText("");
            next.setText(Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t010", "关闭")); summaryButton.setEnabled(false); automatic.setEnabled(false);
            return;
        }
        header.setText(Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t011", "MOCI / 召唤回响 · ") + draws + Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t008", " 抽 · ") + (sequence.index() + 1) + " / " + sequence.count());
        Moci_GachaReward r = rewards.get(sequence.index());
        if (sequence.revealed()) {
            rewardName.setText(r.rarity() + "  /  " + assets.get(sequence.index()).name() + "  ×" + Misc.getWithDGS(r.quantity()));
            rewardName.setColor(Moci_GachaPortalRenderer.rarityColor(r.rank()));
            detail.setText(typeLabel(r.type()) + Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t012", " · 已到账"));
        } else {
            rewardName.setColor(MINT);
            rewardName.setText(sequence.silhouette() ? Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t013", "轮廓已确认") : sequence.charge() >= 0.55f ? Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t014", "信号汇聚中…") : Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t015", "通道连接中…"));
            detail.setText(Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t016", "点击「揭晓」可跳过当前特效；Esc 可关闭，奖励已结算。"));
        }
        next.setText(sequence.revealed() ? sequence.fastForwarding() ? Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t017", "展示中…") : sequence.index() + 1 == sequence.count() ? Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t018", "查看汇总") : Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t019", "下一项") : Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t003", "揭晓"));
    }

    private void buildSummary() {
        summaryBuilt = true;
        panel.removeComponent(stagePanel);
        TooltipMakerAPI list = panel.createUIElement(width - 40, height - 102, true);
        list.addSectionHeading(Moci_TextLoader.getText("code_Moci_GachaRevealDialog", "t020", "全部奖励 · 已到账"), MINT, new Color(20, 35, 26), Alignment.MID, 0);
        for (int i = 0; i < rewards.size(); i++) {
            Moci_GachaReward r = rewards.get(i);
            Asset a = assets.get(i);
            Color c = Moci_GachaPortalRenderer.rarityColor(r.rank());
            if (a.path() != null && a.sprite() != null) {
                TooltipMakerAPI entry = list.beginImageWithText(a.path(), 46);
                entry.addPara(r.rarity() + " / " + a.name() + "  ×" + Misc.getWithDGS(r.quantity()), c, 0);
                entry.addPara(typeLabel(r.type()), DIM, 5);
                list.addImageWithText(12);
            } else {
                list.addPara(r.rarity() + " / " + a.name() + "  ×" + Misc.getWithDGS(r.quantity()), c, 12);
                list.addPara(typeLabel(r.type()), DIM, 5);
            }
        }
        panel.addUIElement(list).inTL(20, 44);
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
    @Override public void customDialogConfirm() { disposed = true; }
    @Override public void customDialogCancel() { disposed = true; }
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
