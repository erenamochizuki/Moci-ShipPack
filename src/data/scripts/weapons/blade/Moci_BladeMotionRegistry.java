package data.scripts.weapons.blade;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 光剑动作层注册表：把「某个武器实例」和「为它编排动作的外部实现」关联起来。
 *
 * 生命周期约定：动作层在构造时 {@link #register}、在关闭时 {@link #unregister}。
 * 我方武器插件每帧 {@link #get} 一次，未注册时返回 null，行为与改造前一致。
 *
 * 隔离约定：注册进来的一律先套一层 {@link Guarded}。动作层抛出的任何异常都在外壳里被吞掉并
 * 就地注销，本帧降级为「无动作层」，绝不把外部实现的异常穿透到我方武器回调、再穿透到战斗引擎。
 */
public final class Moci_BladeMotionRegistry {

    private static final Map<WeaponAPI, Moci_BladeMotion> MOTIONS =
            Collections.synchronizedMap(new IdentityHashMap<WeaponAPI, Moci_BladeMotion>());

    private Moci_BladeMotionRegistry() {
    }

    public static void register(WeaponAPI weapon, Moci_BladeMotion motion) {
        if (weapon == null || motion == null) {
            return;
        }
        MOTIONS.put(weapon, motion instanceof Guarded ? motion : new Guarded(weapon, motion));
    }

    public static void unregister(WeaponAPI weapon, Moci_BladeMotion motion) {
        if (weapon == null) {
            return;
        }
        Moci_BladeMotion current = MOTIONS.get(weapon);
        if (current == null) {
            return;
        }
        if (motion == null || current == motion
                || (current instanceof Guarded && ((Guarded) current).delegate == motion)) {
            MOTIONS.remove(weapon);
        }
    }

    /**
     * 取该武器的动作层。武器或舰船已失效时顺手清理，避免残留引用。
     */
    public static Moci_BladeMotion get(WeaponAPI weapon) {
        if (weapon == null) {
            return null;
        }
        Moci_BladeMotion motion = MOTIONS.get(weapon);
        if (motion == null) {
            return null;
        }
        if (weapon.getShip() == null || !weapon.getShip().isAlive() || weapon.getShip().wasRemoved()) {
            MOTIONS.remove(weapon);
            return null;
        }
        return motion;
    }

    /** 战斗结束或异常降级时清空，防止静态表跨场累积已失效的武器实例。 */
    public static void clear() {
        MOTIONS.clear();
    }

    /**
     * 异常隔离外壳：任一方法抛错即注销自己，本帧及之后都退回「无动作层」的原始行为。
     * 只吞掉外部实现的问题，不改变我方任何语义。
     */
    private static final class Guarded implements Moci_BladeMotion {

        private final WeaponAPI weapon;
        private final Moci_BladeMotion delegate;
        private boolean failed;

        private Guarded(WeaponAPI weapon, Moci_BladeMotion delegate) {
            this.weapon = weapon;
            this.delegate = delegate;
        }

        private boolean usable() {
            return !failed;
        }

        private void fail(Throwable failure) {
            if (failed) {
                return;
            }
            failed = true;
            MOTIONS.remove(weapon);
            String slot = weapon.getSlot() == null ? "?" : weapon.getSlot().getId();
            Global.getLogger(Moci_BladeMotionRegistry.class).warn(
                    "Blade motion detached for weapon slot " + slot + ": " + failure);
        }

        private void onFailure(Throwable failure) {
            if (failure instanceof VirtualMachineError && !(failure instanceof StackOverflowError)) {
                throw (VirtualMachineError) failure;
            }
            fail(failure);
        }

        @Override
        public void advance(float amount, CombatEngineAPI engine, WeaponAPI target) {
            if (!usable()) {
                return;
            }
            try {
                delegate.advance(amount, engine, target);
            } catch (Throwable failure) {
                onFailure(failure);
            }
        }

        @Override
        public void beamFrame(CombatEngineAPI engine, BeamAPI beam) {
            if (!usable()) {
                return;
            }
            try {
                delegate.beamFrame(engine, beam);
            } catch (Throwable failure) {
                onFailure(failure);
            }
        }

        @Override
        public void lateReassert(WeaponAPI target) {
            if (!usable()) {
                return;
            }
            try {
                delegate.lateReassert(target);
            } catch (Throwable failure) {
                onFailure(failure);
            }
        }

        @Override
        public boolean ownsAim(WeaponAPI target) {
            if (!usable()) {
                return false;
            }
            try {
                return delegate.ownsAim(target);
            } catch (Throwable failure) {
                onFailure(failure);
                return false;
            }
        }

        @Override
        public boolean isActionActive(WeaponAPI target) {
            if (!usable()) {
                return false;
            }
            try {
                return delegate.isActionActive(target);
            } catch (Throwable failure) {
                onFailure(failure);
                return false;
            }
        }

        @Override
        public float damageBudgetFraction(WeaponAPI target) {
            if (!usable()) {
                return 0f;
            }
            try {
                return delegate.damageBudgetFraction(target);
            } catch (Throwable failure) {
                onFailure(failure);
                return 0f;
            }
        }

        @Override
        public int rayPhase(WeaponAPI target) {
            if (!usable()) {
                return 0;
            }
            try {
                return delegate.rayPhase(target);
            } catch (Throwable failure) {
                onFailure(failure);
                return 0;
            }
        }

        @Override
        public float collisionProgress(WeaponAPI target) {
            if (!usable()) {
                return 0f;
            }
            try {
                return delegate.collisionProgress(target);
            } catch (Throwable failure) {
                onFailure(failure);
                return 0f;
            }
        }
    }
}
