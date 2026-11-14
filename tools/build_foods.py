"""Builds data/foods.json, the food list Micro Foods ships with.

Run:  python3 -I tools/build_foods.py <INDB repo dir> data/foods.json
Source: github.com/lindsayjaacks/Indian-Nutrient-Databank-INDB- (INDB.xlsx = 1,014 recipes, UK_fct.xlsx).
Only rows that pass two checks are used: fat <= 50 g/100 g (frying oil counted as eaten fails this) and
kcal within 15% of 4P + 4C + 9F + 2 fibre. Each row below was also looked at by eye.
"""
import json, sys
import pandas as pd

MICROS = ['calcium_mg', 'iron_mg', 'zinc_mg', 'magnesium_mg', 'potassium_mg', 'folate_ug', 'vitc_mg', 'vita_ug']

# id, INDB code, display name, aliases (Marathi / Hindi / English), group, katori g per ml (assumed), piece g
INDB = [
    ('chapati', 'ASC096', 'Chapati', ['poli', 'roti', 'phulka', 'chapathi'], 'veg', None, 36),
    ('rice', 'ASC113', 'Rice, boiled', ['bhaat', 'chawal', 'plain rice', 'steamed rice'], 'veg', 0.8, None),
    ('poha', 'BFP044', 'Poha', ['kanda poha', 'pohe'], 'veg', 0.55, None),
    ('veg_poha', 'BFP045', 'Vegetable poha', ['batata poha', 'mixed veg poha'], 'veg', 0.55, None),
    ('upma', 'BFP039', 'Upma', ['rava upma', 'suji upma', 'uppit'], 'veg', 0.6, None),
    ('veg_upma', 'BFP043', 'Vegetable upma', ['veg uppit'], 'veg', 0.6, None),
    ('khichdi', 'BFP144', 'Khichdi', ['khichadi', 'dal khichdi'], 'veg', 1.0, None),
    ('sabudana_khichdi', 'OSR099', 'Sabudana khichdi', ['sago khichdi'], 'veg', 0.7, None),
    ('bhindi', 'BFP269', 'Bhendi bhaji', ['bhindi', 'okra', 'bhindi fry', 'bhendi'], 'veg', 0.7, None),
    ('aloo_sabzi', 'BFP239', 'Batata bhaji', ['aloo sabzi', 'potato sabzi', 'batata'], 'veg', 0.9, None),
    ('aloo_matar', 'ASC190', 'Aloo matar', ['batata vatana', 'potato peas'], 'veg', 1.0, None),
    ('cabbage', 'ASC173', 'Kobi bhaji', ['cabbage sabzi', 'patta gobhi', 'kobi'], 'veg', 0.6, None),
    ('aloo_methi', 'ASC175', 'Methi batata', ['aloo methi', 'methi bhaji'], 'veg', 0.7, None),
    ('palak_paneer', 'ASC215', 'Palak paneer', [], 'veg', 1.0, None),
    ('paneer_curry', 'ASC195', 'Paneer curry', ['paneer bhaji', 'paneer masala'], 'veg', 1.0, None),
    ('rajma', 'ASC165', 'Rajma', ['rajma curry', 'kidney beans'], 'veg', 1.0, None),
    ('moong_whole', 'ASC156', 'Moong usal', ['whole moong', 'moong', 'sprouted moong usal'], 'veg', 1.0, None),
    ('matki', 'ASC158', 'Matki usal', ['moth', 'matki', 'misal usal'], 'veg', 1.0, None),
    ('masoor', 'ASC157', 'Masoor', ['whole masoor', 'masoor usal'], 'veg', 1.0, None),
    ('moong_dal', 'ASC151', 'Moong dal', ['dhuli moong', 'yellow moong dal'], 'veg', 1.0, None),
    ('dal', 'ASC155', 'Dal / varan', ['varan', 'amti', 'toor dal', 'tur dal', 'mixed dal', 'dal tadka'], 'veg', 1.0, None),
    ('omelette', 'ASC061', 'Omelette', ['plain omelette', 'omlet', 'anda omelette'], 'egg', None, None),
    ('egg_curry', 'BFP240', 'Egg curry', ['anda curry', 'egg masala'], 'egg', 1.0, None),
    ('chicken_curry', 'ASC240', 'Chicken curry', ['chicken rassa', 'chicken masala'], 'nonveg', 1.0, None),
    ('fish_curry', 'ASC246', 'Fish curry', ['machli curry', 'fish rassa'], 'nonveg', 1.0, None),
    ('curd_rice', 'ASC126', 'Curd rice', ['dahi bhaat'], 'veg', 0.9, None),
    ('chaas', 'ASC022', 'Chaas', ['taak', 'buttermilk', 'salted lassi'], 'veg', 1.0, None),
    ('shrikhand', 'OSR024', 'Shrikhand', [], 'veg', 1.1, None),
    ('thepla', 'OSR104', 'Methi thepla', ['thepla'], 'veg', None, None),
    ('pav_bhaji', 'OSR112', 'Pav bhaji (bhaji)', ['pav bhaji'], 'veg', 1.0, None),
    ('chai', 'ASC001', 'Chai', ['chaha', 'tea', 'milk tea'], 'veg', 1.0, None),
]

