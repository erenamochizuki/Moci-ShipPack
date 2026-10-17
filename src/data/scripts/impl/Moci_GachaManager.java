/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package data.scripts.impl;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import com.fs.starfarer.api.impl.campaign.rulecmd.AddRemoveCommodity;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.IndustrySpecAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import data.scripts.util.Moci_TextLoader;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;

import java.awt.*;
import java.io.IOException;
import java.util.*;

/**
 * @author cjy4312
 */
public class Moci_GachaManager {
    private static final Logger log = Global.getLogger(Moci_GachaManager.class);
    private static final String TEXT_ID = "Moci_CampaignText";
    private static final String MemKey = "$moci_gacha_manager";
    public static final int NO_SSR_LIMIT = 100; //保底SSR所需抽数
    public static final boolean HAS_Gacha_Pity_System = false; //是否启用保底机制
    private final Map<String, Moci_GachaData> dataMap = new HashMap<>();
    private final Map<String, Integer> Gacha_Recorder = new HashMap<>();
    private final Set<String> Officer_Recorder = new HashSet<>();
    private final Map<String, Map<String, officerData>> Officer_Map = new HashMap<>();
    //
    private static final WeightedRandomPicker<String> R_Officer_Instead_Item = new WeightedRandomPicker<>();
    private static final WeightedRandomPicker<String> SR_Officer_Instead_Item = new WeightedRandomPicker<>();
    private static final WeightedRandomPicker<String> SSR_Officer_Instead_Item = new WeightedRandomPicker<>();
    
    /**
     * 根据珠子类型计算应发放的兑奖券数量
     * @param pearlType 珠子类型 (Moci_Vorttear, Moci_Mournscale, Moci_Dracaryx)
     * @return 兑奖券数量
     */
    private static int calculateTicketsFromPearl(String pearlType) {
        return switch (pearlType) {
            case "Moci_Vorttear" -> 1;   // 涡珠 -> 1券
            case "Moci_Mournscale" -> 5;  // 涡鳞珠 -> 5券
            case "Moci_Dracaryx" -> 10;   // 涡龙珠 -> 10券
            default -> 0;
        };
    }
    
    /**
     * 在抽卡后发放兑奖券
     * @param cargo 玩家货舱
     * @param text 文本面板
     * @param pearlType 消耗的珠子类型
     * @param count 抽卡次数
     */
    private void grantTicketsAfterGacha(CargoAPI cargo, TextPanelAPI text, 
                                        String pearlType, int count) {
        int ticketsPerDraw = calculateTicketsFromPearl(pearlType);
        int totalTickets = ticketsPerDraw * count;
        
        if (totalTickets > 0) {
            // 检查兑奖券物品是否注册
            if (Global.getSettings().getCommoditySpec("Moci_GachaTicket") == null) {
                log.error("兑奖券物品未注册！请检查 commodities.csv");
                // 降级方案：发放等价的星币
                int credits = totalTickets * 100; // 1券=100星币
                cargo.getCredits().add(credits);
                text.addPara(formatText("gacha.manager.ticket_missing",
                                mapOf("credits", String.valueOf(credits))),
                        Misc.getNegativeHighlightColor());
                return;
            }
            
            cargo.addCommodity("Moci_GachaTicket", totalTickets);
            AddRemoveCommodity.addCommodityGainText("Moci_GachaTicket", 
                                                    totalTickets, text);
            log.info("玩家抽卡：消耗 " + pearlType + " x" + count + 
                     "，发放兑奖券 x" + totalTickets);
        }
    }
    private final WeightedRandomPicker<String> R_Picker = new WeightedRandomPicker<>();
    private final WeightedRandomPicker<String> SR_Picker = new WeightedRandomPicker<>();
    private final WeightedRandomPicker<String> SSR_Picker = new WeightedRandomPicker<>();
    public static String gachaMemoryItem = null;
    public static int gachaMemoryItemNum = 0;
    private static final float SSR_CHANCE = 0.0075f;
    private static final float SR_CHANCE = 0.04f;

    public static void addTestObj() {
        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        CustomCampaignEntityAPI entity = player.getStarSystem().addCustomEntity("moci_gacha_entity_test", null, "moci_gacha_entity", Factions.NEUTRAL);
        entity.getLocation().set(player.getLocation());
    }

