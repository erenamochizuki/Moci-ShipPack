package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.input.InputEventAPI;
import data.subsystems.Moci_NTDSubsystem;
import java.util.*;
import org.magiclib.subsystems.MagicSubsystem;
import org.magiclib.subsystems.MagicSubsystemsManager;

/** An owned overlay on the actual installed NTD; no duplicated subsystem or original callback. */
public final class UnicornNtdEnhancement implements AutoCloseable, data.scripts.combat.Moci_CombatSink {
    public static final String KEY = "xi.arsenal.unicornNtd";
    public static final String OWNER_KEY = KEY + ".owner";
    /** Legacy binary constant: the new actual backlash is uptime-scaled,not fixed20. */
    @Deprecated public static final float FATIGUE_SECONDS = 20f;
    public static final int MAX_ACTORS = 128, MAX_SCAN_CANDIDATES = 512;
    private final Map<ShipAPI, Actor> actors = new IdentityHashMap<>();
    private final Map<String, Object> facts = new LinkedHashMap<>();
    private CombatEngineAPI engine;
    private UnicornNtdInterference interference;
    private UnicornNtdOverloadSkill electronicWarfare;
    private MociFunnelTargetRouter funnels;
    private MociWeaponTargetBindings weaponBindings;
    private PsychoframeEnhancement psychoframe;
    private VentingMissileRepulsion ventingMissileRepulsion;
    private boolean closed, duplicate;
    private int cursor;
    private float scanCountdown;
    private long frames, scanned, capacitySkips, fatigueStarts;
    private int resonanceLiveMainCount;

    private static final class Actor {
        final ShipAPI ship;
        final UnicornNtdStats stats;
        final UnicornMagnumModes magnum;
        final Map<String, Object> facts = new LinkedHashMap<>();
        Moci_NTDSubsystem ntd;
        final UnicornNtdBacklash backlash=new UnicornNtdBacklash();
        float refreshCountdown,localTime;
        Actor(ShipAPI ship) { this.ship = ship; stats = new UnicornNtdStats(ship); magnum=new UnicornMagnumModes(ship); }
    }

    /**
     * NTD 叠加层的宿主：装了共享 NTD 的舰船一出现就惰性安装，不再需要全局战斗插件。
     */
    public static UnicornNtdEnhancement ensure(CombatEngineAPI engine) {
        if (Global.getCurrentState() != com.fs.starfarer.api.GameState.COMBAT) return null;
        if (engine == null) return null;
        Object current = engine.getCustomData().get(OWNER_KEY);
        if (current instanceof UnicornNtdEnhancement own && !own.closed) return own;
        UnicornNtdEnhancement created = new UnicornNtdEnhancement();
        created.start(engine);
        return created.closed || created.duplicate ? null : created;
    }

    void start(CombatEngineAPI engine) {
        if (closed) throw new IllegalStateException("Closed Unicorn NTD overlay cannot be reused");
        if (this.engine != null && this.engine != engine) throw new IllegalStateException("NTD overlay engine changed");
        this.engine = Objects.requireNonNull(engine);
        Object current = engine.getCustomData().get(OWNER_KEY);
        if (current != null && current != this) { duplicate = true; return; }
        engine.getCustomData().put(OWNER_KEY, this);
        interference = new UnicornNtdInterference(engine);
        electronicWarfare = new UnicornNtdOverloadSkill(engine);
        funnels = new MociFunnelTargetRouter(engine);
        weaponBindings = new MociWeaponTargetBindings(engine);
        psychoframe = new PsychoframeEnhancement(engine);
        ventingMissileRepulsion = new VentingMissileRepulsion(engine);
        // 无引擎外资源：close() 只逐个 close 上列子系统（全是引擎内 listener/plugin 封装）并注销总线，
        // 结束链路由本类 advance() 的 combat-over 守卫 + Moci_CombatBus.closeAll() 覆盖。
        facts.put("schema", 1); facts.put("closed", false);
        facts.put("scope", "Moci RX-0-1/2/3 complete primary hulls with their real installed shared NTD subsystem");
        facts.put("supportedMainHulls",UnicornNtdStats.HULL_IDS.stream().sorted().toList());
        facts.put("interferenceName", "NTD：干扰");
        facts.put("electronicWarfareName", "NTD：电子战");
        facts.put("ntdMode","native-toggle");facts.put("upkeepSoftFluxPerLocalSecond",UnicornToggleNtdSubsystem.UPKEEP_SOFT_FLUX_PER_SECOND);
        facts.put("backlashBaseLocalSeconds",UnicornNtdBacklash.BASE_LOCK_SECONDS);facts.put("backlashMaximumLocalSeconds",UnicornNtdBacklash.MAX_LOCK_SECONDS);
        engine.getCustomData().put(KEY, facts);
        publish();
        data.scripts.combat.Moci_CombatBus.register(this);
    }

