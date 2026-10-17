package data.campaign.skills;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.ShipSkillEffect;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.util.Misc;
import data.shipsystems.scripts.Moci_MissileJammerSystemScript;

import java.awt.*;
import java.util.Iterator;

public class Moci_PlayerSkill1_old {
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
                ship.getTags().remove(Moci_MissileJammerSystemScript.playerSkillBanTag);
            }
        }

        @Override
        public String getEffectDescription(float level) {
            return "无视米诺夫斯基粒子的干扰效果";
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
            stats.getEccmChance().modifyFlat(id, 1);
        }

        @Override
        public void unapply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id) {
            stats.getEccmChance().unmodify(id);
        }

        @Override
        public String getEffectDescription(float level) {
            return "降低 100% 导弹被电子反制或热诱弹拦截的概率。";
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

    public static class Level3 implements ShipSkillEffect {

        @Override
        public void apply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id, float level) {
            stats.addListener(new listener(stats, id));
        }

        @Override
        public void unapply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id) {
            if (stats.hasListenerOfClass(listener.class)) {
                for (listener l : stats.getListeners(listener.class)) {
                    l.unapply();
                }
                stats.removeListenerOfClass(listener.class);
            }
        }

        @Override
        public String getEffectDescription(float level) {
            return "当周围1000范围内有敌人或500范围内有导弹、弹丸的时候触发一次效果，获得1.2倍的时流，持续3秒，冷却15秒";
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

    private static class listener implements AdvanceableListener {
        final MutableShipStatsAPI stats;
        final CombatEntityAPI entity;
        final String id;
        float worldMult;

        final String spriteName;

        private listener(MutableShipStatsAPI stats, String id) {
            this.stats = stats;
            this.entity = stats.getEntity();
            this.id = id + Misc.genUID();
            worldMult = 1 / 1.2f;
            this.spriteName = Global.getSettings().getSpriteName("icon", "Moci_skill1");
        }

        float coldDown = 0;
        float active = 0;
        Object key = new Object();

        @Override
        public void advance(float amount) {
            if (coldDown > 0) {
                coldDown -= amount;
                return;
            }
            boolean player = Global.getCombatEngine().getPlayerShip() == entity;
            if (active > 0) {
                apply(player);
                active -= amount;
                if (active <= 0) {
                    unapply();
                    coldDown = 15;
                }
            } else {
                Iterator<Object> iter = Global.getCombatEngine().getAllObjectGrid()
                        .getCheckIterator(entity.getLocation(), 1000, 1000);
                while (iter.hasNext()) {
                    Object obj = iter.next();
                    float dist;
                    if (obj instanceof ShipAPI) {
                        ShipAPI t = (ShipAPI) obj;
                        dist = Misc.getDistance(entity.getLocation(), t.getLocation());
                        if (dist <= 1000 && t.getOwner() + entity.getOwner() == 1) {
                            apply(player);
                            active = 3;
                            return;
                        }
                    }
                    if (obj instanceof DamagingProjectileAPI) {
                        DamagingProjectileAPI p = (DamagingProjectileAPI) obj;
                        dist = Misc.getDistance(entity.getLocation(), p.getLocation());
                        if (dist <= 500 && p.getOwner() + entity.getOwner() == 1) {
                            apply(player);
                            active = 3;
                            return;
                        }
                    }
                }
            }
        }

        private void apply(boolean player) {
            stats.getTimeMult().modifyMult(id, 1.2f);

            // 播放音效 - 使用时间系统相关的音效
            Global.getSoundPlayer().playSound("Moci_NT", 1.0f, 1.0f, entity.getLocation(),
                    entity.getVelocity());

            if (player) {
                Global.getCombatEngine().getTimeMult().modifyMult(id, worldMult);
                Global.getCombatEngine().maintainStatusForPlayerShip(key, spriteName, "N.T.", "时间流速提高", false);
            } else {
                Global.getCombatEngine().getTimeMult().unmodify(id);
            }
        }

        private void unapply() {
            stats.getTimeMult().unmodify(id);
            Global.getCombatEngine().getTimeMult().unmodify(id);
        }
    }
}
