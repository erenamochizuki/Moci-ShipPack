package data.scripts.weapons;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;

import data.scripts.util.Moci_LightWing_Util;

/**
 * 光翼拖尾控制器。
 * 通过武器 spec tag 解析目标槽位和 L/W/A 参数，并把起始/终端颜色、尺寸、滚动配置同步给已注册的渲染插件。
 * tag 格式：MOCI_SLOT_<slot>[_L<length>][_W<width>][_A<alpha>]；A 会按 0~255 缩放当前颜色 alpha。
 */
public class Moci_LightWingWeaponEffect implements EveryFrameWeaponEffectPlugin {
    private static final Logger LOG = Logger.getLogger(Moci_LightWingWeaponEffect.class);

    public static final String TAG_TARGET_SLOT_1 = "MOCI_SLOT_1";
    public static final String TAG_TARGET_SLOT_2 = "MOCI_SLOT_2";
    public static final String TAG_TARGET_SLOT_3 = "MOCI_SLOT_3";
    public static final String TAG_TARGET_SLOT_4 = "MOCI_SLOT_4";
    public static final String TAG_TARGET_SLOT_5 = "MOCI_SLOT_5";
    public static final String TAG_TARGET_SLOT_6 = "MOCI_SLOT_6";

    private static final String SLOT_TAG_PREFIX = "MOCI_SLOT_"; // MOCI_SLOT_<slot>[_L...][_W...][_A...]
    private static final String TARGET_SLOT_PREFIX = "LIGHTWING_"; // LIGHTWING_1 ~ LIGHTWING_6

    private static final Color BASE_START_COLOR = new Color(255, 200, 200, 255); // 光翼根部基础色
    private static final Color BASE_END_COLOR = new Color(255, 50, 50, 200); // 光翼末端基础色
    private static final Color SYSTEM_ACTIVE_START_COLOR = new Color(206, 102, 58, 255); // 系统激活根部色
    private static final Color SYSTEM_ACTIVE_END_COLOR = new Color(206, 126, 79, 255); // 系统激活末端色

    private static class TrailSlotConfig { // 单槽解析结果
        final int slotIndex;
        float trailLength;
        float trailWidth;
        int masterAlpha;
        String lengthSourceTag;
        String widthSourceTag;
        String alphaSourceTag;

        TrailSlotConfig(int slotIndex, float trailLength, float trailWidth, int masterAlpha) {
            this.slotIndex = slotIndex;
            this.trailLength = trailLength;
            this.trailWidth = trailWidth;
            this.masterAlpha = masterAlpha;
        }

        boolean hasExplicitLength() {
            return lengthSourceTag != null;
        }

        boolean hasExplicitWidth() {
            return widthSourceTag != null;
        }

        boolean hasExplicitAlpha() {
            return alphaSourceTag != null;
        }
    }

    private boolean targetSlotsResolved = false;
    private final Set<Integer> targetSlotIndices = new LinkedHashSet<Integer>();
    private final Map<Integer, TrailSlotConfig> slotConfigs = new LinkedHashMap<Integer, TrailSlotConfig>();
    private final Map<Integer, Moci_LightWing_Util.Vbc_engineRender_plugin> pluginsBySlot =
            new LinkedHashMap<Integer, Moci_LightWing_Util.Vbc_engineRender_plugin>();

