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
    """A foods.tsv v2 row (strings) for validate.check_foods."""
    r = {'id': 'banana', 'group': 'fruit', 'unit': 'g', 'kcal': '88', 'protein': '1.1', 'carbs': '19.7',
         'sugars': '15.6', 'fat': '0.3', 'sat_fat': '0.1', 'fiber': '2.7', 'salt': '0', 'portion': '120',
         'portions': 'unit:120', 'visual': '0', 'source': 'ciqual:13005', 'mono_fat': '0', 'poly_fat': '0.1',
         'cholesterol': '0', 'potassium': '320', 'calcium': '6.2', 'iron': '0.26', 'magnesium': '28.5',
         'zinc': '0.15', 'vit_a': '2.38', 'vit_c': '8.7', 'vit_d': '0', 'vit_e': '0', 'vit_b12': '0', 'folate': '19',
         'variant_of': ''}
    r.update(over)
    return r


def values(**v):
    """Source values: every nutrient 0 unless given."""
    return dict.fromkeys(build.ALL, 0.0) | v


def mapping_row(fid, source='recipe', unit='g', opts='', visual='0', line=1):
    return {'id': fid, 'group': 'dishes', 'unit': unit, 'visual': visual, 'source': source, 'opts': opts,
            'portion': '100', 'portions': '', 'en': fid, 'es': fid, 'description': '', 'note': '', 'line': line}


def catalog(rows, recipes, sources):
    return build.Catalog(rows, recipes, {'_header': ''} | {k: {'values': v, 'description': k, 'dataset': 'x',
                                                               'line': ''} for k, v in sources.items()})


