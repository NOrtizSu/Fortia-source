"""Checks the generated catalog against the contract (no source data needed).

    python tools/food-db/validate.py [--classes tools/food-model/classes.csv] [--foods ...] [--names ...]

Checks: header and columns, unique ids, groups, units, number formats and ranges, energy vs 4P+4C+9F+2fibre
(within max(25 kcal, 20%); only the drinks in ALCOHOLIC may exceed it, alcohol not being a column), portion keys
and amounts, visual/source values, a food_<id> name (EN and ES) for every food and a food_group_<group> name for
every group in 97_foods.txt, that both files equal a fresh build from sources_used.tsv (build.py --check), and,
when a class list is given (or tools/food-model/classes.csv exists), that every model class id except _not_food is
a visual food and every visual food is a class. Exit code 1 on any error.
"""
import argparse
import csv
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
FOODS = os.path.join(REPO, 'app', 'src', 'main', 'assets', 'food', 'foods.tsv')
NAMES = os.path.join(REPO, 'tools', 'i18n', 'src', 'master', '97_foods.txt')
CLASSES = os.path.join(REPO, 'tools', 'food-model', 'classes.csv')

HEADER = ['id', 'group', 'unit', 'kcal', 'protein', 'carbs', 'sugars', 'fat', 'sat_fat', 'fiber', 'salt', 'portion',
          'portions', 'visual', 'source']
GROUPS = ['fruit', 'vegetables', 'grains', 'legumes', 'meat', 'fish', 'eggs', 'dairy', 'fats_nuts', 'sweets', 'drinks',
          'dishes', 'fast_food', 'snacks', 'sauces']
PORTION_KEYS = ['unit', 'piece', 'slice', 'plate', 'small_plate', 'bowl', 'cup', 'glass', 'can', 'bottle', 'tbsp',
                'tsp', 'handful', 'serving']
ID = re.compile(r'^[a-z0-9_]+$')
DEC = re.compile(r'^\d+(\.\d)?$')
INT = re.compile(r'^\d+$')
SOURCE = re.compile(r'^(fdc:\d+|ciqual:\d+|recipe)$')
NAME = re.compile(r'^(food_[a-z0-9_]+) = (.+?) \|\|\| (.+)$')
# Drinks with alcohol (7 kcal/g, not a column): their energy is more than the macronutrients explain.
ALCOHOLIC = {'beer', 'red_wine', 'white_wine', 'rose_wine', 'sparkling_wine', 'sangria', 'tinto_de_verano', 'cider',
             'vermouth', 'spirits', 'cocktail', 'mojito', 'gin_tonic', 'rum_and_cola'}


def read_foods(path):
    with open(path, encoding='utf-8', newline='') as f:
        data = f.read()
    errors = []
    if '\r' in data:
        errors.append('foods.tsv: CR characters (must be LF)')
    lines = data.split('\n')
    if lines and lines[-1] == '':
        lines.pop()
    if not lines or lines[0].split('\t') != HEADER:
        errors.append(f'foods.tsv: header must be {HEADER}')
        return [], errors
    rows = []
    for n, line in enumerate(lines[1:], 2):
        cells = line.split('\t')
        if len(cells) != len(HEADER):
            errors.append(f'foods.tsv:{n}: {len(cells)} columns')
            continue
        rows.append((n, dict(zip(HEADER, cells))))
    return rows, errors


