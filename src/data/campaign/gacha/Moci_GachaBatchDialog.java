package data.campaign.gacha;

import data.scripts.util.Moci_TextLoader;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.*;
import com.fs.starfarer.api.util.Misc;
import data.campaign.fighter.ui.Moci_AirSquadronArt;

/** Read-only ten-card pages for one already-settled batch. Repeat is an explicit command callback. */
public final class Moci_GachaBatchDialog extends BaseCustomUIPanelPlugin implements CustomDialogDelegate {
    private static final Color MINT = new Color(202,175,115), DIM = new Color(137,151,157);
    // 小号字体一行的大致高度（UI 像素），用于卡片文字的垂直居中。
    private static final float CARD_LINE = 16f;
    private final Moci_GachaBatchReceipt receipt;
    private final Moci_GachaBatchSequence sequence;
    private final Runnable repeat;
    private final List<Moci_GachaRevealDialog.Asset> assets = new ArrayList<>();
    private final List<TooltipMakerAPI> cardText = new ArrayList<>();
    private final List<LabelAPI> cardNames = new ArrayList<>();
    private final List<LabelAPI> cardNumbers = new ArrayList<>();
    // 跟随卡片一起淡入淡出的原生文字（卡片文字 + 左侧重点展示）。
    private final List<TooltipMakerAPI> fadingText = new ArrayList<>();
    private Moci_AirSquadronArt shipArt;
    private CustomPanelAPI panel, stage;
    // 合并清单放在独立容器里：滚动列表连同里面的图标行一起移除，返回结果页时不留残影。
    private CustomPanelAPI summaryHost;
    private CustomDialogCallback callback;
    private PositionAPI stagePosition, panelPosition;
    // 窗口打开期间收起底层交互对话框的插画，关闭时恢复；见 Moci_GachaRevealDialog.hideVisual()。
    private Runnable restoreVisual = () -> { };
    private Moci_GachaPortalRenderer renderer;
    private LabelAPI detail, bonus, featureTitle, featureIndex, featureName, featureQuantity;
    private ButtonAPI next, all, automatic, previous, list, again;
    private float width, height, stageWidth, stageHeight, stageTop, screenScale;
    private boolean disposed, listMode, artFailed;
    private String labelKey = "";
    private int selectedIndex = -1;
    // 音效统一从 Audio 路由，1f 为原音高，volume 为阶段音量；不要在此重复衰减。
    private final Moci_GachaAudio audio = new Moci_GachaAudio((id,volume) -> Global.getSoundPlayer().playUISound(id,1f,volume));

