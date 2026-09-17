// MetaML Target Platform portal data is refreshed from this application's API.

const REFRESH_MS = 2000;

// Presets are portal-only demo choices. The API stores an arbitrary provider-identity -> FIFO
// output-map configuration; it contains no RedCollar routing or activity knowledge.
const SCENARIO_PRESETS = Object.freeze({
  happy: { label: 'Happy Path', responses: {
    'order-approval': [{ orderApproved: true }],
    'quality-check': [{ qualityPassed: true }]
  }},
  edit: { label: 'Order Requires Editing', responses: {
    'order-approval': [{ orderApproved: false }, { orderApproved: true }],
    'quality-check': [{ qualityPassed: true }]
  }},
  rework: { label: 'Quality/Rework Until Resolved', responses: {
    'order-approval': [{ orderApproved: true }],
    'quality-check': [{ qualityPassed: false }, { qualityPassed: false }, { qualityPassed: true }]
  }}
});

const state = {
  view: 'overview',
  pairs: [],           // abbreviated list from /api/portal/lockstep?limit=20
  userKey: null,       // businessKey explicitly chosen by the user, or null = follow most recent
  detail: null,        // full /api/portal/lockstep?businessKey=X for the effective key
  xmlCache: new Map(), // processDefinitionKey -> raw BPMN XML
  panes: {},           // original/twin -> BpmnPane
  runtimeKindFilter: '',
  pairStartAvailable: false
};

