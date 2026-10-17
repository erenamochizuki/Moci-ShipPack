package data.hullmods;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.dark.shaders.distortion.DistortionShader;
import org.dark.shaders.distortion.RippleDistortion;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import com.fs.starfarer.api.util.FaderUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.TimeoutTracker;
import data.scripts.util.Moci_TextLoader;

/**
 * GN力场防御系统核心脚本
 * 参考光环src的HSITurbulanceShieldListenerV2实现
 */
public class Moci_GNFieldDefense_Script implements DamageTakenModifier, AdvanceableListener {

    private static final String TEXT_ID = "Moci_GNFieldDefenseStatus";
    private ShipAPI ship;
    private Moci_GNFieldStats stats;
    private Moci_GNFieldShield shield;
    private Moci_GNFieldRenderData renderData;

    public static final String KEY = "Moci_GNFieldDefense_Absorb";
    private final Color DMG_TEXT = new Color(100, 200, 255, 225); // GN蓝色伤害文字

    // 护盾控制（接管原版护盾）
    private boolean SHIELD_CONTROL = false;

    // 上一帧状态记录
    private float lastFrameHitpoint = 0;
    private boolean lastFramePhaseCloakActive = false;
    private boolean lastFrameSystemActive = true;

    public enum ParamType {
        ARC, SHIP, PROJ, BEAM;
    }

    // ========== 单例管理 ==========

    public static Moci_GNFieldDefense_Script getInstance(ShipAPI ship) {
        if (hasShield(ship)) {
            return ship.getListeners(Moci_GNFieldDefense_Script.class).get(0);
        } else {
            Moci_GNFieldDefense_Script l = new Moci_GNFieldDefense_Script(ship);
            ship.addListener(l);
            return l;
        }
    }

    public static boolean hasShield(ShipAPI ship) {
        return ship.hasListenerOfClass(Moci_GNFieldDefense_Script.class);
    }

    // ========== 构造和初始化 ==========

    public Moci_GNFieldDefense_Script(ShipAPI ship) {
        this.ship = ship;
        init(ship);
    }

    private TimeoutTracker<BeamAPI> effected = new TimeoutTracker<BeamAPI>();
    private TimeoutTracker<BeamAPI> beamController = new TimeoutTracker<BeamAPI>();

    public void init(ShipAPI ship) {
        // 初始化护盾数据
        stats = new Moci_GNFieldStats();
        shield = new Moci_GNFieldShield();
        renderData = new Moci_GNFieldRenderData();

        // 安全覆盖惩罚
        if (ship.getVariant().hasHullMod(HullMods.SAFETYOVERRIDES)) {
            stats.getShieldRecoveryRate().modifyMult(HullMods.SAFETYOVERRIDES, 0.5f);
        }

        // 保存初始装甲
        saveArmor();

        // 设置护盾装甲值（基于幅能容量，而非护盾容量）
        shield.setEngageArmorProcess(true);
        stats.getShieldArmorValue().modifyFlat("GN_BASE",
                Math.min(ship.getMaxFlux() * Moci_GNFieldDefense_Config.ARMOR_FRAC,
                        Moci_GNFieldDefense_Config.SHIELD_ARMOR_MAX));

        SHIELD_CONTROL = ship.getShield() != null;
        if (SHIELD_CONTROL) {
            ship.getShield().setArc(0f);
            ship.getShield().setRadius(Math.max(1f, ship.getCollisionRadius() - ship.getHullSize().ordinal() * 10f));
            ship.getShield().setSkipRendering(true);
            ship.getShield().setInnerColor(new Color(0, 0, 0, 0));
            ship.getShield().setRingColor(new Color(0, 0, 0, 0));
        }

        // 初始化护盾值（满值）
        shield.update();
    }

    // ========== 伤害处理 ==========

