package data.scripts.weapons.render;

import java.util.HashMap;
import java.util.Map;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

/**
 * 着色器管理器 - 负责加载、编译和管理所有着色器
 * 参考光环src的实现模式
 */
public class Moci_ShaderManager {
    
    private static Moci_ShaderManager instance;
    private Map<String, Integer> shaders = new HashMap<>();
    private boolean openGL20Supported = false;
    private static final Logger LOG = Global.getLogger(Moci_ShaderManager.class);
    
    private Moci_ShaderManager() {
        checkOpenGLSupport();
    }
    
    public static Moci_ShaderManager getInstance() {
        if (instance == null) {
            instance = new Moci_ShaderManager();
        }
        return instance;
    }
    
    private void checkOpenGLSupport() {
        try {
            openGL20Supported = GLContext.getCapabilities().OpenGL20;
        } catch (Exception e) {
            openGL20Supported = false;
            LOG.warn("[MOCI_SHADER] OpenGL capability check failed", e);
        }
    }
    
    /**
     * 创建并编译着色器程序
     * @param vertPath 顶点着色器路径
     * @param fragPath 片段着色器路径
     * @param shaderKey 着色器唯一标识
     * @return 着色器程序ID，失败返回0
     */
    public int createShader(String vertPath, String fragPath, String shaderKey) {
        if (!openGL20Supported) {
            LOG.warn("[MOCI_SHADER] Skip createShader (OpenGL 2.0 not supported)");
            return 0;
        }
        
        if (shaders.containsKey(shaderKey)) {
            return shaders.get(shaderKey);
        }
        
        try {
            // 读取着色器源码
            String vertSource = Global.getSettings().loadText(vertPath);
            String fragSource = Global.getSettings().loadText(fragPath);
            
            // 创建着色器程序
            int programID = createShaderProgram(vertSource, fragSource);
            
            if (programID != 0) {
                shaders.put(shaderKey, programID);
                return programID;
            } else {
                LOG.warn("[MOCI_SHADER] Program link failed key=" + shaderKey);
                return 0;
            }
            
        } catch (Exception e) {
            LOG.error("[MOCI_SHADER] Exception while creating program key=" + shaderKey, e);
            return 0;
        }
    }

    /**
     * 获取已创建的着色器
     * @param shaderKey 着色器标识
     * @return 着色器程序ID，不存在返回0
     */
    public int getShader(String shaderKey) {
        Integer shader = shaders.get(shaderKey);
        return shader != null ? shader : 0;
    }
    
    /**
     * 检查着色器是否存在且有效
     */
    public boolean isShaderValid(String shaderKey) {
        Integer shaderID = shaders.get(shaderKey);
        if (shaderID == null || shaderID == 0) {
            return false;
        }
        
        try {
            return GL20.glIsProgram(shaderID);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 创建单个着色器对象
     */
    private int createShader(String source, int shaderType) {
        int shaderID = GL20.glCreateShader(shaderType);
        GL20.glShaderSource(shaderID, source);
        GL20.glCompileShader(shaderID);
        
        // 检查编译状态
        if (GL20.glGetShaderi(shaderID, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shaderID, GL20.glGetShaderi(shaderID, GL20.GL_INFO_LOG_LENGTH));
            LOG.error("[MOCI_SHADER] Compile failed type=" + shaderType + " log=\n" + log);
            GL20.glDeleteShader(shaderID);
            return 0;
        }
        
        return shaderID;
    }
    
    /**
     * 创建着色器程序
     */
    private int createShaderProgram(String vertSource, String fragSource) {
        int programID = GL20.glCreateProgram();
        int vertShader = createShader(vertSource, GL20.GL_VERTEX_SHADER);
        int fragShader = createShader(fragSource, GL20.GL_FRAGMENT_SHADER);
        
        if (vertShader == 0 || fragShader == 0) {
            Global.getLogger(this.getClass()).error("Failed to compile shaders - vertex: " + vertShader + ", fragment: " + fragShader);
            if (vertShader != 0) GL20.glDeleteShader(vertShader);
            if (fragShader != 0) GL20.glDeleteShader(fragShader);
            GL20.glDeleteProgram(programID);
            return 0;
        }
        
        // 链接程序
        GL20.glAttachShader(programID, vertShader);
        GL20.glAttachShader(programID, fragShader);
        GL20.glLinkProgram(programID);
        
        // 检查链接状态
        if (GL20.glGetProgrami(programID, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String error = GL20.glGetProgramInfoLog(programID, GL20.glGetProgrami(programID, GL20.GL_INFO_LOG_LENGTH));
            Global.getLogger(this.getClass()).error("Shader program linking failed:\n" + error);
            LOG.error("[MOCI_SHADER] Link failed log=\n" + error);
            
            GL20.glDeleteProgram(programID);
            GL20.glDeleteShader(vertShader);
            GL20.glDeleteShader(fragShader);
            return 0;
        }
        
        // 清理着色器对象（程序已链接，不再需要）
        GL20.glDeleteShader(vertShader);
        GL20.glDeleteShader(fragShader);
        
        return programID;
    }
    
    /**
     * 清理所有着色器
     */
    public void cleanup() {
        for (Map.Entry<String, Integer> entry : shaders.entrySet()) {
            try {
                GL20.glDeleteProgram(entry.getValue());
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
        shaders.clear();
    }
    
    public boolean isOpenGL20Supported() {
        return openGL20Supported;
    }
    
    /**
     * 获取着色器统计信息
     */
    public void printShaderStats() {
        // Stats available but not logged to reduce console spam
    }
}
