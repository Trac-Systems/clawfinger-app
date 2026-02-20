package com.tracsystems.phonebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class SparkCallAssistantService : Service(), TextToSpeech.OnInitListener {
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var sessionId: String? = null
    private var selectedAudioSource: AudioSourceCandidate? = null
    private var selectedRootCaptureSource: RootCaptureCandidate? = null
    private var selectedRootCaptureSampleRate: Int? = null
    private var selectedRootCaptureChannels: Int? = null
    private var selectedRootPlaybackDevice: Int? = null
    private var consecutiveNoAudioRejects: Int = 0
    private var enrolledSpeaker: SpeakerFingerprint? = null
    @Volatile
    private var lastAssistantReplyText: String = ""
    private var currentVoiceCallVolume: Int = -1
    private val lastRootRouteRecoverAtMs = AtomicLong(0L)
    private val fallbackPromptAtMs = AtomicLong(0L)
    private val serviceActive = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private val callMuteEnforced = AtomicBoolean(false)
    private val lastSpeechActivityAtMs = AtomicLong(0L)
    private val rootBootstrapInFlight = AtomicBoolean(false)
    @Volatile
    private var rootBootstrapDone = false
    @Volatile
    private var rootCapturePinned = false
    private val silenceWatchdog = object : Runnable {
        override fun run() {
            if (!serviceActive.get()) {
                return
            }
            if (!InCallStateHolder.hasLiveCall()) {
                Log.i(TAG, "silence watchdog stopping (call ended)")
                stopSelf()
                return
            }
            val idleMs = System.currentTimeMillis() - lastSpeechActivityAtMs.get()
            if (!speaking.get() && idleMs >= SILENCE_HANGUP_MS) {
                Log.i(TAG, "auto hangup after silence timeout (${idleMs}ms)")
                CommandAuditLog.add("voice_bridge:silence_timeout:${idleMs}ms")
                TelecomControllerRegistry.controller().hangup(applicationContext)
                    .onFailure { error ->
                        Log.e(TAG, "silence timeout hangup failed", error)
                        CommandAuditLog.add("voice_bridge:hangup_error:${error.message}")
                    }
                stopSelf()
                return
            }
            mainHandler.postDelayed(this, SILENCE_CHECK_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "service onCreate")
        if (ENABLE_LOCAL_TTS_FALLBACK) {
            tts = TextToSpeech(this, this)
        } else {
            Log.i(TAG, "local TTS disabled (root playback mode)")
        }
        startForegroundSafe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "service stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REAPPLY_ROUTE) {
            if (serviceActive.get()) {
                val reason = intent.getStringExtra(EXTRA_ROUTE_REAPPLY_REASON).orEmpty().ifBlank { "manual" }
                Thread({
                    applyRootCallRouteProfile()
                    CommandAuditLog.add("root:route_reapply:$reason")
                }, "pb-root-route-reapply").start()
            }
            return START_STICKY
        }
        if (!InCallStateHolder.hasLiveCall()) {
            Log.i(TAG, "service start ignored (no live call)")
            stopSelf()
            return START_NOT_STICKY
        }
        if (serviceActive.compareAndSet(false, true)) {
            Log.i(TAG, "service started")
            CommandAuditLog.add("voice_bridge:start")
            sessionId = null
            selectedAudioSource = null
            selectedRootCaptureSource = ROOT_CAPTURE_CANDIDATES.firstOrNull()
            selectedRootCaptureSampleRate = null
            selectedRootCaptureChannels = null
            selectedRootPlaybackDevice = null
            consecutiveNoAudioRejects = 0
            enrolledSpeaker = null
            lastAssistantReplyText = ""
            rootCapturePinned = false
            fallbackPromptAtMs.set(0L)
            initializeRootRuntime()
            enableCallAudioRoute()
            InCallStateHolder.setSpeakerRoute(FORCE_SPEAKER_ROUTE)
            enforceCallMute()
            markSpeechActivity("service_start")
            startSilenceWatchdog()
            startCaptureLoop(40)
            Thread({
                applyRootCallRouteProfile()
                CommandAuditLog.add("root:route_set_async:done")
            }, "pb-root-route").start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "service onDestroy")
        serviceActive.set(false)
        tts?.stop()
        tts?.shutdown()
        tts = null
        mainHandler.removeCallbacks(silenceWatchdog)
        if (callMuteEnforced.compareAndSet(true, false)) {
            InCallStateHolder.setCallMuted(false)
        }
        restoreRootCallRouteProfile()
        networkExecutor.shutdownNow()
        CommandAuditLog.add("voice_bridge:stop")
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "tts init failed: $status")
            return
        }
        Log.i(TAG, "tts initialized")
        tts?.language = Locale.US
        tts?.setSpeechRate(1.05f)
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speaking.set(true)
                    applyVoiceCallVolume(TTS_SPEAKER_VOLUME_FRACTION, "tts_start")
                    if (KEEP_CALL_MUTED_DURING_TTS) {
                        setCallMute(true, "tts_start_keep_muted")
                    } else if (ENFORCE_CALL_MUTE) {
                        setCallMute(false, "tts_start_unmute_uplink")
                    }
                    Log.i(TAG, "tts onStart")
                }

                override fun onDone(utteranceId: String?) {
                    speaking.set(false)
                    applyVoiceCallVolume(LISTEN_SPEAKER_VOLUME_FRACTION, "tts_done")
                    if (ENFORCE_CALL_MUTE) {
                        setCallMute(true, "tts_done")
                    }
                    Log.i(TAG, "tts onDone")
                    if (serviceActive.get()) {
                        startCaptureLoop(150)
                    }
                }

                override fun onError(utteranceId: String?) {
                    speaking.set(false)
                    applyVoiceCallVolume(LISTEN_SPEAKER_VOLUME_FRACTION, "tts_error")
                    if (ENFORCE_CALL_MUTE) {
                        setCallMute(true, "tts_error")
                    }
                    Log.e(TAG, "tts onError")
                    if (serviceActive.get()) {
                        startCaptureLoop(250)
                    }
                }
            },
        )
        if (serviceActive.get() && SEND_GREETING_ON_CONNECT) {
            requestGreeting()
        }
    }

    private fun requestGreeting() {
        if (speaking.get()) return
        speaking.set(true)
        networkExecutor.execute {
            val response = runCatching {
                callSparkTurn(
                    transcript = "Greet the caller in one short sentence.",
                    audioWav = buildSilenceWav(),
                )
            }.onFailure { error ->
                Log.e(TAG, "spark greeting failed", error)
                CommandAuditLog.add("voice_bridge:error:${error.message}")
            }.getOrNull()
            val reply = sanitizeReply(response?.reply?.trim().orEmpty())
            if (reply.isBlank()) {
                speaking.set(false)
                startCaptureLoop(TRANSCRIPT_RETRY_DELAY_MS)
                return@execute
            }
            val rootPlayback = if (response?.livePlaybackHandled == true) {
                RootPlaybackResult(
                    played = !response.livePlaybackInterrupted,
                    interrupted = response.livePlaybackInterrupted,
                )
            } else if (ENABLE_ROOT_PCM_BRIDGE) {
                playReplyViaRootPcm(
                    audioWavBase64 = response?.audioWavBase64,
                    replyTextForEcho = reply,
                )
            } else {
                RootPlaybackResult(played = false, interrupted = false)
            }
            if (rootPlayback.played) {
                CommandAuditLog.add("voice_bridge:greeting_root:${reply.take(96)}")
                speaking.set(false)
                startCaptureLoop(POST_PLAYBACK_CAPTURE_DELAY_MS)
                return@execute
            }
            if (rootPlayback.interrupted) {
                CommandAuditLog.add("voice_bridge:greeting_barge_in")
                speaking.set(false)
                startCaptureLoop(BARGE_IN_RESUME_DELAY_MS)
                return@execute
            }
            if (ENABLE_ROOT_PCM_BRIDGE) {
                CommandAuditLog.add("voice_bridge:greeting_root_playback_failed")
                speaking.set(false)
                startCaptureLoop(NO_AUDIO_RETRY_DELAY_MS)
                return@execute
            }
            Log.i(TAG, "spark greeting: ${reply.take(96)}")
            mainHandler.post {
                val utteranceId = "pb-${UUID.randomUUID()}"
                tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                CommandAuditLog.add("voice_bridge:greeting:${reply.take(96)}")
            }
        }
    }

    private fun requestReplyFromAudioFallback() {
        speaking.set(true)
        networkExecutor.execute {
            if (!InCallStateHolder.hasLiveCall()) {
                speaking.set(false)
                return@execute
            }
            var transcriptPreview = ""
            var transcriptAudioWav: ByteArray? = null
            var transcriptChunkCount = 0
            var lastRejectionReason: String? = null
            var sameSourceRetries = 0
            if (ENABLE_UTTERANCE_STATE_MACHINE) {
                val utterance = captureUtteranceStateMachine()
                if (utterance == null) {
                    lastRejectionReason = "utterance_empty"
                } else {
                    transcriptPreview = utterance.transcript
                    transcriptAudioWav = utterance.audioWav
                    transcriptChunkCount = utterance.chunkCount
                }
            } else {
                repeat(MAX_CAPTURE_ATTEMPTS_PER_TURN) { attempt ->
                    if (transcriptPreview.isNotBlank()) {
                        return@repeat
                    }
                    Log.i(TAG, "running audio fallback capture (attempt ${attempt + 1}/$MAX_CAPTURE_ATTEMPTS_PER_TURN)")
                    val captureDurationMs = CAPTURE_DURATION_BY_ATTEMPT_MS
                        .getOrElse(attempt) { CAPTURE_DURATION_BY_ATTEMPT_MS.last() }
                    val stickToSelectedSource = sameSourceRetries < MAX_SAME_SOURCE_RETRIES
                    val capture = captureFallbackAudio(
                        durationMs = captureDurationMs,
                        stickToSelectedSource = stickToSelectedSource,
                    )
                    if (capture.wav == null) {
                        lastRejectionReason = capture.rejectionReason ?: "empty"
                        Log.w(TAG, "audio fallback capture rejected: $lastRejectionReason")
                        if (capture.rejectionReason == "no_audio_source") {
                            consecutiveNoAudioRejects += 1
                            maybeRecoverRootRoute()
                            if (consecutiveNoAudioRejects >= NO_AUDIO_UNPIN_THRESHOLD) {
                                resetRootCapturePin("no_audio_source_streak_$consecutiveNoAudioRejects")
                            }
                        } else {
                            consecutiveNoAudioRejects = 0
                        }
                        if (shouldRetrySameSource(lastRejectionReason, sameSourceRetries)) {
                            sameSourceRetries += 1
                            Log.i(
                                TAG,
                                "keeping current root source for retry ($sameSourceRetries/$MAX_SAME_SOURCE_RETRIES) reason=$lastRejectionReason",
                            )
                        } else {
                            sameSourceRetries = 0
                            rotateRootCaptureSource()
                        }
                        return@repeat
                    }
                    consecutiveNoAudioRejects = 0
                    capture.analysis?.let { analysis ->
                        maybeEnrollSpeaker(analysis)
                    }
                    if (ENABLE_DEBUG_WAV_DUMP) {
                        persistDebugWav(
                            prefix = "rx",
                            wavBytes = capture.wav,
                            hint = "a${attempt + 1}-${selectedRootCaptureSource?.name ?: "unknown"}",
                        )
                    }
                    val candidateTranscript = runCatching { callSparkAsr(capture.wav) }
                        .onFailure { error ->
                            Log.e(TAG, "spark ASR failed", error)
                            CommandAuditLog.add("voice_bridge:asr_error:${error.message}")
                        }
                        .getOrNull()
                        ?.trim()
                        .orEmpty()
                    if (candidateTranscript.isBlank()) {
                        Log.w(TAG, "spark ASR transcript empty")
                        lastRejectionReason = "asr_empty"
                        if (shouldRetrySameSource(lastRejectionReason, sameSourceRetries)) {
                            sameSourceRetries += 1
                        } else {
                            sameSourceRetries = 0
                            rotateRootCaptureSource()
                        }
                        return@repeat
                    }
                    Log.i(TAG, "spark ASR transcript: ${candidateTranscript.take(140)}")
                    CommandAuditLog.add("voice_bridge:asr:${candidateTranscript.take(96)}")
                    val transcriptRejectReason = transcriptRejectReason(candidateTranscript)
                    if (transcriptRejectReason != null) {
                        if (transcriptRejectReason == "low_information") {
                            Log.w(
                                TAG,
                                "spark ASR transcript low-information; forwarding full audio to server ASR",
                            )
                            CommandAuditLog.add("voice_bridge:transcript_reject_soft:low_information")
                        } else {
                            Log.w(TAG, "spark ASR transcript rejected as low quality ($transcriptRejectReason)")
                            CommandAuditLog.add("voice_bridge:transcript_reject:$transcriptRejectReason")
                            lastRejectionReason = "low_quality_transcript"
                            if (shouldRetrySameSource(lastRejectionReason, sameSourceRetries)) {
                                sameSourceRetries += 1
                            } else {
                                sameSourceRetries = 0
                                rotateRootCaptureSource()
                            }
                            return@repeat
                        }
                    }
                    pinRootCaptureSource()
                    sameSourceRetries = 0
                    transcriptPreview = candidateTranscript
                    transcriptAudioWav = capture.wav
                    transcriptChunkCount = 1
                }
            }
            if (transcriptPreview.isBlank()) {
                val clarificationSpoken = maybeSpeakClarification(lastRejectionReason)
                if (!clarificationSpoken) {
                    speaking.set(false)
                    val retryDelay = when (lastRejectionReason) {
                        "no_audio_source" -> NO_AUDIO_RETRY_DELAY_MS
                        "asr_empty", "low_quality_transcript" -> TRANSCRIPT_RETRY_DELAY_MS
                        else -> CAPTURE_RETRY_DELAY_MS
                    }
                    startCaptureLoop(retryDelay)
                }
                return@execute
            }
            val seedAudio = transcriptAudioWav ?: buildSilenceWav(320)
            val assembledUtterance = if (!ENABLE_UTTERANCE_STATE_MACHINE && ENABLE_UTTERANCE_CONTINUATION && shouldCollectContinuation(transcriptPreview)) {
                assembleUtteranceContinuation(
                    seedTranscript = transcriptPreview,
                    seedAudioWav = seedAudio,
                    seedChunkCount = transcriptChunkCount,
                )
            } else {
                AssembledUtterance(
                    transcript = transcriptPreview,
                    audioWav = seedAudio,
                    chunkCount = max(1, transcriptChunkCount),
                )
            }
            transcriptPreview = assembledUtterance.transcript
            transcriptAudioWav = assembledUtterance.audioWav
            transcriptChunkCount = assembledUtterance.chunkCount
            if (transcriptChunkCount > 1) {
                Log.i(TAG, "assembled utterance chunks=$transcriptChunkCount transcript=${transcriptPreview.take(140)}")
                CommandAuditLog.add("voice_bridge:utterance_chunks:$transcriptChunkCount")
            }
            val response = runCatching {
                callSparkTurn(
                    transcript = transcriptPreview,
                    audioWav = transcriptAudioWav ?: buildSilenceWav(320),
                    skipAsr = false,
                )
            }.onFailure { error ->
                Log.e(TAG, "spark text turn failed", error)
                CommandAuditLog.add("voice_bridge:error:${error.message}")
            }.getOrNull()
            response?.transcript?.takeIf { it.isNotBlank() }?.let { turnTranscript ->
                Log.i(TAG, "spark turn transcript (server): ${turnTranscript.take(140)}")
                CommandAuditLog.add("voice_bridge:turn_asr:${turnTranscript.take(96)}")
            }
            if (ENABLE_DEBUG_WAV_DUMP) {
                val replyWav = response?.audioWavBase64
                    ?.let { runCatching { Base64.getDecoder().decode(it.trim()) }.getOrNull() }
                persistDebugWav(
                    prefix = "tx",
                    wavBytes = replyWav,
                    hint = transcriptPreview.take(32),
                )
            }
            val reply = response?.reply?.trim().orEmpty()
            if (reply.isBlank()) {
                Log.w(TAG, "spark audio reply empty")
                speaking.set(false)
                startCaptureLoop(TRANSCRIPT_RETRY_DELAY_MS)
                return@execute
            }
            val cleanReply = sanitizeReply(reply)
            Log.i(TAG, "spark audio reply: ${cleanReply.take(96)}")
            val rootPlayback = if (response?.livePlaybackHandled == true) {
                RootPlaybackResult(
                    played = !response.livePlaybackInterrupted,
                    interrupted = response.livePlaybackInterrupted,
                )
            } else if (ENABLE_ROOT_PCM_BRIDGE) {
                playReplyViaRootPcm(
                    audioWavBase64 = response?.audioWavBase64,
                    replyTextForEcho = cleanReply,
                )
            } else {
                RootPlaybackResult(played = false, interrupted = false)
            }
            if (rootPlayback.played) {
                CommandAuditLog.add("voice_bridge:reply_root:${cleanReply.take(96)}")
                lastAssistantReplyText = cleanReply
                markSpeechActivity("root_reply_played")
                speaking.set(false)
                startCaptureLoop(POST_PLAYBACK_CAPTURE_DELAY_MS)
                return@execute
            }
            if (rootPlayback.interrupted) {
                CommandAuditLog.add("voice_bridge:reply_barge_in:${cleanReply.take(96)}")
                lastAssistantReplyText = cleanReply
                markSpeechActivity("root_reply_interrupted")
                speaking.set(false)
                startCaptureLoop(BARGE_IN_RESUME_DELAY_MS)
                return@execute
            }
            if (ENABLE_ROOT_PCM_BRIDGE) {
                CommandAuditLog.add("voice_bridge:root_playback_failed")
                maybeRecoverRootRoute()
                markSpeechActivity("root_playback_failed")
                selectedRootPlaybackDevice = null
                speaking.set(false)
                startCaptureLoop(POST_PLAYBACK_CAPTURE_DELAY_MS)
                return@execute
            }
            mainHandler.post {
                val utteranceId = "pb-${UUID.randomUUID()}"
                tts?.speak(cleanReply, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                CommandAuditLog.add("voice_bridge:reply:${cleanReply.take(96)}")
            }
        }
    }

    private fun startCaptureLoop(delayMs: Long) {
        mainHandler.postDelayed({
            if (!serviceActive.get()) return@postDelayed
            if (speaking.get()) return@postDelayed
            if (!InCallStateHolder.hasLiveCall()) {
                Log.i(TAG, "stopping capture loop (call ended)")
                stopSelf()
                return@postDelayed
            }
            requestReplyFromAudioFallback()
        }, delayMs)
    }

    private fun captureUtteranceStateMachine(): AssembledUtterance? {
        if (!InCallStateHolder.hasLiveCall()) {
            return null
        }
        if (!ENABLE_ROOT_PCM_BRIDGE) {
            return null
        }
        var source = selectedRootCaptureSource
            ?: ROOT_CAPTURE_CANDIDATES.firstOrNull()
            ?: return null
        selectedRootCaptureSource = source

        var targetSampleRate = selectedRootCaptureSampleRate ?: ROOT_CAPTURE_REQUEST_SAMPLE_RATE
        val preRollMaxBytes = ((targetSampleRate * UTTERANCE_PRE_ROLL_MS) / 1000) * 2
        val minSpeechSamples = (targetSampleRate * UTTERANCE_MIN_SPEECH_MS) / 1000
        val silenceSamplesLimit = (targetSampleRate * UTTERANCE_SILENCE_MS) / 1000
        val maxTurnSamples = (targetSampleRate * UTTERANCE_MAX_TURN_MS) / 1000

        var preRoll = ByteArray(0)
        val current = ByteArrayOutputStream()
        var speakingNow = false
        var speechSamples = 0
        var silenceSamples = 0
        var chunkCount = 0
        var loopMs = 0
        var lastTranscriptReject: String? = null

        while (loopMs < UTTERANCE_LOOP_TIMEOUT_MS && InCallStateHolder.hasLiveCall()) {
            val captured = captureRootPcmAdaptive(
                device = source.device,
                durationMs = UTTERANCE_CAPTURE_CHUNK_MS,
                preferredSampleRate = targetSampleRate,
            )
            loopMs += UTTERANCE_CAPTURE_CHUNK_MS
            if (captured == null) {
                consecutiveNoAudioRejects += 1
                maybeRecoverRootRoute()
                if (consecutiveNoAudioRejects >= NO_AUDIO_UNPIN_THRESHOLD) {
                    resetRootCapturePin("utterance_state_no_audio_$consecutiveNoAudioRejects")
                    source = selectedRootCaptureSource
                        ?: ROOT_CAPTURE_CANDIDATES.firstOrNull()
                        ?: source
                }
                if (speakingNow) {
                    silenceSamples += (targetSampleRate * UTTERANCE_CAPTURE_CHUNK_MS) / 1000
                    if (silenceSamples >= silenceSamplesLimit && speechSamples >= minSpeechSamples) {
                        break
                    }
                }
                continue
            }
            consecutiveNoAudioRejects = 0
            selectedRootCaptureSampleRate = captured.sampleRate
            selectedRootCaptureChannels = captured.channels
            if (captured.sampleRate != targetSampleRate) {
                targetSampleRate = captured.sampleRate
            }
            val pcm = if (captured.sampleRate == targetSampleRate) {
                captured.pcm
            } else {
                resamplePcm16Mono(captured.pcm, captured.sampleRate, targetSampleRate)
            }
            if (pcm.size < 2) {
                continue
            }
            val chunkSamples = pcm.size / 2
            val rms = rmsPcm16(pcm)
            val voiced = rms >= UTTERANCE_VAD_RMS

            if (!speakingNow) {
                preRoll = appendAndTrimBytes(preRoll, pcm, preRollMaxBytes)
                if (!voiced) {
                    continue
                }
                speakingNow = true
                current.reset()
                if (preRoll.isNotEmpty()) {
                    current.write(preRoll)
                }
                speechSamples = chunkSamples
                silenceSamples = 0
            } else if (voiced) {
                speechSamples += chunkSamples
                silenceSamples = 0
            } else {
                silenceSamples += chunkSamples
            }

            current.write(pcm)
            chunkCount += 1
            val totalSamples = current.size() / 2
            val shouldFlush = totalSamples >= maxTurnSamples || (
                silenceSamples >= silenceSamplesLimit && speechSamples >= minSpeechSamples
            )
            if (shouldFlush) {
                break
            }
        }

        if (!speakingNow || speechSamples < minSpeechSamples || current.size() < 2) {
            return null
        }
        val utterancePcm = current.toByteArray()
        val maxBytes = maxTurnSamples * 2
        val cappedPcm = if (utterancePcm.size > maxBytes && maxBytes > 0) {
            utterancePcm.copyOfRange(0, maxBytes)
        } else {
            utterancePcm
        }
        val utteranceWav = pcm16ToWav(cappedPcm, targetSampleRate)
        if (ENABLE_DEBUG_WAV_DUMP) {
            persistDebugWav(
                prefix = "rxm",
                wavBytes = utteranceWav,
                hint = "vad-$chunkCount",
            )
        }
        val transcript = runCatching { callSparkAsr(utteranceWav) }
            .onFailure { error ->
                Log.e(TAG, "spark ASR state-machine failed", error)
                CommandAuditLog.add("voice_bridge:asr_error:${error.message}")
            }
            .getOrNull()
            ?.trim()
            .orEmpty()
        if (transcript.isBlank()) {
            return null
        }
        val rejectReason = transcriptRejectReason(transcript)
        if (rejectReason != null && rejectReason != "low_information") {
            lastTranscriptReject = rejectReason
        }
        if (lastTranscriptReject != null) {
            CommandAuditLog.add("voice_bridge:transcript_reject:$lastTranscriptReject")
            return null
        }
        pinRootCaptureSource()
        Log.i(
            TAG,
            "state-machine utterance transcript=${transcript.take(140)} chunks=$chunkCount speechSamples=$speechSamples silenceSamples=$silenceSamples",
        )
        CommandAuditLog.add("voice_bridge:asr:${transcript.take(96)}")
        return AssembledUtterance(
            transcript = transcript,
            audioWav = utteranceWav,
            chunkCount = max(1, chunkCount),
        )
    }

    private fun appendAndTrimBytes(existing: ByteArray, incoming: ByteArray, maxBytes: Int): ByteArray {
        val merged = ByteArray(existing.size + incoming.size)
        System.arraycopy(existing, 0, merged, 0, existing.size)
        System.arraycopy(incoming, 0, merged, existing.size, incoming.size)
        return if (maxBytes > 0 && merged.size > maxBytes) {
            merged.copyOfRange(merged.size - maxBytes, merged.size)
        } else {
            merged
        }
    }

    private fun shouldCollectContinuation(transcript: String): Boolean {
        val trimmed = transcript.trim()
        if (trimmed.isBlank()) {
            return false
        }
        val terminal = trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")
        if (!terminal) {
            return true
        }
        val tokenCount = trimmed.split(Regex("\\s+")).count { it.isNotBlank() }
        return tokenCount <= UTTERANCE_TERMINAL_MIN_TOKEN_COUNT
    }

    private fun assembleUtteranceContinuation(
        seedTranscript: String,
        seedAudioWav: ByteArray,
        seedChunkCount: Int,
    ): AssembledUtterance {
        var mergedTranscript = seedTranscript.trim()
        var mergedAudioWav = seedAudioWav
        var chunkCount = max(1, seedChunkCount)
        var boundaryWindows = 0
        for (windowIndex in 0 until MAX_UTTERANCE_CONTINUATION_WINDOWS) {
            if (!InCallStateHolder.hasLiveCall()) {
                break
            }
            val capture = captureFallbackAudio(
                durationMs = UTTERANCE_CONTINUATION_CAPTURE_MS,
                stickToSelectedSource = true,
            )
            if (capture.wav == null) {
                val reason = capture.rejectionReason ?: "empty"
                if (reason == "no_audio_source") {
                    consecutiveNoAudioRejects += 1
                    maybeRecoverRootRoute()
                } else {
                    consecutiveNoAudioRejects = 0
                }
                boundaryWindows += 1
                Log.i(
                    TAG,
                    "utterance continuation boundary capture reason=$reason window=${windowIndex + 1} boundary=$boundaryWindows/$UTTERANCE_END_BOUNDARY_WINDOWS",
                )
                if (boundaryWindows >= UTTERANCE_END_BOUNDARY_WINDOWS) {
                    break
                }
                continue
            }
            consecutiveNoAudioRejects = 0
            capture.analysis?.let { maybeEnrollSpeaker(it) }
            if (ENABLE_DEBUG_WAV_DUMP) {
                persistDebugWav(
                    prefix = "rxu",
                    wavBytes = capture.wav,
                    hint = "c${windowIndex + 1}-${selectedRootCaptureSource?.name ?: "unknown"}",
                )
            }
            val continuationTranscript = runCatching { callSparkAsr(capture.wav) }
                .onFailure { error ->
                    Log.e(TAG, "spark ASR continuation failed", error)
                    CommandAuditLog.add("voice_bridge:asr_cont_error:${error.message}")
                }
                .getOrNull()
                ?.trim()
                .orEmpty()
            if (continuationTranscript.isBlank()) {
                boundaryWindows += 1
                Log.i(
                    TAG,
                    "utterance continuation boundary transcript=empty window=${windowIndex + 1} boundary=$boundaryWindows/$UTTERANCE_END_BOUNDARY_WINDOWS",
                )
                if (boundaryWindows >= UTTERANCE_END_BOUNDARY_WINDOWS) {
                    break
                }
                continue
            }
            val rejectReason = transcriptRejectReason(continuationTranscript)
            if (rejectReason != null && rejectReason != "low_information") {
                CommandAuditLog.add("voice_bridge:transcript_reject_cont:$rejectReason")
                boundaryWindows += 1
                Log.i(
                    TAG,
                    "utterance continuation rejected reason=$rejectReason window=${windowIndex + 1} boundary=$boundaryWindows/$UTTERANCE_END_BOUNDARY_WINDOWS transcript=${continuationTranscript.take(96)}",
                )
                if (boundaryWindows >= UTTERANCE_END_BOUNDARY_WINDOWS) {
                    break
                }
                continue
            }
            if (rejectReason == "low_information") {
                CommandAuditLog.add("voice_bridge:transcript_reject_cont_soft:low_information")
            }
            boundaryWindows = 0
            pinRootCaptureSource()
            mergedTranscript = appendTranscriptSegment(mergedTranscript, continuationTranscript)
            mergedAudioWav = mergeWavSegments(mergedAudioWav, capture.wav) ?: mergedAudioWav
            chunkCount += 1
            CommandAuditLog.add("voice_bridge:asr_cont:${continuationTranscript.take(96)}")
            if (chunkCount >= MAX_UTTERANCE_CHUNKS_PER_TURN) {
                break
            }
        }
        return AssembledUtterance(
            transcript = mergedTranscript,
            audioWav = mergedAudioWav,
            chunkCount = chunkCount,
        )
    }

    private fun appendTranscriptSegment(current: String, next: String): String {
        val left = current.trim()
        val right = next.trim()
        if (left.isBlank()) {
            return right
        }
        if (right.isBlank()) {
            return left
        }
        val separator = if (right.firstOrNull() in setOf(',', '.', '!', '?', ';', ':')) "" else " "
        return "$left$separator$right"
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun mergeWavSegments(baseWav: ByteArray, nextWav: ByteArray): ByteArray? {
        val baseDecoded = decodeWavToPcm16Mono(baseWav) ?: return null
        val nextDecoded = decodeWavToPcm16Mono(nextWav) ?: return null
        val sampleRate = baseDecoded.sampleRate.takeIf { it > 0 }
            ?: nextDecoded.sampleRate.takeIf { it > 0 }
            ?: ROOT_CAPTURE_REQUEST_SAMPLE_RATE
        val basePcm = if (baseDecoded.sampleRate == sampleRate) {
            baseDecoded.pcm
        } else {
            resamplePcm16Mono(baseDecoded.pcm, baseDecoded.sampleRate, sampleRate)
        }
        val nextPcm = if (nextDecoded.sampleRate == sampleRate) {
            nextDecoded.pcm
        } else {
            resamplePcm16Mono(nextDecoded.pcm, nextDecoded.sampleRate, sampleRate)
        }
        val mergedPcm = ByteArrayOutputStream(basePcm.size + nextPcm.size).apply {
            write(basePcm)
            write(nextPcm)
        }.toByteArray()
        val maxBytes = ((sampleRate * MAX_UTTERANCE_MERGED_AUDIO_MS) / 1000) * 2
        val cappedPcm = if (mergedPcm.size > maxBytes) {
            mergedPcm.copyOfRange(mergedPcm.size - maxBytes, mergedPcm.size)
        } else {
            mergedPcm
        }
        return pcm16ToWav(cappedPcm, sampleRate)
    }

    private fun captureFallbackAudio(
        durationMs: Int = 1800,
        sampleRate: Int = ROOT_CAPTURE_REQUEST_SAMPLE_RATE,
        stickToSelectedSource: Boolean = false,
    ): CaptureResult {
        enableCallAudioRoute()
        if (ENABLE_ROOT_PCM_BRIDGE) {
            val rootCapture = captureFallbackAudioViaRoot(
                durationMs = durationMs,
                sampleRate = sampleRate,
                stickToSelectedSource = stickToSelectedSource,
            )
            if (rootCapture.wav != null || STRICT_REMOTE_AUDIO_ONLY) {
                return rootCapture
            }
        }
        var source = selectedAudioSource ?: probeBestAudioSource(sampleRate)
        if (source == null) {
            return CaptureResult(rejectionReason = "no_audio_source")
        }
        val pcm = capturePcmForSource(
            source = source,
            durationMs = durationMs,
            sampleRate = sampleRate,
        )
        if (pcm == null) {
            return CaptureResult(rejectionReason = "capture_empty")
        }
        selectedAudioSource = source
        val analysis = analyzeCapture(pcm, sampleRate)
        Log.i(
            TAG,
            "audio source=${source.name}(${source.id}) rms=${analysis.overallRms} voicedMs=${analysis.voicedMs} voicedRatio=${"%.2f".format(analysis.voicedRatio)} range=${"%.1f".format(analysis.dynamicRange)} clip=${"%.3f".format(analysis.clippingRatio)} conf=${"%.2f".format(analysis.confidence)}",
        )
        val qualityReject = evaluateCaptureQuality(analysis)
        if (qualityReject != null) {
            CommandAuditLog.add("voice_bridge:reject:$qualityReject")
            return CaptureResult(analysis = analysis, rejectionReason = qualityReject)
        }
        val speakerReject = evaluateSpeakerMatch(analysis)
        if (speakerReject != null) {
            CommandAuditLog.add("voice_bridge:reject:$speakerReject")
            return CaptureResult(analysis = analysis, rejectionReason = speakerReject)
        }
        markSpeechActivity("audio_fallback:${source.name}")
        CommandAuditLog.add(
            "voice_bridge:source:${source.name}:rms=${"%.1f".format(analysis.overallRms)}:conf=${"%.2f".format(analysis.confidence)}",
        )
        return CaptureResult(
            wav = pcm16ToWav(pcm, sampleRate),
            analysis = analysis,
            rejectionReason = null,
        )
    }

    private fun evaluateCaptureQuality(analysis: CaptureAnalysis): String? {
        if (analysis.overallRms < MIN_CAPTURE_RMS) {
            return "low_rms"
        }
        if (analysis.voicedMs < MIN_VOICED_MS) {
            return "short_voice"
        }
        if (analysis.voicedRatio < MIN_VOICED_RATIO) {
            return "low_voiced_ratio"
        }
        if (analysis.dynamicRange < MIN_DYNAMIC_RANGE) {
            return "flat_signal"
        }
        if (analysis.clippingRatio > MAX_CLIPPING_RATIO) {
            return "clipping"
        }
        if (analysis.confidence < MIN_CAPTURE_CONFIDENCE) {
            return "low_confidence"
        }
        return null
    }

    private fun evaluateSpeakerMatch(analysis: CaptureAnalysis): String? {
        if (!ENABLE_SPEAKER_VERIFICATION) {
            return null
        }
        val baseline = enrolledSpeaker ?: return null
        val similarity = speakerSimilarity(baseline, analysis.fingerprint)
        CommandAuditLog.add("voice_bridge:speaker_similarity:${"%.2f".format(similarity)}")
        return if (similarity >= MIN_SPEAKER_SIMILARITY) {
            null
        } else {
            "speaker_mismatch"
        }
    }

    private fun maybeEnrollSpeaker(analysis: CaptureAnalysis) {
        if (!ENABLE_SPEAKER_VERIFICATION) {
            return
        }
        if (enrolledSpeaker != null) {
            return
        }
        if (analysis.voicedMs < MIN_ENROLL_VOICED_MS) {
            return
        }
        enrolledSpeaker = analysis.fingerprint
        CommandAuditLog.add("voice_bridge:speaker_enrolled")
        Log.i(TAG, "speaker profile enrolled")
    }

    private fun maybeSpeakClarification(reason: String?): Boolean {
        if (!ENABLE_LOCAL_CLARIFICATION_TTS) {
            return false
        }
        val now = System.currentTimeMillis()
        val last = fallbackPromptAtMs.get()
        if (now - last < CLARIFICATION_COOLDOWN_MS) {
            return false
        }
        if (!fallbackPromptAtMs.compareAndSet(last, now)) {
            return false
        }
        val prompt = when (reason) {
            "speaker_mismatch" -> "I am picking up another voice nearby. Please speak again clearly."
            "low_rms", "low_voiced_ratio", "short_voice" -> "I could not hear that clearly. Please repeat."
            "flat_signal", "clipping", "low_confidence" -> "The line was noisy. Please repeat slowly."
            else -> "Please repeat that clearly."
        }
        speaking.set(true)
        mainHandler.post {
            val utteranceId = "pb-${UUID.randomUUID()}"
            tts?.speak(prompt, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            CommandAuditLog.add("voice_bridge:clarify:${reason ?: "unknown"}")
        }
        return true
    }

    private fun captureFallbackAudioViaRoot(
        durationMs: Int,
        sampleRate: Int,
        stickToSelectedSource: Boolean,
    ): CaptureResult {
        val captureOrder = if (stickToSelectedSource) {
            selectedRootCaptureSource?.let { listOf(it) } ?: ROOT_CAPTURE_CANDIDATES
        } else {
            buildList {
                selectedRootCaptureSource?.let { add(it) }
                ROOT_CAPTURE_CANDIDATES.forEach { candidate ->
                    if (!contains(candidate)) {
                        add(candidate)
                    }
                }
            }
        }
        var lastRejection: String? = null
        for (candidate in captureOrder) {
            var captured = captureRootPcmAdaptive(
                device = candidate.device,
                durationMs = durationMs,
                preferredSampleRate = sampleRate,
            ) ?: continue
            captured = maybeExtendRootCaptureTail(candidate.device, captured)
            val rms = rmsPcm16(captured.pcm)
            if (rms < ROOT_MIN_CAPTURE_RMS) {
                continue
            }
            val analysis = analyzeCapture(captured.pcm, captured.sampleRate)
            Log.i(
                TAG,
                "audio root source=${candidate.name}(${candidate.device}) sr=${captured.sampleRate} rms=${analysis.overallRms} voicedMs=${analysis.voicedMs} voicedRatio=${"%.2f".format(analysis.voicedRatio)} range=${"%.1f".format(analysis.dynamicRange)} clip=${"%.3f".format(analysis.clippingRatio)} conf=${"%.2f".format(analysis.confidence)}",
            )
            if (!ROOT_SKIP_QUALITY_GATES) {
                val qualityReject = evaluateCaptureQuality(analysis)
                if (qualityReject != null) {
                    lastRejection = qualityReject
                    continue
                }
                val speakerReject = evaluateSpeakerMatch(analysis)
                if (speakerReject != null) {
                    lastRejection = speakerReject
                    continue
                }
            } else {
                if (analysis.overallRms < ROOT_MIN_ACCEPT_RMS) {
                    lastRejection = "root_low_rms"
                    continue
                }
                if (analysis.voicedMs < ROOT_MIN_ACCEPT_VOICED_MS) {
                    lastRejection = "root_short_voice"
                    continue
                }
            }
            selectedRootCaptureSource = candidate
            selectedRootCaptureSampleRate = captured.sampleRate
            selectedRootCaptureChannels = captured.channels
            markSpeechActivity("root_audio:${candidate.name}")
            CommandAuditLog.add(
                "voice_bridge:root_source:${candidate.name}:rms=${"%.1f".format(analysis.overallRms)}:conf=${"%.2f".format(analysis.confidence)}",
            )
            return CaptureResult(
                wav = pcm16ToWav(captured.pcm, captured.sampleRate),
                analysis = analysis,
                rejectionReason = null,
            )
        }
        if (!lastRejection.isNullOrBlank()) {
            CommandAuditLog.add("voice_bridge:reject:$lastRejection")
            return CaptureResult(rejectionReason = lastRejection)
        }
        return CaptureResult(rejectionReason = "no_audio_source")
    }

    private fun shouldRetrySameSource(reason: String?, retriesSoFar: Int): Boolean {
        if (reason.isNullOrBlank()) {
            return false
        }
        if (retriesSoFar >= MAX_SAME_SOURCE_RETRIES) {
            return false
        }
        if (SOURCE_ROTATE_IMMEDIATELY_REASONS.contains(reason)) {
            return false
        }
        return true
    }

    private fun maybeExtendRootCaptureTail(device: Int, frame: RootCaptureFrame): RootCaptureFrame {
        if (!ROOT_CAPTURE_TRAILING_EXTENSION_ENABLED) {
            return frame
        }
        if (!hasTrailingSpeech(frame)) {
            return frame
        }
        val extension = captureRootPcmForDevice(
            device = device,
            durationMs = ROOT_CAPTURE_TRAILING_EXTENSION_MS,
            sampleRate = frame.sampleRate,
            channels = frame.channels,
        ) ?: return frame

        val extensionPcm = if (extension.sampleRate == frame.sampleRate) {
            extension.pcm
        } else {
            resamplePcm16Mono(
                pcm = extension.pcm,
                fromSampleRate = extension.sampleRate,
                toSampleRate = frame.sampleRate,
            )
        }
        if (extensionPcm.isEmpty()) {
            return frame
        }
        val merged = ByteArray(frame.pcm.size + extensionPcm.size)
        System.arraycopy(frame.pcm, 0, merged, 0, frame.pcm.size)
        System.arraycopy(extensionPcm, 0, merged, frame.pcm.size, extensionPcm.size)

        val maxBytes = ((frame.sampleRate * ROOT_CAPTURE_MAX_MERGED_MS) / 1000) * 2
        val capped = if (merged.size > maxBytes) {
            merged.copyOfRange(0, maxBytes)
        } else {
            merged
        }

        Log.i(
            TAG,
            "root capture tail-extended device=$device bytes=${frame.pcm.size}->${capped.size}",
        )
        CommandAuditLog.add("voice_bridge:tail_extend:${frame.pcm.size}->${capped.size}")
        return RootCaptureFrame(
            pcm = capped,
            sampleRate = frame.sampleRate,
            channels = frame.channels,
        )
    }

    private fun hasTrailingSpeech(frame: RootCaptureFrame): Boolean {
        if (frame.pcm.size < 2 || frame.sampleRate <= 0) {
            return false
        }
        val tailSamples = max(1, (frame.sampleRate * ROOT_CAPTURE_TRAILING_VOICE_WINDOW_MS) / 1000)
        val tailBytes = (tailSamples * 2).coerceAtMost(frame.pcm.size)
        val tail = frame.pcm.copyOfRange(frame.pcm.size - tailBytes, frame.pcm.size)
        val analysis = analyzeCapture(tail, frame.sampleRate)
        if (analysis.overallRms < ROOT_CAPTURE_TRAILING_MIN_RMS) {
            return false
        }
        return analysis.voicedMs >= ROOT_CAPTURE_TRAILING_MIN_VOICED_MS
    }

    private fun captureRootPcmAdaptive(
        device: Int,
        durationMs: Int,
        preferredSampleRate: Int,
    ): RootCaptureFrame? {
        val pinnedRate = selectedRootCaptureSampleRate
        val pinnedChannels = selectedRootCaptureChannels
        val pinnedSourceDevice = selectedRootCaptureSource?.device
        val usePinnedFormat = rootCapturePinned && pinnedSourceDevice == device
        val rateOrder = if (usePinnedFormat && pinnedRate != null) {
            listOf(pinnedRate)
        } else {
            buildList {
                add(preferredSampleRate)
                ROOT_CAPTURE_SAMPLE_RATE_CANDIDATES.forEach { rate ->
                    if (!contains(rate)) add(rate)
                }
            }
        }
        val channelOrder = if (usePinnedFormat && pinnedChannels != null) {
            listOf(pinnedChannels)
        } else {
            buildList {
                add(ROOT_CAPTURE_PRIMARY_CHANNELS)
                ROOT_CAPTURE_CHANNEL_CANDIDATES.forEach { channels ->
                    if (!contains(channels)) add(channels)
                }
            }
        }
        for (rate in rateOrder) {
            for (channels in channelOrder) {
                val captured = captureRootPcmForDevice(
                    device = device,
                    durationMs = durationMs,
                    sampleRate = rate,
                    channels = channels,
                ) ?: continue
                if (captured.sampleRate != rate || captured.channels != channels) {
                    Log.w(
                        TAG,
                        "root tinycap format mismatch device=$device requested=${rate}Hz/${channels}ch actual=${captured.sampleRate}Hz/${captured.channels}ch",
                    )
                } else if (rate != preferredSampleRate || channels != ROOT_CAPTURE_PRIMARY_CHANNELS) {
                    Log.i(TAG, "root tinycap fallback device=$device sampleRate=$rate channels=$channels")
                }
                return captured
            }
        }
        return null
    }

    private fun probeBestRootCaptureSource(sampleRate: Int): RootCaptureCandidate? {
        val probeResults = ROOT_CAPTURE_CANDIDATES.mapNotNull { source ->
            val captured = captureRootPcmForDevice(
                device = source.device,
                durationMs = PROBE_CAPTURE_MS,
                sampleRate = sampleRate,
                channels = ROOT_CAPTURE_PRIMARY_CHANNELS,
            ) ?: return@mapNotNull null
            val rms = rmsPcm16(captured.pcm)
            Log.i(TAG, "audio root probe source=${source.name}(${source.device}) rms=$rms")
            RootProbeResult(source = source, rms = rms)
        }
        val best = probeResults.maxByOrNull { it.rms } ?: return null
        if (STRICT_REMOTE_AUDIO_ONLY && best.rms < MIN_PROBE_ACCEPT_RMS) {
            Log.w(
                TAG,
                "audio root probe rejected (strict remote mode, best=${best.rms})",
            )
            return null
        }
        Log.i(TAG, "audio root probe selected=${best.source.name}(${best.source.device}) rms=${best.rms}")
        return best.source
    }

    private fun captureRootPcmForDevice(
        device: Int,
        durationMs: Int,
        sampleRate: Int,
        channels: Int,
    ): RootCaptureFrame? {
        if (ROOT_CAPTURE_PRECISE_CHUNKS) {
            captureRootPcmForDevicePrecise(
                device = device,
                durationMs = durationMs,
                sampleRate = sampleRate,
                channels = channels,
            )?.let { return it }
        }
        return captureRootPcmForDeviceLegacy(
            device = device,
            durationMs = durationMs,
            sampleRate = sampleRate,
            channels = channels,
        )
    }

    private fun captureRootPcmForDevicePrecise(
        device: Int,
        durationMs: Int,
        sampleRate: Int,
        channels: Int,
    ): RootCaptureFrame? {
        val paddedDurationMs = (durationMs + ROOT_CAPTURE_PRECISE_PADDING_MS)
            .coerceAtMost(ROOT_CAPTURE_PRECISE_MAX_MS)
        val targetFrames = max(1, (sampleRate * paddedDurationMs) / 1000)
        val targetBytes = (targetFrames * channels * 2).coerceAtLeast(MIN_ROOT_RAW_CAPTURE_BYTES)
        val seconds = max(1, (paddedDurationMs + 999) / 1000 + ROOT_CAPTURE_PRECISE_EXTRA_SECONDS)
        val rawFile = File(filesDir, "pb-rootcap-$device-${System.currentTimeMillis()}.pcm")
        val command = buildString {
            append(ROOT_TINYCAP_BIN)
            append(" -- -D 0 -d ")
            append(device)
            append(" -c ")
            append(channels)
            append(" -r ")
            append(sampleRate)
            append(" -b 16 -t ")
            append(seconds)
            append(" | head -c ")
            append(targetBytes)
            append(" > ")
            append(shellQuote(rawFile.absolutePath))
            append(" && chmod 666 ")
            append(shellQuote(rawFile.absolutePath))
        }
        val result = RootShellRuntime.run(
            command = command,
            timeoutMs = (seconds * 2_800L).coerceAtLeast(5_000L),
        )
        if (!result.ok) {
            Log.w(
                TAG,
                "root tinycap precise failed device=$device: ${result.error ?: result.stderr.ifBlank { "unknown" }}",
            )
            return null
        }
        val raw = runCatching { rawFile.readBytes() }.getOrNull()
        runCatching { rawFile.delete() }
        if (raw == null || raw.size < MIN_ROOT_RAW_CAPTURE_BYTES) {
            return null
        }
        val monoPcm = interleavedPcm16ToMono(raw, channels)
        if (monoPcm.size < 2) {
            return null
        }
        val effectiveSampleRate = applyRootCaptureSampleRateCorrection(device, sampleRate)
        if (effectiveSampleRate != sampleRate) {
            Log.w(
                TAG,
                "root tinycap sampleRate corrected device=$device from=$sampleRate to=$effectiveSampleRate",
            )
        }
        if (DEBUG_DUMP_ROOT_RAW_CAPTURE) {
            persistDebugWav(
                prefix = "rxraw",
                wavBytes = pcm16ToWav(monoPcm, effectiveSampleRate),
                hint = "d${device}-req${sampleRate}",
            )
        }
        return RootCaptureFrame(
            pcm = monoPcm,
            sampleRate = effectiveSampleRate,
            channels = 1,
        )
    }

    private fun captureRootPcmForDeviceLegacy(
        device: Int,
        durationMs: Int,
        sampleRate: Int,
        channels: Int,
    ): RootCaptureFrame? {
        val seconds = max(1, (durationMs + 499) / 1000)
        val wavFile = File(filesDir, "pb-rootcap-$device-${System.currentTimeMillis()}.wav")
        val command = buildString {
            append(ROOT_TINYCAP_BIN)
            append(" ")
            append(shellQuote(wavFile.absolutePath))
            append(" -D 0 -d ")
            append(device)
            append(" -c ")
            append(channels)
            append(" -r ")
            append(sampleRate)
            append(" -b 16 -t ")
            append(seconds)
            append(" && chmod 666 ")
            append(shellQuote(wavFile.absolutePath))
        }
        val result = RootShellRuntime.run(
            command = command,
            timeoutMs = (seconds * 2_500L).coerceAtLeast(5_000L),
        )
        if (!result.ok) {
            Log.w(
                TAG,
                "root tinycap failed device=$device: ${result.error ?: result.stderr.ifBlank { "unknown" }}",
            )
            return null
        }
        val wav = runCatching { wavFile.readBytes() }.getOrNull()
        runCatching { wavFile.delete() }
        persistDebugWav(
            prefix = "rxraw",
            wavBytes = if (DEBUG_DUMP_ROOT_RAW_CAPTURE && (wav?.size ?: 0) >= MIN_DEBUG_RAW_WAV_BYTES) wav else null,
            hint = "d${device}-req${sampleRate}",
        )
        val decoded = decodeWavToPcm16Mono(wav) ?: return null
        val effectiveSampleRate = applyRootCaptureSampleRateCorrection(device, decoded.sampleRate)
        if (decoded.sampleRate != sampleRate || decoded.channels != channels || decoded.bitsPerSample != 16) {
            Log.w(
                TAG,
                "root tinycap format normalized device=$device requested=${sampleRate}Hz/${channels}ch/16bit actual=${decoded.sampleRate}Hz/${decoded.channels}ch/${decoded.bitsPerSample}bit",
            )
        }
        if (effectiveSampleRate != decoded.sampleRate) {
            Log.w(
                TAG,
                "root tinycap sampleRate corrected device=$device from=${decoded.sampleRate} to=$effectiveSampleRate",
            )
        }
        return RootCaptureFrame(
            pcm = decoded.pcm,
            sampleRate = effectiveSampleRate,
            channels = decoded.channels,
        )
    }

    private fun interleavedPcm16ToMono(pcmBytes: ByteArray, channels: Int): ByteArray {
        val aligned = if (pcmBytes.size % 2 == 0) pcmBytes else pcmBytes.copyOf(pcmBytes.size - 1)
        if (channels <= 1) {
            return aligned
        }
        val frameBytes = channels * 2
        val frames = aligned.size / frameBytes
        if (frames <= 0) {
            return ByteArray(0)
        }
        val input = ByteBuffer.wrap(aligned).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            var sum = 0
            repeat(channels) {
                sum += input.short.toInt()
            }
            val mixed = (sum.toDouble() / channels.toDouble())
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(mixed.toShort())
        }
        return output.array()
    }

    private fun applyRootCaptureSampleRateCorrection(device: Int, sampleRate: Int): Int {
        if (!ROOT_CAPTURE_RATE_FIX_ENABLED) {
            return sampleRate
        }
        if (!ROOT_CAPTURE_RATE_FIX_DEVICES.contains(device)) {
            return sampleRate
        }
        return if (sampleRate == ROOT_CAPTURE_RATE_FIX_FROM) {
            ROOT_CAPTURE_RATE_FIX_TO
        } else {
            sampleRate
        }
    }

    private fun maybeRecoverRootRoute() {
        val now = System.currentTimeMillis()
        val last = lastRootRouteRecoverAtMs.get()
        if (now - last < ROOT_ROUTE_RECOVER_THROTTLE_MS) {
            return
        }
        if (!lastRootRouteRecoverAtMs.compareAndSet(last, now)) {
            return
        }
        Thread({
            applyRootCallRouteProfile()
            CommandAuditLog.add("root:route_recover:done")
        }, "pb-root-route-recover").start()
    }

    private fun rotateRootCaptureSource() {
        if (!ENABLE_ROOT_PCM_BRIDGE || ROOT_CAPTURE_CANDIDATES.isEmpty()) {
            return
        }
        if (rootCapturePinned) {
            return
        }
        val current = selectedRootCaptureSource
        val currentIndex = ROOT_CAPTURE_CANDIDATES.indexOfFirst { it.device == current?.device }
        val next = if (currentIndex >= 0) {
            ROOT_CAPTURE_CANDIDATES[(currentIndex + 1) % ROOT_CAPTURE_CANDIDATES.size]
        } else {
            ROOT_CAPTURE_CANDIDATES.first()
        }
        selectedRootCaptureSource = next
        CommandAuditLog.add("voice_bridge:root_source_rotate:${next.name}")
    }

    private fun resetRootCapturePin(reason: String) {
        if (!ENABLE_ROOT_PCM_BRIDGE) {
            return
        }
        if (!rootCapturePinned) {
            return
        }
        rootCapturePinned = false
        selectedRootCaptureSampleRate = null
        selectedRootCaptureChannels = null
        CommandAuditLog.add("voice_bridge:root_source_unpinned:$reason")
        Log.w(TAG, "root capture source unpinned: $reason")
    }

    private fun pinRootCaptureSource() {
        if (!ENABLE_ROOT_PCM_BRIDGE || rootCapturePinned) {
            return
        }
        val source = selectedRootCaptureSource ?: return
        rootCapturePinned = true
        CommandAuditLog.add("voice_bridge:root_source_pinned:${source.name}")
        Log.i(TAG, "root capture source pinned: ${source.name}(${source.device})")
    }

    private fun persistDebugWav(prefix: String, wavBytes: ByteArray?, hint: String = ""): String? {
        if (!ENABLE_DEBUG_WAV_DUMP || wavBytes == null || wavBytes.isEmpty()) {
            return null
        }
        val directory = File(filesDir, DEBUG_WAV_DIR_NAME)
        runCatching { directory.mkdirs() }
        val safeHint = hint
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(36)
        val suffix = if (safeHint.isBlank()) "" else "-$safeHint"
        val file = File(directory, "$prefix-${System.currentTimeMillis()}$suffix.wav")
        return runCatching {
            file.writeBytes(wavBytes)
            pruneDebugWavDirectory(directory)
            file.absolutePath
        }.onSuccess { path ->
            CommandAuditLog.add("voice_bridge:debug_wav:$path")
            Log.i(TAG, "saved debug wav: $path")
        }.getOrNull()
    }

    private fun pruneDebugWavDirectory(directory: File) {
        val wavFiles = directory.listFiles { entry -> entry.isFile && entry.extension.equals("wav", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (wavFiles.size <= MAX_DEBUG_WAV_FILES) {
            return
        }
        wavFiles.drop(MAX_DEBUG_WAV_FILES).forEach { file ->
            runCatching { file.delete() }
        }
    }

    private fun playReplyViaRootPcm(
        audioWavBase64: String?,
        replyTextForEcho: String? = null,
    ): RootPlaybackResult {
        if (audioWavBase64.isNullOrBlank()) {
            return RootPlaybackResult(played = false, interrupted = false)
        }
        val wavBytes = runCatching {
            Base64.getDecoder().decode(audioWavBase64.trim())
        }.getOrNull() ?: return RootPlaybackResult(played = false, interrupted = false)
        if (wavBytes.size < 44) {
            return RootPlaybackResult(played = false, interrupted = false)
        }
        val normalizedWav = normalizeReplyWavForCallPlayback(wavBytes)
            ?: return RootPlaybackResult(played = false, interrupted = false)
        val wavFile = File(filesDir, "pb-rootplay-${System.currentTimeMillis()}.wav")
        runCatching { wavFile.writeBytes(normalizedWav) }.getOrElse {
            return RootPlaybackResult(played = false, interrupted = false)
        }
        val playbackDurationMs = estimateWavDurationMs(normalizedWav, ROOT_PLAYBACK_SAMPLE_RATE)
        val playbackTimeoutMs = (playbackDurationMs + ROOT_PLAYBACK_TIMEOUT_MARGIN_MS)
            .coerceIn(ROOT_PLAY_TIMEOUT_MIN_MS, ROOT_PLAY_TIMEOUT_MAX_MS)
        val deviceOrder = buildList {
            selectedRootPlaybackDevice?.let { add(it) }
            ROOT_PLAYBACK_DEVICE_CANDIDATES.forEach { candidate ->
                if (!contains(candidate)) add(candidate)
            }
        }
        try {
            for (device in deviceOrder) {
                if (!InCallStateHolder.hasLiveCall()) {
                    Log.i(TAG, "skipping root tinyplay: call is not active")
                    break
                }
                val pid = startRootTinyplayProcess(
                    wavFile = wavFile,
                    device = device,
                )
                if (pid == null) {
                    continue
                }
                selectedRootPlaybackDevice = device
                CommandAuditLog.add("voice_bridge:root_playback_device:$device")
                val playbackResult = monitorRootPlayback(
                    pid = pid,
                    device = device,
                    timeoutMs = playbackTimeoutMs,
                    replyTextForEcho = replyTextForEcho,
                )
                if (playbackResult.played || playbackResult.interrupted) {
                    return playbackResult
                }
                Log.w(
                    TAG,
                    "root tinyplay failed device=$device timeoutMs=$playbackTimeoutMs",
                )
            }
        } finally {
            runCatching { wavFile.delete() }
        }
        return RootPlaybackResult(played = false, interrupted = false)
    }

    private fun startRootTinyplayProcess(wavFile: File, device: Int): Int? {
        val command = buildString {
            append(ROOT_TINYPLAY_BIN)
            append(" ")
            append(shellQuote(wavFile.absolutePath))
            append(" -D 0 -d ")
            append(device)
            append(" -c 1 -r ")
            append(ROOT_PLAYBACK_SAMPLE_RATE)
            append(" -b 16 >/dev/null 2>&1 & echo $!")
        }
        val result = RootShellRuntime.run(
            command = command,
            timeoutMs = ROOT_PLAY_START_TIMEOUT_MS,
        )
        if (!result.ok) {
            Log.w(
                TAG,
                "root tinyplay start failed device=$device err=${result.error ?: "none"} stderr=${result.stderr.ifBlank { "unknown" }}",
            )
            return null
        }
        val pid = Regex("(\\d+)").find(result.stdout)?.groupValues?.get(1)?.toIntOrNull()
        if (pid == null || pid <= 0) {
            Log.w(TAG, "root tinyplay start returned no pid device=$device stdout=${result.stdout.take(64)}")
            return null
        }
        return pid
    }

    private fun monitorRootPlayback(
        pid: Int,
        device: Int,
        timeoutMs: Long,
        replyTextForEcho: String?,
    ): RootPlaybackResult {
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + timeoutMs
        var nextProbeAt = startedAt + BARGE_IN_ARM_DELAY_MS
        while (System.currentTimeMillis() < deadline) {
            if (!InCallStateHolder.hasLiveCall()) {
                stopRootPlaybackProcess(pid)
                return RootPlaybackResult(played = false, interrupted = false)
            }
            if (!isRootProcessAlive(pid)) {
                Log.i(TAG, "root tinyplay ok device=$device")
                markSpeechActivity("root_playback:$device")
                return RootPlaybackResult(played = true, interrupted = false)
            }
            if (ENABLE_BARGE_IN_INTERRUPT) {
                val now = System.currentTimeMillis()
                if (now >= nextProbeAt) {
                    nextProbeAt = now + BARGE_IN_PROBE_INTERVAL_MS
                    if (detectBargeInSpeech(replyTextForEcho)) {
                        stopRootPlaybackProcess(pid)
                        CommandAuditLog.add("voice_bridge:barge_in")
                        Log.i(TAG, "barge-in interrupt triggered for root playback")
                        return RootPlaybackResult(played = false, interrupted = true)
                    }
                }
            }
            try {
                Thread.sleep(BARGE_IN_PLAYBACK_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return RootPlaybackResult(played = false, interrupted = false)
            }
        }
        stopRootPlaybackProcess(pid)
        return RootPlaybackResult(played = false, interrupted = false)
    }

    private fun isRootProcessAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        return RootShellRuntime.run("kill -0 $pid >/dev/null 2>&1", timeoutMs = 900L).ok
    }

    private fun stopRootPlaybackProcess(pid: Int) {
        if (pid <= 0) return
        RootShellRuntime.run(
            command = "kill -TERM $pid >/dev/null 2>&1 || true; sleep 0.05; kill -KILL $pid >/dev/null 2>&1 || true",
            timeoutMs = 1_500L,
        )
    }

    private fun detectBargeInSpeech(replyTextForEcho: String?): Boolean {
        val source = selectedRootCaptureSource
            ?: return false
        val probe = captureRootPcmAdaptive(
            device = source.device,
            durationMs = BARGE_IN_PROBE_CAPTURE_MS,
            preferredSampleRate = ROOT_CAPTURE_REQUEST_SAMPLE_RATE,
        ) ?: return false
        val analysis = analyzeCapture(probe.pcm, probe.sampleRate)
        if (analysis.overallRms < BARGE_IN_MIN_RMS || analysis.voicedMs < BARGE_IN_MIN_VOICED_MS) {
            return false
        }
        if (analysis.overallRms >= BARGE_IN_STRONG_RMS && analysis.voicedMs >= BARGE_IN_STRONG_VOICED_MS) {
            CommandAuditLog.add(
                "voice_bridge:barge_in_energy:rms=${"%.1f".format(analysis.overallRms)}:voiced=${analysis.voicedMs}",
            )
            return true
        }
        val probeWav = pcm16ToWav(probe.pcm, probe.sampleRate)
        val transcript = runCatching { callSparkAsr(probeWav) }
            .getOrNull()
            ?.trim()
            .orEmpty()
        if (transcript.isBlank()) {
            return false
        }
        val alphaNumChars = transcript.count { it.isLetterOrDigit() }
        if (alphaNumChars < BARGE_IN_MIN_ALNUM_CHARS) {
            return false
        }
        if (!replyTextForEcho.isNullOrBlank()) {
            val overlap = tokenOverlapRatio(transcript, replyTextForEcho)
            if (overlap >= BARGE_IN_ECHO_OVERLAP_THRESHOLD) {
                CommandAuditLog.add("voice_bridge:barge_probe_echo:${"%.2f".format(overlap)}")
                return false
            }
        }
        CommandAuditLog.add("voice_bridge:barge_in_asr:${transcript.take(64)}")
        return true
    }

    private fun tokenOverlapRatio(leftText: String, rightText: String): Double {
        val left = leftText
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .filter { it.length >= 2 }
            .toSet()
        val right = rightText
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .filter { it.length >= 2 }
            .toSet()
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0
        }
        return left.intersect(right).size.toDouble() / left.union(right).size.toDouble()
    }

    private fun normalizeReplyWavForCallPlayback(wavBytes: ByteArray): ByteArray? {
        val decoded = decodeWavToPcm16Mono(wavBytes) ?: return null
        if (decoded.sampleRate == ROOT_PLAYBACK_SAMPLE_RATE) {
            return pcm16ToWav(decoded.pcm, ROOT_PLAYBACK_SAMPLE_RATE)
        }
        val resampled = resamplePcm16Mono(
            pcm = decoded.pcm,
            fromSampleRate = decoded.sampleRate,
            toSampleRate = ROOT_PLAYBACK_SAMPLE_RATE,
        )
        return pcm16ToWav(resampled, ROOT_PLAYBACK_SAMPLE_RATE)
    }

    private fun parseWavSampleRate(wavBytes: ByteArray): Int? {
        return decodeWavToPcm16Mono(wavBytes)?.sampleRate
    }

    private fun resamplePcm16Mono(
        pcm: ByteArray,
        fromSampleRate: Int,
        toSampleRate: Int,
    ): ByteArray {
        if (fromSampleRate <= 0 || toSampleRate <= 0 || pcm.size < 2 || fromSampleRate == toSampleRate) {
            return pcm
        }
        val inputSamples = pcm.size / 2
        if (inputSamples <= 1) {
            return pcm
        }
        val input = ShortArray(inputSamples)
        val inputBuffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        repeat(inputSamples) { index ->
            input[index] = inputBuffer.short
        }
        val outputSamples = max(1, ((inputSamples.toLong() * toSampleRate) / fromSampleRate).toInt())
        val outBuffer = ByteBuffer.allocate(outputSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until outputSamples) {
            val sourcePosition = index.toDouble() * fromSampleRate.toDouble() / toSampleRate.toDouble()
            val leftIndex = sourcePosition.toInt().coerceIn(0, inputSamples - 1)
            val rightIndex = (leftIndex + 1).coerceAtMost(inputSamples - 1)
            val fraction = (sourcePosition - leftIndex).coerceIn(0.0, 1.0)
            val left = input[leftIndex].toDouble()
            val right = input[rightIndex].toDouble()
            val mixed = (left + (right - left) * fraction)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outBuffer.putShort(mixed.toShort())
        }
        return outBuffer.array()
    }

    private fun estimateWavDurationMs(wavBytes: ByteArray, defaultSampleRate: Int): Long {
        val decoded = decodeWavToPcm16Mono(wavBytes) ?: return 1_000L
        val sampleRate = decoded.sampleRate.takeIf { it > 0 } ?: defaultSampleRate
        if (sampleRate <= 0) return 1_000L
        val samples = decoded.pcm.size / 2
        return ((samples.toDouble() * 1_000.0) / sampleRate.toDouble()).toLong().coerceAtLeast(300L)
    }

    private fun wavToPcm16Mono(wavBytes: ByteArray?): ByteArray? {
        return decodeWavToPcm16Mono(wavBytes)?.pcm
    }

    private fun decodeWavToPcm16Mono(wavBytes: ByteArray?): DecodedWav? {
        if (wavBytes == null || wavBytes.size < 44) {
            return null
        }
        val riff = String(wavBytes, 0, 4, Charsets.US_ASCII)
        val wave = String(wavBytes, 8, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || wave != "WAVE") {
            return null
        }
        var audioFormat = 1
        var channels = 1
        var sampleRate = 0
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0
        var offset = 12
        while (offset + 8 <= wavBytes.size) {
            val chunkId = String(wavBytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(wavBytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (chunkSize < 0) {
                return null
            }
            val chunkOffset = offset + 8
            if (chunkOffset + chunkSize > wavBytes.size) {
                return null
            }
            if (chunkId == "fmt " && chunkSize >= 16) {
                val fmt = ByteBuffer.wrap(wavBytes, chunkOffset, 16).order(ByteOrder.LITTLE_ENDIAN)
                audioFormat = fmt.short.toInt() and 0xffff
                channels = max(1, fmt.short.toInt() and 0xffff)
                sampleRate = fmt.int
                fmt.int
                fmt.short
                bitsPerSample = fmt.short.toInt() and 0xffff
            } else if (chunkId == "data") {
                dataOffset = chunkOffset
                dataSize = chunkSize
                break
            }
            offset = chunkOffset + chunkSize
            if (offset and 1 == 1) {
                offset += 1
            }
        }
        if (dataOffset < 0 || dataSize <= 0 || dataOffset + dataSize > wavBytes.size) {
            return null
        }
        if (sampleRate <= 0 || channels <= 0) {
            return null
        }
        val bytesPerSample = when (bitsPerSample) {
            8 -> 1
            16 -> 2
            24 -> 3
            32 -> 4
            else -> return null
        }
        val frameSize = bytesPerSample * channels
        if (frameSize <= 0) {
            return null
        }
        val frameCount = dataSize / frameSize
        if (frameCount <= 0) {
            return null
        }
        val out = ByteBuffer.allocate(frameCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frameCount) { frameIndex ->
            val frameBase = dataOffset + frameIndex * frameSize
            var mixed = 0.0
            repeat(channels) { channelIndex ->
                val sampleOffset = frameBase + channelIndex * bytesPerSample
                val sample = when (bitsPerSample) {
                    8 -> {
                        val unsigned = wavBytes[sampleOffset].toInt() and 0xff
                        (unsigned - 128) shl 8
                    }
                    16 -> ByteBuffer.wrap(wavBytes, sampleOffset, 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .short
                        .toInt()
                    24 -> {
                        var value = (wavBytes[sampleOffset].toInt() and 0xff) or
                            ((wavBytes[sampleOffset + 1].toInt() and 0xff) shl 8) or
                            ((wavBytes[sampleOffset + 2].toInt() and 0xff) shl 16)
                        if (value and 0x800000 != 0) {
                            value = value or (-1 shl 24)
                        }
                        value shr 8
                    }
                    32 -> {
                        if (audioFormat == 3) {
                            val floatSample = ByteBuffer.wrap(wavBytes, sampleOffset, 4)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .float
                            (floatSample * 32_767.0f)
                                .toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        } else {
                            ByteBuffer.wrap(wavBytes, sampleOffset, 4)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .int shr 16
                        }
                    }
                    else -> 0
                }
                mixed += sample.toDouble()
            }
            val mono = (mixed / channels.toDouble())
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out.putShort(mono.toShort())
        }
        return DecodedWav(
            pcm = out.array(),
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
        )
    }

    private fun probeBestAudioSource(sampleRate: Int): AudioSourceCandidate? {
        val probeResults = AUDIO_SOURCE_CANDIDATES.mapNotNull { source ->
            val pcm = capturePcmForSource(
                source = source,
                durationMs = PROBE_CAPTURE_MS,
                sampleRate = sampleRate,
            ) ?: return@mapNotNull null
            val rms = rmsPcm16(pcm)
            Log.i(TAG, "audio probe source=${source.name}(${source.id}) rms=$rms")
            SourceProbeResult(source = source, rms = rms)
        }
        val best = probeResults.maxByOrNull { it.rms } ?: return null
        if (STRICT_REMOTE_AUDIO_ONLY && best.rms < MIN_PROBE_ACCEPT_RMS) {
            Log.w(
                TAG,
                "audio probe rejected (strict remote mode, best=${best.rms})",
            )
            return null
        }
        Log.i(TAG, "audio probe selected=${best.source.name}(${best.source.id}) rms=${best.rms}")
        return best.source
    }

    private fun capturePcmForSource(
        source: AudioSourceCandidate,
        durationMs: Int,
        sampleRate: Int,
    ): ByteArray? {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuffer <= 0) {
            return null
        }
        val record = runCatching {
            AudioRecord(
                source.id,
                sampleRate,
                channelConfig,
                audioFormat,
                minBuffer * 2,
            )
        }.onFailure {
            Log.w(TAG, "audio source init failed: ${source.name}(${source.id})")
        }.getOrNull() ?: return null
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "audio source unavailable: ${source.name}(${source.id})")
            record.release()
            return null
        }
        val sessionId = record.audioSessionId
        val aec = if (ENABLE_AUDIO_EFFECTS && AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull()
        } else {
            null
        }
        val ns = if (ENABLE_AUDIO_EFFECTS && NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull()
        } else {
            null
        }
        val agc = if (ENABLE_AUDIO_EFFECTS && AutomaticGainControl.isAvailable()) {
            runCatching { AutomaticGainControl.create(sessionId)?.apply { enabled = true } }.getOrNull()
        } else {
            null
        }
        if (ENABLE_AUDIO_EFFECTS) {
            CommandAuditLog.add(
                "voice_bridge:effects:aec=${aec != null}:ns=${ns != null}:agc=${agc != null}",
            )
        }
        val pcmOut = ByteArray((sampleRate * durationMs / 1000) * 2)
        var offset = 0
        val scratch = ByteArray(minBuffer)
        runCatching { record.startRecording() }
            .onFailure {
                Log.w(TAG, "audio source start failed: ${source.name}(${source.id})")
                record.release()
                return null
            }
        try {
            while (offset < pcmOut.size && serviceActive.get()) {
                val read = record.read(scratch, 0, scratch.size)
                if (read <= 0) {
                    break
                }
                val writable = minOf(read, pcmOut.size - offset)
                System.arraycopy(scratch, 0, pcmOut, offset, writable)
                offset += writable
            }
        } finally {
            runCatching { record.stop() }
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            runCatching { agc?.release() }
            record.release()
        }
        if (offset < sampleRate / 3) {
            return null
        }
        return pcmOut.copyOf(offset)
    }

    private fun rmsPcm16(bytes: ByteArray): Double {
        if (bytes.size < 2) return 0.0
        val samples = bytes.size / 2
        var sum = 0.0
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) {
            val value = buffer.short.toDouble()
            sum += value * value
        }
        return sqrt(sum / samples)
    }

    private fun analyzeCapture(bytes: ByteArray, sampleRate: Int): CaptureAnalysis {
        val frameSamples = max((sampleRate * FRAME_MS) / 1000, 80)
        val sampleCount = bytes.size / 2
        if (sampleCount < frameSamples) {
            return CaptureAnalysis(
                overallRms = rmsPcm16(bytes),
                voicedMs = 0,
                voicedRatio = 0.0,
                dynamicRange = 0.0,
                clippingRatio = 0.0,
                confidence = 0.0,
                fingerprint = SpeakerFingerprint(0.0, 0.0, 0.0, 0.0),
            )
        }
        val sampleBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(sampleCount)
        repeat(sampleCount) { index -> samples[index] = sampleBuffer.short }

        val rmsFrames = mutableListOf<Double>()
        val zcrFrames = mutableListOf<Double>()
        val tiltFrames = mutableListOf<Double>()
        var clippedSamples = 0
        var offset = 0
        while (offset + frameSamples <= sampleCount) {
            var sum = 0.0
            var diffSum = 0.0
            var crossings = 0
            var previous = samples[offset].toInt()
            for (index in 0 until frameSamples) {
                val current = samples[offset + index].toInt()
                val currentDouble = current.toDouble()
                sum += currentDouble * currentDouble
                val diff = (current - previous).toDouble()
                diffSum += diff * diff
                if ((current >= 0 && previous < 0) || (current < 0 && previous >= 0)) {
                    crossings += 1
                }
                if (abs(current) >= 32760) {
                    clippedSamples += 1
                }
                previous = current
            }
            val rms = sqrt(sum / frameSamples)
            val zcr = crossings.toDouble() / frameSamples.toDouble()
            val tilt = if (sum <= 1.0) 0.0 else (diffSum / sum).coerceIn(0.0, 3.0)
            rmsFrames += rms
            zcrFrames += zcr
            tiltFrames += tilt
            offset += frameSamples
        }

        val noiseFloor = percentile(rmsFrames, 0.20)
        val voiceThreshold = max(MIN_VAD_RMS, noiseFloor * VAD_NOISE_MULTIPLIER)
        val voicedIndices = rmsFrames.indices.filter { rmsFrames[it] >= voiceThreshold }
        val voicedMs = voicedIndices.size * FRAME_MS
        val voicedRatio = if (rmsFrames.isEmpty()) 0.0 else voicedIndices.size.toDouble() / rmsFrames.size.toDouble()
        val dynamicRange = percentile(rmsFrames, 0.90) - percentile(rmsFrames, 0.20)
        val clippingRatio = if (sampleCount == 0) 0.0 else clippedSamples.toDouble() / sampleCount.toDouble()

        val voicedRms = voicedIndices.map { rmsFrames[it] }
        val voicedRmsMean = voicedRms.averageOrZero()
        val voicedRmsStd = stdDev(voicedRms, voicedRmsMean)
        val voicedZcrMean = voicedIndices.map { zcrFrames[it] }.averageOrZero()
        val voicedTiltMean = voicedIndices.map { tiltFrames[it] }.averageOrZero()

        val energyScore = ((rmsPcm16(bytes) - MIN_CAPTURE_RMS) / 100.0).coerceIn(0.0, 1.0)
        val voicedScore = ((voicedMs.toDouble() - MIN_VOICED_MS.toDouble()) / 1200.0).coerceIn(0.0, 1.0)
        val rangeScore = (dynamicRange / 18.0).coerceIn(0.0, 1.0)
        val clipPenalty = (1.0 - (clippingRatio / MAX_CLIPPING_RATIO).coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
        val confidence = (0.35 * energyScore + 0.30 * voicedScore + 0.20 * rangeScore + 0.15 * clipPenalty).coerceIn(0.0, 1.0)

        return CaptureAnalysis(
            overallRms = rmsPcm16(bytes),
            voicedMs = voicedMs,
            voicedRatio = voicedRatio,
            dynamicRange = dynamicRange,
            clippingRatio = clippingRatio,
            confidence = confidence,
            fingerprint = SpeakerFingerprint(
                voicedRms = (voicedRmsMean / 32768.0).coerceIn(0.0, 1.0),
                voicedRmsStd = (voicedRmsStd / 32768.0).coerceIn(0.0, 1.0),
                zcr = voicedZcrMean.coerceIn(0.0, 1.0),
                tilt = voicedTiltMean.coerceIn(0.0, 3.0),
            ),
        )
    }

    private fun speakerSimilarity(
        baseline: SpeakerFingerprint,
        sample: SpeakerFingerprint,
    ): Double {
        val rmsDiff = (abs(baseline.voicedRms - sample.voicedRms) / 0.15).coerceIn(0.0, 1.0)
        val stdDiff = (abs(baseline.voicedRmsStd - sample.voicedRmsStd) / 0.10).coerceIn(0.0, 1.0)
        val zcrDiff = (abs(baseline.zcr - sample.zcr) / 0.20).coerceIn(0.0, 1.0)
        val tiltDiff = (abs(baseline.tilt - sample.tilt) / 1.10).coerceIn(0.0, 1.0)
        val distance = 0.35 * rmsDiff + 0.20 * stdDiff + 0.25 * zcrDiff + 0.20 * tiltDiff
        return (1.0 - distance).coerceIn(0.0, 1.0)
    }

    private fun stdDev(values: List<Double>, mean: Double): Double {
        if (values.isEmpty()) return 0.0
        var sum = 0.0
        values.forEach { value ->
            val delta = value - mean
            sum += delta * delta
        }
        return sqrt(sum / values.size)
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val position = ((sorted.size - 1) * p.coerceIn(0.0, 1.0)).toInt()
        return sorted[position]
    }

    private fun List<Double>.averageOrZero(): Double {
        if (isEmpty()) return 0.0
        return average()
    }

    private fun transcriptRejectReason(transcript: String): String? {
        val normalized = transcript
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) {
            return "empty_normalized"
        }
        val alphaNumCount = normalized.count { it.isLetterOrDigit() }
        if (alphaNumCount < MIN_TRANSCRIPT_ALNUM_CHARS) {
            return "short_alnum"
        }
        val tokens = normalized.split(" ").filter { it.length >= 2 }
        if (tokens.isEmpty()) {
            return "no_tokens"
        }
        if (tokens.size <= LOW_INFORMATION_MAX_TOKENS) {
            val informativeTokens = tokens.filterNot { LOW_INFORMATION_TOKENS.contains(it) }
            if (informativeTokens.isEmpty()) {
                return "low_information"
            }
        }
        val uniqueRatio = tokens.toSet().size.toDouble() / tokens.size.toDouble()
        if (tokens.size >= MIN_TRANSCRIPT_TOKEN_COUNT && uniqueRatio < MIN_TRANSCRIPT_UNIQUE_RATIO) {
            return "low_unique_ratio"
        }
        val condensed = tokens.joinToString(" ")
        if (LOW_QUALITY_TRANSCRIPT_PATTERNS.any { pattern -> pattern.matches(condensed) }) {
            return "pattern_match"
        }
        val lettersOnly = normalized.filter { it in 'a'..'z' }
        if (lettersOnly.length >= 8) {
            val maxCount = lettersOnly
                .groupingBy { it }
                .eachCount()
                .values
                .maxOrNull() ?: 0
            val dominantRatio = maxCount.toDouble() / lettersOnly.length.toDouble()
            if (dominantRatio >= 0.78) {
                return "dominant_char"
            }
        }
        if (lastAssistantReplyText.isNotBlank()) {
            val assistantTokens = lastAssistantReplyText
                .lowercase(Locale.US)
                .replace(Regex("[^a-z0-9\\s']"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .split(" ")
                .filter { it.length >= 2 }
                .toSet()
            val transcriptTokens = tokens.toSet()
            if (assistantTokens.isNotEmpty() && transcriptTokens.isNotEmpty()) {
                val overlap = assistantTokens.intersect(transcriptTokens).size.toDouble() /
                    assistantTokens.union(transcriptTokens).size.toDouble()
                if (overlap >= TRANSCRIPT_ECHO_OVERLAP_THRESHOLD) {
                    return "assistant_echo_overlap"
                }
            }
        }
        return null
    }

    private fun sanitizeReply(input: String): String {
        return input
            .replace(Regex("[*`_~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun shellQuote(input: String): String = "'${input.replace("'", "'\"'\"'")}'"

    private data class SparkTurnResponse(
        val sessionId: String?,
        val transcript: String?,
        val reply: String?,
        val audioWavBase64: String?,
        val livePlaybackHandled: Boolean = false,
        val livePlaybackInterrupted: Boolean = false,
    )

    private data class RootPlaybackResult(
        val played: Boolean,
        val interrupted: Boolean,
    )

    private data class StreamedWavHeader(
        val dataOffset: Int,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
    )

    private data class RootStreamPlaybackSession(
        val fifoFile: File,
        val outputStream: FileOutputStream,
        val device: Int,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val pid: Int,
    )

    private data class AssembledUtterance(
        val transcript: String,
        val audioWav: ByteArray,
        val chunkCount: Int,
    )

    private data class CaptureResult(
        val wav: ByteArray? = null,
        val analysis: CaptureAnalysis? = null,
        val rejectionReason: String? = null,
    )

    private data class CaptureAnalysis(
        val overallRms: Double,
        val voicedMs: Int,
        val voicedRatio: Double,
        val dynamicRange: Double,
        val clippingRatio: Double,
        val confidence: Double,
        val fingerprint: SpeakerFingerprint,
    )

    private data class SpeakerFingerprint(
        val voicedRms: Double,
        val voicedRmsStd: Double,
        val zcr: Double,
        val tilt: Double,
    )

    private fun callSparkAsr(audioWav: ByteArray?): String {
        val boundary = "----PhoneBridgeAsr${System.currentTimeMillis()}"
        val url = URL("${SparkConfig.baseUrl}/api/asr")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 20_000
            readTimeout = 45_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (SparkConfig.bearer.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${SparkConfig.bearer}")
            }
        }
        DataOutputStream(connection.outputStream).use { out ->
            writeFileField(out, boundary, "audio", "turn.wav", "audio/wav", audioWav ?: buildSilenceWav())
            out.writeBytes("--$boundary--\r\n")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("spark ASR failed ($code): $body")
        }
        val json = JSONObject(body)
        return json.optString("transcript", "")
    }

    private fun callSparkTurn(transcript: String?, audioWav: ByteArray?, skipAsr: Boolean = false): SparkTurnResponse {
        if (ENABLE_SPARK_TURN_STREAM) {
            val streamResult = runCatching {
                callSparkTurnStream(
                    transcript = transcript,
                    audioWav = audioWav,
                    skipAsr = skipAsr,
                )
            }.onFailure { error ->
                Log.w(TAG, "spark stream turn failed, falling back to json endpoint", error)
                CommandAuditLog.add("voice_bridge:stream_fallback:${error.message?.take(96)}")
            }.getOrNull()
            if (streamResult != null) {
                Log.i(TAG, "spark stream turn ok")
                CommandAuditLog.add("voice_bridge:stream_ok")
                return streamResult
            }
        }
        return callSparkTurnJson(
            transcript = transcript,
            audioWav = audioWav,
            skipAsr = skipAsr,
        )
    }

    private fun callSparkTurnJson(transcript: String?, audioWav: ByteArray?, skipAsr: Boolean = false): SparkTurnResponse {
        val boundary = "----PhoneBridge${System.currentTimeMillis()}"
        val url = URL("${SparkConfig.baseUrl}/api/turn")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 20_000
            readTimeout = 45_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (SparkConfig.bearer.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${SparkConfig.bearer}")
            }
        }
        DataOutputStream(connection.outputStream).use { out ->
            writeFormField(out, boundary, "reset_session", "false")
            sessionId?.let { writeFormField(out, boundary, "session_id", it) }
            if (!transcript.isNullOrBlank()) {
                writeFormField(out, boundary, "transcript_hint", transcript)
            }
            if (skipAsr) {
                writeFormField(out, boundary, "skip_asr", "true")
            }
            writeFileField(out, boundary, "audio", "turn.wav", "audio/wav", audioWav ?: buildSilenceWav())
            out.writeBytes("--$boundary--\r\n")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("spark turn failed ($code): $body")
        }
        val json = JSONObject(body)
        sessionId = json.optString("session_id", sessionId.orEmpty()).ifBlank { sessionId }
        return SparkTurnResponse(
            sessionId = sessionId,
            transcript = json.optString("transcript", ""),
            reply = json.optString("reply", ""),
            audioWavBase64 = json.optString("audio_wav_base64").ifBlank {
                json.optString("audio_base64").ifBlank {
                    json.optString("audioBase64").ifBlank { null }
                }
            },
        )
    }

    private fun callSparkTurnStream(transcript: String?, audioWav: ByteArray?, skipAsr: Boolean = false): SparkTurnResponse {
        val boundary = "----PhoneBridgeStream${System.currentTimeMillis()}"
        val url = URL("${SparkConfig.baseUrl}/api/turn/stream")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 20_000
            readTimeout = SPARK_TURN_STREAM_READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/x-ndjson")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (SparkConfig.bearer.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${SparkConfig.bearer}")
            }
        }
        DataOutputStream(connection.outputStream).use { out ->
            writeFormField(out, boundary, "reset_session", "false")
            sessionId?.let { writeFormField(out, boundary, "session_id", it) }
            if (!transcript.isNullOrBlank()) {
                writeFormField(out, boundary, "transcript_hint", transcript)
            }
            if (skipAsr) {
                writeFormField(out, boundary, "skip_asr", "true")
            }
            writeFileField(out, boundary, "audio", "turn.wav", "audio/wav", audioWav ?: buildSilenceWav())
            out.writeBytes("--$boundary--\r\n")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IllegalStateException("spark stream turn failed ($code): $body")
        }

        var parsedSessionId = sessionId
        var parsedTranscript = ""
        var parsedReply = ""
        val audioChunks = linkedMapOf<Int, ByteArray>()
        val livePlaybackEnabled = ENABLE_ROOT_PCM_BRIDGE && ENABLE_SPARK_STREAM_LIVE_PLAYBACK
        var liveSession: RootStreamPlaybackSession? = null
        var livePlaybackFailed = false
        var livePlaybackInterrupted = false
        var pcmBytesWritten = 0L
        var lastBargeInProbeAt = 0L
        val wavHeaderBuffer = ByteArrayOutputStream()
        var streamedHeader: StreamedWavHeader? = null
        connection.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isBlank()) {
                    return@forEach
                }
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                when (json.optString("event")) {
                    "session" -> {
                        parsedSessionId = json.optString("session_id", parsedSessionId.orEmpty()).ifBlank { parsedSessionId }
                    }
                    "asr" -> {
                        val value = json.optString("transcript", "")
                        if (value.isNotBlank()) {
                            parsedTranscript = value
                        }
                    }
                    "reply" -> {
                        val value = json.optString("reply", "")
                        if (value.isNotBlank()) {
                            parsedReply = value
                        }
                    }
                    "audio" -> {
                        val chunk = json.optString("chunk_base64", "")
                        if (chunk.isNotBlank()) {
                            val index = if (json.has("index")) json.optInt("index", audioChunks.size) else audioChunks.size
                            val chunkBytes = runCatching {
                                Base64.getDecoder().decode(chunk.trim())
                            }.getOrElse { error ->
                                throw IllegalStateException("invalid stream audio chunk at index=$index: ${error.message}")
                            }
                            audioChunks[index] = chunkBytes

                            if (livePlaybackEnabled && !livePlaybackInterrupted && !livePlaybackFailed) {
                                val pcmChunk = if (streamedHeader != null) {
                                    chunkBytes
                                } else {
                                    wavHeaderBuffer.write(chunkBytes)
                                    val buffered = wavHeaderBuffer.toByteArray()
                                    val parsedHeader = parseStreamedWavHeader(buffered)
                                    if (parsedHeader == null) {
                                        ByteArray(0)
                                    } else {
                                        streamedHeader = parsedHeader
                                        val initialPcm = if (buffered.size > parsedHeader.dataOffset) {
                                            buffered.copyOfRange(parsedHeader.dataOffset, buffered.size)
                                        } else {
                                            ByteArray(0)
                                        }
                                        wavHeaderBuffer.reset()
                                        initialPcm
                                    }
                                }

                                if (liveSession == null && streamedHeader != null) {
                                    liveSession = startRootTinyplayStreamSession(streamedHeader!!)
                                    if (liveSession == null) {
                                        livePlaybackFailed = true
                                        CommandAuditLog.add("voice_bridge:stream_live_start_failed")
                                    } else {
                                        CommandAuditLog.add("voice_bridge:stream_live_started")
                                    }
                                }

                                if (pcmChunk.isNotEmpty() && liveSession != null) {
                                    runCatching {
                                        liveSession!!.outputStream.write(pcmChunk)
                                        liveSession!!.outputStream.flush()
                                        pcmBytesWritten += pcmChunk.size.toLong()
                                    }.onFailure { error ->
                                        livePlaybackFailed = true
                                        CommandAuditLog.add("voice_bridge:stream_live_write_error:${error.message?.take(64)}")
                                        Log.w(TAG, "stream live write failed", error)
                                    }
                                }

                                if (liveSession != null && !livePlaybackFailed && ENABLE_BARGE_IN_INTERRUPT) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastBargeInProbeAt >= BARGE_IN_PROBE_INTERVAL_MS) {
                                        lastBargeInProbeAt = now
                                        val echoText = parsedReply.ifBlank { null }
                                        if (detectBargeInSpeech(echoText)) {
                                            livePlaybackInterrupted = true
                                            stopRootPlaybackProcess(liveSession!!.pid)
                                            CommandAuditLog.add("voice_bridge:stream_live_barge_in")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "done" -> {
                        parsedSessionId = json.optString("session_id", parsedSessionId.orEmpty()).ifBlank { parsedSessionId }
                    }
                    "error" -> {
                        val detail = json.optString("detail").ifBlank { "unknown stream error" }
                        throw IllegalStateException("spark stream error: $detail")
                    }
                }
            }
        }

        val livePlaybackResult = if (liveSession != null) {
            runCatching { liveSession!!.outputStream.close() }
            if (livePlaybackInterrupted) {
                RootPlaybackResult(played = false, interrupted = true)
            } else if (!livePlaybackFailed) {
                val bytesPerFrame = (liveSession!!.channels * (liveSession!!.bitsPerSample / 8)).coerceAtLeast(2)
                val frames = (pcmBytesWritten / bytesPerFrame).coerceAtLeast(0L)
                val durationMs = if (liveSession!!.sampleRate > 0) {
                    ((frames.toDouble() * 1_000.0) / liveSession!!.sampleRate.toDouble()).toLong()
                } else {
                    0L
                }
                val timeoutMs = (durationMs + ROOT_PLAYBACK_TIMEOUT_MARGIN_MS)
                    .coerceIn(ROOT_PLAY_TIMEOUT_MIN_MS, ROOT_PLAY_TIMEOUT_MAX_MS)
                monitorRootPlayback(
                    pid = liveSession!!.pid,
                    device = liveSession!!.device,
                    timeoutMs = timeoutMs,
                    replyTextForEcho = parsedReply.ifBlank { null },
                )
            } else {
                stopRootPlaybackProcess(liveSession!!.pid)
                RootPlaybackResult(played = false, interrupted = false)
            }
        } else {
            RootPlaybackResult(played = false, interrupted = false)
        }
        liveSession?.let { session ->
            runCatching { session.fifoFile.delete() }
        }

        sessionId = parsedSessionId?.ifBlank { sessionId }
        val mergedAudioBase64 = if (audioChunks.isEmpty()) {
            null
        } else {
            val merged = ByteArrayOutputStream()
            audioChunks.toSortedMap().values.forEach { merged.write(it) }
            Base64.getEncoder().encodeToString(merged.toByteArray())
        }
        val shouldSuppressReplay = livePlaybackResult.played || livePlaybackResult.interrupted
        return SparkTurnResponse(
            sessionId = sessionId,
            transcript = parsedTranscript,
            reply = parsedReply,
            audioWavBase64 = if (shouldSuppressReplay) null else mergedAudioBase64,
            livePlaybackHandled = shouldSuppressReplay,
            livePlaybackInterrupted = livePlaybackResult.interrupted,
        )
    }

    private fun parseStreamedWavHeader(bytes: ByteArray): StreamedWavHeader? {
        if (bytes.size < 12) return null
        if (!(bytes[0].toInt().toChar() == 'R' && bytes[1].toInt().toChar() == 'I' && bytes[2].toInt().toChar() == 'F' && bytes[3].toInt().toChar() == 'F')) {
            return null
        }
        if (!(bytes[8].toInt().toChar() == 'W' && bytes[9].toInt().toChar() == 'A' && bytes[10].toInt().toChar() == 'V' && bytes[11].toInt().toChar() == 'E')) {
            return null
        }
        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var fmtFound = false
        while (offset + 8 <= bytes.size) {
            val id0 = bytes[offset].toInt().toChar()
            val id1 = bytes[offset + 1].toInt().toChar()
            val id2 = bytes[offset + 2].toInt().toChar()
            val id3 = bytes[offset + 3].toInt().toChar()
            val chunkId = "$id0$id1$id2$id3"
            val chunkSize = readIntLE(bytes, offset + 4)
            if (chunkSize < 0) return null
            val payloadOffset = offset + 8
            val nextOffset = payloadOffset + chunkSize + (chunkSize and 1)
            if (nextOffset > bytes.size && chunkId != "data") {
                return null
            }
            if (chunkId == "fmt ") {
                if (bytes.size < payloadOffset + 16) {
                    return null
                }
                val audioFormat = readShortLE(bytes, payloadOffset)
                channels = readShortLE(bytes, payloadOffset + 2)
                sampleRate = readIntLE(bytes, payloadOffset + 4)
                bitsPerSample = readShortLE(bytes, payloadOffset + 14)
                if (audioFormat != 1) {
                    return null
                }
                fmtFound = true
            } else if (chunkId == "data") {
                if (!fmtFound) {
                    return null
                }
                return StreamedWavHeader(
                    dataOffset = payloadOffset,
                    sampleRate = sampleRate,
                    channels = channels.coerceAtLeast(1),
                    bitsPerSample = bitsPerSample.coerceAtLeast(16),
                )
            }
            offset = nextOffset
        }
        return null
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 >= bytes.size) return -1
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun readShortLE(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return -1
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun startRootTinyplayStreamSession(header: StreamedWavHeader): RootStreamPlaybackSession? {
        if (header.sampleRate <= 0 || header.channels <= 0 || header.bitsPerSample <= 0) {
            return null
        }
        val deviceOrder = buildList {
            selectedRootPlaybackDevice?.let { add(it) }
            ROOT_PLAYBACK_DEVICE_CANDIDATES.forEach { candidate ->
                if (!contains(candidate)) add(candidate)
            }
        }
        for (device in deviceOrder) {
            val fifoFile = File(filesDir, "pb-rootstream-${System.currentTimeMillis()}-$device.raw")
            val prep = RootShellRuntime.run(
                command = "rm -f ${shellQuote(fifoFile.absolutePath)} && mkfifo ${shellQuote(fifoFile.absolutePath)} && chmod 666 ${shellQuote(fifoFile.absolutePath)}",
                timeoutMs = 2_500L,
            )
            if (!prep.ok) {
                continue
            }
            val pid = startRootTinyplayRawProcess(
                fifoFile = fifoFile,
                device = device,
                sampleRate = header.sampleRate,
                channels = header.channels,
                bitsPerSample = header.bitsPerSample,
            )
            if (pid == null) {
                runCatching { fifoFile.delete() }
                continue
            }
            val output = runCatching { FileOutputStream(fifoFile) }.getOrNull()
            if (output == null) {
                stopRootPlaybackProcess(pid)
                runCatching { fifoFile.delete() }
                continue
            }

            selectedRootPlaybackDevice = device
            CommandAuditLog.add("voice_bridge:root_stream_device:$device")
            return RootStreamPlaybackSession(
                fifoFile = fifoFile,
                outputStream = output,
                device = device,
                sampleRate = header.sampleRate,
                channels = header.channels,
                bitsPerSample = header.bitsPerSample,
                pid = pid,
            )
        }
        return null
    }

    private fun startRootTinyplayRawProcess(
        fifoFile: File,
        device: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ): Int? {
        val command = buildString {
            append(ROOT_TINYPLAY_BIN)
            append(" ")
            append(shellQuote(fifoFile.absolutePath))
            append(" -D 0 -d ")
            append(device)
            append(" -i raw -c ")
            append(channels.coerceAtLeast(1))
            append(" -r ")
            append(sampleRate.coerceAtLeast(8000))
            append(" -b ")
            append(bitsPerSample.coerceAtLeast(16))
            append(" >/dev/null 2>&1 & echo $!")
        }
        val result = RootShellRuntime.run(
            command = command,
            timeoutMs = ROOT_PLAY_START_TIMEOUT_MS,
        )
        if (!result.ok) {
            Log.w(
                TAG,
                "root tinyplay raw start failed device=$device err=${result.error ?: "none"} stderr=${result.stderr.ifBlank { "unknown" }}",
            )
            return null
        }
        return Regex("(\\d+)").find(result.stdout)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun writeFormField(
        out: DataOutputStream,
        boundary: String,
        name: String,
        value: String,
    ) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.writeBytes(value)
        out.writeBytes("\r\n")
    }

    private fun writeFileField(
        out: DataOutputStream,
        boundary: String,
        name: String,
        filename: String,
        contentType: String,
        bytes: ByteArray,
    ) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n")
        out.writeBytes("Content-Type: $contentType\r\n\r\n")
        out.write(bytes)
        out.writeBytes("\r\n")
    }

    private fun buildSilenceWav(durationMs: Int = 300, sampleRate: Int = 16_000): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val samples = (sampleRate * durationMs) / 1000
        val dataSize = samples * channels * (bitsPerSample / 8)
        val wav = ByteArray(44 + dataSize)
        fun writeText(offset: Int, value: String) {
            value.forEachIndexed { index, char ->
                wav[offset + index] = char.code.toByte()
            }
        }
        fun writeIntLE(offset: Int, value: Int) {
            wav[offset] = (value and 0xff).toByte()
            wav[offset + 1] = ((value shr 8) and 0xff).toByte()
            wav[offset + 2] = ((value shr 16) and 0xff).toByte()
            wav[offset + 3] = ((value shr 24) and 0xff).toByte()
        }
        fun writeShortLE(offset: Int, value: Int) {
            wav[offset] = (value and 0xff).toByte()
            wav[offset + 1] = ((value shr 8) and 0xff).toByte()
        }
        writeText(0, "RIFF")
        writeIntLE(4, 36 + dataSize)
        writeText(8, "WAVE")
        writeText(12, "fmt ")
        writeIntLE(16, 16)
        writeShortLE(20, 1)
        writeShortLE(22, channels)
        writeIntLE(24, sampleRate)
        writeIntLE(28, sampleRate * channels * (bitsPerSample / 8))
        writeShortLE(32, channels * (bitsPerSample / 8))
        writeShortLE(34, bitsPerSample)
        writeText(36, "data")
        writeIntLE(40, dataSize)
        return wav
    }

    private fun pcm16ToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val dataSize = pcm.size
        val wav = ByteArray(44 + dataSize)
        fun writeText(offset: Int, value: String) {
            value.forEachIndexed { index, char ->
                wav[offset + index] = char.code.toByte()
            }
        }
        fun writeIntLE(offset: Int, value: Int) {
            wav[offset] = (value and 0xff).toByte()
            wav[offset + 1] = ((value shr 8) and 0xff).toByte()
            wav[offset + 2] = ((value shr 16) and 0xff).toByte()
            wav[offset + 3] = ((value shr 24) and 0xff).toByte()
        }
        fun writeShortLE(offset: Int, value: Int) {
            wav[offset] = (value and 0xff).toByte()
            wav[offset + 1] = ((value shr 8) and 0xff).toByte()
        }
        writeText(0, "RIFF")
        writeIntLE(4, 36 + dataSize)
        writeText(8, "WAVE")
        writeText(12, "fmt ")
        writeIntLE(16, 16)
        writeShortLE(20, 1)
        writeShortLE(22, channels)
        writeIntLE(24, sampleRate)
        writeIntLE(28, sampleRate * channels * (bitsPerSample / 8))
        writeShortLE(32, channels * (bitsPerSample / 8))
        writeShortLE(34, bitsPerSample)
        writeText(36, "data")
        writeIntLE(40, dataSize)
        System.arraycopy(pcm, 0, wav, 44, pcm.size)
        return wav
    }

    private fun enableCallAudioRoute() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        runCatching { audioManager.isSpeakerphoneOn = FORCE_SPEAKER_ROUTE }
        applyVoiceCallVolume(LISTEN_SPEAKER_VOLUME_FRACTION, "route_init")
    }

    private fun applyVoiceCallVolume(fraction: Double, reason: String) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_VOICE_CALL
        val maxVolume = audioManager.getStreamMaxVolume(stream)
        if (maxVolume <= 0) {
            return
        }
        val target = if (MUTE_LOCAL_HANDSET_AUDIO) {
            0
        } else {
            max(1, (maxVolume * fraction).toInt())
        }
        val current = audioManager.getStreamVolume(stream)
        if (current == target || currentVoiceCallVolume == target) {
            return
        }
        runCatching { audioManager.setStreamVolume(stream, target, 0) }
        currentVoiceCallVolume = target
        CommandAuditLog.add("voice_bridge:volume:$current->$target:$reason")
    }

    private fun ensureNotificationChannel(): String {
        val channelId = "phonebridge_voice"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val existing = manager.getNotificationChannel(channelId)
            if (existing == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "PhoneBridge Voice",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
        return channelId
    }

    private fun startForegroundSafe() {
        if (!ENABLE_FOREGROUND_NOTIFICATION) {
            return
        }
        val channelId = ensureNotificationChannel()
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("PhoneBridge Voice")
            .setContentText("Spark call assistant active")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .build()
        runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure { error ->
                Log.w(TAG, "startForeground skipped: ${error.message}")
                CommandAuditLog.add("voice_bridge:fgs_skip:${error.javaClass.simpleName}")
            }
    }

    private object SparkConfig {
        const val baseUrl = "http://192.168.178.30:8996"
        const val bearer = "41154c137d0225c8a8d1abc6f659a39811e6a40fd3851bce40da2604ae37ddf3"
    }

    private data class AudioSourceCandidate(
        val id: Int,
        val name: String,
    )

    private data class RootCaptureCandidate(
        val device: Int,
        val name: String,
    )

    private data class RootCaptureFrame(
        val pcm: ByteArray,
        val sampleRate: Int,
        val channels: Int,
    )

    private data class DecodedWav(
        val pcm: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
    )

    private data class SourceProbeResult(
        val source: AudioSourceCandidate,
        val rms: Double,
    )

    private data class RootProbeResult(
        val source: RootCaptureCandidate,
        val rms: Double,
    )

    private fun startSilenceWatchdog() {
        mainHandler.removeCallbacks(silenceWatchdog)
        mainHandler.postDelayed(silenceWatchdog, SILENCE_CHECK_INTERVAL_MS)
    }

    private fun markSpeechActivity(source: String) {
        lastSpeechActivityAtMs.set(System.currentTimeMillis())
        CommandAuditLog.add("voice_bridge:activity:$source")
    }

    private fun enforceCallMute() {
        if (!ENFORCE_CALL_MUTE) {
            callMuteEnforced.set(false)
            CommandAuditLog.add("voice_bridge:call_mute:disabled")
            return
        }
        val callMuted = setCallMute(true, "service_start")
        callMuteEnforced.set(callMuted)
        if (callMuted) {
            CommandAuditLog.add("voice_bridge:call_mute:on")
        } else {
            Log.w(TAG, "call mute not enforced (no in-call service)")
        }
    }

    private fun setCallMute(enabled: Boolean, reason: String): Boolean {
        if (!ENFORCE_CALL_MUTE) {
            return false
        }
        val callMuted = InCallStateHolder.setCallMuted(enabled)
        if (callMuted) {
            CommandAuditLog.add("voice_bridge:call_mute:$enabled:$reason")
        }
        return callMuted
    }

    private fun initializeRootRuntime() {
        val probe = RootShellRuntime.ensureReady()
        if (!probe.ok) {
            val reason = probe.error ?: probe.stderr.ifBlank { "unknown" }
            CommandAuditLog.add("root:unavailable:$reason")
            Log.w(TAG, "root shell unavailable: $reason")
            return
        }
        CommandAuditLog.add("root:ready:${probe.shellPath}")
        Log.i(TAG, "root shell ready: ${probe.shellPath}")
        if (!ENABLE_ROOT_BOOTSTRAP) {
            return
        }
        if (rootBootstrapDone || rootBootstrapInFlight.get()) {
            return
        }
        if (!rootBootstrapInFlight.compareAndSet(false, true)) {
            return
        }
        Thread({
            try {
                ROOT_BOOTSTRAP_COMMANDS.forEach { command ->
                    val result = RootShellRuntime.run(command)
                    val status = if (result.ok) "ok" else "err"
                    CommandAuditLog.add("root:cmd:$status:${command.substringBefore(' ')}")
                    if (!result.ok) {
                        Log.w(
                            TAG,
                            "root bootstrap failed ($command): ${result.error ?: result.stderr.ifBlank { "unknown" }}",
                        )
                    }
                }
                rootBootstrapDone = true
                CommandAuditLog.add("root:bootstrap:done")
            } finally {
                rootBootstrapInFlight.set(false)
            }
        }, "pb-root-bootstrap").start()
    }

    private fun applyRootCallRouteProfile() {
        if (!ENABLE_ROOT_PCM_BRIDGE || !ENABLE_ROOT_CALL_ROUTE_PROFILE) {
            return
        }
        val command = ROOT_CALL_ROUTE_SET_COMMANDS.joinToString(" ; ")
        val result = RootShellRuntime.run(command, timeoutMs = ROOT_ROUTE_TIMEOUT_MS)
        val status = if (result.ok) "ok" else "err"
        CommandAuditLog.add("root:route_set_batch:$status")
        if (!result.ok) {
            Log.w(
                TAG,
                "root route set failed: ${result.error ?: result.stderr.ifBlank { "unknown" }}",
            )
        }
    }

    private fun restoreRootCallRouteProfile() {
        if (!ENABLE_ROOT_PCM_BRIDGE || !ENABLE_ROOT_CALL_ROUTE_PROFILE) {
            return
        }
        val command = ROOT_CALL_ROUTE_RESTORE_COMMANDS.joinToString(" ; ")
        val result = RootShellRuntime.run(command, timeoutMs = ROOT_ROUTE_TIMEOUT_MS)
        val status = if (result.ok) "ok" else "err"
        CommandAuditLog.add("root:route_restore_batch:$status")
        if (!result.ok) {
            Log.w(
                TAG,
                "root route restore failed: ${result.error ?: result.stderr.ifBlank { "unknown" }}",
            )
        }
    }

    companion object {
        private const val TAG = "SparkCallAssistant"
        private const val ACTION_STOP = "com.tracsystems.phonebridge.action.STOP_VOICE"
        private const val ACTION_REAPPLY_ROUTE = "com.tracsystems.phonebridge.action.REAPPLY_ROUTE"
        private const val EXTRA_ROUTE_REAPPLY_REASON = "reason"
        private const val NOTIFICATION_ID = 4501
        private const val SILENCE_HANGUP_MS = 90_000L
        private const val SILENCE_CHECK_INTERVAL_MS = 1_000L
        private const val CAPTURE_RETRY_DELAY_MS = 420L
        private const val TRANSCRIPT_RETRY_DELAY_MS = 680L
        private const val NO_AUDIO_RETRY_DELAY_MS = 1_100L
        private const val BARGE_IN_RESUME_DELAY_MS = 40L
        private const val NO_AUDIO_UNPIN_THRESHOLD = 6
        private const val ENFORCE_CALL_MUTE = true
        private const val ENABLE_FOREGROUND_NOTIFICATION = false
        private const val SEND_GREETING_ON_CONNECT = false
        private const val ENABLE_LOCAL_TTS_FALLBACK = false
        private const val ENABLE_AUDIO_EFFECTS = true
        private const val ENABLE_SPEAKER_VERIFICATION = false
        private const val MIN_CAPTURE_RMS = 24.0
        private const val MIN_PROBE_ACCEPT_RMS = 10.0
        private const val MIN_VAD_RMS = 30.0
        private const val VAD_NOISE_MULTIPLIER = 1.45
        private const val MIN_VOICED_MS = 420
        private const val MIN_VOICED_RATIO = 0.14
        private const val MIN_DYNAMIC_RANGE = 4.5
        private const val MAX_CLIPPING_RATIO = 0.03
        private const val MIN_CAPTURE_CONFIDENCE = 0.36
        private const val MIN_ENROLL_VOICED_MS = 950
        private const val MIN_SPEAKER_SIMILARITY = 0.52
        private const val FRAME_MS = 20
        private const val CLARIFICATION_COOLDOWN_MS = 2_800L
        private const val POST_PLAYBACK_CAPTURE_DELAY_MS = 140L
        private const val LISTEN_SPEAKER_VOLUME_FRACTION = 0.45
        private const val TTS_SPEAKER_VOLUME_FRACTION = 0.80
        private const val PROBE_CAPTURE_MS = 480
        private const val ENABLE_ROOT_BOOTSTRAP = true
        private const val ENABLE_ROOT_PCM_BRIDGE = true
        private const val ENABLE_ROOT_CALL_ROUTE_PROFILE = true
        private const val STRICT_REMOTE_AUDIO_ONLY = true
        private const val ENABLE_LOCAL_CLARIFICATION_TTS = false
        private const val ROOT_SKIP_QUALITY_GATES = true
        private const val ROOT_MIN_CAPTURE_RMS = 6.0
        private const val ROOT_MIN_ACCEPT_RMS = 26.0
        private const val ROOT_MIN_ACCEPT_VOICED_MS = 180
        private const val ROOT_CAPTURE_REQUEST_SAMPLE_RATE = 32_000
        private const val ROOT_CAPTURE_PRIMARY_CHANNELS = 2
        private const val ROOT_CAPTURE_PRECISE_CHUNKS = false
        private const val ROOT_CAPTURE_PRECISE_PADDING_MS = 220
        private const val ROOT_CAPTURE_PRECISE_MAX_MS = 2_400
        private const val ROOT_CAPTURE_PRECISE_EXTRA_SECONDS = 1
        private const val MIN_ROOT_RAW_CAPTURE_BYTES = 320
        private const val ROOT_CAPTURE_TRAILING_EXTENSION_ENABLED = true
        private const val ROOT_CAPTURE_TRAILING_EXTENSION_MS = 900
        private const val ROOT_CAPTURE_TRAILING_VOICE_WINDOW_MS = 320
        private const val ROOT_CAPTURE_TRAILING_MIN_VOICED_MS = 100
        private const val ROOT_CAPTURE_TRAILING_MIN_RMS = 28.0
        private const val ROOT_CAPTURE_MAX_MERGED_MS = 5_200
        private val ROOT_CAPTURE_SAMPLE_RATE_CANDIDATES = listOf(32_000, 24_000, 16_000, 8_000)
        private val ROOT_CAPTURE_CHANNEL_CANDIDATES = listOf(2, 1)
        private const val ROOT_CAPTURE_RATE_FIX_ENABLED = true
        private const val ROOT_CAPTURE_RATE_FIX_FROM = 32_000
        private const val ROOT_CAPTURE_RATE_FIX_TO = 24_000
        private val ROOT_CAPTURE_RATE_FIX_DEVICES = setOf(20, 21, 22, 54)
        private const val DEBUG_DUMP_ROOT_RAW_CAPTURE = false
        private const val MIN_DEBUG_RAW_WAV_BYTES = 8_192
        private const val KEEP_CALL_MUTED_DURING_TTS = true
        private const val FORCE_SPEAKER_ROUTE = false
        private const val MUTE_LOCAL_HANDSET_AUDIO = true
        private const val ROOT_TINYCAP_BIN = "/data/adb/service.d/phonebridge-tinycap"
        private const val ROOT_TINYPLAY_BIN = "/data/adb/service.d/phonebridge-tinyplay"
        private const val ROOT_TINYMIX_BIN = "/data/adb/service.d/phonebridge-tinymix"
        private const val ROOT_PLAY_TIMEOUT_MIN_MS = 4_000L
        private const val ROOT_PLAY_TIMEOUT_MAX_MS = 20_000L
        private const val ROOT_PLAYBACK_TIMEOUT_MARGIN_MS = 2_500L
        private const val ROOT_PLAY_START_TIMEOUT_MS = 2_500L
        private const val ROOT_ROUTE_TIMEOUT_MS = 8_000L
        private const val ROOT_ROUTE_RECOVER_THROTTLE_MS = 1_800L
        private const val ROOT_PLAYBACK_SAMPLE_RATE = 48_000
        private const val ENABLE_BARGE_IN_INTERRUPT = false
        private const val BARGE_IN_ARM_DELAY_MS = 180L
        private const val BARGE_IN_PROBE_INTERVAL_MS = 480L
        private const val BARGE_IN_PLAYBACK_POLL_MS = 60L
        private const val BARGE_IN_PROBE_CAPTURE_MS = 220
        private const val BARGE_IN_MIN_RMS = 24.0
        private const val BARGE_IN_MIN_VOICED_MS = 90
        private const val BARGE_IN_STRONG_RMS = 32.0
        private const val BARGE_IN_STRONG_VOICED_MS = 140
        private const val BARGE_IN_REQUIRE_ASR = true
        private const val BARGE_IN_MIN_ALNUM_CHARS = 2
        private const val BARGE_IN_ECHO_OVERLAP_THRESHOLD = 0.62
        private const val MAX_CAPTURE_ATTEMPTS_PER_TURN = 3
        private val CAPTURE_DURATION_BY_ATTEMPT_MS = listOf(1700, 2100, 2500)
        private const val ENABLE_UTTERANCE_STATE_MACHINE = true
        private const val UTTERANCE_CAPTURE_CHUNK_MS = 380
        private const val UTTERANCE_PRE_ROLL_MS = 350
        private const val UTTERANCE_MIN_SPEECH_MS = 260
        private const val UTTERANCE_SILENCE_MS = 560
        private const val UTTERANCE_MAX_TURN_MS = 8_000
        private const val UTTERANCE_LOOP_TIMEOUT_MS = 11_000
        private const val UTTERANCE_VAD_RMS = 120.0
        private const val ENABLE_UTTERANCE_CONTINUATION = false
        private const val UTTERANCE_CONTINUATION_CAPTURE_MS = 900
        private const val MAX_UTTERANCE_CONTINUATION_WINDOWS = 2
        private const val UTTERANCE_END_BOUNDARY_WINDOWS = 1
        private const val MAX_UTTERANCE_CHUNKS_PER_TURN = 4
        private const val MAX_UTTERANCE_MERGED_AUDIO_MS = 9_500
        private const val UTTERANCE_TERMINAL_MIN_TOKEN_COUNT = 8
        private const val MAX_SAME_SOURCE_RETRIES = 1
        private const val ENABLE_DEBUG_WAV_DUMP = true
        private const val DEBUG_WAV_DIR_NAME = "voice-debug"
        private const val MAX_DEBUG_WAV_FILES = 180
        private const val MIN_TRANSCRIPT_ALNUM_CHARS = 3
        private const val MIN_TRANSCRIPT_TOKEN_COUNT = 5
        private const val MIN_TRANSCRIPT_UNIQUE_RATIO = 0.45
        private const val TRANSCRIPT_ECHO_OVERLAP_THRESHOLD = 0.68
        private const val LOW_INFORMATION_MAX_TOKENS = 5
        private const val ENABLE_SPARK_TURN_STREAM = false
        private const val ENABLE_SPARK_STREAM_LIVE_PLAYBACK = false
        private const val SPARK_TURN_STREAM_READ_TIMEOUT_MS = 90_000
        private val LOW_INFORMATION_TOKENS = setOf(
            "hi",
            "hello",
            "hey",
            "who",
            "who's",
            "whos",
            "there",
            "is",
            "was",
            "so",
            "good",
            "day",
        )
        private val LOW_QUALITY_TRANSCRIPT_PATTERNS = listOf(
            Regex("^(no\\s+){4,}no$"),
            Regex("^([.\\-]\\s*){6,}$"),
            Regex("^(thanks\\s+for\\s+watching|thank\\s+you\\s+very\\s+much|have\\s+a\\s+great\\s+day|have\\s+a\\s+nice\\s+day)$"),
            Regex("^(take\\s+care(\\s+and)?\\s+(bye|bye\\s*bye|goodbye)[.!]?)$"),
            Regex("^(see\\s+you\\s+next\\s+week[.!]?)$"),
            Regex("^(you\\s+know[.!?]?)$"),
            Regex("^((hi|hello|hey)\\s+)*(who('s)?\\s+there)[.!?]?$"),
            Regex("^((so|good\\s+day)\\s+)*(who\\s+was\\s+there)[.!?]?$"),
            Regex("^(yeah|yep|uh|hmm|mmm|ok|okay|sure|alright|i\\s+get\\s+you|thank\\s+you|you\\s*re\\s+welcome)(\\s+(yeah|yep|uh|hmm|mmm|ok|okay|sure|alright|i\\s+get\\s+you|thank\\s+you|you\\s*re\\s+welcome))*$"),
        )
        private val SOURCE_ROTATE_IMMEDIATELY_REASONS = setOf(
            "speaker_mismatch",
            "clipping",
            "flat_signal",
        )
        private val AUDIO_SOURCE_CANDIDATES = listOf(
            AudioSourceCandidate(id = 3, name = "voice_downlink"),
            AudioSourceCandidate(id = 4, name = "voice_call"),
        )
        private val ROOT_CAPTURE_CANDIDATES = listOf(
            RootCaptureCandidate(device = 20, name = "incall_cap_0"),
            RootCaptureCandidate(device = 21, name = "incall_cap_1"),
            RootCaptureCandidate(device = 22, name = "incall_cap_2"),
            RootCaptureCandidate(device = 54, name = "incall_cap_3"),
        )
        private val ROOT_PLAYBACK_DEVICE_CANDIDATES = listOf(19, 18)
        private val ROOT_BOOTSTRAP_COMMANDS = listOf(
            "echo /data/adb/ap/bin/su > /data/adb/ap/su_path",
            "pm grant com.tracsystems.phonebridge android.permission.CALL_PHONE",
            "pm grant com.tracsystems.phonebridge android.permission.ANSWER_PHONE_CALLS",
            "pm grant com.tracsystems.phonebridge android.permission.READ_PHONE_STATE",
            "pm grant com.tracsystems.phonebridge android.permission.RECORD_AUDIO",
            "pm grant com.tracsystems.phonebridge android.permission.POST_NOTIFICATIONS",
            "chmod 755 /data/adb/service.d/phonebridge-tinycap",
            "chmod 755 /data/adb/service.d/phonebridge-tinyplay",
            "chmod 755 /data/adb/service.d/phonebridge-tinymix",
            "appops set com.tracsystems.phonebridge CALL_PHONE allow",
            "appops set com.tracsystems.phonebridge RECORD_AUDIO allow",
            "appops set com.tracsystems.phonebridge READ_PHONE_STATE allow",
            "appops set com.tracsystems.phonebridge ANSWER_PHONE_CALLS allow",
        )
        private val ROOT_CALL_ROUTE_SET_COMMANDS = listOf(
            "$ROOT_TINYMIX_BIN -D 0 set 116 IN_CALL_MUSIC",
            "$ROOT_TINYMIX_BIN -D 0 set 117 1",
            "$ROOT_TINYMIX_BIN -D 0 set 120 DL",
            "$ROOT_TINYMIX_BIN -D 0 set 121 DL",
            "$ROOT_TINYMIX_BIN -D 0 set 122 DL",
            "$ROOT_TINYMIX_BIN -D 0 set 123 DL",
            "$ROOT_TINYMIX_BIN -D 0 set 124 1",
            "$ROOT_TINYMIX_BIN -D 0 set 125 1",
            "$ROOT_TINYMIX_BIN -D 0 set 135 1",
            "$ROOT_TINYMIX_BIN -D 0 set 136 1",
        )
        private val ROOT_CALL_ROUTE_RESTORE_COMMANDS = listOf(
            "$ROOT_TINYMIX_BIN -D 0 set 116 Builtin_MIC",
            "$ROOT_TINYMIX_BIN -D 0 set 117 0",
            "$ROOT_TINYMIX_BIN -D 0 set 120 Off",
            "$ROOT_TINYMIX_BIN -D 0 set 121 Off",
            "$ROOT_TINYMIX_BIN -D 0 set 122 Off",
            "$ROOT_TINYMIX_BIN -D 0 set 123 Off",
            "$ROOT_TINYMIX_BIN -D 0 set 124 0",
            "$ROOT_TINYMIX_BIN -D 0 set 125 0",
            "$ROOT_TINYMIX_BIN -D 0 set 135 0",
            "$ROOT_TINYMIX_BIN -D 0 set 136 0",
        )

        fun start(context: Context) {
            val intent = Intent(context, SparkCallAssistantService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SparkCallAssistantService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun requestRouteReapply(context: Context, reason: String) {
            val intent = Intent(context, SparkCallAssistantService::class.java).apply {
                action = ACTION_REAPPLY_ROUTE
                putExtra(EXTRA_ROUTE_REAPPLY_REASON, reason)
            }
            context.startService(intent)
        }

        fun enforceCallMute(): Boolean = ENFORCE_CALL_MUTE
    }
}
