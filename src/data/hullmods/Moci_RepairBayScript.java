package data.hullmods;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.Set;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import org.jetbrains.annotations.Nullable;
import org.lazywizard.lazylib.MathUtils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.util.FaderUtil;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import org.lwjgl.util.vector.Vector2f;
import data.scripts.util.Moci_TextLoader;

/**
 * Moci_RepairBayScript - 修理舱核心系统
 * 
 * 实现AdvanceableListener接口，持续监听和处理修理过程
 * 为航母舰船提供完整的修理舱管理功能
 * 
 * 核心功能：
 * - 多修理舱管理：支持一艘舰船拥有多个修理舱
 * - 动态修理时间计算：基于损伤程度智能计算修理时间
 * - 渐进式修理过程：使用FaderUtil实现平滑的修理进度
 * - 完整状态恢复：CR、血量、弹药、装甲全面修复
 * - 队列系统：WeightedRandomPicker随机分配空闲修理舱
 * 
 * 修理时间算法：
 * - 基础时间：2秒
 * - CR修复：0.2 * (目标CR - 当前CR) * 100 秒
 * - 弹药补给：每个需要额外整备的有限弹药武器增加时间
 * - 血量修复：0.1 * (1 - 当前血量) * 100 秒
 * 
 * 技术特点：
 * - 基于武器标签"Moci_RepairBay"自动识别修理舱
 * - 修理期间完全控制被修理舰船的状态
 * - 支持玩家舰船的实时进度显示
 * - 异常情况自动清理和状态恢复
 */
public class Moci_RepairBayScript implements AdvanceableListener {
    private static final boolean ENABLE_DEBUG_LOGS = false;
    private static final String TEXT_ID = "Moci_RepairBayScript";
    private static final String LOG_PREFIX = "[MOCI_REPAIR] ";
    private static final String ANIM_LOG_PREFIX = "[MOCI_ANIM] ";
    private static final float LAUNCH_ANIM_OBSERVER_TIMEOUT = 5f;
    private static final float RESERVATION_TIMEOUT = 10f;
    public static final String LANDING_ANIM_STARTED_KEY = "Moci_LandingAnimStarted";
    public static final String LANDING_ANIM_FINISHED_KEY = "Moci_LandingAnimFinished";
    public static final String LANDING_ANIM_FINISH_REASON_KEY = "Moci_LandingAnimFinishReason";
    public static final String LAUNCH_ANIM_STARTED_KEY = "Moci_LaunchAnimStarted";
    public static final String LAUNCH_ANIM_FINISHED_KEY = "Moci_LaunchAnimFinished";
    public static final String LAUNCH_ANIM_FINISH_REASON_KEY = "Moci_LaunchAnimFinishReason";

    private List<Moci_RepairBay> repairBays = new ArrayList<>();               // 修理舱武器列表
    private Map<Moci_RepairBay,Moci_RepairBayStatus> repairBaysStatus = new HashMap<>();  // 修理舱状态映射
    private final ShipAPI ship;  // 拥有修理舱的母舰

    // 修理时间常量配置
    public static final float _CR_REPAIR_TIME = 0.2f;      // CR修复时间系数
    public static final float _WEAPON_AMMO_FEED = 4f;      // 武器弹药补给时间
    public static final float _HULL_REPAIR_TIME = 0.1f;    // 船体修复时间系数
    public static final float _REPLACEMENT_LOSS_PER_SEC = 0.02f;//2%

    public static Map<WeaponAPI.WeaponSize,Float> _WEAPON_AMMO_FEED_TIME = new HashMap<>();
    static {
        _WEAPON_AMMO_FEED_TIME.put(WeaponAPI.WeaponSize.SMALL,2f);
        _WEAPON_AMMO_FEED_TIME.put(WeaponAPI.WeaponSize.MEDIUM,7.5f);
        _WEAPON_AMMO_FEED_TIME.put(WeaponAPI.WeaponSize.LARGE,15f);
    }

