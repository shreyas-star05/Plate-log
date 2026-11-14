/**
 * Micro Foods backend: an Apps Script bound to your Google Sheet.
 *
 *  - analyze: sends the meal photo (or typed description) to Gemini with your food list; Gemini must pick
 *    each item from that list and return count, unit and a gram estimate. It never returns calories.
 *  - append:  writes confirmed rows to the Log tab. Each row carries a UUID made on the phone, and rows
 *    already in the Sheet are skipped, so retries from the offline queue never duplicate.
 *  - weeklyReport: runs Sunday 21:00 IST, writes the Weekly report tab and emails you the summary.
 *
 * First time: run firstTimeSetup() from the editor (Run menu). It creates the tabs, the token and the
 * Sunday trigger, and prints the token. Then set GEMINI_API_KEY in Project Settings > Script properties.
 */

// Same order as Meal.LOG_COLUMNS in the app (a test in the repo checks they match).
var LOG_COLUMNS = [
  'row_id', 'meal_id', 'item_id', 'logged_at', 'day', 'slot', 'item', 'food_id', 'source', 'qty', 'unit',
  'ai_qty', 'ai_grams', 'ai_grams_nohint', 'ai_unit_grams', 'grams', 'grams_source', 'weighed_g',
  'kcal', 'protein_g', 'carbs_g', 'fat_g', 'fibre_g', 'addon_for', 'confidence', 'hint', 'photo_url',
  'status', 'model', 'db_version', 'app_version'
];
var DAYS_COLUMNS = ['day', 'day_type', 'weight_kg', 'notes'];
var DAY_TYPES = ['Gym', 'Rest', 'Travel', 'Outside food', 'Festival'];
var UNITS = ['piece', 'katori', 'bowl', 'plate', 'glass', 'cup', 'scoop', 'tbsp', 'tsp', 'g', 'ml'];
var DEFAULT_MODEL = 'gemini-3.8-flash';
var DEFAULT_FALLBACK = 'gemini-3.5-flash-lite';
var TABS = { log: 'Log', days: 'Days', daily: 'Daily totals', targets: 'Targets', calib: 'Calibration', weekly: 'Weekly report' };

// ---------------------------------------------------------------- web endpoints

function doGet() {
  return json_({ ok: true, app: 'micro-foods' });
}

