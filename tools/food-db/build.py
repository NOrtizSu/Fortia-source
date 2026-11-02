"""Builds the app's food catalog and its names from mapping.txt, recipes.toml and the source tables.

    python tools/food-db/build.py [--dir DIR] [--fix-descriptions] [--sync-visual CLASSES_CSV]
    python tools/food-db/build.py --check

Writes app/src/main/assets/food/foods.tsv and tools/i18n/src/master/97_foods.txt, then prints the energy check.

Source values: with <dir>/work/nutrients.tsv (download.py + extract.py; --dir or $FOODDB_DIR, default
./fooddb-data) the build reads the full tables and refreshes sources_used.tsv, the committed copy of the USDA/CIQUAL
rows that mapping.txt and recipes.toml use. Without it, the build reads sources_used.tsv, so mapping, names,
portions and recipes can be changed without downloading anything (a new source code needs the full tables).

--check rebuilds from sources_used.tsv in memory and fails if foods.tsv or 97_foods.txt differ from it.
--fix-descriptions rewrites the description column of mapping.txt from the sources (use it after changing a source
code, then review the diff). --sync-visual sets the visual column from the model's class list
(tools/food-model/classes.csv) and fails if a class has no row yet.
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
OUT_NAMES = os.path.join(REPO, 'tools', 'i18n', 'src', 'master', '97_foods.txt')

HEADER = ['id', 'group', 'unit', 'kcal', 'protein', 'carbs', 'sugars', 'fat', 'sat_fat', 'fiber', 'salt', 'portion',
          'portions', 'visual', 'source']
NUTRIENTS = ['kcal', 'protein', 'carbs', 'sugars', 'fat', 'sat_fat', 'fiber', 'salt']
EXTRA = ['alcohol', 'polyols', 'organic_acids']
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
ID = re.compile(r'^[a-z0-9_]+$')
KEY = re.compile(r'^(fdc|ciqual):\d+$')


def load_sources(path):
    """nutrients.tsv (extract.py) or sources_used.tsv: key -> description, dataset, values, raw line."""
    if not os.path.exists(path):
        raise SystemExit(f'missing {path}: run tools/food-db/download.py and extract.py first')
    out = {}
    with open(path, encoding='utf-8', newline='') as f:
        header = f.readline().rstrip('\n')
        f.seek(0)
        lines = f.read().split('\n')[1:]
    cols = header.split('\t')
    for line in filter(None, lines):
        r = dict(zip(cols, line.split('\t')))
        vals = {k: (float(r[k]) if r[k] != '' else None) for k in NUTRIENTS + EXTRA}
        out[r['key']] = {'description': r['description'], 'dataset': r['dataset'], 'values': vals, 'line': line}
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
        if k not in ('fill', 'density'):
            raise SystemExit(f'{where}: unknown option {k!r}')
        opts[k] = v
    return opts


def source_values(sources, key, where):
    if key not in sources or key.startswith('_'):
        raise SystemExit(f'{where}: unknown source {key} (a new code needs the full tables: see --dir)')
    sources.setdefault('_used', set()).add(key)
    return dict(sources[key]['values'])


def recipe_values(sources, recipe, where):
    total = {k: 0.0 for k in NUTRIENTS + EXTRA}
    weight = 0.0
    missing = set()
    for item in recipe['ingredients']:
        if len(item) < 2:
            raise SystemExit(f'{where}: bad ingredient {item!r}')
        key, grams = item[0], float(item[1])
        vals = source_values(sources, key, f'{where} ingredient')
        for k in ('kcal', 'protein', 'carbs', 'fat'):
            if vals[k] is None:
                raise SystemExit(f'{where}: {key} has no {k}; pick another ingredient')
        for k in total:
            if vals[k] is None:
                if k in NUTRIENTS and grams >= 5:
                    missing.add(f'{key}.{k}')
                continue
            total[k] += vals[k] * grams / 100
        weight += grams
    cooked = weight * float(recipe.get('yield', 1.0))
    if cooked <= 0:
        raise SystemExit(f'{where}: empty recipe')
    return {k: v * 100 / cooked for k, v in total.items()}, sorted(missing), weight


def energy(v):
    """EU Regulation 1169/2011 energy factors. CIQUAL's carbohydrate includes polyols (as EU labels do)."""
    g = lambda k: v.get(k) or 0.0
    sugars_starch = max(0.0, g('carbs') - g('polyols'))
    return (4 * g('protein') + 4 * sugars_starch + 9 * g('fat') + 2 * g('fiber') + 7 * g('alcohol')
            + 2.4 * g('polyols') + 3 * g('organic_acids'))


def fmt(k, v):
    if v is None:
        return ''
    if k == 'kcal':
        return str(int(math.floor(v + 0.5)))
    s = f'{math.floor(v * 10 + 0.5) / 10:.1f}'
    return s[:-2] if s.endswith('.0') else s


def parse_portions(text, where):
    out = []
    for part in text.split():
        m = re.match(r'^([a-z_]+):(\d+)$', part)
        if not m or m.group(1) not in PORTION_KEYS:
            raise SystemExit(f'{where}: bad portion {part!r}')
        out.append((m.group(1), int(m.group(2))))
    return out


