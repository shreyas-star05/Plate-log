// Tests for apps-script/Code.gs with in-memory fakes of the Apps Script services.
// Run: node --test apps-script/test/
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.join(__dirname, '..', '..');
const CODE = fs.readFileSync(path.join(ROOT, 'apps-script', 'Code.gs'), 'utf8');

// ---------- fakes ----------
function colNum(letters) { let n = 0; for (const ch of letters) n = n * 26 + (ch.charCodeAt(0) - 64); return n; }

class FakeSheet {
  constructor(name) { this.name = name; this.cells = []; this.formulas = {}; this.validations = {}; this.frozen = 0; }
  getName() { return this.name; }
  getLastRow() { for (let r = this.cells.length; r > 0; r--) if ((this.cells[r - 1] || []).some(v => v !== '' && v !== undefined)) return r; return 0; }
  getLastColumn() { return Math.max(0, ...this.cells.map(r => r.length)); }
  setFrozenRows(n) { this.frozen = n; }
  get(r, c) { return ((this.cells[r - 1] || [])[c - 1]) ?? ''; }
  put(r, c, v) { while (this.cells.length < r) this.cells.push([]); const row = this.cells[r - 1]; while (row.length < c) row.push(''); row[c - 1] = v; }
  getRange(a, b, c, d) {
    if (typeof a === 'string') {
      const m = a.match(/^([A-Z]+)(\d+)?(?::([A-Z]+)(\d+)?)?$/);
      const r1 = +(m[2] || 1), c1 = colNum(m[1]);
      const c2 = m[3] ? colNum(m[3]) : c1, r2 = m[3] ? (m[4] ? +m[4] : 1000) : r1;
      return new FakeRange(this, r1, c1, r2 - r1 + 1, c2 - c1 + 1);
    }
    return new FakeRange(this, a, b, c || 1, d || 1);
  }
  getDataRange() { return new FakeRange(this, 1, 1, this.getLastRow(), this.getLastColumn()); }
  insertRowsBefore(r, n) { this.cells.splice(r - 1, 0, ...Array.from({ length: n }, () => [])); }
  rows() { return this.cells.slice(0, this.getLastRow()); }
}
class FakeRange {
  constructor(sh, r, c, nr, nc) { Object.assign(this, { sh, r, c, nr, nc }); }
  getValues() { const out = []; for (let i = 0; i < this.nr; i++) { const row = []; for (let j = 0; j < this.nc; j++) row.push(this.sh.get(this.r + i, this.c + j)); out.push(row); } return out; }
  setValues(v) {
    assert.equal(v.length, this.nr, 'setValues row count'); v.forEach(row => assert.equal(row.length, this.nc, 'setValues col count'));
    v.forEach((row, i) => row.forEach((x, j) => this.sh.put(this.r + i, this.c + j, x))); return this;
  }
  setFormula(f) { this.sh.formulas[`${this.r},${this.c}`] = f; this.sh.put(this.r, this.c, '(formula)'); return this; }
  setDataValidation(v) { this.sh.validations[`${this.r},${this.c}`] = v; return this; }
}

