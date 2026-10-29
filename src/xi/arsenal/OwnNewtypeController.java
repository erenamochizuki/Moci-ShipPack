package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.input.InputEventAPI;
import java.util.*;
import java.util.function.DoubleSupplier;
import data.scripts.combat.Moci_TimeFlow;
import org.apache.log4j.Logger;
import org.lwjgl.input.Keyboard;

/** One player-only active/passive Newtype owner; real time multipliers, never movement bonuses. */
public final class OwnNewtypeController implements AutoCloseable, data.scripts.combat.Moci_CombatSink {
    public static final String INSTANCE_KEY = "xi.arsenal.newtype.controller";
    public static final String TELEMETRY_KEY = "xi.arsenal.newtype";
    /**
     * 迁移前的旧修饰符 id，现在只用于「识别历史残留」。
     * 时间倍率已统一写进 {@code Moci_TimeFlow.MODIFIER_ID}（全模组唯一权威），
     * 本类不再往属性表里写任何自己的 id。
     */
    public static final String MODIFIER = "xi.arsenal.own_newtype.time";
    public static final float DURATION_PLAYER_SECONDS = 10f, ACTIVATION_SOFT_FLUX = 300f, SHIP_TIME_MULT = 1.5f;
    public static final float PASSIVE_INTERVAL_PLAYER_SECONDS = 20f;
    /** 交给 Moci_TimeFlow 的申请名；模块侧与世界侧各一份申请，作用域由句柄自己保证。 */
    private static final String TIME_HANDLE_ID = "OwnNewtypeController";
    private static final float PASSIVE_SCAN_INTERVAL = .12f;
    private static final int MAX_PASSIVE_CANDIDATES = 2048;
    private static final String ORIGINAL_PREFIX = "MociPlayerSkill1_";
    private static final String ORIGINAL_LISTENER = "MociPlayerSkill1Listener";
    private static final String ICON = "graphics/icons/hullsys/moci_skill1.png";
    /** 时间泄漏排查开关：常驻日志前缀，由项目内常量控制，不绑 devMode。 */
    public static final boolean TRACE = true;
    private static final String LOG_PREFIX = "[MOCI-TIME] ";
    private static final Logger LOG = Global.getLogger(OwnNewtypeController.class);
    /**
     * 每个被加速的舰/模块 → 它自己的时间申请。
     * 用 IdentityHashMap 保持原来的「按对象身份匹配」语义（模块是同一份对象引用，不能被 equals 合并）。
     * 迁移后这里不再存「我改过谁」，而是「我向权威申请了什么」——句柄随舰船离场自动作废。
     */
    private final Map<ShipAPI, Moci_TimeFlow.Handle> moduleTime = new IdentityHashMap<>();
    /** 世界侧 1/1.5 的申请；玩家模块树整体加速时才存在，成对配平模块侧。 */
    private Moci_TimeFlow.Handle worldTime;
    /** 权威不可用（被暂停/换场/注册失败）的告警只打一次，避免每帧刷屏。 */
    private boolean timeAuthorityLost;
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
    private long activations, declined, droppedEvents, passiveActivations, passiveCandidates, passiveScanTruncations, releaseFailures, selfAudits;
    private String lastReleaseFailure = "";
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
        // 无引擎外资源：close() 只撤销时间修饰、清 INSTANCE_KEY 并在总线上注销，
        // 全部是引擎内状态；结束链路由本类 advance() 的自审 + Moci_CombatBus.closeAll() 覆盖。
        // 注册失败必须当场收场：否则本实例永远不会被 tick（没有任何自愈入口）却已经占了 INSTANCE_KEY。
        try {
            data.scripts.combat.Moci_CombatBus.register(this);
        } catch (RuntimeException | LinkageError failure) {
            close();
            throw failure;
        }
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
        long failuresBefore = releaseFailures;
        try {
            apply();
        } catch (RuntimeException | LinkageError failure) {
            // 激活失败绝不能把半套修饰符留在战场上：当场收干净再拒绝。
            clear("激活时应用时间修饰失败：" + failure);
            return refuse("新人类激活失败：" + failure);
        }
        // apply() 内部逐项 releaseQuietly，单步失败不再向外抛；这里把「有失败」翻译成「激活失败」，
        // 免得半套修饰符留在战场上还对外宣称激活成功。
        if (releaseFailures != failuresBefore) {
            String why = lastReleaseFailure;
            clear("激活时应用时间修饰失败：" + why);
            return refuse("新人类激活失败：" + why);
        }
        message = "新人类激活"; messageRemaining = 2;
        event("activated", "passive".equals(source)?"来袭弹药概率感应":binding.keyName()+" 或显式玩家动作", Map.of("ship", expectedPlayer.getId(), "source", source, "softFluxAdded", flux.getCurrFlux() - before));
        publish(); return new Activation(true, "已激活");
    }

    /**
     * 每帧入口：先自审存活条件，再把帧体包进 try/catch。
     * 原实现的任何一次抛错都会逃回 {@code Moci_CombatBus}，被总线永久摘除（既不再 tick 也不会 close），
     * 于是 {@code engine.getTimeMult()} 上的 1/1.5 永远留在这场战斗里 = 世界永久 2/3 速。
     */
    @Override public void advance(float amount, List<InputEventAPI> ignoredInputs) {
        if (duplicate || stopped || engine == null) return;
        String invalid = invalidReason();
        if (!invalid.isEmpty()) { selfClose(invalid); return; }
        try {
            tick(amount);
        } catch (RuntimeException | LinkageError failure) {
            selfClose("advance failed: " + failure);
            return;
        }
        selfAudit();
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
        hud(player); publish();
    }

    /** 存活条件：战斗换了/结束、或本实例已不再是这一场的时间所有者键 → 必须自愈收场。 */
    private String invalidReason() {
        if (stopped) return "controller already stopped";
        if (engine == null) return "controller has no battle engine";
        CombatEngineAPI current = Global.getCombatEngine();
        if (current != engine) return "newtype battle ended or was replaced";
        if (engine.isCombatOver()) return "newtype battle is over";
        Object owner = engine.getCustomData().get(INSTANCE_KEY);
        if (owner != this) return "newtype controller key is no longer ours";
        return "";
    }

    /**
     * 自审：**未激活**时引擎上不该还挂着本类的时间残留。
     * 这一步兜住 close()/clear() 的极端遗漏（权威换场、外部误写、旧版本残留）——
     * 只要本类还能被 tick，残留一定会在下一帧被摘掉。
     *
     * <p>必须显式判 {@code activeShip == null}：激活期间本类的申请本来就在场上，
     * 把「有申请」当成残留会让自审每帧清一次场，能力等于永远不生效。</p>
     */
    private void selfAudit() {
        if (activeShip != null) return; // 激活期间有申请是正常状态，不是残留
        boolean dirty = !moduleTime.isEmpty() || worldTime != null;
        if (!dirty) {
            try {
                Map<String, ?> modifiers = engine.getTimeMult().getMultMods();
                // MODIFIER 是迁移前的旧 id：本类已不再写它，出现即历史残留/外部误写，照样清一次。
                // 不检查 Moci_TimeFlow.MODIFIER_ID：那个 id 是全模组共用的，别的系统合法持有它不算本类的残留。
                dirty = modifiers != null && modifiers.containsKey(MODIFIER);
            } catch (RuntimeException | LinkageError failure) {
                recordFailure("self audit read", failure);
            }
        }
        if (dirty) {
            selfAudits++;
            clear("self audit: newtype time modifier left behind while inactive");
        }
    }

    /** 自愈收场：只打一行日志，close() 本身绝对幂等且不抛。 */
    private void selfClose(String why) {
        boolean wasActive = activeShip != null;
        close();
        if (TRACE) {
            LOG.warn(LOG_PREFIX + "新人类控制器自愈收场 modifierId=" + Moci_TimeFlow.MODIFIER_ID + " 原因=" + why
                    + " 当时激活=" + wasActive + " 释放失败=" + releaseFailures
                    + (lastReleaseFailure.isEmpty() ? "" : " 最后失败=" + lastReleaseFailure));
        }
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

    /**
     * 应用时间修饰。
     * ★ 原实现在遍历的同时就 modifyMult，而模块树上限异常是在**已经改了前面若干模块之后**才抛，
     * 留下一套半改状态（部分模块 1.5×、引擎未压速）。现在先只收集+校验，全部通过后才第一次改属性。
     */
    /**
     * 应用时间修饰。
     *
     * <p>★ 原实现在遍历的同时就 modifyMult，而模块树上限异常是在**已经改了前面若干模块之后**才抛，
     * 留下一套半改状态（部分模块 1.5×、引擎未压速）。现在先只收集+校验，全部通过后才第一次申请时间。</p>
     *
     * <p>迁移要点：每个模块各自向 {@link Moci_TimeFlow} 申请一份船侧倍率（{@code SHIP_TIME_MULT}=1.5），
     * 世界侧另外申请一份 {@code 1/1.5}。句柄一旦建立就自带「失效即作废」语义（模块离场由权威收走），
     * 本类不再需要「我改过谁 → 我该摘谁」这本账。</p>
     */
    private void apply() {
        ShipAPI root = activeShip;
        if (root == null) return;
        Set<ShipAPI> current = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<ShipAPI> queue = new ArrayDeque<>(); queue.add(root);
        while (!queue.isEmpty()) {
            ShipAPI ship = queue.removeFirst(); if (!current.add(ship)) continue;
            if (current.size() > 128) throw new IllegalStateException("Player module hierarchy exceeds Newtype ownership bound");
            for (ShipAPI child : ship.getChildModulesCopy()) if (child != null) queue.add(child);
        }
        // —— 校验已全部通过，从这里开始才允许申请/改动时间流 ——
        for (ShipAPI module : current) {
            Moci_TimeFlow.Handle handle = moduleTime.get(module);
            if (handle != null && handle.active()) {
                handle.set(SHIP_TIME_MULT); // 续期：同一模块永远只有一份申请，倍率不会叠加
            } else {
                if (handle != null) releaseHandle(handle, "reopen " + module.getId()); // 旧申请已失效，收掉再开
                Moci_TimeFlow.Handle opened = Moci_TimeFlow.openShip(module, TIME_HANDLE_ID, SHIP_TIME_MULT);
                moduleTime.put(module, opened);
                noteAuthority(module.getId(), opened);
            }
        }
        // Detached modules keep no owned time effect, even if another Mod changes the module tree.
        for (Iterator<Map.Entry<ShipAPI, Moci_TimeFlow.Handle>> it = moduleTime.entrySet().iterator(); it.hasNext();) {
            Map.Entry<ShipAPI, Moci_TimeFlow.Handle> entry = it.next();
            if (current.contains(entry.getKey())) continue;
            releaseHandle(entry.getValue(), "drop detached " + entry.getKey().getId());
            it.remove();
        }
        // 世界侧 1/1.5：玩家模块树整体加速的补偿，与模块侧配平
        worldTime = ensureWorldTime(worldTime, 1f / SHIP_TIME_MULT);
    }

    /** 世界侧申请：仍然有效就 set 续期，否则重开一份。返回可继续使用的句柄。 */
    private Moci_TimeFlow.Handle ensureWorldTime(Moci_TimeFlow.Handle handle, float factor) {
        if (handle != null && handle.active()) {
            handle.set(factor);
            return handle;
        }
        if (handle != null) releaseHandle(handle, "world reopen");
        Moci_TimeFlow.Handle opened = Moci_TimeFlow.openWorld(TIME_HANDLE_ID, factor);
        noteAuthority("world", opened);
        return opened;
    }

    /**
     * 权威不可用时的告警：只报一次，避免每帧刷屏。
     * 激活路径会比较 releaseFailures 前后差值——第一次报错就足以把激活判为失败并当场收场。
     */
    private void noteAuthority(String what, Moci_TimeFlow.Handle handle) {
        if (handle != null && handle.active()) { timeAuthorityLost = false; return; }
        if (timeAuthorityLost) return;
        timeAuthorityLost = true;
        recordFailure("时间流权威不可用 " + what, new IllegalStateException("Moci_TimeFlow.ensure() returned no authority"));
    }

    /** 收回一份时间申请：句柄 release 幂等且自带两半语义（船侧 + 世界侧），这里只补一行排查日志。 */
    private void releaseHandle(Moci_TimeFlow.Handle handle, String why) {
        if (handle == null) return;
        boolean wasActive = handle.active();
        try {
            handle.release();
        } catch (RuntimeException | LinkageError failure) {
            recordFailure("release " + why, failure);
        }
        if (TRACE && wasActive) LOG.info(LOG_PREFIX + "新人类收回时间申请 id=" + TIME_HANDLE_ID + " 原因=" + why);
    }

    private Activation refuse(String reason) { declined++; message = reason; messageRemaining = 3; event("refused", reason, Map.of()); publish(); return new Activation(false, reason); }

    /**
     * 幂等释放：把所有模块侧与世界侧的时间申请一次收回。
     * 原实现逐项 unmodify，一条异常就能把 1/1.5 留在引擎上；现在每份申请都是一个句柄，
     * 收句柄不可能留下半套状态。
     */
    private void clear(String reason) {
        boolean wasActive = activeShip != null || !moduleTime.isEmpty() || worldTime != null;
        for (Map.Entry<ShipAPI, Moci_TimeFlow.Handle> entry : moduleTime.entrySet()) {
            releaseHandle(entry.getValue(), "clear module " + entry.getKey().getId());
        }
        moduleTime.clear();
        Moci_TimeFlow.Handle world = worldTime;
        worldTime = null;
        releaseHandle(world, "clear world time");
        timeAuthorityLost = false;
        activeShip = null; activeSource="none"; remaining = 0;
        if (wasActive) { message = reason; messageRemaining = 3; event("cleared", reason, Map.of()); }
        publish();
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
        row.put("ownedModuleCount", moduleTime.size()); samples.add(row);
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
        state.put("modifierId", Moci_TimeFlow.MODIFIER_ID); state.put("legacyModifierId", MODIFIER); state.put("activeShip", activeShip == null ? "NONE" : activeShip.getId());
        state.put("actualNativeAutopilot", actualAutopilotState(engine));
        state.put("timeReleaseFailures", releaseFailures); state.put("timeLastReleaseFailure", lastReleaseFailure); state.put("timeSelfAuditCleanups", selfAudits);
        state.put("events", events); state.put("samples", samples); state.put("droppedEvents", droppedEvents);
        state.put("durationClock", "10 non-paused player-local seconds: native engine callback amount multiplied by current player ship timeMult; no wall-clock timer, movement stat increase or extra cooldown.");
        state.put("bindingScope", "Stable available letter, initially B. Native GENERAL/FLIGHT and unknown unconfigurable actions, MagicLib global/ship keys and explicit reservations are retained; no F-number or numeric fallback. Undeclared direct keyboard polling cannot be discovered automatically.");
        engine.getCustomData().put(TELEMETRY_KEY, state);
    }

    /** Owner-thread stop hook. Call at framework/mission teardown if it exits before isCombatOver is observed. */
    @Override public void close() {
        if (duplicate || stopped) return;
        // ★ 先置位再做任何事：close() 必须绝对幂等（原实现要先 clear 成功才置 stopped，
        // clear 一抛就会变成「永远关不掉、又不再被 tick」的僵尸，修饰符留在战场上）。
        stopped = true;
        try {
            clear("新人类能力已停止");
        } catch (RuntimeException | LinkageError failure) {
            recordFailure("clear", failure);
        }
        releaseQuietly(() -> heldKeys.clear(), "held keys");
        releaseQuietly(() -> threatRolls.clear(), "threat rolls");
        observedPlayer = null;
        CombatEngineAPI battle = engine;
        if (battle != null) {
            releaseQuietly(() -> {
                if (battle.getCustomData().get(INSTANCE_KEY) == this) battle.getCustomData().remove(INSTANCE_KEY);
            }, "instance key");
        }
        releaseQuietly(() -> data.scripts.combat.Moci_CombatBus.unregister(this), "bus unregister");
        releaseQuietly(this::publish, "publish");
        if (TRACE && releaseFailures > 0) {
            LOG.warn(LOG_PREFIX + "新人类控制器收场有释放失败 次数=" + releaseFailures + " 最后失败=" + lastReleaseFailure);
        }
    }
    public static void uninstall(CombatEngineAPI engine) {
        Object value = engine.getCustomData().get(INSTANCE_KEY); if (value instanceof OwnNewtypeController controller) controller.close();
    }

    /** 单步释放：一步失败只记一条日志，不影响后续步骤，绝不向外抛。 */
    private void releaseQuietly(Runnable step, String what) {
        try {
            step.run();
        } catch (RuntimeException | LinkageError failure) {
            recordFailure(what, failure);
        }
    }

    private void recordFailure(String what, Throwable failure) {
        releaseFailures++;
        lastReleaseFailure = what + " -> " + failure;
        LOG.warn(LOG_PREFIX + "新人类控制器释放失败 " + what + " -> " + failure);
    }
}