    public void showGachaShop(final InteractionDialogAPI dialog, final CargoAPI playerCargo) {

        CargoAPI TraderCargo = Global.getFactory().createCargo(true);
        addShopCargo(TraderCargo, R_Picker);
        addShopCargo(TraderCargo, SR_Picker);
        addShopCargo(TraderCargo, SSR_Picker);

        dialog.showCargoPickerDialog(text("gacha.shop.title"), text("gacha.shop.confirm"), text("gacha.shop.cancel"), true, 300f, TraderCargo,//Common_String_Cancel
                new CargoPickerListener() {
                    @Override
                    public void pickedCargo(CargoAPI cargo) {
                        if (cargo.isEmpty()) {
                            dialog.getTextPanel().addPara(text("gacha.shop.no_items_exchanged"));
                        } else {
                            Map<String, Integer> resultMap = new HashMap<>();
                            for (CargoStackAPI stack : cargo.getStacksCopy()) {
//                                if (stack.isCommodityStack()) {
//                                    id = stack.getCommodityId();
//                                    playerCargo.addCommodity(id, (int) stack.getSize());
//                                    AddRemoveCommodity.addCommodityGainText(id, (int) stack.getSize(), dialog.getTextPanel());
//                                }
//                                if (stack.isWeaponStack()) {
//                                    id = stack.getWeaponSpecIfWeapon().getWeaponId();
//                                    playerCargo.addWeapons(id, (int) stack.getSize());
//                                    AddRemoveCommodity.addCommodityGainText(id, (int) stack.getSize(), dialog.getTextPanel());
//                                }
//                                if (stack.isFighterWingStack()) {
//                                    id = stack.getFighterWingSpecIfWing().getId();
//                                    playerCargo.addFighters(id, (int) stack.getSize());
//                                    AddRemoveCommodity.addFighterGainText(id, (int) stack.getSize(), dialog.getTextPanel());
//                                }
                                if (!stack.isSpecialStack()) {
                                    continue;
                                }
                                String id = stack.getSpecialDataIfSpecial().getData();
                                int num = 1;
                                if (resultMap.containsKey(id)) {
                                    num += resultMap.get(id);
                                }
                                resultMap.put(id, num);
                            }
                            TextPanelAPI text = dialog.getTextPanel();
                            Map<String, Integer> costMap = new HashMap<>();
                            boolean hasInsufficientResources = false;
                            boolean rewardReceived = false;
                            if (!resultMap.isEmpty()) {
                                // 首先检查所有物品是否有足够的资源
                                for (String id : resultMap.keySet()) {
                                    Moci_GachaData data = dataMap.get(id);
                                    int requestedQuantity = resultMap.get(id);
                                    int requiredCost = data.sellReqItemNum * requestedQuantity;
                                    
                                    if (Global.getSettings().getCommoditySpec(data.sellReqItemId) != null) {
                                        int currentQuantity = (int) playerCargo.getCommodityQuantity(data.sellReqItemId);
                                        if (currentQuantity < requiredCost) {
                                            text.addPara(formatText("gacha.shop.insufficient_commodity",
                                                            mapOf("name", data.name,
                                                                    "required", String.valueOf(requiredCost),
                                                                    "commodity", Global.getSettings().getCommoditySpec(data.sellReqItemId).getName(),
                                                                    "current", String.valueOf(currentQuantity))),
                                                    Misc.getNegativeHighlightColor());
                                            hasInsufficientResources = true;
                                        }
                                    } else {
                                        float currentCredits = playerCargo.getCredits().get();
                                        if (currentCredits < requiredCost) {
                                            text.addPara(formatText("gacha.shop.insufficient_credits",
                                                            mapOf("name", data.name,
                                                                    "required", String.valueOf(requiredCost),
                                                                    "current", String.valueOf((int) currentCredits))),
                                                    Misc.getNegativeHighlightColor());
                                            hasInsufficientResources = true;
                                        }
                                    }
                                }
                                
                                // 如果有任何资源不足，则停止兑换
                                if (hasInsufficientResources) {
                                    text.addPara(text("gacha.shop.exchange_failed"), Misc.getNegativeHighlightColor());
                                    return; // 直接返回，不进行任何兑换
                                }
                                
                                // 所有检查通过后，进行实际兑换
                                for (String id : resultMap.keySet()) {
                                    Moci_GachaData data = dataMap.get(id);
                                    int i = resultMap.get(id);
                                    int record = 0;
                                    if (Gacha_Recorder.containsKey(id)) {
                                        record = Gacha_Recorder.get(id);
                                    }
                                    record += i;
                                    if (dataMap.get(id).maxUse > 0 && record >= dataMap.get(id).maxUse) {
                                        removeItem(id, true);
                                    }
                                    Gacha_Recorder.put(id, record);
                                    int cost = data.sellReqItemNum * i;
                                    if (costMap.containsKey(data.sellReqItemId)) {
                                        cost += costMap.get(data.sellReqItemId);
                                    }
                                    costMap.put(data.sellReqItemId, cost);
                                    int num = 0;
                                    while (i > 0) {
                                        i--;
                                        num += data.getItemValue();
                                    }
                                    // 添加兑换成功提示
                                    text.addPara(formatText("gacha.shop.exchanged_item", mapOf("name", data.name)),
                                            Misc.getPositiveHighlightColor());
                                    
                                    // 记录兑换日志
                                    log.info("玩家兑换：商品 " + data.id + "，消耗兑奖券 x" + cost);
                                    
                                    if (data.type == GachaType.Commodity) {
                                        AddRemoveCommodity.addCommodityGainText(data.itemID, num, text);
                                        playerCargo.addCommodity(data.itemID, num);
                                        rewardReceived |= num > 0;
                                    } else if (data.type == GachaType.Weapon) {
                                        playerCargo.addWeapons(data.itemID, num);
                                        rewardReceived |= num > 0;
                                        AddRemoveCommodity.addWeaponGainText(data.itemID, num, text);
                                    } else if (data.type == GachaType.Fighter) {
                                        playerCargo.addFighters(data.itemID, num);
                                        rewardReceived |= num > 0;
                                        AddRemoveCommodity.addFighterGainText(data.itemID, num, text);
                                    } else if (data.type == GachaType.SpecialItem) {
                                        SpecialItemData specialItem = new SpecialItemData(data.itemID, data.param);
                                        AddRemoveCommodity.addItemGainText(specialItem, num, text);
                                        playerCargo.addSpecial(specialItem, num);
                                        rewardReceived |= num > 0;
                                    } else if (data.type == GachaType.ShipID) {
                                        log.info("商店兑换舰船: ID=" + data.id + ", ItemID=" + data.itemID);
                                        if (Global.getSettings().getHullSpec(data.itemID) != null) {
                        String variantId = findValidVariantId(data.itemID);
                        if (variantId != null) {
                            playerCargo.getFleetData().addFleetMember(variantId);
                            rewardReceived = true;
                            log.info("商店兑换成功生成舰船: HullID=" + data.itemID + ", VariantID=" + variantId);
                        } else {
                            text.addPara(formatText("gacha.error.missing_variant", mapOf("id", data.itemID)));
                            log.error("商店兑换舰船变体不存在: HullID=" + data.itemID);
                        }
                                        } else {
                                            text.addPara(formatText("gacha.error.invalid_ship", mapOf("id", data.id)));
                                            log.error("商店兑换非法舰船: ID=" + data.id + ", ItemID=" + data.itemID);
                                        }
                                    } else {
                                        text.addPara(formatText("gacha.shop.unsupported_sale_item", mapOf("id", data.id)));
                                    }

                                }
                            }
                            if (!costMap.isEmpty()) {
                                for (String id : costMap.keySet()) {
                                    int num = costMap.get(id);
                                    if (Global.getSettings().getCommoditySpec(id) != null) {
                                        playerCargo.removeCommodity(id, num);
                                        AddRemoveCommodity.addCommodityLossText(id, num, text);
                                    } else {
                                        playerCargo.getCredits().subtract(num);
                                        AddRemoveCommodity.addCreditsLossText(num, text);
                                    }
                                }
                            }
                            if (rewardReceived) {
                                Moci_CampaignFeedback.playRewardReceived();
                            }
                        }
                    }

                    @Override
                    public void cancelledCargoSelection() {
                        dialog.getTextPanel().addPara(text("gacha.shop.cancelled"));
                    }

                    @Override
                    public void recreateTextPanel(TooltipMakerAPI panel, CargoAPI cargo, CargoStackAPI pickedUp, boolean pickedUpFromSource, CargoAPI combined) {
                        // 显示玩家当前拥有的兑奖券
                        panel.addPara(text("gacha.shop.ticket_heading"), 20);
                        
                        // 显示兑奖券数量（带图标）
                        int tickets = (int) playerCargo.getCommodityQuantity("Moci_GachaTicket");
                        if (Global.getSettings().getCommoditySpec("Moci_GachaTicket") != null) {
                            TooltipMakerAPI ticketImage = panel.beginImageWithText(
                                Global.getSettings().getCommoditySpec("Moci_GachaTicket").getIconName(), 30f);
                            ticketImage.addPara(formatText("gacha.shop.ticket_count", mapOf("tickets", String.valueOf(tickets))), 10f);
                            panel.addImageWithText(3);
                        } else {
                            panel.addPara(formatText("gacha.shop.ticket_count", mapOf("tickets", String.valueOf(tickets))), 10);
                        }
                        
                        panel.addPara("", 10); // 添加间距
                        panel.addPara(text("gacha.shop.cost_heading"), 20);
                        Map<Moci_GachaData, Integer> resultMap = new HashMap<>();
                        if (combined != null) {
                            for (CargoStackAPI stack : combined.getStacksCopy()) {
                                if (stack == null || !stack.isSpecialStack()) {
                                    continue;
                                }
                                Moci_GachaData data = dataMap.get(stack.getSpecialDataIfSpecial().getData());
                                int num = (int) stack.getSize();
                                if (resultMap.containsKey(data)) {
                                    num += resultMap.get(data);
                                }
                                resultMap.put(data, num);
                            }
                        }
                        Map<String, Integer> costMap = new HashMap<>();
                        if (!resultMap.isEmpty()) {
                            for (Moci_GachaData data : resultMap.keySet()) {
                                int i = resultMap.get(data);
                                int cost = data.sellReqItemNum * i;
                                if (costMap.containsKey(data.sellReqItemId)) {
                                    cost += costMap.get(data.sellReqItemId);
                                }
                                costMap.put(data.sellReqItemId, cost);
                            }
                        }
                        if (!costMap.isEmpty()) {
                            boolean a = false;
                            for (String id : costMap.keySet()) {
                                Color color = Misc.getHighlightColor();
                                int num = costMap.get(id);
                                CommoditySpecAPI spec = Global.getSettings().getCommoditySpec(id);
                                if (spec == null) {
                                    if (playerCargo.getCredits().get() < num) {
                                        color = Misc.getNegativeHighlightColor();
                                        a = true;
                                    }
                                    panel.addPara(formatText("gacha.shop.credits_count", mapOf("num", String.valueOf(num))), color, 10);
                                } else {
                                    if (playerCargo.getCommodityQuantity(id) < num) {
                                        color = Misc.getNegativeHighlightColor();
                                        a = true;
                                    }
                                    TooltipMakerAPI image = panel.beginImageWithText(Global.getSettings().getCommoditySpec(id).getIconName(), 30f);
                                    image.addPara(" x " + num, color, 10f);
                                    panel.addImageWithText(5);
                                }

                            }
                            if (a) {
                                panel.addPara(text("gacha.shop.partial_due_to_shortage"), Misc.getNegativeHighlightColor(), 10);
                            }
                        }
                        if (pickedUp != null && pickedUp.isSpecialStack()) {
                            if (pickedUp.getSpecialDataIfSpecial() != null) {
                                panel.addPara(text("gacha.shop.current_action"), 10);
                                String id = pickedUp.getSpecialDataIfSpecial().getData();
                                Moci_GachaData data = dataMap.get(id);
                                id = data.sellReqItemId;
                                CommoditySpecAPI spec = Global.getSettings().getCommoditySpec(id);
                                String add = " + ";
                                Color color = Misc.getNegativeHighlightColor();
                                if (!pickedUpFromSource) {
                                    add = " - ";
                                    color = Misc.getHighlightColor();
                                }
                                if (spec == null) {
                                    panel.addPara(formatText("gacha.shop.credits_delta",
                                            mapOf("delta", add + data.sellReqItemNum * (int) pickedUp.getSize())), color, 5);
                                } else {
                                    TooltipMakerAPI image = panel.beginImageWithText(Global.getSettings().getCommoditySpec(id).getIconName(), 30f);
                                    image.addPara(add + data.sellReqItemNum * (int) pickedUp.getSize(), color, 5);
                                    panel.addImageWithText(5);
                                }
                            }
                        }
                    }
                });

    }

