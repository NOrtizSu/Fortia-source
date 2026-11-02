"""Downloads the source tables (USDA FoodData Central CSV releases and CIQUAL 2025 XML) into <dir>/raw.

    python tools/food-db/download.py [--dir DIR]   (DIR: --dir, $FOODDB_DIR or ./fooddb-data)

Files already present with the expected SHA-256 are skipped; a different checksum is an error (a new release:
check it and update FILES). Nothing here goes into git.
"""
import argparse
import hashlib
import os
import sys
import urllib.request

FDC = 'https://fdc.nal.usda.gov/fdc-datasets/'
# CIQUAL 2025, dataset doi:10.57745/RDMHWY on recherche.data.gouv.fr (Etalab Open Licence 2.0).
CIQUAL = 'https://entrepot.recherche.data.gouv.fr/api/access/datafile/'

FILES = [
    # (file name, url, sha256)
    ('FoodData_Central_foundation_food_csv_2026-04-30.zip', FDC + 'FoodData_Central_foundation_food_csv_2026-04-30.zip',
     '70457ee9d9342f43bda2010318c85f04210c689fdeb9cd2da4c513b0e8dbc655'),
    ('FoodData_Central_sr_legacy_food_csv_2018-04.zip', FDC + 'FoodData_Central_sr_legacy_food_csv_2018-04.zip',
     'b80817294b8850530aaedf2e515c02593b1824f763a0ff356e5c2081643e6fd0'),
    ('FoodData_Central_survey_food_csv_2022-10-28.zip', FDC + 'FoodData_Central_survey_food_csv_2022-10-28.zip',
     'e9a10d13a975bf45087f70306fc6ce1077fe538599843f028329a8b11c37b958'),
    ('alim_2025_11_03.xml', CIQUAL + '666252', 'e0b1de25b3039028205e9d54a96892e403e1b313c2efeb41180fabe132627478'),
    ('alim_grp_2025_11_03.xml', CIQUAL + '666250', 'e216928be1001aed15ba1b120405b70a98145da2a1a76026d6e33542bd5e39dc'),
    ('compo_2025_11_03.xml', CIQUAL + '666249', '8c46a9032ece4eab4ffccc9dfcb0c490ec2f416aa664cc1ee013b241c6bdd4af'),
    ('const_2025_11_03.xml', CIQUAL + '666246', '3a231ea3e9836e4ac0b6ff94511e010210b2523d1b8b486c6b29e211066ba3f3'),
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dir', default=os.environ.get('FOODDB_DIR', 'fooddb-data'),
                    help='work folder (default $FOODDB_DIR or ./fooddb-data)')
    args = ap.parse_args()
    raw = os.path.join(args.dir, 'raw')
    os.makedirs(raw, exist_ok=True)
    failed = False
    for name, url, digest in FILES:
        path = os.path.join(raw, name)
        if not os.path.exists(path):
            print(f'downloading {name}', file=sys.stderr)
            req = urllib.request.Request(url, headers={'User-Agent': 'Fortia-food-db/1.0'})
            with urllib.request.urlopen(req, timeout=600) as r, open(path + '.part', 'wb') as f:
                while block := r.read(1 << 20):
                    f.write(block)
            os.replace(path + '.part', path)
        got = sha256(path)
        if got != digest:
            print(f'{name}: sha256 {got} != expected {digest}', file=sys.stderr)
            failed = True
        else:
            print(f'{name}: ok', file=sys.stderr)
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
