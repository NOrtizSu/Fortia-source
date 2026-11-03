"""Turns the raw USDA FoodData Central and CIQUAL downloads into two compact tables.

    python tools/food-db/extract.py [--dir DIR]   (DIR: --dir, $FOODDB_DIR or ./fooddb-data)

Reads <dir>/raw (see download.py) and writes:
  <dir>/work/nutrients.tsv  key, dataset, description, description_fr, then per 100 g: kcal protein carbs sugars fat
                            sat_fat fiber salt alcohol water polyols organic_acids, then mono_fat poly_fat (g)
                            cholesterol potassium calcium iron magnesium zinc (mg) vit_a (ug RAE) vit_c (mg)
                            vit_d (ug) vit_e (mg alpha-tocopherol) vit_b12 (ug) folate (ug DFE)
                            (empty = not in the source)
  <dir>/work/portions.tsv   key, portion description, grams (USDA household measures)

Keys: `fdc:<fdc_id>` (datasets foundation, sr_legacy, fndds) and `ciqual:<alim_code>` (dataset ciqual).
`carbs` is available carbohydrate (EU style): USDA "carbohydrate, by difference" minus dietary fibre (never below
the sugars), or USDA "carbohydrate, by summation"; CIQUAL "glucides". Salt = sodium x 2.5. CIQUAL "< x" and
"traces" = 0.

Vitamins, when the source lacks the summary value: vitamin A RAE = retinol + beta-carotene/12 (+ alpha-carotene
and beta-cryptoxanthin/24, USDA); vitamin D = D2 + D3, or IU/40 (USDA); folate DFE = food folate + 1.7 x folic
acid, or total folate (foods without folic acid); USDA foods with folic acid (US flour and cereal fortification,
which European foods do not have) keep only their food folate; vitamin E = alpha-tocopherol (CIQUAL "vitamin E"
otherwise).
CIQUAL's "vitamin A activity, retinol equivalent" is retinol + beta-carotene/12, i.e. RAE.
"""
import argparse
import csv
import io
import os
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

FIELDS = ['kcal', 'protein', 'carbs', 'sugars', 'fat', 'sat_fat', 'fiber', 'salt', 'alcohol', 'water', 'polyols',
          'organic_acids']
# Appended after FIELDS (contract 2 of foods.tsv v2, same names and units).
MICRO = ['mono_fat', 'poly_fat', 'cholesterol', 'potassium', 'calcium', 'iron', 'magnesium', 'zinc', 'vit_a', 'vit_c',
         'vit_d', 'vit_e', 'vit_b12', 'folate']
# `fortified`: 1 when the food has added folic acid (US enriched flour and cereals: their iron is enriched too).
ALL_FIELDS = FIELDS + MICRO + ['fortified']

FDC_ZIPS = {
    'foundation': 'FoodData_Central_foundation_food_csv_2026-04-30.zip',
    'sr_legacy': 'FoodData_Central_sr_legacy_food_csv_2018-04.zip',
    'fndds': 'FoodData_Central_survey_food_csv_2022-10-28.zip',
}
# Foods of these data types are kept (the Foundation zip also holds its lab samples).
FDC_TYPES = {'foundation_food', 'sr_legacy_food', 'survey_fndds_food'}

