package com.keyo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * Settings are organised as a home screen of category tiles, each opening a focused sub-screen
 * (iOS-style). Navigation is a single [screen] string driven by the home tiles and the system Back
 * button — no nav library. The palette is a strict, mostly-monochrome dark theme with one accent.
 */
class SettingsActivity : ComponentActivity() {

    // Bumped on resume so the Setup statuses re-evaluate after returning from system screens.
    private val resumeTick = mutableStateOf(0)
    // Deep-link target (e.g. "phrases") so we can open straight to that screen.
    private val deepLink = mutableStateOf<String?>(null)

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { resumeTick.value++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLink.value = intent?.getStringExtra("section")
        setContent { SettingsApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = intent.getStringExtra("section")
    }

    override fun onResume() {
        super.onResume()
        resumeTick.value++
    }

    // ---- Setup status helpers ----
    private fun isMicGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isKeyboardEnabled(): Boolean = try {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .enabledInputMethodList.any { it.packageName == packageName }
    } catch (_: Exception) { false }

    private fun isDefaultIme(): Boolean = try {
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
    } catch (_: Exception) { false }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {}
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {}
    }

    // ---- Linear / Vercel inspired palette: strict, mostly monochrome, single accent ----
    private val bg = Color(0xFF0B0B0E)
    private val groupBg = Color(0xFF141417)
    private val border = Color(0xFF26262C)
    private val divider = Color(0xFF222228)
    private val textPrimary = Color(0xFFECECEE)
    private val textMuted = Color(0xFF8B8B94)
    private val textFaint = Color(0xFF5C5C66)
    private val accent = Color(0xFF5E6AD2)
    private val accentSoft = Color(0xFF5E6AD2).copy(alpha = 0.14f)   // tinted icon tiles / banners

    // ============================== Navigation shell ==============================

    @Composable
    fun SettingsApp() {
        var screen by rememberSaveable { mutableStateOf("home") }
        // Honour a deep link (e.g. the ★ key opening straight to Phrases).
        LaunchedEffect(deepLink.value) {
            when (deepLink.value) {
                "phrases" -> screen = "phrases"
            }
        }
        Surface(modifier = Modifier.fillMaxSize(), color = bg) {
            Crossfade(targetState = screen, label = "settings-nav") { s ->
                when (s) {
                    "home"       -> HomeScreen(onOpen = { screen = it })
                    "setup"      -> SetupScreen { screen = "home" }
                    "typing"     -> TypingScreen { screen = "home" }
                    "appearance" -> AppearanceScreen { screen = "home" }
                    "languages"  -> LanguagesScreen { screen = "home" }
                    "voiceai"    -> VoiceAiScreen { screen = "home" }
                    "phrases"    -> PhrasesScreen { screen = "home" }
                    "help"       -> HelpScreen { screen = "home" }
                    "about"      -> AboutScreen { screen = "home" }
                    else         -> HomeScreen(onOpen = { screen = it })
                }
            }
        }
        // System Back returns to home from any sub-screen; on home it falls through (finishes).
        if (screen != "home") BackHandler { screen = "home" }
    }

    // ============================== Home ==============================

    @Composable
    private fun HomeScreen(onOpen: (String) -> Unit) {
        @Suppress("UNUSED_VARIABLE") val refresh = resumeTick.value
        val done = listOf(isKeyboardEnabled(), isDefaultIme(), isMicGranted()).count { it }

        val enabled = KeyboardPrefs.getEnabledLanguages(this@SettingsActivity)
        val langNames = KeyboardPrefs.SUPPORTED_LANGUAGES
            .filter { it.first in enabled }.joinToString(" · ") { it.second }
        val pinnedCount = KeyboardPrefs.getPinned(this@SettingsActivity).size
        var dictCount by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            UserDictionary.ensureLoaded(this@SettingsActivity)
            dictCount = UserDictionary.wordsByFrequency().size
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.height(36.dp))
            Text("Keyo", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = textPrimary)
            Text("AI voice keyboard", fontSize = 14.sp, color = textMuted)
            Spacer(Modifier.height(12.dp))

