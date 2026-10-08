<p align="center">
  <img src="logo-keyo.png" width="120" alt="Keyo logo" />
</p>

<h1 align="center">Keyo</h1>

<p align="center">An Android keyboard with offline suggestions and autocorrect, voice dictation and an AI assistant.</p>

---

## Overview

Keyo is a custom Android keyboard (`InputMethodService`) built entirely with Jetpack Compose.
Typing works fully offline; the voice and AI features use the Groq API with your own key.

- ⌨️ **Suggestions and autocorrect, offline** — bundled frequency dictionaries and bigram models
  for English, Russian and Latvian drive completions, next-word predictions and typo correction.
  Corrections use where the finger actually landed, not only the letters it spelled. Backspace
  right after a correction reverts it. Keyo learns your own words and phrases on the device.
- 🎤 **Voice dictation** — hold the space bar, speak, release. Speech is transcribed by Groq
  Whisper and optionally tidied by an LLM. Without a key or a network it falls back to the
  device's built-in speech recognition.
- ✨ **Rewrite** — tap ✨ for a menu (fix grammar, change tone, translate, continue), or hold it and
  say what to do with the text.
- 🤖 **AI assistant** — hold the comma key and say a task. The model can set an alarm or a timer,
  open an app, toggle the flashlight, search the web and read or write the clipboard. Actions with
  consequences ask for an on-keyboard **Confirm / Cancel** first.

## Features

- **3 languages** — English, Russian, Latvian, each its own keyboard with its own dictionary.
  Enable the ones you want in Settings; switch with the 🌐 globe key. Latvian diacritics
  (ā č ē ģ ī ķ ļ ņ š ū ž) are on long-press and are restored by autocorrect.
- **Glide typing** (optional) — slide across the letters to type a word.
- **Cursor control** — swipe the space bar to move the caret; hold Shift first to select.
- **Clipboard history** with pinned clips, and an **emoji panel** ordered by how often you use each.
- **Compact mode** — a one-row keyboard for dictating, toggled by holding the bottom-left key.
- **9 color themes** (Catppuccin, Dracula, Nord, Gruvbox, Solarized, Rosé Pine, Tokyo Night,
  AMOLED, Light).
- **Adjustable size** — key height, spacing and bottom offset sliders; optional number row.
- **Configurable haptics** and optional key-press sound.
- **Personal dictionary** — view, search, export and import the words Keyo has learned.
- **In-app updates** — Keyo checks this repository's GitHub releases and offers to install them.
- **Quick settings** — long-press the period and pick ⚙ to open Keyo settings from anywhere.
- Pick the transcription and assistant **models** independently.

## Tech stack

- Kotlin 2.2 · Jetpack Compose (Material 3)
- `InputMethodService` keyboard rendered through a `ComposeView`
- Groq API (Whisper transcription + chat/tool-calling) via OkHttp
- `minSdk` 26 · `targetSdk` 35 · `compileSdk` 36 · JDK 17
- Built with AGP 8.11 · Gradle 8.14 · JUnit unit tests · GitHub Actions CI (tests, lint, tagged releases)

## Getting started

### Prerequisites

- Android Studio (or the Android SDK + JDK 17)
- Optional: a free **Groq API key** for dictation and AI — create one at <https://console.groq.com/keys>

### 1. Clone

```bash
git clone https://github.com/crcknaka/Keyo.git
cd Keyo
```

### 2. Provide your Groq API key (optional)

The key is **not** committed, and release builds ship without one. Supply it in either of these ways:

- **Build-time (recommended for development):** add it to `local.properties` in the project
  root (this file is git-ignored):

  ```properties
  GROQ_API_KEY=gsk_your_key_here
  ```

  Alternatively set a `GROQ_API_KEY` environment variable.

- **At runtime:** install the app and paste your key in **Settings → Voice & AI → Groq API key**.
  A key entered here overrides the build-time default.

> `local.properties` also stores your Android SDK path (`sdk.dir`). Android Studio generates it
> automatically; if you build from the command line, add `sdk.dir=/path/to/Android/Sdk`.

### 3. Build & install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/Keyo-debug.apk
```

(Windows: a convenience script `build-release.ps1` is included.) Signed release APKs are built
by CI when a `v*` tag is pushed and are attached to the GitHub release.

### 4. Enable the keyboard

Open the **Keyo** app and follow the Setup steps:

1. **Enable keyboard** — System Settings → Languages & input
2. **Switch to Keyo** — input method picker
3. **Grant microphone** — required for voice input

A Groq API key can be added on the same screen; typing works without it.

## Usage

| Action | Gesture |
|---|---|
| Type | Tap keys (characters appear on key-down for low latency) |
| Accented / alternate characters | Long-press a letter, slide to the variant |
| Undo an autocorrection | Backspace right after it, or tap the ↩ chip |
| Dictate | Hold the space bar, speak, release (slide left to cancel) |
| Rewrite text | Tap ✨ for the menu, or hold ✨ and say the instruction |
| Run an AI task | Hold the comma key, speak a task, release |
| Move the cursor | Swipe the space bar (hold Shift first to select) |
| Delete | Tap backspace, hold to repeat, swipe left across it to clear the field |
| Switch language | Tap the 🌐 globe |
| Numbers / symbols / emoji | Tap `123` |
| New line in a chat | Shift + Enter |
| Open settings | Long-press the period, pick ⚙ |

## Project structure

```
app/src/main/java/com/keyo/
├── KeyoApp.kt              Application entry — loads the API key, registers tools
├── KeyoService.kt          The keyboard (InputMethodService + Compose UI)
├── KeyTables.kt            Letter rows, long-press alternates, emoji, contractions
├── GlideDecoder.kt         Turns a swipe path into words (pure, unit-tested)
├── KeyGlyphs.kt            Vector glyphs drawn on keys
├── EnterBehavior.kt        What Enter does in a given field
├── SuggestionEngine.kt     Completion, correction and next-word ranking (pure, unit-tested)
├── UserDictionary.kt       Words and phrases learned on the device
├── SettingsActivity.kt     Settings (Compose)
├── KeyboardPrefs.kt        Preferences, themes, sizes, languages
├── GroqApi.kt              Transcription, cleanup, rewrite, assistant + tool-calling
├── AudioRecorder.kt        16 kHz PCM → WAV recorder
├── OfflineDictation.kt     Fallback dictation through the system recognizer
├── UpdateChecker.kt        In-app updates from GitHub releases
└── tools/                  Assistant tools (alarm, timer, app, flashlight, clipboard, web search)

app/src/main/assets/dict/   Word lists and bigram models (en, ru, lv)
tools/                      Scripts that generate and clean the dictionaries
```

## Privacy

Typing never touches the network: suggestions, autocorrect, glide and the personal dictionary run
on the device. Data leaves it only when you use a voice or AI feature with a Groq key:

- **Dictation** — the recorded audio is sent to Groq for transcription; with cleanup on, the
  transcript is sent once more to be tidied.
- **Rewrite / AI assistant** — the text you are rewriting, or your spoken task, is sent to Groq.
- **Update check** — a request to the GitHub API for this repository's latest release.

What stays on the device:

- In **password fields** voice, AI and suggestions are off entirely.
- In **"no personalized learning" (incognito) fields** nothing is learned and the clipboard is
  not recorded; dictation still works when you start it.
- **Clipboard history** and the **personal dictionary** are stored locally only, and are excluded
  from Android backups.
- Your **Groq API key** is stored locally (in `SharedPreferences` / `local.properties`) and is
  only sent to Groq as the request authorization.

## License

Released under the [MIT License](LICENSE).