    public Moci_GachaBatchDialog(Moci_GachaBatchReceipt receipt, Runnable repeat) {
        this.receipt = receipt; this.repeat = repeat;
        this.sequence = new Moci_GachaBatchSequence(receipt.draws());
    }
    public static void open(InteractionDialogAPI dialog, Moci_GachaBatchReceipt receipt, Runnable repeat) {
        if (dialog == null || receipt.draws().isEmpty()) return;
        try {
            float[] size = Moci_GachaRevealDialog.dialogSize();
            float w = size[0], h = size[1];
            Moci_GachaBatchDialog window=new Moci_GachaBatchDialog(receipt,repeat);
            window.restoreVisual=Moci_GachaRevealDialog.hideVisual(dialog);
            try { dialog.showCustomDialog(w,h,window); }
            catch(RuntimeException ex) { window.restoreVisual.run(); throw ex; }
        } catch (RuntimeException ex) {
            Global.getLogger(Moci_GachaBatchDialog.class).error("连抽窗口打开失败；奖励已按原流程到账",ex);
            dialog.getTextPanel().addPara(Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t001", "反馈窗口暂时无法打开，奖励已到账，可查看文字记录。"),Misc.getNegativeHighlightColor());
        }
    }
    @Override public void createCustomDialog(CustomPanelAPI panel, CustomDialogCallback callback) {
        this.panel=panel; this.callback=callback;
        width=panel.getPosition().getWidth(); height=panel.getPosition().getHeight();
        // 舞台保持 16:9，尽量占满窗口。不显示标题行（“MOCI / 十连召唤 · 第几页”之类会让人出戏），舞台贴近窗口顶部。
        stageTop=12;
        stageWidth=Math.min(width-40,(height-stageTop-reservedBelow())*16f/9f); stageHeight=stageWidth*9f/16f;
        screenScale=Global.getSettings().getScreenScaleMult();
        shipArt=new Moci_AirSquadronArt();
        SpriteAPI[] icons=new SpriteAPI[receipt.draws().size()];
        Moci_AirSquadronArt.Model[] models=new Moci_AirSquadronArt.Model[icons.length];
        boolean[] facing=new boolean[icons.length];
        float[] displayScales=new float[icons.length];
        for(int i=0;i<icons.length;i++) {
            Moci_GachaReward r=receipt.draws().get(i).reward();
            Moci_GachaRevealDialog.Asset a=r==null ? new Moci_GachaRevealDialog.Asset(null,Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t002", "未获得奖励"),null,null) : Moci_GachaRevealDialog.resolve(r,shipArt);
            assets.add(a); icons[i]=a.sprite(); models[i]=a.model(); displayScales[i]=a.displayScale();
            facing[i]=r!=null && ("ShipID".equals(r.type()) || "Fighter".equals(r.type()));
        }
        renderer=new Moci_GachaPortalRenderer(Moci_GachaRevealDialog.loadSprite(Moci_GachaPortalRenderer.BACKGROUND),icons,models,facing,displayScales);
        // 抽到的军官头像固定按 128×128 显示。
        for(int i=0;i<icons.length;i++) {
            Moci_GachaReward r=receipt.draws().get(i).reward();
            if(r!=null && "OFFICER".equals(r.type()))renderer.setFixedUiSize(i,Moci_GachaPortalRenderer.PORTRAIT_UI_SIZE);
        }
        stage=panel.createCustomPanel(stageWidth,stageHeight,new Stage());
        panel.addComponent(stage).inTL((width-stageWidth)/2,stageTop);
        TooltipMakerAPI info=panel.createUIElement(width-40,44,false);
        detail=info.addPara(Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t004", "通道连接中…"),MINT,0); detail.setAlignment(Alignment.MID);
        bonus=info.addPara(bonusText(),DIM,8); bonus.setAlignment(Alignment.MID);
        panel.addUIElement(info).inTL(20,stageTop+stageHeight+6);
        next=button(Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t005", "揭晓下一张"),"next",0);
        all=button(Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t006", "快速揭晓"),"all",1);
        automatic=button(Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t007", "暂停自动播放"),"automatic",2);
        previous=button(Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t008", "上一页"),"previous",3);
        list=button(Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t009", "合并清单"),"list",4);
        again=button(Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t010", "再抽 ")+receipt.draws().size()+Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t011", " 次"),"again",5);
        createCardLabels(); updateLabels();
        // 每次创建窗口只播放一次 open 音，不随分页或切换清单重播。
        audio.open();
    }
    /** 宽窗口六个按钮排一行，窄窗口排两行三列。 */
    private int buttonColumns() { return width>=900?6:3; }
    private float reservedBelow() { return 50+(width>=900?1:2)*32+6; }
    private ButtonAPI button(String text,String id,int index) {
        int columns=buttonColumns(), rows=(6+columns-1)/columns;
        int column=index%columns, row=index/columns;
        float w=(width-40-(columns-1)*10f)/columns;
        TooltipMakerAPI ui=panel.createUIElement(w,28,false);
        ButtonAPI button=ui.addButton(text,id,w,28,0);
        panel.addUIElement(ui).inTL(20+column*(w+10),height-rows*32+row*32);
        return button;
    }
    private void createCardLabels() {
        float left=(width-stageWidth)/2;
        float cardHeight=Moci_GachaPortalRenderer.CARD_H*stageHeight/720f;
        // 卡片在小分辨率／高缩放下只有五十多像素高：编号一行 + “名称 ×数量”一行，整体垂直居中，不再溢出卡框。
        boolean compact=cardHeight<36;
        float block=compact?CARD_LINE:CARD_LINE*2+2;
        for(int slot=0;slot<10;slot++) {
            float w=stageWidth*Moci_GachaPortalRenderer.cardTextWidth()/1280f;
            TooltipMakerAPI ui=panel.createUIElement(w,block+4,false); ui.setParaSmallInsignia();
            LabelAPI number=compact?null:ui.addPara("",DIM,0);
            LabelAPI name=ui.addPara("",MINT,compact?0:2);
            if(compact)number=name;
            float x=left+Moci_GachaPortalRenderer.cardTextX(slot)*stageWidth/1280f;
            float y=stageTop+(720-Moci_GachaPortalRenderer.cardY(slot))*stageHeight/720f-block/2;
            panel.addUIElement(ui).inTL(x,y);
            cardText.add(ui); cardNumbers.add(number); cardNames.add(name); fadingText.add(ui);
        }
        float fw=stageWidth*306/1280f;
        TooltipMakerAPI heading=panel.createUIElement(fw,58,false); heading.setParaSmallInsignia();
        featureTitle=heading.addPara("",DIM,0);
        panel.addUIElement(heading).inTL(left+68*stageWidth/1280f,stageTop+114*stageHeight/720f); fadingText.add(heading);
        TooltipMakerAPI number=panel.createUIElement(fw,54,false); number.setParaOrbitronVeryLarge();
        featureIndex=number.addPara("",MINT,0);
        panel.addUIElement(number).inTL(left+68*stageWidth/1280f,stageTop+158*stageHeight/720f); fadingText.add(number);
        TooltipMakerAPI caption=panel.createUIElement(fw,100,false); caption.setParaSmallInsignia();
        featureName=caption.addPara("",MINT,0); featureName.setAlignment(Alignment.MID);
        featureQuantity=caption.addPara("",DIM,12); featureQuantity.setAlignment(Alignment.MID);
        panel.addUIElement(caption).inTL(left+68*stageWidth/1280f,stageTop+484*stageHeight/720f); fadingText.add(caption);
    }
    private String bonusText() {
        if(receipt.bonuses().isEmpty()) return Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t012", "奖励已结算");
        return receipt.bonuses().stream().map(r->r.name()+" ×"+Misc.getWithDGS(r.quantity())).collect(java.util.stream.Collectors.joining("  /  "));
    }
    private static boolean pressed(ButtonAPI b) {
        if(!b.isChecked())return false; b.setChecked(false); return true;
    }
    /**
     * 按钮只提交播放请求；真正的等待和下一张切换由 BatchSequence 负责。
     * “揭晓下一张”立即推进；“快速揭晓”见 BatchSequence.advanceFast()（R 直接显示，SR／SSR 照常播放）；
     * 播放中点“合并清单”走 skipAll() 直接跳过动画。修改停留秒数看 RevealSequence 的 hold 方法。
     * 维持“推进时间轴 -> 更新声音 -> 按完成状态打开清单”的顺序，保证音画同步。
     */
    @Override public void advance(float amount) {
        if(disposed || panel==null)return;
        try {
            if(pressed(next)) { if(listMode)toggleList(); else sequence.next(); }
            if(pressed(all))sequence.fastForwardAll();
            if(pressed(automatic))sequence.toggleAutomatic();
            if(pressed(previous) && !listMode)sequence.previousPage();
            if(pressed(list)) {
                if(!listMode && !sequence.allRevealed())skipAll();
                toggleList();
            }
            if(pressed(again) && repeat!=null && sequence.allRevealed()) {
                close(); callback.dismissCustomDialog(0); repeat.run(); return;
            }
            // 画面绘制失败时直接全部揭晓并切到清单。
            if(artFailed && !listMode) {sequence.revealAll();toggleList();}
            if(!listMode)sequence.advance(amount);
            // 声音观察本帧最新状态；最后一张停留结束后才开始计算总结音延迟。
            audio.updateBatch(sequence,receipt.draws(),amount);
            updateLabels();
            float textAlpha=listMode?0f:sequence.cardsVisibility();
            for(TooltipMakerAPI t:fadingText)t.setOpacity(textAlpha);
        } catch(RuntimeException ex) {
            close();
            Global.getLogger(Moci_GachaBatchDialog.class).error("连抽窗口更新失败；保留已结算奖励与文字记录",ex);
            callback.dismissCustomDialog(0);
        }
    }
    private void updateLabels() {
        String key=sequence.page()+":"+sequence.revealedCount()+":"+sequence.focusIndex()+":"+sequence.focusRevealed()+":"+sequence.intro()+":"+(sequence.landing()>=1)+":"+sequence.automatic()+":"+listMode+":"+selectedIndex+":"+sequence.pageComplete()+":"+sequence.fastForwarding()+":"+(sequence.cardsVisibility()>0);
        if(key.equals(labelKey))return; labelKey=key;
        automatic.setText(sequence.automatic()?Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t007", "暂停自动播放"):Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t016", "继续自动播放"));
        automatic.setEnabled(!listMode && !sequence.allRevealed()); all.setEnabled(!sequence.allRevealed() && !sequence.fastForwarding());
        all.setText(sequence.fastForwarding()?Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t017", "快速揭晓中…"):Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t006", "快速揭晓"));
        previous.setEnabled(!listMode && sequence.page()>0 && sequence.pageComplete());
        again.setEnabled(repeat!=null && sequence.allRevealed()); list.setText(listMode?Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t018", "返回结果"):Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t009", "合并清单"));
        next.setEnabled(listMode || !sequence.pageComplete() || sequence.page()+1<sequence.pageCount());
        next.setText(listMode?Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t018", "返回结果"):sequence.focusIndex()>=0?(sequence.focusRevealed()?Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t005", "揭晓下一张"):Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t021", "立即揭晓")):sequence.revealedCount()>=sequence.pageEnd()?(sequence.page()+1<sequence.pageCount()?Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t022", "下一页"):Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t023", "揭晓完成")):Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t005", "揭晓下一张"));
        int focus=sequence.focusIndex();
        if(listMode)detail.setText(Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t024", "合并清单 · 所有奖励已到账"));
        else if(focus>=0) {
            Moci_GachaReward r=receipt.draws().get(focus).reward();
            if(!sequence.focusRevealed()) {
                detail.setText(sequence.rankAt(focus)==2?Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t025", "强烈信号正在汇聚…"):Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t026", "稀有信号正在确认…"));
                detail.setColor(MINT);
            } else {
                detail.setText(r==null?receipt.draws().get(focus).message():r.rarity()+" / "+assets.get(focus).name()+" ×"+Misc.getWithDGS(r.quantity())+" · "+Moci_GachaRevealDialog.typeLabel(r.type()));
                detail.setColor(Moci_GachaPortalRenderer.rarityColor(sequence.rankAt(focus)));
            }
        } else { detail.setText(sequence.pageComplete()?Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t027", "本页揭晓完成"):Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t028", "信号依次确认中…")); detail.setColor(MINT); }
        int featured=Moci_GachaPortalRenderer.featuredIndex(sequence,selectedIndex);
        boolean cardsShown=!listMode && !sequence.intro() && sequence.landing()>=1 && sequence.cardsVisibility()>0;
        boolean showcase=cardsShown;
        featureTitle.setText(showcase?(featured<0?Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t029", "待确认"):String.format(Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t030", "重点展示 / %02d"),featured+1)):"");
        featureIndex.setText(showcase?(featured<0?"—":String.format("%02d",featured+1)):"");
        if(showcase && featured>=0) {
            Moci_GachaReward r=receipt.draws().get(featured).reward();
            Color color=Moci_GachaPortalRenderer.rarityColor(sequence.rankAt(featured));
            featureName.setText(r==null?Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t002", "未获得奖励"):assets.get(featured).name());
            featureName.setColor(color);
            featureQuantity.setText(r==null?"":r.rarity()+" / "+Moci_GachaRevealDialog.typeLabel(r.type())+"\n×"+Misc.getWithDGS(r.quantity()));
            featureQuantity.setColor(color);
            if(sequence.pageComplete() && r!=null)detail.setText(r.rarity()+" / "+assets.get(featured).name()+" ×"+Misc.getWithDGS(r.quantity()));
        } else {featureName.setText(showcase?Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t032", "等待信号确认"):"");featureQuantity.setText("");}
        for(int slot=0;slot<10;slot++) {
            int index=sequence.pageStart()+slot;
            boolean show=cardsShown && index<sequence.pageEnd();
            Moci_GachaReward visibleReward=show && sequence.visible(index)?receipt.draws().get(index).reward():null;
            cardNumbers.get(slot).setColor(DIM);
            cardNumbers.get(slot).setText(show?String.format("%02d",index+1)+(visibleReward==null?"":" · "+visibleReward.rarity()):"");
            if(!show || !sequence.visible(index))cardNames.get(slot).setText("");
            else {
                Moci_GachaReward r=receipt.draws().get(index).reward();
                String name=r==null?Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t002", "未获得奖励"):assets.get(index).name();
                float textWidth=stageWidth*Moci_GachaPortalRenderer.cardTextWidth()/1280f;
                cardNames.get(slot).setText(cardText(name,r==null?0:r.quantity(),textWidth));
                Color color=Moci_GachaPortalRenderer.rarityColor(receipt.draws().get(index).rank());
                cardNames.get(slot).setColor(color); cardNumbers.get(slot).setColor(color);

            }
        }
    }
    /**
     * 卡片上的单行文字“名称 ×数量”：按估算字宽截断名称（末尾补 …），数量始终保留。
     * 完整名称在左侧重点展示和下方详情行里显示。字宽为估算值：中日韩字符约 13 像素，其余约 7 像素。
     */
    public static String cardText(String name,int quantity,float width) {
        String suffix=quantity>0?" ×"+Misc.getWithDGS(quantity):"";
        float budget=width-textWidth(suffix);
        if(textWidth(name)<=budget)return name+suffix;
        StringBuilder text=new StringBuilder();
        float used=textWidth("…");
        for(int i=0;i<name.length();i++) {
            float c=charWidth(name.charAt(i));
            if(used+c>budget)break;
            text.append(name.charAt(i)); used+=c;
        }
        return text.append('…').append(suffix).toString();
    }
    private static float textWidth(String text) {
        float w=0; for(int i=0;i<text.length();i++)w+=charWidth(text.charAt(i)); return w;
    }
    private static float charWidth(char c) { return c>=0x2E80 ? 13f : 7f; }

    /** 立即揭晓全部奖品（跳过动画）：本页卡片直接显示，可翻页或打开合并清单查看。 */
    private void skipAll() {
        if(sequence.allRevealed())return;
        sequence.revealAll(); selectedIndex=-1; labelKey="";
    }
    private void close() {
        disposed=true; restoreVisual.run();
    }

    private void toggleList() {
        listMode=!listMode;
        if(!listMode) {if(summaryHost!=null)panel.removeComponent(summaryHost);summaryHost=null;labelKey="";return;}
        sequence.closeDetail();
        List<Moci_GachaRevealDialog.Asset> totalAssets=new ArrayList<>();
        for(Moci_GachaReward r:receipt.totals())totalAssets.add(Moci_GachaRevealDialog.resolve(r,shipArt));
        summaryHost=panel.createCustomPanel(stageWidth,stageHeight,null);
        TooltipMakerAPI summary=Moci_GachaRewardList.build(summaryHost,stageWidth,stageHeight,Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t033", "全部奖励 · 已到账"),MINT,receipt.totals(),totalAssets);
        if(receipt.totals().isEmpty())summary.addPara(Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t034", "本批没有获得奖励。"),DIM,10);
        summaryHost.addUIElement(summary).inTL(0,0);
        panel.addComponent(summaryHost).inTL((width-stageWidth)/2,stageTop);labelKey="";
    }
    @Override public void positionChanged(PositionAPI p) {panelPosition=p;}
    @Override public void renderBelow(float alphaMult) {Moci_GachaRevealDialog.drawBackdrop(panelPosition,alphaMult);}
    private final class Stage extends BaseCustomUIPanelPlugin {
        @Override public void positionChanged(PositionAPI p) {stagePosition=p;}
        @Override public void renderBelow(float alphaMult) {
            if(stagePosition==null || disposed || listMode || artFailed)return;
            try { renderer.renderBatch(stagePosition.getX(),stagePosition.getY(),stageWidth,stageHeight,screenScale,alphaMult,sequence,selectedIndex); }
            catch(RuntimeException ex) {artFailed=true;Global.getLogger(Moci_GachaBatchDialog.class).error("连抽画面绘制失败，转为结果清单",ex);}
        }
        @Override public void processInput(List<InputEventAPI> events) { handleStageInput(events); }
    }
    private void handleStageInput(List<InputEventAPI> events) {
        if(disposed || listMode || stagePosition==null)return;
        for(InputEventAPI event:events) {
            if(event.isConsumed() || !event.isLMBDownEvent())continue;
            float x=(event.getX()-stagePosition.getX())*1280f/stageWidth;
            float y=(event.getY()-stagePosition.getY())*720f/stageHeight;
            if(x<0 || x>1280 || y<0 || y>720)continue;
            if(sequence.focusIndex()>=0) {sequence.next();event.consume();continue;}
            if(sequence.intro() || sequence.landing()<1)continue;
            for(int i=sequence.pageStart();i<sequence.pageEnd();i++) {
                int slot=i-sequence.pageStart();
                if(Math.abs(x-Moci_GachaPortalRenderer.cardX(slot))<=Moci_GachaPortalRenderer.CARD_W/2 && Math.abs(y-Moci_GachaPortalRenderer.cardY(slot))<=Moci_GachaPortalRenderer.CARD_H/2) {
                    if(sequence.visible(i)) {selectedIndex=i;labelKey="";event.consume();} break;
                }
            }
        }
    }
    @Override public boolean hasCancelButton(){return false;}
    @Override public String getConfirmText(){return Moci_TextLoader.getText("code_Moci_GachaBatchDialog", "t035", "关闭窗口");}
    @Override public String getCancelText(){return null;}
    @Override public CustomUIPanelPlugin getCustomPanelPlugin(){return this;}
    @Override public void customDialogConfirm(){close();}
    @Override public void customDialogCancel(){close();}
}