    public static UnicornNtdEnhancement instance(CombatEngineAPI engine) {
        Object value = engine == null ? null : engine.getCustomData().get(OWNER_KEY);
        return value instanceof UnicornNtdEnhancement overlay ? overlay : null;
    }
    @SuppressWarnings("unchecked") public static Map<String, Object> status(CombatEngineAPI engine) {
        Object value = engine == null ? null : engine.getCustomData().get(KEY);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }
    public UnicornNtdOverloadSkill.Activation requestActivation(ShipAPI player) {
        if (closed || duplicate || electronicWarfare == null)
            return new UnicornNtdOverloadSkill.Activation(false, "NTD：电子战尚未就绪");
        UnicornNtdOverloadSkill.Activation result = electronicWarfare.requestActivation(player);
        publish();
        return result;
    }

    @Override public void advance(float amount, List<InputEventAPI> inputs) {
        if (closed || duplicate || engine == null) return;
        if (engine.isCombatOver() || Global.getCombatEngine() != engine) { close(); return; }
        if (!Float.isFinite(amount) || amount < 0f) throw new IllegalArgumentException("Invalid NTD frame amount");
        if (engine.isPaused()) { hud(); return; }
        frames++; scanCountdown -= amount;
        weaponBindings.advance(amount);
        if (scanCountdown <= 0f) { scan(); scanCountdown = .1f; }
        resonanceLiveMainCount=UnicornNtdResonance.count(engine);
        List<ShipAPI> operational = new ArrayList<>();
        for (Iterator<Actor> iterator = actors.values().iterator(); iterator.hasNext();) {
            Actor actor=iterator.next();ShipAPI ship=actor.ship;
            if(!live(ship)){actor.magnum.close();actor.stats.close();if(actor.ntd instanceof UnicornToggleNtdSubsystem own)own.close();iterator.remove();continue;}
            float multiplier=ship.getMutableStats().getTimeMult().getModifiedValue();
            if(!Float.isFinite(multiplier)||multiplier<0)throw new IllegalStateException("Invalid Unicorn local time");
            float step=amount*multiplier;actor.localTime+=step;actor.refreshCountdown-=amount;
            if(actor.ntd==null||actor.refreshCountdown<=0){
                Moci_NTDSubsystem actual=findNtd(ship);
                if(actual!=actor.ntd){if(actor.ntd instanceof UnicornToggleNtdSubsystem old)old.close();actor.ntd=actual;}
                actor.refreshCountdown=.25f;
            }
            UnicornToggleNtdSubsystem toggle=actor.ntd instanceof UnicornToggleNtdSubsystem own?own:null;
            boolean beganBacklash=false;
            if(toggle!=null){
                toggle.setRuntimeLocks(resonanceLiveMainCount>=2,actor.backlash.remaining()>0,resonanceLiveMainCount);
                toggle.forceOutForOverload();
                beganBacklash=actor.backlash.begin(ship,toggle.pollShutdown());
                if(beganBacklash){fatigueStarts++;actor.facts.put("fatigueStartedLocalTime",actor.localTime);actor.facts.remove("fatigueEndedLocalTime");}
            }
            float before=actor.backlash.remaining();
            if(!beganBacklash)actor.backlash.advance(step);
            if(before>0&&actor.backlash.remaining()<=0)actor.facts.put("fatigueEndedLocalTime",actor.localTime);
            actor.backlash.suppressAndMaintain(ship);
            if(toggle!=null){
                toggle.setRuntimeLocks(resonanceLiveMainCount>=2,actor.backlash.remaining()>0,resonanceLiveMainCount);
                if(resonanceLiveMainCount>=2)toggle.requestResonanceOn();
            }
            MagicSubsystem.State state=actor.ntd==null?null:actor.ntd.getCurrentState();
            boolean on=state==MagicSubsystem.State.IN||state==MagicSubsystem.State.ACTIVE||state==MagicSubsystem.State.OUT;
            float level=on?actor.ntd.getCurrentEffectLevel():0;
            level=Float.isFinite(level)?Math.max(0,Math.min(1,level)):0;
            actor.stats.apply(actor.backlash.remaining()>0?0:level);
            if(actor.backlash.remaining()<=0&&(state==MagicSubsystem.State.IN||state==MagicSubsystem.State.ACTIVE)
                    &&actor.ntd.isAwakened()&&level>=.8f&&!ship.getFluxTracker().isOverloaded())operational.add(ship);
            float uptime=toggle==null?0:toggle.onLocalSeconds();UnicornNtdBacklash.Estimate estimate=UnicornNtdBacklash.estimate(ship,uptime);
            actor.facts.put("shipId",ship.getId());actor.facts.put("owner",ship.getOwner());actor.facts.put("hull",ship.getHullSpec().getHullId());
            actor.facts.put("nativeState",state==null?"MISSING":state.name());actor.facts.put("phase",actor.backlash.remaining()>0?"fatigue":on?"ntd":"normal");
            actor.facts.put("effectLevel",level);actor.facts.put("localTime",actor.localTime);actor.facts.put("toggleOwned",toggle!=null);
            actor.facts.put("compatibility",actor.ntd==null?"missing-native-ntd":toggle==null?"foreign-original-ntd-not-replaced":"owned-shared-moci-toggle");
            actor.facts.put("ntdActiveDurationLocalSeconds",actor.ntd==null?0:actor.ntd.getActiveDuration());
            actor.facts.put("ntdOnLocalSeconds",uptime);actor.facts.put("fatigueRemainingLocalSeconds",actor.backlash.remaining());
            actor.facts.put("resonanceLiveMainCount",resonanceLiveMainCount);actor.facts.put("resonanceLocked",toggle!=null&&toggle.resonanceLocked());
            actor.facts.put("activationBlockReason",toggle==null?"未安装自有常驻NTD":toggle.blockReason());
            actor.facts.put("predictedHullCost",estimate.hullCost());actor.facts.put("predictedBacklashLocalSeconds",estimate.lockSeconds());
            actor.facts.put("backlash",actor.backlash.status());actor.facts.put("ntdCycles",toggle==null?0:toggle.cycles());actor.facts.put("ntdShutoffs",toggle==null?0:toggle.shutoffs());
            actor.facts.put("nativeOverloaded",ship.getFluxTracker().isOverloaded());actor.facts.put("nativeOverloadRemaining",ship.getFluxTracker().getOverloadTimeRemaining());
        }
        interference.advance(operational, amount);
        electronicWarfare.advance(operational, amount, inputs == null ? List.of() : inputs);
        for(Actor actor:actors.values()){actor.magnum.advance(amount*actor.ship.getMutableStats().getTimeMult().getModifiedValue());actor.facts.put("magnum",actor.magnum.status());}
        psychoframe.advance(amount);
        ventingMissileRepulsion.advance(amount);
        funnels.advance(amount);
        hud(); publish();
    }

