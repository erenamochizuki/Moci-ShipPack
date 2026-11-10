package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 舰载AI配置加载器。
 *
 * 从CSV读取AI类型定义；"船插 -> AI类型"映射由代码内置。
 */
public class Moci_ShipboardAIConfigLoader {

    private static final Logger LOG = Global.getLogger(Moci_ShipboardAIConfigLoader.class);

    private static final String AI_TYPES_PATH = "data/config/Moci_ShipboardAI/Moci_ShipboardAI_Types.csv";
    private static final String DEFAULT_AI_RANK_ID = "Moci_AI_01";
    private static final Map<String, AITypeDefinition> AI_TYPES = new LinkedHashMap<>();
    private static final Map<String, String> HULLMOD_TO_AI_TYPE = new LinkedHashMap<>();

    private static boolean loaded = false;

    public static class AITypeDefinition {
        public final String aiId;
        public final String name;
        public final String portrait;
        public final boolean canRename;
        public final int levelCap;
        public final String personality;
        public final String rankId;
        public final List<String> skillPool;
        public final List<String> defaultSkills;
        public final int maxSkills;
        public final List<String> defaultAuxEffects;
        public final List<String> auxSkillPool;
        public final boolean isEvolved;
        public final boolean hasEvolutionRoute;
        public final int evolutionLevel;
        public final boolean inheritLevel;
        public final boolean inheritSkills;
        public final boolean inheritAux;
        public final String evolvedAiId;
        public final List<String> evolvedAiIds;

        public AITypeDefinition(
                String aiId,
                String name,
                String portrait,
                boolean canRename,
                int levelCap,
                String personality,
                String rankId,
                List<String> skillPool,
                List<String> defaultSkills,
                int maxSkills,
                List<String> defaultAuxEffects,
                List<String> auxSkillPool,
                boolean isEvolved,
                boolean hasEvolutionRoute,
                int evolutionLevel,
                boolean inheritLevel,
                boolean inheritSkills,
                boolean inheritAux,
                List<String> evolvedAiIds
        ) {
            this.aiId = aiId;
            this.name = name;
            this.portrait = portrait;
            this.canRename = canRename;
            this.levelCap = levelCap;
            this.personality = personality;
            this.rankId = rankId;
            this.skillPool = skillPool;
            this.defaultSkills = defaultSkills;
            this.maxSkills = maxSkills;
            this.defaultAuxEffects = defaultAuxEffects;
            this.auxSkillPool = auxSkillPool;
            this.isEvolved = isEvolved;
            this.hasEvolutionRoute = hasEvolutionRoute;
            this.evolutionLevel = evolutionLevel;
            this.inheritLevel = inheritLevel;
            this.inheritSkills = inheritSkills;
            this.inheritAux = inheritAux;
            this.evolvedAiIds = evolvedAiIds == null ? new ArrayList<String>() : evolvedAiIds;
            this.evolvedAiId = this.evolvedAiIds.isEmpty() ? "" : this.evolvedAiIds.get(0);
        }
    }

