package com.dragonnes.emu.record

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import androidx.core.content.FileProvider
import com.dragonnes.emu.CrashReporter
import com.swordfish.libretrodroid.LibretroDroid
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Records ONLY the game picture (with the chosen filter/DSLR look) and the game sound to MP4.
 *
 * Video: on the GL thread, right after the frame is drawn and before it is shown, the game
 * rectangle of the window's back buffer is blitted (GLES 3) into a MediaCodec input surface.
 * The touch buttons are Compose views above the GL surface, so they are never in the video.
 * Audio: the engine copies the core's PCM samples into a buffer that we encode to AAC.
 */
private const val EGL_RECORDABLE_ANDROID = 0x3142

class GameRecorder(private val context: Context, private val gameTitle: String) {

    @Volatile var isRecording = false
        private set
    private var startNs = 0L

    private val tmpFile = File(context.cacheDir, "recording.mp4")

    // Encoders + muxer
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private val muxLock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var expectedTracks = 2

    // EGL / GL
    private var eglDisplay: EGLDisplay? = null
    private var encoderContext: EGLContext? = null      // shares textures with the game's context
    private var encoderSurface: EGLSurface? = null
    private var inputSurface: Surface? = null
    private var outW = 0
    private var outH = 0
    private var lastFrameNs = 0L

    /**
     * The game thread only copies the picture into one of these textures (a cheap GPU blit, never
     * waits for the encoder); the encoder thread draws it into the video. Frames are dropped,
     * never queued up, when the encoder is busy, so recording can't slow the game down.
     */
    private class Slot(val texture: Int, val fbo: Int) { @Volatile var free = true }
    private class Frame(val slot: Slot, val fence: Long, val ptsNs: Long)
    private var slots: List<Slot> = emptyList()
    private val frames = java.util.concurrent.LinkedBlockingQueue<Frame>()
    private var encoderThread: Thread? = null

    // ---- diagnostics (shown when saving fails) ----
    @Volatile private var framesCopied = 0
    @Volatile private var framesEncoded = 0
    @Volatile private var problem: String? = null
    private fun diag(text: String) { CrashReporter.crumb("rec: $text"); if (problem == null) problem = text }
    /** Short reason of the last failure, for the message shown to the player. */
    val lastProblem: String get() = "${problem ?: "?"} (نسخ=$framesCopied، ترميز=$framesEncoded)"

    private var audioThread: Thread? = null
    private var videoDrainThread: Thread? = null
    private var sampleRate = 48000

    /** Elapsed recording time in ms (for the on-screen counter). */
    fun elapsedMs(): Long = if (isRecording) (System.nanoTime() - startNs) / 1_000_000 else 0

    /**
     * Must be called on the GL thread (we need the GL context and the game rectangle).
     * [screenW]/[screenH] = GL surface size. Returns an error message, or null when started.
     */
    fun startOnGlThread(screenW: Int, screenH: Int): String? {
        if (isRecording) return null
        val version = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, version, 0)
        if (version[0] < 3) return "جهازك ما يدعم تسجيل الفيديو (يحتاج OpenGL ES 3)"

        val rect = LibretroDroid.getGameRect()
        val gameW = ((rect[2] - rect[0]) * screenW).toInt().coerceAtLeast(16)
        val gameH = ((rect[3] - rect[1]) * screenH).toInt().coerceAtLeast(16)
        // Target 1080p-class output with the game's own shape; sizes must be even (multiple of 16 is safest).
        val maxH = 1080
        val scale = minOf(1f, maxH.toFloat() / gameH, 1920f / gameW)
        outW = ((gameW * scale).toInt() / 16 * 16).coerceAtLeast(160)
        outH = ((gameH * scale).toInt() / 16 * 16).coerceAtLeast(144)