    public void addShopCargo(CargoAPI shop, WeightedRandomPicker<String> picker) {
        if (picker.isEmpty()) {
            return;
        }
        for (String id : picker.getItems()) {
            if (dataMap.containsKey(id)) {
                Moci_GachaData data = dataMap.get(id);
                if (data == null || data.maxUse <= 0 || data.sellReqItemId == null || data.sellReqItemId.isEmpty()) {
                    continue;
                }
                int num = data.maxUse;
                if (Gacha_Recorder.containsKey(id)) {
                    num -= Gacha_Recorder.get(id);
                }
                if (num <= 0) {
                    continue;
                }

                switch (data.type) {
                    case OFFICER:
                    case Credits:
                        continue;
                    case ShipID:
                    case Weapon:
                    case Commodity:
                    case Fighter:
                    case SpecialItem:
                        shop.addSpecial(new SpecialItemData("moci_sell_item", id), num);
                        break;
                }
            }
        }
    }

    //  private final WeightedRandomPicker<String> CreditsPicker = new WeightedRandomPicker<>();
    //   private final WeightedRandomPicker<String> UniqueShipPicker = new WeightedRandomPicker<>();
    // private final WeightedRandomPicker<String> NormalShipPicker = new WeightedRandomPicker<>();
    // private final WeightedRandomPicker<String> OthersPicker = new WeightedRandomPicker<>();
    static {
        R_Officer_Instead_Item.add(Commodities.GAMMA_CORE);
        SR_Officer_Instead_Item.add(Commodities.BETA_CORE);
        SSR_Officer_Instead_Item.add(Commodities.ALPHA_CORE);
    }

    public static Moci_GachaManager getInstance() {
        if (Global.getSector().getMemoryWithoutUpdate().contains(MemKey)) {
            return (Moci_GachaManager) Global.getSector().getMemoryWithoutUpdate().get(MemKey);
        } else {
            Moci_GachaManager manager = new Moci_GachaManager();
            Global.getSector().getMemoryWithoutUpdate().set(MemKey, manager);
            return manager;
        }

    }


    public void outPutPool(TextPanelAPI text) {
        text.addPara("/////卡池状况///////");
        if (SSR_Picker.isEmpty()) {
            text.addPara("SSR卡池：空");
        } else {
            text.addPara("SSR卡池：");
            for (String s : SSR_Picker.getItems()) {
                text.addPara(dataMap.get(s).itemID + " 权重：" + SSR_Picker.getWeight(s));
            }
        }
        if (SR_Picker.isEmpty()) {
            text.addPara("SR卡池：空");
        } else {
            text.addPara("SR卡池：");
            for (String s : SR_Picker.getItems()) {
                text.addPara(dataMap.get(s).itemID + " 权重：" + SR_Picker.getWeight(s));
            }
        }
        if (R_Picker.isEmpty()) {
            text.addPara("R池：空");
        } else {
            text.addPara("R卡池：");
            for (String s : R_Picker.getItems()) {
                text.addPara(s + " 权重：" + R_Picker.getWeight(s));
            }
        }
    }

    public static int getNoSSRNum() {
        int noSSR = 0;
        if (Global.getSector().getPlayerPerson().getMemoryWithoutUpdate().contains("$Moci_GachaNoSSRTime")) {
            noSSR = Global.getSector().getPlayerPerson().getMemoryWithoutUpdate().getInt("$Moci_GachaNoSSRTime");
        }
        return noSSR;
    }

    public String getLimitText() {
        if (SSR_Picker.isEmpty()) {
            return text("gacha.limit.all_ssr_obtained");
        } else {
            int noSSR = getNoSSRNum();
            int num = NO_SSR_LIMIT - noSSR;
            if (num == 0) {
                return text("gacha.limit.next_guaranteed");
            } else {
                return formatText("gacha.limit.remaining", mapOf("num", String.valueOf(num)));
            }
        }
    }

