"""Builds the app's food catalog, the dishes' ingredients and the food names from mapping.txt, recipes.toml and the
source tables.

    python tools/food-db/build.py [--dir DIR] [--fix-descriptions] [--sync-visual CLASSES_CSV]
    python tools/food-db/build.py --check

Writes app/src/main/assets/food/foods.tsv (v2: the 15 columns of v1, the 14 nutrients of MICRO and variant_of),
app/src/main/assets/food/recipes.tsv (dish, ingredient, grams per 100 g or 100 ml of the dish, adjustable) and
tools/i18n/src/master/97_foods.txt, then prints the energy check.

Source values: with <dir>/work/nutrients.tsv (download.py + extract.py; --dir or $FOODDB_DIR, default
./fooddb-data) the build reads the full tables and refreshes sources_used.tsv, the committed copy of the USDA/CIQUAL
rows that mapping.txt and recipes.toml use. Without it, the build reads sources_used.tsv, so mapping, names,
portions and recipes can be changed without downloading anything (a new source code needs the full tables).

--check rebuilds from sources_used.tsv in memory and fails if foods.tsv, recipes.tsv or 97_foods.txt differ from it.
--fix-descriptions rewrites the description column of mapping.txt from the sources (use it after changing a source
code, then review the diff). --sync-visual sets the visual column from the model's class list (an `id` column and,
optionally, a `status` column whose `dropped` rows are not classes) and fails if a class has no row yet.
"""
import argparse
import csv
import math
import os
import re
import sys
import tomllib

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
MAPPING = os.path.join(HERE, 'mapping.txt')
RECIPES = os.path.join(HERE, 'recipes.toml')
SNAPSHOT = os.path.join(HERE, 'sources_used.tsv')
OUT_TSV = os.path.join(REPO, 'app', 'src', 'main', 'assets', 'food', 'foods.tsv')
OUT_RECIPES = os.path.join(REPO, 'app', 'src', 'main', 'assets', 'food', 'recipes.tsv')
OUT_NAMES = os.path.join(REPO, 'tools', 'i18n', 'src', 'master', '97_foods.txt')

HEADER_V1 = ['id', 'group', 'unit', 'kcal', 'protein', 'carbs', 'sugars', 'fat', 'sat_fat', 'fiber', 'salt',
             'portion', 'portions', 'visual', 'source']
NUTRIENTS = ['kcal', 'protein', 'carbs', 'sugars', 'fat', 'sat_fat', 'fiber', 'salt']
# foods.tsv v2 (contract 2): per 100 g/ml; mono_fat, poly_fat in g; cholesterol, potassium, calcium, iron,
# magnesium, zinc, vit_c, vit_e in mg; vit_a ug RAE, vit_d ug, vit_b12 ug, folate ug DFE.
MICRO = ['mono_fat', 'poly_fat', 'cholesterol', 'potassium', 'calcium', 'iron', 'magnesium', 'zinc', 'vit_a', 'vit_c',
         'vit_d', 'vit_e', 'vit_b12', 'folate']
HEADER = HEADER_V1 + MICRO + ['variant_of']
RECIPES_HEADER = ['dish', 'ingredient', 'grams', 'adjustable']
VALUES = NUTRIENTS + MICRO
EXTRA = ['alcohol', 'polyols', 'organic_acids']
ALL = VALUES + EXTRA
ONE_DECIMAL = set(NUTRIENTS[1:]) | {'mono_fat', 'poly_fat'}  # grams: 1 decimal, like the v1 columns
REQUIRED = ('kcal', 'protein', 'carbs', 'fat')
# Ingredients the user does not adjust when logging a dish (contract 3: water, salt, spices); a recipe line can
# override it with a 4th element (0 or 1).
NOT_ADJUSTABLE = {'water', 'salt', 'paprika', 'garlic', 'vinegar', 'broth', 'lemon', 'lime_juice', 'spring_onion',
                  'chili_pepper', 'sparkling_water'}
