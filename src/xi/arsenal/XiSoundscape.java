package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SoundPlayerAPI;
import com.fs.starfarer.api.SoundAPI;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.combat.entities.MovingRay;
import java.util.*;
import java.util.function.LongSupplier;
import org.lwjgl.util.vector.Vector2f;

/** Two local cockpit warning channels plus a separately owned world-flight component. */
public final class XiSoundscape implements AutoCloseable, data.scripts.combat.Moci_CombatSink {
    public static final String KEY = "xi.arsenal.soundscape";
    public static final String FLIGHT_SOUND = "Moci_fw_xi_flight_loop";
    public static final String NEAR_SOUND = "Moci_fw_xi_enemy_near_loop";
    public static final String INCOMING_SOUND = "Moci_fw_xi_incoming_loop";
    /** Legacy constants kept for source compatibility; enemy contacts no longer use distance. */
    @Deprecated public static final float ENEMY_ENTER = 1800f, ENEMY_EXIT = 2100f;
    /** World distance from the real current collision edge, not center or predicted impact. */
    public static final float INCOMING_ENTER = 300f, INCOMING_EXIT = 420f;
    public static final double INCOMING_PERIOD_SECONDS = 38235d / 44100d;
    public static final int MAX_TRACKED_ATTACKS = 512, MAX_SHIP_CANDIDATES = 512, MAX_ATTACK_CANDIDATES = 2048;
    private static final float SCAN_INTERVAL = .12f, CONTACT_HOLD = .5f;
    private static final String OWNER_KEY = KEY + ".owner";
    /** 全周天驾驶舱船插：座舱告警的唯一入口，谁装谁有效果。 */
    public static final String COCKPIT_HULLMOD = "moci_panoramic_cockpit";

    /**
     * 座舱告警的宿主：由装了全周天驾驶舱的舰船在战斗内惰性安装，不再依赖全局战斗插件。
     * 幂等；敌方单位不装船插就不会有任何额外音频。
     */
    public static XiSoundscape ensure(CombatEngineAPI engine) {
        if (Global.getCurrentState() != com.fs.starfarer.api.GameState.COMBAT) return null;
        if (engine == null) return null;
        Object owner = engine.getCustomData().get(OWNER_KEY);
        if (owner instanceof XiSoundscape own && !own.closed) return own;
        XiSoundscape created = new XiSoundscape();
        created.start(engine);
        return engine.getCustomData().get(OWNER_KEY) == created ? created : null;
    }

    private final Channel near = new Channel(NEAR_SOUND, .95f, .08f, .30f);
    private final IncomingChannel incoming = new IncomingChannel();
    private final Set<DamagingProjectileAPI> attacks = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<ShipAPI, Contact> contacts = new IdentityHashMap<>();
    private final Map<String, Object> facts = new LinkedHashMap<>();
    private final List<Map<String, Object>> transitions = new ArrayList<>();
    private CombatEngineAPI engine;
    private final LongSupplier uiNanoClock;
    private ShipAPI boundPlayer;
    private boolean enabled, nearPredicate, combatEnded, closed;
    private boolean lockingPredicate, visiblePredicate, heldContactPredicate;
    private int shipCursor;
    private float scanClock;
    private double clock;
    private long scans, shipCandidates, attackCandidates, shipScanTruncations, attackScanTruncations,
            trackedCapacityTruncations, admitted, removed, pausedFrames, frames;
    private HullGeometry currentHull;

    public XiSoundscape() { this(System::nanoTime); }
    // The native sound buffer runs in client UI time, even when combat time is accelerated.
    // Package-private clock injection permits deterministic API-only handoff/edge checks.
    XiSoundscape(LongSupplier uiNanoClock) { this.uiNanoClock = Objects.requireNonNull(uiNanoClock); }

    /** Immutable diagnostic snapshot; this does not refresh audio,scan threats or advance time. */
    public static Map<String,Object> status(CombatEngineAPI engine){
        Object raw=engine==null?null:engine.getCustomData().get(KEY);
        if(!(raw instanceof Map<?,?> values))return Map.of();
        Map<String,Object> copy=new LinkedHashMap<>();values.forEach((key,value)->copy.put(String.valueOf(key),diagnosticCopy(value)));
        return Collections.unmodifiableMap(copy);
    }
    private static Object diagnosticCopy(Object value){
        if(value instanceof Map<?,?> map){Map<String,Object> out=new LinkedHashMap<>();map.forEach((key,item)->out.put(String.valueOf(key),diagnosticCopy(item)));return Collections.unmodifiableMap(out);}
        if(value instanceof List<?> list)return list.stream().map(XiSoundscape::diagnosticCopy).toList();
        return value;
    }