    private static boolean needsAmmoRefit(@Nullable WeaponAPI weapon) {
        return Moci_AmmoRefitRules.shouldRefillAmmoDuringRepair(weapon);
    }

    private static boolean shouldCountAmmoRefitTime(@Nullable WeaponAPI weapon) {
        return Moci_AmmoRefitRules.shouldCountAmmoRefitTime(weapon);
    }

    public static class Moci_RepairBay{
        private WeaponAPI bay = null;
        private WeaponSlotAPI slot;
        private ShipAPI ship;

        public Moci_RepairBay(WeaponAPI weapon){
            this.bay = weapon;
            this.ship = weapon.getShip();
        }

        public Moci_RepairBay(WeaponSlotAPI slot,ShipAPI ship){
            this.slot = slot;
            this.ship = ship;
        }

        public Vector2f getLocation(){
            return (bay!=null)?bay.getLocation():slot.computePosition(ship);
        }

        public float getFacing(){
            return (bay!=null)?bay.getCurrAngle():slot.computeMidArcAngle(ship);
        }

        public WeaponAPI getBay() {
            return bay;
        }

        public WeaponSlotAPI getSlot() {
            return slot;
        }

        public ShipAPI getShip() {
            return ship;
        }

        public String getSlotId() {
            if (bay != null && bay.getSlot() != null) {
                return bay.getSlot().getId();
            }
            return slot != null ? slot.getId() : null;
        }
    }

    /**
     * 修理舱状态管理类
     * 负责单个修理舱的状态跟踪和修理过程控制
     */
    public static class Moci_RepairBayStatus {
        protected boolean isOccupied = false;
        protected FaderUtil repairSequence = new FaderUtil(0f, 10f);
        protected ShipAPI rs;
        protected final Moci_RepairBay bay;
        protected ShipAPI takeOff;
        private ShipAPI reservationOwner;
        private float reservationElapsed;
        private final List<RepairSnapshot> repairParts = new ArrayList<>();

        public Moci_RepairBayStatus(WeaponAPI bay) {
            this(new Moci_RepairBay(bay));
        }

        public Moci_RepairBayStatus(WeaponSlotAPI slot, ShipAPI source) {
            this(new Moci_RepairBay(slot, source));
        }

        public Moci_RepairBayStatus(Moci_RepairBay bay) {
            this.bay = bay;
        }

        /** 仅表示正在实际整备；预约占用和起飞等待由 isAvailableFor 一并检查。 */
        public boolean isOccupied() {
            return isOccupied;
        }

        public FaderUtil getRepairSequence() {
            return repairSequence;
        }

        /** 查询不会占用泊位；预约者可以继续使用自己在接近阶段取得的预约。 */
        public boolean isAvailableFor(ShipAPI claimant) {
            return isRegistered() && isOperationalEntity(bay.getShip()) && !isOccupied && takeOff == null
                    && (reservationOwner == null || reservationOwner == claimant);
        }

        private boolean isRegistered() {
            Moci_RepairBayScript current = getInstance(bay.getShip());
            return current != null && current.getBay(bay) == this;
        }

        public boolean tryReserve(ShipAPI claimant) {
            if (!isOperationalEntity(claimant) || !isAvailableFor(claimant)) {
                return false;
            }
            reservationOwner = claimant;
            reservationElapsed = 0f;
            return true;
        }

        public boolean renewReservation(ShipAPI claimant) {
            if (reservationOwner != claimant || !isRegistered() || !isOperationalEntity(claimant)
                    || !isOperationalEntity(bay.getShip())) {
                return false;
            }
            reservationElapsed = 0f;
            return true;
        }

        /** 只有预约者可以释放泊位，整备或起飞途中取消也遵循此规则。 */
        public void releaseReservation(ShipAPI claimant) {
            if (claimant == null || reservationOwner != claimant) {
                return;
            }
            reservationOwner = null;
            reservationElapsed = 0f;
            if (rs == claimant) {
                rs = null;
                isOccupied = false;
                repairParts.clear();
            }
            if (takeOff == claimant) {
                takeOff = null;
            }
        }

