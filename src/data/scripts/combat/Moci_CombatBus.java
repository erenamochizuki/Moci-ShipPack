package data.scripts.combat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 唯一战斗转发器背后的注册表。
 *
 * <p>功能自己注册，转发器只负责把每帧与原始输入送到这里。所有调用都做异常隔离：
 * 单个功能抛错只摘掉它自己并记日志，不再穿透到引擎、也不会连带其它功能。</p>
 */
public final class Moci_CombatBus {

    private static final Logger LOG = Global.getLogger(Moci_CombatBus.class);
    private static final List<Moci_CombatSink> SINKS = new ArrayList<>();

    private static CombatEngineAPI engine;

    private Moci_CombatBus() {
    }

    /** 新一场战斗开始：换引擎并清空上一场的所有注册（静态表不跨场残留）。 */
    public static synchronized void begin(CombatEngineAPI newEngine) {
        engine = newEngine;
        SINKS.clear();
    }

    public static synchronized boolean owns(CombatEngineAPI current) {
        return engine != null && engine == current;
    }

    public static synchronized void register(Moci_CombatSink sink) {
        if (sink != null && !SINKS.contains(sink)) SINKS.add(sink);
    }

    public static synchronized void unregister(Moci_CombatSink sink) {
        SINKS.remove(sink);
    }

    public static void advanceAll(float amount, List<InputEventAPI> events) {
        for (Moci_CombatSink sink : snapshot()) {
            try {
                sink.advance(amount, events);
            } catch (RuntimeException | LinkageError failure) {
                detach(sink, "advance", failure);
            }
        }
    }

    public static void processInputAll(float amount, List<InputEventAPI> events) {
        for (Moci_CombatSink sink : snapshot()) {
            try {
                sink.processInput(amount, events);
            } catch (RuntimeException | LinkageError failure) {
                detach(sink, "input", failure);
            }
        }
    }

    public static void renderWorldAll(ViewportAPI viewport) {
        for (Moci_CombatSink sink : snapshot()) {
            try {
                sink.renderInWorldCoords(viewport);
            } catch (RuntimeException | LinkageError failure) {
                detach(sink, "render", failure);
            }
        }
    }

    public static void renderUiAll(ViewportAPI viewport) {
        for (Moci_CombatSink sink : snapshot()) {
            try {
                sink.renderInUICoords(viewport);
            } catch (RuntimeException | LinkageError failure) {
                detach(sink, "render", failure);
            }
        }
    }

    public static void closeAll() {
        for (Moci_CombatSink sink : snapshot()) {
            try {
                sink.close();
            } catch (RuntimeException | LinkageError failure) {
                detach(sink, "close", failure);
            }
        }
        synchronized (Moci_CombatBus.class) {
            SINKS.clear();
            engine = null;
        }
    }

    private static synchronized List<Moci_CombatSink> snapshot() {
        return new ArrayList<>(SINKS);
    }

    private static void detach(Moci_CombatSink sink, String phase, Throwable failure) {
        synchronized (Moci_CombatBus.class) {
            SINKS.remove(sink);
        }
        LOG.warn("Combat sink detached after " + phase + " failure: " + sink.getClass().getName(), failure);
    }
}