    public static synchronized void loadAllConfigs() {
        AI_TYPES.clear();
        HULLMOD_TO_AI_TYPE.clear();

        boolean typeLoaded = loadAITypeConfig();
        loadBuiltInHullmodMap();

        if (AI_TYPES.isEmpty()) {
            loadBuiltInFallbackTypes();
        }

        loaded = true;
        LOG.info("舰载AI配置加载完成: aiTypes=" + AI_TYPES.size()
                + ", hullmodMappings=" + HULLMOD_TO_AI_TYPE.size()
                + ", csvTypeLoaded=" + typeLoaded);
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static Map<String, AITypeDefinition> getAllAiTypes() {
        ensureLoaded();
        return new LinkedHashMap<>(AI_TYPES);
    }

    public static Map<String, String> getHullmodToAiTypeMap() {
        ensureLoaded();
        return new LinkedHashMap<>(HULLMOD_TO_AI_TYPE);
    }

    public static AITypeDefinition getAiType(String aiTypeId) {
        ensureLoaded();
        return AI_TYPES.get(Moci_ShipboardAISystemRegistry.getCanonicalAiTypeId(aiTypeId));
    }

    public static String getCanonicalAiTypeId(String aiTypeId) {
        ensureLoaded();
        return Moci_ShipboardAISystemRegistry.getCanonicalAiTypeId(aiTypeId);
    }

    public static String getMappedAiTypeByHullmod(String hullmodId) {
        ensureLoaded();
        return HULLMOD_TO_AI_TYPE.get(hullmodId);
    }

    public static String getDefaultAiRankId() {
        return DEFAULT_AI_RANK_ID;
    }

    public static String resolveAiTypeForMember(FleetMemberAPI member) {
        ensureLoaded();
        if (member == null || member.getVariant() == null) {
            return null;
        }
        java.util.Collection<String> mods = member.getVariant().getHullMods();
        if (mods == null || mods.isEmpty()) {
            return null;
        }

        for (Map.Entry<String, String> entry : HULLMOD_TO_AI_TYPE.entrySet()) {
            if (mods.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static String resolveRankForMember(FleetMemberAPI member) {
        ensureLoaded();
        String aiTypeId = resolveAiTypeForMember(member);
        if (aiTypeId == null || aiTypeId.trim().isEmpty()) {
            return DEFAULT_AI_RANK_ID;
        }
        AITypeDefinition type = getAiType(aiTypeId);
        if (type == null || type.rankId == null || type.rankId.trim().isEmpty()) {
            return DEFAULT_AI_RANK_ID;
        }
        return type.rankId.trim();
    }

    public static boolean hasShipboardAISystem(FleetMemberAPI member) {
        return resolveAiTypeForMember(member) != null;
    }

    // ---------------- 可选立绘配置 Moci_ShipboardAI_Illustrations.json（作战小队弹窗 AI 页用） ----------------
    private static final String ILLUSTRATIONS_PATH = "data/config/Moci_ShipboardAI/Moci_ShipboardAI_Illustrations.json";
    private static final String ILLUSTRATIONS_MOD_ID = "moci_shippack";
    private static final Object ILLUSTRATION_LOCK = new Object();
    /** ai_id → 配置里写的图片路径。第一次调用 getIllustration 时读一次文件，之后不再读。 */
    private static final Map<String, String> ILLUSTRATION_PATHS = new LinkedHashMap<>();
    /** ai_id → 结果：贴图已加载的路径，或 null（没配置 / 加载失败）。同一个 AI 不会重复加载贴图、重复打日志。 */
    private static final Map<String, String> ILLUSTRATION_CACHE = new LinkedHashMap<>();
    private static boolean illustrationsLoaded = false;

    /**
     * 读可选的 Moci_ShipboardAI_Illustrations.json，返回该 AI 的立绘路径（贴图已确保加载）；
     * 没配置、文件不存在或贴图加载失败都返回 null，调用方改用头像。aiId 先按原样查，查不到再按规范 ID（旧 ID 映射）查。
     */
    public static String getIllustration(String aiId) {
        if (aiId == null) {
            return null;
        }
        String key = aiId.trim();
        if (key.isEmpty()) {
            return null;
        }
        synchronized (ILLUSTRATION_LOCK) {
            if (ILLUSTRATION_CACHE.containsKey(key)) {
                return ILLUSTRATION_CACHE.get(key);
            }
            ensureIllustrationsLoaded();
            String path = ILLUSTRATION_PATHS.get(key);
            if (path == null) {
                String canonical = Moci_ShipboardAISystemRegistry.getCanonicalAiTypeId(key);
                if (canonical != null && !canonical.equals(key)) {
                    path = ILLUSTRATION_PATHS.get(canonical);
                }
            }
            String result = path == null ? null : ensureIllustrationTexture(key, path);
            ILLUSTRATION_CACHE.put(key, result);
            return result;
        }
    }

    private static void ensureIllustrationsLoaded() {
        if (illustrationsLoaded) {
            return;
        }
        illustrationsLoaded = true;
        ILLUSTRATION_PATHS.clear();
        ILLUSTRATION_SPECS.clear();
        ILLUSTRATION_STAGES.clear();
        JSONObject root = loadIllustrationsJson();
        if (root == null) {
            return;
        }
        JSONObject table = root.optJSONObject("illustrations");
        if (table == null) {
            LOG.warn("[Moci_ShipboardAI] 立绘配置缺少 illustrations 对象，按空表处理: " + ILLUSTRATIONS_PATH);
            return;
        }
        java.util.Iterator<?> it = table.keys();
        while (it.hasNext()) {
            Object rawKey = it.next();
            if (rawKey == null) {
                continue;
            }
            String jsonKey = String.valueOf(rawKey);
            String id = jsonKey.trim();
            if (id.isEmpty() || id.startsWith("_") || id.startsWith("#") || table.isNull(jsonKey)) {
                continue;
            }
            JSONObject obj = table.optJSONObject(jsonKey);
            if (obj != null) {
                List<IllustrationStage> stages = parseIllustrationStages(id, obj);
                // 只写 stages、顶层没写 image 时，不分等级的那一条就用第一个阶段
                IllustrationSpec spec = cleanIllustrationPath(obj.optString("image", "")) == null && !stages.isEmpty()
                        ? stages.get(0).spec : parseIllustrationSpec(id, obj, null);
                if (spec != null) {
                    ILLUSTRATION_PATHS.put(id, spec.image);
                    ILLUSTRATION_SPECS.put(id, spec);
                    if (!stages.isEmpty()) {
                        ILLUSTRATION_STAGES.put(id, stages);
                    }
                }
                continue;
            }
            String path = cleanIllustrationPath(table.optString(jsonKey, ""));
            if (path != null) {
                ILLUSTRATION_PATHS.put(id, path);
            }
        }
        LOG.info("[Moci_ShipboardAI] 立绘配置加载完成: " + ILLUSTRATION_PATHS.size() + " 条（其中带动画/背景 "
                + ILLUSTRATION_SPECS.size() + " 条，按等级分阶段 " + ILLUSTRATION_STAGES.size() + " 条）");
    }

    private static String cleanIllustrationPath(String raw) {
        if (raw == null) {
            return null;
        }
        String path = raw.trim().replace('\\', '/');
        return path.isEmpty() ? null : path;
    }

    /**
     * 对象写法：image 必填；sheet + cols/rows/frames/fps 是序列帧；backdrop + backdropSpin/backdropAlpha 是背景旋转图。
     * defaults 不为 null 时（stages 里的一个阶段）：没写的 cols/rows/frames/fps/backdrop/backdropSpin/backdropAlpha 沿用顶层；
     * sheet 不沿用（序列帧和静态图是一套，阶段没写 sheet 就只显示它自己的静态图）；backdrop 写空字符串表示这个阶段不要背景。
     */
    private static IllustrationSpec parseIllustrationSpec(String id, JSONObject o, JSONObject defaults) {
        String image = cleanIllustrationPath(o.optString("image", ""));
        if (image == null) {
            LOG.warn("[Moci_ShipboardAI] 立绘配置 " + id + " 缺少 image，已忽略");
            return null;
        }
        String sheet = cleanIllustrationPath(o.optString("sheet", ""));
        int cols = inheritInt(o, defaults, "cols", 0);
        int rows = inheritInt(o, defaults, "rows", 0);
        int frames = inheritInt(o, defaults, "frames", cols * rows);
        float fps = (float) inheritDouble(o, defaults, "fps", 3.0);
        if (sheet != null && (cols <= 0 || rows <= 0 || frames <= 1 || frames > cols * rows || fps <= 0f)) {
            LOG.warn("[Moci_ShipboardAI] 立绘配置 " + id + " 的序列帧参数无效（cols=" + cols + ", rows=" + rows
                    + ", frames=" + frames + ", fps=" + fps + "），只显示静态图");
            sheet = null;
        }
        JSONObject backdropSource = o.has("backdrop") || defaults == null ? o : defaults;
        String backdrop = cleanIllustrationPath(backdropSource.optString("backdrop", ""));
        float spin = (float) inheritDouble(o, defaults, "backdropSpin", 4.0);
        float alpha = (float) Math.max(0.0, Math.min(1.0, inheritDouble(o, defaults, "backdropAlpha", 1.0)));
        return new IllustrationSpec(image, sheet, cols, rows, frames, Math.min(30f, fps), backdrop, spin, alpha);
    }

    private static int inheritInt(JSONObject o, JSONObject defaults, String key, int fallback) {
        if (o.has(key) || defaults == null) {
            return o.optInt(key, fallback);
        }
        return defaults.optInt(key, fallback);
    }

    private static double inheritDouble(JSONObject o, JSONObject defaults, String key, double fallback) {
        if (o.has(key) || defaults == null) {
            return o.optDouble(key, fallback);
        }
        return defaults.optDouble(key, fallback);
    }

    /**
     * 可选的 "stages": [{ "minLevel": 7, "image": …, "sheet": … }, …]：AI 等级 ≥ minLevel 的最后一个阶段生效，
     * 等级低于所有阶段时用第一个阶段。按 minLevel 从小到大排序；minLevel 缺失、重复或 image 缺失的阶段记 warn 后跳过。
     */
    private static List<IllustrationStage> parseIllustrationStages(String id, JSONObject o) {
        List<IllustrationStage> out = new ArrayList<>();
        JSONArray arr = o.optJSONArray("stages");
        if (arr == null) {
            return out;
        }
        Set<Integer> seen = new LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null || !s.has("minLevel")) {
                LOG.warn("[Moci_ShipboardAI] 立绘配置 " + id + " 的第 " + (i + 1) + " 个阶段缺少 minLevel，已忽略");
                continue;
            }
            int minLevel = s.optInt("minLevel", 0);
            if (!seen.add(minLevel)) {
                LOG.warn("[Moci_ShipboardAI] 立绘配置 " + id + " 有两个阶段的 minLevel 都是 " + minLevel + "，后一个已忽略");
                continue;
            }
            IllustrationSpec spec = parseIllustrationSpec(id + "#" + minLevel, s, o);
            if (spec != null) {
                out.add(new IllustrationStage(minLevel, spec));
            }
        }
        out.sort((a, b) -> Integer.compare(a.minLevel, b.minLevel));
        return out;
    }

    /** 按 AI 等级分的一个立绘阶段（贴图未加载）。 */
    private static final class IllustrationStage {
        final int minLevel;
        final IllustrationSpec spec;

        IllustrationStage(int minLevel, IllustrationSpec spec) {
            this.minLevel = minLevel;
            this.spec = spec;
        }
    }

    /** 立绘配置（对象写法的完整内容；字符串写法只有 image）。sheet / backdrop 为 null 表示没有或贴图加载失败。 */
    public static final class IllustrationSpec {
        public final String image;
        public final String sheet;
        public final int cols;
        public final int rows;
        public final int frames;
        public final float fps;
        public final String backdrop;
        public final float backdropSpin;
        public final float backdropAlpha;

        IllustrationSpec(String image, String sheet, int cols, int rows, int frames, float fps,
                         String backdrop, float backdropSpin, float backdropAlpha) {
            this.image = image;
            this.sheet = sheet;
            this.cols = cols;
            this.rows = rows;
            this.frames = frames;
            this.fps = fps;
            this.backdrop = backdrop;
            this.backdropSpin = backdropSpin;
            this.backdropAlpha = backdropAlpha;
        }

        public boolean hasSheet() {
            return sheet != null;
        }

        public boolean hasBackdrop() {
            return backdrop != null;
        }

        IllustrationSpec withTextures(String loadedSheet, String loadedBackdrop) {
            return new IllustrationSpec(image, loadedSheet, cols, rows, frames, fps, loadedBackdrop, backdropSpin, backdropAlpha);
        }
    }

    /** ai_id → 对象写法的配置（未加载贴图）。 */
    private static final Map<String, IllustrationSpec> ILLUSTRATION_SPECS = new LinkedHashMap<>();
    /** ai_id → 结果：静态图、序列帧、背景都已确认加载；null = 没配置或静态图加载失败。 */
    private static final Map<String, IllustrationSpec> ILLUSTRATION_SPEC_CACHE = new LinkedHashMap<>();

    /**
     * 立绘完整配置：静态图（同 {@link #getIllustration}）+ 可选序列帧 + 可选背景旋转图，贴图都已加载。
     * 字符串写法只有静态图；序列帧或背景贴图加载失败时对应字段为 null（记一条 warn），静态图照常可用。
     * 没配置或静态图加载失败返回 null，调用方改用头像。
     */
    public static IllustrationSpec getIllustrationSpec(String aiId) {
        String image = getIllustration(aiId);
        if (image == null) {
            return null;
        }
        String key = aiId.trim();
        synchronized (ILLUSTRATION_LOCK) {
            if (ILLUSTRATION_SPEC_CACHE.containsKey(key)) {
                return ILLUSTRATION_SPEC_CACHE.get(key);
            }
            IllustrationSpec raw = ILLUSTRATION_SPECS.get(key);
            if (raw == null) {
                String canonical = Moci_ShipboardAISystemRegistry.getCanonicalAiTypeId(key);
                if (canonical != null && !canonical.equals(key)) {
                    raw = ILLUSTRATION_SPECS.get(canonical);
                }
            }
            IllustrationSpec result;
            if (raw == null) {
                result = new IllustrationSpec(image, null, 0, 0, 0, 0f, null, 0f, 0f);
            } else {
                String sheet = raw.sheet == null ? null : ensureIllustrationTexture(key + "(序列帧)", raw.sheet);
                String backdrop = raw.backdrop == null ? null : ensureIllustrationTexture(key + "(背景)", raw.backdrop);
                result = raw.withTextures(sheet, backdrop);
            }
            ILLUSTRATION_SPEC_CACHE.put(key, result);
            return result;
        }
    }

    /** ai_id → 按等级分的阶段（未加载贴图）；没写 stages 的 AI 不在表里。 */
    private static final Map<String, List<IllustrationStage>> ILLUSTRATION_STAGES = new LinkedHashMap<>();
    /** "ai_id#阶段序号" → 该阶段贴图已加载的配置；null = 这个阶段的静态图加载失败（调用方退回不分等级的那一条）。 */
    private static final Map<String, IllustrationSpec> ILLUSTRATION_STAGE_CACHE = new LinkedHashMap<>();
    /** 已经成功 loadTexture 的路径：换阶段、不同 AI 共用同一张图时不重复加载。 */
    private static final Set<String> LOADED_ILLUSTRATION_TEXTURES = new LinkedHashSet<>();

    /**
     * 按 AI 等级取立绘：配置里写了 stages 的，取 minLevel ≤ level 的最后一个阶段（等级低于所有阶段时取第一个），
     * 只加载这个阶段的贴图；没写 stages 的和 {@link #getIllustrationSpec(String)} 一样。
     * 阶段的静态图加载失败时退回不分等级的那一条（再失败就返回 null，调用方改用头像）。
     */
    public static IllustrationSpec getIllustrationSpec(String aiId, int level) {
        if (aiId == null || aiId.trim().isEmpty()) {
            return null;
        }
        String key = aiId.trim();
        synchronized (ILLUSTRATION_LOCK) {
            ensureIllustrationsLoaded();
            List<IllustrationStage> stages = ILLUSTRATION_STAGES.get(key);
            if (stages == null) {
                String canonical = Moci_ShipboardAISystemRegistry.getCanonicalAiTypeId(key);
                if (canonical != null && !canonical.equals(key)) {
                    stages = ILLUSTRATION_STAGES.get(canonical);
                }
            }
            if (stages != null && !stages.isEmpty()) {
                int index = 0;
                for (int i = 0; i < stages.size(); i++) {
                    if (stages.get(i).minLevel <= level) {
                        index = i;
                    }
                }
                String cacheKey = key + "#" + index;
                IllustrationSpec loaded;
                if (ILLUSTRATION_STAGE_CACHE.containsKey(cacheKey)) {
                    loaded = ILLUSTRATION_STAGE_CACHE.get(cacheKey);
                } else {
                    IllustrationSpec raw = stages.get(index).spec;
                    String label = key + "(阶段 " + (index + 1) + ")";
                    loaded = null;
                    if (ensureIllustrationTexture(label, raw.image) != null) {
                        String sheet = raw.sheet == null ? null : ensureIllustrationTexture(label + "(序列帧)", raw.sheet);
                        String backdrop = raw.backdrop == null ? null : ensureIllustrationTexture(label + "(背景)", raw.backdrop);
                        loaded = raw.withTextures(sheet, backdrop);
                    }
                    ILLUSTRATION_STAGE_CACHE.put(cacheKey, loaded);
                }
                if (loaded != null) {
                    return loaded;
                }
            }
        }
        return getIllustrationSpec(aiId);
    }

    /** 先按本 mod 读，失败再退回全局路径。文件不存在当作空表，只记一条 info；文件存在但解析失败记一条 warn。 */
    private static JSONObject loadIllustrationsJson() {
        Exception first;
        try {
            return Global.getSettings().loadJSON(ILLUSTRATIONS_PATH, ILLUSTRATIONS_MOD_ID);
        } catch (Exception ex) {
            first = ex;
        }
        try {
            return Global.getSettings().loadJSON(ILLUSTRATIONS_PATH);
        } catch (Exception ex) {
            Exception parse = first instanceof org.json.JSONException ? first
                    : (ex instanceof org.json.JSONException ? ex : null);
            if (parse != null) {
                LOG.warn("[Moci_ShipboardAI] 立绘配置解析失败，按空表处理: " + ILLUSTRATIONS_PATH
                        + ", error=" + parse.getMessage());
            } else {
                LOG.info("[Moci_ShipboardAI] 没有立绘配置，按空表处理（所有 AI 用头像）: " + ILLUSTRATIONS_PATH
                        + ", reason=" + ex.getMessage());
            }
            return null;
        }
    }

    /** 确保立绘贴图已加载；失败记一条 warn 并返回 null（调用方改用头像）。 */
    private static String ensureIllustrationTexture(String aiId, String path) {
        if (LOADED_ILLUSTRATION_TEXTURES.contains(path)) {
            return path;
        }
        try {
            Global.getSettings().loadTexture(path);
            LOADED_ILLUSTRATION_TEXTURES.add(path);
            return path;
        } catch (Exception ex) {
            LOG.warn("[Moci_ShipboardAI] 立绘贴图加载失败，改用头像: aiId=" + aiId + ", path=" + path
                    + ", error=" + ex.getMessage());
            return null;
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            loadAllConfigs();
        }
    }

    private static boolean loadAITypeConfig() {
        try {
            JSONArray csv = Global.getSettings().loadCSV(AI_TYPES_PATH);
            int loadedCount = 0;
            for (int i = 0; i < csv.length(); i++) {
                JSONObject row = csv.getJSONObject(i);
                String aiId = row.optString("ai_id", "").trim();
                if (aiId.isEmpty() || aiId.startsWith("#")) {
                    continue;
                }

                AITypeDefinition def = parseAiTypeRow(row);
                if (def == null) {
                    continue;
                }

                AI_TYPES.put(def.aiId, def);
                loadedCount++;
            }

            validateEvolutionTargets();
            LOG.info("舰载AI类型CSV加载完成: " + loadedCount + " 条");
            return loadedCount > 0;
        } catch (Exception ex) {
            LOG.warn("加载舰载AI类型CSV失败: " + AI_TYPES_PATH + ", error=" + ex.getMessage());
            return false;
        }
    }

    private static AITypeDefinition parseAiTypeRow(JSONObject row) {
        try {
            String aiId = row.optString("ai_id", "").trim();
            String name = row.optString("name", aiId).trim();
            String portrait = row.optString("portrait", "graphics/portraits/ai_pilot/Moci_system99.png").trim();
            boolean canRename = parseBoolean(row.opt("can_rename"), false);
            int levelCap = clamp(row.optInt("level_cap", 7), 1, 20);
            String personality = normalizePersonality(row.optString("personality", Personalities.STEADY));
            String rankId = row.optString("rank", DEFAULT_AI_RANK_ID).trim();
            if (rankId.isEmpty()) {
                rankId = DEFAULT_AI_RANK_ID;
            }

            List<String> skillPool = filterValidSkills(splitSemicolon(row.optString("skill_pool", "")));
            List<String> defaultSkills = filterValidSkills(splitSemicolon(row.optString("default_skills", "")));
            int maxSkills = clamp(row.optInt("max_skills", 5), 1, 12);

            List<String> defaultAux = filterValidAuxEffects(splitSemicolon(row.optString("default_aux_effects", "")));
            List<String> auxPool = filterValidAuxEffects(splitSemicolon(row.optString("aux_skill_pool", "")));

            boolean isEvolved = parseBoolean(row.opt("is_evolved"), false);
            boolean hasEvolutionRoute = parseBoolean(row.opt("has_evolution_route"), false);
            int evolutionLevel = clamp(row.optInt("evolution_level", levelCap), 1, 20);
            boolean inheritLevel = parseBoolean(row.opt("inherit_level"), true);
            boolean inheritSkills = parseBoolean(row.opt("inherit_skills"), true);
            boolean inheritAux = parseBoolean(row.opt("inherit_aux"), true);
            List<String> evolvedAiIds = dedupe(splitSemicolon(row.optString("evolved_ai_id", "")));

            if (skillPool.isEmpty()) {
                skillPool = new ArrayList<>(getDefaultSkillPool());
            }
            if (auxPool.isEmpty()) {
                auxPool = new ArrayList<>(getDefaultAuxPool());
            }
            if (defaultSkills.isEmpty() && !skillPool.isEmpty()) {
                defaultSkills = Arrays.asList(skillPool.get(0));
            }
            if (defaultAux.isEmpty() && !auxPool.isEmpty()) {
                defaultAux = Arrays.asList(auxPool.get(0));
            }

            // 保证默认项在池中
            for (String skill : defaultSkills) {
                if (!skillPool.contains(skill)) {
                    skillPool.add(skill);
                }
            }
            for (String aux : defaultAux) {
                if (!auxPool.contains(aux)) {
                    auxPool.add(aux);
                }
            }

            return new AITypeDefinition(
                    aiId, name, portrait, canRename, levelCap, personality, rankId,
                    dedupe(skillPool), dedupe(defaultSkills), maxSkills,
                    dedupe(defaultAux), dedupe(auxPool),
                    isEvolved, hasEvolutionRoute, evolutionLevel,
                    inheritLevel, inheritSkills, inheritAux, evolvedAiIds
            );
        } catch (Exception ex) {
            LOG.warn("解析舰载AI类型行失败: " + ex.getMessage());
            return null;
        }
    }

    private static void validateEvolutionTargets() {
        for (AITypeDefinition def : AI_TYPES.values()) {
            if (!def.hasEvolutionRoute) {
                continue;
            }
            if (def.evolvedAiIds == null || def.evolvedAiIds.isEmpty()) {
                LOG.warn("舰载AI进化配置缺失目标ID: aiId=" + def.aiId);
                continue;
            }
            for (String targetId : def.evolvedAiIds) {
                if (!AI_TYPES.containsKey(targetId)) {
                    LOG.warn("舰载AI进化配置目标不存在: aiId=" + def.aiId + ", evolvedAiId=" + targetId);
                }
            }
        }
    }

    private static void loadBuiltInFallbackTypes() {
        LOG.warn("使用内置舰载AI类型保底配置");

        List<String> systemSkillPool = new ArrayList<>(Arrays.asList(
                com.fs.starfarer.api.impl.campaign.ids.Skills.TARGET_ANALYSIS,
                com.fs.starfarer.api.impl.campaign.ids.Skills.HELMSMANSHIP,
                com.fs.starfarer.api.impl.campaign.ids.Skills.COMBAT_ENDURANCE,
                com.fs.starfarer.api.impl.campaign.ids.Skills.IMPACT_MITIGATION,
                com.fs.starfarer.api.impl.campaign.ids.Skills.DAMAGE_CONTROL,
                com.fs.starfarer.api.impl.campaign.ids.Skills.FIELD_MODULATION,
                com.fs.starfarer.api.impl.campaign.ids.Skills.SYSTEMS_EXPERTISE
        ));

        AITypeDefinition system99 = new AITypeDefinition(
                "Moci_system99", "99系统", "graphics/portraits/ai_pilot/Moci_system99.png", false, 7,
                Personalities.RECKLESS, DEFAULT_AI_RANK_ID,
                systemSkillPool,
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.TARGET_ANALYSIS),
                5,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_MOBILITY),
                getDefaultAuxPool(),
                false, true, 7, true, true, true, Arrays.asList("Moci_Nine")
        );

        AITypeDefinition nine = new AITypeDefinition(
                "Moci_Nine", "奈因", "graphics/portraits/ai_pilot/Moci_Nine.png", false, 11,
                Personalities.AGGRESSIVE, DEFAULT_AI_RANK_ID,
                getDefaultSkillPool(),
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.TARGET_ANALYSIS),
                7,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_DAMAGE_CONTROL),
                getDefaultAuxPool(),
                true, false, 11, true, true, true, new ArrayList<String>()
        );

        AITypeDefinition haro = new AITypeDefinition(
                "Moci_haro", "哈罗AI", "graphics/portraits/Moci_haro_Base.png", true, 9,
                Personalities.STEADY, DEFAULT_AI_RANK_ID,
                getDefaultSkillPool(),
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.HELMSMANSHIP),
                6,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_ATTITUDE_CONTROL),
                getDefaultAuxPool(),
                false, false, 9, true, true, true, new ArrayList<String>()
        );

        AITypeDefinition alphaRandom = new AITypeDefinition(
                "Moci_ARP_AlphaRandom", "阿尔法舰载AI", "graphics/icons/cargo/ai_core_alpha.png", false, 9,
                Personalities.STEADY, DEFAULT_AI_RANK_ID,
                getDefaultSkillPool(),
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.TARGET_ANALYSIS),
                6,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_DAMAGE_MITIGATION),
                getDefaultAuxPool(),
                false, false, 9, true, true, true, new ArrayList<String>()
        );

        AITypeDefinition takao = new AITypeDefinition(
                "Moci_ARP_Takao", "高雄", "graphics/portraits/ai_pilot/Moci_ARP_Takao.png", false, 8,
                Personalities.AGGRESSIVE, DEFAULT_AI_RANK_ID,
                getDefaultSkillPool(),
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.BALLISTIC_MASTERY),
                6,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_TARGETING),
                getDefaultAuxPool(),
                false, false, 8, true, true, true, new ArrayList<String>()
        );

        AITypeDefinition kongou = new AITypeDefinition(
                "Moci_ARP_Kongou", "金刚", "graphics/portraits/ai_pilot/Moci_ARP_Kongou.png", false, 9,
                Personalities.AGGRESSIVE, DEFAULT_AI_RANK_ID,
                getDefaultSkillPool(),
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.BALLISTIC_MASTERY),
                6,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_TARGETING),
                getDefaultAuxPool(),
                false, false, 9, true, true, true, new ArrayList<String>()
        );

        AITypeDefinition haruna = new AITypeDefinition(
                "Moci_ARP_Haruna", "榛名", "graphics/portraits/ai_pilot/Moci_ARP_Haruna.png", false, 9,
                Personalities.CAUTIOUS, DEFAULT_AI_RANK_ID,
                getDefaultSkillPool(),
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.FIELD_MODULATION),
                6,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_SHIELD_MODULATION),
                getDefaultAuxPool(),
                false, false, 9, true, true, true, new ArrayList<String>()
        );

        AITypeDefinition omegaRandom = new AITypeDefinition(
                "Moci_ARP_OmegaRandom", "欧米伽舰载AI", "graphics/icons/cargo/ai_omega_core.png", false, 10,
                Personalities.AGGRESSIVE, DEFAULT_AI_RANK_ID,
                getDefaultSkillPool(),
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.TARGET_ANALYSIS),
                7,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_SYSTEM_OVERCLOCK),
                getDefaultAuxPool(),
                false, false, 10, true, true, true, new ArrayList<String>()
        );

        AITypeDefinition iona = new AITypeDefinition(
                "Moci_ARP_Iona", "伊欧娜", "graphics/portraits/ai_pilot/Moci_ARP_Iona.png", false, 10,
                Personalities.STEADY, DEFAULT_AI_RANK_ID,
                getDefaultSkillPool(),
                Arrays.asList(com.fs.starfarer.api.impl.campaign.ids.Skills.TARGET_ANALYSIS),
                7,
                Arrays.asList(Moci_ShipboardAIAuxEffectRegistry.AUX_FLUX_STABILITY),
                getDefaultAuxPool(),
                false, false, 10, true, true, true, new ArrayList<String>()
        );

        AI_TYPES.put(system99.aiId, system99);
        AI_TYPES.put(nine.aiId, nine);
        AI_TYPES.put(haro.aiId, haro);
        AI_TYPES.put(alphaRandom.aiId, alphaRandom);
        AI_TYPES.put(takao.aiId, takao);
        AI_TYPES.put(kongou.aiId, kongou);
        AI_TYPES.put(haruna.aiId, haruna);
        AI_TYPES.put(omegaRandom.aiId, omegaRandom);
        AI_TYPES.put(iona.aiId, iona);
    }

    private static void loadBuiltInHullmodMap() {
        HULLMOD_TO_AI_TYPE.put(Moci_System99.HULLMOD_ID, Moci_System99.AI_TYPE_ID);
        HULLMOD_TO_AI_TYPE.put(Moci_HaroAISystem.HULLMOD_ID, Moci_HaroAISystem.AI_TYPE_ID);
        HULLMOD_TO_AI_TYPE.put(Moci_AlphaCoreAISystem.HULLMOD_ID, Moci_AlphaCoreAISystem.AI_TYPE_ID);
        HULLMOD_TO_AI_TYPE.put(Moci_OmegaCoreAISystem.HULLMOD_ID, Moci_OmegaCoreAISystem.AI_TYPE_ID);
    }

    private static List<String> getDefaultSkillPool() {
        return new ArrayList<>(Arrays.asList(
                com.fs.starfarer.api.impl.campaign.ids.Skills.COMBAT_ENDURANCE,
                com.fs.starfarer.api.impl.campaign.ids.Skills.HELMSMANSHIP,
                com.fs.starfarer.api.impl.campaign.ids.Skills.ENERGY_WEAPON_MASTERY,
                com.fs.starfarer.api.impl.campaign.ids.Skills.BALLISTIC_MASTERY,
                com.fs.starfarer.api.impl.campaign.ids.Skills.FIELD_MODULATION,
                com.fs.starfarer.api.impl.campaign.ids.Skills.TARGET_ANALYSIS,
                com.fs.starfarer.api.impl.campaign.ids.Skills.IMPACT_MITIGATION,
                com.fs.starfarer.api.impl.campaign.ids.Skills.DAMAGE_CONTROL,
                com.fs.starfarer.api.impl.campaign.ids.Skills.POINT_DEFENSE,
                com.fs.starfarer.api.impl.campaign.ids.Skills.MISSILE_SPECIALIZATION,
                com.fs.starfarer.api.impl.campaign.ids.Skills.SYSTEMS_EXPERTISE,
                com.fs.starfarer.api.impl.campaign.ids.Skills.COORDINATED_MANEUVERS
        ));
    }

    private static List<String> getDefaultAuxPool() {
        return new ArrayList<>(Arrays.asList(
                Moci_ShipboardAIAuxEffectRegistry.AUX_MOBILITY,
                Moci_ShipboardAIAuxEffectRegistry.AUX_FLUX_STABILITY,
                Moci_ShipboardAIAuxEffectRegistry.AUX_TARGETING,
                Moci_ShipboardAIAuxEffectRegistry.AUX_DAMAGE_MITIGATION,
                Moci_ShipboardAIAuxEffectRegistry.AUX_DAMAGE_CONTROL,
                Moci_ShipboardAIAuxEffectRegistry.AUX_SHIELD_MODULATION,
                Moci_ShipboardAIAuxEffectRegistry.AUX_ATTITUDE_CONTROL,
                Moci_ShipboardAIAuxEffectRegistry.AUX_SYSTEM_OVERCLOCK,
                Moci_ShipboardAIAuxEffectRegistry.AUX_PD_MATRIX,
                Moci_ShipboardAIAuxEffectRegistry.AUX_RAPID_CYCLE,
                Moci_ShipboardAIAuxEffectRegistry.AUX_RANGEFINDER,
                Moci_ShipboardAIAuxEffectRegistry.AUX_OVERLOAD_DAMPING,
                Moci_ShipboardAIAuxEffectRegistry.AUX_ZERO_FLUX_BOOST,
                Moci_ShipboardAIAuxEffectRegistry.AUX_HARD_FLUX_VENT,
                Moci_ShipboardAIAuxEffectRegistry.AUX_PROJECTILE_OPTIMIZATION,
                Moci_ShipboardAIAuxEffectRegistry.AUX_MISSILE_SUPPORT,
                Moci_ShipboardAIAuxEffectRegistry.AUX_SHIELD_GEOMETRY,
                Moci_ShipboardAIAuxEffectRegistry.AUX_ARMOR_PACKAGE,
                Moci_ShipboardAIAuxEffectRegistry.AUX_HANGAR_COORDINATION,
                Moci_ShipboardAIAuxEffectRegistry.AUX_VENT_EFFICIENCY
        ));
    }

    private static List<String> splitSemicolon(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }
        String[] parts = raw.split(";");
        for (String part : parts) {
            String trimmed = part == null ? "" : part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static List<String> filterValidSkills(List<String> skillIds) {
        List<String> result = new ArrayList<>();
        for (String skillId : skillIds) {
            try {
                if (Global.getSettings().getSkillSpec(skillId) != null) {
                    result.add(skillId);
                }
            } catch (Exception ex) {
                LOG.warn("非法技能ID已跳过: " + skillId);
            }
        }
        return result;
    }

    private static List<String> filterValidAuxEffects(List<String> auxIds) {
        List<String> result = new ArrayList<>();
        for (String auxId : auxIds) {
            if (Moci_ShipboardAIAuxEffectRegistry.isSupported(auxId)) {
                result.add(auxId);
            } else {
                LOG.warn("非法辅助词条ID已跳过: " + auxId);
            }
        }
        return result;
    }

    private static boolean parseBoolean(Object raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        String str = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (str.isEmpty()) {
            return defaultValue;
        }
        return "true".equals(str) || "1".equals(str) || "yes".equals(str);
    }

    private static String normalizePersonality(String personality) {
        if (personality == null || personality.trim().isEmpty()) {
            return Personalities.STEADY;
        }
        String p = personality.trim().toLowerCase(Locale.ROOT);
        if (Personalities.CAUTIOUS.equals(p) ||
                Personalities.TIMID.equals(p) ||
                Personalities.RECKLESS.equals(p) ||
                Personalities.AGGRESSIVE.equals(p) ||
                Personalities.STEADY.equals(p)) {
            return p;
        }
        return Personalities.STEADY;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static List<String> dedupe(List<String> input) {
        return new ArrayList<>(new LinkedHashSet<>(input));
    }
}
