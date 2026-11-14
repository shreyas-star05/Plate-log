# Micro Foods

Photo of a meal in, one row per food item out, in your own Google Sheet. The AI (Gemini) only names the items
and guesses portions; every kcal and gram of protein comes from a food list on the phone, and you confirm each
portion before anything is saved.

Spec: *Micro Foods — Spec v1 (Phase 1)* (Claude doc). This is **release 0.1, the accuracy-test build**.

## Set it up (about 15 minutes, once)

1. **Make the Sheet.** Create a new Google Sheet called *Micro Foods*.
2. **Add the script.** In the Sheet: *Extensions > Apps Script*. Replace everything in `Code.gs` with
   [`apps-script/Code.gs`](apps-script/Code.gs). In *Project Settings* tick *Show "appsscript.json"*, then paste
   [`apps-script/appsscript.json`](apps-script/appsscript.json) into that file. Save.
3. **Add your free Gemini key.** Get one at <https://aistudio.google.com/apikey>. In the script's *Project Settings >
   Script properties*, add `GEMINI_API_KEY` with the key as its value.
4. **Run the setup once.** Pick `firstTimeSetup` in the function box at the top and press *Run*. Google asks you to
   allow access to the Sheet, Drive (for meal photos), email (for the Sunday report) and outside requests (Gemini).
   When it finishes, open *Execution log* and copy the **token** it printed.
5. **Publish it.** *Deploy > New deployment > Web app*. Execute as: *Me*. Who has access: *Anyone*. Deploy, then copy
   the **Web app URL**. ("Anyone" means anyone with the URL *and* the 40-character token; nothing works without both.)
6. **Install the app.** Download `MicroFoods.apk` from the latest *Release* of this repo on your phone and install it.
   Open *Settings*, paste the URL and the token, tap *Save and test the connection*.
7. **Measure your kitchen.** Fill your katori with water and weigh it (grams of water = ml). Weigh one chapati.
   Type both into *Settings > Your kitchen*.

Changed the script later? *Deploy > Manage deployments > Edit > Version: New version*. The URL stays the same.

Want to look around first? Type `demo` as the web app link: the app answers with sample data and sends nothing.

## The accuracy test (what 0.1 is for)

Turn on *Settings > Test mode*. For 20 meals: weigh each item as you serve it, take the photo, add your usual hint,
and type the scale's grams into each item's *Weighed g* box before you confirm. *Settings > Accuracy test* then shows
the median error of four ways of getting grams (photo only, photo + hint, the AI's count x your sizes, and after your
fixes) and whether the pass line from the spec is met:

- median meal kcal and protein error 20% or less, day totals 15% or less (after your fixes)
- foods named right in at least 18 of 20 meals

The *Calibration* tab in the Sheet holds the same numbers row by row.

## What goes where

| Tab | What it holds |
| --- | --- |
| Log | One row per food item. Never edited in place. `row_id` is made on the phone, so retries never duplicate. |
| Days | Day type (Gym, Rest, Travel, Outside food, Festival) and weight, one row per date. |
| Daily totals | A `QUERY` over Log: kcal, protein, carbs, fat, fibre per day, with day type and the gap to target. |
| Targets | 2,300 kcal and 175 g protein. Change them here and in the app. |
| Calibration | Every weighed item: weighed vs confirmed vs AI grams, with errors and medians. |
| Weekly report | Written every Sunday at 21:00 IST, and emailed to you. |

## Where the numbers come from

`data/foods.json` (39 foods) is built by `tools/build_foods.py` from the open
[Indian Nutrient Databank](https://github.com/lindsayjaacks/Indian-Nutrient-Databank-INDB-) (recipes, per 100 g),
the UK CoFID rows shipped in that repo (ghee, butter, oil, sugar, dahi, pav, peanuts) and USDA FoodData Central 173424
for boiled egg. A row is used only if its fat is 50 g per 100 g or less and its kcal is within 15% of what its own
protein, carbs, fat and fibre add up to. 157 of INDB's 1,014 recipes fail one of those checks. INDB's boiled egg
(45 kcal per 100 g) passes them but is wrong, so eggs come from USDA.

Not in INDB at all: bhakri, varan (mapped to INDB's mixed dal for now), amti, usal (whole moong and moth are close),
misal, pithla, zunka, thalipeeth, plain dahi, sheera, vada pav. Add packaged foods from their label in *Settings > Add
a food from its label*. Home recipes measured by weight ("My recipes") arrive in 0.2.

Katori weights per ml (rice 0.8, poha 0.55, bhaji 0.6 to 0.9, dal 1.0) are estimates until the accuracy test
measures them. "Ghee roti" adds 1 tsp ghee per chapati unless your hint says otherwise ("half tsp ghee").

INDB states no licence, so this repo stays private.

## How it is tested

- `core` (pure Kotlin): 39 checks covering the food list, search by Marathi and Hindi names, portions, the 4 AM day
  boundary, parsing the AI's answer, confirm, Sheet rows, the accuracy report, and saving and loading. Expected
  numbers are worked out by hand from the food list.
- `apps-script/test`: 14 tests run the script against in-memory fakes of Sheets, Drive, Gemini and Mail: token,
  setup and formulas, append without duplicates, photos, days, analyze with fallback model, and the weekly report.
- The emulator run (GitHub Actions) taps through the whole loop in demo mode and publishes screenshots of every
  screen as a *UI check* pre-release.

Build locally: `./gradlew :core:test :app:assembleRelease` and `node --test apps-script/test/run.js`.
