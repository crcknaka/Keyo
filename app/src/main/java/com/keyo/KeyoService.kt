package com.keyo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Path
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

class KeyoService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val audioRecorder = AudioRecorder()
    private val handler = Handler(Looper.getMainLooper())
    // System-dictation fallback: used when there is no Groq key or no network, so voice typing
    // keeps working offline (on-device recognition with downloaded language packs).
    private val offlineDictation by lazy { OfflineDictation(this) }
    private var offlineSession = false
    // Live dictation: while recording, periodically transcribe the audio-so-far and show it as a
    // growing composing region (the final commitText on release replaces it). One transcription in
    // flight at a time (liveBusy). Off unless KeyboardPrefs.isLiveDictation.
    private var liveBusy = false
    private val liveDictationTick = object : Runnable {
        override fun run() {
            if (!audioRecorder.isActive()) return
            if (!liveBusy) {
                val f = File(cacheDir, "voice_live.wav")
                if (audioRecorder.snapshot(f)) {
                    liveBusy = true
                    GroqApi.transcribe(f) { text, _ ->
                        handler.post {
                            liveBusy = false
                            if (audioRecorder.isActive() && !text.isNullOrBlank()) {
                                try { currentInputConnection?.setComposingText(text, 1) } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
            handler.postDelayed(this, 1600)
        }
    }
    // One lifecycle-scoped coroutine scope for the whole service. Cancelled in onDestroy() so no
    // launched job (long-press timers, AI/voice callbacks) can outlive the keyboard and touch
    // destroyed Compose state.
    private val serviceJob = kotlinx.coroutines.SupervisorJob()
    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        serviceJob + kotlinx.coroutines.Dispatchers.Main.immediate
    )

    private var currentLang = mutableStateOf("en")          // "en", "ru", "lv"
    private var enabledLangs = mutableStateOf(listOf("en", "ru"))
    private var isRecording = mutableStateOf(false)
    private var isRecordingAI = mutableStateOf(false)
    private var statusText = mutableStateOf("")
    private var isShift = mutableStateOf(false)
    private var keyboardMode = mutableStateOf("abc") // "abc", "123", "symbols", "numpad"
    private var imeActionId = mutableIntStateOf(android.view.inputmethod.EditorInfo.IME_ACTION_NONE)
    // Multi-line fields (and fields that ask for no enter action) get a real newline Enter, like Gboard.
    private var fieldMultiline = mutableStateOf(false)
    private var fieldNoEnterAction = mutableStateOf(false)
    private var showNumberRow = mutableStateOf(true)
    // Visual sizing (tunable live via the Settings sliders)
    private var keyHeightDp = mutableIntStateOf(KeyboardPrefs.DEFAULT_KEY_HEIGHT)
    private var keyHGapDp = mutableIntStateOf(KeyboardPrefs.DEFAULT_HGAP)
    private var keyVGapDp = mutableIntStateOf(KeyboardPrefs.DEFAULT_VGAP)
    private var bottomOffsetDp = mutableIntStateOf(KeyboardPrefs.DEFAULT_BOTTOM_OFFSET)
    private var hapticStrength = mutableStateOf("light")    // off / light / medium / strong
    private var currentTheme = KeyboardPrefs.KeyboardTheme("catppuccin","",0xFF1E1E2E,0xFF313244,0xFFBB86FC,0xFFCDD6F4)
    private var soundEnabled = mutableStateOf(false)
    private var themeId = mutableStateOf("catppuccin")

    // True only for PASSWORD fields. In these we never send anything to the network (no voice
    // transcription, no AI), show no suggestions and use no composing region.
    private var secureField = false
    // True for password OR no-personalized-learning fields (e.g. Chrome incognito). Here we must not
    // PERSIST anything personal — no learning into the dictionary, no clipboard history — but voice,
    // AI, suggestions and glide are still allowed (incognito isn't a password).
    private var noLearn = false
    // True for fields where smart typing makes no sense and Gboard turns it off too: email / URI /
    // filter variations, fields with TYPE_TEXT_FLAG_NO_SUGGESTIONS, and non-text classes (number /
    // phone / datetime). Disables suggestions, autocorrect, composing, glide and auto-capitalize —
    // so "ivan.petrov@…" is never "corrected" and logins don't get capitalized.
    private var fieldNoSuggestions = false
    // The raw inputType of the current field; getCursorCapsMode reads its CAP_* flags.
    private var fieldInputType = 0

    // Cached hot-path settings (refreshed in reloadPrefs) to avoid SharedPreferences reads per keystroke.
    private var doubleSpaceOn = true
    private var autoCapOn = true
    private var suggestionsOn = true
    private var autocorrectTypingOn = false
    private var swipeTypingOn = true

    // ---- Glide (swipe) typing state ----
    // Letter key bounds in window coordinates (populated as the abc layout lays out), the live trail
    // points (window coords) for the on-screen line, and whether a glide is currently in progress
    // (read by KeyButton to suppress its long-press accent popup while sliding).
    private val keyBounds = HashMap<Char, Rect>()       // window coordinates
    private var glideOrigin = Offset.Zero               // window position of the key-grid container
    private val glideTrail = mutableStateListOf<Offset>()  // container-local coords; drawn by the Canvas
    private val glideActive = mutableStateOf(false)
    // The word a just-finished glide inserted (followed by an auto-space). While set, the strip shows
    // [glideAlts] so a wrong guess is one tap away, Backspace undoes the whole glide, and the next
    // sentence punctuation pulls back onto the word. Cleared as soon as you type past it / move away.
    private var glideWord: String? = null
    private var glideAlts: List<String> = emptyList()
    // Set when we auto-insert a trailing space (after a suggestion tap). The next sentence
    // punctuation pulls that space back so it attaches to the word ("word " + "." -> "word.").
    private var pendingAutoSpace = false
    // Our own suggestion commit produces ONE selection update which we must NOT treat as the user
    // moving the caret; this one-shot flag absorbs it. Any later caret move invalidates pendingAutoSpace
    // so punctuation can't pull/eat a space at an unrelated cursor position.
    private var autoSpaceSkipNextSel = false
    // The space bar commits on finger-UP (so hold=dictate / swipe=cursor can override). When typing
    // fast the NEXT letter's key-DOWN can arrive before that UP, which used to glue the letter onto the
    // previous word ("чау как" -> "чаук ак"). So a space tap is marked "pending" on key-down and the
    // next character flushes it first, keeping the real key order.
    private var pendingSpaceTap = false
    // Live glide preview: the current best guess shown above the keys while the finger is still
    // swiping (Gboard-style). Throttled: one background decode in flight, every ~6 new points.
    private val glidePreview = mutableStateOf<String?>(null)
    private var glidePreviewBusy = false
    private var glidePreviewAt = 0

    private var clipHistory = mutableStateOf<List<String>>(emptyList())
    private var pinnedClips = mutableStateOf<List<String>>(emptyList())
    private var emojiCategory = mutableIntStateOf(0)        // emoji panel category
    private var isCapsLock = mutableStateOf(false)
    private var lastShiftTapMs = 0L
    private var shiftIsAuto = false   // true when Shift was turned on by auto-capitalize (not the user)
    // Undo last AI rewrite
    private var showUndoRewrite = mutableStateOf(false)
    private var rewriteBackup = ""
    private var rewriteResult = ""
    private val hideUndoRewrite = Runnable { showUndoRewrite.value = false }
    // Dynamic top toolbar: a freshly-copied snippet shows as a quick-paste chip for a while.
    private var showClipChip = mutableStateOf(false)
    private var lastClip = mutableStateOf("")
    private val hideClipChip = Runnable { showClipChip.value = false }

    // Track C — live suggestion strip. When the user is typing a word we show suggestions here;
    // when idle the same row shows the toolbar menu. `toolbarPinned` lets the user open the menu
    // while typing (via a small chevron) and is auto-reset once the row goes idle.
    private var suggestions = mutableStateOf<List<String>>(emptyList())
    private var primarySuggestion = mutableStateOf<String?>(null)
    private var toolbarPinned = mutableStateOf(false)
    private val saveUserDict = Runnable { UserDictionary.save(this) }

    // Track D — confirmation prompt for consequential AI actions (call / SMS). When set, a bar with
    // Confirm / Cancel appears; the assistant's tool loop suspends on `confirmDeferred` until tapped.
    private var pendingConfirm = mutableStateOf<String?>(null)
    private var confirmDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    private val clipboardManager: android.content.ClipboardManager? by lazy {
        getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    }
    // Record copied text into the clipboard-history panel (skipped in password / incognito fields).
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        if (!noLearn) {
            try {
                val text = clipboardManager?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                if (!text.isNullOrBlank()) {
                    KeyboardPrefs.addClip(this, text)
                    handler.post {
                        clipHistory.value = KeyboardPrefs.getClipHistory(this)
                        lastClip.value = text
                        showClipChip.value = true
                        handler.removeCallbacks(hideClipChip)
                        handler.postDelayed(hideClipChip, 25_000) // auto-hide the chip
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // Reapply settings live while the user drags the size sliders in the Settings screen.
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { reloadPrefs() }
    }

    private fun reloadPrefs() {
        GroqApi.model = KeyboardPrefs.getModel(this)
        GroqApi.aiModel = KeyboardPrefs.getAiModel(this)
        showNumberRow.value = KeyboardPrefs.isNumberRowEnabled(this)
        keyHeightDp.intValue = KeyboardPrefs.getKeyHeight(this)
        keyHGapDp.intValue = KeyboardPrefs.getHGap(this)
        keyVGapDp.intValue = KeyboardPrefs.getVGap(this)
        bottomOffsetDp.intValue = KeyboardPrefs.getBottomOffset(this)
        hapticStrength.value = KeyboardPrefs.getHapticStrength(this)
        soundEnabled.value = KeyboardPrefs.isSoundEnabled(this)
        themeId.value = KeyboardPrefs.getTheme(this)
        val langsBefore = enabledLangs.value
        enabledLangs.value = KeyboardPrefs.getEnabledLanguages(this)
        // If the user disabled the currently-active language in Settings, switch to a still-enabled
        // one immediately so the layout doesn't keep showing a disabled language until field restart.
        if (enabledLangs.value.isNotEmpty() && currentLang.value !in enabledLangs.value) {
            currentLang.value = enabledLangs.value.first()
            KeyboardPrefs.setCurrentLanguage(this, currentLang.value)
        }
        if (enabledLangs.value != langsBefore) {
            // A language was just enabled in Settings — load its dictionary in the background.
            val langs = enabledLangs.value + currentLang.value
            serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                SuggestionEngine.ensureLoaded(this@KeyoService, langs)
            }
        }
        KeyboardPrefs.migratePhrasesToPinned(this)   // old quick-phrases -> pinned clips (one-time)
        clipHistory.value = KeyboardPrefs.getClipHistory(this)
        pinnedClips.value = KeyboardPrefs.getPinned(this)
        recentEmoji.value = KeyboardPrefs.getRecentEmoji(this)
        doubleSpaceOn = KeyboardPrefs.isDoubleSpacePeriod(this)
        autoCapOn = KeyboardPrefs.isAutoCap(this)
        suggestionsOn = KeyboardPrefs.isSuggestionsEnabled(this)
        autocorrectTypingOn = KeyboardPrefs.isAutocorrectTyping(this)
        swipeTypingOn = KeyboardPrefs.isSwipeTyping(this)
    }

    // Max content rows any mode shows — keeps the keyboard a fixed height so it never
    // jumps when switching between abc / 123 / symbols / numpad.
    private val maxContentRows = 4

    private val vibrator: android.os.Vibrator? by lazy {
        @Suppress("DEPRECATION")
        getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        reloadPrefs()
        KeyboardPrefs.registerChangeListener(this, prefListener)
        clipboardManager?.addPrimaryClipChangedListener(clipListener)
        // Load the user's learned vocabulary + ONLY the enabled languages' dictionaries off the
        // main thread (lazy per-language: an unused language's multi-MB word list + bigram model
        // never gets parsed into RAM). Newly enabled languages load via reloadPrefs.
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            UserDictionary.ensureLoaded(this@KeyoService)
            SuggestionEngine.ensureLoaded(this@KeyoService, enabledLangs.value + currentLang.value)
        }
        // The first Compose composition of the whole keyboard takes hundreds of ms and normally
        // happens INSIDE the first show-IME transaction, blocking this process's main thread while
        // WindowManager waits for the IME window to lay out. On slower devices the system's show
        // retries can all time out, and the client app is left believing the IME is visible — after
        // that every further show request (even a tap on the field) is dropped as redundant and the
        // keyboard never appears in that app until it restarts (seen with WhatsApp's auto-focused
        // two-step-verification PIN prompt). The service is created when the IME is bound (process
        // start / package update), well before any show request, so paying the composition cost here
        // keeps the actual show transaction fast.
        handler.post { prewarmKeyboardUi() }
    }

    /** Compose the keyboard once in a detached throwaway view (loads + JITs Compose and all our
     *  layout code), then pre-build the real input view so the first show only attaches it. */
    private fun prewarmKeyboardUi() {
        if (isShowInputRequested) {
            // The service was cold-started by a show request that is already being processed —
            // the real composition is about to run, so a warm-up pass would only delay it.
            return
        }
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            val recomposer = androidx.compose.runtime.Recomposer(serviceScope.coroutineContext)
            val warm = ComposeView(this).apply {
                setParentCompositionContext(recomposer)
                setContent { KeyboardLayout() }
            }
            warm.measure(
                View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            warm.disposeComposition()
            recomposer.close()
            android.util.Log.i("Keyo", "compose prewarm done in ${android.os.SystemClock.elapsedRealtime() - t0}ms")
        } catch (e: Throwable) {
            android.util.Log.w("Keyo", "compose prewarm skipped", e)
        }
        // Hand the framework a ready-made input view: showWindow() skips onCreateInputView and the
        // (now warm) composition runs immediately on attach. After a config change the framework
        // drops this view and calls onCreateInputView again, which rebuilds it the usual way.
        try {
            setInputView(onCreateInputView())
        } catch (e: Throwable) {
            android.util.Log.w("Keyo", "input view pre-create skipped", e)
        }
    }

    private fun installLifecycleOwnerOnDecorView() {
        try {
            window?.window?.decorView?.let { decorView ->
                decorView.setViewTreeLifecycleOwner(this@KeyoService)
                decorView.setViewTreeSavedStateRegistryOwner(this@KeyoService)
            }
        } catch (_: Exception) {}
    }

    override fun onInitializeInterface() {
        super.onInitializeInterface()
        installLifecycleOwnerOnDecorView()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        installLifecycleOwnerOnDecorView()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        installLifecycleOwnerOnDecorView()

        return try {
            // Use a FrameLayout wrapper that sets lifecycle owner on attach
            // This ensures the owner is available when Compose walks up the tree
            val wrapper = object : android.widget.FrameLayout(this) {
                override fun onAttachedToWindow() {
                    // Set lifecycle on ALL ancestors before Compose sees them
                    var v: View? = this
                    while (v != null) {
                        v.setViewTreeLifecycleOwner(this@KeyoService)
                        v.setViewTreeSavedStateRegistryOwner(this@KeyoService)
                        v = v.parent as? View
                    }
                    super.onAttachedToWindow()
                }
            }

            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@KeyoService)
                setViewTreeSavedStateRegistryOwner(this@KeyoService)
                setContent {
                    KeyboardLayout()
                }
            }

            wrapper.addView(composeView, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            wrapper
        } catch (e: Exception) {
            android.util.Log.e("Keyo", "ComposeView failed, using fallback", e)
            android.widget.TextView(this).apply {
                text = "Keyboard error: ${e.message}\nPlease restart"
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#1E1E2E"))
                setPadding(32, 32, 32, 32)
            }
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        // Load user preferences
        reloadPrefs()
        currentLang.value = KeyboardPrefs.getCurrentLanguage(this)
        // Store IME action for enter key behavior
        imeActionId.intValue = info?.imeOptions?.and(android.view.inputmethod.EditorInfo.IME_MASK_ACTION)
            ?: android.view.inputmethod.EditorInfo.IME_ACTION_NONE
        // Multi-line text fields should insert a newline on Enter (Gboard behavior), not fire an action.
        val it0 = info?.inputType ?: 0
        fieldMultiline.value = (it0 and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_TEXT &&
            (it0 and (android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE)) != 0
        fieldNoEnterAction.value = ((info?.imeOptions ?: 0) and
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

        // Detect password / incognito fields — disable all network features there.
        val inputType = info?.inputType ?: 0
        val cls = inputType and android.text.InputType.TYPE_MASK_CLASS
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        val isPassword =
            (cls == android.text.InputType.TYPE_CLASS_TEXT && (
                variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == android.text.InputType.TYPE_CLASS_NUMBER &&
                variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val noLearning = ((info?.imeOptions ?: 0) and
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        secureField = isPassword              // blocks voice/AI/suggestions/composing (passwords only)
        noLearn = isPassword || noLearning    // blocks personal persistence (also incognito)
        fieldInputType = inputType
        // Fields where Gboard also turns smart typing off: email / URI / filter text variations,
        // explicit TYPE_TEXT_FLAG_NO_SUGGESTIONS, and non-text classes (number/phone/datetime/null).
        val varNoSug = cls == android.text.InputType.TYPE_CLASS_TEXT && (
            variation == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_URI ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_FILTER)
        fieldNoSuggestions = varNoSug ||
            (inputType and android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0 ||
            cls != android.text.InputType.TYPE_CLASS_TEXT

        isShift.value = false
        isCapsLock.value = false
        shiftIsAuto = false
        showUndoRewrite.value = false
        composing.clear()   // fresh field: never carry a composing word across inputs
        recentTaps.clear()
        pendingTapPos = null
        autocorrectUndo = null
        revertedWords.clear()
        glideWord = null
        glideAlts = emptyList()
        pendingAutoSpace = false
        autoSpaceSkipNextSel = false
        pendingSpaceTap = false
        setSuggestions(emptyList(), null)
        confirmDeferred?.complete(false); confirmDeferred = null; pendingConfirm.value = null
        // Open in the layout the field asks for (Gboard behaviour): number/date fields get the
        // numpad, phone fields the symbol page (has + * # ( ) -), everything else the letters.
        keyboardMode.value = when (cls) {
            android.text.InputType.TYPE_CLASS_NUMBER,
            android.text.InputType.TYPE_CLASS_DATETIME -> "numpad"
            android.text.InputType.TYPE_CLASS_PHONE -> "123"
            else -> "abc"
        }
        syncImeSubtype(currentLang.value)   // best-effort: keep the OS subtype on our language
        maybeAutoCapitalize()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // The field is going away: finalize any composing word in place (so it isn't silently dropped
        // or carried over) and clear transient editing state. onStartInputView re-initializes
        // everything when input resumes.
        if (composing.isNotEmpty()) {
            try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}
            composing.clear()
        }
        autocorrectUndo = null
        glideWord = null
        glideAlts = emptyList()
        pendingTapPos = null
        pendingAutoSpace = false
        autoSpaceSkipNextSel = false
        pendingSpaceTap = false
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        // Portrait: stay non-fullscreen so the app shows above the keyboard.
        // Landscape: go fullscreen so a text line (extract view) appears above the keys,
        // like Gboard — otherwise the keyboard would cover the whole field.
        return resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // Ensure the visible insets match content insets
        // This tells apps to resize their content to make room for the keyboard
        if (isInputViewShown) {
            outInsets.contentTopInsets = outInsets.visibleTopInsets
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
        }
    }

    override fun onDestroy() {
        KeyboardPrefs.unregisterChangeListener(this, prefListener)
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        // Cancel pending delayed work and all launched coroutines so nothing fires after teardown.
        handler.removeCallbacksAndMessages(null)
        offlineDictation.cancel()
        UserDictionary.save(this)
        confirmDeferred?.complete(false); confirmDeferred = null
        serviceJob.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    @Composable
    fun KeyboardLayout() {
        val lang by currentLang
        val status by statusText
        val shift by isShift
        val mode by keyboardMode
        val numberRow by showNumberRow
        val keyHeight = keyHeightDp.intValue.dp

        // Read the theme reactively so changing it in settings applies on the next keyboard open
        val theme = KeyboardPrefs.THEMES.find { it.id == themeId.value } ?: KeyboardPrefs.THEMES[0]
        currentTheme = theme
        val bgColor = Color(theme.bg)
        val keyColor = Color(theme.key)
        val accentColor = Color(theme.accent)
        val textColor = Color(theme.text)
        val recordColor = Color(theme.record)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                // targetSdk 35 enforces edge-to-edge: without this the bottom key row slides under
                // the system navigation area (gesture pill / hide-keyboard & IME-switcher buttons).
                // The background stays painted behind the bar; only the keys are lifted above it.
                // On top of the automatic inset, a user-tunable bottom offset (Appearance →
                // Keyboard size) raises the keys further clear of the system buttons.
                .navigationBarsPadding()
                .padding(start = 2.dp, end = 2.dp, top = 4.dp,
                         bottom = (4 + bottomOffsetDp.intValue).dp)
        ) {
            // Dynamic top toolbar (Gboard-style). Priority: status > quick-paste chip > icons.
            TopToolbar(status, textColor, accentColor)

            // Confirmation bar for consequential AI actions (call / SMS).
            val confirmText by pendingConfirm
            confirmText?.let { summary ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        summary, color = textColor, fontSize = 13.sp,
                        maxLines = 2, modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    Box(
                        modifier = Modifier
                            .background(keyColor, RoundedCornerShape(8.dp))
                            .clickable { resolveConfirm(false) }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) { Text("Cancel", color = textColor, fontSize = 13.sp) }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(accentColor, RoundedCornerShape(8.dp))
                            .clickable { resolveConfirm(true) }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) { Text("Confirm", color = Color.Black, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
                }
            }

            if (mode == "emoji") {
                EmojiPanel(keyHeight, keyColor, textColor, accentColor)
            } else if (mode == "clipboard") {
                ClipboardPanel(keyHeight, keyColor, textColor, accentColor)
            } else if (mode == "rewrite") {
                RewritePanel(keyHeight, keyColor, textColor, accentColor)
            } else {

            // Key content area — fixed height (maxContentRows tall) so switching between
            // abc / 123 / symbols / numpad never resizes the keyboard. Rows sit at the
            // bottom, flush against the function row. Wrapped in a Box so one overlay can track
            // glide (swipe) gestures across all letter keys and draw the trail on top. The overlay
            // only OBSERVES pointer events (never consumes), so normal tap typing is unaffected.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { glideOrigin = it.positionInWindow() }
                    .pointerInput(lang, mode, swipeTypingOn) {
                        val threshold = 16.dp.toPx()
                        awaitPointerEventScope {
                            while (true) {
                                // Initial pass: parents see the event BEFORE children. The KeyButton
                                // underneath commits its letter during the Main pass (child-first),
                                // so pendingTapPos must be set here, in Initial — otherwise every
                                // recorded tap point would belong to the PREVIOUS keystroke.
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial
                                )
                                glideActive.value = false
                                val start = down.position + glideOrigin
                                // Record the touch point of a letter tap (in window coords) for the
                                // coordinate spatial autocorrect — even when glide typing is off.
                                val onLetter = mode == "abc" && !secureField && !fieldNoSuggestions && keyAt(start) != null
                                if (onLetter) pendingTapPos = start
                                // Glide only on the letter layout, when enabled, starting on a letter.
                                if (!onLetter || !swipeTypingOn) {
                                    var c = down
                                    while (c.pressed) { c = awaitPointerEvent().changes.firstOrNull() ?: break }
                                    continue
                                }
                                val points = ArrayList<Offset>(); points.add(down.position)
                                val crossed = HashSet<Char>(); keyAt(start)?.let { crossed.add(it) }
                                var glide = false
                                while (true) {
                                    val ch = awaitPointerEvent().changes.firstOrNull() ?: break
                                    if (!ch.pressed) break
                                    points.add(ch.position)
                                    nearestKey(ch.position + glideOrigin)?.let { crossed.add(it) }
                                    if (!glide) {
                                        if ((ch.position - down.position).getDistance() > threshold && crossed.size >= 2) {
                                            glide = true; glideActive.value = true
                                            pendingTapPos = null   // a glide, not a tap — drop the captured point
                                            glideTrail.clear(); glideTrail.addAll(points)
                                        }
                                    } else {
                                        glideTrail.add(ch.position)
                                        glidePreviewTick(points)
                                    }
                                }
                                if (glide) commitGlide(points)
                                glideTrail.clear()
                                glidePreview.value = null
                                glidePreviewAt = 0
                            }
                        }
                    }
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(keyHeight * maxContentRows),
                verticalArrangement = Arrangement.Bottom
            ) {
            when (mode) {
                "abc" -> {
                    // Number row (toggleable)
                    if (numberRow) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            "1234567890".forEach { digit ->
                                KeyButton(digit.toString(), keyColor, textColor, Modifier.weight(1f)) {
                                    commitText(digit.toString())
                                }
                            }
                        }
                    }

                    // Russian uses a Cyrillic layout; English and Latvian share QWERTY
                    // (Latvian diacritics ā č ē ģ ī ķ ļ ņ š ū ž are on long-press alternates).
                    val ruRows = listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
                    val enRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
                    val rows = if (lang == "ru") ruRows else enRows

                    rows.forEachIndexed { index, row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            val caps by isCapsLock
                            if (index == 2) {
                                ShiftKey(
                                    active = shift || caps,
                                    locked = caps,
                                    keyColor = keyColor,
                                    accentColor = accentColor,
                                    iconColor = if (shift || caps) Color.Black else textColor,
                                    height = keyHeight,
                                    modifier = Modifier.weight(1.3f)
                                ) { onShiftTap() }
                            }
                            row.forEach { char ->
                                val up = shift || caps
                                val displayChar = if (up) char.uppercase() else char.toString()
                                KeyButton(
                                    displayChar, keyColor, textColor,
                                    Modifier.weight(1f).onGloballyPositioned { keyBounds[char] = it.boundsInWindow() }
                                ) {
                                    commitChar(if (up) char.uppercaseChar() else char)
                                    if (shift && !caps) { isShift.value = false; shiftIsAuto = false }
                                }
                            }
                            if (index == 2) {
                                BackspaceKey(keyColor, textColor, Modifier.weight(1.3f))
                            }
                        }
                    }
                }
                "123" -> {
                    val numRows = listOf(
                        listOf("1","2","3","4","5","6","7","8","9","0"),
                        listOf("@","#","$","_","&","-","+","(",")","/"),
                        null // handled separately for special keys
                    )
                    // Row 1 — digits
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        numRows[0]!!.forEach { s ->
                            KeyButton(s, keyColor, textColor, Modifier.weight(1f)) { commitText(s) }
                        }
                    }
                    // Row 2 — quick emoji (smiles, hearts, animals, euro)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("😀","😂","🥰","😍","👍","❤️","🔥","🐶","🐱","€").forEach { e ->
                            KeyButton(e, keyColor, textColor, Modifier.weight(1f)) { commitText(e) }
                        }
                    }
                    // Row 3 — punctuation/symbols
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        numRows[1]!!.forEach { s ->
                            KeyButton(s, keyColor, textColor, Modifier.weight(1f)) { commitText(s) }
                        }
                    }
                    // Row 3: =\< symbols backspace
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        KeyButton("!?#", accentColor, Color.Black, Modifier.weight(1.3f), fontSize = modeKeyFont()) {
                            keyboardMode.value = "symbols"
                        }
                        listOf("*","\"","'",":",";","!","?").forEach { s ->
                            KeyButton(s, keyColor, textColor, Modifier.weight(1f)) { commitText(s) }
                        }
                        BackspaceKey(keyColor, textColor, Modifier.weight(1.3f))
                    }
                }
                "symbols" -> {
                    val symRows = listOf(
                        listOf("~","`","|","·","√","π","τ","÷","×","="),
                        listOf("©","®","™","℅","[","]","{","}","<",">"),
                        null
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        symRows[0]!!.forEach { s ->
                            KeyButton(s, keyColor, textColor, Modifier.weight(1f)) { commitText(s) }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        symRows[1]!!.forEach { s ->
                            KeyButton(s, keyColor, textColor, Modifier.weight(1f)) { commitText(s) }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        KeyButton("123", accentColor, Color.Black, Modifier.weight(1.3f), fontSize = modeKeyFont()) {
                            keyboardMode.value = "123"
                        }
                        listOf("°","•","○","●","□","■","♥","♦").forEach { s ->
                            KeyButton(s, keyColor, textColor, Modifier.weight(1f)) { commitText(s) }
                        }
                        BackspaceKey(keyColor, textColor, Modifier.weight(1.3f))
                    }
                }
                "numpad" -> {
                    // Phone-style pad (Gboard-like): big plain digits 1-2-3 on top, a helper
                    // column on the right (backspace + the symbols number fields actually need).
                    // Space / Enter / ABC live on the shared bottom row as usual.
                    val digitFont = (KeyboardPrefs.fontSizeSp(keyHeightDp.intValue, keyVGapDp.intValue) * 1.3f).sp
                    val padRows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("*", "0", "#")
                    )
                    padRows.forEachIndexed { i, row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { s ->
                                val isDigit = s[0].isDigit()
                                KeyButton(
                                    s, keyColor, textColor, Modifier.weight(1f),
                                    fontSize = if (isDigit) digitFont else KeyboardPrefs.fontSizeSp(keyHeightDp.intValue, keyVGapDp.intValue).sp
                                ) { commitText(s) }
                            }
                            when (i) {
                                0 -> BackspaceKey(keyColor, textColor, Modifier.weight(1f))
                                1 -> KeyButton("-", keyColor, textColor, Modifier.weight(1f)) { commitText("-") }
                                2 -> KeyButton("+", keyColor, textColor, Modifier.weight(1f)) { commitText("+") }
                                3 -> KeyButton(".", keyColor, textColor, Modifier.weight(1f)) { commitChar('.') }
                            }
                        }
                    }
                }
            }
            } // end fixed-height key content area
            // Glide trail — drawn on top of the keys. The Canvas has no pointerInput, so it never
            // intercepts touches; key taps still land normally.
            Canvas(Modifier.matchParentSize()) {
                val t = glideTrail
                if (t.size > 1) {
                    val p = Path()
                    p.moveTo(t[0].x, t[0].y)
                    for (i in 1 until t.size) p.lineTo(t[i].x, t[i].y)
                    drawPath(
                        p, color = accentColor.copy(alpha = 0.5f),
                        style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
            // Live glide preview — the current best guess floats above the keys while swiping.
            if (glideActive.value) glidePreview.value?.let { guess ->
                Box(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
                        .background(accentColor, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(guess, color = Color.Black, fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
            }
            } // end glide Box

            // Bottom row — shared across all modes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Mode / lang toggle button (bottom-left)
                when (mode) {
                    "abc" -> {
                        KeyButton("123", accentColor, Color.Black, Modifier.weight(1.2f), fontSize = modeKeyFont()) {
                            keyboardMode.value = "123"
                        }
                    }
                    "123", "symbols", "numpad" -> {
                        KeyButton("ABC", accentColor, Color.Black, Modifier.weight(1.2f), fontSize = modeKeyFont()) {
                            keyboardMode.value = "abc"
                        }
                    }
                }

                // Comma — long press starts AI assistant (🤖)
                val recordingAI by isRecordingAI
                var aiCancelled by remember { mutableStateOf(false) }
                var aiDragX by remember { mutableFloatStateOf(0f) }
                var aiPressed by remember { mutableStateOf(false) }
                val cancelThreshold = 100f

                Box(
                    modifier = Modifier
                        .weight(0.9f)
                        .height(keyHeight)
                        .semantics { contentDescription = "AI assistant. Long press and speak a task" }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                aiCancelled = false
                                aiDragX = 0f
                                aiPressed = true
                                var longPressed = false

                                val longPressJob = serviceScope.launch {
                                    kotlinx.coroutines.delay(400)
                                    longPressed = true
                                    startAIRecording()
                                    performKeyFeedback()
                                }

                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        if (!change.pressed) {
                                            change.consume()
                                            break
                                        }
                                        if (longPressed) {
                                            aiDragX = change.position.x - down.position.x
                                            if (aiDragX < -cancelThreshold) aiCancelled = true
                                        }
                                        change.consume()
                                    }
                                } catch (_: kotlinx.coroutines.CancellationException) {}

                                longPressJob.cancel()
                                aiPressed = false
                                if (longPressed) {
                                    if (aiCancelled) cancelAIRecording() else stopAIRecording()
                                    aiDragX = 0f
                                } else {
                                    performKeyFeedback()
                                    commitChar(',')
                                }
                            }
                        }
                        .padding(horizontal = keyHGapDp.intValue.dp, vertical = keyVGapDp.intValue.dp)
                        .background(
                            when {
                                recordingAI && aiDragX < -cancelThreshold -> Color(0xFF666666)
                                recordingAI -> Color(0xFF50FA7B)
                                aiPressed -> lerp(keyColor, Color.Black, 0.22f)
                                else -> keyColor
                            },
                            RoundedCornerShape(6.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        recordingAI && aiDragX < -cancelThreshold ->
                            Text("✕", fontSize = 16.sp, color = Color.Black)
                        recordingAI ->
                            SparkleGlyph(Color.Black, Modifier.size(20.dp))
                        else -> {
                            Text(",", fontSize = 18.sp, color = textColor)
                            SparkleGlyph(textColor, Modifier.align(Alignment.TopEnd).padding(2.dp).size(13.dp))
                        }
                    }
                }

                // Right of the comma: globe = switch language (abc); mode hint otherwise
                when (mode) {
                    "abc" -> {
                        LanguageKey(keyColor, textColor, keyHeight, Modifier.weight(1f)) {
                            cycleLanguage()
                        }
                    }
                    "123" -> {
                        KeyButton("!?#", keyColor, textColor, Modifier.weight(1f), fontSize = modeKeyFont()) {
                            keyboardMode.value = "symbols"
                        }
                    }
                    "symbols" -> {
                        KeyButton("🔢", keyColor, textColor, Modifier.weight(1f), fontSize = modeKeyFont()) {
                            keyboardMode.value = "numpad"
                        }
                    }
                    "numpad" -> {
                        KeyButton("123", keyColor, textColor, Modifier.weight(1f), fontSize = modeKeyFont()) {
                            keyboardMode.value = "123"
                        }
                    }
                }

                // Space — shared component (tap = space, hold = dictate, swipe = cursor slider).
                // The label names every language sharing the active layout, so the bilingual Latin
                // keyboard reads "EN+LV" when both are enabled (just "EN"/"RU"/"LV" otherwise).
                val spaceLabel = if (mode == "abc") dictLangs().joinToString("+") { it.uppercase() } else "space"
                SpaceKey(Modifier.weight(3.5f), spaceLabel, keyColor, textColor, accentColor, recordColor)

                // Period — long press shows ? , ! -
                KeyButton(".", keyColor, textColor, Modifier.weight(0.9f)) { commitChar('.') }

                // Enter / Submit — Gboard-style: a real Enter (newline) by default, an action
                // icon only when the field explicitly asks for one (search/send/go/next).
                val imeAction by imeActionId
                val multiline by fieldMultiline
                val noEnterAction by fieldNoEnterAction
                val isTextMode = mode == "123" || mode == "symbols" || mode == "numpad"
                // Treat the key as a plain newline Enter in text/symbol modes, multi-line fields,
                // or when the field opts out of the enter action.
                val plainEnter = isTextMode || multiline || noEnterAction
                val hasImeAction = !plainEnter &&
                        imeAction != android.view.inputmethod.EditorInfo.IME_ACTION_NONE &&
                        imeAction != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED

                val enterKind = when {
                    !hasImeAction -> "return"
                    else -> when (imeAction) {
                        android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH -> "search"
                        android.view.inputmethod.EditorInfo.IME_ACTION_SEND -> "send"
                        android.view.inputmethod.EditorInfo.IME_ACTION_GO -> "send"
                        android.view.inputmethod.EditorInfo.IME_ACTION_NEXT -> "next"
                        // IME_ACTION_DONE and anything else fall back to the familiar Enter arrow.
                        else -> "return"
                    }
                }

                EnterKey(enterKind, accentColor, Color.Black, keyHeight, Modifier.weight(1.0f)) {
                    if (!hasImeAction) {
                        commitChar('\n')
                    } else {
                        // Autocorrect the last word like a space would — Gboard also sends the
                        // corrected text on Send/Search. finalizeComposing() is the safety net and
                        // disarms backspace-revert (the text is leaving the field).
                        finishWord()
                        finalizeComposing()
                        currentInputConnection?.performEditorAction(imeAction)
                    }
                }
            }
            } // end else (normal keyboard layout)
        }
    }

    // Dynamic top toolbar (Gboard-style): status text, a quick-paste chip, or action icons.
    @Composable
    fun TopToolbar(status: String, textColor: Color, accentColor: Color) {
        val chip by showClipChip
        val clip by lastClip
        val undo by showUndoRewrite
        val sugg by suggestions
        val primary by primarySuggestion
        val pinned by toolbarPinned
        Box(
            modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            when {
                status.isNotEmpty() -> Text(
                    status, color = accentColor, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                )
                // While typing (and the menu isn't pinned open) the row shows live suggestions.
                sugg.isNotEmpty() && !pinned -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    sugg.forEachIndexed { i, s ->
                        if (i > 0) Box(
                            Modifier.width(1.dp).height(20.dp).background(textColor.copy(alpha = 0.15f))
                        )
                        val isPrimary = s == primary
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .semantics { contentDescription = "Insert $s" }
                                .clickable { applySuggestion(s) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                s,
                                color = if (isPrimary) accentColor else textColor,
                                fontSize = 15.sp,
                                fontWeight = if (isPrimary) androidx.compose.ui.text.font.FontWeight.SemiBold
                                             else androidx.compose.ui.text.font.FontWeight.Normal,
                                maxLines = 1
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.size(34.dp)
                            .semantics { contentDescription = "Open toolbar" }
                            .clickable { toolbarPinned.value = true },
                        contentAlignment = Alignment.Center
                    ) { Text("⋯", color = textColor.copy(alpha = 0.6f), fontSize = 18.sp) }
                }
                undo -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .semantics { contentDescription = "Undo rewrite" }
                            .clickable { performKeyFeedback(); performUndoRewrite() },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text("↩  Undo rewrite", color = accentColor, fontSize = 13.sp,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                    Box(
                        modifier = Modifier.size(36.dp).clickable { showUndoRewrite.value = false },
                        contentAlignment = Alignment.Center
                    ) { Text("✕", color = textColor.copy(alpha = 0.6f), fontSize = 14.sp) }
                }
                chip && clip.isNotEmpty() -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📋", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 6.dp))
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .semantics { contentDescription = "Paste copied text" }
                            .clickable {
                                performKeyFeedback(); commitText(clip)
                                showClipChip.value = false
                            },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            "Paste: " + clip.replace("\n", " ").take(40),
                            color = textColor, fontSize = 13.sp, maxLines = 1
                        )
                    }
                    Box(
                        modifier = Modifier.size(36.dp).clickable { showClipChip.value = false },
                        contentAlignment = Alignment.Center
                    ) { Text("✕", color = textColor.copy(alpha = 0.6f), fontSize = 14.sp) }
                }
                else -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    // When typing, a chevron returns from the pinned menu back to suggestions.
                    if (sugg.isNotEmpty()) Box(
                        modifier = Modifier.size(40.dp)
                            .semantics { contentDescription = "Back to suggestions" }
                            .clickable { toolbarPinned.value = false },
                        contentAlignment = Alignment.Center
                    ) { Text("‹", color = accentColor, fontSize = 22.sp) }
                    Box(
                        modifier = Modifier.size(40.dp)
                            .semantics { contentDescription = "Rewrite (tap) or hold to speak an instruction" }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    var longPressed = false
                                    val job = serviceScope.launch {
                                        kotlinx.coroutines.delay(400)
                                        longPressed = true
                                        startCustomRewriteRecording()
                                    }
                                    try {
                                        while (true) {
                                            val e = awaitPointerEvent()
                                            val c = e.changes.firstOrNull() ?: break
                                            if (!c.pressed) { c.consume(); break }
                                            c.consume()
                                        }
                                    } catch (_: kotlinx.coroutines.CancellationException) {}
                                    job.cancel()
                                    if (longPressed) stopCustomRewriteRecording() else keyboardMode.value = "rewrite"
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) { SparkleGlyph(accentColor, Modifier.size(20.dp)) }
                    ToolbarVec("Emoji", { emojiCategory.intValue = if (recentEmoji.value.isEmpty()) 1 else 0; keyboardMode.value = "emoji" }) { SmileyGlyph(textColor, Modifier.size(21.dp)) }
                    ToolbarVec("Clipboard", { keyboardMode.value = "clipboard" }) { ClipboardGlyph(textColor, Modifier.size(20.dp)) }
                    ToolbarVec("Undo", { performKeyFeedback(); sendCtrlKey(android.view.KeyEvent.KEYCODE_Z) }) { CurvedArrowGlyph(textColor, false, Modifier.size(20.dp)) }
                    ToolbarVec("Redo", { performKeyFeedback(); sendCtrlKey(android.view.KeyEvent.KEYCODE_Z, withShift = true) }) { CurvedArrowGlyph(textColor, true, Modifier.size(20.dp)) }
                    ToolbarVec("Select all", { performKeyFeedback(); selectAll() }) { SelectAllGlyph(textColor, Modifier.size(19.dp)) }
                    ToolbarVec("Settings", { performKeyFeedback(); openSettings() }) { GearGlyph(textColor, Modifier.size(19.dp)) }
                }
            }
        }
    }

    @Composable
    private fun ToolbarIcon(glyph: String, textColor: Color, onClick: () -> Unit) {
        Box(
            modifier = Modifier.size(40.dp)
                .semantics { contentDescription = keyDescription(glyph) }
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) { Text(glyph, fontSize = 18.sp, color = textColor) }
    }

    // Toolbar slot that hosts a drawn (vector) icon.
    @Composable
    private fun ToolbarVec(desc: String, onClick: () -> Unit, glyph: @Composable () -> Unit) {
        Box(
            modifier = Modifier.size(40.dp)
                .semantics { contentDescription = desc }
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) { glyph() }
    }

    // The cursor slider's dial: a knurled wheel with a soft halo and gradient face that spins as
    // you scrub, with an I-beam text cursor in the hub so it reads as "cursor control" at a glance.
    @Composable
    private fun BezelKnob(rotationDeg: Float, ring: Color, hub: Color, modifier: Modifier) {
        Canvas(modifier = modifier) {
            val r = size.minDimension / 2f
            val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            // soft halo + gradient disc (light falling from the top-left)
            drawCircle(color = ring.copy(alpha = 0.28f), radius = r, center = c)
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(lerp(ring, Color.White, 0.30f), ring),
                    center = c + androidx.compose.ui.geometry.Offset(-r * 0.25f, -r * 0.30f),
                    radius = r * 1.6f
                ),
                radius = r * 0.90f, center = c
            )
            // knurled edge: rounded ticks (alternating length) that rotate with the drag
            val ticks = 16
            for (i in 0 until ticks) {
                val ang = Math.toRadians(i * (360.0 / ticks) + rotationDeg)
                val ca = kotlin.math.cos(ang).toFloat()
                val sa = kotlin.math.sin(ang).toFloat()
                val inner = if (i % 2 == 0) 0.62f else 0.72f
                drawLine(
                    color = hub.copy(alpha = if (i % 2 == 0) 0.85f else 0.40f),
                    start = androidx.compose.ui.geometry.Offset(c.x + ca * r * inner, c.y + sa * r * inner),
                    end = androidx.compose.ui.geometry.Offset(c.x + ca * r * 0.85f, c.y + sa * r * 0.85f),
                    strokeWidth = r * 0.10f,
                    cap = StrokeCap.Round
                )
            }
            // hub face + I-beam text cursor
            drawCircle(color = hub.copy(alpha = 0.14f), radius = r * 0.50f, center = c)
            val ib = r * 0.28f      // half-height of the I-beam stem
            val serif = r * 0.15f   // half-width of its serifs
            val w = r * 0.11f
            drawLine(hub, androidx.compose.ui.geometry.Offset(c.x, c.y - ib),
                androidx.compose.ui.geometry.Offset(c.x, c.y + ib), w, StrokeCap.Round)
            drawLine(hub, androidx.compose.ui.geometry.Offset(c.x - serif, c.y - ib),
                androidx.compose.ui.geometry.Offset(c.x + serif, c.y - ib), w, StrokeCap.Round)
            drawLine(hub, androidx.compose.ui.geometry.Offset(c.x - serif, c.y + ib),
                androidx.compose.ui.geometry.Offset(c.x + serif, c.y + ib), w, StrokeCap.Round)
        }
    }

    // Emoji-only panel (mode == "emoji"). No clipboard here.
    @Composable
    fun EmojiPanel(
        keyHeight: androidx.compose.ui.unit.Dp,
        keyColor: Color,
        textColor: Color,
        accentColor: Color
    ) {
        val category by emojiCategory
        val recent by recentEmoji
        var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
        var suggesting by remember { mutableStateOf(false) }
        Column(modifier = Modifier.fillMaxWidth().height(keyHeight * (maxContentRows + 1))) {
            // Category tabs + AI "suggest emoji for my text" button
            Row(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EMOJI_TABS.forEachIndexed { idx, tab ->
                    val selected = idx == category && suggestions.isEmpty()
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .semantics { contentDescription = "Emoji category $tab" }
                            .clickable { emojiCategory.intValue = idx; suggestions = emptyList() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(tab, fontSize = 18.sp,
                            color = if (selected) accentColor else textColor.copy(alpha = 0.6f))
                    }
                }
                // AI emoji search: suggests emoji matching the text just written. Drawn as a tinted
                // round button so it reads as tappable, and always reacts — with a hint when
                // there's nothing to match yet, or an error note when the lookup fails.
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .semantics { contentDescription = "Suggest emoji for my text" }
                        .clickable {
                            performKeyFeedback()
                            val t = currentTargetText()
                            when {
                                secureField -> {}
                                t.isBlank() -> {
                                    statusText.value = "✨ Type a message first — I'll match emoji to it"
                                    handler.postDelayed({ if (statusText.value.startsWith("✨ Type")) statusText.value = "" }, 2200)
                                }
                                else -> {
                                    suggesting = true
                                    GroqApi.suggestEmojis(t) { res, _ ->
                                        handler.post {
                                            suggesting = false
                                            suggestions = splitEmojis(res ?: "")
                                            if (suggestions.isEmpty()) {
                                                statusText.value = "✨ Couldn't fetch suggestions"
                                                handler.postDelayed({ if (statusText.value.startsWith("✨ Could")) statusText.value = "" }, 2000)
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier.size(28.dp).background(
                            if (suggestions.isNotEmpty()) accentColor else accentColor.copy(alpha = 0.16f),
                            androidx.compose.foundation.shape.CircleShape
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        EnterGlyph("search",
                            if (suggestions.isNotEmpty()) Color.Black else accentColor,
                            Modifier.size(16.dp))
                    }
                }
            }
            androidx.compose.material3.HorizontalDivider(color = textColor.copy(alpha = 0.12f))

            val emojis = when {
                suggestions.isNotEmpty() -> suggestions
                category == 0 -> recent.ifEmpty { EMOJI_GROUPS[0] }
                else -> EMOJI_GROUPS[(category - 1).coerceIn(0, EMOJI_GROUPS.size - 1)]
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (suggesting) {
                    Text("✨ Finding emoji…", color = textColor.copy(alpha = 0.6f),
                        fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
                } else {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(8),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(emojis.size) { i ->
                            val e = emojis[i]
                            Box(
                                modifier = Modifier.aspectRatio(1f)
                                    .clickable {
                                        performKeyFeedback(); commitText(e)
                                        KeyboardPrefs.addRecentEmoji(this@KeyoService, e)
                                        recentEmoji.value = KeyboardPrefs.getRecentEmoji(this@KeyoService)
                                    },
                                contentAlignment = Alignment.Center
                            ) { Text(e, fontSize = 22.sp) }
                        }
                    }
                }
            }
            PanelBottomBar(keyColor, textColor, accentColor)
        }
    }

    // Clipboard-only panel (mode == "clipboard"). No emoji here.
    @Composable
    fun ClipboardPanel(
        keyHeight: androidx.compose.ui.unit.Dp,
        keyColor: Color,
        textColor: Color,
        accentColor: Color
    ) {
        val clips by clipHistory
        val pins by pinnedClips
        // Pinned items first, then recent history (excluding any that are pinned).
        val combined = pins.map { it to true } + clips.filter { it !in pins }.map { it to false }
        Column(modifier = Modifier.fillMaxWidth().height(keyHeight * (maxContentRows + 1))) {
            // Header: pin the current text (replaces "save quick phrase"), manage pins in settings,
            // and clear the unpinned history.
            Row(
                modifier = Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("＋ Pin current text", color = accentColor, fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    modifier = Modifier.weight(1f).clickable {
                        val t = currentTargetText()
                        if (!secureField && !noLearn && t.isNotBlank()) {
                            KeyboardPrefs.addPinned(this@KeyoService, t)
                            pinnedClips.value = KeyboardPrefs.getPinned(this@KeyoService)
                            statusText.value = "📌 Pinned"
                            handler.postDelayed({ if (statusText.value.startsWith("📌")) statusText.value = "" }, 1200)
                        }
                    })
                Text("Edit ›", color = textColor.copy(alpha = 0.7f), fontSize = 13.sp,
                    modifier = Modifier.clickable { openSettings("phrases") })
                if (clips.isNotEmpty()) {
                    Text("  Clear", color = accentColor, fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            KeyboardPrefs.clearClips(this@KeyoService); clipHistory.value = emptyList()
                        })
                }
            }
            androidx.compose.material3.HorizontalDivider(color = textColor.copy(alpha = 0.12f))

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (combined.isEmpty()) {
                    Text("Copied text will appear here", color = textColor.copy(alpha = 0.5f),
                        fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(combined.size) { i ->
                            val (clip, pinned) = combined[i]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.weight(1f)
                                        .pointerInput(clip) {
                                            detectTapGestures(
                                                onTap = { performKeyFeedback(); commitText(clip) },
                                                onLongPress = {
                                                    if (!pinned) {
                                                        KeyboardPrefs.removeClip(this@KeyoService, clip)
                                                        clipHistory.value = KeyboardPrefs.getClipHistory(this@KeyoService)
                                                    }
                                                }
                                            )
                                        }
                                        .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp)
                                ) {
                                    Text(clip.replace("\n", " ").take(80),
                                        color = textColor, fontSize = 14.sp, maxLines = 2)
                                }
                                // Pin toggle (pinning a clip keeps it at the top — this replaces the
                                // old "save as quick phrase" action).
                                Box(
                                    modifier = Modifier.size(38.dp)
                                        .semantics { contentDescription = if (pinned) "Unpin" else "Pin" }
                                        .clickable {
                                            KeyboardPrefs.togglePin(this@KeyoService, clip)
                                            pinnedClips.value = KeyboardPrefs.getPinned(this@KeyoService)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("📌", fontSize = 15.sp, modifier = Modifier.alpha(if (pinned) 1f else 0.3f))
                                }
                            }
                            androidx.compose.material3.HorizontalDivider(color = textColor.copy(alpha = 0.08f))
                        }
                    }
                }
            }
            PanelBottomBar(keyColor, textColor, accentColor)
        }
    }

    // Shared space bar: tap = space, hold still = dictate, swipe = cursor slider (rotating bezel).
    // Used in the main keyboard and in the emoji/clipboard panels so behaviour is identical.
    @Composable
    fun SpaceKey(
        modifier: Modifier,
        label: String,
        keyColor: Color,
        textColor: Color,
        accentColor: Color,
        recordColor: Color
    ) {
        val recording by isRecording
        val cancelThreshold = 100f
        var micCancelled by remember { mutableStateOf(false) }
        var micDragX by remember { mutableFloatStateOf(0f) }
        var spacePressed by remember { mutableStateOf(false) }
        var cursorVisual by remember { mutableStateOf(false) }
        var dragXVisual by remember { mutableFloatStateOf(0f) }

        Box(
            modifier = modifier
                .height(keyHeightDp.intValue.dp)
                .semantics { contentDescription = "Space. Long-press to dictate, swipe to move the cursor" }
                .pointerInput(Unit) {
                    // A deliberate horizontal drag starts the cursor slider. The threshold is in dp
                    // and generous: the old ~24px (≈9dp) was so small that a fast typing tap that
                    // drifted sideways got misread as a swipe and the space was dropped (words merged
                    // when typing quickly). A real cursor swipe easily clears this; a tap won't.
                    val cursorThresholdPx = 26.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        @Suppress("VARIABLE_WITH_REDUNDANT_INITIALIZER")
                        var totalDragX = 0f
                        var longPressed = false
                        var cursorMode = false
                        var lastStepX = 0f
                        var selectedDuringSwipe = false
                        micCancelled = false
                        micDragX = 0f
                        spacePressed = true
                        pendingSpaceTap = true   // a space tap is forming; the next char may flush it

                        val longPressJob = serviceScope.launch {
                            kotlinx.coroutines.delay(400)
                            if (!cursorMode) {
                                longPressed = true
                                pendingSpaceTap = false   // it's a hold (dictate), not a space tap
                                startVoiceRecording()
                                performKeyFeedback()
                            }
                        }

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) { change.consume(); break }
                                totalDragX = change.position.x - down.position.x
                                if (longPressed) {
                                    micDragX = totalDragX
                                    if (micDragX < -cancelThreshold) micCancelled = true
                                } else {
                                    if (!cursorMode && kotlin.math.abs(totalDragX) > cursorThresholdPx) {
                                        cursorMode = true
                                        cursorVisual = true
                                        pendingSpaceTap = false   // it's a swipe (cursor), not a space tap
                                        longPressJob.cancel()
                                        lastStepX = totalDragX
                                        cursorSelAnchor = null   // fresh swipe -> fresh selection anchor
                                    }
                                    if (cursorMode) {
                                        dragXVisual = totalDragX
                                        val stepPx = 28f
                                        // Only a deliberate Shift (not auto-capitalize) turns swipe into selection
                                        val sel = (isShift.value && !shiftIsAuto) || isCapsLock.value
                                        while (totalDragX - lastStepX >= stepPx) { moveCursor(false, sel); performKeyFeedback(); lastStepX += stepPx; selectedDuringSwipe = sel }
                                        while (totalDragX - lastStepX <= -stepPx) { moveCursor(true, sel); performKeyFeedback(); lastStepX -= stepPx; selectedDuringSwipe = sel }
                                    }
                                }
                                change.consume()
                            }
                        } catch (_: kotlinx.coroutines.CancellationException) {}

                        longPressJob.cancel()
                        spacePressed = false
                        cursorVisual = false
                        dragXVisual = 0f
                        if (longPressed) {
                            if (micCancelled) cancelVoiceRecording() else stopVoiceRecording()
                            micDragX = 0f
                        } else if (!cursorMode && pendingSpaceTap) {
                            // Normal tap, and no fast next-letter already flushed it: commit the space.
                            pendingSpaceTap = false
                            performKeyFeedback()
                            commitChar(' ')
                        }
                        // Consume the armed (manual) Shift after a selection swipe so typing isn't capitalized
                        if (selectedDuringSwipe && isShift.value && !shiftIsAuto && !isCapsLock.value) {
                            isShift.value = false
                        }
                    }
                }
                .padding(horizontal = keyHGapDp.intValue.dp, vertical = keyVGapDp.intValue.dp)
                .background(
                    when {
                        recording && micDragX < -cancelThreshold -> Color(0xFF666666)
                        recording -> recordColor
                        spacePressed -> lerp(keyColor, Color.Black, 0.22f)
                        else -> keyColor
                    },
                    RoundedCornerShape(6.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (recording) {
                if (micDragX < -cancelThreshold) {
                    Text("✕", fontSize = 16.sp, color = Color.White)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MicGlyph(Color.White, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(7.dp).background(Color.White, androidx.compose.foundation.shape.CircleShape))
                    }
                }
            } else if (cursorVisual) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize().clipToBounds().padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val halfTravelPx = with(density) { (maxWidth / 2 - 16.dp).toPx() }
                    val knobOffset = with(density) { dragXVisual.coerceIn(-halfTravelPx, halfTravelPx).toDp() }
                    // Rail: a gradient track that fades out at the ends, with direction chevrons
                    // that light up toward the side being dragged.
                    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                        val cy = size.height / 2f
                        val th = 4.dp.toPx()
                        drawRoundRect(
                            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                0f to textColor.copy(alpha = 0f),
                                0.15f to textColor.copy(alpha = 0.22f),
                                0.85f to textColor.copy(alpha = 0.22f),
                                1f to textColor.copy(alpha = 0f)
                            ),
                            topLeft = androidx.compose.ui.geometry.Offset(0f, cy - th / 2f),
                            size = androidx.compose.ui.geometry.Size(size.width, th),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(th / 2f)
                        )
                        val s = 5.dp.toPx()
                        fun chevron(xc: Float, dir: Float, alpha: Float) {
                            val p = Path()
                            p.moveTo(xc + dir * s * 0.6f, cy - s)
                            p.lineTo(xc - dir * s * 0.6f, cy)
                            p.lineTo(xc + dir * s * 0.6f, cy + s)
                            drawPath(p, color = textColor.copy(alpha = alpha),
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        chevron(6.dp.toPx(), 1f, if (dragXVisual < -12f) 0.95f else 0.35f)
                        chevron(size.width - 6.dp.toPx(), -1f, if (dragXVisual > 12f) 0.95f else 0.35f)
                    }
                    BezelKnob(
                        rotationDeg = dragXVisual * 0.9f,
                        ring = accentColor,
                        hub = textColor,
                        modifier = Modifier.offset(x = knobOffset).size(34.dp)
                    )
                }
            } else {
                Text(text = label, color = textColor, fontSize = 14.sp, textAlign = TextAlign.Center)
                MicGlyph(textColor, Modifier.align(Alignment.TopEnd).padding(3.dp).size(14.dp))
            }
        }
    }

    // (label, instruction, submenuKey) — instruction null means it opens a submenu.
    private val REWRITE_MAIN = listOf(
        Triple<String, String?, String?>("Fix grammar & spelling", "Fix spelling and grammar; keep the meaning and language.", null),
        Triple<String, String?, String?>("Make shorter", "Make it shorter and more concise; keep the language.", null),
        Triple<String, String?, String?>("Make longer", "Expand it with a little more detail; keep the language.", null),
        Triple<String, String?, String?>("Professional", "Rewrite in a professional, polished tone; keep the language.", null),
        Triple<String, String?, String?>("Friendly", "Rewrite in a warm, friendly tone; keep the language.", null),
        Triple<String, String?, String?>("Bullet points", "Reformat as a short bullet-point list; keep the language.", null),
        Triple<String, String?, String?>("Add emoji", "Add a few fitting emoji without changing the wording; keep the language.", null),
        Triple<String, String?, String?>("Continue writing", null, "continue"),
        Triple<String, String?, String?>("Tone…", null, "tone"),
        Triple<String, String?, String?>("Translate…", null, "translate")
    )
    private val REWRITE_TONE = listOf(
        Triple<String, String?, String?>("Formal", "Rewrite in a formal tone; keep the language.", null),
        Triple<String, String?, String?>("Confident", "Rewrite in a confident, assertive tone; keep the language.", null),
        Triple<String, String?, String?>("Humorous", "Rewrite with light humor; keep the language.", null),
        Triple<String, String?, String?>("Sassy / bold", "Rewrite in a bold, cheeky, sassy tone; keep the language.", null),
        Triple<String, String?, String?>("Empathetic", "Rewrite in an empathetic, caring tone; keep the language.", null),
        Triple<String, String?, String?>("Casual", "Rewrite in a relaxed, casual tone; keep the language.", null),
        Triple<String, String?, String?>("Poetic", "Rewrite in a poetic style; keep the language.", null)
    )
    private val REWRITE_TRANSLATE = listOf("English", "Russian", "Latvian", "German", "Spanish", "French", "Ukrainian", "Chinese")
        .map { Triple<String, String?, String?>("→ $it", "Translate the text to $it. Output only the translation.", null) }

    // AI rewrite panel (mode == "rewrite").
    @Composable
    fun RewritePanel(
        keyHeight: androidx.compose.ui.unit.Dp,
        keyColor: Color,
        textColor: Color,
        accentColor: Color
    ) {
        var sub by remember { mutableStateOf("") }
        val items = when (sub) {
            "tone" -> REWRITE_TONE
            "translate" -> REWRITE_TRANSLATE
            else -> REWRITE_MAIN
        }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(sub) { listState.scrollToItem(0) }  // always start a submenu from the top
        Column(modifier = Modifier.fillMaxWidth().height(keyHeight * (maxContentRows + 1))) {
            androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (sub.isNotEmpty()) {
                    item {
                        RewriteRow("‹  Back", false, accentColor, accentColor) { sub = "" }
                        androidx.compose.material3.HorizontalDivider(color = textColor.copy(alpha = 0.06f))
                    }
                }
                items(items.size) { i ->
                    val (label, instr, subKey) = items[i]
                    RewriteRow(label, subKey != null && subKey != "continue", textColor, accentColor) {
                        when {
                            subKey == "continue" -> runContinue()
                            subKey != null -> sub = subKey
                            instr != null -> runRewrite(instr)
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(color = textColor.copy(alpha = 0.06f))
                }
            }
            PanelBottomBar(keyColor, textColor, accentColor)
        }
    }

    @Composable
    private fun RewriteRow(label: String, hasArrow: Boolean, labelColor: Color, accentColor: Color, onClick: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = labelColor, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (hasArrow) Text("›", color = accentColor, fontSize = 18.sp)
        }
    }

    @Composable
    private fun PanelBottomBar(
        keyColor: Color,
        textColor: Color,
        accentColor: Color
    ) {
        androidx.compose.material3.HorizontalDivider(color = textColor.copy(alpha = 0.12f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyButton("ABC", accentColor, Color.Black, Modifier.weight(1.4f), fontSize = modeKeyFont()) { keyboardMode.value = "abc" }
            SpaceKey(Modifier.weight(4f), "space", keyColor, textColor, accentColor, Color(currentTheme.record))
            BackspaceKey(keyColor, textColor, Modifier.weight(1.4f))
        }
    }

    // Enter key with a drawn icon + press feedback (replaces the plain glyph).
    @Composable
    fun EnterKey(
        kind: String,
        bgColor: Color,
        iconColor: Color,
        height: androidx.compose.ui.unit.Dp,
        modifier: Modifier,
        onClick: () -> Unit
    ) {
        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        Box(
            modifier = modifier
                .height(height)
                .semantics { contentDescription = "Enter" }
                .clickable(interactionSource = interaction, indication = null) { performKeyFeedback(); onClick() }
                .padding(horizontal = keyHGapDp.intValue.dp, vertical = keyVGapDp.intValue.dp)
                .background(if (pressed) lerp(bgColor, Color.Black, 0.22f) else bgColor, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            EnterGlyph(kind, iconColor, Modifier.size(22.dp))
        }
    }

    // Language key — a monochrome globe drawn so it matches the key text colour (not a colored emoji).
    @Composable
    fun LanguageKey(
        keyColor: Color,
        iconColor: Color,
        height: androidx.compose.ui.unit.Dp,
        modifier: Modifier,
        onClick: () -> Unit
    ) {
        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        Box(
            modifier = modifier
                .height(height)
                .semantics { contentDescription = "Switch language" }
                .clickable(interactionSource = interaction, indication = null) { performKeyFeedback(); onClick() }
                .padding(horizontal = keyHGapDp.intValue.dp, vertical = keyVGapDp.intValue.dp)
                .background(if (pressed) lerp(keyColor, Color.Black, 0.22f) else keyColor, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(20.dp)) {
                val r = size.minDimension / 2f * 0.92f
                val cx = size.width / 2f
                val cy = size.height / 2f
                val sw = r * 0.10f
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = sw)
                fun off(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)
                drawCircle(color = iconColor, radius = r, center = off(cx, cy), style = stroke)
                drawOval(
                    color = iconColor,
                    topLeft = off(cx - r * 0.45f, cy - r),
                    size = androidx.compose.ui.geometry.Size(r * 0.9f, r * 2f),
                    style = stroke
                )
                drawLine(iconColor, off(cx - r, cy), off(cx + r, cy), sw)
                drawLine(iconColor, off(cx - r * 0.82f, cy - r * 0.5f), off(cx + r * 0.82f, cy - r * 0.5f), sw)
                drawLine(iconColor, off(cx - r * 0.82f, cy + r * 0.5f), off(cx + r * 0.82f, cy + r * 0.5f), sw)
            }
        }
    }

    // Shift key with a bold, wide, solid shift glyph drawn directly (so it never looks thin).
    @Composable
    fun ShiftKey(
        active: Boolean,
        keyColor: Color,
        accentColor: Color,
        iconColor: Color,
        height: androidx.compose.ui.unit.Dp,
        modifier: Modifier,
        locked: Boolean = false,
        onClick: () -> Unit
    ) {
        var pressed by remember { mutableStateOf(false) }
        val base = if (active) accentColor else keyColor
        Box(
            modifier = modifier
                .height(height)
                .semantics { contentDescription = "Shift. Hold to select text by swiping the space bar" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        pressed = true
                        var longPressed = false
                        // Hold Shift to arm text-selection mode (with a haptic cue).
                        val job = serviceScope.launch {
                            kotlinx.coroutines.delay(250)
                            longPressed = true
                            armSelection()
                        }
                        try {
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull() ?: break
                                if (!c.pressed) { c.consume(); break }
                                c.consume()
                            }
                        } catch (_: kotlinx.coroutines.CancellationException) {}
                        job.cancel()
                        pressed = false
                        if (!longPressed) onClick()
                    }
                }
                .padding(horizontal = keyHGapDp.intValue.dp, vertical = keyVGapDp.intValue.dp)
                .background(if (pressed) lerp(base, Color.Black, 0.22f) else base, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(width = 24.dp, height = 22.dp)) {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.08f)   // apex
                    lineTo(w * 0.92f, h * 0.52f)  // right shoulder
                    lineTo(w * 0.68f, h * 0.52f)  // right inner
                    lineTo(w * 0.68f, h * 0.90f)  // right stem bottom
                    lineTo(w * 0.32f, h * 0.90f)  // left stem bottom
                    lineTo(w * 0.32f, h * 0.52f)  // left inner
                    lineTo(w * 0.08f, h * 0.52f)  // left shoulder
                    close()
                }
                drawPath(path, color = iconColor)
                // Caps-lock indicator: an underline bar
                if (locked) {
                    drawLine(
                        color = iconColor,
                        start = androidx.compose.ui.geometry.Offset(w * 0.32f, h * 0.96f),
                        end = androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.96f),
                        strokeWidth = h * 0.07f
                    )
                }
            }
        }
    }

    @Composable
    fun BackspaceKey(bgColor: Color, textColor: Color, modifier: Modifier) {
        var isPressed by remember { mutableStateOf(false) }
        var swipedClear by remember { mutableStateOf(false) }
        val bsHeight = keyHeightDp.intValue.dp

        Box(
            modifier = modifier
                .height(bsHeight)
                .semantics { contentDescription = "Delete" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        isPressed = true
                        swipedClear = false
                        var didSwipeClear = false

                        // First delete immediately
                        deleteSmart(false)

                        // Background job for repeat delete
                        val startTime = System.currentTimeMillis()
                        val job = serviceScope.launch {
                            kotlinx.coroutines.delay(400)
                            while (isPressed && !didSwipeClear) {
                                val elapsed = System.currentTimeMillis() - startTime
                                val byWord = elapsed > 2000
                                deleteSmart(byWord)
                                val delay = when {
                                    elapsed > 2000 -> 150L
                                    elapsed > 1000 -> 40L
                                    else -> 80L
                                }
                                kotlinx.coroutines.delay(delay)
                            }
                        }

                        // Track drag
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                val dragX = change.position.x - down.position.x
                                if (dragX < -80f) { // swipe left threshold
                                    didSwipeClear = true
                                    swipedClear = true
                                }
                                change.consume()
                            }
                        } catch (_: kotlinx.coroutines.CancellationException) {}

                        job.cancel()
                        isPressed = false

                        if (didSwipeClear) {
                            // Select all and delete
                            deleteAll()
                        }
                        swipedClear = false
                    }
                }
                .padding(horizontal = keyHGapDp.intValue.dp, vertical = keyVGapDp.intValue.dp)
                .background(
                    when {
                        swipedClear -> Color(0xFFFF5555)
                        isPressed -> lerp(bgColor, Color.Black, 0.22f)
                        else -> bgColor
                    },
                    RoundedCornerShape(6.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (swipedClear) {
                Text("✕", color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
            } else {
                BackspaceGlyph(textColor, Modifier.size(22.dp))
            }
        }
    }

    // Alt characters map
    private val altChars = mapOf(
        // Russian
        "е" to listOf("ё"), "Е" to listOf("Ё"),
        "ь" to listOf("ъ"), "Ь" to listOf("Ъ"),
        "и" to listOf("й"), "И" to listOf("Й"),
        // English → accented
        "a" to listOf("ä","à","á","â","ã","å","æ","ā"), "A" to listOf("Ä","À","Á","Â","Ã","Å","Æ","Ā"),
        "e" to listOf("ē","è","é","ê","ë","ė","ę"), "E" to listOf("Ē","È","É","Ê","Ë","Ė","Ę"),
        "i" to listOf("ī","ì","í","î","ï","į"), "I" to listOf("Ī","Ì","Í","Î","Ï","Į"),
        "o" to listOf("ö","ò","ó","ô","õ","ø","ō"), "O" to listOf("Ö","Ò","Ó","Ô","Õ","Ø","Ō"),
        "u" to listOf("ü","ù","ú","û","ū","ų"), "U" to listOf("Ü","Ù","Ú","Û","Ū","Ų"),
        "s" to listOf("š","ś","ß"), "S" to listOf("Š","Ś"),
        "c" to listOf("č","ç","ć"), "C" to listOf("Č","Ç","Ć"),
        "n" to listOf("ņ","ñ","ń"), "N" to listOf("Ņ","Ñ","Ń"),
        "z" to listOf("ž","ź","ż"), "Z" to listOf("Ž","Ź","Ż"),
        "g" to listOf("ģ","ğ"), "G" to listOf("Ģ","Ğ"),
        "k" to listOf("ķ"), "K" to listOf("Ķ"),
        "l" to listOf("ļ","ł"), "L" to listOf("Ļ","Ł"),
        "r" to listOf("ŗ"), "R" to listOf("Ŗ"),
        "y" to listOf("ý","ÿ"), "Y" to listOf("Ý","Ÿ"),
        "d" to listOf("đ"), "D" to listOf("Đ"),
        // Symbols. ⚙ on the period opens Keyo settings (handled specially in KeyButton).
        "." to listOf("?",",","!","-","⚙"),
        "," to listOf(";","‚","„"),
        "?" to listOf("¿","‽"),
        "!" to listOf("¡"),
        "'" to listOf("'","'","‛","\""),
        "-" to listOf("–","—","_"),
        "0" to listOf("°","∅"),
        "1" to listOf("¹","½","⅓"),
        "2" to listOf("²","⅔"),
        "3" to listOf("³","¾"),
        "$" to listOf("€","£","¥","₽","₹")
    )

    // Long-press digits for the top letter row when the dedicated number row is hidden, with a
    // small corner hint on the key (Gboard behaviour). Looked up by lowercase key label.
    private val topRowDigits = mapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
        "й" to "1", "ц" to "2", "у" to "3", "к" to "4", "е" to "5",
        "н" to "6", "г" to "7", "ш" to "8", "щ" to "9", "з" to "0"
    )

    // Emoji panel categories. Tab 0 = recently used; 1..n map to EMOJI_GROUPS.
    private val EMOJI_TABS = listOf("🕘", "😀", "🐶", "🍕", "❤️", "✋")
    private var recentEmoji = mutableStateOf<List<String>>(emptyList())
    private val EMOJI_GROUPS = listOf(
        // Smileys
        listOf("😀","😃","😄","😁","😆","😅","😂","🤣","😊","🙂","🙃","😉","😌","😍","🥰","😘",
               "😋","😛","😜","🤪","😝","🤗","🤔","😐","😶","😏","😒","🙄","😬","😴","😎","🥳",
               "😢","😭","😤","😠","😡","🤯","😱","😳","🥺","😇","🤤","😞","😔","🤥","🤧","🤒"),
        // Animals
        listOf("🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🐔",
               "🐧","🐦","🐤","🦆","🦅","🦉","🐺","🐗","🐴","🦄","🐝","🐛","🦋","🐌","🐞","🐢",
               "🐍","🐙","🐠","🐬","🐳","🦈","🐊","🐅","🦓","🦍","🐘","🐫","🦒","🦘","🐓","🦌"),
        // Food
        listOf("🍏","🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🍈","🍒","🍑","🥭","🍍","🥥","🥝",
               "🍅","🥑","🍆","🥔","🥕","🌽","🌶","🥒","🥬","🥦","🧄","🧅","🍄","🥜","🍞","🥐",
               "🧀","🍕","🍔","🍟","🌭","🌮","🌯","🍣","🍦","🍩","🍪","🎂","🍰","☕","🍺","🍷"),
        // Hearts & symbols
        listOf("❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❣️","💕","💞","💓","💗","💖",
               "💘","💝","✨","⭐","🌟","💫","⚡","🔥","💯","✅","❌","❓","❗","💤","🎉","🎊",
               "🎁","🏆","🎯","🔔","💡","💰","📌","🔒","🔑","⏰","📅","📈","🌈","☀️","🌙","⛄"),
        // Gestures
        listOf("👋","🤚","✋","🖐","🖖","👌","🤌","🤏","✌️","🤞","🤟","🤘","🤙","👈","👉","👆",
               "👇","☝️","👍","👎","✊","👊","🤛","🤜","👏","🙌","👐","🤲","🙏","💪","🦵","🦶",
               "👂","👃","👀","🧠","👶","🧒","👦","👧","🧑","👨","👩","🧓","👴","👵","🙋","🤷")
    )

    // Spoken label for TalkBack / accessibility services.
    private fun keyDescription(label: String): String = when (label) {
        "⌫" -> "Delete"
        "↵" -> "Enter"
        "✓" -> "Done"
        "➤" -> "Send"
        "🔍" -> "Search"
        "123" -> "Numbers and symbols"
        "ABC" -> "Letters"
        "!?#" -> "More symbols"
        "🔢" -> "Number pad"
        "🌐" -> "Switch language"
        "🤖" -> "AI assistant"
        "⬆" -> "Shift"
        "😀" -> "Emoji"
        "📋" -> "Clipboard"
        "⚙" -> "Settings"
        "↶" -> "Undo"
        "↷" -> "Redo"
        "⬚" -> "Select all"
        "★" -> "Quick phrases"
        " " -> "Space"
        else -> label
    }

    // Slightly smaller font for multi-character mode keys (123 / ABC / !?#) so they don't look bulky.
    private fun modeKeyFont(): androidx.compose.ui.unit.TextUnit =
        (KeyboardPrefs.fontSizeSp(keyHeightDp.intValue, keyVGapDp.intValue) * 0.72f).sp

    // ---- Rich formatting (bold / italic) ----
    private fun currentPkg(): String = currentInputEditorInfo?.packageName ?: ""

    // Map A-Z a-z 0-9 to Unicode mathematical bold/italic so they render styled in ANY app
    // (Latin only — Cyrillic has no Unicode bold equivalents).
    private fun toUnicodeStyled(s: String, italic: Boolean): String {
        val sb = StringBuilder()
        for (ch in s) {
            val cp = when {
                ch in 'A'..'Z' -> (if (italic) 0x1D434 else 0x1D400) + (ch - 'A')
                ch in 'a'..'z' -> if (italic && ch == 'h') 0x210E
                                  else (if (italic) 0x1D44E else 0x1D41A) + (ch - 'a')
                !italic && ch in '0'..'9' -> 0x1D7CE + (ch - '0')
                else -> ch.code
            }
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun isWhatsApp() = currentPkg().let { it == "com.whatsapp" || it == "com.whatsapp.w4b" }

    private fun applyBold(text: String): String =
        if (isWhatsApp()) "*$text*" else toUnicodeStyled(text, italic = false)

    private fun applyItalic(text: String): String =
        if (isWhatsApp()) "_${text}_" else toUnicodeStyled(text, italic = true)

    // Convert the AI's ⟦b⟧/⟦i⟧ markers into real per-app formatting.
    private fun formatEmphasis(text: String): String {
        var t = Regex("⟦b⟧(.*?)⟦/b⟧", RegexOption.DOT_MATCHES_ALL).replace(text) { applyBold(it.groupValues[1]) }
        t = Regex("⟦i⟧(.*?)⟦/i⟧", RegexOption.DOT_MATCHES_ALL).replace(t) { applyItalic(it.groupValues[1]) }
        return t.replace("⟦b⟧", "").replace("⟦/b⟧", "").replace("⟦i⟧", "").replace("⟦/i⟧", "")
    }

    // Selected text, or the text just before the cursor (for AI features).
    private fun currentTargetText(): String {
        val ic = currentInputConnection ?: return ""
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) return sel.toString()
        return ic.getTextBeforeCursor(200, 0)?.toString() ?: ""
    }

    // Split a string into emoji (grapheme clusters), dropping plain ASCII/whitespace.
    private fun splitEmojis(s: String): List<String> {
        val bi = java.text.BreakIterator.getCharacterInstance()
        bi.setText(s)
        val out = mutableListOf<String>()
        var start = bi.first(); var end = bi.next()
        while (end != java.text.BreakIterator.DONE) {
            val g = s.substring(start, end).trim()
            if (g.isNotEmpty() && g[0].code > 0x2030) out.add(g)
            start = end; end = bi.next()
        }
        return out
    }

    // ---- AI rewrite of the selected text (or the text before the cursor) ----
    private fun runRewrite(instruction: String) {
        if (secureField) {
            statusText.value = "🔒 AI is off in password fields"
            handler.postDelayed({ if (statusText.value.startsWith("🔒")) statusText.value = "" }, 1500)
            return
        }
        finalizeComposing()
        val ic = currentInputConnection
        val sel = ic?.getSelectedText(0)
        val hadSelection = !sel.isNullOrEmpty()
        val target = if (hadSelection) sel.toString() else (ic?.getTextBeforeCursor(4000, 0)?.toString() ?: "")
        if (target.isBlank()) {
            statusText.value = "✨ Nothing to rewrite"
            handler.postDelayed({ if (statusText.value.startsWith("✨")) statusText.value = "" }, 1500)
            return
        }
        keyboardMode.value = "abc"
        statusText.value = "✨ Improving…"
        GroqApi.rewrite(target, instruction) { res, err ->
            handler.post {
                if (res != null) {
                    val out = formatEmphasis(res)
                    currentInputConnection?.let { c ->
                        if (hadSelection) c.commitText(out, 1)
                        else { c.deleteSurroundingText(target.length, 0); c.commitText(out, 1) }
                    }
                    statusText.value = ""
                    // Offer a quick undo of the rewrite
                    rewriteBackup = target
                    rewriteResult = out
                    showUndoRewrite.value = true
                    handler.removeCallbacks(hideUndoRewrite)
                    handler.postDelayed(hideUndoRewrite, 8000)
                } else {
                    statusText.value = err ?: "Rewrite failed"
                    handler.postDelayed({ statusText.value = "" }, 2500)
                }
            }
        }
    }

    // Continue writing: append an AI-generated continuation at the cursor (does not replace).
    private fun runContinue() {
        if (secureField) {
            statusText.value = "🔒 AI is off in password fields"
            handler.postDelayed({ if (statusText.value.startsWith("🔒")) statusText.value = "" }, 1500)
            return
        }
        finalizeComposing()
        val before = currentInputConnection?.getTextBeforeCursor(4000, 0)?.toString() ?: ""
        if (before.isBlank()) {
            statusText.value = "✨ Nothing to continue"
            handler.postDelayed({ if (statusText.value.startsWith("✨")) statusText.value = "" }, 1500)
            return
        }
        keyboardMode.value = "abc"
        statusText.value = "✨ Writing…"
        GroqApi.rewrite(before, "Continue this text naturally from where it ends. Output ONLY the continuation to append — do NOT repeat the existing text.") { res, err ->
            handler.post {
                if (res != null) {
                    val out = formatEmphasis(res)
                    val sep = if (before.isNotEmpty() && !before.last().isWhitespace() &&
                        out.isNotEmpty() && !out.first().isWhitespace()) " " else ""
                    currentInputConnection?.commitText(sep + out, 1)
                    statusText.value = ""
                } else {
                    statusText.value = err ?: "Failed"
                    handler.postDelayed({ statusText.value = "" }, 2500)
                }
            }
        }
    }

    @Composable
    fun KeyButton(
        label: String,
        bgColor: Color,
        textColor: Color,
        modifier: Modifier,
        height: androidx.compose.ui.unit.Dp = keyHeightDp.intValue.dp,
        fontSize: androidx.compose.ui.unit.TextUnit = KeyboardPrefs.fontSizeSp(keyHeightDp.intValue, keyVGapDp.intValue).sp,
        onClick: () -> Unit
    ) {
        var pressed by remember { mutableStateOf(false) }
        var showAlts by remember { mutableStateOf(false) }
        var selectedAltIdx by remember { mutableIntStateOf(-1) }   // visual index in the popup row
        var keyCenterX by remember { mutableFloatStateOf(0f) }
        // Long-press digit when the number row is hidden; it becomes the primary alternate.
        val digitHint = if (!showNumberRow.value) topRowDigits[label.lowercase()] else null
        val alts = if (digitHint != null) listOf(digitHint) + (altChars[label] ?: emptyList())
                   else altChars[label]
        val hasAlts = alts != null && alts.isNotEmpty()
        val screenWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) {
            androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
        }
        // Near the right screen edge the alt popup extends LEFT from the key (with the row reversed)
        // so the window edge never clamps it — clamping would break the finger-to-item mapping.
        val popupFromRight = keyCenterX > screenWidthPx / 2f

        // The pointer handler sits ABOVE the gap padding, so the whole grid cell is tappable: taps
        // landing in the visual gap between keys go to this key instead of dying (no dead zones).
        Box(
            modifier = modifier
                .height(height)
                .onGloballyPositioned { keyCenterX = it.positionInWindow().x + it.size.width / 2f }
                .semantics { contentDescription = keyDescription(label) }
                .pointerInput(label) {
                    val stepPx = 40.dp.toPx()   // one popup item is 40dp wide; selection step matches
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val fromRight = keyCenterX > screenWidthPx / 2f
                        pressed = true
                        showAlts = false
                        var longPressed = false
                        var selectedAlt: String? = null

                        // Commit on key-DOWN so the character appears instantly (no waiting
                        // for finger-up). This removes the perceived typing latency. A
                        // long-press later replaces this character with the chosen accent.
                        onClick()
                        performKeyFeedback()

                        // Long press timer
                        val longPressJob = serviceScope.launch {
                            kotlinx.coroutines.delay(400)
                            if (pressed && hasAlts && !glideActive.value) {   // not while gliding
                                longPressed = true
                                showAlts = true
                                // Default to the primary alternate (Gboard-style): releasing
                                // without moving the finger inserts it.
                                selectedAlt = alts!!.first()
                                selectedAltIdx = if (fromRight) alts.size - 1 else 0
                                performKeyFeedback()
                            }
                        }

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                // If alts are showing, detect which one the finger is on. The row is
                                // anchored so the primary item starts above the key; near the right
                                // edge it is reversed and extends left instead.
                                if (showAlts && alts != null) {
                                    val dx = change.position.x - down.position.x
                                    val n = alts.size
                                    val vis = if (!fromRight) (dx / stepPx).roundToInt().coerceIn(0, n - 1)
                                              else ((dx / stepPx) + (n - 1)).roundToInt().coerceIn(0, n - 1)
                                    selectedAltIdx = vis
                                    selectedAlt = if (!fromRight) alts[vis] else alts[n - 1 - vis]
                                }
                                change.consume()
                            }
                        } catch (_: kotlinx.coroutines.CancellationException) {}

                        longPressJob.cancel()
                        pressed = false
                        showAlts = false
                        selectedAltIdx = -1

                        // The base character was already committed on key-down. On long-press,
                        // delete it and substitute the chosen alternate (or open settings).
                        val chosenAlt = selectedAlt
                        if (longPressed && chosenAlt != null && !glideActive.value) {
                            performKeyFeedback()
                            when {
                                chosenAlt == "⚙" -> {                      // gear on the period opens settings
                                    finalizeComposing()
                                    currentInputConnection?.deleteSurroundingText(label.length, 0)
                                    openSettings()
                                }
                                composing.isNotEmpty() -> {                // swap the base letter inside the composing word
                                    composing.setLength((composing.length - label.length).coerceAtLeast(0))
                                    composing.append(chosenAlt)
                                    currentInputConnection?.setComposingText(composing.toString(), 1)
                                    updateSuggestions()   // onUpdateSelection skips self-edits now, so refresh here
                                }
                                else -> {                                   // base char already committed: patch it in place
                                    currentInputConnection?.deleteSurroundingText(label.length, 0)
                                    commitText(chosenAlt)
                                }
                            }
                        }
                    }
                }
        ) {
            // Main key — darkens instantly while pressed for a tactile "button" feel
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = keyHGapDp.intValue.dp, vertical = keyVGapDp.intValue.dp)
                    .background(if (pressed) lerp(bgColor, Color.Black, 0.22f) else bgColor, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = textColor,
                    fontSize = fontSize,
                    textAlign = TextAlign.Center
                )
                if (digitHint != null) {
                    Text(
                        text = digitHint,
                        color = textColor.copy(alpha = 0.45f),
                        fontSize = 9.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 4.dp)
                    )
                }
            }

            // Key preview popup → uses Popup to escape clipping
            if (pressed && label.length == 1 && !showAlts) {
                androidx.compose.ui.window.Popup(
                    alignment = Alignment.TopCenter,
                    offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { (-52).dp.roundToPx() })
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color(currentTheme.text), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = Color(currentTheme.bg),
                            fontSize = 28.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Alt characters popup — uses Popup to escape parent clipping. The row is anchored so
            // the PRIMARY alternate sits directly above the key (reversed and extending left near
            // the right screen edge), matching the finger-travel selection math in the gesture.
            if (showAlts && alts != null) {
                val display = if (popupFromRight) alts.asReversed() else alts
                val anchorShift = ((alts.size * 40 + 8) / 2 - 24).dp
                androidx.compose.ui.window.Popup(
                    alignment = Alignment.TopCenter,
                    offset = with(androidx.compose.ui.platform.LocalDensity.current) {
                        androidx.compose.ui.unit.IntOffset(
                            (if (popupFromRight) -anchorShift else anchorShift).roundToPx(),
                            (-56).dp.roundToPx()
                        )
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .background(Color(currentTheme.altPopupBg), RoundedCornerShape(8.dp))
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        display.forEachIndexed { idx, alt ->
                            val isSelected = idx == selectedAltIdx
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .padding(2.dp)
                                    .background(
                                        if (isSelected) Color(currentTheme.accent) else Color(currentTheme.altPopupKey),
                                        RoundedCornerShape(6.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = alt,
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Composing region: stop the editor red-underlining valid words --------------------------
    // We hold the word being typed in the IME composing region (setComposingText) instead of
    // committing each letter immediately. The receiving editor (e.g. a messenger field) never runs
    // its own spell checker over composing text, so correct words no longer get the red squiggle
    // while you type them. The word is finalized (finishComposingText) on a word boundary, a
    // suggestion tap, backspace-to-empty, or any non-letter / cursor / AI action. Never used in
    // secure fields (passwords / no-personalized-learning).
    private val composing = StringBuilder()

    /** Characters that belong to a word and so stay in the composing region. */
    private fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\'' || c == '-'

    // English contractions typed without the apostrophe -> canonical form (correct caps for the "I"
    // ones). Forms that are themselves valid words (its, were, well, lets, id, ill, shed, …) are
    // deliberately left out so a correct word is never "fixed".
    private val enContractions = mapOf(
        "im" to "I'm", "ive" to "I've",
        "dont" to "don't", "cant" to "can't", "wont" to "won't",
        "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't",
        "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't",
        "doesnt" to "doesn't", "didnt" to "didn't",
        "couldnt" to "couldn't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
        "mustnt" to "mustn't", "neednt" to "needn't", "aint" to "ain't",
        "youre" to "you're", "youve" to "you've", "youll" to "you'll", "youd" to "you'd",
        "hes" to "he's", "shes" to "she's", "hed" to "he'd",
        "theyre" to "they're", "theyve" to "they've", "theyll" to "they'll", "theyd" to "they'd",
        "weve" to "we've",
        "thats" to "that's", "theres" to "there's", "whats" to "what's", "whos" to "who's",
        "whod" to "who'd", "wheres" to "where's", "whens" to "when's", "hows" to "how's",
        "couldve" to "could've", "shouldve" to "should've", "wouldve" to "would've",
        "mustve" to "must've", "mightve" to "might've",
        "yall" to "y'all", "oclock" to "o'clock"
    )

    // Emoticons -> emoji, swapped inline as soon as the closing character is typed (only when preceded
    // by a space or the start of text, so URLs like "http://" are never touched). Longest match wins.
    private val emoticons = listOf(
        ":')" to "😢", ":-)" to "😊", ":-(" to "🙁", ":-D" to "😃", ":-P" to "😛",
        ";-)" to "😉", "</3" to "💔",
        ":)" to "😊", ":(" to "🙁", ":D" to "😃", ":P" to "😛",
        ";)" to "😉", ":O" to "😮", ":|" to "😐", ":/" to "😕",
        ":*" to "😘", "<3" to "❤️", "xD" to "😆", "XD" to "😆", "=)" to "😊"
    ).sortedByDescending { it.first.length }
    private val emoticonEndChars = emoticons.map { it.first.last() }.toSet()

    // Word -> emoji offered in the suggestion strip while typing (tap swaps the word for the emoji).
    private val emojiKeywords = mapOf(
        "love" to "❤️", "heart" to "❤️", "like" to "👍", "ok" to "👌", "okay" to "👌",
        "yes" to "✅", "no" to "❌", "fire" to "🔥", "lit" to "🔥", "happy" to "😊", "smile" to "😊",
        "sad" to "😢", "cry" to "😭", "lol" to "😂", "haha" to "😂", "laugh" to "😂", "funny" to "😂",
        "cool" to "😎", "wow" to "😮", "omg" to "😱", "angry" to "😠", "mad" to "😠",
        "kiss" to "😘", "wink" to "😉", "party" to "🎉", "birthday" to "🎂", "cake" to "🎂",
        "gift" to "🎁", "star" to "⭐", "sun" to "☀️", "snow" to "❄️", "coffee" to "☕",
        "beer" to "🍺", "wine" to "🍷", "pizza" to "🍕", "food" to "🍔", "music" to "🎵",
        "money" to "💰", "phone" to "📱", "home" to "🏠", "car" to "🚗", "dog" to "🐶", "cat" to "🐱",
        "thanks" to "🙏", "please" to "🙏", "sleep" to "😴", "tired" to "😴", "sick" to "🤒",
        "think" to "🤔", "eyes" to "👀", "hi" to "👋", "hello" to "👋", "bye" to "👋", "hug" to "🤗",
        "strong" to "💪", "clap" to "👏", "perfect" to "💯", "rocket" to "🚀", "flower" to "🌸", "rose" to "🌹",
        "любовь" to "❤️", "сердце" to "❤️", "люблю" to "❤️", "огонь" to "🔥", "круто" to "😎",
        "смех" to "😂", "ахах" to "😂", "хаха" to "😂", "смешно" to "😂", "грустно" to "😢",
        "плачу" to "😭", "счастье" to "😊", "улыбка" to "😊", "злой" to "😠", "поцелуй" to "😘",
        "праздник" to "🎉", "торт" to "🎂", "подарок" to "🎁", "звезда" to "⭐", "солнце" to "☀️",
        "кофе" to "☕", "пиво" to "🍺", "вино" to "🍷", "пицца" to "🍕", "еда" to "🍔",
        "музыка" to "🎵", "деньги" to "💰", "телефон" to "📱", "дом" to "🏠", "машина" to "🚗",
        "собака" to "🐶", "кошка" to "🐱", "кот" to "🐱", "спасибо" to "🙏", "привет" to "👋",
        "пока" to "👋", "сила" to "💪", "цветок" to "🌸", "роза" to "🌹", "думаю" to "🤔",
        "сон" to "😴", "устал" to "😴", "вау" to "😮", "да" to "✅", "нет" to "❌"
    )

    /** If the text right before the cursor ends with an emoticon (preceded by a space or start of
     *  text), swap it for the matching emoji. Returns true if it replaced. */
    private fun maybeReplaceEmoticon(): Boolean {
        if (secureField || fieldNoSuggestions) return false
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(4, 0)?.toString() ?: return false
        for ((emo, glyph) in emoticons) {
            if (before.endsWith(emo)) {
                val pre = before.dropLast(emo.length)
                if (pre.isEmpty() || pre.last() == ' ' || pre.last() == '\n') {
                    composing.clear()
                    ic.finishComposingText()
                    ic.deleteSurroundingText(emo.length, 0)
                    ic.commitText(glyph, 1)
                    return true
                }
            }
        }
        return false
    }

    /** Commit the active composing word (if any) so the following edit starts on clean text. */
    private fun finalizeComposing() {
        autocorrectUndo = null   // any non-typing action commits the autocorrect (no longer revertable)
        if (composing.isEmpty()) return
        currentInputConnection?.finishComposingText()
        composing.clear()
    }

    // ---- Autocorrect undo (Gboard-style) --------------------------------------------------------
    // After a space-autocorrect ("пивет " -> "привет "), the very next Backspace restores what you
    // actually typed. [autocorrectUndo] holds the committed corrected word + the original; it is
    // armed in finishWord and consumed/cleared in deleteSmart, on the next keystroke, or on any
    // cursor/edit action. Words the user reverts go in [revertedWords] and are never re-corrected.
    private class AutocorrectUndo(val corrected: String, val original: String)
    private var autocorrectUndo: AutocorrectUndo? = null
    private val revertedWords = HashSet<String>()

    // ---- Glide (swipe) typing -------------------------------------------------------------------

    /** The letter key whose cell contains window-point [p], or null. */
    private fun keyAt(p: Offset): Char? = keyBounds.entries.firstOrNull { it.value.contains(p) }?.key

    /** The letter key whose centre is nearest window-point [p]. */
    private fun nearestKey(p: Offset): Char? =
        keyBounds.entries.minByOrNull { (it.value.center - p).getDistanceSquared() }?.key

    private fun avgKeyWidth(): Float {
        if (keyBounds.isEmpty()) return 1f
        var s = 0f; for (r in keyBounds.values) s += r.width
        return (s / keyBounds.size).coerceAtLeast(1f)
    }

    // Physical key-adjacency map (each letter -> the keys within ~1.7 key-widths of it), used by
    // spatial autocorrect to recognise fat-finger taps. Rebuilt lazily from the current layout's
    // keyBounds and invalidated (cleared) alongside keyBounds on every language/layout switch.
    private var keyNeighbors: Map<Char, Set<Char>> = emptyMap()
    private fun neighbors(): Map<Char, Set<Char>> {
        if (keyNeighbors.isEmpty() && keyBounds.size >= 2) {
            val entries = keyBounds.entries.toList()
            val thr = avgKeyWidth() * 1.7f
            val thr2 = thr * thr
            val m = HashMap<Char, MutableSet<Char>>(entries.size * 2)
            for (i in entries.indices) for (j in entries.indices) if (i != j) {
                val a = entries[i]; val b = entries[j]
                if ((a.value.center - b.value.center).getDistanceSquared() <= thr2)
                    m.getOrPut(a.key) { HashSet() }.add(b.key)
            }
            keyNeighbors = m
        }
        return keyNeighbors
    }

    // ---- Coordinate spatial typing (v3) -------------------------------------------------------
    // Each letter tap's touch point (window coords) is recorded so autocorrect can weight candidates
    // by where the finger actually landed, not just which key it hit. `pendingTapPos` is set by the
    // glide overlay on touch-down and consumed by the next commitChar; null when unknown (then we
    // fall back to letter-only behaviour). The buffer self-validates by matching its chars against
    // the word, so backspaces/accents/edits simply make it fall back rather than misalign.
    private class Tap(val ch: Char, val point: Offset?)
    private val recentTaps = ArrayList<Tap>()
    private var pendingTapPos: Offset? = null

    private fun recordTap(ch: Char) {
        recentTaps.add(Tap(ch.lowercaseChar(), pendingTapPos))
        pendingTapPos = null
        if (recentTaps.size > 48) recentTaps.subList(0, recentTaps.size - 48).clear()
    }

    /** Gboard-style dynamic key targets: when a letter tap lands near the boundary between two
     *  keys, re-decide between them using where the finger physically landed AND which letter
     *  better continues the word being typed (dictionary prefix viability). Strictly conservative:
     *  word-starts and dead-centre taps are never second-guessed, and the runner-up key only wins
     *  on a clear combined margin (see [SuggestionEngine.chooseKey]). */
    private fun disambiguateTap(tapped: Char, point: Offset): Char {
        if (composing.isEmpty() || !SuggestionEngine.isReady() || keyBounds.size < 2) return tapped
        val kw = avgKeyWidth()
        if (kw <= 1f) return tapped
        val lower = tapped.lowercaseChar()
        val near = keyBounds.entries.sortedBy { (it.value.center - point).getDistanceSquared() }.take(2)
        if (near.size < 2 || near.none { it.key == lower }) return tapped
        val cands = near.map { it.key to (it.value.center - point).getDistance() / kw }
        val prefix = composing.toString().lowercase()
        val langs = dictLangs()
        val uni = UserDictionary.unigrams()
        val chosen = SuggestionEngine.chooseKey(lower, cands) { c ->
            SuggestionEngine.prefixStrength(prefix + c, langs, uni)
        }
        if (chosen == lower) return tapped
        return if (tapped.isUpperCase()) chosen.uppercaseChar() else chosen
    }

    /** Touch points for [wordLower] iff the last typed letters match it exactly AND every one was
     *  captured. Null otherwise — the signal is untrustworthy, so callers must fall back to v1. */
    private fun tapsFor(wordLower: String): List<Offset>? {
        val n = wordLower.length
        if (n == 0 || recentTaps.size < n) return null
        val out = ArrayList<Offset>(n)
        for (i in 0 until n) {
            val r = recentTaps[recentTaps.size - n + i]
            val p = r.point ?: return null
            if (SuggestionEngine.foldKey(r.ch.toString()) != SuggestionEngine.foldKey(wordLower[i].toString())) return null
            out.add(p)
        }
        return out
    }

    /** Average per-letter distance (in key-widths) between [points] and [cand]'s own key centres.
     *  Lower = the finger physically spelled [cand]. Null when a char has no key on this layout. */
    /** Physical plausibility of [cand] as a SUBSTITUTION slip of [typed]: the WORST distance (in
     *  key-widths) between the finger's tap and [cand]'s key, over only the positions where they
     *  differ. Small = the finger really hovered near the other key at every differing spot.
     *  Judged on the worst position, never the average — an average dilutes over the matching
     *  letters and would favour a wrong substitution over a true transposition (device-verified:
     *  "teh" was "corrected" to "ten" instead of "the"). Null when not a same-length substitution
     *  pattern or any tap/key is missing. */
    private fun spatialSlipCost(typed: String, cand: String, points: List<Offset>, bounds: Map<Char, Rect>): Float? {
        if (cand.length != typed.length || typed.length != points.size || bounds.isEmpty()) return null
        val ft = SuggestionEngine.foldKey(typed)
        val fc = SuggestionEngine.foldKey(cand)
        if (ft.length != fc.length) return null
        var kwSum = 0f
        for (r in bounds.values) kwSum += r.width
        val kw = (kwSum / bounds.size).coerceAtLeast(1f)
        var worst = -1f
        for (i in fc.indices) {
            if (ft[i] == fc[i]) continue
            val ctr = bounds[cand[i]]?.center ?: bounds[fc[i]]?.center ?: return null
            val d = (points[i] - ctr).getDistance() / kw
            if (d > worst) worst = d
        }
        return if (worst < 0f) null else worst
    }

    /** Resample a polyline to exactly [n] points spaced evenly along its length (a $1-recognizer step). */
    private fun resample(pts: List<Offset>, n: Int): List<Offset> {
        if (pts.isEmpty()) return emptyList()
        if (pts.size == 1) return List(n) { pts[0] }
        var total = 0f
        for (i in 1 until pts.size) total += (pts[i] - pts[i - 1]).getDistance()
        if (total <= 0f) return List(n) { pts[0] }
        val interval = total / (n - 1)
        val out = ArrayList<Offset>(n)
        out.add(pts[0])
        var prev = pts[0]; var acc = 0f; var i = 1
        while (i < pts.size && out.size < n) {
            val curr = pts[i]; val seg = (curr - prev).getDistance()
            if (acc + seg >= interval && seg > 0f) {
                val t = (interval - acc) / seg
                val np = Offset(prev.x + t * (curr.x - prev.x), prev.y + t * (curr.y - prev.y))
                out.add(np); prev = np; acc = 0f
            } else { acc += seg; prev = curr; i++ }
        }
        while (out.size < n) out.add(pts.last())
        return out
    }

    /** Decode a glide path (window coords) into up to 3 candidate words, best first. Two channels:
     *  a LOCATION score (the path must pass near each of the word's keys, in order) and a SHAPE score
     *  (overall stroke similarity), with a light frequency prior. Endpoints are matched loosely
     *  (nearest 2 keys) since the finger can land slightly off the intended first/last letter. */
    private fun decodeGlide(
        path: List<Offset>,
        followers: Map<String, Int>?,
        bounds: Map<Char, Rect>,
        langs: List<String>
    ): List<String> {
        if (path.size < 2 || bounds.isEmpty() || !SuggestionEngine.isReady()) return emptyList()
        // Self-contained over the snapshotted [bounds] so it can run on a background dispatcher
        // while the live keyBounds keep mutating with the layout.
        fun nearestIn(p: Offset, k: Int): List<Char> =
            bounds.entries.sortedBy { (it.value.center - p).getDistanceSquared() }.take(k).map { it.key }
        val firstFolds = nearestIn(path.first(), 2).map { SuggestionEngine.foldKey(it.toString())[0] }.toHashSet()
        val lastFolds = nearestIn(path.last(), 2).map { SuggestionEngine.foldKey(it.toString())[0] }.toHashSet()
        if (firstFolds.isEmpty() || lastFolds.isEmpty()) return emptyList()
        val n = 32
        val swipeR = resample(path, n)
        var kwSum = 0f
        for (r in bounds.values) kwSum += r.width
        val keyW = (kwSum / bounds.size).coerceAtLeast(1f)
        // Total finger-travel length: a long swipe means a long word. Candidates whose ideal
        // key-to-key polyline is much shorter (or longer) than the actual path get penalised, so
        // a deliberate long glide no longer loses to a short word sharing its first/last key.
        var pathLen = 0f
        for (i in 1 until path.size) pathLen += (path[i] - path[i - 1]).getDistance()
        val words = SuggestionEngine.wordList(langs)
        val scored = ArrayList<Pair<String, Float>>()
        var matched = 0
        for (rank in words.indices) {
            val w = words[rank]
            if (w.length < 2) continue
            // Match on the base-key skeleton: a glide crosses base letters only, so diacritic words
            // ("ēst", "viņš") are decoded by their e-s-t / v-i-n-s path; the dict supplies the accents.
            val fw = SuggestionEngine.foldKey(w)
            if (fw[0] !in firstFolds || fw[fw.length - 1] !in lastFolds) continue
            var ok = true
            val ideal = ArrayList<Offset>(fw.length)
            // Collapse consecutive duplicate keys: a glide crosses a repeated letter once ("hello" is
            // swiped h-e-l-o), so match the de-duplicated key skeleton and let the dictionary pick
            // between e.g. "hello" and a single-l word by frequency/context.
            var lastC = ' '
            for (c in fw) {
                if (c == lastC) continue
                lastC = c
                val ctr = bounds[c]?.center
                if (ctr == null) { ok = false; break }
                ideal.add(ctr)
            }
            if (!ok || ideal.size < 2) continue
            val idealR = resample(ideal, n)
            // Shape: average per-point offset between the two normalized-length strokes.
            var shape = 0f
            for (k in 0 until n) shape += (swipeR[k] - idealR[k]).getDistance()
            // Location: each word key's nearest swipe point, constrained to move forward (in order).
            var loc = 0f; var idx = 0
            for (ctr in ideal) {
                var best = Float.MAX_VALUE; var bi = idx
                for (j in idx until swipeR.size) {
                    val dd = (swipeR[j] - ctr).getDistanceSquared()
                    if (dd < best) { best = dd; bi = j }
                }
                loc += kotlin.math.sqrt(best); idx = bi
            }
            loc /= ideal.size
            val freq = kotlin.math.ln(1f + rank) * keyW * 0.06f   // gentle bias toward common words
            // Path-length agreement: |actual swipe length − candidate's ideal polyline length|,
            // normalised by the swipe length. The correct word wiggles ~20% over its ideal line
            // (small penalty); a short word matched against a long deliberate swipe is off by
            // 60-80% (large penalty) — this resolves the short-vs-long hesitation.
            var idealLen = 0f
            for (k in 1 until ideal.size) idealLen += (ideal[k] - ideal[k - 1]).getDistance()
            val lenMismatch = keyW * 1.2f *
                kotlin.math.abs(pathLen - idealLen) / kotlin.math.max(pathLen, 1f)
            // loc (did the path pass near each key, in order) leads; shape (overall stroke similarity)
            // gets a bit more weight than before to better separate same-key-set words; freq breaks ties.
            var score = loc + 0.22f * (shape / n) + freq + lenMismatch
            val bg = followers?.get(w) ?: 0
            if (bg > 0) score -= keyW * (0.2f + 0.08f * minOf(bg, 5))   // learned-context boost
            scored.add(w to score)
            if (++matched >= 1500) break
        }
        if (scored.isEmpty()) return emptyList()
        return scored.sortedBy { it.second }.map { it.first }.take(3)
    }

    /** Throttled live decode while the finger is still swiping: at most one decode in flight, and
     *  only after enough new points accumulated. Feeds the floating preview chip above the keys. */
    private fun glidePreviewTick(points: List<Offset>) {
        if (glidePreviewBusy || points.size - glidePreviewAt < 6) return
        glidePreviewBusy = true
        glidePreviewAt = points.size
        val path = points.map { it + glideOrigin }   // copies the live list (still on main thread)
        val bounds = HashMap(keyBounds)
        val langs = dictLangs()
        serviceScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val best = try {
                decodeGlide(path, null, bounds, langs).firstOrNull()
            } catch (_: Throwable) { null }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                glidePreviewBusy = false
                if (glideActive.value && best != null) glidePreview.value = best
            }
        }
    }

    /** Finish a glide: insert the decoded word + an auto-space (Gboard-style) and offer the alternates
     *  in the suggestion strip so a wrong guess is one tap away. [localPath] is key-grid-local coords. */
    private fun commitGlide(localPath: List<Offset>) {
        if (secureField || fieldNoSuggestions) return
        val prev = wordBefore(currentWordBeforeCursor())   // word before the start key's dropped letter
        // Snapshot main-thread state, then decode on a background dispatcher — the decoder resamples
        // hundreds of candidate polylines and would jank the UI right at finger-up.
        val path = localPath.map { it + glideOrigin }
        val bounds = HashMap(keyBounds)
        val langs = dictLangs()
        // Context: words the user has actually typed/glided after [prev] get a boost, so common
        // phrases win over equally-shaped but unrelated words.
        val followers = prev?.lowercase()?.let {
            SuggestionEngine.followerWeights(it, langs, UserDictionary.bigrams())
        }
        val composingBefore = composing.toString()
        serviceScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val cands = try {
                decodeGlide(path, followers, bounds, langs)
            } catch (e: Throwable) {
                android.util.Log.e("Keyo", "decodeGlide failed", e)
                emptyList()
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (cands.isEmpty()) return@withContext
                // The user typed/deleted while we were decoding — don't splice a stale word in.
                if (composing.toString() != composingBefore) return@withContext
                val ic = currentInputConnection ?: return@withContext
                val cap = composing.isNotEmpty() && composing[0].isUpperCase()
                fun cased(s: String) = if (cap) s.replaceFirstChar { it.uppercaseChar() } else s
                val word = cased(cands[0])
                composing.setLength(0)
                ic.setComposingText(word, 1)   // replace the lone letter the start key dropped on touch-down
                ic.finishComposingText()
                composing.clear()
                ic.commitText(" ", 1)          // auto-space so the next word/glide flows on
                performKeyFeedback()
                isShift.value = false; shiftIsAuto = false
                autocorrectUndo = null
                glideWord = word
                glideAlts = cands.map { cased(it) }.distinct()
                // Learn the phrase (prev -> word) so context re-ranking improves over time. Like
                // typing, a glide doesn't add the word itself to the dictionary (bumpUnigram =
                // false); skipped when the field forbids personalization (incognito).
                if (!noLearn) {
                    UserDictionary.learn(prev?.lowercase(), cands[0], bumpUnigram = false)
                    scheduleUserDictSave()
                }
                updateSuggestions()            // shows glideAlts (text now ends with "<word> ")
            }
        }
    }

    // Note: haptic feedback is triggered by the caller (KeyButton tap / custom keys),
    // not here, so every key vibrates exactly once.
    private fun commitChar(char: Char) {
        val ic = currentInputConnection
        // Flush a still-pending space tap (its key-up hasn't arrived yet) BEFORE this character, so a
        // fast next-letter keypress lands after the space instead of gluing onto the previous word.
        if (pendingSpaceTap) {
            pendingSpaceTap = false
            finishWord()
            ic?.commitText(" ", 1)
        }
        autocorrectUndo = null   // typing past a just-corrected word accepts the correction
        val hadAutoSpace = pendingAutoSpace
        pendingAutoSpace = false
        // Glide smart-space: typing on dismisses the glide alternates; sentence punctuation pulls
        // back onto the word ("word ." -> "word.") by removing the auto-space; a single manual
        // space right after the glide is swallowed (the space is already there) so the auto-space
        // can't trigger an accidental double-space period.
        if (glideWord != null) {
            glideWord = null
            val afterAutoSpace = ic?.getTextBeforeCursor(1, 0)?.toString() == " "
            if (char == ' ' && afterAutoSpace) {
                maybeAutoCapitalize()
                updateSuggestions()
                return
            }
            if (char in ".,!?;:" && afterAutoSpace) {
                ic.deleteSurroundingText(1, 0)
            }
        }
        // Smart spacing after a suggestion's auto-inserted trailing space (Gboard-style), mirroring
        // the glide smart-space above: a redundant manual space is swallowed (it's already there, so
        // it can't trigger an accidental double-space period), and sentence punctuation pulls the
        // space back so it hugs the word ("word " + "." -> "word.").
        if (hadAutoSpace && ic?.getTextBeforeCursor(1, 0)?.toString() == " ") {
            if (char == ' ') { maybeAutoCapitalize(); updateSuggestions(); return }
            if (char in ".,!?;:") ic.deleteSurroundingText(1, 0)
        }
        // Word characters build up the composing region (except in secure / no-suggestion fields,
        // where we never expose composing text). The editor doesn't spell-check composing text ->
        // no red underline.
        if (isWordChar(char) && !secureField && !fieldNoSuggestions) {
            // Editing inside an existing word (a word char follows the caret): insert plainly at the
            // caret with NO composing region. Keeps the cursor exactly where it is and avoids running
            // autocorrect/suggestions on a half-word fragment. (Any active composing word was already
            // finalized by onUpdateSelection when the caret moved into the word.)
            val tail = ic?.getTextAfterCursor(1, 0)?.toString() ?: ""
            if (tail.isNotEmpty() && isWordChar(tail[0])) {
                finalizeComposing()
                ic?.commitText(char.toString(), 1)
                pendingTapPos = null
                maybeAutoCapitalize()
                updateSuggestions()
                return
            }
            // Probabilistic key targeting: a tap near a key boundary picks the letter that better
            // continues the current word (needs the touch point captured by the glide overlay).
            var ch = char
            pendingTapPos?.let { p ->
                if (char.isLetter() && keyboardMode.value == "abc") ch = disambiguateTap(char, p)
            }
            // NOTE: we deliberately do NOT "re-adopt" an already-committed word back into the
            // composing region here. That used to delete the word with deleteSurroundingText and
            // re-add it — which, racing a just-committed space during fast typing, ate the space
            // ("чау как" -> "чаукак"). Continuing a word just appends a new composing run; finishWord
            // still autocorrects the whole word on the next boundary via currentWordBeforeCursor.
            composing.append(ch)
            recordTap(ch)   // remember where the finger landed, for coordinate spatial autocorrect
            ic?.setComposingText(composing.toString(), 1)
            // Emoticons ending in a letter (e.g. :D :P xD) complete here.
            if (ch in emoticonEndChars && maybeReplaceEmoticon()) { maybeAutoCapitalize(); updateSuggestions(); return }
            maybeAutoCapitalize()
            updateSuggestions()
            return
        }
        pendingTapPos = null   // not part of a word: drop any captured touch point
        // Double-space -> ". " (only after a word character)
        if (char == ' ' && doubleSpaceOn) {
            val before = ic?.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic?.deleteSurroundingText(1, 0)
                ic?.commitText(". ", 1)
                maybeAutoCapitalize()
                updateSuggestions()
                return
            }
        }
        // Finishing a word (space / newline / sentence punctuation): finalize + optionally autocorrect.
        // Any other non-word char: just commit the active word first, then the char.
        if (char == ' ' || char == '\n' || char in ".,!?;:") finishWord()
        else finalizeComposing()
        ic?.commitText(char.toString(), 1)
        // Gboard-style: a space on the symbol/number pages jumps back to letters, so after ending a
        // sentence ("?", "!", …) + space you're ready to type the next word without switching by hand.
        if (char == ' ' && (keyboardMode.value == "123" || keyboardMode.value == "symbols")) {
            keyboardMode.value = "abc"
        }
        maybeAutoCapitalize()
        updateSuggestions()
    }

    // ---- Track C: word suggestions / learning -------------------------------------------------

    /** The run of word characters immediately before the cursor (the word being typed). */
    private fun currentWordBeforeCursor(): String {
        val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString() ?: return ""
        val sb = StringBuilder()
        for (i in before.indices.reversed()) {
            val c = before[i]
            if (c.isLetter() || c == '\'' || c == '-') sb.append(c) else break
        }
        return sb.reverse().toString()
    }

    /** The run of word characters immediately after the cursor (the tail of a word being edited
     *  from its middle). Empty when the caret sits at the end of a word / on whitespace. */
    private fun wordAfterCursor(): String {
        val after = currentInputConnection?.getTextAfterCursor(48, 0)?.toString() ?: return ""
        val sb = StringBuilder()
        for (c in after) { if (isWordChar(c)) sb.append(c) else break }
        return sb.toString()
    }

    /** The word preceding [currentWord] (text before the cursor still ends with currentWord). */
    private fun wordBefore(currentWord: String): String? {
        val before = currentInputConnection?.getTextBeforeCursor(96, 0)?.toString() ?: return null
        if (!before.endsWith(currentWord)) return null
        val head = before.dropLast(currentWord.length).trimEnd()
        return trailingWord(head)
    }

    /** When the text before the cursor ends with "<word> " (single space), returns that word. */
    private fun wordBeforeTrailingSpace(): String? {
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return null
        if (before.isEmpty() || before.last() != ' ') return null
        return trailingWord(before.trimEnd(' '))
    }

    private fun trailingWord(s: String): String? {
        if (s.isEmpty()) return null
        val sb = StringBuilder()
        for (i in s.indices.reversed()) {
            val c = s[i]
            if (c.isLetter() || c == '\'' || c == '-') sb.append(c) else break
        }
        return if (sb.isEmpty()) null else sb.reverse().toString()
    }

    /** Apply the typed word's capitalization pattern (ALL-CAPS / Title / lower) to [candidate]. */
    private fun matchCase(typed: String, candidate: String): String = when {
        typed.isEmpty() -> candidate
        typed.length > 1 && typed.all { it.isUpperCase() } -> candidate.uppercase()
        typed[0].isUpperCase() -> candidate.replaceFirstChar { it.uppercase() }
        else -> candidate
    }

    private fun setSuggestions(list: List<String>, primary: String?) {
        suggestions.value = list
        primarySuggestion.value = primary
        if (list.isEmpty()) toolbarPinned.value = false   // idle row -> menu returns next time
    }

    // Generation counter for async suggestion computation: each call bumps it, and a finishing
    // background pass only publishes if it is still the latest — stale results are dropped.
    private var suggGen = 0L

    /** Recompute the suggestion strip from the text around the cursor. The InputConnection reads
     *  happen here on the main thread; the dictionary scans (the expensive part — full-vocab prefix
     *  scan + edit-distance pass) run on a background dispatcher so fast typing never janks the UI. */
    private fun updateSuggestions() {
        if (secureField || fieldNoSuggestions || !suggestionsOn || !SuggestionEngine.isReady()) {
            setSuggestions(emptyList(), null); return
        }
        // Never let a suggestion computation crash the IME — onUpdateSelection runs this and an
        // uncaught throw there would take the keyboard down with it.
        try {
        val gen = ++suggGen
        val langs = dictLangs()
        // Right after a glide: show the decoded alternates (while the text still ends with "<word> ").
        val gw = glideWord
        if (gw != null) {
            val before = currentInputConnection?.getTextBeforeCursor(gw.length + 1, 0)?.toString()
            if (before != null && before.length == gw.length + 1 && before.startsWith(gw) && before.last() == ' ') {
                setSuggestions(glideAlts.take(3), gw); return
            }
            glideWord = null   // moved past it — fall through to normal suggestions
        }
        val word = currentWordBeforeCursor()
        if (word.isEmpty()) {
            val prev = wordBeforeTrailingSpace()
            val nexts = if (prev != null)
                SuggestionEngine.nextWords(prev.lowercase(), langs, UserDictionary.bigrams(), 3)
            else emptyList()
            // Right after an autocorrect: surface the original word as a one-tap revert (Gboard
            // underlines the corrected word; a strip chip is deterministic in every editor).
            val undo = autocorrectUndo
            if (undo != null) {
                val n = undo.corrected.length + 1
                val before = currentInputConnection?.getTextBeforeCursor(n, 0)?.toString()
                if (before != null && before.length == n && before.startsWith(undo.corrected)) {
                    setSuggestions(listOf("↩ ${undo.original}") + nexts.take(2), null)
                    return
                }
            }
            // Next-word prediction right after "<word> ". Cheap (bigram map lookups) — stays sync.
            if (nexts.isNotEmpty()) setSuggestions(nexts, nexts.firstOrNull())
            else setSuggestions(emptyList(), null)
            return
        }
        // Snapshot everything the background pass needs: the learned maps are mutated on the main
        // thread, and the tap points / key bounds change with the layout — copies keep it race-free.
        val uni = HashMap(UserDictionary.unigrams())
        val prevW = wordBefore(word)
        val prefer = if (prevW != null)
            SuggestionEngine.followerWeights(prevW.lowercase(), langs, UserDictionary.bigrams()).keys.toSet()
        else emptySet()
        val pts = tapsFor(word.lowercase())
        val bounds = if (pts != null) HashMap(keyBounds) else null
        val prevLower = prevW?.lowercase()
        val emoji = emojiKeywords[word.lowercase()]   // offer a matching emoji as an extra strip chip
        serviceScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val result = try {
                computeSuggestions(word, langs, uni, prefer, prevLower, pts, bounds)
            } catch (e: Throwable) {
                android.util.Log.e("Keyo", "computeSuggestions failed", e)
                emptyList<String>() to null
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (gen == suggGen) {
                    val list = if (emoji != null && emoji !in result.first) result.first + emoji else result.first
                    setSuggestions(list, result.second)
                }
            }
        }
        } catch (e: Throwable) {
            android.util.Log.e("Keyo", "updateSuggestions failed", e)
            setSuggestions(emptyList(), null)
        }
    }

    /** The heavy part of the suggestion strip, safe to run off the main thread (pure functions over
     *  snapshotted data). Returns (list, primary). */
    private fun computeSuggestions(
        word: String,
        langs: List<String>,
        uni: Map<String, Int>,
        prefer: Set<String>,
        prevLower: String?,
        pts: List<Offset>?,
        bounds: Map<Char, Rect>?
    ): Pair<List<String>, String?> {
        val lower = word.lowercase()
        val known = SuggestionEngine.isKnown(lower, langs, uni)
        // Fetch a few extra completions, then let sentence context pick the top ones: bigram
        // followers of the previous word first, plus the Russian number-agreement nudge
        // ("какие иде…" -> "идеи", not the globally-more-frequent "идея").
        val comp = SuggestionEngine.rankCompletions(
            SuggestionEngine.complete(lower, langs, uni, 12), prevLower, prefer)
        // Strip layout mirrors Gboard: leftmost is exactly what you typed (tap to keep / add to dict).
        val list = LinkedHashSet<String>()
        list.add(word)
        var primary: String? = null
        if (comp.isNotEmpty()) {
            // Valid prefix -> mid-typing: offer completions; default to the top one unless the word
            // is already a complete known word (then leave what you typed as the default).
            // A typo can also be a valid prefix of a rarer word ("teh" -> "Tehran"): when a
            // correction is much more frequent than the best completion, the typo is the likelier
            // intent — Gboard defaults to "the" there, not "Tehran".
            var corrPrimary: String? = null
            if (!known) {
                val corr = SuggestionEngine.corrections(lower, langs, uni, 2, prefer = prefer)
                if (corr.isNotEmpty()) {
                    val vocabList = SuggestionEngine.wordList(langs)
                    val corrRank = vocabList.indexOf(corr[0]).let { if (it < 0) 0 else it }
                    val compRank = vocabList.indexOf(comp[0]).let { if (it < 0) 0 else it }
                    if (corrRank < compRank * 4) corrPrimary = corr[0]
                }
            }
            if (corrPrimary != null) {
                primary = matchCase(word, corrPrimary)
                list.add(primary)
            }
            comp.forEach { list.add(matchCase(word, it)) }
            if (primary == null) primary = if (known) word else matchCase(word, comp[0])
        } else if (!known) {
            // No completions and not a known word -> likely a typo. Offer corrections like Gboard:
            // [typed | best correction (bold) | a longer form of it]. Space applies the best one.
            // Context: prefer a fix that fits the previous word (bundled + learned bigrams).
            var corr = SuggestionEngine.corrections(lower, langs, uni, 2, prefer = prefer)
            // Spatial re-rank, promote-only: fixes whose differing keys the finger physically
            // grazed (slip cost ≤ 0.75 key-widths) float to the front in cost order; everything
            // else (incl. transpositions, where taps are far by construction) keeps rank order.
            if (corr.size > 1 && pts != null && bounds != null) {
                corr = corr.mapIndexed { i, c ->
                    val slip = spatialSlipCost(lower, c, pts, bounds)
                    c to (if (slip != null && slip <= 0.75f) slip else 1f + i * 0.08f)
                }.sortedBy { it.second }.map { it.first }
            }
            if (corr.isNotEmpty()) {
                primary = matchCase(word, corr[0])
                list.add(primary)
                SuggestionEngine.complete(corr[0], langs, uni, 2).firstOrNull { it != corr[0] }
                    ?.let { list.add(matchCase(word, it)) }
                corr.drop(1).forEach { list.add(matchCase(word, it)) }
            }
        }
        return list.take(3) to (primary ?: word)
    }

    /** Learn from an explicit suggestion tap: always record the phrase (prev -> word) for context,
     *  but only add the word to the *personal* dictionary when it isn't already a built-in word — so
     *  tapping common words (e.g. "привет") doesn't clutter your saved words or waste its capacity. */
    private fun learnFromTap(prev: String?, wordLower: String) {
        if (noLearn) return   // incognito / password: don't personalize
        val isNewWord = !SuggestionEngine.isKnown(wordLower, dictLangs(), emptyMap())
        UserDictionary.learn(prev, wordLower, bumpUnigram = isNewWord)
    }

    /** Tap on the "↩ original" strip chip: swap the autocorrected word back, KEEPING the boundary
     *  char (unlike Backspace-revert, which deletes it — here the user isn't deleting anything).
     *  Keeping the typed word is a strong signal, so it is learned like an explicit tap. */
    private fun revertAutocorrectKeepBoundary() {
        val undo = autocorrectUndo ?: return
        autocorrectUndo = null
        val ic = currentInputConnection ?: return
        val n = undo.corrected.length + 1
        val before = ic.getTextBeforeCursor(n, 0)?.toString() ?: return
        if (before.length == n && before.startsWith(undo.corrected)) {
            revertedWords.add(SuggestionEngine.fold(undo.original.lowercase()))
            ic.deleteSurroundingText(n, 0)
            ic.commitText(undo.original + before.last(), 1)
            if (!noLearn) {
                learnFromTap(null, undo.original.lowercase())
                scheduleUserDictSave()
            }
        }
        updateSuggestions()
    }

    /** Replace the in-progress word (or insert a predicted next word) with [s] and learn it. */
    private fun applySuggestion(s: String) {
        performKeyFeedback()
        if (s.startsWith("↩ ")) { revertAutocorrectKeepBoundary(); return }
        val ic = currentInputConnection ?: return
        // Emoji suggestion (e.g. typed "love" -> tapped ❤️): replace the word with the emoji, but
        // don't learn the glyph as a personal word. Detected by a high first code point (emoji/symbol).
        val isEmoji = s.isNotEmpty() && s[0].code > 0x2000
        // Tapping a glide alternate: swap the just-glided word for it, keeping the auto-space.
        val gw = glideWord
        if (gw != null) {
            val before = ic.getTextBeforeCursor(gw.length + 1, 0)?.toString()
            if (before != null && before.length == gw.length + 1 && before.startsWith(gw) && before.last() == ' ') {
                if (s != gw) { ic.deleteSurroundingText(gw.length + 1, 0); ic.commitText("$s ", 1) }
                glideWord = s
                // Explicit correction: learn the word AND the phrase (prev -> s) — a strong context
                // signal so this glide decodes right next time.
                val full = ic.getTextBeforeCursor(s.length + 1 + 48, 0)?.toString() ?: ""
                val prevPart = if (full.length >= s.length + 1) full.dropLast(s.length + 1).trimEnd() else ""
                learnFromTap(trailingWord(prevPart)?.lowercase(), s.lowercase())
                scheduleUserDictSave()
                updateSuggestions()
                return
            }
            glideWord = null
        }
        val word = currentWordBeforeCursor()
        // Defensive: a composing buffer that covers only part of the visible word would make
        // setComposingText replace just that part (duplicating text). Finalize and use the
        // committed-text path instead.
        if (composing.isNotEmpty() && composing.toString() != word) {
            ic.finishComposingText()
            composing.clear()
        }
        val prev: String?
        // Batch the edits so the editor reports a SINGLE selection update (the skip-flag absorbs it).
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            // The active word lives in the composing region: swap it for the suggestion + finalize.
            prev = wordBefore(word)
            ic.setComposingText(s, 1)
            ic.finishComposingText()
            composing.clear()
            ic.commitText(" ", 1)
            pendingAutoSpace = true
        } else if (word.isNotEmpty()) {
            prev = wordBefore(word)
            // Replace the WHOLE word, including any tail after the caret (suggestion tapped while
            // editing mid-word) — never split it. Trailing space only at a real word end.
            val tail = wordAfterCursor()
            ic.deleteSurroundingText(word.length, tail.length)
            if (tail.isEmpty()) { ic.commitText("$s ", 1); pendingAutoSpace = true }
            else ic.commitText(s, 1)
        } else {
            prev = wordBeforeTrailingSpace()
            ic.commitText("$s ", 1)
            pendingAutoSpace = true
        }
        ic.endBatchEdit()
        if (pendingAutoSpace) autoSpaceSkipNextSel = true
        if (!isEmoji) {
            learnFromTap(prev?.lowercase(), s.lowercase())
            scheduleUserDictSave()
        }
        isShift.value = false; shiftIsAuto = false
        updateSuggestions()
    }

    /** On a word boundary: finalize the composing word, autocorrect it (if enabled), learn it. */
    private fun finishWord() {
        if (secureField || fieldNoSuggestions) { finalizeComposing(); return }
        val ic = currentInputConnection
        // A boundary typed in the MIDDLE of a word (a word char follows the caret) splits it — don't
        // autocorrect/learn the left fragment as if it were a finished word.
        val afterCaret = ic?.getTextAfterCursor(1, 0)?.toString() ?: ""
        if (afterCaret.isNotEmpty() && isWordChar(afterCaret[0])) { finalizeComposing(); return }
        // Defensive: if the composing buffer covers only part of the visible word (it normally won't,
        // but editors can surprise), finalize it and treat the whole word as committed text so
        // autocorrect sees the full word, not just the buffered part.
        if (composing.isNotEmpty() && composing.toString() != currentWordBeforeCursor()) {
            ic?.finishComposingText()
            composing.clear()
        }
        val composingWord = composing.isNotEmpty()
        val word = if (composingWord) composing.toString() else currentWordBeforeCursor()
        if (word.isEmpty()) return
        val prev = wordBefore(word)
        var learned = word.lowercase()
        var finalWord = word
        // English niceties applied FIRST (so autocorrect can't mangle "dont" -> "done"): a standalone
        // "i"/"i'..." -> "I...", and apostrophe-less contractions (dont -> don't, im -> I'm, youre ->
        // you're, ...). Only when English is active, independent of the autocorrect toggle, and NOT
        // Backspace-revertable. Forms that are valid words on their own are kept out of enContractions.
        var enFixed = false
        if ("en" in dictLangs() && !revertedWords.contains(SuggestionEngine.fold(learned))) {
            val c = enContractions[finalWord.lowercase()]
            if (c != null) { finalWord = matchCase(finalWord, c); enFixed = true }
            else if (finalWord == "i" || finalWord.startsWith("i'")) { finalWord = "I" + finalWord.substring(1); enFixed = true }
        }
        // Gboard-style autocorrect: swap an unknown typed word for the closest dictionary word.
        // correct() returns null for words already known (bundled or learned) and ranks by frequency,
        // so valid short words like "как"/"что" are never touched — only unknown typos (e.g. "кае").
        // We only auto-apply single-edit fixes — without word context, 2-edit guesses miss too often
        // (and stay available as tap suggestions). Words the user reverted this session are left alone.
        if (!enFixed && autocorrectTypingOn && SuggestionEngine.isReady() && word.length >= 3 &&
            !revertedWords.contains(SuggestionEngine.fold(learned))) {
            try {
                // Spatial-aware: among ranked corrections, apply the top single-edit fix (as before),
                // or a confident double fat-finger (two adjacent-key substitutions) the old rule missed.
                // Context (bundled + learned bigrams of the previous word) breaks ties toward a fix
                // that actually fits the sentence.
                val prefer = if (prev != null)
                    SuggestionEngine.followerWeights(prev.lowercase(), dictLangs(), UserDictionary.bigrams()).keys else emptySet()
                val cands = SuggestionEngine.corrections(learned, dictLangs(), UserDictionary.unigrams(), 12, maxEdits = 2, prefer = prefer)
                var corr = SuggestionEngine.pickAutocorrect(learned, cands, neighbors())
                // (v3) Coordinate refinement: only when v1 already decided to correct AND the taps for
                // this word were fully captured. Re-pick among the SAME candidate set the word whose
                // keys the finger was physically closest to — but only if that's clearly confident
                // (avg < 0.65 key-widths). Can't correct anything v1 wouldn't; just chooses better.
                if (corr != null) {
                    val pts = tapsFor(learned)
                    if (pts != null) {
                        // Substitution slips only, judged on the worst differing position: a
                        // candidate overrides the rank-best fix ONLY when the finger physically
                        // landed near its key at EVERY position where they differ.
                        val best = cands.filter { it.length == learned.length }
                            .mapNotNull { c -> spatialSlipCost(learned, c, pts, keyBounds)?.let { c to it } }
                            .minByOrNull { it.second }
                        if (best != null && best.second <= 0.65f) corr = best.first
                    }
                }
                // Context correction of VALID words (Gboard's "magic"): a slip can form a real
                // word ("знаю сто" for "знаю что"), which the unknown-word path never touches.
                // Extremely conservative: only when the previous word's context STRONGLY expects
                // another word (top bundled follower / well-learned pair), the typed word is not
                // itself an expected follower, and the fix is a single adjacent-key substitution
                // or transposition. Backspace-revert applies as usual.
                if (corr == null && prev != null &&
                    SuggestionEngine.isKnown(learned, dictLangs(), UserDictionary.unigrams())) {
                    val fw = SuggestionEngine.followerWeights(prev.lowercase(), dictLangs(), UserDictionary.bigrams())
                    if (fw.isNotEmpty() && (fw[learned] ?: 0) == 0) {
                        corr = fw.entries.asSequence()
                            .filter { it.value >= 7 }
                            .map { it.key }
                            .firstOrNull { SuggestionEngine.isConfidentSlip(learned, it, neighbors()) }
                    }
                }
                if (corr != null && SuggestionEngine.fold(corr) != SuggestionEngine.fold(learned)) {
                    finalWord = matchCase(word, corr)
                    learned = corr
                }
            } catch (e: Throwable) { android.util.Log.e("Keyo", "autocorrect failed", e) }
        }
        val corrected = finalWord != word
        if (composingWord) {
            // Replace the composing region with the corrected word (if changed), then finalize it.
            if (corrected) ic?.setComposingText(finalWord, 1)
            ic?.finishComposingText()
            composing.clear()
        } else if (corrected) {
            // Cursor sat inside already-committed text: patch the word in place.
            ic?.deleteSurroundingText(word.length, 0)
            ic?.commitText(finalWord, 1)
        }
        // Arm Backspace-to-revert: the caller commits the boundary char right after this, leaving
        // "<finalWord><boundary>" in the field; the next Backspace restores [word].
        // Any change (autocorrect, i->I, contraction) is one-tap revertable: the strip shows
        // "↩ <original>" and the first Backspace restores it (and won't be re-fixed this session).
        autocorrectUndo = if (corrected) AutocorrectUndo(finalWord, word) else null
        // Plain typing must NOT add the word to the dictionary — only an explicit suggestion tap
        // (applySuggestion) does. We still record the next-word pattern for prediction (unless the
        // field forbids personalization, e.g. incognito).
        if (!noLearn) {
            UserDictionary.learn(prev?.lowercase(), learned, bumpUnigram = false)
            scheduleUserDictSave()
        }
    }

    private fun scheduleUserDictSave() {
        handler.removeCallbacks(saveUserDict)
        handler.postDelayed(saveUserDict, 4000)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (composing.isNotEmpty()) {
            // Our own setComposingText(...,1) always leaves the caret collapsed exactly at the end of
            // the composing span, so that state means "this update came from our own keystroke".
            val caretAtComposingEnd = newSelStart == newSelEnd && newSelEnd == candidatesEnd
            when {
                // candidatesStart==-1 means no active composing span — normally an external drop (user
                // tapped elsewhere / app edited). But during FAST typing it's often a STALE, out-of-order
                // callback from a prior commit (e.g. the boundary space, which has no composing region)
                // that arrives AFTER we already started a new composing word. Clearing the buffer then
                // would let the next setComposingText overwrite the new word's first letter
                // ("чау как" -> "чау ак"). So only drop the buffer if the word is really gone from the
                // field; if it's still right before the cursor, the -1 is stale — keep the buffer.
                candidatesStart == -1 ->
                    if (!currentWordBeforeCursor().endsWith(composing.toString())) composing.clear()
                // Self-generated update from typing/backspace: commitChar/deleteSmart already refreshed
                // the strip + auto-shift, so skip the redundant (and costly) recompute.
                caretAtComposingEnd -> return
                // The caret moved INTO or away from the composing word (a mid-word tap, or a
                // selection was made). Finalize the word in place so the next keystroke edits real
                // text at the true caret instead of snapping back to the end of the composing region.
                else -> {
                    try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}
                    composing.clear()
                }
            }
        }
        // Invalidate a pending suggestion auto-space once the caret really moves (skip the single
        // self-update from our own suggestion commit) — stops punctuation eating an unrelated space.
        if (pendingAutoSpace) {
            if (autoSpaceSkipNextSel) autoSpaceSkipNextSel = false
            else pendingAutoSpace = false
        }
        maybeAutoCapitalize()   // keep Shift in sync when the cursor moves (arm at sentence starts, disarm mid-text)
        updateSuggestions()
    }

    /** Show a confirm bar with [summary] and suspend until the user approves or cancels. */
    private suspend fun requestConfirm(summary: String): Boolean {
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        confirmDeferred = d
        handler.post { pendingConfirm.value = summary }
        val ok = try { d.await() } catch (_: Exception) { false }
        handler.post { pendingConfirm.value = null }
        return ok
    }

    private fun resolveConfirm(ok: Boolean) {
        performKeyFeedback()
        confirmDeferred?.complete(ok)
        confirmDeferred = null
        pendingConfirm.value = null
    }

    // Auto-capitalize: arm Shift at the start of input / a new sentence. When the field declares
    // its own capitalization request (TYPE_TEXT_FLAG_CAP_*), follow the editor's precise answer via
    // getCursorCapsMode (handles cap-words/cap-characters fields, quotes, locale rules); otherwise
    // fall back to a local sentence heuristic. Also DISARMS a stale auto-shift when the cursor ends
    // up mid-sentence (e.g. after tapping into existing text) — a deliberate Shift is never touched.
    private fun maybeAutoCapitalize() {
        if (!autoCapOn || secureField) return
        val cls = fieldInputType and android.text.InputType.TYPE_MASK_CLASS
        if (cls != android.text.InputType.TYPE_CLASS_TEXT || fieldNoSuggestions) return
        val capFlags = fieldInputType and (
            android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val should = if (capFlags != 0) {
            ((currentInputConnection?.getCursorCapsMode(fieldInputType) ?: 0) != 0)
        } else {
            val before = currentInputConnection?.getTextBeforeCursor(3, 0)?.toString() ?: ""
            val trimmed = before.trimEnd(' ')
            before.isEmpty() || before.last() == '\n' ||
                (before.last() == ' ' && trimmed.isNotEmpty() && trimmed.last() in ".!?")
        }
        if (should && !isShift.value && !isCapsLock.value) {
            isShift.value = true
            shiftIsAuto = true
        } else if (!should && isShift.value && shiftIsAuto && !isCapsLock.value) {
            isShift.value = false
            shiftIsAuto = false
        }
    }

    private fun commitText(text: String) {
        finalizeComposing()   // symbols / digits / paste / emoji end the current word first
        currentInputConnection?.commitText(text, 1)
        // Emoticons ending in a symbol/digit (e.g. :) :( ;) :/ <3) complete here.
        if (text.isNotEmpty() && text.last() in emoticonEndChars) maybeReplaceEmoticon()
    }

    // Unified key feedback. Every key (letters, backspace, mode, shift, space) routes through
    // here, so the haptic intensity is identical everywhere and fully controlled by the
    // Haptics-strength setting (off / light / medium / strong) via an explicit amplitude.
    private fun performKeyFeedback() {
        val level = hapticStrength.value
        val durationMs = KeyboardPrefs.hapticDurationMs(level)
        if (durationMs > 0L) {
            try {
                vibrator?.let { v ->
                    if (v.hasVibrator()) {
                        val amplitude = if (v.hasAmplitudeControl())
                            KeyboardPrefs.hapticAmplitude(level).coerceIn(1, 255)
                        else
                            android.os.VibrationEffect.DEFAULT_AMPLITUDE
                        v.vibrate(android.os.VibrationEffect.createOneShot(durationMs, amplitude))
                    }
                }
            } catch (_: Exception) {}
        }
        if (soundEnabled.value) {
            try {
                val am = getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                am?.playSoundEffect(android.media.AudioManager.FX_KEYPRESS_STANDARD, -1f)
            } catch (_: Exception) {}
        }
    }

    /** The on-screen layout for [lang]: Russian is Cyrillic; English and Latvian share Latin/QWERTY. */
    private fun layoutOf(lang: String) = if (lang == "ru") "ru" else "latin"

    /** Enabled dictionary languages that share the current keyboard's layout. With both English and
     *  Latvian enabled they map to the one Latin keyboard, so suggestions, autocorrect and glide all
     *  draw from both word lists at once — no toggling between two identical QWERTY layouts. */
    private fun dictLangs(): List<String> {
        val layout = layoutOf(currentLang.value)
        return enabledLangs.value.filter { layoutOf(it) == layout }.ifEmpty { listOf(currentLang.value) }
    }

    // Cycle the keyboard across the user's enabled layouts (globe key / space-bar swipe). English and
    // Latvian collapse into a single Latin stop, so the globe flips Latin <-> Cyrillic, not en/lv/ru.
    private fun cycleLanguage() {
        val stops = LinkedHashMap<String, String>()   // layout -> first enabled lang for it
        for (l in enabledLangs.value) stops.putIfAbsent(layoutOf(l), l)
        val cycle = stops.values.toList()
        if (cycle.size <= 1) return   // only one layout enabled (e.g. en+lv) — nothing to switch to
        finalizeComposing()   // commit the current word before switching layout
        val idx = cycle.indexOfFirst { layoutOf(it) == layoutOf(currentLang.value) }.coerceAtLeast(0)
        val next = cycle[(idx + 1) % cycle.size]
        currentLang.value = next
        KeyboardPrefs.setCurrentLanguage(this, next)
        keyBounds.clear()      // new layout's keys re-register their positions for glide typing
        keyNeighbors = emptyMap()   // recomputed lazily for the new layout
        recentTaps.clear()          // tap points belong to the old layout
        syncImeSubtype(next)   // best-effort: tell the OS our language (UI already switched)
        updateSuggestions()
    }

    /**
     * Tell the system which language we're typing in by switching our IME subtype. The editor's
     * spell checker follows the active subtype's locale — without this it checks (e.g.) Russian text
     * against English and red-underlines correct words. Best-effort; guarded for OEM quirks.
     */
    /** Best-effort: tell the OS which language we're typing by switching our IME subtype, so the
     *  editor's spell checker can follow it. Searches ALL declared subtypes (not just the *enabled*
     *  list — on an en-US device only English is enabled, so ru/lv would never be found there).
     *  Note: setCurrentInputMethodSubtype is a no-op on Android 14+ (API 34), so on the newest
     *  releases the OS-level language can't be changed from here; the on-screen layout still switches. */
    private fun syncImeSubtype(lang: String) {
        try {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager ?: return
            val me = imm.enabledInputMethodList.firstOrNull { it.packageName == packageName } ?: return
            if (subtypeLang(imm.currentInputMethodSubtype ?: return) == lang) return  // already there
            var target: android.view.inputmethod.InputMethodSubtype? = null
            for (i in 0 until me.subtypeCount) {
                if (subtypeLang(me.getSubtypeAt(i)) == lang) { target = me.getSubtypeAt(i); break }
            }
            val st = target ?: return
            @Suppress("DEPRECATION")
            imm.setCurrentInputMethodSubtype(st)
        } catch (_: Throwable) { /* best-effort: restricted on many OEMs / Android 14+ */ }
    }

    /** Map an IME subtype to our internal language code, or null if it isn't one of ours. */
    private fun subtypeLang(st: android.view.inputmethod.InputMethodSubtype): String? {
        val tag = try { st.languageTag } catch (_: Throwable) { "" }
        @Suppress("DEPRECATION") val loc = st.locale ?: ""
        return when {
            tag.startsWith("ru", true) || loc.startsWith("ru", true) -> "ru"
            tag.startsWith("lv", true) || loc.startsWith("lv", true) -> "lv"
            tag.startsWith("en", true) || loc.startsWith("en", true) -> "en"
            else -> null
        }
    }

    // The system (or our own globe key) switched the active subtype — follow it so the on-screen
    // layout matches the language the OS now thinks we're typing, and vice-versa.
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: android.view.inputmethod.InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        val lang = subtypeLang(newSubtype) ?: return
        if (lang != currentLang.value) {
            val layoutChanged = layoutOf(lang) != layoutOf(currentLang.value)
            currentLang.value = lang
            KeyboardPrefs.setCurrentLanguage(this, lang)
            if (layoutChanged) { keyBounds.clear(); keyNeighbors = emptyMap(); recentTaps.clear() }   // en<->lv keep the same Latin key positions
            handler.post { updateSuggestions() }
        }
    }

    // Send a Ctrl(+Shift)+key combo (used for undo/redo). Works in editors that support it.
    private fun sendCtrlKey(keyCode: Int, withShift: Boolean = false) {
        val ic = currentInputConnection ?: return
        var meta = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
        if (withShift) meta = meta or android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
        ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    private fun selectAll() {
        finalizeComposing()
        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
    }

    // The anchor of a space-bar selection swipe (where the selection started growing from),
    // reset when a new cursor swipe begins so each swipe selects like a fresh shift+arrow run.
    private var cursorSelAnchor: Int? = null

    /** Move the text cursor one position left/right (space-bar swipe). Operates directly on the
     *  editor's selection instead of sending DPAD key events: at the field's edge DPAD makes apps
     *  move the FOCUS to neighbouring UI elements (buttons, links, other fields) — the swipe must
     *  never leave the text. Clamped to the text bounds; falls back to key events only when the
     *  editor can't expose its text. */
    private fun moveCursor(left: Boolean, select: Boolean = false) {
        finalizeComposing()   // moving the caret commits the word being typed
        pendingAutoSpace = false   // navigating away ends the suggestion auto-space association
        val ic = currentInputConnection ?: return
        val et = try {
            ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        } catch (_: Exception) { null }
        val text = et?.text
        if (text == null || et.selectionStart < 0 || et.selectionEnd < 0) {
            // Rare: the editor won't report its text — old key-event path (clamping impossible).
            val code = if (left) android.view.KeyEvent.KEYCODE_DPAD_LEFT else android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            val meta = if (select) android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON else 0
            ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, code, 0, meta))
            ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_UP, code, 0, meta))
            return
        }
        val lo = et.startOffset
        val hi = et.startOffset + text.length
        val selA = et.startOffset + et.selectionStart
        val selB = et.startOffset + et.selectionEnd
        if (!select) {
            cursorSelAnchor = null
            // A collapsed caret steps by one; an active selection first collapses to its edge.
            val pos = if (selA != selB) (if (left) selA else selB)
                      else (selA + if (left) -1 else 1).coerceIn(lo, hi)
            ic.setSelection(pos, pos)
        } else {
            val anchor = cursorSelAnchor ?: (if (selA != selB) (if (left) selB else selA) else selA)
                .also { cursorSelAnchor = it }
            val moving = if (anchor == selA) selB else selA
            val m = (moving + if (left) -1 else 1).coerceIn(lo, hi)
            ic.setSelection(minOf(anchor, m), maxOf(anchor, m))
        }
    }

    // Shift tap handling: single = one-shot shift, double-tap = caps lock.
    private fun onShiftTap() {
        performKeyFeedback()
        shiftIsAuto = false   // this is a deliberate Shift, so it enables selection on space-swipe
        val now = System.currentTimeMillis()
        if (now - lastShiftTapMs < 300) {
            isCapsLock.value = true
            isShift.value = true
        } else if (isCapsLock.value) {
            isCapsLock.value = false; isShift.value = false
        } else {
            isShift.value = !isShift.value
        }
        lastShiftTapMs = now
    }

    // Hold-Shift: arm selection mode (swipe the space bar to select) with a haptic cue.
    private fun armSelection() {
        isShift.value = true
        shiftIsAuto = false
        performKeyFeedback()
        statusText.value = "⇧ Swipe space to select"
        handler.postDelayed({ if (statusText.value.startsWith("⇧")) statusText.value = "" }, 1500)
    }

    private fun performUndoRewrite() {
        finalizeComposing()
        val ic = currentInputConnection ?: return
        if (rewriteResult.isNotEmpty()) ic.deleteSurroundingText(rewriteResult.length, 0)
        ic.commitText(rewriteBackup, 1)
        showUndoRewrite.value = false
    }

    // Open Keyo settings directly from the keyboard, optionally deep-linked to a section.
    private fun openSettings(section: String? = null) {
        try {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (section != null) putExtra("section", section)
            })
        } catch (e: Exception) {
            android.util.Log.e("Keyo", "openSettings failed", e)
        }
    }

    private fun deleteAll() {
        finalizeComposing()
        val ic = currentInputConnection ?: return
        // Select all (Ctrl+A) then delete
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL))
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL))
    }

    private fun deleteSmart(byWord: Boolean) {
        performKeyFeedback()
        pendingAutoSpace = false
        val ic = currentInputConnection ?: return

        // Backspace right after a glide deletes the whole inserted word + its auto-space in one tap.
        val gw = glideWord
        if (gw != null && !byWord && composing.isEmpty()) {
            glideWord = null
            val n = gw.length + 1
            val before = ic.getTextBeforeCursor(n, 0)?.toString()
            if (before != null && before.length == n && before.startsWith(gw) && before.last() == ' ') {
                ic.deleteSurroundingText(n, 0)
                updateSuggestions()
                return
            }
        }

        // Gboard-style revert: the first Backspace right after a space-autocorrect restores exactly
        // what you typed (deletes "<corrected><boundary>", puts the original back) and remembers it
        // so it isn't auto-corrected again.
        val undo = autocorrectUndo
        autocorrectUndo = null
        if (undo != null && !byWord && composing.isEmpty()) {
            val n = undo.corrected.length + 1
            val before = ic.getTextBeforeCursor(n, 0)?.toString() ?: ""
            if (before.length == n && before.startsWith(undo.corrected)) {
                revertedWords.add(SuggestionEngine.fold(undo.original.lowercase()))
                ic.deleteSurroundingText(n, 0)
                ic.commitText(undo.original, 1)
                updateSuggestions()
                return
            }
        }

        // While a word is composing, edit the buffer directly so the region stays consistent
        // (sending a raw DEL key event would desync the composing span).
        if (composing.isNotEmpty()) {
            if (byWord) {
                composing.clear()
                ic.setComposingText("", 1)
                ic.finishComposingText()
            } else {
                composing.setLength(composing.length - 1)
                // finishComposingText() alone only *finalizes* the span — it never deletes text, so
                // the last glyph would be orphaned (you'd need a 2nd Backspace). Clear the visual
                // composing text first, then finalize.
                if (composing.isEmpty()) { ic.setComposingText("", 1); ic.finishComposingText() }
                else ic.setComposingText(composing.toString(), 1)
            }
            maybeAutoCapitalize()   // deleting to empty at a sentence start should re-arm auto-shift
            updateSuggestions()
            return
        }

        if (byWord) {
            // Delete word: send Ctrl+Backspace
            ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_DEL, 0, android.view.KeyEvent.META_CTRL_ON))
            ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_UP,
                android.view.KeyEvent.KEYCODE_DEL, 0, android.view.KeyEvent.META_CTRL_ON))
        } else {
            // Single char delete via KeyEvent — works in all contexts (browser, address bar, etc.)
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL))
        }
    }

    private fun networkAvailable(): Boolean = try {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        cm?.activeNetwork != null
    } catch (_: Throwable) { true }

    private fun langTagOf(lang: String) = when (lang) {
        "ru" -> "ru-RU"; "lv" -> "lv-LV"; else -> "en-US"
    }

    private fun startVoiceRecording() {
        try {
            if (secureField) {
                statusText.value = "🔒 Voice is off in password fields"
                handler.postDelayed({ if (statusText.value.startsWith("🔒")) statusText.value = "" }, 1500)
                return
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                statusText.value = "⚠ Mic permission needed — open app settings"
                return
            }

            // No Groq key / no network -> fall back to the device's own speech recognition, so
            // dictation still works. Interim text streams into the composing region.
            if ((GroqApi.apiKey.isBlank() || !networkAvailable()) && offlineDictation.isAvailable()) {
                finalizeComposing()
                offlineSession = true
                isRecording.value = true
                statusText.value = "🎤 Recording (on-device)…"
                offlineDictation.start(
                    langTagOf(currentLang.value),
                    preferOffline = !networkAvailable(),
                    onPartial = { t ->
                        try { currentInputConnection?.setComposingText(t, 1) } catch (_: Exception) {}
                    },
                    onFinal = { t ->
                        handler.post {
                            offlineSession = false
                            isRecording.value = false
                            try {
                                if (!t.isNullOrBlank()) currentInputConnection?.setComposingText(t, 1)
                                currentInputConnection?.finishComposingText()
                            } catch (_: Exception) {}
                            if (t.isNullOrBlank()) {
                                statusText.value = "Didn't catch that"
                                handler.postDelayed({ if (statusText.value == "Didn't catch that") statusText.value = "" }, 2000)
                            } else statusText.value = ""
                        }
                    }
                )
                return
            }

            if (audioRecorder.start()) {
                isRecording.value = true
                statusText.value = "🎤 Recording..."
                if (KeyboardPrefs.isLiveDictation(this)) {
                    finalizeComposing()        // so the interim transcript owns the composing region
                    liveBusy = false
                    handler.postDelayed(liveDictationTick, 1600)
                }
            } else {
                statusText.value = "⚠ Failed to start recording"
            }
        } catch (e: Exception) {
            statusText.value = "⚠ Error: ${e.message}"
            android.util.Log.e("Keyo", "startVoiceRecording failed", e)
        }
    }

    private fun cancelVoiceRecording() {
        if (offlineSession) {
            offlineSession = false
            offlineDictation.cancel()
            try {
                currentInputConnection?.setComposingText("", 1)
                currentInputConnection?.finishComposingText()
            } catch (_: Exception) {}
            isRecording.value = false
            statusText.value = "✕ Cancelled"
            handler.postDelayed({ if (statusText.value == "✕ Cancelled") statusText.value = "" }, 1500)
            return
        }
        handler.removeCallbacks(liveDictationTick)
        try {
            audioRecorder.stop(File(cacheDir, "voice_input.wav"))
        } catch (_: Exception) {}
        // Drop any live-dictation interim text from the field.
        try { currentInputConnection?.setComposingText("", 1); currentInputConnection?.finishComposingText() } catch (_: Exception) {}
        isRecording.value = false
        statusText.value = "✕ Cancelled"
        handler.postDelayed({ if (statusText.value == "✕ Cancelled") statusText.value = "" }, 1500)
    }

    private fun stopVoiceRecording() {
        try {
            if (offlineSession) {
                // Finish the system-recognizer session; the final text arrives via its onFinal.
                statusText.value = "⏳ Transcribing..."
                offlineDictation.stop()
                return
            }
            handler.removeCallbacks(liveDictationTick)   // stop live-interim updates; final replaces them
            if (!audioRecorder.isActive()) return
            finalizeComposing()   // commit any composing word so the transcript appends after it

            val audioFile = File(cacheDir, "voice_input.wav")
            if (audioRecorder.stop(audioFile)) {
                isRecording.value = false
                statusText.value = "⏳ Transcribing..."

                GroqApi.transcribe(audioFile) { text, error ->
                    if (text != null) {
                        if (KeyboardPrefs.isAutocorrectEnabled(this@KeyoService)) {
                            handler.post { statusText.value = "🧹 Cleaning up..." }
                            GroqApi.cleanupText(text) { cleaned, _ ->
                                handler.post {
                                    try {
                                        currentInputConnection?.commitText(cleaned ?: text, 1)
                                        statusText.value = ""
                                    } catch (e: Exception) {
                                        statusText.value = "⚠ ${e.message}"
                                    }
                                }
                            }
                        } else {
                            handler.post {
                                try {
                                    currentInputConnection?.commitText(text, 1)
                                    statusText.value = ""
                                } catch (e: Exception) {
                                    statusText.value = "⚠ ${e.message}"
                                }
                            }
                        }
                    } else {
                        handler.post {
                            // Keep whatever live dictation already recognised (commit the interim).
                            try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}
                            statusText.value = error ?: "Transcription failed"
                            handler.postDelayed({ statusText.value = "" }, 3000)
                        }
                    }
                }
            } else {
                try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}
                isRecording.value = false
                statusText.value = "⚠ Recording too short"
                handler.postDelayed({ statusText.value = "" }, 2000)
            }
        } catch (e: Exception) {
            isRecording.value = false
            statusText.value = "⚠ Error: ${e.message}"
            android.util.Log.e("Keyo", "stopVoiceRecording failed", e)
        }
    }

    // --- AI Assistant mode ---
    private val aiAudioRecorder = AudioRecorder()

    // --- Custom rewrite via voice (long-press the ✨ toolbar icon, speak an instruction) ---
    private val rewriteRecorder = AudioRecorder()
    private var customRecording = false

    private fun startCustomRewriteRecording() {
        if (secureField) {
            statusText.value = "🔒 AI is off in password fields"
            handler.postDelayed({ if (statusText.value.startsWith("🔒")) statusText.value = "" }, 1500)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusText.value = "⚠ Mic permission needed"
            return
        }
        if (currentTargetText().isBlank()) {
            statusText.value = "✨ Type or select text first"
            handler.postDelayed({ if (statusText.value.startsWith("✨")) statusText.value = "" }, 1500)
            return
        }
        if (rewriteRecorder.start()) {
            customRecording = true
            statusText.value = "🎤 Say how to change the text…"
        }
    }

    private fun stopCustomRewriteRecording() {
        if (!customRecording) return
        customRecording = false
        val f = File(cacheDir, "rewrite_voice.wav")
        if (rewriteRecorder.stop(f)) {
            statusText.value = "⏳ Transcribing…"
            GroqApi.transcribe(f) { instr, err ->
                handler.post {
                    if (!instr.isNullOrBlank()) runRewrite(instr.trim())
                    else {
                        statusText.value = err ?: "Couldn't hear that"
                        handler.postDelayed({ statusText.value = "" }, 2000)
                    }
                }
            }
        }
    }

    private fun startAIRecording() {
        try {
            if (secureField) {
                statusText.value = "🔒 AI is off in password fields"
                handler.postDelayed({ if (statusText.value.startsWith("🔒")) statusText.value = "" }, 1500)
                return
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                statusText.value = "⚠ Mic permission needed"
                return
            }
            if (aiAudioRecorder.start()) {
                isRecordingAI.value = true
                statusText.value = "🤖 Listening for task..."
            } else {
                statusText.value = "⚠ Failed to start recording"
            }
        } catch (e: Exception) {
            statusText.value = "⚠ Error: ${e.message}"
        }
    }

    private fun cancelAIRecording() {
        try {
            aiAudioRecorder.stop(File(cacheDir, "ai_voice_input.wav"))
        } catch (_: Exception) {}
        isRecordingAI.value = false
        statusText.value = "✕ Cancelled"
        handler.postDelayed({ if (statusText.value == "✕ Cancelled") statusText.value = "" }, 1500)
    }

    private fun stopAIRecording() {
        try {
            if (!aiAudioRecorder.isActive()) return
            finalizeComposing()

            val audioFile = File(cacheDir, "ai_voice_input.wav")
            if (aiAudioRecorder.stop(audioFile)) {
                isRecordingAI.value = false
                statusText.value = "🤖 Transcribing task..."

                GroqApi.transcribe(audioFile) { text, error ->
                    if (text != null) {
                        handler.post { statusText.value = "🤖 Executing: $text" }
                        GroqApi.executeTask(text, this@KeyoService, confirm = { summary -> requestConfirm(summary) }) { result, taskError ->
                            handler.post {
                                try {
                                    if (result != null) {
                                        currentInputConnection?.commitText(formatEmphasis(result), 1)
                                        statusText.value = ""
                                    } else {
                                        statusText.value = taskError ?: "Task failed"
                                        handler.postDelayed({ statusText.value = "" }, 3000)
                                    }
                                } catch (e: Exception) {
                                    statusText.value = "⚠ ${e.message}"
                                }
                            }
                        }
                    } else {
                        handler.post {
                            statusText.value = error ?: "Transcription failed"
                            handler.postDelayed({ statusText.value = "" }, 3000)
                        }
                    }
                }
            } else {
                isRecordingAI.value = false
                statusText.value = "⚠ Recording too short"
                handler.postDelayed({ statusText.value = "" }, 2000)
            }
        } catch (e: Exception) {
            isRecordingAI.value = false
            statusText.value = "⚠ Error: ${e.message}"
        }
    }
}
