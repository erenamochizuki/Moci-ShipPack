package data.campaign.skills;

import com.fs.starfarer.api.characters.ShipSkillEffect;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import data.scripts.util.Moci_TextLoader;

/**
 * 泰蕾莎专属被动技能「耳语者」。
 *
 * <p>基础：缩短舰船系统冷却、加快系统充能。<br>
 * 精英：强化导弹的伤害、航速与生命值。</p>
 */
public class Moci_TeresaSkill {
    public static final String SKILL_ID = "Moci_TeresaSkill";
    private static final String TEXT_ID = SKILL_ID;

    public static final float SYSTEM_COOLDOWN_REDUCTION = 20f;
    public static final float SYSTEM_REGEN_BONUS = 20f;

    public static final float MISSILE_DAMAGE_BONUS = 10f;
    public static final float MISSILE_SPEED_BONUS = 25f;
    public static final float MISSILE_HEALTH_BONUS = 50f;

    public static class Level1 implements ShipSkillEffect {
        @Override
        public void apply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id, float level) {
            stats.getSystemCooldownBonus().modifyMult(id, 1f - SYSTEM_COOLDOWN_REDUCTION / 100f);
            stats.getSystemRegenBonus().modifyPercent(id, SYSTEM_REGEN_BONUS);
        }

        @Override
        public void unapply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id) {
            stats.getSystemCooldownBonus().unmodify(id);
            stats.getSystemRegenBonus().unmodify(id);
        }

        @Override
        public String getEffectDescription(float level) {
            return Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.level1",
                    Moci_TextLoader.mapOf(
                            "cooldown", String.valueOf(Math.round(SYSTEM_COOLDOWN_REDUCTION)),
                            "regen", String.valueOf(Math.round(SYSTEM_REGEN_BONUS))));
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

    public static class Level2 implements ShipSkillEffect {
        @Override
        public void apply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id, float level) {
            stats.getMissileWeaponDamageMult().modifyPercent(id, MISSILE_DAMAGE_BONUS);
            stats.getMissileMaxSpeedBonus().modifyPercent(id, MISSILE_SPEED_BONUS);
            stats.getMissileHealthBonus().modifyPercent(id, MISSILE_HEALTH_BONUS);
        }

        @Override
        public void unapply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id) {
            stats.getMissileWeaponDamageMult().unmodify(id);
            stats.getMissileMaxSpeedBonus().unmodify(id);
            stats.getMissileHealthBonus().unmodify(id);
        }

        @Override
        public String getEffectDescription(float level) {
            return Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.level2",
                    Moci_TextLoader.mapOf(
                            "damage", String.valueOf(Math.round(MISSILE_DAMAGE_BONUS)),
                            "speed", String.valueOf(Math.round(MISSILE_SPEED_BONUS)),
                            "health", String.valueOf(Math.round(MISSILE_HEALTH_BONUS))));
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
}
