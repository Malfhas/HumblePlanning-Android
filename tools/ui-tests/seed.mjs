// Disposable data set for the instrumented UI tests of the app (tools/ui-tests/run.ps1).
//
//   node tools/ui-tests/seed.mjs [--url http://localhost:3100] [--out tools/ui-tests/build/seed.json] [--today 2026-11-05]
//
// Goes through the REST API only, like tools/api-tests/run.mjs. Every run creates new roles, accounts,
// a project and tasks whose names carry a run suffix: running it again never touches an earlier set, and
// lockouts or one-shot accounts of an earlier run cannot get in the way. Nothing is removed afterwards: the
// server of tools/test-server is disposable (its database lives in memory).
//
// `--today` is the date of the emulator: the app and the tests read their "today" from it.
//
// The JSON written to --out is pushed to the emulator by run.ps1 and read by the tests (support/Seed.kt).
// It holds test passwords, a TOTP secret and backup codes of throwaway accounts: never run this against
// anything but the local test server. For that reason the address must be local.
import { mkdirSync, writeFileSync } from 'node:fs';
import { createHmac } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : fallback;
};

const URL_ = arg('url', 'http://localhost:3100').replace(/\/+$/, '');
const OUT = resolve(arg('out', resolve(here, 'build', 'seed.json')));
const TODAY = arg('today', new Date().toISOString().slice(0, 10));
if (!/^http:\/\/(localhost|127\.0\.0\.1):\d+$/.test(URL_)) {
  console.error(`Refusing ${URL_}: this script only runs against the local test server.`);
  process.exit(2);
}

const ADMIN = 'testadmin';
const ADMIN_PASSWORD = 'TestAdmin2026!x';
const PASSWORD = 'Campaign2026!xZ';
const APP_VERSION = '1.0.0';
// Same server, seen from the emulator
const EMULATOR_URL = 'http://10.0.2.2:3100';
const EMULATOR_MOCK = 'http://10.0.2.2:3102';
const RUN = Date.now().toString(36);
const prefix = `UI-${RUN}`;