        private void cancelReservation(boolean restoreAI) {
            ShipAPI owner = reservationOwner;
            releaseReservation(owner);
            Moci_RefitSession session = Moci_RefitSession.get(owner);
            if (session != null) {
                session.abort(restoreAI);
            }
        }

        public boolean startRepair(ShipAPI shipToRepair) {
            if (isOccupied && rs == shipToRepair) {
                return true;
            }
            if (Moci_RefitPolicy.getBlockReason(shipToRepair) != Moci_RefitPolicy.BlockReason.NONE) {
                return false;
            }
            Moci_RefitSession existingSession = Moci_RefitSession.get(shipToRepair);
            Moci_MobileSuitRepairTracker existingTracker = Moci_MobileSuitRepairTracker.get(shipToRepair);
            if (existingSession != null && existingSession.isActive()) {
                if (existingTracker == null || existingTracker.getBay() != bay
                        || existingTracker.getState() != Moci_MobileSuitRepairTracker.State.APPROACHING) {
                    return false;
                }
            } else if (!Moci_RefitPolicy.canStartRefit(shipToRepair)) {
                return false;
            }
            if (!tryReserve(shipToRepair)) {
                return false;
            }
            rs = shipToRepair;
            isOccupied = true;
            repairParts.clear();
            for (ShipAPI part : getRepairableParts(rs)) {
                repairParts.add(new RepairSnapshot(part));
            }

            Moci_MobileSuitRepairTracker tracker = Moci_MobileSuitRepairTracker.getOrCreate(rs);
            if (tracker.getBay() != bay) {
                tracker.beginApproach(bay.getShip(), bay);
            }
            Moci_LandingSequence.recordLandingMemory(rs, bay);
            float repairTime = computeRepairTime(rs);
            ShipAPI carrier = bay.getShip();
            if (carrier.getVariant() != null
                    && (carrier.getVariant().hasHullMod("Moci_ms_repair_bay")
                    || carrier.getVariant().hasHullMod("HugeOpenPort")
                    || carrier.getVariant().hasHullMod("OpenPort"))) {
                repairTime *= 0.5f;
            }
            repairSequence = new FaderUtil(0f, repairTime);
            repairSequence.fadeIn();
            Moci_RefitSession.getOrCreate(rs).enterRepair();
            logRepairEvent(rs, "startRepair: carrier=" + carrier.getName() + ", slot=" + bay.getSlotId());
            return true;
        }

        /** 通过接近阶段使用的同一个整备会话，统一解除整备保护。 */
        public void forceFinishRepair() {
            if (!isOccupied || rs == null) {
                return;
            }
            ShipAPI launching = rs;
            if (!isOperationalEntity(launching)) {
                cancelReservation(false);
                return;
            }
            Moci_RefitSession session = Moci_RefitSession.getOrCreate(launching);
            ShipAIConfig restoreConfig = session.getRestoreAIConfig();
            isOccupied = false;
            rs = null;
            repairParts.clear();
            takeOff = launching;
            // 保留泊位预约，直到起飞观察器完成收尾或整备会话中止时再释放。
            session.beginLaunch(restoreConfig);
            if (!launching.hasListenerOfClass(Moci_AnimationLaunch.class)) {
                launching.addListener(new Moci_AnimationLaunch(launching));
            }
            if (!launching.hasListenerOfClass(Moci_LaunchAnimationObserver.class)) {
                launching.addListener(new Moci_LaunchAnimationObserver(launching, this));
            }
            Vector2f carrierVelocity = bay.getShip().getVelocity();
            float angle = (float) Math.toRadians(bay.getFacing());
            launching.getVelocity().set(carrierVelocity.x + (float) Math.cos(angle) * 150f,
                    carrierVelocity.y + (float) Math.sin(angle) * 150f);
            logRepairEvent(launching, "repair finished; waiting for launch clearance");
        }

