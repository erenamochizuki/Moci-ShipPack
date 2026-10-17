package data.hullmods;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 舰载AI辅助词条效果注册表。
 *
 * 这里统一管理：
 * 1. 辅助词条的正式ID
 * 2. 每个辅助词条的显示名称
 * 3. 每个辅助词条在战斗中的具体属性效果
 * 4. UI 使用的文字描述
 */
public class Moci_ShipboardAIAuxEffectRegistry {

    // ===== 机动 / 生存 / 火力 / 系统类辅助词条 =====
    public static final String AUX_MOBILITY = "Moci_Aux_Mobility";
    public static final String AUX_FLUX_STABILITY = "Moci_Aux_FluxStability";
    public static final String AUX_TARGETING = "Moci_Aux_Targeting";
    public static final String AUX_DAMAGE_MITIGATION = "Moci_Aux_DamageMitigation";
    public static final String AUX_DAMAGE_CONTROL = "Moci_Aux_DamageControl";
    public static final String AUX_SHIELD_MODULATION = "Moci_Aux_ShieldModulation";
    public static final String AUX_BALLISTIC_CALIBRATION = "Moci_Aux_BallisticCalibration";
    public static final String AUX_ENERGY_FOCUS = "Moci_Aux_EnergyFocus";
    public static final String AUX_MISSILE_GUIDANCE = "Moci_Aux_MissileGuidance";
    public static final String AUX_SYSTEM_OVERCLOCK = "Moci_Aux_SystemOverclock";
    public static final String AUX_PD_MATRIX = "Moci_Aux_PDMatrix";
    public static final String AUX_ATTITUDE_CONTROL = "Moci_Aux_AttitudeControl";

    // ===== 新增扩展效果 =====
    public static final String AUX_RAPID_CYCLE = "Moci_Aux_RapidCycle";
    public static final String AUX_RANGEFINDER = "Moci_Aux_Rangefinder";
    public static final String AUX_OVERLOAD_DAMPING = "Moci_Aux_OverloadDamping";
    public static final String AUX_ZERO_FLUX_BOOST = "Moci_Aux_ZeroFluxBoost";
    public static final String AUX_HARD_FLUX_VENT = "Moci_Aux_HardFluxVent";
    public static final String AUX_PROJECTILE_OPTIMIZATION = "Moci_Aux_ProjectileOptimization";
    public static final String AUX_MISSILE_SUPPORT = "Moci_Aux_MissileSupport";
    public static final String AUX_SHIELD_GEOMETRY = "Moci_Aux_ShieldGeometry";
    public static final String AUX_ARMOR_PACKAGE = "Moci_Aux_ArmorPackage";
    public static final String AUX_HANGAR_COORDINATION = "Moci_Aux_HangarCoordination";
    public static final String AUX_VENT_EFFICIENCY = "Moci_Aux_VentEfficiency";

    /**
     * 正式支持的辅助词条ID。
     *
     * clearAllEffects() 会按这里的正式ID统一清理效果。
     */
    private static final Set<String> SUPPORTED_AUX_SKILLS = new LinkedHashSet<>(Arrays.asList(
            AUX_MOBILITY,
            AUX_FLUX_STABILITY,
            AUX_TARGETING,
            AUX_DAMAGE_MITIGATION,
            AUX_DAMAGE_CONTROL,
            AUX_SHIELD_MODULATION,
            AUX_BALLISTIC_CALIBRATION,
            AUX_ENERGY_FOCUS,
            AUX_MISSILE_GUIDANCE,
            AUX_SYSTEM_OVERCLOCK,
            AUX_PD_MATRIX,
            AUX_ATTITUDE_CONTROL,
            AUX_RAPID_CYCLE,
            AUX_RANGEFINDER,
            AUX_OVERLOAD_DAMPING,
            AUX_ZERO_FLUX_BOOST,
            AUX_HARD_FLUX_VENT,
            AUX_PROJECTILE_OPTIMIZATION,
            AUX_MISSILE_SUPPORT,
            AUX_SHIELD_GEOMETRY,
            AUX_ARMOR_PACKAGE,
            AUX_HANGAR_COORDINATION,
            AUX_VENT_EFFICIENCY
    ));

