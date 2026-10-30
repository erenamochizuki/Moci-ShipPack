package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.input.InputEventAPI;
import java.util.*;
import java.util.function.DoubleSupplier;
import org.apache.log4j.Logger;
import org.lwjgl.input.Keyboard;

/** One player-only active/passive Newtype owner; real time multipliers, never movement bonuses. */
public final class OwnNewtypeController implements AutoCloseable, data.scripts.combat.Moci_CombatSink {
    public static final String INSTANCE_KEY = "xi.arsenal.newtype.controller";
    public static final String TELEMETRY_KEY = "xi.arsenal.newtype";
    public static final String MODIFIER = "xi.arsenal.own_newtype.time";
    public static final float DURATION_PLAYER_SECONDS = 10f, ACTIVATION_SOFT_FLUX = 300f, SHIP_TIME_MULT = 1.5f;
    public static final float PASSIVE_INTERVAL_PLAYER_SECONDS = 20f;
    private static final float PASSIVE_SCAN_INTERVAL = .12f;
    private static final int MAX_PASSIVE_CANDIDATES = 2048;
    private static final String ORIGINAL_PREFIX = "MociPlayerSkill1_";
    private static final String ORIGINAL_LISTENER = "MociPlayerSkill1Listener";
    private static final String ICON = "graphics/icons/hullsys/moci_skill1.png";
    /** 常驻排查日志开关（禁止挂到 devMode 上）。 */
    public static final boolean TRACE = true;
    private static final String LOG_PREFIX = "[MOCI-TIME] ";
    private static final Logger LOG = Global.getLogger(OwnNewtypeController.class);
    private final Set<ShipAPI> touched = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Map<String, Object>> events = new ArrayList<>();
    private final List<Map<String, Object>> samples = new ArrayList<>();
    private final Set<Integer> heldKeys = new HashSet<>();
    private final NewtypeThreatRolls threatRolls;
    private CombatEngineAPI engine;
    private ShipAPI activeShip, observedPlayer;
    private OwnNewtypeBindings.Result binding = new OwnNewtypeBindings.Result(false, List.of(), "等待玩家机体");
    private boolean duplicate, stopped, passiveOriginalDelegate;
    private float remaining, scanCountdown, sampleCountdown, messageRemaining, passiveCooldown, passiveScanCountdown;
    private String message = "", activeSource = "none";
    private long activations, declined, droppedEvents, passiveActivations, passiveCandidates, passiveScanTruncations;
    private int timeReleaseFailures;
    private String lastTimeReleaseFailure = "";
    private double fluxSpent, activePlayerSeconds;

    public OwnNewtypeController() { this(Math::random); }
    OwnNewtypeController(DoubleSupplier random) { threatRolls = new NewtypeThreatRolls(random); }

    /**
     * 新人类主动能力的宿主：由拥有 {@code Moci_PlayerSkill1} 的飞行员技能监听器惰性安装。
     * 没有该技能就没有这套叠加层，也不再需要 settings.json 的全局插件条目。
     */
    public static OwnNewtypeController ensure(CombatEngineAPI engine) {
        if (Global.getCurrentState() != com.fs.starfarer.api.GameState.COMBAT) return null;
        Objects.requireNonNull(engine, "engine");
        Object existing = engine.getCustomData().get(INSTANCE_KEY);
        if (existing instanceof OwnNewtypeController controller) return controller.stopped ? null : controller;
        if (existing != null) throw new IllegalStateException("Own Newtype controller key already has another owner");
        OwnNewtypeController result = new OwnNewtypeController();
        result.start(engine);
        return result.duplicate || result.stopped ? null : result;
    }

    /** 兼容旧调用点；语义与 ensure 相同。 */
    @Deprecated public static OwnNewtypeController install(CombatEngineAPI engine) { return ensure(engine); }