    void start(CombatEngineAPI engine) {
        if (closed) throw new IllegalStateException("Closed cockpit audio cannot be reused");
        if (this.engine != null && this.engine != engine) throw new IllegalStateException("Cockpit audio cannot move between engines");
        this.engine = Objects.requireNonNull(engine, "Native combat engine");
        Object owner = engine.getCustomData().get(OWNER_KEY);
        if (owner != null && owner != this) return; // Duplicate registration must not layer the same cockpit audio.
        engine.getCustomData().put(OWNER_KEY, this);
        // 引擎外资源只有本类自持的两路音频：near 是固定 sound id 的 UI loop，incoming 是一个
        // 自持的 SoundAPI 句柄（IncomingChannel.handle）。拥有者就是本对象，由 close() 释放；
        // 不再登记进任何中央注册表，也不再依赖战斗插件在结束时顺手关。
        enabled = true;
        facts.put("enabled", true); facts.put("closed", false);
        facts.put("schema", 5);
        facts.put("scope", "one current local live Xi/Penelope cockpit per client; UI loop requests do not prove an audible device");
        facts.put("audioRouting", "client-local UI: enemy contacts native loop; incoming one owned immediate SoundAPI handle");
        facts.put("recipient", "local-cockpit"); facts.put("localPlayerOnly", true);
        facts.put("listenerBindingStorage", "client-process-only; excluded from synchronized combat state");
        facts.put("enemyPredicate", "live hostile main ship targets the local cockpit OR is visible in that owner's fog of war; no distance limit");
        facts.put("enemyContactHoldSeconds", CONTACT_HOLD);
        facts.put("incomingEnter", INCOMING_ENTER); facts.put("incomingExit", INCOMING_EXIT); facts.put("scanInterval", SCAN_INTERVAL);
        facts.put("incomingDistanceRule", "current hostile attack to actual local hull collision polygon edge; collision-radius fallback only when exact original bounds unavailable");
        facts.put("incomingPredictionStartsAlarm", false);facts.put("incomingPeriodSeconds",INCOMING_PERIOD_SECONDS);
        facts.put("maxTrackedAttacks", MAX_TRACKED_ATTACKS);
        facts.put("transitions", transitions);
        facts.put("channels", Map.of("enemyNear", near.facts, "incoming", incoming.facts));
        engine.getCustomData().put(KEY, facts);
        publish(false);
        // 总线登记是唯一可能失败的外部步骤：失败即整体回滚（含已建立的音频通道），
        // 不把半初始化的对象留在引擎 customData 里。
        try { data.scripts.combat.Moci_CombatBus.register(this); }
        catch (RuntimeException | LinkageError failure) {
            try { close(); } catch (RuntimeException | LinkageError cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override public void processInput(float amount, List<InputEventAPI> events) {
        if (enabled && !closed) MinovskyFlightStats.observeInput(engine, events);
    }

    @Override public void advance(float amount, List<InputEventAPI> events) {
        if (closed) return;
        // 引擎消失/切场守卫：即使 isCombatOver() 这一帧没轮到，句柄也必须在第一次发现引擎换了时释放，
        // 保证不把 UI loop 音量或 SoundAPI 句柄带进下一场战斗。
        if (engine != null && Global.getCombatEngine() != engine) { close(); return; }
        if (!enabled || engine == null) return;
        frames++;
        if (engine.isCombatOver()) { combatEnded = true; close(); return; }
        if (engine.isPaused()) {
            incoming.stopOwned();
            pausedFrames++; facts.put("paused", true); facts.put("pausedFrames", pausedFrames); return;
        }
        float dt = Float.isFinite(amount) ? Math.max(0f, amount) : 0f; clock += dt;
        ShipAPI player = combatEnded ? null : LocalCockpitAudio.resolve(engine);
        boolean eligible = !combatEnded && isEligiblePlayer(player);
        if (player != boundPlayer) {
            near.resetForListenerChange(); incoming.resetForListenerChange();
            attacks.clear(); clearContacts(); scanClock = SCAN_INTERVAL; boundPlayer = player;
        }
        if (eligible) {
            currentHull = HullGeometry.read(player);
            pruneAttacks(player); pruneContacts(player); scanClock += dt;
            if (scanClock >= SCAN_INTERVAL) {
                scanClock %= SCAN_INTERVAL; scanShips(player); scanAttacks(player); scans++;
            }
        } else {
            currentHull = null;
            attacks.clear(); clearContacts(); near.resetForListenerChange(); incoming.resetForListenerChange();
        }
        near.update(eligible && nearPredicate, dt); incoming.update(eligible && !attacks.isEmpty(), dt);
        publish(eligible);
    }
    private boolean isEligiblePlayer(ShipAPI ship) {
        return liveMainShip(ship) && engine.isEntityInPlay(ship) && hasCockpit(ship);
    }

    /** 全周天驾驶舱船插判定：取代原来写死的柯西/佩涅罗佩舰船名单。 */
    public static boolean hasCockpit(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(COCKPIT_HULLMOD);
    }

    /** Resolve base/D-parent chains through public hull APIs, with a hard bound and identity-cycle guard. */
    public static boolean isSupportedHull(ShipHullSpecAPI spec) {
        if (spec == null) return false;
        Set<ShipHullSpecAPI> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<ShipHullSpecAPI> pending = new ArrayDeque<>(); pending.add(spec);
        for (int checked = 0; checked < 8 && !pending.isEmpty(); checked++) {
            ShipHullSpecAPI current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (supportedId(current.getHullId()) || supportedId(current.getBaseHullId()) || supportedId(current.getDParentHullId())) return true;
            ShipHullSpecAPI base = current.getBaseHull(), parent = current.getDParentHull();
            if (base != null && !seen.contains(base)) pending.addLast(base);
            if (parent != null && !seen.contains(parent)) pending.addLast(parent);
        }
        return false;
    }

    /** Legacy entry point retained for external checks; the shared cockpit now supports both hull families. */
    public static boolean isXiHull(ShipHullSpecAPI spec) { return isSupportedHull(spec); }


    private static boolean penelopeId(String id) {
        return "Moci_RX_104FF".equals(id) || "Moci_RX_104FF_framework_arsenal".equals(id);
    }

    private static boolean supportedId(String id) {
        return "Moci_RX_105".equals(id) || "Moci_RX_105_framework_arsenal".equals(id)
                || penelopeId(id);
    }

    private static boolean liveMainShip(ShipAPI ship) {
        return ship != null && ship.isAlive() && !ship.isHulk() && !ship.isStationModule() && ship.getParentStation() == null;
    }

    private static boolean hostile(int owner, int playerOwner) {
        return owner >= 0 && owner <= 1 && playerOwner >= 0 && playerOwner <= 1 && owner != playerOwner;
    }

    private record Contact(double seen, boolean locking, boolean visible) { }

    private void clearContacts() {
        contacts.clear(); nearPredicate = false; lockingPredicate = false; visiblePredicate = false;
        heldContactPredicate = false; shipCursor = 0;
    }

    private boolean validEnemy(ShipAPI ship, ShipAPI player) {
        return ship != player && liveMainShip(ship) && hostile(ship.getOwner(), player.getOwner()) && engine.isEntityInPlay(ship);
    }

    private void pruneContacts(ShipAPI player) {
        contacts.entrySet().removeIf(entry -> !validEnemy(entry.getKey(), player) || clock - entry.getValue().seen() > CONTACT_HOLD);
        refreshContactPredicates();
    }

    private void refreshContactPredicates() {
        lockingPredicate = false; visiblePredicate = false; heldContactPredicate = false;
        for (Contact contact : contacts.values()) {
            lockingPredicate |= contact.locking(); visiblePredicate |= contact.visible();
            heldContactPredicate |= clock > contact.seen();
        }
        nearPredicate = !contacts.isEmpty();
    }

    private void scanShips(ShipAPI player) {
        List<ShipAPI> ships = engine.getShips();
        if (ships == null || ships.isEmpty()) { facts.put("shipListUnavailable", ships == null); return; }
        FogOfWarAPI fog = engine.getFogOfWar(player.getOwner());
        facts.put("fogOfWarUnavailable", fog == null);
        // Global contacts must not depend on the local spatial grid. Rotate a bounded slice
        // rather than permanently starving ships beyond the first 512 entries.
        int count = ships.size(), checked = Math.min(count, MAX_SHIP_CANDIDATES);
        shipCursor = Math.floorMod(shipCursor, count);
        for (int i = 0; i < checked; i++) {
            ShipAPI ship = ships.get((shipCursor + i) % count);
            if (!validEnemy(ship, player)) continue;
            boolean locking = ship.getShipTarget() == player;
            boolean visible = fog != null && fog.isVisible(ship);
            if ((locking || visible) && (contacts.containsKey(ship) || contacts.size() < MAX_SHIP_CANDIDATES))
                contacts.put(ship, new Contact(clock, locking, visible));
        }
        shipCursor = (shipCursor + checked) % count;
        shipCandidates += checked;
        if (count > checked) shipScanTruncations++;
        refreshContactPredicates();
    }

    private void pruneAttacks(ShipAPI player) {
        Iterator<DamagingProjectileAPI> iterator = attacks.iterator();
        while (iterator.hasNext()) {
            DamagingProjectileAPI attack = iterator.next();
            if (!validIncoming(attack,player) || currentHull.distance(attack) > INCOMING_EXIT) { iterator.remove(); removed++; }
        }
    }

    private void scanAttacks(ShipAPI player) {
        CollisionGridAPI grid = engine.getAllObjectGrid();
        if (grid == null) { facts.put("attackGridUnavailable", true); return; }
        float range=currentHull.extent+INCOMING_ENTER;
        Iterator<Object> iterator = grid.getCheckIterator(player.getLocation(), range * 2f, range * 2f);
        int checked = 0;
        while (iterator.hasNext() && checked < MAX_ATTACK_CANDIDATES) {
            Object object = iterator.next(); checked++;
            if (!(object instanceof DamagingProjectileAPI attack) || attacks.contains(attack) || !validIncoming(attack,player)) continue;
            if (currentHull.distance(attack) > INCOMING_ENTER) continue;
            if (attacks.size() >= MAX_TRACKED_ATTACKS) { trackedCapacityTruncations++; continue; }
            attacks.add(attack); admitted++;
        }
        attackCandidates += checked;
        if (iterator.hasNext()) attackScanTruncations++;
    }

    private boolean validIncoming(DamagingProjectileAPI attack,ShipAPI player){
        return attack!=null&&hostile(attack.getOwner(),player.getOwner())&&attack.getSource()!=player
                &&!attack.isExpired()&&!attack.wasRemoved()&&engine.isEntityInPlay(attack)
                &&Float.isFinite(attack.getDamageAmount())&&attack.getDamageAmount()>0
                &&attack.getCollisionClass()!=CollisionClass.NONE
                &&(attack.getWeapon()==null||!attack.getWeapon().isDecorative());
    }
    /** Read-only test/adapter seam; original native bounds and vectors are never updated or written. */
    static float incomingHullDistance(DamagingProjectileAPI attack,ShipAPI player){return HullGeometry.read(player).distance(attack);}
    private static final class HullGeometry {
        final float cx,cy,cos,sin,radius,extent;final float[][] segments;
        HullGeometry(float cx,float cy,float facing,float radius,float extent,float[][] segments){this.cx=cx;this.cy=cy;this.radius=radius;this.extent=extent;this.segments=segments;double a=Math.toRadians(facing);cos=(float)Math.cos(a);sin=(float)Math.sin(a);}
        static HullGeometry read(ShipAPI ship){
            Vector2f p=ship.getLocation();float r=Float.isFinite(ship.getCollisionRadius())?Math.max(0,ship.getCollisionRadius()):0;
            List<float[]> copied=new ArrayList<>();float extent=r;BoundsAPI bounds=ship.getExactBounds();
            List<BoundsAPI.SegmentAPI> original=bounds==null?null:bounds.getOrigSegments();
            if(original!=null&&original.size()<=128)for(BoundsAPI.SegmentAPI s:original){
                if(s==null||!finite(s.getP1())||!finite(s.getP2()))continue;Vector2f a=s.getP1(),b=s.getP2();
                copied.add(new float[]{a.x,a.y,b.x,b.y});extent=Math.max(extent,Math.max(a.length(),b.length()));
            }
            return new HullGeometry(p.x,p.y,ship.getFacing(),r,extent,copied.size()>=3?copied.toArray(float[][]::new):new float[0][]);
        }
        float distance(DamagingProjectileAPI attack){
            if(attack==null||!Float.isFinite(cx)||!Float.isFinite(cy)||!Float.isFinite(cos)||!Float.isFinite(sin))return Float.POSITIVE_INFINITY;Vector2f from=attack.getLocation(),to=from;
            boolean ray=attack instanceof MovingRay;
            if(attack instanceof MovingRay r&&finite(r.getFrom())&&finite(r.getTo())){from=r.getFrom();to=r.getTo();}
            if(!finite(from)||!finite(to))return Float.POSITIVE_INFINITY;
            float ax=(from.x-cx)*cos+(from.y-cy)*sin,ay=-(from.x-cx)*sin+(from.y-cy)*cos;
            float bx=(to.x-cx)*cos+(to.y-cy)*sin,by=-(to.x-cx)*sin+(to.y-cy)*cos;
            float attackRadius=ray?0:Math.max(0,Float.isFinite(attack.getCollisionRadius())?attack.getCollisionRadius():0);
            if(segments.length==0){float gap=Math.max(0,(float)Math.sqrt(pointDistanceSquared(0,0,ax,ay,bx,by))-radius-attackRadius);return Float.isFinite(gap)?gap:Float.POSITIVE_INFINITY;}
            if(inside(ax,ay)||inside(bx,by))return 0;
            float best=Float.POSITIVE_INFINITY;
            for(float[] s:segments)best=Math.min(best,segmentDistanceSquared(ax,ay,bx,by,s[0],s[1],s[2],s[3]));
            float gap=Math.max(0,(float)Math.sqrt(best)-attackRadius);return Float.isFinite(gap)?gap:Float.POSITIVE_INFINITY;
        }
        boolean inside(float x,float y){boolean in=false;for(float[] s:segments)if((s[1]>y)!=(s[3]>y)&&x<(s[2]-s[0])*(y-s[1])/(s[3]-s[1])+s[0])in=!in;return in;}
        static float segmentDistanceSquared(float ax,float ay,float bx,float by,float cx,float cy,float dx,float dy){
            float ux=bx-ax,uy=by-ay,vx=dx-cx,vy=dy-cy,denom=ux*vy-uy*vx;
            if(Math.abs(denom)>.00001f){float wx=cx-ax,wy=cy-ay,t=(wx*vy-wy*vx)/denom,s=(wx*uy-wy*ux)/denom;if(t>=0&&t<=1&&s>=0&&s<=1)return 0;}
            return Math.min(Math.min(pointDistanceSquared(ax,ay,cx,cy,dx,dy),pointDistanceSquared(bx,by,cx,cy,dx,dy)),Math.min(pointDistanceSquared(cx,cy,ax,ay,bx,by),pointDistanceSquared(dx,dy,ax,ay,bx,by)));
        }
        static float pointDistanceSquared(float x,float y,float ax,float ay,float bx,float by){float dx=bx-ax,dy=by-ay,denom=dx*dx+dy*dy,t=denom<=.000001f?0:Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy)/denom));float px=x-ax-t*dx,py=y-ay-t*dy;return px*px+py*py;}
        static boolean finite(Vector2f p){return p!=null&&Float.isFinite(p.x)&&Float.isFinite(p.y);}
    }

