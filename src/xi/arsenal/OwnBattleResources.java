/* 本文件来自「设计交付包 · 02_原作者整合零件版/合并载荷」的 97 行超集版本，
 * 与合并版 28 行精简版同名系统；区别仅在于它多出类型化 owned hub 与 closeOwned(CombatEngineAPI)，
 * 也就是「谁在战斗结束时真正关闭这些资源」。甲方案（只并合并版）需要这个清理执行者，
 * 否则 xi 包注册的 13 处资源永远不会被关闭（音效 handle、渲染器、动作层对象逐场泄漏）。
 * 引号内容与逻辑未作改动。 */
package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.*;

/**
 * Existing shared host bridge plus an export-only, typed registry of this package's resources.
 * NativeBattleLifecycle may close the typed owned hub without closing another Mod's shared entries.
 */
public final class OwnBattleResources {
    public static final String KEY="host.lifecycle.resources";
    public static final String OWNED_KEY="xi.arsenal.lifecycle.ownedResources";
    public static final int MAX_RESOURCES=128;
    private OwnBattleResources() { }
    public record CloseFailure(String resource,String error) { }
    public record CloseReport(int attempted,int closed,List<CloseFailure> failures) {
        public CloseReport{failures=List.copyOf(failures);}
    }
    private static final class OwnedHub {
        final Thread owner=Thread.currentThread();
        final List<AutoCloseable> resources=new ArrayList<>();
        final Set<AutoCloseable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<List<AutoCloseable>> sharedLists=Collections.newSetFromMap(new IdentityHashMap<>());
        final List<CloseFailure> failures=new ArrayList<>();
        List<AutoCloseable> createdShared;
        int attempted,closed;
        boolean finished;
        void requireOwner(){if(owner!=Thread.currentThread())throw new IllegalStateException("Owned battle cleanup requires its registering combat thread");}
        CloseReport report(){return new CloseReport(attempted,closed,failures);}
    }
    @SuppressWarnings("unchecked")
    public static void register(CombatEngineAPI engine,AutoCloseable resource) {
        Objects.requireNonNull(engine,"engine");Objects.requireNonNull(resource,"resource");
        if(!resource.getClass().getName().startsWith("xi.arsenal."))throw new IllegalArgumentException("Only this package's explicit resources belong in its owned hub");
        Object own=engine.getCustomData().get(OWNED_KEY);
        if(own!=null&&!(own instanceof OwnedHub))throw new IllegalStateException("Owned resource hub is occupied by an unknown owner");
        OwnedHub hub=own==null?new OwnedHub():(OwnedHub)own;hub.requireOwner();
        if(hub.seen.contains(resource))return;
        if(hub.finished){
            // A final native weapon callback can initialize after the end observer. Close that
            // late resource on this same thread rather than leaving a new live renderer behind.
            if(hub.seen.size()>=MAX_RESOURCES)throw new IllegalStateException("Owned late-resource bound exceeded");
            hub.seen.add(resource);closeOne(hub,resource);return;
        }
        if(hub.resources.size()>=MAX_RESOURCES)throw new IllegalStateException("Owned battle resource registry is full");
        Object existing=engine.getCustomData().get(KEY);
        List<AutoCloseable> resources;
        boolean created=existing==null;
        if(created){resources=new ArrayList<>();}
        else if(existing instanceof List<?> list){
            if(list.size()>MAX_RESOURCES)throw new IllegalStateException("Battle resource list exceeds ownership bound");
            for(Object item:list)if(!(item instanceof AutoCloseable))throw new IllegalStateException("Invalid battle resource owner");
            resources=(List<AutoCloseable>)list;
        }else throw new IllegalStateException("Battle resource registry is not a List<AutoCloseable>");
        boolean present=false;for(AutoCloseable item:resources)if(item==resource){present=true;break;}
        if(!present){if(resources.size()>=MAX_RESOURCES)throw new IllegalStateException("Battle resource registry is full");resources.add(resource);}
        if(created){engine.getCustomData().put(KEY,resources);hub.createdShared=resources;}
        hub.sharedLists.add(resources);hub.resources.add(resource);hub.seen.add(resource);
        if(own==null)engine.getCustomData().put(OWNED_KEY,hub);
    }
    /** Close only the typed hub we created, in reverse registration order; keep failures and continue. */
    public static CloseReport closeOwned(CombatEngineAPI engine){
        Objects.requireNonNull(engine);Object raw=engine.getCustomData().get(OWNED_KEY);
        if(raw==null)return new CloseReport(0,0,List.of());
        if(!(raw instanceof OwnedHub hub))return new CloseReport(0,0,List.of(new CloseFailure("registry","Unknown owned hub left untouched")));
        hub.requireOwner();if(hub.finished)return hub.report();hub.finished=true;
        List<AutoCloseable> copy=new ArrayList<>(hub.resources);hub.resources.clear();
        for(int i=copy.size()-1;i>=0;i--)closeOne(hub,copy.get(i));
        for(List<AutoCloseable> shared:hub.sharedLists)try{shared.removeIf(hub.seen::contains);}
        catch(RuntimeException failure){hub.failures.add(new CloseFailure("shared-registry-release",failure.toString()));}
        hub.sharedLists.clear();
        if(engine.getCustomData().get(KEY)==hub.createdShared&&hub.createdShared!=null&&hub.createdShared.isEmpty())engine.getCustomData().remove(KEY);
        return hub.report();
    }
    private static void closeOne(OwnedHub hub,AutoCloseable resource){
        hub.attempted++;
        try{resource.close();hub.closed++;}
        catch(Throwable failure){if(failure instanceof VirtualMachineError fatal)throw fatal;hub.failures.add(new CloseFailure(resource.getClass().getName(),failure.toString()));}
    }
    public static Map<String,Object> ownedStatus(CombatEngineAPI engine){
        Object raw=Objects.requireNonNull(engine).getCustomData().get(OWNED_KEY);
        if(!(raw instanceof OwnedHub hub))return Map.of("schema",1,"present",raw!=null,"foreign",raw!=null,"closed",false,"registered",0);
        hub.requireOwner();return Map.of("schema",1,"present",true,"foreign",false,"closed",hub.finished,"registered",hub.resources.size(),
                "attempted",hub.attempted,"closedCount",hub.closed,"failures",List.copyOf(hub.failures));
    }
}
