package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/** Native continuous rifle beam and bounded optical layers. No extra damaging entity is spawned. */
public final class BeamRifleChargeEffect implements EveryFrameWeaponEffectPluginWithAdvanceAfter,WeaponEffectPluginWithInit,BeamEffectPlugin,AutoCloseable {
    public static final String OPTICS_KEY="xi.arsenal.rifle.chargeOptics";
    private WeaponAPI bound;private CombatEngineAPI engine;private boolean rendered,closed;private float visualTime;
    private Map<String,Object> opticalFacts;private NativeRifleEmp nativeEmp;
    private final String damageId="xi.arsenal.rifle.charge."+UUID.randomUUID();private DamageAPI previous;
    private boolean loggedRenderFailure;
    /** 所有权契约：只有本地驾驶机体的蓄力枪需要预先禁火、等玩家输入驱动原生首亮；
     *  AI 载机的原生开火指令不归 xi 语义接管（否则会永久哑火）。 */
    @Override public void init(WeaponAPI weapon){if(bound!=null&&bound!=weapon)throw new IllegalStateException("Charge rifle callback shared between weapons");if(!BeamRifleModes.isCharge(weapon))throw new IllegalArgumentException("Owned charge rifle required");bound=weapon;weapon.ensureClonedSpec();for(var offsets:List.of(weapon.getSpec().getTurretFireOffsets(),weapon.getSpec().getHardpointFireOffsets(),weapon.getSpec().getHiddenFireOffsets()))for(int i=0;i<offsets.size();i++)offsets.set(i,new Vector2f(offsets.get(i)));if(BeamRifleModes.controlOwned(weapon.getShip()))weapon.setForceNoFireOneFrame(true);}
    @Override public void advance(float amount,CombatEngineAPI engine,WeaponAPI weapon){if(bound==null)init(weapon);if(closed||engine==null||engine.isPaused())return;this.engine=engine;visualTime+=Math.max(0,amount);
        // 所有权契约分两层：禁火/放行闸门只归本地驾驶机体；光学层两者都要装，
        // 否则 AI 舰的蓄力过程完全不可见（用户实测「AI 不蓄力就打激光」的视觉成因）。
        boolean owned=BeamRifleModes.controlOwned(weapon.getShip(),engine);
        if(owned)BeamRifleModes.beforeWeapon(weapon);
        if(!rendered){engine.addLayeredRenderingPlugin(new Visual());rendered=true;}}
    @Override public void advanceAfter(float amount,CombatEngineAPI engine,WeaponAPI weapon){if(!closed&&!engine.isPaused()){
        BeamRifleModes.afterWeapon(weapon);BeamRifleModes own=BeamRifleModes.instance(weapon.getShip());
        if(own!=null){if(nativeEmp==null)nativeEmp=new NativeRifleEmp();nativeEmp.advance(amount,engine,own.source(),weapon,own.status());}
    }}
    @Override public void advance(float amount,CombatEngineAPI engine,BeamAPI beam){if(closed||beam==null||beam.getWeapon()==null)return;DamageAPI actual=beam.getDamage();if(actual!=null){if(previous!=null&&previous!=actual)previous.getModifier().unmodify(damageId);float scale=BeamRifleModes.firingScale(beam.getWeapon());actual.getModifier().modifyMult(damageId,Math.max(1,Math.min(BeamRifleModes.MAX_FINAL_DAMAGE_SCALE,scale)));previous=actual;}
        BeamRiflePalette palette=palette(beam.getWeapon());beam.setWidth(BeamRifleModes.firingWidth(beam.getWeapon()));
        BeamRifleModes own=BeamRifleModes.instance(beam.getWeapon().getShip());
        float power=own==null?0:((Number)own.status().getOrDefault("overlimitPower",0f)).floatValue();
        beam.setCoreColor(RifleChargeOptics.mix(palette.inner,RifleChargeOptics.ELECTRIC_CORE,power*.45f));
        beam.setFringeColor(RifleChargeOptics.mix(palette.primary,RifleChargeOptics.ELECTRIC,power*.52f));
    }
    /** 枪口世界坐标：AI 舰没有可见枪身姿态，取不到就退回武器挂点、再退回舰体中心。 */
    private static Vector2f safeMuzzle(WeaponAPI source,WeaponAPI weapon){
        try{Vector2f muzzle=BeamRifleMount.visibleMuzzleWorld(source);if(muzzle!=null)return muzzle;}
        catch(RuntimeException|LinkageError ignored){}
        try{Vector2f at=weapon==null?null:weapon.getLocation();if(at!=null)return new Vector2f(at);}
        catch(RuntimeException ignored){}
        WeaponAPI fallback=source!=null?source:weapon;
        try{if(fallback!=null&&fallback.getShip()!=null&&fallback.getShip().getLocation()!=null)return new Vector2f(fallback.getShip().getLocation());}
        catch(RuntimeException ignored){}
        return new Vector2f();
    }
    /** 可见枪身长度的一半：AI 舰取不到贴图时按 0 处理，只是枪口光环少一点后坐偏移。 */
    private static float safeBack(WeaponAPI source){
        try{return BeamRifleMount.visibleGunSprite(source).getHeight()*.25f;}
        catch(RuntimeException|LinkageError ignored){return 0f;}
    }
    private static BeamRiflePalette palette(WeaponAPI weapon){return BeamRiflePalette.forWeapon(BeamRifleModes.PENELOPE_CHARGE.equals(weapon.getId())?BeamRiflePalette.PENELOPE_WEAPON:BeamRiflePalette.XI_WEAPON);}
    private final class Visual extends BaseCombatLayeredRenderingPlugin {
        @Override public EnumSet<CombatEngineLayers> getActiveLayers(){return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);}
        @Override public float getRenderRadius(){return bound==null?6000:Math.max(6000,Math.min(20000,bound.getRange()+256));}
        @Override public boolean isExpired(){return closed||bound==null||!bound.getShip().isAlive()||bound.getShip().wasRemoved()||Global.getCombatEngine()!=engine;}
        @Override public void advance(float amount){if(entity!=null&&bound!=null)entity.getLocation().set(bound.getLocation());}
        @Override public void render(CombatEngineLayers layer,ViewportAPI viewport){if(isExpired()||bound.getShip().isPhased())return;BeamRifleModes own=BeamRifleModes.instance(bound.getShip());if(own==null)return;try{renderBody(own,viewport);}catch(RuntimeException|LinkageError failure){failOnce(failure);}}
        /** 渲染主体。AI 舰从未跑过 reconcileNativePose，visibleMuzzleWorld/visibleGunSprite
         *  与 RifleFiringPose.gunTranslation 都可能抛；整体由 render() 兜住，只记一次日志。 */
        private void renderBody(BeamRifleModes own,ViewportAPI viewport){
            BeamRiflePalette palette=palette(bound);Map<String,Object> state=own.status();String phase=String.valueOf(state.get("phase"));
            boolean ai=Boolean.TRUE.equals(state.get("aiMode"));
            float held,charge,fired;boolean gathering;
            if(ai){
                // AI 的读数来自节奏机：蓄满 AI_CHARGE_SECONDS 才进 FIRE 段，之前都是「还在蓄」。
                held=((Number)state.getOrDefault("aiChargeSeconds",0f)).floatValue();
                charge=((Number)state.getOrDefault("aiChargeRatio",0f)).floatValue();
                boolean firing=Boolean.TRUE.equals(state.get("aiChargeFiring"));
                fired=firing?held:-1;
                gathering=charge>0f&&!firing;
            }else{
                held=((Number)state.getOrDefault("heldLocal",0f)).floatValue();charge=held/BeamRifleModes.MAX_HOLD;
                fired="FIRING".equals(phase)?((Number)state.getOrDefault("firedLocal",0f)).floatValue():-1;
                gathering="HOLDING".equals(phase)||"PRESENTING".equals(phase);
            }
            float overlimit=((Number)state.getOrDefault("overlimitPower",0f)).floatValue(),lockoutAge=((Number)state.getOrDefault("lockoutAge",-1f)).floatValue();
            java.awt.Color primary=RifleChargeOptics.mix(palette.primary,RifleChargeOptics.ELECTRIC,overlimit*.52f);
            java.awt.Color inner=RifleChargeOptics.mix(palette.inner,RifleChargeOptics.ELECTRIC_CORE,overlimit*.45f);
            List<PsychoOpticalRenderer.Segment> segments=new ArrayList<>();List<PsychoOpticalRenderer.Flare> flares=new ArrayList<>();Vector2f muzzle=safeMuzzle(own.source(),bound);
            float onset=RifleChargeOptics.cues(charge,fired).firstLight();int checked=0;
            for(BeamAPI ray:bound.getBeams())if(++checked<=RifleChargeOptics.MAX_RAYS&&ray.getBrightness()>.001f){Vector2f from=ray.getFrom(),to=ray.getTo();
                float ripple=1.16f+.035f*(float)Math.sin(visualTime*17);
                segments.add(new PsychoOpticalRenderer.Segment(from.x,from.y,to.x,to.y,ray.getWidth()*ripple,ray.getBrightness()*(.92f+.16f*onset),visualTime*8,primary,inner,true));
                flares.add(new PsychoOpticalRenderer.Flare(muzzle.x,muzzle.y,bound.getCurrAngle(),30+ray.getWidth()*.45f,ray.getBrightness()*.78f,primary,inner,false));
                if(ray.getDamageTarget()!=null)flares.add(new PsychoOpticalRenderer.Flare(to.x,to.y,visualTime*56,44+ray.getWidth()*.55f,ray.getBrightness()*.85f,primary,inner,true));}
            int ribbons=PsychoOpticalRenderer.render(segments,viewport),lights=PsychoOpticalRenderer.renderFlares(flares,viewport);
            double axis=Math.toRadians(own.source().getCurrAngle());float back=safeBack(own.source());
            Vector2f gunPart=new Vector2f(muzzle.x-(float)Math.cos(axis)*back,muzzle.y-(float)Math.sin(axis)*back);
            Vector2f armPart=null;
            try{for(WeaponAPI part:bound.getShip().getAllWeapons())if(part.getSlot()!=null&&"ARM_R".equals(part.getSlot().getId())){armPart=new Vector2f(part.getLocation());Vector2f offset=RifleFiringPose.gunTranslation(own.source());if(offset!=null)Vector2f.add(armPart,offset,armPart);break;}}
            catch(RuntimeException|LinkageError ignored){armPart=null;}
            int accents=RifleChargeOptics.render(muzzle,own.source().getCurrAngle(),visualTime,held,gathering,fired,overlimit,gunPart,armPart,lockoutAge,bound.getBeams(),palette,viewport);
            RifleChargeOptics.ChargeEnvelope volume=RifleChargeOptics.envelope(held);
            Map<String,Object> facts=new LinkedHashMap<>();facts.put("phase",phase);facts.put("nativeFirstLightAge",fired);facts.put("gathering",gathering);facts.put("firstLightPulse",onset);facts.put("drawnRibbons",ribbons);facts.put("drawnFlares",lights);facts.put("drawnAccents",accents);facts.put("overlimitPower",overlimit);facts.put("chargeCoreRadius",volume.coreRadius());facts.put("chargeHaloRadius",volume.haloRadius());facts.put("chargeRingRadius",volume.ringRadius());facts.put("chargeBlueMix",volume.blueMix());facts.put("weaponEmpPulse",RifleChargeOptics.empPulse(lockoutAge));facts.put("rightArmElectric",RifleChargeOptics.armElectric(lockoutAge));facts.put("weaponEmpAt",List.of(gunPart.x,gunPart.y));facts.put("rightArmEmpAt",armPart==null?List.of():List.of(armPart.x,armPart.y));facts.put("beamElectric",overlimit>0&&!bound.getBeams().isEmpty());facts.put("scope","Actual native-beam/muzzle/right-arm optical groups; no damage/audio/EMP entity emission");opticalFacts=Map.copyOf(facts);
            Map<String,Object> enriched=new LinkedHashMap<>(opticalFacts);enriched.put("nativeEmp",nativeEmp==null?Map.of():nativeEmp.snapshot());enriched.put("scope","Actual native beam, muzzle and right-arm presentation; native visual EMP entities, no added damage or audio");opticalFacts=Map.copyOf(enriched);
            bound.getShip().setCustomData(OPTICS_KEY,opticalFacts);
        }
    }
    /** 光学层失败只报一次：AI 舰缺姿态是常态，不是每帧都要喊的异常。 */
    private void failOnce(Throwable failure){
        if(loggedRenderFailure)return;
        loggedRenderFailure=true;
        try{Global.getLogger(BeamRifleChargeEffect.class).warn("[MOCI-RIFLE-AI] optical layer unavailable on "+describeShip()+" : "+failure,closed?null:failure);}
        catch(RuntimeException ignored){}
    }
    private String describeShip(){
        try{return bound==null||bound.getShip()==null?"(no weapon)":bound.getShip().getHullSpec().getHullId();}
        catch(RuntimeException ignored){return "(unknown)";}
    }
    @Override public void close(){if(closed)return;closed=true;if(nativeEmp!=null)nativeEmp.close();if(previous!=null)previous.getModifier().unmodify(damageId);if(bound!=null&&bound.getShip().getCustomData().get(OPTICS_KEY)==opticalFacts)bound.getShip().removeCustomData(OPTICS_KEY);}
}
