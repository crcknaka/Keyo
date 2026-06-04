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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
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

class KeyoService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val audioRecorder = AudioRecorder()
    private val handler = Handler(Looper.getMainLooper())

    private var currentLang = mutableStateOf("en")          // "en", "ru", "lv"
    private var enabledLangs = mutableStateOf(listOf("en", "ru"))
    private var isRecording = mutableStateOf(false)
    private var isRecordingAI = mutableStateOf(false)
    private var statusText = mutableStateOf("")
    private var isShift = mutableStateOf(false)
    private var keyboardMode = mutableStateOf("abc") // "abc", "123", "symbols", "numpad"
    private var imeActionId = mutableIntStateOf(android.view.inputmethod.EditorInfo.IME_ACTION_NONE)
    private var showNumberRow = mutableStateOf(true)
    // Visual sizing (tunable live via the Settings sliders)
    private var keyHeightDp = mutableIntStateOf(KeyboardPrefs.DEFAULT_KEY_HEIGHT)
    private var keyHGapDp = mutableIntStateOf(KeyboardPrefs.DEFAULT_HGAP)
    private var keyVGapDp = mutableIntStateOf(KeyboardPrefs.DEFAULT_VGAP)
    private var hapticStrength = mutableStateOf("light")    // off / light / medium / strong
    private var currentTheme = KeyboardPrefs.KeyboardTheme("catppuccin","",0xFF1E1E2E,0xFF313244,0xFFBB86FC,0xFFCDD6F4)
    private var soundEnabled = mutableStateOf(false)
    private var themeId = mutableStateOf("catppuccin")

    // True for password / no-personalized-learning fields. In these we never send anything
    // to the network: no spellcheck, no voice transcription, no AI.
    private var secureField = false

    private var clipHistory = mutableStateOf<List<String>>(emptyList())
    private var pinnedClips = mutableStateOf<List<String>>(emptyList())
    private var phrasesState = mutableStateOf<List<String>>(emptyList())
    private var emojiCategory = mutableIntStateOf(0)        // emoji panel category
    // Dynamic top toolbar: a freshly-copied snippet shows as a quick-paste chip for a while.
    private var showClipChip = mutableStateOf(false)
    private var lastClip = mutableStateOf("")
    private val hideClipChip = Runnable { showClipChip.value = false }

    private val clipboardManager: android.content.ClipboardManager? by lazy {
        getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    }
    // Record copied text into the clipboard-history panel (skipped in password fields).
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        if (!secureField) {
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
        hapticStrength.value = KeyboardPrefs.getHapticStrength(this)
        soundEnabled.value = KeyboardPrefs.isSoundEnabled(this)
        themeId.value = KeyboardPrefs.getTheme(this)
        enabledLangs.value = KeyboardPrefs.getEnabledLanguages(this)
        clipHistory.value = KeyboardPrefs.getClipHistory(this)
        pinnedClips.value = KeyboardPrefs.getPinned(this)
        recentEmoji.value = KeyboardPrefs.getRecentEmoji(this)
        phrasesState.value = KeyboardPrefs.getPhrases(this)
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
        secureField = isPassword || noLearning

        isShift.value = false
        maybeAutoCapitalize()
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
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    @Composable
    fun KeyboardLayout() {
        val lang by currentLang
        val recording by isRecording
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
                .padding(horizontal = 2.dp, vertical = 4.dp)
        ) {
            // Dynamic top toolbar (Gboard-style). Priority: status > quick-paste chip > icons.
            TopToolbar(status, bgColor, keyColor, textColor, accentColor)

            if (mode == "emoji") {
                EmojiPanel(keyHeight, keyColor, textColor, accentColor)
            } else if (mode == "clipboard") {
                ClipboardPanel(keyHeight, keyColor, textColor, accentColor)
            } else if (mode == "rewrite") {
                RewritePanel(keyHeight, keyColor, textColor, accentColor)
            } else if (mode == "phrases") {
                PhrasesPanel(keyHeight, keyColor, textColor, accentColor)
            } else {

            // Key content area — fixed height (maxContentRows tall) so switching between
            // abc / 123 / symbols / numpad never resizes the keyboard. Rows sit at the
            // bottom, flush against the function row.
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
                            if (index == 2) {
                                ShiftKey(
                                    active = shift,
                                    keyColor = keyColor,
                                    accentColor = accentColor,
                                    iconColor = if (shift) Color.Black else textColor,
                                    height = keyHeight,
                                    modifier = Modifier.weight(1.3f)
                                ) { isShift.value = !isShift.value }
                            }
                            row.forEach { char ->
                                val displayChar = if (shift) char.uppercase() else char.toString()
                                KeyButton(displayChar, keyColor, textColor, Modifier.weight(1f)) {
                                    commitChar(if (shift) char.uppercaseChar() else char)
                                    if (shift) isShift.value = false
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
                    // Calculator-style numpad
                    val numpadRows = listOf(
                        listOf("7","8","9","÷"),
                        listOf("4","5","6","×"),
                        listOf("1","2","3","-"),
                        listOf("0",".",",","+")
                    )
                    numpadRows.forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { s ->
                                KeyButton(s, keyColor, textColor, Modifier.weight(1f)) { commitText(s) }
                            }
                            if (row == numpadRows.last()) {
                                // = and backspace on last row
                                KeyButton("=", keyColor, textColor, Modifier.weight(1f)) { commitText("=") }
                                BackspaceKey(keyColor, textColor, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            } // end fixed-height key content area

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
                        .padding(horizontal = keyHGapDp.intValue.dp, vertical = keyVGapDp.intValue.dp)
                        .semantics { contentDescription = "AI assistant. Long press and speak a task" }
                        .background(
                            when {
                                recordingAI && aiDragX < -cancelThreshold -> Color(0xFF666666)
                                recordingAI -> Color(0xFF50FA7B)
                                aiPressed -> lerp(keyColor, Color.Black, 0.22f)
                                else -> keyColor
                            },
                            RoundedCornerShape(6.dp)
                        )
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                aiCancelled = false
                                aiDragX = 0f
                                aiPressed = true
                                var longPressed = false

                                val longPressJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
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
                        },
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

                // Space — shared component (tap = space, hold = dictate, swipe = cursor slider)
                val spaceLabel = if (mode == "abc") lang.uppercase() else "space"
                SpaceKey(Modifier.weight(3.5f), spaceLabel, keyColor, textColor, accentColor, recordColor)

                // Period — long press shows ? , ! -
                KeyButton(".", keyColor, textColor, Modifier.weight(0.9f)) { commitChar('.') }

                // Enter / Submit
                val imeAction by imeActionId
                val isTextMode = mode == "123" || mode == "symbols" || mode == "numpad"
                val hasImeAction = imeAction != android.view.inputmethod.EditorInfo.IME_ACTION_NONE
                        && imeAction != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED

                val enterKind = when {
                    isTextMode -> "return"
                    hasImeAction -> when (imeAction) {
                        android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH -> "search"
                        android.view.inputmethod.EditorInfo.IME_ACTION_SEND -> "send"
                        android.view.inputmethod.EditorInfo.IME_ACTION_GO -> "send"
                        android.view.inputmethod.EditorInfo.IME_ACTION_DONE -> "done"
                        android.view.inputmethod.EditorInfo.IME_ACTION_NEXT -> "next"
                        else -> "send"
                    }
                    else -> "return"
                }

                EnterKey(enterKind, accentColor, Color.Black, keyHeight, Modifier.weight(1.0f)) {
                    if (isTextMode || !hasImeAction) {
                        commitChar('\n')
                    } else {
                        currentInputConnection?.performEditorAction(imeAction)
                    }
                }
            }
            } // end else (normal keyboard layout)
        }
    }

    // Dynamic top toolbar (Gboard-style): status text, a quick-paste chip, or action icons.
    @Composable
    fun TopToolbar(status: String, bgColor: Color, keyColor: Color, textColor: Color, accentColor: Color) {
        val chip by showClipChip
        val clip by lastClip
        Box(
            modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            when {
                status.isNotEmpty() -> Text(
                    status, color = accentColor, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                )
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
                    Box(
                        modifier = Modifier.size(40.dp)
                            .semantics { contentDescription = "Rewrite / improve text" }
                            .clickable { keyboardMode.value = "rewrite" },
                        contentAlignment = Alignment.Center
                    ) { SparkleGlyph(accentColor, Modifier.size(20.dp)) }
                    ToolbarIcon("😀", textColor) { emojiCategory.intValue = if (recentEmoji.value.isEmpty()) 1 else 0; keyboardMode.value = "emoji" }
                    ToolbarIcon("📋", textColor) { keyboardMode.value = "clipboard" }
                    ToolbarIcon("★", textColor) { keyboardMode.value = "phrases" }
                    ToolbarIcon("↶", textColor) { performKeyFeedback(); sendCtrlKey(android.view.KeyEvent.KEYCODE_Z) }
                    ToolbarIcon("↷", textColor) { performKeyFeedback(); sendCtrlKey(android.view.KeyEvent.KEYCODE_Z, withShift = true) }
                    ToolbarIcon("⬚", textColor) { performKeyFeedback(); selectAll() }
                    ToolbarIcon("⚙", textColor) { performKeyFeedback(); openSettings() }
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

    // A circular bezel/dial knob with tick marks that rotate as you scrub the cursor.
    @Composable
    private fun BezelKnob(rotationDeg: Float, ring: Color, hub: Color, modifier: Modifier) {
        Canvas(modifier = modifier) {
            val r = size.minDimension / 2f
            val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            // base disc + subtle inner face
            drawCircle(color = ring, radius = r, center = c)
            drawCircle(color = hub.copy(alpha = 0.18f), radius = r * 0.78f, center = c)
            // rotating ticks around the bezel
            val ticks = 12
            for (i in 0 until ticks) {
                val ang = Math.toRadians((i * (360.0 / ticks) + rotationDeg))
                val ca = kotlin.math.cos(ang).toFloat()
                val sa = kotlin.math.sin(ang).toFloat()
                drawLine(
                    color = hub,
                    start = androidx.compose.ui.geometry.Offset(c.x + ca * r * 0.60f, c.y + sa * r * 0.60f),
                    end = androidx.compose.ui.geometry.Offset(c.x + ca * r * 0.90f, c.y + sa * r * 0.90f),
                    strokeWidth = r * 0.13f
                )
            }
            // center hub
            drawCircle(color = hub, radius = r * 0.30f, center = c)
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
                // Suggest from current text
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .semantics { contentDescription = "Suggest emoji for my text" }
                        .clickable {
                            val t = currentTargetText()
                            if (t.isNotBlank()) {
                                suggesting = true
                                GroqApi.suggestEmojis(t) { res, _ ->
                                    handler.post { suggesting = false; suggestions = splitEmojis(res ?: "") }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { SparkleGlyph(if (suggestions.isNotEmpty()) accentColor else textColor.copy(alpha = 0.6f), Modifier.size(17.dp)) }
            }
            androidx.compose.material3.Divider(color = textColor.copy(alpha = 0.12f))

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
            PanelBottomBar(keyHeight, keyColor, textColor, accentColor)
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
            // Slim "Clear" row (clears unpinned history), only when there's history
            if (clips.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text("Clear", color = accentColor, fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            KeyboardPrefs.clearClips(this@KeyoService); clipHistory.value = emptyList()
                        })
                }
                androidx.compose.material3.Divider(color = textColor.copy(alpha = 0.12f))
            }

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
                                // Pin toggle
                                Box(
                                    modifier = Modifier.size(40.dp)
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
                            androidx.compose.material3.Divider(color = textColor.copy(alpha = 0.08f))
                        }
                    }
                }
            }
            PanelBottomBar(keyHeight, keyColor, textColor, accentColor)
        }
    }

    // Quick phrases panel (mode == "phrases"). Tap a saved phrase to insert it.
    @Composable
    fun PhrasesPanel(
        keyHeight: androidx.compose.ui.unit.Dp,
        keyColor: Color,
        textColor: Color,
        accentColor: Color
    ) {
        val phrases by phrasesState
        Column(modifier = Modifier.fillMaxWidth().height(keyHeight * (maxContentRows + 1))) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (phrases.isEmpty()) {
                    Text("Add quick phrases in Settings → Quick phrases",
                        color = textColor.copy(alpha = 0.5f), fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp))
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(phrases.size) { i ->
                            val p = phrases[i]
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { performKeyFeedback(); commitText(formatEmphasis(p)) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Text(p.replace("\n", " ").take(100),
                                    color = textColor, fontSize = 14.sp, maxLines = 2)
                            }
                            androidx.compose.material3.Divider(color = textColor.copy(alpha = 0.08f))
                        }
                    }
                }
            }
            PanelBottomBar(keyHeight, keyColor, textColor, accentColor)
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
                .padding(horizontal = keyHGapDp.intValue.dp, vertical = keyVGapDp.intValue.dp)
                .semantics { contentDescription = "Space. Long-press to dictate, swipe to move the cursor" }
                .background(
                    when {
                        recording && micDragX < -cancelThreshold -> Color(0xFF666666)
                        recording -> recordColor
                        spacePressed -> lerp(keyColor, Color.Black, 0.22f)
                        else -> keyColor
                    },
                    RoundedCornerShape(6.dp)
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var totalDragX = 0f
                        var longPressed = false
                        var cursorMode = false
                        var lastStepX = 0f
                        micCancelled = false
                        micDragX = 0f
                        spacePressed = true

                        val longPressJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            kotlinx.coroutines.delay(400)
                            if (!cursorMode) {
                                longPressed = true
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
                                    if (!cursorMode && kotlin.math.abs(totalDragX) > 24f) {
                                        cursorMode = true
                                        cursorVisual = true
                                        longPressJob.cancel()
                                        lastStepX = totalDragX
                                    }
                                    if (cursorMode) {
                                        dragXVisual = totalDragX
                                        val stepPx = 28f
                                        while (totalDragX - lastStepX >= stepPx) { moveCursor(false); performKeyFeedback(); lastStepX += stepPx }
                                        while (totalDragX - lastStepX <= -stepPx) { moveCursor(true); performKeyFeedback(); lastStepX -= stepPx }
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
                        } else if (!cursorMode) {
                            performKeyFeedback()
                            commitChar(' ')
                        }
                    }
                },
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
                    Box(
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                            .background(textColor.copy(alpha = 0.22f), RoundedCornerShape(2.dp))
                    )
                    BezelKnob(
                        rotationDeg = dragXVisual * 0.9f,
                        ring = accentColor,
                        hub = textColor,
                        modifier = Modifier.offset(x = knobOffset).size(30.dp)
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
        Column(modifier = Modifier.fillMaxWidth().height(keyHeight * (maxContentRows + 1))) {
            // Formatting chips + back
            Row(
                modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FormatChip("𝐁", textColor, keyColor) { applyFormatToSelection(true) }
                Spacer(Modifier.width(8.dp))
                FormatChip("𝐼", textColor, keyColor) { applyFormatToSelection(false) }
                Spacer(Modifier.weight(1f))
                if (sub.isNotEmpty()) {
                    Text("‹ Back", color = accentColor, fontSize = 14.sp,
                        modifier = Modifier.clickable { sub = "" })
                }
            }
            androidx.compose.material3.Divider(color = textColor.copy(alpha = 0.12f))

            val items = when (sub) {
                "tone" -> REWRITE_TONE
                "translate" -> REWRITE_TRANSLATE
                else -> REWRITE_MAIN
            }
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(items.size) { i ->
                    val (label, instr, subKey) = items[i]
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                if (subKey != null) sub = subKey
                                else if (instr != null) runRewrite(instr)
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, color = textColor, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (subKey != null) Text("›", color = accentColor, fontSize = 18.sp)
                    }
                    androidx.compose.material3.Divider(color = textColor.copy(alpha = 0.06f))
                }
            }
            PanelBottomBar(keyHeight, keyColor, textColor, accentColor)
        }
    }

    @Composable
    private fun FormatChip(label: String, textColor: Color, keyColor: Color, onClick: () -> Unit) {
        Box(
            modifier = Modifier.height(30.dp).widthIn(min = 46.dp)
                .background(keyColor, RoundedCornerShape(8.dp))
                .clickable { onClick() }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) { Text(label, color = textColor, fontSize = 16.sp) }
    }

    @Composable
    private fun PanelBottomBar(
        keyHeight: androidx.compose.ui.unit.Dp,
        keyColor: Color,
        textColor: Color,
        accentColor: Color
    ) {
        androidx.compose.material3.Divider(color = textColor.copy(alpha = 0.12f))
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

    // ---- Hand-drawn vector key icons (one cohesive monochrome line style, no emoji) ----

    @Composable
    private fun MicGlyph(color: Color, modifier: Modifier) {
        Canvas(modifier) {
            val w = size.width; val h = size.height
            val sw = h * 0.08f
            val cap = androidx.compose.ui.graphics.StrokeCap.Round
            val bodyW = w * 0.30f
            val left = (w - bodyW) / 2f
            // mic body (filled capsule)
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(left, h * 0.10f),
                size = androidx.compose.ui.geometry.Size(bodyW, h * 0.44f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bodyW / 2f, bodyW / 2f)
            )
            // cradle arc
            drawArc(
                color = color, startAngle = 18f, sweepAngle = 144f, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(w * 0.24f, h * 0.30f),
                size = androidx.compose.ui.geometry.Size(w * 0.52f, h * 0.50f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = cap)
            )
            // stem + base
            drawLine(color, androidx.compose.ui.geometry.Offset(w / 2f, h * 0.80f), androidx.compose.ui.geometry.Offset(w / 2f, h * 0.90f), sw, cap)
            drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.36f, h * 0.92f), androidx.compose.ui.geometry.Offset(w * 0.64f, h * 0.92f), sw, cap)
        }
    }

    // Modern "AI" sparkle (4-point star), filled.
    @Composable
    private fun SparkleGlyph(color: Color, modifier: Modifier) {
        Canvas(modifier) {
            val cx = size.width / 2f; val cy = size.height / 2f
            val rOut = size.minDimension / 2f * 0.96f
            val rIn = rOut * 0.34f
            val path = androidx.compose.ui.graphics.Path()
            val tips = floatArrayOf(-90f, 0f, 90f, 180f)
            for (i in tips.indices) {
                val a = Math.toRadians(tips[i].toDouble())
                val tx = cx + (kotlin.math.cos(a) * rOut).toFloat()
                val ty = cy + (kotlin.math.sin(a) * rOut).toFloat()
                val da = Math.toRadians((tips[i] + 45f).toDouble())
                val ix = cx + (kotlin.math.cos(da) * rIn).toFloat()
                val iy = cy + (kotlin.math.sin(da) * rIn).toFloat()
                if (i == 0) path.moveTo(tx, ty) else path.lineTo(tx, ty)
                path.lineTo(ix, iy)
            }
            path.close()
            drawPath(path, color)
        }
    }

    @Composable
    private fun BackspaceGlyph(color: Color, modifier: Modifier) {
        Canvas(modifier) {
            val w = size.width; val h = size.height
            val sw = h * 0.085f
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = sw, join = androidx.compose.ui.graphics.StrokeJoin.Round, cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * w, y * h)
            val body = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.07f, h * 0.5f)
                lineTo(w * 0.33f, h * 0.23f)
                lineTo(w * 0.93f, h * 0.23f)
                lineTo(w * 0.93f, h * 0.77f)
                lineTo(w * 0.33f, h * 0.77f)
                close()
            }
            drawPath(body, color, style = stroke)
            drawLine(color, o(0.50f, 0.40f), o(0.74f, 0.60f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, o(0.74f, 0.40f), o(0.50f, 0.60f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }

    // Enter / action glyph. kind: "return" | "done" | "search" | "send" | "next"
    @Composable
    private fun EnterGlyph(kind: String, color: Color, modifier: Modifier) {
        Canvas(modifier) {
            val w = size.width; val h = size.height
            val sw = h * 0.10f
            val cap = androidx.compose.ui.graphics.StrokeCap.Round
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = sw, cap = cap, join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
            fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * w, y * h)
            when (kind) {
                "done" -> {
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.22f, h * 0.55f); lineTo(w * 0.42f, h * 0.74f); lineTo(w * 0.80f, h * 0.28f)
                    }
                    drawPath(p, color, style = stroke)
                }
                "search" -> {
                    drawCircle(color, radius = w * 0.22f, center = o(0.44f, 0.44f), style = stroke)
                    drawLine(color, o(0.60f, 0.60f), o(0.80f, 0.80f), sw, cap)
                }
                "send", "next" -> {
                    drawLine(color, o(0.20f, 0.5f), o(0.78f, 0.5f), sw, cap)
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.60f, h * 0.32f); lineTo(w * 0.80f, h * 0.5f); lineTo(w * 0.60f, h * 0.68f)
                    }
                    drawPath(p, color, style = stroke)
                }
                else -> { // return arrow ⏎
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.78f, h * 0.28f); lineTo(w * 0.78f, h * 0.58f); lineTo(w * 0.28f, h * 0.58f)
                    }
                    drawPath(p, color, style = stroke)
                    val head = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.42f, h * 0.46f); lineTo(w * 0.28f, h * 0.58f); lineTo(w * 0.42f, h * 0.70f)
                    }
                    drawPath(head, color, style = stroke)
                }
            }
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
                .padding(horizontal = keyHGapDp.intValue.dp, vertical = keyVGapDp.intValue.dp)
                .background(if (pressed) lerp(bgColor, Color.Black, 0.22f) else bgColor, RoundedCornerShape(6.dp))
                .semantics { contentDescription = "Enter" }
                .clickable(interactionSource = interaction, indication = null) { performKeyFeedback(); onClick() },
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
                .padding(horizontal = keyHGapDp.intValue.dp, vertical = keyVGapDp.intValue.dp)
                .background(if (pressed) lerp(keyColor, Color.Black, 0.22f) else keyColor, RoundedCornerShape(6.dp))
                .semantics { contentDescription = "Switch language" }
                .clickable(interactionSource = interaction, indication = null) { performKeyFeedback(); onClick() },
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
        onClick: () -> Unit
    ) {
        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val base = if (active) accentColor else keyColor
        Box(
            modifier = modifier
                .height(height)
                .padding(horizontal = keyHGapDp.intValue.dp, vertical = keyVGapDp.intValue.dp)
                .background(if (pressed) lerp(base, Color.Black, 0.22f) else base, RoundedCornerShape(6.dp))
                .semantics { contentDescription = "Shift" }
                .clickable(interactionSource = interaction, indication = null) {
                    performKeyFeedback()
                    onClick()
                },
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
                .padding(horizontal = keyHGapDp.intValue.dp, vertical = keyVGapDp.intValue.dp)
                .semantics { contentDescription = "Delete" }
                .background(
                    when {
                        swipedClear -> Color(0xFFFF5555)
                        isPressed -> lerp(bgColor, Color.Black, 0.22f)
                        else -> bgColor
                    },
                    RoundedCornerShape(6.dp)
                )
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
                        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
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
                },
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

    private fun applyFormatToSelection(bold: Boolean) {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (sel.isNullOrEmpty()) {
            statusText.value = "Select text to format"
            handler.postDelayed({ if (statusText.value.startsWith("Select")) statusText.value = "" }, 1500)
            return
        }
        performKeyFeedback()
        ic.commitText(if (bold) applyBold(sel.toString()) else applyItalic(sel.toString()), 1)
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
                } else {
                    statusText.value = err ?: "Rewrite failed"
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
        var selectedAltIdx by remember { mutableIntStateOf(-1) }
        val alts = altChars[label]
        val hasAlts = alts != null && alts.isNotEmpty()

        Box(modifier = modifier.height(height).padding(horizontal = keyHGapDp.intValue.dp, vertical = keyVGapDp.intValue.dp)) {
            // Main key — darkens instantly while pressed for a tactile "button" feel
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = keyDescription(label) }
                    .background(if (pressed) lerp(bgColor, Color.Black, 0.22f) else bgColor, RoundedCornerShape(6.dp))
                    .pointerInput(label) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
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
                            val longPressJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                kotlinx.coroutines.delay(400)
                                if (pressed && hasAlts) {
                                    longPressed = true
                                    showAlts = true
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
                                    // If alts are showing, detect which one finger is on
                                    if (showAlts && alts != null) {
                                        val dx = change.position.x - down.position.x
                                        // Center the selection: 0 at center, negative=left, positive=right
                                        val halfCount = alts.size / 2f
                                        val altIdx = ((dx / 44f) + halfCount).toInt().coerceIn(0, alts.size - 1)
                                        selectedAlt = alts[altIdx]
                                        selectedAltIdx = altIdx
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
                            if (longPressed && chosenAlt != null) {
                                currentInputConnection?.deleteSurroundingText(label.length, 0)
                                performKeyFeedback()
                                if (chosenAlt == "⚙") openSettings()  // gear on the period opens settings
                                else commitText(chosenAlt)            // insert the selected alt character
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = textColor,
                    fontSize = fontSize,
                    textAlign = TextAlign.Center
                )
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

            // Alt characters popup — uses Popup to escape parent clipping
            if (showAlts && alts != null) {
                androidx.compose.ui.window.Popup(
                    alignment = Alignment.TopCenter,
                    offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { (-56).dp.roundToPx() })
                ) {
                    Row(
                        modifier = Modifier
                            .background(Color(currentTheme.altPopupBg), RoundedCornerShape(8.dp))
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        alts.forEachIndexed { idx, alt ->
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

    // Spellcheck debounce
    private val spellcheckHandler = Handler(Looper.getMainLooper())
    private var spellcheckRunnable: Runnable? = null
    private var lastCheckedText: String = ""
    private var isSpellchecking = false

    // Note: haptic feedback is triggered by the caller (KeyButton tap / custom keys),
    // not here, so every key vibrates exactly once.
    private fun commitChar(char: Char) {
        val ic = currentInputConnection
        // Double-space -> ". " (only after a word character)
        if (char == ' ' && KeyboardPrefs.isDoubleSpacePeriod(this)) {
            val before = ic?.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic?.deleteSurroundingText(1, 0)
                ic?.commitText(". ", 1)
                scheduleSpellcheck()
                maybeAutoCapitalize()
                return
            }
        }
        ic?.commitText(char.toString(), 1)
        scheduleSpellcheck()
        maybeAutoCapitalize()
    }

    // Auto-capitalize: turn shift on at the start of input / a new sentence.
    private fun maybeAutoCapitalize() {
        if (!KeyboardPrefs.isAutoCap(this)) return
        val before = currentInputConnection?.getTextBeforeCursor(2, 0)?.toString() ?: ""
        val atStart = before.isEmpty()
        val afterSentence = before.length == 2 && before[1] == ' ' &&
            (before[0] == '.' || before[0] == '!' || before[0] == '?' || before[0] == '\n')
        val afterNewline = before.isNotEmpty() && before.last() == '\n'
        if (atStart || afterSentence || afterNewline) isShift.value = true
    }

    private fun scheduleSpellcheck() {
        if (secureField) return  // never send text from password/incognito fields
        if (!KeyboardPrefs.isSpellcheckEnabled(this)) return
        spellcheckRunnable?.let { spellcheckHandler.removeCallbacks(it) }
        spellcheckRunnable = Runnable { runSpellcheck() }
        spellcheckHandler.postDelayed(spellcheckRunnable!!, 2500) // 2.5s after last keystroke
    }

    private fun runSpellcheck() {
        if (isSpellchecking) return
        val ic = currentInputConnection ?: return

        // Get all text before cursor (up to 500 chars)
        val text = ic.getTextBeforeCursor(500, 0)?.toString() ?: return
        if (text.length < 5 || text == lastCheckedText) return
        // Only check if there's at least one space (at least one complete word)
        if (!text.contains(' ')) return

        isSpellchecking = true
        lastCheckedText = text

        GroqApi.spellcheck(text) { corrected, _ ->
            handler.post {
                isSpellchecking = false
                if (corrected != null && corrected != text && corrected.isNotEmpty()) {
                    val currentIc = currentInputConnection ?: return@post
                    // Re-read current text to make sure it hasn't changed
                    val currentText = currentIc.getTextBeforeCursor(500, 0)?.toString() ?: return@post
                    if (currentText == text) {
                        // Replace the text
                        currentIc.deleteSurroundingText(text.length, 0)
                        currentIc.commitText(corrected, 1)
                        lastCheckedText = corrected
                        statusText.value = "✓ corrected"
                        handler.postDelayed({ if (statusText.value == "✓ corrected") statusText.value = "" }, 1500)
                    }
                }
            }
        }
    }

    private fun commitText(text: String) {
        currentInputConnection?.commitText(text, 1)
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
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            am.playSoundEffect(android.media.AudioManager.FX_KEYPRESS_STANDARD, -1f)
        }
    }

    // Cycle the keyboard language across the user's enabled set (swipe the space bar).
    private fun cycleLanguage() {
        val enabled = enabledLangs.value
        if (enabled.size <= 1) return
        val idx = enabled.indexOf(currentLang.value).coerceAtLeast(0)
        val next = enabled[(idx + 1) % enabled.size]
        currentLang.value = next
        KeyboardPrefs.setCurrentLanguage(this, next)
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
        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
    }

    // Move the text cursor one character left/right (space-bar swipe).
    private fun moveCursor(left: Boolean) {
        val ic = currentInputConnection ?: return
        val code = if (left) android.view.KeyEvent.KEYCODE_DPAD_LEFT else android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    }

    // Open Keyo settings directly from the keyboard (long-press the period and pick ⚙).
    private fun openSettings() {
        try {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            android.util.Log.e("Keyo", "openSettings failed", e)
        }
    }

    private fun deleteAll() {
        val ic = currentInputConnection ?: return
        // Select all (Ctrl+A) then delete
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL))
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL))
    }

    private fun deleteSmart(byWord: Boolean) {
        performKeyFeedback()
        val ic = currentInputConnection ?: return

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

            if (audioRecorder.start()) {
                isRecording.value = true
                statusText.value = "🎤 Recording..."
            } else {
                statusText.value = "⚠ Failed to start recording"
            }
        } catch (e: Exception) {
            statusText.value = "⚠ Error: ${e.message}"
            android.util.Log.e("Keyo", "startVoiceRecording failed", e)
        }
    }

    private fun cancelVoiceRecording() {
        try {
            audioRecorder.stop(File(cacheDir, "voice_input.wav"))
        } catch (_: Exception) {}
        isRecording.value = false
        statusText.value = "✕ Cancelled"
        handler.postDelayed({ if (statusText.value == "✕ Cancelled") statusText.value = "" }, 1500)
    }

    private fun stopVoiceRecording() {
        try {
            if (!audioRecorder.isActive()) return

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
                            statusText.value = error ?: "Transcription failed"
                            handler.postDelayed({ statusText.value = "" }, 3000)
                        }
                    }
                }
            } else {
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

            val audioFile = File(cacheDir, "ai_voice_input.wav")
            if (aiAudioRecorder.stop(audioFile)) {
                isRecordingAI.value = false
                statusText.value = "🤖 Transcribing task..."

                GroqApi.transcribe(audioFile) { text, error ->
                    if (text != null) {
                        handler.post { statusText.value = "🤖 Executing: $text" }
                        GroqApi.executeTask(text, this@KeyoService) { result, taskError ->
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
