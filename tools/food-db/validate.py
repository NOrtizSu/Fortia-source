"""Checks the generated catalog against the contract (no source data needed).

    python tools/food-db/validate.py [--classes tools/food-model/classes.csv] [--foods ...] [--names ...]
                                     [--recipes ...] [-v]

foods.tsv (v2): header and columns, unique ids, groups, units, number formats and ranges, energy vs
4P+4C+9F+2fibre (within max(25 kcal, 20%); only the drinks in ALCOHOLIC may exceed it, alcohol not being a column),
sugars <= carbs, saturated (and saturated + mono + poly) fat <= fat, the 14 micronutrients (at most 2 decimals,
plausible ranges), variant_of (an existing food that is not itself a variant; variants have visual = 0), portion keys
and amounts, visual/source values.
recipes.tsv: header, dishes and ingredients are foods, grams > 0 with 1 decimal, adjustable 0/1, no duplicate
ingredient; for dishes computed from their recipe (source = recipe) the ingredients reproduce every value of the dish
within rounding; for the others (a typical recipe of a USDA/CIQUAL dish) the ingredients' energy is within
TYPICAL_KCAL (and 10 kcal) of the dish and no adjustable ingredient brings more energy, protein, carbohydrate, fat,
saturated fat or salt than the dish has (removing it would give a negative value; the app also clamps at 0).
97_foods.txt: a food_<id> name (EN and ES) for every food and a food_group_<group> name for every group.
Both files equal a fresh build from sources_used.tsv (build.py --check), and, when a class list is given (or
tools/food-model/classes.csv exists), every model class id except _not_food (and rows with status dropped) is a visual
food and every visual food is a class. Exit code 1 on any error.
"""
import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
FOODS = os.path.join(REPO, 'app', 'src', 'main', 'assets', 'food', 'foods.tsv')
RECIPES = os.path.join(REPO, 'app', 'src', 'main', 'assets', 'food', 'recipes.tsv')
NAMES = os.path.join(REPO, 'tools', 'i18n', 'src', 'master', '97_foods.txt')
CLASSES = os.path.join(REPO, 'tools', 'food-model', 'classes.csv')

HEADER_V1 = ['id', 'group', 'unit', 'kcal', 'protein', 'carbs', 'sugars', 'fat', 'sat_fat', 'fiber', 'salt',
             'portion', 'portions', 'visual', 'source']
BASE = ['kcal', 'protein', 'carbs', 'sugars', 'fat', 'sat_fat', 'fiber', 'salt']
MICRO = ['mono_fat', 'poly_fat', 'cholesterol', 'potassium', 'calcium', 'iron', 'magnesium', 'zinc', 'vit_a', 'vit_c',
         'vit_d', 'vit_e', 'vit_b12', 'folate']
HEADER = HEADER_V1 + MICRO + ['variant_of']
RECIPES_HEADER = ['dish', 'ingredient', 'grams', 'adjustable']
# Upper bounds per 100 g that no food in the catalog should reach (implausible = an error in the data).
MICRO_MAX = {'mono_fat': 100, 'poly_fat': 100, 'cholesterol': 1500, 'potassium': 4000, 'calcium': 2000, 'iron': 60,
             'magnesium': 800, 'zinc': 40, 'vit_a': 15000, 'vit_c': 2000, 'vit_d': 60, 'vit_e': 150, 'vit_b12': 100,
             'folate': 3000}
GROUPS = ['fruit', 'vegetables', 'grains', 'legumes', 'meat', 'fish', 'eggs', 'dairy', 'fats_nuts', 'sweets', 'drinks',
          'dishes', 'fast_food', 'snacks', 'sauces']
PORTION_KEYS = ['unit', 'piece', 'slice', 'plate', 'small_plate', 'bowl', 'cup', 'glass', 'can', 'bottle', 'tbsp',
                'tsp', 'handful', 'serving']
ID = re.compile(r'^[a-z0-9_]+$')
DEC = re.compile(r'^\d+(\.\d)?$')
DEC2 = re.compile(r'^\d+(\.\d{1,2})?$')
INT = re.compile(r'^\d+$')
SOURCE = re.compile(r'^(fdc:\d+|ciqual:\d+|recipe)$')
NAME = re.compile(r'^(food_[a-z0-9_]+) = (.+?) \|\|\| (.+)$')
# Drinks with alcohol (7 kcal/g, not a column): their energy is more than the macronutrients explain.
ALCOHOLIC = {'beer', 'red_wine', 'white_wine', 'rose_wine', 'sparkling_wine', 'sangria', 'tinto_de_verano', 'cider',
             'vermouth', 'spirits', 'cocktail', 'mojito', 'gin_tonic', 'rum_and_cola', 'rum', 'gin', 'vodka',
             'brandy', 'carajillo', 'clara', 'kalimotxo'}
# A typical recipe's ingredients (dishes with their own USDA/CIQUAL values) must explain the dish's energy within
# this share (the app applies ingredient changes as deltas, so the amounts must be realistic).
TYPICAL_KCAL = 0.25
# ...and no adjustable ingredient may bring more of these than the dish has (removing it would go below 0).
TYPICAL_STRICT = ['kcal', 'protein', 'carbs', 'fat', 'sat_fat', 'salt']


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