function doPost(e) {
  try {
    var req = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    var token = prop_('TOKEN');
    if (!token || req.token !== token) return json_({ ok: false, error: 'Wrong token. Copy it again from the script log.' });
    switch (req.action) {
      case 'ping': return json_({ ok: true, sheet: SpreadsheetApp.getActive().getName(), model: prop_('MODEL') || DEFAULT_MODEL, hasKey: !!prop_('GEMINI_API_KEY') });
      case 'setup': setup_(); return json_({ ok: true });
      case 'analyze': return json_(analyze_(req));
      case 'append': return json_(append_(req));
      default: return json_({ ok: false, error: 'Unknown action: ' + req.action });
    }
  } catch (err) {
    return json_({ ok: false, error: String((err && err.message) || err) });
  }
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function prop_(k) {
  return PropertiesService.getScriptProperties().getProperty(k);
}

// ---------------------------------------------------------------- analyze

function buildPrompt_(foods, hint, hasPhoto) {
  var lines = foods.map(function (f) {
    var al = (f.aliases && f.aliases.length) ? ' (also: ' + f.aliases.join(', ') + ')' : '';
    return f.id + ': ' + f.name + al + ' [units: ' + (f.units || []).join(', ') + ']';
  });
  return [
    'You log meals for a nutrition tracker. The food is mostly Maharashtrian home cooking.',
    hasPhoto ? 'Look at the photo and list every distinct food item on the plate or in the bowls.'
             : 'There is no photo. Read the description and list every food item in it.',
    'For each item:',
    '- food_id: pick from the FOOD LIST below. Use the closest home-style match when it is clearly the same dish',
    '  (varan or amti -> dal, matki usal -> matki, poli -> chapati). Use UNKNOWN if nothing on the list fits;',
    '  never force a wrong match.',
    '- seen_as: a short name for what you see, in the words the person would use.',
    '- qty and unit: prefer a unit listed for that food. Count pieces of chapati, bhakri, pav, eggs.',
    '  A katori is a small steel bowl of about 150 ml.',
    '- est_grams: your own estimate of the weight of that item as served.',
    '- confidence: 0 to 1, how sure you are of both the food and the amount.',
    'Do NOT return calories or nutrients; those come from a database.',
    'Fats and sugar mentioned in the hint (ghee, butter, oil, sugar) are separate items with food_id ghee, butter,',
    'oil or sugar, unit tsp, and addon_for set to the seen_as of the item they go on. "Ghee roti" or "butter',
    'chapati" means 1 tsp per piece unless the hint gives an amount. Add no fat that is neither in the hint nor',
    'clearly visible (a pat of butter, a pool of oil).',
    hint ? 'HINT from the person (trust it for names, counts and preparation): ' + hint : 'No hint was given.',
    '',
    'FOOD LIST (id: name [units]):',
    lines.join('\n')
  ].join('\n');
}

function buildSchema_(foods) {
  var ids = foods.map(function (f) { return f.id; }).concat(['UNKNOWN']);
  return {
    type: 'OBJECT',
    properties: {
      items: {
        type: 'ARRAY',
        items: {
          type: 'OBJECT',
          properties: {
            food_id: { type: 'STRING', format: 'enum', enum: ids },
            seen_as: { type: 'STRING' },
            qty: { type: 'NUMBER' },
            unit: { type: 'STRING', format: 'enum', enum: UNITS },
            est_grams: { type: 'NUMBER' },
            confidence: { type: 'NUMBER' },
            addon_for: { type: 'STRING' }
          },
          required: ['food_id', 'seen_as', 'qty', 'unit', 'est_grams', 'confidence']
        }
      },
      note: { type: 'STRING' }
    },
    required: ['items']
  };
}

/** The answer text, skipping any "thought" parts that thinking models add. */
function answerText_(resp) {
  var c = resp && resp.candidates && resp.candidates[0];
  var parts = (c && c.content && c.content.parts) || [];
  var text = '';
  parts.forEach(function (p) { if (p.text && !p.thought) text = p.text; });
  if (!text) throw new Error('Gemini returned no answer' + (c && c.finishReason ? ' (' + c.finishReason + ')' : ''));
  return text;
}

function callGemini_(model, body, key) {
  var res = UrlFetchApp.fetch('https://generativelanguage.googleapis.com/v1beta/models/' + model + ':generateContent', {
    method: 'post',
    contentType: 'application/json',
    headers: { 'x-goog-api-key': key },
    payload: JSON.stringify(body),
    muteHttpExceptions: true
  });
  return { code: res.getResponseCode(), text: res.getContentText() };
}

function analyze_(req) {
  var key = prop_('GEMINI_API_KEY');
  if (!key) return { ok: false, error: 'GEMINI_API_KEY is not set in Script properties.' };
  var foods = req.foods || [];
  if (!foods.length) return { ok: false, error: 'No food list was sent.' };
  var hasPhoto = !!req.image_b64;
  var hint = req.with_hint === false ? '' : String(req.hint || '').slice(0, 300);
  if (!hasPhoto && !hint) return { ok: false, error: 'Nothing to analyse: no photo and no description.' };
  var parts = [{ text: buildPrompt_(foods, hint, hasPhoto) }];
  if (hasPhoto) parts.push({ inlineData: { mimeType: req.mime || 'image/jpeg', data: req.image_b64 } });
  var body = {
    contents: [{ role: 'user', parts: parts }],
    generationConfig: { responseMimeType: 'application/json', responseSchema: buildSchema_(foods), temperature: 0.2 }
  };
  var models = [prop_('MODEL') || DEFAULT_MODEL, prop_('FALLBACK_MODEL') || DEFAULT_FALLBACK];
  var last = '';
  for (var i = 0; i < models.length; i++) {
    var r = callGemini_(models[i], body, key);
    if (r.code === 200) {
      var parsed = JSON.parse(answerText_(JSON.parse(r.text)));
      var out = { ok: true, model: models[i], items: parsed.items || [], note: parsed.note || '' };
      if (hasPhoto && req.save_photo) out.photo_url = savePhoto_(req.image_b64, req.mime, req.meal_id);
      return out;
    }
    last = models[i] + ' answered ' + r.code + ': ' + String(r.text).slice(0, 200);
    // quota, overload or a retired model name: try the fallback; anything else is our request's fault
    if ([404, 429, 500, 503].indexOf(r.code) < 0) break;
  }
  return { ok: false, error: last };
}

function savePhoto_(b64, mime, mealId) {
  var folderId = prop_('PHOTO_FOLDER_ID');
  var folder;
  if (folderId) {
    folder = DriveApp.getFolderById(folderId);
  } else {
    folder = DriveApp.createFolder('Micro Foods photos');
    PropertiesService.getScriptProperties().setProperty('PHOTO_FOLDER_ID', folder.getId());
  }
  var blob = Utilities.newBlob(Utilities.base64Decode(b64), mime || 'image/jpeg', (mealId || Utilities.getUuid()) + '.jpg');
  return folder.createFile(blob).getUrl();
}

// ---------------------------------------------------------------- append

function append_(req) {
  var lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    var ss = SpreadsheetApp.getActive();
    var log = ss.getSheetByName(TABS.log) || setup_().log;
    var header = log.getRange(1, 1, 1, log.getLastColumn()).getValues()[0];
    var idCol = header.indexOf('row_id');
    var last = log.getLastRow();
    var seen = {};
    if (last > 1) log.getRange(2, idCol + 1, last - 1, 1).getValues().forEach(function (r) { seen[r[0]] = true; });

    var photoUrls = {};
    (req.meals || []).forEach(function (m) {
      if (m.photo_b64 && m.meal_id) photoUrls[m.meal_id] = savePhoto_(m.photo_b64, m.mime, m.meal_id);
    });

    var fresh = [];
    var skipped = 0;
    (req.rows || []).forEach(function (row) {
      if (!row.row_id || seen[row.row_id]) { skipped++; return; }
      seen[row.row_id] = true;
      if (!row.photo_url && photoUrls[row.meal_id]) row.photo_url = photoUrls[row.meal_id];
      fresh.push(header.map(function (h) { var v = row[h]; return v === undefined || v === null ? '' : v; }));
    });
    if (fresh.length) log.getRange(last + 1, 1, fresh.length, header.length).setValues(fresh);

    var daysSaved = upsertDays_(ss, req.days || []);
    return { ok: true, added: fresh.length, skipped: skipped, days: daysSaved, photo_urls: photoUrls };
  } finally {
    lock.releaseLock();
  }
}