function makeEnv(props = {}) {
  const sheets = {};
  const calls = { fetch: [], mail: [], files: [], triggers: [], logs: [] };
  const responses = [];
  const store = Object.assign({}, props);
  const ss = {
    getSheetByName: n => sheets[n] || null,
    insertSheet: n => (sheets[n] = new FakeSheet(n)),
    setSpreadsheetTimeZone: () => {},
    getName: () => 'Micro Foods', getUrl: () => 'https://docs.google.com/spreadsheets/d/x'
  };
  const dv = { requireValueInList(list) { this.list = list; return this; }, build() { return { list: this.list }; } };
  const ctx = {
    SpreadsheetApp: { getActive: () => ss, newDataValidation: () => Object.create(dv) },
    PropertiesService: { getScriptProperties: () => ({ getProperty: k => (k in store ? store[k] : null), setProperty: (k, v) => { store[k] = v; } }) },
    UrlFetchApp: {
      fetch: (url, opts) => {
        calls.fetch.push({ url, opts, body: JSON.parse(opts.payload) });
        const r = responses.shift() || { code: 500, body: 'no fake response left' };
        return { getResponseCode: () => r.code, getContentText: () => (typeof r.body === 'string' ? r.body : JSON.stringify(r.body)) };
      }
    },
    LockService: { getScriptLock: () => ({ waitLock: () => {}, releaseLock: () => {} }) },
    DriveApp: {
      createFolder: () => ({ getId: () => 'folder1', createFile: b => { calls.files.push(b); return { getUrl: () => 'https://drive.google.com/file/' + b.name }; } }),
      getFolderById: () => ({ createFile: b => { calls.files.push(b); return { getUrl: () => 'https://drive.google.com/file/' + b.name }; } })
    },
    Utilities: {
      newBlob: (bytes, mime, name) => ({ bytes, mime, name }),
      base64Decode: s => Buffer.from(s, 'base64'),
      getUuid: () => require('node:crypto').randomUUID(),
      formatDate: (d, tz, fmt) => new Date(d.getTime() + 330 * 60000).toISOString().slice(0, 10)
    },
    ContentService: { createTextOutput: s => ({ s, setMimeType() { return this; } }), MimeType: { JSON: 'json' } },
    MailApp: { sendEmail: (to, subj, body) => calls.mail.push({ to, subj, body }) },
    Session: { getEffectiveUser: () => ({ getEmail: () => 'me@example.com' }) },
    ScriptApp: {
      getProjectTriggers: () => calls.triggers, deleteTrigger: t => calls.triggers.splice(calls.triggers.indexOf(t), 1),
      newTrigger: fn => ({ timeBased: () => ({ onWeekDay: day => ({ atHour: h => ({ create: () => calls.triggers.push({ fn, day, h, getHandlerFunction: () => fn }) }) }) }) }),
      WeekDay: { SUNDAY: 'SUNDAY' }
    },
    Logger: { log: s => calls.logs.push(s) },
    JSON, Math, Date, String, Object, Array, Error, Number, Buffer
  };
  vm.createContext(ctx);
  vm.runInContext(CODE, ctx);
  const post = body => JSON.parse(ctx.doPost({ postData: { contents: JSON.stringify(body) } }).s);
  return { ctx, sheets, calls, responses, store, post };
}

const FOODS = [
  { id: 'chapati', name: 'Chapati', aliases: ['poli'], units: ['piece', 'g'] },
  { id: 'dal', name: 'Dal / varan', aliases: ['varan'], units: ['katori', 'bowl', 'g'] },
  { id: 'ghee', name: 'Ghee', aliases: [], units: ['tsp', 'g'] }
];
const geminiOk = (items, thought) => ({
  code: 200,
  body: { candidates: [{ content: { parts: (thought ? [{ text: 'thinking...', thought: true }] : []).concat([{ text: JSON.stringify({ items, note: 'lunch' }) }]) } }] }
});
const row = (id, extra = {}) => Object.assign({ row_id: id, meal_id: 'm1', item_id: 'i' + id, day: '2026-11-14', item: 'Chapati', food_id: 'chapati', kcal: 218.5, status: 'active' }, extra);

// ---------- tests ----------
test('Log columns match the app (core Meals.kt LOG_COLUMNS)', () => {
  const kt = fs.readFileSync(path.join(ROOT, 'core/src/main/kotlin/com/microfoods/core/Meals.kt'), 'utf8');
  const block = kt.match(/val LOG_COLUMNS = listOf\(([\s\S]*?)\)/)[1];
  const cols = [...block.matchAll(/"([a-z_0-9]+)"/g)].map(m => m[1]);
  const { ctx } = makeEnv();
  assert.deepEqual(Array.from(vm.runInContext('LOG_COLUMNS', ctx)), cols);
});

test('a wrong or missing token is refused before anything runs', () => {
  const e1 = makeEnv({ TOKEN: 'secret-token-1234567890' });
  assert.equal(e1.post({ token: 'nope', action: 'ping' }).ok, false);
  const e2 = makeEnv({});
  assert.equal(e2.post({ token: '', action: 'ping' }).ok, false);
  assert.equal(e1.post({ token: 'secret-token-1234567890', action: 'ping' }).ok, true);
  assert.match(e1.post({ token: 'secret-token-1234567890', action: 'dance' }).error, /Unknown action/);
});

test('column letters', () => {
  const { ctx } = makeEnv();
  assert.equal(ctx.colLetter_(1), 'A'); assert.equal(ctx.colLetter_(26), 'Z'); assert.equal(ctx.colLetter_(27), 'AA'); assert.equal(ctx.colLetter_(31), 'AE');
  assert.equal(ctx.col_('day'), 'E'); assert.equal(ctx.col_('kcal'), 'S'); assert.equal(ctx.col_('status'), 'AB'); assert.equal(ctx.col_('weighed_g'), 'R');
});

