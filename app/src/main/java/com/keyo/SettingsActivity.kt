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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class SettingsActivity : ComponentActivity() {

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent { SettingsScreen() }
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

    @Composable
    fun SettingsScreen() {
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

                // ===== Setup =====
                SectionLabel("Setup")
                Group {
                    NavRow("Enable keyboard", "System Settings → Languages & input") {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    }
                    Divider(color = divider, thickness = 1.dp)
                    NavRow("Switch to Keyo", "Open the input method picker") {
                        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                    }
                    Divider(color = divider, thickness = 1.dp)
                    NavRow("Grant microphone", "Required for voice input") {
                        requestPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                // ===== Languages =====
                SectionLabel("Languages")
                var enabledLangs by remember { mutableStateOf(KeyboardPrefs.getEnabledLanguages(this@SettingsActivity)) }
                Group {
                    KeyboardPrefs.SUPPORTED_LANGUAGES.forEachIndexed { i, (code, name) ->
                        if (i > 0) Divider(color = divider, thickness = 1.dp)
                        CheckRow(name, enabledLangs.contains(code)) { want ->
                            KeyboardPrefs.setLanguageEnabled(this@SettingsActivity, code, want)
                            enabledLangs = KeyboardPrefs.getEnabledLanguages(this@SettingsActivity)
                        }
                    }
                }
                Hint("Swipe the space bar to cycle between enabled languages.")

                // ===== Voice & AI =====
                SectionLabel("Voice & AI")
                var selectedModel by remember { mutableStateOf(KeyboardPrefs.getModel(this@SettingsActivity)) }
                var selectedAiModel by remember { mutableStateOf(KeyboardPrefs.getAiModel(this@SettingsActivity)) }
                var autocorrect by remember { mutableStateOf(KeyboardPrefs.isAutocorrectEnabled(this@SettingsActivity)) }
                var spellcheck by remember { mutableStateOf(KeyboardPrefs.isSpellcheckEnabled(this@SettingsActivity)) }

                ModelSelector(
                    title = "Transcription model",
                    models = KeyboardPrefs.AVAILABLE_MODELS,
                    selected = selectedModel
                ) { modelId ->
                    selectedModel = modelId
                    KeyboardPrefs.setModel(this@SettingsActivity, modelId)
                    GroqApi.model = modelId
                }
                ModelSelector(
                    title = "AI assistant model",
                    models = KeyboardPrefs.AVAILABLE_MODELS,
                    selected = selectedAiModel
                ) { modelId ->
                    selectedAiModel = modelId
                    KeyboardPrefs.setAiModel(this@SettingsActivity, modelId)
                    GroqApi.aiModel = modelId
                }
                Group {
                    ToggleRow("Auto-correction", "LLM cleans up voice transcription", autocorrect) {
                        autocorrect = it; KeyboardPrefs.setAutocorrectEnabled(this@SettingsActivity, it)
                    }
                    Divider(color = divider, thickness = 1.dp)
                    ToggleRow("Live spellcheck", "Fixes typos while typing (after a short pause)", spellcheck) {
                        spellcheck = it; KeyboardPrefs.setSpellcheckEnabled(this@SettingsActivity, it)
                    }
                }

                // ===== API key =====
                SectionLabel("Groq API key")
                var apiKey by remember { mutableStateOf(KeyboardPrefs.getApiKey(this@SettingsActivity)) }
                var keyVisible by remember { mutableStateOf(false) }
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
                        Button(
                            onClick = {
                                KeyboardPrefs.setApiKey(this@SettingsActivity, apiKey)
                                GroqApi.apiKey = apiKey.ifBlank { BuildConfig.GROQ_API_KEY }
                                android.widget.Toast.makeText(this@SettingsActivity, "API key saved", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Text("Save key", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Column(Modifier.padding(start = 4.dp, top = 6.dp)) {
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

                // ===== Keyboard size (visual editor) =====
                SectionLabel("Keyboard size")
                var keyH by remember { mutableStateOf(KeyboardPrefs.getKeyHeight(this@SettingsActivity)) }
                var vGap by remember { mutableStateOf(KeyboardPrefs.getVGap(this@SettingsActivity)) }
                var hGap by remember { mutableStateOf(KeyboardPrefs.getHGap(this@SettingsActivity)) }
                var testText by remember { mutableStateOf("") }
                Group {
                    SliderRow("Key height", keyH, "dp", KeyboardPrefs.KEY_HEIGHT_RANGE) {
                        keyH = it; KeyboardPrefs.setKeyHeight(this@SettingsActivity, it)
                    }
                    Divider(color = divider, thickness = 1.dp)
                    SliderRow("Vertical spacing", vGap, "dp", KeyboardPrefs.GAP_RANGE) {
                        vGap = it; KeyboardPrefs.setVGap(this@SettingsActivity, it)
                    }
                    Divider(color = divider, thickness = 1.dp)
                    SliderRow("Horizontal spacing", hGap, "dp", KeyboardPrefs.GAP_RANGE) {
                        hGap = it; KeyboardPrefs.setHGap(this@SettingsActivity, it)
                    }
                }
                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    placeholder = { Text("Tap here and type to preview…", color = textFaint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textPrimary,
                        focusedBorderColor = accent,
                        unfocusedBorderColor = border,
                        cursorColor = accent
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Type in the field while dragging the sliders — the keyboard resizes live.",
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
                Group {
                    ChoiceRow("Haptics", KeyboardPrefs.HAPTIC_LEVELS, haptic) {
                        haptic = it; KeyboardPrefs.setHapticStrength(this@SettingsActivity, it)
                    }
                    Divider(color = divider, thickness = 1.dp)
                    ToggleRow("Key sound", "Play a click on every key", sound) {
                        sound = it; KeyboardPrefs.setSoundEnabled(this@SettingsActivity, it)
                    }
                }

                // ===== AI tools =====
                SectionLabel("AI tools")
                Group {
                    Text(
                        "Hold the 🤖 key and say:",
                        fontSize = 13.sp,
                        color = textMuted,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)
                    )
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
                    "Long-press the space bar to dictate, hold 🤖 to run a task.\n\nKeyo v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                    fontSize = 12.sp,
                    color = textFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
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