    @Override
    public String modifyDamageTaken(Object param, CombatEntityAPI target,
            DamageAPI damage, Vector2f point, boolean shieldHit) {
        damage.getModifier().unmodify(KEY);

        // 检查护盾是否被击破
        if (shield.isShieldBroken()) {
            return null; // 护盾被击破，不处理伤害
        }

        // 检查护盾值是否可用
        if ((shield.getCurrent() + shield.getExtra()) <= 0) {
            return null;
        }

        // 如果有原版护盾控制，检查是否激活
        // 如果没有原版护盾，GN力场防御系统始终激活
        if (SHIELD_CONTROL) {
            // 有原版护盾：只在护盾开启或过载时工作
            if (ship.getShield() != null && !ship.getShield().isOn() &&
                    !(ship.getFluxTracker().isOverloadedOrVenting())) {
                return null;
            }
        }
        // 没有原版护盾：始终工作，不需要额外检查

        // 特殊处理：爆炸伤害减免
        if (param == null) {
            damage.getModifier().modifyMult("GN_CloseCombat", 0.25f);
        }

        // 获取伤害类型
        ParamType type = getType(param, damage);

        // 计算穿透强度
        float strength = getPierceStrength(damage);

        // 预处理伤害
        float damageTakenBase = preProcessDamage(type, damage, point, param);

        // 护盾装甲处理
        if (shield.isEngageArmorProcess()) {
            damageTakenBase = engageArmorProcess(damageTakenBase, point,
                    (type.equals(ParamType.BEAM)), strength);
        }

        // 伤害过低，完全吸收
        if (damageTakenBase <= 0.1) {
            damage.getModifier().modifyMult(KEY, 0.0001f);
            return KEY;
        }

        // 最终伤害处理
        boolean isSoft = damage.isSoftFlux();
        float damageTaken = processDamage(damageTakenBase, type, damage, point);

        if (damageTaken > 0) {
            // 添加击中视觉效果
            addHitEffect(type, param, point, damageTaken, damageTakenBase);

            // 扣除护盾值
            boolean isHitExtra = shield.getExtra() >= damageTaken;
            float damageLeft = shield.takeDamage(damageTaken);

            // 显示伤害数字
            if (damageTaken - damageLeft >= 0.01f) {
                Global.getCombatEngine().addFloatingDamageText(point,
                        damageTaken - damageLeft, DMG_TEXT, ship, target);
            }

            // 阻挡护盾回复
            if (!isHitExtra && !isSoft) {
                shield.setShieldRegenBlocked(true);
            }

            // 计算剩余伤害
            float computed = damageLeft / damage.getDamage();
            if (shield.getExtra() + shield.getCurrent() <= shield.getShieldCap() * 0.05f) {
                computed = (damage.getDamage() - (damageTaken - damageLeft)) / damage.getDamage();
            }
            damage.getModifier().modifyMult(KEY, Math.max(computed, 0.0001f));

            // 光束特殊处理
            if (type == ParamType.BEAM) {
                if (effected.contains((BeamAPI) param)) {
                    effected.set((BeamAPI) param, 1.1f);
                } else {
                    effected.add((BeamAPI) param, 1.1f);
                }
            }

            return KEY;
        }

        return null;
    }

    // ========== 每帧更新 ==========

    @Override
    public void advance(float amount) {
        // 自动清理检查 - 防止内存泄漏
        if (ship == null || !ship.isAlive() || ship.isExpired()) {
            if (ship != null) {
                ship.removeListener(this);
            }
            return;
        }
        
        // 清理光束效果
        if (!effected.getItems().isEmpty()) {
            for (BeamAPI b : effected.getItems()) {
                if (effected.getRemaining(b) < 1f) {
                    b.getDamage().getModifier().unmodify(KEY);
                }
            }
        }
        effected.advance(1);
        beamController.advance(amount);

        // 过载惩罚
        if (ship.getFluxTracker().isOverloadedOrVenting()) {
            stats.getShieldRecoveryRate().modifyMult("GN_Overload",
                    (1f - Moci_GNFieldDefense_Config.VENTING_REGEN_LOSS));
        } else {
            stats.getShieldRecoveryRate().unmodify("GN_Overload");
        }

        // CR过低时禁用
        if (ship.getCurrentCR() <= 0.0001f) {
            shield.setExtra(0);
            shield.setCurrent(0);
            stats.getShieldRegen().modifyMult("NO_CR", 0f);
        }

        if (Global.getCombatEngine().isPaused())
            return;

        // 更新渲染数据
        renderData.advance(amount);
        renderData.setBlockRender(SHIELD_CONTROL && ship.getShield() != null && !ship.getShield().isOn());

        // 检查存活
        if (checkSurvival())
            return;

        // 护盾回复
        if (shield.checkRegen(amount)) {
            shield.setShieldRegenBlocked(false);
            regen(amount);
        }
        regen(amount * shield.getShipStatsShieldRegenSync());

        // 过载免疫
        if (shield.getShieldLevel() >= 0.5) {
            if (ship.getFluxTracker().isOverloadedOrVenting()) {
                if (!isShieldBlockWhileVenting()) {
                    immuneOverload();
                }
            }
        }

        // 装甲保护：护盾未被击破且护盾值足够就保护
        boolean shouldRetain = !shield.isShieldBroken() && shield.getShieldLevel() >= 0.05f;

        if (shouldRetain) {
            if (ship.getFluxTracker().isOverloadedOrVenting()) {
                if (!isShieldBlockWhileVenting()) {
                    retainArmor();
                    if (ship.getHitpoints() < lastFrameHitpoint) {
                        ship.setHitpoints(lastFrameHitpoint);
                    }
                    lastFrameHitpoint = ship.getHitpoints();
                }
            } else {
                retainArmor();
                if (ship.getHitpoints() < lastFrameHitpoint) {
                    ship.setHitpoints(lastFrameHitpoint);
                }
                lastFrameHitpoint = ship.getHitpoints();
            }
        }
        saveArmor();
        lastFrameHitpoint = ship.getHitpoints();

        // 记录系统状态
        lastFrameSystemActive = ship.getSystem() != null && ship.getSystem().isOn();
        lastFramePhaseCloakActive = ship.getPhaseCloak() != null && ship.getPhaseCloak().isOn();

        // EMP免疫
        immuneEMPEffects(shield.getShieldLevel());

        // AI行为调整
        adjustAIBehavior();

        // 护盾控制（总是调用，内部会判断是否有原版护盾）
        controlShield();

        // 自动排气
        autoVent();
    }

    // ========== 辅助方法 ==========

    protected ParamType getType(Object param, DamageAPI damage) {
        if (param instanceof DamagingProjectileAPI) {
            return ParamType.PROJ;
        }
        if (param instanceof BeamAPI) {
            return ParamType.BEAM;
        }
        if (param instanceof EmpArcEntityAPI) {
            return ParamType.ARC;
        }
        return ParamType.SHIP;
    }

