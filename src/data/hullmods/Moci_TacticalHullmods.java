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
 *     <li>{@link #EXPANDED_MAGAZINES} 弹匣扩容</li>
 *     <li>{@link #EXPANDED_MISSILE_RACKS} 导弹挂架</li>
 * </ul>
 * 单选由 {@link #applyToVariant} 保证：先移除 variant 上所有带标记的战术改装，再写入选中项。
 * 本类只做纯工具调用，不持有状态、不读存档、不依赖 devMode。
 * </p>
 */
public final class Moci_TacticalHullmods {

    /** 在船插规格的 tags（不是 uiTags）中添加此标记即可加入战术改装。 */
    public static final String TAG = "moci_tactical";

    /** 战术改装：强化护盾。 */
    public static final String HARDENED_SHIELD = "moci_tactical_hardened_shield";
    /** 战术改装：弹匣扩容。 */
    public static final String EXPANDED_MAGAZINES = "moci_tactical_expanded_magazines";
    /** 战术改装：导弹挂架。 */
    public static final String EXPANDED_MISSILE_RACKS = "moci_tactical_expanded_missile_racks";

    /** 三个战术改装的兜底贴图路径（直接复用原版贴图，不复制文件）。 */
    private static final String FALLBACK_SPRITE_HARDENED_SHIELD = "graphics/hullmods/hardened_shields.png";
    private static final String FALLBACK_SPRITE_EXPANDED_MAGAZINES = "graphics/hullmods/expanded_magazines2.png";
    private static final String FALLBACK_SPRITE_EXPANDED_MISSILE_RACKS = "graphics/hullmods/expanded_missile_racks.png";

    /** 三个战术改装的兜底显示名，仅在 CSV/运行时规格缺失时使用。 */
    private static final String FALLBACK_NAME_HARDENED_SHIELD = "强化护盾";
    private static final String FALLBACK_NAME_EXPANDED_MAGAZINES = "弹匣扩容";
    private static final String FALLBACK_NAME_EXPANDED_MISSILE_RACKS = "导弹挂架";

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
     * @return 已注册的规格；未注册或读取失败返回 null
     */
    public static HullModSpecAPI spec(String hullmodId) {
        try {
            if (hullmodId == null || hullmodId.isEmpty()) return null;
            if (Global.getSettings() == null) return null;
            return Global.getSettings().getHullModSpec(hullmodId);
        } catch (Throwable t) {
            // 规格表未加载完成、id 未注册或混淆接口变动时都不应打断 UI/服务层
            return null;
        }
    }

    /**
     * 取战术改装的图标 sprite 路径，供 UI 直接绘制。
     *
     * @param hullmodId 船插 id，可为 null
     * @return sprite 路径；未知 id 返回 null
     */
    public static String sprite(String hullmodId) {
        try {
            HullModSpecAPI hullModSpec = spec(hullmodId);
            if (hullModSpec != null) {
                String sprite = hullModSpec.getSpriteName();
                if (sprite != null && !sprite.isEmpty()) return sprite;
            }
        } catch (Throwable t) {
            // 忽略规格读取异常，退回内置兜底路径
        }
        return fallbackSprite(hullmodId);
    }

    /**
     * 取战术改装的显示名，供 UI 在规格缺失时仍能显示条目。
     *
     * @param hullmodId 船插 id，可为 null
     * @return 显示名；未知 id 返回 null
     */
    public static String displayName(String hullmodId) {
        try {
            HullModSpecAPI hullModSpec = spec(hullmodId);
            if (hullModSpec != null) {
                String name = hullModSpec.getDisplayName();
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Throwable t) {
            // 忽略规格读取异常，退回内置兜底名称
        }
        return fallbackName(hullmodId);
    }

    /**
     * 把 variant 上的战术改装切换为指定项。
     * <p>
     * 空选（null 或非战术改装 id）表示移除全部战术改装，即「不选」。写入前会先移除所有带标记的战术插，
     * 因此同一 variant 上最多只会存在一个战术改装。
     * </p>
     *
     * @param variant         战舰/战机联队 variant，可为 null
     * @param selectedIdOrNull 选中的战术改装 id，null 表示清空
     */
    public static void applyToVariant(ShipVariantAPI variant, String selectedIdOrNull) {
        if (variant == null) return;
        try {
            for (String id : new ArrayList<>(variant.getHullMods())) {
                if (isTactical(id)) variant.removeMod(id);
            }
            if (isTactical(selectedIdOrNull)) variant.addMod(selectedIdOrNull);
        } catch (Throwable t) {
            // variant 处于不可修改状态（例如正在被游戏保存）时不应把异常抛给 UI 调用方
        }
    }

    /**
     * 读取 variant 当前的战术改装。
     *
     * @param variant 战舰/战机联队 variant，可为 null
     * @return variant 上第一个带战术改装标记的船插 id，没有则 null
     */
    public static String selected(ShipVariantAPI variant) {
        if (variant == null) return null;
        try {
            for (String id : variant.getHullMods()) {
                if (isTactical(id)) return id;
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    /**
     * 取 variant 上是否已安装任意战术改装。
     *
     * @param variant 战舰/战机联队 variant，可为 null
     * @return 已安装任一战术改装返回 true
     */
    public static boolean hasAny(ShipVariantAPI variant) {
        return selected(variant) != null;
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
        if (HARDENED_SHIELD.equals(hullmodId)) return FALLBACK_SPRITE_HARDENED_SHIELD;
        if (EXPANDED_MAGAZINES.equals(hullmodId)) return FALLBACK_SPRITE_EXPANDED_MAGAZINES;
        if (EXPANDED_MISSILE_RACKS.equals(hullmodId)) return FALLBACK_SPRITE_EXPANDED_MISSILE_RACKS;
        return null;
    }

    private static String fallbackName(String hullmodId) {
        if (HARDENED_SHIELD.equals(hullmodId)) return FALLBACK_NAME_HARDENED_SHIELD;
        if (EXPANDED_MAGAZINES.equals(hullmodId)) return FALLBACK_NAME_EXPANDED_MAGAZINES;
        if (EXPANDED_MISSILE_RACKS.equals(hullmodId)) return FALLBACK_NAME_EXPANDED_MISSILE_RACKS;
        return null;
    }
}