    void start(CombatEngineAPI engine) {
        if (stopped) throw new IllegalStateException("Closed Newtype controller cannot be reused");
        if (this.engine != null && this.engine != engine) throw new IllegalStateException("Newtype controller cannot move between engines");
        this.engine = Objects.requireNonNull(engine, "engine");
        Object existing = engine.getCustomData().get(INSTANCE_KEY);
        if (existing != null && existing != this) { duplicate = true; return; }
        engine.getCustomData().put(INSTANCE_KEY, this);
        // 无引擎外资源：close() 只撤销 timeMult 修饰、清 INSTANCE_KEY 并在总线上注销，
        // 全部是引擎内状态；结束链路由本类 advance() 的 combat-over 守卫 + Moci_CombatBus.closeAll() 覆盖。
        data.scripts.combat.Moci_CombatBus.register(this);
        publish();
    }

    @Override public void processInput(float amount, List<InputEventAPI> inputs) {
        if (duplicate || stopped || engine == null || inputs == null) return;
        // The native map is reflective: sample it at the normal .5-second cadence, then
        // recheck immediately inside an actual activation attempt rather than every input frame.
        if (scanCountdown <= 0) refreshBinding(engine.getPlayerShip());
        for (InputEventAPI event : inputs) {
            if (event == null || event.isConsumed() || !event.isKeyboardEvent()) continue;
            int key=event.getEventValue();
            if (event.isKeyUpEvent()) { heldKeys.remove(key); continue; }
            if (!event.isKeyDownEvent() || key<=0 || key>=Keyboard.KEYBOARD_SIZE) continue;
            boolean fresh=heldKeys.add(key);
            if (!fresh || event.isRepeat() || event.isConsumed() || !binding.available() || key!=binding.keyCode()) continue;
            Activation result = requestActivation(engine.getPlayerShip(), key);
            // Failed/conflicting input belongs to its previous owner. Never consume it on refusal.
            if (result.accepted()) event.consume();
        }
    }

    public record Activation(boolean accepted, String reason) { }

    /** Public player action retains normal UI, key availability, flux and time preconditions. */
    public Activation requestActivation(ShipAPI expectedPlayer) {
        return requestActivation(expectedPlayer, Keyboard.KEY_NONE);
    }
    private Activation requestActivation(ShipAPI expectedPlayer, int inputKey) {
        String blocked = playerBlockReason(expectedPlayer, true);
        if (!blocked.isEmpty()) return refuse(blocked);
        refreshBinding(expectedPlayer);
        if (!binding.available()) return refuse(binding.error().isEmpty() ? "字母键冲突：" + String.join("、", binding.conflicts()) : binding.error());
        if (inputKey!=Keyboard.KEY_NONE && inputKey!=binding.keyCode()) return refuse("原字母键占用已改变，请使用 " + binding.keyName());
        return activate(expectedPlayer,"manual");
    }
    private void refreshBinding(ShipAPI player) {
        if(player==null){binding=new OwnNewtypeBindings.Result(false,List.of(),"等待玩家机体");return;}
        binding=OwnNewtypeBindings.select(engine,player,binding.keyCode());scanCountdown=.5f;
    }
    private Activation activate(ShipAPI expectedPlayer,String source) {
        if (activeShip != null) return refuse("新人类已经激活，不续时、不重复扣幅能");
        if (originalActive(expectedPlayer)) return refuse("原版自动新人类正在生效，主动能力让位");
        FluxTrackerAPI flux = expectedPlayer.getFluxTracker();
        if (flux.getMaxFlux() - flux.getCurrFlux() < ACTIVATION_SOFT_FLUX) return refuse("幅能空间不足，需要 300");
        float globalTime = engine.getTimeMult().getModifiedValue(), localTime = expectedPlayer.getMutableStats().getTimeMult().getModifiedValue();
        if (!Float.isFinite(globalTime) || globalTime <= 0 || !Float.isFinite(localTime) || localTime <= 0)
            return refuse("当前时间流状态不允许激活");
        double before = flux.getCurrFlux();
        if (!flux.increaseFlux(ACTIVATION_SOFT_FLUX, false)) return refuse("原生幅能消耗被拒绝");
        activeShip = expectedPlayer; activeSource=source; remaining = DURATION_PLAYER_SECONDS; activations++;
        if ("passive".equals(source)) {passiveActivations++;passiveCooldown=PASSIVE_INTERVAL_PLAYER_SECONDS;}
        fluxSpent += flux.getCurrFlux() - before;
        apply();
        message = "新人类激活"; messageRemaining = 2;
        event("activated", "passive".equals(source)?"来袭弹药概率感应":binding.keyName()+" 或显式玩家动作", Map.of("ship", expectedPlayer.getId(), "source", source, "softFluxAdded", flux.getCurrFlux() - before));
        publish(); return new Activation(true, "已激活");
    }

