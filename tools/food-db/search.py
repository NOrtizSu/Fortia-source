"""Looks up foods in the extracted tables (helper for editing mapping.tsv and recipes.toml).

    python tools/food-db/search.py lentil cooked            every word must appear (EN or FR description)
    python tools/food-db/search.py -d ciqual paella         only one dataset (foundation, sr_legacy, fndds, ciqual)
    python tools/food-db/search.py -k fdc:172421 ciqual:20587   show these keys with their USDA portions
"""
import argparse
import csv
import os
import sys
import unicodedata

COLS = ['kcal', 'protein', 'carbs', 'sugars', 'fat', 'sat_fat', 'fiber', 'salt']


def fold(s):
    return ''.join(c for c in unicodedata.normalize('NFD', s.lower()) if unicodedata.category(c) != 'Mn')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('words', nargs='*')
    ap.add_argument('-d', '--dataset')
    ap.add_argument('-k', '--keys', action='store_true', help='the words are keys')
    ap.add_argument('-n', type=int, default=40)
    ap.add_argument('--dir', default=os.environ.get('FOODDB_DIR', 'fooddb-data'),
                    help='work folder (default $FOODDB_DIR or ./fooddb-data)')
    args = ap.parse_args()
    work = os.path.join(args.dir, 'work')
    words = [fold(w) for w in args.words]
    out = []
    with open(os.path.join(work, 'nutrients.tsv'), encoding='utf-8', newline='') as f:
        for r in csv.DictReader(f, delimiter='\t', quoting=csv.QUOTE_NONE):
            if args.keys:
                if r['key'] not in args.words:
                    continue
            else:
                if args.dataset and r['dataset'] != args.dataset:
                    continue
                text = fold(r['description'] + ' | ' + r['description_fr'])
                if not all(w in text for w in words):
                    continue
            out.append(r)
    portions = {}
    if args.keys:
        with open(os.path.join(work, 'portions.tsv'), encoding='utf-8', newline='') as f:
            for r in csv.DictReader(f, delimiter='\t', quoting=csv.QUOTE_NONE):
                if r['key'] in args.words:
                    portions.setdefault(r['key'], []).append(f'{r["portion"]}={r["grams"]}')
    for r in out[:args.n]:
        vals = ' '.join(f'{c[:3]}={r[c]}' for c in COLS)
        desc = r['description'] + (f' [{r["description_fr"]}]' if r['description_fr'] else '')
        print(f'{r["key"]:<14} {r["dataset"][:4]:<4} {desc[:110]}\n{"":19}{vals}')
        if r['key'] in portions:
            print(f'{"":19}portions: {"; ".join(portions[r["key"]][:12])}')
    if len(out) > args.n:
        print(f'... {len(out) - args.n} more', file=sys.stderr)


if __name__ == '__main__':
    main()
