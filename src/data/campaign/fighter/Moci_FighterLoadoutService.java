package data.campaign.fighter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.util.Misc;
import data.hullmods.Moci_TacticalHullmods;

/** Single-carrier transactions. Global prototypes and cargo LPC stacks are never edited. */
public final class Moci_FighterLoadoutService {
    public static final String HULLMOD_ID = "Moci_air_squadron_manager";
    private static final Set<String> CUSTOM_FIGHTER_TAGS = Set.of(Tags.WING_NO_DROP, Tags.WING_NO_SELL, Tags.RESTRICTED);
    /** 排查日志前缀：装配落库后打印最终 Variant 的 hullMods/槽位，便于逐环核对「装了却没生效」。 */
    private static final String LOG_PREFIX = "Moci-AirSquadron|loadout|";
    private static boolean initialized;
    private static boolean busy;
    private static String failure;
    private static final Map<String, String> definitionIssues = new LinkedHashMap<>();
    // Package-local ports are used only by the recording-API regression harness.
    static Supplier<List<CargoAPI>> inventorySources = Moci_FighterLoadoutService::nativeSources;
    private Moci_FighterLoadoutService() {}
    public record BayEntry(int index, String wingId, FighterWingSpecAPI wing,
            boolean builtIn, boolean eligible, String lockReason, boolean weaponEligible, boolean tacticalEligible) {
        public BayEntry(int index, String wingId, FighterWingSpecAPI wing,
                boolean builtIn, boolean eligible, String lockReason) {
            this(index, wingId, wing, builtIn, eligible, lockReason, eligible, eligible);
        }
    }
    /** A fitting weapon that is present in at least one visible player-accessible source. */
    public record WeaponAvailability(WeaponSpecAPI spec, int cargo, int storage,
            int market, int blackMarket) {
        public int total() {
            return Math.addExact(Math.addExact(cargo, storage), Math.addExact(market, blackMarket));
        }
        public boolean isAvailable() { return cargo > 0 || storage > 0 || market > 0 || blackMarket > 0; }
    }
    private enum WeaponSource { CARGO, STORAGE, MARKET, BLACK_MARKET }
    private record DisplayCargo(CargoAPI cargo, WeaponSource source, SubmarketAPI submarket) {}
    /** 一次「应用装配」里需要从市场购买的武器：同一武器可能来自多个市场，按来源拆分。 */
    public record Purchase(SubmarketAPI submarket, String weaponId, int count, int unitPrice) {}
    public static final class RefitPlan {
        public final String memberId;
        public final int bayIndex;
        public final String sourceWingId;
        public final String targetWingId;
        public final Map<String, String> loadout;
        public final Map<String, Integer> consume;
        public final Map<String, Integer> returns;
        public final String blockReason;
        public final boolean hasChanges;
        /** 目标战术改装，每组至多一项。 */
        public final List<String> tacticalHullmods;
        /** 当前战术改装，供 UI 比较草稿。 */
        public final List<String> sourceTacticalHullmods;
        /** 应用时需要从市场购买的武器（货舱/仓库不够的部分）；空表表示无需购买。 */
        public final List<Purchase> purchases;
        /** 购买总价（信用点）；0 表示无需购买。 */
        public final int purchaseCost;
        private final Moci_FighterLoadoutSpec targetSpec;
        private RefitPlan(String memberId, int bayIndex, String sourceWingId,
                String targetWingId, Map<String, String> loadout, Map<String, Integer> consume,
                Map<String, Integer> returns, String blockReason, boolean hasChanges,
                Moci_FighterLoadoutSpec targetSpec, List<String> sourceTacticalHullmods,
                List<String> tacticalHullmods, List<Purchase> purchases, int purchaseCost) {
            this.memberId = memberId; this.bayIndex = bayIndex;
            this.sourceWingId = sourceWingId; this.targetWingId = targetWingId;
            this.loadout = immutable(loadout); this.consume = immutable(consume);
            this.returns = immutable(returns); this.blockReason = blockReason;
            this.hasChanges = hasChanges; this.targetSpec = targetSpec;
            this.sourceTacticalHullmods = List.copyOf(sourceTacticalHullmods);
            this.tacticalHullmods = List.copyOf(tacticalHullmods);
            this.purchases = List.copyOf(purchases);
            this.purchaseCost = purchaseCost;
        }
    }
    private static <K, V> Map<K, V> immutable(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
    public static synchronized void initializeApplication() {
        initialized = true; failure = null; definitionIssues.clear();
        Moci_FighterLoadoutEligibility.initialize();
        Moci_FighterLoadoutStore.initialize();
        if (!Moci_FighterLoadoutRegistry.init()) {
            failure = "联队规格注册入口不可用：" + Moci_FighterLoadoutRegistry.getUnavailableReason();
            return;
        }
        registerAllFromStore();
    }
    public static synchronized void registerAllFromStore() {
        if (!initialized) { initializeApplication(); return; }
        if (!Moci_FighterLoadoutRegistry.init()) return;
        for (Moci_FighterLoadoutSpec spec : Moci_FighterLoadoutStore.all()) {
            try {
                String issue = registerDefinition(spec);
                if (issue != null) definitionIssues.put(spec.getWingId(), issue);
            } catch (Exception ex) {
                definitionIssues.put(spec.getWingId(), "型号恢复失败：" + ex.getMessage());
                Global.getLogger(Moci_FighterLoadoutService.class).warn("Cannot restore " + spec.getWingId(), ex);
            }
        }
    }
    public static boolean isAvailable() { return getUnavailableReason().isEmpty(); }
    public static String getUnavailableReason() {
        if (!initialized) initializeApplication();
        if (failure != null) return failure;
        String configFailure = Moci_FighterLoadoutEligibility.getFailure();
        if (configFailure != null) return configFailure;
        String storeFailure = Moci_FighterLoadoutStore.getFailure();
        return storeFailure == null ? "" : storeFailure;
    }
    public static FleetMemberAPI findMember(String memberId) {
        if (memberId == null || Global.getSector() == null || Global.getSector().getPlayerFleet() == null) return null;
        for (FleetMemberAPI member : Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy()) {
            if (memberId.equals(member.getId())) return member;
        }
        return null;
    }
    public static FighterWingSpecAPI getWingSpec(String wingId) {
        if (wingId == null || wingId.isEmpty() || Global.getSettings() == null) return null;
        try { return Global.getSettings().getFighterWingSpec(wingId); }
        catch (RuntimeException ex) { return null; }
    }
    public static String getBaseWingId(String wingId) {
        if (!isCustomWingId(wingId)) return wingId;
        Moci_FighterLoadoutSpec spec = Moci_FighterLoadoutStore.get(wingId);
        return spec == null ? null : spec.getBaseWingId();
    }
    /** Prefix detection deliberately includes orphan IDs, which must stay locked. */
    public static boolean isCustomWingId(String wingId) {
        return wingId != null && wingId.startsWith(Moci_FighterLoadoutSpec.ID_PREFIX);
    }
    public static List<BayEntry> collectBays(FleetMemberAPI member) {
        List<BayEntry> result = new ArrayList<>();
        if (member == null || member.getVariant() == null) return result;
        ShipVariantAPI variant = member.getVariant();
        int capacity = Math.max(0, member.getNumFlightDecks());
        int count = Math.max(capacity, variant.getWings().size());
        for (int i = 0; i < count; i++) {
            String id = variant.getWingId(i);
            FighterWingSpecAPI wing = getWingSpec(id);
            boolean builtIn = variant.getHullSpec().isBuiltInWing(i);
            String reason = null;
            if (builtIn) reason = "内置联队不可改装";
            else if (i >= capacity) reason = "该机库当前不可用";
            else if (!variant.hasHullMod(HULLMOD_ID)) reason = "需要航空中队管理船插";
            else if (Moci_FighterLoadoutEligibility.getCarrierLockReason(variant) != null) {
                reason = Moci_FighterLoadoutEligibility.getCarrierLockReason(variant);
            }
            else if (id == null || id.isEmpty()) reason = "空机库";
            else if (wing == null) reason = "联队规格缺失，已保留原装配";
            else if (isCustomWingId(id)) reason = validateSource(id);
            FighterWingSpecAPI prototype = getWingSpec(getBaseWingId(id));
            boolean weapons = reason == null && Moci_FighterLoadoutEligibility.acceptsWeapons(prototype)
                    && !getEditableSlots(wing).isEmpty();
            boolean tactical = reason == null && Moci_FighterLoadoutEligibility.acceptsTactical(prototype);
            if (reason == null && !weapons && !tactical) reason = "该联队不支持装配管理";
            result.add(new BayEntry(i, id, wing, builtIn, weapons || tactical, reason, weapons, tactical));
        }
        return result;
    }
    public static List<WeaponSlotAPI> getEditableSlots(FighterWingSpecAPI wing) {
        List<WeaponSlotAPI> result = new ArrayList<>();
        if (wing == null || wing.getVariant() == null || wing.getVariant().getHullSpec() == null) return result;
        ShipHullSpecAPI hull = wing.getVariant().getHullSpec();
        for (WeaponSlotAPI slot : hull.getAllWeaponSlotsCopy()) {
            // A hidden hardpoint/turret is still a normal weapon slot. Visibility is not a lock.
            if (slot != null && slot.getId() != null && slot.isWeaponSlot() && !slot.isSystemSlot() && !slot.isStationModule() && !slot.isDecorative()
                    && !slot.isBuiltIn() && !hull.isBuiltIn(slot.getId())) result.add(slot);
        }
        result.sort(Comparator.comparing(WeaponSlotAPI::getId));
        return result;
    }
    public static LinkedHashMap<String, String> readLoadout(String wingId) {
        FighterWingSpecAPI wing = getWingSpec(wingId);
        return wing == null ? new LinkedHashMap<>() : readVariant(wing.getVariant(), getEditableSlots(wing));
    }
    public static LinkedHashMap<String, String> readPrototypeLoadout(String wingId) {
        String baseId = getBaseWingId(wingId);
        FighterWingSpecAPI prototype = getWingSpec(baseId);
        return prototype == null ? new LinkedHashMap<>()
                : readVariant(prototype.getVariant(), getEditableSlots(prototype), true);
    }
    /** Count only replacements relative to the original prototype, never relative to the last draft. */
    public static Moci_FighterRefitBudget.Budget refitBudget(String wingId, Map<String, String> loadout,
                                                           String tacticalHullmod) {
        return refitBudgetForTacticals(wingId, loadout, tacticalHullmod == null ? List.of() : List.of(tacticalHullmod));
    }
    public static Moci_FighterRefitBudget.Budget refitBudgetForTacticals(String wingId,
            Map<String, String> loadout, List<String> tacticalHullmods) {
        FighterWingSpecAPI prototype = getWingSpec(getBaseWingId(wingId));
        if (prototype == null || prototype.getVariant() == null || loadout == null) {
            throw new IllegalArgumentException("原型联队规格或装配缺失");
        }
        int points = Moci_TacticalHullmods.normalize(tacticalHullmods).size() * Moci_FighterRefitBudget.TACTICAL_POINTS;
        for (WeaponSlotAPI slot : getEditableSlots(prototype)) {
            String id = loadout.get(slot.getId());
            String original = prototype.getVariant().getWeaponId(slot.getId());
            if (id == null || id.isEmpty() || id.equals(original)) continue;
            WeaponSpecAPI spec = weapon(id);
            if (spec == null || spec.getSize() == null) throw new IllegalArgumentException("武器尺寸不可用：" + id);
            int cost = switch (spec.getSize()) {
                case SMALL -> Moci_FighterRefitBudget.SMALL_POINTS;
                case MEDIUM -> Moci_FighterRefitBudget.MEDIUM_POINTS;
                case LARGE -> Moci_FighterRefitBudget.LARGE_POINTS;
            };
            points = Math.addExact(points, cost);
        }
        // A null stats argument reads raw wing-spec OP; custom wings always resolve back to their prototype.
        return new Moci_FighterRefitBudget.Budget(prototype.getOpCost(null), points);
    }
    /**
     * 读取某联队当前生效的战术改装：优先取已注册保存定义里的值，再从联队 variant 的 hullMods 兜底。
     * 返回 null 表示未选；非法值同样返回 null，不抛异常。
     */
    public static String getTacticalHullmod(String wingId) {
        List<String> ids = getTacticalHullmods(wingId);
        return ids.isEmpty() ? null : ids.get(0);
    }
    public static List<String> getTacticalHullmods(String wingId) {
        Moci_FighterLoadoutSpec spec = Moci_FighterLoadoutStore.get(wingId);
        if (spec != null) return Moci_TacticalHullmods.normalize(spec.getTacticalHullmods());
        FighterWingSpecAPI wing = getWingSpec(wingId);
        return wing == null ? List.of() : selectedTactical(wing.getVariant());
    }
    /** UI 用：按规格表顺序列出所有带战术改装标记的船插。 */
    public static List<String> getTacticalHullmodOptions() {
        try { return Moci_TacticalHullmods.all(); }
        catch (Throwable ignored) { return List.of(); }
    }
    /** UI 用：该船插当前是否已注册可用（未注册返回 false，不抛异常）。 */
    public static boolean isTacticalHullmodAvailable(String tacticalHullmodId) {
        return isTacticalAvailable(tacticalHullmodId);
    }
    /** UI 与提交校验共用的适用条件；null 表示可以安装。 */
    public static String tacticalUnavailableReason(String id, ShipVariantAPI variant) {
        if (!isTacticalAvailable(id)) return "当前不可用。";
        if (Moci_TacticalHullmods.HARDENED_SHIELD.equals(id)) {
            com.fs.starfarer.api.combat.ShieldAPI.ShieldType type = variant == null || variant.getHullSpec() == null
                    ? null : variant.getHullSpec().getShieldType();
            if (type != com.fs.starfarer.api.combat.ShieldAPI.ShieldType.FRONT
                    && type != com.fs.starfarer.api.combat.ShieldAPI.ShieldType.OMNI) {
                return "该舰载机没有护盾，无法安装强化护盾。";
            }
        }
        return null;
    }
    /** 战术改装 tag 判断：helper 类缺失或未初始化时一律按 false 处理。 */
    private static boolean isTacticalSafe(String hullmodId) {
        if (hullmodId == null || hullmodId.isBlank()) return false;
        try { return Moci_TacticalHullmods.isTactical(hullmodId); }
        catch (Throwable ignored) { return false; }
    }
    /** 船插是否已在 Settings 里注册；未注册的 ID 不写进新定义，避免战斗内效果缺失。 */
    private static boolean isTacticalAvailable(String hullmodId) {
        if (!isTacticalSafe(hullmodId)) return false;
        try { return Moci_TacticalHullmods.spec(hullmodId) != null; }
        catch (Throwable ignored) { return false; }
    }
    /** Replace both tactical groups together; failures propagate to transaction rollback. */
    private static void applyTactical(ShipVariantAPI variant, List<String> ids) {
        Moci_TacticalHullmods.applySelections(variant, ids);
    }
    private static List<String> selectedTactical(ShipVariantAPI variant) {
        return Moci_TacticalHullmods.selectedAll(variant);
    }
    private static LinkedHashMap<String, String> readVariant(ShipVariantAPI variant, List<WeaponSlotAPI> slots) {
        return readVariant(variant, slots, false);
    }
    /**
     * Reads the editable portion of a variant. A stock hull can contain an old
     * or malformed weapon/slot pair; when requested, expose that pair as empty
     * so a draft can replace it instead of being rejected before it is applied.
     */
    private static LinkedHashMap<String, String> readVariant(ShipVariantAPI variant, List<WeaponSlotAPI> slots,
                                                               boolean clearInvalid) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (variant != null) for (WeaponSlotAPI slot : slots) {
            String id = Objects.toString(variant.getWeaponId(slot.getId()), "");
            if (clearInvalid && !id.isEmpty() && !canFitSlot(slot, id)) id = "";
            result.put(slot.getId(), id);
        }
        return result;
    }