# UK McCance & Widdowson rows shipped inside the INDB repo (UK_fct.xlsx)
UK = [
    ('dahi', 'L520', 'Dahi (plain curd)', ['curd', 'dahi', 'yogurt', 'dahi katori'], 'veg', 1.03, None),
    ('ghee', 'T506', 'Ghee', ['toop', 'tup'], 'addon', 0.91, None),
    ('butter', 'T500', 'Butter', ['loni', 'amul butter'], 'addon', 0.95, None),
    ('oil', 'T508', 'Oil', ['tel', 'sunflower oil', 'cooking oil'], 'addon', 0.92, None),
    ('sugar', 'I502', 'Sugar', ['sakhar', 'cheeni'], 'addon', 0.85, None),
    ('pav', 'U511', 'Pav / bread', ['pav', 'ladi pav', 'bread', 'bun', 'slice bread'], 'veg', None, 40),
    ('peanuts', 'B505', 'Peanuts, roasted', ['shengdana', 'moongphali'], 'veg', 0.6, None),
]

# USDA FoodData Central 173424 "Egg, whole, cooked, hard-boiled" (per 100 g). INDB's boiled egg row is 45 kcal/100 g.
USDA_EGG = dict(id='egg_boiled', name='Boiled egg', aliases=['egg', 'anda', 'ukadlela anda', 'boiled egg'], group='egg',
                source='USDA FDC 173424', kcal=155.0, protein_g=12.58, carbs_g=1.12, fat_g=10.61, fibre_g=0.0,
                micros={}, units={'piece': 50.0}, katori_g_per_ml=None, assumed=['piece'])

TSP_ML, KATORI_DEFAULT_ML = 5.0, 150.0
# INDB serving names -> the units the app and the model use
SERVING_UNITS = {'bowl': 'bowl', 'curry bowl': 'bowl', 'small bowl': 'bowl', 'plate': 'plate', 'tea cup': 'cup',
                 'cup': 'cup', 'glass': 'glass', 'tall glass': 'glass', 'omelette': 'piece', 'thepla': 'piece', 'chapati': 'piece'}


def checks(r):
    kcal = float(r['energy_kcal'])
    atw = 4 * r['carb_g'] + 4 * r['protein_g'] + 9 * r['fat_g'] + 2 * (r['fibre_g'] if pd.notna(r['fibre_g']) else 0)
    return kcal > 0 and abs(atw - kcal) / kcal <= 0.15


def row_to_food(fid, r, name, aliases, group, dens, piece, source, allow_fat=False):
    if not allow_fat:
        assert r['fat_g'] <= 50, f'{fid}: fat {r["fat_g"]}'
    assert checks(r), f'{fid}: kcal does not match its macros'
    units, assumed = {}, []
    num = lambda k: round(float(r[k]), 3) if k in r and pd.notna(r[k]) else 0.0
    if piece:
        units['piece'] = float(piece)
        assumed.append('piece')
    if 'unit_serving_energy_kcal' in r and pd.notna(r.get('unit_serving_energy_kcal')) and r['energy_kcal'] > 0:
        g = float(r['unit_serving_energy_kcal']) / float(r['energy_kcal']) * 100
        u = SERVING_UNITS.get(str(r['servings_unit']).strip().lower())
        if u == 'piece' and not piece:
            units['piece'] = round(g, 1)
        elif u and u != 'piece' and g <= 450:  # INDB servings above 450 g are not one person's portion
            units[u] = round(g, 1)
    if dens is not None:
        units['katori'] = round(KATORI_DEFAULT_ML * dens, 1)
        units['tsp'] = round(TSP_ML * dens, 2)
        units['tbsp'] = round(3 * TSP_ML * dens, 2)
        assumed += ['katori', 'tsp', 'tbsp']
    return dict(id=fid, name=name, aliases=aliases, group=group, source=source,
                kcal=num('energy_kcal'), protein_g=num('protein_g'), carbs_g=num('carb_g'), fat_g=num('fat_g'),
                fibre_g=num('fibre_g'), micros={m: num(m) for m in MICROS if m in r and pd.notna(r[m])},
                units=units, katori_g_per_ml=dens, assumed=assumed)


def main(src, out):
    indb = pd.read_excel(f'{src}/INDB.xlsx').set_index('food_code')
    uk = pd.read_excel(f'{src}/UK_fct.xlsx').set_index('food_code')
    foods = []
    for fid, code, name, al, grp, dens, piece in INDB:
        foods.append(row_to_food(fid, indb.loc[code], name, al, grp, dens, piece, f'INDB {code}'))
    for fid, code, name, al, grp, dens, piece in UK:
        r = uk.loc[code].copy()
        for k in ['energy_kcal', 'carb_g', 'protein_g', 'fat_g', 'fibre_g'] + MICROS:
            if k in r:  # 'Tr' (trace) and 'N' (not measured) become 0
                v = pd.to_numeric(r[k], errors='coerce')
                r[k] = 0.0 if pd.isna(v) else float(v)
        foods.append(row_to_food(fid, r, name, al, grp, dens, piece, f'UK CoFID {code} (via INDB repo)', allow_fat=True))
    foods.append(USDA_EGG)
    ids = [f['id'] for f in foods]
    assert len(ids) == len(set(ids))
    json.dump({'version': '2026.11a', 'katori_default_ml': KATORI_DEFAULT_ML, 'foods': foods}, open(out, 'w'), indent=1, ensure_ascii=False)
    print(f'{len(foods)} foods -> {out}')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
