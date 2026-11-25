package us.snakepit.gravitywell.ui.console

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.Immutable
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class VoiceModePhase {
    IDLE,
    CONNECTING,
    LISTENING,
    PROCESSING,
    SPEAKING,
}

@Immutable
data class VoiceOption(
    val id: String,
    val displayName: String,
    val localeTag: String,
)

@Immutable
data class VoiceModeUiState(
    val isActive: Boolean = false,
    val isDictating: Boolean = false,
    val phase: VoiceModePhase = VoiceModePhase.IDLE,
    val micMuted: Boolean = false,
    val agentMuted: Boolean = false,
    val minimized: Boolean = false,
    val speed: Float = 1.0f,
    val elapsedSeconds: Long = 0L,
    val rmsLevel: Float = 0f,
    val partialTranscript: String = "",
    val lastSpokenReply: String = "",
    val statusHint: String = "Listening. Ask anything.",
    val selectedVoiceId: String? = null,
    val availableVoices: List<VoiceOption> = emptyList(),
    val showVoicePicker: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface VoiceIntent {
    data object EndCall : VoiceIntent
    data object StopTurn : VoiceIntent
    data class PermissionDecision(val decision: String) : VoiceIntent
    data class PromptText(val text: String) : VoiceIntent
}

object VoiceSpeechFormatter {
    private val fencedCodeRegex = Regex("```[\\s\\S]*?```")
    private val inlineCodeRegex = Regex("`([^`]+)`")
    private val markdownLinkRegex = Regex("\\[([^\\]]+)]\\([^)]+\\)")
    private val markdownSymbolRegex = Regex("[*_~#>]+")
    private val whitespaceRegex = Regex("\\s+")

    fun toSpokenText(markdown: String, maxChars: Int = 700): String {
        if (markdown.isBlank()) return ""
        val hadCodeBlock = fencedCodeRegex.containsMatchIn(markdown)
        var cleaned = fencedCodeRegex.replace(markdown, " ")
        cleaned = markdownLinkRegex.replace(cleaned, "$1")
        cleaned = inlineCodeRegex.replace(cleaned, "$1")
        cleaned = markdownSymbolRegex.replace(cleaned, " ")
        cleaned = cleaned.replace("|", " ").replace("---", " ")
        cleaned = whitespaceRegex.replace(cleaned, " ").trim()

        if (cleaned.isEmpty() && hadCodeBlock) {
            return "I've added the code in the chat."
        }
        val clipped = if (cleaned.length > maxChars) {
            val cut = cleaned.substring(0, maxChars)
            val sentenceEnd = maxOf(cut.lastIndexOf('.'), cut.lastIndexOf('!'), cut.lastIndexOf('?'))
            if (sentenceEnd > maxChars / 2) cut.substring(0, sentenceEnd + 1) else "$cut..."
        } else {
            cleaned
        }
        return if (hadCodeBlock && clipped.length < maxChars - 40) {
            "$clipped Code details are in the chat."
        } else {
            clipped
        }
    }
}

object VoiceCommandParser {
    private val destructivePatterns = listOf(
        "rm ", "rm -", "sudo ", "mkfs", "dd ", "git push --force", "git push -f",
        "drop table", "truncate ", "chmod 777",
    )

    fun isDestructiveCommand(commandSummary: String): Boolean {
        val low = commandSummary.lowercase(Locale.US)
        return destructivePatterns.any { low.contains(it) }
    }

    fun parseUtterance(
        utterance: String,
        hasPendingPermission: Boolean,
        isTurnActive: Boolean,
    ): VoiceIntent {
        val norm = utterance.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (norm in setOf("bye", "goodbye", "end the call", "end call", "end voice mode", "hang up", "stop voice mode")) {
            return VoiceIntent.EndCall
        }

        if (hasPendingPermission) {
            if (norm.contains("deny") || norm == "no" || norm.startsWith("no ") || norm.contains("reject") || norm.contains("cancel") || norm == "stop") {
                return VoiceIntent.PermissionDecision("deny")
            }
            if (norm.contains("always allow") || norm.contains("allow for this project") || norm.contains("allow for this workspace") || norm.contains("allow always")) {
                return VoiceIntent.PermissionDecision("allow_rule")
            }
            if (norm.contains("allow") || norm == "yes" || norm == "approve" || norm == "go ahead" || norm == "proceed") {
                return VoiceIntent.PermissionDecision("allow_once")
            }
        }

        if (isTurnActive && norm in setOf("stop", "cancel", "cancel that", "never mind", "nevermind", "abort")) {
            return VoiceIntent.StopTurn
        }

        return VoiceIntent.PromptText(utterance.trim())
    }
}

class AndroidVoiceEngine(
    context: Context,
    private val onDictationPartial: (String) -> Unit,
    private val onDictationFinal: (String) -> Unit,
    private val onVoiceModeUtterance: (String) -> Unit,
    private val onVoiceError: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(VoiceModeUiState())
    val state: StateFlow<VoiceModeUiState> = _state.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeechAfterInit: String? = null
    private var elapsedTicker: Runnable? = null
    private var voiceStartedAtMs: Long = 0L
    private var isRestartScheduled = false

    fun initializeTtsIfNeeded() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(_state.value.speed)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        mainHandler.post {
                            if (_state.value.isActive) {
                                _state.update {
                                    it.copy(
                                        phase = VoiceModePhase.SPEAKING,
                                        statusHint = "Antigravity is speaking...",
                                    )
                                }
                            }
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post {
                            if (_state.value.isActive) {
                                _state.update {
                                    it.copy(
                                        phase = VoiceModePhase.LISTENING,
                                        statusHint = if (it.micMuted) "Microphone muted" else "Listening. Ask anything.",
                                    )
                                }
                                if (!_state.value.micMuted) {
                                    startListeningInternal()
                                }
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onDone(utteranceId)
                    }
                })
                refreshAvailableVoices()
                pendingSpeechAfterInit?.let { queued ->
                    pendingSpeechAfterInit = null
                    speak(queued)
                }
            }
        }
    }

    private fun refreshAvailableVoices() {
        val rawVoices: Set<Voice> = runCatching { tts?.voices.orEmpty() }.getOrDefault(emptySet())
        val localeLang = Locale.getDefault().language
        val options = rawVoices
            .filter { !it.isNetworkConnectionRequired || it.locale.language == localeLang }
            .sortedWith(
                compareByDescending<Voice> { it.locale.language == localeLang }
                    .thenBy { it.name },
            )
            .take(18)
            .mapIndexed { idx, v ->
                val pretty = v.name
                    .substringAfterLast('-')
                    .replace('_', ' ')
                    .replaceFirstChar { it.uppercase() }
                VoiceOption(
                    id = v.name,
                    displayName = "Voice ${idx + 1} · ${v.locale.displayLanguage} ($pretty)",
                    localeTag = v.locale.toLanguageTag(),
                )
            }
        val currentId = runCatching { tts?.voice?.name }.getOrNull()
        _state.update {
            it.copy(
                availableVoices = options,
                selectedVoiceId = it.selectedVoiceId ?: currentId,
            )
        }
    }

    fun startVoiceMode() {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                val msg = "Speech recognition service is unavailable on this device."
                _state.update { it.copy(errorMessage = msg) }
                onVoiceError(msg)
                return@post
            }
            stopDictationInternal()
            initializeTtsIfNeeded()
            voiceStartedAtMs = System.currentTimeMillis()
            _state.update {
                it.copy(
                    isActive = true,
                    isDictating = false,
                    phase = VoiceModePhase.CONNECTING,
                    micMuted = false,
                    minimized = false,
                    elapsedSeconds = 0L,
                    partialTranscript = "",
                    lastSpokenReply = "",
                    statusHint = "Connecting...",
                    errorMessage = null,
                )
            }
            startElapsedTimer()
            startListeningInternal()
        }
    }

    fun stopVoiceMode(): Long {
        val duration = if (voiceStartedAtMs > 0L) {
            ((System.currentTimeMillis() - voiceStartedAtMs) / 1000L).coerceAtLeast(0L)
        } else {
            _state.value.elapsedSeconds
        }
        mainHandler.post {
            stopElapsedTimer()
            stopListeningInternal()
            tts?.stop()
            voiceStartedAtMs = 0L
            _state.update {
                it.copy(
                    isActive = false,
                    isDictating = false,
                    phase = VoiceModePhase.IDLE,
                    rmsLevel = 0f,
                    partialTranscript = "",
                    showVoicePicker = false,
                    errorMessage = null,
                )
            }
        }
        return duration
    }

    fun startDictation() {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                val msg = "Speech recognition service is unavailable on this device."
                _state.update { it.copy(errorMessage = msg) }
                onVoiceError(msg)
                return@post
            }
            if (_state.value.isActive) {
                stopVoiceMode()
            }
            _state.update {
                it.copy(
                    isDictating = true,
                    partialTranscript = "",
                    errorMessage = null,
                )
            }
            startListeningInternal()
        }
    }

    fun stopDictation() {
        mainHandler.post { stopDictationInternal() }
    }

    private fun stopDictationInternal() {
        if (!_state.value.isDictating) return
        stopListeningInternal()
        _state.update { it.copy(isDictating = false, rmsLevel = 0f, partialTranscript = "") }
    }

    fun toggleMicMuted() {
        mainHandler.post {
            val nextMuted = !_state.value.micMuted
            _state.update {
                it.copy(
                    micMuted = nextMuted,
                    rmsLevel = if (nextMuted) 0f else it.rmsLevel,
                    statusHint = if (nextMuted) "Microphone muted" else "Listening. Ask anything.",
                )
            }
            if (nextMuted) {
                stopListeningInternal()
            } else if (_state.value.isActive && _state.value.phase != VoiceModePhase.SPEAKING) {
                startListeningInternal()
            }
        }
    }

    fun toggleAgentMuted() {
        mainHandler.post {
            val nextMuted = !_state.value.agentMuted
            _state.update { it.copy(agentMuted = nextMuted) }
            if (nextMuted) {
                tts?.stop()
                if (_state.value.isActive && !_state.value.micMuted) {
                    _state.update { it.copy(phase = VoiceModePhase.LISTENING, statusHint = "Listening. Ask anything.") }
                    startListeningInternal()
                }
            }
        }
    }

    fun setMinimized(minimized: Boolean) {
        _state.update { it.copy(minimized = minimized) }
    }

    fun setShowVoicePicker(show: Boolean) {
        if (show) initializeTtsIfNeeded()
        _state.update { it.copy(showVoicePicker = show) }
    }

    fun selectVoice(voiceId: String, preview: Boolean = true) {
        mainHandler.post {
            val match = runCatching { tts?.voices?.firstOrNull { it.name == voiceId } }.getOrNull()
            if (match != null) {
                tts?.voice = match
            }
            _state.update { it.copy(selectedVoiceId = voiceId) }
            if (preview && ttsReady) {
                tts?.speak(
                    "Hi, I'm Antigravity. Ready when you are.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "preview_${UUID.randomUUID()}",
                )
            }
        }
    }

    fun cycleSpeed() {
        val steps = listOf(0.85f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val cur = _state.value.speed
        val next = steps.firstOrNull { it > cur + 0.01f } ?: steps.first()
        setSpeed(next)
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.75f, 2.0f)
        _state.update { it.copy(speed = clamped) }
        mainHandler.post {
            tts?.setSpeechRate(clamped)
        }
    }

    fun setWorkingHint(hint: String) {
        if (!_state.value.isActive) return
        _state.update {
            it.copy(
                phase = VoiceModePhase.PROCESSING,
                statusHint = hint,
            )
        }
    }

    fun speak(rawText: String, interruptMic: Boolean = true) {
        val spoken = VoiceSpeechFormatter.toSpokenText(rawText)
        if (spoken.isBlank()) return
        mainHandler.post {
            _state.update { it.copy(lastSpokenReply = spoken) }
            if (!_state.value.isActive) return@post
            if (_state.value.agentMuted) {
                _state.update {
                    it.copy(
                        phase = VoiceModePhase.LISTENING,
                        statusHint = if (it.micMuted) "Microphone muted" else "Listening. Ask anything.",
                    )
                }
                if (!_state.value.micMuted) startListeningInternal()
                return@post
            }
            if (interruptMic) {
                stopListeningInternal()
            }
            if (!ttsReady) {
                pendingSpeechAfterInit = spoken
                initializeTtsIfNeeded()
                return@post
            }
            tts?.setSpeechRate(_state.value.speed)
            tts?.speak(
                spoken,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "agt_${UUID.randomUUID()}",
            )
        }
    }

    fun interruptSpeakingAndListen() {
        mainHandler.post {
            tts?.stop()
            if (_state.value.isActive && !_state.value.micMuted) {
                _state.update {
                    it.copy(
                        phase = VoiceModePhase.LISTENING,
                        statusHint = "Listening. Ask anything.",
                    )
                }
                startListeningInternal()
            }
        }
    }

    private fun startListeningInternal() {
        isRestartScheduled = false
        val st = _state.value
        if (!st.isActive && !st.isDictating) return
        if (st.isActive && st.micMuted) return

        stopListeningInternal()
        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrNull()
        if (recognizer == null) {
            onVoiceError("Unable to initialize Android speech recognizer.")
            return
        }
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.update {
                    it.copy(
                        phase = if (it.isActive) VoiceModePhase.LISTENING else it.phase,
                        statusHint = if (it.micMuted) "Microphone muted" else "Listening. Ask anything.",
                        errorMessage = null,
                    )
                }
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _state.update { it.copy(rmsLevel = normalized) }
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                _state.update { it.copy(rmsLevel = 0f) }
            }

            override fun onError(error: Int) {
                _state.update { it.copy(rmsLevel = 0f) }
                val cur = _state.value
                if (cur.isDictating) {
                    _state.update { it.copy(isDictating = false) }
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        onVoiceError("Dictation error (code $error)")
                    }
                    return
                }
                if (cur.isActive && !cur.micMuted && cur.phase != VoiceModePhase.SPEAKING) {
                    val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    if (recoverable) {
                        scheduleListenRestart(350L)
                    } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        val msg = "Microphone permission is required for Voice mode."
                        _state.update { it.copy(errorMessage = msg) }
                        stopVoiceMode()
                    } else {
                        scheduleListenRestart(900L)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = matches?.firstOrNull()?.trim().orEmpty()
                val cur = _state.value
                _state.update { it.copy(rmsLevel = 0f, partialTranscript = "") }

                if (cur.isDictating) {
                    _state.update { it.copy(isDictating = false) }
                    if (best.isNotEmpty()) onDictationFinal(best)
                    return
                }

                if (cur.isActive) {
                    if (best.isNotEmpty()) {
                        _state.update {
                            it.copy(
                                phase = VoiceModePhase.PROCESSING,
                                partialTranscript = best,
                                statusHint = "Heard: $best",
                            )
                        }
                        onVoiceModeUtterance(best)
                    } else if (!cur.micMuted) {
                        scheduleListenRestart(250L)
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isEmpty()) return
                _state.update { it.copy(partialTranscript = partial) }
                if (_state.value.isDictating) {
                    onDictationPartial(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        runCatching { recognizer.startListening(intent) }.onFailure { err ->
            onVoiceError(err.message ?: "Failed to start microphone listening.")
        }
    }

    private fun scheduleListenRestart(delayMs: Long) {
        if (isRestartScheduled) return
        isRestartScheduled = true
        mainHandler.postDelayed(
            {
                isRestartScheduled = false
                val cur = _state.value
                if (cur.isActive && !cur.micMuted && cur.phase != VoiceModePhase.SPEAKING) {
                    startListeningInternal()
                }
            },
            delayMs,
        )
    }

    private fun stopListeningInternal() {
        isRestartScheduled = false
        speechRecognizer?.let { rec ->
            runCatching { rec.cancel() }
            runCatching { rec.destroy() }
        }
        speechRecognizer = null
    }

    private fun startElapsedTimer() {
        stopElapsedTimer()
        val runnable = object : Runnable {
            override fun run() {
                if (_state.value.isActive && voiceStartedAtMs > 0L) {
                    val secs = ((System.currentTimeMillis() - voiceStartedAtMs) / 1000L).coerceAtLeast(0L)
                    _state.update { it.copy(elapsedSeconds = secs) }
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }
        elapsedTicker = runnable
        mainHandler.postDelayed(runnable, 1000L)
    }

    private fun stopElapsedTimer() {
        elapsedTicker?.let { mainHandler.removeCallbacks(it) }
        elapsedTicker = null
    }

    fun release() {
        mainHandler.post {
            stopElapsedTimer()
            stopListeningInternal()
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
            ttsReady = false
        }
    }
}