# A recipe nutrient is unknown when the ingredients that lack it weigh at least this share of the recipe.
MISSING_SHARE = 0.05
GROUPS = {
    'fruit': ('Fruit', 'Fruta'),
    'vegetables': ('Vegetables', 'Verduras y hortalizas'),
    'grains': ('Bread, cereals and pasta', 'Pan, cereales y pasta'),
    'legumes': ('Legumes', 'Legumbres'),
    'meat': ('Meat and cold cuts', 'Carne y embutidos'),
    'fish': ('Fish and seafood', 'Pescado y marisco'),
    'eggs': ('Eggs', 'Huevos'),
    'dairy': ('Dairy', 'Lácteos'),
    'fats_nuts': ('Oils, fats and nuts', 'Aceites, grasas y frutos secos'),
    'sweets': ('Sweets and desserts', 'Dulces y postres'),
    'drinks': ('Drinks', 'Bebidas'),
    'dishes': ('Dishes', 'Platos'),
    'fast_food': ('Fast food', 'Comida rápida'),
    'snacks': ('Snacks', 'Aperitivos'),
    'sauces': ('Sauces and dressings', 'Salsas y aliños'),
}
PORTION_KEYS = ['unit', 'piece', 'slice', 'plate', 'small_plate', 'bowl', 'cup', 'glass', 'can', 'bottle', 'tbsp',
                'tsp', 'handful', 'serving']
COLUMNS = ['id', 'group', 'unit', 'visual', 'source', 'opts', 'portion', 'portions', 'en', 'es', 'description', 'note']
OPTIONS = ('fill', 'density', 'variant_of', 'zero', 'replace')
ID = re.compile(r'^[a-z0-9_]+$')
KEY = re.compile(r'^(fdc|ciqual):\d+$')


def load_sources(path):
    """nutrients.tsv (extract.py) or sources_used.tsv: key -> description, dataset, values, raw line."""
    if not os.path.exists(path):
        raise SystemExit(f'missing {path}: run tools/food-db/download.py and extract.py first')
    out = {}
    with open(path, encoding='utf-8', newline='') as f:
        lines = f.read().split('\n')
    header = lines[0]
    cols = header.split('\t')
    for line in filter(None, lines[1:]):
        r = dict(zip(cols, line.split('\t')))
        vals = {k: (float(r[k]) if r.get(k, '') != '' else None) for k in ALL}
        out[r['key']] = {'description': r['description'], 'dataset': r['dataset'], 'values': vals, 'line': line,
                         'fortified': r.get('fortified', '') == '1'}
    out['_header'] = header
    return out


def snapshot_text(sources, used):
    """The rows of the source tables that the catalog uses, sorted by key (sources_used.tsv)."""
    def order(key):
        ds, num = key.split(':')
        return ds, int(num)
    return '\n'.join([sources['_header']] + [sources[k]['line'] for k in sorted(used, key=order)]) + '\n'


def read_mapping(path=None):
    """Rows of `a | b | ...` with the COLUMNS above; `#` lines are comments."""
    rows = []
    with open(path or MAPPING, encoding='utf-8') as f:
        for n, raw in enumerate(f, 1):
            line = raw.rstrip('\n')
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            cells = [c.strip() for c in line.split('|')]
            if len(cells) != len(COLUMNS):
                raise SystemExit(f'mapping.txt:{n}: {len(cells)} columns, expected {len(COLUMNS)}: {line[:80]}')
            row = dict(zip(COLUMNS, cells))
            row['line'] = n
            rows.append(row)
    return rows


def parse_opts(text, where):
    opts = {}
    for part in filter(None, (p.strip() for p in text.split(';'))):
        if '=' not in part:
            raise SystemExit(f'{where}: bad option {part!r}')
        k, v = (s.strip() for s in part.split('=', 1))
        if k not in OPTIONS:
            raise SystemExit(f'{where}: unknown option {k!r}')
        if k in ('zero', 'replace'):
            names = v
            if k == 'replace':
                parts = v.split(':', 2)
                if len(parts) != 3 or not KEY.match(f'{parts[0]}:{parts[1]}'):
                    raise SystemExit(f'{where}: replace= must be <source key>:<nutrient>,<nutrient>')
                names = parts[2]
            bad = [n for n in names.split(',') if n.strip() not in VALUES]
            if bad:
                raise SystemExit(f'{where}: {k}= lists unknown nutrients {bad}')
        if k == 'density':
            try:
                ok = float(v) > 0
            except ValueError:
                ok = False
            if not ok:
                raise SystemExit(f'{where}: density must be a number > 0')
        opts[k] = v
    return opts


def parse_replace(text):
    """`fdc:123:zinc,vit_c` -> ('fdc:123', ['zinc', 'vit_c'])."""
    ds, num, names = text.split(':', 2)
    return f'{ds}:{num}', [n.strip() for n in names.split(',') if n.strip()]


