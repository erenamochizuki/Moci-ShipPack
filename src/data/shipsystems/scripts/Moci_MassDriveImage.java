package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.combat.CombatViewport;
import com.fs.starfarer.combat.entities.Ship;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

final class Moci_MassDriveImage {
    private static int program;
    private static final Map<String, Integer> UNIFORMS = new HashMap<>();
    private static final Set<Moci_MassDriveImage> ALLOCATED = new HashSet<>();
    private static WeakReference<CombatEngineAPI> battle = new WeakReference<>(null);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private int framebuffer;
    private int depthStencil;
    private int hullTexture;
    private int sceneTexture;
    private int sceneBuffer;
    private int sceneX;
    private int sceneY;
    private int sceneWidth;
    private int sceneHeight;
    private int resolution;
    private float stampWidth;

    private static int shader(int type, String extension) throws IOException {
        String source = Global.getSettings().loadText("data/shaders/Moci_MassDrive." + extension);
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader, 4096);
            glDeleteShader(shader);
            throw new IllegalStateException(extension + ": " + log);
        }
        return shader;
    }

    private static void load() {
        if (program != 0) return;
        int vertex = 0, fragment = 0, linked = 0;
        try {
            vertex = shader(GL_VERTEX_SHADER, "vert");
            fragment = shader(GL_FRAGMENT_SHADER, "frag");
            linked = glCreateProgram();
            glAttachShader(linked, vertex);
            glAttachShader(linked, fragment);
            glLinkProgram(linked);
            if (glGetProgrami(linked, GL_LINK_STATUS) == GL_FALSE) {
                throw new IllegalStateException(glGetProgramInfoLog(linked, 4096));
            }
            program = linked;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read shader", ex);
        } finally {
            if (vertex != 0) glDeleteShader(vertex);
            if (fragment != 0) glDeleteShader(fragment);
            if (linked != 0 && program != linked) glDeleteProgram(linked);
        }
    }

    private static int uniform(String name) {
        return UNIFORMS.computeIfAbsent(name, key -> glGetUniformLocation(program, key));
    }

    private static int texture() {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return id;
    }

    private void beginResources() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (battle.get() != engine) {
            // 从模拟器直接退场会跳过实体 cleanup，在下次使用 GL 时回收遗留缓冲。
            for (Moci_MassDriveImage abandoned : ALLOCATED.toArray(new Moci_MassDriveImage[0])) abandoned.close();
            battle = new WeakReference<>(engine);
        }
        load();
        ALLOCATED.add(this);
    }

    void prepare(Moci_MassDrivePass pass, Ship ship, CombatViewport view) {
        beginResources();
        int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int previousRenderbuffer = glGetInteger(GL_RENDERBUFFER_BINDING);
        glPushAttrib(GL_ALL_ATTRIB_BITS);
        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        try {
            glActiveTexture(GL_TEXTURE0);
            if (framebuffer == 0) {
                stampWidth = pass.stampSize;
                resolution = Math.min(2048, Math.max(128, (int)Math.ceil(stampWidth)));
                hullTexture = texture();
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, resolution, resolution, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer)null);
                framebuffer = glGenFramebuffers();
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, hullTexture, 0);
                depthStencil = glGenRenderbuffers();
                glBindRenderbuffer(GL_RENDERBUFFER, depthStencil);
                glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, resolution, resolution);
                glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthStencil);
                if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("Moci_MassDrive: hull framebuffer incomplete");
                }
                glDisable(GL_SCISSOR_TEST);
                glColorMask(true,true,true,true);
                glClearColor(0,0,0,0);
                glClear(GL_COLOR_BUFFER_BIT);
            }
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glBindTexture(GL_TEXTURE_2D, 0);
            glViewport(0,0,resolution,resolution);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);
            glStencilMask(0xff);
            glClearStencil(0);
            glColorMask(true,true,true,true);
            glClearColor(0,0,0,0);
            glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
            glUseProgram(0);
            glMatrixMode(GL_PROJECTION);
            glLoadIdentity();
            float half = stampWidth * 0.5f;
            glOrtho(-half,half,-half,half,-1000,1000);
            glMatrixMode(GL_MODELVIEW);
            glLoadIdentity();
            glRotatef(-pass.facing,0,0,1);
            glTranslatef(-pass.position.x,-pass.position.y,0);
            // Attached station-module ships render separately from the parent's fitted weapons.
            // Composite them before masking any entity; never bring independently hidden parts back.
            for (Ship child : pass.modules) {
                if (child.getLayer().ordinal() < ship.getLayer().ordinal()) child.render(child.getLayer(), view);
            }
            ship.render(ship.getLayer(), view);
            for (Ship child : pass.modules) {
                if (child.getLayer().ordinal() >= ship.getLayer().ordinal()) child.render(child.getLayer(), view);
            }
            renderStationWeapons(ship, view);
            for (Ship child : pass.modules) renderStationWeapons(child, view);
        } finally {
            glMatrixMode(GL_MODELVIEW);
            glPopMatrix();
            glMatrixMode(GL_PROJECTION);
            glPopMatrix();
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
            glBindRenderbuffer(GL_RENDERBUFFER, previousRenderbuffer);
            glUseProgram(previousProgram);
            glPopAttrib();
        }
    }

    private static void renderStationWeapons(Ship part, CombatViewport view) {
        CombatEngineLayers weapons = CombatEngineLayers.STATION_WEAPONS_LAYER;
        if (part.getLayer() != weapons && part.getActiveLayers().contains(weapons)) part.render(weapons, view);
    }

    boolean draw(Moci_MassDrivePass pass, CombatViewport view) {
        if (pass.leaving && framebuffer == 0) return false;
        if (!pass.leaving) beginResources();
        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        glPushAttrib(GL_ALL_ATTRIB_BITS);
        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        try {
            glGetInteger(GL_VIEWPORT, viewport);
            int width = viewport.get(2), height = viewport.get(3);
            if (width == 0 || height == 0) return false;
            glActiveTexture(GL_TEXTURE1);
            if (!pass.leaving) captureGate(pass, view, width, height);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, hullTexture);
            glDisable(GL_ALPHA_TEST);
            glDisable(GL_DEPTH_TEST);
            glEnable(GL_BLEND);
            glBlendEquation(GL_FUNC_ADD);
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            glUseProgram(program);
            glUniform1i(uniform("fittedShip"),0);
            glUniform1i(uniform("scene"),1);
            float ux = pass.hullLength/1.13f, uy = pass.hullWidth/0.8475f;
            double angle = Math.toRadians(pass.facing);
            float dx = (float)Math.cos(angle), dy = (float)Math.sin(angle);
            float gx = pass.gate.x-pass.position.x, gy = pass.gate.y-pass.position.y;
            float front = gx*dx + gy*dy, side = gy*dx - gx*dy;
            glUniform2f(uniform("units"),ux,uy);
            glUniform2f(uniform("gate"),front,side);
            glUniform2f(uniform("axis"),dx,dy);
            glUniform4f(uniform("screenRect"),sceneX,sceneY,Math.max(1,sceneWidth),Math.max(1,sceneHeight));
            glUniform2f(uniform("pixelsPerUnit"),width/view.getVisibleWidth(),height/view.getVisibleHeight());
            glUniform1f(uniform("stampWidth"),stampWidth);
            glUniform1f(uniform("noseX"),pass.hullNose);
            glUniform1f(uniform("age"),pass.renderAge());
            glUniform1f(uniform("trailAge"),pass.trailAge);
            glUniform1f(uniform("seed"),pass.seed);
            glUniform1f(uniform("leaving"),pass.leaving ? 1f : 0f);
            glUniform1f(uniform("fade"),pass.lightFade());
            glTranslatef(pass.position.x,pass.position.y,0);
            glRotatef(pass.facing,0,0,1);
            float half = stampWidth * 0.5f;
            if (!pass.leaving) ux = uy = (float)Math.sqrt(ux * uy);
            float left = Math.min(-half,(pass.leaving ? 0f : front)-3.7f*ux);
            float right = Math.max(half,(pass.leaving ? 0f : front)+2.1f*ux);
            float bottom = Math.min(-half,(pass.leaving ? 0f : side)-2.1f*uy);
            float top = Math.max(half,(pass.leaving ? 0f : side)+2.1f*uy);
            glBegin(GL_QUADS);
            glVertex2f(left,bottom);
            glVertex2f(right,bottom);
            glVertex2f(right,top);
            glVertex2f(left,top);
            glEnd();
            return true;
        } finally {
            glUseProgram(previousProgram);
            glPopMatrix();
            glPopAttrib();
        }
    }

    private void captureGate(Moci_MassDrivePass pass, CombatViewport view, int width, int height) {
        int left = viewport.get(0), bottom = viewport.get(1);
        float px = width/view.getVisibleWidth(), py = height/view.getVisibleHeight();
        float cx = left + (pass.gate.x - view.getLLX()) * px;
        float cy = bottom + (pass.gate.y - view.getLLY()) * py;
        float radius = 2.15f * (float)Math.sqrt((pass.hullLength/1.13f)*(pass.hullWidth/0.8475f));
        int rx = Math.min(width, (int)Math.ceil(radius * px) + 2);
        int ry = Math.min(height, (int)Math.ceil(radius * py) + 2);
        int w = Math.min(width, 2*rx), h = Math.min(height, 2*ry);
        sceneX = Math.max(left,Math.min(left+width-w,Math.round(cx)-rx));
        sceneY = Math.max(bottom,Math.min(bottom+height-h,Math.round(cy)-ry));
        if (sceneTexture == 0) {
            sceneTexture = texture();
            sceneBuffer = glGenFramebuffers();
        }
        glBindTexture(GL_TEXTURE_2D, sceneTexture);
        int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        try {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, draw);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, sceneBuffer);
            if (sceneWidth != w || sceneHeight != h) {
                glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);
                glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,sceneTexture,0);
                if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("Moci_MassDrive: scene framebuffer incomplete");
                }
                sceneWidth = w;
                sceneHeight = h;
            }
            glDisable(GL_SCISSOR_TEST);
            glBlitFramebuffer(sceneX,sceneY,sceneX+w,sceneY+h,0,0,w,h,GL_COLOR_BUFFER_BIT,GL_NEAREST);
        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,read);
        }
    }

    void close() {
        ALLOCATED.remove(this);
        if (depthStencil != 0) glDeleteRenderbuffers(depthStencil);
        if (sceneBuffer != 0) glDeleteFramebuffers(sceneBuffer);
        if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
        if (hullTexture != 0) glDeleteTextures(hullTexture);
        if (sceneTexture != 0) glDeleteTextures(sceneTexture);
        framebuffer = depthStencil = sceneBuffer = hullTexture = sceneTexture = 0;
    }
}