    /** True when every editable stock weapon is legal for its declared slot. */
    private static boolean legalPrototypeLoadout(FighterWingSpecAPI prototype, List<WeaponSlotAPI> slots) {
        if (prototype == null || prototype.getVariant() == null) return false;
        for (WeaponSlotAPI slot : slots) {
            String id = prototype.getVariant().getWeaponId(slot.getId());
            if (id != null && !id.isEmpty() && !canFitSlot(slot, id)) return false;
        }
        return true;
    }
    private static WeaponSpecAPI weapon(String id) {
        if (id == null || id.isEmpty()) return null;
        try { return Global.getSettings().getWeaponSpec(id); }
        catch (RuntimeException ex) { return null; }
    }
    public static boolean canFitSlot(WeaponSlotAPI slot, String id) {
        WeaponSpecAPI spec = weapon(id);
        return fits(slot, spec);
    }
    private static boolean fits(WeaponSlotAPI slot, WeaponSpecAPI spec) {
        if (slot == null || spec == null || !slot.isWeaponSlot() || slot.isSystemSlot()
                || slot.isStationModule() || slot.isDecorative() || slot.isBuiltIn()) return false;
        if (spec.getAIHints() == null || !spec.getAIHints().contains(WeaponAPI.AIHints.SYSTEM)) {
            return slot.weaponFits(spec);
        }
        // Moci fighter arm weapons use SYSTEM as an AI/availability hint despite being
        // ordinary ENERGY/BALLISTIC mounts. The native fitter rejects that hint before
        // checking size/type. Reproduce its public mount/size rules directly so this
        // script never needs reflection or a dynamic proxy (both are forbidden here).
        return fitsIgnoringSystemHint(slot, spec);
    }
    private static boolean fitsIgnoringSystemHint(WeaponSlotAPI slot, WeaponSpecAPI spec) {
        if (spec.getSize().ordinal() > slot.getSlotSize().ordinal()) return false;
        WeaponAPI.WeaponType mountType = spec.getMountType();
        if (mountType == WeaponAPI.WeaponType.STATION_MODULE
                || mountType == WeaponAPI.WeaponType.SYSTEM) return false;
        WeaponAPI.WeaponType slotType = slot.getWeaponType();
        boolean exact = mountType == slotType;
        boolean compatible = false;
        compatible |= mountType == WeaponAPI.WeaponType.HYBRID
                && (slotType == WeaponAPI.WeaponType.ENERGY
                || slotType == WeaponAPI.WeaponType.BALLISTIC
                || slotType == WeaponAPI.WeaponType.UNIVERSAL
                || slotType == WeaponAPI.WeaponType.HYBRID);
        compatible |= mountType == WeaponAPI.WeaponType.SYNERGY
                && (slotType == WeaponAPI.WeaponType.ENERGY
                || slotType == WeaponAPI.WeaponType.MISSILE
                || slotType == WeaponAPI.WeaponType.UNIVERSAL
                || slotType == WeaponAPI.WeaponType.SYNERGY);
        compatible |= mountType == WeaponAPI.WeaponType.COMPOSITE
                && (slotType == WeaponAPI.WeaponType.BALLISTIC
                || slotType == WeaponAPI.WeaponType.MISSILE
                || slotType == WeaponAPI.WeaponType.UNIVERSAL
                || slotType == WeaponAPI.WeaponType.COMPOSITE);
        compatible |= mountType == WeaponAPI.WeaponType.UNIVERSAL
                && (slotType == WeaponAPI.WeaponType.BALLISTIC
                || slotType == WeaponAPI.WeaponType.MISSILE
                || slotType == WeaponAPI.WeaponType.ENERGY
                || slotType == WeaponAPI.WeaponType.HYBRID
                || slotType == WeaponAPI.WeaponType.COMPOSITE
                || slotType == WeaponAPI.WeaponType.SYNERGY);
        compatible |= slotType == WeaponAPI.WeaponType.HYBRID
                && (mountType == WeaponAPI.WeaponType.ENERGY
                || mountType == WeaponAPI.WeaponType.BALLISTIC);
        compatible |= slotType == WeaponAPI.WeaponType.SYNERGY
                && (mountType == WeaponAPI.WeaponType.ENERGY
                || mountType == WeaponAPI.WeaponType.MISSILE);
        compatible |= slotType == WeaponAPI.WeaponType.COMPOSITE
                && (mountType == WeaponAPI.WeaponType.BALLISTIC
                || mountType == WeaponAPI.WeaponType.MISSILE);
        compatible |= slotType == WeaponAPI.WeaponType.UNIVERSAL
                && (mountType == WeaponAPI.WeaponType.BALLISTIC
                || mountType == WeaponAPI.WeaponType.MISSILE
                || mountType == WeaponAPI.WeaponType.ENERGY);
        if (spec.isRestrictToSpecifiedMountType()) compatible = false;
        if (!exact && !compatible) return false;
        if (exact && spec.getSize().ordinal() < slot.getSlotSize().ordinal() - 1) return false;
        return exact || !compatible || spec.getSize().ordinal() == slot.getSlotSize().ordinal();
    }
    public static List<WeaponSpecAPI> listFittingWeapons(WeaponSlotAPI slot) {
        List<WeaponSpecAPI> result = new ArrayList<>();
        if (slot == null || Global.getSettings() == null) return result;
        for (WeaponSpecAPI spec : Global.getSettings().getAllWeaponSpecs()) {
            if (spec != null && spec.getWeaponId() != null && fits(slot, spec)) result.add(spec);
        }
        result.sort(Comparator.comparing((WeaponSpecAPI w) -> Objects.toString(w.getType(), ""))
                .thenComparing(w -> Objects.toString(w.getSize(), ""))
                .thenComparing(w -> Objects.toString(w.getWeaponName(), w.getWeaponId())));
        return result;
    }