class BuildTest(unittest.TestCase):
    def test_format_rounds_half_up_and_drops_zero_decimal(self):
        self.assertEqual(build.fmt('kcal', 87.5), '88')
        self.assertEqual(build.fmt('protein', 1.25), '1.3')
        self.assertEqual(build.fmt('salt', 0.04), '0')
        self.assertEqual(build.fmt('fat', 12.0), '12')
        self.assertEqual(build.fmt('fat', None), '')

    def test_format_of_the_new_columns(self):
        # grams: 1 decimal like fat; mg/ug: integer from 100, 1 decimal from 10, else up to 2 decimals
        self.assertEqual(build.fmt('mono_fat', 7.06), '7.1')
        self.assertEqual(build.fmt('potassium', 358.4), '358')
        self.assertEqual(build.fmt('calcium', 12.34), '12.3')
        self.assertEqual(build.fmt('iron', 0.255), '0.26')
        self.assertEqual(build.fmt('vit_b12', 0.004), '0')
        self.assertEqual(build.fmt('vit_d', 2.0), '2')
        self.assertEqual(build.fmt('folate', None), '')

    def test_energy_counts_polyols_inside_carbohydrate_at_2_4_kcal(self):
        # CIQUAL dried apricot: 239 kcal with 22.6 g of polyols inside its 59.1 g of carbohydrate.
        v = {'protein': 2.88, 'carbs': 59.1, 'fat': 0.5, 'fiber': 8.3, 'polyols': 22.6, 'organic_acids': 1.98,
             'alcohol': 0}
        self.assertAlmostEqual(build.energy(v), 239, delta=1)

    def test_recipe_is_weighted_and_scaled_by_yield(self):
        rows = [mapping_row('a', 'ciqual:1'), mapping_row('b', 'ciqual:2', line=2), mapping_row('dish', line=3)]
        sources = {'ciqual:1': values(kcal=100.0, protein=10.0, carbs=10.0, fat=2.0, iron=1.0),
                   'ciqual:2': values(kcal=900.0, fat=100.0, iron=None)}
        cat = catalog(rows, {'dish': {'ingredients': [['a', 300], ['b', 100]], 'yield': 0.8}}, sources)
        vals = cat.food('dish')
        # (300 + 900) kcal in 320 g
        self.assertAlmostEqual(vals['kcal'], 1200 / 3.2)
        self.assertAlmostEqual(vals['fat'], (6 + 100) / 3.2)
        # b lacks iron and is 25% of the recipe: iron is unknown, not a partial sum
        self.assertIsNone(vals['iron'])
        self.assertIn('iron', cat.missing['dish'])

    def test_small_ingredients_without_a_value_are_ignored(self):
        rows = [mapping_row('a', 'ciqual:1'), mapping_row('spice', 'ciqual:2', line=2), mapping_row('dish', line=3)]
        sources = {'ciqual:1': values(kcal=100.0, iron=2.0), 'ciqual:2': values(kcal=300.0, iron=None)}
        cat = catalog(rows, {'dish': {'ingredients': [['a', 990], ['spice', 10]]}}, sources)
        self.assertAlmostEqual(cat.food('dish')['iron'], 2.0 * 990 / 1000)

    def test_ml_ingredients_are_in_ml_and_recipes_tsv_reproduces_the_dish(self):
        # milk per 100 ml = per 100 g x 1.03; 200 ml of it weigh 206 g
        rows = [mapping_row('milk', 'ciqual:1', unit='ml', opts='density=1.03'),
                mapping_row('cocoa', 'ciqual:2', line=2), mapping_row('salt', 'ciqual:3', line=3),
                mapping_row('drink', unit='ml', opts='density=1.05', line=4)]
        sources = {'ciqual:1': values(kcal=46.0, protein=3.3, carbs=4.7, fat=1.5),
                   'ciqual:2': values(kcal=380.0, protein=6.0, carbs=80.0, fat=3.0), 'ciqual:3': values(salt=97.0)}
        recipes = {'drink': {'ingredients': [['milk', 200], ['cocoa', 20], ['salt', 0.5]]}}
        cat = catalog(rows, recipes, sources)
        drink = cat.food('drink')
        weight = 200 * 1.03 + 20 + 0.5
        self.assertAlmostEqual(drink['kcal'], (200 * 46 * 1.03 + 20 * 380) / weight * 1.05, places=6)
        rows_out = build.recipe_rows(cat, 'drink')
        self.assertEqual([r[1] for r in rows_out], ['milk', 'cocoa', 'salt'])
        self.assertEqual([r[3] for r in rows_out], ['1', '1', '0'])  # salt is not adjustable
        # per 100 ml of the drink: sum(amount x value per 100 of its unit) = the drink's value
        amounts = build.recipe_amounts(cat, 'drink')
        total = sum(a * cat.food(i)['kcal'] / 100 for i, (a, _) in amounts.items())
        self.assertAlmostEqual(total, drink['kcal'], places=6)

    def test_expand_lists_the_ingredients_of_a_recipe_ingredient(self):
        rows = [mapping_row('flour', 'ciqual:1'), mapping_row('oil', 'ciqual:2', line=2),
                mapping_row('sugar', 'ciqual:3', line=3), mapping_row('churro', line=4),
                mapping_row('churro_sugar', line=5)]
        sources = {'ciqual:1': values(kcal=350.0, carbs=75.0), 'ciqual:2': values(kcal=900.0, fat=100.0),
                   'ciqual:3': values(kcal=400.0, carbs=100.0)}
        recipes = {'churro': {'ingredients': [['flour', 80], ['oil', 20]], 'yield': 0.5},
                   'churro_sugar': {'ingredients': [['churro', 100], ['sugar', 10]], 'expand': True}}
        cat = catalog(rows, recipes, sources)
        cat.food('churro_sugar')
        amounts = build.recipe_amounts(cat, 'churro_sugar')
        self.assertEqual(set(amounts), {'flour', 'oil', 'sugar'})
        self.assertAlmostEqual(amounts['flour'][0], 160 * 100 / 110)  # 80 g in 50 g of churro, x 100/110
        total = sum(a * cat.food(i)['kcal'] / 100 for i, (a, _) in amounts.items())
        self.assertAlmostEqual(total, cat.food('churro_sugar')['kcal'], places=6)

    def test_fill_borrows_missing_values_and_scales_fatty_acids_to_the_food_fat(self):
        rows = [mapping_row('ham', 'ciqual:1', opts='fill=fdc:2; zero=vit_c')]
        sources = {'ciqual:1': values(kcal=125.0, fat=4.5, mono_fat=None, iron=None, vit_c=None),
                   'fdc:2': values(kcal=164.0, fat=9.0, mono_fat=4.0, iron=1.0, vit_c=None)}
        vals = catalog(rows, {}, sources).food('ham')
        self.assertAlmostEqual(vals['mono_fat'], 2.0)  # 4 g in 9 g of fat -> 2 g in 4.5 g
        self.assertEqual(vals['iron'], 1.0)
        self.assertEqual(vals['vit_c'], 0.0)

    def test_unknown_blanks_values_that_do_not_describe_the_food(self):
        # a US fortified bar: its vitamins are not those of a European one
        rows = [mapping_row('bar', 'fdc:1', opts='unknown=vit_a,vit_c; fill=fdc:2')]
        sources = {'fdc:1': values(kcal=426.0, protein=30.0, vit_a=1050.0, vit_c=120.0, iron=None),
                   'fdc:2': values(kcal=400.0, vit_a=5.0, iron=2.0)}
        vals = catalog(rows, {}, sources).food('bar')
        self.assertIsNone(vals['vit_a'])  # not refilled by fill= either
        self.assertIsNone(vals['vit_c'])
        self.assertEqual(vals['iron'], 2.0)
        with self.assertRaises(SystemExit):  # the four required values cannot be blanked
            catalog([mapping_row('bar', 'fdc:1', opts='unknown=kcal')], {}, sources).food('bar')
        with self.assertRaises(SystemExit):  # not on recipe dishes (their values are their ingredients)
            catalog([mapping_row('a', 'fdc:1'), mapping_row('dish', opts='unknown=iron', line=2)],
                    {'dish': {'ingredients': [['a', 10]]}}, sources).food('dish')

    def test_fill_recipe_uses_the_typical_recipe(self):
        rows = [mapping_row('rice', 'ciqual:1'), mapping_row('risotto', 'ciqual:2', opts='fill=recipe', line=2)]
        sources = {'ciqual:1': values(kcal=150.0, carbs=33.0, fat=1.0, iron=0.2),
                   'ciqual:2': values(kcal=160.0, carbs=30.0, fat=2.0, iron=None)}
        cat = catalog(rows, {'risotto': {'ingredients': [['rice', 100]]}}, sources)
        self.assertAlmostEqual(cat.food('risotto')['iron'], 0.2)
        self.assertEqual(cat.food('risotto')['kcal'], 160.0)  # the source's own values stay

    def test_water_does_not_hide_a_missing_value(self):
        # pork lacks vitamin D and is 4% of the pot with its water, but 10% of what has energy: unknown
        rows = [mapping_row('beans', 'ciqual:1'), mapping_row('pork', 'ciqual:2', line=2),
                mapping_row('water', 'ciqual:3', unit='ml', line=3), mapping_row('stew', line=4)]
        sources = {'ciqual:1': values(kcal=300.0, vit_d=0.0), 'ciqual:2': values(kcal=270.0, vit_d=None),
                   'ciqual:3': values(vit_d=None)}
        cat = catalog(rows, {'stew': {'ingredients': [['beans', 450], ['pork', 50], ['water', 750]]}}, sources)
        self.assertIsNone(cat.food('stew')['vit_d'])

    def test_recipe_dishes_reject_borrowed_values_and_ml_dishes_need_a_density(self):
        rows = [mapping_row('a', 'ciqual:1'), mapping_row('dish', opts='zero=vit_d', line=2)]
        sources = {'ciqual:1': values(kcal=100.0)}
        with self.assertRaises(SystemExit):
            catalog(rows, {'dish': {'ingredients': [['a', 10]]}}, sources).food('dish')
        rows = [mapping_row('a', 'ciqual:1'), mapping_row('drink', unit='ml', line=2)]
        with self.assertRaises(SystemExit):
            catalog(rows, {'drink': {'ingredients': [['a', 10]]}}, sources).food('drink')
        rows = [mapping_row('a', 'ciqual:1'), mapping_row('dish', line=2)]
        with self.assertRaises(SystemExit):  # adjustable must be the integer 0 or 1
            catalog(rows, {'dish': {'ingredients': [['a', 10, 'x', True]]}}, sources).food('dish')
        with self.assertRaises(SystemExit):
            build.parse_opts('density=0', 'x')

    def test_iron_is_not_borrowed_from_a_fortified_us_food(self):
        rows = [mapping_row('cake', 'ciqual:1', opts='fill=fdc:2')]
        sources = {'ciqual:1': values(kcal=380.0, fat=13.0, iron=None, zinc=None, mono_fat=None),
                   'fdc:2': values(kcal=290.0, fat=0.0, iron=2.7, zinc=0.5, mono_fat=1.0)}
        cat = catalog(rows, {}, sources)
        cat.sources['fdc:2']['fortified'] = True
        vals = cat.food('cake')
        self.assertIsNone(vals['iron'])
        self.assertEqual(vals['zinc'], 0.5)
        self.assertIsNone(vals['mono_fat'])  # the donor has no fat to scale its profile by

    def test_recipe_cycles_and_unknown_ingredients_fail(self):
        rows = [mapping_row('a'), mapping_row('b', line=2)]
        with self.assertRaises(SystemExit):
            catalog(rows, {'a': {'ingredients': [['b', 1]]}, 'b': {'ingredients': [['a', 1]]}}, {}).food('a')
        with self.assertRaises(SystemExit):
            catalog(rows, {'a': {'ingredients': [['nope', 1]]}}, {}).food('a')

    def test_composition_gives_the_documented_values_and_leaves_the_rest_unknown(self):
        rows = [mapping_row('creatine')]
        comp = {'kcal': 0, 'protein': 0, 'carbs': 0, 'fat': 0, 'salt': 0}
        recipe = {'list': False, 'composition': comp, 'basis': 'pure compound'}
        cat = catalog(rows, {'creatine': recipe}, {})
        vals = cat.food('creatine')
        self.assertEqual((vals['kcal'], vals['protein'], vals['salt']), (0.0, 0.0, 0.0))
        self.assertIsNone(vals['sugars'])
        self.assertIn('sugars', cat.missing['creatine'])
        self.assertEqual(build.recipe_amounts(cat, 'creatine'), {})
        bad = [{'composition': comp, 'basis': 'x'},  # listed: it has no ingredients to list
               {'list': False, 'composition': comp},  # no basis
               {'list': False, 'composition': comp | {'vit_q': 1}, 'basis': 'x'},
               {'list': False, 'composition': comp | {'fat': -1}, 'basis': 'x'},
               {'list': False, 'composition': {'kcal': 0}, 'basis': 'x'},
               {'list': False, 'composition': comp, 'basis': 'x', 'ingredients': [['a', 1]]}]
        for r in bad:
            with self.assertRaises(SystemExit, msg=r):
                catalog(rows, {'creatine': r}, {}).food('creatine')
        # a food with its own source code cannot borrow a composition as its typical recipe
        rows = [mapping_row('gel', 'ciqual:1', opts='fill=recipe')]
        with self.assertRaises(SystemExit):
            catalog(rows, {'gel': recipe}, {'ciqual:1': values(kcal=240.0, sugars=None)}).food('gel')

    def test_portions_only_accept_the_fixed_keys(self):
        self.assertEqual(build.parse_portions('unit:120 slice:30', 'x'), [('unit', 120), ('slice', 30)])
        self.assertEqual(build.parse_portions('scoop:30', 'x'), [('scoop', 30)])
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

    def test_class_list_v2_skips_dropped_classes(self):
        with tempfile.TemporaryDirectory() as d:
            classes = os.path.join(d, 'food_classes_v2.csv')
            with open(classes, 'w', encoding='utf-8') as f:
                f.write('id,name_en,name_es,status\napple,Apple,Manzana,kept\nkiwi,Kiwi,Kiwi,dropped\n'
                        'leek,Leek,Puerro,recovered\nmochi,Mochi,Mochi,new\n_not_food,Not food,No comida,kept\n')
            self.assertEqual(build.read_classes(classes), {'apple', 'leek', 'mochi'})

    def test_options(self):
        self.assertEqual(build.parse_opts('fill=fdc:1; density=1.03', 'x'), {'fill': 'fdc:1', 'density': '1.03'})
        self.assertEqual(build.parse_opts('variant_of=paella; zero=vit_d,vit_b12', 'x'),
                         {'variant_of': 'paella', 'zero': 'vit_d,vit_b12'})
        with self.assertRaises(SystemExit):
            build.parse_opts('salt=1', 'x')
        with self.assertRaises(SystemExit):
            build.parse_opts('zero=vitamin_q', 'x')


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

    def test_usda_vitamins(self):
        row = extract.fdc_row({'vit_a': 50, 'retinol': 1, 'vit_d': 2.5, 'vit_d_iu': 1, 'folate_dfe': 90,
                               'folate_total': 10, 'mono_fat': 1.2, 'cholesterol': 3})
        self.assertEqual((row['vit_a'], row['vit_d'], row['folate'], row['mono_fat'], row['cholesterol']),
                         (50, 2.5, 90, 1.2, 3))
        # Foundation foods often lack the summary values
        row = extract.fdc_row({'beta_carotene': 1200, 'alpha_carotene': 240, 'vit_d_iu': 40, 'folate_food': 20,
                               'folic_acid': 0})
        self.assertAlmostEqual(row['vit_a'], 1200 / 12 + 240 / 24)
        self.assertAlmostEqual(row['vit_d'], 1.0)
        self.assertAlmostEqual(row['folate'], 20)
        # US fortification (folic acid) is left out: European flour is not fortified
        row = extract.fdc_row({'folate_dfe': 191, 'folate_food': 25, 'folic_acid': 98})
        self.assertEqual(row['folate'], 25)
        self.assertAlmostEqual(extract.folate_dfe({'folate_food': 20, 'folic_acid': 10}), 37)
        row = extract.fdc_row({'vit_d2': 0.5, 'vit_d3': 1.5, 'folate_total': 30})
        self.assertEqual((row['vit_d'], row['folate']), (2.0, 30))
        row = extract.fdc_row({})
        self.assertTrue(all(row[k] is None for k in extract.MICRO))

    def test_ciqual_vitamins(self):
        row = extract.ciqual_row({'vit_a': 927, 'vit_e': 2.0, 'vit_e_other': 9, 'folate_dfe': 26.8})
        self.assertEqual((row['vit_a'], row['vit_e'], row['folate']), (927, 2.0, 26.8))
        row = extract.ciqual_row({'retinol': 160, 'beta_carotene': 120, 'vit_e_other': 1.5, 'folate_total': 47})
        self.assertAlmostEqual(row['vit_a'], 170)  # RAE: beta-carotene / 12
        self.assertEqual((row['vit_e'], row['folate']), (1.5, 47))

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
            'saturated + mono + poly': [row(fat='3', sat_fat='1', mono_fat='2', poly_fat='1')],
            'max 2 decimals': [row(iron='0.255')],
            'max 1 decimals': [row(mono_fat='0.05')],
            'implausible': [row(vit_a='99999')],
            'far from': [row(kcal='300')],
            'bad portion': [row(portions='unit:120|ración:200')],
            'default portion is not one of the portions': [row(portion='100')],
            'bad source': [row(source='bedca:123')],
            'is not another food': [row(variant_of='mango')],
            'is itself a variant': [row(), row(id='plantain', variant_of='banana'),
                                    row(id='red_banana', variant_of='plantain')],
            'a variant must have visual = 0': [row(), row(id='plantain', variant_of='banana', visual='1')],
        }
        for message, rows in cases.items():
            errors, _ = self.check(*rows)
            self.assertTrue(any(message in e for e in errors), f'{message}: {errors}')

    def test_variants(self):
        errors, _ = self.check(row(), row(id='plantain', variant_of='banana'))
        self.assertEqual(errors, [])

    def test_drinks_may_have_alcohol_energy(self):
        beer = row(id='beer', group='drinks', unit='ml', kcal='39', protein='0.4', carbs='3.1', sugars='0',
                   fat='0', sat_fat='0', fiber='0', portion='330', portions='can:330', poly_fat='0')
        self.assertEqual(self.check(beer)[0], [])

    def test_only_alcoholic_drinks_may_exceed_their_macronutrient_energy(self):
        juice = row(id='orange_juice', group='drinks', unit='ml', kcal='90', protein='0.7', carbs='9.6',
                    sugars='8', fat='0.2', sat_fat='0', fiber='0.2', portion='200', portions='glass:200')
        errors, _ = self.check(juice)
        self.assertTrue(any('far from' in e for e in errors), errors)

    def test_recipes_reproduce_their_dish(self):
        oil = row(id='oil', kcal='900', protein='0', carbs='0', sugars='0', fat='100', sat_fat='14',
                  mono_fat='73', poly_fat='10', fiber='0', salt='0')
        egg = row(id='egg', kcal='140', protein='12.8', carbs='0.1', sugars='0.1', fat='9.8', sat_fat='2.6',
                  mono_fat='3.6', poly_fat='1.6', fiber='0', salt='0.3')
        # 95 g egg + 5 g oil per 100 g
        fried = row(id='fried_egg', source='recipe', kcal='178', protein='12.2', carbs='0.1', sugars='0.1',
                    fat='14.3', sat_fat='3.2', mono_fat='7.1', poly_fat='2', fiber='0', salt='0.3')
        foods = list(enumerate([oil, egg, fried], 2))
        lines = [(2, {'dish': 'fried_egg', 'ingredient': 'egg', 'grams': '95', 'adjustable': '1'}),
                 (3, {'dish': 'fried_egg', 'ingredient': 'oil', 'grams': '5', 'adjustable': '1'})]
        for k in validate.MICRO[2:]:
            for r in (oil, egg, fried):
                r[k] = ''
        errors, _, n, n_recipe = validate.check_recipes(lines, foods)
        self.assertEqual((errors, n, n_recipe), ([], 1, 1))
        fried['fat'] = '15.3'
        errors, _, _, _ = validate.check_recipes(lines, foods)
        self.assertTrue(any('fat of the ingredients' in e for e in errors), errors)
        bad = [(2, {'dish': 'fried_egg', 'ingredient': 'bacon', 'grams': '0', 'adjustable': '2'})]
        errors, _, _, _ = validate.check_recipes(bad, foods)
        for message in ('ingredient is not a food', 'must be > 0', 'adjustable must be 0 or 1'):
            self.assertTrue(any(message in e for e in errors), (message, errors))

    def test_typical_recipes_must_explain_the_energy(self):
        oil = row(id='oil', kcal='900', protein='0', carbs='0', sugars='0', fat='100', sat_fat='14', fiber='0')
        salad = row(id='salad', source='ciqual:1', kcal='90', protein='1', carbs='3', sugars='2', fat='9.5',
                    sat_fat='1.5', fiber='1')
        foods = list(enumerate([oil, salad], 2))
        lines = [(2, {'dish': 'salad', 'ingredient': 'oil', 'grams': '9', 'adjustable': '1'})]
        self.assertEqual(validate.check_recipes(lines, foods)[0], [])
        lines[0][1]['grams'] = '20'
        self.assertTrue(any('typical recipe gives' in e for e in validate.check_recipes(lines, foods)[0]))

    def test_no_adjustable_ingredient_brings_more_than_the_dish(self):
        # 9.5 g of oil bring 9.5 g of fat: removing it from a salad with 8 g of fat would give -1.5 g
        oil = row(id='oil', kcal='900', protein='0', carbs='0', sugars='0', fat='100', sat_fat='14', fiber='0')
        salad = row(id='salad', source='ciqual:1', kcal='90', protein='1', carbs='3', sugars='2', fat='8',
                    sat_fat='1.5', fiber='1')
        foods = list(enumerate([oil, salad], 2))
        lines = [(2, {'dish': 'salad', 'ingredient': 'oil', 'grams': '9.5', 'adjustable': '1'})]
        errors = validate.check_recipes(lines, foods)[0]
        self.assertTrue(any('oil brings 9.5 fat' in e for e in errors), errors)
        lines[0][1]['adjustable'] = '0'  # not offered for adjusting: fine
        self.assertEqual(validate.check_recipes(lines, foods)[0], [])

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

    def test_recipes_tsv_reproduces_every_recipe_dish(self):
        rows, errors = validate.read_foods(validate.FOODS)
        lines, e = validate.read_recipes(validate.RECIPES)
        e2, _, n, n_recipe = validate.check_recipes(lines, rows)
        self.assertEqual(errors + e + e2, [])
        # every recipe dish whose recipe is listed has its ingredients in recipes.tsv
        listed = {r['dish'] for _, r in lines}
        import tomllib
        with open(build.RECIPES, 'rb') as f:
            recipes = tomllib.load(f)
        want = {fid for fid, rec in recipes.items() if rec.get('list', True) is not False}
        self.assertEqual(listed, want)
        self.assertGreaterEqual(n_recipe, 90)

    def test_outputs_equal_a_fresh_build_from_the_committed_sources(self):
        self.assertEqual(build.check(), [])

    def test_mapping_is_well_formed(self):
        rows = build.read_mapping()
        ids = [r['id'] for r in rows]
        self.assertEqual(len(ids), len(set(ids)))
        for r in rows:
            self.assertIn(r['group'], build.GROUPS, r['id'])
            self.assertTrue(r['description'], f'{r["id"]}: run build.py --fix-descriptions')
        # one name per food and language, so a search never shows two identical lines
        for lang in ('en', 'es'):
            names = [r[lang].lower() for r in rows]
            self.assertEqual(sorted({n for n in names if names.count(n) > 1}), [], lang)


