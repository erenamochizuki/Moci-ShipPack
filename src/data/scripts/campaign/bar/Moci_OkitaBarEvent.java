package data.scripts.campaign.bar;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BaseBarEvent;
import com.fs.starfarer.api.util.Misc;
import data.scripts.impl.Moci_TulipColonyManager;
import data.scripts.impl.Moci_CampaignFeedback;
import data.scripts.util.Moci_TextLoader;

import java.util.Map;

public class Moci_OkitaBarEvent extends BaseBarEvent {
    private static final String TEXT_ID = "Moci_CampaignText";
    private static final String TULIP_MARKET_ID = "market_moci_tulip";
    private static final String OKITA_CONTACT_ID = "moci_tulip_contact_okita";
    private static final String OKITA_BAR_MET_KEY = "$moci_okita_bar_met";
    private static final String OKITA_BAR_MET_PERSISTENT_KEY = "Moci_OkitaTulipBarMet";
    private static final String OKITA_PORTRAIT = "graphics/portraits/bounty/Moci_Okita.png";

    private enum OptionId {
        MAIN,
        APPROACH,
        ACCEPT,
        DECLINE,
        LEAVE
    }

    private MarketAPI market;
    private PersonAPI okita;

    @Override
    public boolean shouldShowAtMarket(MarketAPI market) {
        return market != null
                && TULIP_MARKET_ID.equals(market.getId())
                && !hasMetOkitaAtBar();
    }

    @Override
    public void addPromptAndOption(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
        if (dialog == null || dialog.getInteractionTarget() == null) {
            return;
        }
        dialog.getTextPanel().addPara(text("okita_bar.prompt"));
        dialog.getOptionPanel().addOption(text("okita_bar.option_approach"), this);
    }

    @Override
    public void init(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
        super.init(dialog, memoryMap);
        done = false;
        noContinue = false;

        market = dialog.getInteractionTarget() != null ? dialog.getInteractionTarget().getMarket() : null;
        okita = getDisplayPerson(market);
        if (okita != null) {
            dialog.getInteractionTarget().setActivePerson(okita);
            dialog.getVisualPanel().saveCurrentVisual();
            dialog.getVisualPanel().showPersonInfo(okita, false, true);
        }

        showMainScreen();
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        super.optionSelected(optionText, optionData);
        if (!(optionData instanceof OptionId)) {
            return;
        }

        OptionId option = (OptionId) optionData;
        if (option == OptionId.MAIN) {
            showMainScreen();
            return;
        }
        if (option == OptionId.APPROACH) {
            showConversationOffer();
            return;
        }
        if (option == OptionId.ACCEPT) {
            resolveAcceptedConversation();
            return;
        }
        if (option == OptionId.DECLINE) {
            text.addPara(text("okita_bar.decline_text"));
            showReturnOnly();
            return;
        }
        if (option == OptionId.LEAVE) {
            if (dialog != null && dialog.getInteractionTarget() != null) {
                dialog.getInteractionTarget().setActivePerson(null);
            }
            done = true;
            noContinue = true;
        }
    }

    private void showMainScreen() {
        if (okita != null) {
            dialog.getInteractionTarget().setActivePerson(okita);
            dialog.getVisualPanel().showPersonInfo(okita, false, true);
        }

        text.addPara(text("okita_bar.main_text"), Misc.getStoryOptionColor());

        options.clearOptions();
        options.addOption(text("okita_bar.option_approach"), OptionId.APPROACH);
        dialog.setOptionColor(OptionId.APPROACH, Misc.getStoryOptionColor());
        options.addOption(text("common.return_to_bar"), OptionId.LEAVE);
    }

    private void showConversationOffer() {
        if (okita != null) {
            dialog.getInteractionTarget().setActivePerson(okita);
            dialog.getVisualPanel().showPersonInfo(okita, false, true);
        }

        text.addPara(text("okita_bar.offer_text"));

        options.clearOptions();
        options.addOption(text("okita_bar.option_accept"), OptionId.ACCEPT);
        dialog.setOptionColor(OptionId.ACCEPT, Misc.getStoryOptionColor());
        options.addOption(text("okita_bar.option_decline"), OptionId.DECLINE);
    }

    private void resolveAcceptedConversation() {
        boolean firstMeeting = !hasMetOkitaAtBar();
        Moci_TulipColonyManager.notifyOkitaBarMet();
        if (firstMeeting && hasMetOkitaAtBar()) {
            Moci_CampaignFeedback.playContactReceived();
        }

        text.addPara(text("okita_bar.accept_text_1"));
        text.addPara(text("okita_bar.accept_text_2"));
        text.addPara(text("okita_bar.contact_received"), Misc.getHighlightColor());

        options.clearOptions();
        options.addOption(text("common.return_to_bar"), OptionId.LEAVE);
    }

    private void showReturnOnly() {
        options.clearOptions();
        options.addOption(text("common.continue"), OptionId.MAIN);
        options.addOption(text("common.return_to_bar"), OptionId.LEAVE);
    }

    private static boolean hasMetOkitaAtBar() {
        if (Global.getSector() == null) {
            return false;
        }
        if (Global.getSector().getMemoryWithoutUpdate().getBoolean(OKITA_BAR_MET_KEY)) {
            return true;
        }
        Object persistentFlag = Global.getSector().getPersistentData().get(OKITA_BAR_MET_PERSISTENT_KEY);
        return persistentFlag instanceof Boolean && (Boolean) persistentFlag;
    }

    private PersonAPI getDisplayPerson(MarketAPI market) {
        PersonAPI existing = getExistingContact(market);
        if (existing != null) {
            return existing;
        }

        if (market == null) {
            return null;
        }

        PersonAPI temp = market.getFaction().createRandomPerson();
        temp.setId("moci_okita_bar_temp");
        temp.getName().setFirst("Juzo");
        temp.getName().setLast("Okita");
        temp.setGender(FullName.Gender.MALE);
        temp.setPortraitSprite(OKITA_PORTRAIT);
        temp.setRankId("spaceMarshal");
        temp.setPostId("Moci_Liaison_Officer");
        return temp;
    }

    private PersonAPI getExistingContact(MarketAPI market) {
        if (market == null) {
            return null;
        }
        for (PersonAPI person : market.getPeopleCopy()) {
            if (OKITA_CONTACT_ID.equals(person.getId())) {
                return person;
            }
        }
        return null;
    }

    private static String text(String key) {
        return Moci_TextLoader.getText(TEXT_ID, key);
    }
}