def number(s, pattern):
    if s == '':
        return None, True
    if not pattern.match(s):
        return None, False
    return float(s), True


def check_foods(rows):
    errors, warnings = [], []
    seen = set()
    by_id = {r['id']: r for _, r in rows}
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
        for k in BASE:
            s = r[k]
            if s == '' and k in ('kcal', 'protein', 'carbs', 'fat'):
                errors.append(f'{w}: {k} is empty')
            v[k], ok = number(s, INT if k == 'kcal' else DEC)
            if not ok:
                errors.append(f'{w}: {k} {s!r} is not a {"integer" if k == "kcal" else "number with max 1 decimal"}')
        for k in MICRO:
            s = r.get(k, '')
            v[k], ok = number(s, DEC if k in ('mono_fat', 'poly_fat') else DEC2)
            if not ok:
                errors.append(f'{w}: {k} {s!r} is not a number with max {1 if k.endswith("_fat") else 2} decimals')
            elif v[k] is not None and v[k] > MICRO_MAX[k]:
                errors.append(f'{w}: {k} {v[k]} is implausible (> {MICRO_MAX[k]})')
        g = lambda k: v.get(k) or 0.0
        if v.get('kcal') is not None and v['kcal'] > 900:
            errors.append(f'{w}: kcal {v["kcal"]} > 900')
        if g('protein') + g('carbs') + g('fat') + g('fiber') + g('salt') > 101:
            errors.append(f'{w}: protein+carbs+fat+fibre+salt > 100 g')
        if v.get('sugars') is not None and v['sugars'] > g('carbs') + 0.15:
            errors.append(f'{w}: sugars > carbs')
        if v.get('sat_fat') is not None and v['sat_fat'] > g('fat') + 0.15:
            errors.append(f'{w}: saturated fat > fat')
        fatty = g('sat_fat') + g('mono_fat') + g('poly_fat')
        if fatty > g('fat') * 1.02 + 0.35:
            errors.append(f'{w}: saturated + mono + poly fat {fatty:.1f} > fat {g("fat")}')
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
        base = r.get('variant_of', '')
        if base:
            if base not in by_id or base == r['id']:
                errors.append(f'{w}: variant_of {base!r} is not another food')
            elif by_id[base].get('variant_of'):
                errors.append(f'{w}: variant_of {base!r} is itself a variant')
            if r['visual'] != '0':
                errors.append(f'{w}: a variant must have visual = 0')
    return errors, warnings


def read_recipes(path):
    if not os.path.exists(path):
        return [], [f'{path}: missing (run tools/food-db/build.py)']
    with open(path, encoding='utf-8', newline='') as f:
        data = f.read()
    errors = []
    if '\r' in data:
        errors.append('recipes.tsv: CR characters (must be LF)')
    lines = [l for l in data.split('\n') if l]
    if not lines or lines[0].split('\t') != RECIPES_HEADER:
        return [], errors + [f'recipes.tsv: header must be {RECIPES_HEADER}']
    out = []
    for n, line in enumerate(lines[1:], 2):
        cells = line.split('\t')
        if len(cells) != len(RECIPES_HEADER):
            errors.append(f'recipes.tsv:{n}: {len(cells)} columns')
            continue
        out.append((n, dict(zip(RECIPES_HEADER, cells))))
    return out, errors


def value(r, k):
    """The number in a cell, None when empty or malformed (check_foods reports malformed ones)."""
    try:
        return float(r[k]) if r.get(k, '') != '' else None
    except ValueError:
        return None


def rounding_slack(k, dish_val):
    """Half a unit of the last decimal the column uses (kcal 0.5; grams 0.05; mg/ug by size)."""
    if k == 'kcal':
        return 0.5
    if k in ('protein', 'carbs', 'sugars', 'fat', 'sat_fat', 'fiber', 'salt', 'mono_fat', 'poly_fat'):
        return 0.05
    a = abs(dish_val)
    return 0.5 if a >= 100 else 0.05 if a >= 10 else 0.005


