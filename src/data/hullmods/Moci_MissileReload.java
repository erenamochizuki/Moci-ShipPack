package data.hullmods;

import java.awt.Color;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.combat.listeners.DamageDealtModifier;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

public class Moci_MissileReload extends BaseHullMod {
    private static final String TEXT_ID = "Moci_nanobot_factory";
    // 配置参数
    public static float KILL_TIMEOUT = 20f;             // 击杀有效判定时间（秒）
    public static float REPAIR_TIME_MULT = 1.3f;             // 过载和修复时间倍率
    public static Map<HullSize, Float> AMMO_REPLENISH = new HashMap<>();  // 各体型对应的弹药补充比例

    static {
        // 初始化不同体型敌舰的弹药补充比例
        AMMO_REPLENISH.put(HullSize.FRIGATE, 0.10f);      // 护卫舰：补充10%弹药
        AMMO_REPLENISH.put(HullSize.DESTROYER, 0.20f);    // 驱逐舰：补充20%弹药
        AMMO_REPLENISH.put(HullSize.CRUISER, 0.40f);      // 巡洋舰：补充40%弹药
        AMMO_REPLENISH.put(HullSize.CAPITAL_SHIP, 0.60f); // 主力舰：补充60%弹药
    }

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 过载、修复时间提高30%
        stats.getOverloadTimeMod().modifyMult(id, REPAIR_TIME_MULT);
        stats.getCombatWeaponRepairTimeMult().modifyMult(id, REPAIR_TIME_MULT);
        stats.getCombatEngineRepairTimeMult().modifyMult(id, REPAIR_TIME_MULT);
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 确保只添加一个监听器实例
        if (!ship.hasListenerOfClass(MissileKillTracker.class)) {
            ship.addListener(new MissileKillTracker(ship));
        }
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 10f;
        float opad = 5f;
        Color highlight = Misc.getHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();
        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.warning_heading"),
                new Color(255,150,150,255), //文字颜色
                new Color(200,0,0,150), //背景颜色
                Alignment.MID, // 文字位置
                opad); // 与其他元素之间的间距

        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.warning_main"),
                pad, highlight,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.warning_main_highlights").toArray(new String[0]));
        // 添加分段标题
        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.mechanism_heading"), Alignment.MID, opad);
        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.mechanism_main",
                        Moci_TextLoader.mapOf("kill_timeout", String.valueOf((int) KILL_TIMEOUT))),
                opad, highlight,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.mechanism_main_highlights",
                        Moci_TextLoader.mapOf("kill_timeout", String.valueOf((int) KILL_TIMEOUT))).toArray(new String[0])
        );

        // 添加装填容量分段标题
        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.target_heading"), Alignment.MID, opad);

        // 创建表格
        float[] columnWidths = {width * 0.4f, width * 0.6f}; // 两列宽度比例
        tooltip.beginTable(
                Misc.getBasePlayerColor(),
                Misc.getDarkPlayerColor(),
                Misc.getBrightPlayerColor(),
                20f,
                Moci_TextLoader.getText(TEXT_ID, "description.table_ship_size"), columnWidths[0],
                Moci_TextLoader.getText(TEXT_ID, "description.table_replenish_ratio"), columnWidths[1]
        );

        // 添加表格行
        tooltip.addRow(highlight, Moci_TextLoader.getText(TEXT_ID, "description.table_frigate"), "" + (int)(AMMO_REPLENISH.get(HullSize.FRIGATE)*100) + "%");
        tooltip.addRow(highlight, Moci_TextLoader.getText(TEXT_ID, "description.table_destroyer"), "" + (int)(AMMO_REPLENISH.get(HullSize.DESTROYER)*100) + "%");
        tooltip.addRow(highlight, Moci_TextLoader.getText(TEXT_ID, "description.table_cruiser"), "" + (int)(AMMO_REPLENISH.get(HullSize.CRUISER)*100) + "%");
        tooltip.addRow(highlight, Moci_TextLoader.getText(TEXT_ID, "description.table_capital"), "" + (int)(AMMO_REPLENISH.get(HullSize.CAPITAL_SHIP)*100) + "%");

        tooltip.addTable("", 0, pad); // 结束表格

        // 添加其他说明
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.effect_limited"), pad);
        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.repair_penalty",
                        Moci_TextLoader.mapOf("repair_penalty", String.valueOf((int)((REPAIR_TIME_MULT - 1) * 100)))),
                pad, bad,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.repair_penalty_highlights",
                        Moci_TextLoader.mapOf("repair_penalty", String.valueOf((int)((REPAIR_TIME_MULT - 1) * 100)))).toArray(new String[0]));
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_reason");
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        // 判断舰船体型是否不是主力舰
        return ship.getHullSpec().getHullSize() != HullSize.CAPITAL_SHIP;
    }

    /**
     * 导弹击杀追踪器 - 修复时序问题版本
     * 每帧检查而不是依赖IntervalUtil，确保能立即检测到死亡
     */
    public static class MissileKillTracker implements AdvanceableListener, DamageDealtModifier {
        private final ShipAPI ship;
        private final Map<String, TargetData> trackedTargets = new HashMap<>();

        public MissileKillTracker(ShipAPI ship) {
            this.ship = ship;
        }

        @Override
        public void advance(float amount) {
            // 舰船死亡检查
            if (ship == null || !ship.isAlive() || ship.isHulk()) return;
            
            // 【简化逻辑】每帧同时处理死亡检查和超时清理
            Iterator<Map.Entry<String, TargetData>> iter = trackedTargets.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<String, TargetData> entry = iter.next();
                TargetData data = entry.getValue();
                
                // 减少计时器
                data.timer -= amount;

                // 检查目标状态
                boolean targetDead = !data.target.isAlive();
                boolean timerExpired = data.timer <= 0;
                boolean notConsumed = !data.consumed;

                // 处理死亡目标（立即触发补给）
                if (targetDead && notConsumed) {
                    if (data.target.getHullSize() != null) {
                        applyReplenish(data.target.getHullSize());
                        data.consumed = true; // 标记为已处理
                    }
                    iter.remove(); // 从追踪表中移除
                }
                // 清理超时或已处理的目标
                else if (timerExpired || data.consumed) {
                    iter.remove();
                }
            }
        }

        /**
         * 处理伤害事件 - 简化版本，专注解决时序问题
         */
        @Override
        public String modifyDamageDealt(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point, boolean shieldHit) {
            // 基础验证
            if (!(target instanceof ShipAPI)) return null;
            ShipAPI shipTarget = (ShipAPI) target;
            
            // 排除飞机、无人机和残骸
            if (shipTarget.isFighter() || shipTarget.isDrone() || shipTarget.isHulk()) return null;

            // 验证伤害来源是否为当前舰船
            ShipAPI sourceShip = null;
            if (param instanceof DamagingProjectileAPI) {
                sourceShip = ((DamagingProjectileAPI) param).getSource();
            } else if (param instanceof BeamAPI) {
                sourceShip = ((BeamAPI) param).getSource();
            }
            
            // 只有当前舰船造成的伤害才会被追踪
            if (sourceShip == null || !sourceShip.equals(ship)) return null;
 
            // 更新或创建目标记录
            String key = shipTarget.getId();
            if (trackedTargets.containsKey(key)) {
                // 刷新现有目标的计时器
                trackedTargets.get(key).timer = KILL_TIMEOUT;
            } else {
                // 创建新目标记录
                TargetData data = new TargetData();
                data.target = shipTarget;
                data.timer = KILL_TIMEOUT;
                data.consumed = false;
                trackedTargets.put(key, data);
            }
            return null; // 不修改伤害值
        }

        /**
         * 应用弹药补充
         * @param hullSize 被击毁目标的尺寸
         */
        private void applyReplenish(HullSize hullSize) {
            Float ratio = AMMO_REPLENISH.get(hullSize);
            if (ratio == null) return;

            // 遍历所有武器
            for (WeaponAPI weapon : ship.getAllWeapons()) {
                // 仅处理导弹且有限弹药的武器（排除内置武器）
                if (weapon.getType() == WeaponAPI.WeaponType.MISSILE && weapon.getMaxAmmo() > 0 && !weapon.getSlot().isBuiltIn()) {
                    int maxAmmo = weapon.getMaxAmmo();
                    int currentAmmo = weapon.getAmmo();
                    int toAdd = (int) (maxAmmo * ratio);

                    // 确保不超过最大弹药量
                    int newAmmo = Math.min(currentAmmo + toAdd, maxAmmo);
                    if (newAmmo > currentAmmo) {
                        weapon.setAmmo(newAmmo);
                        spawnText(weapon.getLocation(), "+" + toAdd);
                    }
                }
            }
        }

        /**
         * 生成浮动文字提示
         * @param loc  文字位置（武器坐标）
         * @param text 显示内容
         */
        private void spawnText(Vector2f loc, String text) {
            Global.getCombatEngine().addFloatingText(
                    loc,
                    text,
                    20f,
                    Color.GREEN,
                    ship,
                    1f,
                    1.5f
            );
        }

        /**
         * 目标数据存储结构
         */
        private static class TargetData {
            ShipAPI target;    // 目标舰船
            float timer;       // 剩余有效时间
            boolean consumed;  // 是否已处理标记
        }
    }
}
