package com.keyo

// Data tables for the keyboard: letter rows, long-press alternates, emoji, contractions.

// The letter rows and their side allowances, as constants: built with listOf() inside the
// layout they were fresh (unstable) instances on every pass, which defeated the memoization
// of the BoxWithConstraints content lambda and re-ran all three letter rows on every root
// recomposition.
internal val RU_ROWS = listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
internal val EN_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
internal val RU_SIDES = listOf(0f, 0f, 1.3f)
internal val EN_SIDES = listOf(0f, 0.5f, 1.5f)

// Alt characters map
internal val altChars = mapOf(
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
    "?" to listOf("¿","‽"),
    "!" to listOf("¡"),
    "'" to listOf("‘","’","‛","\""),
    "-" to listOf("–","—","_"),
    "0" to listOf("°","∅"),
    "1" to listOf("¹","½","⅓"),
    "2" to listOf("²","⅔"),
    "3" to listOf("³","¾"),
    "$" to listOf("€","£","¥","₽","₹")
)

// Long-press digits for the top letter row when the dedicated number row is hidden, with a
// small corner hint on the key (Gboard behaviour). Looked up by lowercase key label.
internal val topRowDigits = mapOf(
    "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
    "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
    "й" to "1", "ц" to "2", "у" to "3", "к" to "4", "е" to "5",
    "н" to "6", "г" to "7", "ш" to "8", "щ" to "9", "з" to "0"
)

// Emoji panel categories. Tab 0 = recently used; 1..n map to EMOJI_GROUPS.
internal val EMOJI_TABS = listOf("🕘", "😀", "🐶", "🍕", "❤️", "✋")
internal val EMOJI_GROUPS = listOf(
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

// English contractions typed without the apostrophe -> canonical form (correct caps for the "I"
// ones). Forms that are themselves valid words (its, were, well, lets, id, ill, shed, …) are
// deliberately left out so a correct word is never "fixed".
internal val enContractions = mapOf(
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