def source_values(sources, key, where):
    if key not in sources or key.startswith('_'):
        raise SystemExit(f'{where}: unknown source {key} (a new code needs the full tables: see --dir)')
    sources.setdefault('_used', set()).add(key)
    return dict(sources[key]['values'])


def energy(v):
    """EU Regulation 1169/2011 energy factors. CIQUAL's carbohydrate includes polyols (as EU labels do)."""
    g = lambda k: v.get(k) or 0.0
    sugars_starch = max(0.0, g('carbs') - g('polyols'))
    return (4 * g('protein') + 4 * sugars_starch + 9 * g('fat') + 2 * g('fiber') + 7 * g('alcohol')
            + 2.4 * g('polyols') + 3 * g('organic_acids'))


def decimals(k, v):
    if k == 'kcal':
        return 0
    if k in ONE_DECIMAL:
        return 1
    a = abs(v)
    return 0 if a >= 100 else 1 if a >= 10 else 2


def fmt(k, v):
    """kcal: integer; grams: 1 decimal; mg/ug: integer from 100, 1 decimal from 10, else 2 (rounded half up)."""
    if v is None:
        return ''
    d = decimals(k, v)
    s = f'{math.floor(v * 10 ** d + 0.5) / 10 ** d:.{d}f}'
    if '.' in s:
        s = s.rstrip('0').rstrip('.')
    return s


def parse_portions(text, where):
    out = []
    for part in text.split():
        m = re.match(r'^([a-z_]+):(\d+)$', part)
        if not m or m.group(1) not in PORTION_KEYS:
            raise SystemExit(f'{where}: bad portion {part!r}')
        out.append((m.group(1), int(m.group(2))))
    return out