# Nutrient ids (and the older nutrient numbers that the FNDDS 2019-2020 file uses) -> our names.
FDC_NUTRIENTS = {
    '1008': 'kcal', '208': 'kcal',
    '2048': 'kcal_specific', '958': 'kcal_specific',
    '2047': 'kcal_general', '957': 'kcal_general',
    '1003': 'protein', '203': 'protein',
    '1005': 'carb_diff', '205': 'carb_diff',
    '1050': 'carb_sum', '205.2': 'carb_sum',
    '2000': 'sugars', '269': 'sugars',
    '1063': 'sugars_nlea', '269.3': 'sugars_nlea',
    '1004': 'fat', '204': 'fat',
    '1085': 'fat_nlea', '298': 'fat_nlea',
    '1258': 'sat_fat', '606': 'sat_fat',
    '1079': 'fiber', '291': 'fiber',
    '1093': 'sodium', '307': 'sodium',
    '1018': 'alcohol', '221': 'alcohol',
    '1051': 'water', '255': 'water',
    '1292': 'mono_fat', '645': 'mono_fat',
    '1293': 'poly_fat', '646': 'poly_fat',
    '1253': 'cholesterol', '601': 'cholesterol',
    '1092': 'potassium', '306': 'potassium',
    '1087': 'calcium', '301': 'calcium',
    '1089': 'iron', '303': 'iron',
    '1090': 'magnesium', '304': 'magnesium',
    '1095': 'zinc', '309': 'zinc',
    '1106': 'vit_a', '320': 'vit_a',
    '1105': 'retinol', '319': 'retinol',
    '1107': 'beta_carotene', '321': 'beta_carotene',
    '1108': 'alpha_carotene', '322': 'alpha_carotene',
    '1120': 'cryptoxanthin', '334': 'cryptoxanthin',
    '1162': 'vit_c', '401': 'vit_c',
    '1114': 'vit_d', '328': 'vit_d',
    '1111': 'vit_d2', '325': 'vit_d2',
    '1112': 'vit_d3', '326': 'vit_d3',
    '1110': 'vit_d_iu', '324': 'vit_d_iu',
    '1109': 'vit_e', '323': 'vit_e',
    '1178': 'vit_b12', '418': 'vit_b12',
    '1190': 'folate_dfe', '435': 'folate_dfe',
    '1187': 'folate_food', '432': 'folate_food',
    '1186': 'folic_acid', '431': 'folic_acid',
    '1177': 'folate_total', '417': 'folate_total',
}

CIQUAL = {
    '328': 'kcal', '25000': 'protein', '31000': 'carbs', '32000': 'sugars', '40000': 'fat', '40302': 'sat_fat',
    '34100': 'fiber', '10110': 'sodium', '10004': 'salt', '60000': 'alcohol', '400': 'water', '34000': 'polyols',
    '65000': 'organic_acids',
    '40303': 'mono_fat', '40304': 'poly_fat', '75100': 'cholesterol', '10190': 'potassium', '10200': 'calcium',
    '10260': 'iron', '10120': 'magnesium', '10300': 'zinc', '51104': 'vit_a', '51200': 'retinol',
    '51330': 'beta_carotene', '55100': 'vit_c', '52100': 'vit_d', '71010': 'vit_e', '53100': 'vit_e_other',
    '56600': 'vit_b12', '56702': 'folate_dfe', '56704': 'folate_food', '56708': 'folic_acid', '56700': 'folate_total',
}


def _sum(*vals):
    """Sum of the known values, None when none is known."""
    known = [x for x in vals if x is not None]
    return sum(known) if known else None


def folate_dfe(v):
    """Folate in ug DFE: the source's DFE, else food folate + 1.7 x folic acid, else total folate."""
    if v.get('folate_dfe') is not None:
        return v['folate_dfe']
    if v.get('folate_food') is not None:
        return v['folate_food'] + 1.7 * (v.get('folic_acid') or 0.0)
    return v.get('folate_total')


def micro_fdc(v):
    out = {k: v.get(k) for k in ('mono_fat', 'poly_fat', 'cholesterol', 'potassium', 'calcium', 'iron', 'magnesium',
                                 'zinc', 'vit_c', 'vit_e', 'vit_b12')}
    out['vit_a'] = v['vit_a'] if v.get('vit_a') is not None else (
        None if v.get('retinol') is None and v.get('beta_carotene') is None else
        (v.get('retinol') or 0.0) + (v.get('beta_carotene') or 0.0) / 12
        + ((v.get('alpha_carotene') or 0.0) + (v.get('cryptoxanthin') or 0.0)) / 24)
    if v.get('vit_d') is not None:
        out['vit_d'] = v['vit_d']
    elif v.get('vit_d2') is not None or v.get('vit_d3') is not None:
        out['vit_d'] = _sum(v.get('vit_d2'), v.get('vit_d3'))
    else:
        out['vit_d'] = v['vit_d_iu'] / 40 if v.get('vit_d_iu') is not None else None
    folic = v.get('folic_acid') or 0
    if folic > 0:
        # US flour and cereals are fortified with folic acid, European ones are not: keep the food's own folate.
        if v.get('folate_food') is not None:
            out['folate'] = v['folate_food']
        elif v.get('folate_total') is not None:
            out['folate'] = max(0.0, v['folate_total'] - folic)
        else:
            out['folate'] = max(0.0, v['folate_dfe'] - 1.7 * folic) if v.get('folate_dfe') is not None else None
    else:
        out['folate'] = folate_dfe(v)
    out['fortified'] = None if v.get('folic_acid') is None else (1 if folic > 0 else 0)
    return out


