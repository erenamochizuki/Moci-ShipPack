package data.campaign.fighter.ui;

/**
 * 航空中队管理界面的几何计算。
 *
 * <p>这个类刻意不引用任何游戏类：主窗口三栏与右列顶部布局都从这里取几何，
 * 而 {@link #main(String[])} 可以在没有游戏资源的情况下独立运行几何自检。
 */
final class Moci_AirSquadronLayout {
    static final float MIN_EDITOR_HEIGHT = 320f;

    // ===== 三栏宽度比例 =====
    /**
     * 三栏宽度比例（相对 content 去掉左右各 20px 后的可用宽度 usable = width - 40）。
     *
     * <p>三个常量之和必须等于 1（自检里有断言），并且都大于 0，否则右栏会算出负宽。
     * <ul>
     *   <li>{@link #CARRIER_RATIO}：左栏「母舰」，调大 = 舰船预览变大，中/右两栏同步变窄；</li>
     *   <li>{@link #MIDDLE_RATIO}：中栏「机库联队」，调大 = 联队卡片变宽，右栏（主操作区）变窄；</li>
     *   <li>{@link #EDITOR_RATIO}：右栏「装配工作台」。</li>
     * </ul>
     * 实际使用时右栏宽度 = usable - 左栏 - 中栏（减法而非乘法），这样即使三个常量因手改
     * 出现微小误差，三栏加起来也正好填满、不会溢出。可用宽度本身会被
     * {@code Math.max(320f, screenWidth - 64f)} 夹住，右栏在出现外层滚动条时再让出 18px，
     * 因此极小分辨率也不会算出 0 宽。
     */
    static final float CARRIER_RATIO = 0.23f;
    static final float MIDDLE_RATIO = 0.22f;
    static final float EDITOR_RATIO = 0.55f;

    /** 三栏之间的横向间隔（与主窗口 rebuild() 里的 gap 保持一致）。 */
    static final float COLUMN_GAP = 10f;

    // ===== 右列顶部一行：「武器改装」列表 + 舰载机预览 + 战术改装 =====
    /** 舰载机预览占工作台宽度的比例。用户要求这块尺寸/位置保持不变，不要改。 */
    static final float WORKBENCH_PREVIEW_RATIO = 0.42f;
    /** 预览与右侧内容之间的间隔。 */
    static final float WORKBENCH_GAP = 8f;
    /**
     * 「武器改装」武器名列表占「预览右侧剩余宽度」的比例。
     * 旧布局里它占满全部剩余宽度；改成 0.5 正好缩减为原来的一半（用户要求 -50%），
     * 腾出的右半边用于新增「战术改装」图标区。
     */
    static final float WORKBENCH_NAMES_RATIO = 0.50f;
    /** 武器名列表的最小宽度兜底；低于它中文武器名会被截断，但不会横向溢出。 */
    static final float MIN_WEAPON_NAMES_WIDTH = 88f;
    /** 战术改装区一行 3 个图标，这是图标的标准边长。 */
    static final float TACTICAL_SLOT = 40f;
    /** 战术改装图标的间隔。 */
    static final float TACTICAL_GAP = 6f;
    /** 战术改装区一行 3 个图标所需的最小宽度：3 个图标 + 2 个间隔 + 少量内边距。 */
    static final float MIN_TACTICAL_WIDTH = 3f * TACTICAL_SLOT + 2f * TACTICAL_GAP + 4f;
    record Editor(float height, float topHeight, float weaponsHeight, float summaryHeight) {}
    record Header(float bodyY, float titleWidth, float gaugeX, float gaugeY, float gaugeWidth,
                  float textX, float textY, float textWidth) {}