class Catalog:
    """The mapping rows with their computed values (per 100 g, or per 100 ml for unit ml)."""

    def __init__(self, rows, recipes, sources):
        self.rows = {r['id']: r for r in rows}
        self.order = [r['id'] for r in rows]
        self.recipes = recipes
        self.sources = sources
        self.values = {}
        self.missing = {}
        self.lines = {}  # dish id -> [(ingredient ref, amount, adjustable, weight g)]
        self.finished = {}  # dish id -> finished amount of the whole recipe, in the dish's unit
        self.opts = {}
        for r in rows:
            self.opts[r['id']] = parse_opts(r['opts'], f'mapping.txt:{r["line"]} {r["id"]}')
        self._stack = []

    def density(self, fid):
        r = self.rows[fid]
        return float(self.opts[fid].get('density', '1')) if r['unit'] == 'ml' else 1.0

    def food(self, fid):
        """Values of a catalog food (memoised; recipes may use other catalog foods, also recipes)."""
        if fid in self.values:
            return self.values[fid]
        if fid not in self.rows:
            raise SystemExit(f'unknown food {fid!r}' + (f' in recipe {self._stack[-1]}' if self._stack else ''))
        if fid in self._stack:
            raise SystemExit(f'recipe cycle: {" -> ".join(self._stack + [fid])}')
        r = self.rows[fid]
        opts = self.opts[fid]
        where = f'mapping.txt:{r["line"]} {fid}'
        missing = []
        if r['source'] == 'recipe':
            if fid not in self.recipes:
                raise SystemExit(f'{where}: no [{fid}] in recipes.toml')
            bad = [o for o in ('fill', 'zero', 'replace') if o in opts]
            if bad:
                raise SystemExit(f'{where}: {bad} not allowed on a recipe dish (its values are its ingredients)')
            if r['unit'] == 'ml' and 'density' not in opts:
                raise SystemExit(f'{where}: an ml recipe dish needs density= (weight of 1 ml of the drink)')
            self._stack.append(fid)
            vals, missing = self.recipe(fid, where)
            self._stack.pop()
        elif KEY.match(r['source']):
            vals = source_values(self.sources, r['source'], where)
        else:
            raise SystemExit(f'{where}: bad source {r["source"]!r}')
        if 'fill' in opts:
            if opts['fill'] == 'recipe':
                # A USDA/CIQUAL dish without some nutrients: estimate them from its typical recipe.
                if r['source'] == 'recipe' or fid not in self.recipes:
                    raise SystemExit(f'{where}: fill=recipe needs a typical recipe [{fid}] in recipes.toml')
                self._stack.append(fid)
                extra, _ = self.recipe(fid, f'recipes.toml [{fid}] (typical recipe)')
                self._stack.pop()
            else:
                extra = source_values(self.sources, opts['fill'], where)
                if self.sources[opts['fill']].get('fortified'):
                    # A US food made with enriched flour: its iron is not that of a European one.
                    extra['iron'] = None
            # Borrowed fatty acids keep the borrowing food's profile, scaled to its own total fat.
            fat_ratio = (vals['fat'] / extra['fat'] if vals.get('fat') is not None and extra.get('fat') else None)
            for k in ALL:
                if vals.get(k) is None and extra.get(k) is not None:
                    if k in ('sat_fat', 'mono_fat', 'poly_fat') and vals.get('fat') is not None:
                        if fat_ratio is None:
                            continue  # no profile to scale
                        vals[k] = extra[k] * fat_ratio
                    else:
                        vals[k] = extra[k]
        for k in filter(None, (s.strip() for s in opts.get('zero', '').split(','))):
            if vals.get(k) is None:
                vals[k] = 0.0
        if 'replace' in opts:
            # A value of the main source that is clearly wrong (see AUDIT.md) taken from another source instead.
            key, names = parse_replace(opts['replace'])
            other = source_values(self.sources, key, where)
            for k in names:
                if other.get(k) is None:
                    raise SystemExit(f'{where}: replace= source {key} has no {k}')
                vals[k] = other[k]
        if r['unit'] == 'ml':
            d = float(opts.get('density', '1'))
            vals = {k: (v * d if v is not None else None) for k, v in vals.items()}
        elif 'density' in opts:
            raise SystemExit(f'{where}: density is only for ml')
        if vals.get('kcal') is None:
            raise SystemExit(f'{where}: no energy value; add fill=')
        self.values[fid] = vals
        self.missing[fid] = missing
        return vals

    def ingredient(self, ref, where):
        """(values per 100 units, density g per unit) of a recipe line: a catalog food id or a source key."""
        if KEY.match(ref):
            return source_values(self.sources, ref, where), 1.0
        if not ID.match(ref):
            raise SystemExit(f'{where}: bad ingredient {ref!r}')
        if ref not in self.rows:
            raise SystemExit(f'{where}: ingredient {ref!r} is not a food in mapping.txt')
        return self.food(ref), self.density(ref)

    def recipe(self, fid, where):
        """Values per 100 g of the finished dish, the nutrients left unknown, and the recipe lines (kept in
        self.lines / self.finished for recipes.tsv)."""
        recipe = self.recipes[fid]
        total = {k: 0.0 for k in ALL}
        lacking = {k: 0.0 for k in ALL}
        weight = 0.0
        energetic = 0.0  # weight of the ingredients with energy (water and salt do not dilute a missing value)
        lines = []
        for item in recipe['ingredients']:
            if len(item) < 2 or len(item) > 4:
                raise SystemExit(f'{where}: bad ingredient {item!r}')
            ref, amount = item[0], float(item[1])
            if amount <= 0:
                raise SystemExit(f'{where}: {ref} amount must be > 0')
            vals, d = self.ingredient(ref, f'{where} ingredient')
            for k in REQUIRED:
                if vals[k] is None:
                    raise SystemExit(f'{where}: {ref} has no {k}; pick another ingredient')
            grams = amount * d
            for k in ALL:
                if vals[k] is None:
                    if vals['kcal'] > 0:
                        lacking[k] += grams
                else:
                    total[k] += vals[k] * amount / 100
            weight += grams
            if vals['kcal'] > 0:
                energetic += grams
            if len(item) == 4:
                if type(item[3]) is not int or item[3] not in (0, 1):
                    raise SystemExit(f'{where}: {ref} adjustable must be 0 or 1')
                adjustable = item[3]
            else:
                adjustable = 0 if ref in NOT_ADJUSTABLE else 1
            lines.append((ref, amount, adjustable, grams))
        cooked = weight * float(recipe.get('yield', 1.0))
        if cooked <= 0:
            raise SystemExit(f'{where}: empty recipe')
        vals = {k: v * 100 / cooked for k, v in total.items()}
        missing = []
        for k in ALL:
            if lacking[k] > 0 and lacking[k] >= MISSING_SHARE * (energetic or weight):
                if k in VALUES:
                    missing.append(k)
                vals[k] = None
        self.lines[fid] = lines
        self.finished[fid] = cooked
        return vals, missing


