package xi.arsenal;

import data.scripts.weapons.Moci_MechTransformController;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.entities.Ship;
import java.util.*;
import java.util.function.Consumer;

/**
 * Instance-only native render-layer leases. No replacement sprite or custom renderer is created.
 * Verified 0.98 native bridge: CombatEngine.getRenderer().recompile(Ship) refreshes cached layer lists;
 * ShipAPI.setLayer alone only edits its EnumSet. STATION_WEAPONS_LAYER skips native hull sprites.
 */
public final class AdaptiveShieldVisualLayer implements AutoCloseable {
    public static final String KEY="xi.arsenal.adaptiveShieldVisualLayer";
    public static final CombatEngineLayers FOREGROUND=CombatEngineLayers.ABOVE_SHIPS_LAYER;
    private static final String OWNER=KEY+".owner",TOKEN=KEY+".lease";
    private static final int MAX_SHIELDS=512;
    private final Thread owner=Thread.currentThread();
    private final CombatEngineAPI engine;
    private final Consumer<ShipAPI> recompile;
    private final Map<ShipAPI,Lease> leases=new IdentityHashMap<>();
    private final Set<ShipAPI> yielded=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String,Object> facts=new LinkedHashMap<>();
    private long acquired,restored,foreign;private boolean closed;

    AdaptiveShieldVisualLayer(CombatEngineAPI engine,Consumer<ShipAPI> recompile) {
        this.engine=Objects.requireNonNull(engine);this.recompile=Objects.requireNonNull(recompile);
        facts.put("schema",1);facts.put("scope","Declared physical SHIELD_L native layer leases and Zeta folded-form layer only; no sprite copying or physics mutation");
        facts.put("nativeBridge","0.98 CombatEngine.getRenderer().recompile(Ship), exact verified methods; no reflection");
        facts.put("foreground",FOREGROUND.name());
    }
    public static void ensure(CombatEngineAPI engine,ShipAPI parent,ShipAPI shield) {
        if (Global.getCurrentState() != com.fs.starfarer.api.GameState.COMBAT) return;
        Object existing=engine.getCustomData().get(OWNER);
        AdaptiveShieldVisualLayer visual;
        if(existing==null) {
            if(!(engine instanceof CombatEngine nativeEngine))throw new IllegalStateException("Adaptive shield layer requires the verified native CombatEngine renderer bridge");
            visual=new AdaptiveShieldVisualLayer(engine,ship->{
                if(!(ship instanceof Ship nativeShip))throw new IllegalStateException("Adaptive shield layer requires a native Ship instance");
                nativeEngine.getRenderer().recompile(nativeShip);
            });
            engine.getCustomData().put(OWNER,visual);engine.getCustomData().put(KEY,Collections.unmodifiableMap(visual.facts));
            // 无引擎外资源：close() 只把 SHIELD_L 的 layer 还原并 recompile 渲染缓存（全部是引擎内对象），
            // engine 销毁即随之消失；注册到中央表只是让插件在战斗结束时多 close 一次，不产生任何实际释放。
            // 唯一登记处就是引擎自己的 customData（OWNER/KEY），由 releaseParent 与引擎生命周期闭环。
        }else if(existing instanceof AdaptiveShieldVisualLayer found)visual=found;
        else throw new IllegalStateException("Another owner occupies the adaptive shield layer registry");
        visual.apply(parent,shield);
    }
    public static void releaseParent(CombatEngineAPI engine,ShipAPI parent) {
        if(engine==null||parent==null)return;Object found=engine.getCustomData().get(OWNER);
        if(found instanceof AdaptiveShieldVisualLayer visual)visual.release(parent);
    }
    void apply(ShipAPI parent,ShipAPI shield) {
        requireOwner();if(closed)return;purge();
        if(!valid(parent,shield)||shield.isPhased()||parent.isPhased()||yielded.contains(shield)){publish();return;}
        Lease current=leases.get(shield);
        if(current!=null){publish();return;}
        if(leases.size()+yielded.size()>=MAX_SHIELDS)throw new IllegalStateException("Physical shield visual lease capacity exceeded");
        if(shield.getCustomData().containsKey(TOKEN)){yielded.add(shield);foreign++;publish();return;}
        // Native station modules can ALREADY start in the foreground. The Zeta's
        // blank WR cover cannot hide that hull sprite, so fold only this instance
        // onto the native weapons-only layer; retain its exact prior MS layer.
        CombatEngineLayers target=foldedForTransform(parent)?CombatEngineLayers.STATION_WEAPONS_LAYER:FOREGROUND;
        if(shield.getLayer()==target||shield.getActiveLayers().contains(target)){publish();return;}
        Lease lease=new Lease(parent,shield,shield.getLayer(),target);
        // BaseEntity.getCustomData() may return a detached empty map before its
        // first setCustomData call. Persist ownership through the native setter.
        shield.setCustomData(TOKEN,lease);
        try{shield.setLayer(target);recompile.accept(shield);leases.put(shield,lease);acquired++;}
        catch(RuntimeException failure){restore(lease);throw failure;}
        publish();
    }
    private boolean valid(ShipAPI parent,ShipAPI shield) {
        return parent!=null&&shield!=null&&parent.isAlive()&&!parent.isHulk()&&shield.isAlive()&&!shield.isHulk()
                &&shield.getParentStation()==parent&&shield.getStationSlot()!=null&&"SHIELD_L".equals(shield.getStationSlot().getId())
                &&engine.isEntityInPlay(parent)&&engine.isEntityInPlay(shield);
    }
    /** Moci's WR frame owns the folded shield image and its original module placement.
     * A foreground lease must not reveal the physical module behind its blank cover. */
    static boolean foldedForTransform(ShipAPI parent) {
        if(parent==null||!ZetaMobility.supports(parent))return false;
        Map<String,Object> data=parent.getCustomData();
        if(Boolean.TRUE.equals(data.get(Moci_MechTransformController.CUSTOM_PARTS_HIDDEN))||Boolean.TRUE.equals(data.get(Moci_MechTransformController.CUSTOM_TRANSFORMED)))return true;
        Object state=data.get(Moci_MechTransformController.CUSTOM_TRANSFORM_STATE);
        return state!=null&&!"NORMAL".equals(String.valueOf(state));
    }
    private void purge() {
        for(Lease lease:List.copyOf(leases.values())) {
            boolean owns=lease.shield.getCustomData().get(TOKEN)==lease&&lease.shield.getLayer()==lease.target;
            if(!owns){leases.remove(lease.shield);if(lease.shield.getCustomData().get(TOKEN)==lease)lease.shield.removeCustomData(TOKEN);yielded.add(lease.shield);foreign++;}
            else if(!valid(lease.parent,lease.shield)||lease.shield.isPhased()||lease.parent.isPhased()
                    ||lease.target!=(foldedForTransform(lease.parent)?CombatEngineLayers.STATION_WEAPONS_LAYER:FOREGROUND)){leases.remove(lease.shield);restore(lease);}
        }
        yielded.removeIf(ship->!engine.isEntityInPlay(ship));
    }
    private void release(ShipAPI parent) {
        requireOwner();if(closed)return;
        for(Lease lease:List.copyOf(leases.values()))if(lease.parent==parent){leases.remove(lease.shield);restore(lease);}
        publish();
    }
    private void restore(Lease lease) {
        ShipAPI shield=lease.shield;
        if(shield.getCustomData().get(TOKEN)!=lease)return;
        shield.removeCustomData(TOKEN);
        if(shield.getLayer()!=lease.target){foreign++;return;}
        boolean wasActive=shield.getActiveLayers().contains(lease.target);
        shield.setLayer(lease.previous);
        // Native phasing can replace the active EnumSet independently of its main layer field.
        if(!wasActive)shield.getActiveLayers().remove(lease.previous);
        if(engine.isEntityInPlay(shield))recompile.accept(shield);
        restored++;
    }
    private void publish() {
        facts.put("closed",closed);facts.put("activeLeases",leases.size());facts.put("acquired",acquired);facts.put("restored",restored);facts.put("foreignChanges",foreign);
        facts.put("leases",leases.values().stream().map(lease->Map.of("parent",Objects.toString(lease.parent.getId(),""),"shield",Objects.toString(lease.shield.getId(),""),
                "originalLayer",lease.previous.name(),"leasedLayer",lease.target.name(),"currentLayer",lease.shield.getLayer().name(),"activeLayers",lease.shield.getActiveLayers().stream().map(Enum::name).toList())).toList());
    }
    Map<String,Object> snapshot(){requireOwner();return Collections.unmodifiableMap(new LinkedHashMap<>(facts));}
    @Override public void close() {
        requireOwner();if(closed)return;closed=true;
        for(Lease lease:List.copyOf(leases.values()))restore(lease);
        leases.clear();yielded.clear();publish();
        if(engine.getCustomData().get(OWNER)==this)engine.getCustomData().remove(OWNER);
    }
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Shield visual leases belong to the combat thread");}
    private record Lease(ShipAPI parent,ShipAPI shield,CombatEngineLayers previous,CombatEngineLayers target) { }
}