def build(sources, fix_descriptions=False):
    """Computes the catalog. Returns (rows, report, foods.tsv text, 97_foods.txt text); writes nothing except the
    description column of mapping.txt with fix_descriptions."""
    sources['_used'] = set()
    with open(RECIPES, 'rb') as f:
        recipes = tomllib.load(f)
    rows = read_mapping()
    seen = set()
    out_rows = []
    report = []
    descriptions = {}
    used_recipes = set()
    for r in rows:
        where = f'mapping.txt:{r["line"]} {r["id"]}'
        fid = r['id']
        if not ID.match(fid) or fid in seen:
            raise SystemExit(f'{where}: bad or duplicate id')
        seen.add(fid)
        if r['group'] not in GROUPS:
            raise SystemExit(f'{where}: unknown group {r["group"]}')
        if r['unit'] not in ('g', 'ml'):
            raise SystemExit(f'{where}: unit must be g or ml')
        if r['visual'] not in ('0', '1'):
            raise SystemExit(f'{where}: visual must be 0 or 1')
        if not r['en'] or not r['es']:
            raise SystemExit(f'{where}: missing EN or ES name')
        opts = parse_opts(r['opts'], where)
        missing = []
        if r['source'] == 'recipe':
            if fid not in recipes:
                raise SystemExit(f'{where}: no [{fid}] in recipes.toml')
            vals, missing, _ = recipe_values(sources, recipes[fid], where)
            desc = recipes[fid].get('name', '')
            used_recipes.add(fid)
        elif KEY.match(r['source']):
            vals = source_values(sources, r['source'], where)
            desc = sources[r['source']]['description']
        else:
            raise SystemExit(f'{where}: bad source {r["source"]!r}')
        if 'fill' in opts:
            extra = source_values(sources, opts['fill'], where)
            for k in NUTRIENTS + EXTRA:
                if vals.get(k) is None and extra.get(k) is not None:
                    vals[k] = extra[k]
        if r['unit'] == 'ml':
            d = float(opts.get('density', '1'))
            vals = {k: (v * d if v is not None else None) for k, v in vals.items()}
        elif 'density' in opts:
            raise SystemExit(f'{where}: density is only for ml')
        if vals.get('kcal') is None:
            raise SystemExit(f'{where}: no energy value; add fill=')
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
        for k in NUTRIENTS:
            if vals.get(k) is None:
                missing.append(k)
        out_rows.append([fid, r['group'], r['unit']] + [fmt(k, vals.get(k)) for k in NUTRIENTS] +
                        [r['portion'], '|'.join(f'{k}:{a}' for k, a in portions), r['visual'],
                         r['source']])
        if missing:
            report.append(f'  {fid:<28} missing: {", ".join(missing)}')
    unused = sorted(set(recipes) - used_recipes)
    if unused:
        raise SystemExit(f'recipes.toml: recipes not used by mapping.txt: {unused}')
    if fix_descriptions:
        lines = open(MAPPING, encoding='utf-8').read().split('\n')
        for n, desc in descriptions.items():
            cells = lines[n - 1].split('|')
            cells[COLUMNS.index('description')] = f' {desc} '
            lines[n - 1] = '|'.join(cells)
        with open(MAPPING, 'w', encoding='utf-8', newline='\n') as f:
            f.write('\n'.join(lines))
    tsv = '\n'.join(['\t'.join(HEADER)] + ['\t'.join(row) for row in out_rows]) + '\n'
    return out_rows, report, tsv, names_text(rows)


def sync_visual(classes_csv):
    """Sets mapping.txt's visual column from the model's class list; class ids without a row are an error."""
    with open(classes_csv, encoding='utf-8', newline='') as f:
        classes = {r['id'].strip() for r in csv.DictReader(f) if r.get('id', '').strip()} - {'_not_food'}
    rows = read_mapping()
    missing = sorted(classes - {r['id'] for r in rows})
    if missing:
        raise SystemExit(f'add these model classes to mapping.txt first: {missing}')
    lines = open(MAPPING, encoding='utf-8').read().split('\n')
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


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


def check():
    """Problems of the committed outputs against a fresh build from sources_used.tsv (empty list = up to date)."""
    _, _, tsv, names = build(load_sources(SNAPSHOT))
    problems = []
    for path, want in ((OUT_TSV, tsv), (OUT_NAMES, names)):
        have = open(path, encoding='utf-8', newline='').read() if os.path.exists(path) else ''
        if have != want:
            problems.append(f'{os.path.relpath(path, REPO)} differs from a fresh build: run tools/food-db/build.py')
    return problems


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
        print('\n'.join(problems) or 'foods.tsv and 97_foods.txt are up to date')
        sys.exit(1 if problems else 0)
    if args.sync_visual:
        sync_visual(args.sync_visual)
    full = os.path.join(args.dir, 'work', 'nutrients.tsv')
    if os.path.exists(full):
        sources = load_sources(full)
    else:
        print(f'{full} not found: using the committed {os.path.relpath(SNAPSHOT, REPO)}')
        sources = load_sources(SNAPSHOT)
    rows, report, tsv, names = build(sources, args.fix_descriptions)
    write(OUT_TSV, tsv)
    write(OUT_NAMES, names)
    if os.path.exists(full):
        write(SNAPSHOT, snapshot_text(sources, sources['_used']))
    by_group = {}
    by_source = {}
    for row in rows:
        by_group[row[1]] = by_group.get(row[1], 0) + 1
        src = row[-1].split(':')[0]
        by_source[src] = by_source.get(src, 0) + 1
    visual = sum(1 for row in rows if row[-2] == '1')
    print(f'{len(rows)} foods ({visual} visual) -> {os.path.relpath(OUT_TSV, REPO)}')
    print('groups: ' + ', '.join(f'{g} {n}' for g, n in sorted(by_group.items())))
    print('sources: ' + ', '.join(f'{s} {n}' for s, n in sorted(by_source.items())))
    if report:
        print('check (energy outside max(15 kcal, 12%) of 4P+4C+9F+2fibre+7alcohol, or missing values):')
        print('\n'.join(report))


if __name__ == '__main__':
    main()
