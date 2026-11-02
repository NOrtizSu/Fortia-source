"""Tests of the food-db tools: python -m unittest discover -s tools/food-db -p "test_*.py" (no downloads needed)."""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import build  # noqa: E402
import extract  # noqa: E402
import validate  # noqa: E402


def row(**over):
    r = {'id': 'banana', 'group': 'fruit', 'unit': 'g', 'kcal': '88', 'protein': '1.1', 'carbs': '19.7',
         'sugars': '15.6', 'fat': '0.3', 'sat_fat': '0.1', 'fiber': '2.7', 'salt': '0', 'portion': '120',
         'portions': 'unit:120', 'visual': '0', 'source': 'ciqual:13005'}
    r.update(over)
    return r


class BuildTest(unittest.TestCase):
    def test_format_rounds_half_up_and_drops_zero_decimal(self):
        self.assertEqual(build.fmt('kcal', 87.5), '88')
        self.assertEqual(build.fmt('protein', 1.25), '1.3')
        self.assertEqual(build.fmt('salt', 0.04), '0')
        self.assertEqual(build.fmt('fat', 12.0), '12')
        self.assertEqual(build.fmt('fat', None), '')

    def test_energy_counts_polyols_inside_carbohydrate_at_2_4_kcal(self):
        # CIQUAL dried apricot: 239 kcal with 22.6 g of polyols inside its 59.1 g of carbohydrate.
        v = {'protein': 2.88, 'carbs': 59.1, 'fat': 0.5, 'fiber': 8.3, 'polyols': 22.6, 'organic_acids': 1.98,
             'alcohol': 0}
        self.assertAlmostEqual(build.energy(v), 239, delta=1)

    def test_recipe_is_weighted_and_scaled_by_yield(self):
        sources = {
            'a': {'values': dict.fromkeys(build.NUTRIENTS + build.EXTRA, 0.0) | {'kcal': 100.0, 'protein': 10.0,
                                                                                'carbs': 10.0, 'fat': 2.0}},
            'b': {'values': dict.fromkeys(build.NUTRIENTS + build.EXTRA, 0.0) | {'kcal': 900.0, 'fat': 100.0}},
        }
        recipe = {'ingredients': [['a', 300], ['b', 100]], 'yield': 0.8}
        vals, missing, weight = build.recipe_values(sources, recipe, 'test')
        self.assertEqual(weight, 400)
        self.assertEqual(missing, [])
        # (300 + 900) kcal in 320 g
        self.assertAlmostEqual(vals['kcal'], 1200 / 3.2)
        self.assertAlmostEqual(vals['fat'], (6 + 100) / 3.2)

    def test_portions_only_accept_the_fixed_keys(self):
        self.assertEqual(build.parse_portions('unit:120 slice:30', 'x'), [('unit', 120), ('slice', 30)])
        with self.assertRaises(SystemExit):
            build.parse_portions('ración:120', 'x')
        with self.assertRaises(SystemExit):
            build.parse_portions('unit:1.5', 'x')

    def test_sync_visual_follows_the_class_list(self):
        line = '{id} | fruit | g | {v} | ciqual:1 |  | 100 | unit:100 | A | A | d | n\n'
        with tempfile.TemporaryDirectory() as d:
            mapping = os.path.join(d, 'mapping.txt')
            with open(mapping, 'w', encoding='utf-8') as f:
                f.write('# comment\n' + line.format(id='apple', v=0) + line.format(id='kiwi', v=1))
            classes = os.path.join(d, 'classes.csv')
            with open(classes, 'w', encoding='utf-8') as f:
                f.write('id,en_name\napple,Apple\n_not_food,Not food\n')
            saved = build.MAPPING
            build.MAPPING = mapping
            try:
                build.sync_visual(classes)
                rows = {r['id']: r['visual'] for r in build.read_mapping(mapping)}
                self.assertEqual(rows, {'apple': '1', 'kiwi': '0'})
                with open(classes, 'a', encoding='utf-8') as f:
                    f.write('mango,Mango\n')
                with self.assertRaises(SystemExit):
                    build.sync_visual(classes)
            finally:
                build.MAPPING = saved

    def test_options(self):
        self.assertEqual(build.parse_opts('fill=fdc:1; density=1.03', 'x'), {'fill': 'fdc:1', 'density': '1.03'})
        with self.assertRaises(SystemExit):
            build.parse_opts('salt=1', 'x')