function $(id) { return document.getElementById(id); }
function esc(v) {
  if (v === null || v === undefined) return '';
  return String(v).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
async function getJson(url) {
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error(url + ' -> HTTP ' + res.status);
  return res.json();
}
async function getText(url) {
  const res = await fetch(url);
  if (!res.ok) return null;
  return res.text();
}
function effectiveKey() {
  if (state.userKey) return state.userKey;
  return state.pairs.length ? state.pairs[0].businessKey : null;
}
function pill(stateName) {
  return '<span class="pill ' + esc(stateName) + '">' + esc(stateName.replace('_', ' ')) + '</span>';
}
function fmtMs(ms) {
  if (ms === null || ms === undefined) return '';
  if (ms < 1000) return ms + ' ms';
  return (ms / 1000).toFixed(1) + ' s';
}

// ---------------------------------------------------------------------------
// bpmn-js renders deployed BPMN XML; state markers are based on live lockstep data.
// ---------------------------------------------------------------------------

class BpmnPane {
  constructor(containerId) {
    this.viewer = new BpmnJS({ container: '#' + containerId });
    this.loadedKey = null;
    this.marked = new Set();
  }
  async ensureLoaded(key) {
    if (!key || this.loadedKey === key) return true;
    let xml = state.xmlCache.get(key);
    if (!xml) {
      xml = await getText('/api/portal/bpmn/' + encodeURIComponent(key));
      if (!xml) return false;
      state.xmlCache.set(key, xml);
    }
    try {
      await this.viewer.importXML(xml);
      this.viewer.get('canvas').zoom('fit-viewport');
      this.loadedKey = key;
      this.marked.clear();
      return true;
    } catch (e) {
      console.warn('BPMN import failed for', key, e);
      return false;
    }
  }
  applyStates(stateByActivityId) {
    let canvas;
    try { canvas = this.viewer.get('canvas'); } catch (e) { return; }
    const classes = ['marker-completed', 'marker-active', 'marker-waiting', 'marker-incident', 'marker-pending'];
    for (const id of this.marked) {
      for (const c of classes) { try { canvas.removeMarker(id, c); } catch (e) { /* gone */ } }
    }
    this.marked = new Set();
    for (const [id, st] of Object.entries(stateByActivityId || {})) {
      const cls = classFor(st);
      if (!cls) continue;
      try { canvas.addMarker(id, cls); this.marked.add(id); } catch (e) { /* not in this diagram */ }
    }
  }
}
function classFor(s) {
  switch (s) {
    case 'COMPLETED': return 'marker-completed';
    case 'ACTIVE': return 'marker-active';
    case 'WAITING_SYNC': return 'marker-waiting';
    case 'INCIDENT': return 'marker-incident';
    case 'PENDING': return 'marker-pending';
    default: return null;
  }
}

// ---------------------------------------------------------------------------
// Header / platform status
// ---------------------------------------------------------------------------

let overviewData = null;

async function refreshHeader() {
  overviewData = await getJson('/api/portal/overview');
  $('dotPlatform').className = 'dot ok';
  $('stPlatform').textContent = 'Platform Running';
  const rc = !!overviewData.rabbitConnected;
  $('dotRabbit').className = 'dot ' + (rc ? 'ok' : 'bad');
  $('stRabbit').textContent = 'RabbitMQ ' + (rc ? 'Connected' : 'Disconnected');
  $('stUptime').textContent = 'Uptime ' + fmtUptime(overviewData.uptimeSeconds);
}
function fmtUptime(sec) {
  sec = sec || 0;
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  if (h > 0) return h + 'h ' + m + 'm';
  if (m > 0) return m + 'm ' + s + 's';
  return s + 's';
}

// ---------------------------------------------------------------------------
// Pair list + detail
// ---------------------------------------------------------------------------

async function refreshPairsAndDetail() {
  state.pairs = await getJson('/api/portal/lockstep?limit=20');
  const key = effectiveKey();
  if (key) {
    const rows = await getJson('/api/portal/lockstep?businessKey=' + encodeURIComponent(key));
    state.detail = rows[0] || null;
  } else {
    state.detail = null;
  }
}

async function probePairStart() {
  try {
    const res = await fetch('/api/proxy/health');
    state.pairStartAvailable = res.ok;
  } catch (e) {
    state.pairStartAvailable = false;
  }
  $('btnStartRun').hidden = !state.pairStartAvailable;
  $('btnStartRun2').hidden = !state.pairStartAvailable;
}

function selectedScenario() {
  return SCENARIO_PRESETS[$('scenarioPreset').value] || SCENARIO_PRESETS.happy;
}
function stableJson(value) {
  if (Array.isArray(value)) return '[' + value.map(stableJson).join(',') + ']';
  if (value && typeof value === 'object') return '{' + Object.keys(value).sort()
    .map(key => JSON.stringify(key) + ':' + stableJson(value[key])).join(',') + '}';
  return JSON.stringify(value);
}
function configuredScenarioLabel(configuration) {
  if (!configuration || !Object.keys(configuration).length) return 'Scenario configuration cleared after run completion.';
  const actual = stableJson(configuration);
  for (const preset of Object.values(SCENARIO_PRESETS)) {
    if (actual === stableJson(preset.responses)) return 'Scenario: ' + preset.label;
  }
  return 'Scenario: custom provider responses';
}

async function startNewRun() {
  const key = 'run-' + Date.now().toString(36);
  const scenario = selectedScenario();
  $('ovStartHint').textContent = 'starting ' + key + '...';
  try {
    // STEP is configured before the first Camunda instance is created. Generated workers may see
    // the tasks, but RunExecutionGate keeps both sides held until configuration below succeeds.
    const mode = '&executionMode=STEP';
    const p = await fetch('/api/proxy/start?businessKey=' + encodeURIComponent(key) + mode, { method: 'POST' });
    const pj = await p.json();
    if (!p.ok) { $('ovStartHint').textContent = 'proxy start failed: ' + JSON.stringify(pj); return; }
    const t = await fetch('/api/twin/start?businessKey=' + encodeURIComponent(key), { method: 'POST' });
    const tj = await t.json();
    if (!t.ok) { $('ovStartHint').textContent = 'twin start failed: ' + JSON.stringify(tj); return; }
    const configured = await fetch('/api/portal/runs/' + encodeURIComponent(key) + '/capability-responses', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(scenario.responses)
    });
    const configurationBody = await configured.json();
    if (!configured.ok) {
      $('ovStartHint').textContent = 'pair started and remains held; scenario configuration failed: '
        + (configurationBody.error || configured.status);
      return;
    }
    state.userKey = key;
    $('ovStartHint').textContent = 'started ' + key + ' — ' + scenario.label
      + ' configured and held. Use Go to Next Step or Complete Process.';
    await tick();
  } catch (e) {
    $('ovStartHint').textContent = 'start failed: ' + e.message;
  }
}

