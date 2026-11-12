#!/usr/bin/env python3
"""
Add the proper nouns a bundled dictionary is missing: names, cities, countries, brands.

The Russian and English lists were built from sources that simply do not mention this part of the
world — ru.txt had no "рига", "латвия", "юрмала" or "таллин" at all — so those words were unknown:
never offered, and fair game for autocorrect. This reads running text, finds the words that are
(a) not in the dictionary, (b) frequent, and (c) capitalized in the MIDDLE of a sentence nearly
every time (same evidence and same rules as gen_caps.py), and inserts the top <maxNew> of them.

Where: by how common the word is in the corpus relative to the dictionary's own words, but never
above rank <floor>. The top of the list is where frequency buys the most (autocorrect targets,
glide priors), and a corpus of news is no judge of what belongs there.

Run gen_caps.py afterwards — it is what gives the new words their capital.

Reproduce (corpora as in gen_caps.py):
    python3 add_names.py ru $D/ru.txt 3000 40 0.97 8000 --extra names_extra_ru.txt \
        rus_news_2022_1M/*-sentences.txt rus_wikipedia_2021_1M/*-sentences.txt
    python3 add_names.py en $D/en.txt 0 40 0.97 8000 --extra names_extra_en.txt \
        eng_news_2023_1M/*-sentences.txt

From the corpus for Russian only (English gets just its --extra list, maxNew 0): en.txt and
lv.txt were built from this same kind of text and already have their names — run on English the
corpus pass finds thirteen words, all of them halves of "Pokémon" and "Beyoncé".
"""
import sys, re, collections, bisect

lang      = sys.argv[1]
dictfile  = sys.argv[2]
max_new   = int(sys.argv[3])
min_count = int(sys.argv[4])     # mid-sentence occurrences
share     = float(sys.argv[5])   # fraction of those that are not lowercase
floor     = int(sys.argv[6])     # no new word is inserted above this rank
rest      = sys.argv[7:]
extra_file = None                # hand-kept names the corpora are too thin on (names_extra_<lang>.txt)
if rest and rest[0] == '--extra':
    extra_file = rest[1]
    rest = rest[2:]
sent_files = rest

if lang == 'ru':
    token = re.compile(r'[А-Яа-яЁё]+')
    word_ok = re.compile(r'[а-яё]+$')
elif lang == 'lv':
    token = re.compile(r'[A-Za-zĀČĒĢĪĶĻŅŠŪŽāčēģīķļņšūž]+')
    word_ok = re.compile(r'[a-zāčēģīķļņšūž]+$')
else:
    token = re.compile(r"[A-Za-z]+")
    word_ok = re.compile(r'[a-z]+$')
triple = re.compile(r'(.)\1\1')

def fold(w):
    return w.replace('ё', 'е') if lang == 'ru' else w

with open(dictfile, encoding='utf-8') as f:
    words = [line.strip() for line in f if line.strip()]
known = {fold(w) for w in words}

MID_GAPS = (' ', ', ')
mid = collections.defaultdict(collections.Counter)   # folded lowercase -> exact form -> count
freq = collections.Counter()                         # folded lowercase -> count, any position

for sf in sent_files:
    with open(sf, encoding='utf-8', errors='ignore') as f:
        for line in f:
            tab = line.find('\t')
            text = line[tab + 1:].rstrip('\n') if tab >= 0 else line.rstrip('\n')
            toks = list(token.finditer(text))
            if len(toks) < 3:
                continue
            capped = sum(1 for m in toks if m.group()[0].isupper())
            headline = len(toks) >= 4 and capped / len(toks) > 0.6
            for i, m in enumerate(toks):
                w = m.group()
                key = fold(w.lower())
                freq[key] += 1
                if headline or i == 0 or text[toks[i - 1].end():m.start()] not in MID_GAPS:
                    continue
                mid[key][fold(w)] += 1

new = []
for key, counter in mid.items():
    if key in known or len(key) < 3 or len(key) > 20:
        continue
    if not word_ok.match(key) or triple.search(key):
        continue
    total = sum(counter.values())
    if total < min_count:
        continue
    lower = counter.get(key, 0)
    if (total - lower) / total < share:
        continue
    best, _ = max(((f, c) for f, c in counter.items() if f != key), key=lambda fc: fc[1])
    # A name is Capitalized; an acronym is CAPITALS. Anything else (camelCase tokens, glued
    # words from a scraped page) is not something to teach a dictionary.
    if not (best[1:] == best[1:].lower() or best.isupper()):
        continue
    new.append((total, best.lower()))

new.sort(key=lambda tw: -tw[0])
new = new[:max_new]

extra = []
if extra_file:
    taken = known | {fold(w) for _, w in new}
    with open(extra_file, encoding='utf-8') as f:
        for line in f:
            if line.startswith('#'):
                continue
            for w in line.split():
                w = w.lower()
                if fold(w) not in taken:
                    taken.add(fold(w)); extra.append(w)

# Rank: the share of dictionary words that are MORE common in this corpus, as a position.
dict_counts = sorted(freq.get(fold(w), 0) for w in words)
placed = []
for w in [w for _, w in new] + extra:
    c = freq[fold(w)]
    more_common = len(dict_counts) - bisect.bisect_right(dict_counts, c)
    placed.append((max(floor, more_common), w))
placed.sort(key=lambda pw: pw[0])

out = []
j = 0
for i, w in enumerate(words):
    while j < len(placed) and placed[j][0] <= i:
        out.append(placed[j][1]); j += 1
    out.append(w)
out.extend(w for _, w in placed[j:])

with open(dictfile, 'w', encoding='utf-8') as f:
    f.write('\n'.join(out) + '\n')
print(f"{lang}: {len(words)} + {len(new)} names + {len(extra)} listed = {len(out)}; first: {' '.join(w for _, w in new[:40])}")