class ExtractTest(unittest.TestCase):
    def test_usda_salt_is_sodium_times_2_5_and_carbs_are_available_carbohydrate(self):
        row = extract.fdc_row({'kcal': 116, 'protein': 9, 'carb_diff': 20.1, 'fiber': 7.9, 'sugars': 1.8,
                               'fat': 0.4, 'sodium': 238})
        self.assertAlmostEqual(row['salt'], 0.595)
        self.assertAlmostEqual(row['carbs'], 12.2)  # by difference includes fibre: subtract it
        self.assertEqual(row['kcal'], 116)

    def test_usda_fallbacks(self):
        # Foundation foods: Atwater energy, carbohydrate by summation (no fibre inside), NLEA fat/sugars.
        row = extract.fdc_row({'kcal_specific': 52, 'kcal_general': 55, 'carb_sum': 11.5, 'fiber': 2.4,
                               'fat_nlea': 0.2, 'sugars_nlea': 10.3})
        self.assertEqual((row['kcal'], row['carbs'], row['fat'], row['sugars']), (52, 11.5, 0.2, 10.3))
        self.assertIsNone(row['salt'])

    def test_usda_carbs_never_below_sugars(self):
        row = extract.fdc_row({'carb_diff': 4.28, 'fiber': 2.9, 'sugars': 1.78})
        self.assertAlmostEqual(row['carbs'], 1.78)

    def test_ciqual_values(self):
        self.assertEqual(extract.parse_ciqual_value('12,5'), 12.5)
        self.assertEqual(extract.parse_ciqual_value('traces'), 0.0)
        self.assertEqual(extract.parse_ciqual_value('< 0,5'), 0.0)
        self.assertIsNone(extract.parse_ciqual_value('-'))
        self.assertIsNone(extract.parse_ciqual_value(''))
        self.assertAlmostEqual(extract.ciqual_row({'sodium': 400, 'salt': 1.02})['salt'], 1.0)
        self.assertEqual(extract.ciqual_row({'salt': 1.02})['salt'], 1.02)


class ValidateTest(unittest.TestCase):
    def check(self, *rows):
        return validate.check_foods(list(enumerate(rows, 2)))

    def test_good_row(self):
        errors, warnings = self.check(row())
        self.assertEqual(errors, [])

    def test_bad_values(self):
        cases = {
            'duplicate id': [row(), row()],
            'bad group': [row(group='fruits')],
            'is not a integer': [row(kcal='88.0')],
            'max 1 decimal': [row(protein='1.25')],
            'sugars > carbs': [row(sugars='25')],
            'saturated fat > fat': [row(sat_fat='1')],
            'far from': [row(kcal='300')],
            'bad portion': [row(portions='unit:120|ración:200')],
            'default portion is not one of the portions': [row(portion='100')],
            'bad source': [row(source='bedca:123')],
        }
        for message, rows in cases.items():
            errors, _ = self.check(*rows)
            self.assertTrue(any(message in e for e in errors), f'{message}: {errors}')

    def test_drinks_may_have_alcohol_energy(self):
        beer = row(id='beer', group='drinks', unit='ml', kcal='39', protein='0.4', carbs='3.1', sugars='0',
                   fat='0', sat_fat='0', fiber='0', portion='330', portions='can:330')
        self.assertEqual(self.check(beer)[0], [])

    def test_only_alcoholic_drinks_may_exceed_their_macronutrient_energy(self):
        juice = row(id='orange_juice', group='drinks', unit='ml', kcal='90', protein='0.7', carbs='9.6',
                    sugars='8', fat='0.2', sat_fat='0', fiber='0.2', portion='200', portions='glass:200')
        errors, _ = self.check(juice)
        self.assertTrue(any('far from' in e for e in errors), errors)

    def test_names_and_classes(self):
        rows = [(2, row()), (3, row(id='paella', group='dishes', visual='1'))]
        with tempfile.TemporaryDirectory() as d:
            names = os.path.join(d, 'names.txt')
            with open(names, 'w', encoding='utf-8') as f:
                f.write('# c\n' + ''.join(f'food_group_{g} = G ||| G\n' for g in validate.GROUPS))
                f.write('food_banana = Banana ||| Plátano\nfood_kiwi = Kiwi ||| Kiwi\n')
            errors, _ = validate.check_names(rows, names)
            self.assertIn('97_foods.txt: no food_paella', errors)
            self.assertIn('97_foods.txt: food_kiwi has no food in foods.tsv', errors)
            classes = os.path.join(d, 'classes.csv')
            with open(classes, 'w', encoding='utf-8') as f:
                f.write('id,en_name\nbanana,Banana\npaella,Paella\nsushi,Sushi\n_not_food,Not food\n')
            errors, n = validate.check_classes(rows, classes)
            self.assertEqual(n, 3)
            self.assertIn('class banana: visual must be 1', errors)
            self.assertIn('class sushi: not in foods.tsv', errors)


class RepoFilesTest(unittest.TestCase):
    def test_generated_catalog_is_valid(self):
        rows, errors = validate.read_foods(validate.FOODS)
        e, _ = validate.check_foods(rows)
        errors += e
        e, _ = validate.check_names(rows, validate.NAMES)
        errors += e
        if os.path.exists(validate.CLASSES):
            errors += validate.check_classes(rows, validate.CLASSES)[0]
        self.assertEqual(errors, [])
        self.assertGreaterEqual(len(rows), 250)

    def test_outputs_equal_a_fresh_build_from_the_committed_sources(self):
        self.assertEqual(build.check(), [])

    def test_mapping_is_well_formed(self):
        rows = build.read_mapping()
        ids = [r['id'] for r in rows]
        self.assertEqual(len(ids), len(set(ids)))
        for r in rows:
            self.assertIn(r['group'], build.GROUPS, r['id'])
            self.assertTrue(r['description'], f'{r["id"]}: run build.py --fix-descriptions')


if __name__ == '__main__':
    unittest.main()
