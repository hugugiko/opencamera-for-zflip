package net.sourceforge.opencamera.preview;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Handles real-time video mirroring using OpenGL ES.
 *  Corrects stretching, orientation and provides horizontal mirroring for all camera modes.
 */
public class VideoMirrorer implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "VideoMirrorer";
    
    private volatile SurfaceTexture cameraSurfaceTexture;
    private volatile Surface cameraSurface;
    private final Surface recorderSurface;
    private final int videoWidth;
    private final int videoHeight;
    private final int rotation;
    
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    
    private int program;
    private int textureId;
    private int mvpMatrixLoc;
    private int texMatrixLoc;
    
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    
    private final float[] mvpMatrix = new float[16];
    private final float[] texMatrix = new float[16];
    
    private final HandlerThread renderThread;
    private final Handler renderHandler;

    public VideoMirrorer(final Surface recorderSurface, final int width, final int height, final int rotation) {
        this.recorderSurface = recorderSurface;
        this.videoWidth = width;
        this.videoHeight = height;
        this.rotation = rotation;
        
        renderThread = new HandlerThread("VideoMirrorer");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());
        
        renderHandler.post(new Runnable() {
            @Override
            public void run() {
                initEGL();
                initGL();
                
                textureId = createTexture();
                cameraSurfaceTexture = new SurfaceTexture(textureId);
                cameraSurfaceTexture.setDefaultBufferSize(videoWidth, videoHeight);
                cameraSurfaceTexture.setOnFrameAvailableListener(VideoMirrorer.this);
                cameraSurface = new Surface(cameraSurfaceTexture);
            }
        });
    }

    public synchronized Surface getCameraSurface() {
        long start = System.currentTimeMillis();
        while (cameraSurface == null && System.currentTimeMillis() - start < 2000) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        return cameraSurface;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        renderHandler.post(new Runnable() {
            @Override
            public void run() {
                drawFrame();
            }
        });
    }

    private void initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1);
        
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0);
        
        int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        
        int[] surfaceAttribs = { EGL14.EGL_NONE };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], recorderSurface, surfaceAttribs, 0);
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
    }

    private void initGL() {
        String vertexShaderCode =
                "uniform mat4 uMVPMatrix;" +
                "uniform mat4 uTexMatrix;" +
                "attribute vec4 aPosition;" +
                "attribute vec4 aTexCoord;" +
                "varying vec2 vTexCoord;" +
                "void main() {" +
                "  gl_Position = uMVPMatrix * aPosition;" +
                "  vTexCoord = (uTexMatrix * aTexCoord).xy;" +
                "}";
        String fragmentShaderCode =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;" +
                "varying vec2 vTexCoord;" +
                "uniform samplerExternalOES sTexture;" +
                "void main() {" +
                "  gl_FragColor = texture2D(sTexture, vTexCoord);" +
                "}";
        
        program = createProgram(vertexShaderCode, fragmentShaderCode);
        mvpMatrixLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");
        
        // Quad covering the whole screen
        float[] coords = {
            -1.0f, -1.0f, 0.0f,
             1.0f, -1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f,
             1.0f,  1.0f, 0.0f
        };
        vertexBuffer = ByteBuffer.allocateDirect(coords.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(coords);
        vertexBuffer.position(0);
        
        // Full texture coordinates
        float[] texCoords = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        };
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords);
        texCoordBuffer.position(0);
    }

    private void drawFrame() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return;
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
        
        try {
            cameraSurfaceTexture.updateTexImage();
            cameraSurfaceTexture.getTransformMatrix(texMatrix);
        } catch (Exception e) {
            return;
        }

        // IMPORTANT: Query actual surface size to prevent "left bottom corner" issue
        int[] surfaceSize = new int[1];
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, surfaceSize, 0);
        int w = surfaceSize[0];
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, surfaceSize, 0);
        int h = surfaceSize[0];

        GLES20.glViewport(0, 0, w, h);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        
        GLES20.glUseProgram(program);
        
        Matrix.setIdentityM(mvpMatrix, 0);
        
        // Handle Mirroring and Rotation Correction
        // Apply -90 degree rotation to correct the clockwise 90 degree offset reported by user
        Matrix.rotateM(mvpMatrix, 0, -90f, 0f, 0f, 1f);

        if (rotation == 90 || rotation == 270) {
            // In Portrait, Y axis in buffer is horizontal for viewer
            Matrix.scaleM(mvpMatrix, 0, 1, -1, 1);
        } else {
            // In Landscape, X axis is horizontal
            Matrix.scaleM(mvpMatrix, 0, -1, 1, 1);
        }
        
        int posLoc = GLES20.glGetAttribLocation(program, "aPosition");
        GLES20.glEnableVertexAttribArray(posLoc);
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);
        
        int texLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
        GLES20.glEnableVertexAttribArray(texLoc);
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer);
        
        GLES20.glUniformMatrix4fv(mvpMatrixLoc, 1, false, mvpMatrix, 0);
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0);
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    private int createTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return textures[0];
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        return prog;
    }

    private int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }
    
    public void stop() {
        renderHandler.post(new Runnable() {
            @Override
            public void run() {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                    EGL14.eglReleaseThread();
                    EGL14.eglTerminate(eglDisplay);
                }
                eglDisplay = EGL14.EGL_NO_DISPLAY;
                eglContext = EGL14.EGL_NO_CONTEXT;
                eglSurface = EGL14.EGL_NO_SURFACE;
                if (cameraSurfaceTexture != null) {
                    cameraSurfaceTexture.release();
                    cameraSurfaceTexture = null;
                }
                if (cameraSurface != null) {
                    cameraSurface.release();
                    cameraSurface = null;
                }
                renderThread.quit();
            }
        });
    }
}
