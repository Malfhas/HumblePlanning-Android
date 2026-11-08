// Fictitious data set for the screenshots of the README, on the local test server (tools/test-server).
//
//   node tools/screenshots/seed.mjs [--url http://localhost:3100] [--week 2026-11-09]
//
// A small maintenance company: three teams, ten people, five worksites, three weeks of tasks around
// --week (a Monday) and a public holiday on its Wednesday. Run it on a fresh test server (its database
// lives in memory: `docker compose -p hp-test down` then `up -d`), with its test administrator (see
// "Tests" in README.md). Every name is invented. The scripted answer of the assistant
// (tools/test-server/mock-services.mjs) matches this data set.
//
// It signs Emma Collins in once, then changes a few of her tasks, so that her Alerts screen is not empty.
// Sign the app in as `emma` with the password printed at the end.
import { randomBytes } from 'node:crypto';

const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : fallback;
};
const URL_ = arg('url', 'http://localhost:3100').replace(/\/+$/, '');
const WEEK = arg('week', '2026-11-09');
if (!/^http:\/\/(localhost|127\.0\.0\.1):\d+$/.test(URL_)) {
  console.error(`Refusing ${URL_}: this script only runs against the local test server.`);
  process.exit(2);
}
const ADMIN = arg('admin', 'testadmin');
const ADMIN_PASSWORD = arg('password', 'TestAdmin2026!x');
const PASSWORD = `Demo-${randomBytes(6).toString('hex')}-Aa9!`;
const APP = '1.0.0';