function upsertDays_(ss, days) {
  if (!days.length) return 0;
  var sh = ss.getSheetByName(TABS.days) || setup_().days;
  var last = sh.getLastRow();
  var existing = last > 1 ? sh.getRange(2, 1, last - 1, 1).getValues().map(function (r) { return dayKey_(r[0]); }) : [];
  days.forEach(function (d) {
    var i = existing.indexOf(d.day);
    var row = [d.day, d.day_type || '', d.weight_kg === null || d.weight_kg === undefined ? '' : d.weight_kg];
    if (i >= 0) {
      sh.getRange(i + 2, 1, 1, 3).setValues([row]);
    } else {
      sh.getRange(sh.getLastRow() + 1, 1, 1, 3).setValues([row]);
      existing.push(d.day);
    }
  });
  return days.length;
}

/** A Days-tab cell as 'yyyy-mm-dd', whether Sheets stored it as a date or as text. */
function dayKey_(v) {
  if (v instanceof Date) return Utilities.formatDate(v, 'Asia/Kolkata', 'yyyy-MM-dd');
  return String(v);
}

// ---------------------------------------------------------------- setup

function colLetter_(n) {
  var s = '';
  while (n > 0) { var m = (n - 1) % 26; s = String.fromCharCode(65 + m) + s; n = Math.floor((n - 1) / 26); }
  return s;
}

function col_(name) {
  var i = LOG_COLUMNS.indexOf(name);
  if (i < 0) throw new Error('No column ' + name);
  return colLetter_(i + 1);
}

