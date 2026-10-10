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
  let configurationRequestSequence = 0;
  let resourceLoadPending = false;
  let resourceCursor = null;
  let resourceItems = [];
  let activeResource = null;
  let debugDefaults = null;
  let debugTarget = null;
  let debugFormSequence = 0;
  let debugDraft = null;
  let debugDraftFingerprint = '';
  let debugPreviewPending = null;
  let debugPreviewPendingFingerprint = '';
  let quickLoadDefaults = null;
  let quickLoadTarget = null;
  let quickLoadPolicyDefaults = null;
  let quickLoadFormSequence = 0;
  let quickLoadDraft = null;
  let quickLoadDraftFingerprint = '';
  let quickLoadPreviewPending = null;
  let quickLoadPreviewPendingFingerprint = '';
  let advancedLoadSequence = 0;
  let advancedLoadPackage = '';
  let advancedLoadModel = '';
  let advancedLoadPolicy = null;
  let advancedLoadWorkloadSequence = 0;
  let advancedLoadMixSequence = 0;
  let advancedLoadDraft = null;
  let advancedLoadDraftFingerprint = '';
  let advancedLoadPreviewPending = null;
  let advancedLoadPreviewPendingFingerprint = '';
  let advancedTargetSuggestionSequence = 0;
  const advancedTargetCatalogs = new Map();
  let advancedSubmitting = false;
  let cancelRequested = false;
  let versionPromise = null;
  let inlineLoadEnabled = true;
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
      const failure = new Error(error && (error.summary || error.code) || `Server request failed (${response.status}).`);
      failure.status = response.status;
      failure.code = error && error.code;
      failure.diagnostics = Array.isArray(data && data.diagnostics) ? data.diagnostics : [];
      throw failure;
    }
    if (!type.includes('json')) throw new Error('The Server returned an incompatible response.');
    return data;
  }
  function ensureCompatible() {
    if (!versionPromise) {
      versionPromise = request('version').then(info => {
        if (String(info.apiVersion) !== '1') throw new Error('Incompatible ATT Server API version; this Web UI requires /api/v1.');
        inlineLoadEnabled = info.inlineLoadEnabled !== false;
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
    byId('advanced-load').hidden = parts[0] !== 'advanced-load' || !parts[1];
    byId('job-view').hidden = parts[0] !== 'jobs' || !parts[1];
    message('');
    selectedPackage = (parts[0] === 'packages' || parts[0] === 'advanced-load') ? (parts[1] || '') : '';
    if (selectedPackage && selectedPackage !== formPackage) {
      byId('submit-form').reset();
      formPackage = selectedPackage;
    }
    activeJob = parts[0] === 'jobs' ? (parts[1] || '') : '';
    const submitButton = byId('submit-form').querySelector('button[type="submit"]');
    if (submitButton) submitButton.disabled = selectedPackage !== '' && submitting.has(selectedPackage);
    ensureCompatible().then(() => {
      if (generation !== navigation) return;
      if (parts[0] === 'packages' && selectedPackage) showPackage(selectedPackage, generation);
      else if (parts[0] === 'advanced-load' && selectedPackage) showAdvancedLoadPackage(selectedPackage, generation);
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
      listItems(packages).forEach(item => {
        const li = document.createElement('li');
        li.append(link(`#/packages/${encodeURIComponent(item.packageId)}`, item.packageId));
        const advanced = el('button', inlineLoadEnabled ? 'Advanced Load' : 'Advanced Load (disabled)'); advanced.type = 'button';
        advanced.disabled = !inlineLoadEnabled;
        if (inlineLoadEnabled) advanced.addEventListener('click', () => { location.hash = `#/advanced-load/${encodeURIComponent(item.packageId)}`; });
        else advanced.title = 'The Server administrator has disabled browser-created Load drafts.';
        li.append(document.createTextNode(' '), advanced); packageList.append(li);
      });
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
  async function showAdvancedLoadPackage(packageId, generation) {
    if (advancedLoadPackage !== packageId) {
      advancedLoadPackage = packageId; advancedLoadSequence++; advancedLoadModel = ''; advancedLoadPolicy = null;
      advancedLoadWorkloadSequence = 0; advancedLoadMixSequence = 0; advancedTargetSuggestionSequence++; advancedTargetCatalogs.clear();
      byId('advanced-load-model').value = ''; byId('advanced-load-environment').value = '';
      byId('advanced-load-editor').hidden = true; byId('advanced-load-workloads').replaceChildren();
      text(byId('advanced-load-policy-status'), 'Choose the scenario model before configuring workloads.');
      text(byId('advanced-load-status'), ''); text(byId('advanced-load-preview'), ''); clearAdvancedLoadDraft();
    }
    if (!inlineLoadEnabled) {
      advancedLoadModel = ''; advancedLoadPolicy = null;
      byId('advanced-load-model').disabled = true; byId('advanced-load-editor').hidden = true;
      text(byId('advanced-load-package'), packageId);
      text(byId('advanced-load-policy-status'), 'Browser-created Quick and Advanced Load are disabled by Server configuration.');
      return;
    }
    byId('advanced-load-model').disabled = false;
    try {
      const item = await request(`packages/${encodeURIComponent(packageId)}`);
      if (generation !== navigation || selectedPackage !== packageId) return;
      text(byId('advanced-load-package'), item.packageId || packageId);
    } catch (error) { if (generation === navigation) message(error.message); }
  }
  function clearAdvancedLoadDraft() {
    advancedLoadDraft = null; advancedLoadDraftFingerprint = '';
    advancedLoadPreviewPending = null; advancedLoadPreviewPendingFingerprint = '';
    byId('copy-advanced-load').disabled = true; byId('export-advanced-load').disabled = true;
  }
  function markAdvancedLoadDirty() {
    clearAdvancedLoadDraft(); text(byId('advanced-load-preview'), '');
    if (advancedLoadPolicy) text(byId('advanced-load-status'), 'Input changed. Validate the full scenario before starting Load.');
  }
  async function loadAdvancedLoadPolicy() {
    const packageId = selectedPackage, generation = navigation;
    if (!inlineLoadEnabled) {
      text(byId('advanced-load-policy-status'), 'Browser-created Quick and Advanced Load are disabled by Server configuration.'); return;
    }
    const model = String(byId('advanced-load-model').value || '');
    const sequence = ++advancedLoadSequence;
    advancedLoadModel = model; advancedLoadPolicy = null; clearAdvancedLoadDraft();
    byId('advanced-load-editor').hidden = true; byId('advanced-load-workloads').replaceChildren();
    text(byId('advanced-load-preview'), ''); text(byId('advanced-load-policy-status'), ''); text(byId('advanced-load-status'), '');
    if (!packageId || !['virtualUsers','arrivalRate'].includes(model)) {
      text(byId('advanced-load-policy-status'), 'Choose the scenario model before configuring workloads.'); return;
    }
    text(byId('advanced-load-policy-status'), 'Loading safe model policy defaults…');
    try {
      const params = [`model=${encodeURIComponent(model)}`];
      const environment = String(byId('advanced-load-environment').value || '').trim();
      if (environment) params.push(`environment=${encodeURIComponent(environment)}`);
      const data = await request(`packages/${encodeURIComponent(packageId)}/load-policy?${params.join('&')}`);
      if (sequence !== advancedLoadSequence || generation !== navigation || selectedPackage !== packageId) return;
      if (!data.policy || typeof data.policy !== 'object' || Array.isArray(data.policy)) throw new Error('The Server returned an invalid Load policy.');
      advancedLoadPolicy = JSON.parse(JSON.stringify(data.policy));
      setAdvancedLoadControls(advancedLoadPolicy, model);
      byId('advanced-load-editor').hidden = false;
      addAdvancedWorkload();
      text(byId('advanced-load-policy-status'), data.redacted
        ? 'Safe policy defaults loaded. Sensitive values are hidden in the form and preview.'
        : 'Safe policy defaults loaded from the package or the bundled low-intensity policy.');
    } catch (error) {
      if (sequence === advancedLoadSequence && generation === navigation && selectedPackage === packageId) {
        text(byId('advanced-load-policy-status'), `Load policy defaults are unavailable. ${debugValidationMessage(error)}`); message(error.message);
      }
    }
  }
  function setAdvancedLoadControls(policy, model) {
    const load = policy.load || {};
    byId('advanced-load-warmup').value = load.warmup == null ? '' : String(load.warmup);
    byId('advanced-load-ramp-up').value = load.rampUp == null ? '' : String(load.rampUp);
    byId('advanced-load-duration').value = load.duration == null ? '' : String(load.duration);
    byId('advanced-load-ramp-down').value = load.rampDown == null ? '' : String(load.rampDown);
    byId('advanced-load-seed').value = policy.seed == null ? '' : String(policy.seed);
    byId('advanced-load-execution').value = JSON.stringify(policy.execution || {}, null, 2);
    byId('advanced-load-testdata').value = JSON.stringify(policy.testdata || [], null, 2);
    byId('advanced-load-thresholds').value = JSON.stringify(policy.thresholds || {}, null, 2);
    byId('advanced-load-evidence').value = JSON.stringify(policy.evidence || {}, null, 2);
    advancedLoadModel = model;
  }
  function advancedField(root, name) { return root.querySelector(`[data-field="${name}"]`); }
  function syncAdvancedWorkload(article) {
    const model = advancedLoadModel;
    const isUsers = model === 'virtualUsers';
    article.querySelector('[data-role="virtual-users-fields"]').hidden = !isUsers;
    article.querySelector('[data-role="arrival-rate-fields"]').hidden = isUsers;
    const structure = advancedField(article, 'structure');
    const mixOption = Array.from(structure.options).find(option => option.value === 'mix');
    if (mixOption) mixOption.disabled = !isUsers;
    if (!isUsers) structure.value = 'single';
    const mixed = isUsers && structure.value === 'mix';
    article.querySelector('[data-role="single-target"]').hidden = mixed;
    article.querySelector('[data-role="mix-targets"]').hidden = !mixed;
    article.querySelector('[data-role="mix-workload-business"]').hidden = !mixed;
  }
  function addAdvancedWorkload(copySource = null) {
    if (!advancedLoadPolicy || !['virtualUsers','arrivalRate'].includes(advancedLoadModel)) return null;
    const template = byId('advanced-workload-template');
    const article = copySource ? copySource.cloneNode(true) : template.content.firstElementChild.cloneNode(true);
    advancedLoadWorkloadSequence++;
    advancedField(article, 'id').value = `workload-${advancedLoadWorkloadSequence}`;
    if (copySource) {
      article.querySelectorAll('.advanced-mix-entry').forEach(entry => {
        advancedLoadMixSequence++;
        advancedField(entry, 'id').value = `mix-${advancedLoadMixSequence}`;
      });
    } else {
      const policyLoad = advancedLoadPolicy.load || {};
      advancedField(article, 'users').value = policyLoad.users == null ? '1' : String(policyLoad.users);
      advancedField(article, 'arrivalRate').value = policyLoad.arrivalRate == null ? '1/s' : String(policyLoad.arrivalRate);
      advancedField(article, 'maxConcurrent').value = policyLoad.maxConcurrent == null ? '1' : String(policyLoad.maxConcurrent);
      advancedField(article, 'thinkTime').value = (advancedLoadPolicy.execution || {}).thinkTime == null ? '' : String((advancedLoadPolicy.execution || {}).thinkTime);
      advancedField(article, 'testdata').value = '{}'; advancedField(article, 'thresholds').value = '{}';
    }
    syncAdvancedWorkload(article);
    byId('advanced-load-workloads').append(article);
    article.querySelectorAll('[data-field="targetType"]').forEach(field => updateAdvancedTargetSuggestions(String(field.value || '')));
    markAdvancedLoadDirty();
    return article;
  }
  function addAdvancedMixEntry(workload) {
    const entry = byId('advanced-mix-entry-template').content.firstElementChild.cloneNode(true);
    advancedLoadMixSequence++;
    advancedField(entry, 'id').value = `mix-${advancedLoadMixSequence}`;
    workload.querySelector('[data-role="mix-entries"]').append(entry);
    updateAdvancedTargetSuggestions('template');
    markAdvancedLoadDirty();
    return entry;
  }
  function moveAdvancedElement(node, direction, selector) {
    const siblings = Array.from(node.parentElement.querySelectorAll(selector));
    const index = siblings.indexOf(node), next = siblings[index + direction];
    if (!next) return;
    if (direction < 0) node.parentElement.insertBefore(node, next);
    else node.parentElement.insertBefore(next, node);
    markAdvancedLoadDirty();
  }
  async function updateAdvancedTargetSuggestions(type) {
    if (!selectedPackage || !['template','flow','tool'].includes(type)) return;
    const packageId = selectedPackage;
    const sequence = ++advancedTargetSuggestionSequence;
    try {
      let values = advancedTargetCatalogs.get(`${packageId}:${type}`);
      if (!values) {
        const data = await request(`packages/${encodeURIComponent(packageId)}/resources?type=${encodeURIComponent(type)}&limit=100`);
        values = listItems(data).filter(item => item.state === 'ready' && item.logicalId);
        advancedTargetCatalogs.set(`${packageId}:${type}`, values);
      }
      if (sequence !== advancedTargetSuggestionSequence || selectedPackage !== packageId) return;
      const list = byId('advanced-target-options'); list.replaceChildren();
      values.forEach(item => { const option = document.createElement('option'); option.value = item.logicalId; list.append(option); });
    } catch (_) { /* A typed logical target ID remains available when suggestions cannot load. */ }
  }
  async function findAdvancedTargetResource(type, logicalId) {
    const key = `${selectedPackage}:${type}`;
    let values = advancedTargetCatalogs.get(key);
    const exact = items => items.find(item => item.logicalId === logicalId && item.state === 'ready');
    let found = values && exact(values);
    if (found) return found;
    const params = [`type=${encodeURIComponent(type)}`, 'limit=100', `query=${encodeURIComponent(logicalId)}`];
    const data = await request(`packages/${encodeURIComponent(selectedPackage)}/resources?${params.join('&')}`);
    values = listItems(data); found = exact(values);
    if (found) return found;
    throw new Error(`No ready ${type} target matches logical ID ${logicalId}.`);
  }
  function sidecarBusiness(type, input) {
    const projected = input && typeof input === 'object' && !Array.isArray(input) ? input : {};
    return type === 'tool'
      ? { arguments: projected.arguments && typeof projected.arguments === 'object' && !Array.isArray(projected.arguments) ? projected.arguments : {} }
      : {
          inputs: projected.inputs && typeof projected.inputs === 'object' && !Array.isArray(projected.inputs) ? projected.inputs : {},
          vars: projected.vars && typeof projected.vars === 'object' && !Array.isArray(projected.vars) ? projected.vars : {}
        };
  }
  async function loadAdvancedTargetDefaults(button) {
    const container = button.closest('.advanced-workload, .advanced-mix-entry');
    const packageId = selectedPackage, generation = navigation;
    const type = String(advancedField(container, 'targetType').value || '');
    const logicalId = String(advancedField(container, 'targetId').value || '').trim();
    const model = advancedLoadModel;
    const environment = String(byId('advanced-load-environment').value || '').trim();
    if (!logicalId) throw new Error('Enter a target logical ID first.');
    const resource = await findAdvancedTargetResource(type, logicalId);
    if (generation !== navigation || selectedPackage !== packageId || advancedLoadModel !== model
        || String(byId('advanced-load-environment').value || '').trim() !== environment
        || String(advancedField(container, 'targetType').value || '') !== type
        || String(advancedField(container, 'targetId').value || '').trim() !== logicalId) return;
    const params = [`model=${encodeURIComponent(model)}`]; if (environment) params.push(`environment=${encodeURIComponent(environment)}`);
    const data = await request(`${resourcePath(packageId, resource)}/quick-load-form?${params.join('&')}`);
    if (generation !== navigation || selectedPackage !== packageId || advancedLoadModel !== model
        || String(byId('advanced-load-environment').value || '').trim() !== environment
        || String(advancedField(container, 'targetType').value || '') !== type
        || String(advancedField(container, 'targetId').value || '').trim() !== logicalId) return;
    advancedField(container, 'targetId').value = data.target && data.target.id || logicalId;
    advancedField(container, 'business').value = JSON.stringify(sidecarBusiness(type, data.input), null, 2);
    markAdvancedLoadDirty(); text(byId('advanced-load-status'), data.redacted
      ? `Safe ${type} sidecar defaults loaded; sensitive values remain hidden.`
      : `Safe ${type} sidecar defaults loaded, or empty defaults were generated.`);
  }
  function parseAdvancedJson(container, fieldName, label, fallback) {
    const field = advancedField(container, fieldName) || byId(`advanced-load-${fieldName}`);
    const raw = String(field && field.value || '').trim();
    if (!raw) return fallback;
    let value; try { value = JSON.parse(raw); } catch (_) { throw new Error(`${label} must contain valid JSON.`); }
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label} must be a JSON object.`);
    return value;
  }
  function parseAdvancedBusiness(container, type) {
    const business = parseAdvancedJson(container, 'business', 'Target business values', {});
    const allowed = type === 'tool' ? ['arguments'] : ['inputs','vars'];
    if (Object.keys(business).some(key => !allowed.includes(key)))
      throw new Error(type === 'tool' ? 'Tool values support arguments only.' : 'Template and Flow values support inputs and vars only.');
    for (const key of allowed) if (business[key] != null && (typeof business[key] !== 'object' || Array.isArray(business[key])))
      throw new Error(`${key} must be a JSON object.`);
    return business;
  }
  function advancedTarget(container) {
    const type = String(advancedField(container, 'targetType').value || '');
    const id = String(advancedField(container, 'targetId').value || '').trim();
    if (!['template','flow','tool'].includes(type) || !id) throw new Error('Every target needs a type and logical ID.');
    const target = { type, id };
    const business = parseAdvancedBusiness(container, type);
    if (type === 'tool') { if (business.arguments && Object.keys(business.arguments).length) target.arguments = business.arguments; }
    return { target, business };
  }
  function readAdvancedWorkload(article) {
    const id = String(advancedField(article, 'id').value || '').trim();
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(id)) throw new Error('Workload IDs must start with a letter or number and contain only letters, numbers, dot, underscore, or hyphen.');
    const workload = { id, load: {} };
    if (advancedLoadModel === 'virtualUsers') {
      const users = Number(String(advancedField(article, 'users').value || '').trim());
      if (!Number.isSafeInteger(users) || users < 1) throw new Error(`Workload ${id} needs a positive Virtual users count.`);
      workload.load.users = users;
      const thinkTime = String(advancedField(article, 'thinkTime').value || '').trim();
      if (thinkTime) workload.execution = { thinkTime };
    } else {
      const arrivalRate = String(advancedField(article, 'arrivalRate').value || '').trim();
      if (!arrivalRate) throw new Error(`Workload ${id} needs an arrival rate.`);
      const maximum = Number(String(advancedField(article, 'maxConcurrent').value || '').trim());
      if (!Number.isSafeInteger(maximum) || maximum < 1) throw new Error(`Workload ${id} needs a positive maximum concurrency.`);
      Object.assign(workload.load, { arrivalRate, maxConcurrent: maximum, overloadPolicy: 'drop' });
    }
    const testdata = parseAdvancedJson(article, 'testdata', `Workload ${id} Testdata policies`, {});
    const thresholds = parseAdvancedJson(article, 'thresholds', `Workload ${id} thresholds`, {});
    if (Object.keys(testdata).length) workload.testdata = testdata;
    if (Object.keys(thresholds).length) workload.thresholds = thresholds;
    if (advancedLoadModel === 'virtualUsers' && String(advancedField(article, 'structure').value) === 'mix') {
      const defaults = parseAdvancedJson(article, 'workloadBusiness', `Workload ${id} mix defaults`, {});
      if (Object.keys(defaults).some(key => !['inputs','vars'].includes(key))) throw new Error(`Workload ${id} mix defaults support inputs and vars only.`);
      for (const key of ['inputs','vars']) if (defaults[key] != null && (typeof defaults[key] !== 'object' || Array.isArray(defaults[key]))) throw new Error(`Workload ${id} mix ${key} defaults must be a JSON object.`);
      if (defaults.inputs && Object.keys(defaults.inputs).length) workload.inputs = defaults.inputs;
      if (defaults.vars && Object.keys(defaults.vars).length) workload.vars = defaults.vars;
      const entries = Array.from(article.querySelectorAll('.advanced-mix-entry'));
      if (!entries.length) throw new Error(`Workload ${id} needs at least one mix target.`);
      workload.mix = entries.map(entry => {
        const entryId = String(advancedField(entry, 'id').value || '').trim();
        if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(entryId)) throw new Error(`Mix entry IDs in workload ${id} must start with a letter or number and contain only letters, numbers, dot, underscore, or hyphen.`);
        const weight = Number(String(advancedField(entry, 'weight').value || '').trim());
        if (!Number.isSafeInteger(weight) || weight < 1) throw new Error(`Mix entry ${entryId} needs a positive integer weight.`);
        const value = advancedTarget(entry);
        const row = { id: entryId, weight, target: value.target };
        if (value.business.inputs && Object.keys(value.business.inputs).length) row.inputs = value.business.inputs;
        if (value.business.vars && Object.keys(value.business.vars).length) row.vars = value.business.vars;
        return row;
      });
    } else {
      const value = advancedTarget(article);
      workload.target = value.target;
      if (value.business.inputs && Object.keys(value.business.inputs).length) workload.inputs = value.business.inputs;
      if (value.business.vars && Object.keys(value.business.vars).length) workload.vars = value.business.vars;
    }
    return workload;
  }
  function currentAdvancedLoadBody() {
    if (!advancedLoadPolicy || !['virtualUsers','arrivalRate'].includes(advancedLoadModel)) throw new Error('Choose a scenario model and load its policy defaults first.');
    const scenario = JSON.parse(JSON.stringify(advancedLoadPolicy));
    scenario.schemaVersion = scenario.schemaVersion || 'att-load/v1.6';
    const load = scenario.load && typeof scenario.load === 'object' ? scenario.load : {};
    for (const [id,key] of [['advanced-load-warmup','warmup'],['advanced-load-ramp-up','rampUp'],['advanced-load-duration','duration'],['advanced-load-ramp-down','rampDown']]) {
      const value = String(byId(id).value || '').trim();
      if (!value) { delete load[key]; continue; }
      load[key] = value;
    }
    if (!load.duration) throw new Error('The shared scenario duration is required.');
    scenario.load = load;
    const seedText = String(byId('advanced-load-seed').value || '').trim();
    if (seedText) {
      const seed = Number(seedText); if (!Number.isSafeInteger(seed)) throw new Error('Seed must be a safe integer.'); scenario.seed = seed;
    } else delete scenario.seed;
    scenario.execution = parseAdvancedJson(byId('advanced-load-editor'), 'execution', 'Root execution defaults', {});
    if (!Object.keys(scenario.execution).length) delete scenario.execution;
    scenario.testdata = JSON.parse(String(byId('advanced-load-testdata').value || '[]'));
    if (!Array.isArray(scenario.testdata) || scenario.testdata.some(path => typeof path !== 'string')) throw new Error('Package-relative Testdata imports must be a JSON array of paths.');
    if (!scenario.testdata.length) delete scenario.testdata;
    scenario.thresholds = parseAdvancedJson(byId('advanced-load-editor'), 'thresholds', 'Aggregate thresholds', {});
    if (!Object.keys(scenario.thresholds).length) delete scenario.thresholds;
    scenario.evidence = parseAdvancedJson(byId('advanced-load-editor'), 'evidence', 'Scenario evidence', {});
    if (!Object.keys(scenario.evidence).length) delete scenario.evidence;
    const articles = Array.from(byId('advanced-load-workloads').querySelectorAll('.advanced-workload'));
    if (!articles.length) throw new Error('Add at least one workload.');
    const workloads = articles.map(readAdvancedWorkload);
    const ids = workloads.map(workload => workload.id);
    if (new Set(ids).size !== ids.length) throw new Error('Workload IDs must be unique.');
    for (const workload of workloads) if (workload.mix) {
      const mixIds = workload.mix.map(entry => entry.id);
      if (new Set(mixIds).size !== mixIds.length) throw new Error(`Mix entry IDs must be unique within workload ${workload.id}.`);
      if (workload.mix.some(entry => entry.target.type === 'tool') && (workload.vars || workload.mix.some(entry => entry.vars)))
        throw new Error(`Workload ${workload.id} cannot combine Tool mix entries with workload or entry vars.`);
    }
    scenario.workloads = workloads;
    const body = { packageId: selectedPackage, scenario };
    const environment = String(byId('advanced-load-environment').value || '').trim();
    if (environment) body.environment = environment;
    return body;
  }
  async function previewAdvancedLoadDraft() {
    const packageId = selectedPackage, generation = navigation;
    if (!packageId) throw new Error('Choose a configured package first.');
    const body = currentAdvancedLoadBody(), fingerprint = JSON.stringify(body);
    if (advancedLoadDraft && advancedLoadDraftFingerprint === fingerprint && Date.parse(advancedLoadDraft.expiresAt || '') > Date.now() + 1000) {
      text(byId('advanced-load-preview'), advancedLoadDraft.previewYaml || JSON.stringify(advancedLoadDraft.preview || {}, null, 2));
      byId('copy-advanced-load').disabled = Boolean(advancedLoadDraft.redacted);
      byId('export-advanced-load').disabled = Boolean(advancedLoadDraft.redacted);
      text(byId('advanced-load-status'), advancedLoadDraft.redacted ? 'Validation passed. The preview hides sensitive fields; copy and export are disabled.' : 'Validation passed. Review the complete effective scenario before starting.');
      return advancedLoadDraft;
    }
    if (advancedLoadPreviewPending && advancedLoadPreviewPendingFingerprint === fingerprint) return advancedLoadPreviewPending;
    const pending = (async () => {
      text(byId('advanced-load-status'), 'Validating the full att-load/v1.6 scenario and every target…');
      const draft = await request('drafts/load', { method: 'POST', body: JSON.stringify(body) });
      if (generation !== navigation || selectedPackage !== packageId || !advancedLoadPolicy) return null;
      if (JSON.stringify(currentAdvancedLoadBody()) !== fingerprint) return null;
      advancedLoadDraft = draft; advancedLoadDraftFingerprint = fingerprint;
      text(byId('advanced-load-preview'), draft.previewYaml || JSON.stringify(draft.preview || {}, null, 2));
      byId('copy-advanced-load').disabled = Boolean(draft.redacted);
      byId('export-advanced-load').disabled = Boolean(draft.redacted);
      text(byId('advanced-load-status'), draft.redacted ? 'Validation passed. The preview hides sensitive fields; copy and export are disabled.' : 'Validation passed. Review the complete effective scenario before starting.');
      return draft;
    })();
    advancedLoadPreviewPending = pending; advancedLoadPreviewPendingFingerprint = fingerprint;
    try { return await pending; }
    finally { if (advancedLoadPreviewPending === pending) { advancedLoadPreviewPending = null; advancedLoadPreviewPendingFingerprint = ''; } }
  }
  async function runAdvancedLoadDraft() {
    const packageId = selectedPackage, generation = navigation;
    if (!packageId || submitting.has(packageId) || advancedSubmitting) return;
    advancedSubmitting = true; submitting.add(packageId); byId('start-advanced-load').disabled = true;
    try {
      const draft = await previewAdvancedLoadDraft();
      if (!draft || generation !== navigation || selectedPackage !== packageId) return;
      if (JSON.stringify(currentAdvancedLoadBody()) !== advancedLoadDraftFingerprint) return;
      const model = draft.model === 'arrivalRate' ? 'Arrival Rate' : 'Virtual Users';
      const count = Array.from(byId('advanced-load-workloads').querySelectorAll('.advanced-workload')).length;
      if (!window.confirm(`Start ${model} Load with ${count} workload${count === 1 ? '' : 's'}? Review the validated full scenario above before confirming.`)) return;
      const accepted = await request('jobs/load', { method: 'POST', body: JSON.stringify({ packageId, draftId: draft.draftId }) });
      clearAdvancedLoadDraft();
      if (generation === navigation && selectedPackage === packageId) location.hash = `#/jobs/${encodeURIComponent(accepted.jobId)}`;
    } catch (error) {
      if (generation === navigation && selectedPackage === packageId) {
        text(byId('advanced-load-status'), `Load was not submitted. ${debugValidationMessage(error)}`); message(error.message);
      }
      if (error.status === 404 || error.status === 409) clearAdvancedLoadDraft();
    } finally {
      advancedSubmitting = false; submitting.delete(packageId); if (selectedPackage === packageId) byId('start-advanced-load').disabled = false;
    }
  }
  async function copyAdvancedLoadYaml() {
    const draft = advancedLoadDraft;
    if (!draft || draft.redacted || !draft.previewYaml) return;
    try { await navigator.clipboard.writeText(draft.previewYaml); text(byId('advanced-load-status'), 'Complete validated YAML copied to the clipboard.'); }
    catch (_) { text(byId('advanced-load-status'), 'Clipboard access is unavailable in this browser context.'); }
  }
  function exportAdvancedLoadYaml() {
    const draft = advancedLoadDraft;
    if (!draft || draft.redacted || !draft.previewYaml) return;
    const url = URL.createObjectURL(new Blob([draft.previewYaml], { type: 'text/yaml;charset=utf-8' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'att-load-v1.6.yaml'; anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  async function showPackage(id, generation) {
    try {
      const item = await request(`packages/${encodeURIComponent(id)}`);
      if (generation !== navigation) return;
      text(byId('package-title'), item.packageId || id);
      byId('submit-form').elements.packageId?.remove();
      await Promise.all([loadResources(id, true, generation), loadDeclaredConfiguration(id, generation)]);
    } catch (error) { if (generation === navigation) message(error.message); }
  }
  function renderConfiguration(data, label) {
    const state = data && data.state ? data.state : 'unavailable';
    const diagnostics = Array.isArray(data && data.diagnostics) ? data.diagnostics : [];
    const detail = diagnostics.map(item => item.summary || item.code || 'Configuration warning').join(' ');
    text(byId('configuration-status'), `${label}: ${state}${detail ? `. ${detail}` : '.'}`);
    text(byId('configuration-view'), JSON.stringify(safeData(data || {}), null, 2));
  }
  function setConfigurationProfiles(data) {
    const profiles = Array.isArray(data && data.environments) ? data.environments.filter(item => item.state === 'active' && item.name) : [];
    const ids = ['configuration-environment', 'configuration-left', 'configuration-right'];
    ids.forEach(id => {
      const select = byId(id); select.replaceChildren();
      profiles.forEach(profile => {
        const option = el('option', profile.name); option.value = profile.name; select.append(option);
      });
      if (profiles.length) select.value = profiles.find(profile => profile.default)?.name || profiles[0].name;
    });
    if (profiles.length > 1) byId('configuration-right').value = profiles[1].name;
    return profiles;
  }
  async function loadDeclaredConfiguration(packageId, generation = navigation) {
    const sequence = ++configurationRequestSequence;
    text(byId('configuration-view'), '');
    text(byId('configuration-status'), 'Loading declared configuration…');
    try {
      const data = await request(`packages/${encodeURIComponent(packageId)}/configuration?view=declared`);
      if (sequence !== configurationRequestSequence || generation !== navigation || selectedPackage !== packageId) return;
      setConfigurationProfiles(data);
      renderConfiguration(data, 'Declared configuration');
    } catch (error) {
      if (sequence === configurationRequestSequence && generation === navigation && selectedPackage === packageId) {
        text(byId('configuration-status'), 'Declared configuration is unavailable.'); message(error.message);
      }
    }
  }
  async function showEffectiveConfiguration() {
    const packageId = selectedPackage, generation = navigation;
    const environment = String(byId('configuration-environment').value || '').trim();
    if (!packageId || !environment) { text(byId('configuration-status'), 'Select a declared environment first.'); return; }
    const sequence = ++configurationRequestSequence;
    text(byId('configuration-view'), '');
    text(byId('configuration-status'), `Loading effective configuration for ${environment}…`);
    try {
      const data = await request(`packages/${encodeURIComponent(packageId)}/configuration/effective?environment=${encodeURIComponent(environment)}`);
      if (sequence !== configurationRequestSequence || generation !== navigation || selectedPackage !== packageId) return;
      renderConfiguration(data, `Effective configuration for ${environment}`);
    } catch (error) {
      if (sequence === configurationRequestSequence && generation === navigation && selectedPackage === packageId) {
        text(byId('configuration-status'), `Effective configuration for ${environment} is unavailable.`); message(error.message);
      }
    }
  }
  async function compareConfiguration() {
    const packageId = selectedPackage, generation = navigation;
    const left = String(byId('configuration-left').value || '').trim();
    const right = String(byId('configuration-right').value || '').trim();
    if (!packageId || !left || !right) { text(byId('configuration-status'), 'Select two declared environments to compare.'); return; }
    const sequence = ++configurationRequestSequence;
    text(byId('configuration-view'), '');
    text(byId('configuration-status'), `Comparing ${left} with ${right}…`);
    try {
      const query = `left=${encodeURIComponent(left)}&right=${encodeURIComponent(right)}`;
      const data = await request(`packages/${encodeURIComponent(packageId)}/configuration/compare?${query}`);
      if (sequence !== configurationRequestSequence || generation !== navigation || selectedPackage !== packageId) return;
      renderConfiguration(data, `Configuration comparison: ${left} and ${right}`);
    } catch (error) {
      if (sequence === configurationRequestSequence && generation === navigation && selectedPackage === packageId) {
        text(byId('configuration-status'), 'Configuration comparison is unavailable.'); message(error.message);
      }
    }
  }
  function resourcePath(packageId, resource) {
    return `packages/${encodeURIComponent(packageId)}/resources/${encodeURIComponent(resource.type)}/${encodeURIComponent(resource.resourceId)}`;
  }
  function clearDebugForm() {
    debugFormSequence++; debugDefaults = null; debugTarget = null;
    debugDraft = null; debugDraftFingerprint = '';
    debugPreviewPending = null; debugPreviewPendingFingerprint = '';
    byId('resource-debug').hidden = true; byId('debug-form-editor').hidden = true;
    text(byId('debug-form-status'), ''); text(byId('debug-preview'), '');
  }
  function clearQuickLoadForm() {
    quickLoadFormSequence++; quickLoadDefaults = null; quickLoadTarget = null; quickLoadPolicyDefaults = null;
    quickLoadDraft = null; quickLoadDraftFingerprint = '';
    quickLoadPreviewPending = null; quickLoadPreviewPendingFingerprint = '';
    byId('resource-quick-load').hidden = true;
    byId('quick-load-form-editor').hidden = true;
    text(byId('quick-load-form-status'), ''); text(byId('quick-load-preview'), '');
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
      clearDebugForm();
      clearQuickLoadForm();
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
      byId('debug-resource').hidden = !['template','flow','tool'].includes(activeResource.type) || activeResource.state === 'invalid';
      byId('quick-load-resource').hidden = !inlineLoadEnabled || !['template','flow','tool'].includes(activeResource.type) || activeResource.state === 'invalid';
      clearDebugForm();
      clearQuickLoadForm();
      byId('resource-source-heading').hidden = true; byId('resource-source').hidden = true; text(byId('resource-source'), '');
      byId('resource-detail').hidden = false;
    } catch (error) { if (selection === resourceDetailSequence && generation === navigation) message(error.message); }
  }
  async function loadDebugForm() {
    const item = activeResource, packageId = selectedPackage, generation = navigation;
    if (!item || !packageId || !['template','flow','tool'].includes(item.type)) return;
    const sequence = ++debugFormSequence;
    debugDefaults = null; debugTarget = null;
    byId('resource-debug').hidden = false; byId('debug-form-editor').hidden = true;
    text(byId('debug-form-status'), 'Loading safe Debug defaults…'); text(byId('debug-preview'), '');
    try {
      const environment = String(byId('submit-form').elements.environment.value || '').trim();
      const suffix = environment ? `?environment=${encodeURIComponent(environment)}` : '';
      const data = await request(`${resourcePath(packageId,item)}/debug-form${suffix}`);
      if (sequence !== debugFormSequence || generation !== navigation || activeResource !== item || selectedPackage !== packageId) return;
      debugDefaults = JSON.parse(JSON.stringify(data.input || {}));
      debugTarget = data.target || { type: item.type, id: item.logicalId };
      const tool = item.type === 'tool';
      byId('debug-inputs-label').hidden = false;
      byId('debug-vars-label').hidden = tool;
      byId('debug-arguments-label').hidden = !tool;
      resetDebugForm();
      byId('debug-form-editor').hidden = false;
      text(byId('debug-form-status'), data.redacted ? 'Defaults loaded. Sensitive fields are hidden in the form and preview.' : 'Defaults loaded from the package sidecar, or generated as empty defaults.');
    } catch (error) {
      if (sequence === debugFormSequence && generation === navigation && activeResource === item) {
        text(byId('debug-form-status'), `Debug defaults are unavailable. ${debugValidationMessage(error)}`); message(error.message);
      }
    }
  }
  function resetDebugForm() {
    if (!debugDefaults) return;
    debugDraft = null; debugDraftFingerprint = '';
    byId('debug-inputs').value = JSON.stringify(debugDefaults.inputs || {}, null, 2);
    byId('debug-vars').value = JSON.stringify(debugDefaults.vars || {}, null, 2);
    byId('debug-arguments').value = JSON.stringify(debugDefaults.arguments || {}, null, 2);
    text(byId('debug-preview'), '');
  }
  function debugValidationMessage(error) {
    const diagnostics = Array.isArray(error && error.diagnostics) ? error.diagnostics : [];
    const details = diagnostics.map(item => [item.resourceId, item.field, item.summary].filter(Boolean).join(' · ')).filter(Boolean);
    return details.length ? details.join('; ') : error.message;
  }
  function parseDebugObject(id, label) {
    let value;
    try { value = JSON.parse(String(byId(id).value || '{}')); }
    catch (_) { throw new Error(`${label} must be a JSON object.`); }
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label} must be a JSON object.`);
    return value;
  }
  function currentDebugInput() {
    if (!debugDefaults || !debugTarget) throw new Error('Open Debug from a selected Template, Flow, or Tool first.');
    const input = JSON.parse(JSON.stringify(debugDefaults));
    if (debugTarget.type === 'tool') {
      delete input.vars; delete input.tools;
      input.inputs = parseDebugObject('debug-inputs','Inputs');
      input.arguments = parseDebugObject('debug-arguments','Tool arguments');
    } else {
      delete input.arguments; delete input.tools;
      input.inputs = parseDebugObject('debug-inputs','Inputs');
      input.vars = parseDebugObject('debug-vars','Variables');
    }
    return input;
  }
  async function previewDebugDraft() {
    const item = activeResource, packageId = selectedPackage, generation = navigation;
    if (!item || !packageId || !debugTarget) throw new Error('Open Debug from a selected resource detail first.');
    const body = { packageId, target: debugTarget, input: currentDebugInput() };
    const environment = String(byId('submit-form').elements.environment.value || '').trim();
    if (environment) body.environment = environment;
    const fingerprint = JSON.stringify(body);
    if (debugDraft && debugDraftFingerprint === fingerprint) {
      text(byId('debug-preview'), debugDraft.previewYaml || JSON.stringify(debugDraft.preview || {}, null, 2));
      text(byId('debug-form-status'), debugDraft.redacted ? 'Validation passed. The preview hides sensitive fields.' : 'Validation passed.');
      return debugDraft;
    }
    if (debugPreviewPending && debugPreviewPendingFingerprint === fingerprint) return debugPreviewPending;
    const pending = (async () => {
      text(byId('debug-form-status'), 'Validating the effective Debug input…');
      const draft = await request('drafts/debug', { method: 'POST', body: JSON.stringify(body) });
      if (generation !== navigation || selectedPackage !== packageId || activeResource !== item) return null;
      const currentBody = { packageId, target: debugTarget, input: currentDebugInput() };
      const currentEnvironment = String(byId('submit-form').elements.environment.value || '').trim();
      if (currentEnvironment) currentBody.environment = currentEnvironment;
      if (JSON.stringify(currentBody) !== fingerprint) return null;
      debugDraft = draft; debugDraftFingerprint = fingerprint;
      text(byId('debug-preview'), draft.previewYaml || JSON.stringify(draft.preview || {}, null, 2));
      text(byId('debug-form-status'), draft.redacted ? 'Validation passed. The preview hides sensitive fields.' : 'Validation passed.');
      return draft;
    })();
    debugPreviewPending = pending; debugPreviewPendingFingerprint = fingerprint;
    try { return await pending; }
    finally {
      if (debugPreviewPending === pending) {
        debugPreviewPending = null; debugPreviewPendingFingerprint = '';
      }
    }
  }
  async function runDebugDraft() {
    const packageId = selectedPackage, generation = navigation;
    if (!packageId || submitting.has(packageId)) return;
    submitting.add(packageId); byId('submit-debug-form').disabled = true;
    try {
      const draft = await previewDebugDraft();
      if (!draft || generation !== navigation || selectedPackage !== packageId) return;
      const accepted = await request('jobs/debug', { method: 'POST', body: JSON.stringify({ packageId, draftId: draft.draftId }) });
      debugDraft = null; debugDraftFingerprint = '';
      if (generation === navigation && selectedPackage === packageId) location.hash = `#/jobs/${encodeURIComponent(accepted.jobId)}`;
    } catch (error) {
      if (generation === navigation && selectedPackage === packageId) { text(byId('debug-form-status'), `Debug was not submitted. ${debugValidationMessage(error)}`); message(error.message); }
    } finally {
      submitting.delete(packageId); if (selectedPackage === packageId) byId('submit-debug-form').disabled = false;
    }
  }
  async function loadQuickLoadForm() {
    const item = activeResource, packageId = selectedPackage, generation = navigation;
    if (!inlineLoadEnabled) {
      text(byId('quick-load-form-status'), 'Browser-created Quick and Advanced Load are disabled by Server configuration.'); return;
    }
    if (!item || !packageId || !['template','flow','tool'].includes(item.type)) return;
    const model = String(byId('quick-load-model').value || 'virtualUsers');
    const sequence = ++quickLoadFormSequence;
    quickLoadDefaults = null; quickLoadTarget = null; quickLoadPolicyDefaults = null;
    quickLoadDraft = null; quickLoadDraftFingerprint = '';
    byId('resource-quick-load').hidden = false; byId('quick-load-form-editor').hidden = true;
    text(byId('quick-load-form-status'), 'Loading safe business defaults and Load policy…'); text(byId('quick-load-preview'), '');
    try {
      const environment = String(byId('submit-form').elements.environment.value || '').trim();
      const params = [`model=${encodeURIComponent(model)}`];
      if (environment) params.push(`environment=${encodeURIComponent(environment)}`);
      const data = await request(`${resourcePath(packageId,item)}/quick-load-form?${params.join('&')}`);
      if (sequence !== quickLoadFormSequence || generation !== navigation || activeResource !== item || selectedPackage !== packageId) return;
      quickLoadDefaults = JSON.parse(JSON.stringify(data.input || {}));
      quickLoadPolicyDefaults = JSON.parse(JSON.stringify(data.preview || {}));
      quickLoadTarget = data.target || { type: item.type, id: item.logicalId };
      setQuickLoadControls(quickLoadPolicyDefaults, model);
      resetQuickLoadForm();
      byId('quick-load-form-editor').hidden = false;
      text(byId('quick-load-form-status'), data.redacted
        ? 'Defaults loaded. Sensitive business values are hidden in the form and preview.'
        : 'Defaults loaded from the target sidecar, or generated as empty inputs with a bundled low-intensity policy.');
    } catch (error) {
      if (sequence === quickLoadFormSequence && generation === navigation && activeResource === item) {
        text(byId('quick-load-form-status'), `Quick Load defaults are unavailable. ${debugValidationMessage(error)}`); message(error.message);
      }
    }
  }
  function setQuickLoadControls(scenario, model) {
    const load = scenario && scenario.load || {};
    const workload = scenario && Array.isArray(scenario.workloads) ? scenario.workloads[0] || {} : {};
    const execution = workload.execution || {};
    byId('quick-load-users-fields').hidden = model !== 'virtualUsers';
    byId('quick-load-arrival-fields').hidden = model !== 'arrivalRate';
    byId('quick-load-users').value = load.users == null ? '' : String(load.users);
    byId('quick-load-arrival-rate').value = load.arrivalRate == null ? '' : String(load.arrivalRate);
    byId('quick-load-max-concurrent').value = load.maxConcurrent == null ? '' : String(load.maxConcurrent);
    byId('quick-load-duration').value = load.duration == null ? '' : String(load.duration);
    byId('quick-load-warmup').value = load.warmup == null ? '' : String(load.warmup);
    byId('quick-load-ramp-up').value = load.rampUp == null ? '' : String(load.rampUp);
    byId('quick-load-ramp-down').value = load.rampDown == null ? '' : String(load.rampDown);
    byId('quick-load-think-time').value = execution.thinkTime == null ? '' : String(execution.thinkTime);
  }
  function resetQuickLoadForm() {
    if (!quickLoadDefaults || !quickLoadPolicyDefaults) return;
    quickLoadDraft = null; quickLoadDraftFingerprint = '';
    byId('quick-load-input').value = JSON.stringify(quickLoadDefaults, null, 2);
    setQuickLoadControls(quickLoadPolicyDefaults, String(byId('quick-load-model').value || 'virtualUsers'));
    text(byId('quick-load-preview'), '');
  }
  function parsePositiveInteger(id, label) {
    const value = String(byId(id).value || '').trim();
    if (!value) return null;
    const parsed = Number(value);
    if (!Number.isSafeInteger(parsed) || parsed < 1) throw new Error(`${label} must be a positive integer.`);
    return parsed;
  }
  function currentQuickLoadBody() {
    if (!quickLoadDefaults || !quickLoadTarget) throw new Error('Open Quick Load from a selected Template, Flow, or Tool first.');
    let input;
    try { input = JSON.parse(String(byId('quick-load-input').value || '{}')); }
    catch (_) { throw new Error('Business inputs and variables must be a JSON object.'); }
    if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('Business inputs and variables must be a JSON object.');
    const model = String(byId('quick-load-model').value || 'virtualUsers');
    const load = {};
    const duration = String(byId('quick-load-duration').value || '').trim();
    if (!duration) throw new Error('Duration is required.');
    load.duration = duration;
    for (const [id, key] of [['quick-load-warmup','warmup'],['quick-load-ramp-up','rampUp'],['quick-load-ramp-down','rampDown']]) {
      const value = String(byId(id).value || '').trim(); if (value) load[key] = value;
    }
    const execution = {};
    if (model === 'virtualUsers') {
      const users = parsePositiveInteger('quick-load-users', 'Virtual users');
      if (users == null) throw new Error('Virtual users is required.');
      load.users = users;
      const thinkTime = String(byId('quick-load-think-time').value || '').trim(); if (thinkTime) execution.thinkTime = thinkTime;
    } else {
      const arrivalRate = String(byId('quick-load-arrival-rate').value || '').trim();
      if (!arrivalRate) throw new Error('Arrival rate is required.');
      load.arrivalRate = arrivalRate;
      const maximum = parsePositiveInteger('quick-load-max-concurrent', 'Maximum concurrent');
      if (maximum == null) throw new Error('Maximum concurrent is required.');
      load.maxConcurrent = maximum;
      load.overloadPolicy = 'drop';
    }
    const body = { packageId: selectedPackage, target: quickLoadTarget, model, input, load, execution };
    const environment = String(byId('submit-form').elements.environment.value || '').trim();
    if (environment) body.environment = environment;
    return body;
  }
  async function previewQuickLoadDraft() {
    const item = activeResource, packageId = selectedPackage, generation = navigation;
    if (!item || !packageId || !quickLoadTarget) throw new Error('Open Quick Load from a selected resource detail first.');
    const body = currentQuickLoadBody();
    const fingerprint = JSON.stringify(body);
    if (quickLoadDraft && quickLoadDraftFingerprint === fingerprint
        && Date.parse(quickLoadDraft.expiresAt || '') > Date.now() + 1000) {
      text(byId('quick-load-preview'), quickLoadDraft.previewYaml || JSON.stringify(quickLoadDraft.preview || {}, null, 2));
      text(byId('quick-load-form-status'), quickLoadDraft.redacted ? 'Validation passed. The preview hides sensitive fields.' : 'Validation passed.');
      return quickLoadDraft;
    }
    if (quickLoadPreviewPending && quickLoadPreviewPendingFingerprint === fingerprint) return quickLoadPreviewPending;
    const pending = (async () => {
      text(byId('quick-load-form-status'), 'Validating the policy and effective one-workload scenario…');
      const draft = await request('drafts/quick-load', { method: 'POST', body: JSON.stringify(body) });
      if (generation !== navigation || selectedPackage !== packageId || activeResource !== item) return null;
      if (JSON.stringify(currentQuickLoadBody()) !== fingerprint) return null;
      quickLoadDraft = draft; quickLoadDraftFingerprint = fingerprint;
      text(byId('quick-load-preview'), draft.previewYaml || JSON.stringify(draft.preview || {}, null, 2));
      text(byId('quick-load-form-status'), draft.redacted ? 'Validation passed. The preview hides sensitive fields.' : 'Validation passed.');
      return draft;
    })();
    quickLoadPreviewPending = pending; quickLoadPreviewPendingFingerprint = fingerprint;
    try { return await pending; }
    finally {
      if (quickLoadPreviewPending === pending) { quickLoadPreviewPending = null; quickLoadPreviewPendingFingerprint = ''; }
    }
  }
  async function runQuickLoadDraft() {
    const packageId = selectedPackage, generation = navigation;
    if (!packageId || submitting.has(packageId)) return;
    submitting.add(packageId); byId('submit-quick-load-form').disabled = true;
    try {
      const draft = await previewQuickLoadDraft();
      if (!draft || generation !== navigation || selectedPackage !== packageId) return;
      const modelLabel = draft.model === 'arrivalRate' ? 'Arrival Rate' : 'Virtual Users';
      if (JSON.stringify(currentQuickLoadBody()) !== quickLoadDraftFingerprint) return;
      if (!window.confirm(`Start ${modelLabel} Load for ${draft.target.type} ${draft.target.id}? Review the validated YAML preview before confirming.`)) return;
      const accepted = await request('jobs/load', { method: 'POST', body: JSON.stringify({ packageId, draftId: draft.draftId }) });
      quickLoadDraft = null; quickLoadDraftFingerprint = '';
      if (generation === navigation && selectedPackage === packageId) location.hash = `#/jobs/${encodeURIComponent(accepted.jobId)}`;
    } catch (error) {
      if (generation === navigation && selectedPackage === packageId) {
        text(byId('quick-load-form-status'), `Quick Load was not submitted. ${debugValidationMessage(error)}`); message(error.message);
      }
      if (error.status === 404 || error.status === 409) { quickLoadDraft = null; quickLoadDraftFingerprint = ''; }
    } finally {
      submitting.delete(packageId); if (selectedPackage === packageId) byId('submit-quick-load-form').disabled = false;
    }
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
    ['environment','config','runId','debugId','suiteDirectory','validationScope','scenario'].forEach(key => { const value = String(values.get(key) || '').trim(); if (value) body[key] = value; });
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
  byId('debug-resource').addEventListener('click', loadDebugForm);
  byId('quick-load-resource').addEventListener('click', loadQuickLoadForm);
  byId('advanced-load-model').addEventListener('change', loadAdvancedLoadPolicy);
  byId('advanced-load-environment').addEventListener('input', markAdvancedLoadDirty);
  byId('advanced-load-environment').addEventListener('change', () => { if (advancedLoadModel) loadAdvancedLoadPolicy(); });
  ['advanced-load-warmup','advanced-load-ramp-up','advanced-load-duration','advanced-load-ramp-down',
    'advanced-load-seed','advanced-load-execution','advanced-load-testdata','advanced-load-thresholds','advanced-load-evidence']
    .forEach(id => byId(id).addEventListener('input', markAdvancedLoadDirty));
  byId('advanced-load-workloads').addEventListener('input', markAdvancedLoadDirty);
  byId('advanced-load-workloads').addEventListener('change', event => {
    const article = event.target.closest('.advanced-workload');
    if (article && event.target.matches('[data-field="structure"]')) syncAdvancedWorkload(article);
    if (event.target.matches('[data-field="targetType"]')) {
      const container = event.target.closest('.advanced-workload, .advanced-mix-entry');
      const business = advancedField(container, 'business');
      if (business) business.value = event.target.value === 'tool' ? '{\n  "arguments": {}\n}' : '{\n  "inputs": {},\n  "vars": {}\n}';
      updateAdvancedTargetSuggestions(String(event.target.value || ''));
    }
    markAdvancedLoadDirty();
  });
  byId('advanced-load-workloads').addEventListener('click', async event => {
    const button = event.target.closest('button[data-action]'); if (!button) return;
    const action = button.dataset.action, article = button.closest('.advanced-workload');
    if (action === 'duplicate-workload' && article) addAdvancedWorkload(article);
    else if (action === 'move-workload-up' && article) moveAdvancedElement(article, -1, '.advanced-workload');
    else if (action === 'move-workload-down' && article) moveAdvancedElement(article, 1, '.advanced-workload');
    else if (action === 'remove-workload' && article) { article.remove(); markAdvancedLoadDirty(); }
    else if (action === 'add-mix-entry' && article) addAdvancedMixEntry(article);
    else if (action === 'move-mix-up') moveAdvancedElement(button.closest('.advanced-mix-entry'), -1, '.advanced-mix-entry');
    else if (action === 'move-mix-down') moveAdvancedElement(button.closest('.advanced-mix-entry'), 1, '.advanced-mix-entry');
    else if (action === 'remove-mix-entry') { button.closest('.advanced-mix-entry').remove(); markAdvancedLoadDirty(); }
    else if (action === 'load-target-defaults') {
      try { await loadAdvancedTargetDefaults(button); }
      catch (error) { text(byId('advanced-load-status'), `Target defaults are unavailable. ${debugValidationMessage(error)}`); message(error.message); }
    }
  });
  byId('add-advanced-workload').addEventListener('click', () => addAdvancedWorkload());
  byId('validate-advanced-load').addEventListener('click', async () => {
    try { await previewAdvancedLoadDraft(); }
    catch (error) { text(byId('advanced-load-status'), `Load scenario is invalid. ${debugValidationMessage(error)}`); message(error.message); }
  });
  byId('copy-advanced-load').addEventListener('click', copyAdvancedLoadYaml);
  byId('export-advanced-load').addEventListener('click', exportAdvancedLoadYaml);
  byId('start-advanced-load').addEventListener('click', runAdvancedLoadDraft);
  byId('reset-debug-form').addEventListener('click', resetDebugForm);
  ['debug-inputs','debug-vars','debug-arguments'].forEach(id => byId(id).addEventListener('input', () => {
    debugDraft = null; debugDraftFingerprint = ''; text(byId('debug-preview'), '');
    text(byId('debug-form-status'), 'Input changed. Validate it before starting Debug.');
  }));
  const debugEnvironment = byId('submit-form').elements.environment;
  if (debugEnvironment) debugEnvironment.addEventListener('input', () => {
    debugDraft = null; debugDraftFingerprint = ''; text(byId('debug-preview'), '');
    quickLoadDraft = null; quickLoadDraftFingerprint = ''; text(byId('quick-load-preview'), '');
  });
  byId('preview-debug-form').addEventListener('click', async () => {
    try { await previewDebugDraft(); }
    catch (error) { text(byId('debug-form-status'), `Debug input is invalid. ${debugValidationMessage(error)}`); message(error.message); }
  });
  byId('submit-debug-form').addEventListener('click', runDebugDraft);
  byId('quick-load-model').addEventListener('change', loadQuickLoadForm);
  byId('reset-quick-load-form').addEventListener('click', resetQuickLoadForm);
  ['quick-load-users','quick-load-think-time','quick-load-arrival-rate','quick-load-max-concurrent',
    'quick-load-duration','quick-load-warmup','quick-load-ramp-up','quick-load-ramp-down','quick-load-input'].forEach(id =>
    byId(id).addEventListener('input', () => {
      quickLoadDraft = null; quickLoadDraftFingerprint = ''; text(byId('quick-load-preview'), '');
      text(byId('quick-load-form-status'), 'Input changed. Validate it before starting Load.');
    }));
  byId('preview-quick-load-form').addEventListener('click', async () => {
    try { await previewQuickLoadDraft(); }
    catch (error) { text(byId('quick-load-form-status'), `Quick Load input is invalid. ${debugValidationMessage(error)}`); message(error.message); }
  });
  byId('submit-quick-load-form').addEventListener('click', runQuickLoadDraft);
  byId('show-declared-configuration').addEventListener('click', () => { if (selectedPackage) loadDeclaredConfiguration(selectedPackage); });
  byId('show-effective-configuration').addEventListener('click', showEffectiveConfiguration);
  byId('compare-configuration').addEventListener('click', compareConfiguration);
  byId('refresh-configuration').addEventListener('click', () => { if (selectedPackage) loadDeclaredConfiguration(selectedPackage); });
  byId('refresh-jobs').addEventListener('click', () => loadHome());
  window.addEventListener('hashchange', route);
  route();
})();