    // ===== 顶部能量条调整参数（单位：UI 像素） =====
    // X 增大向右移动，Y 增大向下移动；宽窗口与窄窗口分别设置。
    static final float ENERGY_BAR_X = 184f;
    static final float ENERGY_BAR_Y = 12f;
    static final float ENERGY_BAR_MAX_WIDTH = 260f; // 总宽上限；实际宽度还受窗口可用空间限制。
    static final float ENERGY_BAR_WIDTH_RATIO = 0.45f; // 可用空间分配给能量条的比例。
    static final float ENERGY_BAR_HEIGHT = 25f; // 总高度；所有格子共用此高度。
    static final float ENERGY_BAR_NARROW_X = 12f; // 窗口宽度小于 720 时使用下面四项。
    static final float ENERGY_BAR_NARROW_Y = 49f;
    static final float ENERGY_BAR_NARROW_MAX_WIDTH = 220f;
    static final float ENERGY_BAR_NARROW_WIDTH_RATIO = 0.43f;
    // 移动/加宽能量条时，右侧数值文字跟随其右边缘；向下移动/加高时正文自动让位。
    // 格子数量、格间距见 Moci_AirSquadronEnergyBar 的 SEGMENTS / SEGMENT_GAP。
    static Header header(float width) {
        float available = width - 24f;
        boolean narrow = width < 720f;
        float x = narrow ? ENERGY_BAR_NARROW_X : ENERGY_BAR_X;
        float y = narrow ? ENERGY_BAR_NARROW_Y : ENERGY_BAR_Y;
        float gaugeWidth = narrow
                ? Math.min(ENERGY_BAR_NARROW_MAX_WIDTH, (available - 12f) * ENERGY_BAR_NARROW_WIDTH_RATIO)
                : Math.min(ENERGY_BAR_MAX_WIDTH, (available - 232f) * ENERGY_BAR_WIDTH_RATIO);
        float textX = x + gaugeWidth + 12f;
        float textY = y - 1f;
        float bodyY = Math.max(narrow ? 84f : 48f,
                Math.max(y + ENERGY_BAR_HEIGHT + 4f, textY + 36f + 4f));
        return new Header(bodyY, narrow ? available : 164f, x, y, gaugeWidth,
                textX, textY, width - 24f - textX);
    }
    record Carrier(float height, float iconSize, float imageTop, float infoTop) {}

    /**
     * 右列顶部一行的水平划分结果（宽度以工作台宽度为基准，不含工作台内边距）。
     *
     * @param previewWidth  舰载机预览宽度（保持 {@link #WORKBENCH_PREVIEW_RATIO} 不变）
     * @param weaponWidth   「武器改装」武器名列表宽度（已相对旧布局缩减 50%）
     * @param tacticalX     战术改装区左边相对工作台左边缘的偏移
     * @param tacticalWidth 战术改装区宽度
     * @param slotSize      战术改装图标边长（极窄屏退化时跟随宽度缩小）
     */
    record TopWorkbench(float previewWidth, float weaponWidth, float tacticalX,
            float tacticalWidth, float slotSize) {}

    record Transform(float originX, float originY, float scale) {
        float mapX(float x) { return originX + x * scale; }
        float mapY(float y) { return originY + y * scale; }
    }

    static Transform fit(float minX, float minY, float maxX, float maxY, float x, float y, float w, float h) {
        float scale = Math.min(w / Math.max(1f, maxX - minX), h / Math.max(1f, maxY - minY));
        return new Transform(x + w / 2f - (minX + maxX) * scale / 2f,
                y + h / 2f - (minY + maxY) * scale / 2f, scale);
    }

    static boolean circleHit(float dx, float dy, float radius) { return dx * dx + dy * dy <= radius * radius; }