function dailyFormula_() {
  var c = col_;
  var lastCol = colLetter_(LOG_COLUMNS.length);
  return '=QUERY(' + TABS.log + '!A1:' + lastCol + ', "select ' + c('day') + ', sum(' + c('kcal') + '), sum(' + c('protein_g') +
    '), sum(' + c('carbs_g') + '), sum(' + c('fat_g') + '), sum(' + c('fibre_g') + '), count(' + c('row_id') +
    ') where ' + c('status') + " = 'active' and " + c('food_id') + " <> 'UNMATCHED' and " + c('day') + ' is not null group by ' +
    c('day') + ' order by ' + c('day') + ' desc label ' + c('day') + " 'day', sum(" + c('kcal') + ") 'kcal', sum(" + c('protein_g') +
    ") 'protein_g', sum(" + c('carbs_g') + ") 'carbs_g', sum(" + c('fat_g') + ") 'fat_g', sum(" + c('fibre_g') + ") 'fibre_g', count(" +
    c('row_id') + ") 'items'\", 1)";
}

function calibrationFormula_() {
  var r = function (n) { return TABS.log + '!' + col_(n) + '2:' + col_(n); };
  return '=IFERROR(FILTER({' + [r('day'), r('item'), r('food_id'), r('weighed_g'), r('grams'), r('ai_grams'), r('ai_grams_nohint'), r('ai_unit_grams')].join(', ') +
    '}, ' + r('weighed_g') + '<>"", ' + r('status') + '="active"), "No weighed items yet")';
}

function setup_() {
  var ss = SpreadsheetApp.getActive();
  ss.setSpreadsheetTimeZone('Asia/Kolkata');
  var get = function (name) { return ss.getSheetByName(name) || ss.insertSheet(name); };

  var log = get(TABS.log);
  if (log.getLastRow() === 0) log.getRange(1, 1, 1, LOG_COLUMNS.length).setValues([LOG_COLUMNS]);
  log.setFrozenRows(1);

  var days = get(TABS.days);
  if (days.getLastRow() === 0) days.getRange(1, 1, 1, DAYS_COLUMNS.length).setValues([DAYS_COLUMNS]);
  days.setFrozenRows(1);
  days.getRange('B2:B').setDataValidation(SpreadsheetApp.newDataValidation().requireValueInList(DAY_TYPES, true).build());

  var targets = get(TABS.targets);
  if (targets.getLastRow() === 0) targets.getRange(1, 1, 3, 2).setValues([['target', 'per day'], ['kcal', 2300], ['protein_g', 175]]);

  var daily = get(TABS.daily);
  daily.getRange('A1').setFormula(dailyFormula_());
  daily.getRange('H1:J1').setValues([['day_type', 'kcal_vs_target', 'protein_vs_target']]);
  daily.getRange('H2').setFormula('=ARRAYFORMULA(IF(A2:A="",,IFERROR(VLOOKUP(A2:A, ' + TABS.days + '!A:B, 2, FALSE), "")))');
  daily.getRange('I2').setFormula('=ARRAYFORMULA(IF(A2:A="",,B2:B - ' + TABS.targets + '!B2))');
  daily.getRange('J2').setFormula('=ARRAYFORMULA(IF(A2:A="",,C2:C - ' + TABS.targets + '!B3))');

  var calib = get(TABS.calib);
  calib.getRange('A1:J1').setValues([['day', 'item', 'food_id', 'weighed_g', 'confirmed_g', 'ai_g_photo_hint', 'ai_g_photo_only', 'ai_g_count', 'confirmed_err', 'ai_err']]);
  calib.getRange('A2').setFormula(calibrationFormula_());
  calib.getRange('I2').setFormula('=ARRAYFORMULA(IF(ISNUMBER(D2:D), (E2:E - D2:D) / D2:D, ))');
  calib.getRange('J2').setFormula('=ARRAYFORMULA(IF(ISNUMBER(D2:D)*ISNUMBER(F2:F), (F2:F - D2:D) / D2:D, ))');
  calib.getRange('L1:M3').setValues([['median |error|', ''], ['after your fixes', ''], ['AI photo + hint', '']]);
  calib.getRange('M2').setFormula('=IFERROR(MEDIAN(ARRAYFORMULA(ABS(FILTER(I2:I, ISNUMBER(I2:I))))), "")');
  calib.getRange('M3').setFormula('=IFERROR(MEDIAN(ARRAYFORMULA(ABS(FILTER(J2:J, ISNUMBER(J2:J))))), "")');

  get(TABS.weekly);
  return { log: log, days: days };
}