    private void publish(boolean eligible) {
        facts.put("simulationClock", clock); facts.put("frames", frames); facts.put("paused", false);
        facts.put("playerId", boundPlayer == null ? "" : boundPlayer.getId());
        facts.put("playerHull", boundPlayer == null ? "" : boundPlayer.getHullSpec().getHullId());
        facts.put("xiEligible", eligible);
        facts.put("cockpitEligible", eligible);
        facts.put("explicitLocalBinding", LocalCockpitAudio.hasExplicitBinding(engine));
        facts.put("enemyNearPredicate", eligible && nearPredicate);
        facts.put("enemyLockingPredicate", eligible && lockingPredicate); facts.put("enemyVisiblePredicate", eligible && visiblePredicate);
        facts.put("enemyContactHeld", eligible && heldContactPredicate); facts.put("trackedEnemyContacts", contacts.size());
        facts.put("incomingPredicate", eligible && !attacks.isEmpty()); facts.put("trackedAttacks", attacks.size());
        facts.put("scans", scans); facts.put("shipCandidates", shipCandidates); facts.put("attackCandidates", attackCandidates);
        facts.put("shipScanTruncations", shipScanTruncations); facts.put("attackScanTruncations", attackScanTruncations);
        facts.put("trackedCapacityTruncations", trackedCapacityTruncations); facts.put("attacksAdmitted", admitted);
        facts.put("attacksRemoved", removed); facts.put("pausedFrames", pausedFrames);
        facts.put("incomingGeometry",currentHull==null?"inactive":currentHull.segments.length==0?"native-collision-radius-fallback":"copied-native-original-polygon");
    }

