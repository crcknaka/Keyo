#!/usr/bin/env python3
"""
Build a bundled frequency dictionary (app/src/main/assets/dict/<lang>.txt, most-frequent first)
from Leipzig Corpora Collection *-words.txt files (format: id<TAB>word<TAB>count).

Counts are case-folded and summed across all given corpora. Words must fully match the language's
alphabet; junk (triple repeated letters, too-rare tokens) is dropped. An optional --base previous
dictionary is merged in at the tail so expanding the dictionary never *loses* a word that used to
be known (old words keep their relative order, ranked below the fresh top-N).

Reproduce (corpora as in gen_bigrams.py):
    python3 gen_dict.py en 30000 3 out/en.txt --base old/en.txt \
        eng_news_2023_300K/*-words.txt
    python3 gen_dict.py lv 30000 3 out/lv.txt --base old/lv.txt \
        lav_news_2020_300K/*-words.txt lav-lv_web_2015_300K/*-words.txt
"""
import sys, re, collections

lang = sys.argv[1]
topN = int(sys.argv[2])
minCount = int(sys.argv[3])
outfile = sys.argv[4]
args = sys.argv[5:]
base = None
if args and args[0] == '--base':
    base = args[1]
    args = args[2:]
word_files = args

if lang == 'ru':
    pat = re.compile(r'[а-яё]+$')
elif lang == 'lv':
    pat = re.compile(r'[a-zāčēģīķļņšūž]+$')
else:  # en — keep internal apostrophes ("don't"), strip edge ones
    pat = re.compile(r"[a-z']+$")

triple = re.compile(r'(.)\1\1')
single_ok = {'a', 'i'} if lang == 'en' else ({'я', 'и', 'а', 'о', 'у', 'в', 'с', 'к'} if lang == 'ru' else set())

counts = collections.Counter()
for wf in word_files:
    with open(wf, encoding='utf-8', errors='ignore') as f:
        for line in f:
            parts = line.rstrip('\n').split('\t')
            if len(parts) < 3:
                continue
            w = parts[1].strip().lower().replace('’', "'").strip("'")
            try:
                c = int(parts[-1])
            except ValueError:
                continue
            if not w or not pat.match(w):
                continue
            if len(w) == 1 and w not in single_ok:
                continue
            if len(w) > 24 or triple.search(w):
                continue
            counts[w] += c

fresh = [w for w, c in counts.most_common() if c >= minCount][:topN]
seen = set(fresh)
out = list(fresh)

kept_old = 0
if base:
    with open(base, encoding='utf-8') as f:
        for line in f:
            w = line.strip()
            if w and w not in seen:
                seen.add(w)
                out.append(w)
                kept_old += 1

with open(outfile, 'w', encoding='utf-8') as f:
    f.write('\n'.join(out) + '\n')

print(f"{lang}: corpusTypes={len(counts)} fresh={len(fresh)} keptFromOldDict={kept_old} total={len(out)} -> {outfile}")