/** Run once from the editor: tabs, a token for the app, and the Sunday trigger. */
function firstTimeSetup() {
  setup_();
  var props = PropertiesService.getScriptProperties();
  if (!props.getProperty('TOKEN')) props.setProperty('TOKEN', (Utilities.getUuid() + Utilities.getUuid()).replace(/-/g, '').slice(0, 40));
  installTriggers();
  Logger.log('Micro Foods token (paste into the app): ' + props.getProperty('TOKEN'));
  if (!props.getProperty('GEMINI_API_KEY')) Logger.log('Now add GEMINI_API_KEY in Project Settings > Script properties.');
}

function installTriggers() {
  ScriptApp.getProjectTriggers().forEach(function (t) { if (t.getHandlerFunction() === 'weeklyReport') ScriptApp.deleteTrigger(t); });
  ScriptApp.newTrigger('weeklyReport').timeBased().onWeekDay(ScriptApp.WeekDay.SUNDAY).atHour(21).create();
}

// ---------------------------------------------------------------- weekly report

function readTable_(sh) {
  var v = sh.getDataRange().getValues();
  var h = v.shift() || [];
  return v.map(function (r) { var o = {}; h.forEach(function (k, i) { o[k] = r[i]; }); return o; });
}

/**
 * The week's numbers. Pure: takes plain rows, so the repo's tests can check the arithmetic.
 * end = the Sunday (yyyy-mm-dd); the week is the 7 days ending on it.
 */
function weekSummary_(logRows, dayRows, end, kcalTarget, proteinTarget) {
  var endD = new Date(end + 'T00:00:00Z');
  var keys = [];
  for (var i = 6; i >= 0; i--) keys.push(new Date(endD.getTime() - i * 864e5).toISOString().slice(0, 10));
  var prevKeys = keys.map(function (k) { return new Date(new Date(k + 'T00:00:00Z').getTime() - 7 * 864e5).toISOString().slice(0, 10); });
  var rows = logRows.filter(function (r) { return r.status === 'active' && r.food_id !== 'UNMATCHED' && keys.indexOf(dayKey_(r.day)) >= 0; });
  var byDay = {};
  keys.forEach(function (k) { byDay[k] = { kcal: 0, protein: 0, items: 0 }; });
  rows.forEach(function (r) { var d = byDay[dayKey_(r.day)]; d.kcal += +r.kcal || 0; d.protein += +r.protein_g || 0; d.items++; });
  var logged = keys.filter(function (k) { return byDay[k].items > 0; });
  var avg = function (xs) { return xs.length ? xs.reduce(function (a, b) { return a + b; }, 0) / xs.length : null; };
  var dayType = {};
  var weight = {};
  dayRows.forEach(function (d) { var k = dayKey_(d.day); dayType[k] = d.day_type; if (d.weight_kg !== '' && d.weight_kg !== null) weight[k] = +d.weight_kg; });
  var bySlot = {};
  rows.forEach(function (r) { bySlot[r.slot] = (bySlot[r.slot] || 0) + (+r.protein_g || 0); });
  Object.keys(bySlot).forEach(function (s) { bySlot[s] = bySlot[s] / Math.max(1, logged.length); });
  var foods = {};
  rows.forEach(function (r) { foods[r.item] = (foods[r.item] || 0) + (+r.kcal || 0); });
  var top = Object.keys(foods).sort(function (a, b) { return foods[b] - foods[a]; }).slice(0, 5).map(function (k) { return [k, Math.round(foods[k])]; });
  var lastWeight = function (ks) { var w = null; ks.forEach(function (k) { if (weight[k] !== undefined) w = weight[k]; }); return w; };
  var gym = logged.filter(function (k) { return dayType[k] === 'Gym'; });
  var rest = logged.filter(function (k) { return dayType[k] === 'Rest'; });
  return {
    start: keys[0], end: keys[6], daysLogged: logged.length,
    avgKcal: avg(logged.map(function (k) { return byDay[k].kcal; })),
    avgProtein: avg(logged.map(function (k) { return byDay[k].protein; })),
    kcalDaysOnTarget: logged.filter(function (k) { return Math.abs(byDay[k].kcal - kcalTarget) <= 0.1 * kcalTarget; }).length,
    proteinDaysOnTarget: logged.filter(function (k) { return byDay[k].protein >= proteinTarget; }).length,
    gymAvgKcal: avg(gym.map(function (k) { return byDay[k].kcal; })),
    restAvgKcal: avg(rest.map(function (k) { return byDay[k].kcal; })),
    proteinBySlot: bySlot,
    topFoods: top,
    unmatched: logRows.filter(function (r) { return r.status === 'active' && r.food_id === 'UNMATCHED' && keys.indexOf(dayKey_(r.day)) >= 0; }).length,
    lowConfidence: rows.filter(function (r) { return +r.confidence < 0.5; }).length,
    weightNow: lastWeight(keys), weightBefore: lastWeight(prevKeys),
    byDay: keys.map(function (k) { return [k, Math.round(byDay[k].kcal), Math.round(byDay[k].protein), dayType[k] || '']; })
  };
}