    protected float getPierceStrength(DamageAPI damage) {
        float s = damage.getDamage();
        if (damage.getStats() != null) {
            s = damage.getStats().getHitStrengthBonus().computeEffective(damage.getDamage());
        }
        return s;
    }

    protected float preProcessDamage(ParamType type, DamageAPI damage, Vector2f point, Object param) {
        ShipAPI source = null;
        float factor = Moci_GNFieldDefense_Config.DEFAULT_DAMAGE_TAKEN;
        boolean isBeam = false;

        switch (type) {
            case ARC:
                factor *= 0.5f;
                break;
            case BEAM:
                source = ((BeamAPI) param).getSource();
                isBeam = true;
                break;
            case PROJ:
                factor *= 1;
                source = ((DamagingProjectileAPI) param).getSource();
                break;
            case SHIP:
                factor *= 0.8f;
                break;
            default:
                break;
        }

        if (damage.isDps())
            isBeam = true;

        float damageAmount = computeDamageToShield(damage, source, isBeam);
        factor *= shield.getShipStatsDamageTakenSync();
        factor *= stats.getShieldEfficiency().getModifiedValue();
        damageAmount = damageAmount * factor;

        return damageAmount;
    }

    protected float processDamage(float base, ParamType param, DamageAPI damage, Vector2f point) {
        return base; // 可以在这里添加额外的伤害处理逻辑
    }

    private float computeDamageToShield(DamageAPI damage, ShipAPI source, boolean isBeam) {
        float base = damage.getDamage();
        if (isBeam) {
            if (damage.getDamage() > 0) {
                base = damage.getDpsDuration() * damage.getDamage();
            }
        }

        if (base == 0) {
            return 0;
        }

        float mult = 1;
        if (source != null) {
            switch (ship.getHullSize()) {
                case DEFAULT:
                    break;
                case FIGHTER:
                    mult *= source.getMutableStats().getDamageToFighters().getModifiedValue();
                    break;
                case FRIGATE:
                    mult *= source.getMutableStats().getDamageToFrigates().getModifiedValue();
                    break;
                case DESTROYER:
                    mult *= source.getMutableStats().getDamageToDestroyers().getModifiedValue();
                    break;
                case CRUISER:
                    mult *= source.getMutableStats().getDamageToCruisers().getModifiedValue();
                    break;
                case CAPITAL_SHIP:
                    mult *= source.getMutableStats().getDamageToCapital().getModifiedValue();
                    break;
            }
        }

        switch (damage.getType()) {
            case KINETIC:
                mult *= ship.getMutableStats().getKineticDamageTakenMult().getModifiedValue();
                break;
            case HIGH_EXPLOSIVE:
                mult *= ship.getMutableStats().getHighExplosiveDamageTakenMult().getModifiedValue();
                break;
            case FRAGMENTATION:
                mult *= ship.getMutableStats().getFragmentationDamageTakenMult().getModifiedValue();
                mult *= 0.25f;
                break;
            case ENERGY:
                mult *= ship.getMutableStats().getEnergyDamageTakenMult().getModifiedValue();
                break;
            case OTHER:
                break;
        }

        if (isBeam) {
            mult *= ship.getMutableStats().getBeamDamageTakenMult().getModifiedValue();
        } else {
            mult *= ship.getMutableStats().getProjectileDamageTakenMult().getModifiedValue();
        }

        return base * mult;
    }

    protected float engageArmorProcess(float damage, Vector2f point, boolean isBeam, float pierceStrength) {
        float d = damage;
        float piercePower = (isBeam ? pierceStrength * 0.5f : pierceStrength);
        float armorValue = stats.getShieldArmorValue().computeEffective(0);
        float aFactor = piercePower / (piercePower + armorValue);
        aFactor = MathUtils.clamp(aFactor,
                1f - ship.getMutableStats().getMaxArmorDamageReduction().getModifiedValue(), 1f);
        d = d * aFactor;
        return d;
    }

    protected boolean checkSurvival() {
        return !ship.isAlive();
    }

    protected void regen(float amount) {
        float toRegen = amount * shield.getBaseShieldRegen();
        shield.regenShield(toRegen);
    }

    // ========== 视觉效果 ==========

    private void addHitEffect(ParamType type, Object param, Vector2f point,
            float damageTaken, float damageTakenBase) {
        float t = damageTakenBase / 300f;
        if (t < 1.3f)
            t = 1.3f;
        if (t > 3f)
            t = 3f;

        float radius = (float) Math.pow(damageTaken, 0.3333333) * 5f;
        if (radius < 10f)
            radius = 10f;

        if (type == ParamType.BEAM) {
            BeamAPI beam = (BeamAPI) param;
            if (beam != null && !beamController.contains(beam) && beam.getBrightness() > 0) {
                t = 1f;
                radius = Math.min(15f, (float) Math.pow(damageTaken, 0.5) * 5f);
                beamController.add(beam, 1f);
            }
        }

        if (!renderData.getBlockRender()) {
            renderData.getHitData().add(new Moci_GNShieldHitData(ship, point, radius, t), t);
        }

        if (type == ParamType.PROJ) {
            createHitRipple(point, ship.getVelocity(), damageTakenBase,
                    null,
                    VectorUtils.getFacing(VectorUtils.getDirectionalVector(ship.getLocation(), point)),
                    ship.getCollisionRadius());
        }
    }