def recipe_amounts(cat, fid):
    """{ingredient: (amount in its own unit per 100 units of the finished dish, adjustable)}, merged per ingredient,
    unrounded. With `expand = true` in the recipe, ingredients that are themselves listed recipe dishes are replaced
    by their own ingredients (exact, since a recipe dish's values are the sum of its ingredients)."""
    finished = cat.finished[fid]
    if cat.rows[fid]['unit'] == 'ml':
        finished /= cat.density(fid)
    expand = cat.recipes[fid].get('expand', False)
    merged = {}

    def add(ref, per100, adjustable):
        if ref in merged:
            a, adj = merged[ref]
            merged[ref] = (a + per100, max(adj, adjustable))
        else:
            merged[ref] = (per100, adjustable)

    for ref, amount, adjustable, _ in cat.lines[fid]:
        per100 = amount * 100 / finished
        sub = cat.recipes.get(ref)
        if (expand and ref in cat.rows and cat.rows[ref]['source'] == 'recipe' and sub is not None
                and sub.get('list', True) is not False):
            for sref, (samount, sadj) in recipe_amounts(cat, ref).items():
                add(sref, samount * per100 / 100, sadj if adjustable else 0)
        else:
            add(ref, per100, adjustable)
    return merged


def recipe_rows(cat, fid):
    """recipes.tsv rows of one dish: amount of each ingredient (in its own unit: g, or ml for ml foods) in 100 g of
    the finished dish (100 ml for an ml dish), merged per ingredient, rounded to 1 decimal, rows under 0.05 left
    out."""
    return [[fid, ref, fmt('protein', per100), str(adjustable)]
            for ref, (per100, adjustable) in recipe_amounts(cat, fid).items() if per100 >= 0.05]


