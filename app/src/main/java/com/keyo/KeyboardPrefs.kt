package com.keyo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object KeyboardPrefs {
    private const val PREFS_NAME = "keyo_prefs"
    private const val KEY_MODEL = "ai_model"
    private const val KEY_AI_MODEL = "ai_assistant_model"
    private const val KEY_AUTOCORRECT = "autocorrect_enabled"
    private const val KEY_NUMBER_ROW = "number_row_enabled"
    private const val KEY_KEY_HEIGHT = "key_height"   // row height in dp
    private const val KEY_HGAP = "key_hgap"           // horizontal gap between keys, dp
    private const val KEY_VGAP = "key_vgap"           // vertical gap between rows, dp
    private const val KEY_HAPTIC_STRENGTH = "haptic_strength"
    private const val KEY_SOUND = "sound_enabled"
    private const val KEY_THEME = "theme"
    private const val KEY_LANGS = "enabled_languages"
    private const val KEY_CUR_LANG = "current_language"
    private const val KEY_API_KEY = "groq_api_key"
    private const val KEY_CLIPS = "clip_history"
    private const val MAX_CLIPS = 25
    private const val KEY_PINNED = "pinned_clips"
    private const val KEY_PHRASES = "quick_phrases"
    private const val KEY_RECENT_EMOJI = "recent_emoji"
    private const val KEY_DOUBLE_SPACE = "double_space_period"
    private const val KEY_AUTO_CAP = "auto_capitalize"
    private const val KEY_SUGGESTIONS = "suggestions_enabled"
    private const val KEY_AUTOCORRECT_TYPING = "autocorrect_typing"
    private const val KEY_SWIPE_TYPING = "swipe_typing"
    private const val KEY_LIVE_DICTATION = "live_dictation"
    private const val KEY_INSTANT_DICTATION = "instant_dictation"

    // Defaults / ranges for the visual size editor.
    const val DEFAULT_KEY_HEIGHT = 48
    const val DEFAULT_HGAP = 1
    const val DEFAULT_VGAP = 2
    const val DEFAULT_BOTTOM_OFFSET = 32
    val KEY_HEIGHT_RANGE = 28..64
    val GAP_RANGE = 0..10
    val BOTTOM_OFFSET_RANGE = 0..64
    private const val KEY_BOTTOM_OFFSET = "bottom_offset"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun registerChangeListener(context: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).registerOnSharedPreferenceChangeListener(l)

    fun unregisterChangeListener(context: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).unregisterOnSharedPreferenceChangeListener(l)

    fun getModel(context: Context): String {
        return prefs(context).getString(KEY_MODEL, "openai/gpt-oss-20b") ?: "openai/gpt-oss-20b"
    }

    fun setModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_MODEL, model).apply()
    }

    fun getAiModel(context: Context): String {
        return prefs(context).getString(KEY_AI_MODEL, "openai/gpt-oss-120b") ?: "openai/gpt-oss-120b"
    }

    fun setAiModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_AI_MODEL, model).apply()
    }

    fun isAutocorrectEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTOCORRECT, true)
    }

    fun setAutocorrectEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOCORRECT, enabled).apply()
    }

    fun isNumberRowEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_NUMBER_ROW, true)
    }

    fun setNumberRowEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NUMBER_ROW, enabled).apply()
    }

    // --- Visual keyboard sizing (user-tunable via sliders in Settings) ---

    /** Row height in dp (the vertical pitch of each key row). */
    fun getKeyHeight(context: Context): Int =
        prefs(context).getInt(KEY_KEY_HEIGHT, DEFAULT_KEY_HEIGHT).coerceIn(KEY_HEIGHT_RANGE.first, KEY_HEIGHT_RANGE.last)

    fun setKeyHeight(context: Context, dp: Int) =
        prefs(context).edit().putInt(KEY_KEY_HEIGHT, dp.coerceIn(KEY_HEIGHT_RANGE.first, KEY_HEIGHT_RANGE.last)).apply()

    /** Horizontal gap between keys, dp. */
    fun getHGap(context: Context): Int =
        prefs(context).getInt(KEY_HGAP, DEFAULT_HGAP).coerceIn(GAP_RANGE.first, GAP_RANGE.last)

    fun setHGap(context: Context, dp: Int) =
        prefs(context).edit().putInt(KEY_HGAP, dp.coerceIn(GAP_RANGE.first, GAP_RANGE.last)).apply()

    /** Vertical gap between rows, dp (carved from the row height, so it shortens the keys). */
    fun getVGap(context: Context): Int =
        prefs(context).getInt(KEY_VGAP, DEFAULT_VGAP).coerceIn(GAP_RANGE.first, GAP_RANGE.last)

    fun setVGap(context: Context, dp: Int) =
        prefs(context).edit().putInt(KEY_VGAP, dp.coerceIn(GAP_RANGE.first, GAP_RANGE.last)).apply()

    /** Extra lift (dp) between the keyboard and the system navigation area at the bottom of the
     *  screen — ON TOP of the automatic navigation-bar inset, so the keys always clear the
     *  hide-keyboard / IME-switcher buttons and sit comfortably above the gesture pill. */
    fun getBottomOffset(context: Context): Int =
        prefs(context).getInt(KEY_BOTTOM_OFFSET, DEFAULT_BOTTOM_OFFSET)
            .coerceIn(BOTTOM_OFFSET_RANGE.first, BOTTOM_OFFSET_RANGE.last)

    fun setBottomOffset(context: Context, dp: Int) =
        prefs(context).edit().putInt(KEY_BOTTOM_OFFSET,
            dp.coerceIn(BOTTOM_OFFSET_RANGE.first, BOTTOM_OFFSET_RANGE.last)).apply()

    fun resetSize(context: Context) {
        prefs(context).edit()
            .putInt(KEY_KEY_HEIGHT, DEFAULT_KEY_HEIGHT)
            .putInt(KEY_HGAP, DEFAULT_HGAP)
            .putInt(KEY_VGAP, DEFAULT_VGAP)
            .putInt(KEY_BOTTOM_OFFSET, DEFAULT_BOTTOM_OFFSET)
            .apply()
    }

    /** Key glyph size derived from the visible key height (row height minus vertical gaps). */
    fun fontSizeSp(keyHeight: Int, vGap: Int): Int {
        val visible = (keyHeight - 2 * vGap).coerceAtLeast(18)
        return (visible * 0.5f).toInt().coerceIn(13, 24)
    }

    // --- Haptics ---
    // Values: "off" | "light" | "medium" | "strong"
    val HAPTIC_LEVELS = listOf(
        "off" to "Off",
        "light" to "Light",
        "medium" to "Medium",
        "strong" to "Strong"
    )

    fun getHapticStrength(context: Context): String {
        return prefs(context).getString(KEY_HAPTIC_STRENGTH, "light") ?: "light"
    }

    fun setHapticStrength(context: Context, level: String) {
        prefs(context).edit().putString(KEY_HAPTIC_STRENGTH, level).apply()
    }

    /** Vibration duration (ms) for the configured strength; 0 = disabled. */
    fun hapticDurationMs(level: String): Long = when (level) {
        "light" -> 10L
        "medium" -> 18L
        "strong" -> 28L
        else -> 0L // off
    }

    /** Vibration amplitude (1..255) for the configured strength. */
    fun hapticAmplitude(level: String): Int = when (level) {
        "light" -> 70
        "medium" -> 140
        "strong" -> 230
        else -> 0
    }

    fun isSoundEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SOUND, false)
    }

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUND, enabled).apply()
    }

    /** User-supplied Groq API key. Blank means "use the build-time default". */
    fun getApiKey(context: Context): String {
        return prefs(context).getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    // --- Clipboard history (most-recent first) ---
    fun getClipHistory(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_CLIPS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    fun addClip(context: Context, text: String) {
        if (text.isBlank()) return
        val cur = getClipHistory(context).toMutableList()
        cur.remove(text)            // de-duplicate
        cur.add(0, text)
        while (cur.size > MAX_CLIPS) cur.removeAt(cur.size - 1)
        saveClips(context, cur)
    }

    fun removeClip(context: Context, text: String) {
        val cur = getClipHistory(context).toMutableList()
        cur.remove(text)
        saveClips(context, cur)
    }

    fun clearClips(context: Context) = prefs(context).edit().remove(KEY_CLIPS).apply()

    // --- Generic JSON string-list storage (phrases, recents, pins) ---
    private fun getList(context: Context, key: String): List<String> {
        val raw = prefs(context).getString(key, "[]") ?: "[]"
        return try { val a = JSONArray(raw); List(a.length()) { a.getString(it) } } catch (_: Exception) { emptyList() }
    }
    private fun putList(context: Context, key: String, list: List<String>) {
        val a = JSONArray(); list.forEach { a.put(it) }
        prefs(context).edit().putString(key, a.toString()).apply()
    }

    // Pinned clips (kept above clipboard history, never auto-evicted). These double as saved
    // templates — they replaced the old "quick phrases" feature.
    fun getPinned(context: Context) = getList(context, KEY_PINNED)
    fun isPinned(context: Context, text: String) = getPinned(context).contains(text)
    fun togglePin(context: Context, text: String) {
        val cur = getPinned(context).toMutableList()
        if (!cur.remove(text)) cur.add(0, text)
        putList(context, KEY_PINNED, cur)
    }
    fun addPinned(context: Context, text: String) {
        if (text.isBlank()) return
        val cur = getPinned(context).toMutableList()
        cur.remove(text); cur.add(0, text)
        while (cur.size > 50) cur.removeAt(cur.size - 1)
        putList(context, KEY_PINNED, cur)
    }
    fun removePinned(context: Context, text: String) {
        putList(context, KEY_PINNED, getPinned(context).filter { it != text })
    }

    /** One-time merge of the retired "quick phrases" into pinned clips, then clears the old store. */
    fun migratePhrasesToPinned(context: Context) {
        val phrases = getList(context, KEY_PHRASES)
        if (phrases.isEmpty()) return
        val pinned = getPinned(context).toMutableList()
        phrases.forEach { if (it.isNotBlank() && it !in pinned) pinned.add(it) }
        putList(context, KEY_PINNED, pinned)
        prefs(context).edit().remove(KEY_PHRASES).apply()
    }

    // Recently-used emoji, ordered by how OFTEN each is used (recency breaks ties) — favourites
    // gravitate to the front of the Recents tab instead of being pushed out by one-off picks.
    private const val KEY_EMOJI_COUNTS = "emoji_counts"

    private fun emojiCounts(context: Context): org.json.JSONObject =
        try { org.json.JSONObject(prefs(context).getString(KEY_EMOJI_COUNTS, "{}") ?: "{}") }
        catch (_: Exception) { org.json.JSONObject() }

    fun getRecentEmoji(context: Context): List<String> {
        val recency = getList(context, KEY_RECENT_EMOJI)   // most-recent first
        if (recency.size < 2) return recency
        val counts = emojiCounts(context)
        return recency.sortedByDescending { counts.optInt(it, 0) }   // stable: ties keep recency
    }

    fun addRecentEmoji(context: Context, e: String) {
        val cur = getList(context, KEY_RECENT_EMOJI).toMutableList()
        cur.remove(e); cur.add(0, e)
        val counts = emojiCounts(context)
        counts.put(e, counts.optInt(e, 0) + 1)
        while (cur.size > 40) counts.remove(cur.removeAt(cur.size - 1))
        prefs(context).edit()
            .putString(KEY_EMOJI_COUNTS, counts.toString())
            .apply()
        putList(context, KEY_RECENT_EMOJI, cur)
    }

    // Typing helpers
    fun isDoubleSpacePeriod(context: Context) = prefs(context).getBoolean(KEY_DOUBLE_SPACE, true)
    fun setDoubleSpacePeriod(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_DOUBLE_SPACE, v).apply()
    fun isAutoCap(context: Context) = prefs(context).getBoolean(KEY_AUTO_CAP, true)
    fun setAutoCap(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO_CAP, v).apply()

    /** Show the live word-suggestion strip while typing. */
    fun isSuggestionsEnabled(context: Context) = prefs(context).getBoolean(KEY_SUGGESTIONS, true)
    fun setSuggestionsEnabled(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_SUGGESTIONS, v).apply()

    /** Automatically fix the previous word on space (Gboard-style, offline dictionary). On by default. */
    fun isAutocorrectTyping(context: Context) = prefs(context).getBoolean(KEY_AUTOCORRECT_TYPING, true)
    fun setAutocorrectTyping(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_AUTOCORRECT_TYPING, v).apply()

    /** Glide / swipe typing — slide across letters to type a word. Off by default. */
    fun isSwipeTyping(context: Context) = prefs(context).getBoolean(KEY_SWIPE_TYPING, false)
    fun setSwipeTyping(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_SWIPE_TYPING, v).apply()

    /** Live dictation — show the transcript growing in the field while you speak (more API calls).
     *  Off by default; the standard path transcribes once on release. */
    fun isLiveDictation(context: Context) = prefs(context).getBoolean(KEY_LIVE_DICTATION, false)
    fun setLiveDictation(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_LIVE_DICTATION, v).apply()

    /** When dictation cleanup is on: insert the raw transcription immediately and swap in the cleaned
     *  version a moment later (one round-trip to first text instead of two). On by default. */
    fun isInstantDictation(context: Context) = prefs(context).getBoolean(KEY_INSTANT_DICTATION, true)
    fun setInstantDictation(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_INSTANT_DICTATION, v).apply()

    private fun saveClips(context: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_CLIPS, arr.toString()).apply()
    }

    fun getTheme(context: Context): String {
        return prefs(context).getString(KEY_THEME, "catppuccin") ?: "catppuccin"
    }

    fun setTheme(context: Context, theme: String) {
        prefs(context).edit().putString(KEY_THEME, theme).apply()
    }

    // --- Languages ---
    // Supported codes: "en", "ru", "lv". At least one must always be enabled.
    val SUPPORTED_LANGUAGES = listOf(
        "en" to "English",
        "ru" to "Russian",
        "lv" to "Latvian"
    )

    fun getEnabledLanguages(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_LANGS, "en,ru") ?: "en,ru"
        val list = raw.split(",").map { it.trim() }.filter { code -> SUPPORTED_LANGUAGES.any { it.first == code } }
        return list.ifEmpty { listOf("en") }
    }

    fun isLanguageEnabled(context: Context, code: String): Boolean = getEnabledLanguages(context).contains(code)

    fun setLanguageEnabled(context: Context, code: String, enabled: Boolean) {
        val current = getEnabledLanguages(context).toMutableList()
        if (enabled) {
            if (!current.contains(code)) current.add(code)
        } else {
            current.remove(code)
            if (current.isEmpty()) current.add("en") // never allow empty
        }
        // Persist in the canonical supported order
        val ordered = SUPPORTED_LANGUAGES.map { it.first }.filter { current.contains(it) }
        prefs(context).edit().putString(KEY_LANGS, ordered.joinToString(",")).apply()
    }

    fun getCurrentLanguage(context: Context): String {
        val enabled = getEnabledLanguages(context)
        val cur = prefs(context).getString(KEY_CUR_LANG, enabled.first()) ?: enabled.first()
        return if (enabled.contains(cur)) cur else enabled.first()
    }

    fun setCurrentLanguage(context: Context, code: String) {
        prefs(context).edit().putString(KEY_CUR_LANG, code).apply()
    }

    data class KeyboardTheme(
        val id: String,
        val name: String,
        val bg: Long,
        val key: Long,
        val accent: Long,
        val text: Long,
        val record: Long = 0xFFFF5555,
        val altPopupBg: Long = 0xFF45475A,
        val altPopupKey: Long = 0xFF585B70
    )

    val THEMES = listOf(
        KeyboardTheme("catppuccin", "Catppuccin Mocha",
            bg = 0xFF1E1E2E, key = 0xFF313244, accent = 0xFFBB86FC, text = 0xFFCDD6F4),
        KeyboardTheme("dracula", "Dracula",
            bg = 0xFF282A36, key = 0xFF44475A, accent = 0xFFBD93F9, text = 0xFFF8F8F2,
            altPopupBg = 0xFF44475A, altPopupKey = 0xFF6272A4),
        KeyboardTheme("nord", "Nord",
            bg = 0xFF2E3440, key = 0xFF3B4252, accent = 0xFF88C0D0, text = 0xFFECEFF4,
            altPopupBg = 0xFF3B4252, altPopupKey = 0xFF4C566A),
        KeyboardTheme("gruvbox", "Gruvbox Dark",
            bg = 0xFF282828, key = 0xFF3C3836, accent = 0xFFFE8019, text = 0xFFEBDBB2,
            altPopupBg = 0xFF3C3836, altPopupKey = 0xFF504945),
        KeyboardTheme("solarized", "Solarized Dark",
            bg = 0xFF002B36, key = 0xFF073642, accent = 0xFF268BD2, text = 0xFF839496,
            altPopupBg = 0xFF073642, altPopupKey = 0xFF586E75),
        KeyboardTheme("rosepine", "Rosé Pine",
            bg = 0xFF191724, key = 0xFF26233A, accent = 0xFFEB6F92, text = 0xFFE0DEF4,
            altPopupBg = 0xFF26233A, altPopupKey = 0xFF403D52),
        KeyboardTheme("tokyonight", "Tokyo Night",
            bg = 0xFF1A1B26, key = 0xFF24283B, accent = 0xFF7AA2F7, text = 0xFFC0CAF5,
            altPopupBg = 0xFF24283B, altPopupKey = 0xFF414868),
        KeyboardTheme("amoled", "AMOLED Black",
            bg = 0xFF000000, key = 0xFF1A1A1A, accent = 0xFF00E5FF, text = 0xFFFFFFFF,
            altPopupBg = 0xFF1A1A1A, altPopupKey = 0xFF333333),
        KeyboardTheme("light", "Light",
            bg = 0xFFE8E8E8, key = 0xFFFFFFFF, accent = 0xFF6750A4, text = 0xFF1C1B1F,
            record = 0xFFB3261E, altPopupBg = 0xFFE0E0E0, altPopupKey = 0xFFD0D0D0)
    )

    fun getThemeData(context: Context): KeyboardTheme {
        val id = getTheme(context)
        return THEMES.find { it.id == id } ?: THEMES[0]
    }

    val AVAILABLE_MODELS = listOf(
        "llama-3.3-70b-versatile" to "Llama 3.3 70B (quality)",
        "openai/gpt-oss-120b" to "GPT-OSS 120B (top, 500 t/s)",
        "openai/gpt-oss-20b" to "GPT-OSS 20B (fast, 1000 t/s)",
        "meta-llama/llama-4-maverick-17b-128e-instruct" to "Llama 4 Maverick 17B",
        "meta-llama/llama-4-scout-17b-16e-instruct" to "Llama 4 Scout 17B (fast)",
        "qwen/qwen3-32b" to "Qwen 3 32B",
        "moonshotai/kimi-k2-instruct-0905" to "Kimi K2 (262K context)",
        "llama-3.1-8b-instant" to "Llama 3.1 8B (fastest)"
    )

    val AI_TOOLS = listOf(
        "📞 Call" to "\"Call Mom\"",
        "💬 SMS" to "\"Text Arthur: running 10 min late\"",
        "⏰ Alarm" to "\"Set an alarm for 7:30\"",
        "⏱ Timer" to "\"Timer for 5 minutes\"",
        "📱 Apps" to "\"Open Telegram\"",
        "🔦 Flashlight" to "\"Turn on the flashlight\"",
        "🔊 Volume" to "\"Set volume to 50%\"",
        "🔍 Search" to "\"Google the weather in Riga\"",
        "📋 Clipboard" to "\"What's in the clipboard?\"",
        "🔋 Battery" to "\"How much battery is left?\"",
        "🌍 Translate" to "\"Translate to English: ...\"",
        "✍️ Text" to "\"Write an email to a colleague...\""
    )
}