test('setup makes every tab, headers, the day-type dropdown and formulas on the right columns', () => {
  const env = makeEnv({ TOKEN: 't'.repeat(32) });
  assert.equal(env.post({ token: 't'.repeat(32), action: 'setup' }).ok, true);
  assert.deepEqual(Object.keys(env.sheets).sort(), ['Calibration', 'Daily totals', 'Days', 'Log', 'Targets', 'Weekly report']);
  assert.equal(env.sheets.Log.rows()[0].length, 31);
  assert.deepEqual(Array.from(env.sheets.Days.rows()[0]), ['day', 'day_type', 'weight_kg', 'notes']);
  assert.deepEqual(Array.from(env.sheets.Days.validations['2,2'].list), ['Gym', 'Rest', 'Travel', 'Outside food', 'Festival']);
  assert.deepEqual(JSON.parse(JSON.stringify(env.sheets.Targets.rows())), [['target', 'per day'], ['kcal', 2300], ['protein_g', 175]]);
  const f = env.sheets['Daily totals'].formulas['1,1'];
  assert.match(f, /^=QUERY\(Log!A1:AE, "select E, sum\(S\), sum\(T\), sum\(U\), sum\(V\), sum\(W\), count\(A\) where AB = 'active' and H <> 'UNMATCHED'/);
  assert.match(env.sheets.Calibration.formulas['2,1'], /FILTER\(\{Log!E2:E, Log!G2:G, Log!H2:H, Log!R2:R, Log!P2:P, Log!M2:M, Log!N2:N, Log!O2:O\}, Log!R2:R<>"", Log!AB2:AB="active"\)/);
  // running setup again keeps existing data
  env.sheets.Log.put(2, 1, 'keep-me');
  env.post({ token: 't'.repeat(32), action: 'setup' });
  assert.equal(env.sheets.Log.get(2, 1), 'keep-me');
});

test('append writes rows by column name and never duplicates a row id', () => {
  const T = 't'.repeat(32);
  const env = makeEnv({ TOKEN: T });
  const r1 = env.post({ token: T, action: 'append', rows: [row('a'), row('b', { kcal: 92.9, item: 'Dal' }), row('a')] });
  assert.deepEqual([r1.added, r1.skipped], [2, 1]);
  const log = env.sheets.Log;
  const hdr = log.rows()[0];
  assert.equal(log.getLastRow(), 3);
  assert.equal(log.get(3, hdr.indexOf('kcal') + 1), 92.9);
  assert.equal(log.get(3, hdr.indexOf('item') + 1), 'Dal');
  assert.equal(log.get(2, hdr.indexOf('weighed_g') + 1), '', 'missing fields are blank');
  const r2 = env.post({ token: T, action: 'append', rows: [row('b'), row('c')] });
  assert.deepEqual([r2.added, r2.skipped], [1, 1]);
  assert.deepEqual(Array.from(log.rows().slice(1).map(r => r[0])), ['a', 'b', 'c']);
});

test('append saves a queued photo once and links it on that meal’s rows', () => {
  const T = 't'.repeat(32);
  const env = makeEnv({ TOKEN: T });
  const r = env.post({ token: T, action: 'append', rows: [row('a'), row('b', { meal_id: 'm2' })], meals: [{ meal_id: 'm1', photo_b64: Buffer.from('jpeg').toString('base64'), mime: 'image/jpeg' }] });
  assert.equal(env.calls.files.length, 1);
  assert.equal(r.photo_urls.m1, 'https://drive.google.com/file/m1.jpg');
  const hdr = env.sheets.Log.rows()[0];
  assert.equal(env.sheets.Log.get(2, hdr.indexOf('photo_url') + 1), 'https://drive.google.com/file/m1.jpg');
  assert.equal(env.sheets.Log.get(3, hdr.indexOf('photo_url') + 1), '');
  assert.equal(env.store.PHOTO_FOLDER_ID, 'folder1');
});

test('days are inserted once and updated in place', () => {
  const T = 't'.repeat(32);
  const env = makeEnv({ TOKEN: T });
  env.post({ token: T, action: 'append', rows: [], days: [{ day: '2026-11-14', day_type: 'Gym' }] });
  env.post({ token: T, action: 'append', rows: [], days: [{ day: '2026-11-14', day_type: 'Gym', weight_kg: 84.2 }, { day: '2026-11-15', day_type: 'Rest' }] });
  assert.deepEqual(JSON.parse(JSON.stringify(env.sheets.Days.rows().slice(1).map(r => r.slice(0, 3)))), [['2026-11-14', 'Gym', 84.2], ['2026-11-15', 'Rest', '']]);
});

test('analyze: key in a header, food ids as an enum with UNKNOWN, hint in the prompt, thoughts skipped', () => {
  const T = 't'.repeat(32);
  const env = makeEnv({ TOKEN: T, GEMINI_API_KEY: 'k-123' });
  env.responses.push(geminiOk([{ food_id: 'chapati', seen_as: 'Chapati', qty: 3, unit: 'piece', est_grams: 105, confidence: 0.9 }], true));
  const r = env.post({ token: T, action: 'analyze', foods: FOODS, hint: 'ghee roti', image_b64: 'aGk=', mime: 'image/jpeg' });
  assert.equal(r.ok, true); assert.equal(r.model, 'gemini-3.8-flash'); assert.equal(r.items[0].qty, 3); assert.equal(r.note, 'lunch');
  const call = env.calls.fetch[0];
  assert.match(call.url, /models\/gemini-3\.8-flash:generateContent$/);
  assert.equal(call.opts.headers['x-goog-api-key'], 'k-123');
  assert.ok(!call.url.includes('k-123'), 'key must not be in the URL');
  const schema = call.body.generationConfig.responseSchema;
  assert.deepEqual(Array.from(schema.properties.items.items.properties.food_id.enum), ['chapati', 'dal', 'ghee', 'UNKNOWN']);
  const prompt = call.body.contents[0].parts[0].text;
  assert.match(prompt, /HINT from the person.*ghee roti/);
  assert.match(prompt, /dal: Dal \/ varan \(also: varan\) \[units: katori, bowl, g\]/);
  assert.match(prompt, /Do NOT return calories/);
  assert.equal(call.body.contents[0].parts[1].inlineData.data, 'aGk=');
  assert.equal(env.calls.files.length, 0, 'no photo saved unless asked');
});

test('analyze: photo-only run leaves the hint out; typed entry works without a photo', () => {
  const T = 't'.repeat(32);
  const env = makeEnv({ TOKEN: T, GEMINI_API_KEY: 'k' });
  env.responses.push(geminiOk([]), geminiOk([]));
  env.post({ token: T, action: 'analyze', foods: FOODS, hint: 'ghee roti', with_hint: false, image_b64: 'aGk=' });
  assert.match(env.calls.fetch[0].body.contents[0].parts[0].text, /No hint was given/);
  env.post({ token: T, action: 'analyze', foods: FOODS, hint: '3 chapati, 1 katori dal' });
  const b = env.calls.fetch[1].body.contents[0];
  assert.equal(b.parts.length, 1); assert.match(b.parts[0].text, /There is no photo/);
  assert.equal(env.post({ token: T, action: 'analyze', foods: FOODS }).ok, false);
});

test('analyze: quota error falls back to the second model; a bad request does not', () => {
  const T = 't'.repeat(32);
  const env = makeEnv({ TOKEN: T, GEMINI_API_KEY: 'k' });
  env.responses.push({ code: 429, body: 'quota' }, geminiOk([]));
  const r = env.post({ token: T, action: 'analyze', foods: FOODS, image_b64: 'aGk=', save_photo: true, meal_id: 'm9' });
  assert.equal(r.ok, true); assert.equal(r.model, 'gemini-3.5-flash-lite');
  assert.equal(r.photo_url, 'https://drive.google.com/file/m9.jpg');
  env.responses.push({ code: 400, body: 'bad schema' });
  const r2 = env.post({ token: T, action: 'analyze', foods: FOODS, image_b64: 'aGk=' });
  assert.equal(r2.ok, false); assert.match(r2.error, /400: bad schema/);
  assert.equal(env.calls.fetch.length, 3);
});

test('analyze: model names come from Script properties when set; no key is a clear error', () => {
  const T = 't'.repeat(32);
  const env = makeEnv({ TOKEN: T, GEMINI_API_KEY: 'k', MODEL: 'gemini-x' });
  env.responses.push(geminiOk([]));
  env.post({ token: T, action: 'analyze', foods: FOODS, image_b64: 'aGk=' });
  assert.match(env.calls.fetch[0].url, /gemini-x:generateContent/);
  const env2 = makeEnv({ TOKEN: T });
  assert.match(env2.post({ token: T, action: 'analyze', foods: FOODS, image_b64: 'aGk=' }).error, /GEMINI_API_KEY/);
});

test('weekly summary arithmetic', () => {
  const { ctx } = makeEnv();
  const log = [
    // Mon 9th..Sun 15th Nov 2026. Two full days, one unmatched and one replaced row that must not count.
    row('1', { day: '2026-11-10', slot: 'Lunch', item: 'Chapati', kcal: 1200, protein_g: 80, confidence: 0.9 }),
    row('2', { day: '2026-11-10', slot: 'Dinner', item: 'Dal', kcal: 1100, protein_g: 100, confidence: 0.4 }),
    row('3', { day: '2026-11-12', slot: 'Lunch', item: 'Chapati', kcal: 1800, protein_g: 120, confidence: 0.8 }),
    row('4', { day: '2026-11-12', slot: 'Lunch', item: 'Pickle', food_id: 'UNMATCHED', kcal: 0, protein_g: 0 }),
    row('5', { day: '2026-11-12', slot: 'Lunch', item: 'Chapati', kcal: 999, protein_g: 99, status: 'replaced' }),
    row('6', { day: '2026-11-08', slot: 'Lunch', item: 'Old', kcal: 5000, protein_g: 1 })
  ];
  const days = [
    { day: '2026-11-08', day_type: 'Rest', weight_kg: 85.0 },
    { day: '2026-11-10', day_type: 'Gym', weight_kg: '' },
    { day: '2026-11-12', day_type: 'Rest', weight_kg: 84.4 }
  ];
  const s = ctx.weekSummary_(log, days, '2026-11-15', 2300, 175);
  assert.equal(s.start, '2026-11-09'); assert.equal(s.end, '2026-11-15');
  assert.equal(s.daysLogged, 2);
  assert.equal(s.avgKcal, 2050); assert.equal(s.avgProtein, 150);
  assert.equal(s.kcalDaysOnTarget, 1, '2300 is on target, 1800 is 22% under');
  assert.equal(s.proteinDaysOnTarget, 1);
  assert.equal(s.gymAvgKcal, 2300); assert.equal(s.restAvgKcal, 1800);
  assert.deepEqual(JSON.parse(JSON.stringify(s.proteinBySlot)), { Lunch: 100, Dinner: 50 });
  assert.deepEqual(JSON.parse(JSON.stringify(s.topFoods)), [['Chapati', 3000], ['Dal', 1100]]);
  assert.equal(s.unmatched, 1); assert.equal(s.lowConfidence, 1);
  assert.equal(s.weightNow, 84.4); assert.equal(s.weightBefore, 85.0);
});

test('weekly report writes a block to the tab and emails it; triggers are installed once', () => {
  const env = makeEnv({ TOKEN: 't'.repeat(32) });
  env.ctx.setup_();
  env.sheets.Log.getRange(2, 1, 1, 31).setValues([env.sheets.Log.rows()[0].map(h => ({ row_id: 'x', day: new Date().toISOString().slice(0, 10), item: 'Chapati', food_id: 'chapati', kcal: 300, protein_g: 9, status: 'active', slot: 'Lunch', confidence: 0.9 })[h] ?? '')]);
  env.ctx.weeklyReport();
  assert.equal(env.calls.mail.length, 1);
  assert.match(env.calls.mail[0].body, /Average: 300 kcal, 9 g protein/);
  assert.match(env.sheets['Weekly report'].get(1, 1), /^Micro Foods week/);
  env.ctx.installTriggers(); env.ctx.installTriggers();
  assert.equal(env.calls.triggers.length, 1);
  assert.deepEqual([env.calls.triggers[0].day, env.calls.triggers[0].h], ['SUNDAY', 21]);
});

test('first-time setup creates a 40-character token and logs it', () => {
  const env = makeEnv({});
  env.ctx.firstTimeSetup();
  assert.equal(env.store.TOKEN.length, 40);
  assert.ok(env.calls.logs.some(l => l.includes(env.store.TOKEN)));
});
