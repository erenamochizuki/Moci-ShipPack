package data.campaign.skills;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.ShipSkillEffect;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import data.scripts.combat.Moci_TimeFlow;
import data.scripts.util.Moci_TextLoader;
import data.shipsystems.scripts.Moci_MissileJammerSystemScript;
import org.lazywizard.lazylib.MathUtils;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class Moci_PlayerSkill1 {
    private static final String TEXT_ID = "Moci_PlayerSkill1";
    private static final String AUGMENTED_HUMAN_SKILL_ID = "Moci_AugmentedHumanSkill";

    private static String formatWholeNumber(float value) {
        return String.valueOf(Math.round(value));
    }

    private static String formatOneDecimal(float value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static String formatTwoDecimals(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

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

    public static class Level3 implements ShipSkillEffect {

        @Override
        public void apply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id, float level) {
            if (stats.getEntity() instanceof ShipAPI) {
                ShipAPI ship = (ShipAPI) stats.getEntity();
                if (listener.isChildModule(ship)) {
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
            return Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.level3",
                    Moci_TextLoader.mapOf(
                            "enemy_range", formatWholeNumber(listener.ENEMY_RANGE),
                            "projectile_range", formatWholeNumber(listener.PROJECTILE_RANGE),
                            "ship_time_mult", formatOneDecimal(listener.SHIP_TIME_MULT),
                            "active_duration", formatWholeNumber(listener.ACTIVE_DURATION),
                            "cooldown_duration", formatWholeNumber(listener.COOLDOWN_DURATION)
                    ));
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
        private static final String LISTENER_DATA_KEY = "MociPlayerSkill1Listener";

        // ========== 可配置参数 ==========
        // 时间流速相关
        static final float SHIP_TIME_MULT = 1.5f;        // 舰船时间流速倍率
        static final float WORLD_TIME_MULT = 0.9f;     // 世界时间流速倍率
        
        // 持续时间和冷却时间
        static final float ACTIVE_DURATION = 20f;      // 效果持续时间（秒）
        static final float COOLDOWN_DURATION = 50f;    // 冷却时间（秒）
        
        // 检测范围（使用距离平方避免平方根运算）
        static final float ENEMY_RANGE = 1000f;        // 敌方舰船检测范围
        static final float PROJECTILE_RANGE = 500f;    // 弹药检测范围
        static final float ENEMY_RANGE_SQ = ENEMY_RANGE * ENEMY_RANGE;
        static final float PROJECTILE_RANGE_SQ = PROJECTILE_RANGE * PROJECTILE_RANGE;
        
        // 威胁阈值
        static final float MISSILE_DAMAGE_THRESHOLD = 500f;    // 导弹伤害阈值
        static final float PROJECTILE_DAMAGE_THRESHOLD = 140f; // 弹丸伤害阈值
        
        // 性能优化：采样间隔
        static final float CHECK_FREQUENCY = 0.1f;     // 检测频率（秒）
        // ================================
        
        final ShipAPI ship;
        final String id;
        final String spriteName;

        float coldDown = 0;
        float active = 0;
        float checkInterval = 0;
        Object key = new Object();

        // 时间流句柄。技能的效果是「本舰 ×1.5→1」叠加「世界 ×0.9→1」，两者不是互为倒数，
        // 所以不能合成一发子弹时间，而是「船一份 + 世界一份」两笔独立申请；模块树再逐模块各一份。
        // 申请的有效期由 Moci_TimeFlow 按「作用域舰船是否还在场」判定，本类只负责还在驱动时每帧刷新。
        private Moci_TimeFlow.Handle shipTimeLease;
        private Moci_TimeFlow.Handle worldTimeLease;
        private final Map<ShipAPI, Moci_TimeFlow.Handle> moduleTime = new IdentityHashMap<>();

        public static listener getInstance(ShipAPI ship) {
            if (ship.getCustomData().containsKey(LISTENER_DATA_KEY)) {
                return (listener) ship.getCustomData().get(LISTENER_DATA_KEY);
            } else {
                if (ship.hasListenerOfClass(listener.class)) {
                    ship.removeListenerOfClass(listener.class);
                }
                listener newListener = new listener(ship);
                ship.getCustomData().put(LISTENER_DATA_KEY, newListener);
                ship.addListener(newListener);
                return newListener;
            }
        }

        public static void removeInstance(ShipAPI ship) {
            if (ship == null) {
                return;
            }

            Object listenerObj = ship.getCustomData().remove(LISTENER_DATA_KEY);
            if (listenerObj instanceof listener) {
                ((listener) listenerObj).unapply();
                ship.removeListener((listener) listenerObj);
            } else {
                for (listener existing : ship.getListeners(listener.class)) {
                    existing.unapply();
                }
                ship.removeListenerOfClass(listener.class);
            }
        }

        public static boolean isChildModule(ShipAPI ship) {
            if (ship == null) {
                return false;
            }

            ShipAPI parent = ship.getParentStation();
            return ship.isStationModule() || (parent != null && parent != ship);
        }

        private static ShipAPI getRootShip(ShipAPI ship) {
            ShipAPI current = ship;
            while (current != null) {
                ShipAPI parent = current.getParentStation();
                if (parent == null || parent == current) {
                    return current;
                }
                current = parent;
            }
            return ship;
        }

        private listener(ShipAPI ship) {
            this.ship = ship;
            this.id = "MociPlayerSkill1_" + ship.getId();
            this.spriteName = Global.getSettings().getSpriteName("icon", "Moci_skill1");
        }

        @Override
        public void advance(float amount) {
            // 检查战斗引擎是否可用
            if (Global.getCombatEngine() == null || Global.getCombatEngine().isPaused()) {
                return;
            }
            // 新人类主动能力（叠加层）只在这名飞行员拥有本技能时安装，不再是全局战斗插件。
            if (Global.getCombatEngine().getPlayerShip() == ship) {
                try { xi.arsenal.OwnNewtypeController.ensure(Global.getCombatEngine()); }
                catch (RuntimeException | LinkageError ignored) { }
            }

            if (!ship.isAlive()) {
                unapply();
                ship.getCustomData().remove(LISTENER_DATA_KEY);
                ship.removeListener(this);
                return;
            }

            if (isChildModule(ship)) {
                active = 0f;
                coldDown = 0f;
                checkInterval = 0f;
                unapply();
                return;
            }

            if (coldDown > 0) {
                coldDown -= amount;
                return;
            }

            boolean player = isPlayerShip();
            if (active > 0) {
                float progress = 1f - (active / ACTIVE_DURATION);
                apply(player, progress);
                active -= amount;
                if (active <= 0) {
                    unapply();
                    coldDown = COOLDOWN_DURATION;
                }
            } else {
                // 性能优化：采样间隔，不是每帧都检测
                checkInterval -= amount;
                if (checkInterval > 0) {
                    return;
                }
                checkInterval = CHECK_FREQUENCY;
                
                var shipLoc = ship.getLocation();
                int shipOwner = ship.getOwner();
                
                // 性能优化1：使用舰船专用网格检测敌方舰船（1000范围）
                Iterator<Object> shipIter = Global.getCombatEngine().getShipGrid()
                        .getCheckIterator(shipLoc, 2000, 2000);
                
                while (shipIter.hasNext()) {
                    Object obj = shipIter.next();
                    if (!(obj instanceof ShipAPI)) continue;
                    
                    ShipAPI t = (ShipAPI) obj;
                    // 提前过滤：敌对且非残骸
                    if (t.getOwner() + shipOwner != 1 || t.isHulk()) {
                        continue;
                    }
                    
                    // 使用距离平方避免平方根运算
                    float distSq = MathUtils.getDistanceSquared(shipLoc, t.getLocation());
                    if (distSq <= ENEMY_RANGE_SQ) {
                        Global.getSoundPlayer().playSound("Moci_NT", 1.0f, 0.7f, shipLoc,
                                ship.getVelocity());
                        activate(player);
                        return;
                    }
                }
                
                // 性能优化2：使用导弹专用网格检测高威胁弹药（500范围）
                Iterator<Object> missileIter = Global.getCombatEngine().getMissileGrid()
                        .getCheckIterator(shipLoc, 1000, 1000);
                
                while (missileIter.hasNext()) {
                    Object obj = missileIter.next();
                    if (!(obj instanceof MissileAPI)) continue;
                    
                    MissileAPI missile = (MissileAPI) obj;
                    // 提前过滤：敌对导弹
                    if (missile.getOwner() + shipOwner != 1) {
                        continue;
                    }
                    
                    // 使用距离平方避免平方根运算
                    float distSq = MathUtils.getDistanceSquared(shipLoc, missile.getLocation());
                    if (distSq > PROJECTILE_RANGE_SQ) {
                        continue;
                    }
                    
                    // 检查导弹伤害阈值
                    if (missile.getDamageAmount() >= MISSILE_DAMAGE_THRESHOLD) {
                        Global.getSoundPlayer().playSound("Moci_NT", 1.0f, 1.0f, shipLoc,
                                ship.getVelocity());
                        activate(player);
                        return;
                    }
                }
                
                // 性能优化3：使用全对象网格检测高威胁弹丸（500范围）
                // 注意：这里只检测非导弹的弹丸，因为导弹已经在上面检测过了
                Iterator<Object> projIter = Global.getCombatEngine().getAllObjectGrid()
                        .getCheckIterator(shipLoc, 1000, 1000);
                
                while (projIter.hasNext()) {
                    Object obj = projIter.next();
                    if (!(obj instanceof DamagingProjectileAPI)) continue;
                    if (obj instanceof MissileAPI) continue; // 跳过导弹，已经检测过
                    
                    DamagingProjectileAPI p = (DamagingProjectileAPI) obj;
                    // 提前过滤：敌对弹丸
                    if (p.getOwner() + shipOwner != 1) {
                        continue;
                    }
                    
                    // 使用距离平方避免平方根运算
                    float distSq = MathUtils.getDistanceSquared(shipLoc, p.getLocation());
                    if (distSq > PROJECTILE_RANGE_SQ) {
                        continue;
                    }
                    
                    // 检查弹丸伤害阈值
                    if (p.getDamageAmount() >= PROJECTILE_DAMAGE_THRESHOLD) {
                        Global.getSoundPlayer().playSound("Moci_NT", 1.0f, 1.0f, shipLoc,
                                ship.getVelocity());
                        activate(player);
                        return;
                    }
                }
            }
        }

        private void activate(boolean player) {
            active = ACTIVE_DURATION;
            apply(player, 0f);
        }

        private void apply(boolean player, float progress) {
            float clampedProgress = Math.max(0f, Math.min(1f, progress));
            float shipTimeMult = SHIP_TIME_MULT + (1f - SHIP_TIME_MULT) * clampedProgress;
            float worldTimeMult = WORLD_TIME_MULT + (1f - WORLD_TIME_MULT) * clampedProgress;

            shipTimeLease = ensureShipTime(ship, shipTimeLease, shipTimeMult);
            syncChildModuleTimeMult(shipTimeMult);

            if (player && Global.getCombatEngine() != null) {
                worldTimeLease = ensureWorldTime(worldTimeLease, worldTimeMult);
                Global.getCombatEngine().maintainStatusForPlayerShip(
                        key,
                        spriteName,
                        Moci_TextLoader.getText(TEXT_ID, "status.title"),
                        Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.time_mult",
                                Moci_TextLoader.mapOf("ship_time_mult", formatTwoDecimals(shipTimeMult))),
                        false
                );
            } else if (Global.getCombatEngine() != null) {
                // 卸任/换乘后世界那一份必须真的收回：原来只 unmodify 属性键，
                // 句柄模型里就是 release，否则世界会被永久压到 0.9。
                releaseWorldTime("非玩家舰不压世界");
            }
        }

        private void unapply() {
            // 时间倍率一律经句柄收回：本舰、模块树、世界三笔各自独立，
            // 原实现这里只反挂属性键，一旦某条退出路径没走到世界就会被永久压到 0.9。
            releaseShipTime("unapply");
            clearChildModuleTimeMult();
            releaseWorldTime("unapply");
        }

        private boolean isPlayerShip() {
            ShipAPI playerShip = Global.getCombatEngine().getPlayerShip();
            return playerShip != null && getRootShip(playerShip) == ship;
        }

        /** 申请/刷新本舰自己的时间倍率句柄（句柄失效时重建，保持与权威的唯一契约）。 */
        private Moci_TimeFlow.Handle ensureShipTime(ShipAPI target, Moci_TimeFlow.Handle handle, float mult) {
            if (handle != null && handle.active()) {
                handle.set(mult);
                return handle;
            }
            if (handle != null) {
                releaseHandle(handle, "本舰句柄失效重建");
            }
            return Moci_TimeFlow.openShip(target, id, mult);
        }

        /** 申请/刷新「压慢世界」那一笔（只有玩家正在驾驶本舰时才存在）。 */
        private Moci_TimeFlow.Handle ensureWorldTime(Moci_TimeFlow.Handle handle, float mult) {
            if (handle != null && handle.active()) {
                handle.set(mult);
                return handle;
            }
            if (handle != null) {
                releaseHandle(handle, "世界句柄失效重建");
            }
            // 独立的申请名：世界申请与船申请即使同名也在不同的作用域键里，但仍分开命名以便排查
            return Moci_TimeFlow.openWorld(id + "_world", mult);
        }

        private void releaseShipTime(String why) {
            Moci_TimeFlow.Handle handle = shipTimeLease;
            shipTimeLease = null;
            releaseHandle(handle, why);
        }

        private void releaseWorldTime(String why) {
            Moci_TimeFlow.Handle handle = worldTimeLease;
            worldTimeLease = null;
            releaseHandle(handle, why);
        }

        private void releaseHandle(Moci_TimeFlow.Handle handle, String why) {
            if (handle == null) {
                return;
            }
            if (Moci_TimeFlow.TRACE) {
                Global.getLogger(Moci_PlayerSkill1.class)
                        .info("[MOCI-TIME] Moci_PlayerSkill1 收回时间申请 ship=" + ship.getId() + " 原因=" + why);
            }
            handle.release();
        }

        /** 逐模块刷新时间倍率；掉出模块树的模块先收回申请再移出。 */
        private void syncChildModuleTimeMult(float shipTimeMult) {
            Set<ShipAPI> visited = new HashSet<>();
            Set<ShipAPI> alive = new HashSet<>();
            syncChildModuleTimeMult(ship, shipTimeMult, visited, alive);
            Iterator<Map.Entry<ShipAPI, Moci_TimeFlow.Handle>> it = moduleTime.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<ShipAPI, Moci_TimeFlow.Handle> entry = it.next();
                if (alive.contains(entry.getKey())) {
                    continue;
                }
                // 模块已被摘离（被打爆/分离）：它的属性表不会再被本技能刷新，
                // 不收回就会一直停在 1.5 倍时流上；先 release 再从表里移出。
                releaseHandle(entry.getValue(), "模块已脱离模块树");
                it.remove();
            }
        }

        private void syncChildModuleTimeMult(ShipAPI parent, float shipTimeMult, Set<ShipAPI> visited, Set<ShipAPI> alive) {
            if (parent == null || !visited.add(parent) || parent.getChildModulesCopy() == null) {
                return;
            }

            for (ShipAPI module : parent.getChildModulesCopy()) {
                if (module == null) {
                    continue;
                }
                alive.add(module);
                Moci_TimeFlow.Handle handle = moduleTime.get(module);
                if (handle != null && handle.active()) {
                    handle.set(shipTimeMult);
                } else {
                    if (handle != null) {
                        releaseHandle(handle, "模块句柄失效重建");
                    }
                    moduleTime.put(module, Moci_TimeFlow.openShip(module, id, shipTimeMult));
                }
                syncChildModuleTimeMult(module, shipTimeMult, visited, alive);
            }
        }

        /** 收回所有模块的时间申请（不再按「当前模块树」递归摘除——树可能已经变了）。 */
        private void clearChildModuleTimeMult() {
            for (Moci_TimeFlow.Handle handle : moduleTime.values()) {
                releaseHandle(handle, "unapply");
            }
            moduleTime.clear();
        }
    }
}