def build(sources, fix_descriptions=False):
    """Computes the catalog. Returns (rows, report, foods.tsv text, 97_foods.txt text, recipes.tsv text); writes
    nothing except the description column of mapping.txt with fix_descriptions."""
    sources['_used'] = set()
    with open(RECIPES, 'rb') as f:
        recipes = tomllib.load(f)
    rows = read_mapping()
    seen = set()
    for r in rows:
        where = f'mapping.txt:{r["line"]} {r["id"]}'
        if not ID.match(r['id']) or r['id'] in seen:
            raise SystemExit(f'{where}: bad or duplicate id')
        seen.add(r['id'])
    cat = Catalog(rows, recipes, sources)
    out_rows = []
    report = []
    descriptions = {}
    for r in rows:
        where = f'mapping.txt:{r["line"]} {r["id"]}'
        fid = r['id']
        if r['group'] not in GROUPS:
            raise SystemExit(f'{where}: unknown group {r["group"]}')
        if r['unit'] not in ('g', 'ml'):
            raise SystemExit(f'{where}: unit must be g or ml')
        if r['visual'] not in ('0', '1'):
            raise SystemExit(f'{where}: visual must be 0 or 1')
        if not r['en'] or not r['es']:
            raise SystemExit(f'{where}: missing EN or ES name')
        opts = cat.opts[fid]
        base = opts.get('variant_of', '')
        if base:
            if base not in cat.rows or base == fid:
                raise SystemExit(f'{where}: variant_of {base!r} is not another food')
            if cat.opts[base].get('variant_of'):
                raise SystemExit(f'{where}: variant_of {base!r} is itself a variant')
            if r['visual'] != '0':
                raise SystemExit(f'{where}: a variant must have visual = 0')
        vals = cat.food(fid)
        if r['source'] == 'recipe':
            desc = recipes[fid].get('name', '')
        else:
            desc = sources[r['source']]['description']
        descriptions[r['line']] = desc
        if r['description'] != desc and not fix_descriptions:
            raise SystemExit(f'{where}: description {r["description"]!r} != source {desc!r} '
                             f'(run with --fix-descriptions if the code is right)')
        portions = parse_portions(r['portions'], where)
        if not r['portion'].isdigit() or int(r['portion']) <= 0:
            raise SystemExit(f'{where}: bad default portion')
        if portions and int(r['portion']) not in [a for _, a in portions]:
            raise SystemExit(f'{where}: default portion {r["portion"]} is not one of the portions')
        calc = energy(vals)
        kcal = vals['kcal']
        if abs(calc - kcal) > max(15.0, 0.12 * kcal):
            report.append(f'  {fid:<28} source {kcal:6.0f} kcal, factors {calc:6.0f} ({r["source"]})')
        missing = [k for k in NUTRIENTS if vals.get(k) is None]
        if missing:
            report.append(f'  {fid:<28} missing: {", ".join(missing)}')
        out_rows.append([fid, r['group'], r['unit']] + [fmt(k, vals.get(k)) for k in NUTRIENTS] +
                        [r['portion'], '|'.join(f'{k}:{a}' for k, a in portions), r['visual'], r['source']] +
                        [fmt(k, vals.get(k)) for k in MICRO] + [base])
    unused = sorted(set(recipes) - set(cat.rows))
    if unused:
        raise SystemExit(f'recipes.toml: recipes for foods not in mapping.txt: {unused}')
    recipe_lines = []
    for fid in cat.order:
        if fid not in recipes:
            continue
        recipe = recipes[fid]
        typical = cat.rows[fid]['source'] != 'recipe'
        if typical:
            if recipe.get('list') is False and cat.opts[fid].get('fill') != 'recipe':
                raise SystemExit(f'recipes.toml [{fid}]: a typical recipe (food with its own source) must be listed '
                                 f'or used by fill=recipe')
            cat._stack.append(fid)
            cat.recipe(fid, f'recipes.toml [{fid}] (typical recipe)')
            cat._stack.pop()
        if recipe.get('list', True) is False:
            continue
        for ref, *_ in cat.lines[fid]:
            if KEY.match(ref):
                raise SystemExit(f'recipes.toml [{fid}]: listed recipes use food ids, not {ref} (or set list = false)')
        if not typical:
            # Exact self-check before rounding: the listed ingredients are the dish (contract 3).
            amounts = recipe_amounts(cat, fid)
            dish = cat.food(fid)
            for k in VALUES:
                parts = [(a, cat.food(i)[k]) for i, (a, _) in amounts.items()]
                if dish[k] is None or any(v is None for _, v in parts):
                    continue
                total = sum(a * v / 100 for a, v in parts)
                if abs(total - dish[k]) > 1e-6 * max(1.0, abs(dish[k])):
                    raise SystemExit(f'recipes.toml [{fid}]: {k} of the listed ingredients {total} != {dish[k]}')
        recipe_lines += recipe_rows(cat, fid)
    if fix_descriptions:
        lines = read_text(MAPPING).split('\n')
        for n, desc in descriptions.items():
            cells = lines[n - 1].split('|')
            cells[COLUMNS.index('description')] = f' {desc} '
            lines[n - 1] = '|'.join(cells)
        with open(MAPPING, 'w', encoding='utf-8', newline='\n') as f:
            f.write('\n'.join(lines))
    tsv = '\n'.join(['\t'.join(HEADER)] + ['\t'.join(row) for row in out_rows]) + '\n'
    rtsv = '\n'.join(['\t'.join(RECIPES_HEADER)] + ['\t'.join(row) for row in recipe_lines]) + '\n'
    build.catalog = cat
    return out_rows, report, tsv, names_text(rows), rtsv


def read_classes(classes_csv):
    """Class ids of a model class list: the `id` column, without `_not_food` and rows whose status is dropped."""
    with open(classes_csv, encoding='utf-8', newline='') as f:
        return {r['id'].strip() for r in csv.DictReader(f)
                if r.get('id', '').strip() and (r.get('status') or '').strip().lower() != 'dropped'} - {'_not_food'}


def sync_visual(classes_csv):
    """Sets mapping.txt's visual column from the model's class list; class ids without a row are an error."""
    classes = read_classes(classes_csv)
    rows = read_mapping()
    missing = sorted(classes - {r['id'] for r in rows})
    if missing:
        raise SystemExit(f'add these model classes to mapping.txt first: {missing}')
    variants = sorted(r['id'] for r in rows if r['id'] in classes and 'variant_of=' in r['opts'])
    if variants:
        raise SystemExit(f'these model classes are variants (variants have visual = 0): {variants}')
    lines = read_text(MAPPING).split('\n')
    changed = 0
    for r in rows:
        want = '1' if r['id'] in classes else '0'
        if r['visual'] != want:
            cells = lines[r['line'] - 1].split('|')
            cells[COLUMNS.index('visual')] = f' {want} '
            lines[r['line'] - 1] = '|'.join(cells)
            changed += 1
    with open(MAPPING, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines))
    print(f'{len(classes)} model classes; visual changed on {changed} rows of mapping.txt')


