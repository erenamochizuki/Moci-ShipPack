package data.campaign.fighter;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.campaign.listeners.ColonyInteractionListener;
import com.fs.starfarer.api.campaign.listeners.ListenerManagerAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.loading.VariantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 市场守卫：编辑过的自定义联队不应该停留在市场里。
 *
 * <p>自定义联队（{@code moci_flo_*}）本身已经带 no_drop / no_sell / restricted，不会被市场自然刷出，
 * 但玩家仍然可以主动把「自定义 LPC」或「装着自定义联队的母舰」卖给市场，让编辑过的编队
 * 变成市场货架上的商品。所以这里在玩家打开、关闭市场以及完成交易时，
 * 把市场侧子市场（玩家仓库与免费转移的子市场除外；隐藏市场照常处理）里的自定义联队换回原型联队，
 * 并把相对原型多投入的武器退回玩家货舱——卖价本来就按原型 baseValue 计，玩家不会因此多拿或少拿钱。
 *
 * <p>只动市场货舱与市场待售舰船（连同舰船模块），绝不改玩家自己舰队里的联队。
 * 能用 API 事先判断的输入（空引用、规格是否存在、有没有模块）都先判断；异常拦截只留在
 * {@link #install()} 和 {@link #sanitizeMarket(MarketAPI)} 两个入口边界，四个监听回调都经由后者，所以入口都不抛异常。
 */
public final class Moci_FighterMarketGuard implements ColonyInteractionListener {
    private static final String LOG_PREFIX = "Moci-AirSquadron|market|";
    private static Moci_FighterMarketGuard installed;
    /** 只报一次的消息键（缺原型、定义已消失），避免每次开关市场都刷同一条日志。 */
    private static final Set<String> reportedOnce = new LinkedHashSet<>();

    private Moci_FighterMarketGuard() {}

    /** 读档时调用；幂等，重复读档也只保留一个监听器。 */
    public static void install() {
        SectorAPI sector = Global.getSector();
        if (sector == null) return;
        ListenerManagerAPI listeners = sector.getListenerManager();
        if (listeners == null) return;
        if (installed == null) installed = new Moci_FighterMarketGuard();
        try {
            listeners.removeListenerOfClass(Moci_FighterMarketGuard.class);
            listeners.addListener(installed, true);
        } catch (RuntimeException ex) {
            // 读档入口：ListenerManager 没有「能否注册」的查询接口，注册失败只能事后记录，不能让读档中断。
            warn("cannot install market guard", ex);
        }
    }

    @Override
    public void reportPlayerOpenedMarket(MarketAPI market) {
        sanitizeMarket(market);
    }

    @Override
    public void reportPlayerOpenedMarketAndCargoUpdated(MarketAPI market) {
        sanitizeMarket(market);
    }

    @Override
    public void reportPlayerClosedMarket(MarketAPI market) {
        sanitizeMarket(market);
    }

    @Override
    public void reportPlayerMarketTransaction(PlayerMarketTransaction transaction) {
        // getSubmarket / getMarket 都只是读字段，判空即可；清理本身由 sanitizeMarket 拦截异常。
        SubmarketAPI submarket = transaction == null ? null : transaction.getSubmarket();
        sanitizeMarket(submarket == null ? null : submarket.getMarket());
    }

    /**
     * 清理一个市场的所有非仓库子市场；返回处理过的条目数。
     *
     * <p>这是四个监听回调共用的异常边界。子市场插件和货舱可能来自其他 mod，首次触碰本 mod 的
     * Service / Store 也可能在脚本类加载器里链接失败（LinkageError 不是 RuntimeException），这些都无法事先验证，
     * 穿出去会打断引擎的市场回调。拦下后本次清理停止，已完成的转换和退款保持有效，下次开关市场接着处理。
     */
    public static int sanitizeMarket(MarketAPI market) {
        if (market == null) return 0;
        int cleaned = 0;
        try {
            CargoAPI playerCargo = playerCargo();
            if (playerCargo == null) {
                // 退款没有去处时整次不换：换了就会让玩家已投入的武器凭空消失。
                warnOnce("no-player-cargo", "skipped market sanitize: player fleet cargo is unavailable");
                return 0;
            }
            // 隐藏市场（海盗基地、卢德教基地）同样能停靠交易，玩家可以把自定义联队卖进去，不能跳过。
            List<SubmarketAPI> submarkets = market.getSubmarketsCopy();
            if (submarkets == null) return 0;
            for (SubmarketAPI submarket : submarkets) {
                if (submarket == null || isPlayerStorage(submarket)) continue;
                // 用 getCargoNullOk 而不是 getCargo：后者会为每个子市场惰性建货舱，
                // 而一个从来没有货舱的子市场不可能装着玩家卖进去的 LPC。
                cleaned += sanitizeCargo(submarket.getCargoNullOk(), market, submarket, playerCargo);
            }
        } catch (RuntimeException | LinkageError ex) {
            // 回调边界：第三方子市场插件、货舱和首次链接失败都无法事先验证（见方法注释），不能穿进引擎的市场回调。
            warn("cannot sanitize market " + nameOf(market), ex);
        }
        return cleaned;
    }

    private static String nameOf(MarketAPI market) {
        String name = market.getName();
        return name == null || name.isEmpty() ? "<unnamed>" : name;
    }

    /**
     * 玩家仓库与免费转移子市场要跳过。第三方插件的 isFreeTransfer 若抛异常，
     * 由 sanitizeMarket 放弃本次清理，不能把「判断失败」当成「不是仓库」去转换玩家自己的东西。
     */
    private static boolean isPlayerStorage(SubmarketAPI submarket) {
        // 玩家仓库里的自定义联队是正常状态（本 mod 的装配流程就靠它中转），必须跳过。
        if (Submarkets.SUBMARKET_STORAGE.equals(submarket.getSpecId())) return true;
        // 免费转移的子市场（殖民地仓库、其它 mod 的仓库类子市场）里放的仍然是玩家自己的东西，不算卖出。
        SubmarketPlugin plugin = submarket.getPlugin();
        return plugin != null && plugin.isFreeTransfer();
    }

    private static int sanitizeCargo(CargoAPI cargo, MarketAPI market, SubmarketAPI submarket, CargoAPI playerCargo) {
        if (cargo == null) return 0;
        int cleaned = 0;
        cleaned += sanitizeFighterStacks(cargo, market, submarket, playerCargo);
        cleaned += sanitizeMothballedShips(cargo, market, submarket, playerCargo);
        return cleaned;
    }

    /** 市场货架上的自定义 LPC → 原型 LPC，并把多投入的武器退回玩家货舱。 */
    private static int sanitizeFighterStacks(CargoAPI cargo, MarketAPI market, SubmarketAPI submarket,
            CargoAPI playerCargo) {
        List<CargoAPI.CargoItemQuantity<String>> fighters = cargo.getFighters();
        if (fighters == null || fighters.isEmpty()) return 0;
        int cleaned = 0;
        // 先拷贝：下面的 removeFighters / addFighters 会改动货舱里的堆叠。
        for (CargoAPI.CargoItemQuantity<String> line : new ArrayList<>(fighters)) {
            if (line == null) continue;
            String wingId = line.getItem();
            int count = line.getCount();
            if (wingId == null || count <= 0 || !Moci_FighterLoadoutService.isCustomWingId(wingId)) continue;
            if (Moci_FighterLoadoutStore.get(wingId) == null) {
                // 找不到定义：不知道原型也算不出退款，换掉会让玩家白丢已投入的武器，宁可保留并报警。
                warnOnce("gone:" + wingId, "kept orphan custom wing on " + nameOf(market) + ": " + wingId);
                continue;
            }
            String prototypeId = Moci_FighterLoadoutService.getBaseWingId(wingId);
            // getFighterWingSpec 对未知 id 返回 null，不抛异常。
            if (prototypeId == null || Global.getSettings().getFighterWingSpec(prototypeId) == null) {
                warnOnce("proto:" + wingId, "kept custom wing on " + nameOf(market)
                        + ": prototype of " + wingId + " is missing");
                continue;
            }
            Map<String, Integer> refund = scaled(Moci_FighterLoadoutService.refundForWing(wingId), count);
            cargo.removeFighters(wingId, count);
            cargo.addFighters(prototypeId, count);
            returnToPlayer(playerCargo, refund);
            cleaned++;
            Global.getLogger(Moci_FighterMarketGuard.class).info(LOG_PREFIX + "market=" + nameOf(market)
                    + " submarket=" + submarket.getSpecId() + " lpc " + wingId + " x" + count
                    + " -> prototype " + prototypeId + " refund=" + refund);
        }
        return cleaned;
    }

    /**
     * 市场待售舰船：母舰本体或任一模块装着自定义联队，就换回原型，并把多投入的武器退回玩家货舱。
     *
     * <p>模块变体要拷贝后再改：{@code getModuleVariant} 只有在这艘船已经持有该槽的独立模块变体时才返回内部实例，
     * 否则按 stationModules 里的变体 id 返回全局共享的变体，原地改会波及所有用它的舰船。改完必须用
     * {@code setModuleVariant} 写回才生效，而它遇到 STOCK 来源的变体会移除该槽、退回共享变体，
     * 所以拷贝要先改成 REFIT（与原版 CoreAutofitPlugin 的做法一致）。
     */
    private static int sanitizeMothballedShips(CargoAPI cargo, MarketAPI market, SubmarketAPI submarket,
            CargoAPI playerCargo) {
        FleetDataAPI ships = cargo.getMothballedShips();
        if (ships == null) return 0;
        int cleaned = 0;
        for (FleetMemberAPI member : ships.getMembersListCopy()) {
            if (member == null || !hasRestorableCustomWing(member)) continue;
            ShipVariantAPI variant = member.getVariant();
            Map<String, Integer> refund = new LinkedHashMap<>();
            boolean changed = hasRestorableCustomWing(variant) && restoreWings(variant, refund);
            for (String slot : moduleSlotsOf(variant)) {
                ShipVariantAPI module = variant.getModuleVariant(slot);
                if (module == null || !hasRestorableCustomWing(module)) continue;
                ShipVariantAPI copy = module.clone();
                if (!restoreWings(copy, refund)) continue;
                if (copy.isStockVariant()) copy.setSource(VariantSource.REFIT);
                variant.setModuleVariant(slot, copy);
                changed = true;
            }
            if (!changed) continue;
            returnToPlayer(playerCargo, refund);
            refreshStats(member);
            cleaned++;
            Global.getLogger(Moci_FighterMarketGuard.class).info(LOG_PREFIX + "market=" + nameOf(market)
                    + " submarket=" + submarket.getSpecId()
                    + " ship=" + member.getShipName() + " restored prototype wings refund=" + refund);
        }
        return cleaned;
    }

    /**
     * 把一个变体里的自定义联队就地换回原型，退款并入 refund；返回联队是否真的变了。
     * restorePrototypeWings 会跳过「原型 wing spec 缺失」的机位并返回空表，这种情况不算清理。
     */
    private static boolean restoreWings(ShipVariantAPI variant, Map<String, Integer> refund) {
        List<String> before = new ArrayList<>(variant.getWings());
        Map<String, Integer> restored = Moci_FighterLoadoutService.restorePrototypeWings(variant);
        restored.forEach((weaponId, count) -> refund.merge(weaponId, count, Integer::sum));
        return !restored.isEmpty() || !before.equals(variant.getWings());
    }

    /** 退款按堆叠数量放大：一次卖出 N 架同款 LPC，就要退 N 份武器。 */
    private static Map<String, Integer> scaled(Map<String, Integer> refund, int count) {
        if (refund == null || refund.isEmpty()) return Map.of();
        if (count <= 1) return refund;
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : refund.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            result.put(entry.getKey(), entry.getValue() * count);
        }
        return result;
    }

    private static void warnOnce(String key, String message) {
        if (!reportedOnce.add(key)) return;
        warn(message, null);
    }

    /**
     * 联队换回原型后刷新舰船数据（机库、OP、售价显示）。updateStats 会跑各 mod 船插的统计代码，没法事先验证；
     * 它若抛异常由 sanitizeMarket 拦下，此时联队与退款都已生效，这艘船下次也不会再被当成待清理。
     */
    private static void refreshStats(FleetMemberAPI member) {
        member.updateStats();
    }

    /** 这艘船的本体或任一模块是否装着定义仍在的自定义联队。 */
    private static boolean hasRestorableCustomWing(FleetMemberAPI member) {
        ShipVariantAPI variant = member.getVariant();
        if (variant == null) return false;
        if (hasRestorableCustomWing(variant)) return true;
        for (String slot : moduleSlotsOf(variant)) {
            ShipVariantAPI module = variant.getModuleVariant(slot);
            if (module != null && hasRestorableCustomWing(module)) return true;
        }
        return false;
    }

    private static boolean hasRestorableCustomWing(ShipVariantAPI variant) {
        List<String> wings = variant.getWings();
        if (wings == null) return false;
        for (String wingId : wings) {
            if (wingId == null || !Moci_FighterLoadoutService.isCustomWingId(wingId)) continue;
            if (Moci_FighterLoadoutStore.get(wingId) != null) return true;
        }
        return false;
    }

    /**
     * 变体的模块槽，没有模块时返回空表。getModuleSlots 在船体规格或 stationModules 为 null 时
     * 会直接抛 RuntimeException，所以先判这两项；未知槽位的 getModuleVariant 返回 null。
     */
    private static List<String> moduleSlotsOf(ShipVariantAPI variant) {
        if (variant.getHullSpec() == null) return List.of();
        Map<String, String> modules = variant.getStationModules();
        if (modules == null || modules.isEmpty()) return List.of();
        return variant.getModuleSlots();
    }

    /** 退还的武器进玩家舰队货舱：这正是玩家当初从货舱里投入的那些武器。 */
    private static void returnToPlayer(CargoAPI playerCargo, Map<String, Integer> refund) {
        if (refund == null || refund.isEmpty()) return;
        for (Map.Entry<String, Integer> entry : refund.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) continue;
            playerCargo.addWeapons(entry.getKey(), entry.getValue());
        }
    }

    private static CargoAPI playerCargo() {
        SectorAPI sector = Global.getSector();
        CampaignFleetAPI fleet = sector == null ? null : sector.getPlayerFleet();
        return fleet == null ? null : fleet.getCargo();
    }

    private static void warn(String message, Throwable failure) {
        if (failure == null) Global.getLogger(Moci_FighterMarketGuard.class).warn(LOG_PREFIX + message);
        else Global.getLogger(Moci_FighterMarketGuard.class).warn(LOG_PREFIX + message, failure);
    }
}