def normalize(s):
    """FoodSearch.normalize of the app (Latin scripts): lowercase, no accents, letters and digits, single spaces."""
    import unicodedata
    plain = ''.join(c for c in unicodedata.normalize('NFD', s) if unicodedata.category(c) != 'Mn').lower()
    return ' '.join(''.join(c if c.isalnum() else ' ' for c in plain).split())


def tier(name, query):
    """FoodSearch.tier of the app: 3 starts with the query, 2 every word starts a word, 1 every word is in it."""
    if not query:
        return 0
    if name.startswith(query):
        return 3
    words, terms = name.split(' '), query.split(' ')
    if all(any(w.startswith(t) for w in words) for t in terms):
        return 2
    return 1 if all(t in name for t in terms) else 0


# What people type (Spanish first, then English) -> the food it must find. The app has no synonyms: a food is
# found by the words of its name in the user's language, so names carry the common alternatives ("o latte").
SEARCHES = [
    ('proteina', 'whey_protein'), ('whey', 'whey_protein'), ('proteina vegetal', 'vegan_protein'),
    ('batido de proteinas', 'protein_shake'), ('batido proteinas agua', 'protein_shake_water'),
    ('barrita proteinas', 'protein_bar'), ('barrita energetica', 'energy_bar'), ('gel energetico', 'energy_gel'),
    ('creatina', 'creatine'), ('bcaa', 'bcaa'), ('aminoacidos', 'bcaa'), ('gainer', 'mass_gainer'),
    ('maltodextrina', 'maltodextrin'), ('colageno', 'collagen'), ('omega 3', 'fish_oil'),
    ('electrolitos', 'electrolyte_drink'), ('isotonica', 'sports_drink'), ('isotonico', 'isotonic_powder'),
    ('cacahuete en polvo', 'peanut_butter_powder'), ('sustitutivo', 'meal_replacement_shake'),
    ('refresco de naranja', 'orange_soda'), ('refresco de limon', 'lemon_soda'), ('refresco zero', 'soft_drink_zero'),
    ('tonica', 'tonic_water'), ('mosto', 'grape_juice'), ('zumo de tomate', 'tomato_juice'),
    ('agua de coco', 'coconut_water'), ('kombucha', 'kombucha'), ('capuchino', 'cappuccino'),
    ('latte', 'coffee_with_milk'), ('cerveza sin alcohol', 'alcohol_free_beer'), ('cerveza negra', 'dark_beer'),
    ('cava', 'sparkling_wine'), ('fino', 'sherry'), ('manzanilla', 'sherry'), ('moscatel', 'sweet_wine'),
    ('pacharan', 'liqueur'), ('orujo', 'orujo'), ('cubata', 'rum_and_cola'), ('gin tonic', 'gin_tonic'),
    ('whisky cola', 'whisky_cola'), ('rebujito', 'rebujito'), ('pina colada', 'pina_colada'),
    ('galletas maria', 'maria_biscuits'), ('digestive', 'wholemeal_biscuits'), ('napolitana', 'pain_au_chocolat'),
    ('magdalena', 'muffin'), ('berlina', 'donut'), ('mazapan', 'marzipan'), ('polvoron', 'polvorones'),
    ('mantecado', 'polvorones'), ('peladillas', 'sugared_almonds'), ('roscon', 'roscon'),
    ('chocolate blanco', 'white_chocolate'), ('regaliz', 'liquorice'), ('nubes', 'marshmallows'),
    ('bombon helado', 'ice_cream_bar'), ('quicos', 'corn_nuts'), ('palomitas', 'popcorn'),
    ('marmitako', 'marmitako'), ('pil pil', 'bacalao_pil_pil'), ('salsa verde', 'merluza_salsa_verde'),
    ('arroz a banda', 'arroz_a_banda'), ('arroz con pollo', 'arroz_con_pollo'), ('rabo de toro', 'rabo_de_toro'),
    ('cachopo', 'cachopo'), ('flamenquin', 'flamenquin'), ('san jacobo', 'san_jacobo'), ('serranito', 'serranito'),
    ('pulpo', 'octopus'), ('cachelos', 'pulpo_con_cachelos'), ('caldo gallego', 'caldo_gallego'),
    ('papas arrugadas', 'papas_arrugadas'), ('escalivada', 'escalivada'), ('menestra', 'menestra'),
    ('croquetas de pollo', 'croquettes_chicken'), ('bocadillo de atun', 'tuna_baguette'),
    ('empanadillas', 'empanadillas'), ('pimientos rellenos', 'stuffed_peppers'),
    ('macarrones con atun', 'pasta_tuna'), ('poke', 'poke_bowl'), ('burrito', 'burrito'),
    ('tikka masala', 'chicken_tikka_masala'), ('curry verde', 'thai_green_curry'), ('shawarma', 'shawarma'),
    ('tortitas de avena', 'protein_pancakes'), ('pollo con arroz', 'chicken_rice_broccoli'),
    ('tortilla de claras', 'egg_white_omelette'), ('pipirrana', 'pipirrana'), ('ajoblanco', 'ajoblanco'),
    ('clementina', 'mandarin'), ('paraguayo', 'flat_peach'), ('higo chumbo', 'prickly_pear'),
    ('piquillo', 'roasted_peppers'), ('queso de burgos', 'fresh_cheese'), ('canonigos', 'lambs_lettuce'),
    ('setas', 'oyster_mushrooms'), ('grelos', 'turnip_greens'), ('soja texturizada', 'textured_soy'),
    ('altramuces', 'lupini'), ('seitan', 'seitan'), ('harina de avena', 'oat_flour'), ('cecina', 'cecina'),
    ('solomillo de ternera', 'beef_tenderloin'), ('chuleton', 'entrecote'), ('rape', 'monkfish'),
    ('panga', 'pangasius'), ('emperador', 'swordfish'), ('cigalas', 'langoustines'), ('sepia', 'cuttlefish'),
    ('leche sin lactosa', 'lactose_free_milk'), ('queso batido', 'quark'), ('cuajada', 'cuajada'),
    ('nata para montar', 'heavy_cream'), ('aceite de coco', 'coconut_oil'), ('pinones', 'pine_nuts'),
    ('tahini', 'tahini'), ('croissant', 'croissant'), ('cruasan mixto', 'croissant_mixto'), ('durum', 'durum'),
    ('zarzuela', 'zarzuela'), ('nigiri', 'nigiri_salmon'), ('maki', 'maki_roll'), ('arepa', 'arepa'),
    ('te verde', 'green_tea'), ('crema de arroz', 'rice_flour'), ('pollo al limon', 'lemon_chicken'),
    ('tres delicias', 'fried_rice'), ('garbanzos con chorizo', 'garbanzos_con_chorizo'),
    ('mejillones a la marinera', 'mejillones_marinera'), ('tarta de la abuela', 'tarta_de_la_abuela'),
    ('basmati', 'basmati_rice'), ('jabali', 'wild_boar'), ('mojo picon', 'mojo_picon'), ('romesco', 'romesco'),
    ('jurel', 'horse_mackerel'), ('chicharro', 'horse_mackerel'), ('mijo', 'millet'), ('trigo sarraceno', 'buckwheat'),
    ('baklava', 'baklava'), ('malvaviscos', 'marshmallows'), ('hojaldre de manzana', 'apple_turnover'), ('cerveza negra', 'dark_beer'), ('peanut powder', 'peanut_butter_powder'),
    ('protein powder', 'whey_protein'), ('protein shake', 'protein_shake'), ('protein bar', 'protein_bar'),
    ('creatine', 'creatine'), ('energy gel', 'energy_gel'), ('collagen', 'collagen'), ('orange soda', 'orange_soda'),
    ('diet', 'cola_zero'), ('sugar-free', 'soft_drink_zero'), ('cappuccino', 'cappuccino'), ('sherry', 'sherry'),
    ('digestive', 'wholemeal_biscuits'), ('marzipan', 'marzipan'), ('tuna salad', 'tuna_salad'),
    ('cod', 'cod'), ('oxtail', 'rabo_de_toro'), ('poke', 'poke_bowl'), ('overnight oats', 'overnight_oats'),
    ('chicken wrap', 'chicken_wrap'), ('hake', 'hake'), ('monkfish', 'monkfish'), ('lactose-free', 'lactose_free_milk'),
]


class SearchTest(unittest.TestCase):
    def test_common_names_find_the_food(self):
        rows = build.read_mapping()
        by_id = {r['id']: r for r in rows}
        for query, fid in SEARCHES:
            self.assertIn(fid, by_id, query)
            r = by_id[fid]
            q = normalize(query)
            self.assertTrue(tier(normalize(r['es']), q) or tier(normalize(r['en']), q),
                            f'{query!r} does not find {fid} ({r["en"]} / {r["es"]})')

    def test_normalize_matches_the_app(self):
        self.assertEqual(normalize('Café con leche o latte'), 'cafe con leche o latte')
        self.assertEqual(normalize('Gin-tonic'), 'gin tonic')
        self.assertEqual(tier('proteina whey en polvo', 'whey'), 2)
        self.assertEqual(tier('bocadillo de atun', 'atun boca'), 2)


if __name__ == '__main__':
    unittest.main()
