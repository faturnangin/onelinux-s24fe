package dev.omalauncher.linux

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import kotlin.concurrent.thread

private const val TAG = "audio"
private const val RATE = 48000
private const val FRAME_BYTES = 4 // s16le stereo (2 channels * 2 bytes = 4 bytes)

/** Restarts of a crashing PulseAudio back off up to this. */
private const val MAX_BACKOFF_MS = 60_000L

/**
 * Plays guest audio and captures guest microphone:
 * - PulseAudio renders into audio.fifo (module-pipe-sink) -> AudioTrack.
 * - AudioRecord captures Android mic -> writes into mic.fifo (module-pipe-source).
 */
class AudioBridge(
    private val context: Context,
    private val proot: Proot,
) {
    private val fifo = File(proot.runtimeDir, "audio.fifo")
    private val micFifo = File(proot.runtimeDir, "mic.fifo")
    private val runtimeDir = Proot.GUEST_RUNTIME_DIR

    /** PulseAudio config: native socket, pipe sink (speaker), and pipe source (mic). */
    private val config =
        """
        load-module module-native-protocol-unix auth-anonymous=1 socket=$runtimeDir/pulse/native
        load-module module-pipe-sink file=$runtimeDir/${fifo.name} format=s16le rate=$RATE channels=2 sink_name=android sink_properties=device.description=Android
        load-module module-pipe-source file=$runtimeDir/${micFifo.name} format=s16le rate=$RATE channels=2 source_name=android_mic source_properties=device.description=Android_Microphone
        load-module module-suspend-on-idle timeout=1
        set-default-sink android
        set-default-source android_mic
        .nofail
        set-source-volume android_mic 65536
        set-source-mute android_mic 0
        .fail
        """.trimIndent() + "\n"

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = java.lang.Object()
    private var playing = false

    @Volatile
    private var lastData = 0L

    private val track by lazy {
        val minBuffer = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack
            .Builder()
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            ).setAudioFormat(
                AudioFormat
                    .Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            ).setBufferSizeInBytes(maxOf(minBuffer, RATE * FRAME_BYTES / 10))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Whether PulseAudio should run; off while Linux is being removed. */
    @Volatile
    private var running = false
    private var started = false

    @Volatile
    var isScreenActive: () -> Boolean = { true }

    /** Active source output IDs connected to PulseAudio sources. */
    private val activeSourceOutputs = Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var micNeeded = false

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val micLock = java.lang.Object()

    fun onScreensStopped() {
        Log.i(TAG, "Screens stopped (background) -> pausing microphone if active")
        synchronized(micLock) {
            micLock.notifyAll()
        }
    }

    fun onScreensStarted() {
        Log.i(TAG, "Screens started -> checking microphone requirements")
        synchronized(micLock) {
            micLock.notifyAll()
        }
    }

    /** Starts the bridge, or resumes it after [stop]. */
    fun start() {
        running = true
        if (started) return
        started = true
        proot.hostFile("/tmp/pulse.pa").writeText(config)
        File(proot.runtimeDir, "pulse").mkdirs()
        micFifo.delete()
        fifo.delete()
        thread(name = "pulseaudio", isDaemon = true) { superviseDaemon() }
        thread(name = "audio-bridge", isDaemon = true) { pump() }
        thread(name = "audio-idle", isDaemon = true) { pauseWhenIdle() }
        thread(name = "mic-monitor", isDaemon = true) { monitorPulseEvents() }
        thread(name = "mic-bridge", isDaemon = true) { pumpMic() }
    }

    /** Stops restarting PulseAudio; the caller ends the running one along with the other guest processes. */
    fun stop() {
        running = false
        synchronized(micLock) {
            micLock.notifyAll()
        }
    }

    /** Keeps PulseAudio running; it runs as the (fake) root under proot, which it warns about harmlessly. */
    private fun superviseDaemon() {
        var backoff = 1000L
        while (true) {
            while (!running) Thread.sleep(1000)
            val started = SystemClock.elapsedRealtime()
            micFifo.delete()
            fifo.delete()
            val code =
                runCatching {
                    proot.run("exec pulseaudio -n -F /tmp/pulse.pa --daemonize=no --use-pid-file=no --exit-idle-time=-1 --disable-shm=yes") {
                        Log.i(TAG, "[pulseaudio] $it")
                    }
                }.getOrElse { -1 }
            if (SystemClock.elapsedRealtime() - started > MAX_BACKOFF_MS) backoff = 1000L
            Log.w(TAG, "pulseaudio exited $code; restarting in ${backoff / 1000} s")
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** Copies the sink's FIFO into the track; PulseAudio (re)creates the FIFO when it loads the sink. */
    private fun pump() {
        val buf = ByteArray(RATE * FRAME_BYTES / 50) // 20 ms
        while (true) {
            if (!fifo.exists()) {
                Thread.sleep(500)
                continue
            }
            try {
                // Opening a FIFO for reading blocks until PulseAudio opens it for writing.
                fifo.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        lastData = SystemClock.uptimeMillis()
                        synchronized(lock) {
                            if (!playing) {
                                track.play()
                                playing = true
                                lock.notify()
                            }
                        }
                        track.write(buf, 0, n - n % FRAME_BYTES)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "audio pipe failed", e)
                Thread.sleep(1000)
            }
        }
    }

    /** Releases the audio output when the guest goes quiet (the sink suspends and stops writing); sleeps while paused. */
    private fun pauseWhenIdle() {
        while (true) {
            synchronized(lock) { while (!playing) lock.wait() }
            Thread.sleep(500)
            synchronized(lock) {
                if (playing && SystemClock.uptimeMillis() - lastData > 1000) {
                    track.pause()
                    playing = false
                }
            }
        }
    }

    /**
     * Monitors PulseAudio source-output events to detect when Linux applications
     * actually start or stop recording from the microphone.
     */
    private fun monitorPulseEvents() {
        while (running) {
            while (!running) Thread.sleep(1000)
            if (!micFifo.exists()) {
                Thread.sleep(500)
                continue
            }
            val pulseSocket = File(proot.runtimeDir, "pulse/native")
            if (!pulseSocket.exists()) {
                Thread.sleep(500)
                continue
            }

            // Sync current active recording clients on monitor start/reconnect
            runCatching {
                val current = mutableSetOf<String>()
                proot.run("pactl list source-outputs short") { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        val parts = trimmed.split(Regex("""\s+"""))
                        if (parts.size >= 2) {
                            current.add(parts[0])
                        }
                    }
                }
                synchronized(micLock) {
                    activeSourceOutputs.clear()
                    activeSourceOutputs.addAll(current)
                    updateMicStateLocked()
                }
            }

            // Subscribe to real-time events from PulseAudio
            runCatching {
                proot.run("exec pactl subscribe") { line ->
                    if (line.contains("source-output")) {
                        handleSourceOutputEvent(line)
                    }
                }
            }

            Thread.sleep(1000)
        }
    }

    private fun handleSourceOutputEvent(line: String) {
        val match = Regex("""'(\w+)' on source-output #(\d+)""").find(line) ?: return
        val event = match.groupValues[1]
        val id = match.groupValues[2]
        synchronized(micLock) {
            when (event) {
                "new" -> {
                    activeSourceOutputs.add(id)
                    updateMicStateLocked()
                }
                "remove" -> {
                    activeSourceOutputs.remove(id)
                    updateMicStateLocked()
                }
            }
        }
    }

    private fun updateMicStateLocked() {
        val hasActiveStreams = activeSourceOutputs.isNotEmpty()
        if (hasActiveStreams) {
            if (!micNeeded) {
                micNeeded = true
                Log.i(TAG, "Mic needed: active source outputs: $activeSourceOutputs")
                micLock.notifyAll()
            }
        } else {
            // Debounce before stopping AudioRecord: wait 1.5s in case another stream starts
            thread(name = "mic-debounce", isDaemon = true) {
                Thread.sleep(1500)
                synchronized(micLock) {
                    if (activeSourceOutputs.isEmpty() && micNeeded) {
                        micNeeded = false
                        Log.i(TAG, "Mic no longer needed: all source outputs closed")
                        micLock.notifyAll()
                    }
                }
            }
        }
    }

    /**
     * Captures audio from the phone's microphone using AudioRecord and streams
     * it into PulseAudio's mic.fifo ONLY when a Linux application is actively
     * recording (on-demand) and the app is visible.
     */
    private fun pumpMic() {
        val micBuf = ByteArray(RATE * FRAME_BYTES / 50) // 20 ms chunk = 3840 bytes
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val recordBufferSize = maxOf(minBuffer, RATE * FRAME_BYTES / 5) // 200 ms buffer

        while (true) {
            while (!running) Thread.sleep(1000)
            if (!micFifo.exists()) {
                Thread.sleep(500)
                continue
            }

            try {
                // Opening FIFO for writing blocks until PulseAudio opens it for reading
                micFifo.outputStream().use { output ->
                    Log.i(TAG, "mic FIFO connected to PulseAudio")
                    val fd = (output as? FileOutputStream)?.fd
                    val pfd = fd?.let {
                        StructPollfd().apply {
                            this.fd = it
                            this.events = OsConstants.POLLOUT.toShort()
                        }
                    }

                    var recorder: AudioRecord? = null

                    try {
                        while (running) {
                            val shouldRecord = isScreenActive() && micNeeded

                            if (!shouldRecord) {
                                if (recorder != null) {
                                    Log.i(TAG, "Stopping AudioRecord (idle / not in use, screens active: ${isScreenActive()}, micNeeded: $micNeeded)")
                                    runCatching {
                                        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                            recorder.stop()
                                        }
                                        recorder.release()
                                    }
                                    recorder = null
                                }

                                synchronized(micLock) {
                                    while (running && (!isScreenActive() || !micNeeded)) {
                                        micLock.wait(1000)
                                    }
                                }
                                if (!running) break
                                continue
                            }

                            // Active recording needed! Start AudioRecord if not running
                            if (recorder == null) {
                                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    Log.w(TAG, "RECORD_AUDIO permission not granted")
                                    Thread.sleep(1000)
                                    continue
                                }

                                recorder = AudioRecord(
                                    MediaRecorder.AudioSource.MIC,
                                    RATE,
                                    AudioFormat.CHANNEL_IN_STEREO,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                    recordBufferSize,
                                )

                                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                                    Log.w(TAG, "AudioRecord failed to initialize, retrying")
                                    recorder.release()
                                    recorder = null
                                    Thread.sleep(500)
                                    continue
                                }

                                recorder.startRecording()
                                Log.i(TAG, "AudioRecord started recording (active on-demand)")
                            }

                            // Guard write against blocking if pipe reader is suspended
                            if (pfd != null) {
                                val pollRes = runCatching { Os.poll(arrayOf(pfd), 100) }.getOrDefault(1)
                                if (pollRes <= 0 || (pfd.revents.toInt() and OsConstants.POLLOUT) == 0) {
                                    // Pipe buffer full, reader paused
                                    Thread.sleep(20)
                                    continue
                                }
                            }

                            val n = recorder.read(micBuf, 0, micBuf.size)
                            if (n < 0) {
                                Log.w(TAG, "AudioRecord.read error: $n")
                                break
                            }
                            if (n > 0) {
                                output.write(micBuf, 0, n)
                                output.flush()
                            }
                        }
                    } finally {
                        runCatching {
                            if (recorder != null) {
                                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                    recorder.stop()
                                }
                                recorder.release()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "mic pump error: ${e.message}")
                Thread.sleep(1000)
            }
        }
    }
}
