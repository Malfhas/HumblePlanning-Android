// Automated API campaign against a Humble Planning server with the mobile API.
// Accounts with different permissions, scopes, real time, alerts, version gate, security.
//
//   node tools/api-tests/run.mjs [--url http://localhost:3100] [--admin login] [--password ...]
//
// Everything it creates is prefixed TEST-ANDROID and removed at the end. The local server is
// disposable; on a shared server, make sure this is acceptable first.
import { createRequire } from 'node:module';
import { readFileSync, existsSync } from 'node:fs';
import { generateKeyPairSync, randomBytes } from 'node:crypto';

const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : fallback;
};
const URL_ = arg('url', 'http://localhost:3100').replace(/\/+$/, '');
const MOCK = arg('mock', 'http://localhost:3102');
const LOCAL = /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(URL_);
const ADMIN = arg('admin', 'testadmin');
// The default is the administrator of the disposable local server (tools/test-server) only
const ADMIN_PASSWORD = arg('password', LOCAL ? 'TestAdmin2026!x' : undefined);
if (!ADMIN_PASSWORD) {
  console.error('--admin and --password are required for a server other than the local test server.');
  process.exit(2);
}
const PREFIX = 'TEST-ANDROID';
// Accounts created by the campaign: a new random password each run, never a published one
const PASSWORD = `Tst-${randomBytes(12).toString('hex')}-Aa9!`;
const APP = '1.0.0';
// Lockouts outlive a run: a new set of accounts each time
const RUN = Date.now().toString(36);

const results = [];
let group = '';
const section = (name) => { group = name; console.log(`\n== ${name}`); };
function check(name, ok, detail = '') {
  results.push({ group, name, ok });
  console.log(`${ok ? '  ok ' : '  KO '} ${name}${!ok && detail ? ` -> ${detail}` : ''}`);
  return ok;
}
const expectStatus = (name, res, status) => check(name, res.status === status, `HTTP ${res.status} ${JSON.stringify(res.body)?.slice(0, 180)}`);

async function http(method, path, { body, cookie, headers = {}, web = false } = {}) {
  const res = await fetch(URL_ + path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(cookie ? { Cookie: cookie } : {}),
      ...(web ? {} : { 'X-App-Version': APP }),
      'Accept-Language': 'fr',
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    redirect: 'manual',
  });
  const text = await res.text();
  let parsed = null;
  try { parsed = JSON.parse(text); } catch { /* not JSON */ }
  return { status: res.status, body: parsed, text, headers: res.headers };
}

const device = (name) => ({ name, appVersion: APP, locale: 'fr' });

// The QR code is created from the web profile: a browser session of the account (the app's own is refused)
async function webLogin(identifier, password) {
  const res = await http('POST', '/api/auth/login', { web: true, body: { identifier, password } });
  const cookie = (res.headers.getSetCookie?.() ?? []).map((c) => c.split(';')[0]).join('; ');
  if (res.status !== 200 || !cookie) throw new Error(`web login ${identifier}: HTTP ${res.status} ${res.text.slice(0, 200)}`);
  return cookie;
}

async function login(identifier, password, deviceName = 'test') {
  const res = await http('POST', '/api/mobile/auth/login', { body: { identifier, password, device: device(deviceName) } });
  if (res.body?.status !== 'authenticated') throw new Error(`login ${identifier}: HTTP ${res.status} ${res.text.slice(0, 200)}`);
  return { ...res.body, cookie: `${res.body.session.cookieName}=${res.body.session.cookieValue}`, userId: res.body.session.userId };
}

const today = new Date();
const day = (offset) => new Date(today.getTime() + offset * 86400000).toISOString().slice(0, 10);

const created = { users: [], roles: [], projects: [], teams: [], tasks: [] };