        return try {
            tmpFile.delete()
            muxer = MediaMuxer(tmpFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            setupVideo()
            setupEgl()
            sampleRate = LibretroDroid.getAudioSampleRate().takeIf { it in 8000..96000 } ?: 48000
            expectedTracks = if (setupAudio()) 2 else 1
            startNs = System.nanoTime()
            lastFrameNs = 0L
            framesCopied = 0; framesEncoded = 0; problem = null
            isRecording = true
            startDrainThreads()
            CrashReporter.crumb("rec start ${outW}x$outH audio=$sampleRate tracks=$expectedTracks")
            null
        } catch (e: Throwable) {
            CrashReporter.crumb("rec start failed: ${e.message}")
            releaseAll()
            "تعذر بدء التسجيل: ${e.javaClass.simpleName}"
        }
    }

    private fun setupVideo() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (outH >= 1000) 10_000_000 else 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        videoCodec = codec
    }

    private fun setupAudio(): Boolean = runCatching {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        audioCodec = codec
        LibretroDroid.setAudioCapture(true)
        true
    }.getOrElse { CrashReporter.crumb("rec audio off: ${it.message}"); false }

    private fun setupEgl() {
        val display = EGL14.eglGetCurrentDisplay()
        val gameCtx = EGL14.eglGetCurrentContext()
        check(display != EGL14.EGL_NO_DISPLAY && gameCtx != EGL14.EGL_NO_CONTEXT) { "no current EGL context" }
        // Same config + client version as the game's context, so they can share textures.
        val id = IntArray(1); val ver = IntArray(1)
        EGL14.eglQueryContext(display, gameCtx, EGL14.EGL_CONFIG_ID, id, 0)
        EGL14.eglQueryContext(display, gameCtx, EGL14.EGL_CONTEXT_CLIENT_VERSION, ver, 0)
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        // The encoder needs a "recordable" config (EGL_RECORDABLE_ANDROID), otherwise MediaCodec may
        // silently never receive frames. Contexts with different configs can still share textures.
        val recordable = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(display, recordable, 0, configs, 0, 1, n, 0) || n[0] == 0 || configs[0] == null) {
            EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, configs, 0, 1, n, 0)
            diag("no recordable config, using game config")
        }
        val ctx = EGL14.eglCreateContext(display, configs[0], gameCtx,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, maxOf(ver[0], 3), EGL14.EGL_NONE), 0)
        check(ctx != null && ctx != EGL14.EGL_NO_CONTEXT) { "encoder context: 0x" + Integer.toHexString(EGL14.eglGetError()) }
        val surface = EGL14.eglCreateWindowSurface(display, configs[0], inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != null && surface != EGL14.EGL_NO_SURFACE) { "encoder surface: 0x" + Integer.toHexString(EGL14.eglGetError()) }
        eglDisplay = display; encoderContext = ctx; encoderSurface = surface

        // Copy targets, created in the game's context (textures are shared, framebuffers are not).
        slots = List(3) {
            val tex = IntArray(1); val fbo = IntArray(1)
            GLES30.glGenTextures(1, tex, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, outW, outH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glGenFramebuffers(1, fbo, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[0], 0)
            Slot(tex[0], fbo[0])
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /** Encoder thread: draws copied frames into the MediaCodec surface. */
    private fun encoderLoop() {
        val display = eglDisplay ?: return
        val surface = encoderSurface ?: return
        if (!EGL14.eglMakeCurrent(display, surface, surface, encoderContext)) {
            diag("encoder makeCurrent 0x" + Integer.toHexString(EGL14.eglGetError())); return
        }
        val program = buildBlitProgram()
        val quad = java.nio.ByteBuffer.allocateDirect(8 * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        val posLoc = GLES30.glGetAttribLocation(program, "aPos")
        val texLoc = GLES30.glGetUniformLocation(program, "uTex")
        try {
            while (true) {
                val f = frames.poll(30, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (f == null) { if (!isRecording) break else continue }
                GLES30.glWaitSync(f.fence, 0, GLES30.GL_TIMEOUT_IGNORED)
                GLES30.glDeleteSync(f.fence)
                GLES30.glViewport(0, 0, outW, outH)
                GLES30.glUseProgram(program)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, f.slot.texture)
                GLES30.glUniform1i(texLoc, 0)
                GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 0, quad)
                GLES30.glEnableVertexAttribArray(posLoc)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                EGLExt.eglPresentationTimeANDROID(display, surface, f.ptsNs)
                if (!EGL14.eglSwapBuffers(display, surface)) diag("swap 0x" + Integer.toHexString(EGL14.eglGetError()))
                else framesEncoded++
                f.slot.free = true
            }
        } finally {
            frames.clear()
            GLES30.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, encoderContext)
            encoderSurface = null; encoderContext = null
        }
    }

    private fun buildBlitProgram(): Int {
        fun shader(type: Int, src: String) = GLES30.glCreateShader(type).also {
            GLES30.glShaderSource(it, src); GLES30.glCompileShader(it)
        }
        val vs = shader(GLES30.GL_VERTEX_SHADER,
            "attribute vec2 aPos; varying vec2 vUv; void main(){ vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }")
        val fs = shader(GLES30.GL_FRAGMENT_SHADER,
            "precision mediump float; uniform sampler2D uTex; varying vec2 vUv; void main(){ gl_FragColor = texture2D(uTex, vUv); }")
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vs); GLES30.glAttachShader(it, fs); GLES30.glLinkProgram(it)
            GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        }
    }

    /** GL thread, after the game frame is drawn and before it is shown. Never blocks. */
    fun onFrameDrawn(screenW: Int, screenH: Int) {
        if (!isRecording) return
        val now = System.nanoTime()
        if (lastFrameNs != 0L && now - lastFrameNs < 15_000_000L) return  // cap ~60 fps on 90/120 Hz screens
        val slot = slots.firstOrNull { it.free } ?: return                // encoder busy: drop this frame
        lastFrameNs = now
        slot.free = false
        val r = LibretroDroid.getGameRect()
        val x0 = (r[0] * screenW).toInt(); val y0 = (r[1] * screenH).toInt()
        val x1 = (r[2] * screenW).toInt(); val y1 = (r[3] * screenH).toInt()
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, slot.fbo)
        GLES30.glBlitFramebuffer(x0, y0, x1, y1, 0, 0, outW, outH, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR)
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) diag("blit 0x" + Integer.toHexString(err))
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        framesCopied++
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glFlush()
        frames.offer(Frame(slot, fence, now - startNs))
    }

    private fun startDrainThreads() {
        encoderThread = thread(name = "rec-gl") {
            runCatching { encoderLoop() }.onFailure { diag("gl: ${it.javaClass.simpleName} ${it.message}") }
        }
        videoDrainThread = thread(name = "rec-video") {
            val codec = videoCodec ?: return@thread
            runCatching { drain(codec, isVideo = true) }.onFailure { diag("video: ${it.javaClass.simpleName} ${it.message}") }
        }
        audioCodec?.let { codec ->
            audioThread = thread(name = "rec-audio") { runCatching {
                val pcm = ShortArray(4096)
                var samplesWritten = 0L
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (true) {
                    if (!inputDone) {
                        val n = LibretroDroid.readCapturedAudio(pcm)
                        val stopping = !isRecording
                        if (n > 0 || stopping) {
                            val idx = codec.dequeueInputBuffer(10_000)
                            if (idx >= 0) {
                                val buf = codec.getInputBuffer(idx)!!
                                buf.clear()
                                val count = minOf(n, buf.capacity() / 2)
                                // PCM for MediaCodec is little-endian (ByteBuffer defaults to big-endian).
                                buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, count)
                                val ptsUs = samplesWritten * 1_000_000L / (sampleRate * 2)
                                samplesWritten += count
                                codec.queueInputBuffer(idx, 0, count * 2, ptsUs,
                                    if (stopping) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                                if (stopping) inputDone = true
                            }
                        } else Thread.sleep(8)
                    }
                    if (drainOnce(codec, info, isVideo = false)) break
                }
            }.onFailure { diag("audio: ${it.javaClass.simpleName} ${it.message}") } }
        }
    }

    /** Video: drains until end of stream. */
    private fun drain(codec: MediaCodec, isVideo: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (!drainOnce(codec, info, isVideo)) { /* loop */ }
    }

    /** Returns true when the end of stream was reached. */
    private fun drainOnce(codec: MediaCodec, info: MediaCodec.BufferInfo, isVideo: Boolean): Boolean {
        val idx = codec.dequeueOutputBuffer(info, 10_000)
        when {
            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxLock) {
                val m = muxer ?: return true
                if (isVideo) videoTrack = m.addTrack(codec.outputFormat) else audioTrack = m.addTrack(codec.outputFormat)
                val ready = (videoTrack >= 0) && (expectedTracks == 1 || audioTrack >= 0)
                if (ready && !muxerStarted) { m.start(); muxerStarted = true; muxLock.notifyAll() }
            }
            idx >= 0 -> {
                val buf = codec.getOutputBuffer(idx)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && buf != null) synchronized(muxLock) {
                    // Wait (briefly) for the other track before the muxer can start.
                    var waited = 0
                    while (!muxerStarted && waited < 20) { muxLock.wait(50); waited++ }
                    if (muxerStarted) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        runCatching { muxer?.writeSampleData(if (isVideo) videoTrack else audioTrack, buf, info) }
                    }
                }
                codec.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
            }
        }
        return false
    }

    /**
     * Stops on the GL thread (releases the EGL surface there), then finishes the file in the
     * background and calls [onSaved] with the saved video's Uri (or null on failure).
     */
    fun stopOnGlThread(onSaved: (Uri?) -> Unit) {
        if (!isRecording) return
        isRecording = false
        LibretroDroid.setAudioCapture(false)
        val oldSlots = slots
        slots = emptyList()
        thread(name = "rec-finish") {
            val uri = runCatching {
                encoderThread?.join(3000)               // draws the last frames, then frees its EGL objects
                videoCodec?.signalEndOfInputStream()
                videoDrainThread?.join(3000)
                audioThread?.join(3000)
                synchronized(muxLock) { if (muxerStarted) muxer?.stop() }
                releaseAll()
                if (!muxerStarted) diag("muxer never started (video=$videoTrack audio=$audioTrack)")
                if (muxerStarted) runCatching { publish() }.getOrElse { diag("save: ${it.javaClass.simpleName} ${it.message}"); null } else null
            }.getOrElse { diag("stop: ${it.javaClass.simpleName} ${it.message}"); releaseAll(); null }
            onSaved(uri)
        }
        // Copy targets belong to the game's context: delete them here, on the GL thread.
        oldSlots.forEach {
            GLES30.glDeleteFramebuffers(1, intArrayOf(it.fbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(it.texture), 0)
        }
    }

    private fun releaseAll() {
        runCatching { videoCodec?.stop() }; runCatching { videoCodec?.release() }; videoCodec = null
        runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }; audioCodec = null
        runCatching { muxer?.release() }; muxer = null
        runCatching { inputSurface?.release() }; inputSurface = null
        videoTrack = -1; audioTrack = -1
    }

    /** Moves the finished file to Movies/DragonNES (gallery). Returns a shareable Uri. */
    private fun publish(): Uri? {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val safe = gameTitle.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(40).ifEmpty { "DragonNES" }
        val name = "${safe}_$stamp.mp4"
        muxerStarted = false
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DragonNES")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> tmpFile.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            tmpFile.delete()
            uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "DragonNES").apply { mkdirs() }
            val out = File(dir, name)
            tmpFile.copyTo(out, overwrite = true); tmpFile.delete()
            FileProvider.getUriForFile(context, context.packageName + ".files", out)
        }
    }
}
