package data.campaign.fighter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
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
    // 图鉴（CodexDataV2.populateFighters）读的是联队规格 FighterWingSpecAPI.getTags() 里的 hide_in_codex，
    // 由 applyCustomFighterTags(FighterWingSpecAPI) 写入；变体上的同名 tag 图鉴不读，只是随同一集合一起加上。
    private static final Set<String> CUSTOM_FIGHTER_TAGS = Set.of(Tags.WING_NO_DROP, Tags.WING_NO_SELL, Tags.RESTRICTED,
            Tags.HIDE_IN_CODEX);
    /** 排查日志前缀：装配落库后打印最终 Variant 的 hullMods/槽位，便于逐环核对「装了却没生效」。 */
    private static final String LOG_PREFIX = "Moci-AirSquadron|loadout|";
    private static boolean initialized;
    private static boolean busy;
    private static String failure;
    private static final Map<String, String> definitionIssues = new LinkedHashMap<>();
    /** 读档时从保存定义里自动拆除的战术改装：wingId → (船插 id → 原因)。不锁机库，只供界面提示。 */
    private static final Map<String, Map<String, String>> removedTacticals = new LinkedHashMap<>();
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
        initialized = true; failure = null; definitionIssues.clear(); removedTacticals.clear();
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
        // 每次读档都从干净状态重新评估：上一局的事务失败提示与定义问题不能带进新存档。
        failure = null; definitionIssues.clear(); removedTacticals.clear();
        for (Moci_FighterLoadoutSpec spec : Moci_FighterLoadoutStore.all()) {
            try {
                String issue = registerDefinition(spec);
                if (issue != null) definitionIssues.put(spec.getWingId(), issue);
            } catch (RuntimeException ex) {
                // 逐条隔离：每条定义来自存档/注册库文件，会驱动变体克隆、引擎注册表和 SCVE 快照；
                // 能事先校验的都在 registerDefinition 里返回原因了，剩下的是引擎内部状态，
                // 一条出错只锁这一条，不能让后面的定义都不注册（读档入口的外层 catch 会整段跳过）。
                definitionIssues.put(spec.getWingId(), "型号恢复失败：" + ex.getMessage());
                Global.getLogger(Moci_FighterLoadoutService.class).warn(LOG_PREFIX + "cannot restore " + spec.getWingId(), ex);
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
    /** 联队规格；未注册的 id 返回 null（引擎 getFighterWingSpec 对未知 id 返回 null，不抛异常）。 */
    public static FighterWingSpecAPI getWingSpec(String wingId) {
        if (wingId == null || wingId.isEmpty() || Global.getSettings() == null) return null;
        return Global.getSettings().getFighterWingSpec(wingId);
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
    public static List<String> getTacticalHullmods(String wingId) {
        Moci_FighterLoadoutSpec spec = Moci_FighterLoadoutStore.get(wingId);
        if (spec == null) {
            FighterWingSpecAPI wing = getWingSpec(wingId);
            return wing == null ? List.of() : selectedTactical(wing.getVariant());
        }
        // 生效列表 = 保存定义（按每组一项宽松读取）去掉读档时因不可用而自动拆除的项。
        Map<String, String> removed = removedTacticals.getOrDefault(wingId, Map.of());
        List<String> effective = new ArrayList<>();
        for (String id : tolerantTacticals(spec.getTacticalHullmods())) {
            if (!removed.containsKey(id)) effective.add(id);
        }
        return List.copyOf(effective);
    }
    /**
     * 读档宽松模式：保存定义里的战术改装若同组冲突或已失效，保留每组第一项而不是抛异常。
     * 严格校验（normalize）仍然用于玩家在界面里做的选择；只有「读回已保存的定义」走这条路（例如之后改了分组 tag），
     * 否则一条坏记录会让整支联队注册失败、连原型都恢复不了。没有冲突时结果与 normalize 相同。
     */
    private static List<String> tolerantTacticals(List<String> ids) {
        List<String> kept = new ArrayList<>();
        EnumSet<Moci_TacticalHullmods.Group> groups = EnumSet.noneOf(Moci_TacticalHullmods.Group.class);
        if (ids != null) for (String id : ids) {
            if (Moci_TacticalHullmods.isTactical(id) && groups.add(Moci_TacticalHullmods.group(id))) kept.add(id);
        }
        kept.sort(String::compareTo);
        return List.copyOf(kept);
    }
    /** UI 用：按规格表顺序列出所有带战术改装标记的船插。 */
    public static List<String> getTacticalHullmodOptions() {
        // Moci_TacticalHullmods.all() 在 settings 未注入时返回空表，本身不抛异常。
        return Moci_TacticalHullmods.all();
    }
    /** UI 用：该船插当前是否已注册且带战术改装标记（未注册返回 false）。 */
    public static boolean isTacticalHullmodAvailable(String tacticalHullmodId) {
        // getHullModSpec 对未注册的 id 返回 null（javap 核对过 SpecStore），isTactical 因此不抛异常。
        return Moci_TacticalHullmods.isTactical(tacticalHullmodId);
    }
    /**
     * 按联队原型判断战术改装能否安装；null 表示可以。依据只有原型和配置：船插已注册且是战术改装、
     * 配置的战术限制策略、强化护盾要求原型有 FRONT 或 OMNI 护盾、先进光学器件与高散射增幅器互斥。
     * 草稿武器和已装变体都不参与，所以同一联队的结论不随草稿变化。界面、预览和读档时的自动拆除共用这条规则。
     */
    public static synchronized String tacticalUnavailableReason(String id, String wingId) {
        return tacticalIssue(id, getWingSpec(getBaseWingId(wingId)), true);
    }
    /** 保存定义里有、但因不可用已被自动拆除的战术改装：id → 原因（按 id 排序）；没有则为空表。 */
    public static synchronized Map<String, String> getRemovedTacticalHullmods(String wingId) {
        Map<String, String> removed = removedTacticals.get(wingId);
        return removed == null ? Map.of() : immutable(removed);
    }
    /**
     * applyPolicy=false 只看原型事实，不看配置策略：配置读不出来时编辑器已整体锁定，
     * 读档不能因此把存档里已装的战术改装全部拆掉。
     */
    private static String tacticalIssue(String id, FighterWingSpecAPI prototype, boolean applyPolicy) {
        if (!Moci_TacticalHullmods.isTactical(id)) return "当前不可用。";
        if (applyPolicy && Moci_FighterLoadoutEligibility.getFailure() != null) return Moci_FighterLoadoutEligibility.getFailure();
        ShipVariantAPI variant = prototype == null ? null : prototype.getVariant();
        if (variant == null) return "原型联队缺失。";
        if (applyPolicy && !Moci_FighterLoadoutEligibility.acceptsTactical(prototype)) return "该联队不支持战术改装。";
        if (Moci_TacticalHullmods.HARDENED_SHIELD.equals(id)) {
            ShieldType type = variant.getHullSpec() == null ? null : variant.getHullSpec().getShieldType();
            if (type != ShieldType.FRONT && type != ShieldType.OMNI) return "该舰载机没有护盾，无法安装强化护盾。";
        }
        if (Moci_TacticalHullmods.ADVANCED_OPTICS.equals(id) && variant.hasHullMod(HullMods.HIGH_SCATTER_AMP)) {
            return "先进光学器件与高散射增幅器不兼容。";
        }
        return null;
    }
    /**
     * 读档时按可用性过滤保存的战术改装，返回生效列表；被拆掉的项连同原因记进 removedTacticals。
     * 只拆不锁：定义本身仍然有效，玩家不改动时不会生成新定义，改武器时新定义直接使用生效列表。
     */
    private static List<String> availableTacticals(String wingId, List<String> saved, FighterWingSpecAPI prototype) {
        boolean applyPolicy = Moci_FighterLoadoutEligibility.getFailure() == null;
        List<String> effective = new ArrayList<>();
        Map<String, String> removed = new TreeMap<>();
        for (String id : saved) {
            String issue = tacticalIssue(id, prototype, applyPolicy);
            if (issue == null) effective.add(id);
            else removed.put(id, issue);
        }
        if (removed.isEmpty()) {
            removedTacticals.remove(wingId);
        } else {
            removedTacticals.put(wingId, removed);
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, String> entry : removed.entrySet()) parts.add(entry.getKey() + "（" + entry.getValue() + "）");
            Global.getLogger(Moci_FighterLoadoutService.class).warn(LOG_PREFIX + wingId
                    + " 保存的战术改装已不可用，已自动拆除：" + String.join("、", parts));
        }
        return List.copyOf(effective);
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
    /**
     * 武器规格；未注册的 id 返回 null。引擎的 getWeaponSpec 对未知 id 抛
     * RuntimeException("Weapon spec [%s] not found!")（不像 getFighterWingSpec / getHullModSpec 返回 null），
     * 而 SettingsAPI 没有按 id 判断武器是否存在的方法，只能在这里捕获；所有按 id 取武器的地方都经过这里。
     */
    static WeaponSpecAPI weapon(String id) {
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
                boolean marketSource = source.source() == WeaponSource.MARKET
                        || source.source() == WeaponSource.BLACK_MARKET;
                int count = marketSource ? marketQuantity(source, spec.getWeaponId())
                        : safeQuantity(source.cargo(), spec.getWeaponId());
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

    /** 展示/市场来源的库存数量：数量异常（非有限、负数、小数、超出 int）时按 0 计，不中断列表。 */
    private static int safeQuantity(CargoAPI cargo, String id) {
        float value = cargo.getQuantity(CargoAPI.CargoItemType.WEAPONS, id);
        return validQuantity(value) ? (int) value : 0;
    }

    private static boolean validQuantity(float value) {
        return Float.isFinite(value) && value >= 0f && value == Math.floor(value) && value < 0x1p31f;
    }

    /** 市场来源里该武器实际可买的数量：被该子市场禁售的武器按 0 计，和原版改装页一致。 */
    private static int marketQuantity(DisplayCargo source, String weaponId) {
        int count = safeQuantity(source.cargo(), weaponId);
        if (count <= 0) return 0;
        return Moci_FighterMarketAccess.isLegalPurchase(source.submarket(), weaponId) ? count : 0;
    }

    private static List<DisplayCargo> displaySources() {
        List<DisplayCargo> result = new ArrayList<>();
        Set<CargoAPI> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) return result;
        addDisplaySource(result, seen, Global.getSector().getPlayerFleet().getCargo(), WeaponSource.CARGO, null);
        MarketAPI market = currentMarket();
        if (market == null || market.isHidden()) return result;
        // 与原版一致：仓库子市场存在且已解锁（OPEN_SUBMARKET）才算来源；
        // Misc.getStorageCargo 对缺失的仓库返回 null，addDisplaySource 会跳过。
        if (Misc.playerHasStorageAccess(market)) {
            addDisplaySource(result, seen, Misc.getStorageCargo(market), WeaponSource.STORAGE, null);
        }
        // 只有玩家正在交互的市场、且该子市场在当前交易模式下允许交易时才计入来源；
        // 交易模式为 NONE（未交易/潜伏关闭应答器失败）时这里返回空表。
        for (SubmarketAPI submarket : Moci_FighterMarketAccess.marketSubmarkets(market)) {
            SubmarketPlugin plugin = submarket.getPlugin();
            if (plugin == null) continue;
            CargoAPI cargo = submarket.getCargoNullOk();
            if (cargo == null) cargo = plugin.getCargoNullOk();
            WeaponSource source = plugin.isBlackMarket() ? WeaponSource.BLACK_MARKET : WeaponSource.MARKET;
            addDisplaySource(result, seen, cargo, source, submarket);
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
     * 市场买入单价（信用点），与原版改装页武器选择器（WeaponPickerDialog）同一规则：
     * 交互实体或子市场是免费转移（{@code isFreeTransfer()}）时为 0；
     * 否则为 round(基础价值 × {@code shipWeaponBuyPriceMult} × (1 + 子市场关税))。
     * 原版不截断负关税，这里也不截断；只把结果下限钳到 0（关税低于 -100% 时原版会给出负价）。
     */
    public static int marketUnitPrice(WeaponSpecAPI spec, SubmarketAPI submarket) {
        if (spec == null || submarket == null) return 0;
        SubmarketPlugin plugin = submarket.getPlugin();
        if (plugin == null) return 0; // 没有插件的子市场不会进入市场来源（marketSubmarkets 已跳过）。
        SectorEntityToken entity = Moci_FighterMarketAccess.interactionTarget();
        if (plugin.isFreeTransfer() || (entity != null && entity.isFreeTransfer())) return 0;
        // 原版 computeBuyCostOfItem 对武器无条件读取这个键，原版 settings.json 里一定有它。
        float base = spec.getBaseValue() * Global.getSettings().getFloat("shipWeaponBuyPriceMult");
        float price = base * (1f + submarket.getTariff());
        if (!Float.isFinite(price) || price <= 0f) return 0;
        return Math.round(price);
    }

    /**
     * 当前交互市场里能合法买到、且有库存的来源：先排非黑市，再按买入单价升序（同价保持子市场顺序）。
     * 原版黑市的关税是 0，单纯按价格排会让黑市在开放市场有货时也排第一，
     * 玩家开着应答器时就会被悄悄计入走私嫌疑；所以黑市只在其他子市场缺货时补足。
     * 价格提示和采购计划都用它，所以提示的单价就是实际最先扣款的单价。
     */
    private static List<DisplayCargo> purchasableSources(List<DisplayCargo> markets, WeaponSpecAPI spec,
            String weaponId) {
        List<DisplayCargo> result = new ArrayList<>();
        for (DisplayCargo source : markets) {
            if (marketQuantity(source, weaponId) > 0) result.add(source);
        }
        result.sort(Comparator.comparing(Moci_FighterLoadoutService::isBlackMarketSource)
                .thenComparingInt(source -> marketUnitPrice(spec, source.submarket())));
        return result;
    }

    private static boolean isBlackMarketSource(DisplayCargo source) {
        SubmarketAPI submarket = source.submarket();
        return submarket != null && submarket.getPlugin() != null && submarket.getPlugin().isBlackMarket();
    }

    /** UI 用：当前交互市场里该武器最先会被买入的单价（先非黑市、再按单价，与采购计划一致）；没有来源时返回 0。 */
    public static int bestMarketUnitPrice(WeaponSpecAPI spec) {
        if (spec == null) return 0;
        List<DisplayCargo> sources = purchasableSources(marketSources(), spec, spec.getWeaponId());
        return sources.isEmpty() ? 0 : marketUnitPrice(spec, sources.get(0).submarket());
    }

    /** 玩家当前信用点（只读）；没有玩家舰队时返回 0。 */
    private static int playerCredits() {
        if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) return 0;
        float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        if (!Float.isFinite(credits) || credits <= 0) return 0;
        return Math.min(Integer.MAX_VALUE, (int) Math.floor(credits));
    }

    /**
     * 当前交互市场：只有玩家真正打开了某个市场的交互对话框时才算市场。
     * 不再回退到「舰队环绕的市场」——平飞/停在轨道上并不是在交易，
     * 否则会把黑市与封锁的子市场当成可购买库存。
     */
    private static MarketAPI currentMarket() {
        return Moci_FighterMarketAccess.interactionMarket();
    }

    private static List<CargoAPI> nativeSources() {
        List<CargoAPI> result = new ArrayList<>();
        if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) return result;
        result.add(Global.getSector().getPlayerFleet().getCargo());
        MarketAPI market = currentMarket();
        // 与 displaySources 同一判断：仓库子市场存在且已解锁才计入；getStorageCargo 对缺失的仓库返回 null。
        if (market != null && !market.isHidden() && Misc.playerHasStorageAccess(market)) {
            CargoAPI storage = Misc.getStorageCargo(market);
            if (storage != null) result.add(storage);
        }
        return result;
    }
    private static List<CargoAPI> sources() {
        List<CargoAPI> result = new ArrayList<>();
        Set<CargoAPI> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CargoAPI cargo : inventorySources.get()) if (cargo != null && seen.add(cargo)) result.add(cargo);
        return result;
    }
    /** 换装结算用的库存数量：数量异常时抛出，由预览入口转成「装配数据异常」并保留原装配。 */
    private static int quantity(CargoAPI cargo, String id) {
        float value = cargo.getQuantity(CargoAPI.CargoItemType.WEAPONS, id);
        if (!validQuantity(value)) throw new IllegalStateException("武器库存数量异常：" + id);
        return (int) value;
    }
    private static int available(List<CargoAPI> cargos, String id) {
        int count = 0;
        for (CargoAPI cargo : cargos) count = Math.addExact(count, quantity(cargo, id));
        return count;
    }
    /** 展示用的可用库存总数；任一来源数量异常或总数超出 int 时返回 0（这种库存预览也会拒绝换装）。 */
    public static int getAvailableWeaponCount(String weaponId) {
        if (weaponId == null || weaponId.isEmpty()) return 0;
        long count = 0;
        for (CargoAPI cargo : sources()) {
            float value = cargo.getQuantity(CargoAPI.CargoItemType.WEAPONS, weaponId);
            if (!validQuantity(value)) return 0;
            count += (long) value;
        }
        return count > Integer.MAX_VALUE ? 0 : (int) count;
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
     * 带战术改装选择的预览：tacticalHullmods 传空表表示明确不带战术改装；
     * 传非法或未注册的 ID 时返回带 blockReason 的计划。
     */
    public static RefitPlan previewTactical(String memberId, int bayIndex, String expectedWingId,
            Map<String, String> draft, List<String> tacticalHullmods) {
        return previewInternal(memberId, bayIndex, expectedWingId, draft, tacticalHullmods, true);
    }
    private static RefitPlan previewInternal(String memberId, int bayIndex, String expectedWingId,
            Map<String, String> draft, List<String> tacticalHullmods, boolean explicitTactical) {
        try {
            return previewChecked(memberId, bayIndex, expectedWingId, draft, tacticalHullmods, explicitTactical);
        } catch (RuntimeException ex) {
            // 界面入口：库存数量异常（quantity 抛 IllegalStateException）、预算越界
            // （Budget 抛 IllegalArgumentException）、数量溢出（addExact）都在这里变成「已保留原装配」的计划，
            // 不能穿进对话框。
            Global.getLogger(Moci_FighterLoadoutService.class).warn(LOG_PREFIX + "cannot preview fighter refit", ex);
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
        // 按原型判断（不看已装变体）：与界面置灰、读档自动拆除用同一条规则。
        for (String id : targetTactical) {
            String issue = tacticalUnavailableReason(id, expectedWingId);
            if (issue != null) return blocked(memberId, bayIndex, expectedWingId, issue);
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
        // 读档时自动拆过战术改装的定义：保存的 ID 仍含被拆的项，按生效列表重算的 ID 必然不同。
        // 玩家什么都没改时保持当前联队，不生成新定义；改了武器或战术才按生效列表落新定义。
        boolean keepCurrent = isCustomWingId(expectedWingId) && !getRemovedTacticalHullmods(expectedWingId).isEmpty()
                && target.equals(source) && targetTactical.equals(sourceTactical);
        boolean vanilla = !keepCurrent && target.equals(defaults) && targetTactical.isEmpty()
                && (!bay.weaponEligible || legalPrototypeLoadout(prototype, slots));
        Moci_FighterLoadoutSpec targetSpec = vanilla || keepCurrent ? null
                : Moci_FighterLoadoutSpec.withTacticals(baseId, baseline, target, targetTactical);
        String targetId = keepCurrent ? expectedWingId : vanilla ? baseId : targetSpec.getWingId();
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
                int remaining = need - owned;
                WeaponSpecAPI purchased = weapon(entry.getKey());
                if (purchased == null) { reason = "武备不足：未知武器"; break; }
                // 与 bestMarketUnitPrice 同一组来源、同一顺序：只用能合法买到且有库存的子市场，先买最便宜的。
                for (DisplayCargo marketSource : purchasableSources(markets, purchased, entry.getKey())) {
                    if (remaining <= 0) break;
                    int take = Math.min(remaining, marketQuantity(marketSource, entry.getKey()));
                    int unitPrice = marketUnitPrice(purchased, marketSource.submarket());
                    purchases.add(new Purchase(marketSource.submarket(), entry.getKey(), take, unitPrice));
                    purchaseCost = Math.addExact(purchaseCost, Math.multiplyExact(unitPrice, take));
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
            try {
                String purchaseIssue = makePurchases(fresh.purchases, bought);
                if (purchaseIssue != null) { undoPurchases(bought); return purchaseIssue; }
                if (fresh.targetSpec != null) {
                    if (!Moci_FighterLoadoutStore.persist(fresh.targetSpec)) {
                        undoPurchases(bought);
                        return Moci_FighterLoadoutStore.describeFailure();
                    }
                    String issue = registerDefinition(fresh.targetSpec);
                    if (issue != null) { undoPurchases(bought); return issue; }
                } else if (isCustomWingId(fresh.sourceWingId) && !Moci_FighterLoadoutStore.verifyDurableCatalog()) {
                    undoPurchases(bought);
                    return Moci_FighterLoadoutStore.describeFailure();
                }
                ShipVariantAPI before = member.getVariant();
                ShipVariantAPI next = isolatedVariant(before, member.getId());
                next.setWingId(fresh.bayIndex, fresh.targetWingId);
                if (!fresh.targetWingId.equals(next.getWingId(fresh.bayIndex))) {
                    undoPurchases(bought);
                    return "该机库拒绝替换联队";
                }
                String result = transact(member, before, next, fresh.consume, fresh.returns);
                if (result != null) { undoPurchases(bought); return result; }
            } catch (RuntimeException ex) {
                // 事务回滚：采购开始后任何一步抛出，都先把已买的武器退回市场、退还信用点，再交给外层入口。
                undoPurchases(bought);
                throw ex;
            }
            // 采购与装配事务都成功后才上报成交；这两步在回滚范围之外，绝不撤销已完成的换装。
            reportPurchases(bought);
            logAppliedVariant(fresh);
            return null;
        } catch (RuntimeException ex) {
            // 界面入口：换装准备或事务中的意外异常不能穿进对话框；库存与采购已在内层回滚。
            Global.getLogger(Moci_FighterLoadoutService.class).warn(LOG_PREFIX + "fighter refit could not be prepared", ex);
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
        StringBuilder line = new StringBuilder(LOG_PREFIX)
                .append("applied member=").append(plan.memberId)
                .append(" bay=").append(plan.bayIndex)
                .append(" wing=").append(plan.targetWingId)
                .append(" specVariant=").append(plan.targetSpec == null ? "-" : plan.targetSpec.getVariantId())
                .append(" tactical=").append(plan.tacticalHullmods)
                .append(" specLoadout=").append(plan.targetSpec == null ? "-" : plan.targetSpec.getLoadout());
        FighterWingSpecAPI wing = getWingSpec(plan.targetWingId);
        ShipVariantAPI variant = wing == null ? null : wing.getVariant();
        // getNonBuiltInWeaponSlots 需要船体规格；缺失时只记录缺失，不去读槽位。
        if (variant == null || variant.getHullSpec() == null) {
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
    }

    /**
     * 按计划从当前市场购买武器：先整体校验交易模式/禁售规则、库存与信用点，再逐笔扣库存、
     * 加货舱、扣信用点。这里**不**上报交易——只有装配事务全部成功后才由
     * {@link #reportPurchases(List)} 补报，失败回滚的采购绝不会被上报。
     * 返回 null 表示成功，否则返回可显示的中文原因。
     */
    private static String makePurchases(List<Purchase> lines, List<Purchase> done) {
        if (lines == null || lines.isEmpty()) return null;
        CargoAPI player = playerCargo();
        if (player == null) return "货舱不可用";
        long total = 0;
        for (Purchase p : lines) total += (long) p.unitPrice() * p.count();
        // 单价与数量都是非负 int：总额不超过 int 时每一笔也不超过，下面逐笔直接相乘。
        if (total > Integer.MAX_VALUE) return "购买价格溢出，已取消";
        if (playerCredits() < total) return "信用点不足，请重新确认";
        for (Purchase p : lines) {
            // 预览到提交之间玩家可能退出市场界面或改变交易模式，提交前必须再核对一次。
            if (!Moci_FighterMarketAccess.isTradable(p.submarket())
                    || !Moci_FighterMarketAccess.isLegalPurchase(p.submarket(), p.weaponId())) {
                return "市场已不可交易，请重新确认";
            }
            if (safeQuantity(p.submarket().getCargo(), p.weaponId()) < p.count()) {
                return "市场库存已改变，请重新确认";
            }
        }
        for (Purchase p : lines) {
            try {
                p.submarket().getCargo().removeWeapons(p.weaponId(), p.count());
                player.addWeapons(p.weaponId(), p.count());
                player.getCredits().subtract(p.unitPrice() * p.count());
                done.add(p);
            } catch (RuntimeException ex) {
                // 事务回滚：货舱/信用点操作在中途失败时，退回本次已完成的所有购买再报告失败。
                undoPurchases(done);
                Global.getLogger(Moci_FighterLoadoutService.class).warn(LOG_PREFIX + "市场购买失败，已回退", ex);
                return "购买失败，已退回信用点";
            }
        }
        return null;
    }

    private static CargoAPI playerCargo() {
        SectorAPI sector = Global.getSector();
        CampaignFleetAPI fleet = sector == null ? null : sector.getPlayerFleet();
        return fleet == null ? null : fleet.getCargo();
    }

    /**
     * 把已完成的购买原路退回：货舱扣掉、市场加回、信用点退还。
     * 回滚不是成交，因此这里绝不上报任何市场交易。
     */
    private static void undoPurchases(List<Purchase> done) {
        if (done == null || done.isEmpty()) return;
        CargoAPI player = playerCargo();
        if (player == null) return;
        for (int i = done.size() - 1; i >= 0; i--) {
            Purchase p = done.get(i);
            try {
                player.removeWeapons(p.weaponId(), p.count());
                p.submarket().getCargo().addWeapons(p.weaponId(), p.count());
                player.getCredits().add((float) ((long) p.unitPrice() * p.count()));
            } catch (RuntimeException ex) {
                // 回滚中的回滚：一笔退不回去也要继续退其余各笔，并留下错误日志供人工核对。
                Global.getLogger(Moci_FighterLoadoutService.class).error(LOG_PREFIX + "购买回退失败：" + p.weaponId(), ex);
            }
        }
        done.clear();
    }

    /** 装配事务成功后的补报：逐笔上报实际成交；上报失败只记日志，不回滚已完成的换装。 */
    private static void reportPurchases(List<Purchase> purchases) {
        if (purchases == null || purchases.isEmpty()) return;
        for (Purchase purchase : purchases) {
            long cost = (long) purchase.unitPrice() * purchase.count();
            reportPurchase(purchase, (int) Math.min(cost, Integer.MAX_VALUE));
        }
    }

    /**
     * 向市场报告一笔玩家买入交易。原版改装页（WeaponPickerDialog/FighterPickerDialog）同时走
     * 子市场插件通道和星区（CampaignEngine）通道，这里保持同样的双通道；
     * 交易模式取交互目标上真实的 {@code $tradeMode}（SNEAK/OPEN），不再写死 OPEN。
     * 采购可能已经发生，所以任何上报失败都只记 warn，绝不回滚已完成的采购与装配。
     */
    private static void reportPurchase(Purchase purchase, int cost) {
        MarketAPI market = purchase.submarket().getMarket();
        SubmarketPlugin plugin = purchase.submarket().getPlugin();
        if (market == null || plugin == null || Global.getFactory() == null) return;
        CampaignUIAPI.CoreUITradeMode mode = Moci_FighterMarketAccess.tradeMode();
        if (mode == CampaignUIAPI.CoreUITradeMode.NONE) {
            // 采购发生在交易界面里，正常不会走到这里；万一交互目标已被清空，
            // 用子市场性质推断一个可上报的模式，而不是把一笔真实成交记成 NONE。
            mode = plugin.isBlackMarket()
                    ? CampaignUIAPI.CoreUITradeMode.SNEAK : CampaignUIAPI.CoreUITradeMode.OPEN;
            Global.getLogger(Moci_FighterLoadoutService.class).warn(LOG_PREFIX
                    + "trade mode lost before reporting " + purchase.weaponId() + "; using " + mode);
        }
        PlayerMarketTransaction transaction = new PlayerMarketTransaction(market, purchase.submarket(), mode);
        CargoAPI bought = Global.getFactory().createCargo(true);
        bought.addWeapons(purchase.weaponId(), purchase.count());
        transaction.setBought(bought);
        transaction.setCreditValue(-cost);
        try {
            plugin.reportPlayerMarketTransaction(transaction);
        } catch (RuntimeException ex) {
            // 引擎监听器入口：子市场插件（可能是第三方）在成交后回调，它的异常不能撤销已完成的换装。
            Global.getLogger(Moci_FighterLoadoutService.class)
                    .warn(LOG_PREFIX + "市场交易报告失败（子市场通道）" + purchase.weaponId(), ex);
        }
        try {
            if (Global.getSector() != null) Global.getSector().reportPlayerMarketTransaction(transaction);
        } catch (RuntimeException ex) {
            // 引擎监听器入口：星区通道会分发给所有交易监听器（含其它模组），同上。
            Global.getLogger(Moci_FighterLoadoutService.class)
                    .warn(LOG_PREFIX + "市场交易报告失败（星区通道）" + purchase.weaponId(), ex);
        }
    }
    /** Called by the campaign coordinator after the native refit editor has closed. */
    public static synchronized String restoreWithoutHullmod(FleetMemberAPI member) {
        if (member == null || member.getVariant() == null || member.getVariant().hasHullMod(HULLMOD_ID)) return null;
        if (busy) return "另一项装配变更正在提交";
        // 恢复路径只读内存里的定义、不改注册库：注册库不可写时必须仍能把管理船插卸干净。
        if (!initialized) initializeApplication();
        if (failure != null) return failure;
        String eligibilityFailure = Moci_FighterLoadoutEligibility.getFailure();
        if (eligibilityFailure != null) return eligibilityFailure;
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
            // 恢复只读内存里的定义、不写注册库，因此不校验落库副本：注册库不可写时也要能卸干净。
            return transact(member, before, next, Map.of(), returns);
        } catch (RuntimeException ex) {
            // 入口：协调器在原版改装界面关闭后调用（Moci_AirSquadronCoordinator），异常不能穿进原版界面流程；
            // 能事先判断的情况都已在上面返回了原因，这里只剩变体克隆隔离失败等引擎内部状态。
            Global.getLogger(Moci_FighterLoadoutService.class).warn(LOG_PREFIX + "cannot prepare carrier restoration", ex);
            return "恢复准备失败，已保留原装配和库存";
        } finally { busy = false; }
    }
    /**
     * 市场守卫用：某自定义联队的装配相对原型基线多投入的武器（需要退回玩家货舱的那部分）。
     * 空表表示没有额外投入或定义不可用。
     */
    public static synchronized Map<String, Integer> refundForWing(String wingId) {
        Moci_FighterLoadoutSpec spec = Moci_FighterLoadoutStore.get(wingId);
        if (spec == null) return Map.of();
        // readLoadout 对缺失的联队/变体返回空表，paid 只做表计算，都不会抛出。
        return paid(readLoadout(wingId), spec.getBaseline());
    }
    /**
     * 市场守卫用：把 variant 上的自定义联队就地换回原型联队，返回应退回玩家货舱的武器合计。
     * 找不到定义的联队保持原样：不知道原型，也算不出退款。
     */
    public static synchronized Map<String, Integer> restorePrototypeWings(ShipVariantAPI variant) {
        Map<String, Integer> refund = new LinkedHashMap<>();
        if (variant == null) return refund;
        if (!initialized) initializeApplication();
        for (int i = 0; i < variant.getWings().size(); i++) {
            String id = variant.getWingId(i);
            if (!isCustomWingId(id)) continue;
            Moci_FighterLoadoutSpec spec = Moci_FighterLoadoutStore.get(id);
            if (spec == null || getWingSpec(spec.getBaseWingId()) == null) continue;
            refundForWing(id).forEach((weapon, n) -> refund.merge(weapon, n, Math::addExact));
            variant.setWingId(i, spec.getBaseWingId());
        }
        return refund;
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
            // 事务回滚：扣武器、还武器、写变体任何一步抛出（含读回校验自己抛的 IllegalStateException），
            // 都按快照把母舰和每个来源库存恢复原样；恢复不完整时进入只读保护。
            boolean restored = true;
            if (mutationsStarted) {
                if (member.getVariant() != before) try {
                    member.setVariant(before, false, true);
                    if (member.getVariant() != before) restored = false;
                } catch (RuntimeException rollbackFailure) {
                    // 回滚中的回滚：写回旧变体失败也要继续恢复库存，最后按实际状态判断是否恢复。
                    restored = member.getVariant() == before;
                }
                for (Snapshot snapshot : snapshots) for (Map.Entry<String, Integer> entry : snapshot.amounts.entrySet()) {
                    try {
                        int current = quantity(snapshot.cargo, entry.getKey());
                        int difference = entry.getValue() - current;
                        if (difference > 0) snapshot.cargo.addWeapons(entry.getKey(), difference);
                        else if (difference < 0) snapshot.cargo.removeWeapons(entry.getKey(), -difference);
                    } catch (RuntimeException rollbackFailure) {
                        // 回滚中的回滚：一条库存补不回去也要继续补其余各条，结果由下面的读回判定。
                        Global.getLogger(Moci_FighterLoadoutService.class)
                                .error(LOG_PREFIX + "inventory rollback operation failed", rollbackFailure);
                    }
                    if (rollbackQuantity(snapshot.cargo, entry.getKey()) != entry.getValue()) restored = false;
                }
            }
            if (!restored) failure = "装配事务回滚未能完成，已进入只读保护；请保留当前存档并查看日志";
            Global.getLogger(Moci_FighterLoadoutService.class)
                    .error(LOG_PREFIX + "fighter transaction failed; rollback=" + restored, ex);
            return restored ? "换装提交失败，母舰与各来源库存已恢复" : failure;
        }
    }
    /** 回滚读回用：数量异常时返回 -1，快照里的数量都是合法的非负整数，所以 -1 一定判为未恢复。 */
    private static int rollbackQuantity(CargoAPI cargo, String id) {
        float value = cargo.getQuantity(CargoAPI.CargoItemType.WEAPONS, id);
        return validQuantity(value) ? (int) value : -1;
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
                || !getTacticalHullmods(id).equals(selectedTactical(wing.getVariant()))) return "自定义联队与保存定义不一致，已保留装配";
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
        // 保存的战术改装可能同组冲突或引用了已移除的船插：按每组一项恢复，
        // 而不是让整支联队注册失败（那会连原型装配都恢复不了）。这类定义仍按原规则只读锁定。
        List<String> rawTacticals = spec.getTacticalHullmods() == null ? List.of() : spec.getTacticalHullmods();
        List<String> tolerated = tolerantTacticals(rawTacticals);
        if (!new LinkedHashSet<>(rawTacticals).equals(new LinkedHashSet<>(tolerated))) {
            Global.getLogger(Moci_FighterLoadoutService.class).warn(LOG_PREFIX + "saved tactical selection repaired to one per group: "
                    + spec.getWingId() + " " + rawTacticals + " -> " + tolerated);
            if (metadataIssue == null) metadataIssue = "保存的战术改装已失效或同组冲突，已按每组一项恢复";
        }
        if (metadataIssue != null) definitionIssues.put(spec.getWingId(), metadataIssue);
        // 再按原型判断可用性得到生效列表：变体 hullMods、联队 OP 和之后的换装都只用生效列表。
        List<String> tacticals = availableTacticals(spec.getWingId(), tolerated, prototype);
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
            applyTactical(variant, tacticals);
            applyCustomFighterTags(variant);
            if (!Moci_FighterLoadoutRegistry.registerVariant(variant)) return "战机变体注册失败";
        }
        FighterWingSpecAPI wing = getWingSpec(spec.getWingId());
        if (wing == null) {
            // Definitions that no longer fit (e.g. a weapon spec was removed) already fall back to prototype weapons above;
            // retain the original OP for that read-only fallback instead of rejecting recovery.
            float opCost = metadataIssue == null
                    ? refitBudgetForTacticals(spec.getBaseWingId(), spec.getLoadout(), tacticals).opCost()
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
        if (!tacticals.equals(selectedTactical(registeredVariant))) {
            return "战术改装与保存定义不一致";
        }
        applyCustomFighterTags(registeredVariant);
        applyCustomFighterTags(wing);
        Moci_ScveSnapshotBridge.noteWingRegistered(wing);
        if (metadataIssue == null) {
            // Existing saved definitions keep their IDs, but receive the current derived OP rule on load.
            float opCost = refitBudgetForTacticals(spec.getBaseWingId(), spec.getLoadout(), tacticals).opCost();
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
        for (String tag : CUSTOM_FIGHTER_TAGS) variant.addTag(tag);
    }

    static void applyCustomFighterTags(FighterWingSpecAPI wing) {
        // addTag() clears autofitPriorityCategories even if the tag already exists.
        // These tags do not affect autofit categories; preserve the inherited priority list.
        Set<String> tags = wing.getTags();
        tags.removeIf(Moci_FighterLoadoutService::isBlueprintTag);
        tags.addAll(CUSTOM_FIGHTER_TAGS);
        tags.add(Moci_FighterLoadoutSpec.TAG);
    }

    private static boolean isBlueprintTag(String tag) {
        return tag != null && tag.endsWith("_bp");
    }
}