    static boolean rectHit(float px, float py, float x, float y, float w, float h) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }

    static Carrier carrier(float viewportHeight, float width) {
        float h = Math.max(280f, viewportHeight);
        float icon = Math.max(48f, Math.min(width - 16f, h - 206f));
        return new Carrier(h, icon, 82f + Math.max(0f, (h - 206f - icon) / 2f), h - 104f);
    }

    static Editor editor(float viewportHeight) {
        float h = Math.max(MIN_EDITOR_HEIGHT, viewportHeight);
        // 让舰载机/槽位工作台占据编辑器上半部分的视觉重量；
        // 下面的武器网格也需要真实视口，尤其是游戏常见的 16:9 分辨率。
        float top = Math.min(380f, Math.max(112f, h * 0.46f));
        float summary = Math.min(118f, Math.max(44f, h * 0.12f));
        float weapons = h - top - 20f - summary - 46f;
        if (weapons < 96f) {
            weapons = 96f;
            top = Math.max(112f, h - weapons - 20f - summary - 46f);
        }
        return new Editor(h, top, weapons, summary);
    }

    /**
     * 计算右列顶部一行（舰载机预览 / 武器改装名 / 战术改装）的几何。
     *
     * <p>正常宽度下：预览固定占 {@link #WORKBENCH_PREVIEW_RATIO}，剩下的水平空间按
     * {@link #WORKBENCH_NAMES_RATIO}=0.5 对半分给「武器名列表」和「战术改装区」，
     * 也就是武器名列表相对旧布局（占满剩余宽度）正好缩减 50%。
     * <p>窄屏兜底：某一边被最小宽度挤压时先保住两边的最小宽度；如果连最小宽度都放不下
     * （例如 640x360 这种极端分辨率），退化成 1:1 均分并按宽度缩小图标，
     * 保证任何情况下都不重叠、不横向溢出。
     */
    static TopWorkbench topWorkbench(float workWidth) {
        float preview = Math.max(80f, workWidth * WORKBENCH_PREVIEW_RATIO);
        float remaining = Math.max(1f, workWidth - preview - WORKBENCH_GAP);
        float weapon;
        float tactical;
        if (remaining < MIN_WEAPON_NAMES_WIDTH + MIN_TACTICAL_WIDTH + WORKBENCH_GAP) {
            // 退化分支：可用宽度不足，两列均分剩余宽度，图标边长跟随战术区收窄。
            weapon = Math.max(1f, (remaining - WORKBENCH_GAP) * 0.5f);
            tactical = Math.max(1f, remaining - weapon - WORKBENCH_GAP);
        } else {
            weapon = Math.max(MIN_WEAPON_NAMES_WIDTH, remaining * WORKBENCH_NAMES_RATIO);
            weapon = Math.min(weapon, remaining - WORKBENCH_GAP - MIN_TACTICAL_WIDTH);
            tactical = Math.max(MIN_TACTICAL_WIDTH, remaining - weapon - WORKBENCH_GAP);
            if (weapon + WORKBENCH_GAP + tactical > remaining) {
                weapon = Math.max(1f, remaining - WORKBENCH_GAP - tactical);
            }
        }
        float tacticalX = preview + WORKBENCH_GAP + weapon + WORKBENCH_GAP;
        float slot = Math.min(TACTICAL_SLOT,
                Math.max(12f, (tactical - 2f * TACTICAL_GAP - 4f) / 3f));
        return new TopWorkbench(preview, weapon, tacticalX, tactical, slot);
    }

    /**
     * M2：把「重建前记下的滚动偏移」钳到新内容的合法区间 [0, max(0, contentHeight - viewHeight)]。
     *
     * <p>原生 ScrollPanelAPI 的 setYOffset 不做钳位，而 rebuild 后各栏内容高度可能变化（换联队、
     * 内容变少）；直接写回旧偏移会露出空白，所以先钳位。偏移/高度不可用（NaN、无穷、负数、
     * 内容比视口还矮）一律回到 0。纯函数，{@link #main(String[])} 里有断言。
     */
    static float clampScroll(float offset, float contentHeight, float viewHeight) {
        if (!Float.isFinite(offset) || offset <= 0f) return 0f;
        if (!Float.isFinite(contentHeight) || !Float.isFinite(viewHeight)) return 0f;
        float max = contentHeight - viewHeight;
        if (!Float.isFinite(max) || max <= 0f) return 0f;
        return Math.min(offset, max);
    }

    /**
     * M2：一次 rebuild 该把偏移恢复到多少——只有同一母舰、同一选择上下文才复用旧偏移，
     * 换母舰 / 换联队 / 空机库 / 首次构建都从 0 开始。纯函数。
     */
    static float restoredScroll(boolean sameContext, float savedOffset, float contentHeight, float viewHeight) {
        return sameContext ? clampScroll(savedOffset, contentHeight, viewHeight) : 0f;
    }

    /** Run with JDK 17 source-file mode; does not initialize game assets or write a save. */
    public static void main(String[] args) {
        if (Math.abs(CARRIER_RATIO + MIDDLE_RATIO + EDITOR_RATIO - 1f) > 1e-4f) {
            throw new AssertionError("三栏比例之和不等于 1：" + (CARRIER_RATIO + MIDDLE_RATIO + EDITOR_RATIO));
        }
        int checks = 0;
        float[][] screens = {{1920, 1080}, {1536, 864}, {1280, 720}, {1024, 576}, {853, 480}, {640, 360}};
        for (float[] screen : screens) {
            float viewport = Math.max(160f, Math.max(300f, screen[1] - 96f) - 104f);
            Editor layout = editor(viewport);
            float slotsBottom = layout.topHeight();
            float weaponsTop = slotsBottom + 10f;
            float weaponsBottom = weaponsTop + layout.weaponsHeight();
            float summaryBottom = weaponsBottom + 5f + layout.summaryHeight();
            float commandsTop = layout.height() - 36f;
            if (layout.height() < viewport || layout.weaponsHeight() < 32f
                    || weaponsTop < slotsBottom || summaryBottom > commandsTop
                    || commandsTop + 30f > layout.height()) {
                throw new AssertionError("Overlapping or inaccessible editor at " + screen[0] + "x" + screen[1]);
            }
            // 视口不足最小高度时必须启用外层原生滚动条，保证底部按钮行仍可到达。
            if (viewport < MIN_EDITOR_HEIGHT && layout.height() <= viewport) throw new AssertionError("Missing outer scroller");

            // ---- 三栏宽度：与主窗口 rebuild() 完全同一套常量与公式 ----
            float dialogWidth = Math.max(320f, screen[0] - 64f);
            float usable = dialogWidth - 40f;
            float carrierWidth = usable * CARRIER_RATIO;
            float middleWidth = usable * MIDDLE_RATIO;
            float editorWidth = usable - carrierWidth - middleWidth;
            if (Math.abs(editorWidth - usable * EDITOR_RATIO) > 0.5f) throw new AssertionError("右栏宽度与比例不一致");
            if (carrierWidth <= 0f || middleWidth <= 0f || editorWidth <= 0f) throw new AssertionError("三栏出现非正宽度");

            Carrier carrier = carrier(viewport, carrierWidth);
            if (carrier.imageTop() < 74f || carrier.imageTop() + carrier.iconSize() + 12f > carrier.infoTop()
                    || carrier.infoTop() + 100f > carrier.height()) throw new AssertionError("Overlapping carrier blocks");
            checks++;

            // ---- 右列顶部：预览宽度不变、武器名列表缩减 50%、战术改装区不越界 ----
            float editorColumn = editorWidth;
            if (viewport < MIN_EDITOR_HEIGHT) editorColumn = Math.max(30f, editorWidth - 18f);
            float workWidth = editorColumn - 26f; // 与 buildFighterWorkbench 的 workWidth 对齐
            TopWorkbench top = topWorkbench(workWidth);
            float remaining = workWidth - top.previewWidth() - WORKBENCH_GAP;
            if (Math.abs(top.previewWidth() - Math.max(80f, workWidth * WORKBENCH_PREVIEW_RATIO)) > 0.01f) {
                throw new AssertionError("舰载机预览宽度被改动 @" + screen[0] + "x" + screen[1]);
            }
            if (top.weaponWidth() > remaining * WORKBENCH_NAMES_RATIO + 0.5f) {
                throw new AssertionError("武器名列表未缩减到原宽度的一半 @" + screen[0] + "x" + screen[1]);
            }
            if (top.weaponWidth() + WORKBENCH_GAP + top.tacticalWidth() > remaining + 0.5f) {
                throw new AssertionError("武器名列表与战术改装区横向溢出 @" + screen[0] + "x" + screen[1]);
            }
            if (top.tacticalX() <= top.previewWidth() + WORKBENCH_GAP
                    || top.tacticalX() + top.tacticalWidth() > workWidth + 0.5f) {
                throw new AssertionError("战术改装区越界 @" + screen[0] + "x" + screen[1]);
            }
            if (3f * top.slotSize() + 2f * TACTICAL_GAP + 4f > top.tacticalWidth() + 0.5f) {
                throw new AssertionError("战术改装图标行放不下 @" + screen[0] + "x" + screen[1]);
            }
            if (top.slotSize() + 24f > layout.topHeight()) throw new AssertionError("战术改装区高度超出工作台");
            checks++;

            // ---- 图标命中：3 个图标互不重叠，且各自完整落在战术改装区里 ----
            for (int i = 0; i < 3; i++) {
                float iconX = top.tacticalX() + i * (top.slotSize() + TACTICAL_GAP);
                if (iconX + top.slotSize() > top.tacticalX() + top.tacticalWidth() + 0.01f) {
                    throw new AssertionError("战术图标横向越界 @" + screen[0] + "x" + screen[1]);
                }
                if (iconX < top.tacticalX() - 0.01f) {
                    throw new AssertionError("战术图标未落在改装区内 @" + screen[0] + "x" + screen[1]);
                }
                checks++;
            }
        }
        for (float scale : new float[]{1f, 1.25f, 1.5f, 2f}) {
            // Engine InputEventAPI coordinates are converted to virtual pixels once, before delivery.
            // Do not divide a delivered event by UI scale a second time.
            float viewportX = 475f, viewportY = 167f, viewportW = 330f, viewportH = 600f;
            for (float scroll : new float[]{0f, 90f, 260f}) {
                float cardX = 483f, cardY = 595f + scroll;
                float pixelX = (cardX + 50f) * scale, pixelY = (cardY + 60f) * scale;
                float eventX = pixelX / scale, eventY = pixelY / scale;
                boolean expected = cardY + 60f < viewportY + viewportH;
                boolean hit = rectHit(eventX, eventY, cardX, cardY, 302f, 144f)
                        && rectHit(eventX, eventY, viewportX, viewportY, viewportW, viewportH);
                if (hit != expected) throw new AssertionError("Scrolled second-card hit failed at scale " + scale);
                checks++;
            }
            Transform art = fit(-38, -44, 49, 31, 875, 550, 220, 196);
            // Representative right arm and head slots: forward X, port Y -> screen (-Y, X).
            float[][] locations = {{-1, -15}, {15, 0}};
            for (float[] slot : locations) {
                float markerX = art.mapX(-slot[1]), markerY = art.mapY(slot[0]);
                float deliveredX = markerX * scale / scale, deliveredY = markerY * scale / scale;
                if (!circleHit(deliveredX - markerX, deliveredY - markerY, 14)
                        || circleHit(15, 0, 14)) throw new AssertionError("Slot marker hit failed");
                checks++;
            }
            float left = art.mapX(-38), right = art.mapX(49);
            if (Math.abs((left + right) / 2f - (875 + 110)) > .01f) throw new AssertionError("Composite not centered");
            checks++;
        }
        // ---- M2 滚动位置恢复：钳位与上下文判断是纯函数，这里锁死边界值 ----
        if (clampScroll(0f, 900f, 400f) != 0f) throw new AssertionError("零偏移不该被恢复");
        if (clampScroll(120f, 900f, 400f) != 120f) throw new AssertionError("合法偏移被改动");
        if (clampScroll(800f, 900f, 400f) != 500f) throw new AssertionError("超界偏移未钳到 contentHeight - viewHeight");
        if (clampScroll(-40f, 900f, 400f) != 0f) throw new AssertionError("负偏移未回到 0");
        if (clampScroll(120f, 300f, 400f) != 0f) throw new AssertionError("内容比视口矮时未回到 0");
        if (clampScroll(Float.NaN, 900f, 400f) != 0f) throw new AssertionError("NaN 偏移未回到 0");
        if (clampScroll(120f, Float.POSITIVE_INFINITY, 400f) != 0f) throw new AssertionError("无穷内容高度未回到 0");
        if (clampScroll(120f, 900f, 0f) != 120f) throw new AssertionError("视口高度为 0 时不该丢掉偏移");
        if (restoredScroll(false, 300f, 900f, 400f) != 0f) throw new AssertionError("换了上下文仍恢复旧偏移");
        if (restoredScroll(true, 300f, 900f, 400f) != 300f) throw new AssertionError("同一上下文未恢复偏移");
        checks += 10;
        System.out.println("Aircraft editor geometry/click checks passed: " + checks + " checks; 6 virtual sizes, 4 UI scales.");
        System.out.println("三栏比例: CARRIER=" + CARRIER_RATIO + " MIDDLE=" + MIDDLE_RATIO + " EDITOR=" + EDITOR_RATIO);
        TopWorkbench sample = topWorkbench(700f);
        System.out.println("右列顶部(工作台 700px): preview=" + sample.previewWidth()
                + " weapons=" + sample.weaponWidth()
                + " tacticalX=" + sample.tacticalX()
                + " tacticalW=" + sample.tacticalWidth()
                + " slot=" + sample.slotSize());
    }
}