def micro_ciqual(v):
    out = {k: v.get(k) for k in ('mono_fat', 'poly_fat', 'cholesterol', 'potassium', 'calcium', 'iron', 'magnesium',
                                 'zinc', 'vit_c', 'vit_d', 'vit_b12')}
    out['vit_a'] = v['vit_a'] if v.get('vit_a') is not None else (
        None if v.get('retinol') is None and v.get('beta_carotene') is None else
        (v.get('retinol') or 0.0) + (v.get('beta_carotene') or 0.0) / 12)
    out['vit_e'] = v['vit_e'] if v.get('vit_e') is not None else v.get('vit_e_other')
    out['folate'] = folate_dfe(v)
    out['fortified'] = None if v.get('folic_acid') is None else (1 if v['folic_acid'] > 0 else 0)
    return out


def fmt(v):
    if v is None:
        return ''
    return f'{v:.3f}'.rstrip('0').rstrip('.') if v != int(v) else str(int(v))


def open_csv(zf, prefix, name):
    for info in zf.infolist():
        if info.filename.endswith('/' + name) or info.filename == name:
            return csv.DictReader(io.TextIOWrapper(zf.open(info), encoding='utf-8', newline=''))
    raise SystemExit(f'{prefix}: {name} not in zip')


def fdc_row(v):
    """Our per-100 g row from one USDA food's nutrient amounts (FDC_NUTRIENTS names; sodium in mg)."""
    kcal = v.get('kcal', v.get('kcal_specific', v.get('kcal_general')))
    fat = v.get('fat', v.get('fat_nlea'))
    sugars = v.get('sugars', v.get('sugars_nlea'))
    fiber = v.get('fiber')
    if 'carb_diff' in v:
        carbs = max(0.0, v['carb_diff'] - (fiber or 0.0))  # by difference includes fibre
    else:
        carbs = v.get('carb_sum')  # by summation = sugars + starch
    if carbs is not None and sugars is not None and sugars > carbs:
        carbs = sugars  # "by difference" minus fibre can come out below the measured sugars
    sodium = v.get('sodium')
    salt = sodium * 2.5 / 1000 if sodium is not None else None
    return {'kcal': kcal, 'protein': v.get('protein'), 'carbs': carbs, 'sugars': sugars, 'fat': fat,
            'sat_fat': v.get('sat_fat'), 'fiber': fiber, 'salt': salt, 'alcohol': v.get('alcohol'),
            'water': v.get('water'), 'polyols': None, 'organic_acids': None} | micro_fdc(v)


def ciqual_row(v):
    """Our per-100 g row from one CIQUAL food's parsed constituents (CIQUAL names; sodium in mg)."""
    row = {k: v.get(k) for k in FIELDS}
    row['salt'] = v['sodium'] * 2.5 / 1000 if 'sodium' in v else v.get('salt')
    return row | micro_ciqual(v)


