package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/** Read-only entry gate for the DSK execution presentation.
 * Scope and native firing/range remain the calling rifle controller's responsibility.
 * Trigger policy: overloaded alone is enough; otherwise the armor-scaled hull threshold AND
 * "no active defense" must both hold (thick armor resists the hull line; thin shieldless hulls
 * fall in once their guard is down). This is a trigger, never a damage forecast.
 */
public final class RifleExecutionGate {
    /** 装甲数据不可读时的兜底处决线（沿用旧值 25%）。 */
    public static final float LOW_HULL_FRACTION=.25f;
    /** 目标装甲低于自机：结构不高于 75% 即可处决。 */
    public static final float LOW_HULL_BELOW_ARMOR=.75f;
    /** 目标装甲与自机相等：结构不高于 50% 即可处决。 */
    public static final float LOW_HULL_EQUAL_ARMOR=.50f;
    /** 目标装甲达到自机两倍及以上：线压到此下限（10%）。 */
    public static final float LOW_HULL_HEAVY_ARMOR=.10f;
    private static final int MAX_MODULES=128;
    private static final String SHIELD_SLOT="SHIELD_L";
    private RifleExecutionGate() { }

    public record Snapshot(boolean allowed,String reason,boolean overloaded,boolean lowHull,
                           boolean undefended,float hullFraction,float hullThreshold,String lockEvidence) {
        public Map<String,Object> status() {
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("allowed",allowed);result.put("reason",reason);
            result.put("overloaded",overloaded);result.put("lowHull",lowHull);
            result.put("undefended",undefended);result.put("hullFraction",hullFraction);
            result.put("hullThreshold",hullThreshold);result.put("lockEvidence",lockEvidence);
            result.put("policy","OVERLOAD_OR_(HULL_THRESHOLD_AND_NO_ACTIVE_DEFENSE); not a lethal-damage prediction");
            return Collections.unmodifiableMap(result);
        }
    }

    private record Entry(boolean allowed,String reason,String evidence) { }

    public static Snapshot evaluate(CombatEngineAPI engine,ShipAPI source,ShipAPI target) {
        Entry entry=entry(engine,source,target);
        if(!entry.allowed)return new Snapshot(false,entry.reason,false,false,false,-1f,-1f,entry.evidence);
        float hp=target.getHitpoints(),max=target.getMaxHitpoints();
        if(!Float.isFinite(hp)||!Float.isFinite(max)||hp<=0||max<=0)
            return new Snapshot(false,"目标结构数据不可用",false,false,false,-1f,-1f,entry.evidence);
        float fraction=Math.max(0,Math.min(1,hp/max));
        boolean overloaded=target.getFluxTracker()!=null&&target.getFluxTracker().isOverloaded();
        float threshold=hullThreshold(source,target);
        boolean low=fraction<=threshold;
        boolean undefended=!nativeShieldCovers(source,target)&&!physicalShieldPresent(engine,target);
        /* 超载单独成立；否则必须"结构达到处决线"与"无有效防护"同时成立。 */
        boolean allowed=overloaded||(low&&undefended);
        String reason=overloaded?"锁定目标超载，可处决"
                :low&&undefended?String.format(Locale.ROOT,"锁定目标结构不高于%.0f%%（装甲比%.2f倍）且无有效防护，可处决",threshold*100f,armorRatio(source,target))
                :low?String.format(Locale.ROOT,"锁定目标结构已低于%.0f%%处决线，但仍有有效防护",threshold*100f)
                :undefended?String.format(Locale.ROOT,"锁定目标无有效防护，但结构仍高于%.0f%%处决线",threshold*100f)
                :String.format(Locale.ROOT,"目标未超载，且结构高于%.0f%%处决线、仍有有效防护",threshold*100f);
        return new Snapshot(allowed,reason,overloaded,low,undefended,fraction,threshold,entry.evidence);
    }

    /** Once committed, retain only target identity and legality. A recovered weak state
     * does not revoke the already-triggered presentation or create another shot. */
    public static boolean stillLocked(CombatEngineAPI engine,ShipAPI source,ShipAPI target) {
        return entry(engine,source,target).allowed;
    }
    public static boolean entryValid(CombatEngineAPI engine,ShipAPI source,ShipAPI target) {
        return stillLocked(engine,source,target);
    }