    private Moci_ShipboardAIAuxEffectRegistry() {
    }

    /**
     * 判断某个辅助词条ID是否受当前系统支持。
     */
    public static boolean isSupported(String skillId) {
        return SUPPORTED_AUX_SKILLS.contains(skillId);
    }

    /**
     * 获取所有正式辅助词条ID，用于配置校验或批量清理。
     */
    public static Set<String> getSupportedAuxSkillIds() {
        return new LinkedHashSet<>(SUPPORTED_AUX_SKILLS);
    }

    /**
     * 获取辅助词条在UI中的中文名。
     */
    public static String getDisplayName(String skillId) {
        String id = skillId;
        if (AUX_MOBILITY.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "动力协调");
        if (AUX_FLUX_STABILITY.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "幅能稳态");
        if (AUX_TARGETING.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "目标解析");
        if (AUX_DAMAGE_MITIGATION.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "损伤缓释");
        if (AUX_DAMAGE_CONTROL.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "战损管制");
        if (AUX_SHIELD_MODULATION.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "护盾调制");
        if (AUX_BALLISTIC_CALIBRATION.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "实弹校准");
        if (AUX_ENERGY_FOCUS.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "能量聚焦");
        if (AUX_MISSILE_GUIDANCE.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "导弹制导");
        if (AUX_SYSTEM_OVERCLOCK.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "系统超频");
        if (AUX_PD_MATRIX.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "近防矩阵");
        if (AUX_ATTITUDE_CONTROL.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "姿态调整");
        if (AUX_RAPID_CYCLE.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "火控循环");
        if (AUX_RANGEFINDER.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "射程测绘");
        if (AUX_OVERLOAD_DAMPING.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "过载抑制");
        if (AUX_ZERO_FLUX_BOOST.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "零幅驱动");
        if (AUX_HARD_FLUX_VENT.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "硬幅泄放");
        if (AUX_PROJECTILE_OPTIMIZATION.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "弹道优化");
        if (AUX_MISSILE_SUPPORT.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "导弹支援");
        if (AUX_SHIELD_GEOMETRY.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "护盾几何");
        if (AUX_ARMOR_PACKAGE.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "装甲包覆");
        if (AUX_HANGAR_COORDINATION.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "机库协同");
        if (AUX_VENT_EFFICIENCY.equals(id)) return Moci_ShipboardAITextRegistry.getAuxDisplayName(id, "散热导流");
        return id == null ? "" : id;
    }