const day = (offset) => {
  const d = new Date(`${WEEK}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + offset);
  return d.toISOString().slice(0, 10);
};

async function http(method, path, { body, cookie, web = false } = {}) {
  const res = await fetch(URL_ + path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(cookie ? { Cookie: cookie } : {}),
      ...(web ? {} : { 'X-App-Version': APP }),
      'Accept-Language': 'en',
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    redirect: 'manual',
  });
  const text = await res.text();
  let parsed = null;
  try { parsed = JSON.parse(text); } catch { /* not JSON */ }
  if (res.status >= 300) throw new Error(`${method} ${path}: HTTP ${res.status} ${text.slice(0, 300)}`);
  return parsed;
}

async function login(identifier, password) {
  const res = await http('POST', '/api/mobile/auth/login', { body: { identifier, password, device: { name: 'Pixel 9', appVersion: APP, locale: 'en' } } });
  if (res?.status !== 'authenticated') throw new Error(`login ${identifier} failed`);
  return { cookie: `${res.session.cookieName}=${res.session.cookieValue}`, userId: res.session.userId };
}

const STATUS_NAMES = { 'A faire': 'Planned', 'En cours': 'In progress', 'Termine': 'Done', 'Annule': 'Cancelled', 'En attente': 'On hold', 'Valide': 'Approved' };

async function main() {
  const admin = await login(ADMIN, ADMIN_PASSWORD);
  const put = (path, body) => http('PUT', path, { cookie: admin.cookie, web: true, body });
  const post = (path, body) => http('POST', path, { cookie: admin.cookie, body });

  for (const [key, value] of Object.entries({
    APP_TITLE: 'Northwind Facilities', MOBILE_PUSH_ENABLED: 'true', MOBILE_PUSH_TRIGGERS: '', MOBILE_PUSH_RELAY_URL: '',
    MAINTENANCE_MODE: 'false', MOBILE_MIN_APP_VERSION: '', TWOFACTOR_REQUIRED: 'false', PLANNING_MAX_TASKS_PER_DAY: '0',
  })) await put('/api/config', { [key]: value });
  const aiConfig = (await http('GET', '/api/ai-chat/admin/config', { cookie: admin.cookie, web: true }))?.config ?? {};
  await put('/api/ai-chat/admin/config', { ...aiConfig, enabled: true, apiKey: 'test-key', models: ['test/model'], defaultModel: 'test/model' });

  // The administrator of the test server makes the changes that reach Emma as alerts, and stays off the planning
  await put(`/api/users/${admin.userId}`, { firstName: 'Daniel', name: 'Brooks', job: 'Operations manager', visibleOnPlanning: false });

  const statuses = {};
  for (const s of await http('GET', '/api/statuses', { cookie: admin.cookie })) {
    const name = STATUS_NAMES[s.name] ?? s.name;
    if (name !== s.name) await put(`/api/statuses/${s.id}`, { name });
    statuses[name] = s.id;
  }

  const bu = (await http('GET', '/api/business-units', { cookie: admin.cookie }))?.[0]
    ?? await post('/api/business-units', { name: 'Head office', number: 'HO', color: '#475569', address: '1 Market Street', latitude: '48.8566', longitude: '2.3522' });

  const pages = ['mobile_app:use', 'pages:planning', 'pages:home', 'pages:tasks', 'scope:projects_all', 'scope:users_all', 'scope:bu_all'];
  const tasksAll = ['tasks:create', 'tasks:delete', 'tasks:assign', 'tasks:move', 'tasks:resize'];
  const files = ['files:view', 'files:upload', 'files:delete'];
  const manager = (await post('/api/roles', { name: 'Site manager', description: 'Plans the teams', color: '#0f766e', permissions: [...pages, 'scope:modify_all', ...tasksAll, 'tasks:lock', ...files, 'ai_chat:use', 'ai_chat:write'] })).id;
  const technician = (await post('/api/roles', { name: 'Technician', description: 'Field staff', color: '#64748b', permissions: [...pages, 'scope:modify_own', ...tasksAll, ...files] })).id;

  const team = async (name, color) => (await post('/api/teams', { name, color, businessUnitId: bu.id })).id;
  const teams = { electrical: await team('Electrical', '#f59e0b'), plumbing: await team('Plumbing', '#0ea5e9'), carpentry: await team('Carpentry', '#84cc16') };

  const people = {};
  const person = async (key, firstName, name, teamKey, job, color, roleId = technician) => {
    const user = await post('/api/users', {
      firstName, name, username: key, password: PASSWORD, roleId, color, teamId: teams[teamKey], job, visibleOnPlanning: true,
      notifications: false, pushNotifications: false, smsNotifications: false, active: true, businessUnitId: bu.id,
    });
    people[key] = user.id;
  };
  await person('emma', 'Emma', 'Collins', 'electrical', 'Site manager', '#0f766e', manager);
  await person('liam', 'Liam', 'Turner', 'electrical', 'Electrician', '#2563eb');
  await person('olivia', 'Olivia', 'Bennett', 'electrical', 'Electrician', '#7c3aed');
  await person('noah', 'Noah', 'Hughes', 'electrical', 'Apprentice electrician', '#db2777');
  await person('ava', 'Ava', 'Morgan', 'plumbing', 'Plumber', '#0891b2');
  await person('ethan', 'Ethan', 'Price', 'plumbing', 'Heating engineer', '#ea580c');
  await person('mia', 'Mia', 'Foster', 'plumbing', 'Plumber', '#16a34a');
  await person('lucas', 'Lucas', 'Reed', 'carpentry', 'Carpenter', '#b45309');
  await person('chloe', 'Chloe', 'Ward', 'carpentry', 'Joiner', '#be123c');
  await person('james', 'James', 'Carter', 'carpentry', 'Carpenter', '#4f46e5');

  const project = async (businessNumber, name, color, address) => (await post('/api/projects', {
    businessNumber, name, color, ownerId: people.emma, status: 'in_progress', businessUnitId: bu.id, groupId: null, address,
  })).id;
  const p = {
    harbour: await project('B26-014', 'Harbour View Offices', '#2563eb', '12 Harbour Road'),
    school: await project('B26-022', 'Maple Street School', '#16a34a', '48 Maple Street'),
    clinic: await project('B26-031', 'Riverside Clinic', '#db2777', '3 Riverside Walk'),
    library: await project('B26-037', 'City Library', '#9333ea', '20 Library Square'),
    warehouse: await project('B26-040', 'Greenfield Warehouse', '#ea580c', 'Greenfield Business Park'),
  };

  await post('/api/holidays', { date: day(2), label: 'Armistice Day', color: '#FF0000' });

  const AM = { startTime: '08:00', endTime: '12:00' };
  const PM = { startTime: '13:30', endTime: '17:00' };
  const tasks = {};
  const task = async (title, projectKey, who, from, to = from, extra = {}) => {
    const status = extra.status ?? (from < 0 ? 'Done' : 'Planned');
    const created = await post('/api/tasks', {
      title, projectId: p[projectKey], userIds: who.map((k) => people[k]), statusId: statuses[status],
      startDate: day(from), endDate: to !== from ? day(to) : null, isMilestone: false,
      ...(extra.time ?? {}), ...(extra.notes ? { notes: extra.notes } : {}), ...(extra.place ? { placeText: extra.place } : {}),
    });
    tasks[extra.key ?? title] = created;
    return created;
  };

  // Week before: done
  await task('Cable pulling, level 1', 'harbour', ['liam', 'noah'], -7, -5);
  await task('Site survey', 'warehouse', ['emma'], -7, -7, { time: AM });
  await task('Distribution board check', 'clinic', ['olivia'], -7, -6);
  await task('Emergency lighting check', 'school', ['olivia'], -5, -4);
  await task('Client meeting', 'library', ['emma'], -6, -6, { time: PM });
  await task('Socket installation', 'library', ['emma', 'noah'], -4, -3);
  await task('Cable trays', 'warehouse', ['liam'], -4, -3);
  await task('Radiator replacement', 'school', ['ava'], -7, -5);
  await task('Boiler service', 'clinic', ['ethan'], -7, -7);
  await task('Pipe insulation', 'library', ['ethan'], -6, -4);
  await task('Water supply, first fix', 'warehouse', ['mia'], -7, -3);
  await task('Shower trays', 'school', ['ava'], -4, -3);
  await task('Ceiling battens', 'harbour', ['lucas'], -7, -4);
  await task('Classroom shelving', 'school', ['chloe'], -7, -5);
  await task('Skirting boards', 'clinic', ['chloe'], -4, -3);
  await task('Stair treads', 'warehouse', ['james'], -7, -3);
  await task('Door survey', 'harbour', ['lucas'], -3, -3, { time: AM });

  // This week (holiday on Wednesday)
  await task('Switchboard replacement', 'harbour', ['liam', 'emma'], 0, 1, {
    key: 'switchboard',
    place: '12 Harbour Road, level 1',
    notes: 'Replace the main switchboard on level 1 and label every circuit.\n\nPower cut from 8:00 to 10:00 on Monday: the tenants have been told. The new RCBOs are at the warehouse.',
  });
  await task('Fire alarm testing', 'school', ['emma'], 3, 3, { time: AM });
  await task('Client meeting', 'clinic', ['emma'], 3, 3, { time: PM, key: 'meeting' });
  await task('Safety training', 'library', ['emma', 'olivia', 'noah'], 4, 4);
  await task('Lighting retrofit', 'library', ['olivia'], 0, 1, { key: 'lighting' });
  await task('Emergency lighting check', 'clinic', ['olivia'], 3, 3);
  await task('Cable pulling, level 2', 'harbour', ['noah'], 0, 0);
  await task('EV charger survey', 'warehouse', ['noah'], 1, 1, { time: AM });
  await task('Cable pulling, level 2', 'harbour', ['noah'], 1, 1, { time: PM });
  await task('Data cabinet wiring', 'harbour', ['noah'], 3, 3);
  await task('Bathroom fit-out', 'school', ['ava'], 0, 4, { status: 'In progress' });
  await task('Boiler service', 'clinic', ['ethan'], 0, 0);
  await task('Leak repair, basement', 'library', ['ethan'], 1, 1, { time: AM });
  await task('Water heater replacement', 'library', ['ethan'], 1, 1, { time: PM, status: 'On hold' });
  await task('Drain inspection', 'warehouse', ['ethan'], 3, 3);
  await task('Boiler service', 'harbour', ['ethan'], 4, 4, { time: AM });
  await task('Plumbing first fix', 'warehouse', ['mia'], 0, 4);
  await task('Partition walls', 'harbour', ['lucas'], 0, 1);
  await task('Door frames installation', 'harbour', ['lucas'], 3, 4);
  await task('Classroom shelving', 'school', ['chloe'], 0, 1);
  await task('Floor repair', 'library', ['chloe'], 3, 3, { time: AM });
  await task('Window sills', 'clinic', ['chloe'], 4, 4);
  await task('Site survey', 'warehouse', ['james'], 0, 0, { time: AM });
  await task('Mezzanine stairs', 'warehouse', ['james'], 1, 3);

  // Week after
  await task('Switchboard commissioning', 'harbour', ['emma', 'liam'], 7, 7);
  await task('EV charger installation', 'warehouse', ['liam'], 8, 10);
  await task('Lighting retrofit', 'library', ['olivia'], 7, 9);
  await task('Cable pulling, level 3', 'harbour', ['noah'], 7, 9);
  await task('Bathroom fit-out', 'school', ['ava'], 7, 8);
  await task('Heat pump service', 'clinic', ['ethan'], 7, 8);
  await task('Plumbing first fix', 'warehouse', ['mia'], 7, 11);
  await task('Door frames installation', 'harbour', ['lucas'], 7, 8);
  await task('Reception desk', 'library', ['chloe', 'james'], 8, 11);
  await task('Site visit with the architect', 'clinic', ['emma'], 9, 9, { time: AM });

  // Emma signs in on a phone: from now on, changes made by others reach her as alerts
  const emma = await login('emma', PASSWORD);
  await http('POST', '/api/locale', { cookie: emma.cookie, body: { preference: 'en' } });
  const update = (t, body) => http('PUT', `/api/tasks/${t.id}`, { cookie: admin.cookie, body });
  await update(tasks.switchboard, { statusId: statuses['In progress'] });
  await update(tasks.lighting, { userIds: [people.olivia, people.emma] });
  await update(tasks.meeting, { startTime: '14:00', endTime: '15:30' });
  await post('/api/tasks', { title: 'Handover with the client', projectId: p.clinic, userIds: [people.emma], statusId: statuses.Planned, startDate: day(11), isMilestone: false, startTime: '10:00', endTime: '11:00' });

  console.log(`Demo data set ready (week of ${WEEK}). Sign in as "emma" with the password: ${PASSWORD}`);
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