    /**
     * Owner-thread battle teardown. Only this pack's handles/voices are released.
     * 幂等：closed 先置位，重复调用（advance 守卫 / Moci_CombatBus.closeAll() / start 回滚）直接短路。
     * 每一步独立兜底（RuntimeException | LinkageError），任一步失败都不阻断后续释放，
     * 确保 near 的 UI loop 音量、incoming 的 SoundAPI 句柄、OWNER_KEY 与总线登记没有一处被留在外面。
     */
    @Override public void close() {
        if (closed) return;
        closed = true;
        boolean owns = engine != null && engine.getCustomData().get(OWNER_KEY) == this;
        if (owns) {
            releaseQuietly(near::stopOwned, "near UI loop");
            releaseQuietly(incoming::stopOwned, "incoming SoundAPI handle");
            releaseQuietly(() -> LocalCockpitAudio.clear(engine), "local cockpit binding");
            releaseQuietly(() -> engine.getCustomData().remove(OWNER_KEY), "owner key");
        }
        enabled = false; attacks.clear(); clearContacts(); boundPlayer = null;
        if (owns) { publish(false); facts.put("closed", true); facts.put("enabled", false); }
        releaseQuietly(() -> data.scripts.combat.Moci_CombatBus.unregister(this), "combat bus registration");
    }

