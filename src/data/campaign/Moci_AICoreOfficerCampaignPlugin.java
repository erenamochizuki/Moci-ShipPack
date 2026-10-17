package data.campaign;

import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.campaign.AICoreOfficerPlugin;
import com.fs.starfarer.api.campaign.BaseCampaignPlugin;

/**
 * Moci AI核心战役插件
 * 负责注册自定义AI核心（舰长）和管理员
 */
public class Moci_AICoreOfficerCampaignPlugin extends BaseCampaignPlugin {

    @Override
    public boolean isTransient() {
        return false;
    }

    @Override
    public PluginPick<AICoreOfficerPlugin> pickAICoreOfficerPlugin(String commodityId) {
        
        if ("Moci_AICore_Haro".equals(commodityId)) {
            return new PluginPick<AICoreOfficerPlugin>(new Moci_HaroAICorePlugin(), PickPriority.MOD_SPECIFIC);
        }
        
        if ("Moci_AICore_Omega_Lexington".equals(commodityId)) {
            return new PluginPick<AICoreOfficerPlugin>(new Moci_OmegaLexingtonAICorePlugin(), PickPriority.MOD_SPECIFIC);
        }
        
        return null;
    }
    
    @Override
    public PluginPick<com.fs.starfarer.api.campaign.AICoreAdminPlugin> pickAICoreAdminPlugin(String commodityId) {
        
        if ("Moci_AICore_Omega_Lexington".equals(commodityId)) {
            return new PluginPick<com.fs.starfarer.api.campaign.AICoreAdminPlugin>(
                new Moci_OmegaLexingtonAdminPlugin(), PickPriority.MOD_SPECIFIC);
        }
        
        return null;
    }
}