const day = (offset) => {
  const d = new Date(`${TODAY}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + offset);
  return d.toISOString().slice(0, 10);
};

async function http(method, path, { body, cookie, web = false } = {}) {
  const res = await fetch(URL_ + path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(cookie ? { Cookie: cookie } : {}),
      ...(web ? {} : { 'X-App-Version': APP_VERSION }),
      'Accept-Language': 'en',
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    redirect: 'manual',
  });
  const text = await res.text();
  let parsed = null;
  try { parsed = JSON.parse(text); } catch { /* not JSON */ }
  return { status: res.status, body: parsed, text };
}

function must(what, res) {
  if (res.status >= 300) throw new Error(`${what}: HTTP ${res.status} ${res.text.slice(0, 400)}`);
  return res.body;
}

async function login(identifier, password) {
  const res = await http('POST', '/api/mobile/auth/login', { body: { identifier, password, device: { name: `seed ${RUN}`, appVersion: APP_VERSION, locale: 'en' } } });
  if (res.body?.status !== 'authenticated') throw new Error(`login ${identifier}: HTTP ${res.status} ${res.text.slice(0, 300)}`);
  return { cookie: `${res.body.session.cookieName}=${res.body.session.cookieValue}`, userId: res.body.session.userId };
}

/** RFC 6238 TOTP (SHA-1, 6 digits, 30 s). Waits for the next period near the end of this one: the server refuses a code just expired. */
async function totp(secret) {
  const left = 30000 - Date.now() % 30000;
  if (left < 6000) await new Promise((r) => setTimeout(r, left + 200));
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of secret.replace(/=+$/, '').toUpperCase()) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const hmac = createHmac('sha1', key).update(counter).digest();
  const offset = hmac[hmac.length - 1] & 15;
  return String((hmac.readUInt32BE(offset) & 0x7fffffff) % 1000000).padStart(6, '0');
}

async function main() {
  const info = must('info', await http('GET', '/api/mobile/info'));
  if (info.service !== 'humble-planning') throw new Error('not a Humble Planning server');
  const admin = await login(ADMIN, ADMIN_PASSWORD);

  // An earlier run that stopped half way must not leave the instance blocked
  const config = {
    MAINTENANCE_MODE: 'false', MOBILE_MIN_APP_VERSION: '', MOBILE_LOGIN_ENABLED: 'true', MOBILE_PUSH_ENABLED: 'true',
    TWOFACTOR_REQUIRED: 'false', PLANNING_MAX_TASKS_PER_DAY: '0', MOBILE_PUSH_RELAY_URL: '', MOBILE_PUSH_TRIGGERS: '',
  };
  for (const [key, value] of Object.entries(config)) must(`config ${key}`, await http('PUT', '/api/config', { cookie: admin.cookie, web: true, body: { [key]: value } }));

  // The assistant answers from the mock model of the test server
  const aiConfig = (await http('GET', '/api/ai-chat/admin/config', { cookie: admin.cookie, web: true })).body?.config ?? {};
  must('AI config', await http('PUT', '/api/ai-chat/admin/config', { cookie: admin.cookie, web: true, body: { ...aiConfig, enabled: true, apiKey: 'test-key', models: ['test/model'], defaultModel: 'test/model' } }));

  // Teams need a business unit, and a new instance has none
  const bu = must('business units', await http('GET', '/api/business-units', { cookie: admin.cookie }))?.[0]
    ?? must('business unit', await http('POST', '/api/business-units', { cookie: admin.cookie, body: { name: `${prefix}-bu`, number: RUN, color: '#6b7280', address: 'Test', latitude: '48.8566', longitude: '2.3522' } }));
  const statuses = must('statuses', await http('GET', '/api/statuses', { cookie: admin.cookie }));
  if (!statuses?.length) throw new Error('no status on this server');
  const defaultStatus = statuses.find((s) => s.isDefault) ?? statuses[0];

  // A status with a text style, to check that the planning shows it
  const styledStatus = must('status', await http('POST', '/api/statuses', {
    cookie: admin.cookie,
    body: { name: `${prefix}-styled`, color: '#AA3366', bold: true, italic: true, strikethrough: true, underline: false, showInList: true },
  }));

  const makeRole = async (name, permissions) => must(`role ${name}`, await http('POST', '/api/roles', {
    cookie: admin.cookie,
    body: { name: `${prefix}-${name}`, description: 'UI tests', color: '#6b7280', permissions },
  })).id;
  const pages = ['mobile_app:use', 'pages:planning', 'pages:home', 'pages:tasks', 'scope:projects_all', 'scope:users_all', 'scope:bu_all'];
  const tasksAll = ['tasks:create', 'tasks:delete', 'tasks:assign', 'tasks:move', 'tasks:resize'];
  const filesAll = ['files:view', 'files:upload', 'files:delete'];
  const roles = {
    reader: await makeRole('reader', pages),
    own: await makeRole('own', [...pages, 'scope:modify_own', ...tasksAll, ...filesAll]),
    team: await makeRole('team', [...pages, 'scope:modify_team', ...tasksAll]),
    full: await makeRole('full', [...pages, 'scope:modify_all', ...tasksAll, 'tasks:lock', ...filesAll, 'ai_chat:use', 'ai_chat:write']),
    noAssistant: await makeRole('no-assistant', [...pages, 'scope:modify_all', ...tasksAll, 'tasks:lock', ...filesAll]),
  };

  const makeTeam = async (name, color) => must(`team ${name}`, await http('POST', '/api/teams', {
    cookie: admin.cookie,
    body: { name: `${prefix}-${name}`, color, businessUnitId: bu?.id },
  }));
  const teamA = await makeTeam('team-a', '#3b82f6');
  const teamB = await makeTeam('team-b', '#ef4444');

  const accounts = { admin: { id: admin.userId, login: ADMIN, password: ADMIN_PASSWORD, name: ADMIN } };
  const makeUser = async (key, roleId, extra = {}) => {
    const login = `${prefix.toLowerCase()}-${key.toLowerCase()}`;
    const name = `${prefix} ${key}`;
    const user = must(`user ${key}`, await http('POST', '/api/users', {
      cookie: admin.cookie,
      body: {
        name, username: login, password: PASSWORD, roleId, color: '#3366ff', visibleOnPlanning: true,
        notifications: false, pushNotifications: false, smsNotifications: false, active: true, businessUnitId: bu?.id ?? null, ...extra,
      },
    }));
    accounts[key] = { id: user.id, login, password: PASSWORD, name };
    return accounts[key];
  };
  await makeUser('reader', roles.reader);
  await makeUser('own', roles.own);
  await makeUser('own2', roles.own);
  await makeUser('team', roles.team, { teamId: teamA.id });
  await makeUser('teammate', roles.own, { teamId: teamA.id });
  await makeUser('outsider', roles.own, { teamId: teamB.id });
  await makeUser('full', roles.full);
  await makeUser('noAssistant', roles.noAssistant);
  await makeUser('forced', roles.own, { forcePasswordChange: true });
  await makeUser('force2fa', roles.own, { forceTwoFactor: true });
  await makeUser('disabled', roles.own);
  must('disable', await http('PATCH', `/api/users/${accounts.disabled.id}`, { cookie: admin.cookie, body: { active: false } }));

  // Two-step verification on: the secret and the codes are what the phone of this person would hold
  const tf = await makeUser('twoFactor', roles.own);
  const tfSession = await login(tf.login, PASSWORD);
  const enabled = must('2FA enable', await http('POST', '/api/profile/2fa/enable', { cookie: tfSession.cookie, body: { password: PASSWORD } }));
  const verified = must('2FA verify', await http('POST', '/api/profile/2fa/verify', { cookie: tfSession.cookie, body: { code: await totp(enabled.secret) } }));
  tf.totpSecret = enabled.secret;
  tf.backupCodes = verified.backupCodes;

  const project = must('project', await http('POST', '/api/projects', { cookie: admin.cookie, body: { name: `${prefix} Alpha`, color: '#22AA66', ownerId: admin.userId, status: 'todo', businessUnitId: bu?.id ?? null, groupId: null } }));
  const project2 = must('project', await http('POST', '/api/projects', { cookie: admin.cookie, body: { name: `${prefix} Beta`, color: '#AA2266', ownerId: admin.userId, status: 'todo', businessUnitId: bu?.id ?? null, groupId: null } }));

  const holidayDate = day(3);
  // One holiday per date on the server: an earlier run of the same day may have taken it
  const earlierHolidays = must('holidays', await http('GET', `/api/holidays?startDate=${holidayDate}&endDate=${holidayDate}`, { cookie: admin.cookie }));
  for (const earlier of earlierHolidays) must('holiday', await http('DELETE', `/api/holidays/${earlier.id}`, { cookie: admin.cookie }));
  const holiday = must('holiday', await http('POST', '/api/holidays', { cookie: admin.cookie, body: { date: holidayDate, label: `${prefix} Holiday`, color: '#FF0000' } }));

  // Tasks read by several tests; a test that changes a task creates its own through the API
  const task = async (key, userIds, over = {}, cookie = admin.cookie) => must(`task ${key}`, await http('POST', '/api/tasks', {
    cookie,
    body: { projectId: project.id, userIds, statusId: defaultStatus.id, startDate: day(0), title: `${prefix} ${key}`, isMilestone: false, ...over },
  }));
  const tasks = {};
  const add = async (key, ...rest) => { const t = await task(key, ...rest); tasks[key] = { id: t.id, title: t.title, startDate: t.startDate, endDate: t.endDate }; return t; };
  await add('mine', [accounts.own.id]);
  await add('someoneElse', [accounts.own2.id]);
  await add('multiDay', [accounts.own.id], { startDate: day(0), endDate: day(2) });
  await add('teamTask', [accounts.teammate.id]);
  await add('outsideTeam', [accounts.outsider.id]);
  await add('styled', [accounts.own.id], { statusId: styledStatus.id });
  await add('otherProject', [accounts.own.id], { projectId: project2.id });
  await add('fullMine', [accounts.full.id]);
  await add('readerMine', [accounts.reader.id]);
  const recurring = await add('recurring', [accounts.own.id], { startDate: day(10) });
  must('repeat', await http('POST', `/api/tasks/${recurring.id}/repeat`, { cookie: admin.cookie, body: { recurType: 'daily', recurFrequency: 1, recurEndDate: day(13), recurException: 'include' } }));

  const attached = await add('attached', [accounts.own.id]);
  const form = new FormData();
  form.append('file', new Blob(['%PDF-1.4 seeded attachment'], { type: 'application/pdf' }), 'seeded.pdf');
  const upload = await fetch(`${URL_}/api/tasks/${attached.id}/files`, { method: 'POST', headers: { Cookie: admin.cookie, 'X-App-Version': APP_VERSION }, body: form });
  if (!upload.ok) throw new Error(`upload: HTTP ${upload.status} ${await upload.text()}`);

  const seed = {
    runId: RUN,
    prefix,
    today: TODAY,
    serverUrl: EMULATOR_URL,
    mockUrl: EMULATOR_MOCK,
    password: PASSWORD,
    appVersion: APP_VERSION,
    roles,
    accounts,
    teams: { a: { id: teamA.id, name: teamA.name } },
    project: { id: project.id, name: project.name },
    project2: { id: project2.id, name: project2.name },
    defaultStatus: { id: defaultStatus.id, name: defaultStatus.name },
    styledStatus: { id: styledStatus.id, name: styledStatus.name },
    holiday: { id: holiday.id, date: holidayDate, label: `${prefix} Holiday` },
    tasks,
  };
  mkdirSync(dirname(OUT), { recursive: true });
  writeFileSync(OUT, JSON.stringify(seed, null, 2));
  console.log(`Seed ${RUN}: ${Object.keys(accounts).length} accounts, ${Object.keys(tasks).length} tasks -> ${OUT}`);
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