    @Override public void advance(float amount, List<InputEventAPI> ignoredInputs) {
        if (duplicate || stopped || engine == null) return;
        if (engine.isCombatOver() || Global.getCombatEngine() != engine) { close(); return; }
        try {
            tick(amount);
        } catch (RuntimeException | LinkageError failure) {
            // 单帧异常必须当场整组摘除：控制器一旦抛出，总线会把它永久摘掉，
            // 之后再没有代码替它摘 engine 上那条 1/1.5，整个战场会永久停在 2/3 速。
            LOG.warn(LOG_PREFIX + "新人类控制器单帧异常，已整组释放时间修饰：" + failure);
            clear("单帧异常，已释放时间修饰");
        }
        try {
            publish();
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn(LOG_PREFIX + "新人类遥测写入失败：" + failure);
        }
    }

    private void tick(float amount) {
        ShipAPI player = engine.getPlayerShip();
        if(player!=observedPlayer){observedPlayer=player;passiveScanCountdown=0;}
        if (activeShip != null && (player != activeShip || !activeShip.isAlive() || activeShip.isHulk() || !engine.isEntityInPlay(activeShip)))
            clear("玩家换船、机体失效或离场");
        if (!Float.isFinite(amount) || amount < 0) { clear("无效时间步长"); return; }
        if (!engine.isPaused()) {
            scanCountdown -= amount; sampleCountdown -= amount; messageRemaining = Math.max(0, messageRemaining - amount);
            if (player != null && scanCountdown <= 0) refreshBinding(player);
            float playerStep=player==null?0f:amount*player.getMutableStats().getTimeMult().getModifiedValue();
            if(Float.isFinite(playerStep)&&playerStep>=0)passiveCooldown=Math.max(0f,passiveCooldown-playerStep);
            if (activeShip != null) {
                if (originalActive(activeShip)) clear("原版自动新人类生效，主动能力让位");
                else if ("manual".equals(activeSource) && !binding.available()) clear("无空闲字母键，停止主动能力");
                else {
                    apply();
                    // Native per-frame amount is world time. Ship-local seconds match that ship's own advance clock.
                    float playerAmount = amount * activeShip.getMutableStats().getTimeMult().getModifiedValue();
                    if (!Float.isFinite(playerAmount) || playerAmount < 0) { clear("玩家时间步长无效"); return; }
                    float used = Math.min(remaining, playerAmount); remaining -= used; activePlayerSeconds += used;
                    if (remaining <= 0) clear("10 秒玩家体感时间结束");
                }
            }
            passiveScanCountdown-=amount;
            if(passiveScanCountdown<=0){passiveScanCountdown=PASSIVE_SCAN_INTERVAL;scanPassive(player);}
            if (sampleCountdown <= 0) { sample(); sampleCountdown = .5f; }
        }
        hud(player);
    }

    private String playerBlockReason(ShipAPI player,boolean manual) {
        if (stopped || duplicate || engine == null) return "主动能力尚未就绪";
        if (engine.isCombatOver()) return "战斗已经结束";
        if (player == null || player != engine.getPlayerShip()) return "仅当前玩家驾驶机体可激活";
        if (!player.isAlive() || player.isHulk() || !engine.isEntityInPlay(player)) return "玩家机体不可用";
        if (player.isStationModule()) return "子模块不能独立激活新人类";
        if (engine.isPaused()) return "暂停时不能激活";
        if (manual && (engine.isUIShowingDialog() || engine.getCombatUI() != null && (engine.getCombatUI().isShowingCommandUI() || engine.getCombatUI().isShowingDeploymentDialog()))) return "当前界面未接管新人类字母键";
        Boolean autopilot = actualAutopilotState(engine);
        if (manual && autopilot == null) return "无法核实当前自动驾驶状态，未接管字母键";
        if (manual && (Boolean.TRUE.equals(autopilot) || player.controlsLocked())) return "自动驾驶或控制锁定时不能激活";
        if (player.getFluxTracker().isOverloadedOrVenting()) return "过载或排幅时不能激活";
        return "";
    }