    /** 单个释放步骤的兜底：只记一次 warn，绝不把异常抛回引擎或战斗插件。 */
    private static void releaseQuietly(Runnable step, String what) {
        try {
            step.run();
        } catch (RuntimeException | LinkageError failure) {
            Global.getLogger(XiSoundscape.class).warn("Xi cockpit audio release step failed: " + what, failure);
        }
    }

    /**
     * Native playUILoop has an unconfigurable0.5s UI fade and ignores volume0 refreshes.
     * This local warning owns one ordinary UI handle instead; no gain envelope or world routing.
     */
    private final class IncomingChannel {
        final Map<String,Object> facts=new LinkedHashMap<>();final float maximum=.75f;
        SoundAPI handle;boolean requested,failed;long nextCycle,activations,releases,calls,stops,failures,unavailable;
        IncomingChannel(){facts.put("sound",INCOMING_SOUND);facts.put("maximumVolume",maximum);facts.put("routing","local-cockpit-owned-ui-handle");facts.put("attackSeconds",0f);facts.put("releaseSeconds",0f);facts.put("playUILoopCalls",0L);publish();}
        void update(boolean active,float dt){
            if(requested!=active){requested=active;if(active)activations++;else releases++;if(transitions.size()<64)transitions.add(Map.of("t",clock,"sound",INCOMING_SOUND,"active",active));}
            if(!active){stopHandle();publish();return;}
            if(!failed){long now=uiNanoClock.getAsLong();if(handle==null||now>=nextCycle){
                stopHandle();SoundPlayerAPI player=Global.getSoundPlayer();
                if(player==null)unavailable++;
                else try{handle=player.playUISound(INCOMING_SOUND,1f,maximum);calls++;if(handle==null)unavailable++;nextCycle=now+Math.round(INCOMING_PERIOD_SECONDS*1_000_000_000d);}
                catch(RuntimeException | LinkageError error){recordFailure(error);}
            }}publish();
        }
        void stopHandle(){if(handle==null)return;SoundAPI owned=handle;handle=null;try{owned.stop();stops++;}catch(RuntimeException | LinkageError error){recordFailure(error);}nextCycle=0;}
        void recordFailure(Throwable error){failed=true;failures++;String detail=error.toString();facts.put("lastError",detail.length()>512?detail.substring(0,512):detail);Global.getLogger(XiSoundscape.class).warn("Owned incoming cockpit warning failed",error);}
        void resetForListenerChange(){stopOwned();}
        void stopOwned(){if(requested)releases++;requested=false;stopHandle();publish();}
        void publish(){facts.put("predicate",requested);facts.put("volume",requested&&!failed?maximum:0f);facts.put("activations",activations);facts.put("releases",releases);facts.put("playUISoundCalls",calls);facts.put("playLoopCalls",0L);facts.put("stopCalls",stops);facts.put("failures",failures);facts.put("soundPlayerUnavailableFrames",unavailable);facts.put("ownedHandleActive",handle!=null);}
    }

