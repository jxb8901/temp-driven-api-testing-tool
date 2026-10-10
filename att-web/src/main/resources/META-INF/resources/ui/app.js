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
  let formPackage = '';
  let activeJob = '';
  let source = null;
  let lastEventId = 0;
  let artifactRequestSequence = 0;
  let homeRequestSequence = 0;
  let resourcePageSequence = 0;
  let resourceDetailSequence = 0;
  let resourceLoadPending = false;
  let resourceCursor = null;
  let resourceItems = [];
  let activeResource = null;
  let cancelRequested = false;
  let versionPromise = null;
  let navigation = 0;
  const submitting = new Set();

  async function request(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (options.body) headers.set('Content-Type', 'application/json');
    const response = await fetch(api + path, { ...options, headers, credentials: 'same-origin' });
    const type = response.headers.get('content-type') || '';
    const data = type.includes('json') ? await response.json() : null;
    if (!response.ok) {
      const error = data && data.error;
      if (response.status === 401) throw new Error('Authentication is required or access was denied by Tomcat.');
      if (response.status === 403 && !type.includes('json')) throw new Error('Authentication is required or access was denied by Tomcat.');
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
    if (selectedPackage && selectedPackage !== formPackage) {
      byId('submit-form').reset();
      formPackage = selectedPackage;
    }
    activeJob = parts[0] === 'jobs' ? (parts[1] || '') : '';
    const submitButton = byId('submit-form').querySelector('button[type="submit"]');
    if (submitButton) submitButton.disabled = selectedPackage !== '' && submitting.has(selectedPackage);
    ensureCompatible().then(() => {
      if (generation !== navigation) return;
      if (selectedPackage) showPackage(selectedPackage, generation);
      else if (activeJob) showJob(activeJob, generation);
      else loadHome(generation);
    }).catch(error => { if (generation === navigation) { text(byId('connection'), 'Unavailable'); message(error.message); } });
  }
  async function loadHome(generation = navigation) {
    const requestSequence = ++homeRequestSequence;
    try {
      const [packages, jobs] = await Promise.all([request('packages'), request('jobs')]);
      if (requestSequence !== homeRequestSequence || generation !== navigation) return;
      byId('connection').textContent = 'Connected';
      const packageList = byId('packages'); packageList.replaceChildren();
      listItems(packages).forEach(item => { const li = document.createElement('li'); li.append(link(`#/packages/${encodeURIComponent(item.packageId)}`, item.packageId)); packageList.append(li); });
      const tbody = byId('jobs'); tbody.replaceChildren();
      listItems(jobs).forEach(job => {
        const row = document.createElement('tr');
        [job.jobId, job.packageId, job.command, job.status, job.createdAt].forEach((value, i) => { const cell = document.createElement('td'); if (i === 0) cell.append(link(`#/jobs/${encodeURIComponent(job.jobId)}`, value)); else text(cell, value); row.append(cell); });
        tbody.append(row);
      });
    } catch (error) {
      if (requestSequence === homeRequestSequence && generation === navigation) {
        byId('connection').textContent = 'Unavailable'; message(error.message);
      }
    }
  }
  async function showPackage(id, generation) {
    try {
      const item = await request(`packages/${encodeURIComponent(id)}`);
      if (generation !== navigation) return;
      text(byId('package-title'), item.packageId || id);
      byId('submit-form').elements.packageId?.remove();
      await loadResources(id, true, generation);
    } catch (error) { if (generation === navigation) message(error.message); }
  }
  function resourcePath(packageId, resource) {
    return `packages/${encodeURIComponent(packageId)}/resources/${encodeURIComponent(resource.type)}/${encodeURIComponent(resource.resourceId)}`;
  }
  function renderResourceList() {
    const list = byId('resource-list'); list.replaceChildren();
    resourceItems.forEach(item => {
      const li = document.createElement('li');
      const button = el('button', `${item.name || item.logicalId || item.resourceId} · ${item.type}${item.state === 'invalid' ? ' · invalid' : ''}`);
      button.type = 'button';
      button.addEventListener('click', () => selectResource(item));
      li.append(button); list.append(li);
    });
    if (!resourceItems.length) list.append(el('li', 'No resources match this search.'));
  }
  async function loadResources(packageId, reset, generation = navigation) {
    if (reset) {
      resourcePageSequence++; resourceCursor = null; resourceItems = []; activeResource = null;
      resourceDetailSequence++; resourceLoadPending = false;
      byId('resource-list').replaceChildren(); byId('resource-detail').hidden = true;
      byId('load-more-resources').hidden = true; byId('load-more-resources').disabled = false;
      text(byId('resource-count'), 'Loading package resources…');
    }
    if (resourceLoadPending) return;
    resourceLoadPending = true;
    byId('load-more-resources').disabled = true;
    const sequence = resourcePageSequence;
    const type = String(byId('resource-type').value || '');
    const query = String(byId('resource-search').value || '').trim();
    const params = ['limit=50'];
    if (type) params.push(`type=${encodeURIComponent(type)}`);
    if (query) params.push(`query=${encodeURIComponent(query)}`);
    if (!reset && resourceCursor) params.push(`cursor=${encodeURIComponent(resourceCursor)}`);
    try {
      const data = await request(`packages/${encodeURIComponent(packageId)}/resources?${params.join('&')}`);
      if (sequence !== resourcePageSequence || generation !== navigation || selectedPackage !== packageId) return;
      const items = listItems(data);
      resourceItems = reset ? items : resourceItems.concat(items);
      resourceCursor = data.nextCursor || null;
      renderResourceList();
      const shown = resourceItems.length; const total = Number(data.total || 0);
      const diagnostics = Array.isArray(data.diagnostics) ? data.diagnostics : [];
      const diagnosticText = diagnostics.length ? ` ${diagnostics.map(item => item.summary || item.code || 'Inspection warning').join(' ')}` : '';
      text(byId('resource-count'), `Showing ${shown} of ${total} resources.${diagnosticText}`);
      byId('load-more-resources').hidden = !resourceCursor;
    } catch (error) {
      if (sequence === resourcePageSequence && generation === navigation && selectedPackage === packageId) {
        text(byId('resource-count'), 'Package resources are unavailable.'); message(error.message);
      }
    } finally {
      if (sequence === resourcePageSequence) {
        resourceLoadPending = false;
        byId('load-more-resources').disabled = false;
      }
    }
  }
  function renderResourceLinks(targetId, values) {
    const list = byId(targetId); list.replaceChildren();
    (Array.isArray(values) ? values : []).forEach(value => {
      const li = document.createElement('li');
      if (value.resourceId && value.type) {
        const button = el('button', `${value.name || value.logicalId || value.resourceId} · ${value.type}`);
        button.type = 'button'; button.addEventListener('click', () => selectResource(value)); li.append(button);
      } else text(li, `${value.logicalId || 'Unknown resource'} · ${value.resolution || 'unresolved'}`);
      list.append(li);
    });
    if (!list.children.length) list.append(el('li', 'None'));
  }
  async function selectResource(item, generation = navigation) {
    if (!selectedPackage || !item || !item.resourceId || !item.type) return;
    const selection = ++resourceDetailSequence;
    try {
      const data = await request(resourcePath(selectedPackage, item));
      if (selection !== resourceDetailSequence || generation !== navigation || !selectedPackage) return;
      activeResource = data.resource || item;
      text(byId('resource-title'), `${activeResource.name || activeResource.logicalId} · ${activeResource.type}`);
      text(byId('resource-description'), activeResource.description || activeResource.state || '');
      const invalid = activeResource.state === 'invalid';
      const diagnostic = Array.isArray(activeResource.diagnostics) ? activeResource.diagnostics[0] : null;
      text(byId('resource-state'), invalid
        ? `Invalid resource${diagnostic && diagnostic.summary ? `: ${diagnostic.summary}` : ''}`
        : 'Resource is ready.');
      const metadata = {
        logicalId: activeResource.logicalId,
        tags: activeResource.tags || [],
        provenance: activeResource.provenance || {}
      };
      text(byId('resource-metadata'), JSON.stringify(safeData(metadata), null, 2));
      text(byId('resource-definition'), JSON.stringify(safeData(data.definition || {}), null, 2));
      renderResourceLinks('resource-references', activeResource.references);
      renderResourceLinks('resource-referenced-by', activeResource.referencedBy);
      const sourceAvailable = activeResource.sourceAvailable === true;
      text(byId('resource-source-status'), sourceAvailable ? 'Safe source is available.' : 'Source is not available for this resource.');
      byId('show-resource-source').hidden = !sourceAvailable;
      byId('run-resource-case').hidden = activeResource.type !== 'case' || activeResource.state === 'invalid';
      byId('resource-source-heading').hidden = true; byId('resource-source').hidden = true; text(byId('resource-source'), '');
      byId('resource-detail').hidden = false;
    } catch (error) { if (selection === resourceDetailSequence && generation === navigation) message(error.message); }
  }
  async function showResourceSource() {
    const item = activeResource, packageId = selectedPackage, generation = navigation;
    if (!item || !packageId || !item.sourceAvailable) return;
    try {
      const data = await request(`${resourcePath(packageId, item)}/source`);
      if (generation !== navigation || activeResource !== item) return;
      text(byId('resource-source-heading'), 'Safe source projection');
      const available = data.available === true;
      text(byId('resource-source-status'), available ? 'Safe source is available.' : `Source unavailable (${data.reason || 'policy'}).`);
      text(byId('resource-source'), available ? data.text : `Source unavailable (${data.reason || 'policy'}).`);
      byId('resource-source-heading').hidden = false; byId('resource-source').hidden = false;
    } catch (error) { if (generation === navigation) message(error.message); }
  }
  async function runResourceCase() {
    const item = activeResource, packageId = selectedPackage, generation = navigation;
    if (!item || item.type !== 'case' || !item.logicalId || !item.provenance || !item.provenance.suite || submitting.has(packageId)) return;
    submitting.add(packageId); byId('run-resource-case').disabled = true;
    try {
      const body = { packageId, suites: [item.provenance.suite], caseIds: [item.logicalId] };
      const accepted = await request('jobs/run', { method: 'POST', body: JSON.stringify(body) });
      if (generation === navigation && selectedPackage === packageId) location.hash = `#/jobs/${encodeURIComponent(accepted.jobId)}`;
    } catch (error) { if (generation === navigation && selectedPackage === packageId) message(error.message); }
    finally { submitting.delete(packageId); if (selectedPackage === packageId) byId('run-resource-case').disabled = false; }
  }
  function commaList(value) { const items = value.split(',').map(part => part.trim()).filter(Boolean); return items.length ? items : undefined; }
  byId('submit-form').addEventListener('submit', async event => {
    event.preventDefault();
    const generation = navigation; const packageId = selectedPackage;
    if (submitting.has(packageId)) return;
    const form = event.currentTarget;
    const values = new FormData(form); const command = values.get('command');
    if (command === 'debug') {
      const type = String(values.get('targetType') || '').trim(); const id = String(values.get('targetId') || '').trim();
      if (!type || !id) { message('Debug requires a target type and target ID.'); return; }
    }
    submitting.add(packageId);
    const submitButton = form.querySelector('button[type="submit"]');
    if (submitButton) submitButton.disabled = true;
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
    const overrides = String(values.get('overrides') || '').split(/\r?\n/).map(s => s.trim()).filter(Boolean);
    if (overrides.length) body.overrides = overrides;
    try {
      const accepted = await request(`jobs/${command}`, { method: 'POST', body: JSON.stringify(body) });
      if (generation === navigation && selectedPackage === packageId)
        location.hash = `#/jobs/${encodeURIComponent(accepted.jobId)}`;
    } catch (error) {
      if (generation === navigation && selectedPackage === packageId) message(error.message);
    } finally {
      submitting.delete(packageId);
      if (submitButton && selectedPackage === packageId) submitButton.disabled = submitting.has(packageId);
    }
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
    const loaded = await refreshJob(id, generation);
    if (!loaded || generation !== navigation || activeJob !== id) return;
    source = new EventSource(api + `jobs/${encodeURIComponent(id)}/events`);
    source.onopen = () => { if (generation === navigation && activeJob === id) text(byId('stream-state'), 'Live updates connected.'); };
    source.onerror = () => { if (generation === navigation && activeJob === id) text(byId('stream-state'), 'Connection interrupted. The browser will reconnect automatically.'); };
    ['status','progress','log','diagnostic','result'].forEach(type => source.addEventListener(type, event => {
      if (generation === navigation && activeJob === id) appendEvent(type, event, id, generation);
    }));
    loadArtifacts(id, generation);
  }
  async function refreshJob(id, generation = navigation) {
    let loaded = false;
    try {
      const job = await request(`jobs/${encodeURIComponent(id)}`);
      if (generation !== navigation || activeJob !== id) return false;
      loaded = true;
      const summary = byId('job-summary'); summary.replaceChildren();
      [['Package',job.packageId],['Command',job.command],['Principal',job.principal],['Status',job.status],['Created',job.createdAt],['Started',job.startedAt],['Finished',job.finishedAt]].forEach(([label,value]) => {
        if (value) summary.append(el('dt',label),el('dd',value));
      });
      byId('cancel-job').disabled = terminal.has(job.status) || cancelRequested;
      byId('cancel-area').hidden = terminal.has(job.status);
      if (terminal.has(job.status)) {
        request(`jobs/${encodeURIComponent(id)}/result`).then(result => {
          if (generation === navigation && activeJob === id)
            text(byId('result'), JSON.stringify(safeData({ status: job.status, result: result.result, diagnostic: result.diagnostic }), null, 2));
        }).catch(error => {
          if (generation === navigation && activeJob === id) message(error.message);
        });
      }
      return true;
    } catch (error) {
      if (generation === navigation && activeJob === id) message(error.message);
      return loaded;
    }
  }
  async function loadArtifacts(id, generation = navigation) {
    const requestSequence = ++artifactRequestSequence;
    try {
      const data = await request(`jobs/${encodeURIComponent(id)}/artifacts`);
      if (requestSequence !== artifactRequestSequence || generation !== navigation || activeJob !== id) return;
      const list = byId('artifacts'); list.replaceChildren();
      listItems(data).forEach(item => {
        const li = document.createElement('li');
        const path = String(item.path);
        li.append(link(`${api}jobs/${encodeURIComponent(id)}/artifacts/${path.split('/').map(encodeURIComponent).join('/')}`, `${path} (${item.size} bytes)`));
        list.append(li);
      });
    } catch (error) {
      if (requestSequence === artifactRequestSequence && generation === navigation && activeJob === id) message(error.message);
    }
  }
  byId('cancel-job').addEventListener('click', async () => {
    const jobId = activeJob;
    const generation = navigation;
    if (!jobId || !window.confirm(`Cancel job ${jobId}?`)) return;
    cancelRequested = true; byId('cancel-job').disabled = true; text(byId('cancel-state'), 'Cancellation requested; waiting for Server confirmation.');
    try {
      await request(`jobs/${encodeURIComponent(jobId)}`, { method:'DELETE' });
      if (generation !== navigation || activeJob !== jobId) return;
      await refreshJob(jobId, generation);
    } catch (error) {
      if (generation !== navigation || activeJob !== jobId) return;
      cancelRequested = false; byId('cancel-job').disabled = false; message(error.message);
    }
  });
  byId('search-resources').addEventListener('click', () => { if (selectedPackage) loadResources(selectedPackage, true); });
  byId('refresh-resources').addEventListener('click', () => { if (selectedPackage) loadResources(selectedPackage, true); });
  byId('load-more-resources').addEventListener('click', () => { if (selectedPackage && resourceCursor) loadResources(selectedPackage, false); });
  byId('show-resource-source').addEventListener('click', showResourceSource);
  byId('run-resource-case').addEventListener('click', runResourceCase);
  byId('refresh-jobs').addEventListener('click', () => loadHome());
  window.addEventListener('hashchange', route);
  route();
})();