    /**
     * Returns only fitting weapons that can currently be seen in the player's
     * cargo, accessible storage, or the market at the current interaction.
     * Market entries are display-only; the existing refit transaction still
     * consumes cargo/storage and will report a shortage before mutating anything.
     */
    public static List<WeaponAvailability> listAvailableFittingWeapons(WeaponSlotAPI slot) {
        if (slot == null || Global.getSettings() == null) return List.of();
        List<DisplayCargo> sources = displaySources();
        if (sources.isEmpty()) return List.of();
        List<WeaponAvailability> result = new ArrayList<>();
        for (WeaponSpecAPI spec : Global.getSettings().getAllWeaponSpecs()) {
            if (spec == null || spec.getWeaponId() == null || !fits(slot, spec)) continue;
            int cargo = 0, storage = 0, market = 0, blackMarket = 0;
            for (DisplayCargo source : sources) {
                int count = safeQuantity(source.cargo(), spec.getWeaponId());
                switch (source.source()) {
                    case CARGO -> cargo = Math.addExact(cargo, count);
                    case STORAGE -> storage = Math.addExact(storage, count);
                    case MARKET -> market = Math.addExact(market, count);
                    case BLACK_MARKET -> blackMarket = Math.addExact(blackMarket, count);
                }
            }
            if (cargo > 0 || storage > 0 || market > 0 || blackMarket > 0) {
                result.add(new WeaponAvailability(spec, cargo, storage, market, blackMarket));
            }
        }
        result.sort(Comparator.comparing((WeaponAvailability w) -> Objects.toString(w.spec().getType(), ""))
                .thenComparing(w -> Objects.toString(w.spec().getSize(), ""))
                .thenComparing(w -> Objects.toString(w.spec().getWeaponName(), w.spec().getWeaponId())));
        return result;
    }

    private static int safeQuantity(CargoAPI cargo, String id) {
        try { return quantity(cargo, id); }
        catch (RuntimeException ex) { return 0; }
    }