            SetupBanner(done, 3) { onOpen("setup") }
            Spacer(Modifier.height(8.dp))

            Group {
                NavTile("⌨️", "Typing", "Suggestions, glide, autocorrect") { onOpen("typing") }
                Sep()
                NavTile("🎨", "Appearance", "Theme, keyboard size, number row") { onOpen("appearance") }
                Sep()
                NavTile("🌐", "Languages", langNames.ifEmpty { "Choose keyboards" }) { onOpen("languages") }
                Sep()
                NavTile("✨", "Voice & AI", "Models, dictation, API key") { onOpen("voiceai") }
            }
            Spacer(Modifier.height(8.dp))
            Group {
                NavTile("📋", "Clips & dictionary", "$pinnedCount pinned · $dictCount words") { onOpen("phrases") }
                Sep()
                NavTile("💡", "Help & gestures", "Dictation, cursor, shortcuts") { onOpen("help") }
                Sep()
                NavTile("ℹ️", "About", "Version & credits") { onOpen("about") }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Keyo v${BuildConfig.VERSION_NAME}",
                fontSize = 12.sp, color = textFaint, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    // ============================== Sub-screens ==============================

    @Composable
    private fun SetupScreen(onBack: () -> Unit) {
        @Suppress("UNUSED_VARIABLE") val refresh = resumeTick.value
        val kbEnabled = isKeyboardEnabled(); val isDefault = isDefaultIme(); val micOk = isMicGranted()
        SubScreen("Setup", onBack) {
            Group {
                SetupRow("Enable keyboard", if (kbEnabled) "Enabled" else "Turn Keyo on in system settings", kbEnabled) {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
                Sep()
                SetupRow("Switch to Keyo", if (isDefault) "Currently active" else "Pick Keyo as the keyboard", isDefault) {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                }
                Sep()
                SetupRow("Microphone", if (micOk) "Granted" else "Required for voice input", micOk) {
                    if (micOk) openAppSettings() else requestPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            Hint("Complete the three steps to start using Keyo. You can revisit this any time.")
        }
    }

    @Composable
    private fun TypingScreen(onBack: () -> Unit) {
        var suggestions by remember { mutableStateOf(KeyboardPrefs.isSuggestionsEnabled(this@SettingsActivity)) }
        var swipeTyping by remember { mutableStateOf(KeyboardPrefs.isSwipeTyping(this@SettingsActivity)) }
        var autoCorrectTyping by remember { mutableStateOf(KeyboardPrefs.isAutocorrectTyping(this@SettingsActivity)) }
        var autoCap by remember { mutableStateOf(KeyboardPrefs.isAutoCap(this@SettingsActivity)) }
        var dblSpace by remember { mutableStateOf(KeyboardPrefs.isDoubleSpacePeriod(this@SettingsActivity)) }
        var haptic by remember { mutableStateOf(KeyboardPrefs.getHapticStrength(this@SettingsActivity)) }
        var sound by remember { mutableStateOf(KeyboardPrefs.isSoundEnabled(this@SettingsActivity)) }

        SubScreen("Typing", onBack) {
            SectionLabel("Suggestions & corrections")
            Group {
                ToggleRow("Word suggestions", "Show suggestions while typing; tap to insert", suggestions) {
                    suggestions = it; KeyboardPrefs.setSuggestionsEnabled(this@SettingsActivity, it)
                }
                Sep()
                ToggleRow("Glide typing", "Slide across letters to type a word", swipeTyping) {
                    swipeTyping = it; KeyboardPrefs.setSwipeTyping(this@SettingsActivity, it)
                }
                Sep()
                ToggleRow("Auto-correct while typing", "Fixes the previous word on space; Backspace reverts", autoCorrectTyping) {
                    autoCorrectTyping = it; KeyboardPrefs.setAutocorrectTyping(this@SettingsActivity, it)
                }
            }

            SectionLabel("Behaviour")
            Group {
                ToggleRow("Auto-capitalize", "Capitalize the start of sentences", autoCap) {
                    autoCap = it; KeyboardPrefs.setAutoCap(this@SettingsActivity, it)
                }
                Sep()
                ToggleRow("Double-space → period", "Two spaces insert \". \"", dblSpace) {
                    dblSpace = it; KeyboardPrefs.setDoubleSpacePeriod(this@SettingsActivity, it)
                }
            }

            SectionLabel("Feedback")
            Group {
                ChoiceRow("Haptics", KeyboardPrefs.HAPTIC_LEVELS, haptic) {
                    haptic = it; KeyboardPrefs.setHapticStrength(this@SettingsActivity, it)
                }
                Sep()
                ToggleRow("Key sound", "Play a click on every key", sound) {
                    sound = it; KeyboardPrefs.setSoundEnabled(this@SettingsActivity, it)
                }
            }
        }
    }

    @Composable
    private fun AppearanceScreen(onBack: () -> Unit) {
        var selectedTheme by remember { mutableStateOf(KeyboardPrefs.getTheme(this@SettingsActivity)) }
        var numberRow by remember { mutableStateOf(KeyboardPrefs.isNumberRowEnabled(this@SettingsActivity)) }
        var keyH by remember { mutableStateOf(KeyboardPrefs.getKeyHeight(this@SettingsActivity)) }
        var vGap by remember { mutableStateOf(KeyboardPrefs.getVGap(this@SettingsActivity)) }
        var hGap by remember { mutableStateOf(KeyboardPrefs.getHGap(this@SettingsActivity)) }
        var testText by remember { mutableStateOf("") }

        SubScreen("Appearance", onBack) {
            SectionLabel("Color theme")
            ThemePicker(selectedTheme) {
                selectedTheme = it; KeyboardPrefs.setTheme(this@SettingsActivity, it)
            }

            SectionLabel("Layout")
            Group {
                ToggleRow("Number row", "Show the 1–0 row above the letters", numberRow) {
                    numberRow = it; KeyboardPrefs.setNumberRowEnabled(this@SettingsActivity, it)
                }
            }

            SectionLabel("Keyboard size")
            Group {
                SliderRow("Key height", keyH, "dp", KeyboardPrefs.KEY_HEIGHT_RANGE) {
                    keyH = it; KeyboardPrefs.setKeyHeight(this@SettingsActivity, it)
                }
                Sep()
                SliderRow("Vertical spacing", vGap, "dp", KeyboardPrefs.GAP_RANGE) {
                    vGap = it; KeyboardPrefs.setVGap(this@SettingsActivity, it)
                }
                Sep()
                SliderRow("Horizontal spacing", hGap, "dp", KeyboardPrefs.GAP_RANGE) {
                    hGap = it; KeyboardPrefs.setHGap(this@SettingsActivity, it)
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    PlainField(testText, "Tap here and type to preview…") { testText = it }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Type here while dragging — the keyboard resizes live.",
                            fontSize = 12.sp, color = textFaint, modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                        Text(
                            "Reset", fontSize = 13.sp, color = accent, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable {
                                KeyboardPrefs.resetSize(this@SettingsActivity)
                                keyH = KeyboardPrefs.getKeyHeight(this@SettingsActivity)
                                vGap = KeyboardPrefs.getVGap(this@SettingsActivity)
                                hGap = KeyboardPrefs.getHGap(this@SettingsActivity)
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun LanguagesScreen(onBack: () -> Unit) {
        val flags = mapOf("en" to "🇬🇧", "ru" to "🇷🇺", "lv" to "🇱🇻")
        var enabledLangs by remember { mutableStateOf(KeyboardPrefs.getEnabledLanguages(this@SettingsActivity)) }
        SubScreen("Languages", onBack) {
            Group {
                KeyboardPrefs.SUPPORTED_LANGUAGES.forEachIndexed { i, (code, name) ->
                    if (i > 0) Sep()
                    CheckRow("${flags[code] ?: "🏳"}   $name", enabledLangs.contains(code)) { want ->
                        KeyboardPrefs.setLanguageEnabled(this@SettingsActivity, code, want)
                        enabledLangs = KeyboardPrefs.getEnabledLanguages(this@SettingsActivity)
                    }
                }
            }
            Hint("English and Latvian share one Latin keyboard — enable both for a bilingual layout whose suggestions, autocorrect and glide draw from both, with no language switching. Latvian diacritics (ā č ē ī š ž…) are on long-press. The 🌐 key (or a space-bar swipe) flips between Latin and Russian.")
        }
    }

    @Composable
    private fun VoiceAiScreen(onBack: () -> Unit) {
        var selectedModel by remember { mutableStateOf(KeyboardPrefs.getModel(this@SettingsActivity)) }
        var selectedAiModel by remember { mutableStateOf(KeyboardPrefs.getAiModel(this@SettingsActivity)) }
        var autocorrect by remember { mutableStateOf(KeyboardPrefs.isAutocorrectEnabled(this@SettingsActivity)) }
        var liveDictation by remember { mutableStateOf(KeyboardPrefs.isLiveDictation(this@SettingsActivity)) }

        SubScreen("Voice & AI", onBack) {
            SectionLabel("Models")
            ModelSelector("Transcription model", "Cleans up dictation", KeyboardPrefs.AVAILABLE_MODELS, selectedModel) { id ->
                selectedModel = id; KeyboardPrefs.setModel(this@SettingsActivity, id); GroqApi.model = id
            }
            ModelSelector("AI assistant model", "Powers ✨ Rewrite and hold-to-speak AI tasks", KeyboardPrefs.AVAILABLE_MODELS, selectedAiModel) { id ->
                selectedAiModel = id; KeyboardPrefs.setAiModel(this@SettingsActivity, id); GroqApi.aiModel = id
            }
            Group {
                ToggleRow("Live dictation", "Show words in the field as you speak (uses more data)", liveDictation) {
                    liveDictation = it; KeyboardPrefs.setLiveDictation(this@SettingsActivity, it)
                }
                Sep()
                ToggleRow("Dictation cleanup", "Tidies up dictation: punctuation, fillers, casing", autocorrect) {
                    autocorrect = it; KeyboardPrefs.setAutocorrectEnabled(this@SettingsActivity, it)
                }
            }
            Hint("Voice and AI use the Groq API. They're disabled in password fields.")

            SectionLabel("Groq API key")
            ApiKeyGroup()

            VoiceCommandsSection()
        }
    }

    @Composable
    private fun PhrasesScreen(onBack: () -> Unit) {
        var pinned by remember { mutableStateOf(KeyboardPrefs.getPinned(this@SettingsActivity)) }
        var newPhrase by remember { mutableStateOf("") }

        var dictWords by remember { mutableStateOf(emptyList<String>()) }
        var dictQuery by remember { mutableStateOf("") }
        var newWord by remember { mutableStateOf("") }
        var dictGroup by remember { mutableStateOf("ru") }   // which learned-words group is shown
        LaunchedEffect(Unit) {
            UserDictionary.ensureLoaded(this@SettingsActivity)
            dictWords = UserDictionary.wordsByFrequency()
        }
        fun refreshDict() { dictWords = UserDictionary.wordsByFrequency() }

        SubScreen("Clips & dictionary", onBack) {
            SectionLabel("Pinned clips")
            Group {
                Column(Modifier.padding(12.dp)) {
                    if (pinned.isEmpty()) {
                        Text("No pinned clips yet. Pin text from the clipboard panel (📋), or add reusable templates here — they stay at the top of the clipboard.",
                            color = textMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    pinned.forEach { p ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(p.replace("\n", " ").take(80), color = textPrimary, fontSize = 14.sp,
                                maxLines = 2, modifier = Modifier.weight(1f).padding(end = 8.dp))
                            DeleteGlyph { KeyboardPrefs.removePinned(this@SettingsActivity, p); pinned = KeyboardPrefs.getPinned(this@SettingsActivity) }
                        }
                        HorizontalDivider(color = divider, thickness = 1.dp)
                    }
                    Spacer(Modifier.height(10.dp))
                    PlainField(newPhrase, "New pinned clip or template…") { newPhrase = it }
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Add pinned clip") {
                        if (newPhrase.isNotBlank()) {
                            KeyboardPrefs.addPinned(this@SettingsActivity, newPhrase.trim())
                            pinned = KeyboardPrefs.getPinned(this@SettingsActivity); newPhrase = ""
                        }
                    }
                }
            }

            SectionLabel("Personal dictionary")
            Group {
                Column(Modifier.padding(12.dp)) {
                    PlainField(newWord, "Add a word…", singleLine = true) { newWord = it }
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Add word") {
                        if (UserDictionary.addWord(newWord)) {
                            UserDictionary.save(this@SettingsActivity); newWord = ""; refreshDict()
                        }
                    }
                    if (dictWords.isEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("New words you keep (by tapping them) and words you add here appear in your dictionary. Common words aren't saved.",
                            color = textMuted, fontSize = 13.sp)
                    } else {
                        // Split learned words by script: Cyrillic -> Russian, the rest -> English/Latvian.
                        val ruWords = dictWords.filter { w -> w.any { it in 'а'..'я' || it == 'ё' } }
                        val latinWords = dictWords.filter { w -> w.none { it in 'а'..'я' || it == 'ё' } }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GroupChip("🇷🇺 Russian", ruWords.size, dictGroup == "ru", Modifier.weight(1f)) { dictGroup = "ru" }
                            GroupChip("EN / LV", latinWords.size, dictGroup == "latin", Modifier.weight(1f)) { dictGroup = "latin" }
                        }
                        Spacer(Modifier.height(10.dp))
                        PlainField(dictQuery, "Search words…", singleLine = true) { dictQuery = it }
                        Spacer(Modifier.height(8.dp))
                        val source = if (dictGroup == "ru") ruWords else latinWords
                        val q = dictQuery.trim().lowercase()
                        val filtered = if (q.isEmpty()) source else source.filter { it.contains(q) }
                        if (filtered.isEmpty()) {
                            Text(if (source.isEmpty()) "No words in this group yet." else "No matches.",
                                color = textMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                        }
                        val shown = filtered.take(200)
                        shown.forEach { w ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(w, color = textPrimary, fontSize = 14.sp, maxLines = 1,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp).clickable { newWord = w })
                                DeleteGlyph { UserDictionary.removeWord(w); UserDictionary.save(this@SettingsActivity); refreshDict() }
                            }
                            HorizontalDivider(color = divider, thickness = 1.dp)
                        }
                        if (filtered.size > shown.size) {
                            Spacer(Modifier.height(8.dp))
                            Text("+${filtered.size - shown.size} more — narrow with search", color = textMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Clear all words", color = Color(0xFFE5484D), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { UserDictionary.clear(this@SettingsActivity); refreshDict(); dictQuery = "" })
                    }
                }
            }
        }
    }

    @Composable
    private fun HelpScreen(onBack: () -> Unit) {
        val items = listOf(
            "Dictate" to "Long-press the space bar and speak; release to insert.",
            "Move cursor" to "Swipe left/right on the space bar.",
            "Select text" to "Hold Shift, then swipe the space bar.",
            "AI task (voice)" to "Hold the comma/✨ key (left of space) and speak a command — alarm, timer, open app, search, write a message, etc.",
            "Improve text" to "Tap ✨ in the top bar: fix grammar, shorten, change tone, translate, continue writing. Works on selected text (or the whole message).",
            "Custom rewrite (voice)" to "Hold the ✨ icon and speak an instruction (e.g. \"make it rhyme\").",
            "Caps Lock" to "Double-tap Shift; tap again to release.",
            "Accents & extra symbols" to "Long-press a letter and slide to the variant.",
            "Quick settings" to "Long-press the period and pick the ⚙ icon.",
            "Switch language" to "Tap the 🌐 key (right of comma) or swipe the space bar.",
            "Emoji · Clipboard · Phrases" to "Open them from the icons in the top bar."
        )
        SubScreen("Help & gestures", onBack) {
            Group {
                items.forEachIndexed { i, (a, b) ->
                    if (i > 0) Sep()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(a, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = textPrimary)
                        Text(b, fontSize = 12.sp, color = textMuted, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun AboutScreen(onBack: () -> Unit) {
        SubScreen("About", onBack) {
            Group {
                Column(Modifier.padding(16.dp)) {
                    Text("Keyo", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                    Text("AI voice keyboard", fontSize = 13.sp, color = textMuted)
                    Spacer(Modifier.height(10.dp))
                    Text("v${BuildConfig.VERSION_NAME}", fontSize = 13.sp, color = textFaint)
                    Spacer(Modifier.height(14.dp))
                    Text("Long-press the space bar to dictate, hold the ✨ key to run a task.",
                        fontSize = 13.sp, color = textMuted)
                }
            }
            Group {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Brought to you by SitesPro", fontSize = 13.sp, color = textMuted)
                    Text("sitespro.org", fontSize = 14.sp, color = accent, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 4.dp).clickable { openUrl("https://sitespro.org") })
                }
            }
        }
    }

    // ============================== Composite pieces ==============================

    /** Groq API key editor (text field + Save/Test + helper links). Used on the Voice & AI screen. */
    @Composable
    private fun ApiKeyGroup() {
        var apiKey by remember { mutableStateOf(KeyboardPrefs.getApiKey(this@SettingsActivity)) }
        var keyVisible by remember { mutableStateOf(false) }
        var testing by remember { mutableStateOf(false) }
        Group {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = { Text("gsk_…", color = textFaint) },
                    singleLine = true,
                    visualTransformation = if (keyVisible) androidx.compose.ui.text.input.VisualTransformation.None
                        else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        Text(if (keyVisible) "Hide" else "Show", color = accent, fontSize = 12.sp,
                            modifier = Modifier.clickable { keyVisible = !keyVisible }.padding(horizontal = 12.dp))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            KeyboardPrefs.setApiKey(this@SettingsActivity, apiKey)
                            GroqApi.apiKey = apiKey.ifBlank { BuildConfig.GROQ_API_KEY }
                            android.widget.Toast.makeText(this@SettingsActivity, "API key saved", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                    ) { Text("Save key", color = Color.White, fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(
                        onClick = {
                            GroqApi.apiKey = apiKey.ifBlank { BuildConfig.GROQ_API_KEY }
                            testing = true
                            GroqApi.testKey { ok, err ->
                                runOnUiThread {
                                    testing = false
                                    android.widget.Toast.makeText(this@SettingsActivity,
                                        if (ok) "✅ Key works" else "❌ ${err ?: "Failed"}",
                                        android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !testing, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, border)
                    ) { Text(if (testing) "Testing…" else "Test", color = textPrimary) }
                }
                Spacer(Modifier.height(10.dp))
                Text("Leave empty to use the built-in default.", fontSize = 12.sp, color = textFaint)
                Text("Get a free key at console.groq.com/keys ↗", fontSize = 12.sp, color = accent,
                    modifier = Modifier.padding(top = 2.dp).clickable { openUrl("https://console.groq.com/keys") })
            }
        }
    }

    /** Collapsible reference list of the spoken AI commands. */
    @Composable
    private fun VoiceCommandsSection() {
        val toolCount = com.keyo.tools.ToolRegistry.all().count { it.uiExample.isNotEmpty() }
        ExpandableSection("AI voice commands", "$toolCount commands — hold the ✨ key and say…") {
            Spacer(Modifier.height(6.dp))
            com.keyo.tools.ToolRegistry.all().forEach { tool ->
                if (tool.uiExample.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tool.uiLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = textPrimary, modifier = Modifier.width(150.dp))
                        Text("\"${tool.uiExample}\"", fontSize = 12.sp, color = textMuted)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    // ============================== Reusable building blocks ==============================

    /** A sub-screen scaffold: a back-arrow top bar + a scrolling, padded content column. */
    @Composable
    private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 28.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) { Text("‹", fontSize = 26.sp, color = textPrimary) }
                Spacer(Modifier.width(2.dp))
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
            }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Spacer(Modifier.height(4.dp))
                content()
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    /** A home-screen navigation tile: tinted icon badge + title + subtitle + chevron. */
    @Composable
    private fun NavTile(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).background(accentSoft, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) { Text(icon, fontSize = 18.sp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = textPrimary)
                Text(subtitle, fontSize = 12.sp, color = textMuted)
            }
            Text("›", fontSize = 20.sp, color = textFaint)
        }
    }

    /** Top-of-home banner: setup progress when incomplete, a subtle "ready" card when done. */
    @Composable
    private fun SetupBanner(done: Int, total: Int, onClick: () -> Unit) {
        val complete = done >= total
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(if (complete) groupBg else accentSoft, RoundedCornerShape(14.dp))
                .border(1.dp, if (complete) border else accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .clickable { onClick() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (complete) "Keyo is ready" else "Finish setting up",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                Text(if (complete) "All steps complete — tap to review" else "$done of $total steps done",
                    fontSize = 12.sp, color = if (complete) textMuted else textPrimary.copy(alpha = 0.85f))
                if (!complete) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        repeat(total) { i ->
                            Box(Modifier.height(4.dp).weight(1f)
                                .background(if (i < done) accent else border, RoundedCornerShape(2.dp)))
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            if (complete) Text("✓", color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            else Text("›", color = textPrimary, fontSize = 20.sp)
        }
    }

    /** A 1dp in-group separator (named so the screens read cleanly). */
    @Composable
    private fun Sep() = HorizontalDivider(color = divider, thickness = 1.dp)

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textFaint,
            letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)
        )
    }

    @Composable
    private fun Group(content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(groupBg, RoundedCornerShape(14.dp))
                .border(1.dp, border, RoundedCornerShape(14.dp)),
            content = content
        )
    }

    @Composable
    private fun Hint(text: String) {
        Text(text, fontSize = 12.sp, color = textFaint, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
    }

    // A collapsible card whose body is revealed by tapping the header.
    @Composable
    private fun ExpandableSection(
        label: String,
        summary: String,
        initiallyExpanded: Boolean = false,
        content: @Composable ColumnScope.() -> Unit
    ) {
        var expanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
        SectionLabel(label)
        Group {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(summary, fontSize = 14.sp, color = textMuted, modifier = Modifier.weight(1f))
                Text(if (expanded) "⌃" else "⌄", fontSize = 16.sp, color = textFaint)
            }
            if (expanded) {
                Sep()
                content()
            }
        }
    }

    // A Setup step row: shows a check when done, otherwise a chevron.
    @Composable
    private fun SetupRow(title: String, status: String, done: Boolean, onClick: () -> Unit) {
        RowScaffold(onClick = onClick) {
            TitleBlock(title, status, Modifier.weight(1f))
            if (done) Text("✓", color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            else Text("›", color = textFaint, fontSize = 20.sp)
        }
    }

    @Composable
    private fun RowScaffold(onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }

    @Composable
    private fun TitleBlock(title: String, description: String?, modifier: Modifier = Modifier) {
        Column(modifier) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = textPrimary)
            if (!description.isNullOrEmpty()) Text(description, fontSize = 12.sp, color = textMuted)
        }
    }

    @Composable
    private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        RowScaffold {
            TitleBlock(title, description, Modifier.weight(1f))
            Switch(
                checked = checked, onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White, checkedTrackColor = accent,
                    uncheckedThumbColor = textMuted, uncheckedTrackColor = groupBg, uncheckedBorderColor = border
                )
            )
        }
    }

    @Composable
    private fun CheckRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        RowScaffold(onClick = { onChange(!checked) }) {
            TitleBlock(title, null, Modifier.weight(1f))
            Checkbox(
                checked = checked, onCheckedChange = onChange,
                colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = textFaint, checkmarkColor = Color.White)
            )
        }
    }

    /** A labelled row with a horizontal segmented chooser (Linear-style chips). */
    @Composable
    private fun ChoiceRow(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = textPrimary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (id, label) ->
                    val isSel = id == selected
                    Box(
                        modifier = Modifier.weight(1f)
                            .background(if (isSel) accent else Color.Transparent, RoundedCornerShape(8.dp))
                            .border(1.dp, if (isSel) accent else border, RoundedCornerShape(8.dp))
                            .clickable { onSelect(id) }.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 12.sp, color = if (isSel) Color.White else textMuted,
                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }

    /** A labelled integer slider (used by the visual keyboard-size editor). */
    @Composable
    private fun SliderRow(title: String, value: Int, unit: String, range: IntRange, onChange: (Int) -> Unit) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = textPrimary)
                Text("$value $unit", fontSize = 13.sp, color = accent)
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { f -> val v = Math.round(f); if (v != value) onChange(v) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first - 1).coerceAtLeast(0),
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = border)
            )
        }
    }

    @Composable
    private fun ModelSelector(
        title: String, subtitle: String, models: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit
    ) {
        var expanded by remember { mutableStateOf(false) }
        val currentLabel = models.firstOrNull { it.first == selected }?.second ?: selected
        Group {
            Box {
                RowScaffold(onClick = { expanded = true }) {
                    Column(Modifier.weight(1f)) {
                        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = textPrimary)
                        Text(subtitle, fontSize = 12.sp, color = textMuted)
                        Text(currentLabel, fontSize = 12.sp, color = accent)
                    }
                    Text("▾", fontSize = 16.sp, color = textMuted)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(groupBg)) {
                    models.forEach { (modelId, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(label, fontSize = 14.sp,
                                    color = if (modelId == selected) accent else textPrimary,
                                    fontWeight = if (modelId == selected) FontWeight.SemiBold else FontWeight.Normal)
                            },
                            onClick = { onSelect(modelId); expanded = false }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ThemePicker(selected: String, onSelect: (String) -> Unit) {
        Group {
            KeyboardPrefs.THEMES.forEachIndexed { i, theme ->
                if (i > 0) Sep()
                val isSelected = theme.id == selected
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(theme.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(18.dp).background(Color(theme.bg), CircleShape).border(1.dp, border, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(18.dp).background(Color(theme.key), CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(18.dp).background(Color(theme.accent), CircleShape))
                    Spacer(Modifier.width(14.dp))
                    Text(theme.name, fontSize = 14.sp,
                        color = if (isSelected) textPrimary else textMuted,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f))
                    if (isSelected) Text("✓", color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // --- small shared widgets ---

    @Composable
    private fun fieldColors() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = textPrimary, unfocusedTextColor = textPrimary,
        focusedBorderColor = accent, unfocusedBorderColor = border, cursorColor = accent
    )

    @Composable
    private fun PlainField(value: String, placeholder: String, singleLine: Boolean = false, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value, onValueChange = onChange,
            placeholder = { Text(placeholder, color = textFaint) },
            singleLine = singleLine, modifier = Modifier.fillMaxWidth(), colors = fieldColors()
        )
    }

    @Composable
    private fun PrimaryButton(label: String, onClick: () -> Unit) {
        Button(
            onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent)
        ) { Text(label, color = Color.White, fontWeight = FontWeight.SemiBold) }
    }

    @Composable
    private fun DeleteGlyph(onClick: () -> Unit) {
        Text("✕", color = textMuted, fontSize = 16.sp, modifier = Modifier.clickable { onClick() }.padding(start = 8.dp, end = 2.dp))
    }

    /** A segmented-style chip with a label and a count, for switching the dictionary's language group. */
    @Composable
    private fun GroupChip(label: String, count: Int, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Box(
            modifier = modifier
                .background(if (selected) accent else Color.Transparent, RoundedCornerShape(8.dp))
                .border(1.dp, if (selected) accent else border, RoundedCornerShape(8.dp))
                .clickable { onClick() }
                .padding(vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("$label · $count", fontSize = 12.sp,
                color = if (selected) Color.White else textMuted,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}