    /**
     * 将辅助词条效果施加到舰船属性上。
     *
     * @param skillId    辅助词条正式ID
     * @param skillLevel 词条等级。通常 1 表示普通，2 表示强化
     * @param stats      目标舰船属性
     * @param modId      来源ID，用于构造唯一修改器键
     */
    public static void applySkillEffect(String skillId, float skillLevel, MutableShipStatsAPI stats, String modId) {
        if (stats == null || skillId == null) {
            return;
        }
        String id = skillId;
        String effectId = buildEffectId(modId, id);
        float lv = Math.max(1f, skillLevel);

        switch (id) {
            case AUX_MOBILITY:
                stats.getMaxSpeed().modifyPercent(effectId, 5f * lv);
                stats.getAcceleration().modifyPercent(effectId, 8f * lv);
                stats.getDeceleration().modifyPercent(effectId, 8f * lv);
                break;
            case AUX_FLUX_STABILITY:
                stats.getFluxDissipation().modifyPercent(effectId, 8f * lv);
                stats.getFluxCapacity().modifyPercent(effectId, 6f * lv);
                break;
            case AUX_TARGETING:
                stats.getWeaponTurnRateBonus().modifyPercent(effectId, 10f * lv);
                stats.getAutofireAimAccuracy().modifyPercent(effectId, 10f * lv);
                break;
            case AUX_DAMAGE_MITIGATION:
                stats.getArmorDamageTakenMult().modifyMult(effectId, 1f - 0.05f * lv);
                stats.getHullDamageTakenMult().modifyMult(effectId, 1f - 0.03f * lv);
                break;
            case AUX_DAMAGE_CONTROL:
                stats.getCombatWeaponRepairTimeMult().modifyMult(effectId, 1f - 0.10f * lv);
                stats.getCombatEngineRepairTimeMult().modifyMult(effectId, 1f - 0.10f * lv);
                break;
            case AUX_SHIELD_MODULATION:
                stats.getShieldDamageTakenMult().modifyMult(effectId, 1f - 0.04f * lv);
                stats.getShieldUpkeepMult().modifyMult(effectId, 1f - 0.03f * lv);
                break;
            case AUX_BALLISTIC_CALIBRATION:
                stats.getBallisticWeaponDamageMult().modifyMult(effectId, 1f + 0.05f * lv);
                break;
            case AUX_ENERGY_FOCUS:
                stats.getEnergyWeaponDamageMult().modifyMult(effectId, 1f + 0.05f * lv);
                break;
            case AUX_MISSILE_GUIDANCE:
                stats.getMissileWeaponDamageMult().modifyMult(effectId, 1f + 0.06f * lv);
                break;
            case AUX_SYSTEM_OVERCLOCK:
                stats.getSystemCooldownBonus().modifyPercent(effectId, 6f * lv);
                break;
            case AUX_PD_MATRIX:
                stats.getDamageToMissiles().modifyPercent(effectId, 12f * lv);
                break;
            case AUX_ATTITUDE_CONTROL:
                stats.getMaxTurnRate().modifyPercent(effectId, 8f * lv);
                stats.getTurnAcceleration().modifyPercent(effectId, 8f * lv);
                break;
            case AUX_RAPID_CYCLE:
                stats.getBallisticRoFMult().modifyPercent(effectId, 4f * lv);
                stats.getEnergyRoFMult().modifyPercent(effectId, 4f * lv);
                stats.getMissileRoFMult().modifyPercent(effectId, 4f * lv);
                break;
            case AUX_RANGEFINDER:
                stats.getBallisticWeaponRangeBonus().modifyFlat(effectId, 40f * lv);
                stats.getEnergyWeaponRangeBonus().modifyFlat(effectId, 40f * lv);
                break;
            case AUX_OVERLOAD_DAMPING:
                stats.getOverloadTimeMod().modifyPercent(effectId, -10f * lv);
                break;
            case AUX_ZERO_FLUX_BOOST:
                stats.getZeroFluxSpeedBoost().modifyPercent(effectId, 25f * lv);
                break;
            case AUX_HARD_FLUX_VENT:
                stats.getHardFluxDissipationFraction().modifyFlat(effectId, 0.03f * lv);
                break;
            case AUX_PROJECTILE_OPTIMIZATION:
                stats.getBallisticProjectileSpeedMult().modifyPercent(effectId, 6f * lv);
                stats.getEnergyProjectileSpeedMult().modifyPercent(effectId, 6f * lv);
                break;
            case AUX_MISSILE_SUPPORT:
                stats.getMissileMaxSpeedBonus().modifyPercent(effectId, 10f * lv);
                stats.getMissileAccelerationBonus().modifyPercent(effectId, 12f * lv);
                stats.getMissileGuidance().modifyPercent(effectId, 6f * lv);
                break;
            case AUX_SHIELD_GEOMETRY:
                stats.getShieldArcBonus().modifyFlat(effectId, 12f * lv);
                stats.getShieldUnfoldRateMult().modifyPercent(effectId, 8f * lv);
                break;
            case AUX_ARMOR_PACKAGE:
                stats.getArmorBonus().modifyFlat(effectId, 50f * lv);
                stats.getHullBonus().modifyPercent(effectId, 5f * lv);
                break;
            case AUX_HANGAR_COORDINATION:
                stats.getFighterRefitTimeMult().modifyMult(effectId, 1f - 0.08f * lv);
                break;
            case AUX_VENT_EFFICIENCY:
                stats.getVentRateMult().modifyPercent(effectId, 8f * lv);
                break;
            default:
                break;
        }
    }

