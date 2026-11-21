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

    // EGL (GL thread only)
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var encoderSurface: EGLSurface? = null
    private var inputSurface: Surface? = null
    private var outW = 0
    private var outH = 0
    private var lastFrameNs = 0L

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
        val ctx = EGL14.eglGetCurrentContext()
        check(display != EGL14.EGL_NO_DISPLAY && ctx != EGL14.EGL_NO_CONTEXT) { "no current EGL context" }
        // Use the same config as the game's context, so the encoder surface is compatible with it.
        val id = IntArray(1)
        EGL14.eglQueryContext(display, ctx, EGL14.EGL_CONFIG_ID, id, 0)
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, configs, 0, 1, n, 0)
        val surface = EGL14.eglCreateWindowSurface(display, configs[0], inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != null && surface != EGL14.EGL_NO_SURFACE) { "encoder surface: 0x" + Integer.toHexString(EGL14.eglGetError()) }
        eglDisplay = display; eglContext = ctx; encoderSurface = surface
    }

    /** GL thread, after the game frame is drawn and before it is shown. */
    fun onFrameDrawn(screenW: Int, screenH: Int) {
        if (!isRecording) return
        val now = System.nanoTime()
        if (lastFrameNs != 0L && now - lastFrameNs < 15_000_000L) return  // cap ~60 fps on 90/120 Hz screens
        lastFrameNs = now
        val display = eglDisplay ?: return
        val enc = encoderSurface ?: return
        val draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        try {
            if (!EGL14.eglMakeCurrent(display, enc, draw, eglContext)) return
            val r = LibretroDroid.getGameRect()
            val x0 = (r[0] * screenW).toInt(); val y0 = (r[1] * screenH).toInt()
            val x1 = (r[2] * screenW).toInt(); val y1 = (r[3] * screenH).toInt()
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, outW, outH)
            GLES30.glBlitFramebuffer(x0, y0, x1, y1, 0, 0, outW, outH, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR)
            EGLExt.eglPresentationTimeANDROID(display, enc, now - startNs)
            EGL14.eglSwapBuffers(display, enc)
        } finally {
            EGL14.eglMakeCurrent(display, draw, read, eglContext)
            GLES30.glViewport(0, 0, screenW, screenH)
        }
    }

    private fun startDrainThreads() {
        videoDrainThread = thread(name = "rec-video") {
            val codec = videoCodec ?: return@thread
            drain(codec, isVideo = true)
        }
        audioCodec?.let { codec ->
            audioThread = thread(name = "rec-audio") {
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
                                for (i in 0 until count) buf.putShort(pcm[i])
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
            }
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
                        muxer?.writeSampleData(if (isVideo) videoTrack else audioTrack, buf, info)
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
        eglDisplay?.let { d -> encoderSurface?.let { EGL14.eglDestroySurface(d, it) } }
        encoderSurface = null
        thread(name = "rec-finish") {
            val uri = runCatching {
                videoCodec?.signalEndOfInputStream()
                videoDrainThread?.join(3000)
                audioThread?.join(3000)
                synchronized(muxLock) { if (muxerStarted) muxer?.stop() }
                releaseAll()
                if (muxerStarted) publish() else null
            }.getOrElse { CrashReporter.crumb("rec stop failed: ${it.message}"); releaseAll(); null }
            onSaved(uri)
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
