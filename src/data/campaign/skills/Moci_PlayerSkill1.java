package data.campaign.skills;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.ShipSkillEffect;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import data.scripts.util.Moci_TextLoader;
import data.shipsystems.scripts.Moci_MissileJammerSystemScript;
import org.apache.log4j.Logger;

public class Moci_PlayerSkill1 {
    private static final Logger LOG = Global.getLogger(Moci_PlayerSkill1.class);
    private static final String LOG_PREFIX = "[MOCI-TIME] ";

    private static final String TEXT_ID = "Moci_PlayerSkill1";
    private static final String AUGMENTED_HUMAN_SKILL_ID = "Moci_AugmentedHumanSkill";

    public static class Level1 implements ShipSkillEffect {

        @Override
        public void apply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id, float level) {
            if (stats.getEntity() instanceof ShipAPI) {
                ShipAPI ship = (ShipAPI) stats.getEntity();
                ship.addTag(Moci_MissileJammerSystemScript.playerSkillBanTag);
            }
        }

        @Override
        public void unapply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id) {
            if (stats.getEntity() instanceof ShipAPI) {
                ShipAPI ship = (ShipAPI) stats.getEntity();
                if (!hasJammerImmunitySkill(ship)) {
                    ship.getTags().remove(Moci_MissileJammerSystemScript.playerSkillBanTag);
                }
            }
        }

        @Override
        public String getEffectDescription(float level) {
            return Moci_TextLoader.getText(TEXT_ID, "description.level1");
        }

        @Override
        public String getEffectPerLevelDescription() {
            return null;
        }

        @Override
        public ScopeDescription getScopeDescription() {
            return ScopeDescription.PILOTED_SHIP;
        }
    }

    private static boolean hasJammerImmunitySkill(ShipAPI ship) {
        return ship != null
                && ship.getCaptain() != null
                && ship.getCaptain().getStats() != null
                && (ship.getCaptain().getStats().getSkillLevel(TEXT_ID) > 0f
                || ship.getCaptain().getStats().getSkillLevel(AUGMENTED_HUMAN_SKILL_ID) > 0f);
    }

    public static class Level2 implements ShipSkillEffect {

        @Override
        public void apply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id, float level) {
            stats.getEccmChance().modifyFlat(id, 1);
        }

        @Override
        public void unapply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id) {
            stats.getEccmChance().unmodify(id);
        }

        @Override
        public String getEffectDescription(float level) {
            return Moci_TextLoader.getText(TEXT_ID, "description.level2");
        }

        @Override
        public String getEffectPerLevelDescription() {
            return null;
        }

        @Override
        public ScopeDescription getScopeDescription() {
            return ScopeDescription.PILOTED_SHIP;
        }
    }

    /**
     * 新人类主动能力：亲自驾驶时把 {@link data.subsystems.Moci_NewtypeSubsystem} 挂到座舰上。
     *
     * <p>原先这里还有「附近有敌舰 / 高威胁弹药时自动触发时流」的被动效果，已按设计移除，只保留手动主动能力。</p>
     */
    public static class Level3 implements ShipSkillEffect {

        @Override
        public void apply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id, float level) {
            if (stats.getEntity() instanceof ShipAPI) {
                ShipAPI ship = (ShipAPI) stats.getEntity();
                if (ship.isShuttlePod() || listener.isChildModule(ship)) {
                    listener.removeInstance(ship);
                    return;
                }
                listener.getInstance(ship);
            }
        }

        @Override
        public void unapply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id) {
            if (stats.getEntity() instanceof ShipAPI) {
                ShipAPI ship = (ShipAPI) stats.getEntity();
                listener.removeInstance(ship);
            }
        }

        @Override
        public String getEffectDescription(float level) {
            return Moci_TextLoader.getText(TEXT_ID, "description.level3");
        }

        @Override
        public String getEffectPerLevelDescription() {
            return null;
        }

        @Override
        public ScopeDescription getScopeDescription() {
            return ScopeDescription.PILOTED_SHIP;
        }
    }

    /** 只负责在玩家亲自驾驶时挂载新人类手动子系统。 */
    private static class listener implements AdvanceableListener {
        private static final String LISTENER_DATA_KEY = "MociPlayerSkill1Listener";

        final ShipAPI ship;
        /** 新人类手动子系统是否已尝试挂到本舰（成功失败都只试一次，避免每帧重试刷日志）。 */
        private boolean newtypeAttached;

        public static listener getInstance(ShipAPI ship) {
            Object cached = ship.getCustomData().get(LISTENER_DATA_KEY);
            if (cached instanceof listener && ship.hasListener(cached)) return (listener) cached;
            removeInstance(ship);
            listener newListener = new listener(ship);
            ship.getCustomData().put(LISTENER_DATA_KEY, newListener);
            ship.addListener(newListener);
            return newListener;
        }

        public static void removeInstance(ShipAPI ship) {
            if (ship == null) {
                return;
            }
            Object listenerObj = ship.getCustomData().remove(LISTENER_DATA_KEY);
            if (listenerObj instanceof listener) {
                ship.removeListener((listener) listenerObj);
            }
            ship.removeListenerOfClass(listener.class);
        }

        public static boolean isChildModule(ShipAPI ship) {
            if (ship == null) {
                return false;
            }
            ShipAPI parent = ship.getParentStation();
            return ship.isStationModule() || (parent != null && parent != ship);
        }

        private listener(ShipAPI ship) {
            this.ship = ship;
        }

        @Override
        public void advance(float amount) {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null || engine.isPaused()) {
                return;
            }
            if (!ship.isAlive() || ship.isHulk() || ship.wasRemoved() || !engine.isEntityInPlay(ship)) {
                ship.getCustomData().remove(LISTENER_DATA_KEY);
                ship.removeListener(this);
                return;
            }
            if (newtypeAttached || ship.isShuttlePod() || isChildModule(ship)) return;

            // 新人类手动主动能力：MagicLib 子系统，挂在玩家当前座舰上。
            // 按键默认由 MagicLib 分配，玩家可在 LunaLib「新人类主动键」里改。
            if (engine.getPlayerShip() == ship) {
                newtypeAttached = true;
                try {
                    data.subsystems.Moci_NewtypeSubsystem.attach(ship);
                } catch (RuntimeException | LinkageError e) {
                    LOG.warn(LOG_PREFIX + "新人类子系统挂载失败 ship=" + ship.getId(), e);
                }
            }
        }
    }
}