    /**
     * 清除某个来源施加的全部辅助词条效果。
     */
    public static void clearAllEffects(MutableShipStatsAPI stats, String modId) {
        if (stats == null) {
            return;
        }
        for (String skillId : SUPPORTED_AUX_SKILLS) {
            clearSkillEffect(skillId, stats, modId);
        }
    }

    private static void clearSkillEffect(String skillId, MutableShipStatsAPI stats, String modId) {
        String effectId = buildEffectId(modId, skillId);

        stats.getMaxSpeed().unmodify(effectId);
        stats.getAcceleration().unmodify(effectId);
        stats.getDeceleration().unmodify(effectId);
        stats.getMaxTurnRate().unmodify(effectId);
        stats.getTurnAcceleration().unmodify(effectId);

        stats.getFluxDissipation().unmodify(effectId);
        stats.getFluxCapacity().unmodify(effectId);
        stats.getHardFluxDissipationFraction().unmodify(effectId);
        stats.getVentRateMult().unmodify(effectId);

        stats.getWeaponTurnRateBonus().unmodify(effectId);
        stats.getAutofireAimAccuracy().unmodify(effectId);
        stats.getBallisticWeaponRangeBonus().unmodify(effectId);
        stats.getEnergyWeaponRangeBonus().unmodify(effectId);
        stats.getBallisticRoFMult().unmodify(effectId);
        stats.getEnergyRoFMult().unmodify(effectId);
        stats.getMissileRoFMult().unmodify(effectId);
        stats.getBallisticWeaponDamageMult().unmodify(effectId);
        stats.getEnergyWeaponDamageMult().unmodify(effectId);
        stats.getMissileWeaponDamageMult().unmodify(effectId);
        stats.getBallisticProjectileSpeedMult().unmodify(effectId);
        stats.getEnergyProjectileSpeedMult().unmodify(effectId);

        stats.getArmorDamageTakenMult().unmodify(effectId);
        stats.getHullDamageTakenMult().unmodify(effectId);
        stats.getArmorBonus().unmodify(effectId);
        stats.getHullBonus().unmodify(effectId);

        stats.getCombatWeaponRepairTimeMult().unmodify(effectId);
        stats.getCombatEngineRepairTimeMult().unmodify(effectId);
        stats.getOverloadTimeMod().unmodify(effectId);

        stats.getShieldDamageTakenMult().unmodify(effectId);
        stats.getShieldUpkeepMult().unmodify(effectId);
        stats.getShieldArcBonus().unmodify(effectId);
        stats.getShieldUnfoldRateMult().unmodify(effectId);

        stats.getSystemCooldownBonus().unmodify(effectId);
        stats.getZeroFluxSpeedBoost().unmodify(effectId);
        stats.getDamageToMissiles().unmodify(effectId);

        stats.getMissileMaxSpeedBonus().unmodify(effectId);
        stats.getMissileAccelerationBonus().unmodify(effectId);
        stats.getMissileGuidance().unmodify(effectId);

        stats.getFighterRefitTimeMult().unmodify(effectId);
    }

