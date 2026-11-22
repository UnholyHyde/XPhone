package ru.xphone.booster;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Adds XPhone's touch dots directly to the encoder input when capturing a single app. */
final class RecordingFrames implements AutoCloseable {
    private static final String FRAME_VERTEX = """
            attribute vec2 position;
            uniform mat4 transform;
            varying vec2 uv;
            void main() {
                gl_Position = vec4(position, 0.0, 1.0);
                uv = (transform * vec4(position * 0.5 + 0.5, 0.0, 1.0)).xy;
            }
            """;
    private static final String FRAME_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 uv;
            uniform samplerExternalOES frame;
            void main() { gl_FragColor = texture2D(frame, uv); }
            """;
    private static final String DOT_VERTEX = """
            attribute vec2 position;
            uniform float size;
            void main() {
                gl_Position = vec4(position, 0.0, 1.0);
                gl_PointSize = size;
            }
            """;
    private static final String DOT_FRAGMENT = """
            precision mediump float;
            uniform vec3 accent;
            void main() {
                float radius = length(gl_PointCoord - vec2(0.5)) * 2.0;
                float alpha = (1.0 - smoothstep(0.05, 1.0, radius)) * 0.78;
                vec3 color = mix(vec3(1.0), accent, smoothstep(0.08, 0.5, radius));
                gl_FragColor = vec4(color, alpha);
            }
            """;
    private final int width, height, accent;
    private final float dotSize;
    private final android.view.Display phone;
    private final FloatBuffer quad = floats(8), dots = floats(64);
    private final float[] matrix = new float[16];
    private EGLDisplay egl = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface output = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private Surface input;
    private int frameProgram, dotProgram, external;
    private long previousFrame;

    RecordingFrames(Surface target, Handler handler, int width, int height, int accent,
                    float dotSize, android.view.Display phone) {
        this.width = width;
        this.height = height;
        this.accent = accent;
        this.dotSize = dotSize;
        this.phone = phone;
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).flip();
        try {
            egl = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            check(EGL14.eglInitialize(egl, new int[1], 0, new int[1], 0), "EGL initialize");
            int[] configAttributes = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, 0x3142, 1, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            check(EGL14.eglChooseConfig(egl, configAttributes, 0, configs, 0, 1, count, 0)
                    && count[0] == 1, "EGL config");
            context = EGL14.eglCreateContext(egl, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            output = EGL14.eglCreateWindowSurface(egl, configs[0], target, new int[]{EGL14.EGL_NONE}, 0);
            check(EGL14.eglMakeCurrent(egl, output, output, context), "EGL context");
            frameProgram = program(FRAME_VERTEX, FRAME_FRAGMENT);
            dotProgram = program(DOT_VERTEX, DOT_FRAGMENT);
            int[] names = new int[1];
            GLES20.glGenTextures(1, names, 0);
            external = names[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, external);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            texture = new SurfaceTexture(external);
            texture.setDefaultBufferSize(width, height);
            input = new Surface(texture);
            texture.setOnFrameAvailableListener(ignored -> draw(), handler);
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    Surface input() { return input; }

    private void draw() {
        if (texture == null) return;
        texture.updateTexImage();
        long timestamp = texture.getTimestamp();
        if (previousFrame != 0 && timestamp - previousFrame < 31_000_000L) return;
        previousFrame = timestamp;
        texture.getTransformMatrix(matrix);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(frameProgram);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(frameProgram, "transform"), 1, false, matrix, 0);
        drawPositions(frameProgram, quad, GLES20.GL_TRIANGLE_STRIP, 4);

        float[] points = TouchTrace.recordingPoints();
        int count = Math.min(points.length / 2, dots.capacity() / 2);
        if (count > 0) {
            dots.clear();
            int rotation = phone.getRotation();
            for (int i = 0; i < count; i++) {
                float[] xy = TouchState.rotate(points[2 * i], points[2 * i + 1], rotation);
                dots.put(2 * xy[0] - 1).put(1 - 2 * xy[1]);
            }
            dots.flip();
            GLES20.glUseProgram(dotProgram);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(dotProgram, "size"), dotSize);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(dotProgram, "accent"),
                    ((accent >> 16) & 255) / 255f, ((accent >> 8) & 255) / 255f, (accent & 255) / 255f);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            drawPositions(dotProgram, dots, GLES20.GL_POINTS, count);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
        EGLExt.eglPresentationTimeANDROID(egl, output, timestamp);
        EGL14.eglSwapBuffers(egl, output);
    }

    private static void drawPositions(int program, FloatBuffer positions, int mode, int count) {
        int attribute = GLES20.glGetAttribLocation(program, "position");
        GLES20.glEnableVertexAttribArray(attribute);
        GLES20.glVertexAttribPointer(attribute, 2, GLES20.GL_FLOAT, false, 0, positions);
        GLES20.glDrawArrays(mode, 0, count);
        GLES20.glDisableVertexAttribArray(attribute);
    }

    private static FloatBuffer floats(int count) {
        return ByteBuffer.allocateDirect(count * Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static int program(String vertex, String fragment) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vertex);
        int f = shader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, v);
        GLES20.glAttachShader(program, f);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        check(linked[0] == GLES20.GL_TRUE, "GL link");
        return program;
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        check(compiled[0] == GLES20.GL_TRUE, "GL shader");
        return shader;
    }

    private static void check(boolean success, String operation) {
        if (!success) throw new IllegalStateException(operation);
    }

    @Override public void close() {
        if (input != null) input.release();
        if (texture != null) {
            texture.setOnFrameAvailableListener(null);
            texture.release();
            texture = null;
        }
        if (egl != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(egl, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (output != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(egl, output);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(egl, context);
            EGL14.eglTerminate(egl);
            EGL14.eglReleaseThread();
        }
    }
}
