#!/usr/bin/env python3
"""
Clean a bundled frequency dictionary IN PLACE (app/src/main/assets/dict/<lang>.txt).

The lists come straight out of news/web corpora (gen_dict.py), which means they faithfully
contain whatever people actually wrote — including the most common misspellings. That matters
more here than in a spell-checker, because membership SHORT-CIRCUITS correction: a word that is in
the list is never corrected, so shipping "teh" as a word means "teh" can never become "the".

Rules, all deliberately narrow — every one was checked against the real lists first:

  1. A curated blacklist of misspellings per language. Only spellings that are unambiguously
     wrong; anything that is also a real word ("wether", "fro", "amd") stays.
  2. Apostrophe-less contractions that have their proper form in the list ("dont" when "don't"
     is present). Removing them lets autocorrect produce "don't", as Gboard does. Forms that are
     also ordinary words ("well", "ill", "were", "its", "id") are never touched.
  3. Russian ё/е pairs collapsed to ONE entry — the more frequent spelling, at the better rank.
     Membership is already checked folded (ё == е), so nothing becomes unknown — but completion
     used to show "еще" and "ещё" as two of the three chips for one word. The more frequent
     spelling rather than "always ё" because some pairs are DIFFERENT words ("все"/"всё",
     "небо"/"нёбо") and the frequent one is the one the typo was almost certainly aiming at.
  4. A short curated list of Russian corpus artefacts that are not words ("щрн", "врн").
     NOT a "no vowel" rule: that would also remove днк, смс, кгб, html, pdf, vpn, which people type.

Usage:  python3 tools/clean_dict.py app/src/main/assets/dict/ru.txt
Idempotent; prints what it removed.
"""
import sys, os

EN_MISSPELLINGS = """
teh thier seperate seperately definately definatly definitly alot occured occurence occuring
recieve recieved recieving untill wich becuase becasue beleive belive goverment enviroment
tommorow tomorow wierd freind freinds accomodate acheive adress agressive apparantly arguement
basicly begining calender cemetary collegue comming commitee completly concious curiousity
decieve dissapoint dissapear embarass existance experiance familar finaly foriegn fourty grammer
happend harrass humourous immediatly independant intresting knowlege liason libary lisence
maintenence millenium mischievious neccessary necesary noticable occassion persistant posession
prefered priviledge probaly publically realy reccomend recomend refered relevent religous rember
remeber resistence rythm sieze similiar sincerly speach succesful sucess suprise tounge truely
unfortunatly usefull vacume whereever writting sory realise's
""".split()

# Apostrophe-less contractions: removed ONLY if the apostrophe form is present in the list.
EN_CONTRACTIONS = {
    "dont": "don't", "isnt": "isn't", "wasnt": "wasn't", "werent": "weren't", "doesnt": "doesn't",
    "didnt": "didn't", "couldnt": "couldn't", "wouldnt": "wouldn't", "shouldnt": "shouldn't",
    "hasnt": "hasn't", "havent": "haven't", "hadnt": "hadn't", "arent": "aren't",
    "youre": "you're", "theyre": "they're", "thats": "that's", "whats": "what's",
    "ive": "i've", "youve": "you've", "weve": "we've", "theyve": "they've",
    "youll": "you'll", "theyll": "they'll", "youd": "you'd", "theyd": "they'd",
    "shes": "she's", "whos": "who's", "wheres": "where's", "heres": "here's", "theres": "there's",
    "hows": "how's", "im": "i'm",
}

RU_MISSPELLINGS = """
здраствуйте здраствуй здраствую пожалуста вобще вообщем чтоли извените извени спосибо сдесь
зделать зделал зделала зделали чуствую чуствовать чуствует симпотичный симпотичная будующий
будующее будующего расчитывать расчитать расчитан инцедент прецендент компроментировать
скурпулёзный скурпулезный рассчёт рассчет расчитал ньюанс ньюансы конкурентноспособный
""".split()

# Everyday chat words the NEWS corpora never contain, appended at the tail (known, so never
# "corrected" into something else — "idk" used to become "id"; low rank, so never suggested
# over a real word).
EN_ADDITIONS = "idk brb thx np lmao afk imho tbf ikr nvm smh irl tysm".split()
RU_ADDITIONS = "спс пж плз кст збс кек имхо хз мб инфа оч ясн прив спасибки пасиб".split()

# Corpus artefacts with no reading as a word. Kept short on purpose.
RU_ARTEFACTS = """
щрн врн рш лш бш нм тгм вь вз мрт кт бл мр чт дл мн
""".split()



def clean(path):
    lang = os.path.basename(path).split(".")[0]
    with open(path, encoding="utf-8") as f:
        words = [w.rstrip("\n") for w in f if w.strip()]
    present = set(words)
    removed = []

    if lang == "en":
        drop = set(w for w in EN_MISSPELLINGS if w in present)
        for bare, proper in EN_CONTRACTIONS.items():
            if bare in present and proper in present:
                drop.add(bare)
        out = [w for w in words if w not in drop]
        out += [w for w in EN_ADDITIONS if w not in present]
        removed = sorted(drop)

    elif lang == "ru":
        drop = set(w for w in RU_MISSPELLINGS + RU_ARTEFACTS if w in present)
        # ё/е merge: the first occurrence (best rank) of each folded form survives, as spelled.
        seen = set()
        out = []
        merged = 0
        for w in words:
            if w in drop:
                continue
            key = w.replace("ё", "е")
            if key in seen:
                merged += 1
                continue
            seen.add(key)
            out.append(w)
        out += [w for w in RU_ADDITIONS if w not in present]
        removed = sorted(drop)
        print(f"{path}: merged {merged} ё/е duplicates")

    else:
        out = words

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
    print(f"{path}: {len(words)} -> {len(out)} words; removed {len(removed)}: {' '.join(removed)}")


if __name__ == "__main__":
    for p in sys.argv[1:]:
        clean(p)
