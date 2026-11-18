package dev.glowcam

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Live camera preview rendered on the GPU: the native CameraX preview stream goes into an external
 * texture and a fragment shader applies the beauty effect (edge-preserving smoothing on skin,
 * whitening, rosy tint) at full preview resolution.
 */
class GlPreviewView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    GLSurfaceView(context, attrs) {

    @Volatile var params = BeautyParams()
    @Volatile var mirror = false

    /** Height of the status bar in px: the picture starts below it. */
    @Volatile var topInset = 0f

    /** The GL context was lost and recreated: the camera must be re-bound to get a new surface. */
    var onContextRecreated: (() -> Unit)? = null
    var onGlError: (() -> Unit)? = null

    private val renderer = PreviewRenderer()

    val surfaceProvider = Preview.SurfaceProvider { request -> renderer.onSurfaceRequest(request) }

    init {
        setEGLContextClientVersion(2)
        setPreserveEGLContextOnPause(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    private fun displayRotationDegrees(): Int = when (display?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private inner class PreviewRenderer : Renderer {
        private val lock = Any()
        private val direct = Executor { it.run() }
        private var program = 0
        private var aPos = 0
        private var uTexMat = 0
        private var uRot = 0
        private var uScale = 0
        private var uOffset = 0
        private var uMirror = 0
        private var uTexel = 0
        private var uSpacing = 0
        private var uSigma = 0
        private var uSmooth = 0
        private var uWhiten = 0
        private var uRosy = 0
        private var uActive = 0
        private var texId = 0
        private var st: SurfaceTexture? = null
        private var pending: SurfaceRequest? = null
        private var created = false
        private var hasFrame = false
        private val frameAvailable = AtomicBoolean(false)
        private val texMat = FloatArray(16)
        /** Rotation applied in the shader (the camera orientation itself is already in the SurfaceTexture matrix). */
        @Volatile private var shaderRotation = 0
        /** Full sensor-to-display rotation; decides whether the upright frame is portrait or landscape. */
        @Volatile private var fullRotation = 0
        @Volatile private var bufW = 1
        @Volatile private var bufH = 1
        private var viewW = 1
        private var viewH = 1
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

        fun onSurfaceRequest(request: SurfaceRequest) {
            synchronized(lock) { pending = request }
            tryProvide()
        }

        private fun tryProvide() {
            val s: SurfaceTexture
            val req: SurfaceRequest
            synchronized(lock) {
                s = st ?: return
                req = pending ?: return
                pending = null
            }
            val size = req.resolution
            s.setDefaultBufferSize(size.width, size.height)
            bufW = size.width
            bufH = size.height
            val surface = Surface(s)
            req.setTransformationInfoListener(direct) { info ->
                fullRotation = info.rotationDegrees
                // A surface fed directly by the camera carries the sensor orientation in its transform
                // matrix, so only the display rotation is left to apply (see hasCameraTransform()).
                shaderRotation = if (info.hasCameraTransform()) displayRotationDegrees() else info.rotationDegrees
            }
            req.provideSurface(surface, direct) { surface.release() }
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                program = buildProgram(VERTEX, FRAGMENT)
            } catch (e: RuntimeException) {
                Log.e(TAG, "shader build failed", e)
                post { onGlError?.invoke() }
                return
            }
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            uTexMat = GLES20.glGetUniformLocation(program, "uTexMat")
            uRot = GLES20.glGetUniformLocation(program, "uRot")
            uScale = GLES20.glGetUniformLocation(program, "uScale")
            uOffset = GLES20.glGetUniformLocation(program, "uOffset")
            uMirror = GLES20.glGetUniformLocation(program, "uMirror")
            uTexel = GLES20.glGetUniformLocation(program, "uTexel")
            uSpacing = GLES20.glGetUniformLocation(program, "uSpacing")
            uSigma = GLES20.glGetUniformLocation(program, "uSigma")
            uSmooth = GLES20.glGetUniformLocation(program, "uSmooth")
            uWhiten = GLES20.glGetUniformLocation(program, "uWhiten")
            uRosy = GLES20.glGetUniformLocation(program, "uRosy")
            uActive = GLES20.glGetUniformLocation(program, "uActive")

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            texId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val fresh = SurfaceTexture(texId)
            fresh.setOnFrameAvailableListener {
                frameAvailable.set(true)
                requestRender()
            }
            synchronized(lock) {
                st?.release()
                st = fresh
            }
            hasFrame = false
            if (created) post { onContextRecreated?.invoke() }
            created = true
            tryProvide()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width
            viewH = height
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val s = st ?: return
            if (program == 0) return
            if (frameAvailable.getAndSet(false)) {
                try {
                    s.updateTexImage()
                    s.getTransformMatrix(texMat)
                    hasFrame = true
                } catch (e: RuntimeException) {
                    Log.w(TAG, "updateTexImage failed", e)
                    return
                }
            }
            if (!hasFrame) return

            val rot = shaderRotation
            val (fw, fh) = PreviewTransform.displaySize(bufW, bufH, fullRotation)
            val rect = PreviewTransform.fitRect(viewW.toFloat(), viewH.toFloat(), fw.toFloat(), fh.toFloat(), topInset)
            val (sx, sy) = PreviewTransform.quadScale(rect, viewW.toFloat(), viewH.toFloat())
            val (ox, oy) = PreviewTransform.quadOffset(rect, viewW.toFloat(), viewH.toFloat())
            val p = params.clamped()

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniformMatrix4fv(uTexMat, 1, false, texMat, 0)
            GLES20.glUniformMatrix2fv(uRot, 1, false, PreviewTransform.rotationMat2(rot), 0)
            GLES20.glUniform2f(uScale, sx, sy)
            GLES20.glUniform2f(uOffset, ox, oy)
            GLES20.glUniform1f(uMirror, if (mirror) 1f else 0f)
            GLES20.glUniform2f(uTexel, 1f / bufW, 1f / bufH)
            GLES20.glUniform1f(uSpacing, PreviewTransform.blurSpacing(bufW, bufH))
            GLES20.glUniform1f(uSigma, PreviewTransform.rangeSigma(p.smooth))
            GLES20.glUniform1f(uSmooth, p.smooth)
            GLES20.glUniform1f(uWhiten, p.whiten)
            GLES20.glUniform1f(uRosy, p.rosy)
            GLES20.glUniform1f(uActive, if (p.isIdentity) 0f else 1f)

            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
        }
    }

    private companion object {
        const val TAG = "GlowCam"

        const val VERTEX = """
            attribute vec2 aPos;
            uniform mat4 uTexMat;
            uniform mat2 uRot;
            uniform vec2 uScale;
            uniform vec2 uOffset;
            uniform float uMirror;
            varying vec2 vUV;
            void main() {
                gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0);
                vec2 d = aPos * 0.5 + 0.5;
                if (uMirror > 0.5) d.x = 1.0 - d.x;
                vec2 b = uRot * (d - 0.5) + 0.5;
                vUV = (uTexMat * vec4(b, 0.0, 1.0)).xy;
            }
        """

        // Skin mask (YCbCr), 5x5 bilateral smoothing weighted by the mask, whitening, rosy tint.
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUV;
            uniform samplerExternalOES uTex;
            uniform vec2 uTexel;
            uniform float uSpacing;
            uniform float uSigma;
            uniform float uSmooth;
            uniform float uWhiten;
            uniform float uRosy;
            uniform float uActive;

            float skin(vec3 c) {
                float y = dot(c, vec3(0.299, 0.587, 0.114)) * 255.0;
                float cb = 128.0 + (-0.168736 * c.r - 0.331264 * c.g + 0.5 * c.b) * 255.0;
                float cr = 128.0 + (0.5 * c.r - 0.418688 * c.g - 0.081312 * c.b) * 255.0;
                float a = smoothstep(69.0, 77.0, cb) * (1.0 - smoothstep(127.0, 135.0, cb));
                float b = smoothstep(125.0, 133.0, cr) * (1.0 - smoothstep(173.0, 181.0, cr));
                return a * b * smoothstep(40.0, 70.0, y);
            }

            void main() {
                vec3 c0 = texture2D(uTex, vUV).rgb;
                if (uActive < 0.5) {
                    gl_FragColor = vec4(c0, 1.0);
                    return;
                }
                float l0 = dot(c0, vec3(0.299, 0.587, 0.114));
                vec3 sum = vec3(0.0);
                float wsum = 0.0;
                float msum = 0.0;
                float ssum = 0.0;
                float inv2s = 1.0 / (2.0 * uSigma * uSigma);
                for (int i = -2; i <= 2; i++) {
                    for (int j = -2; j <= 2; j++) {
                        vec2 o = vec2(float(i), float(j)) * uTexel * uSpacing;
                        vec3 c = texture2D(uTex, vUV + o).rgb;
                        float ws = exp(-float(i * i + j * j) * 0.25);
                        float dl = dot(c, vec3(0.299, 0.587, 0.114)) - l0;
                        float w = ws * exp(-dl * dl * inv2s);
                        sum += c * w;
                        wsum += w;
                        msum += ws * skin(c);
                        ssum += ws;
                    }
                }
                vec3 smoothed = sum / wsum;
                float m = msum / ssum;
                vec3 col = mix(c0, smoothed, m * uSmooth * 0.85);
                col += (1.0 - col) * (m * uWhiten * 0.4);
                float rk = m * uRosy;
                col += vec3(0.06, -0.01, 0.015) * rk;
                gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
            }
        """

        fun buildProgram(vs: String, fs: String): Int {
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v)
            GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(p)
                GLES20.glDeleteProgram(p)
                throw RuntimeException("link failed: $log")
            }
            return p
        }

        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(s)
                GLES20.glDeleteShader(s)
                throw RuntimeException("compile failed: $log")
            }
            return s
        }
    }
}