    private boolean warnedMissingSlotTag = false; // 缺失槽位 tag 只警告一次
    private final Set<String> warnedMissingTargetSlots = new LinkedHashSet<String>(); // 每个目标槽位只警告一次
    private final Set<String> warnedInvalidConfigTags = new LinkedHashSet<String>(); // 非法 tag 警告去重
    private final Set<String> warnedConflictingConfigTags = new LinkedHashSet<String>(); // 冲突字段警告去重

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || weapon == null) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        if (!targetSlotsResolved) {
            resolveTargetSlotIndices(weapon, targetSlotIndices);
            targetSlotsResolved = true;

            if (targetSlotIndices.isEmpty()) {
                warnMissingSlotTag(weapon);
                return;
            }
        }

        if (targetSlotIndices.isEmpty()) {
            return;
        }

        for (int slotIndex : getRenderRegistrationOrder()) {
            Moci_LightWing_Util.Vbc_engineRender_plugin plugin =
                    getOrCreatePluginForSlot(engine, weapon, ship, slotIndex);
            if (plugin != null) {
                syncPluginConfig(plugin, ship, slotIndex);
            }
        }
    }

    private Moci_LightWing_Util.Vbc_engineRender_plugin getOrCreatePluginForSlot(
            CombatEngineAPI engine, WeaponAPI sourceWeapon, ShipAPI ship, int slotIndex) {
        Moci_LightWing_Util.Vbc_engineRender_plugin existingPlugin = getExistingPluginForSlot(slotIndex);
        if (existingPlugin != null) {
            return existingPlugin;
        }

        String targetSlotId = getTargetSlotId(slotIndex);
        WeaponAPI targetWeapon = findWeaponBySlotId(ship, targetSlotId);
        WeaponSlotAPI targetSlot = null;

        if (targetWeapon == null) {
            targetSlot = findSlotById(ship, targetSlotId);
            if (targetSlot == null) {
                warnMissingTargetWeapon(sourceWeapon, ship, targetSlotId);
                return null;
            }
        }

        Moci_LightWing_Util.Vbc_engineRender_plugin plugin = new Moci_LightWing_Util.Vbc_engineRender_plugin(ship);
        if (targetWeapon != null) {
            plugin.createTrailEntity(
                    ship,
                    targetWeapon,
                    getCurrentStartColor(ship, slotIndex),
                    getCurrentEndColor(ship, slotIndex),
                    getTrailLength(slotIndex),
                    getTrailWidth(slotIndex));
        } else {
            plugin.createTrailEntity(
                    ship,
                    targetSlot,
                    getCurrentStartColor(ship, slotIndex),
                    getCurrentEndColor(ship, slotIndex),
                    getTrailLength(slotIndex),
                    getTrailWidth(slotIndex));
        }

        syncPluginConfig(plugin, ship, slotIndex);
        engine.addLayeredRenderingPlugin(plugin);
        pluginsBySlot.put(slotIndex, plugin);
        return plugin;
    }

    private Moci_LightWing_Util.Vbc_engineRender_plugin getExistingPluginForSlot(int slotIndex) {
        Moci_LightWing_Util.Vbc_engineRender_plugin plugin = pluginsBySlot.get(slotIndex);
        if (isUsablePlugin(plugin)) {
            return plugin;
        }
        pluginsBySlot.remove(slotIndex);
        return null;
    }

    private boolean isUsablePlugin(Moci_LightWing_Util.Vbc_engineRender_plugin plugin) {
        return plugin != null && !plugin.isExpired();
    }

    private void syncPluginConfig(Moci_LightWing_Util.Vbc_engineRender_plugin plugin, ShipAPI ship, int slotIndex) {
        Color currentStartColor = getCurrentStartColor(ship, slotIndex);
        Color currentEndColor = getCurrentEndColor(ship, slotIndex);

        plugin.setTextureScrollSpeedMult(getTrailScrollSpeedMult());
        plugin.setSystemLengthMult(getSystemTrailLengthMult());
        plugin.setSystemWidthMult(getSystemTrailWidthMult());
        plugin.setSystemScrollSpeedMult(getSystemTrailScrollSpeedMult());
        plugin.setBaseSegmentCount(getBaseTrailSegmentCount());
        plugin.setSegmentReferenceDimensions(getDefaultTrailLength(), getDefaultTrailWidth());
        plugin.setRenderLayer(getTrailRenderLayer());
        plugin.setTrailDimensions(getTrailLength(slotIndex), getTrailWidth(slotIndex));
        plugin.setTrailGradientColors(currentStartColor, currentEndColor);
        plugin.setRootTransitionProfile(
                getTrailRootTransitionFraction(),
                getTrailRootWidthMult(),
                getTrailRootStartAlphaMult(),
                getTrailRootOverlapAlphaMult(),
                getTrailRootOverlapWidthMult());
    }

    private float getSystemEffectLevel(ShipAPI ship) {
        if (ship == null || ship.getSystem() == null) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, ship.getSystem().getEffectLevel()));
    }

    private int clampColorComponent(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 255) {
            return 255;
        }
        return value;
    }

    private float clampUnitFloat(float value) {
        if (Float.isNaN(value) || value <= 0f) {
            return 0f;
        }
        if (value >= 1f) {
            return 1f;
        }
        return value;
    }

    private Color interpolateColor(Color from, Color to, float progress) {
        if (from == null) {
            return to;
        }
        if (to == null) {
            return from;
        }

        float clampedProgress = clampUnitFloat(progress);
        int r = (int) (from.getRed() + (to.getRed() - from.getRed()) * clampedProgress);
        int g = (int) (from.getGreen() + (to.getGreen() - from.getGreen()) * clampedProgress);
        int b = (int) (from.getBlue() + (to.getBlue() - from.getBlue()) * clampedProgress);
        int a = (int) (from.getAlpha() + (to.getAlpha() - from.getAlpha()) * clampedProgress);

        return new Color(
                clampColorComponent(r),
                clampColorComponent(g),
                clampColorComponent(b),
                clampColorComponent(a));
    }

    private Color applyMasterAlpha(Color color, int alpha255) {
        if (color == null) {
            return null;
        }

        int safeAlpha = clampColorComponent(alpha255);
        if (safeAlpha == 255) {
            return color;
        }

        int alpha = clampColorComponent((int) (color.getAlpha() * (safeAlpha / 255f)));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private Color getCurrentStartColor(ShipAPI ship, int slotIndex) {
        Color interpolated = interpolateColor(
                BASE_START_COLOR,
                SYSTEM_ACTIVE_START_COLOR,
                getSystemEffectLevel(ship));
        return applyMasterAlpha(interpolated, getTrailMasterAlpha(slotIndex));
    }

    private Color getCurrentEndColor(ShipAPI ship, int slotIndex) {
        Color interpolated = interpolateColor(
                BASE_END_COLOR,
                SYSTEM_ACTIVE_END_COLOR,
                getSystemEffectLevel(ship));
        return applyMasterAlpha(interpolated, getTrailMasterAlpha(slotIndex));
    }

    // 这些非颜色配置保留 getter，方便战斗内热调。
    private float getDefaultTrailLength() {
        return 240f;
    }

    private float getDefaultTrailWidth() {
        return 58f;
    }

    private int getDefaultTrailMasterAlpha() {
        return 255;
    }

    private int getBaseTrailSegmentCount() {
        return 100;
    }

    private float getTrailLength(int slotIndex) {
        TrailSlotConfig config = slotConfigs.get(slotIndex);
        if (config == null) {
            return getDefaultTrailLength();
        }
        return config.trailLength;
    }

    private float getTrailWidth(int slotIndex) {
        TrailSlotConfig config = slotConfigs.get(slotIndex);
        if (config == null) {
            return getDefaultTrailWidth();
        }
        return config.trailWidth;
    }

    private int getTrailMasterAlpha(int slotIndex) {
        TrailSlotConfig config = slotConfigs.get(slotIndex);
        if (config == null) {
            return getDefaultTrailMasterAlpha();
        }
        return config.masterAlpha;
    }

    private float getTrailScrollSpeedMult() {
        return 1f;
    }

    private float getSystemTrailLengthMult() {
        return 3f;
    }

    private float getSystemTrailWidthMult() {
        return 1.5f;
    }

    private float getSystemTrailScrollSpeedMult() {
        return 1.5f;
    }

    private float getTrailRootTransitionFraction() {
        return 0.18f;
    }

    private float getTrailRootWidthMult() {
        return 0.24f;
    }

    private float getTrailRootStartAlphaMult() {
        return 0.12f;
    }

    private float getTrailRootOverlapAlphaMult() {
        return 0.45f;
    }

    private float getTrailRootOverlapWidthMult() {
        return 1.25f;
    }

    private CombatEngineLayers getTrailRenderLayer() {
        return CombatEngineLayers.ABOVE_SHIPS_LAYER; // 可改成 ABOVE_SHIPS_LAYER / BELOW_SHIPS_LAYER 等
    }

    /**
     * 同一渲染层内依赖插件注册顺序决定覆盖关系。
     * 这里倒序注册，让序号更小的槽位在视觉上压到序号更大的槽位之上。
     */
    private List<Integer> getRenderRegistrationOrder() {
        List<Integer> order = new ArrayList<Integer>(targetSlotIndices);
        Collections.reverse(order);
        return order;
    }

    private void resolveTargetSlotIndices(WeaponAPI weapon, Set<Integer> result) {
        if (result == null) {
            return;
        }
        result.clear();
        slotConfigs.clear();

        if (weapon == null || weapon.getSpec() == null || weapon.getSpec().getTags() == null) {
            return;
        }

        List<String> tags = new ArrayList<String>(weapon.getSpec().getTags());
        Collections.sort(tags);

        for (String tag : tags) {
            TrailSlotConfig parsedConfig = parseSlotConfigTag(weapon, tag);
            if (parsedConfig == null) {
                continue;
            }

            result.add(parsedConfig.slotIndex);
            mergeSlotConfig(weapon, parsedConfig);
        }
    }

    private TrailSlotConfig parseSlotConfigTag(WeaponAPI weapon, String tag) {
        if (tag == null || !tag.startsWith(SLOT_TAG_PREFIX)) {
            return null;
        }

        String suffix = tag.substring(SLOT_TAG_PREFIX.length());
        if (suffix.length() <= 0) {
            warnInvalidSlotConfigTag(weapon, tag, "缺少槽位编号");
            return null;
        }

        String[] parts = suffix.split("_");
        if (parts.length < 1 || parts.length > 4) {
            warnInvalidSlotConfigTag(weapon, tag, "参数段数非法，支持格式为 MOCI_SLOT_<slot>[_L<length>][_W<width>][_A<alpha>]");
            return null;
        }

        Integer slotIndex = tryParseSlotIndex(parts[0]);
        if (slotIndex == null) {
            warnInvalidSlotConfigTag(weapon, tag, "槽位编号非法，只支持 1~6");
            return null;
        }

        TrailSlotConfig config = new TrailSlotConfig(
                slotIndex,
                getDefaultTrailLength(),
                getDefaultTrailWidth(),
                getDefaultTrailMasterAlpha());

        for (int i = 1; i < parts.length; i++) {
            applyParameterToken(weapon, tag, config, parts[i]);
        }

        return config;
    }

    private void applyParameterToken(WeaponAPI weapon, String fullTag, TrailSlotConfig config, String token) {
        if (config == null) {
            return;
        }
        if (token == null || token.length() < 2) {
            warnInvalidSlotConfigTag(weapon, fullTag, "参数段 [" + token + "] 缺少数值，只支持 L/W/A 前缀");
            return;
        }

        char type = Character.toUpperCase(token.charAt(0));
        String numericPart = token.substring(1);

        if (type == 'L') {
            Float parsedLength = tryParseLength(numericPart);
            if (parsedLength == null) {
                warnInvalidSlotConfigTag(weapon, fullTag, "长度参数非法，已退回默认长度");
                return;
            }
            config.trailLength = parsedLength;
            config.lengthSourceTag = fullTag;
            return;
        }

        if (type == 'W') {
            Float parsedWidth = tryParseWidth(numericPart);
            if (parsedWidth == null) {
                warnInvalidSlotConfigTag(weapon, fullTag, "宽度参数非法，已退回默认宽度");
                return;
            }
            config.trailWidth = parsedWidth;
            config.widthSourceTag = fullTag;
            return;
        }

        if (type == 'A') {
            Integer parsedAlpha = tryParseAlpha(numericPart);
            if (parsedAlpha == null) {
                warnInvalidSlotConfigTag(weapon, fullTag, "透明度参数非法，已退回默认透明度");
                return;
            }
            config.masterAlpha = parsedAlpha;
            config.alphaSourceTag = fullTag;
            return;
        }

        warnInvalidSlotConfigTag(weapon, fullTag, "未知参数段 [" + token + "]，只支持 L/W/A 前缀");
    }

    // 同槽位按字段覆盖；冲突时记录一次警告，并使用后解析到的值。
    private void mergeSlotConfig(WeaponAPI weapon, TrailSlotConfig parsedConfig) {
        TrailSlotConfig existing = slotConfigs.get(parsedConfig.slotIndex);
        if (existing == null) {
            slotConfigs.put(parsedConfig.slotIndex, parsedConfig);
            return;
        }

        if (parsedConfig.hasExplicitLength()) {
            if (existing.hasExplicitLength() && Float.compare(existing.trailLength, parsedConfig.trailLength) != 0) {
                warnConflictingSlotConfig(
                        weapon,
                        parsedConfig.slotIndex,
                        "长度",
                        existing.lengthSourceTag,
                        parsedConfig.lengthSourceTag);
            }
            existing.trailLength = parsedConfig.trailLength;
            existing.lengthSourceTag = parsedConfig.lengthSourceTag;
        }

        if (parsedConfig.hasExplicitWidth()) {
            if (existing.hasExplicitWidth() && Float.compare(existing.trailWidth, parsedConfig.trailWidth) != 0) {
                warnConflictingSlotConfig(
                        weapon,
                        parsedConfig.slotIndex,
                        "宽度",
                        existing.widthSourceTag,
                        parsedConfig.widthSourceTag);
            }
            existing.trailWidth = parsedConfig.trailWidth;
            existing.widthSourceTag = parsedConfig.widthSourceTag;
        }

        if (parsedConfig.hasExplicitAlpha()) {
            if (existing.hasExplicitAlpha() && existing.masterAlpha != parsedConfig.masterAlpha) {
                warnConflictingSlotConfig(
                        weapon,
                        parsedConfig.slotIndex,
                        "透明度",
                        existing.alphaSourceTag,
                        parsedConfig.alphaSourceTag);
            }
            existing.masterAlpha = parsedConfig.masterAlpha;
            existing.alphaSourceTag = parsedConfig.alphaSourceTag;
        }
    }

    private Integer tryParseSlotIndex(String token) {
        Integer value = tryParseInteger(token);
        if (value == null || value < 1 || value > 6) {
            return null;
        }
        return value;
    }

    private Float tryParseLength(String token) {
        if (token == null || token.length() <= 0) {
            return null;
        }
        try {
            float value = Float.parseFloat(token);
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                return null;
            }
            return Math.max(0f, value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Float tryParseWidth(String token) {
        return tryParseLength(token);
    }

    private Integer tryParseAlpha(String token) {
        Integer value = tryParseInteger(token);
        if (value == null) {
            return null;
        }
        if (value < 0) {
            return 0;
        }
        if (value > 255) {
            return 255;
        }
        return value;
    }

    private Integer tryParseInteger(String token) {
        if (token == null || token.length() <= 0) {
            return null;
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String getTargetSlotId(int slotIndex) {
        if (slotIndex < 1 || slotIndex > 6) {
            return null;
        }
        return TARGET_SLOT_PREFIX + slotIndex;
    }

    private WeaponAPI findWeaponBySlotId(ShipAPI ship, String targetSlotId) {
        if (ship == null || targetSlotId == null) {
            return null;
        }

        for (WeaponAPI candidate : ship.getAllWeapons()) {
            if (candidate == null || candidate.getSlot() == null) {
                continue;
            }

            if (targetSlotId.equals(candidate.getSlot().getId())) {
                return candidate;
            }
        }

        return null;
    }

    private WeaponSlotAPI findSlotById(ShipAPI ship, String targetSlotId) {
        if (ship == null || ship.getHullSpec() == null || targetSlotId == null) {
            return null;
        }

        for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
            if (slot == null) {
                continue;
            }
            if (targetSlotId.equals(slot.getId())) {
                return slot;
            }
        }

        return null;
    }

    private void warnMissingSlotTag(WeaponAPI weapon) {
        if (warnedMissingSlotTag) {
            return;
        }
        warnedMissingSlotTag = true;

        String weaponId = weapon != null && weapon.getSpec() != null
                ? weapon.getSpec().getWeaponId()
                : "unknown_weapon";

        LOG.warn("Moci_LightWingWeaponEffect: weapon " + weaponId
                + " 缺少目标槽位 tag，请添加例如 "
                + TAG_TARGET_SLOT_1 + " / "
                + TAG_TARGET_SLOT_1 + "_L240_W100_A255 / "
                + TAG_TARGET_SLOT_5 + "_L420_W230_A96");
    }

    private void warnMissingTargetWeapon(WeaponAPI sourceWeapon, ShipAPI ship, String targetSlotId) {
        if (targetSlotId == null || warnedMissingTargetSlots.contains(targetSlotId)) {
            return;
        }
        warnedMissingTargetSlots.add(targetSlotId);

        String sourceWeaponId = sourceWeapon != null && sourceWeapon.getSpec() != null
                ? sourceWeapon.getSpec().getWeaponId()
                : "unknown_weapon";
        String hullId = ship != null && ship.getHullSpec() != null
                ? ship.getHullSpec().getHullId()
                : "unknown_hull";

        LOG.warn("Moci_LightWingWeaponEffect: weapon " + sourceWeaponId
                + " 试图绑定槽位 " + targetSlotId
                + "，但舰船 " + hullId + " 上没有找到这个槽位对应的武器。");
    }

    private void warnInvalidSlotConfigTag(WeaponAPI weapon, String tag, String reason) {
        String warnKey = tag + "|" + reason;
        if (warnedInvalidConfigTags.contains(warnKey)) {
            return;
        }
        warnedInvalidConfigTags.add(warnKey);

        String weaponId = weapon != null && weapon.getSpec() != null
                ? weapon.getSpec().getWeaponId()
                : "unknown_weapon";

        LOG.warn("Moci_LightWingWeaponEffect: weapon " + weaponId
                + " 的 tag [" + tag + "] 配置无效，原因："
                + reason + "。");
    }

    private void warnConflictingSlotConfig(
            WeaponAPI weapon, int slotIndex, String fieldName, String oldTag, String newTag) {
        String warnKey = slotIndex + "|" + fieldName;
        if (warnedConflictingConfigTags.contains(warnKey)) {
            return;
        }
        warnedConflictingConfigTags.add(warnKey);

        String weaponId = weapon != null && weapon.getSpec() != null
                ? weapon.getSpec().getWeaponId()
                : "unknown_weapon";

        LOG.warn("Moci_LightWingWeaponEffect: weapon " + weaponId
                + " 的 LIGHTWING_" + slotIndex
                + " 的" + fieldName
                + "被多个 tag 重复指定，已用 ["
                + newTag + "] 覆盖 ["
                + oldTag + "]。");
    }
}
