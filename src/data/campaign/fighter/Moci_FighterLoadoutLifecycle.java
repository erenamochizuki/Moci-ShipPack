package data.campaign.fighter;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 自定义联队的存档生命周期。
 *
 * <p>存档必须自包含（H2）：自定义联队的定义保存在 saves/common 的注册库里，
 * 只复制存档文件、清理 common 目录或换一台机器后，存档里的联队就找不到定义。所以每次存档
 * 把本存档引用到的定义写进 {@link Global#getSector()} 的 persistent data（纯字符串键值，mod 卸载后存档仍可读），
 * 读档时把注册库里缺失的定义补回来。
 *
 * <p>这里的入口是引擎的存档/读档钩子，最外层拦截 {@link RuntimeException} 和 {@link LinkageError}
 * （脚本类加载器拒绝某个类时抛出）：钩子里抛异常会直接中断玩家的存档或读档。
 */
public final class Moci_FighterLoadoutLifecycle {
    private static final String LOG_PREFIX = "Moci-AirSquadron|lifecycle|";
    /** persistent data 里存内嵌注册库 JSON 的键。 */
    public static final String CATALOG_KEY = "moci_fighter_loadout_catalog";
    private static final List<String> notices = new ArrayList<>();

    private Moci_FighterLoadoutLifecycle() {}

    /**
     * 存档前：只把本存档引用到的自定义联队定义写进存档。
     * 内存里没有的定义从存档原有的内嵌副本里取；两边都没有的记 warn。本存档没有引用任何自定义联队时删掉内嵌副本。
     * 常驻日志，便于排查。
     */
    public static void beforeGameSave() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) return;
            Map<String, Object> data = sector.getPersistentData();
            Set<String> referenced = referencedCustomWings(sector);
            if (referenced.isEmpty()) {
                if (data.remove(CATALOG_KEY) != null) {
                    log().info(LOG_PREFIX + "save references no custom fighter wing; removed the embedded catalog");
                }
                return;
            }
            Object previous = data.get(CATALOG_KEY);
            Set<String> unresolved = new TreeSet<>();
            String json = Moci_FighterLoadoutStore.exportJson(referenced,
                    previous instanceof String text ? text : null, unresolved);
            data.put(CATALOG_KEY, json);
            log().info(LOG_PREFIX + "embedded fighter catalog into save: "
                    + (referenced.size() - unresolved.size()) + " of " + referenced.size()
                    + " referenced definition(s), " + json.length() + " chars");
            if (!unresolved.isEmpty()) {
                log().warn(LOG_PREFIX + "save references custom fighter wings with no definition in memory or in the save: "
                        + String.join(", ", unresolved));
            }
        } catch (RuntimeException | LinkageError ex) {
            // 引擎存档钩子的最外层：扫描或导出失败时保留存档里原有的内嵌副本，不能让玩家存不了档。
            log().warn(LOG_PREFIX + "cannot embed fighter catalog into save", ex);
        }
    }

    /**
     * 本存档引用到的自定义联队 ID：玩家舰队每艘船（含模块）的机库、玩家货舱里的 LPC 和封存舰船，
     * 以及每个市场每个子市场货舱里的 LPC 和封存舰船。孤儿 ID（没有定义的）也算在内，由调用方报告。
     */
    static Set<String> referencedCustomWings(SectorAPI sector) {
        Set<String> ids = new TreeSet<>();
        CampaignFleetAPI fleet = sector.getPlayerFleet();
        if (fleet != null) {
            collectMembers(fleet.getFleetData(), ids);
            collectCargo(fleet.getCargo(), ids);
        }
        EconomyAPI economy = sector.getEconomy();
        if (economy != null) {
            for (MarketAPI market : economy.getMarketsCopy()) {
                for (SubmarketAPI submarket : market.getSubmarketsCopy()) {
                    // getCargoNullOk：玩家没打开过的子市场还没有货舱，不能为了扫描去创建它。
                    collectCargo(submarket.getCargoNullOk(), ids);
                }
            }
        }
        return ids;
    }

    private static void collectCargo(CargoAPI cargo, Set<String> ids) {
        if (cargo == null) return;
        for (CargoAPI.CargoItemQuantity<String> stack : cargo.getFighters()) {
            if (Moci_FighterLoadoutService.isCustomWingId(stack.getItem())) ids.add(stack.getItem());
        }
        // getMothballedShips 只有调用过 initMothballedShips 的货舱才有（原版 CoreScript 同样先判空）。
        collectMembers(cargo.getMothballedShips(), ids);
    }

    private static void collectMembers(FleetDataAPI members, Set<String> ids) {
        if (members == null) return;
        for (FleetMemberAPI member : members.getMembersListCopy()) collectVariant(member.getVariant(), ids);
    }

    private static void collectVariant(ShipVariantAPI variant, Set<String> ids) {
        if (variant == null) return;
        for (String wingId : variant.getFittedWings()) {
            if (Moci_FighterLoadoutService.isCustomWingId(wingId)) ids.add(wingId);
        }
        // getModuleSlots 在船体规格或 stationModules 为 null 时直接抛 RuntimeException（HullVariantSpec 实现），先判这两项。
        if (variant.getHullSpec() == null) return;
        Map<String, String> modules = variant.getStationModules();
        if (modules == null || modules.isEmpty()) return;
        // 与原版 FleetEncounterContext.lootWings 相同：遍历模块槽，没有装模块的槽返回 null。
        for (String slot : variant.getModuleSlots()) collectVariant(variant.getModuleVariant(slot), ids);
    }

    /** 读档（含读 autosave/中途读档）：内嵌副本回灌 → 注册定义 → 安装市场守卫；任何一步失败都不挡后面的步骤。 */
    public static void onGameLoad(boolean newGame) {
        synchronized (notices) { notices.clear(); }
        int restored = 0;
        int definitions = -1;
        try {
            SectorAPI sector = Global.getSector();
            Object embedded = sector == null ? null : sector.getPersistentData().get(CATALOG_KEY);
            if (embedded instanceof String text && !text.isEmpty()) {
                restored = Moci_FighterLoadoutStore.importMissing(text);
            }
        } catch (RuntimeException | LinkageError ex) {
            // 引擎读档钩子：回灌失败不能挡住后面的注册和市场守卫。
            log().warn(LOG_PREFIX + "cannot read embedded fighter catalog", ex);
        }
        try {
            Moci_FighterLoadoutService.registerAllFromStore();
            definitions = Moci_FighterLoadoutStore.size();
        } catch (RuntimeException | LinkageError ex) {
            // 引擎读档钩子：注册失败只影响本局的自定义联队，不能让读档中断，也不能跳过市场守卫。
            log().error(LOG_PREFIX + "cannot register custom fighter wings", ex);
        }
        if (restored > 0) {
            addNotice("航空中队管理：从存档内嵌副本恢复了 " + restored + " 条自定义联队定义");
        }
        try {
            Moci_FighterMarketGuard.install();
        } catch (RuntimeException | LinkageError ex) {
            // 引擎读档钩子的最后一步：守卫装不上只记录，不能让读档中断。
            log().warn(LOG_PREFIX + "cannot install market guard", ex);
        }
        log().info(LOG_PREFIX + "game load done: newGame=" + newGame
                + " restored=" + restored + " definitions=" + definitions);
    }

    /** 协调器在安全的时机取一条待显示的提示；没有则返回 null。 */
    public static String pollNotice() {
        synchronized (notices) {
            return notices.isEmpty() ? null : notices.remove(0);
        }
    }

    private static void addNotice(String notice) {
        synchronized (notices) { notices.add(notice); }
        log().info(LOG_PREFIX + "notice: " + notice);
    }

    private static org.apache.log4j.Logger log() {
        return Global.getLogger(Moci_FighterLoadoutLifecycle.class);
    }
}