def names_text(rows):
    out = ['# Food catalog names (app/src/main/assets/food/foods.tsv): food_group_<group> and food_<id>.',
           '# Generated by tools/food-db/build.py from tools/food-db/mapping.txt: edit the names there, then rebuild.',
           '', '# Food groups']
    for g, (en, es) in GROUPS.items():
        out.append(f'food_group_{g} = {en} ||| {es}')
    for g in GROUPS:
        group_rows = [r for r in rows if r['group'] == g]
        if not group_rows:
            continue
        out += ['', f'# {GROUPS[g][0]}']
        for r in group_rows:
            out.append(f'food_{r["id"]} = {r["en"]} ||| {r["es"]}')
    return '\n'.join(out) + '\n'


def read_text(path):
    with open(path, encoding='utf-8', newline='') as f:
        return f.read()


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


def check():
    """Problems of the committed outputs against a fresh build from sources_used.tsv (empty list = up to date)."""
    _, _, tsv, names, rtsv = build(load_sources(SNAPSHOT))
    problems = []
    for path, want in ((OUT_TSV, tsv), (OUT_RECIPES, rtsv), (OUT_NAMES, names)):
        have = read_text(path) if os.path.exists(path) else ''
        if have != want:
            problems.append(f'{os.path.relpath(path, REPO)} differs from a fresh build: run tools/food-db/build.py')
    return problems


def coverage(rows):
    """Known values per nutrient column: (column, known, total)."""
    out = []
    for i, k in enumerate(NUTRIENTS + MICRO):
        col = 3 + i if i < len(NUTRIENTS) else 15 + i - len(NUTRIENTS)
        out.append((k, sum(1 for row in rows if row[col] != ''), len(rows)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dir', default=os.environ.get('FOODDB_DIR', 'fooddb-data'),
                    help='work folder of download.py/extract.py (default $FOODDB_DIR or ./fooddb-data)')
    ap.add_argument('--fix-descriptions', action='store_true')
    ap.add_argument('--sync-visual', metavar='CLASSES_CSV',
                    help="first set mapping.txt's visual column from the model's classes.csv")
    ap.add_argument('--check', action='store_true', help='only compare the outputs with a fresh build')
    args = ap.parse_args()
    if args.check:
        problems = check()
        print('\n'.join(problems) or 'foods.tsv, recipes.tsv and 97_foods.txt are up to date')
        sys.exit(1 if problems else 0)
    if args.sync_visual:
        sync_visual(args.sync_visual)
    full = os.path.join(args.dir, 'work', 'nutrients.tsv')
    if os.path.exists(full):
        sources = load_sources(full)
    else:
        print(f'{full} not found: using the committed {os.path.relpath(SNAPSHOT, REPO)}')
        sources = load_sources(SNAPSHOT)
    rows, report, tsv, names, rtsv = build(sources, args.fix_descriptions)
    write(OUT_TSV, tsv)
    write(OUT_RECIPES, rtsv)
    write(OUT_NAMES, names)
    if os.path.exists(full):
        write(SNAPSHOT, snapshot_text(sources, sources['_used']))
    by_group = {}
    by_source = {}
    for row in rows:
        by_group[row[1]] = by_group.get(row[1], 0) + 1
        src = row[14].split(':')[0]
        by_source[src] = by_source.get(src, 0) + 1
    visual = sum(1 for row in rows if row[13] == '1')
    variants = sum(1 for row in rows if row[-1])
    dishes = len({line.split('\t')[0] for line in rtsv.split('\n')[1:] if line})
    print(f'{len(rows)} foods ({visual} visual, {variants} variants) -> {os.path.relpath(OUT_TSV, REPO)}')
    print(f'{dishes} dishes with ingredients -> {os.path.relpath(OUT_RECIPES, REPO)}')
    print('groups: ' + ', '.join(f'{g} {n}' for g, n in sorted(by_group.items())))
    print('sources: ' + ', '.join(f'{s} {n}' for s, n in sorted(by_source.items())))
    print('known values: ' + ', '.join(f'{k} {n}' for k, n, _ in coverage(rows)))
    if report:
        print('check (energy outside max(15 kcal, 12%) of 4P+4C+9F+2fibre+7alcohol, or missing values):')
        print('\n'.join(report))


if __name__ == '__main__':
    main()