    public void Gacha(CargoAPI cargo, @NotNull TextPanelAPI text, int time) {
        String id;
        boolean rewardReceived = false;
        Map<Moci_GachaData, Integer> resultMap = new HashMap<>();
        int noSSR = getNoSSRNum();
        
        // 记录抽卡次数用于发放兑奖券
        int totalDraws = time;
        
        while (time > 0) {
            double random = Math.random();
            time--;
            if (!SSR_Picker.isEmpty() && (random < SSR_CHANCE || (HAS_Gacha_Pity_System && noSSR >= NO_SSR_LIMIT))) {
                id = SSR_Picker.pick();

                noSSR = 0;
            } else if (!SR_Picker.isEmpty() && random < SSR_CHANCE + SR_CHANCE) {
                id = SR_Picker.pick();
                noSSR += 1;
            } else if (!R_Picker.isEmpty()) {
                id = R_Picker.pick();
                noSSR += 1;
            } else {
                text.addPara(text("gacha.error.empty_pool"));
                break;
            }
            if (noSSR > NO_SSR_LIMIT) {
                noSSR = 0;
            }
            if (dataMap.containsKey(id)) {
                Moci_GachaData data = dataMap.get(id);
                int num = 1;
                if (resultMap.containsKey(data)) {
                    num += resultMap.get(data);
                }
                resultMap.put(data, num);
                int record = 0;
                if (Gacha_Recorder.containsKey(id)) {
                    record = Gacha_Recorder.get(id);
                }
                record++;
                if (dataMap.get(id).maxUse > 0 && record >= dataMap.get(id).maxUse) {
                    removeItem(id, true);
                }
                Gacha_Recorder.put(id, record);
            } else {
                text.addPara(formatText("gacha.error.invalid_pool_item", mapOf("id", id)));
                removeItem(id, false);
            }
            Global.getSector().getPlayerPerson().getMemoryWithoutUpdate().set("$Moci_GachaNoSSRTime", noSSR);
        }
        if (!resultMap.isEmpty()) {
            String CreditText = null;
            String Text = null;
            String UniqueText = null;
            for (Moci_GachaData data : resultMap.keySet()) {
                int i = resultMap.get(data);
                int num = 0;
                while (i > 0) {
                    i--;
                    num += data.getItemValue();
                }
                if (data.type == GachaType.Commodity) {
                    AddRemoveCommodity.addCommodityGainText(data.itemID, num, text);
                    cargo.addCommodity(data.itemID, num);
                    rewardReceived |= num > 0;
                } else if (data.type == GachaType.Credits) {
                    cargo.getCredits().add(num);
                    rewardReceived |= num > 0;
                    AddRemoveCommodity.addCreditsGainText(num, text);
                } else if (data.type == GachaType.Weapon) {
                    cargo.addWeapons(data.itemID, num);
                    rewardReceived |= num > 0;
                    AddRemoveCommodity.addWeaponGainText(data.itemID, num, text);
                } else if (data.type == GachaType.Fighter) {
                    cargo.addFighters(data.itemID, num);
                    rewardReceived |= num > 0;
                    AddRemoveCommodity.addFighterGainText(data.itemID, num, text);
                } else if (data.type == GachaType.SpecialItem) {
                    SpecialItemData specialItem = new SpecialItemData(data.itemID, data.param);
                    AddRemoveCommodity.addItemGainText(specialItem, num, text);
                    cargo.addSpecial(specialItem, num);
                    rewardReceived |= num > 0;
                } else if (data.type == GachaType.ShipID) {
                    log.debug("抽卡获得舰船: ID=" + data.id + ", ItemID=" + data.itemID + ", 稀有度=" + data.rare);
                    if (Global.getSettings().getHullSpec(data.itemID) != null) {
                        String variantId = findValidVariantId(data.itemID);
                        if (variantId != null) {
                            cargo.getFleetData().addFleetMember(variantId);
                            rewardReceived = true;
                            log.debug("成功生成舰船: HullID=" + data.itemID + ", VariantID=" + variantId);
                        } else {
                            text.addPara(formatText("gacha.error.missing_variant", mapOf("id", data.itemID)));
                            log.error("舰船变体不存在: HullID=" + data.itemID);
                        }
                    } else {
                        text.addPara(formatText("gacha.error.invalid_ship", mapOf("id", data.id)));
                        log.error("抽到非法舰船: ID=" + data.id + ", ItemID=" + data.itemID);
                    }
                } else if (data.type == GachaType.OFFICER) {
                    log.debug("抽卡获得军官: ID=" + data.id + ", ItemID=" + data.itemID + ", 稀有度=" + data.rare);
                    WeightedRandomPicker<String> picker = new WeightedRandomPicker<>();
                    Map<String, officerData> officers = null;
                    boolean out = false;
                    String coreType = null; // 用于记录替换的核心类型
                    officers = Officer_Map.get(data.itemID);
                    if (officers != null && !officers.isEmpty()) {
                        picker.addAll(officers.keySet());
                        log.debug("可用军官池: " + data.itemID + ", 数量=" + officers.size() + ", 军官ID列表=" + officers.keySet());
                    } else {
                        out = true;
                        log.info("军官池为空或不存在，转换为AI核心: " + data.itemID);
                        if ("SSR_Officer".equals(data.itemID)) {
                            picker = SSR_Officer_Instead_Item;
                            coreType = "阿尔法核心";
                        } else if ("SR_Officer".equals(data.itemID)) {
                            picker = SR_Officer_Instead_Item;
                            coreType = "贝塔核心";
                        } else {
                            picker = R_Officer_Instead_Item;
                            coreType = "伽马核心";
                        }
                    }
                    String pickedId = picker.pick();
                    log.info("选中的ID: " + pickedId + ", 是否转换为核心=" + out);
                    if (out) {
                        // 添加重复军官转化提示
                        text.addPara(formatText("gacha.officer.convert_duplicate", mapOf("core", coreType)),
                                com.fs.starfarer.api.util.Misc.getHighlightColor());
                        AddRemoveCommodity.addCommodityGainText(pickedId, 1, text);
                        cargo.addCommodity(pickedId, 1);
                        rewardReceived = true;
                        log.info("转换为AI核心完成: " + coreType + ", 物品ID=" + pickedId);
                    } else {
                        officerData officerInfo = officers.get(pickedId);
                        PersonAPI officer = officerInfo.genOfficer();
                        String hire = officerInfo.hireText;
                        log.info("生成军官: OfficerDataID=" + pickedId + ", 生成的军官名=" + officer.getNameString() + 
                                ", 等级=" + officer.getStats().getLevel() + ", 性格=" + officer.getPersonalityAPI().getId());
                        if(hire!=null){
                            text.addPara(officer.getNameString()+":"+hire);
                        }

                        Global.getSector().getPlayerFleet().getFleetData().addOfficer(officer);
                        rewardReceived = true;
                        AddRemoveCommodity.addOfficerGainText(officer, text);
                        officers.remove(pickedId);
                        log.info("军官已添加到舰队，从池中移除: " + pickedId);
                    }
                } else {
                    text.addPara(formatText("gacha.error.unknown_prize_type", mapOf("id", data.id)));
                }
                if (data.text != null && !data.text.isEmpty()) {
                    if ("SSR".equals(data.rare)) {
                        UniqueText = data.text;
                    } else {
                        if (data.type == GachaType.Credits) {
                            if (CreditText == null) {
                                CreditText = data.text;
                            }
                        } else {
                            if (Text == null || Text.isEmpty()) {
                                Text = data.text;
                            }
                        }
                    }
                } else {
                    Text = "";
                }
            }

            if (UniqueText != null) {
                text.addPara(UniqueText);
            } else {
                if (Text != null && !Text.isEmpty()) {
                    text.addPara(Text);
                } else {
                    if (CreditText != null && !CreditText.isEmpty()) {
                        text.addPara(CreditText);
                    }
                }
            }
        }
        
        // 发放兑奖券（基于消耗的珠子类型）
        if (gachaMemoryItem != null && totalDraws > 0) {
            grantTicketsAfterGacha(cargo, text, gachaMemoryItem, totalDraws);
        }
        if (rewardReceived) {
            Moci_CampaignFeedback.playRewardReceived();
        }
    }

