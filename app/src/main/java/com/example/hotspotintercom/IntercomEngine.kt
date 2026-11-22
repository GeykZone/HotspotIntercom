package com.example.hotspotintercom

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Star-topology intercom over UDP for up to MAX_CLIENTS phones plus the host.
 *
 * Packet: [type:1][senderId:1][PCM16 mono 16 kHz, 20 ms = 640 bytes]   (HELLO / FULL are 1 byte)
 *  - Host stamps each phone's id on relayed audio and refuses phone MAX_CLIENTS+1.
 *  - Every phone keeps a small jitter buffer per sender and mixes all talkers together.
 *  - Talking: hold-to-talk (always sends) OR hands-free (voice activity detection).
 *  - Output: loudspeaker or earpiece, switchable at any time (phone-call audio mode, with echo cancellation).
 */
class IntercomEngine(
    context: Context,
    private val isHost: Boolean,
    private val hostIp: String,
    @Volatile private var speaker: Boolean,
    @Volatile private var handsFree: Boolean,
    private val onStatus: (String) -> Unit
) {
    companion object {
        const val PORT = 50005
        const val MAX_CLIENTS = 10
        const val SAMPLE_RATE = 16000
        const val FRAME_SAMPLES = 320            // 20 ms
        const val FRAME_BYTES = FRAME_SAMPLES * 2
        const val GAIN = 2                       // playback boost; try 1 to 4
        private const val HEADER = 2
        private const val PACKET = HEADER + FRAME_BYTES
        private const val T_HELLO: Byte = 0
        private const val T_AUDIO: Byte = 1
        private const val T_FULL: Byte = 2
        private const val CLIENT_TIMEOUT_MS = 15_000L
        private const val PRIME_FRAMES = 3
        private const val MAX_QUEUE = 12
        // Hands-free voice detection
        private const val MIN_VOICE_RMS = 500.0  // raise if background noise triggers it, lower if it misses quiet voices
        private const val NOISE_MULTIPLIER = 3.0
        private const val HANGOVER_FRAMES = 15   // keep sending 300 ms after you stop speaking
    }

    private class ClientInfo(val id: Int, @Volatile var lastSeen: Long)
    private class Sender { val q = java.util.ArrayDeque<ShortArray>(); var primed = false }

    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var prevMode = AudioManager.MODE_NORMAL
    private var prevSpeaker = false
    private var audioEntered = false

    @Volatile private var running = false
    @Volatile private var talking = false
    @Volatile private var connected = false
    @Volatile private var full = false
    @Volatile private var lastAck = 0L
    private val micRunning = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var track: AudioTrack? = null
    private var hostAddr: InetSocketAddress? = null
    private val clients = ConcurrentHashMap<SocketAddress, ClientInfo>()
    private val senders = ConcurrentHashMap<Int, Sender>()

    fun start() {
        if (running) return
        running = true
        thread(name = "intercom-start") {
            try {
                val s = if (isHost) {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        receiveBufferSize = 256 * 1024
                        bind(InetSocketAddress(PORT))
                    }
                } else {
                    hostAddr = InetSocketAddress(InetAddress.getByName(hostIp), PORT)
                    DatagramSocket()
                }
                socket = s
                enterCallAudio()
                track = buildTrack().also { it.play() }
                onStatus(if (isHost) "Hosting. Waiting for phones to join..." else "Connecting to $hostIp ...")
                thread(name = "intercom-rx") { receiveLoop(s) }
                thread(name = "intercom-mix") { mixLoop() }
                thread(name = "intercom-keepalive") { keepAliveLoop() }
                if (handsFree) ensureMic()
            } catch (e: Exception) {
                onStatus("Error: ${e.message}")
                stop()
            }
        }
    }

    fun stop() {
        running = false
        talking = false
        try { socket?.close() } catch (_: Exception) {}
        try { track?.stop(); track?.release() } catch (_: Exception) {}
        socket = null
        track = null
        clients.clear()
        senders.clear()
        exitCallAudio()
    }

    // ---------------------------------------------------------------- controls

    /** Hold-to-talk button pressed / released. */
    fun setTalking(on: Boolean) {
        if (!running || on == talking) return
        talking = on
        if (on) ensureMic()
    }

    /** Hands-free on/off: mic stays open and your voice is sent only while you speak. */
    fun setHandsFree(on: Boolean) {
        handsFree = on
        if (on && running) ensureMic()
    }

    /** true = loudspeaker, false = earpiece. */
    fun setSpeaker(on: Boolean) {
        speaker = on
        if (audioEntered) applySpeaker(on)
    }

    // ---------------------------------------------------------------- audio routing

    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (audioEntered) applySpeaker(speaker)
        }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (audioEntered) applySpeaker(speaker)
        }
    }
    private fun enterCallAudio() {
        prevMode = am.mode
        @Suppress("DEPRECATION")
        prevSpeaker = am.isSpeakerphoneOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        audioEntered = true
        am.registerAudioDeviceCallback(deviceCallback, null)
        applySpeaker(speaker)
    }

    private fun exitCallAudio() {
        if (!audioEntered) return
        audioEntered = false
        try {
            am.unregisterAudioDeviceCallback(deviceCallback)
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = prevSpeaker
            am.mode = prevMode
        } catch (_: Exception) {}
    }

    private fun applySpeaker(on: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val headsetTypes = setOf(
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET
                )
                val devices = am.availableCommunicationDevices
                val wanted = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                val dev = devices.firstOrNull { it.type in headsetTypes }
                    ?: devices.firstOrNull { it.type == wanted }
                if (dev != null) { am.setCommunicationDevice(dev); return }
            } else {
                val wired = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                if (wired) {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                    return
                }
            }
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- microphone

    private fun ensureMic() {
        if (micRunning.compareAndSet(false, true)) {
            thread(name = "intercom-mic") {
                try { micLoop() } finally { micRunning.set(false) }
                // if a control changed right as the loop ended, start again
                if (running && (talking || handsFree)) ensureMic()
            }
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by MainActivity before starting
    private fun micLoop() {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, FRAME_BYTES * 4)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            onStatus("Microphone failed to start. Check the microphone permission.")
            rec.release()
            talking = false
            handsFree = false
            return
        }
        try {
            rec.startRecording()
            val frame = ByteArray(PACKET).also { it[0] = T_AUDIO }   // sender id 0; host stamps the real one
            val prev = ByteArray(PACKET).also { it[0] = T_AUDIO }
            var prevValid = false
            var hang = 0
            var noise = 200.0
            while ((talking || handsFree) && running) {
                var off = 0
                while (off < FRAME_BYTES) {
                    val n = rec.read(frame, HEADER + off, FRAME_BYTES - off)
                    if (n <= 0) break
                    off += n
                }
                if (off < FRAME_BYTES) break

                if (talking) {                       // hold-to-talk: always send
                    broadcast(frame, PACKET, null)
                    hang = 0
                } else {                             // hands-free: send only when voice is detected
                    val level = rms(frame)
                    if (level > maxOf(MIN_VOICE_RMS, noise * NOISE_MULTIPLIER)) {
                        if (hang == 0 && prevValid) broadcast(prev, PACKET, null)  // don't clip the first syllable
                        hang = HANGOVER_FRAMES
                        broadcast(frame, PACKET, null)
                    } else if (hang > 0) {
                        hang--
                        broadcast(frame, PACKET, null)
                    } else {
                        noise = noise * 0.95 + level * 0.05
                    }
                }
                System.arraycopy(frame, 0, prev, 0, PACKET)
                prevValid = true
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    private fun rms(b: ByteArray): Double {
        var sum = 0.0
        var i = HEADER
        while (i < PACKET) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toInt()
            sum += (s * s).toDouble()
            i += 2
        }
        return Math.sqrt(sum / FRAME_SAMPLES)
    }

    // ---------------------------------------------------------------- network

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(PACKET)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                pkt.length = buf.size
                s.receive(pkt)
                val from = pkt.socketAddress
                if (isHost) {
                    val c = register(from)
                    if (c == null) {
                        sendTo(byteArrayOf(T_FULL), 1, from)
                        continue
                    }
                    when (buf[0]) {
                        T_HELLO -> sendTo(byteArrayOf(T_HELLO), 1, from)
                        T_AUDIO -> if (pkt.length == PACKET) {
                            buf[1] = c.id.toByte()
                            broadcast(buf, PACKET, from)
                            enqueue(c.id, buf)
                        }
                    }
                } else {
                    when (buf[0]) {
                        T_HELLO -> { connected = true; full = false; lastAck = System.currentTimeMillis() }
                        T_FULL -> { connected = false; full = true }
                        T_AUDIO -> if (pkt.length == PACKET) enqueue(buf[1].toInt() and 0xff, buf)
                    }
                }
            } catch (e: Exception) {
                if (running) onStatus("Network error: ${e.message}")
                break
            }
        }
    }

    @Synchronized
    private fun register(addr: SocketAddress): ClientInfo? {
        val now = System.currentTimeMillis()
        clients[addr]?.let { it.lastSeen = now; return it }
        val used = clients.values.map { it.id }.toSet()
        val id = (1..MAX_CLIENTS).firstOrNull { it !in used } ?: return null
        return ClientInfo(id, now).also { clients[addr] = it }
    }

    private fun keepAliveLoop() {
        val hello = byteArrayOf(T_HELLO)
        while (running) {
            if (isHost) {
                val cutoff = System.currentTimeMillis() - CLIENT_TIMEOUT_MS
                for ((addr, info) in clients) {
                    if (info.lastSeen < cutoff) { clients.remove(addr); senders.remove(info.id) }
                }
                onStatus("Hosting. ${clients.size}/$MAX_CLIENTS phones connected.")
            } else {
                sendTo(hello, hello.size, hostAddr)
                if (connected && System.currentTimeMillis() - lastAck > 9_000) connected = false
                onStatus(
                    when {
                        connected -> "Joined $hostIp."
                        full -> "Host is full (max $MAX_CLIENTS phones). Retrying..."
                        else -> "Connecting to $hostIp ..."
                    }
                )
            }
            try { Thread.sleep(3000) } catch (_: InterruptedException) { return }
        }
    }

    private fun broadcast(data: ByteArray, len: Int, except: SocketAddress?) {
        if (isHost) {
            for (addr in clients.keys) if (addr != except) sendTo(data, len, addr)
        } else {
            sendTo(data, len, hostAddr)
        }
    }

    private fun sendTo(data: ByteArray, len: Int, addr: SocketAddress?) {
        val s = socket ?: return
        if (addr == null) return
        try { s.send(DatagramPacket(data, len, addr)) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- audio out

    private fun enqueue(senderId: Int, buf: ByteArray) {
        val frame = ShortArray(FRAME_SAMPLES)
        ByteBuffer.wrap(buf, HEADER, FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(frame)
        val snd = senders.getOrPut(senderId) { Sender() }
        synchronized(snd) {
            snd.q.addLast(frame)
            while (snd.q.size > MAX_QUEUE) snd.q.removeFirst()
        }
    }

    private fun mixLoop() {
        val mix = IntArray(FRAME_SAMPLES)
        val out = ShortArray(FRAME_SAMPLES)
        while (running) {
            java.util.Arrays.fill(mix, 0)
            for (snd in senders.values) {
                var f: ShortArray? = null
                synchronized(snd) {
                    if (!snd.primed && snd.q.size >= PRIME_FRAMES) snd.primed = true
                    if (snd.primed) {
                        f = snd.q.pollFirst()
                        if (f == null) snd.primed = false
                    }
                }
                f?.let { for (i in 0 until FRAME_SAMPLES) mix[i] += it[i] }
            }
            for (i in 0 until FRAME_SAMPLES) out[i] = (mix[i] * GAIN).coerceIn(-32768, 32767).toShort()
            val t = track ?: break
            val n = try { t.write(out, 0, FRAME_SAMPLES) } catch (_: Exception) { -1 }
            if (n <= 0) try { Thread.sleep(5) } catch (_: InterruptedException) { return }
        }
    }

    private fun buildTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, FRAME_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