    private void scanPassive(ShipAPI player) {
        threatRolls.prune(key->key instanceof DamagingProjectileAPI p && !p.isExpired() && !p.wasRemoved() && engine.isEntityInPlay(p));
        passiveOriginalDelegate=player!=null && player.getCustomData().containsKey(ORIGINAL_LISTENER);
        // Original Moci Level3 already installs a deterministic threat listener. Never add a
        // second passive scanner beside it; active original modifiers also own precedence.
        if(player==null||activeShip!=null||passiveCooldown>0||passiveOriginalDelegate||!playerBlockReason(player,false).isEmpty()||originalActive(player))return;
        CollisionGridAPI grid=engine.getAllObjectGrid();if(grid==null)return;
        Iterator<Object> candidates=grid.getCheckIterator(player.getLocation(),NewtypeThreatRolls.NEAR_RADIUS*2,NewtypeThreatRolls.NEAR_RADIUS*2);
        int checked=0;
        while(candidates.hasNext()&&checked<MAX_PASSIVE_CANDIDATES){
            Object object=candidates.next();checked++;if(!(object instanceof DamagingProjectileAPI p))continue;
            boolean live=!p.isExpired()&&!p.wasRemoved()&&!p.isFading()&&!p.didDamage()&&engine.isEntityInPlay(p);
            boolean damage=p.getDamageAmount()>0&&p.getCollisionClass()!=CollisionClass.NONE&&p.getSource()!=player&&(p.getWeapon()==null||!p.getWeapon().isDecorative());
            float dx=p.getLocation().x-player.getLocation().x,dy=p.getLocation().y-player.getLocation().y;
            float vx=p.getVelocity().x-player.getVelocity().x,vy=p.getVelocity().y-player.getVelocity().y;
            boolean collision=NewtypeThreatRolls.collisionLikely(dx,dy,vx,vy,player.getCollisionRadius()+p.getCollisionRadius()+18f);
            var decision=threatRolls.consider(p,p.getOwner(),player.getOwner(),live,damage,collision,p instanceof MissileAPI);
            if(decision==NewtypeThreatRolls.Decision.TRIGGER){activate(player,"passive");break;}
        }
        passiveCandidates+=checked;if(candidates.hasNext()&&checked>=MAX_PASSIVE_CANDIDATES)passiveScanTruncations++;
    }

    /** This engine's API isUIAutopilotOn() is inverted; use the actual public native UI flag. */
    public static Boolean actualAutopilotState(CombatEngineAPI engine) {
        if (engine instanceof com.fs.starfarer.combat.CombatEngine nativeEngine && nativeEngine.getCombatUI() != null)
            return nativeEngine.getCombatUI().isAutopilotOn();
        return null;
    }

    private boolean originalActive(ShipAPI player) {
        return player.getMutableStats().getTimeMult().getMultMods().entrySet().stream().anyMatch(e -> e.getKey().startsWith(ORIGINAL_PREFIX) && Math.abs(e.getValue().getValue() - 1) > .0001f)
                || engine.getTimeMult().getMultMods().entrySet().stream().anyMatch(e -> e.getKey().startsWith(ORIGINAL_PREFIX) && Math.abs(e.getValue().getValue() - 1) > .0001f);
    }

    private void apply() {
        Set<ShipAPI> current = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<ShipAPI> queue = new ArrayDeque<>(); queue.add(activeShip);
        // 先只收集、只校验，不写任何修饰符：上限校验必须发生在第一笔 modifyMult 之前，
        // 否则"写了一半再抛"会留下几只已经改了 timeMult、却没登记进 touched 的机体。
        while (!queue.isEmpty()) {
            ShipAPI ship = queue.removeFirst(); if (!current.add(ship)) continue;
            if (current.size() > 128) throw new IllegalStateException("Player module hierarchy exceeds Newtype ownership bound");
            for (ShipAPI child : ship.getChildModulesCopy()) if (child != null) queue.add(child);
        }
        for (ShipAPI ship : current) {
            ship.getMutableStats().getTimeMult().modifyMult(MODIFIER, SHIP_TIME_MULT);
            touched.add(ship);
        }
        // Detached modules keep no owned time effect, even if another Mod changes the module tree.
        // 脱离模块树后不再归本控制器管：当场摘修饰并从归属集合里移除，摘除失败也要移除。
        for (Iterator<ShipAPI> it = touched.iterator(); it.hasNext();) { ShipAPI ship = it.next(); if (!current.contains(ship)) { releaseShipTime(ship, "模块已脱离宿主"); it.remove(); } }
        engine.getTimeMult().modifyMult(MODIFIER, 1f / SHIP_TIME_MULT);
    }

