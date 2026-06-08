package com.keyo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class SettingsActivity : ComponentActivity() {

    // Bumped on resume so the Setup statuses re-evaluate after returning from system screens.
    private val resumeTick = mutableStateOf(0)
    // Deep-link target section (e.g. "phrases") so we can auto-expand it.
    private val deepLink = mutableStateOf<String?>(null)

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { resumeTick.value++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLink.value = intent?.getStringExtra("section")
        setContent { SettingsScreen() }
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

    // ---- Linear / Vercel inspired palette: strict, mostly monochrome, single accent ----
    private val bg = Color(0xFF0B0B0E)
    private val groupBg = Color(0xFF141417)
    private val border = Color(0xFF26262C)
    private val divider = Color(0xFF222228)
    private val textPrimary = Color(0xFFECECEE)
    private val textMuted = Color(0xFF8B8B94)
    private val textFaint = Color(0xFF5C5C66)
    private val accent = Color(0xFF5E6AD2)

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun SettingsScreen() {
        val phrasesRequester = remember { BringIntoViewRequester() }
        LaunchedEffect(deepLink.value) {
            if (deepLink.value == "phrases") {
                kotlinx.coroutines.delay(300)
                try { phrasesRequester.bringIntoView() } catch (_: Exception) {}
            }
        }
        Surface(modifier = Modifier.fillMaxSize(), color = bg) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Spacer(Modifier.height(28.dp))

                // Header / wordmark
                Text("Keyo", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                Text("AI voice keyboard", fontSize = 14.sp, color = textMuted)
                Spacer(Modifier.height(12.dp))

                // ===== Setup ===== (re-evaluated on resume via resumeTick)
                @Suppress("UNUSED_VARIABLE") val refresh = resumeTick.value
                val kbEnabled = isKeyboardEnabled()
                val isDefault = isDefaultIme()
                val micOk = isMicGranted()
                SectionLabel("Setup")
                Group {
                    SetupRow("Enable keyboard", if (kbEnabled) "Enabled" else "Turn Keyo on in system settings", kbEnabled) {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    }
                    HorizontalDivider(color = divider, thickness = 1.dp)
                    SetupRow("Switch to Keyo", if (isDefault) "Currently active" else "Pick Keyo as the keyboard", isDefault) {
                        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                    }
                    HorizontalDivider(color = divider, thickness = 1.dp)
                    SetupRow("Microphone", if (micOk) "Granted" else "Required for voice input", micOk) {
                        if (micOk) openAppSettings() else requestPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                Hint("One-time setup — complete the three steps to start using Keyo.")

                // ===== How to use =====
                ExpandableSection("How to use", "Gestures & shortcuts") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        listOf(
                            "Dictate" to "Long-press the space bar and speak; release to insert.",
                            "Move cursor" to "Swipe left/right on the space bar.",
                            "Select text" to "Hold Shift, then swipe the space bar.",
                            "AI task (voice)" to "Hold the comma/✨ key (left of space) and speak a command — call, SMS, alarm, write a message, etc.",
                            "Improve text" to "Tap ✨ in the top bar: fix grammar, shorten, change tone, translate, continue writing. Works on selected text (or the whole message).",
                            "Custom rewrite (voice)" to "Hold the ✨ icon and speak an instruction (e.g. \"make it rhyme\").",
                            "Caps Lock" to "Double-tap Shift; tap again to release.",
                            "Accents & extra symbols" to "Long-press a letter and slide to the variant.",
                            "Quick settings" to "Long-press the period and pick the ⚙ icon.",
                            "Switch language" to "Tap the 🌐 key (right of comma) or swipe the space bar.",
                            "Emoji · Clipboard · Phrases" to "Open them from the icons in the top bar."
                        ).forEach { (a, b) ->
                            Column(Modifier.padding(vertical = 5.dp)) {
                                Text(a, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = textPrimary)
                                Text(b, fontSize = 12.sp, color = textMuted)
                            }
                        }
                    }
                }

                // ===== Languages =====
                SectionLabel("Languages")
                val flags = mapOf("en" to "🇬🇧", "ru" to "🇷🇺", "lv" to "🇱🇻")
                var enabledLangs by remember { mutableStateOf(KeyboardPrefs.getEnabledLanguages(this@SettingsActivity)) }
                Group {
                    KeyboardPrefs.SUPPORTED_LANGUAGES.forEachIndexed { i, (code, name) ->
                        if (i > 0) HorizontalDivider(color = divider, thickness = 1.dp)
                        CheckRow("${flags[code] ?: "🏳"}   $name", enabledLangs.contains(code)) { want ->
                            KeyboardPrefs.setLanguageEnabled(this@SettingsActivity, code, want)
                            enabledLangs = KeyboardPrefs.getEnabledLanguages(this@SettingsActivity)
                        }
                    }
                }
                Hint("Switch with the 🌐 key or by swiping the space bar. Latvian diacritics (ā č ē ī š ž…) are on long-press.")

                // ===== Keyboard size (collapsible visual editor) =====
                var keyH by remember { mutableStateOf(KeyboardPrefs.getKeyHeight(this@SettingsActivity)) }
                var vGap by remember { mutableStateOf(KeyboardPrefs.getVGap(this@SettingsActivity)) }
                var hGap by remember { mutableStateOf(KeyboardPrefs.getHGap(this@SettingsActivity)) }
                var testText by remember { mutableStateOf("") }
                ExpandableSection("Keyboard size", "Height ${keyH}dp · spacing ${vGap}/${hGap} — tap to adjust") {
                    SliderRow("Key height", keyH, "dp", KeyboardPrefs.KEY_HEIGHT_RANGE) {
                        keyH = it; KeyboardPrefs.setKeyHeight(this@SettingsActivity, it)
                    }
                    HorizontalDivider(color = divider, thickness = 1.dp)
                    SliderRow("Vertical spacing", vGap, "dp", KeyboardPrefs.GAP_RANGE) {
                        vGap = it; KeyboardPrefs.setVGap(this@SettingsActivity, it)
                    }
                    HorizontalDivider(color = divider, thickness = 1.dp)
                    SliderRow("Horizontal spacing", hGap, "dp", KeyboardPrefs.GAP_RANGE) {
                        hGap = it; KeyboardPrefs.setHGap(this@SettingsActivity, it)
                    }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        OutlinedTextField(
                            value = testText,
                            onValueChange = { testText = it },
                            placeholder = { Text("Tap here and type to preview…", color = textFaint) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary,
                                focusedBorderColor = accent,
                                unfocusedBorderColor = border,
                                cursorColor = accent
                            )
                        )
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
                                "Reset",
                                fontSize = 13.sp, color = accent, fontWeight = FontWeight.Medium,
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

                // ===== Appearance =====
                SectionLabel("Appearance")
                var numberRow by remember { mutableStateOf(KeyboardPrefs.isNumberRowEnabled(this@SettingsActivity)) }
                var selectedTheme by remember { mutableStateOf(KeyboardPrefs.getTheme(this@SettingsActivity)) }

                Group {
                    ToggleRow("Number row", "Show the 1–0 row above the letters", numberRow) {
                        numberRow = it; KeyboardPrefs.setNumberRowEnabled(this@SettingsActivity, it)
                    }
                }
                ThemePicker(selectedTheme) {
                    selectedTheme = it; KeyboardPrefs.setTheme(this@SettingsActivity, it)
                }

                // ===== Typing feel =====
                SectionLabel("Typing feel")
                var haptic by remember { mutableStateOf(KeyboardPrefs.getHapticStrength(this@SettingsActivity)) }
                var sound by remember { mutableStateOf(KeyboardPrefs.isSoundEnabled(this@SettingsActivity)) }
                var dblSpace by remember { mutableStateOf(KeyboardPrefs.isDoubleSpacePeriod(this@SettingsActivity)) }
                var autoCap by remember { mutableStateOf(KeyboardPrefs.isAutoCap(this@SettingsActivity)) }
                Group {
                    ChoiceRow("Haptics", KeyboardPrefs.HAPTIC_LEVELS, haptic) {
                        haptic = it; KeyboardPrefs.setHapticStrength(this@SettingsActivity, it)
                    }
                    HorizontalDivider(color = divider, thickness = 1.dp)
                    ToggleRow("Key sound", "Play a click on every key", sound) {
                        sound = it; KeyboardPrefs.setSoundEnabled(this@SettingsActivity, it)
                    }
                    HorizontalDivider(color = divider, thickness = 1.dp)
                    ToggleRow("Double-space → period", "Two spaces insert \". \"", dblSpace) {
                        dblSpace = it; KeyboardPrefs.setDoubleSpacePeriod(this@SettingsActivity, it)
                    }
                    HorizontalDivider(color = divider, thickness = 1.dp)
                    ToggleRow("Auto-capitalize", "Capitalize the start of sentences", autoCap) {
                        autoCap = it; KeyboardPrefs.setAutoCap(this@SettingsActivity, it)
                    }
                }

                // ===== Quick phrases =====
                var phrases by remember { mutableStateOf(KeyboardPrefs.getPhrases(this@SettingsActivity)) }
                var newPhrase by remember { mutableStateOf("") }
                Column(Modifier.fillMaxWidth().bringIntoViewRequester(phrasesRequester)) {
                ExpandableSection("Quick phrases", "${phrases.size} saved — tap the ★ key to insert",
                    initiallyExpanded = deepLink.value == "phrases") {
                    Column(Modifier.padding(12.dp)) {
                        phrases.forEach { p ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(p.replace("\n", " ").take(80), color = textPrimary, fontSize = 14.sp,
                                    maxLines = 2, modifier = Modifier.weight(1f).padding(end = 8.dp))
                                Text("✕", color = textMuted, fontSize = 16.sp,
                                    modifier = Modifier.clickable {
                                        KeyboardPrefs.removePhrase(this@SettingsActivity, p)
                                        phrases = KeyboardPrefs.getPhrases(this@SettingsActivity)
                                    })
                            }
                            HorizontalDivider(color = divider, thickness = 1.dp)
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newPhrase,
                            onValueChange = { newPhrase = it },
                            placeholder = { Text("New phrase or template…", color = textFaint) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = textPrimary, unfocusedTextColor = textPrimary,
                                focusedBorderColor = accent, unfocusedBorderColor = border, cursorColor = accent
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (newPhrase.isNotBlank()) {
                                    KeyboardPrefs.addPhrase(this@SettingsActivity, newPhrase.trim())
                                    phrases = KeyboardPrefs.getPhrases(this@SettingsActivity)
                                    newPhrase = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = accent)
                        ) { Text("Add phrase", color = Color.White, fontWeight = FontWeight.SemiBold) }
                    }
                }
                }

                // ===== Voice & AI =====
                SectionLabel("Voice & AI")
                var selectedModel by remember { mutableStateOf(KeyboardPrefs.getModel(this@SettingsActivity)) }
                var selectedAiModel by remember { mutableStateOf(KeyboardPrefs.getAiModel(this@SettingsActivity)) }
                var autocorrect by remember { mutableStateOf(KeyboardPrefs.isAutocorrectEnabled(this@SettingsActivity)) }
                var spellcheck by remember { mutableStateOf(KeyboardPrefs.isSpellcheckEnabled(this@SettingsActivity)) }

                ModelSelector(
                    title = "Transcription model",
                    subtitle = "Cleans up dictation and powers live spellcheck",
                    models = KeyboardPrefs.AVAILABLE_MODELS,
                    selected = selectedModel
                ) { modelId ->
                    selectedModel = modelId
                    KeyboardPrefs.setModel(this@SettingsActivity, modelId)
                    GroqApi.model = modelId
                }
                ModelSelector(
                    title = "AI assistant model",
                    subtitle = "Powers ✨ Rewrite and the hold-to-speak AI tasks",
                    models = KeyboardPrefs.AVAILABLE_MODELS,
                    selected = selectedAiModel
                ) { modelId ->
                    selectedAiModel = modelId
                    KeyboardPrefs.setAiModel(this@SettingsActivity, modelId)
                    GroqApi.aiModel = modelId
                }
                Group {
                    ToggleRow("Auto-correction", "Tidies up dictation: punctuation, fillers, casing", autocorrect) {
                        autocorrect = it; KeyboardPrefs.setAutocorrectEnabled(this@SettingsActivity, it)
                    }
                    HorizontalDivider(color = divider, thickness = 1.dp)
                    ToggleRow("Live spellcheck", "Fixes typos while typing (after a short pause)", spellcheck) {
                        spellcheck = it; KeyboardPrefs.setSpellcheckEnabled(this@SettingsActivity, it)
                    }
                }
                Hint("Voice, spellcheck and AI use the Groq API (set the key below). They're disabled in password fields.")

                // ===== Groq API key (collapsible) =====
                var apiKey by remember { mutableStateOf(KeyboardPrefs.getApiKey(this@SettingsActivity)) }
                var keyVisible by remember { mutableStateOf(false) }
                var testing by remember { mutableStateOf(false) }
                ExpandableSection(
                    "Groq API key",
                    if (apiKey.isBlank()) "Using built-in default key" else "Custom key set"
                ) {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            placeholder = { Text("gsk_…", color = textFaint) },
                            singleLine = true,
                            visualTransformation = if (keyVisible) androidx.compose.ui.text.input.VisualTransformation.None
                                else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = {
                                Text(
                                    if (keyVisible) "Hide" else "Show",
                                    color = accent,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clickable { keyVisible = !keyVisible }
                                        .padding(horizontal = 12.dp)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary,
                                focusedBorderColor = accent,
                                unfocusedBorderColor = border,
                                cursorColor = accent
                            )
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    KeyboardPrefs.setApiKey(this@SettingsActivity, apiKey)
                                    GroqApi.apiKey = apiKey.ifBlank { BuildConfig.GROQ_API_KEY }
                                    android.widget.Toast.makeText(this@SettingsActivity, "API key saved", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = accent)
                            ) {
                                Text("Save key", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = {
                                    GroqApi.apiKey = apiKey.ifBlank { BuildConfig.GROQ_API_KEY }
                                    testing = true
                                    GroqApi.testKey { ok, err ->
                                        runOnUiThread {
                                            testing = false
                                            android.widget.Toast.makeText(
                                                this@SettingsActivity,
                                                if (ok) "✅ Key works" else "❌ ${err ?: "Failed"}",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                                enabled = !testing,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, border)
                            ) {
                                Text(if (testing) "Testing…" else "Test", color = textPrimary)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Leave empty to use the built-in default.", fontSize = 12.sp, color = textFaint)
                        Text(
                            "Get a free key at console.groq.com/keys ↗",
                            fontSize = 12.sp,
                            color = accent,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clickable {
                                    try {
                                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://console.groq.com/keys")))
                                    } catch (_: Exception) {}
                                }
                        )
                    }
                }

                // ===== AI tools (collapsible) =====
                val toolCount = com.keyo.tools.ToolRegistry.all().count { it.uiExample.isNotEmpty() }
                ExpandableSection("AI tools", "$toolCount voice commands — hold the ✨ key and say…") {
                    Spacer(Modifier.height(6.dp))
                    com.keyo.tools.ToolRegistry.all().forEach { tool ->
                        if (tool.uiExample.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tool.uiLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = textPrimary, modifier = Modifier.width(150.dp))
                                Text("\"${tool.uiExample}\"", fontSize = 12.sp, color = textMuted)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "Long-press the space bar to dictate, hold the ✨ key to run a task.\n\nKeyo v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                    fontSize = 12.sp,
                    color = textFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Brought to you by SitesPro", fontSize = 12.sp, color = textFaint)
                    Text(
                        "sitespro.org",
                        fontSize = 13.sp, color = accent, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable {
                                try {
                                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://sitespro.org")))
                                } catch (_: Exception) {}
                            }
                    )
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    // ---------- Reusable building blocks ----------

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = textFaint,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp)
        )
    }

    @Composable
    private fun Group(content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(groupBg, RoundedCornerShape(14.dp))
                .border(1.dp, border, RoundedCornerShape(14.dp)),
            content = content
        )
    }

    @Composable
    private fun Hint(text: String) {
        Text(text, fontSize = 12.sp, color = textFaint, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
    }

    // A collapsible section: a labelled card whose body is revealed by tapping the header.
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
                HorizontalDivider(color = divider, thickness = 1.dp)
                content()
            }
        }
    }

    // A Setup step row: shows a green check when done, otherwise a chevron.
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
            modifier = Modifier
                .fillMaxWidth()
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
            if (!description.isNullOrEmpty()) {
                Text(description, fontSize = 12.sp, color = textMuted)
            }
        }
    }

    @Composable
    private fun NavRow(title: String, description: String, onClick: () -> Unit) {
        RowScaffold(onClick = onClick) {
            TitleBlock(title, description, Modifier.weight(1f))
            Text("›", fontSize = 20.sp, color = textFaint)
        }
    }

    @Composable
    private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        RowScaffold {
            TitleBlock(title, description, Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = accent,
                    uncheckedThumbColor = textMuted,
                    uncheckedTrackColor = groupBg,
                    uncheckedBorderColor = border
                )
            )
        }
    }

    @Composable
    private fun CheckRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        RowScaffold(onClick = { onChange(!checked) }) {
            TitleBlock(title, null, Modifier.weight(1f))
            Checkbox(
                checked = checked,
                onCheckedChange = onChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = accent,
                    uncheckedColor = textFaint,
                    checkmarkColor = Color.White
                )
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
                        modifier = Modifier
                            .weight(1f)
                            .background(if (isSel) accent else Color.Transparent, RoundedCornerShape(8.dp))
                            .border(1.dp, if (isSel) accent else border, RoundedCornerShape(8.dp))
                            .clickable { onSelect(id) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = if (isSel) Color.White else textMuted,
                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Center
                        )
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
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = border
                )
            )
        }
    }

    @Composable
    private fun ModelSelector(
        title: String,
        subtitle: String,
        models: List<Pair<String, String>>,
        selected: String,
        onSelect: (String) -> Unit
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
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(groupBg)
                ) {
                    models.forEach { (modelId, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    fontSize = 14.sp,
                                    color = if (modelId == selected) accent else textPrimary,
                                    fontWeight = if (modelId == selected) FontWeight.SemiBold else FontWeight.Normal
                                )
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
            Text(
                "Color theme",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = textPrimary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)
            )
            KeyboardPrefs.THEMES.forEach { theme ->
                val isSelected = theme.id == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(theme.id) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(18.dp).background(Color(theme.bg), CircleShape).border(1.dp, border, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(18.dp).background(Color(theme.key), CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(18.dp).background(Color(theme.accent), CircleShape))
                    Spacer(Modifier.width(14.dp))
                    Text(
                        theme.name,
                        fontSize = 14.sp,
                        color = if (isSelected) textPrimary else textMuted,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) Text("✓", color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