// ---------------------------------------------------------------------------
// Overview
// ---------------------------------------------------------------------------

// The "current" business activity is read from the pair's own `steps` list (real BPMN activity ids
// the Twin actually mirrors), never from raw active-activity ids - those can be a technical
// synchronization catch event (sync_evt_*) that means nothing to a reader. The first non-completed
// step is "current"; once every step is COMPLETED, the last one is shown as such.
function currentStep(steps) {
  if (!steps || !steps.length) return null;
  return steps.find(s => s.originalState !== 'COMPLETED' || s.twinState !== 'COMPLETED')
      || steps[steps.length - 1];
}
function stateLabel(s) { return s ? s.replace('_', ' ').toLowerCase() : ''; }

function renderOverview() {
  const d = state.detail;
  $('ovProcessName').textContent = d ? (d.original.processDefinitionKey || 'Original/Main Process') : 'No process pair running';

  // Once both instances have actually ended, "current step" is meaningless - a step can be left
  // PENDING forever because an exclusive gateway simply never took that branch, which is correct
  // BPMN behaviour, not an unfinished run. Report completion from the instances' own endTime rather
  // than from step state in that case.
  const bothEnded = d && !d.original.active && !d.twin.active;
  const step = d && !bothEnded ? currentStep(d.steps) : null;
  if (d && step) {
    $('ovOriginalActivity').textContent = step.originalName;
    $('ovOriginalSub').innerHTML = pill(step.originalState);
    $('ovTwinActivity').textContent = step.twinName;
    $('ovTwinSub').innerHTML = pill(step.twinState);
  } else if (d) {
    $('ovOriginalActivity').textContent = d.original.active ? 'running' : 'completed';
    $('ovOriginalSub').textContent = '';
    $('ovTwinActivity').textContent = d.twin.active ? 'running' : 'completed';
    $('ovTwinSub').textContent = '';
  } else {
    $('ovOriginalActivity').textContent = '—';
    $('ovOriginalSub').textContent = 'No pair running yet';
    $('ovTwinActivity').textContent = '—';
    $('ovTwinSub').textContent = 'No pair running yet';
  }

  const o = overviewData || {};
  $('ovCards').innerHTML = [
    ['Running Instances', o.runningInstances],
    ['Completed Instances', o.completedInstances],
    ['Open Human Task', o.openTasks],
    ['Incidents', o.openIncidents]
  ].map(([k, v]) => card(k, v)).join('');

  const incidents = o.openIncidents || 0;
  const banner = $('ovIncidentBanner');
  if (incidents > 0) {
    banner.hidden = false;
    banner.innerHTML = '<div class="banner incident"><span class="txt">' + incidents +
      ' real Camunda incident(s) open</span></div>';
  } else {
    banner.hidden = true;
  }

  const syncPanel = $('ovSyncPanel');
  if (d && d.steps && d.steps.length) {
    syncPanel.hidden = false;
    $('ovSyncList').innerHTML = d.steps.map(s => {
      const done = s.originalState === 'COMPLETED' && s.twinState === 'COMPLETED';
      const now = !done && (s.originalState !== 'PENDING' || s.twinState !== 'PENDING');
      const icoClass = done ? 'done' : (now ? 'now' : 'next');
      const ico = done ? '✓' : (now ? '●' : '○');
      return '<li><span class="ico ' + icoClass + '">' + ico + '</span> ' + esc(s.originalName) + '</li>';
    }).join('');
  } else {
    syncPanel.hidden = true;
  }

  $('ovDefinitions').innerHTML = table(
    [
      { label: 'Key', cell: r => '<span class="mono">' + esc(r.key) + '</span>' },
      { label: 'Name', cell: r => esc(r.name || '') },
      { label: 'Version', cell: r => esc(r.version) },
      { label: 'Running', cell: r => esc(r.running) },
      { label: 'Ever Started', cell: r => esc(r.everStarted) }
    ], o.processDefinitions || [], 'No process definition is deployed.');
}
function card(k, v) {
  return '<div class="card"><div class="k">' + esc(k) + '</div><div class="v">' + esc(v) + '</div></div>';
}
function table(columns, rows, emptyMessage) {
  if (!rows.length) return '<div class="empty">' + esc(emptyMessage) + '</div>';
  const head = columns.map(c => '<th>' + esc(c.label) + '</th>').join('');
  const body = rows.map(r => '<tr>' + columns.map(c => '<td>' + c.cell(r) + '</td>').join('') + '</tr>').join('');
  return '<table><thead><tr>' + head + '</tr></thead><tbody>' + body + '</tbody></table>';
}