    private Activation refuse(String reason) { declined++; message = reason; messageRemaining = 3; event("refused", reason, Map.of()); publish(); return new Activation(false, reason); }

    private void clear(String reason) {
        boolean wasActive = activeShip != null || !touched.isEmpty();
        // 先取快照再清集合：某个机体摘除失败不能挡住其余机体，也不能留下"集合已空但修饰还在"的状态。
        List<ShipAPI> owned = new ArrayList<>(touched);
        touched.clear();
        for (ShipAPI ship : owned) releaseShipTime(ship, "clear(" + reason + ")");
        if (engine != null) releaseEngineTime("clear(" + reason + ")");
        activeShip = null; activeSource="none"; remaining = 0;
        // 清理自身的收尾（事件+遥测）绝不允许抛出：clear 是异常路径上的兜底，
        // 它要是再抛，调用它的 advance/close 就会被总线摘除，时间修饰就彻底没人管了。
        try {
            if (wasActive) { message = reason; messageRemaining = 3; event("cleared", reason, Map.of()); }
            publish();
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn(LOG_PREFIX + "新人类清理事件写入失败：" + failure);
        }
    }

    private void hud(ShipAPI player) {
        if (player == null || !player.isAlive() || player.isHulk()) return;
        String detail = activeShip != null ? String.format(Locale.ROOT, "%s · 剩余 %.1f 秒", binding.keyName(), remaining)
                : messageRemaining > 0 ? message
                : !binding.available() ? (binding.error().isEmpty() ? "字母键冲突：" + String.join("、", binding.conflicts()) : binding.error())
                : binding.keyName()+" · 300 软幅能 · 持续 10 秒";
        engine.maintainStatusForPlayerShip(INSTANCE_KEY, ICON, "新人类", detail, activeShip == null && !binding.available());
    }

    private void sample() {
        if (samples.size() >= 96) return;
        ShipAPI player = engine.getPlayerShip();
        Map<String, Object> row = new LinkedHashMap<>(); row.put("engineTime", engine.getTotalElapsedTime(false)); row.put("active", activeShip != null);
        row.put("remainingPlayerSeconds", remaining); row.put("globalTimeMult", engine.getTimeMult().getModifiedValue());
        row.put("player", player == null ? "NONE" : player.getId()); row.put("playerTimeMult", player == null ? 1 : player.getMutableStats().getTimeMult().getModifiedValue());
        row.put("ownedModuleCount", touched.size()); samples.add(row);
    }

