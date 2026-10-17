package data.campaign.skills;

import com.fs.starfarer.api.characters.ShipSkillEffect;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import data.scripts.util.Moci_TextLoader;
import data.shipsystems.scripts.Moci_MissileJammerSystemScript;

public class Moci_AugmentedHumanSkill {
    public static final String SKILL_ID = "Moci_AugmentedHumanSkill";
    public static final String NEWTYPE_SKILL_ID = "Moci_PlayerSkill1";
    private static final String TEXT_ID = SKILL_ID;

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

    private static boolean hasJammerImmunitySkill(ShipAPI ship) {
        return ship != null
                && ship.getCaptain() != null
                && ship.getCaptain().getStats() != null
                && (ship.getCaptain().getStats().getSkillLevel(NEWTYPE_SKILL_ID) > 0f
                || ship.getCaptain().getStats().getSkillLevel(SKILL_ID) > 0f);
    }
}