    private final class Channel {
        final String sound;
        final float maximum, fadeIn, fadeOut;
        final Map<String, Object> facts = new LinkedHashMap<>();
        float level;
        boolean requested, failed;
        long activations, releases, calls, failures, unavailable;
        Channel(String sound, float maximum, float fadeIn, float fadeOut) {
            this.sound = sound; this.maximum = maximum; this.fadeIn = fadeIn; this.fadeOut = fadeOut;
            facts.put("sound", sound); facts.put("maximumVolume", maximum);
            facts.put("predicate", false); facts.put("volume", 0f); facts.put("playLoopCalls", 0L);
            facts.put("playUILoopCalls", 0L); facts.put("routing", "local-cockpit-ui");
        }
        void update(boolean active, float dt) {
            if (requested != active) {
                requested = active;
                if (active) activations++; else releases++;
                if (transitions.size() < 64) transitions.add(Map.of("t", clock, "sound", sound, "active", active));
            }
            float step = dt / (active ? fadeIn : fadeOut);
            level = active ? Math.min(1f, level + step) : Math.max(0f, level - step);
            if (level > .001f && !failed) {
                SoundPlayerAPI soundPlayer = Global.getSoundPlayer();
                if (soundPlayer == null) unavailable++;
                else try {
                    // Stable sound id is the native UI-loop key. Neither ship identity nor enemy
                    // count creates another voice; distance/camera listener positions do not apply.
                    soundPlayer.playUILoop(sound, 1f, level * maximum);
                    calls++;
                } catch (RuntimeException | LinkageError error) {
                    failed = true; failures++;
                    String detail = error.toString();
                    facts.put("lastError", detail.length() > 512 ? detail.substring(0, 512) : detail);
                    Global.getLogger(XiSoundscape.class).warn("Xi sound loop disabled after playback failure: " + sound, error);
                }
            }
            facts.put("predicate", active); facts.put("volume", level * maximum);
            facts.put("activations", activations); facts.put("releases", releases); facts.put("playLoopCalls", calls);
            facts.put("playUILoopCalls", calls);
            facts.put("failures", failures); facts.put("soundPlayerUnavailableFrames", unavailable);
        }

        void resetForListenerChange() {
            if (!requested && level <= 0f) return;
            stopOwned();
        }

        void stopOwned() {
            if (requested) releases++;
            requested = false; level = 0f;
            SoundPlayerAPI soundPlayer = Global.getSoundPlayer();
            if (soundPlayer != null) {
                try {
                    // Zero only this pack's fixed UI voice, never all UI sounds or global volume.
                    soundPlayer.playUILoop(sound, 1f, 0f); calls++;
                } catch (RuntimeException | LinkageError error) {
                    failed = true; failures++;
                    String detail = error.toString();
                    facts.put("lastError", detail.length() > 512 ? detail.substring(0, 512) : detail);
                }
            }
            facts.put("predicate", false); facts.put("volume", 0f);
            facts.put("releases", releases); facts.put("playLoopCalls", calls); facts.put("playUILoopCalls", calls);
        }
    }
}