def check_recipes(recipe_rows, food_rows):
    """Errors and warnings of recipes.tsv against foods.tsv (see the module docstring)."""
    errors, warnings = [], []
    foods = {r['id']: r for _, r in food_rows}
    dishes = {}
    for n, r in recipe_rows:
        w = f'recipes.tsv:{n} {r["dish"]}/{r["ingredient"]}'
        if r['dish'] not in foods:
            errors.append(f'{w}: dish is not a food')
        if r['ingredient'] not in foods:
            errors.append(f'{w}: ingredient is not a food')
        if r['ingredient'] == r['dish']:
            errors.append(f'{w}: a dish is not its own ingredient')
        if not DEC.match(r['grams']) or float(r['grams']) <= 0:
            errors.append(f'{w}: grams {r["grams"]!r} must be > 0 with max 1 decimal')
        if r['adjustable'] not in ('0', '1'):
            errors.append(f'{w}: adjustable must be 0 or 1')
        items = dishes.setdefault(r['dish'], {})
        if r['ingredient'] in items:
            errors.append(f'{w}: duplicate ingredient')
        items[r['ingredient']] = r
    for dish, items in dishes.items():
        if dish not in foods or any(i not in foods for i in items) or any(not DEC.match(x['grams']) for x in items.values()):
            continue
        d = foods[dish]
        sums = {}
        for k in BASE + MICRO:
            dv = value(d, k)
            parts = [(float(x['grams']), value(foods[i], k)) for i, x in items.items()]
            if dv is None or any(v is None for _, v in parts):
                continue
            total = sum(gr * v / 100 for gr, v in parts)
            sums[k] = total
            if d['source'] != 'recipe':
                continue
            # Rounding of the dish, of each ingredient's value and of its grams (0.05 g), plus ingredient rows left
            # out under 0.05 g (at most 0.05 g each of the strongest ingredient is not knowable here: 0.1%).
            slack = rounding_slack(k, dv) + sum(gr * rounding_slack(k, v) / 100 + 0.05 * v / 100 for gr, v in parts)
            slack += 0.001 * max(abs(dv), 1)
            if abs(total - dv) > slack:
                errors.append(f'recipes.tsv {dish}: {k} of the ingredients {total:.3f} != {dv} (slack {slack:.3f})')
        if d['source'] != 'recipe' and 'kcal' in sums:
            kcal = value(d, 'kcal')
            if abs(sums['kcal'] - kcal) > max(10.0, TYPICAL_KCAL * kcal):
                errors.append(f'recipes.tsv {dish}: typical recipe gives {sums["kcal"]:.0f} kcal, dish {kcal:.0f}')
            elif abs(sums['kcal'] - kcal) > max(8.0, 0.12 * kcal):
                warnings.append(f'recipes.tsv {dish}: typical recipe gives {sums["kcal"]:.0f} kcal, dish {kcal:.0f}')
        if d['source'] != 'recipe':
            # The app subtracts what a removed ingredient brings: no single adjustable ingredient may bring more of
            # a main nutrient than the dish has (the result would go below 0).
            for k in TYPICAL_STRICT:
                dv = value(d, k)
                if dv is None:
                    continue
                adj = [(float(x['grams']) * value(foods[i], k) / 100, i) for i, x in items.items()
                       if x['adjustable'] == '1' and value(foods[i], k) is not None]
                slack = 1.0 if k == 'kcal' else 0.15
                for c, i in adj:
                    if c > dv + slack:
                        errors.append(f'recipes.tsv {dish}: {i} brings {c:.1f} {k}, more than the dish ({dv})')
                total = sum(c for c, _ in adj)
                if total > 1.25 * dv + slack:
                    warnings.append(f'recipes.tsv {dish}: adjustable ingredients bring {total:.1f} {k}, dish {dv}')
    recipe_dishes = {r['id'] for _, r in food_rows if r['source'] == 'recipe'}
    return errors, warnings, len(dishes), len(recipe_dishes & set(dishes))


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
    sys.path.insert(0, HERE)
    import build
    errors = []
    classes = build.read_classes(path)
    visual = {r['id'] for _, r in rows if r['visual'] == '1'}
    ids = {r['id'] for _, r in rows}
    for c in sorted(classes):
        if c not in ids:
            errors.append(f'class {c}: not in foods.tsv')
        elif c not in visual:
            errors.append(f'class {c}: visual must be 1')
    for v in sorted(visual - classes):
        errors.append(f'{v}: visual = 1 but not a model class')
    return errors, len(classes)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--foods', default=FOODS)
    ap.add_argument('--recipes', default=RECIPES)
    ap.add_argument('--names', default=NAMES)
    ap.add_argument('--classes', default=CLASSES if os.path.exists(CLASSES) else None)
    ap.add_argument('-v', '--verbose', action='store_true')
    args = ap.parse_args()
    rows, errors = read_foods(args.foods)
    e, warnings = check_foods(rows)
    errors += e
    recipe_rows, e = read_recipes(args.recipes)
    errors += e
    e, w, n_dishes, n_recipe = check_recipes(recipe_rows, rows)
    errors += e
    warnings += w
    e, _ = check_names(rows, args.names)
    errors += e
    n_classes = None
    if args.classes:
        e, n_classes = check_classes(rows, args.classes)
        errors += e
    if args.foods == FOODS and args.names == NAMES and args.recipes == RECIPES:
        sys.path.insert(0, HERE)
        import build
        errors += build.check()
    visual = sum(1 for _, r in rows if r['visual'] == '1')
    variants = sum(1 for _, r in rows if r.get('variant_of'))
    print(f'{len(rows)} foods, {visual} visual, {variants} variants, {n_dishes} dishes with ingredients '
          f'({n_recipe} computed from them)' +
          (f', {n_classes} model classes checked' if n_classes is not None else ', no class list given'))
    if args.verbose or errors:
        for w in warnings:
            print('warning: ' + w)
    for err in errors:
        print('error: ' + err)
    sys.exit(1 if errors else 0)


if __name__ == '__main__':
    main()
