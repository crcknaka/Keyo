#!/usr/bin/env python3
"""
Build the bundled bigram models in app/src/main/assets/dict/bigram_<lang>.txt.

These give next-word prediction / glide & autocorrect context from day one (before the user's own
UserDictionary bigrams have learned anything). Output line format:  prev<TAB>f1 f2 ... fK
(followers most-frequent first), constrained to words present in the bundled dictionary so the
model stays aligned with what the keyboard can suggest and stays small.

Source: Leipzig Corpora Collection (https://wortschatz-leipzig.de/, downloads under
https://downloads.wortschatz-leipzig.de/corpora/<corpus>.tar.gz). Russian gets the heaviest model
(two 1M-sentence corpora, K=10, 40k prev-words); English/Latvian use 300K corpora.

Reproduce:
    cd /tmp && mkdir keyo_corpora && cd keyo_corpora
    B=https://downloads.wortschatz-leipzig.de/corpora
    for f in rus_news_2022_1M rus_wikipedia_2021_1M eng_news_2023_300K \
             lav_news_2020_300K lav-lv_web_2015_300K; do curl -sLO $B/$f.tar.gz; tar -xzf $f.tar.gz; done
    D=<repo>/app/src/main/assets/dict
    python3 gen_bigrams.py ru $D/ru.txt $D/bigram_ru.txt 10 40000 3 \
        rus_news_2022_1M/*-sentences.txt rus_wikipedia_2021_1M/*-sentences.txt
    python3 gen_bigrams.py en $D/en.txt $D/bigram_en.txt 8 18000 2 eng_news_2023_300K/*-sentences.txt
    python3 gen_bigrams.py lv $D/lv.txt $D/bigram_lv.txt 8 15000 2 \
        lav_news_2020_300K/*-sentences.txt lav-lv_web_2015_300K/*-sentences.txt
"""
import sys, re, collections

lang     = sys.argv[1]
dictfile = sys.argv[2]
outfile  = sys.argv[3]
K        = int(sys.argv[4])      # max followers per prev word
maxPrev  = int(sys.argv[5])      # max prev entries (by total follower volume)
minCount = int(sys.argv[6])      # drop follower pairs seen fewer than this many times
sent_files = sys.argv[7:]

def fold(w):
    w = w.replace('’', "'")
    return w.replace('ё', 'е') if lang == 'ru' else w

if lang == 'ru':
    tok = re.compile(r'[а-яё]+')
elif lang == 'lv':
    tok = re.compile(r'[a-zāčēģīķļņšūž]+')
else:  # en — keep apostrophes inside tokens so "i'm"/"don't" stay atomic (and get dropped by the
       # dict filter) instead of splitting into junk fragments like "m"/"ve"/"t".
    tok = re.compile(r"[a-z']+")

def clean(w):
    return w.strip("'")

vocab = set()
with open(dictfile, encoding='utf-8') as f:
    for line in f:
        w = fold(line.strip().lower())
        if w:
            vocab.add(w)

counts = collections.defaultdict(collections.Counter)
n_sent = 0
for sf in sent_files:
    with open(sf, encoding='utf-8', errors='ignore') as f:
        for line in f:
            tab = line.find('\t')                      # Leipzig format: "<id>\t<sentence>"
            text = line[tab + 1:] if tab >= 0 else line
            words = [w for w in (clean(t) for t in tok.findall(fold(text.lower()))) if w in vocab]
            for a, b in zip(words, words[1:]):
                if a != b:
                    counts[a][b] += 1
            n_sent += 1

ranked = sorted(counts.items(), key=lambda kv: -sum(kv[1].values()))
lines = pairs = 0
with open(outfile, 'w', encoding='utf-8') as out:
    for prev, foll in ranked:
        top = [(w, c) for w, c in foll.most_common(K) if c >= minCount]
        if not top:
            continue
        out.write(prev + '\t' + ' '.join(w for w, _ in top) + '\n')
        lines += 1
        pairs += len(top)
        if lines >= maxPrev:
            break

print(f"{lang}: sentences={n_sent} prevWords={len(counts)} -> emitted {lines} lines, {pairs} pairs -> {outfile}")