    private static List<DisplayCargo> displaySources() {
        List<DisplayCargo> result = new ArrayList<>();
        Set<CargoAPI> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) return result;
        addDisplaySource(result, seen, Global.getSector().getPlayerFleet().getCargo(), WeaponSource.CARGO, null);
        MarketAPI market = currentMarket();
        if (market == null || market.isHidden()) return result;
        try {
            if (Misc.playerHasStorageAccess(market)) {
                addDisplaySource(result, seen, Misc.getStorageCargo(market), WeaponSource.STORAGE, null);
            }
        } catch (RuntimeException ex) {
            // Storage access checks can fail for synthetic/immature markets; ignore it.
        }
        List<SubmarketAPI> submarkets;
        try { submarkets = market.getSubmarketsCopy(); }
        catch (RuntimeException ex) { submarkets = null; }
        if (submarkets == null) return result;
        for (SubmarketAPI submarket : submarkets) {
            if (submarket == null || Submarkets.SUBMARKET_STORAGE.equals(submarket.getSpecId())) continue;
            try {
                SubmarketPlugin plugin = submarket.getPlugin();
                if (plugin == null || plugin.isHidden()) continue;
                CargoAPI cargo = submarket.getCargoNullOk();
                if (cargo == null) cargo = plugin.getCargoNullOk();
                WeaponSource source = plugin.isBlackMarket() ? WeaponSource.BLACK_MARKET : WeaponSource.MARKET;
                addDisplaySource(result, seen, cargo, source, submarket);
            } catch (RuntimeException ex) {
                // A market can disappear while its interaction panel is closing.
            }
        }
        return result;
    }

    private static void addDisplaySource(List<DisplayCargo> result, Set<CargoAPI> seen,
            CargoAPI cargo, WeaponSource source, SubmarketAPI submarket) {
        if (cargo != null && seen.add(cargo)) result.add(new DisplayCargo(cargo, source, submarket));
    }

    /** 当前交互市场里的市场/黑市来源（只含可购买的条目），用于价格显示与购买结算。 */
    private static List<DisplayCargo> marketSources() {
        List<DisplayCargo> result = new ArrayList<>();
        for (DisplayCargo source : displaySources()) {
            if (source.source() == WeaponSource.MARKET || source.source() == WeaponSource.BLACK_MARKET) {
                result.add(source);
            }
        }
        return result;
    }

    /**
     * 市场买入单价（信用点）：与游戏原版武器购买一致 ——
     * 基础价值 × {@code shipWeaponBuyPriceMult} × (1 + 关税)，取整；免费转移的市场不加关税。
     */
    public static int marketUnitPrice(WeaponSpecAPI spec, SubmarketAPI submarket) {
        if (spec == null) return 0;
        float mult = 1.2f;
        try { mult = Global.getSettings().getFloat("shipWeaponBuyPriceMult"); }
        catch (RuntimeException ignored) { }
        float tariff = 0f;
        try {
            SubmarketPlugin plugin = submarket == null ? null : submarket.getPlugin();
            if (plugin != null && !plugin.isFreeTransfer()) tariff = Math.max(0f, plugin.getTariff());
        } catch (RuntimeException ignored) { }
        double price = (double) spec.getBaseValue() * mult * (1d + tariff);
        if (!Double.isFinite(price) || price <= 0) return 0;
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, Math.round(price)));
    }

    /** UI 用：当前交互市场里该武器的最低买入单价；没有任何市场来源时返回 0。 */
    public static int bestMarketUnitPrice(WeaponSpecAPI spec) {
        if (spec == null) return 0;
        int best = 0;
        for (DisplayCargo source : marketSources()) {
            int price = marketUnitPrice(spec, source.submarket());
            if (price > 0 && (best == 0 || price < best)) best = price;
        }
        return best;
    }

    /** 玩家当前信用点（只读；异常时返回 0，避免 UI 崩溃）。 */
    private static int playerCredits() {        try {
            CargoAPI cargo = Global.getSector().getPlayerFleet().getCargo();
            float credits = cargo.getCredits().get();
            if (!Float.isFinite(credits) || credits <= 0) return 0;
            return Math.min(Integer.MAX_VALUE, (int) Math.floor(credits));
        } catch (RuntimeException ex) { return 0; }
    }

    private static MarketAPI currentMarket() {
        try {
            if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) return null;
            MarketAPI market = null;
            if (Global.getSector().getCampaignUI() != null) {
                InteractionDialogAPI dialog = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
                SectorEntityToken target = dialog == null ? null : dialog.getInteractionTarget();
                if (target != null) market = target.getMarket();
            }
            if (market == null) {
                SectorEntityToken focus = Global.getSector().getPlayerFleet().getOrbitFocus();
                if (focus != null) market = focus.getMarket();
            }
            return market;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static List<CargoAPI> nativeSources() {
        List<CargoAPI> result = new ArrayList<>();
        if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) return result;
        result.add(Global.getSector().getPlayerFleet().getCargo());
        MarketAPI market = currentMarket();
        try {
            if (market != null && !market.isHidden() && Misc.playerHasStorageAccess(market)) {
                CargoAPI storage = Misc.getStorageCargo(market);
                if (storage != null) result.add(storage);
            }
        } catch (RuntimeException ex) {
            // Synthetic markets used by test harnesses may not implement storage lookups.
        }
        return result;
    }
    private static List<CargoAPI> sources() {
        List<CargoAPI> result = new ArrayList<>();
        Set<CargoAPI> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CargoAPI cargo : inventorySources.get()) if (cargo != null && seen.add(cargo)) result.add(cargo);
        return result;
    }
    private static int quantity(CargoAPI cargo, String id) {
        float value = cargo.getQuantity(CargoAPI.CargoItemType.WEAPONS, id);
        if (!Float.isFinite(value) || value < 0 || value != Math.floor(value)) {
            throw new IllegalStateException("武器库存数量异常：" + id);
        }
        return Math.toIntExact((long) value);
    }
    private static int available(List<CargoAPI> cargos, String id) {
        int count = 0;
        for (CargoAPI cargo : cargos) count = Math.addExact(count, quantity(cargo, id));
        return count;
    }
    public static int getAvailableWeaponCount(String weaponId) {
        if (weaponId == null || weaponId.isEmpty()) return 0;
        try { return available(sources(), weaponId); }
        catch (RuntimeException ex) { return 0; }
    }
    private static Map<String, Integer> countWeapons(Map<String, String> loadout) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (String id : loadout.values()) if (id != null && !id.isEmpty()) count.merge(id, 1, Math::addExact);
        return count;
    }
    static Map<String, Integer> paid(Map<String, String> loadout, Map<String, String> baseline) {
        Map<String, Integer> count = countWeapons(loadout);
        Map<String, Integer> defaults = countWeapons(baseline);
        count.replaceAll((id, amount) -> Math.max(0, amount - defaults.getOrDefault(id, 0)));
        count.values().removeIf(n -> n == 0);
        return count;
    }
    private static void delta(Map<String, Integer> source, Map<String, Integer> target,
            Map<String, Integer> consume, Map<String, Integer> returns) {
        Set<String> ids = new LinkedHashSet<>(source.keySet()); ids.addAll(target.keySet());
        for (String id : ids) {
            int difference = target.getOrDefault(id, 0) - source.getOrDefault(id, 0);
            if (difference > 0) consume.put(id, difference);
            else if (difference < 0) returns.put(id, -difference);
        }
    }
    private static RefitPlan blocked(String memberId, int bay, String source, String reason) {
        return new RefitPlan(memberId, bay, source, source, Map.of(), Map.of(), Map.of(), reason, false,
                null, List.of(), List.of(), List.of(), 0);
    }
    /** 旧签名：战术改装沿用当前值，历史调用方行为不变。 */
    public static RefitPlan preview(String memberId, int bayIndex, String expectedWingId,
            Map<String, String> draft) {
        return previewInternal(memberId, bayIndex, expectedWingId, draft, null, false);
    }
    /**
     * 带战术改装选择的预览：tacticalHullmod 传 null/空串表示明确不带战术改装；
     * 传非法或未注册的 ID 时返回带 blockReason 的计划，绝不抛异常。
     */
    public static RefitPlan preview(String memberId, int bayIndex, String expectedWingId,
            Map<String, String> draft, String tacticalHullmod) {
        return previewInternal(memberId, bayIndex, expectedWingId, draft, tacticalHullmod == null || tacticalHullmod.isBlank() ? List.of() : List.of(tacticalHullmod), true);
    }
    public static RefitPlan previewTactical(String memberId, int bayIndex, String expectedWingId,
            Map<String, String> draft, List<String> tacticalHullmods) {
        return previewInternal(memberId, bayIndex, expectedWingId, draft, tacticalHullmods, true);
    }
    private static RefitPlan previewInternal(String memberId, int bayIndex, String expectedWingId,
            Map<String, String> draft, List<String> tacticalHullmods, boolean explicitTactical) {
        try {
            return previewChecked(memberId, bayIndex, expectedWingId, draft, tacticalHullmods, explicitTactical);
        } catch (RuntimeException ex) {
            Global.getLogger(Moci_FighterLoadoutService.class).warn("Cannot preview fighter refit", ex);
            return blocked(memberId, bayIndex, expectedWingId, "装配数据异常，已保留原装配");
        }
    }
    private static RefitPlan previewChecked(String memberId, int bayIndex, String expectedWingId,
            Map<String, String> draft, List<String> tacticalHullmods, boolean explicitTactical) {
        if (!isAvailable()) return blocked(memberId, bayIndex, expectedWingId, getUnavailableReason());
        FleetMemberAPI member = findMember(memberId);
        if (member == null) return blocked(memberId, bayIndex, expectedWingId, "母舰已不在玩家舰队");
        if (!Objects.equals(member.getVariant().getWingId(bayIndex), expectedWingId)) {
            return blocked(memberId, bayIndex, expectedWingId, "该机库联队已改变，请重新选择");
        }
        BayEntry bay = collectBays(member).stream().filter(b -> b.index == bayIndex).findFirst().orElse(null);
        if (bay == null || !bay.eligible) return blocked(memberId, bayIndex, expectedWingId,
                bay == null ? "机库不存在" : bay.lockReason);
        List<String> sourceTactical = getTacticalHullmods(expectedWingId);
        List<String> targetTactical = explicitTactical ? tacticalHullmods : sourceTactical;
        if (!Moci_TacticalHullmods.validSelection(targetTactical)) {
            return blocked(memberId, bayIndex, expectedWingId, "战术改装不可用或同组重复，请重新选择");
        }
        targetTactical = Moci_TacticalHullmods.normalize(targetTactical);
        if (!bay.tacticalEligible && !targetTactical.equals(sourceTactical)) {
            return blocked(memberId, bayIndex, expectedWingId, "该联队不支持战术改装");
        }
        for (String id : targetTactical) {
            String issue = tacticalUnavailableReason(id, bay.wing.getVariant());
            if (issue != null) return blocked(memberId, bayIndex, expectedWingId, issue);
        }
        if (targetTactical.contains(Moci_TacticalHullmods.ADVANCED_OPTICS)
                && bay.wing.getVariant().hasHullMod(com.fs.starfarer.api.impl.campaign.ids.HullMods.HIGH_SCATTER_AMP)) {
            return blocked(memberId, bayIndex, expectedWingId, "先进光学器件与高散射增幅器不兼容");
        }
        String baseId = getBaseWingId(expectedWingId);
        FighterWingSpecAPI prototype = getWingSpec(baseId);
        List<WeaponSlotAPI> slots = getEditableSlots(prototype);
        Map<String, String> defaults = bay.weaponEligible ? readPrototypeLoadout(baseId)
                : readVariant(prototype.getVariant(), slots);
        Map<String, String> source = isCustomWingId(expectedWingId)
                ? readVariant(bay.wing.getVariant(), slots)
                : defaults;
        Moci_FighterLoadoutSpec sourceSpec = Moci_FighterLoadoutStore.get(expectedWingId);
        Map<String, String> baseline = sourceSpec == null ? defaults : sourceSpec.getBaseline();
        LinkedHashMap<String, String> target = new LinkedHashMap<>();
        if (draft == null || !defaults.keySet().containsAll(draft.keySet())) {
            return blocked(memberId, bayIndex, expectedWingId, "草稿含未知槽位");
        }
        for (WeaponSlotAPI slot : slots) {
            String id = draft.containsKey(slot.getId()) ? draft.get(slot.getId()) : source.get(slot.getId());
            if (id == null) return blocked(memberId, bayIndex, expectedWingId, "草稿武器无效");
            // A stock prototype may still expose a stale weapon ID that no longer
            // fits its slot. Treat that unchanged stock entry as the sanitized
            // empty default; an explicit replacement is still validated normally.
            String rawPrototype = prototype.getVariant().getWeaponId(slot.getId());
            if (bay.weaponEligible && !isCustomWingId(expectedWingId) && id.equals(rawPrototype)
                    && !id.isEmpty() && !canFitSlot(slot, id)) {
                id = defaults.get(slot.getId());
            }
            if (!id.isEmpty() && !canFitSlot(slot, id) && (bay.weaponEligible || !id.equals(source.get(slot.getId())))) {
                return blocked(memberId, bayIndex, expectedWingId, "武器不适配槽位：" + slot.getId());
            }
            target.put(slot.getId(), id);
        }
        if (!bay.weaponEligible && !target.equals(source)) {
            return blocked(memberId, bayIndex, expectedWingId, "该联队不支持武器换装");
        }
        // Validate the same calculation used by the header and the registered wing before any payment.
        refitBudgetForTacticals(baseId, target, targetTactical);
        // 只有“武器回到原型默认且不带战术改装”才算回到原型定义；战术改装本身也参与 digest。
        // Reusing the stock wing is safe only when its editable stock weapons are
        // legal. If a legacy hull declares an invalid default, materialize the
        // sanitized draft as a custom wing so applying the prototype still works.
        boolean vanilla = target.equals(defaults) && targetTactical.isEmpty()
                && (!bay.weaponEligible || legalPrototypeLoadout(prototype, slots));
        Moci_FighterLoadoutSpec targetSpec = vanilla ? null
                : Moci_FighterLoadoutSpec.withTacticals(baseId, baseline, target, targetTactical);
        String targetId = vanilla ? baseId : targetSpec.getWingId();
        boolean changed = !expectedWingId.equals(targetId);
        Map<String, Integer> consume = new LinkedHashMap<>(), returns = new LinkedHashMap<>();
        delta(paid(source, baseline), vanilla ? Map.of() : paid(target, baseline), consume, returns);
        String reason = changed ? null : "装配未改动";
        List<CargoAPI> cargos = sources();
        if (cargos.isEmpty()) reason = "货舱不可用";
        // 采购计划：货舱/仓库不够的部分从当前交互市场购买，并在应用前校验信用点。
        // 只做纯计算，不修改任何库存；真正扣款/转移发生在 applyRefit 里。
        List<Purchase> purchases = new ArrayList<>();
        int purchaseCost = 0;
        if (reason == null && !consume.isEmpty()) {
            List<DisplayCargo> markets = marketSources();
            for (Map.Entry<String, Integer> entry : consume.entrySet()) {
                int need = entry.getValue();
                int owned = available(cargos, entry.getKey());
                if (owned >= need) continue;
                int shortfall = need - owned;
                int remaining = shortfall;
                WeaponSpecAPI purchased = weapon(entry.getKey());
                if (purchased == null) { reason = "武备不足：未知武器"; break; }
                for (DisplayCargo marketSource : markets) {
                    if (remaining <= 0) break;
                    int take = Math.min(remaining, safeQuantity(marketSource.cargo(), entry.getKey()));
                    if (take <= 0) continue;
                    purchases.add(new Purchase(marketSource.submarket(), entry.getKey(), take,
                            marketUnitPrice(purchased, marketSource.submarket())));
                    purchaseCost = Math.addExact(purchaseCost,
                            Math.multiplyExact(marketUnitPrice(purchased, marketSource.submarket()), take));
                    remaining -= take;
                }
                if (remaining > 0) { reason = "武备不足：" + Objects.toString(purchased.getWeaponName(), "未知武器"); purchases.clear(); purchaseCost = 0; break; }
            }
        }
        if (reason == null && purchaseCost > 0) {
            int credits = playerCredits();
            if (credits < purchaseCost) {
                reason = "信用点不足：需要 " + purchaseCost + " · 现有 " + credits;
            }
        }
        return new RefitPlan(memberId, bayIndex, expectedWingId, targetId, target, consume, returns,
                reason, changed, targetSpec, sourceTactical, targetTactical,
                reason == null ? purchases : List.of(), reason == null ? purchaseCost : 0);
    }
    public static synchronized String applyRefit(RefitPlan plan) {
        if (busy) return "另一项装配变更正在提交";
        if (plan == null) return "无可用装配方案";
        busy = true;
        try {
            RefitPlan fresh = previewTactical(plan.memberId, plan.bayIndex, plan.sourceWingId, plan.loadout,
                    plan.tacticalHullmods);
            if (fresh.blockReason != null) return fresh.blockReason;
            if (!Objects.equals(fresh.targetWingId, plan.targetWingId)) return "原型或装配定义已改变，请重新确认";
            FleetMemberAPI member = findMember(fresh.memberId);
            // 先按当前预览把市场武器买进玩家货舱，让后续装配事务能像普通库存一样消耗它们；
            // 任何一步失败都必须把已发生的购买完整退回市场并退还信用点。
            List<Purchase> bought = new ArrayList<>();
            String purchaseIssue = makePurchases(fresh.purchases, bought);
            if (purchaseIssue != null) { undoPurchases(bought); return purchaseIssue; }
            try {
                if (fresh.targetSpec != null) {
                    if (!Moci_FighterLoadoutStore.persist(fresh.targetSpec)) {
                        undoPurchases(bought);
                        return Moci_FighterLoadoutStore.getFailure();
                    }
                    String issue = registerDefinition(fresh.targetSpec);
                    if (issue != null) { undoPurchases(bought); return issue; }
                } else if (isCustomWingId(fresh.sourceWingId) && !Moci_FighterLoadoutStore.verifyDurableCatalog()) {
                    undoPurchases(bought);
                    return Moci_FighterLoadoutStore.getFailure();
                }
                ShipVariantAPI before = member.getVariant();
                ShipVariantAPI next = isolatedVariant(before, member.getId());
                next.setWingId(fresh.bayIndex, fresh.targetWingId);
                if (!fresh.targetWingId.equals(next.getWingId(fresh.bayIndex))) {
                    undoPurchases(bought);
                    return "该机库拒绝替换联队";
                }
                String result = transact(member, before, next, fresh.consume, fresh.returns);
                if (result != null) undoPurchases(bought);
                else logAppliedVariant(fresh);
                return result;
            } catch (RuntimeException ex) {
                undoPurchases(bought);
                throw ex;
            }
        } catch (RuntimeException ex) {
            Global.getLogger(Moci_FighterLoadoutService.class).warn("Fighter refit could not be prepared", ex);
            return "换装准备失败，已保留原装配和库存";
        } finally { busy = false; }
    }

    /**
     * 打印「应用装配」最终写进全局联队表（并已持久化到存档）的 Variant 配置。
     *
     * <p>这里是引擎生成战机时真正读取的那份数据：
     * 注册表里的 Variant → hullMods 会在 {@code Ship} 构造时应用，武器槽位决定初始武器。
     * 因此把 wingId / variantId / hullMods / 槽位武器 一起写进日志后，可与
     * UI 选择、存档 JSON 里的定义、出击实测三者逐环比对，定位是哪一环丢数据。
     * <p>常驻输出，不依赖 devMode；成功装配才打印，失败路径由各自 warn/error 负责。
     */
    private static void logAppliedVariant(RefitPlan plan) {
        try {
            StringBuilder line = new StringBuilder(LOG_PREFIX)
                    .append("applied member=").append(plan.memberId)
                    .append(" bay=").append(plan.bayIndex)
                    .append(" wing=").append(plan.targetWingId)
                    .append(" specVariant=").append(plan.targetSpec == null ? "-" : plan.targetSpec.getVariantId())
                    .append(" tactical=").append(plan.tacticalHullmods)
                    .append(" specLoadout=").append(plan.targetSpec == null ? "-" : plan.targetSpec.getLoadout());
            FighterWingSpecAPI wing = getWingSpec(plan.targetWingId);
            ShipVariantAPI variant = wing == null ? null : wing.getVariant();
            if (variant == null) {
                Global.getLogger(Moci_FighterLoadoutService.class).info(line.append(" variant=<missing>").toString());
                return;
            }
            Map<String, String> slots = new java.util.TreeMap<>();
            for (String slotId : variant.getNonBuiltInWeaponSlots()) {
                slots.put(slotId, String.valueOf(variant.getWeaponId(slotId)));
            }
            line.append(" variantId=").append(variant.getHullVariantId())
                    .append(" hullMods=").append(new java.util.TreeSet<>(variant.getHullMods()))
                    .append(" slots=").append(slots);
            Global.getLogger(Moci_FighterLoadoutService.class).info(line.toString());
        } catch (RuntimeException ex) {
            Global.getLogger(Moci_FighterLoadoutService.class).warn(LOG_PREFIX + "cannot describe variant", ex);
        }
    }

    /**
     * 按计划从当前市场购买武器：先整体校验库存与信用点，再逐笔扣库存、加货舱、扣信用点，
     * 并向市场报告交易（与原版购买一致）。返回 null 表示成功，否则返回可显示的中文原因。
     */
    private static String makePurchases(List<Purchase> lines, List<Purchase> done) {
        if (lines == null || lines.isEmpty()) return null;
        CargoAPI player;
        try { player = Global.getSector().getPlayerFleet().getCargo(); }
        catch (RuntimeException ex) { return "货舱不可用"; }
        long total = 0;
        for (Purchase p : lines) total += (long) p.unitPrice() * p.count();
        if (playerCredits() < total) return "信用点不足，请重新确认";
        for (Purchase p : lines) {
            try {
                if (safeQuantity(p.submarket().getCargo(), p.weaponId()) < p.count()) {
                    return "市场库存已改变，请重新确认";
                }
            } catch (RuntimeException ex) { return "市场库存已改变，请重新确认"; }
        }
        for (Purchase p : lines) {
            int cost;
            try { cost = Math.multiplyExact(p.unitPrice(), p.count()); }
            catch (ArithmeticException ex) { undoPurchases(done); return "购买价格溢出，已取消"; }
            try {
                p.submarket().getCargo().removeWeapons(p.weaponId(), p.count());
                player.addWeapons(p.weaponId(), p.count());
                player.getCredits().subtract(cost);
                done.add(p);
                reportPurchase(p, cost);
            } catch (RuntimeException ex) {
                undoPurchases(done);
                Global.getLogger(Moci_FighterLoadoutService.class).warn("市场购买失败，已回退", ex);
                return "购买失败，已退回信用点";
            }
        }
        return null;
    }

    /** 把已完成的购买原路退回：货舱扣掉、市场加回、信用点退还。 */
    private static void undoPurchases(List<Purchase> done) {
        if (done == null || done.isEmpty()) return;
        CargoAPI player;
        try { player = Global.getSector().getPlayerFleet().getCargo(); }
        catch (RuntimeException ex) { return; }
        for (int i = done.size() - 1; i >= 0; i--) {
            Purchase p = done.get(i);
            int cost;
            try { cost = Math.multiplyExact(p.unitPrice(), p.count()); }
            catch (ArithmeticException ex) { continue; }
            try {
                player.removeWeapons(p.weaponId(), p.count());
                p.submarket().getCargo().addWeapons(p.weaponId(), p.count());
                player.getCredits().add(cost);
            } catch (RuntimeException ex) {
                Global.getLogger(Moci_FighterLoadoutService.class).error("购买回退失败：" + p.weaponId(), ex);
            }
        }
        done.clear();
    }

    /** 向市场报告一笔玩家买入交易，让价格/库存的常规经济结算照常触发。 */
    private static void reportPurchase(Purchase purchase, int cost) {
        try {
            MarketAPI market = purchase.submarket().getMarket();
            if (market == null) return;
            PlayerMarketTransaction transaction = new PlayerMarketTransaction(market,
                    purchase.submarket(), CampaignUIAPI.CoreUITradeMode.OPEN);
            CargoAPI bought = Global.getFactory().createCargo(true);
            bought.addWeapons(purchase.weaponId(), purchase.count());
            transaction.setBought(bought);
            transaction.setCreditValue(-cost);
            purchase.submarket().getPlugin().reportPlayerMarketTransaction(transaction);
        } catch (RuntimeException ex) {
            Global.getLogger(Moci_FighterLoadoutService.class).warn("市场交易报告失败", ex);
        }
    }
    /** Called by the campaign coordinator after the native refit editor has closed. */
    public static synchronized String restoreWithoutHullmod(FleetMemberAPI member) {
        if (member == null || member.getVariant() == null || member.getVariant().hasHullMod(HULLMOD_ID)) return null;
        if (busy) return "另一项装配变更正在提交";
        if (!isAvailable()) return getUnavailableReason();
        if (findMember(member.getId()) != member) return "母舰已不在玩家舰队";
        busy = true;
        try {
            ShipVariantAPI before = member.getVariant();
            ShipVariantAPI next = null;
            // 母舰变体上若残留战术改装（例如曾被其它链路写入），卸下管理船插时必须一并清掉。
            boolean tacticalResidue = !selectedTactical(before).isEmpty();
            Map<String, Integer> returns = new LinkedHashMap<>();
            for (int i = 0; i < before.getWings().size(); i++) {
                String id = before.getWingId(i);
                if (!isCustomWingId(id)) continue;
                if (before.getHullSpec().isBuiltInWing(i)) return "内置联队装配异常，已保留所有装配";
                String issue = validateSource(id);
                if (issue != null) return issue;
                Moci_FighterLoadoutSpec source = Moci_FighterLoadoutStore.get(id);
                Map<String, Integer> refund = paid(readLoadout(id), source.getBaseline());
                refund.forEach((weapon, n) -> returns.merge(weapon, n, Math::addExact));
                if (next == null) next = isolatedVariant(before, member.getId());
                next.setWingId(i, source.getBaseWingId());
                if (!source.getBaseWingId().equals(next.getWingId(i))) return "机库恢复失败，已保留所有装配";
            }
            if (next == null && tacticalResidue) next = isolatedVariant(before, member.getId());
            if (next == null) return null;
            // 卸下“航空中队管理”船插时同步移除战术改装，避免战术插残留在恢复后的装配上。
            applyTactical(next, List.of());
            if (!Moci_FighterLoadoutStore.verifyDurableCatalog()) return Moci_FighterLoadoutStore.getFailure();
            return transact(member, before, next, Map.of(), returns);
        } catch (RuntimeException ex) {
            Global.getLogger(Moci_FighterLoadoutService.class).warn("Cannot prepare carrier restoration", ex);
            return "恢复准备失败，已保留原装配和库存";
        } finally { busy = false; }
    }
    private static ShipVariantAPI isolatedVariant(ShipVariantAPI before, String memberId) {
        ShipVariantAPI next = before.clone();
        if (next == null || next == before || next.getWings() == before.getWings()) {
            throw new IllegalStateException("Variant clone is not isolated");
        }
        next.setHullVariantId("moci_carrier_" + memberId);
        next.setSource(VariantSource.REFIT);
        next.setOriginalVariant(null);
        return next;
    }
    private record Snapshot(CargoAPI cargo, Map<String, Integer> amounts) {}
    /** Snapshot each actual source, including the operation that throws after partial mutation. */
    private static String transact(FleetMemberAPI member, ShipVariantAPI before, ShipVariantAPI next,
            Map<String, Integer> consume, Map<String, Integer> returns) {
        List<Snapshot> snapshots = new ArrayList<>();
        boolean mutationsStarted = false;
        try {
            List<CargoAPI> cargos = sources();
            if (cargos.isEmpty()) return "货舱不可用";
            Set<String> ids = new LinkedHashSet<>(consume.keySet()); ids.addAll(returns.keySet());
            for (CargoAPI cargo : cargos) {
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (String id : ids) counts.put(id, quantity(cargo, id));
                snapshots.add(new Snapshot(cargo, counts));
            }
            for (Map.Entry<String, Integer> entry : consume.entrySet()) {
                if (available(cargos, entry.getKey()) < entry.getValue()) return "库存已改变，请重新确认";
            }
            if (member.getVariant() != before) return "母舰装配已改变，请重新确认";
            mutationsStarted = true;
            for (Map.Entry<String, Integer> entry : consume.entrySet()) {
                int remaining = entry.getValue();
                for (CargoAPI cargo : cargos) {
                    int count = quantity(cargo, entry.getKey());
                    int take = Math.min(count, remaining);
                    if (take > 0) {
                        cargo.removeWeapons(entry.getKey(), take);
                        if (quantity(cargo, entry.getKey()) != count - take) throw new IllegalStateException("Incomplete weapon deduction");
                        remaining -= take;
                    }
                    if (remaining == 0) break;
                }
                if (remaining != 0) throw new IllegalStateException("Weapon source changed");
            }
            for (Map.Entry<String, Integer> entry : returns.entrySet()) {
                int count = quantity(cargos.get(0), entry.getKey());
                cargos.get(0).addWeapons(entry.getKey(), entry.getValue());
                if (quantity(cargos.get(0), entry.getKey()) != Math.addExact(count, entry.getValue())) {
                    throw new IllegalStateException("Incomplete weapon return");
                }
            }
            member.setVariant(next, false, true);
            if (member.getVariant() != next && !member.getVariant().getWings().equals(next.getWings())) {
                throw new IllegalStateException("Variant update was not applied");
            }
            return null;
        } catch (RuntimeException ex) {
            boolean restored = true;
            if (mutationsStarted) {
                if (member.getVariant() != before) try {
                    member.setVariant(before, false, true);
                    if (member.getVariant() != before) restored = false;
                } catch (RuntimeException rollbackFailure) {
                    restored = member.getVariant() == before;
                }
                for (Snapshot snapshot : snapshots) for (Map.Entry<String, Integer> entry : snapshot.amounts.entrySet()) {
                    try {
                        int current = quantity(snapshot.cargo, entry.getKey());
                        int difference = entry.getValue() - current;
                        if (difference > 0) snapshot.cargo.addWeapons(entry.getKey(), difference);
                        else if (difference < 0) snapshot.cargo.removeWeapons(entry.getKey(), -difference);
                    } catch (RuntimeException rollbackFailure) {
                        Global.getLogger(Moci_FighterLoadoutService.class).error("Inventory rollback operation failed", rollbackFailure);
                    }
                    try { if (quantity(snapshot.cargo, entry.getKey()) != entry.getValue()) restored = false; }
                    catch (RuntimeException readFailure) { restored = false; }
                }
            }
            if (!restored) failure = "装配事务回滚未能完成，已进入只读保护；请保留当前存档并查看日志";
            Global.getLogger(Moci_FighterLoadoutService.class).error("Fighter transaction failed; rollback=" + restored, ex);
            return restored ? "换装提交失败，母舰与各来源库存已恢复" : failure;
        }
    }
    private static String validateSource(String id) {
        Moci_FighterLoadoutSpec source = Moci_FighterLoadoutStore.get(id);
        if (source == null) return "自定义联队缺少保存定义，已保留装配";
        if (definitionIssues.containsKey(id)) return definitionIssues.get(id);
        String issue = definitionIssue(source);
        if (issue != null) return issue;
        FighterWingSpecAPI wing = getWingSpec(id);
        if (wing == null || !source.getVariantId().equals(wing.getVariantId())
                || !source.getLoadout().equals(readLoadout(id))
                || !Moci_TacticalHullmods.normalize(source.getTacticalHullmods()).equals(selectedTactical(wing.getVariant()))) return "自定义联队与保存定义不一致，已保留装配";
        return null;
    }
    private static String definitionIssue(Moci_FighterLoadoutSpec spec) {
        FighterWingSpecAPI prototype = getWingSpec(spec.getBaseWingId());
        if (prototype == null || prototype.getVariant() == null) return "原型联队缺失，已保留装配";
        List<WeaponSlotAPI> slots = getEditableSlots(prototype);
        Set<String> ids = new LinkedHashSet<>();
        for (WeaponSlotAPI slot : slots) {
            ids.add(slot.getId());
            String weaponId = spec.getLoadout().get(slot.getId());
            boolean stockWeapon = weaponId != null && weapon(weaponId) != null
                    && weaponId.equals(prototype.getVariant().getWeaponId(slot.getId()));
            if (weaponId == null || (!weaponId.isEmpty() && !stockWeapon && !canFitSlot(slot, weaponId))) {
                return "保存的武器或槽位已失效，已保留装配";
            }
        }
        if (!ids.equals(spec.getLoadout().keySet()) || !ids.equals(spec.getBaseline().keySet())) {
            return "原型武器槽已改变，已保留装配";
        }
        for (String id : spec.getBaseline().values()) {
            if (!id.isEmpty() && weapon(id) == null) return "保存的默认武器基线缺失，已保留装配";
        }
        return null;
    }
    private static String registerDefinition(Moci_FighterLoadoutSpec spec) {
        FighterWingSpecAPI prototype = getWingSpec(spec.getBaseWingId());
        if (prototype == null || prototype.getVariant() == null) return "无法恢复型号：原型联队缺失";
        String metadataIssue = definitionIssue(spec);
        if (metadataIssue != null) definitionIssues.put(spec.getWingId(), metadataIssue);
        if (!Moci_FighterLoadoutRegistry.isVariantRegistered(spec.getVariantId())) {
            ShipVariantAPI variant = prototype.getVariant().clone();
            if (variant == null || variant == prototype.getVariant()) return "战机变体无法隔离";
            variant.setHullVariantId(spec.getVariantId()); variant.setSource(VariantSource.STOCK);
            for (WeaponSlotAPI slot : getEditableSlots(prototype)) {
                String id = spec.getLoadout().get(slot.getId());
                // Tactical-only refits preserve stock weapon/slot pairs even if normal fitting rejects them.
                if (id != null && weapon(id) != null && id.equals(prototype.getVariant().getWeaponId(slot.getId()))) continue;
                variant.clearSlot(slot.getId());
                if (id != null && id.isEmpty()) continue;
                if (id != null && canFitSlot(slot, id)) variant.addWeapon(slot.getId(), id);
                else {
                    String fallback = prototype.getVariant().getWeaponId(slot.getId());
                    if (fallback != null && canFitSlot(slot, fallback)) variant.addWeapon(slot.getId(), fallback);
                }
            }
            Set<String> assigned = new LinkedHashSet<>();
            for (WeaponGroupSpec group : variant.getWeaponGroups()) assigned.addAll(group.getSlots());
            for (WeaponSlotAPI slot : getEditableSlots(prototype)) {
                if (variant.getWeaponId(slot.getId()) != null && !assigned.contains(slot.getId())) {
                    WeaponGroupSpec group = new WeaponGroupSpec();
                    group.setAutofireOnByDefault(true); group.addSlot(slot.getId());
                    variant.addWeaponGroup(group);
                }
            }
            // 战术改装必须在变体注册前写进 hullMods：注册后再 addMod 不会同步到已注册的 HullVariantSpec。
            applyTactical(variant, spec.getTacticalHullmods());
            applyCustomFighterTags(variant);
            if (!Moci_FighterLoadoutRegistry.registerVariant(variant)) return "战机变体注册失败";
        }
        FighterWingSpecAPI wing = getWingSpec(spec.getWingId());
        if (wing == null) {
            // Invalid legacy definitions already fall back to prototype weapons above;
            // retain the original OP for that read-only fallback instead of rejecting recovery.
            float opCost = metadataIssue == null
                    ? refitBudgetForTacticals(spec.getBaseWingId(), spec.getLoadout(), spec.getTacticalHullmods()).opCost()
                    : prototype.getOpCost(null);
            wing = copyWing(spec, prototype, opCost);
            if (wing == null) return "联队规格复制失败：自动装配分类或来源模组信息无法继承";
            if (!Moci_FighterLoadoutRegistry.registerWing(spec.getWingId(), wing)) return "联队规格注册失败";
        }
        if (!spec.getWingId().equals(wing.getId()) || !spec.getVariantId().equals(wing.getVariantId())) {
            return "型号 ID 或装配定义冲突";
        }
        ShipVariantAPI registeredVariant = wing.getVariant();
        if (registeredVariant == null || !spec.getVariantId().equals(registeredVariant.getHullVariantId())) {
            return "联队变体规格缺失或 ID 不匹配";
        }
        if (metadataIssue == null && !spec.getLoadout().equals(readLoadout(spec.getWingId()))) {
            return "型号 ID 或装配定义冲突";
        }
        if (!Moci_TacticalHullmods.normalize(spec.getTacticalHullmods()).equals(selectedTactical(registeredVariant))) {
            return "战术改装与保存定义不一致";
        }
        applyCustomFighterTags(registeredVariant);
        applyCustomFighterTags(wing);
        Moci_ScveSnapshotBridge.noteWingRegistered(wing);
        if (metadataIssue == null) {
            // Existing saved definitions keep their IDs, but receive the current derived OP rule on load.
            float opCost = refitBudgetForTacticals(spec.getBaseWingId(), spec.getLoadout(), spec.getTacticalHullmods()).opCost();
            if (Float.compare(wing.getOpCost(null), opCost) != 0) {
                wing.setOpCost(opCost);
                Moci_ScveSnapshotBridge.noteWingRegistered(wing);
            }
        }
        return metadataIssue;
    }
    private static FighterWingSpecAPI copyWing(Moci_FighterLoadoutSpec spec, FighterWingSpecAPI prototype, float opCost) {
        FighterWingSpecAPI wing = Moci_FighterLoadoutRegistry.createWingSpec();
        if (wing == null) return null;
        wing.setId(spec.getWingId()); wing.setVariantId(spec.getVariantId());
        wing.setNumFighters(prototype.getNumFighters()); wing.setRole(prototype.getRole());
        wing.setFormation(prototype.getFormation()); wing.setRefitTime(prototype.getRefitTime());
        wing.setFleetPoints(prototype.getFleetPoints()); wing.setTier(prototype.getTier());
        wing.setRarity(prototype.getRarity()); wing.setRoleDesc(prototype.getRoleDesc());
        wing.setRange(prototype.getRange()); wing.setAttackRunRange(prototype.getAttackRunRange());
        wing.setAttackPositionOffset(prototype.getAttackPositionOffset());
        wing.setBaseValue(prototype.getBaseValue());
        wing.setOpCost(opCost);
        wing.getTags().addAll(prototype.getTags());
        applyCustomFighterTags(wing);
        if (!Moci_FighterLoadoutRegistry.inheritUnsettableWingFields(wing, prototype)) return null;
        return wing;
    }

    /** Exclude dynamic LPCs from blueprint packages, salvage and generated market stock. */
    private static void applyCustomFighterTags(ShipVariantAPI variant) {
        if (variant == null) return;
        for (String tag : new ArrayList<>(variant.getTags())) {
            if (isBlueprintTag(tag)) variant.removeTag(tag);
        }
        variant.removeTag("variant"); // Remove the literal tag mistakenly added by the previous implementation.
        for (String tag : CUSTOM_FIGHTER_TAGS) variant.addTag(tag);
    }

    static void applyCustomFighterTags(FighterWingSpecAPI wing) {
        // addTag() clears autofitPriorityCategories even if the tag already exists.
        // These tags do not affect autofit categories; preserve the inherited priority list.
        Set<String> tags = wing.getTags();
        tags.removeIf(Moci_FighterLoadoutService::isBlueprintTag);
        tags.remove("variant");
        tags.addAll(CUSTOM_FIGHTER_TAGS);
        tags.add(Moci_FighterLoadoutSpec.TAG);
    }

    private static boolean isBlueprintTag(String tag) {
        return tag != null && tag.endsWith("_bp");
    }
}
