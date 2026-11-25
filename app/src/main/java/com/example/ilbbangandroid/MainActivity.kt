package com.example.ilbbangandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var textToSpeech: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var pendingTtsText: String? = null
    private var pendingTtsRate = 0.85f
    private val appUrl = "https://creep1020.github.io/1-book/"
    private val handler = Handler(Looper.getMainLooper())

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var pendingRequestId = ""
    private var pendingMode = ""
    private var recordingActive = false
    private var processingWatchdog: Runnable? = null
    private var processingJob: Job? = null

    companion object {
        private const val TAG = "IlbbangVoice"
        private const val MIC_PERMISSION = 1001
        private const val NOTIFICATION_PERMISSION = 1002
        private const val RECOGNITION_TIMEOUT = 30000L
        private const val MODEL_DOWNLOAD_TIMEOUT = 60000L
        private const val PROCESSING_WATCHDOG_TIMEOUT = 95000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textToSpeech = TextToSpeech(this) { status ->
            runOnUiThread {
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    try { textToSpeech?.language = Locale.US } catch (e: Exception) {
                        Log.e(TAG, "영어 TTS 언어 설정 실패", e)
                    }
                    val queued = pendingTtsText
                    if (!queued.isNullOrBlank()) {
                        val rate = pendingTtsRate
                        pendingTtsText = null
                        speakNative(queued, rate)
                    }
                } else Log.e(TAG, "기기 TTS 엔진 초기화 실패: status=$status")
            }
        }
        try {
            val browser = WebView(this)
            webView = browser
            browser.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            browser.addJavascriptInterface(NativeVoiceBridge(), "IlbbangAndroid")
            browser.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    // 페이지 새로고침/복원으로 JS 상태가 초기화될 때 네이티브 녹음 잠금도 함께 해제합니다.
                    val staleRequest = pendingRequestId
                    if (staleRequest.isNotEmpty()) cancelNativeVoice(staleRequest)
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    if (!isTrustedPage()) return
                    // Firebase Android SDK에서 실제 FCM 토큰을 받아 웹앱 콜백으로 전달합니다.
                    FirebaseMessaging.getInstance().token
                        .addOnCompleteListener { task ->
                            if (!task.isSuccessful) {
                                Log.e(TAG, "FCM 토큰 발급 실패", task.exception)
                                return@addOnCompleteListener
                            }
                            val token = task.result ?: return@addOnCompleteListener
                            val quotedToken = JSONObject.quote(token)
                            view.post {
                                if (isTrustedPage()) {
                                    view.evaluateJavascript(
                                        "window.onIlbbangNativeFcmToken && window.onIlbbangNativeFcmToken($quotedToken);",
                                        null
                                    )
                                }
                            }
                            Log.d(TAG, "FCM 토큰을 웹앱에 전달했습니다.")
                        }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val isAppPage = uri.scheme == "https" &&
                        uri.host == "creep1020.github.io" &&
                        uri.path?.startsWith("/1-book/") == true
                    if (isAppPage) return false
                    if (uri.scheme == "https" || uri.scheme == "http") {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (e: Exception) {
                            Log.e(TAG, "외부 링크 오류", e)
                        }
                    }
                    return true
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        showError("페이지를 불러오지 못했습니다.\n${error.description}")
                    }
                }
            }
            browser.webChromeClient = WebChromeClient()
            browser.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            setContentView(browser)
            if (savedInstanceState == null) {
                browser.loadUrl(appUrl)
            } else {
                val restored = browser.restoreState(savedInstanceState)
                if (restored == null) browser.loadUrl(appUrl)
            }
            requestNotificationPermissionIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "앱 실행 오류", e)
            showError("앱 실행 오류\n\n${e.message ?: e.toString()}")
        }
    }

    private inner class NativeVoiceBridge {
        @JavascriptInterface
        fun startRecording(requestId: String, mode: String) {
            runOnUiThread {
                if (!isTrustedPage()) {
                    Log.e(TAG, "신뢰할 수 없는 페이지 주소: ${webView?.url}")
                    sendVoiceError(requestId, "앱 주소 확인 오류: ${webView?.url}")
                    return@runOnUiThread
                }
                if (mode != "sentence" && mode != "review") {
                    sendVoiceError(requestId, "잘못된 녹음 요청입니다.")
                    return@runOnUiThread
                }
                if (recordingActive || pendingRequestId.isNotEmpty()) {
                    sendVoiceError(requestId, "이전 음성 처리가 아직 끝나지 않았습니다. 잠시 후 다시 시도해 주세요.", mode)
                    return@runOnUiThread
                }
                pendingRequestId = requestId
                pendingMode = mode
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION)
                    return@runOnUiThread
                }
                startNativeRecording()
            }
        }

        @JavascriptInterface
        fun stopRecording(requestId: String) {
            runOnUiThread {
                if (!isTrustedPage() || requestId.isBlank() || pendingRequestId != requestId) return@runOnUiThread
                if (recordingActive) {
                    Log.d(TAG, "사용자 요청으로 녹음 조기 종료: requestId=$requestId")
                    finishNativeRecording()
                } else if (processingJob?.isActive == true) {
                    // 녹음은 끝났고 실제 인식 작업이 진행 중인 경우에만 대기 상태를 유지합니다.
                    sendVoiceStatus(requestId, "recognizing")
                } else {
                    // 권한 대기/시작 실패 등으로 녹음도 인식 작업도 없는 유령 요청을 정리합니다.
                    sendVoiceError(requestId, "녹음 상태가 종료되어 다시 시작해야 합니다.")
                }
            }
        }

        @JavascriptInterface
        fun cancelRecording(requestId: String) {
            runOnUiThread {
                if (isTrustedPage()) cancelNativeVoice(requestId)
            }
        }
        @JavascriptInterface
        fun canSpeakEnglish(): Boolean {
            if (!ttsReady) return false
            return try {
                val engine = textToSpeech ?: return false
                engine.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE
            } catch (e: Exception) {
                Log.w(TAG, "영어 TTS 사용 가능 여부 확인 실패", e)
                false
            }
        }

        @JavascriptInterface
        fun speak(text: String, rate: Double) {
            runOnUiThread {
                if (!isTrustedPage() || !canSpeakEnglish()) return@runOnUiThread
                val safeText = text.take(500)
                if (safeText.isBlank()) return@runOnUiThread
                pendingTtsText = null
                pendingTtsRate = rate.toFloat().coerceIn(0.5f, 2.0f)
                speakNative(safeText, pendingTtsRate)
            }
        }

        @JavascriptInterface
        fun stopSpeaking() {
            runOnUiThread {
                pendingTtsText = null
                try { textToSpeech?.stop() } catch (e: Exception) {
                    Log.w(TAG, "TTS 중지 실패", e)
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
        }
    }

    private fun speakNative(text: String, rate: Float) {
        if (!ttsReady) {
            pendingTtsText = text
            pendingTtsRate = rate
            return
        }
        try {
            val engine = textToSpeech ?: return
            engine.language = Locale.US
            engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ilbbang_tts_${System.currentTimeMillis()}")
        } catch (e: Exception) { Log.e(TAG, "네이티브 영어 음성 재생 실패", e) }
    }

    private fun cancelNativeVoice(requestId: String) {
        if (requestId.isBlank() || pendingRequestId != requestId) return
        Log.w(TAG, "네이티브 음성 요청 취소: requestId=$requestId")
        processingWatchdog?.let { handler.removeCallbacks(it) }
        processingWatchdog = null
        releaseRecorder()
        processingJob?.cancel()
        processingJob = null
        recordingFile?.delete()
        recordingFile = null
        pendingRequestId = ""
        pendingMode = ""
    }

    private fun isTrustedPage(): Boolean {
        val uri = Uri.parse(webView?.url ?: "")
        return uri.scheme == "https" &&
            uri.host == "creep1020.github.io" &&
            uri.path?.startsWith("/1-book/") == true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION) {
            // 권한 창이 떠 있는 동안 사용자가 학습 화면을 나가거나 요청이 취소됐을 수 있습니다.
            // 취소된 요청에는 녹음을 시작하거나 JS 결과를 보내지 않습니다.
            if (pendingRequestId.isBlank() || !isTrustedPage()) {
                Log.w(TAG, "마이크 권한 응답 무시: 대기 중인 음성 요청이 없습니다.")
                return
            }
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startNativeRecording()
            } else {
                sendVoiceError(pendingRequestId, "마이크 권한을 허용해야 녹음할 수 있습니다.")
            }
        }
    }

    private fun startNativeRecording() {
        // 권한 응답이 늦게 도착했거나 이전 요청이 취소된 경우를 차단합니다.
        if (pendingRequestId.isBlank() || !isTrustedPage()) {
            Log.w(TAG, "녹음 시작 차단: 유효한 요청이 없습니다.")
            return
        }
        if (recordingActive || recorder != null) {
            sendVoiceError(pendingRequestId, "이미 녹음이 진행 중입니다. 잠시 후 다시 시도해 주세요.")
            return
        }
        try {
            val file = File(cacheDir, "ilbbang_${System.currentTimeMillis()}.m4a")
            val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(64000)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mediaRecorder
            recordingFile = file
            recordingActive = true
            sendVoiceStatus(pendingRequestId, "recording")
            // 녹음은 사용자가 녹음 종료 버튼을 누를 때까지 유지합니다.
        } catch (e: Exception) {
            Log.e(TAG, "녹음 시작 실패", e)
            releaseRecorder()
            sendVoiceError(pendingRequestId, "녹음을 시작하지 못했습니다: ${e.message}")
        }
    }

    private fun finishNativeRecording() {
        if (!recordingActive) return
        recordingActive = false

        val activeRecorder = recorder
        recorder = null
        try {
            activeRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "녹음 종료 오류", e)
        } finally {
            try { activeRecorder?.release() } catch (_: Exception) {}
        }

        val file = recordingFile
        recordingFile = null
        if (file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            sendVoiceError(pendingRequestId, "녹음 파일을 만들지 못했습니다.")
            return
        }

        val requestId = pendingRequestId
        val mode = pendingMode
        Log.d(TAG, "녹음 완료: requestId=$requestId, mode=$mode, bytes=${file.length()}")
        // ML Kit 흐름이 예기치 않게 멈춰도 네이티브 요청 잠금이 영구히 남지 않도록 감시합니다.
        processingWatchdog?.let { handler.removeCallbacks(it) }
        processingWatchdog = Runnable {
            if (pendingRequestId == requestId) {
                Log.e(TAG, "네이티브 처리 감시 시간 초과: requestId=$requestId")
                processingJob?.cancel()
                processingJob = null
                sendVoiceResult(
                    requestId, mode, "", null,
                    "음성인식 처리가 응답하지 않아 종료했어요. 다시 녹음해 주세요."
                )
                pendingRequestId = ""
                pendingMode = ""
            }
            processingWatchdog = null
        }.also { handler.postDelayed(it, PROCESSING_WATCHDOG_TIMEOUT) }
        // 녹음 직후에는 모델 준비 단계를 별도로 알립니다.
        sendVoiceStatus(requestId, "preparing")

        processingJob = lifecycleScope.launch {
            var transcript = ""
            var recognitionError = ""
            var audioBase64: String? = null

            try {
                Log.d(TAG, "ML Kit 인식 작업 시작: requestId=$requestId")
                transcript = withContext(Dispatchers.IO) {
                    recognizeAudio(file) {
                        Log.d(TAG, "ML Kit 인식 단계 진입: requestId=$requestId")
                        sendVoiceStatus(requestId, "recognizing")
                    }
                }
                Log.d(TAG, "ML Kit 인식 작업 종료: requestId=$requestId, transcriptLength=${transcript.length}")
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "음성인식 또는 모델 준비 시간 초과", e)
                recognitionError = "음성 확인 시간이 길어졌어요. 다시 한 번 천천히 말해 주세요."
            } catch (e: CancellationException) {
                Log.i(TAG, "음성인식 작업 취소: requestId=$requestId")
                file.delete()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "ML Kit 인식 오류", e)
                recognitionError = e.message ?: "음성인식에 실패했습니다."
            }

            if (mode == "sentence") {
                try {
                    audioBase64 = withContext(Dispatchers.IO) {
                        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "녹음 데이터 변환 실패", e)
                    recognitionError = recognitionError.ifEmpty {
                        "녹음 데이터를 전달하지 못했습니다."
                    }
                }
            }

            file.delete()
            if (transcript.isBlank() && recognitionError.isBlank()) {
                recognitionError = "음성을 인식하지 못했습니다."
            }
            Log.d(TAG, "웹뷰로 최종 결과 전달: requestId=$requestId, error=$recognitionError")
            if (pendingRequestId == requestId) {
                sendVoiceResult(requestId, mode, transcript, audioBase64, recognitionError)
                pendingRequestId = ""
                pendingMode = ""
            } else {
                Log.w(TAG, "이미 취소되거나 교체된 요청이므로 결과 전달 생략: requestId=$requestId")
            }
            if (processingJob?.isActive == false) processingJob = null
            processingWatchdog?.let { handler.removeCallbacks(it) }
            processingWatchdog = null
        }
    }

    private suspend fun recognizeAudio(file: File, onRecognitionStarted: () -> Unit): String {
        val options = speechRecognizerOptions {
            locale = Locale.US
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
        }
        val recognizer = SpeechRecognition.getClient(options)
        try {
            sendVoiceStatus(pendingRequestId, "checking")
            val status = recognizer.checkStatus()
            Log.d(TAG, "ML Kit 모델 상태: $status")
            when (status) {
                FeatureStatus.AVAILABLE -> {
                    Log.d(TAG, "ML Kit 모델이 이미 준비되어 있습니다.")
                    sendVoiceStatus(pendingRequestId, "model-ready")
                }
                FeatureStatus.DOWNLOADABLE -> {
                    sendVoiceStatus(pendingRequestId, "downloading")
                    // 다운로드 Flow가 계속 열려 있을 수 있으므로 완료/실패의 첫 종결 이벤트만 기다립니다.
                    val terminal = withTimeout(MODEL_DOWNLOAD_TIMEOUT) {
                        recognizer.download().first { result ->
                            result is DownloadStatus.DownloadCompleted ||
                                result is DownloadStatus.DownloadFailed
                        }
                    }
                    when (terminal) {
                        is DownloadStatus.DownloadCompleted -> {
                            Log.d(TAG, "ML Kit 모델 다운로드 완료")
                            sendVoiceStatus(pendingRequestId, "model-ready")
                        }
                        is DownloadStatus.DownloadFailed -> {
                            val message = terminal.e.message ?: "모델 다운로드 실패"
                            Log.e(TAG, "ML Kit 모델 다운로드 실패: $message", terminal.e)
                            throw Exception(message)
                        }
                        else -> throw Exception("음성인식 모델 다운로드 상태를 확인하지 못했습니다.")
                    }
                }
                FeatureStatus.DOWNLOADING ->
                    throw Exception("음성인식 모델을 다운로드 중입니다. 잠시 후 다시 시도해 주세요.")
                else ->
                    throw Exception("이 기기에서 영어 음성인식을 사용할 수 없습니다. 모델 상태: $status")
            }

            val audioSource = AudioSource.fromFile(file)
            try {
                val request = speechRecognizerRequest { this.audioSource = audioSource }
                var recognizedText = ""
                onRecognitionStarted()
                // 녹음 파일 처리와 최종 응답까지 충분히 기다립니다.
                withTimeout(RECOGNITION_TIMEOUT) {
                    recognizer.startRecognition(request).collect { response ->
                        Log.d(TAG, "ML Kit 응답: ${response::class.java.simpleName}")
                        when (response) {
                            is SpeechRecognizerResponse.FinalTextResponse ->
                                recognizedText = response.text
                            is SpeechRecognizerResponse.ErrorResponse ->
                                throw Exception(response.e.message ?: "음성인식 처리 오류")
                            is SpeechRecognizerResponse.CompletedResponse ->
                                Log.d(TAG, "ML Kit 인식 완료 이벤트 수신")
                            else -> Unit
                        }
                    }
                }
                return recognizedText
            } finally {
                audioSource.close()
            }
        } finally {
            recognizer.close()
        }
    }

    private fun sendVoiceStatus(requestId: String, status: String) {
        val js = "window.onIlbbangNativeVoiceStatus && " +
            "window.onIlbbangNativeVoiceStatus(" +
            "${JSONObject.quote(requestId)}," +
            "${JSONObject.quote(status)});"
        webView?.post {
            val browser = webView
            if (browser == null) {
                Log.e(TAG, "상태 전달 실패: WebView가 없습니다. status=$status")
            } else {
                browser.evaluateJavascript(js) { value ->
                    Log.d(TAG, "상태 전달 완료: status=$status, jsResult=$value")
                }
            }
        }
    }

    private fun sendVoiceError(
        requestId: String,
        message: String,
        mode: String = if (pendingRequestId == requestId) pendingMode else ""
    ) {
        sendVoiceResult(requestId, mode, "", null, message)
        // 오류 경로에서도 요청 잠금을 반드시 해제해 다음 녹음을 허용합니다.
        if (pendingRequestId == requestId) {
            pendingRequestId = ""
            pendingMode = ""
        }
    }

    private fun sendVoiceResult(
        requestId: String,
        mode: String,
        transcript: String,
        audioBase64: String?,
        error: String
    ) {
        val result = JSONObject().apply {
            put("requestId", requestId)
            put("mode", mode)
            put("transcript", transcript)
            put("audioBase64", audioBase64 ?: JSONObject.NULL)
            put("mimeType", "audio/mp4")
            put("error", error)
        }
        val js = "window.onIlbbangNativeVoiceResult && " +
            "window.onIlbbangNativeVoiceResult($result);"
        webView?.post {
            val browser = webView
            if (browser == null) {
                Log.e(TAG, "최종 결과 전달 실패: WebView가 없습니다. requestId=$requestId")
            } else {
                browser.evaluateJavascript(js) { value ->
                    Log.d(TAG, "최종 결과 JS 실행 결과: requestId=$requestId, jsResult=$value")
                }
            }
        }
    }

    private fun releaseRecorder() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        recordingActive = false
        recordingFile?.delete()
        recordingFile = null
    }

    private fun showError(message: String) {
        val text = TextView(this).apply {
            this.text = message
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(40, 60, 40, 40)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(text)
        }
        setContentView(layout)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        val browser = webView
        if (browser?.canGoBack() == true) browser.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        // Activity가 종료될 때 네이티브 요청/마이크/파일 잠금을 남기지 않습니다.
        if (pendingRequestId.isNotEmpty()) cancelNativeVoice(pendingRequestId)
        processingWatchdog?.let { handler.removeCallbacks(it) }
        processingWatchdog = null
        processingJob?.cancel()
        processingJob = null
        releaseRecorder()
        webView?.removeJavascriptInterface("IlbbangAndroid")
        webView?.destroy()
        webView = null
        try { textToSpeech?.stop() } catch (_: Exception) {}
        try { textToSpeech?.shutdown() } catch (_: Exception) {}
        textToSpeech = null
        ttsReady = false
        super.onDestroy()
    }
}