async function main() {
  section('Découverte et connexion');
  const info = await http('GET', '/api/mobile/info');
  expectStatus('info publique', info, 200);
  check('info identifie le serveur', info.body?.service === 'humble-planning');
  check('info ne révèle pas la version serveur', !('version' in (info.body ?? {})) && !('serverVersion' in (info.body ?? {})));

  const bad = await http('POST', '/api/mobile/auth/login', { body: { identifier: `${PREFIX}-inconnu`, password: 'x', device: device('t') } });
  expectStatus('mauvais identifiants : 401', bad, 401);
  check('message traduit, code stable', bad.body?.code === 'auth.errors.invalidCredentials' && !!bad.body?.error);

  const fromBrowser = await http('POST', '/api/mobile/auth/login', { body: { identifier: ADMIN, password: ADMIN_PASSWORD }, headers: { Origin: URL_ } });
  expectStatus('un navigateur est refusé (Origin)', fromBrowser, 403);
  const noBody = await http('POST', '/api/mobile/auth/login', { body: { identifier: '' } });
  expectStatus('corps invalide : 422', noBody, 422);

  const admin = await login(ADMIN, ADMIN_PASSWORD, 'admin');
  check('connexion admin sans captcha', !!admin.cookie);
  check('session mobile longue', new Date(admin.session.expiresAt) - Date.now() > 20 * 86400000, admin.session.expiresAt);

  const noCookie = await http('GET', '/api/mobile/bootstrap');
  expectStatus('bootstrap sans session : 401', noCookie, 401);
  const forged = await http('GET', '/api/mobile/bootstrap', { cookie: `${admin.session.cookieName}=faux.AAAA` });
  expectStatus('cookie forgé : 401', forged, 401);

  section('Préparation des comptes');
  const roles = (await http('GET', '/api/roles', { cookie: admin.cookie })).body;
  const memberRole = roles.find((r) => r.id.endsWith('0004'));
  const managerRole = roles.find((r) => r.id.endsWith('0002'));
  check('rôles système présents', !!memberRole && !!managerRole);
  const makeRole = async (name, permissions) => {
    const res = await http('POST', '/api/roles', { cookie: admin.cookie, body: { name: `${PREFIX}-${RUN}-${name}`, description: 'campagne', color: '#6b7280', permissions } });
    if (res.status >= 300) throw new Error(`role ${name}: ${res.text.slice(0, 300)}`);
    created.roles.push(res.body.id);
    return res.body.id;
  };
  // Every campaign role may use the app, except the one made to check that refusal
  const pages = ['mobile_app:use', 'pages:planning', 'pages:home', 'pages:tasks', 'scope:projects_all', 'scope:users_all', 'scope:bu_all'];
  const readerRole = await makeRole('lecture', pages);
  const ownRole = await makeRole('mes-taches', [...pages, 'scope:modify_own', 'tasks:create', 'tasks:delete', 'tasks:assign', 'tasks:move', 'tasks:resize', 'files:view', 'files:upload', 'files:delete']);
  const teamRole = await makeRole('equipe', [...pages, 'scope:modify_team', 'tasks:create', 'tasks:delete', 'tasks:assign']);
  const fullRole = await makeRole('complet', [...pages, 'scope:modify_all', 'tasks:create', 'tasks:delete', 'tasks:assign', 'tasks:move', 'tasks:resize', 'tasks:lock', 'files:view', 'files:upload', 'files:delete', 'projects:create', 'ai_chat:use', 'ai_chat:write']);
  const blindRole = await makeRole('sans-planning', ['mobile_app:use', 'scope:projects_all']);

  const bu = (await http('GET', '/api/business-units', { cookie: admin.cookie })).body?.[0];
  const statuses = (await http('GET', '/api/statuses', { cookie: admin.cookie })).body;
  check('statuts présents', statuses?.length > 0, 'installation neuve sans statuts');
  const statusId = statuses.find((s) => s.isDefault)?.id ?? statuses[0].id;

  let teamA = null;
  if (bu) {
    const team = await http('POST', '/api/teams', { cookie: admin.cookie, body: { name: `${PREFIX}-${RUN}-equipe-a`, color: '#3b82f6', businessUnitId: bu.id } });
    if (team.status < 300) { teamA = team.body.id; created.teams.push(teamA); }
  }

  const makeUser = async (name, roleId, extra = {}) => {
    const res = await http('POST', '/api/users', {
      cookie: admin.cookie,
      body: { name: `${PREFIX}-${name}`, username: `${PREFIX.toLowerCase()}-${RUN}-${name}`, password: PASSWORD, roleId, color: '#3366ff', visibleOnPlanning: true, notifications: false, pushNotifications: false, smsNotifications: false, active: true, businessUnitId: bu?.id ?? null, ...extra },
    });
    if (res.status >= 300) throw new Error(`user ${name}: ${res.text.slice(0, 400)}`);
    created.users.push(res.body.id);
    return res.body;
  };
  const uReader = await makeUser('lecture', readerRole);
  const uOwn = await makeUser('own', ownRole);
  const uOwn2 = await makeUser('own2', ownRole);
  const uTeam = await makeUser('team', teamRole, teamA ? { teamId: teamA } : {});
  const uFull = await makeUser('full', fullRole);
  const uBlind = await makeUser('blind', blindRole);
  check('comptes créés', true);

  const project = await http('POST', '/api/projects', { cookie: admin.cookie, body: { name: `${PREFIX}-projet`, color: '#22AA66', ownerId: admin.userId, status: 'todo', businessUnitId: bu?.id ?? null, groupId: null } });
  if (!expectStatus('projet créé', project, 201)) throw new Error(project.text);
  created.projects.push(project.body.id);
  const projectId = project.body.id;

  const sReader = await login(uReader.username, PASSWORD, 'lecture');
  const sOwn = await login(uOwn.username, PASSWORD, 'own');
  const sOwn2 = await login(uOwn2.username, PASSWORD, 'own2');
  const sTeam = await login(uTeam.username, PASSWORD, 'team');
  const sFull = await login(uFull.username, PASSWORD, 'full');
  const sBlind = await login(uBlind.username, PASSWORD, 'blind');

  section('Chargement initial selon le rôle');
  const bootAdmin = await http('GET', '/api/mobile/bootstrap', { cookie: admin.cookie });
  expectStatus('bootstrap admin', bootAdmin, 200);
  check('admin reconnu', bootAdmin.body?.isAdmin === true);
  const bootReader = await http('GET', '/api/mobile/bootstrap', { cookie: sReader.cookie });
  check('lecteur : pas admin', bootReader.body?.isAdmin === false);
  check('lecteur : permissions du rôle', bootReader.body?.permissions?.includes('pages:planning') && !bootReader.body?.permissions?.includes('tasks:create'));
  const someone = bootReader.body?.users?.find((u) => u.id === uOwn.id);
  check('champs de contact masqués pour un autre', someone && !someone.email && !someone.phone);
  check('équipes sans lignes utilisateurs complètes', (bootReader.body?.teams ?? []).every((t) => !('users' in t) && Array.isArray(t.userIds)));
  const bootBlind = await http('GET', '/api/mobile/bootstrap', { cookie: sBlind.cookie });
  expectStatus('compte sans page planning : bootstrap répond', bootBlind, 200);
  check('liste interdite renvoyée à null', bootBlind.body?.users === null, JSON.stringify(bootBlind.body?.users)?.slice(0, 80));
  check('assistant indisponible sans droit', bootReader.body?.assistant?.available === false);

  section('Tâches : droits par rôle');
  const payload = (userIds, over = {}) => ({ projectId, userIds, statusId, startDate: day(1), title: `${PREFIX} tâche`, isMilestone: false, ...over });
  const track = (res) => { if (res.body?.id) created.tasks.push(res.body.id); return res; };

  expectStatus('lecteur ne crée pas', await http('POST', '/api/tasks', { cookie: sReader.cookie, body: payload([uReader.id]) }), 403);
  const ownTask = track(await http('POST', '/api/tasks', { cookie: sOwn.cookie, body: payload([uOwn.id]) }));
  expectStatus('mes-tâches crée pour lui', ownTask, 201);
  expectStatus('mes-tâches ne crée pas pour un autre', await http('POST', '/api/tasks', { cookie: sOwn.cookie, body: payload([uOwn2.id]) }), 403);
  const adminTask = track(await http('POST', '/api/tasks', { cookie: admin.cookie, body: payload([uOwn2.id], { notes: 'note', startTime: '08:00', endTime: '12:00', isMilestone: true }) }));
  expectStatus('admin crée pour un autre', adminTask, 201);
  check('l\'heure revient en HH:MM:SS', adminTask.body?.startTime === '08:00:00', adminTask.body?.startTime);
  check('tâche : plusieurs champs relus', adminTask.body?.isMilestone === true && adminTask.body?.users?.[0]?.id === uOwn2.id);

  expectStatus('mes-tâches ne modifie pas celle d\'un autre', await http('PUT', `/api/tasks/${adminTask.body.id}`, { cookie: sOwn.cookie, body: { title: 'piraté', isMilestone: false } }), 403);
  expectStatus('mes-tâches ne supprime pas celle d\'un autre', await http('DELETE', `/api/tasks/${adminTask.body.id}`, { cookie: sOwn.cookie }), 403);
  expectStatus('lecteur lit une tâche', await http('GET', `/api/tasks/${adminTask.body.id}`, { cookie: sReader.cookie }), 200);
  expectStatus('sans page : tâches interdites', await http('GET', `/api/mobile/tasks/sync?startDate=${day(0)}&endDate=${day(3)}`, { cookie: sBlind.cookie }), 403);
  expectStatus('complet modifie celle d\'un autre', await http('PUT', `/api/tasks/${adminTask.body.id}`, { cookie: sFull.cookie, body: { title: `${PREFIX} modifiée`, isMilestone: true } }), 200);

  if (teamA) {
    const teamTaskOther = track(await http('POST', '/api/tasks', { cookie: admin.cookie, body: payload([uOwn.id]) }));
    expectStatus('équipe ne modifie pas hors équipe', await http('PUT', `/api/tasks/${teamTaskOther.body.id}`, { cookie: sTeam.cookie, body: { title: 'x', isMilestone: false } }), 403);
    const teamTask = track(await http('POST', '/api/tasks', { cookie: sTeam.cookie, body: payload([uTeam.id]) }));
    expectStatus('équipe crée dans son équipe', teamTask, 201);
  }

  section('Effacer des valeurs depuis l\'app');
  const clear = await http('PUT', `/api/tasks/${adminTask.body.id}`, { cookie: admin.cookie, body: { ...payload([uOwn2.id]), startTime: null, endTime: null, endDate: null, isMilestone: true } });
  expectStatus('passage en journée entière', clear, 200);
  check('les horaires sont bien effacés', clear.body?.startTime === null && clear.body?.endTime === null);
  check('isMilestone conservé', clear.body?.isMilestone === true);

  section('Verrouillage');
  const lockable = track(await http('POST', '/api/tasks', { cookie: sFull.cookie, body: payload([uFull.id]) }));
  expectStatus('complet verrouille', await http('PUT', `/api/tasks/${lockable.body.id}`, { cookie: sFull.cookie, body: { isLocked: true, isMilestone: false } }), 200);
  expectStatus('tâche verrouillée : suppression refusée', await http('DELETE', `/api/tasks/${lockable.body.id}`, { cookie: sFull.cookie }), 403);

  section('Synchronisation incrémentale');
  // The cursor reads 5 s back, so a row committed during a read is never skipped: let the changes above age past it
  await new Promise((r) => setTimeout(r, 6000));
  const full = await http('GET', `/api/mobile/tasks/sync?startDate=${day(0)}&endDate=${day(3)}`, { cookie: admin.cookie });
  expectStatus('premier passage', full, 200);
  check('premier passage : toutes les tâches', full.body?.changed?.length === full.body?.ids?.length && full.body?.changed?.length > 0);
  const since = full.body.serverTime;
  const idle = await http('GET', `/api/mobile/tasks/sync?startDate=${day(0)}&endDate=${day(3)}&since=${encodeURIComponent(since)}`, { cookie: admin.cookie });
  check('sans changement : rien de renvoyé', idle.body?.changed?.length === 0 && idle.body?.ids?.length === full.body.ids.length, `${idle.body?.changed?.length}`);
  await http('PUT', `/api/tasks/${adminTask.body.id}`, { cookie: admin.cookie, body: { title: `${PREFIX} delta`, isMilestone: true } });
  const delta = await http('GET', `/api/mobile/tasks/sync?startDate=${day(0)}&endDate=${day(3)}&since=${encodeURIComponent(since)}`, { cookie: admin.cookie });
  check('après changement : seule la tâche modifiée', delta.body?.changed?.length === 1 && delta.body.changed[0].id === adminTask.body.id, `${delta.body?.changed?.length}`);
  const gone = track(await http('POST', '/api/tasks', { cookie: admin.cookie, body: payload([uOwn.id]) }));
  await http('DELETE', `/api/tasks/${gone.body.id}`, { cookie: admin.cookie });
  const afterDelete = await http('GET', `/api/mobile/tasks/sync?startDate=${day(0)}&endDate=${day(3)}&since=${encodeURIComponent(since)}`, { cookie: admin.cookie });
  check('une tâche supprimée disparaît de ids', !afterDelete.body?.ids?.includes(gone.body.id));
  expectStatus('dates invalides : 422', await http('GET', '/api/mobile/tasks/sync?startDate=hier&endDate=demain', { cookie: admin.cookie }), 422);

  section('Récurrence');
  const root = track(await http('POST', '/api/tasks', { cookie: sFull.cookie, body: payload([uFull.id], { startDate: day(10) }) }));
  const repeat = await http('POST', `/api/tasks/${root.body.id}/repeat`, { cookie: sFull.cookie, body: { recurType: 'daily', recurFrequency: 1, recurEndDate: day(13), recurException: 'include' } });
  expectStatus('série créée', repeat, 200);
  check('occurrences créées', repeat.body?.occurrences >= 3, JSON.stringify(repeat.body));
  const series = await http('GET', `/api/mobile/tasks/sync?startDate=${day(10)}&endDate=${day(13)}`, { cookie: sFull.cookie });
  const occurrences = series.body?.changed?.filter((t) => t.parentId === root.body.id) ?? [];
  check('occurrences visibles', occurrences.length >= 3, `${occurrences.length}`);
  const removeAll = await http('DELETE', `/api/tasks/${root.body.id}?scope=all`, { cookie: sFull.cookie });
  expectStatus('suppression de la série', removeAll, 200);

  section('Temps réel');
  const require = createRequire(new URL('./package.json', import.meta.url));
  let io = null;
  try { io = require('socket.io-client').io; } catch { check('socket.io-client installé (npm install dans tools/api-tests)', false); }
  if (io) {
    const connectAs = (cookie) => new Promise((resolve, reject) => {
      const socket = io(URL_, { transports: ['websocket'], extraHeaders: { Cookie: cookie }, reconnection: false, timeout: 8000 });
      socket.on('connect', () => resolve(socket));
      socket.on('connect_error', (e) => reject(e));
    });
    const waitFor = (socket, event, ms = 6000) => new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`pas d'événement ${event}`)), ms);
      socket.once(event, (payload) => { clearTimeout(timer); resolve(payload); });
    });
    const refused = await connectAs(`${admin.session.cookieName}=faux.AAAA`).then(() => false, (e) => e.message);
    check('socket refusé avec un faux cookie', refused === 'Invalid session', String(refused));
    const socket = await connectAs(sReader.cookie);
    check('socket connecté avec la session mobile', socket.connected);
    const changed = waitFor(socket, 'task:changed');
    const liveTask = track(await http('POST', '/api/tasks', { cookie: admin.cookie, body: payload([uOwn.id]) }));
    const event = await changed.catch((e) => e);
    check('task:changed reçu à la création', event?.taskId === liveTask.body.id, String(event?.message ?? JSON.stringify(event)));

    section('Alertes push');
    const reset = await fetch(`${MOCK}/__reset`, { method: 'POST' }).then((r) => r.ok).catch(() => false);
    const socketOwn = await connectAs(sOwn.cookie);
    const alertEvent = waitFor(socketOwn, 'mobile:alert');
    const t0 = new Date(Date.now() - 2000).toISOString();
    const notified = track(await http('POST', '/api/tasks', { cookie: admin.cookie, body: payload([uOwn.id], { title: `${PREFIX} alerte` }) }));
    const alert = await alertEvent.catch((e) => e);
    check('événement mobile:alert reçu', !!alert?.alertId, String(alert?.message));
    const alerts = await http('GET', `/api/mobile/alerts?after=${encodeURIComponent(t0)}`, { cookie: sOwn.cookie });
    const received = alerts.body?.alerts?.find((a) => a.actionUrl?.includes(notified.body.id));
    check('alerte lisible, avec lien vers la tâche', !!received, JSON.stringify(alerts.body).slice(0, 200));
    check('alerte traduite', !!received?.message);
    const leak = await http('GET', `/api/mobile/alerts?after=${encodeURIComponent(t0)}`, { cookie: sOwn2.cookie });
    check('alertes d\'un autre invisibles', !leak.body?.alerts?.some((a) => a.id === received?.id));
    const selfAlerts = await http('GET', `/api/mobile/alerts?after=${encodeURIComponent(t0)}`, { cookie: admin.cookie });
    check('pas d\'alerte pour l\'auteur', !selfAlerts.body?.alerts?.some((a) => a.actionUrl?.includes(notified.body.id)));
    expectStatus('après invalide : 422', await http('GET', '/api/mobile/alerts?after=hier', { cookie: sOwn.cookie }), 422);

    const prefOff = await http('PUT', '/api/profile/preferences', { cookie: sOwn.cookie, body: { mobilePushNotifications: false } });
    expectStatus('l\'utilisateur coupe ses alertes', prefOff, 200);
    const profile = await http('GET', '/api/profile', { cookie: sOwn.cookie });
    check('préférence relue', profile.body?.mobilePushNotificationsUser === false);
    const t1 = new Date(Date.now() - 1000).toISOString();
    const silent = track(await http('POST', '/api/tasks', { cookie: admin.cookie, body: payload([uOwn.id], { title: `${PREFIX} silence` }) }));
    await new Promise((r) => setTimeout(r, 1200));
    const noAlert = await http('GET', `/api/mobile/alerts?after=${encodeURIComponent(t1)}`, { cookie: sOwn.cookie });
    check('alerte coupée : plus rien', !noAlert.body?.alerts?.some((a) => a.actionUrl?.includes(silent.body.id)));
    check('page incomplète : more à false', noAlert.body?.more === false);
    // Like the e-mails: an administrator who turns the alerts back on turns them back on for the user too
    await http('PUT', `/api/users/${uOwn.id}`, { cookie: admin.cookie, body: { mobilePushNotifications: false } });
    await http('PUT', `/api/users/${uOwn.id}`, { cookie: admin.cookie, body: { mobilePushNotifications: true } });
    const reEnabled = await http('GET', '/api/profile', { cookie: sOwn.cookie });
    check('réactivées par l\'admin : préférence remise à oui', reEnabled.body?.mobilePushNotifications === true && reEnabled.body?.mobilePushNotificationsUser === true, JSON.stringify({ a: reEnabled.body?.mobilePushNotifications, u: reEnabled.body?.mobilePushNotificationsUser }));

    const saveConfig = (key, value) => http('PUT', '/api/config', { cookie: admin.cookie, web: true, body: { [key]: value } });

    if (LOCAL && reset) {
      section('Relais de push');
      await http('PUT', '/api/mobile/devices/current', { cookie: sOwn.cookie, body: { pushToken: 'fid-own-1' } });
      await saveConfig('MOBILE_PUSH_RELAY_URL', 'http://mock:4000');
      await new Promise((r) => setTimeout(r, 600));
      check('info annonce le push', (await http('GET', '/api/mobile/info')).body?.pushEnabled === true);
      const pushed = track(await http('POST', '/api/tasks', { cookie: admin.cookie, body: payload([uOwn.id], { title: `${PREFIX} secret` }) }));
      await new Promise((r) => setTimeout(r, 1500));
      const pushes = await fetch(`${MOCK}/__pushes`).then((r) => r.json());
      const push = pushes.find((p) => p.fid === 'fid-own-1');
      check('sonnette envoyée au relais', !!push, JSON.stringify(pushes).slice(0, 200));
      check('aucun contenu dans le push', push && !JSON.stringify(push).includes('secret') && /^[0-9a-f-]{36}$/.test(push.alertId));
      check('auteur non réveillé', pushed.status === 201 && !pushes.some((p) => p.fid === 'fid-admin'));
      await fetch(`${MOCK}/__dead`, { method: 'POST', body: JSON.stringify({ fid: 'fid-own-1' }) });
      track(await http('POST', '/api/tasks', { cookie: admin.cookie, body: payload([uOwn.id], { title: `${PREFIX} mort` }) }));
      await new Promise((r) => setTimeout(r, 1500));
      const devices = await http('GET', '/api/mobile/devices', { cookie: sOwn.cookie });
      check('jeton mort oublié', devices.body?.find((d) => d.current)?.pushEnabled === false, JSON.stringify(devices.body).slice(0, 200));

      // More than one page between two reads: the cursor reads on, nothing is skipped
      const t3 = new Date(Date.now() - 1000).toISOString();
      // One alert per task assigned (the push test is limited to a few per user)
      for (let i = 0; i < 101; i++) track(await http('POST', '/api/tasks', { cookie: admin.cookie, body: payload([uOwn2.id], { title: `${PREFIX} page ${i}` }) }));
      await new Promise((r) => setTimeout(r, 2000));
      const page1 = await http('GET', `/api/mobile/alerts?after=${encodeURIComponent(t3)}`, { cookie: sOwn2.cookie });
      const last = page1.body?.alerts?.at(-1);
      check('page pleine : more et curseur sur la dernière', page1.body?.alerts?.length === 100 && page1.body.more === true && page1.body.serverTime === last?.createdAt,
        JSON.stringify({ n: page1.body?.alerts?.length, more: page1.body?.more, st: page1.body?.serverTime, last: last?.createdAt }));
      const page2 = await http('GET', `/api/mobile/alerts?after=${encodeURIComponent(page1.body?.serverTime ?? t3)}`, { cookie: sOwn2.cookie });
      const read = new Set([...(page1.body?.alerts ?? []), ...(page2.body?.alerts ?? [])].map((a) => a.id));
      check('page suivante : aucune alerte perdue', page2.body?.more === false && read.size === 101, JSON.stringify({ n: page2.body?.alerts?.length, total: read.size }));
      await saveConfig('MOBILE_PUSH_RELAY_URL', '');

      section('Compte de service Firebase saisi dans les réglages');
      const bad = await saveConfig('FCM_SERVICE_ACCOUNT_JSON', '{"project_id":"x"}');
      check('compte de service invalide refusé', bad.status === 400 && JSON.stringify(bad.body).includes('fcmServiceAccountInvalid'), `${bad.status} ${JSON.stringify(bad.body).slice(0, 150)}`);
      const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048, privateKeyEncoding: { type: 'pkcs8', format: 'pem' }, publicKeyEncoding: { type: 'spki', format: 'pem' } });
      const serviceAccount = JSON.stringify({ type: 'service_account', project_id: 'hp-test', client_email: 'push@hp-test.iam.gserviceaccount.com', private_key: privateKey }, null, 2);
      expectStatus('compte de service enregistré', await saveConfig('FCM_SERVICE_ACCOUNT_JSON', serviceAccount), 200);
      const configAfter = await http('GET', '/api/config', { cookie: admin.cookie, web: true });
      check('clé jamais renvoyée au navigateur, marquée configurée', !JSON.stringify(configAfter.body).includes('PRIVATE KEY') && String(configAfter.body?._dbConfigured).includes('FCM_SERVICE_ACCOUNT_JSON'));
      await new Promise((r) => setTimeout(r, 600));
      check('info annonce le push sans relais', (await http('GET', '/api/mobile/info')).body?.pushEnabled === true);
      await fetch(`${MOCK}/__reset`, { method: 'POST' });
      await http('PUT', '/api/mobile/devices/current', { cookie: sOwn.cookie, body: { pushToken: 'fid-own-2' } });
      track(await http('POST', '/api/tasks', { cookie: admin.cookie, body: payload([uOwn.id], { title: `${PREFIX} firebase` }) }));
      await new Promise((r) => setTimeout(r, 1500));
      const direct = (await fetch(`${MOCK}/__pushes`).then((r) => r.json())).find((p) => p.fid === 'fid-own-2');
      check('push envoyé directement à Firebase, au FID', direct?.via === 'firebase' && /^[0-9a-f-]{36}$/.test(direct?.data?.alertId ?? ''), JSON.stringify(direct));
      await saveConfig('FCM_SERVICE_ACCOUNT_JSON', '');
    }

    section('Réglages admin des alertes');
    await saveConfig('MOBILE_PUSH_TRIGGERS', 'task_deleted');
    await new Promise((r) => setTimeout(r, 400));
    const t2 = new Date(Date.now() - 1000).toISOString();
    const notTriggered = track(await http('POST', '/api/tasks', { cookie: admin.cookie, body: payload([uOwn.id], { title: `${PREFIX} déclencheur` }) }));
    await new Promise((r) => setTimeout(r, 1200));
    const trig = await http('GET', `/api/mobile/alerts?after=${encodeURIComponent(t2)}`, { cookie: sOwn.cookie });
    check('événement non retenu : pas d\'alerte', !trig.body?.alerts?.some((a) => a.actionUrl?.includes(notTriggered.body.id)), JSON.stringify(trig.body).slice(0, 200));
    await saveConfig('MOBILE_PUSH_TRIGGERS', '');
    socket.close();
    socketOwn.close();
  }

  section('Pièces jointes');
  const fileTask = track(await http('POST', '/api/tasks', { cookie: sOwn.cookie, body: payload([uOwn.id]) }));
  const form = () => {
    const data = new FormData();
    data.append('file', new Blob(['%PDF-1.4 test'], { type: 'application/pdf' }), 'test.pdf');
    return data;
  };
  const upload = await fetch(`${URL_}/api/tasks/${fileTask.body.id}/files`, { method: 'POST', headers: { Cookie: sOwn.cookie, 'X-App-Version': APP }, body: form() });
  const uploaded = upload.ok ? await upload.json() : null;
  check('envoi d\'un fichier', upload.status === 201, `HTTP ${upload.status}`);
  const noUpload = await fetch(`${URL_}/api/tasks/${fileTask.body.id}/files`, { method: 'POST', headers: { Cookie: sReader.cookie, 'X-App-Version': APP }, body: form() });
  check('lecteur : envoi refusé', noUpload.status === 403, `HTTP ${noUpload.status}`);
  if (uploaded) {
    const dl = await fetch(`${URL_}/api/files/tasks/${fileTask.body.id}/${encodeURIComponent(uploaded.name)}`, { headers: { Cookie: sOwn.cookie, 'X-App-Version': APP } });
    check('téléchargement', dl.status === 200, `HTTP ${dl.status}`);
    const anonymous = await fetch(`${URL_}/api/files/tasks/${fileTask.body.id}/${encodeURIComponent(uploaded.name)}`, { redirect: 'manual' });
    check('téléchargement sans session refusé', anonymous.status === 401, `HTTP ${anonymous.status}`);
    expectStatus('suppression', await http('DELETE', `/api/tasks/${fileTask.body.id}/files?fileId=${uploaded.id}`, { cookie: sOwn.cookie }), 200);
  }

  section('Connexion par QR code');
  const webFull = await webLogin(uFull.username, PASSWORD);
  const browser = { 'Sec-Fetch-Site': 'same-origin' };
  expectStatus('QR refusé depuis la session de l\'app', await http('POST', '/api/mobile/pairing', { cookie: sFull.cookie, headers: browser }), 403);
  expectStatus('QR refusé hors du navigateur', await http('POST', '/api/mobile/pairing', { cookie: webFull, web: true }), 403);
  const pairing = await http('POST', '/api/mobile/pairing', { cookie: webFull, web: true, headers: browser });
  expectStatus('code créé', pairing, 201);
  const code = new URL(pairing.body.link.replace('humbleplanning://', 'https://x/')).searchParams.get('code');
  check('lien au bon format', pairing.body.link.startsWith('humbleplanning://login?url=') && /^[0-9a-f]{64}$/.test(code));
  const paired = await http('POST', '/api/mobile/auth/pairing', { body: { code, device: device('qr') } });
  check('connexion par QR', paired.body?.status === 'authenticated', paired.text.slice(0, 200));
  const pairedCookie = `${paired.body?.session?.cookieName}=${paired.body?.session?.cookieValue}`;
  expectStatus('la session QR marche sur le REST', await http('GET', '/api/profile', { cookie: pairedCookie }), 200);
  check('session QR au bon utilisateur', (await http('GET', '/api/profile', { cookie: pairedCookie })).body?.id === uFull.id);
  const replay = await http('POST', '/api/mobile/auth/pairing', { body: { code, device: device('qr2') } });
  expectStatus('code à usage unique', replay, 401);
  expectStatus('code inventé refusé', await http('POST', '/api/mobile/auth/pairing', { body: { code: 'a'.repeat(64), device: device('qr3') } }), 401);
  expectStatus('code mal formé refusé', await http('POST', '/api/mobile/auth/pairing', { body: { code: 'abc' } }), 422);
  expectStatus('pas de QR sans session', await http('POST', '/api/mobile/pairing'), 401);

  section('Appareils et révocation');
  const list = await http('GET', '/api/mobile/devices', { cookie: sFull.cookie });
  check('deux appareils listés', list.body?.length === 2, `${list.body?.length}`);
  check('jeton de push jamais renvoyé', !JSON.stringify(list.body).includes('pushToken'));
  const other = list.body.find((d) => !d.current);
  expectStatus('révoquer l\'appareil d\'un autre : 404', await http('DELETE', `/api/mobile/devices/${other.id}`, { cookie: sOwn.cookie }), 404);
  expectStatus('révoquer son autre appareil', await http('DELETE', `/api/mobile/devices/${other.id}`, { cookie: sFull.cookie }), 200);
  expectStatus('session révoquée refusée', await http('GET', '/api/profile', { cookie: pairedCookie }), 401);

  section('Compte désactivé');
  const toDisable = await makeUser('off', ownRole);
  const sOff = await login(toDisable.username, PASSWORD, 'off');
  await http('PATCH', `/api/users/${toDisable.id}`, { cookie: admin.cookie, body: { active: false } });
  expectStatus('session d\'un compte désactivé refusée', await http('GET', '/api/profile', { cookie: sOff.cookie }), 401);
  const offLogin = await http('POST', '/api/mobile/auth/login', { body: { identifier: toDisable.username, password: PASSWORD, device: device('off') } });
  expectStatus('connexion d\'un compte désactivé refusée', offLogin, 403);

  section('Permission application mobile');
  // System roles: Manager has it by default, Membre does not
  const uManager = await makeUser('manager', managerRole.id);
  check('Manager : autorisé par défaut', (await http('POST', '/api/mobile/auth/login', { body: { identifier: uManager.username, password: PASSWORD, device: device('manager') } })).body?.status === 'authenticated');
  const uMember = await makeUser('membre', memberRole.id);
  const refused = await http('POST', '/api/mobile/auth/login', { body: { identifier: uMember.username, password: PASSWORD, device: device('membre') } });
  expectStatus('Membre : connexion refusée, 403', refused, 403);
  check('code stable, message traduit, pas de session', refused.body?.code === 'mobile.errors.appNotAllowed' && !!refused.body?.error && !refused.body?.session);
  // Withdrawn while signed in: blocked at the next request, no QR code, given back: open again
  const appRole = await makeRole('retrait', pages);
  const uWithdrawn = await makeUser('retrait', appRole);
  const sWithdrawn = await login(uWithdrawn.username, PASSWORD, 'retrait');
  await http('PUT', `/api/roles/${appRole}`, { cookie: admin.cookie, web: true, body: { permissions: pages.filter((p) => p !== 'mobile_app:use') } });
  const cut = await http('GET', '/api/mobile/bootstrap', { cookie: sWithdrawn.cookie });
  expectStatus('permission retirée : bloqué à la requête suivante', cut, 403);
  check('même code', cut.body?.code === 'mobile.errors.appNotAllowed');
  expectStatus('pas de QR code sans la permission', await http('POST', '/api/mobile/pairing', { cookie: sWithdrawn.cookie, web: true }), 403);
  const webCut = await fetch(`${URL_}/api/profile`, { headers: { Cookie: sWithdrawn.cookie } });
  check('le web reste ouvert à ce compte', webCut.status === 200, `HTTP ${webCut.status}`);
  await http('PUT', `/api/roles/${appRole}`, { cookie: admin.cookie, web: true, body: { permissions: pages } });
  expectStatus('permission rendue : la session repart', await http('GET', '/api/mobile/bootstrap', { cookie: sWithdrawn.cookie }), 200);

  section('Mot de passe forcé');
  const forced = await makeUser('force', ownRole, { forcePasswordChange: true });
  const forcedLogin = await http('POST', '/api/mobile/auth/login', { body: { identifier: forced.username, password: PASSWORD, device: device('force') } });
  check('changement exigé à la connexion', forcedLogin.body?.forcePasswordChange === true, forcedLogin.text.slice(0, 200));
  const forcedCookie = `${forcedLogin.body?.session?.cookieName}=${forcedLogin.body?.session?.cookieValue}`;
  const changedPwd = await http('POST', '/api/auth/change-password', { cookie: forcedCookie, body: { password: `${PASSWORD}9` } });
  expectStatus('changement accepté depuis l\'app', changedPwd, 200);
  const relog = await http('POST', '/api/mobile/auth/login', { body: { identifier: forced.username, password: `${PASSWORD}9`, device: device('force') } });
  check('nouveau mot de passe valable, plus d\'exigence', relog.body?.status === 'authenticated' && relog.body?.forcePasswordChange === false);

  section('Double authentification');
  const twoFa = await makeUser('2fa', ownRole);
  const s2fa = await login(twoFa.username, PASSWORD, '2fa');
  const enable = await http('POST', '/api/profile/2fa/enable', { cookie: s2fa.cookie, body: { password: PASSWORD } });
  expectStatus('activation', enable, 200);
  const totp = generateTotp(enable.body.secret);
  const verify = await http('POST', '/api/profile/2fa/verify', { cookie: s2fa.cookie, body: { code: totp } });
  expectStatus('code accepté', verify, 200);
  const step1 = await http('POST', '/api/mobile/auth/login', { body: { identifier: twoFa.username, password: PASSWORD, device: device('2fa') } });
  check('code exigé, pas de session', step1.body?.status === 'two_factor_required' && !step1.body?.session);
  const badCode = await http('POST', '/api/mobile/auth/2fa', { body: { pendingToken: step1.body.pendingToken, code: '000000', device: device('2fa') } });
  check('mauvais code refusé', badCode.status >= 400 && badCode.status < 500, `HTTP ${badCode.status}`);
  const backup = await http('POST', '/api/mobile/auth/2fa', { body: { pendingToken: step1.body.pendingToken, code: verify.body.backupCodes[0], isBackupCode: true, device: device('2fa') } });
  check('code de secours accepté', backup.body?.status === 'authenticated', backup.text.slice(0, 200));
  expectStatus('la session 2FA marche', await http('GET', '/api/profile', { cookie: `${backup.body?.session?.cookieName}=${backup.body?.session?.cookieValue}` }), 200);

  section('Version minimale de l\'app');
  // Settings are changed from the web: the version gate only applies to the app
  const save = (key, value) => http('PUT', '/api/config', { cookie: admin.cookie, web: true, body: { [key]: value } });
  const set = await save('MOBILE_MIN_APP_VERSION', '9.0.0');
  expectStatus('réglage enregistré', set, 200);
  await new Promise((r) => setTimeout(r, 600));
  const blocked = await http('GET', '/api/profile', { cookie: sOwn.cookie });
  expectStatus('app trop ancienne : 426', blocked, 426);
  // The download page is the server's choice (MOBILE_STORE_URL, by default the GitHub releases of the app):
  // any link the app can open (BlockingScreen.openStore) is fine.
  check('version minimale et page du store fournies', blocked.body?.params?.minimumVersion === '9.0.0' && /^(https|market):\/\//i.test(blocked.body?.params?.storeUrl ?? ''));
  const newer = await http('GET', '/api/profile', { cookie: sOwn.cookie, headers: { 'X-App-Version': '9.1.0' } });
  expectStatus('app à jour : passe', newer, 200);
  const blockedLogin = await http('POST', '/api/mobile/auth/login', { body: { identifier: uOwn.username, password: PASSWORD, device: device('t') } });
  expectStatus('connexion bloquée aussi', blockedLogin, 426);
  const web = await fetch(`${URL_}/api/profile`, { headers: { Cookie: sOwn.cookie } });
  check('le web n\'est pas concerné', web.status === 200, `HTTP ${web.status}`);
  await save('MOBILE_MIN_APP_VERSION', '');
  await new Promise((r) => setTimeout(r, 600));
  expectStatus('minimum retiré : passe', await http('GET', '/api/profile', { cookie: sOwn.cookie }), 200);

  section('Maintenance');
  await save('MAINTENANCE_MODE', 'true');
  await new Promise((r) => setTimeout(r, 600));
  const down = await http('GET', '/api/profile', { cookie: sOwn.cookie });
  expectStatus('maintenance : 423', down, 423);
  check('maintenance : message traduit', !!down.body?.error);
  expectStatus('admin passe', await http('GET', '/api/profile', { cookie: admin.cookie }), 200);
  await save('MAINTENANCE_MODE', 'false');
  await new Promise((r) => setTimeout(r, 600));

  section('Connexion mobile désactivée');
  await save('MOBILE_LOGIN_ENABLED', 'false');
  await new Promise((r) => setTimeout(r, 600));
  expectStatus('connexion mot de passe refusée', await http('POST', '/api/mobile/auth/login', { body: { identifier: uOwn.username, password: PASSWORD, device: device('t') } }), 403);
  check('info l\'annonce', (await http('GET', '/api/mobile/info')).body?.loginEnabled === false);
  await save('MOBILE_LOGIN_ENABLED', 'true');
  await new Promise((r) => setTimeout(r, 600));

  section('Force brute');
  if (LOCAL) {
    let locked = null;
    for (let i = 0; i < 8 && !locked; i++) {
      const res = await http('POST', '/api/mobile/auth/login', { body: { identifier: uOwn2.username, password: 'mauvais', device: device('t') } });
      if (res.status === 429) locked = res;
    }
    check('compte bloqué après plusieurs échecs', !!locked);
    check('message avec délai', !!locked?.body?.error);
  } else {
    console.log('  -- ignoré hors serveur local (bloquerait un compte)');
  }

  section('Assistant IA');
  if (LOCAL) {
    const aiConfig = (await http('GET', '/api/ai-chat/admin/config', { cookie: admin.cookie, web: true })).body?.config ?? {};
    await http('PUT', '/api/ai-chat/admin/config', { cookie: admin.cookie, web: true, body: { ...aiConfig, enabled: true, apiKey: 'test-key', models: ['test/model'], defaultModel: 'test/model' } }).then((r) => { if (r.status >= 300) console.log('     config IA :', r.status, r.text.slice(0, 300)); });
    await new Promise((r) => setTimeout(r, 800));
    const bootFull = await http('GET', '/api/mobile/bootstrap', { cookie: sFull.cookie });
    check('assistant disponible avec le droit', bootFull.body?.assistant?.available === true, JSON.stringify(bootFull.body?.assistant));
    check('assistant masqué sans le droit', (await http('GET', '/api/mobile/bootstrap', { cookie: sReader.cookie })).body?.assistant?.available === false);
    expectStatus('conversation refusée sans le droit', await http('POST', '/api/ai-chat/conversations', { cookie: sReader.cookie, body: {} }), 403);
    if (bootFull.body?.assistant?.available) {
      const conv = await http('POST', '/api/ai-chat/conversations', { cookie: sFull.cookie, body: { title: `${PREFIX} conv` } });
      expectStatus('conversation créée', conv, 201);
      const turn = await fetch(`${URL_}/api/ai-chat/conversations/${conv.body.id}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Cookie: sFull.cookie, 'X-App-Version': APP },
        body: JSON.stringify({ messages: [{ id: 'm1', role: 'user', parts: [{ type: 'text', text: 'bonjour' }] }] }),
      });
      const stream = await turn.text();
      check('réponse en flux', turn.status === 200 && stream.includes('text-delta'), stream.slice(0, 300));
      check('texte de la réponse reçu', stream.includes('test'));
      const question = `crée une tâche :: ${JSON.stringify({ projectId, userIds: [uFull.id], statusId, startDate: day(30), title: `${PREFIX} via IA` })}`;
      const write = await fetch(`${URL_}/api/ai-chat/conversations/${conv.body.id}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Cookie: sFull.cookie, 'X-App-Version': APP },
        body: JSON.stringify({ messages: [{ id: 'm2', role: 'user', parts: [{ type: 'text', text: question }] }] }),
      });
      const writeStream = await write.text();
      check('une écriture demande une confirmation', writeStream.includes('tool-approval-request'), writeStream.slice(0, 400));
      console.log(`     forme du flux : ${[...new Set([...writeStream.matchAll(/"type":"([a-z-]+)"/g)].map((m) => m[1]))].join(', ')}`);
      const events = writeStream.split(/\r?\n/).filter((l) => l.startsWith('data: {')).map((l) => JSON.parse(l.slice(6)));
      const inputEvent = events.find((e) => e.type === 'tool-input-available');
      const approvalEvent = events.find((e) => e.type === 'tool-approval-request');
      console.log(`     ${JSON.stringify(approvalEvent)}`);
      check('rien écrit avant la confirmation', !(await http('GET', `/api/mobile/tasks/sync?startDate=${day(30)}&endDate=${day(30)}`, { cookie: sFull.cookie })).body?.changed?.some((t) => t.title === `${PREFIX} via IA`));
      if (inputEvent && approvalEvent) {
        await new Promise((r) => setTimeout(r, 1500));
        const resume = await fetch(`${URL_}/api/ai-chat/conversations/${conv.body.id}/messages`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Cookie: sFull.cookie, 'X-App-Version': APP },
          body: JSON.stringify({ messages: [
            { id: 'm2', role: 'user', parts: [{ type: 'text', text: question }] },
            { id: 'r2', role: 'assistant', parts: [{ type: `tool-${inputEvent.toolName}`, toolCallId: inputEvent.toolCallId, state: 'approval-responded', input: inputEvent.input, approval: { id: approvalEvent.approvalId, approved: true } }] },
          ] }),
        });
        const resumed = await resume.text();
        check("confirmation : la reprise exécute l'outil", resumed.includes('tool-output-available'), resumed.slice(0, 500));
        const wrote = await http('GET', `/api/mobile/tasks/sync?startDate=${day(30)}&endDate=${day(30)}`, { cookie: sFull.cookie });
        const aiTask = wrote.body?.changed?.find((t) => t.title === `${PREFIX} via IA`);
        check("la tâche de l'IA existe", !!aiTask, JSON.stringify(wrote.body?.changed?.map((t) => t.title)));
        if (aiTask) created.tasks.push(aiTask.id);
      }
    }
  } else {
    console.log('  -- ignoré hors serveur local (dépend de la clé du serveur)');
  }

  section('Déconnexion');
  const session = await login(uReader.username, PASSWORD, 'fin');
  expectStatus('déconnexion', await http('POST', '/api/mobile/auth/logout', { cookie: session.cookie }), 200);
  expectStatus('session fermée', await http('GET', '/api/profile', { cookie: session.cookie }), 401);
  expectStatus('déconnexion sans session : sans erreur', await http('POST', '/api/mobile/auth/logout'), 200);

  await cleanup(admin);
}