    private void createHitRipple(Vector2f location, Vector2f velocity, float damage, DamageType type,
            float direction, float shieldRadius) {
        float dmg = damage;
        if (type == DamageType.FRAGMENTATION) {
            dmg *= 0.25f;
        }
        if (type == DamageType.HIGH_EXPLOSIVE) {
            dmg *= 0.5f;
        }
        if (type == DamageType.KINETIC) {
            dmg *= 2f;
        }

        if (dmg < 75f) {
            return;
        }

        float fadeTime = (float) Math.pow(dmg, 0.25) * 0.1f;
        float size = (float) Math.pow(dmg, 0.3333333) * 8f;

        float ratio = Math.min(size / shieldRadius, 1f);
        float arc = 90f - ratio * 14.54136f;

        float start1 = direction - arc;
        if (start1 < 0f) {
            start1 += 360f;
        }
        float end1 = direction + arc;
        if (end1 >= 360f) {
            end1 -= 360f;
        }

        float start2 = direction + arc;
        if (start2 < 0f) {
            start2 += 360f;
        }
        float end2 = direction - arc;
        if (end2 >= 360f) {
            end2 -= 360f;
        }

        RippleDistortion ripple = new RippleDistortion(location, velocity);
        ripple.setSize(size);
        ripple.setIntensity(size * 0.2f);
        ripple.setFrameRate(60f / fadeTime);
        ripple.fadeInSize(fadeTime * 1.2f);
        ripple.fadeOutIntensity(fadeTime);
        ripple.setSize(size * 0.2f);
        ripple.setArc(start1, end1);
        DistortionShader.addDistortion(ripple);

        ripple = new RippleDistortion(location, velocity);
        ripple.setSize(size);
        ripple.setIntensity(size * 0.05f);
        ripple.setFrameRate(60f / fadeTime);
        ripple.fadeInSize(fadeTime * 1.2f);
        ripple.fadeOutIntensity(fadeTime);
        ripple.setSize(size * 0.2f);
        ripple.setArc(start2, end2);
        DistortionShader.addDistortion(ripple);
    }

    // ========== 装甲保护 ==========

    private float[][] armorGridCopy = null;

    public void retainArmor() {
        if (armorGridCopy == null) {
            saveArmor();
        } else {
            ArmorGridAPI a = ship.getArmorGrid();
            if (a == null)
                return;
            float[][] cag = a.getGrid();
            if (cag.length == 0)
                return;
            for (int i = 0; i < cag.length; i++) {
                for (int j = 0; j < cag[i].length; j++) {
                    a.setArmorValue(i, j, Math.max(armorGridCopy[i][j], cag[i][j]));
                }
            }
        }
    }

    public void saveArmor() {
        ArmorGridAPI a = ship.getArmorGrid();
        if (a == null)
            return;
        float[][] cag = a.getGrid();
        if (cag.length == 0)
            return;
        armorGridCopy = new float[cag.length][cag[0].length];
        for (int i = 0; i < cag.length; i++) {
            for (int j = 0; j < cag[i].length; j++) {
                armorGridCopy[i][j] = cag[i][j];
            }
        }
    }

    // ========== 特殊效果 ==========

    public void immuneOverload() {
        if (ship.getFluxTracker().isOverloaded() && ship.getShield() == null) {
            ship.getFluxTracker().stopOverload();
            if (ship.getPhaseCloak() != null && lastFramePhaseCloakActive) {
                if (ship.getPhaseCloak().isCoolingDown()) {
                    ship.getPhaseCloak().setCooldownRemaining(0f);
                }
                if (ship.getPhaseCloak().getMaxAmmo() > 0 &&
                        ship.getPhaseCloak().getAmmo() < ship.getPhaseCloak().getMaxAmmo()) {
                    ship.getPhaseCloak().setAmmo(ship.getPhaseCloak().getAmmo() + 1);
                }
            }
            if (ship.getSystem() != null && lastFrameSystemActive) {
                if (ship.getSystem().isCoolingDown()) {
                    ship.getSystem().setCooldownRemaining(0f);
                }
                if (ship.getSystem().getMaxAmmo() > 0 &&
                        ship.getSystem().getAmmo() < ship.getSystem().getMaxAmmo()) {
                    ship.getSystem().setAmmo(ship.getSystem().getAmmo() + 1);
                }
            }
        }
    }

    public void immuneEMPEffects(float level) {
        float EMP_REDUCTION = 0f;
        if (level < 0.25f) {
            EMP_REDUCTION = (0.25f - level) * 4f;
        }
        ship.getMutableStats().getEmpDamageTakenMult().modifyMult(KEY, EMP_REDUCTION);
    }

    // ========== AI行为 ==========

