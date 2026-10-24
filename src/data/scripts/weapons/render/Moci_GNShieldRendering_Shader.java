package data.scripts.weapons.render;
import org.moci.filmshield.render.FilmShieldRenderer;
/** Legacy facade. All callers resolve the same combat-scoped renderer as third-party mods. */
public class Moci_GNShieldRendering_Shader {
    public static final String RENDER_KEY=FilmShieldRenderer.RENDER_KEY;
    private static final Moci_GNShieldRendering_Shader FACADE=new Moci_GNShieldRendering_Shader();
    public static Moci_GNShieldRendering_Shader getInstance() { FilmShieldRenderer.getInstance(); return FACADE; }
    public boolean isShaderInitialized() { FilmShieldRenderer r=FilmShieldRenderer.getInstance(); return r!=null&&r.isShaderInitialized(); }
    public static void registerNormalTextureDataCSV(String path) { FilmShieldRenderer.registerNormalTextureDataCSV(path); }
    public static boolean isRegisteredNormalFallbackEnabled() { return FilmShieldRenderer.isRegisteredNormalFallbackEnabled(); }
    public static void setRegisteredNormalFallbackEnabled(boolean value) { FilmShieldRenderer.setRegisteredNormalFallbackEnabled(value); }
    public static String getPerformanceStats() { return FilmShieldRenderer.getPerformanceStats(); }
    public static void resetPerformanceStats() { FilmShieldRenderer.resetPerformanceStats(); }
}