    private void event(String type, String reason, Map<String, Object> extra) {
        if (events.size() >= 96) { droppedEvents++; return; }
        Map<String, Object> row = new LinkedHashMap<>(); row.put("type", type); row.put("reason", reason);
        row.put("engineTime", engine == null ? 0 : engine.getTotalElapsedTime(false)); row.putAll(extra); events.add(row);
    }
    private void publish() {
        if (engine == null || duplicate) return;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("schema", 1); state.put("installed", !stopped); state.put("active", activeShip != null); state.put("remainingPlayerSeconds", remaining);
        state.put("activationCount", activations); state.put("declined", declined); state.put("actualFluxSpent", fluxSpent);
        state.put("activePlayerSeconds", activePlayerSeconds); state.put("key", binding.keyName()); state.put("keyCode",binding.keyCode());state.put("preferredKey","B"); state.put("keyInspectionCompleted", binding.inspected());
        state.put("activeSource",activeSource);state.put("passiveActivationCount",passiveActivations);state.put("passiveCooldownPlayerSeconds",passiveCooldown);
        state.put("passiveOriginalListenerOwnsThreats",passiveOriginalDelegate);state.put("passiveRolls",threatRolls.rolls());state.put("passiveRollTriggers",threatRolls.triggers());
        state.put("passiveTrackedAttacks",threatRolls.tracked());state.put("passiveCapacitySkips",threatRolls.capacitySkips());state.put("passiveCandidates",passiveCandidates);state.put("passiveScanTruncations",passiveScanTruncations);
        state.put("passiveMissileChance",NewtypeThreatRolls.MISSILE_CHANCE);state.put("passiveProjectileChance",NewtypeThreatRolls.PROJECTILE_CHANCE);state.put("passiveScanSeconds",PASSIVE_SCAN_INTERVAL);
        state.put("keyConflicts", binding.conflicts()); state.put("keyInspectionError", binding.error()); state.put("message", message);
        state.put("ownShipTimeMult", SHIP_TIME_MULT); state.put("ownEngineTimeMult", 1f / SHIP_TIME_MULT);
        state.put("modifierId", MODIFIER); state.put("activeShip", activeShip == null ? "NONE" : activeShip.getId());
        state.put("timeReleaseFailures", timeReleaseFailures); state.put("timeLastReleaseFailure", lastTimeReleaseFailure);
        state.put("actualNativeAutopilot", actualAutopilotState(engine));
        state.put("events", events); state.put("samples", samples); state.put("droppedEvents", droppedEvents);
        state.put("durationClock", "10 non-paused player-local seconds: native engine callback amount multiplied by current player ship timeMult; no wall-clock timer, movement stat increase or extra cooldown.");
        state.put("bindingScope", "Stable available letter, initially B. Native GENERAL/FLIGHT and unknown unconfigurable actions, MagicLib global/ship keys and explicit reservations are retained; no F-number or numeric fallback. Undeclared direct keyboard polling cannot be discovered automatically.");
        engine.getCustomData().put(TELEMETRY_KEY, state);
    }

    private void releaseShipTime(ShipAPI ship, String what) {
        if (ship == null) return;
        try {
            ship.getMutableStats().getTimeMult().unmodify(MODIFIER);
        } catch (RuntimeException | LinkageError failure) {
            noteReleaseFailure(what + " 机体 " + ship.getId(), failure);
        }
    }

    private void releaseEngineTime(String what) {
        if (engine == null) return;
        try {
            engine.getTimeMult().unmodify(MODIFIER);
        } catch (RuntimeException | LinkageError failure) {
            noteReleaseFailure(what + " 引擎", failure);
        }
    }

    private void noteReleaseFailure(String what, Throwable failure) {
        timeReleaseFailures++;
        lastTimeReleaseFailure = what + " -> " + failure;
        LOG.warn(LOG_PREFIX + "新人类时间修饰摘除失败 " + lastTimeReleaseFailure);
    }

    /** Owner-thread stop hook. Call at framework/mission teardown if it exits before isCombatOver is observed. */
    @Override public void close() {
        if (stopped || duplicate) return;
        // 先置 stopped 再拆解：即使后续某一步抛错，close 也不会变成可重入的半关闭状态。
        stopped = true;
        try {
            clear("新人类能力已停止");
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn(LOG_PREFIX + "新人类停止时释放时间失败：" + failure);
        } finally {
            heldKeys.clear();threatRolls.clear();observedPlayer=null;
            try {
                if (engine != null && engine.getCustomData().get(INSTANCE_KEY) == this) engine.getCustomData().remove(INSTANCE_KEY);
            } catch (RuntimeException | LinkageError failure) {
                LOG.warn(LOG_PREFIX + "新人类控制器键清理失败：" + failure);
            }
            try {
                data.scripts.combat.Moci_CombatBus.unregister(this);
            } catch (RuntimeException | LinkageError failure) {
                LOG.warn(LOG_PREFIX + "新人类控制器总线注销失败：" + failure);
            }
            try {
                publish();
            } catch (RuntimeException | LinkageError failure) {
                LOG.warn(LOG_PREFIX + "新人类停止遥测写入失败：" + failure);
            }
        }
    }
    public static void uninstall(CombatEngineAPI engine) {
        Object value = engine.getCustomData().get(INSTANCE_KEY); if (value instanceof OwnNewtypeController controller) controller.close();
    }
}