def extract_fdc(raw, out_rows, out_portions):
    for dataset, zname in FDC_ZIPS.items():
        path = os.path.join(raw, zname)
        if not os.path.exists(path):
            raise SystemExit(f'missing {path} (run download.py)')
        zf = zipfile.ZipFile(path)
        foods = {}
        for r in open_csv(zf, zname, 'food.csv'):
            if r['data_type'] in FDC_TYPES:
                foods[r['fdc_id']] = r['description']
        values = {k: {} for k in foods}
        units = {}
        for r in open_csv(zf, zname, 'measure_unit.csv'):
            units[r['id']] = r['name']
        for r in open_csv(zf, zname, 'food_nutrient.csv'):
            v = values.get(r['fdc_id'])
            if v is None:
                continue
            name = FDC_NUTRIENTS.get(r['nutrient_id'])
            if name and r['amount'] != '':
                v[name] = float(r['amount'])
        for fid, desc in foods.items():
            out_rows.append((f'fdc:{fid}', dataset, desc, '', fdc_row(values[fid])))
        for r in open_csv(zf, zname, 'food_portion.csv'):
            if r['fdc_id'] not in foods:
                continue
            try:
                grams = float(r['gram_weight'] or 0)
            except ValueError:
                continue
            if grams <= 0:
                continue
            unit = units.get(r['measure_unit_id'], '')
            if unit == 'undetermined':
                unit = ''
            parts = [r.get('amount') or '', unit, r.get('portion_description') or '', r.get('modifier') or '']
            # FNDDS puts the whole text in portion_description and a numeric code in modifier.
            if r.get('portion_description'):
                parts = [parts[2]]
            text = re.sub(r'\s+', ' ', ' '.join(p for p in parts if p)).strip()
            out_portions.append((f'fdc:{r["fdc_id"]}', text, grams))
        print(f'{dataset}: {len(foods)} foods', file=sys.stderr)


def parse_ciqual_value(text):
    t = (text or '').strip().replace(',', '.')
    if t in ('', '-'):
        return None
    if t.lower() == 'traces':
        return 0.0
    if re.match(r'^<\s*[0-9.]+$', t):
        return 0.0  # below the limit of quantification: 0, as EU labels declare it
    try:
        return float(t)
    except ValueError:
        return None


def iter_xml(path, tag):
    for _, el in ET.iterparse(path, events=('end',)):
        if el.tag == tag:
            yield {c.tag: (c.text or '').strip() for c in el}
            el.clear()


def extract_ciqual(raw, out_rows):
    alim = os.path.join(raw, 'alim_2025_11_03.xml')
    compo = os.path.join(raw, 'compo_2025_11_03.xml')
    for p in (alim, compo):
        if not os.path.exists(p):
            raise SystemExit(f'missing {p} (run download.py)')
    foods = {}
    for a in iter_xml(alim, 'ALIM'):
        foods[a['alim_code']] = (a.get('alim_nom_eng', ''), a.get('alim_nom_fr', ''))
    values = {k: {} for k in foods}
    for c in iter_xml(compo, 'COMPO'):
        name = CIQUAL.get(c.get('const_code'))
        if name and c.get('alim_code') in values:
            v = parse_ciqual_value(c.get('teneur'))
            if v is not None:
                values[c['alim_code']][name] = v
    for code, (en, fr) in foods.items():
        out_rows.append((f'ciqual:{code}', 'ciqual', en, fr, ciqual_row(values[code])))
    print(f'ciqual: {len(foods)} foods', file=sys.stderr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dir', default=os.environ.get('FOODDB_DIR', 'fooddb-data'),
                    help='work folder (default $FOODDB_DIR or ./fooddb-data)')
    args = ap.parse_args()
    raw = os.path.join(args.dir, 'raw')
    work = os.path.join(args.dir, 'work')
    os.makedirs(work, exist_ok=True)
    rows, portions = [], []
    extract_fdc(raw, rows, portions)
    extract_ciqual(raw, rows)
    with open(os.path.join(work, 'nutrients.tsv'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\t'.join(['key', 'dataset', 'description', 'description_fr'] + ALL_FIELDS) + '\n')
        for key, ds, desc, fr, row in rows:
            clean = lambda s: s.replace('\t', ' ').replace('\n', ' ')
            f.write('\t'.join([key, ds, clean(desc), clean(fr)] + [fmt(row[k]) for k in ALL_FIELDS]) + '\n')
    with open(os.path.join(work, 'portions.tsv'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('key\tportion\tgrams\n')
        for key, text, grams in portions:
            f.write(f'{key}\t{text}\t{fmt(grams)}\n')
    print(f'{len(rows)} foods, {len(portions)} portions -> {work}', file=sys.stderr)


if __name__ == '__main__':
    main()