    public boolean hasItem(String id) {
        return dataMap.containsKey(id);
    }

    public boolean hasOfficer(String id) {
        return Officer_Recorder.contains(id);
    }

    public void addItem(String id, Moci_GachaData data, float weight, String rare) {
//        if (id.equals("UniqueShip") || id.equals("NormalShip") || id.equals("Credits") || id.equals("Others")) {
//            id += Misc.genUID();
//        }
        if ("SR".equals(rare)) {
            SR_Picker.add(id, weight);
            dataMap.put(id, data);
        } else if ("SSR".equals(rare)) {
            SSR_Picker.add(id, weight);
            dataMap.put(id, data);
        } else {
            R_Picker.add(id, weight);
            dataMap.put(id, data);
        }
    }

    private void addOfficer(String category, String id, officerData data) {
        if (!Officer_Map.containsKey(category)) {
            Officer_Map.put(category, new HashMap<String, officerData>());
        }
        Map<String, officerData> map = Officer_Map.get(category);
        map.put(id, data);
        Officer_Recorder.add(id);
    }

    public void removeItem(String id, boolean forGacha) {
        R_Picker.remove(id);
        SR_Picker.remove(id);
        SSR_Picker.remove(id);
        if (!forGacha) {
            dataMap.remove(id);
        }
    }

    public void removeOfficer(String id) {
        for (Map<String, officerData> map : Officer_Map.values()) {
            map.remove(id);
        }
        Officer_Recorder.remove(id);
    }

    public enum GachaType {
        Commodity,
        SpecialItem,
        ShipID,
        Credits,
        Weapon,
        Fighter,
        OFFICER
    }

    public static class Moci_GachaData {

        public final String id;
        public final String name;
        public final GachaType type;
        public final String itemID;
        public final String param;
        // public final boolean unique;
        public final float min;
        public final float max;
        public final String text;
        private final int maxUse;
        private final String rare;
        private final String sellReqItemId;
        private final int sellReqItemNum;

        public Moci_GachaData(String id, String name, GachaType type, String itemID, String param, float min, float max, String text, int maxUse, String rare, String sellReqItemId, int sellReqItemNum) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.itemID = itemID;
            this.maxUse = maxUse;
            this.rare = rare;
            if (param != null && param.isEmpty()) {
                this.param = null;
            } else {
                this.param = param;
            }
            //  this.unique = unique;
            this.min = min;
            this.max = max;
            this.text = text;
            this.sellReqItemId = sellReqItemId;
            this.sellReqItemNum = sellReqItemNum;
        }

