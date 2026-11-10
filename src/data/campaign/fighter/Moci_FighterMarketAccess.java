package data.campaign.fighter;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.CoreUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.SubmarketPlugin.TransferAction;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.ui.HintPanelAPI;

/**
 * 航空中队编辑器的市场访问规则：只有玩家当前真正在与某个市场交互时，该市场的子市场才算
 * 「可购买来源」，并且必须遵守与游戏原版市场界面相同的交易模式、启用状态与禁售规则。
 *
 * <p>交易模式来自交互目标 memory 里的 {@code $tradeMode}（原版 {@code MarketPostOpen} 系列规则写入）：
 * {@code OPEN} 公开交易、{@code SNEAK} 关闭应答器潜入、{@code NONE} 不可交易。原版规则写入的是
 * {@link String}，但为兼容其它脚本也接受 {@link CoreUITradeMode} 实例；读不到或无法识别一律按
 * {@code NONE} 处理，绝不把非交易状态当成可购买市场。
 *
 * <p>这里的调用都是普通 getter 或原版市场界面同样会直接调用的插件方法（原版 WeaponPickerDialog
 * 对 updateCargoPrePlayerInteraction / isEnabled / isIllegalOnSubmarket 都不做异常保护），所以只做
 * 判空，不兜底；唯一的例外见 {@link #isTradingAllowed}。
 */
public final class Moci_FighterMarketAccess {

    /** 排查日志前缀：沿用装配服务的前缀风格，常驻输出，不依赖 devMode。 */
    static final String LOG_PREFIX = "Moci-AirSquadron|loadout|";
    /** 原版市场规则写入交易模式用的 memory 键。 */
    static final String TRADE_MODE_KEY = "$tradeMode";

    private Moci_FighterMarketAccess() {}

    /** 玩家当前正在交互的市场；没有打开的市场交互对话框时返回 null。 */
    public static MarketAPI interactionMarket() {
        SectorEntityToken target = interactionTarget();
        return target == null ? null : target.getMarket();
    }

    /** 玩家当前正在交互的目标（市场、舰队或其它实体）；没有交互对话框时返回 null。 */
    public static SectorEntityToken interactionTarget() {
        SectorAPI sector = Global.getSector();
        if (sector == null) return null;
        CampaignUIAPI ui = sector.getCampaignUI();
        if (ui == null) return null;
        InteractionDialogAPI dialog = ui.getCurrentInteractionDialog();
        return dialog == null ? null : dialog.getInteractionTarget();
    }

    /** 当前交互市场的真实交易模式；没有交互目标或读不到 {@code $tradeMode} 时为 {@code NONE}。 */
    public static CoreUITradeMode tradeMode() {
        SectorEntityToken target = interactionTarget();
        if (target == null) return CoreUITradeMode.NONE;
        MemoryAPI memory = target.getMemoryWithoutUpdate();
        if (memory == null || !memory.contains(TRADE_MODE_KEY)) return CoreUITradeMode.NONE;
        Object raw = memory.get(TRADE_MODE_KEY);
        CoreUITradeMode parsed = parseTradeMode(raw);
        if (parsed == null) {
            log().warn(LOG_PREFIX + "ignoring unexpected " + TRADE_MODE_KEY + " value: " + raw);
            return CoreUITradeMode.NONE;
        }
        return parsed;
    }

    /**
     * 当前交互市场里真正可用于购买的子市场。规则与原版市场界面一致：
     * <ul>
     *   <li>没有交互市场 / 市场隐藏 / 交易模式为 {@code NONE}：空表；</li>
     *   <li>{@code SNEAK}：只允许黑市；</li>
     *   <li>{@code OPEN}：按 {@link SubmarketPlugin#isEnabled(CoreUIAPI)} 过滤（军队市场还要求声望）；</li>
     *   <li>跳过空子市场、隐藏子市场和玩家仓库（仓库单独作为 {@code STORAGE} 来源）。</li>
     * </ul>
     * 通过过滤的子市场在返回前各刷新一次库存（{@link SubmarketPlugin#updateCargoPrePlayerInteraction()}），
     * 与原版进入子市场界面时一致，保证读到的是玩家交互时刻的库存。
     */
    public static List<SubmarketAPI> marketSubmarkets(MarketAPI market) {
        List<SubmarketAPI> result = new ArrayList<>();
        if (market == null) return result;
        CoreUITradeMode mode = tradeMode();
        if (mode == CoreUITradeMode.NONE) return result;
        // 与 Service.displaySources / nativeSources 保持一致：隐藏市场（海盗基地等）暂不作为编辑器来源。
        if (market.isHidden()) return result;
        List<SubmarketAPI> submarkets = market.getSubmarketsCopy();
        if (submarkets == null) return result;
        for (SubmarketAPI submarket : submarkets) {
            if (submarket == null) continue;
            if (Submarkets.SUBMARKET_STORAGE.equals(submarket.getSpecId())) continue;
            SubmarketPlugin plugin = submarket.getPlugin();
            if (plugin == null || plugin.isHidden()) continue;
            if (!isTradingAllowed(plugin, mode)) continue;
            // 每个子市场本次调用只刷新一次，且必须在读取库存之前。
            plugin.updateCargoPrePlayerInteraction();
            result.add(submarket);
        }
        return result;
    }

