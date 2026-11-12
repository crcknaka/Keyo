#!/usr/bin/env python3
"""
Build the proper-noun list app/src/main/assets/dict/caps_<lang>.txt: the dictionary words that are
written with a capital (or all in capitals, or in a fixed mixed case) — names, cities, countries,
brands, acronyms, and in English the weekdays, months and nationalities. One cased form per line;
lowercased it is a word of <lang>.txt. The keyboard shows and inserts these words in this case.

The bundled dictionaries are case-folded, so the evidence has to come from running text — and from
the MIDDLE of sentences only: every word is capitalized at the start of one, which says nothing.
A word counts as a proper noun when, of its occurrences that follow another word directly (after a
space, or a comma and a space), at least <share> are not lowercase. Sentences that are mostly
capitalized words are skipped: English news is full of Title Case Headlines.

Source: the *-sentences.txt files of the Leipzig Corpora Collection (same corpora as
gen_bigrams.py; format id<TAB>sentence).

Reproduce:
    python3 gen_caps.py ru $D/ru.txt $D/caps_ru.txt 20 0.97 --always names_extra_ru.txt \
        rus_news_2022_1M/*-sentences.txt rus_wikipedia_2021_1M/*-sentences.txt
    python3 gen_caps.py en $D/en.txt $D/caps_en.txt 20 0.97 --always names_extra_en.txt \
        eng_news_2023_1M/*-sentences.txt
    python3 gen_caps.py lv $D/lv.txt $D/caps_lv.txt 20 0.97 \
        lav-lv_web_2020_1M/*-sentences.txt lav_newscrawl_2016_1M/*-sentences.txt

0.97, not lower: at 0.95 English news lets in "Advisors", "United", "Eagles" and "Senate" —
words that are capitalized there because of what the news is about.
"""
import sys, re, collections

lang      = sys.argv[1]
dictfile  = sys.argv[2]
outfile   = sys.argv[3]
min_count = int(sys.argv[4])     # mid-sentence occurrences needed before a word is judged at all
share     = float(sys.argv[5])   # of those, the fraction that must not be lowercase
rest      = sys.argv[6:]
always_file = None               # names_extra_<lang>.txt: its capitalized lines are taken as given
if rest and rest[0] == '--always':
    always_file = rest[1]
    rest = rest[2:]
sent_files = rest

if lang == 'ru':
    token = re.compile(r'[А-Яа-яЁё]+')
elif lang == 'lv':
    token = re.compile(r'[A-Za-zĀČĒĢĪĶĻŅŠŪŽāčēģīķļņšūž]+')
else:
    token = re.compile(r"[A-Za-z]+(?:['’][A-Za-z]+)*")

def fold(w):
    w = w.replace('’', "'")
    return w.replace('ё', 'е') if lang == 'ru' else w

# dictionary: folded lowercase -> the dictionary's own spelling
dict_words = {}
with open(dictfile, encoding='utf-8') as f:
    for line in f:
        w = line.strip()
        if len(w) >= 2:
            dict_words.setdefault(fold(w), w)

MID_GAPS = (' ', ', ')           # what may separate a mid-sentence word from the word before it
forms = collections.defaultdict(collections.Counter)

for sf in sent_files:
    with open(sf, encoding='utf-8', errors='ignore') as f:
        for line in f:
            tab = line.find('\t')
            text = line[tab + 1:].rstrip('\n') if tab >= 0 else line.rstrip('\n')
            toks = list(token.finditer(text))
            if len(toks) < 3:
                continue
            capped = sum(1 for m in toks if m.group()[0].isupper())
            if len(toks) >= 4 and capped / len(toks) > 0.6:
                continue                      # a headline, a list of names, shouting
            for i in range(1, len(toks)):
                m = toks[i]
                if text[toks[i - 1].end():m.start()] not in MID_GAPS:
                    continue
                w = m.group()
                key = fold(w.lower())
                if key in dict_words:
                    forms[key][fold(w)] += 1

always = {}
if always_file:
    with open(always_file, encoding='utf-8') as f:
        for line in f:
            if line.startswith('#'):
                continue
            for w in line.split():
                if w != w.lower() and fold(w.lower()) in dict_words:
                    always[fold(w.lower())] = w

out = [(10 ** 9, w, 1.0) for w in always.values()]
for key, counter in forms.items():
    if key in always:
        continue
    total = sum(counter.values())
    if total < min_count:
        continue
    # Two letters are too few to tell a name from a noise word ("Эм", "Ай", "Фу" all pass the
    # share test in subtitles and news). Only a well-attested ACRONYM gets through: TV, AI, UK.
    if len(key) == 2 and (lang == 'ru' or total < 200 or counter.most_common(1)[0][0] != key.upper()):
        continue
    lower = counter.get(key, 0)
    if (total - lower) / total < share:
        continue
    best, _ = max(((f, c) for f, c in counter.items() if f != key), key=lambda fc: fc[1])
    spelling = dict_words[key]              # keep the dictionary's ё / apostrophes
    if best.isupper() and len(best) > 1:
        cased = spelling.upper()
    elif best[0].isupper() and best[1:] == key[1:]:
        cased = spelling[0].upper() + spelling[1:]
    elif len(best) == len(spelling):
        # a fixed mixed case ("iPhone", "YouTube"): copy the pattern onto the dictionary spelling
        cased = ''.join(s.upper() if b.isupper() else s for s, b in zip(spelling, best))
    else:
        continue
    out.append((total, cased, (total - lower) / total))

out.sort(key=lambda tc: -tc[0])
with open(outfile, 'w', encoding='utf-8') as f:
    f.write('\n'.join(c for _, c, _ in out) + '\n')
# For choosing the thresholds: every accepted word with its evidence, next to the list.
with open(outfile + '.stats.tsv', 'w', encoding='utf-8') as f:
    for total, cased, sh in out:
        f.write(f"{cased}\t{total}\t{sh:.3f}\n")
print(f"{lang}: judged={sum(1 for c in forms.values() if sum(c.values()) >= min_count)} proper={len(out)} -> {outfile}")