function weeklyReport() {
  var ss = SpreadsheetApp.getActive();
  setup_();
  var end = Utilities.formatDate(new Date(), 'Asia/Kolkata', 'yyyy-MM-dd');
  var t = readTable_(ss.getSheetByName(TABS.targets));
  var target = function (name, d) { var r = t.filter(function (x) { return x.target === name; })[0]; return r ? +r['per day'] : d; };
  var s = weekSummary_(readTable_(ss.getSheetByName(TABS.log)), readTable_(ss.getSheetByName(TABS.days)), end, target('kcal', 2300), target('protein_g', 175));
  var r0 = function (x) { return x === null ? '-' : Math.round(x); };
  var lines = [
    'Micro Foods week ' + s.start + ' to ' + s.end + ' (' + s.daysLogged + ' of 7 days logged)',
    'Average: ' + r0(s.avgKcal) + ' kcal, ' + r0(s.avgProtein) + ' g protein a day',
    'Days within 10% of kcal target: ' + s.kcalDaysOnTarget + '; days at or above protein target: ' + s.proteinDaysOnTarget,
    'Gym days avg ' + r0(s.gymAvgKcal) + ' kcal; rest days avg ' + r0(s.restAvgKcal) + ' kcal',
    'Protein per logged day by slot: ' + Object.keys(s.proteinBySlot).map(function (k) { return k + ' ' + Math.round(s.proteinBySlot[k]) + ' g'; }).join(', '),
    'Top foods by kcal: ' + s.topFoods.map(function (x) { return x[0] + ' ' + x[1]; }).join(', '),
    'Unmatched items: ' + s.unmatched + '; low-confidence items: ' + s.lowConfidence,
    'Weight: ' + (s.weightNow === null ? 'no reading this week' : s.weightNow + ' kg') + (s.weightNow !== null && s.weightBefore !== null ? ' (' + (s.weightNow - s.weightBefore >= 0 ? '+' : '') + (Math.round((s.weightNow - s.weightBefore) * 10) / 10) + ' kg on last week)' : '')
  ];
  var sh = ss.getSheetByName(TABS.weekly);
  var block = lines.map(function (l) { return [l, '', '', '']; }).concat([['day', 'kcal', 'protein_g', 'day_type']]).concat(s.byDay).concat([['', '', '', '']]);
  sh.insertRowsBefore(1, block.length);
  sh.getRange(1, 1, block.length, 4).setValues(block);
  MailApp.sendEmail(Session.getEffectiveUser().getEmail(), 'Micro Foods: week to ' + s.end, lines.join('\n') + '\n\nSheet: ' + ss.getUrl());
  return s;
}
