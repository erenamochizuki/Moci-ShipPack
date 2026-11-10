package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 「航空中队管理」战术改装船插的公共访问层。
 * <p>
 * 战术改装由 hull_mods.csv 的 tags 列中的 {@code moci_tactical} 标记识别；内置项包括：
 * <ul>
 *     <li>{@link #HARDENED_SHIELD} 强化护盾</li>
 *     <li>{@link #EXPANDED_MAGAZINES} 扩展弹仓</li>
 *     <li>{@link #EXPANDED_MISSILE_RACKS} 扩展发射架</li>
 *     <li>{@link #ADVANCED_OPTICS} 先进光学器件</li>
 *     <li>{@link #HEAVY_ARMOR} 重型装甲</li>
 * </ul>
 * 战术改装分防御组和火力组，每组最多一项；按 tags 判组（见 {@link #group}）。切换一组不影响另一组。
 * 本类只做纯工具调用，不持有状态、不读存档、不依赖 devMode。
 * </p>
 */
public final class Moci_TacticalHullmods {

    /** 在船插规格的 tags（不是 uiTags）中添加此标记即可加入战术改装。 */
    public static final String TAG = "moci_tactical";
    public static final String DEFENSE_TAG = "moci_tactical_defense";
    public static final String OFFENSE_TAG = "moci_tactical_offense";
    public enum Group { DEFENSE, OFFENSE }

    /** 战术改装：强化护盾。 */
    public static final String HARDENED_SHIELD = "moci_tactical_hardened_shield";
    /** 战术改装：扩展弹仓。 */
    public static final String EXPANDED_MAGAZINES = "moci_tactical_expanded_magazines";
    /** 战术改装：扩展发射架。 */
    public static final String EXPANDED_MISSILE_RACKS = "moci_tactical_expanded_missile_racks";
    public static final String ADVANCED_OPTICS = "moci_tactical_advanced_optics";
    public static final String HEAVY_ARMOR = "moci_tactical_heavy_armor";

    /** 战术改装的兜底贴图路径（直接复用原版贴图，不复制文件）。 */
    private static final String FALLBACK_SPRITE_HARDENED_SHIELD = "graphics/hullmods/hardened_shields.png";
    private static final String FALLBACK_SPRITE_EXPANDED_MAGAZINES = "graphics/hullmods/expanded_magazines2.png";
    private static final String FALLBACK_SPRITE_EXPANDED_MISSILE_RACKS = "graphics/hullmods/expanded_missile_racks.png";

    /** 战术改装的兜底显示名，与原版对应船插的名字一致，仅在 CSV/运行时规格缺失时使用。 */
    private static final String FALLBACK_NAME_HARDENED_SHIELD = "强化护盾";
    private static final String FALLBACK_NAME_EXPANDED_MAGAZINES = "扩展弹仓";
    private static final String FALLBACK_NAME_EXPANDED_MISSILE_RACKS = "扩展发射架";

    private Moci_TacticalHullmods() {
    }

    /**
     * 判断已注册的船插是否带有战术改装标记，不使用 ID 白名单。
     *
     * @param hullmodId 船插 id，可为 null
     * @return 是战术改装返回 true
     */
    public static boolean isTactical(String hullmodId) {
        HullModSpecAPI spec = spec(hullmodId);
        return spec != null && spec.hasTag(TAG);
    }

    /**
     * 读取船插的运行时规格。
     *
     * @param hullmodId 船插 id，可为 null
     * @return 已注册的规格；id 为空、settings 尚未注入（加载前或测试环境）或 id 未注册时返回 null
     */
    public static HullModSpecAPI spec(String hullmodId) {
        if (hullmodId == null || hullmodId.isEmpty()) return null;
        if (Global.getSettings() == null) return null;
        // 0.98 引擎的 getHullModSpec 对未注册的 id 返回 null、不抛异常（已用 javap 核对 SpecStore），所以这里不需要 try/catch。
        return Global.getSettings().getHullModSpec(hullmodId);
    }

    /**
     * 取战术改装的图标 sprite 路径，供 UI 直接绘制。
     *
     * @param hullmodId 船插 id，可为 null
     * @return 规格里的 sprite 路径；规格缺失或未配置贴图时退回内置路径；未知 id 返回 null
     */
    public static String sprite(String hullmodId) {
        HullModSpecAPI hullModSpec = spec(hullmodId);
        if (hullModSpec != null) {
            String sprite = hullModSpec.getSpriteName();
            if (sprite != null && !sprite.isEmpty()) return sprite;
        }
        return fallbackSprite(hullmodId);
    }

    /**
     * 取战术改装的显示名，供 UI 在规格缺失时仍能显示条目。
     *
     * @param hullmodId 船插 id，可为 null
     * @return 规格里的显示名；规格缺失或名字为空时退回内置名字；未知 id 返回 null
     */
    public static String displayName(String hullmodId) {
        HullModSpecAPI hullModSpec = spec(hullmodId);
        if (hullModSpec != null) {
            String name = hullModSpec.getDisplayName();
            if (name != null && !name.isEmpty()) return name;
        }
        return fallbackName(hullmodId);
    }

    /**
     * 按 tags 判组：带 {@link #DEFENSE_TAG} 的是防御组，内置的强化护盾和重型装甲即使规格缺失也算防御组；
     * 其余战术改装（包括没有组 tag 的扩展项）都归火力组。
     */
    public static Group group(String id) {
        HullModSpecAPI spec = spec(id);
        return HARDENED_SHIELD.equals(id) || HEAVY_ARMOR.equals(id)
                || (spec != null && spec.hasTag(DEFENSE_TAG)) ? Group.DEFENSE : Group.OFFENSE;
    }

    public static boolean validSelection(List<String> ids) {
        if (ids == null) return false;
        java.util.EnumSet<Group> groups = java.util.EnumSet.noneOf(Group.class);
        for (String id : ids) if (!isTactical(id) || !groups.add(group(id))) return false;
        return true;
    }

    public static List<String> normalize(List<String> ids) {
        List<String> result = new ArrayList<>();
        if (ids != null) for (String id : ids) if (isTactical(id)) result.add(id);
        if (!validSelection(result)) throw new IllegalArgumentException("每组战术改装最多选择一项");
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    public static List<String> toggle(List<String> current, String choice) {
        if (!isTactical(choice)) return current;
        List<String> next = new ArrayList<>(current);
        boolean selected = next.contains(choice);
        next.removeIf(id -> group(id) == group(choice));
        if (!selected) next.add(choice);
        return normalize(next);
    }

    public static void applySelections(ShipVariantAPI variant, List<String> ids) {
        if (variant == null) return;
        List<String> effective = normalize(ids);
        for (String id : new ArrayList<>(variant.getHullMods())) {
            if (isTactical(id)) variant.removeMod(id);
        }
        for (String id : effective) variant.addMod(id);
    }

    public static List<String> selectedAll(ShipVariantAPI variant) {
        if (variant == null) return List.of();
        List<String> ids = new ArrayList<>();
        for (String id : variant.getHullMods()) if (isTactical(id)) ids.add(id);
        ids.sort(String::compareTo);
        return List.copyOf(ids);
    }


    /**
     * 取全部战术改装 id 的只读副本，便于 UI 遍历时避免直接暴露内部列表。
     *
     * @return 按规格表顺序列出所有带标记的船插；不缓存未完成加载的规格表
     */
    public static List<String> all() {
        if (Global.getSettings() == null) return List.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (HullModSpecAPI spec : Global.getSettings().getAllHullModSpecs()) {
            if (spec != null && spec.hasTag(TAG)) ids.add(spec.getId());
        }
        return List.copyOf(ids);
    }

    private static String fallbackSprite(String hullmodId) {
        if (ADVANCED_OPTICS.equals(hullmodId)) return "graphics/hullmods/advanced_optics.png";
        if (HEAVY_ARMOR.equals(hullmodId)) return "graphics/hullmods/heavy_armor.png";
        if (HARDENED_SHIELD.equals(hullmodId)) return FALLBACK_SPRITE_HARDENED_SHIELD;
        if (EXPANDED_MAGAZINES.equals(hullmodId)) return FALLBACK_SPRITE_EXPANDED_MAGAZINES;
        if (EXPANDED_MISSILE_RACKS.equals(hullmodId)) return FALLBACK_SPRITE_EXPANDED_MISSILE_RACKS;
        return null;
    }

    private static String fallbackName(String hullmodId) {
        if (ADVANCED_OPTICS.equals(hullmodId)) return "先进光学器件";
        if (HEAVY_ARMOR.equals(hullmodId)) return "重型装甲";
        if (HARDENED_SHIELD.equals(hullmodId)) return FALLBACK_NAME_HARDENED_SHIELD;
        if (EXPANDED_MAGAZINES.equals(hullmodId)) return FALLBACK_NAME_EXPANDED_MAGAZINES;
        if (EXPANDED_MISSILE_RACKS.equals(hullmodId)) return FALLBACK_NAME_EXPANDED_MISSILE_RACKS;
        return null;
    }
}
