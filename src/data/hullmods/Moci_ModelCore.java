package data.hullmods;

import java.util.HashMap;
import java.util.Map;

public enum Moci_ModelCore {
    CHATGPT("ChatGPT"), CLAUDE("Claude"), DEEPSEEK("DeepSeek"), GEMINI("Gemini"), GROK("Grok");

    public final String coreId;
    public final String hullmodId;
    public final String baseType;
    private static final Map<String, Moci_ModelCore> BY_ITEM = new HashMap<>();
    private static final Map<String, Moci_ModelCore> BY_HULLMOD = new HashMap<>();

    static {
        for (Moci_ModelCore model : values()) {
            BY_ITEM.put(model.coreId, model);
            BY_HULLMOD.put(model.hullmodId, model);
        }
    }

    Moci_ModelCore(String model) {
        coreId = "Moci_AICore_" + model;
        hullmodId = "Moci_" + model + "AISystem";
        baseType = "Moci_LLM_" + model;
    }

    public static Moci_ModelCore forItem(String id) { return BY_ITEM.get(id); }
    public static Moci_ModelCore forHullmod(String id) { return BY_HULLMOD.get(id); }

    public static Moci_ModelCore forType(String id) {
        if (id != null) for (Moci_ModelCore model : values()) {
            if (model.baseType.equals(id) || id.startsWith(model.baseType + "_")) return model;
        }
        return null;
    }
}