// ---------------------------------------------------------------------------
// Live Execution
// ---------------------------------------------------------------------------

function populatePairSelect() {
  const sel = $('pairSelect');
  const prev = sel.value;
  sel.innerHTML = state.pairs.map(p =>
    '<option value="' + esc(p.businessKey) + '">' + esc(p.businessKey) + ' — ' +
    esc(p.original.processDefinitionKey) + (p.original.active ? ' (running)' : ' (ended)') + '</option>'
  ).join('');
  const key = effectiveKey();
  if (key) sel.value = key;
  else if (prev) sel.value = prev;
}

function stepStateMap(steps, side) {
  const m = {};
  for (const s of steps) m[s.activityId] = side === 'original' ? s.originalState : s.twinState;
  return m;
}

async function renderLive() {
  populatePairSelect();
  const d = state.detail;
  $('liveEmpty').hidden = !!d;
  $('liveBody').style.display = d ? '' : 'none';
  if (!d) return;

  const execution = await getJson('/api/portal/runs/' + encodeURIComponent(d.businessKey) + '/execution');
  $('executionControl').hidden = false;
  $('executionState').textContent = execution.mode === 'STEP_WAITING'
    ? 'Step mode — waiting for release' : execution.mode === 'STEP_RUNNING'
      ? 'Step mode — running current boundary' : 'Automatic — workers may continue';
  const used = execution.providersUsed || [];
  $('configuredScenario').textContent = configuredScenarioLabel(execution.capabilityResponseConfiguration);
  $('providersUsed').textContent = 'Capability providers used: ' + (used.length ? used.join(', ') : 'None yet');

  $('pairOriginalStatus').className = 'pill ' + (d.original.active ? 'RUNNING' : 'ENDED');
  $('pairOriginalStatus').textContent = 'Original: ' + (d.original.active ? 'running' : 'ended');
  $('pairTwinStatus').className = 'pill ' + (d.twin.active ? 'RUNNING' : 'ENDED');
  $('pairTwinStatus').textContent = 'Twin: ' + (d.twin.active ? 'running' : 'ended');

  $('origBpmnName').textContent = d.original.processDefinitionKey;
  $('twinBpmnName').textContent = d.twin.processDefinitionKey;

  if (!state.panes.original) state.panes.original = new BpmnPane('origCanvas');
  if (!state.panes.twin) state.panes.twin = new BpmnPane('twinCanvas');
  await state.panes.original.ensureLoaded(d.original.processDefinitionKey);
  await state.panes.twin.ensureLoaded(d.twin.processDefinitionKey);
  state.panes.original.applyStates(stepStateMap(d.steps, 'original'));
  state.panes.twin.applyStates(stepStateMap(d.steps, 'twin'));

  $('syncSteps').innerHTML = d.steps.map(s => {
    const synced = s.originalState === 'COMPLETED' && s.twinState === 'COMPLETED';
    return '<div class="syncStep"><div class="name">' + esc(s.originalName) + '</div>' +
      '<div class="states">' + pill(s.originalState) + '<span style="color:var(--ink-soft)">↔</span>' + pill(s.twinState) +
      (synced ? ' <span style="color:var(--ok);font-size:11px;font-weight:700;">✓ synced</span>' : '') + '</div></div>';
  }).join('') || '<div class="empty">No corresponding activities to synchronize.</div>';

  await renderHumanTaskPanel(d.businessKey);

  $('stepTable').innerHTML = table([
    { label: '#', cell: r => r.index },
    { label: 'Activity', cell: r => esc(r.originalName) },
    { label: 'Activity ID', cell: r => '<span class="mono">' + esc(r.activityId) + '</span>' },
    { label: 'Original', cell: r => pill(r.originalState) },
    { label: 'Twin', cell: r => pill(r.twinState) }
  ], d.steps, 'No steps.');
}

