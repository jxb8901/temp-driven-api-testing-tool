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
  let configurationProfiles = [];
  let configurationMode = 'declared';
  let configurationEnvironment = '';
  let configurationSection = 'globals';
  let configurationPage = null;
  let configurationNextOffset = null;
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
      await Promise.all([loadResources(id, true, generation), loadDeclaredConfiguration(id, generation)]);
    } catch (error) { if (generation === navigation) message(error.message); }
  }
  function renderConfiguration(data, label) {
    const state = data && data.state ? data.state : 'unavailable';
    const diagnostics = Array.isArray(data && data.diagnostics) ? data.diagnostics : [];
    const detail = diagnostics.map(item => item.summary || item.code || 'Configuration warning').join(' ');
    text(byId('configuration-status'), `${label}: ${state}${detail ? `. ${detail}` : '.'}`);
    const view = byId('configuration-view'); view.replaceChildren(); text(view, label);
    if (data && data.view === 'compare') {
      configurationMode = 'compare'; configurationPage = data;
      configurationNextOffset = data.nextOffset == null ? null : Number(data.nextOffset);
      renderConfigurationComparison(data.fields || []);
      byId('configuration-section').disabled = true;
      byId('use-configuration-environment').disabled = true;
    } else if (data && data.view === 'effective') {
      configurationMode = 'effective';
      if (data.section != null) configurationSection = data.section;
      if (data.section == null && !configurationSection) configurationSection = 'globals';
      configurationPage = data;
      configurationNextOffset = data.nextOffset == null ? null : Number(data.nextOffset);
      configurationEnvironment = String(data.environment || configurationEnvironment || '');
      if (data.section == null) setConfigurationSections(data.sections || []);
      byId('configuration-section').disabled = false;
      byId('use-configuration-environment').disabled = !configurationEnvironment;
      renderEffectiveConfiguration(data);
    } else {
      configurationMode = 'declared'; configurationPage = data; configurationNextOffset = null;
      byId('configuration-section').disabled = true;
      byId('use-configuration-environment').disabled = true;
      renderDeclaredConfiguration(data || {});
    }
    byId('load-more-configuration').hidden = configurationNextOffset == null;
  }
  function setConfigurationProfiles(data) {
    const profiles = Array.isArray(data && data.environments) ? data.environments.filter(item => item.state === 'active' && item.name) : [];
    configurationProfiles = profiles;
    const ids = ['configuration-environment', 'configuration-left', 'configuration-right'];
    ids.forEach(id => {
      const select = byId(id); select.replaceChildren();
      profiles.forEach(profile => {
        const option = el('option', profile.name); option.value = profile.name; select.append(option);
      });
      if (profiles.length) select.value = profiles.find(profile => profile.default)?.name || profiles[0].name;
    });
    if (!profiles.length) {
      const option = el('option', 'Package default'); option.value = '';
      byId('configuration-environment').append(option); byId('configuration-environment').value = '';
    }
    const left = String(byId('configuration-left').value || '');
    const different = profiles.find(profile => profile.name !== left);
    if (different) byId('configuration-right').value = different.name;
    updateCompareEnabled();
    return profiles;
  }
  function updateCompareEnabled() {
    const left = String(byId('configuration-left').value || '');
    const right = String(byId('configuration-right').value || '');
    byId('compare-configuration').disabled = configurationProfiles.length < 2 || !left || !right || left === right;
  }
  function setConfigurationSections(sections) {
    const select = byId('configuration-section'); select.replaceChildren();
    const globalOption = el('option', 'Global'); globalOption.value = 'globals'; select.append(globalOption);
    for (const section of sections) {
      const option = el('option', section.title || section.id); option.value = section.id; select.append(option);
    }
    if (!sections.some(section => section.id === configurationSection)) configurationSection = 'globals';
    select.value = configurationSection;
  }
  function fieldDisplay(field) {
    if (!field || field.state !== 'visible') return field && field.state || 'unavailable';
    const origin = field.origin ? ` · ${field.origin}` : '';
    return `${String(field.value)}${origin}`;
  }
  function appendTable(view, headers, rows) {
    const table = document.createElement('table');
    const thead = document.createElement('thead'); const headerRow = document.createElement('tr');
    headers.forEach(header => headerRow.append(el('th', header))); thead.append(headerRow); table.append(thead);
    const body = document.createElement('tbody');
    rows.forEach(values => { const row = document.createElement('tr'); values.forEach(value => row.append(el('td', value))); body.append(row); });
    table.append(body); view.append(table);
  }
  function renderDeclaredConfiguration(data) {
    const view = byId('configuration-view');
    appendTable(view, ['Section', 'Declared entries', 'Environment state'],
      (data.sections || []).map(section => [section.title || section.id,
        String(section.root && section.root.entryCount || 0),
        (section.profiles || []).map(profile => `${profile.environment}: ${profile.state}`).join(', ')]));
    const globals = Object.entries(data.globals || {}).map(([name, field]) => [name, fieldDisplay(field)]);
    appendTable(view, ['Global field', 'Declared value'], globals);
  }
  function currentSectionEntries(data) {
    if (configurationSection === 'globals') return Object.entries(data.globals || {}).map(([name, field]) => ({id:name,fields:{value:field}}));
    const section = (data.sections || []).find(item => item.id === configurationSection);
    return section && Array.isArray(section.entries) ? section.entries : [];
  }
  function renderEffectiveConfiguration(data) {
    const view = byId('configuration-view');
    const entries = currentSectionEntries(data);
    const query = String(byId('configuration-search').value || '').trim().toLowerCase();
    const rows = [];
    if (configurationSection === 'globals') {
      for (const entry of entries) {
        const field = entry.fields.value;
        if (!query || `${entry.id} ${fieldDisplay(field)}`.toLowerCase().includes(query)) rows.push([entry.id, fieldDisplay(field), field && field.state || 'unavailable', field && field.origin || '']);
      }
    } else {
      for (const entry of entries) for (const [name, field] of Object.entries(entry.fields || {})) {
        const path = `${entry.id}.${name}`;
        if (!query || `${path} ${fieldDisplay(field)}`.toLowerCase().includes(query)) rows.push([path, fieldDisplay(field), field && field.state || 'unavailable', field && field.origin || entry.origin || '']);
      }
    }
    appendTable(view, ['Field', 'Value', 'State', 'Origin'], rows);
    const sectionMeta = (data.sections || []).find(item => item.id === configurationSection);
    if (sectionMeta && sectionMeta.entryCount != null) text(byId('configuration-status'), `${byId('configuration-status').textContent} Showing ${entries.length} of ${sectionMeta.entryCount} entries.`);
  }
  function renderConfigurationComparison(fields) {
    const query = String(byId('configuration-search').value || '').trim().toLowerCase();
    const rows = fields.filter(item => !query || String(item.path || '').toLowerCase().includes(query)).map(item => [
      item.path, fieldDisplay(item.left), fieldDisplay(item.right), item.change || 'unavailable'
    ]);
    appendTable(byId('configuration-view'), ['Field', 'Left environment', 'Right environment', 'Change'], rows);
  }
  function configurationQuery(environment, section, offset) {
    const params = [];
    if (environment) params.push(`environment=${encodeURIComponent(environment)}`);
    if (section) params.push(`section=${encodeURIComponent(section)}`);
    params.push(`offset=${Number(offset || 0)}`, 'limit=50');
    return params.join('&');
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
    if (!packageId || configurationProfiles.length && !environment) { text(byId('configuration-status'), 'Select a declared environment first.'); return; }
    const sequence = ++configurationRequestSequence;
    text(byId('configuration-view'), '');
    const label = environment ? `Effective configuration for ${environment}` : 'Effective package default configuration';
    text(byId('configuration-status'), `Loading ${label.toLowerCase()}…`);
    try {
      const query = configurationQuery(environment, null, 0);
      const data = await request(`packages/${encodeURIComponent(packageId)}/configuration/effective?${query}`);
      if (sequence !== configurationRequestSequence || generation !== navigation || selectedPackage !== packageId) return;
      configurationEnvironment = String(data.environment || environment || ''); configurationSection = 'globals';
      renderConfiguration(data, label);
    } catch (error) {
      if (sequence === configurationRequestSequence && generation === navigation && selectedPackage === packageId) {
        text(byId('configuration-status'), `${label} is unavailable.`); message(error.message);
      }
    }
  }
  async function loadConfigurationSection(section, offset = 0, append = false) {
    const packageId = selectedPackage, generation = navigation, environment = configurationEnvironment;
    if (!packageId || !section) return;
    configurationSection = section;
    const sequence = ++configurationRequestSequence;
    try {
      const query = configurationQuery(environment, section, offset);
      const data = await request(`packages/${encodeURIComponent(packageId)}/configuration/effective?${query}`);
      if (sequence !== configurationRequestSequence || generation !== navigation || selectedPackage !== packageId) return;
      if (append && configurationPage && configurationPage.section === section) {
        const previous = (configurationPage.sections || []).find(item => item.id === section);
        const incoming = (data.sections || []).find(item => item.id === section);
        if (previous && incoming) incoming.entries = (previous.entries || []).concat(incoming.entries || []);
      }
      renderConfiguration(data, `Effective configuration for ${environment || 'package default'}`);
    } catch (error) {
      if (sequence === configurationRequestSequence && generation === navigation && selectedPackage === packageId) message(error.message);
    }
  }
  async function compareConfiguration() {
    const packageId = selectedPackage, generation = navigation;
    const left = String(byId('configuration-left').value || '').trim();
    const right = String(byId('configuration-right').value || '').trim();
    if (!packageId || !left || !right || left === right || configurationProfiles.length < 2) { text(byId('configuration-status'), 'Select two different declared environments to compare.'); return; }
    const sequence = ++configurationRequestSequence;
    text(byId('configuration-view'), '');
    text(byId('configuration-status'), `Comparing ${left} with ${right}…`);
    try {
      const query = `left=${encodeURIComponent(left)}&right=${encodeURIComponent(right)}&offset=0&limit=50`;
      const data = await request(`packages/${encodeURIComponent(packageId)}/configuration/compare?${query}`);
      if (sequence !== configurationRequestSequence || generation !== navigation || selectedPackage !== packageId) return;
      renderConfiguration(data, `Configuration comparison: ${left} and ${right}`);
    } catch (error) {
      if (sequence === configurationRequestSequence && generation === navigation && selectedPackage === packageId) {
        text(byId('configuration-status'), 'Configuration comparison is unavailable.'); message(error.message);
      }
    }
  }
  async function loadMoreConfiguration() {
    if (configurationNextOffset == null || !selectedPackage) return;
    const offset = configurationNextOffset;
    if (configurationMode === 'effective') return loadConfigurationSection(configurationSection, offset, true);
    if (configurationMode !== 'compare') return;
    const left = String(byId('configuration-left').value || '').trim();
    const right = String(byId('configuration-right').value || '').trim();
    const sequence = ++configurationRequestSequence, generation = navigation;
    try {
      const query = `left=${encodeURIComponent(left)}&right=${encodeURIComponent(right)}&offset=${offset}&limit=50`;
      const data = await request(`packages/${encodeURIComponent(selectedPackage)}/configuration/compare?${query}`);
      if (sequence !== configurationRequestSequence || generation !== navigation) return;
      data.fields = (configurationPage && configurationPage.fields || []).concat(data.fields || []);
      data.offset = 0;
      renderConfiguration(data, `Configuration comparison: ${left} and ${right}`);
    } catch (error) { if (sequence === configurationRequestSequence && generation === navigation) message(error.message); }
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
      byId('resource-configuration-link').hidden = activeResource.type !== 'tool';
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
  byId('resource-configuration-link').addEventListener('click', async () => {
    const item = activeResource;
    if (!item || item.type !== 'tool') return;
    byId('configuration-search').value = item.logicalId || '';
    await showEffectiveConfiguration();
    if (configurationMode === 'effective') {
      byId('configuration-section').value = 'tools';
      await loadConfigurationSection('tools', 0, false);
    }
  });
  byId('run-resource-case').addEventListener('click', runResourceCase);
  byId('show-declared-configuration').addEventListener('click', () => { if (selectedPackage) loadDeclaredConfiguration(selectedPackage); });
  byId('show-effective-configuration').addEventListener('click', showEffectiveConfiguration);
  byId('compare-configuration').addEventListener('click', compareConfiguration);
  byId('use-configuration-environment').addEventListener('click', () => {
    const field = byId('submit-form').elements.environment;
    if (!field || !configurationEnvironment) return;
    field.value = configurationEnvironment;
    text(byId('configuration-status'), `Set the Run/Debug/Load environment to ${configurationEnvironment}. No job was submitted.`);
  });
  byId('configuration-section').addEventListener('change', () => {
    const selected = String(byId('configuration-section').value || 'globals');
    loadConfigurationSection(selected, 0, false);
  });
  byId('configuration-left').addEventListener('change', () => {
    const left = String(byId('configuration-left').value || '');
    const right = String(byId('configuration-right').value || '');
    if (left && left === right) {
      const different = configurationProfiles.find(profile => profile.name !== left);
      if (different) byId('configuration-right').value = different.name;
    }
    updateCompareEnabled();
  });
  byId('configuration-right').addEventListener('change', updateCompareEnabled);
  byId('configuration-search').addEventListener('input', () => {
    if (!configurationPage) return;
    if (configurationMode === 'effective') renderConfiguration(configurationPage, `Effective configuration for ${configurationEnvironment || 'package default'}`);
    else if (configurationMode === 'compare') renderConfiguration(configurationPage, `Configuration comparison: ${configurationPage.leftEnvironment} and ${configurationPage.rightEnvironment}`);
  });
  byId('load-more-configuration').addEventListener('click', loadMoreConfiguration);
  byId('refresh-configuration').addEventListener('click', () => { if (selectedPackage) loadDeclaredConfiguration(selectedPackage); });
  byId('refresh-jobs').addEventListener('click', () => loadHome());
  window.addEventListener('hashchange', route);
  route();
})();
