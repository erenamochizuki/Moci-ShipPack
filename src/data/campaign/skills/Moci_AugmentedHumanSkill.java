package data.campaign.skills;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.ShipSkillEffect;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import data.scripts.util.Moci_TextLoader;
import data.shipsystems.scripts.Moci_MissileJammerSystemScript;
import data.subsystems.Moci_AugmentedHumanSubsystem;
import data.subsystems.Moci_NewtypeSubsystem;
import org.apache.log4j.Logger;

import java.util.Locale;

public class Moci_AugmentedHumanSkill {
    public static final String SKILL_ID = "Moci_AugmentedHumanSkill";
    public static final String NEWTYPE_SKILL_ID = "Moci_PlayerSkill1";
    private static final String TEXT_ID = SKILL_ID;
    private static final Logger LOG = Global.getLogger(Moci_AugmentedHumanSkill.class);
    private static final String LOG_PREFIX = "[MOCI-TIME] ";

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
            return Moci_TextLoader.getText(TEXT_ID, "description.level1",
                    "无视米诺夫斯基粒子的干扰效果。");
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
     * 手动主动能力：新人类主动能力的 75% 版本，做成 MagicLib 子系统
     * （{@link Moci_AugmentedHumanSubsystem}），只在玩家亲自驾驶时可用。
     */
    public static class Level2 implements ShipSkillEffect {

        @Override
        public void apply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id, float level) {
            if (stats.getEntity() instanceof ShipAPI) {
                ShipAPI ship = (ShipAPI) stats.getEntity();
                if (!ship.hasListenerOfClass(SubsystemAttacher.class)) {
                    ship.addListener(new SubsystemAttacher(ship));
                }
            }
        }

        @Override
        public void unapply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id) {
            if (stats.getEntity() instanceof ShipAPI) {
                ((ShipAPI) stats.getEntity()).removeListenerOfClass(SubsystemAttacher.class);
            }
        }

        @Override
        public String getEffectDescription(float level) {
            String fallback = "亲自驾驶时可以按子系统栏的「强化人 [键]」手动开启：消耗 {flux_cost} 软幅能，"
                    + "{active_duration} 秒内本舰时流 ×{ship_time_mult}、世界时流放慢（效果为新人类的 {strength}%）；"
                    + "按键与新人类共用 LunaLib 设置「新人类主动键」。";
            String template = Moci_TextLoader.getText(TEXT_ID, "description.level2", fallback);
            return template
                    .replace("{flux_cost}", String.valueOf(Math.round(Moci_NewtypeSubsystem.SOFT_FLUX_COST)))
                    .replace("{active_duration}", String.valueOf(Math.round(Moci_NewtypeSubsystem.ACTIVE_SECONDS)))
                    .replace("{ship_time_mult}", String.format(Locale.US, "%.3f", Moci_AugmentedHumanSubsystem.SHIP_TIME_MULT))
                    .replace("{strength}", String.valueOf(Math.round(Moci_AugmentedHumanSubsystem.STRENGTH * 100f)));
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
     * 等本舰成为玩家座舰时把强化人子系统挂上去（子系统只给玩家手动用，没必要给每艘 AI 舰都挂）。
     * 成功失败都只试一次，避免每帧重试刷日志。
     */
    private static class SubsystemAttacher implements AdvanceableListener {
        private final ShipAPI ship;
        private boolean attempted;

        SubsystemAttacher(ShipAPI ship) {
            this.ship = ship;
        }

        @Override
        public void advance(float amount) {
            if (attempted) return;
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null || engine.getPlayerShip() != ship) return;
            attempted = true;
            try {
                // 同时是新人类时由新人类技能挂它自己的（更强的）子系统，这里让位
                if (hasSkill(ship, NEWTYPE_SKILL_ID)) return;
                Moci_AugmentedHumanSubsystem.attachAugmented(ship);
            } catch (RuntimeException | LinkageError e) {
                LOG.warn(LOG_PREFIX + "强化人子系统挂载失败 ship=" + ship.getId(), e);
            }
        }
    }

    private static boolean hasSkill(ShipAPI ship, String skillId) {
        return ship != null
                && ship.getCaptain() != null
                && ship.getCaptain().getStats() != null
                && ship.getCaptain().getStats().getSkillLevel(skillId) > 0f;
    }

    private static boolean hasJammerImmunitySkill(ShipAPI ship) {
        return ship != null
                && ship.getCaptain() != null
                && ship.getCaptain().getStats() != null
                && (ship.getCaptain().getStats().getSkillLevel(NEWTYPE_SKILL_ID) > 0f
                || ship.getCaptain().getStats().getSkillLevel(SKILL_ID) > 0f);
    }
}