    /** 该子市场在当前交易模式下是否允许买入（不校验具体商品）。 */
    public static boolean isTradable(SubmarketAPI submarket) {
        if (submarket == null) return false;
        CoreUITradeMode mode = tradeMode();
        if (mode == CoreUITradeMode.NONE) return false;
        SubmarketPlugin plugin = submarket.getPlugin();
        if (plugin == null || plugin.isHidden()) return false;
        return isTradingAllowed(plugin, mode);
    }

    /**
     * 该武器是否允许在该子市场买入：调用原版的 {@code isIllegalOnSubmarket(stack, PLAYER_BUY)}，
     * 与改装页武器选择器使用同一个重载（军队市场的声望/委任要求、被封锁的商品都在这里生效）。
     * 未注册的武器 id 按「禁售」处理，宁可不买也不绕过限制。
     */
    public static boolean isLegalPurchase(SubmarketAPI submarket, String weaponId) {
        if (submarket == null) return false;
        if (weaponId == null || weaponId.isEmpty()) return true;
        SubmarketPlugin plugin = submarket.getPlugin();
        if (plugin == null) return false;
        // 先确认武器已注册：引擎构造武器 CargoStack 时会对未知 id 抛异常。
        if (Moci_FighterLoadoutService.weapon(weaponId) == null) return false;
        CargoStackAPI stack = weaponStack(weaponId);
        if (stack != null) {
            return !plugin.isIllegalOnSubmarket(stack, TransferAction.PLAYER_BUY);
        }
        return !plugin.isIllegalOnSubmarket(weaponId, TransferAction.PLAYER_BUY);
    }

    private static boolean isTradingAllowed(SubmarketPlugin plugin, CoreUITradeMode mode) {
        if (mode == null || mode == CoreUITradeMode.NONE) return false;
        // 潜入时原版只开放黑市：普通/军队市场在 SNEAK 下都不允许交易。
        if (mode == CoreUITradeMode.SNEAK) return plugin.isBlackMarket();
        try {
            return plugin.isEnabled(coreUI(mode));
        } catch (NullPointerException | ClassCastException ex) {
            // 不能事先验证：原版界面传给 isEnabled 的是真实的 CoreUI，这里在核心界面回调之外拿不到它，
            // 只能传只回答交易模式的替身（getHintPanel 为 null）。原版实现只读交易模式；第三方插件若
            // 读替身的提示面板或把它强转成具体界面类，会在替身上抛出这两类异常，此时退回原版的
            // 「黑市总是可用」判断。插件自身的其它异常照常抛出，与原版界面一致。
            log().warn(LOG_PREFIX + "isEnabled rejected the trade-mode stand-in; falling back to black-market-only check", ex);
            return plugin.isBlackMarket();
        }
    }

    private static CoreUITradeMode parseTradeMode(Object raw) {
        if (raw instanceof CoreUITradeMode mode) return mode;
        if (raw instanceof String text) {
            String trimmed = text.trim();
            for (CoreUITradeMode candidate : CoreUITradeMode.values()) {
                if (candidate.name().equalsIgnoreCase(trimmed)) return candidate;
            }
        }
        return null;
    }

    /**
     * 原版 {@link SubmarketPlugin#isEnabled(CoreUIAPI)} 只读 {@code getTradeMode()}；这里给它一个只回答
     * 交易模式的普通实现，不依赖「当前打开的是原版市场界面」。
     *
     * <p>不能改用 {@code java.lang.reflect.Proxy}：游戏的脚本类加载器拒绝加载 {@code java.lang.reflect}
     * 下的大多数类，链接失败抛出的是 {@link LinkageError} 而不是 RuntimeException。
     */
    private static CoreUIAPI coreUI(CoreUITradeMode mode) {
        return new TradeModeOnly(mode);
    }

    private static final class TradeModeOnly implements CoreUIAPI {
        private final CoreUITradeMode mode;

        TradeModeOnly(CoreUITradeMode mode) {
            this.mode = mode;
        }

        @Override
        public CoreUITradeMode getTradeMode() {
            return mode;
        }

        @Override
        public HintPanelAPI getHintPanel() {
            return null;
        }
    }

    /** 已注册武器的单件货物栈；调用方先确认武器已注册。工厂尚未初始化时返回 null。 */
    private static CargoStackAPI weaponStack(String weaponId) {
        if (Global.getFactory() == null) return null;
        return Global.getFactory().createCargoStack(CargoAPI.CargoItemType.WEAPONS, weaponId, null);
    }

    private static org.apache.log4j.Logger log() {
        return Global.getLogger(Moci_FighterMarketAccess.class);
    }
}