async function controlExecution(action) {
  const key = effectiveKey();
  if (!key) return;
  const id = action === 'next' ? 'btnNextStep' : 'btnCompleteProcess';
  const button = $(id);
  button.disabled = true;
  try {
    const res = await fetch('/api/portal/runs/' + encodeURIComponent(key) + '/' + action, { method: 'POST' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    await tick();
  } catch (e) {
    $('executionState').textContent = 'Control failed: ' + e.message;
  } finally { button.disabled = false; }
}

async function renderHumanTaskPanel(businessKey) {
  const tasks = await getJson('/api/portal/tasks');
  const mine = tasks.filter(t => t.businessKey === businessKey);
  const host = $('taskPanelHost');
  if (!mine.length) { host.innerHTML = ''; return; }
  host.innerHTML = mine.map(t =>
    '<div class="taskPanel"><div class="lbl">Current Human Task</div>' +
    '<div class="actName">' + esc(t.name || t.taskDefinitionKey) + '</div>' +
    '<div class="hint" style="margin-bottom:8px;">Waiting for human &mdash; real Camunda user task</div>' +
    '<button class="btn small" data-task="' + esc(t.id) + '">Complete Task</button></div>'
  ).join('');
  host.querySelectorAll('button[data-task]').forEach(btn => {
    btn.addEventListener('click', async () => {
      btn.disabled = true; btn.textContent = 'Completing…';
      try {
        const res = await fetch('/api/portal/tasks/' + encodeURIComponent(btn.dataset.task) + '/complete',
          { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
        const body = await res.json();
        if (!res.ok || !body.completed) { btn.textContent = 'Failed: ' + (body.error || res.status); return; }
        await tick();
      } catch (e) { btn.textContent = 'Failed: ' + e.message; }
    });
  });
}

// ---------------------------------------------------------------------------
// Communication
// ---------------------------------------------------------------------------

async function renderComm() {
  const d = state.detail;
  const rowsHost = $('commRows'), tableHost = $('commTable'), empty = $('commEmpty');
  if (!d) { rowsHost.innerHTML = ''; tableHost.innerHTML = ''; empty.hidden = false; return; }

  const messages = (await getJson('/api/portal/messages?limit=500'))
    .filter(m => m.businessKey === d.businessKey);

  // Group TASK/RESPONSE entries by signal name so each row shows one activity's full round trip.
  const bySignal = new Map();
  for (const m of messages) {
    if (!m.signal) continue;
    if (!bySignal.has(m.signal)) bySignal.set(m.signal, { task: null, response: null });
    const g = bySignal.get(m.signal);
    if (m.direction === 'TASK' && !g.task) g.task = m;
    if (m.direction === 'RESPONSE' && !g.response) g.response = m;
  }

  // A signal's label: the platform's own "sync_<activityId>" lockstep convention maps back to the
  // step's real activity names when the id matches one of this pair's own steps; a BPMN's own
  // natively-authored signal (RedCollar's checkingSignal, orderVerifySignal, etc. - any name, no
  // fixed convention) has no such mapping, so its own name is shown as-is on both sides instead.
  // Never assumes every signal follows the sync_ shape.
  const nameByActivity = {};
  for (const s of d.steps) nameByActivity[s.activityId] = s.originalName;
  function labelFor(signal) {
    if (signal.startsWith('sync_')) {
      const activityId = signal.slice('sync_'.length);
      if (nameByActivity[activityId]) return nameByActivity[activityId];
    }
    return signal;
  }

  const rows = Array.from(bySignal.entries())
    .filter(([, ev]) => ev.task || ev.response)
    .sort((a, b) => (a[1].task || a[1].response).epochMillis - (b[1].task || b[1].response).epochMillis);

  empty.hidden = rows.length > 0;
  rowsHost.innerHTML = rows.map(([signal, ev]) => {
    const rt = (ev.task && ev.response) ? fmtMs(Math.abs(ev.response.epochMillis - ev.task.epochMillis)) : null;
    const label = labelFor(signal);
    return '<div class="commRow">' +
      '<div class="commSide"><div class="commActivity">' + esc(label) + '</div>' +
      (ev.task ? '<div class="step"><span class="t">TASK &rarr;</span> ' + esc(ev.task.time) + '</div>' : '<div class="step"><span class="t">no TASK yet</span></div>') + '</div>' +
      '<div class="commMid">' + (rt ? '<div class="rt">' + rt + '</div><div>round trip</div>' : '<div>&#8596;</div>') + '</div>' +
      '<div class="commSide" style="text-align:right;">' +
      (ev.response ? '<div class="step" style="justify-content:flex-end;"><span class="t">&larr; RESPONSE</span> ' + esc(ev.response.time) + '</div>' : '<div class="step" style="justify-content:flex-end;"><span class="t">awaiting RESPONSE</span></div>') +
      '<div class="commActivity">' + esc(label) + '</div></div>' +
      '</div>';
  }).join('');

  tableHost.innerHTML = table([
    { label: 'Time', cell: r => '<span class="mono">' + esc(r.time) + '</span>' },
    { label: 'Direction', cell: r => esc(r.direction || r.kind) },
    { label: 'Signal', cell: r => '<span class="mono">' + esc(r.signal || '') + '</span>' },
    { label: 'Event', cell: r => '<span class="mono">' + esc(r.message) + '</span>' }
  ], messages, 'No messages for this pair yet.');
}

// ---------------------------------------------------------------------------
// Runtime
// ---------------------------------------------------------------------------

async function renderRuntime() {
  const logs = await getJson('/api/portal/logs?limit=400&kinds=' + encodeURIComponent(state.runtimeKindFilter));
  const d = state.detail;
  const originalPid = d ? d.original.processInstanceId : null;
  const twinPid = d ? d.twin.processInstanceId : null;

  // SIDE resolution: an entry may carry an explicit generated PROXY/TWIN label, otherwise its
  // processInstanceId is matched against the selected pair. That covers capability and worker-
  // failure records without assuming which side invoked a provider. SYSTEM (platform-level, no
  // process id) and anything unpaired stay neutral rather than being misattributed.
  const left = [], right = [];
  let otherCount = 0;
  for (const e of logs) {
    let side = e.side;
    if (!side) {
      if (e.processInstanceId && e.processInstanceId === originalPid) side = 'PROXY';
      else if (e.processInstanceId && e.processInstanceId === twinPid) side = 'TWIN';
    }
    if (side === 'PROXY') left.push(e);
    else if (side === 'TWIN') right.push(e);
    else otherCount++;
  }

  $('runtimeOrigCount').textContent = left.length + ' events';
  $('runtimeTwinCount').textContent = right.length + ' events';
  $('runtimeOrig').innerHTML = logRows(left, 'No Original/Proxy activity yet.');
  $('runtimeTwin').innerHTML = logRows(right, 'No Twin activity yet.');

  const otherPanel = $('runtimeOtherPanel');
  if (otherCount > 0) {
    otherPanel.hidden = false;
    $('runtimeOtherHint').textContent = otherCount + ' platform/system event(s) (startup, other pairs, unattributed) not shown above.';
  } else {
    otherPanel.hidden = true;
  }
}
function logRows(entries, emptyMessage) {
  if (!entries.length) return '<div class="empty">' + esc(emptyMessage) + '</div>';
  return entries.map(e =>
    '<div class="logRow"><span class="lt mono">' + esc(e.time) + '</span><span class="lm mono">' + esc(e.message) + '</span></div>'
  ).join('');
}

// ---------------------------------------------------------------------------
// System
// ---------------------------------------------------------------------------

async function renderSystem() {
  const o = overviewData || {};
  $('sysProviders').innerHTML = (o.capabilityProviders || []).length
    ? o.capabilityProviders.map(p => '<span class="providerChip">' + esc(p) + '</span>').join('')
    : '<span class="hint">No capability provider is on this application’s classpath.</span>';

  const modes = await getJson('/api/portal/providers/technical-modes');
  $('sysProviderModes').innerHTML = modes.length
    ? modes.map(p => {
        const failing = p.technicalMode === 'TECHNICAL_FAILURE';
        return '<div class="providerMode"><span class="identity">' + esc(p.providerIdentity) + '</span>' +
          '<span class="mode ' + (failing ? 'failure' : '') + '">' + esc(p.technicalMode) + '</span>' +
          '<button class="btn secondary small" data-provider-identity="' + esc(p.providerIdentity) + '" ' +
          'data-technical-mode="' + (failing ? 'NORMAL' : 'TECHNICAL_FAILURE') + '">' +
          (failing ? 'Restore Provider' : 'Simulate Technical Failure') + '</button></div>';
      }).join('')
    : '';
  document.querySelectorAll('[data-provider-identity]').forEach(button => button.addEventListener('click', async () => {
    button.disabled = true;
    try {
      const identity = button.dataset.providerIdentity;
      const mode = button.dataset.technicalMode;
      const response = await fetch('/api/portal/providers/' + encodeURIComponent(identity) + '/technical-mode', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ technicalMode: mode })
      });
      if (!response.ok) throw new Error('technical mode update -> HTTP ' + response.status);
      await tick();
    } catch (e) {
      console.error(e);
      button.disabled = false;
    }
  }));

  $('sysPlatform').innerHTML = [
    ['RabbitMQ', o.rabbitMq + (o.rabbitConnected ? ' (connected)' : ' (unreachable)')],
    ['Messaging Enabled', o.messagingEnabled],
    ['Workbench Dependency', o.workbenchUrlConfigured],
    ['Uptime', fmtUptime(o.uptimeSeconds)]
  ].map(([k, v]) => '<div class="kv"><span class="k">' + esc(k) + '</span><span class="v">' + esc(v) + '</span></div>').join('');

  const instances = await getJson('/api/portal/instances');
  $('sysInstances').innerHTML = table([
    { label: 'Process', cell: r => '<span class="mono">' + esc(r.processDefinitionKey) + '</span>' },
    { label: 'Business Key', cell: r => '<span class="mono">' + esc(r.businessKey) + '</span>' },
    { label: 'Active Activities', cell: r => '<span class="mono">' + esc((r.activeActivityIds || []).join(', ')) + '</span>' },
    { label: 'Incidents', cell: r => esc(r.incidents) }
  ], instances, 'No process instance is currently active.');
}

// ---------------------------------------------------------------------------
// View wiring
// ---------------------------------------------------------------------------

const RENDERERS = { overview: renderOverview, live: renderLive, comm: renderComm, runtime: renderRuntime, system: renderSystem };

function showView(name) {
  state.view = name;
  document.querySelectorAll('.view').forEach(v => { v.hidden = v.id !== 'view-' + name; });
  document.querySelectorAll('.tab').forEach(b => b.classList.toggle('active', b.dataset.view === name));
  tick();
}

async function tick() {
  try {
    await refreshHeader();
    await refreshPairsAndDetail();
    await RENDERERS[state.view]();
  } catch (e) {
    $('dotPlatform').className = 'dot bad';
    $('stPlatform').textContent = 'No response from platform';
    console.error(e);
  }
}

document.querySelectorAll('.tab').forEach(b => b.addEventListener('click', () => showView(b.dataset.view)));
$('btnOpenLive').addEventListener('click', () => showView('live'));
$('btnStartRun').addEventListener('click', startNewRun);
$('btnStartRun2').addEventListener('click', startNewRun);
$('btnCompleteProcess').addEventListener('click', () => controlExecution('complete'));
$('btnNextStep').addEventListener('click', () => controlExecution('next'));
$('pairSelect').addEventListener('change', (e) => { state.userKey = e.target.value || null; tick(); });
document.querySelectorAll('.chip').forEach(c => c.addEventListener('click', () => {
  document.querySelectorAll('.chip').forEach(x => x.classList.remove('active'));
  c.classList.add('active');
  state.runtimeKindFilter = c.dataset.kind || '';
  tick();
}));

probePairStart();
tick();
setInterval(tick, REFRESH_MS);