    @Override public void processInput(float amount,List<InputEventAPI> inputs){
        if(!closed&&!duplicate&&psychoframe!=null)psychoframe.processHandInput(inputs);
        if(!closed&&!duplicate&&electronicWarfare!=null)electronicWarfare.processInputPreCoreControls(amount,inputs);
        if(!closed&&!duplicate&&engine!=null){Actor actor=actors.get(engine.getPlayerShip());if(actor!=null)actor.magnum.processInputPreCoreControls(inputs);}
    }

    private void scan() {
        List<ShipAPI> ships = engine.getShips(); int count = ships.size();
        if (count == 0) { cursor = 0; return; }
        int budget = Math.min(count, MAX_SCAN_CANDIDATES), start = Math.floorMod(cursor, count);
        for (int i = 0; i < budget; i++) {
            ShipAPI ship = ships.get((start + i) % count); scanned++;
            if (!live(ship) || !UnicornNtdStats.supports(ship) || actors.containsKey(ship)) continue;
            if (actors.size() >= MAX_ACTORS) { capacitySkips++; continue; }
            actors.put(ship, new Actor(ship));
        }
        cursor = (start + budget) % count;
    }
    private boolean live(ShipAPI ship) {
        return ship != null && ship.isAlive() && !ship.isHulk() && engine.isEntityInPlay(ship);
    }
    public static Moci_NTDSubsystem findNtd(ShipAPI ship) {
        List<MagicSubsystem> systems = MagicSubsystemsManager.getSubsystemsForShipCopy(ship);
        if (systems == null) return null;
        for (MagicSubsystem system : systems) if (system instanceof Moci_NTDSubsystem ntd) return ntd;
        return null;
    }
    private static void suppressWeapons(ShipAPI ship) {
        for (WeaponAPI weapon : ship.getAllWeapons()) if (!weapon.isDecorative()) {
            weapon.setForceNoFireOneFrame(true); weapon.stopFiring();
        }
    }
    /** Public native flux state transition; preserves a longer existing overload, bypasses no damage rules. */
    public static void forceOverloadAtLeast(ShipAPI ship, float seconds) {
        if (ship == null || !Float.isFinite(seconds) || seconds <= 0f) return;
        FluxTrackerAPI flux = ship.getFluxTracker();
        if (flux.isOverloaded() && flux.getOverloadTimeRemaining() >= seconds) return;
        flux.stopVenting();
        flux.beginOverloadWithTotalBaseDuration(seconds);
        // Native begin() refuses to extend an existing overload and applies duration modifiers.
        // The explicit scenario duration uses the documented progress/duration API instead.
        flux.setOverloadDuration(seconds); flux.setOverloadProgress(0f);
    }
    @Override public void renderInWorldCoords(ViewportAPI viewport) {
        if(!closed&&!duplicate&&psychoframe!=null)psychoframe.renderHands();
        if (!closed && !duplicate && electronicWarfare != null) electronicWarfare.renderWorldPose();
        if (!closed && !duplicate && ventingMissileRepulsion != null) ventingMissileRepulsion.renderWorld(viewport);
    }
    @Override public void renderInUICoords(ViewportAPI viewport){if(!closed&&!duplicate&&psychoframe!=null)psychoframe.renderHandHud();}
    private void hud(){
        ShipAPI player=engine.getPlayerShip();Actor actor=actors.get(player);if(actor==null)return;
        actor.magnum.present();
        MagicSubsystem.State state=actor.ntd==null?null:actor.ntd.getCurrentState();
        UnicornToggleNtdSubsystem toggle=actor.ntd instanceof UnicornToggleNtdSubsystem own?own:null;
        float fatigue=actor.backlash.remaining();String mode;
        if(toggle==null)mode="自有常驻NTD尚未安装";
        else if(fatigue>0)mode="反噬：过载 / 武器停摆 · "+String.format(Locale.ROOT,"%.1f秒",fatigue);
        else if(player.getFluxTracker().isOverloaded())mode="过载优先：NTD强制解除";
        else if(state==MagicSubsystem.State.IN)mode="正在展开 · 常驻开关";
        else if(state==MagicSubsystem.State.ACTIVE)mode=toggle.resonanceLocked()?"已开启 · 共振锁定，不能手动关闭":"已开启 · 再按子系统键关闭";
        else if(state==MagicSubsystem.State.OUT)mode="正在解除 · 结算本次反噬";
        else mode="按子系统键开启 · 无固定时限";
        engine.maintainStatusForPlayerShip(KEY+".mode","graphics/icons/hullsys/temporal_shell.png","NT-D状态",mode,fatigue>0);
        float uptime=toggle==null?0:toggle.onLocalSeconds();
        engine.maintainStatusForPlayerShip(KEY+".upkeep","graphics/icons/hullsys/temporal_shell.png","NT-D维持",
                String.format(Locale.ROOT,"本次开启 %.1f秒 · 20软幅能/秒",uptime),false);
        UnicornNtdBacklash.Estimate cost=UnicornNtdBacklash.estimate(player,uptime);
        Map<String,Object> last=actor.backlash.status();
        String penalty=fatigue>0?String.format(Locale.ROOT,"本次结构成本 %.0f · 过载/武器停摆 %.1f秒",((Number)last.get("lastHullCost")).floatValue(),((Number)last.get("lastLockLocalSeconds")).floatValue())
                :String.format(Locale.ROOT,"关闭预计：结构 -%.0f · 过载/武器停摆 %.1f秒",cost.hullCost(),cost.lockSeconds());
        engine.maintainStatusForPlayerShip(KEY+".cost","graphics/icons/hullsys/temporal_shell.png","NT-D累积代价",penalty,fatigue>0);
        String resonance=resonanceLiveMainCount>=2?"自动展开并锁定开关":"单机：允许手动关闭";
        if(player.getFluxTracker().isOverloaded())resonance="过载阻断重启，恢复后再判定";
        else if(fatigue>0)resonance="反噬未结束，共振重启等待";
        engine.maintainStatusForPlayerShip(KEY+".resonance","graphics/icons/hullsys/temporal_shell.png","精神感应共振 · "+resonanceLiveMainCount+"台",resonance,resonanceLiveMainCount>=2);
        boolean interferenceOn=fatigue<=0&&actor.ntd!=null&&actor.ntd.isAwakened()
                &&(state==MagicSubsystem.State.IN||state==MagicSubsystem.State.ACTIVE)&&actor.ntd.getCurrentEffectLevel()>=.8f&&!player.getFluxTracker().isOverloaded();
        engine.maintainStatusForPlayerShip(KEY+".interference","graphics/icons/hullsys/temporal_shell.png",
                "NTD：干扰 · "+(interferenceOn?"生效":"未生效"),interferenceOn?"极限机动 / 马格南火控 / 电子压制":"NT-D展开到位后自动生效",false);
    }
    private void publish() {
        facts.put("closed", closed); facts.put("frames", frames); facts.put("scanCandidates", scanned);
        facts.put("capacitySkips", capacitySkips); facts.put("maxActors", MAX_ACTORS);
        facts.put("fatigueStarts", fatigueStarts);facts.put("resonanceLiveMainCount",resonanceLiveMainCount);
        facts.put("actors", actors.values().stream().map(actor -> actor.facts).toList());
        if (electronicWarfare != null) facts.put("electronicWarfare", electronicWarfare.status());
        if (funnels != null) facts.put("funnelTargeting", funnels.status());
        if (weaponBindings != null) facts.put("weaponBindings", weaponBindings.status());
        if (psychoframe != null) facts.put("psychoframe", psychoframe.status());
        if (ventingMissileRepulsion != null) facts.put("ventingMissileRepulsion", ventingMissileRepulsion.status());
    }
    @Override public void close() {
        if (closed || duplicate) return; closed = true;
        Throwable failure = null;
        List<AutoCloseable> resources = new ArrayList<>();
        if (weaponBindings != null) resources.add(weaponBindings);
        if (funnels != null) resources.add(funnels);
        if (ventingMissileRepulsion != null) resources.add(ventingMissileRepulsion);
        if (psychoframe != null) resources.add(psychoframe);
        if (electronicWarfare != null) resources.add(electronicWarfare);
        if (interference != null) resources.add(interference);
        for (Actor actor : actors.values()) {resources.add(actor.magnum);resources.add(actor.stats);if(actor.ntd instanceof UnicornToggleNtdSubsystem own)resources.add(own);}
        for (AutoCloseable resource : resources) try { resource.close(); }
        catch (Throwable error) { if (error instanceof VirtualMachineError fatal) throw fatal; if (failure == null) failure = error; else failure.addSuppressed(error); }
        actors.clear();
        if (engine != null && engine.getCustomData().get(OWNER_KEY) == this) engine.getCustomData().remove(OWNER_KEY);
        data.scripts.combat.Moci_CombatBus.unregister(this);
        publish();
        if (failure != null) throw new IllegalStateException("Unicorn NTD cleanup failed; retire this battle worker", failure);
    }
}