    private void adjustAIBehavior() {
        if (shield.getCurrent() + shield.getExtra() > 1000f + ship.getHullSize().ordinal() * 1000f) {
            if (ship.getShipAI() != null) {
                ShipwideAIFlags flags = ship.getAIFlags();
                if (flags == null)
                    return;
                // 只有在未过载时才给予激进指令
                if (ship.getVariant().hasHullMod(HullMods.SAFETYOVERRIDES) 
                        && !ship.getFluxTracker().isOverloadedOrVenting()) {
                    flags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, 0.5f);
                    flags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_AVOID_BORDER, 1f);
                }
            }
        }
        
        // 护盾容量大于80%时，如果没有安装机动兵器船插且未过载，给予激进AI行为
        if (shield.getShieldLevel() >= 0.75f && !ship.getVariant().hasHullMod("Moci_MobileSuitsIDcard")
                && !ship.getFluxTracker().isOverloadedOrVenting()) {
            ShipwideAIFlags flags = ship.getAIFlags();
            if (flags != null) {
                flags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, 0.5f);
            }
        }
    }

    private void controlShield() {
        // 只在有原版护盾时才控制
        if (ship.getShield() == null) {
            // 没有原版护盾，显示独立状态
            if (ship == Global.getCombatEngine().getPlayerShip()) {
                if (shield.isShieldBroken()) {
                    if (shield.isRestarting()) {
                        // 正在充能
                        Global.getCombatEngine().maintainStatusForPlayerShip("GN_FieldDefense",
                                "graphics/icons/hullsys/fortress_shield.png", Moci_TextLoader.getText(TEXT_ID, "status.title"),
                                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.charging",
                                        Moci_TextLoader.mapOf("percent", String.format("%.0f", shield.getShieldLevel() * 100))), true);
                    } else {
                        // 重启延迟
                        Global.getCombatEngine().maintainStatusForPlayerShip("GN_FieldDefense",
                                "graphics/icons/hullsys/fortress_shield.png", Moci_TextLoader.getText(TEXT_ID, "status.title"),
                                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.restarting",
                                        Moci_TextLoader.mapOf("seconds", String.format("%.1f", shield.getRestartTimeRemaining()))), true);
                    }
                } else if (shield.getShieldLevel() > 0.05f) {
                    Global.getCombatEngine().maintainStatusForPlayerShip("GN_FieldDefense",
                            "graphics/icons/hullsys/fortress_shield.png", Moci_TextLoader.getText(TEXT_ID, "status.title"),
                            String.format("%.0f%%", shield.getShieldLevel() * 100), false);
                } else {
                    Global.getCombatEngine().maintainStatusForPlayerShip("GN_FieldDefense",
                            "graphics/icons/hullsys/fortress_shield.png", Moci_TextLoader.getText(TEXT_ID, "status.title"),
                            Moci_TextLoader.getText(TEXT_ID, "status.depleted"), true);
                }
            }
            return;
        }

        // 有原版护盾，控制其行为
        if (shield.getShieldLevel() <= 0) {
            ship.getShield().toggleOff();
        }
        if (ship.getShipAI() != null) {
            if (ship.getHullLevel() > 0.5f) {
                if (shield.getShieldLevel() <= 0.2f) {
                    ship.getShield().toggleOff();
                }
            }
        }
        stats.getShieldEfficiency().applyMods(ship.getMutableStats().getShieldAbsorptionMult());
        if (ship.getShield().isOn()) {
            ship.getShield().forceFacing(Misc.normalizeAngle(ship.getFacing() + 180f));
            if (ship == Global.getCombatEngine().getPlayerShip()) {
                if (shield.isShieldBroken()) {
                    if (shield.isRestarting()) {
                        // 正在充能
                        Global.getCombatEngine().maintainStatusForPlayerShip("GN_FieldDefense",
                                "graphics/icons/hullsys/fortress_shield.png", Moci_TextLoader.getText(TEXT_ID, "status.title"),
                                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.charging",
                                        Moci_TextLoader.mapOf("percent", String.format("%.0f", shield.getShieldLevel() * 100))), true);
                    } else {
                        // 重启延迟
                        Global.getCombatEngine().maintainStatusForPlayerShip("GN_FieldDefense",
                                "graphics/icons/hullsys/fortress_shield.png", Moci_TextLoader.getText(TEXT_ID, "status.title"),
                                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.restarting",
                                        Moci_TextLoader.mapOf("seconds", String.format("%.1f", shield.getRestartTimeRemaining()))), true);
                    }
                } else {
                    Global.getCombatEngine().maintainStatusForPlayerShip("GN_FieldDefense",
                            "graphics/icons/hullsys/fortress_shield.png", Moci_TextLoader.getText(TEXT_ID, "status.title"),
                            String.format("%.0f%%", shield.getShieldLevel() * 100), false);
                }
            }
        } else {
            if (ship == Global.getCombatEngine().getPlayerShip()) {
                if (shield.isShieldBroken()) {
                    if (shield.isRestarting()) {
                        Global.getCombatEngine().maintainStatusForPlayerShip("GN_FieldDefense",
                                "graphics/icons/hullsys/fortress_shield.png", Moci_TextLoader.getText(TEXT_ID, "status.title"),
                                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.charging",
                                        Moci_TextLoader.mapOf("percent", String.format("%.0f", shield.getShieldLevel() * 100))), true);
                    } else {
                        Global.getCombatEngine().maintainStatusForPlayerShip("GN_FieldDefense",
                                "graphics/icons/hullsys/fortress_shield.png", Moci_TextLoader.getText(TEXT_ID, "status.title"),
                                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.restarting",
                                        Moci_TextLoader.mapOf("seconds", String.format("%.1f", shield.getRestartTimeRemaining()))), true);
                    }
                } else {
                    Global.getCombatEngine().maintainStatusForPlayerShip("GN_FieldDefense",
                            "graphics/icons/hullsys/fortress_shield.png", Moci_TextLoader.getText(TEXT_ID, "status.title"),
                            Moci_TextLoader.getText(TEXT_ID, "status.inactive"), true);
                }
            }
        }
    }

    private void autoVent() {
        if (!(ship.getSystem() != null && ship.getSystem().isActive()) && ship.getAI() != null) {
            if (ship.getFluxTracker().getFluxLevel() > 0.8f && ship.getHullLevel() > 0.8f &&
                    isShieldBlockWhileVenting() && shield.getCurrent() + shield.getExtra() > 4000f) {
                ship.giveCommand(ShipCommand.VENT_FLUX, null, 0);
            }
        }
    }

    public boolean isShieldBlockWhileVenting() {
        return stats.getShieldBlockWhenVenting().getModifiedValue() >= 1;
    }

    // ========== Getter方法 ==========

    public static String getKey() {
        return KEY;
    }

    public Moci_GNFieldShield getShield() {
        return shield;
    }

    public ShipAPI getShip() {
        return ship;
    }

    public Moci_GNFieldStats getStats() {
        return stats;
    }

    public Moci_GNFieldRenderData getRenderData() {
        return renderData;
    }

    public List<Moci_GNShieldHitData> getHitDatas() {
        return renderData.getHitData().getItems();
    }

    // ========== 内部类：护盾数据 ==========

    /**
     * 护盾可变数据
     */
    public class Moci_GNFieldStats {
        private com.fs.starfarer.api.combat.MutableStat shieldCap;
        private com.fs.starfarer.api.combat.MutableStat shieldRegen;
        private com.fs.starfarer.api.combat.MutableStat shieldRecoveryRate;
        private com.fs.starfarer.api.combat.MutableStat shieldRegenCooldown;
        private com.fs.starfarer.api.combat.MutableStat shieldRegenBlock;
        private com.fs.starfarer.api.combat.MutableStat shieldEfficiency;
        private com.fs.starfarer.api.combat.MutableStat shieldRecoveryCost;
        private com.fs.starfarer.api.combat.MutableStat shieldBufferCap;
        private com.fs.starfarer.api.combat.MutableStat shieldBlockWhenVenting;
        private com.fs.starfarer.api.combat.StatBonus shieldArmorValue;

        public Moci_GNFieldStats() {
            ShipAPI.HullSize hullsize = ship.getHullSize();
            shieldCap = new com.fs.starfarer.api.combat.MutableStat(
                    Moci_GNFieldDefense_Config.SHIELD_CAP.get(hullsize));
            shieldRegen = new com.fs.starfarer.api.combat.MutableStat(
                    Moci_GNFieldDefense_Config.REGEN_MAX_SPEED.get(hullsize));
            shieldRecoveryRate = new com.fs.starfarer.api.combat.MutableStat(1);
            shieldRegenCooldown = new com.fs.starfarer.api.combat.MutableStat(
                    Moci_GNFieldDefense_Config.REGEN_CD.get(hullsize));
            shieldRegenBlock = new com.fs.starfarer.api.combat.MutableStat(
                    Moci_GNFieldDefense_Config.LOW_REGEN_STOP_LIMIT);
            shieldEfficiency = new com.fs.starfarer.api.combat.MutableStat(
                    Moci_GNFieldDefense_Config.DEFAULT_DAMAGE_TAKEN);
            shieldRecoveryCost = new com.fs.starfarer.api.combat.MutableStat(
                    Moci_GNFieldDefense_Config.REGEN_FLUX_RATE);
            shieldBufferCap = new com.fs.starfarer.api.combat.MutableStat(
                    Moci_GNFieldDefense_Config.BUFFER_CAP);
            shieldBlockWhenVenting = new com.fs.starfarer.api.combat.MutableStat(
                    Moci_GNFieldDefense_Config.SHIELD_BLOCK_WHEN_VENTING);
            shieldArmorValue = new com.fs.starfarer.api.combat.StatBonus();
        }

        public com.fs.starfarer.api.combat.MutableStat getShieldCap() {
            return shieldCap;
        }

        public com.fs.starfarer.api.combat.MutableStat getShieldRecoveryRate() {
            return shieldRecoveryRate;
        }

        public com.fs.starfarer.api.combat.MutableStat getShieldRegen() {
            return shieldRegen;
        }

        public com.fs.starfarer.api.combat.MutableStat getShieldRegenCooldown() {
            return shieldRegenCooldown;
        }

        public com.fs.starfarer.api.combat.MutableStat getShieldRegenBlock() {
            return shieldRegenBlock;
        }

        public com.fs.starfarer.api.combat.MutableStat getShieldEfficiency() {
            return shieldEfficiency;
        }

        public com.fs.starfarer.api.combat.MutableStat getShieldRecoveryCost() {
            return shieldRecoveryCost;
        }

        public com.fs.starfarer.api.combat.MutableStat getShieldBufferCap() {
            return shieldBufferCap;
        }

        public com.fs.starfarer.api.combat.MutableStat getShieldBlockWhenVenting() {
            return shieldBlockWhenVenting;
        }

        public com.fs.starfarer.api.combat.StatBonus getShieldArmorValue() {
            return shieldArmorValue;
        }
    }

    /**
     * 护盾状态数据
     */
    public class Moci_GNFieldShield {
        private float current;
        private float extra;
        private FaderUtil regenCooldownTimer;
        private boolean engageArmorProcess = false;
        private float buffer = 0;

        // 护盾重启机制
        private boolean shieldBroken = false; // 护盾是否被击破
        private float restartTimer = 0f; // 重启计时器
        private float restartTimeRequired = 0f; // 需要的重启时间
        private boolean isRestarting = false; // 是否正在重启中

        public Moci_GNFieldShield() {
            update();
        }

        public void update() {
            current = getShieldCap();
            extra = 0;
            regenCooldownTimer = new FaderUtil(0, stats.getShieldRegenCooldown().getModifiedValue());
            shieldBroken = false;
            isRestarting = false;
            restartTimer = 0f;
            restartTimeRequired = Moci_GNFieldDefense_Config.SHIELD_RESTART_TIME.get(ship.getHullSize());
        }

        public float getCurrent() {
            return current;
        }

        public float getExtra() {
            return extra;
        }

        public float getShieldCap() {
            return stats.getShieldCap().getModifiedValue() * ship.getMaxFlux();
        }

        public float getBufferCap() {
            return stats.getShieldBufferCap().getModifiedValue() * getShieldCap();
        }

        public float getBaseShieldRegen() {
            return stats.getShieldRegen().getModifiedValue()
                    * ship.getMutableStats().getFluxDissipation().getModifiedValue();
        }

        public float getExtraShieldCap() {
            return getShieldCap() * 0.5f;
        }

        public float getShieldLevel() {
            return getCurrent() / (Math.max(1f, getShieldCap()));
        }

        public float takeDamage(float amount) {
            float damage = amount;
            if (extra >= damage) {
                extra -= damage;
                damage = 0;
            } else {
                damage -= extra;
                extra = 0;
            }
            if (damage > 0) {
                if (current >= damage) {
                    current -= damage;
                    damage = 0;
                } else {
                    damage -= current;
                    current = 0;
                    // 护盾被击破
                    onShieldBroken();
                }
            }
            return damage;
        }

        /**
         * 护盾被击破时调用
         */
        private void onShieldBroken() {
            if (!shieldBroken) {
                shieldBroken = true;
                isRestarting = false;
                restartTimer = 0f;
                // 播放护盾破碎音效（如果需要）
                // Global.getSoundPlayer().playSound("shield_burnout", 1f, 1f,
                // ship.getLocation(), ship.getVelocity());
            }
        }

        public void regenShield(float amount) {
            // 如果护盾被击破，不能回复
            if (shieldBroken && !isRestarting) {
                return;
            }

            // 如果舰船处于相位状态，不能回复护盾
            if (ship.isPhased()) {
                return;
            }

            float actualRegen = amount * stats.getShieldRecoveryRate().getModifiedValue();
            current += actualRegen;
            if (current > getShieldCap()) {
                actualRegen -= (current - getShieldCap());
                current = getShieldCap();
            }
            buffer -= actualRegen * stats.getShieldBufferCap().getModifiedValue();
            if (actualRegen >= 0.1f)
                generateFlux(actualRegen);

            // 检查是否达到重启阈值
            if (shieldBroken && isRestarting) {
                if (getShieldLevel() >= Moci_GNFieldDefense_Config.SHIELD_RESTART_THRESHOLD) {
                    // 护盾重启成功
                    shieldBroken = false;
                    isRestarting = false;
                    restartTimer = 0f;
                    // 播放护盾重启音效（如果需要）
                    // Global.getSoundPlayer().playSound("shield_raise", 1f, 1f, ship.getLocation(),
                    // ship.getVelocity());
                }
            }
        }

        protected void generateFlux(float amount) {
            ship.getFluxTracker().increaseFlux(amount * stats.getShieldRecoveryCost().getModifiedValue(), false);
        }

        public void addExtraShield(float amount) {
            extra += amount;
            if (extra >= getExtraShieldCap()) {
                extra = getExtraShieldCap();
            }
        }

        public float getShieldRegenBlock() {
            return 0;
        }

        public void setShieldRegenBlocked(boolean shouldBlock) {
            if (shouldBlock) {
                regenCooldownTimer.setBrightness(1f);
                regenCooldownTimer.fadeOut();
            } else {
                regenCooldownTimer.setBrightness(0f);
                regenCooldownTimer.forceOut();
            }
        }

        public boolean isShieldRegenBlocked() {
            return regenCooldownTimer.isFadingOut();
        }

        public FaderUtil getRegenCooldownTimer() {
            return regenCooldownTimer;
        }

        public float getShieldRegenTime() {
            return regenCooldownTimer.getDurationOut();
        }

        public void setShieldRegenTime(float newTime) {
            regenCooldownTimer.setDuration(newTime, newTime);
        }

        public boolean checkBlockRegen(float damage) {
            if (damage <= getShieldRegenBlock()) {
                if (buffer < getBufferCap()) {
                    buffer += damage;
                }
                if (buffer > getBufferCap())
                    buffer = getBufferCap();
                return buffer >= getBufferCap();
            } else {
                return true;
            }
        }

        public boolean checkRegen(float amount) {
            // 如果护盾被击破，检查重启计时器
            if (shieldBroken) {
                if (!isRestarting) {
                    // 还在重启延迟中
                    restartTimer += amount;
                    if (restartTimer >= restartTimeRequired) {
                        // 重启延迟结束，开始充能
                        isRestarting = true;
                        restartTimer = 0f;
                    }
                    return false; // 不能回复
                } else {
                    // 正在充能中，可以回复
                    return true;
                }
            }

            // 正常的回复冷却检查
            if (regenCooldownTimer.isFadingOut()) {
                regenCooldownTimer.advance(amount);
                if (!ship.areSignificantEnemiesInRange()) {
                    regenCooldownTimer.advance(0.5f * amount);
                }
            }
            return regenCooldownTimer.isFadedOut();
        }

        public boolean isEngageArmorProcess() {
            return engageArmorProcess;
        }

        public void setEngageArmorProcess(boolean engageArmorProcess) {
            this.engageArmorProcess = engageArmorProcess;
        }

        public float getShipStatsDamageTakenSync() {
            float factor = ship.getMutableStats().getHullDamageTakenMult().getModifiedValue() +
                    ship.getMutableStats().getArmorDamageTakenMult().getModifiedValue() +
                    ship.getMutableStats().getShieldDamageTakenMult().getModifiedValue();
            factor = factor / 3f;

            if (SHIELD_CONTROL) {
                factor = ship.getMutableStats().getShieldDamageTakenMult().getModifiedValue();
            }
            return factor;
        }

        public float getShipStatsShieldRegenSync() {
            return Math.max(0f,
                    ship.getMutableStats().getHardFluxDissipationFraction().getModifiedValue() * 0.4f);
        }

        public void setCurrent(float current) {
            this.current = current;
        }

        public void setExtra(float extra) {
            this.extra = extra;
        }

        public boolean isShieldBroken() {
            return shieldBroken;
        }

        public boolean isRestarting() {
            return isRestarting;
        }

        public float getRestartProgress() {
            if (!shieldBroken)
                return 1.0f;
            if (!isRestarting) {
                // 重启延迟阶段
                return restartTimer / restartTimeRequired;
            } else {
                // 充能阶段
                return getShieldLevel() / Moci_GNFieldDefense_Config.SHIELD_RESTART_THRESHOLD;
            }
        }

        public float getRestartTimeRemaining() {
            if (!shieldBroken)
                return 0f;
            if (!isRestarting) {
                return restartTimeRequired - restartTimer;
            } else {
                // 充能阶段，估算剩余时间
                float neededCharge = Moci_GNFieldDefense_Config.SHIELD_RESTART_THRESHOLD - getShieldLevel();
                float regenRate = getBaseShieldRegen() * stats.getShieldRecoveryRate().getModifiedValue();
                if (regenRate > 0) {
                    return (neededCharge * getShieldCap()) / regenRate;
                }
                return 999f;
            }
        }
    }

    /**
     * 渲染数据
     */
    public class Moci_GNFieldRenderData {
        private TimeoutTracker<Moci_GNShieldHitData> hitData = new TimeoutTracker<Moci_GNShieldHitData>();
        private boolean reverseSpread = false;
        private com.fs.starfarer.api.util.IntervalUtil reverseTimer;
        private float elapsed = 0f;
        private boolean blockRender = false;

        public Moci_GNFieldRenderData() {
        }

        public void advance(float amount) {
            if (!reverseSpread) {
                elapsed += amount;
            } else {
                elapsed -= 24.5f * amount;
                if (reverseTimer != null) {
                    reverseTimer.advance(amount);
                    if (reverseTimer.intervalElapsed()) {
                        reverseTimer = null;
                        reverseSpread = false;
                    }
                }
            }
            if (elapsed >= 14f) {
                elapsed -= 14f;
            }
            if (elapsed < 0f) {
                elapsed += 14f;
            }
            hitData.advance(amount);
        }

        public float getSpreadLevel() {
            return elapsed / 14f;
        }

        public TimeoutTracker<Moci_GNShieldHitData> getHitData() {
            return hitData;
        }

        public void setReverseSpread(boolean reverseSpread) {
            this.reverseSpread = reverseSpread;
        }

        public void setReverseSpreadForTime(boolean reverseSpread, float time) {
            this.reverseSpread = reverseSpread;
            reverseTimer = new com.fs.starfarer.api.util.IntervalUtil(time, time);
        }

        public float getElapsed() {
            return elapsed;
        }

        public com.fs.starfarer.api.util.IntervalUtil getReverseTimer() {
            return reverseTimer;
        }

        public void setBlockRender(boolean blockRender) {
            this.blockRender = blockRender;
        }

        public boolean getBlockRender() {
            return this.blockRender;
        }
    }

    /**
     * 击中数据
     */
    public static class Moci_GNShieldHitData {
        private Vector2f rel;
        private float time;
        private final float size;
        private final float orgT;

        public Moci_GNShieldHitData(ShipAPI ship, Vector2f location, float size, float time) {
            this.rel = Vector2f.sub(location, ship.getLocation(), null);
            VectorUtils.rotate(rel, -ship.getFacing());
            this.orgT = time;
            this.time = time;
            this.size = size;
        }

        public void advance(float amount) {
            time -= amount;
        }

        public boolean shouldExpire() {
            return time <= 0;
        }

        public float getSize() {
            return size;
        }

        public Vector2f getRel() {
            return new Vector2f(rel);
        }

        public float getLevel() {
            return (orgT == 0) ? 0 : time / orgT;
        }
    }
}