    private static Entry entry(CombatEngineAPI engine,ShipAPI source,ShipAPI target) {
        if(engine==null||source==null||target==null)return new Entry(false,"需要已锁定的敌方目标","missing-target");
        if(engine.isCombatOver()||!source.isAlive()||source.isHulk()||source.wasRemoved()||!engine.isEntityInPlay(source))
            return new Entry(false,"射手机体或战场不可用","source-unavailable");
        if(source.getShipTarget()!=target)return new Entry(false,"目标不是当前原生锁定焦点","native-focus-mismatch");
        if(target.wasRemoved()||!ContentTargetLocks.legal(engine,source,target))
            return new Entry(false,"锁定目标已失效、转为友军或处于相位状态","illegal-target");
        Object raw=engine.getCustomData().get(ContentTargetLocks.KEY);
        if(raw==null)return new Entry(true,"原生焦点锁定有效","native-focus");
        if(!(raw instanceof Map<?,?> bridge)||!(bridge.get("schema") instanceof Number schema)||schema.intValue()!=1
                ||!(bridge.get("targets") instanceof Map<?,?> pools))
            return new Entry(false,"共享锁定桥格式不可用","invalid-lock-bridge");
        if(!pools.containsKey(source))return new Entry(true,"原生焦点锁定有效","native-focus");
        Object pool=pools.get(source);
        if(!(pool instanceof List<?> targets))return new Entry(false,"本机共享锁定池格式不可用","invalid-source-pool");
        if(targets.isEmpty())return new Entry(false,"共享锁定已明确清空","managed-empty");
        int checked=0;
        for(Object candidate:targets){if(checked++>=ContentTargetLocks.MAX_TARGETS)break;
            if(candidate==target)return new Entry(true,"原生焦点与共享锁定身份一致","native-focus+managed-pool");}
        return new Entry(false,"当前焦点已不在本机共享锁定池","managed-target-missing");
    }

    /** 处决结构线：目标装甲相对自机越低线越高 —— 低于 75%、相等 50%、两倍及以上线性压到 10%。
     *  只读装甲评级（含统计修正），装甲数据不可用时退回旧常量，绝不放宽到不可验证。 */
    public static float hullThreshold(ShipAPI source,ShipAPI target){
        float own=armorRating(source),enemy=armorRating(target);
        if(!(own>0f)||!Float.isFinite(own)||!(enemy>0f)||!Float.isFinite(enemy))return LOW_HULL_FRACTION;
        float ratio=enemy/own;
        if(ratio<1f)return LOW_HULL_BELOW_ARMOR;
        if(ratio>=2f)return LOW_HULL_HEAVY_ARMOR;
        return LOW_HULL_EQUAL_ARMOR+(LOW_HULL_HEAVY_ARMOR-LOW_HULL_EQUAL_ARMOR)*(ratio-1f);
    }
    /** 目标装甲 / 自机装甲，仅用于提示文案；取不到时给 -1。 */
    public static float armorRatio(ShipAPI source,ShipAPI target){
        float own=armorRating(source),enemy=armorRating(target);
        return own>0f&&Float.isFinite(own)&&enemy>0f&&Float.isFinite(enemy)?enemy/own:-1f;
    }
    /** 装甲评级（原生伤害结算同源口径：hullSpec.getArmorRating() 经 getArmorBonus 修正）。 */
    private static float armorRating(ShipAPI ship){
        if(ship==null||ship.getMutableStats()==null||ship.getHullSpec()==null)return -1f;
        try{float rating=ship.getMutableStats().getArmorBonus().computeEffective(ship.getHullSpec().getArmorRating());
            return Float.isFinite(rating)?rating:-1f;}
        catch(RuntimeException failure){return -1f;}
    }
    private static boolean nativeShieldCovers(ShipAPI source,ShipAPI target) {
        ShieldAPI shield=target.getShield();
        if(shield==null||!shield.isOn()||shield.getType()==ShieldAPI.ShieldType.NONE
                ||shield.getType()==ShieldAPI.ShieldType.PHASE)return false;
        Vector2f incoming=source.getLocation();
        // Missing geometry cannot be used as proof that a raised shield is absent.
        return incoming==null||!Float.isFinite(incoming.x)||!Float.isFinite(incoming.y)||shield.isWithinArc(incoming);
    }

    /** A module is a physical shield only when its public native parent/slot/hull
     * declaration agrees. No private Moci state or name-substring inference is used.
     * Any surviving declared shield is conservatively treated as protective. */
    private static boolean physicalShieldPresent(CombatEngineAPI engine,ShipAPI target) {
        List<ShipAPI> modules=target.getChildModulesCopy();
        if(modules==null||modules.isEmpty())return false;
        if(modules.size()>MAX_MODULES)return true;
        ShipVariantAPI variant=target.getVariant();
        ShipVariantAPI declared=variant==null?null:variant.getModuleVariant(SHIELD_SLOT);
        ShipHullSpecAPI declaredHull=declared==null?null:declared.getHullSpec();
        if(declaredHull==null)return false;
        for(ShipAPI module:modules) {
            if(module==null||module.getParentStation()!=target||module.getStationSlot()==null
                    ||!SHIELD_SLOT.equals(module.getStationSlot().getId())||module.getOwner()!=target.getOwner()
                    ||!module.isAlive()||module.isHulk()||module.wasRemoved()||module.isPhased()
                    ||module.getCollisionClass()==CollisionClass.NONE||!engine.isEntityInPlay(module))continue;
            ShipHullSpecAPI actual=module.getHullSpec();
            if(actual!=null&&sameHull(actual,declaredHull))return true;
        }
        return false;
    }
    private static boolean sameHull(ShipHullSpecAPI actual,ShipHullSpecAPI declared) {
        for(String a:new String[]{actual.getHullId(),actual.getBaseHullId()})
            if(a!=null&&!a.isEmpty()&&(a.equals(declared.getHullId())||a.equals(declared.getBaseHullId())))return true;
        return false;
    }
}