        public void advance(float amount) {
            if (reservationOwner == null) {
                return;
            }
            if (!isOperationalEntity(reservationOwner)) {
                cancelReservation(false);
                return;
            }
            if (!isOperationalEntity(bay.getShip())) {
                if (isOccupied) {
                    Moci_MobileSuitRepairTracker.getOrCreate(rs).markEmergencyRelease();
                    forceFinishRepair();
                } else if (takeOff == null) {
                    cancelReservation(true);
                }
                return;
            }
            if (takeOff != null) {
                return; // 即使母舰已被移除，也由起飞机体上的观察器负责完成收尾。
            }
            if (!isOccupied) {
                reservationElapsed += Math.max(0f, amount);
                if (reservationElapsed >= RESERVATION_TIMEOUT) {
                    cancelReservation(true);
                }
                return;
            }

            Moci_RefitSession session = Moci_RefitSession.get(rs);
            if (session != null) {
                session.advance();
            }
            if (!isOccupied || rs == null) {
                return; // 玩家接管或其他中止流程可能已释放该泊位。
            }
            repairSequence.advance(amount);
            for (RepairSnapshot part : repairParts) {
                if (isAttachedLivingPart(rs, part.ship)) {
                    part.advance(repairSequence.getBrightness());
                }
            }
            if (bay.getBay() == null) {
                for (FighterLaunchBayAPI launchBay : bay.getShip().getLaunchBaysCopy()) {
                    if (launchBay.getWeaponSlot().equals(bay.getSlot())) {
                        float currentRate = launchBay.getCurrRate();
                        launchBay.setCurrRate(Math.min(currentRate,
                                Math.max(0.3f, currentRate - _REPLACEMENT_LOSS_PER_SEC * amount)));
                    }
                }
            }
            rs.getLocation().set(bay.getLocation());
            rs.getVelocity().set(bay.getShip().getVelocity());
            if (!Moci_LandingSequence.bayShouldHidden(bay) || rs.getExtraAlphaMult2() <= 0f) {
                rs.setFacing(bay.getFacing());
            }
            Moci_RefitSession.getOrCreate(rs).maintainRepairState();
            if (repairSequence.isFadedIn()) {
                for (RepairSnapshot part : repairParts) {
                    if (isAttachedLivingPart(rs, part.ship)) {
                        finishPartRepair(part.ship);
                    }
                }
                forceFinishRepair();
            } else if (rs == Global.getCombatEngine().getPlayerShip()) {
                Global.getCombatEngine().maintainStatusForPlayerShip("Moci_Repair",
                        Global.getSettings().getSpriteName("ui", "icon_tactical_bdeck"),
                        Moci_TextLoader.getText(TEXT_ID, "status.title"),
                        Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.repairing",
                                Moci_TextLoader.mapOf("seconds", String.format("%.1f",
                                        (1f - repairSequence.getBrightness()) * repairSequence.getDurationIn()))), true);
            }
        }