    /**
     * 返回辅助词条在UI中的说明文本。
     */
    public static String getEffectDescription(String skillId, float skillLevel) {
        String id = skillId;
        float lv = Math.max(1f, skillLevel);

        if (AUX_MOBILITY.equals(id)) {
            return Moci_ShipboardAITextRegistry.formatTemplate(
                    Moci_ShipboardAITextRegistry.getAuxDescriptionTemplate(id, "速度 +{speed}%, 加减速 +{accel}%"),
                    Moci_ShipboardAITextRegistry.mapOf("speed", String.valueOf((int) (5f * lv)),
                            "accel", String.valueOf((int) (8f * lv))));
        }
        if (AUX_FLUX_STABILITY.equals(id)) {
            return Moci_ShipboardAITextRegistry.formatTemplate(
                    Moci_ShipboardAITextRegistry.getAuxDescriptionTemplate(id, "幅能耗散 +{dissipation}%, 幅能容量 +{capacity}%"),
                    Moci_ShipboardAITextRegistry.mapOf("dissipation", String.valueOf((int) (8f * lv)),
                            "capacity", String.valueOf((int) (6f * lv))));
        }
        if (AUX_TARGETING.equals(id)) {
            return Moci_ShipboardAITextRegistry.formatTemplate(
                    Moci_ShipboardAITextRegistry.getAuxDescriptionTemplate(id, "武器转速 +{turn}%, 自动瞄准 +{aim}%"),
                    Moci_ShipboardAITextRegistry.mapOf("turn", String.valueOf((int) (10f * lv)),
                            "aim", String.valueOf((int) (10f * lv))));
        }
        if (AUX_DAMAGE_MITIGATION.equals(id)) {
            return Moci_ShipboardAITextRegistry.formatTemplate(
                    Moci_ShipboardAITextRegistry.getAuxDescriptionTemplate(id, "装甲受伤 -{armor}%, 船体受伤 -{hull}%"),
                    Moci_ShipboardAITextRegistry.mapOf("armor", String.valueOf((int) (5f * lv)),
                            "hull", String.valueOf((int) (3f * lv))));
        }
        if (AUX_DAMAGE_CONTROL.equals(id)) {
            return Moci_ShipboardAITextRegistry.formatTemplate(
                    Moci_ShipboardAITextRegistry.getAuxDescriptionTemplate(id, "武器/引擎维修时间 -{repair}%"),
                    Moci_ShipboardAITextRegistry.mapOf("repair", String.valueOf((int) (10f * lv))));
        }
        if (AUX_SHIELD_MODULATION.equals(id)) {
            return Moci_ShipboardAITextRegistry.formatTemplate(
                    Moci_ShipboardAITextRegistry.getAuxDescriptionTemplate(id, "护盾受伤 -{damage}%, 护盾维持耗散 -{upkeep}%"),
                    Moci_ShipboardAITextRegistry.mapOf("damage", String.valueOf((int) (4f * lv)),
                            "upkeep", String.valueOf((int) (3f * lv))));
        }
        if (AUX_BALLISTIC_CALIBRATION.equals(id)) {
            return simpleValueDesc(id, lv, "实弹伤害 +{value}%");
        }
        if (AUX_ENERGY_FOCUS.equals(id)) {
            return simpleValueDesc(id, lv, "能量伤害 +{value}%");
        }
        if (AUX_MISSILE_GUIDANCE.equals(id)) {
            return simpleValueDesc(id, lv, "导弹伤害 +{value}%");
        }
        if (AUX_SYSTEM_OVERCLOCK.equals(id)) {
            return simpleValueDesc(id, lv, "系统冷却加速 +{value}%");
        }
        if (AUX_PD_MATRIX.equals(id)) {
            return simpleValueDesc(id, lv, "对导弹伤害 +{value}%");
        }
        if (AUX_ATTITUDE_CONTROL.equals(id)) {
            return simpleValueDesc(id, lv, "转向速度/加速度 +{value}%");
        }
        if (AUX_RAPID_CYCLE.equals(id)) {
            return simpleValueDesc(id, lv, "全武器射速 +{value}%");
        }
        if (AUX_RANGEFINDER.equals(id)) {
            return simpleValueDesc(id, lv, "实弹/能量射程 +{value}");
        }
        if (AUX_OVERLOAD_DAMPING.equals(id)) {
            return simpleValueDesc(id, lv, "过载时间 -{value}%");
        }
        if (AUX_ZERO_FLUX_BOOST.equals(id)) {
            return simpleValueDesc(id, lv, "零幅能加速额外 +{value}%");
        }
        if (AUX_HARD_FLUX_VENT.equals(id)) {
            return simpleValueDesc(id, lv, "硬幅能被动泄放 +{value}%");
        }
        if (AUX_PROJECTILE_OPTIMIZATION.equals(id)) {
            return simpleValueDesc(id, lv, "实弹/能量弹速 +{value}%");
        }
        if (AUX_MISSILE_SUPPORT.equals(id)) {
            return Moci_ShipboardAITextRegistry.formatTemplate(
                    Moci_ShipboardAITextRegistry.getAuxDescriptionTemplate(id, "导弹速度 +{speed}%, 加速度 +{accel}%, 制导 +{guidance}%"),
                    Moci_ShipboardAITextRegistry.mapOf("speed", String.valueOf((int) (10f * lv)),
                            "accel", String.valueOf((int) (12f * lv)),
                            "guidance", String.valueOf((int) (6f * lv))));
        }
        if (AUX_SHIELD_GEOMETRY.equals(id)) {
            return Moci_ShipboardAITextRegistry.formatTemplate(
                    Moci_ShipboardAITextRegistry.getAuxDescriptionTemplate(id, "护盾角度 +{arc}, 展开速度 +{unfold}%"),
                    Moci_ShipboardAITextRegistry.mapOf("arc", String.valueOf((int) (12f * lv)),
                            "unfold", String.valueOf((int) (8f * lv))));
        }
        if (AUX_ARMOR_PACKAGE.equals(id)) {
            return Moci_ShipboardAITextRegistry.formatTemplate(
                    Moci_ShipboardAITextRegistry.getAuxDescriptionTemplate(id, "装甲 +{armor}, 船体 +{hull}%"),
                    Moci_ShipboardAITextRegistry.mapOf("armor", String.valueOf((int) (50f * lv)),
                            "hull", String.valueOf((int) (5f * lv))));
        }
        if (AUX_HANGAR_COORDINATION.equals(id)) {
            return simpleValueDesc(id, lv, "战机替换时间 -{value}%");
        }
        if (AUX_VENT_EFFICIENCY.equals(id)) {
            return simpleValueDesc(id, lv, "主动散热效率 +{value}%");
        }
        return Moci_ShipboardAITextRegistry.getUiText("aux_unconfigured_effect", "未配置固定映射效果");
    }

