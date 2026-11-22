package data.campaign.gacha;

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
    private final Moci_GachaBatchReceipt receipt;
    private final Moci_GachaBatchSequence sequence;
    private final Runnable repeat;
    private final List<Moci_GachaRevealDialog.Asset> assets = new ArrayList<>();
    private final List<TooltipMakerAPI> cardText = new ArrayList<>();
    private final List<LabelAPI> cardNames = new ArrayList<>();
    private final List<LabelAPI> cardNumbers = new ArrayList<>();
    private CustomPanelAPI panel, stage;
    private TooltipMakerAPI summary;
    private CustomDialogCallback callback;
    private PositionAPI stagePosition;
    private Moci_GachaPortalRenderer renderer;
    private LabelAPI header, detail, bonus, featureTitle, featureIndex, featureName, featureQuantity;
    private ButtonAPI next, all, automatic, previous, list, again;
    private float width, height, stageWidth, stageHeight, screenScale;
    // pendingList：用户在播放中点了合并清单，先快速展示剩余卡，再切换清单；不是立刻跳过。
    private boolean disposed, listMode, artFailed, pendingList;
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
            float w = Math.max(400,Math.min(1100,Global.getSettings().getScreenWidth()-100));
            float h = Math.max(380,Math.min(780,Global.getSettings().getScreenHeight()-160));
            dialog.showCustomDialog(w,h,new Moci_GachaBatchDialog(receipt,repeat));
        } catch (RuntimeException ex) {
            Global.getLogger(Moci_GachaBatchDialog.class).error("连抽窗口打开失败；奖励已按原流程到账",ex);
            dialog.getTextPanel().addPara("反馈窗口暂时无法打开，奖励已到账，可查看文字记录。",Misc.getNegativeHighlightColor());
        }
    }
    @Override public void createCustomDialog(CustomPanelAPI panel, CustomDialogCallback callback) {
        this.panel=panel; this.callback=callback;
        width=panel.getPosition().getWidth(); height=panel.getPosition().getHeight();
        stageWidth=Math.min(width-40,(height-218)*16f/9f); stageHeight=stageWidth*9f/16f;
        screenScale=Global.getSettings().getScreenScaleMult();
        Moci_AirSquadronArt shipArt=new Moci_AirSquadronArt();
        SpriteAPI[] icons=new SpriteAPI[receipt.draws().size()];
        Moci_AirSquadronArt.Model[] models=new Moci_AirSquadronArt.Model[icons.length];
        boolean[] facing=new boolean[icons.length];
        float[] displayScales=new float[icons.length];
        for(int i=0;i<icons.length;i++) {
            Moci_GachaReward r=receipt.draws().get(i).reward();
            Moci_GachaRevealDialog.Asset a=r==null ? new Moci_GachaRevealDialog.Asset(null,"未独立发放",null,null) : Moci_GachaRevealDialog.resolve(r,shipArt);
            assets.add(a); icons[i]=a.sprite(); models[i]=a.model(); displayScales[i]=a.displayScale();
            facing[i]=r!=null && ("ShipID".equals(r.type()) || "Fighter".equals(r.type()));
        }
        renderer=new Moci_GachaPortalRenderer(Moci_GachaRevealDialog.loadSprite(Moci_GachaPortalRenderer.BACKGROUND),icons,models,facing,displayScales);
        TooltipMakerAPI title=panel.createUIElement(width-40,32,false);
        title.setParaFontOrbitron(); header=title.addPara("MOCI / 十连召唤",MINT,0);
        panel.addUIElement(title).inTL(20,8);
        stage=panel.createCustomPanel(stageWidth,stageHeight,new Stage());
        panel.addComponent(stage).inTL((width-stageWidth)/2,44);
        TooltipMakerAPI info=panel.createUIElement(width-40,66,false);
        detail=info.addPara("通道连接中…",MINT,0); detail.setAlignment(Alignment.MID);
        bonus=info.addPara(bonusText(),DIM,8); bonus.setAlignment(Alignment.MID);
        panel.addUIElement(info).inTL(20,50+stageHeight);
        next=button("揭晓下一张","next",0,0);
        all=button("快速揭晓","all",1,0);
        automatic=button("暂停自动播放","automatic",2,0);
        previous=button("上一页","previous",0,1);
        list=button("合并清单","list",1,1);
        again=button("再抽 "+receipt.draws().size()+" 次","again",2,1);
        createCardLabels(); updateLabels();
        // 每次创建窗口只播放一次 open 音，不随分页或切换清单重播。
        audio.open();
    }
    private ButtonAPI button(String text,String id,int column,int row) {
        float w=(width-64)/3f;
        TooltipMakerAPI ui=panel.createUIElement(w,28,false);
        ButtonAPI button=ui.addButton(text,id,w,26,0);
        panel.addUIElement(ui).inTL(20+column*(w+12),height-66+row*30);
        return button;
    }
    private void createCardLabels() {
        float left=(width-stageWidth)/2;
        for(int slot=0;slot<10;slot++) {
            float w=stageWidth*Moci_GachaPortalRenderer.cardTextWidth()/1280f;
            TooltipMakerAPI ui=panel.createUIElement(w,78,false); ui.setParaSmallInsignia();
            boolean compact=stageHeight<320;
            LabelAPI number=compact?null:ui.addPara("",DIM,0);
            LabelAPI name=ui.addPara(compact?"":"\n\n",MINT,compact?0:3);
            if(compact)number=name;
            float x=left+Moci_GachaPortalRenderer.cardTextX(slot)*stageWidth/1280f;
            float y=44+(720-Moci_GachaPortalRenderer.cardY(slot)-32)*stageHeight/720f;
            panel.addUIElement(ui).inTL(x,y);
            cardText.add(ui); cardNumbers.add(number); cardNames.add(name);
        }
        float fw=stageWidth*306/1280f;
        TooltipMakerAPI heading=panel.createUIElement(fw,58,false); heading.setParaSmallInsignia();
        featureTitle=heading.addPara("",DIM,0);
        panel.addUIElement(heading).inTL(left+68*stageWidth/1280f,44+114*stageHeight/720f);
        TooltipMakerAPI number=panel.createUIElement(fw,54,false); number.setParaOrbitronVeryLarge();
        featureIndex=number.addPara("",MINT,0);
        panel.addUIElement(number).inTL(left+68*stageWidth/1280f,44+158*stageHeight/720f);
        TooltipMakerAPI caption=panel.createUIElement(fw,100,false); caption.setParaSmallInsignia();
        featureName=caption.addPara("",MINT,0); featureName.setAlignment(Alignment.MID);
        featureQuantity=caption.addPara("",DIM,12); featureQuantity.setAlignment(Alignment.MID);
        panel.addUIElement(caption).inTL(left+68*stageWidth/1280f,44+484*stageHeight/720f);
    }
    private String bonusText() {
        if(receipt.bonuses().isEmpty()) return "奖励已结算 · 点击已揭晓条目切换展示";
        return receipt.bonuses().stream().map(r->r.name()+" ×"+Misc.getWithDGS(r.quantity())).collect(java.util.stream.Collectors.joining("  /  "));
    }
    private static boolean pressed(ButtonAPI b) {
        if(!b.isChecked())return false; b.setChecked(false); return true;
    }
    /**
     * 按钮只提交播放请求；真正的等待和下一张切换由 BatchSequence 负责。
     * 修改“快速揭晓”行为看 fastForwardAll()，修改停留秒数看 RevealSequence 的 hold 方法。
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
                if(listMode || sequence.allRevealed())toggleList();
                else {pendingList=true;sequence.fastForwardAll();}
            }
            if(pressed(again) && repeat!=null && sequence.allRevealed()) {
                disposed=true; callback.dismissCustomDialog(0); repeat.run(); return;
            }
            // 只有画面失败时允许立即 revealAll，正常按钮不得使用此捷径。
            if(artFailed && !listMode) {sequence.revealAll();toggleList();}
            if(!listMode)sequence.advance(amount);
            // 声音观察本帧最新状态；最后一张停留结束后才开始计算总结音延迟。
            audio.updateBatch(sequence,receipt.draws(),amount);
            if(pendingList && sequence.allRevealed() && !listMode) {pendingList=false;toggleList();}
            updateLabels();
        } catch(RuntimeException ex) {
            disposed=true;
            Global.getLogger(Moci_GachaBatchDialog.class).error("连抽窗口更新失败；保留已结算奖励与文字记录",ex);
            callback.dismissCustomDialog(0);
        }
    }
    private void updateLabels() {
        String key=sequence.page()+":"+sequence.revealedCount()+":"+sequence.focusIndex()+":"+sequence.focusRevealed()+":"+sequence.intro()+":"+(sequence.landing()>=1)+":"+sequence.automatic()+":"+listMode+":"+selectedIndex+":"+sequence.pageComplete()+":"+sequence.fastForwarding()+":"+pendingList;
        if(key.equals(labelKey))return; labelKey=key;
        header.setText("MOCI / "+receipt.draws().size()+" 连召唤 · "+sequence.revealedCount()+" / "+receipt.draws().size()+" · 第 "+(sequence.page()+1)+" / "+sequence.pageCount()+" 页");
        automatic.setText(sequence.automatic()?"暂停自动播放":"继续自动播放");
        automatic.setEnabled(!listMode && !sequence.allRevealed()); all.setEnabled(!sequence.allRevealed());
        all.setText(sequence.fastForwarding()?"快速揭晓中…":"快速揭晓");
        previous.setEnabled(!listMode && sequence.page()>0 && sequence.pageComplete());
        again.setEnabled(repeat!=null && sequence.allRevealed()); list.setText(listMode?"返回结果":"合并清单");
        next.setEnabled(listMode || !sequence.pageComplete() || sequence.page()+1<sequence.pageCount());
        next.setText(listMode?"返回结果":sequence.focusIndex()>=0?(sequence.focusRevealed()?(sequence.fastForwarding()?"展示中…":"加快展示"):"立即揭晓"):sequence.pageComplete()?(sequence.page()+1<sequence.pageCount()?"下一页":"揭晓完成"):(sequence.fastForwarding()?"展示中…":"揭晓下一张"));
        int focus=sequence.focusIndex();
        if(listMode)detail.setText("合并清单 · 所有奖励已到账");
        else if(focus>=0) {
            Moci_GachaReward r=receipt.draws().get(focus).reward();
            if(!sequence.focusRevealed()) {
                detail.setText(sequence.rankAt(focus)==2?"强烈信号正在汇聚…":"稀有信号正在确认…");
                detail.setColor(MINT);
            } else {
                detail.setText(r==null?receipt.draws().get(focus).message():r.rarity()+" / "+assets.get(focus).name()+" ×"+Misc.getWithDGS(r.quantity())+" · "+Moci_GachaRevealDialog.typeLabel(r.type()));
                detail.setColor(Moci_GachaPortalRenderer.rarityColor(sequence.rankAt(focus)));
            }
        } else { detail.setText(sequence.pageComplete()?"本页揭晓完成 · 点击条目切换展示":"信号依次确认中… · 点击可加快，保留音效播放时间"); detail.setColor(MINT); }
        int featured=Moci_GachaPortalRenderer.featuredIndex(sequence,selectedIndex);
        boolean showcase=!listMode && !sequence.intro() && sequence.landing()>=1 && focus<0;
        featureTitle.setText(showcase?(featured<0?"MOCI / 待确认档案":String.format("重点展示 / %02d",featured+1)):"");
        featureIndex.setText(showcase?(featured<0?"—":String.format("%02d",featured+1)):"");
        if(showcase && featured>=0) {
            Moci_GachaReward r=receipt.draws().get(featured).reward();
            Color color=Moci_GachaPortalRenderer.rarityColor(sequence.rankAt(featured));
            featureName.setText(r==null?"未独立发放":assets.get(featured).name());
            featureName.setColor(color);
            featureQuantity.setText(r==null?"":r.rarity()+" / "+Moci_GachaRevealDialog.typeLabel(r.type())+"\n×"+Misc.getWithDGS(r.quantity()));
            featureQuantity.setColor(color);
            if(sequence.pageComplete() && r!=null)detail.setText(r.rarity()+" / "+assets.get(featured).name()+" ×"+Misc.getWithDGS(r.quantity())+" · 点击条目切换展示");
        } else {featureName.setText(showcase?"等待信号确认":"");featureQuantity.setText("");}
        for(int slot=0;slot<10;slot++) {
            int index=sequence.pageStart()+slot;
            boolean show=!listMode && !sequence.intro() && sequence.landing()>=1 && focus<0 && index<sequence.pageEnd();
            Moci_GachaReward visibleReward=show && sequence.visible(index)?receipt.draws().get(index).reward():null;
            cardNumbers.get(slot).setColor(DIM);
            cardNumbers.get(slot).setText(show?String.format("%02d",index+1)+(visibleReward==null?"":" · "+visibleReward.rarity()):"");
            if(!show || !sequence.visible(index))cardNames.get(slot).setText("");
            else {
                Moci_GachaReward r=receipt.draws().get(index).reward();
                String name=r==null?"未独立发放":assets.get(index).name();
                float textWidth=stageWidth*Moci_GachaPortalRenderer.cardTextWidth()/1280f;
                cardNames.get(slot).setText(cardText(name,r==null?0:r.quantity(),textWidth));
                Color color=Moci_GachaPortalRenderer.rarityColor(receipt.draws().get(index).rank());
                cardNames.get(slot).setColor(color); cardNumbers.get(slot).setColor(color);

            }
        }
    }
    /** Match native card name wrapping in off-screen previews; details always retain the full name. */
    public static String cardText(String name,int quantity,float width) {
        int chars=Math.max(2,Math.min(12,(int)(width/12)));
        int lines=width<80?1:2;
        StringBuilder text=new StringBuilder();
        int limit=Math.min(name.length(),chars*lines);
        for(int i=0;i<limit;i++) {
            if(i>0 && i%chars==0)text.append('\n');
            text.append(i==limit-1 && limit<name.length()?'…':name.charAt(i));
        }
        if(quantity>0)text.append("\n×").append(Misc.getWithDGS(quantity));
        return text.toString();
    }

    private void toggleList() {
        listMode=!listMode;
        if(!listMode) {panel.removeComponent(summary);summary=null;labelKey="";return;}
        sequence.closeDetail();
        summary=panel.createUIElement(stageWidth,stageHeight,true);
        summary.addSectionHeading("全部奖励 · 同类合并 · 已到账",MINT,new Color(20,29,36),Alignment.MID,0);
        for(Moci_GachaReward r:receipt.totals())summary.addPara(r.rarity()+" / "+r.name()+" ×"+Misc.getWithDGS(r.quantity()),Moci_GachaPortalRenderer.rarityColor(r.rank()),10);
        if(receipt.totals().isEmpty())summary.addPara("本批未获得可发放奖励，请查看原文字记录。",DIM,10);
        panel.addUIElement(summary).inTL((width-stageWidth)/2,44);labelKey="";
    }
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
    @Override public String getConfirmText(){return "关闭窗口";}
    @Override public String getCancelText(){return null;}
    @Override public CustomUIPanelPlugin getCustomPanelPlugin(){return this;}
    @Override public void customDialogConfirm(){disposed=true;}
    @Override public void customDialogCancel(){disposed=true;}
}