async function cleanup(admin) {
  section('Nettoyage');
  for (const id of created.tasks) await http('DELETE', `/api/tasks/${id}`, { cookie: admin.cookie });
  for (const id of created.tasks) await http('PUT', `/api/tasks/${id}`, { cookie: admin.cookie, body: { isLocked: false, isMilestone: false } }).then(() => http('DELETE', `/api/tasks/${id}`, { cookie: admin.cookie }));
  for (const id of created.projects) await http('DELETE', `/api/projects/${id}`, { cookie: admin.cookie });
  for (const id of created.users) await http('DELETE', `/api/users/${id}`, { cookie: admin.cookie });
  for (const id of created.teams) await http('DELETE', `/api/teams/${id}`, { cookie: admin.cookie });
  for (const id of created.roles) await http('DELETE', `/api/roles/${id}`, { cookie: admin.cookie });
  console.log(`  ${created.users.length} comptes, ${created.roles.length} rôles, ${created.projects.length} projets retirés`);
}

/** RFC 6238 TOTP (SHA-1, 6 digits, 30 s). */
function generateTotp(secret) {
  const { createHmac } = createRequire(import.meta.url)('node:crypto');
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

main()
  .catch((error) => check('la campagne va au bout', false, error.stack))
  .finally(() => {
    const failed = results.filter((r) => !r.ok);
    console.log(`\n${results.length - failed.length}/${results.length} vérifications réussies`);
    for (const f of failed) console.log(`  KO [${f.group}] ${f.name}`);
    process.exitCode = failed.length ? 1 : 0;
  });
