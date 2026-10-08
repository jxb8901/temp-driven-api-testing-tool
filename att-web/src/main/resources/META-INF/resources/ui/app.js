(() => {
  'use strict';
  const api = new URL('../api/v1/', document.baseURI).pathname;
  const byId = id => document.getElementById(id);
  const text = (node, value) => { node.textContent = value == null ? '' : String(value); };
  const message = value => text(byId('message'), value || '');
  const terminal = new Set(['PASS', 'FAIL', 'ERROR', 'INVALID', 'CANCELLED']);
  const boundedEvents = 500;
  const boundedEventText = 200000;
  let selectedPackage = '';
  let activeJob = '';
  let source = null;
  let lastEventId = 0;
  let cancelRequested = false;
  let versionPromise = null;
  let navigation = 0;

  async function request(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (options.body) headers.set('Content-Type', 'application/json');
    const response = await fetch(api + path, { ...options, headers, credentials: 'same-origin' });
    const type = response.headers.get('content-type') || '';
    const data = type.includes('json') ? await response.json() : null;
    if (!response.ok) {
      const error = data && data.error;
      if (response.status === 401 || response.status === 403) throw new Error('Authentication is required or access was denied by Tomcat.');
      throw new Error(error && (error.summary || error.code) || `Server request failed (${response.status}).`);
    }
    if (!type.includes('json')) throw new Error('The Server returned an incompatible response.');
    return data;
  }
  function ensureCompatible() {
    if (!versionPromise) {
      versionPromise = request('version').then(info => {
        if (String(info.apiVersion) !== '1') throw new Error('Incompatible ATT Server API version; this Web UI requires /api/v1.');
        text(byId('connection'), 'Connected');
      }).catch(error => { versionPromise = null; throw error; });
    }
    return versionPromise;
  }
  function listItems(data) { return Array.isArray(data.items) ? data.items : []; }
  function el(tag, value) { const node = document.createElement(tag); text(node, value); return node; }
  function link(href, label) { const node = el('a', label); node.href = href; return node; }
  function safeData(value, key = '') {
    if (/^(packageRoot|outputDirectory|serverDataDir|physicalPath|absolutePath)$/i.test(key)) return '[path hidden]';
    if (typeof value === 'string') return value.replace(/(^|[\s("'=])((?:[A-Za-z]:[\\/]|\\\\[^\\/\s]+[\\/][^\\/\s]+[\\/]|\/)[^\s"'<>|]*)/g, '$1[path hidden]');
    if (Array.isArray(value)) return value.map(item => safeData(item));
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([name, child]) => [name, safeData(child, name)]));
    return value;
  }
  function closeStream() { if (source) source.close(); source = null; }
  function route() {
    const generation = ++navigation;
    const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean).map(decodeURIComponent);
    closeStream();
    byId('home').hidden = parts.length > 0;
    byId('package-view').hidden = parts[0] !== 'packages' || !parts[1];
    byId('job-view').hidden = parts[0] !== 'jobs' || !parts[1];
    message('');
    selectedPackage = parts[0] === 'packages' ? (parts[1] || '') : '';
    activeJob = parts[0] === 'jobs' ? (parts[1] || '') : '';
    ensureCompatible().then(() => {
      if (generation !== navigation) return;
      if (selectedPackage) showPackage(selectedPackage, generation);
      else if (activeJob) showJob(activeJob, generation);
      else loadHome();
    }).catch(error => { if (generation === navigation) { text(byId('connection'), 'Unavailable'); message(error.message); } });
  }
  async function loadHome() {
    try {
      const [packages, jobs] = await Promise.all([request('packages'), request('jobs')]);
      byId('connection').textContent = 'Connected';
      const packageList = byId('packages'); packageList.replaceChildren();
      listItems(packages).forEach(item => { const li = document.createElement('li'); li.append(link(`#/packages/${encodeURIComponent(item.packageId)}`, item.packageId)); packageList.append(li); });
      const tbody = byId('jobs'); tbody.replaceChildren();
      listItems(jobs).forEach(job => {
        const row = document.createElement('tr');
        [job.jobId, job.packageId, job.command, job.status, job.createdAt].forEach((value, i) => { const cell = document.createElement('td'); if (i === 0) cell.append(link(`#/jobs/${encodeURIComponent(job.jobId)}`, value)); else text(cell, value); row.append(cell); });
        tbody.append(row);
      });
    } catch (error) { byId('connection').textContent = 'Unavailable'; message(error.message); }
  }
  async function showPackage(id, generation) {
    try {
      const item = await request(`packages/${encodeURIComponent(id)}`);
      if (generation !== navigation) return;
      text(byId('package-title'), item.packageId || id);
      byId('submit-form').elements.packageId?.remove();
    } catch (error) { if (generation === navigation) message(error.message); }
  }
  function commaList(value) { const items = value.split(',').map(part => part.trim()).filter(Boolean); return items.length ? items : undefined; }
  byId('submit-form').addEventListener('submit', async event => {
    event.preventDefault();
    const form = event.currentTarget; const values = new FormData(form); const command = values.get('command');
    const body = { packageId: selectedPackage };
    ['environment','config','runId','debugId','suiteDirectory','debugInput','validationScope','scenario'].forEach(key => { const value = String(values.get(key) || '').trim(); if (value) body[key] = value; });
    ['suites','tags','excludeTags','caseIds'].forEach(key => { const value = commaList(String(values.get(key) || '')); if (value) body[key] = value; });
    if (values.has('all')) body.all = true;
    if (values.has('dryRun')) body.dryRun = true;
    if (values.has('rerunFailed')) body.rerunFailed = true;
    if (values.has('failFast')) body.failFast = true;
    if (command === 'debug' || command === 'load') {
      const type = String(values.get('targetType') || '').trim(); const id = String(values.get('targetId') || '').trim();
      if (type && id) body.target = { type, id };
    }
    if (command === 'load') {
      const load = {}; ['users','arrivalRate','warmup','rampUp','duration','rampDown','thinkTime','maxConcurrent','overloadPolicy'].forEach(key => { const value = String(values.get(key) || '').trim(); if (value) load[key] = value; });
      if (Object.keys(load).length) body.load = load;
    }
    if (command === 'debug' && !body.target) { message('Debug requires a target type and target ID.'); return; }
    const overrides = String(values.get('overrides') || '').split(/\r?\n/).map(s => s.trim()).filter(Boolean);
    if (overrides.length) body.overrides = overrides;
    try { const accepted = await request(`jobs/${command}`, { method: 'POST', body: JSON.stringify(body) }); location.hash = `#/jobs/${encodeURIComponent(accepted.jobId)}`; }
    catch (error) { message(error.message); }
  });
  function appendEvent(type, event, jobId, generation) {
    const id = Number(event.lastEventId || 0);
    if (id && id <= lastEventId) return;
    if (id) lastEventId = id;
    const item = document.createElement('li');
    let payload = {};
    try { payload = JSON.parse(event.data || '{}'); } catch (_) { payload = { message: 'Invalid event data' }; }
    const rendered = `${type}${id ? ` #${id}` : ''}: ${JSON.stringify(safeData(payload))}`.slice(0, 4000);
    text(item, rendered); item.dataset.length = String(rendered.length);
    const list = byId('events'); list.append(item);
    let size = Array.from(list.children).reduce((sum, node) => sum + Number(node.dataset.length || 0), 0);
    while (list.children.length > boundedEvents || size > boundedEventText) { size -= Number(list.firstElementChild.dataset.length || 0); list.firstElementChild.remove(); }
    if (type === 'progress') text(byId('progress'), JSON.stringify(safeData(payload), null, 2));
    if (type === 'status' && terminal.has(payload.status)) { refreshJob(jobId, generation); loadArtifacts(jobId, generation); }
    if (type === 'result') {
      closeStream();
      text(byId('stream-state'), 'Job completed; event stream closed.');
      refreshJob(jobId, generation);
      loadArtifacts(jobId, generation);
    }
  }
  async function showJob(id, generation) {
    text(byId('job-title'), `Job ${id}`); text(byId('stream-state'), 'Loading job…');
    byId('events').replaceChildren(); byId('artifacts').replaceChildren();
    text(byId('progress'), ''); text(byId('result'), '');
    lastEventId = 0; cancelRequested = false; text(byId('cancel-state'), '');
    await refreshJob(id, generation);
    if (generation !== navigation || activeJob !== id) return;
    await loadArtifacts(id, generation);
    if (generation !== navigation || activeJob !== id) return;
    source = new EventSource(api + `jobs/${encodeURIComponent(id)}/events`);
    source.onopen = () => { if (generation === navigation) text(byId('stream-state'), 'Live updates connected.'); };
    source.onerror = () => { if (generation === navigation) text(byId('stream-state'), 'Connection interrupted. The browser will reconnect automatically.'); };
    ['status','progress','log','diagnostic','result'].forEach(type => source.addEventListener(type, event => {
      if (generation === navigation && activeJob === id) appendEvent(type, event, id, generation);
    }));
  }
  async function refreshJob(id, generation = navigation) {
    try {
      const job = await request(`jobs/${encodeURIComponent(id)}`);
      if (generation !== navigation || activeJob !== id) return;
      const summary = byId('job-summary'); summary.replaceChildren();
      [['Package',job.packageId],['Command',job.command],['Principal',job.principal],['Status',job.status],['Created',job.createdAt],['Started',job.startedAt],['Finished',job.finishedAt]].forEach(([label,value]) => {
        if (value) summary.append(el('dt',label),el('dd',value));
      });
      byId('cancel-job').disabled = terminal.has(job.status) || cancelRequested;
      byId('cancel-area').hidden = terminal.has(job.status);
      if (terminal.has(job.status)) {
        const result = await request(`jobs/${encodeURIComponent(id)}/result`);
        if (generation === navigation && activeJob === id)
          text(byId('result'), JSON.stringify(safeData({ status: job.status, result: result.result, diagnostic: result.diagnostic }), null, 2));
      }
    } catch (error) { if (generation === navigation && activeJob === id) message(error.message); }
  }
  async function loadArtifacts(id, generation = navigation) {
    try {
      const data = await request(`jobs/${encodeURIComponent(id)}/artifacts`);
      if (generation !== navigation || activeJob !== id) return;
      const list = byId('artifacts'); list.replaceChildren();
      listItems(data).forEach(item => {
        const li = document.createElement('li');
        const path = String(item.path);
        li.append(link(`${api}jobs/${encodeURIComponent(id)}/artifacts/${path.split('/').map(encodeURIComponent).join('/')}`, `${path} (${item.size} bytes)`));
        list.append(li);
      });
    } catch (error) { if (generation === navigation && activeJob === id) message(error.message); }
  }
  byId('cancel-job').addEventListener('click', async () => {
    if (!activeJob || !window.confirm(`Cancel job ${activeJob}?`)) return;
    cancelRequested = true; byId('cancel-job').disabled = true; text(byId('cancel-state'), 'Cancellation requested; waiting for Server confirmation.');
    try { await request(`jobs/${encodeURIComponent(activeJob)}`, { method:'DELETE' }); await refreshJob(activeJob); }
    catch (error) { cancelRequested = false; byId('cancel-job').disabled = false; message(error.message); }
  });
  byId('refresh-jobs').addEventListener('click', loadHome);
  window.addEventListener('hashchange', route);
  route();
})();