        public int getItemValue() {
            if (max != 0) {
                if (max == min) {
                    return (int) max;
                } else {
                    return Math.max(1, (int) (Math.abs(max - min) * Math.random() + min));
                }
            }
            return 1;
        }
    }

    public static void loadGachaData(Moci_GachaManager manager) throws JSONException, IOException {
        Logger log = Global.getLogger(Moci_GachaManager.class);

        String file = "data/config/Moci_Gacha.csv";
        JSONArray csv = Global.getSettings().loadCSV(file);
        for (int x = 0; x < csv.length(); x++) {
            String id = csv.getJSONObject(x).getString("id");
            if (id == null || id.isEmpty() || id.startsWith("#")) {
                //  log.error("loadGachaData - Empty ID");
                continue;
            }
            boolean override = csv.getJSONObject(x).optBoolean("update_when_load", false);
            if (manager.hasItem(id)) {
                if (!override) {
                    continue;
                } else {
                    manager.removeItem(id, false);
                }
            }
            String typeString = csv.getJSONObject(x).getString("type");
            GachaType type;
            if (typeString == null || typeString.isEmpty()) {
                log.error("loadGachaData - Empty Type - id: " + id);
                continue;
            } else {
                switch (typeString) {
                    case "Commodity":
                        type = GachaType.Commodity;
                        break;
                    case "SpecialItem":
                        type = GachaType.SpecialItem;
                        break;
                    case "ShipId":
                        type = GachaType.ShipID;
                        break;
                    case "Credits":
                        type = GachaType.Credits;
                        break;
                    case "Weapon":
                        type = GachaType.Weapon;
                        break;
                    case "Fighter":
                        type = GachaType.Fighter;
                        break;
                    case "Officer":
                        type = GachaType.OFFICER;
                        break;
                    default:
                        log.error("loadGachaData - Unknow Type - id: " + id);
                        continue;
                }
            }

            String itemid = csv.getJSONObject(x).getString("item_id");
            if (itemid.isEmpty() && type != GachaType.Credits) {
                log.error("loadGachaData - Empty ItemID - id: " + id);
                continue;
            } else {
                manager.removeOfficer(id);
            }
            String param = csv.getJSONObject(x).getString("param");
            if (param.isEmpty()) {
                param = null;
            }
            //   boolean unique = csv.getJSONObject(x).optBoolean("unique", false);
            String text = csv.getJSONObject(x).getString("text");
            int min = csv.getJSONObject(x).optInt("min_value", 1);
            int max = csv.getJSONObject(x).optInt("max_value", 1);
            float weight = (float) csv.getJSONObject(x).optDouble("weight", 1);
            int maxUse = csv.getJSONObject(x).optInt("max_uses", 1);
            String rare = csv.getJSONObject(x).getString("rare");
            if (!"SSR".equals(rare) && !"SR".equals(rare)) {
                rare = "R";
            }
            String name = csv.getJSONObject(x).optString("name");
            String sellReqItemId = csv.getJSONObject(x).optString("require_item_in_shop", "");
            int sellReqItemNum = csv.getJSONObject(x).optInt("shop_require_num", 1);
            
            // 读取兑奖券价格（如果配置了 ticket_price 列）
            int ticketPrice = csv.getJSONObject(x).optInt("ticket_price", 0);
            
            // 如果配置了 ticket_price，使用兑奖券作为兑换货币
            if (ticketPrice > 0) {
                sellReqItemId = "Moci_GachaTicket";
                sellReqItemNum = ticketPrice;
            } 
            // 如果没有配置 ticket_price，但配置了珠子价格，自动换算为兑奖券
            else if (!sellReqItemId.isEmpty() && 
                     (sellReqItemId.equals("Moci_Vorttear") || 
                      sellReqItemId.equals("Moci_Mournscale") || 
                      sellReqItemId.equals("Moci_Dracaryx"))) {
                int multiplier = switch (sellReqItemId) {
                    case "Moci_Vorttear" -> 1;
                    case "Moci_Mournscale" -> 5;
                    case "Moci_Dracaryx" -> 10;
                    default -> 0;
                };
                sellReqItemId = "Moci_GachaTicket";
                sellReqItemNum = sellReqItemNum * multiplier;
                log.debug("自动换算商品 " + id + " 价格为兑奖券: " + sellReqItemNum);
            }
            
            // 验证配置有效性
            if (!sellReqItemId.isEmpty() && sellReqItemNum <= 0) {
                log.warn("商品 " + id + " 价格配置无效，使用默认价格 10");
                sellReqItemNum = 10;
            }
            
            manager.addItem(id, new Moci_GachaData(id, name, type, itemid, param, min, max, text, maxUse, rare, sellReqItemId, sellReqItemNum), weight, rare);
        }
        file = "data/config/Moci_Gacha_Officer.csv";
        csv = Global.getSettings().loadCSV(file);
        for (int x = 0; x < csv.length(); x++) {
            String id = csv.getJSONObject(x).getString("id");
            if (id == null || id.isEmpty() || id.startsWith("#")) {
                //  log.error("loadGachaData - Empty ID");
                continue;
            }
            String category = csv.getJSONObject(x).getString("category");
            if (category == null || category.isEmpty() || category.startsWith("#")) {
                //  log.error("loadGachaData - Empty ID");
                continue;
            }
            if (manager.hasOfficer(id)) {
                if (!csv.getJSONObject(x).optBoolean("update_when_load", false)) {
                    continue;
                }
            }
            String name = csv.getJSONObject(x).optString("name", "无效姓名");
            String lastName = csv.getJSONObject(x).optString("last_name", "无效姓名");
            String sprite = csv.getJSONObject(x).optString("sprite", null);
            if (sprite == null || sprite.isEmpty()) {
                log.warn("军官 " + id + " 没有配置默认头像，跳过");
                continue;
            }
            
            // 加载默认头像纹理
            if (sprite.contains("/")) {
                try {
                    Global.getSettings().loadTexture(sprite);
                } catch (Exception e) {
                    log.warn("加载军官 " + id + " 的默认头像失败: " + sprite, e);
                }
            }
            FullName.Gender gender = FullName.Gender.MALE;
            String genderString = csv.getJSONObject(x).optString("gender", "MALE");
            if ("FEMAIL".equals(genderString)) {
                gender = FullName.Gender.FEMALE;
            }
            if ("ANY".equals(genderString)) {
                gender = FullName.Gender.ANY;
            }
            int level = csv.getJSONObject(x).optInt("level", 1);
            int maxLevel = csv.getJSONObject(x).optInt("max_level", -1);
            int maxElite = csv.getJSONObject(x).optInt("max_elite", -1);
            Set<String> skills = getStringSet(csv.getJSONObject(x).optString("skills", ""));
            Set<String> eliteSkills = getStringSet(csv.getJSONObject(x).optString("elite_skills", ""));
            String personality = csv.getJSONObject(x).optString("personality", Personalities.STEADY);
            if (!Personalities.CAUTIOUS.equals(personality) && !Personalities.TIMID.equals(personality) && !Personalities.RECKLESS.equals(personality) && !Personalities.AGGRESSIVE.equals(personality)) {
                personality = Personalities.STEADY;
            }
            String hireText = csv.getJSONObject(x).optString("hire_text");
            if (hireText != null && hireText.isEmpty()) {
                hireText = null;
            }
            officerData data = new officerData(id, lastName, name, sprite,
                                              gender, level, maxLevel, maxElite, skills, eliteSkills, personality, hireText);
            manager.addOfficer(category, id, data);
        }

    }

    public static Set<String> getStringSet(String string) {
        Set<String> set = null;
        //    log.info("///////Loading String Set//////");
        if (string != null && !string.isEmpty()) {
            set = new HashSet<>();
            String[] parts = string.split(",");
            for (String s : parts) {
                while (s.startsWith(" ")) {
                    s = s.substring(1);
                }
                while (s.endsWith(" ")) {
                    s = s.substring(0, s.length() - 1);
                }
                if (!s.isEmpty()) {
                    set.add(s);
                }
            }
        } else {
            set = new HashSet<>();
        }
        return set;
    }

    /**
     * 查找有效的舰船变体ID
     * 优先级：_Standard > _Base > 原始hull id
     * @param hullId 舰船hull id
     * @return 有效的变体ID，如果都不存在则返回null
     */
    private static String findValidVariantId(String hullId) {
        // 尝试_Standard变体
        String standardVariant = hullId + "_Standard";
        if (Global.getSettings().getVariant(standardVariant) != null) {
            return standardVariant;
        }
        
        // 尝试_Base变体
        String baseVariant = hullId + "_Base";
        if (Global.getSettings().getVariant(baseVariant) != null) {
            return baseVariant;
        }
        
        // 最后尝试原始hull id（某些mod可能直接使用hull id作为变体名）
        if (Global.getSettings().getVariant(hullId) != null) {
            return hullId;
        }
        
        // 都不存在则返回null
        return null;
    }

    public String getGachaItemName(String id) {
        Moci_GachaData data = dataMap.get(id);
        if (data == null) {
            return "不存在的商品： " + id;
        }
        if (data.name == null || data.name.isEmpty()) {
            return "无名商品： " + id;
        }
        return data.name;
    }

    public void createGachaItemTooltip(TooltipMakerAPI tooltip, boolean expanded, CargoTransferHandlerAPI transferHandler, Object stackSource, String id) {
        float pad = 3f;
//        float opad = 10f;
//        float small = 5f;
//        Color h = Misc.getHighlightColor();
//        Color g = Misc.getGrayColor();
//        Color b = Misc.getButtonTextColor();
//        b = Misc.getPositiveHighlightColor();
//        Moci_GachaData data = dataMap.get(id);
        tooltip.addPara(getGachaItemName(id), pad);
        Moci_GachaData data = dataMap.get(id);
        if (data.sellReqItemId != null && !data.sellReqItemId.isEmpty()) {
            tooltip.addPara("售价", 10);
            id = data.sellReqItemId;
            CommoditySpecAPI spec = Global.getSettings().getCommoditySpec(id);
            if (spec == null) {
                tooltip.addPara("星币: " + data.sellReqItemNum, 10);
            } else {
                TooltipMakerAPI image = tooltip.beginImageWithText(Global.getSettings().getCommoditySpec(id).getIconName(), 40f);
                image.addPara(spec.getName() + " " + data.sellReqItemNum + " 单位", 10f);
                tooltip.addImageWithText(10);
            }
        }
    }

    public void renderGachaItem(float x, float y, float w, float h, float alphaMult,
                                float glowMult, SpecialItemPlugin.SpecialItemRendererAPI renderer, String id) {
        float X = w / 2f;
        float Y = h / 2f;
        float cx = x + X;
        float cy = y + Y;


        float blX = x;//cx - 30f;
        float blY = y;//cy - 15f;
        float tlX = x;//cx - 20f;
        float tlY = y + h;//cy + 26f;
        float trX = x + w;//cx + 23f;
        float trY = y + h;//cy + 26f;
        float brX = x + w;//cx + 15f;
        float brY = y;//cy - 18f;

        Moci_GachaData data = dataMap.get(id);
        SpriteAPI nothing = Global.getSettings().getSprite("default_sell_Item", "bpOverlayShip"); //啥都没有的时候渲染的图

        if (data.type == GachaType.Weapon) {
            String weaponId = data.itemID;
            renderer.renderWeaponWithCorners(weaponId, blX, blY, tlX, tlY, trX, trY, brX, brY, alphaMult, glowMult * 0.5f, false);
        } else if (data.type == GachaType.Fighter) {
            FighterWingSpecAPI spec = Global.getSettings().getFighterWingSpec(data.itemID);
            if (spec == null) {
                nothing.renderWithCorners(blX, blY, tlX, tlY, trX, trY, brX, brY);
            } else {
                String hullId = spec.getVariant().getHullSpec().getHullId();
                renderer.renderShipWithCorners(hullId, null, blX, blY, tlX, tlY, trX, trY, brX, brY,
                        alphaMult, glowMult * 0.5f, false);
            }
        } else if (data.type == GachaType.ShipID) {
            String hullId = data.itemID;
            renderer.renderShipWithCorners(hullId, null, blX, blY, tlX, tlY, trX, trY, brX, brY,
                    alphaMult, glowMult * 0.5f, false);
        } else if (data.type == GachaType.Commodity) {
            CommoditySpecAPI spec = Global.getSettings().getCommoditySpec(data.itemID);
            if (spec != null) {
                nothing = Global.getSettings().getSprite(spec.getIconName());
            }
            nothing.renderWithCorners(blX, blY, tlX, tlY, trX, trY, brX, brY);
        } else if (data.type == GachaType.SpecialItem) {
            SpecialItemSpecAPI spec = Global.getSettings().getSpecialItemSpec(data.itemID);
            if (spec != null) {
                nothing = Global.getSettings().getSprite(spec.getIconName());
            }
            nothing.renderWithCorners(blX, blY, tlX, tlY, trX, trY, brX, brY);
            if (spec != null) {
                if (spec.getId().equals("ship_bp")) {
                    renderShipBP(x, y, w, h, alphaMult, glowMult, renderer, data.param);
                } else if (spec.getId().equals("weapon_bp")) {
                    renderWeaponBP(x, y, w, h, alphaMult, glowMult, renderer, data.param);
                } else if (spec.getId().equals("fighter_bp")) {
                    renderFighterBP(x, y, w, h, alphaMult, glowMult, renderer, data.param);
                } else if (spec.getId().equals("industry_bp")) {
                    renderIndustryBP(x, y, w, h, alphaMult, glowMult, renderer, data.param);
                }
            }
        }

    }