        /** 保留此入口，供外部明确要求修复当前整备机体的装甲时调用。 */
        public void repairArmor() {
            if (rs != null) {
                for (ShipAPI part : getRepairableParts(rs)) {
                    restoreArmor(part);
                }
            }
        }
    }

    private static final class RepairSnapshot {
        private final ShipAPI ship;
        private final float baseHull;
        private final float baseCR;
        private final float targetCR;

        private RepairSnapshot(ShipAPI ship) {
            this.ship = ship;
            baseHull = ship.getHullLevel();
            baseCR = ship.getCurrentCR();
            targetCR = Math.max(baseCR, ship.getCRAtDeployment());
        }

        private void advance(float progress) {
            ship.setHitpoints(Math.min(ship.getMaxHitpoints(), ship.getMaxHitpoints()
                    * (baseHull + progress * (1f - baseHull))));
            ship.setCurrentCR(baseCR + progress * (targetCR - baseCR));
        }
    }

    private static boolean isOperationalEntity(ShipAPI ship) {
        return isLivingPart(ship) && Global.getCombatEngine() != null
                && Global.getCombatEngine().isEntityInPlay(ship);
    }

    private static boolean isLivingPart(ShipAPI ship) {
        return ship != null && ship.isAlive() && !ship.isHulk() && ship.getHitpoints() > 0f;
    }

    static List<ShipAPI> getRepairableParts(ShipAPI root) {
        List<ShipAPI> parts = new ArrayList<>();
        Set<ShipAPI> seen = Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
        collectRepairableParts(root, parts, seen);
        return parts;
    }

    private static void collectRepairableParts(ShipAPI ship, List<ShipAPI> parts, Set<ShipAPI> seen) {
        if (!isLivingPart(ship) || !seen.add(ship)) {
            return;
        }
        parts.add(ship);
        for (ShipAPI module : ship.getChildModulesCopy()) {
            // 原生脱离流程会清空停靠槽位，但可能仍保留原来的父舰引用。
            if (module != null && module.isStationModule() && module.getStationSlot() != null
                    && module.getParentStation() == ship) {
                collectRepairableParts(module, parts, seen);
            }
        }
    }

    static boolean isAttachedLivingPart(ShipAPI root, ShipAPI part) {
        Set<ShipAPI> seen = Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
        ShipAPI current = part;
        while (isLivingPart(current) && seen.add(current)) {
            if (current == root) {
                return true;
            }
            if (!current.isStationModule() || current.getStationSlot() == null) {
                return false;
            }
            current = current.getParentStation();
        }
        return false;
    }

    private static void finishPartRepair(ShipAPI ship) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (needsAmmoRefit(weapon)) {
                weapon.setAmmo(weapon.getMaxAmmo());
            }
            if (weapon != null && weapon.isDisabled() && !weapon.isPermanentlyDisabled()) {
                weapon.repair();
            }
        }
        if (ship.getEngineController() != null) {
            for (ShipEngineControllerAPI.ShipEngineAPI engine : ship.getEngineController().getShipEngines()) {
                if (engine.isDisabled() && !engine.isPermanentlyDisabled()) {
                    engine.repair();
                }
            }
        }
        if (ship.getMutableStats().getPeakCRDuration().computeEffective(0f) < ship.getTimeDeployedForCRReduction()) {
            ship.getMutableStats().getPeakCRDuration().modifyFlat(ship.getId(), ship.getTimeDeployedForCRReduction());
        }
        restoreArmor(ship);
    }

    private static void restoreArmor(ShipAPI ship) {
        ArmorGridAPI armor = ship.getArmorGrid();
        if (armor == null) {
            return;
        }
        float[][] cells = armor.getGrid();
        for (int x = 0; x < cells.length; x++) {
            for (int y = 0; y < cells[x].length; y++) {
                armor.setArmorValue(x, y, armor.getMaxArmorInCell());
            }
        }
        ship.syncWithArmorGridState();
        ship.syncWeaponDecalsWithArmorDamage();
    }

    /**
     * 计算舰船的修理时间
     * 基于舰船的损伤情况动态计算所需的修理时间
     * 
     * @param ship 要修理的舰船
     * @return 计算出的修理时间（秒）
     */
    public static float computeRepairTime(ShipAPI ship) {
        float time = 2f;
        for (ShipAPI part : getRepairableParts(ship)) {
            time += _CR_REPAIR_TIME * MathUtils.clamp(
                    (part.getCRAtDeployment() - part.getCurrentCR()) * 100f, 0f, 100f);
            time += _HULL_REPAIR_TIME * MathUtils.clamp((1f - part.getHullLevel()) * 100f, 0f, 100f);
            for (WeaponAPI weapon : part.getAllWeapons()) {
                if (shouldCountAmmoRefitTime(weapon)) {
                    Float ammoTime = _WEAPON_AMMO_FEED_TIME.get(weapon.getSize());
                    if (ammoTime != null) {
                        time += ammoTime;
                    }
                }
            }
        }
        return time;
    }

    /**
     * 构造函数
     * 初始化修理舱系统，扫描母舰的所有修理舱
     * 
     * @param ship 拥有修理舱的母舰
     */
    public Moci_RepairBayScript(ShipAPI ship){
        this.ship = ship;
        
        // 扫描所有武器，找到修理舱
        for(WeaponAPI weapon:ship.getAllWeapons()){
            if(weapon.getSpec().hasTag("Moci_RepairBay")||weapon.getSpec().getWeaponId().equals("Moci_RepairBayBeacon")){
                Moci_RepairBay bay = new Moci_RepairBay(weapon);
                repairBays.add(bay);  // 添加到修理舱列表
                repairBaysStatus.put(bay,new Moci_RepairBayStatus(bay));  // 创建状态跟踪
            }
        }

        if(repairBays.isEmpty()){
            int numDecks = ship.getNumFighterBays();
            for(String slotId:ship.getVariant().getLaunchBaysSlotIds()){
                if(numDecks>0){
                    WeaponSlotAPI slot = ship.getVariant().getSlot(slotId);
                    if(slot!=null) {
                        Moci_RepairBay bay = new Moci_RepairBay(slot,ship);
                        repairBays.add(bay);
                        repairBaysStatus.put(bay,new Moci_RepairBayStatus(bay));
                        numDecks--;
                    }
                }
            }
        }
        
        // 将脚本实例保存到舰船自定义数据中
        ship.setCustomData("Moci_RepairBayScript",this);
    }

    /**
     * 主推进方法
     * 每帧调用，更新所有修理舱的状态
     */
    @Override
    public void advance(float amount) {
        if (getInstance(ship) != this) {
            for (Moci_RepairBayStatus status : repairBaysStatus.values()) {
                status.cancelReservation(true);
            }
            ship.removeListener(this);
            return; // 不清除其他脚本实例的登记。
        }
        for (Moci_RepairBay bay : repairBays) {
            repairBaysStatus.get(bay).advance(amount);
        }
        if (!isOperationalEntity(ship)) {
            // 起飞收尾由各机体自身的观察器负责，不依赖母舰上的此监听器。
            ship.removeListener(this);
            if (getInstance(ship) == this) {
                ship.removeCustomData("Moci_RepairBayScript");
            }
        }
    }

    /**
     * 检查是否有空闲的修理舱
     * 
     * @return 如果有空闲修理舱返回true
     */
    public boolean hasVacancy(){
        return getVacancy()!=null;
    }

    /**
     * 获取一个空闲的修理舱
     * 使用加权随机选择器从所有空闲修理舱中随机选择一个
     * 
     * @return 空闲的修理舱武器，如果没有空闲的则返回null
     */
    public Moci_RepairBay getVacancy(){
        return getVacancy(null);
    }

    public Moci_RepairBay getVacancy(String preferredSlotId) {
        return getVacancyFor(null, preferredSlotId);
    }

    public Moci_RepairBay getVacancyFor(ShipAPI claimant, String preferredSlotId) {
        WeightedRandomPicker<Moci_RepairBay> available = new WeightedRandomPicker<>();
        for (Moci_RepairBay candidate : repairBays) {
            if (!repairBaysStatus.get(candidate).isAvailableFor(claimant)) {
                continue;
            }
            if (preferredSlotId != null && preferredSlotId.equals(candidate.getSlotId())) {
                return candidate;
            }
            available.add(candidate);
        }
        return available.isEmpty() ? null : available.pick();
    }

    /**
     * 获取指定舰船的修理舱脚本实例
     * 从舰船的自定义数据中提取修理舱脚本
     * 
     * @param ship 目标舰船
     * @return 修理舱脚本实例，如果没有则返回null
     */
    @Nullable
    public static Moci_RepairBayScript getInstance(ShipAPI ship){
        Moci_RepairBayScript script = null;
        if(ship != null && ship.getCustomData()!=null&& ship.getCustomData().containsKey("Moci_RepairBayScript")&&ship.getCustomData().get("Moci_RepairBayScript") instanceof Moci_RepairBayScript){
            script = (Moci_RepairBayScript) ship.getCustomData().get("Moci_RepairBayScript");
        }
        return script;
    }

    /**
     * 获取指定修理舱的状态
     * 
     * @param bay 修理舱武器
     * @return 修理舱状态对象，如果不存在则返回null
     */
    public Moci_RepairBayStatus getBay(Moci_RepairBay bay){
        if(repairBaysStatus.containsKey(bay)){
            return repairBaysStatus.get(bay);
        }else{
            return null;
        }
    }



    public static class Moci_AnimationLaunch implements AdvanceableListener {
        private final ShipAPI ship;
        private final FaderUtil fader = new FaderUtil(0f, 1f);

        public Moci_AnimationLaunch(ShipAPI ship) {
            this.ship = ship;
            fader.fadeIn();
        }

        @Override
        public void advance(float amount) {
            if (!isOperationalEntity(ship)) {
                ship.removeListener(this);
                return;
            }
            fader.advance(amount);
            for (ShipAPI part : getRepairableParts(ship)) {
                part.setExtraAlphaMult2(fader.getBrightness());
            }
            if (fader.isFadedIn()) {
                ship.removeListener(this);
            }
        }
    }

    public static class Moci_AnimationLanding implements AdvanceableListener{

        private ShipAPI ship;
        private FaderUtil fader = new FaderUtil(1,1f);

        public Moci_AnimationLanding(ShipAPI ship){
            this.ship = ship;
            fader.fadeOut();
        }

        @Override
        public void advance(float v) {
            fader.advance(v);
            for(ShipAPI module:ship.getChildModulesCopy()){
                module.setExtraAlphaMult2(fader.getBrightness());
            }
            ship.setExtraAlphaMult2(fader.getBrightness());
            if(fader.isFadedOut()){
                logAnimationEvent(ship, ANIM_LOG_PREFIX + "辅助着陆淡出完成。state=" + getAnimationStateSummary(ship));
                ship.removeListener(this);
            }
        }
    }

    public static class Moci_LaunchAnimationObserver implements AdvanceableListener {
        private final ShipAPI ship;
        private final Moci_RepairBayStatus berth;
        private float elapsed;

        public Moci_LaunchAnimationObserver(ShipAPI ship) {
            this(ship, null);
        }

        public Moci_LaunchAnimationObserver(ShipAPI ship, Moci_RepairBayStatus berth) {
            this.ship = ship;
            this.berth = berth;
        }

        @Override
        public void advance(float amount) {
            elapsed += amount;
            Moci_RefitSession session = Moci_RefitSession.get(ship);
            if (!isOperationalEntity(ship)) {
                if (session != null) {
                    session.abort(false);
                }
                releaseBerth();
                ship.removeListener(this);
                return;
            }
            // 原生状态在屏幕外也会推进；combinedAlphaMult 依赖渲染，不能作为完成依据。
            boolean finished = elapsed >= 1f && !ship.isLanding() && !ship.isLiftingOff()
                    && !ship.hasListenerOfClass(Moci_AnimationLaunch.class);
            if (finished || elapsed >= LAUNCH_ANIM_OBSERVER_TIMEOUT) {
                markLaunchAnimationFinished(ship, finished ? "native_launch_complete" : "launch_timeout_recovery");
                if (session != null) {
                    session.finishLaunch();
                }
                releaseBerth();
                ship.removeListener(this);
            }
        }

        private void releaseBerth() {
            if (berth != null) {
                berth.releaseReservation(ship);
            }
        }
    }

    public static void markLandingAnimationStarted(ShipAPI ship, Moci_RepairBay bay, boolean nativeAnimation) {
        if (ship == null) {
            return;
        }
        ship.setCustomData(LANDING_ANIM_STARTED_KEY, true);
        ship.removeCustomData(LANDING_ANIM_FINISHED_KEY);
        ship.removeCustomData(LANDING_ANIM_FINISH_REASON_KEY);
        String mode = nativeAnimation ? "native" : "custom_fade";
        logAnimationEvent(ship, ANIM_LOG_PREFIX + "着陆动画开始。mode="
                + mode
                + ", carrier=" + (bay != null && bay.getShip() != null ? bay.getShip().getName() : "null")
                + ", bay=" + (bay != null ? bay.getSlotId() : "null")
                + ", state=" + getAnimationStateSummary(ship));
    }

    public static void markLandingAnimationFinished(ShipAPI ship, String reason) {
        if (ship == null) {
            return;
        }
        ship.setCustomData(LANDING_ANIM_FINISHED_KEY, true);
        ship.setCustomData(LANDING_ANIM_FINISH_REASON_KEY, reason);
        logAnimationEvent(ship, ANIM_LOG_PREFIX + "着陆动画完成标记已记录。reason="
                + reason + ", state=" + getAnimationStateSummary(ship));
    }

    public static void clearLaunchAnimationMarkers(ShipAPI ship) {
        if (ship == null) {
            return;
        }
        ship.removeCustomData(LAUNCH_ANIM_STARTED_KEY);
        ship.removeCustomData(LAUNCH_ANIM_FINISHED_KEY);
        ship.removeCustomData(LAUNCH_ANIM_FINISH_REASON_KEY);
    }

    public static void markLaunchAnimationStarted(ShipAPI ship, String reason) {
        if (ship == null) {
            return;
        }
        ship.setCustomData(LAUNCH_ANIM_STARTED_KEY, true);
        ship.removeCustomData(LAUNCH_ANIM_FINISHED_KEY);
        ship.removeCustomData(LAUNCH_ANIM_FINISH_REASON_KEY);
        logAnimationEvent(ship, ANIM_LOG_PREFIX + "起飞动画开始。reason="
                + reason + ", state=" + getAnimationStateSummary(ship));
    }

    public static void markLaunchAnimationFinished(ShipAPI ship, String reason) {
        if (ship == null) {
            return;
        }
        ship.setCustomData(LAUNCH_ANIM_FINISHED_KEY, true);
        ship.setCustomData(LAUNCH_ANIM_FINISH_REASON_KEY, reason);
        logAnimationEvent(ship, ANIM_LOG_PREFIX + "起飞动画完成标记已记录。reason="
                + reason + ", state=" + getAnimationStateSummary(ship));
    }

    public static String getAnimationStateSummary(ShipAPI ship) {
        if (ship == null) {
            return "ship=null";
        }

        Object landingStarted = ship.getCustomData().get(LANDING_ANIM_STARTED_KEY);
        Object landingFinished = ship.getCustomData().get(LANDING_ANIM_FINISHED_KEY);
        Object landingReason = ship.getCustomData().get(LANDING_ANIM_FINISH_REASON_KEY);
        Object launchStarted = ship.getCustomData().get(LAUNCH_ANIM_STARTED_KEY);
        Object launchFinished = ship.getCustomData().get(LAUNCH_ANIM_FINISHED_KEY);
        Object launchReason = ship.getCustomData().get(LAUNCH_ANIM_FINISH_REASON_KEY);

        return "isLanding=" + ship.isLanding()
                + ", isFinishedLanding=" + ship.isFinishedLanding()
                + ", combinedAlpha=" + String.format("%.2f", ship.getCombinedAlphaMult())
                + ", extraAlpha2=" + String.format("%.2f", ship.getExtraAlphaMult2())
                + ", landingStarted=" + landingStarted
                + ", landingFinished=" + landingFinished
                + ", landingReason=" + landingReason
                + ", launchStarted=" + launchStarted
                + ", launchFinished=" + launchFinished
                + ", launchReason=" + launchReason;
    }

    private static boolean shouldLogDebug() {
        return ENABLE_DEBUG_LOGS;
    }

    private static void logRepairEvent(ShipAPI ship, String message) {
        if (!shouldLogDebug()) {
            return;
        }
        if (ship == null) {
            return;
        }
        Global.getLogger(Moci_RepairBayScript.class).info(LOG_PREFIX + ship.getName() + " - " + message);
    }

    private static void logAnimationEvent(ShipAPI ship, String message) {
        if (!shouldLogDebug()) {
            return;
        }
        if (ship == null) {
            return;
        }
        Global.getLogger(Moci_RepairBayScript.class).info(message + " ship=" + ship.getName());
    }
}
