package xi.arsenal;

import data.scripts.weapons.Moci_MechTransformController;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.input.InputEventAPI;
import java.util.*;
import org.lwjgl.input.Keyboard;
import org.lwjgl.util.vector.Vector2f;

/** Sole owner of the Zeta feature clock. Original body, blade and transform callbacks remain engine-driven once. */
public final class ZetaEnhancement implements AutoCloseable, data.scripts.combat.Moci_CombatSink {
    public static final String KEY="xi.arsenal.zeta";
    private final Map<ShipAPI,Actor> actors=new IdentityHashMap<>();
    private final Map<String,Object> facts=new LinkedHashMap<>();
    private CombatEngineAPI engine;
    private ZetaBiosensorJam jam;
    private boolean closed,duplicate;
    private float scanClock;
    private int cursor;
    private long frames,budgetSkips;
    private final class Actor implements AutoCloseable {
        final ShipAPI ship;final ZetaOverdriveStats stats;final ZetaShieldRam ram;final ZetaRamWeaponGroup group;final ZetaRifleModes rifle;final ZetaWeaponGroups weaponGroups;
        final ZetaBeamDeflection deflection;final ZetaDefensiveMechanisms defenses;final ZetaTetherCombo tether;final ZetaSaberMotion saber;final ZetaCeaseFire ceaseFire;
        final ZetaOverdriveLifecycle overdrive;final ZetaOverdriveArmament loadout;
        Moci_MechTransformController transform;float effect;boolean wr,eligible;
        Actor(ShipAPI ship){
            this.ship=ship;List<AutoCloseable> created=new ArrayList<>();
            try{
                overdrive=track(created,new ZetaOverdriveLifecycle(engine,ship));loadout=track(created,new ZetaOverdriveArmament(ship));
                weaponGroups=track(created,ZetaWeaponGroups.ensure(ship));stats=track(created,new ZetaOverdriveStats(ship));ram=track(created,new ZetaShieldRam(engine,ship));
                group=track(created,new ZetaRamWeaponGroup(ship));rifle=track(created,new ZetaRifleModes(ship));
                deflection=track(created,new ZetaBeamDeflection(engine,ship));defenses=track(created,new ZetaDefensiveMechanisms(engine,ship,rifle));
                defenses.setOpticalDeflectionGuard(deflection::interceptDamage);
                // The native left-saber callback owns its sole motion clock.
                // The actor only owns construction, diagnostics and teardown.
                saber=ZetaSaberMotion.supports(ship)?track(created,new ZetaSaberMotion(engine,ship)):null;
                // Tether can request the already-owned native saber for its optional close-range finish.
                tether=ZetaTetherCombo.supports(ship)?track(created,new ZetaTetherCombo(engine,ship,rifle)):null;
                ceaseFire=track(created,new ZetaCeaseFire(engine,ship,()->rifle.cancelCharge("玩家停火")));findTransform();
            }catch(RuntimeException|Error failure){Collections.reverse(created);for(AutoCloseable resource:created)try{resource.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
        void findTransform(){transform=null;for(WeaponAPI weapon:ship.getAllWeapons())if(weapon.getSlot()!=null&&"BACKPACK".equals(weapon.getSlot().getId())&&weapon.getEffectPlugin() instanceof Moci_MechTransformController own){transform=own;break;}}
        void advance(float dt){if(transform==null)findTransform();wr=transform!=null&&transform.waveRiderReady();
            float local=ship.getMutableStats().getTimeMult().getModifiedValue();if(!Float.isFinite(local)||local<0)throw new IllegalStateException("Invalid Zeta local time");
            overdrive.advance(dt*local);loadout.advance(overdrive.active());
            ShipSystemAPI system=ship.getSystem();effect=!overdrive.stunned()&&system!=null&&ZetaContent.SYSTEM.equals(system.getId())?clamp(system.getEffectLevel()):0;
            eligible=wr&&effect>=.8f&&!ship.getFluxTracker().isOverloaded()&&!ship.getFluxTracker().isVenting();
            weaponGroups.advance(wr,eligible);stats.apply(effect,wr);
            if(!Float.isFinite(local)||local<0)throw new IllegalStateException("Invalid Zeta local time");
            ceaseFire.advance(dt*local);if(tether!=null)tether.advance(dt*local);
            rifle.advance(dt*local,wr);
            deflection.advance(dt*local,List.of());defenses.advance(dt*local,wr,effect);overdrive.afterEffects();
            ram.advance(dt*local,eligible);group.advance(eligible,ram.active());
            // The actor can update BODY orbit after native weapon callbacks.
            // Reconcile the physical shield once against that same final pose.
            ZetaShieldCoverEffect.reconcile(ship);
        }
        Map<String,Object> state(){Map<String,Object> row=new LinkedHashMap<>();row.put("shipId",Objects.toString(ship.getId(),""));row.put("hullId",ship.getHullSpec().getHullId());
            row.put("effectLevel",effect);row.put("systemState",ship.getSystem()==null?"MISSING":ship.getSystem().getState().name());row.put("waveRider",wr);row.put("ramEligible",eligible);
            row.put("overdrive",overdrive.status());row.put("overdriveLoadout",loadout.status());
            row.put("transform",transform==null?"missing-owned-transform":transform.state());row.put("stats",stats.status());row.put("ram",ram.status());row.put("ramGroup",group.status());row.put("rifle",rifle.status());row.put("weaponGroups",weaponGroups.status());row.put("deflection",deflection.status());row.put("defenses",defenses.status());row.put("tether",tether==null?Map.of("available",false,"reason","当前装配缺少钢索原生武装"):tether.status());row.put("saber",saber==null?Map.of("available",false):saber.status());row.put("ceaseFire",ceaseFire.status());return Collections.unmodifiableMap(row);}
        @Override public void close(){
            Throwable failure=null;
            for(AutoCloseable resource:Arrays.asList(tether,saber,ceaseFire,defenses,deflection,group,ram,rifle,stats,loadout,overdrive,transform,weaponGroups))if(resource!=null)try{resource.close();}
                catch(Throwable error){if(error instanceof VirtualMachineError fatal)throw fatal;if(failure==null)failure=error;else failure.addSuppressed(error);}
            if(failure!=null)throw new IllegalStateException("Zeta actor resources did not all close",failure);
        }
    }
    /** Z 系功能时钟的宿主：由 zeta 武器每帧惰性安装，不再是全局插件。 */
    public static ZetaEnhancement ensure(CombatEngineAPI engine){
        if (Global.getCurrentState() != com.fs.starfarer.api.GameState.COMBAT) return null;
        if(engine==null)return null;Object found=engine.getCustomData().get(KEY+".owner");
        if(found instanceof ZetaEnhancement own&&!own.closed)return own;
        ZetaEnhancement created=new ZetaEnhancement();created.start(engine);
        return created.closed||created.duplicate?null:created;
    }
    void start(CombatEngineAPI engine){
        this.engine=Objects.requireNonNull(engine);if(engine.getCustomData().containsKey(KEY+".owner")){duplicate=true;return;}
        engine.getCustomData().put(KEY+".owner",this);engine.getCustomData().put(KEY,Collections.unmodifiableMap(facts));jam=new ZetaBiosensorJam(engine);OwnBattleResources.register(engine,this);publish();
        data.scripts.combat.Moci_CombatBus.register(this);
    }
    public static ZetaEnhancement instance(CombatEngineAPI engine){Object found=engine==null?null:engine.getCustomData().get(KEY+".owner");return found instanceof ZetaEnhancement own&&!own.closed?own:null;}
    public Moci_MechTransformController.Request requestTransform(ShipAPI source){
        Actor actor=actors.get(source);if(actor==null||actor.transform==null||!canPilot(source))return new Moci_MechTransformController.Request(false,"需要可操作的完整Z高达");
        return actor.transform.requestTransform();
    }
    public ZetaBiosensorJam.Result requestJam(ShipAPI source){return jam==null?new ZetaBiosensorJam.Result(false,"尚未初始化",0):jam.requestActivation(source);}
    public ZetaShieldRam.StartResult requestRam(ShipAPI source,Vector2f point){
        Actor actor=actors.get(source);if(actor==null||!canPilot(source))return new ZetaShieldRam.StartResult(false,"需要本机操作");
        ShipSystemAPI system=source.getSystem();
        if(!actor.eligible||actor.transform==null||!actor.transform.waveRiderReady()||system==null||!ZetaContent.SYSTEM.equals(system.getId())
                ||system.getEffectLevel()<.8f||source.getFluxTracker().isOverloaded()||source.getFluxTracker().isVenting())
            return new ZetaShieldRam.StartResult(false,"仅生物传感器过载与完整冲浪者形态可用");
        return actor.ram.start(point);
    }
    private boolean canPilot(ShipAPI ship){return ship!=null&&ship==engine.getPlayerShip()&&live(ship)&&!engine.isPaused()&&!ship.controlsLocked()
            &&!engine.isUIShowingDialog()&&(engine.getCombatUI()==null||!engine.getCombatUI().isShowingCommandUI()&&!engine.getCombatUI().isShowingDeploymentDialog());}
    @Override public void advance(float dt,List<InputEventAPI> events){
        if(closed||duplicate)return;if(Global.getCombatEngine()!=engine||engine.isCombatOver()){close();return;}
        if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid Zeta frame amount");if(engine.isPaused()){hud();return;}
        frames++;scanClock-=dt;if(scanClock<=0){scanClock=.1f;scan();}
        List<ShipAPI> active=new ArrayList<>();
        for(Iterator<Actor> it=actors.values().iterator();it.hasNext();){Actor actor=it.next();if(!live(actor.ship)){actor.close();it.remove();continue;}
            actor.advance(dt);if(actor.effect>=.8f)active.add(actor.ship);}
        processRifleInput(events);processTransformInput(events);
        jam.advance(active,dt,events);hud();publish();
    }
    /** X is the requested transform control; intercept it before native flight controls can consume it. */
    @Override public void processInput(float dt,List<InputEventAPI> events){
        if(closed||duplicate||engine==null)return;
        // Key releases remain meaningful while a pause/dialog owns input: a held
        // E-cap or charge must not resume after its physical key was released.
        processReleasedInput(events);
        if(engine.isPaused())return;
        processRifleInput(events);processTransformInput(events);
    }
    private void processReleasedInput(List<InputEventAPI> events){
        if(events==null)return;Actor actor=actors.get(engine.getPlayerShip());if(actor==null)return;
        List<InputEventAPI> released=events.stream().filter(Objects::nonNull).filter(e->!e.isConsumed()&&(e.isKeyUpEvent()||e.isControlUpEvent("SHIP_FIRE"))).toList();
        actor.defenses.processInput(released);actor.rifle.processInput(released);
    }
    private void processRifleInput(List<InputEventAPI> events){ZetaTetherEntryProbe.observeInput(engine,events);Actor actor=actors.get(engine.getPlayerShip());if(actor!=null&&canPilot(actor.ship)){actor.ceaseFire.processInput(events);actor.deflection.processInput(events);actor.defenses.processInput(events);actor.rifle.processInput(events);}}
    private void processTransformInput(List<InputEventAPI> events){
        if(events!=null)for(InputEventAPI event:events){if(event==null||event.isConsumed()||!event.isKeyboardEvent()||!event.isKeyDownEvent()||event.isRepeat())continue;
            if(event.getEventValue()==Keyboard.KEY_X&&actors.containsKey(engine.getPlayerShip())&&canPilot(engine.getPlayerShip())){
                requestTransform(engine.getPlayerShip());event.consume();}}
    }
    private void scan(){List<ShipAPI> ships=engine.getShips();int size=ships.size(),limit=Math.min(size,512);
        for(int n=0;n<limit;n++){ShipAPI ship=ships.get((cursor+n)%Math.max(1,size));if(!live(ship)||!ZetaOverdriveStats.supports(ship)||actors.containsKey(ship))continue;
            if(actors.size()>=128){budgetSkips++;continue;}actors.put(ship,new Actor(ship));}
        if(size>limit)budgetSkips+=size-limit;cursor=size==0?0:(cursor+limit)%size;
    }
    private boolean live(ShipAPI ship){return ship!=null&&ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&engine.isEntityInPlay(ship);}
    private void hud(){Actor player=actors.get(engine.getPlayerShip());if(player==null||!engine.isUIShowingHUD())return;
        String form=player.wr?"冲浪者":"MS";String text="X 变形 · "+form;
        if(!player.wr){Map<String,Object> guns=player.rifle.status();text+=player.overdrive.active()?" · 超载步枪"+guns.get("burstGroupNumber")+" / 高速榴弹"+guns.get("wristGroupNumber")+" · 弹开见武器组":" · 三连"+guns.get("burstGroupNumber")+" / 狙击"+guns.get("sniperGroupNumber")+" / 腕榴弹"+guns.get("wristGroupNumber");}
        if(player.eligible){Object number=player.group.status().get("groupNumber");text+=" · 第"+number+"组：机首冲击";}
        engine.maintainStatusForPlayerShip(KEY,"graphics/icons/hullsys/maneuvering_jets.png","Z高达 · "+form,text,false);
    }
    @Override public void renderInWorldCoords(ViewportAPI viewport){if(closed||duplicate||engine==null)return;for(Actor actor:actors.values()){actor.stats.renderWorld(viewport);actor.ram.renderWorld(viewport);actor.deflection.renderWorld(viewport);if(actor.tether!=null)actor.tether.render(viewport);}}
    private void publish(){facts.put("schema",1);facts.put("closed",closed);facts.put("frames",frames);facts.put("budgetSkips",budgetSkips);facts.put("actors",actors.values().stream().map(Actor::state).toList());if(jam!=null)facts.put("jam",jam.status());}
    /** The full weapon inventory is an on-demand diagnostic, not rebuilt every combat frame. */
    public Map<String,Object> status(){publish();Map<String,Object> snapshot=new LinkedHashMap<>(facts);snapshot.put("tetherEntry",ZetaTetherEntryProbe.snapshot(engine));return Collections.unmodifiableMap(snapshot);}
    private static float clamp(float value){return Float.isFinite(value)?Math.max(0,Math.min(1,value)):0;}
    private static <T extends AutoCloseable>T track(List<AutoCloseable> resources,T resource){resources.add(resource);return resource;}
    @Override public void close(){if(closed||duplicate)return;closed=true;Throwable failure=null;
        for(Actor actor:actors.values())try{actor.close();}catch(Throwable error){if(error instanceof VirtualMachineError fatal)throw fatal;if(failure==null)failure=error;else failure.addSuppressed(error);}
        actors.clear();try{if(jam!=null)jam.close();}catch(Throwable error){if(error instanceof VirtualMachineError fatal)throw fatal;if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(engine!=null&&engine.getCustomData().get(KEY+".owner")==this)engine.getCustomData().remove(KEY+".owner");data.scripts.combat.Moci_CombatBus.unregister(this);publish();
        if(failure!=null)throw new IllegalStateException("Zeta cleanup failed; retire battle worker",failure);
    }
}