//    public static List<String> getStringList(String string) {
//        List<String> list = null;
//        //    log.info("///////Loading String Set//////");
//        if (string != null && !string.isEmpty()) {
//            list = new ArrayList<>();
//            String[] parts = string.split(",");
//            for (String s : parts) {
//                while (s.startsWith(" ")) {
//                    s = s.substring(1);
//                }
//                while (s.endsWith(" ")) {
//                    s = s.substring(0, s.length() - 1);
//                }
//                if (!s.isEmpty()) {
//                    list.add(s);
//                }
//            }
//        } else {
//            list = new ArrayList<>();
//        }
//        return list;
//    }

    private static class officerData {
        private final String id;
        private final String lastName;
        private final String name;
        private final String sprite;          // 默认头像
        private final FullName.Gender gender;
        private final int level;
        private final int maxLevel;
        private final int maxElite;
        private final Set<String> skills;
        private final Set<String> eliteSkills;
        private final String personality;
        private final String hireText;

        private officerData(String id, String lastName, String name, String sprite,
                           FullName.Gender gender, int level, int maxLevel, int maxElite, 
                           Set<String> skills, Set<String> eliteSkills, String personality, String hireText) throws IOException {
            this.id = id;
            this.lastName = lastName;
            this.name = name;
            this.sprite = sprite;
            this.gender = gender;
            this.level = level;
            this.maxLevel = maxLevel;
            this.maxElite = maxElite;
            this.skills = skills;
            this.eliteSkills = eliteSkills;
            this.personality = personality;
            this.hireText = hireText;
        }

        public PersonAPI genOfficer() {
            PersonAPI person = Global.getSector().getFaction(Factions.INDEPENDENT).createRandomPerson();
            person.setName(new FullName(name, lastName, gender));
            person.setPersonality(personality);
            person.setPortraitSprite(sprite);
            person.setId(id);
            MutableCharacterStatsAPI stats = person.getStats();
            stats.setLevel(level);
            if (!skills.isEmpty()) {
                for (String skill : skills) {
                    stats.setSkillLevel(skill, 1);
                }
            }
            if (!eliteSkills.isEmpty()) {
                for (String skill : eliteSkills) {
                    stats.setSkillLevel(skill, 2);
                }
            }
            MemoryAPI memory = person.getMemoryWithoutUpdate();
            if (maxLevel > 0) {
                memory.set(MemFlags.OFFICER_MAX_LEVEL, maxLevel);
            }
            if (maxElite > 0) {
                memory.set(MemFlags.OFFICER_MAX_ELITE_SKILLS, maxElite);
            }

            return person;
        }
    }

    public void renderShipBP(float x, float y, float w, float h, float alphaMult,
                             float glowMult, SpecialItemPlugin.SpecialItemRendererAPI renderer, String hullId) {
        float cx = x + w / 2f;
        float cy = y + h / 2f;

        float blX = cx - 30f;
        float blY = cy - 15f;
        float tlX = cx - 20f;
        float tlY = cy + 26f;
        float trX = cx + 23f;
        float trY = cy + 26f;
        float brX = cx + 15f;
        float brY = cy - 18f;


        boolean known = Global.getSector().getPlayerFaction().knowsShip(hullId);

        float mult = 1f;
        //if (known) mult = 0.5f;

        Color bgColor = Global.getSector().getPlayerFaction().getDarkUIColor();
        bgColor = Misc.setAlpha(bgColor, 255);

        //float b = Global.getSector().getCampaignUI().getSharedFader().getBrightness() * 0.25f;
        renderer.renderBGWithCorners(bgColor, blX, blY, tlX, tlY, trX, trY, brX, brY,
                alphaMult * mult, glowMult * 0.5f * mult, false);
        renderer.renderShipWithCorners(hullId, null, blX, blY, tlX, tlY, trX, trY, brX, brY,
                alphaMult * mult, glowMult * 0.5f * mult, !known);


        SpriteAPI overlay = Global.getSettings().getSprite("ui", "bpOverlayShip");
        overlay.setColor(Color.green);
        overlay.setColor(Global.getSector().getPlayerFaction().getBrightUIColor());
        overlay.setAlphaMult(alphaMult);
        overlay.setNormalBlend();
        renderer.renderScanlinesWithCorners(blX, blY, tlX, tlY, trX, trY, brX, brY, alphaMult, false);


        if (known) {
            renderer.renderBGWithCorners(Color.black, blX, blY, tlX, tlY, trX, trY, brX, brY,
                    alphaMult * 0.5f, 0f, false);
        }


        overlay.renderWithCorners(blX, blY, tlX, tlY, trX, trY, brX, brY);
    }

    public void renderWeaponBP(float x, float y, float w, float h, float alphaMult,
                               float glowMult, SpecialItemPlugin.SpecialItemRendererAPI renderer, String weaponId) {
        float cx = x + w / 2f;
        float cy = y + h / 2f;

        float blX = cx - 31f;
        float blY = cy - 16f;
        float tlX = cx - 22f;
        float tlY = cy + 27f;
        float trX = cx + 23f;
        float trY = cy + 27f;
        float brX = cx + 15f;
        float brY = cy - 19f;

        boolean known = Global.getSector().getPlayerFaction().knowsWeapon(weaponId);

        float mult = 1f;

        Color bgColor = Global.getSector().getPlayerFaction().getDarkUIColor();
//		if (weapon.getType() == WeaponType.BALLISTIC) {
//			bgColor = Misc.getBallisticMountColor();
//		} else if (weapon.getType() == WeaponType.ENERGY) {
//			bgColor = Misc.getEnergyMountColor();
//		} else if (weapon.getType() == WeaponType.MISSILE) {
//			bgColor = Misc.getMissileMountColor();
//		}
        //bgColor = new Color(200,0,0);

        bgColor = Misc.setAlpha(bgColor, 255);
        renderer.renderBGWithCorners(bgColor, blX, blY, tlX, tlY, trX, trY, brX, brY,
                alphaMult * mult, glowMult * 0.5f * mult, false);
        //weaponId = "hurricane";
        //weaponId = "plasma";
        //weaponId = "amblaster";
        renderer.renderWeaponWithCorners(weaponId, blX, blY, tlX, tlY, trX, trY, brX, brY,
                alphaMult * mult, glowMult * 0.5f * mult, !known);

        SpriteAPI overlay = Global.getSettings().getSprite("ui", "bpOverlayWeapon");
        overlay.setColor(Color.green);
        overlay.setColor(Global.getSector().getPlayerFaction().getBrightUIColor());
        overlay.setAlphaMult(alphaMult);
        overlay.setNormalBlend();
        renderer.renderScanlinesWithCorners(blX, blY, tlX, tlY, trX, trY, brX, brY, alphaMult, false);

        if (known) {
            renderer.renderBGWithCorners(Color.black, blX, blY, tlX, tlY, trX, trY, brX, brY,
                    alphaMult * 0.5f, 0f, false);
        }

        //renderer.renderScanlinesWithCorners(blX, blY, tlX, tlY, trX, trY, brX, brY, alphaMult, false);

        overlay.renderWithCorners(blX, blY, tlX, tlY, trX, trY, brX, brY);
    }

    public void renderFighterBP(float x, float y, float w, float h, float alphaMult,
                                float glowMult, SpecialItemPlugin.SpecialItemRendererAPI renderer, String wingId) {
        float cx = x + w / 2f;
        float cy = y + h / 2f;

        w = 40;
        h = 40;

        float blX = cx - 24f;
        float blY = cy - 17f;
        float tlX = cx - 14f;
        float tlY = cy + 26f;
        float trX = cx + 28f;
        float trY = cy + 25f;
        float brX = cx + 20f;
        float brY = cy - 18f;

        boolean known = Global.getSector().getPlayerFaction().knowsFighter(wingId);

        float mult = 1f;

        Color bgColor = Global.getSector().getPlayerFaction().getDarkUIColor();
        bgColor = Misc.setAlpha(bgColor, 255);
        renderer.renderBGWithCorners(bgColor, blX, blY, tlX, tlY, trX, trY, brX, brY,
                alphaMult * mult, glowMult * 0.5f * mult, false);
        renderer.renderShipWithCorners(wingId, null, blX, blY, tlX, tlY, trX, trY, brX, brY,
                alphaMult * mult, glowMult * 0.5f * mult, !known);

        SpriteAPI overlay = Global.getSettings().getSprite("ui", "bpOverlayFighter");
        overlay.setColor(Color.green);
        overlay.setColor(Global.getSector().getPlayerFaction().getBrightUIColor());
        overlay.setAlphaMult(alphaMult);
        overlay.setNormalBlend();
        renderer.renderScanlinesWithCorners(blX, blY, tlX, tlY, trX, trY, brX, brY, alphaMult, false);

        if (known) {
            renderer.renderBGWithCorners(Color.black, blX, blY, tlX, tlY, trX, trY, brX, brY,
                    alphaMult * 0.5f, 0f, false);
        }

        //renderer.renderScanlinesWithCorners(blX, blY, tlX, tlY, trX, trY, brX, brY, alphaMult, false);

        overlay.renderWithCorners(blX, blY, tlX, tlY, trX, trY, brX, brY);
    }

    public void renderIndustryBP(float x, float y, float w, float h, float alphaMult,
                                 float glowMult, SpecialItemPlugin.SpecialItemRendererAPI renderer, String industryId) {
        float cx = x + w / 2f;
        float cy = y + h / 2f;

        float blX = cx - 25f;
        float blY = cy - 14f;
        float tlX = cx - 30f;
        float tlY = cy + 16f;
        float trX = cx + 24f;
        float trY = cy + 22f;
        float brX = cx + 30f;
        float brY = cy - 6f;
        IndustrySpecAPI industry = Global.getSettings().getIndustrySpec(industryId);
        SpriteAPI sprite = Global.getSettings().getSprite(industry.getImageName());
        boolean known = Global.getSector().getPlayerFaction().knowsIndustry(industryId);

        float mult = 1f;

        sprite.setAlphaMult(alphaMult * mult);
        sprite.setNormalBlend();
        sprite.renderWithCorners(blX, blY, tlX, tlY, trX, trY, brX, brY);

        if (glowMult > 0) {
            sprite.setAlphaMult(alphaMult * glowMult * 0.5f * mult);
            sprite.setAdditiveBlend();
            sprite.renderWithCorners(blX, blY, tlX, tlY, trX, trY, brX, brY);
        }

        if (known) {
            renderer.renderBGWithCorners(Color.black, blX, blY, tlX, tlY, trX, trY, brX, brY,
                    alphaMult * 0.5f, 0f, false);
        }

        renderer.renderScanlinesWithCorners(blX, blY, tlX, tlY, trX, trY, brX, brY, alphaMult, false);
    }

    private static String text(String key) {
        return Moci_TextLoader.getText(TEXT_ID, key);
    }

    private static String formatText(String key, Map<String, String> replacements) {
        return Moci_TextLoader.getTextWithReplacements(TEXT_ID, key, replacements);
    }

    private static Map<String, String> mapOf(String... values) {
        return Moci_TextLoader.mapOf(values);
    }
}
