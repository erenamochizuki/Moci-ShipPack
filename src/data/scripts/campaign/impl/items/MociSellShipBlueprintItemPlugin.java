/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package data.scripts.campaign.impl.items;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import data.scripts.impl.Moci_GachaManager;
import org.json.JSONException;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.CargoTransferHandlerAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.campaign.impl.items.BaseSpecialItemPlugin;
import com.fs.starfarer.api.campaign.impl.items.BlueprintProviderItem;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.Description;
import com.fs.starfarer.api.loading.Description.Type;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
/**
 *
 * @author cjy4312
 */
public class MociSellShipBlueprintItemPlugin extends BaseSpecialItemPlugin  {

  //  protected ShipHullSpecAPI ship;

    @Override
    public void init(CargoStackAPI stack) {
        super.init(stack);
     //   ship = Global.getSettings().getHullSpec(stack.getSpecialDataIfSpecial().getData());
    }


    @Override
    public void render(float x, float y, float w, float h, float alphaMult,
                       float glowMult, SpecialItemRendererAPI renderer) {
//        float cx = x + w/2f;
//        float cy = y + h/2f;
//
//        float blX = cx - 30f;
//        float blY = cy - 15f;
//        float tlX = cx - 20f;
//        float tlY = cy + 26f;
//        float trX = cx + 23f;
//        float trY = cy + 26f;
//        float brX = cx + 15f;
//        float brY = cy - 18f;
//
//

        String id = stack.getSpecialDataIfSpecial().getData();
        Moci_GachaManager.getInstance().renderGachaItem(x,y,w,h,alphaMult,glowMult,renderer,id);
//
//        //	boolean known = Global.getSector().getPlayerFaction().knowsShip(hullId);
//
//        float mult = 1f;
//        //if (known) mult = 0.5f;
//
//        Color bgColor = Global.getSector().getPlayerFaction().getDarkUIColor();
//        bgColor = Misc.setAlpha(bgColor, 255);
//
//        //float b = Global.getSector().getCampaignUI().getSharedFader().getBrightness() * 0.25f;
//        renderer.renderBGWithCorners(bgColor, blX, blY, tlX, tlY, trX, trY, brX, brY,
//                alphaMult * mult, glowMult * 0.5f * mult, false);
//        renderer.renderShipWithCorners(hullId, null, blX, blY, tlX, tlY, trX, trY, brX, brY,
//                alphaMult * mult, glowMult * 0.5f * mult, false);
//
//
//        SpriteAPI overlay = Global.getSettings().getSprite("ui", "bpOverlayShip");
//        overlay.setColor(Color.green);
//        overlay.setColor(Global.getSector().getPlayerFaction().getBrightUIColor());
//        overlay.setAlphaMult(alphaMult);
//        overlay.setNormalBlend();
//        renderer.renderScanlinesWithCorners(blX, blY, tlX, tlY, trX, trY, brX, brY, alphaMult, false);
//
//
//        //	if (known) {
//        renderer.renderBGWithCorners(Color.black, blX, blY, tlX, tlY, trX, trY, brX, brY,
//                alphaMult * 0.5f, 0f, false);
//        //	}
//
//
//        overlay.renderWithCorners(blX, blY, tlX, tlY, trX, trY, brX, brY);
    }

    @Override
    public int getPrice(MarketAPI market, SubmarketAPI submarket) {
        return 0;
    }

    @Override
    protected float getItemPriceMult() {
        return 0;
    }

    @Override
    public String getName() {
        return  Moci_GachaManager.getInstance().getGachaItemName(stack.getSpecialDataIfSpecial().getData());
    }

    @Override
    public String getDesignType() {
        return null;
    }

    @Override
    public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, CargoTransferHandlerAPI transferHandler, Object stackSource) {
        super.createTooltip(tooltip, expanded, transferHandler, stackSource);

        String id = stack.getSpecialDataIfSpecial().getData();
        Moci_GachaManager.getInstance().createGachaItemTooltip(tooltip, expanded, transferHandler, stackSource,id);
//        //	boolean known = Global.getSector().getPlayerFaction().knowsShip(hullId);
//
//        List<String> hulls = new ArrayList<>();
//        hulls.add(hullId);
//        addShipList(tooltip, "许可对象:", hulls, 1, opad);
//        Description desc = Global.getSettings().getDescription(ship.getDescriptionId(), Type.SHIP);
//
//        String prefix = "";
//        if (ship.getDescriptionPrefix() != null) {
//            prefix = ship.getDescriptionPrefix() + "\n\n";
//        }
//        tooltip.addPara(prefix + desc.getText1FirstPara(), opad);
//
//        addCostLabel(tooltip, opad, transferHandler, stackSource);
//
//        //	if (known) {
//        //		tooltip.addPara("Already known", g, opad);
//        //	} else {
//        //		tooltip.addPara("Right-click to learn", b, opad);
//        //	}
    }

    @Override
    public boolean hasRightClickAction() {
        return false;
    }

    @Override
    public boolean shouldRemoveOnRightClickAction() {
        //	String hullId = stack.getSpecialDataIfSpecial().getData();
        //	return !Global.getSector().getPlayerFaction().knowsShip(hullId);
        return false;
    }

    @Override
    public void performRightClickAction() {
    }

    @Override
    public String resolveDropParamsToSpecificItemData(String params, Random random) throws JSONException {
        return null;
    }
}