    private static String simpleValueDesc(String id, float lv, String fallbackTemplate) {
        int value;
        if (AUX_BALLISTIC_CALIBRATION.equals(id) || AUX_ENERGY_FOCUS.equals(id)) {
            value = (int) (5f * lv);
        } else if (AUX_MISSILE_GUIDANCE.equals(id)) {
            value = (int) (6f * lv);
        } else if (AUX_SYSTEM_OVERCLOCK.equals(id)) {
            value = (int) (6f * lv);
        } else if (AUX_PD_MATRIX.equals(id)) {
            value = (int) (12f * lv);
        } else if (AUX_ATTITUDE_CONTROL.equals(id)) {
            value = (int) (8f * lv);
        } else if (AUX_RAPID_CYCLE.equals(id)) {
            value = (int) (4f * lv);
        } else if (AUX_RANGEFINDER.equals(id)) {
            value = (int) (40f * lv);
        } else if (AUX_OVERLOAD_DAMPING.equals(id)) {
            value = (int) (10f * lv);
        } else if (AUX_ZERO_FLUX_BOOST.equals(id)) {
            value = (int) (25f * lv);
        } else if (AUX_HARD_FLUX_VENT.equals(id)) {
            value = (int) (3f * lv);
        } else if (AUX_PROJECTILE_OPTIMIZATION.equals(id)) {
            value = (int) (6f * lv);
        } else if (AUX_HANGAR_COORDINATION.equals(id)) {
            value = (int) (8f * lv);
        } else if (AUX_VENT_EFFICIENCY.equals(id)) {
            value = (int) (8f * lv);
        } else {
            value = 0;
        }
        return Moci_ShipboardAITextRegistry.formatTemplate(
                Moci_ShipboardAITextRegistry.getAuxDescriptionTemplate(id, fallbackTemplate),
                Moci_ShipboardAITextRegistry.mapOf("value", String.valueOf(value)));
    }

    private static String buildEffectId(String modId, String skillId) {
        return modId + "_shipboard_aux_" + skillId;
    }
}