def check_foods(rows):
    errors, warnings = [], []
    seen = set()
    for n, r in rows:
        w = f'foods.tsv:{n} {r["id"]}'
        if not ID.match(r['id']):
            errors.append(f'{w}: bad id')
        if r['id'] in seen:
            errors.append(f'{w}: duplicate id')
        seen.add(r['id'])
        if r['group'] not in GROUPS:
            errors.append(f'{w}: bad group {r["group"]}')
        if r['unit'] not in ('g', 'ml'):
            errors.append(f'{w}: bad unit {r["unit"]}')
        v = {}
        for k in ('kcal', 'protein', 'carbs', 'sugars', 'fat', 'sat_fat', 'fiber', 'salt'):
            s = r[k]
            if s == '':
                v[k] = None
                if k in ('kcal', 'protein', 'carbs', 'fat'):
                    errors.append(f'{w}: {k} is empty')
                continue
            if not (INT if k == 'kcal' else DEC).match(s):
                errors.append(f'{w}: {k} {s!r} is not a {"integer" if k == "kcal" else "number with max 1 decimal"}')
                v[k] = None
                continue
            v[k] = float(s)
        g = lambda k: v.get(k) or 0.0
        if v.get('kcal') is not None and v['kcal'] > 900:
            errors.append(f'{w}: kcal {v["kcal"]} > 900')
        if g('protein') + g('carbs') + g('fat') + g('fiber') + g('salt') > 101:
            errors.append(f'{w}: protein+carbs+fat+fibre+salt > 100 g')
        if v.get('sugars') is not None and v['sugars'] > g('carbs') + 0.15:
            errors.append(f'{w}: sugars > carbs')
        if v.get('sat_fat') is not None and v['sat_fat'] > g('fat') + 0.15:
            errors.append(f'{w}: saturated fat > fat')
        if r['unit'] == 'ml' and g('protein') + g('carbs') + g('fat') > 60:
            errors.append(f'{w}: ml food with more than 60 g of solids per 100 ml')
        calc = 4 * g('protein') + 4 * g('carbs') + 9 * g('fat') + 2 * g('fiber')
        alcohol = r['id'] in ALCOHOLIC and v.get('kcal') is not None and v['kcal'] > calc
        if v.get('kcal') is not None and not alcohol:
            if abs(calc - v['kcal']) > max(25.0, 0.2 * v['kcal']):
                errors.append(f'{w}: kcal {v["kcal"]:.0f} far from 4P+4C+9F+2fibre = {calc:.0f}')
            elif abs(calc - v['kcal']) > max(15.0, 0.12 * v['kcal']):
                warnings.append(f'{w}: kcal {v["kcal"]:.0f} vs 4P+4C+9F+2fibre = {calc:.0f}')
        if not INT.match(r['portion']) or int(r['portion']) <= 0:
            errors.append(f'{w}: bad portion {r["portion"]!r}')
        amounts = []
        if r['portions']:
            for part in r['portions'].split('|'):
                m = re.match(r'^([a-z_]+):(\d+)$', part)
                if not m or m.group(1) not in PORTION_KEYS or int(m.group(2)) <= 0:
                    errors.append(f'{w}: bad portion {part!r}')
                else:
                    amounts.append(int(m.group(2)))
        if amounts and INT.match(r['portion']) and int(r['portion']) not in amounts:
            errors.append(f'{w}: default portion is not one of the portions')
        if r['visual'] not in ('0', '1'):
            errors.append(f'{w}: visual must be 0 or 1')
        if not SOURCE.match(r['source']):
            errors.append(f'{w}: bad source {r["source"]!r}')
    return errors, warnings


def check_names(rows, path):
    errors = []
    names = {}
    with open(path, encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line.strip() or line.startswith('#'):
                continue
            m = NAME.match(line)
            if not m or not m.group(2).strip() or not m.group(3).strip():
                errors.append(f'97_foods.txt:{n}: expected `food_<key> = English ||| Spanish`: {line!r}')
                continue
            if m.group(1) in names:
                errors.append(f'97_foods.txt:{n}: duplicate {m.group(1)}')
            names[m.group(1)] = (m.group(2), m.group(3))
    ids = {r['id'] for _, r in rows}
    for _, r in rows:
        if f'food_{r["id"]}' not in names:
            errors.append(f'97_foods.txt: no food_{r["id"]}')
    for g in GROUPS:
        if f'food_group_{g}' not in names:
            errors.append(f'97_foods.txt: no food_group_{g}')
    for key in names:
        if not key.startswith('food_group_') and key[5:] not in ids:
            errors.append(f'97_foods.txt: {key} has no food in foods.tsv')
    return errors, names


def check_classes(rows, path):
    errors = []
    with open(path, encoding='utf-8', newline='') as f:
        classes = [r['id'].strip() for r in csv.DictReader(f) if r.get('id')]
    classes = [c for c in classes if c != '_not_food']
    visual = {r['id'] for _, r in rows if r['visual'] == '1'}
    ids = {r['id'] for _, r in rows}
    for c in classes:
        if c not in ids:
            errors.append(f'class {c}: not in foods.tsv')
        elif c not in visual:
            errors.append(f'class {c}: visual must be 1')
    for v in sorted(visual - set(classes)):
        errors.append(f'{v}: visual = 1 but not a model class')
    return errors, len(classes)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--foods', default=FOODS)
    ap.add_argument('--names', default=NAMES)
    ap.add_argument('--classes', default=CLASSES if os.path.exists(CLASSES) else None)
    ap.add_argument('-v', '--verbose', action='store_true')
    args = ap.parse_args()
    rows, errors = read_foods(args.foods)
    e, warnings = check_foods(rows)
    errors += e
    e, _ = check_names(rows, args.names)
    errors += e
    n_classes = None
    if args.classes:
        e, n_classes = check_classes(rows, args.classes)
        errors += e
    if args.foods == FOODS and args.names == NAMES:
        sys.path.insert(0, HERE)
        import build
        errors += build.check()
    visual = sum(1 for _, r in rows if r['visual'] == '1')
    print(f'{len(rows)} foods, {visual} visual' + (f', {n_classes} model classes checked' if n_classes is not None
                                                   else ', no class list given'))
    if args.verbose or errors:
        for w in warnings:
            print('warning: ' + w)
    for err in errors:
        print('error: ' + err)
    sys.exit(1 if errors else 0)


if __name__ == '__main__':
    main()
