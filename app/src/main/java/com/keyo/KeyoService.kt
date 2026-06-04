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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Path
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
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
    private var keyboardSize = mutableStateOf("normal")     // compact / normal / large / xlarge
    private var hapticStrength = mutableStateOf("light")    // off / light / medium / strong
    private var currentTheme = KeyboardPrefs.KeyboardTheme("catppuccin","",0xFF1E1E2E,0xFF313244,0xFFBB86FC,0xFFCDD6F4)
    private var soundEnabled = mutableStateOf(false)
    private var themeId = mutableStateOf("catppuccin")

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
        GroqApi.model = KeyboardPrefs.getModel(this)
        GroqApi.aiModel = KeyboardPrefs.getAiModel(this)
        showNumberRow.value = KeyboardPrefs.isNumberRowEnabled(this)
        keyboardSize.value = KeyboardPrefs.getKeyboardSize(this)
        hapticStrength.value = KeyboardPrefs.getHapticStrength(this)
        soundEnabled.value = KeyboardPrefs.isSoundEnabled(this)
        themeId.value = KeyboardPrefs.getTheme(this)
        enabledLangs.value = KeyboardPrefs.getEnabledLanguages(this)
        currentLang.value = KeyboardPrefs.getCurrentLanguage(this)
        // Store IME action for enter key behavior
        imeActionId.intValue = info?.imeOptions?.and(android.view.inputmethod.EditorInfo.IME_MASK_ACTION)
            ?: android.view.inputmethod.EditorInfo.IME_ACTION_NONE
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        // Never go fullscreen — always let the app show above the keyboard
        return false
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
        val size by keyboardSize
        val keyHeight = KeyboardPrefs.rowHeightDp(size).dp

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
            // Fixed-height status line so the layout never shifts when status appears/clears
            Box(
                modifier = Modifier.fillMaxWidth().height(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (status.isNotEmpty()) {
                    Text(
                        text = status,
                        color = accentColor,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

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
                        KeyButton("!?#", accentColor, Color.Black, Modifier.weight(1.3f)) {
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
                        listOf("~","`","|","·","√","π","τ","÷","×","¶"),
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
                        KeyButton("123", accentColor, Color.Black, Modifier.weight(1.3f)) {
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
                        KeyButton("123", accentColor, Color.Black, Modifier.weight(1.2f)) {
                            keyboardMode.value = "123"
                        }
                    }
                    "123", "symbols", "numpad" -> {
                        KeyButton("ABC", accentColor, Color.Black, Modifier.weight(1.2f)) {
                            keyboardMode.value = "abc"
                        }
                    }
                }

                // Comma — long press starts AI assistant (🤖)
                val recordingAI by isRecordingAI
                var aiCancelled by remember { mutableStateOf(false) }
                var aiDragX by remember { mutableFloatStateOf(0f) }
                val cancelThreshold = 100f

                Box(
                    modifier = Modifier
                        .weight(0.9f)
                        .height(keyHeight)
                        .padding(1.dp)
                        .background(
                            when {
                                recordingAI && aiDragX < -cancelThreshold -> Color(0xFF666666)
                                recordingAI -> Color(0xFF50FA7B)
                                else -> keyColor
                            },
                            RoundedCornerShape(6.dp)
                        )
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                aiCancelled = false
                                aiDragX = 0f
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
                    Text(
                        text = when {
                            recordingAI && aiDragX < -cancelThreshold -> "✕"
                            recordingAI -> "🤖"
                            else -> ","
                        },
                        fontSize = if (recordingAI) 16.sp else 18.sp,
                        color = if (recordingAI) Color.Black else textColor
                    )
                    if (!recordingAI) {
                        Text(
                            "🤖",
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)
                        )
                    }
                }

                // Right of the comma: globe = switch language (abc); mode hint otherwise
                when (mode) {
                    "abc" -> {
                        KeyButton("🌐", keyColor, textColor, Modifier.weight(1f)) {
                            cycleLanguage()
                        }
                    }
                    "123" -> {
                        KeyButton("!?#", keyColor, textColor, Modifier.weight(1f)) {
                            keyboardMode.value = "symbols"
                        }
                    }
                    "symbols" -> {
                        KeyButton("🔢", keyColor, textColor, Modifier.weight(1f)) {
                            keyboardMode.value = "numpad"
                        }
                    }
                    "numpad" -> {
                        KeyButton("123", keyColor, textColor, Modifier.weight(1f)) {
                            keyboardMode.value = "123"
                        }
                    }
                }

                // Space — swipe to switch language, long press for voice dictation (🎤)
                val spaceLabel = if (mode == "abc") lang.uppercase() else "space"
                var micCancelled by remember { mutableStateOf(false) }
                var micDragX by remember { mutableFloatStateOf(0f) }

                Box(
                    modifier = Modifier
                        .weight(3.5f)
                        .height(keyHeight)
                        .padding(1.dp)
                        .background(
                            when {
                                recording && micDragX < -cancelThreshold -> Color(0xFF666666)
                                recording -> recordColor
                                else -> keyColor
                            },
                            RoundedCornerShape(6.dp)
                        )
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                var totalDragX = 0f
                                var swiped = false
                                var longPressed = false
                                micCancelled = false
                                micDragX = 0f

                                val longPressJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                    kotlinx.coroutines.delay(400)
                                    longPressed = true
                                    startVoiceRecording()
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
                                        totalDragX = change.position.x - down.position.x
                                        if (longPressed) {
                                            micDragX = totalDragX
                                            if (micDragX < -cancelThreshold) micCancelled = true
                                        } else if (kotlin.math.abs(totalDragX) > 60f) {
                                            swiped = true
                                        }
                                        change.consume()
                                    }
                                } catch (_: kotlinx.coroutines.CancellationException) {}

                                longPressJob.cancel()
                                if (longPressed) {
                                    if (micCancelled) cancelVoiceRecording() else stopVoiceRecording()
                                    micDragX = 0f
                                } else if (swiped) {
                                    performKeyFeedback()
                                    cycleLanguage()
                                } else {
                                    performKeyFeedback()
                                    commitChar(' ')
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (recording) {
                        Text(
                            text = if (micDragX < -cancelThreshold) "✕" else "🎤 ●",
                            fontSize = 16.sp,
                            color = Color.White
                        )
                    } else {
                        Text(
                            text = spaceLabel,
                            color = textColor,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "🎤",
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp)
                        )
                    }
                }

                // Period — long press shows ? , ! -
                KeyButton(".", keyColor, textColor, Modifier.weight(0.9f)) { commitChar('.') }

                // Enter / Submit
                val imeAction by imeActionId
                val isTextMode = mode == "123" || mode == "symbols" || mode == "numpad"
                val hasImeAction = imeAction != android.view.inputmethod.EditorInfo.IME_ACTION_NONE
                        && imeAction != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED

                val enterLabel = when {
                    isTextMode -> "↵"  // Always newline in symbol/number modes
                    hasImeAction -> when (imeAction) {
                        android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH -> "🔍"
                        android.view.inputmethod.EditorInfo.IME_ACTION_SEND -> "➤"
                        android.view.inputmethod.EditorInfo.IME_ACTION_GO -> "➤"
                        android.view.inputmethod.EditorInfo.IME_ACTION_DONE -> "✓"
                        android.view.inputmethod.EditorInfo.IME_ACTION_NEXT -> "→"
                        else -> "➤"
                    }
                    else -> "↵"
                }

                KeyButton(enterLabel, accentColor, Color.Black, Modifier.weight(1.0f)) {
                    if (isTextMode || !hasImeAction) {
                        commitChar('\n')
                    } else {
                        currentInputConnection?.performEditorAction(imeAction)
                    }
                }
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
        Box(
            modifier = modifier
                .height(height)
                .padding(2.dp)
                .background(if (active) accentColor else keyColor, RoundedCornerShape(6.dp))
                .clickable {
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
        val bsHeight = KeyboardPrefs.rowHeightDp(keyboardSize.value).dp

        Box(
            modifier = modifier
                .height(bsHeight)
                .padding(1.dp)
                .background(
                    if (swipedClear) Color(0xFFFF5555) else bgColor,
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
            Text(
                text = if (swipedClear) "✕" else "⌫",
                color = if (swipedClear) Color.White else textColor,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
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

    @Composable
    fun KeyButton(
        label: String,
        bgColor: Color,
        textColor: Color,
        modifier: Modifier,
        height: androidx.compose.ui.unit.Dp = KeyboardPrefs.rowHeightDp(keyboardSize.value).dp,
        fontSize: androidx.compose.ui.unit.TextUnit = KeyboardPrefs.fontSizeSp(keyboardSize.value).sp,
        onClick: () -> Unit
    ) {
        var pressed by remember { mutableStateOf(false) }
        var showAlts by remember { mutableStateOf(false) }
        var selectedAltIdx by remember { mutableIntStateOf(-1) }
        val alts = altChars[label]
        val hasAlts = alts != null && alts.isNotEmpty()

        Box(modifier = modifier.height(height).padding(2.dp)) {
            // Main key
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor, RoundedCornerShape(6.dp))
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
        currentInputConnection?.commitText(char.toString(), 1)
        scheduleSpellcheck()
    }

    private fun scheduleSpellcheck() {
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
                                        currentInputConnection?.commitText(result, 1)
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
