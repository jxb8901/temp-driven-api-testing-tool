const path = require('node:path');
const assert = require('node:assert/strict');
const { launchBrowser, selectFixture, startFixture, stopFixture } = require('./browser-support.cjs');

function jsonResponse(route, status, body) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function assertAxe(page, message) {
  const audit = await page.evaluate(async () => window.axe.run(document, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
  }));
  assert.deepEqual(audit.violations.map(({ id, impact, help, nodes }) => ({
    id, impact, help, selectors: nodes.map(node => node.target),
  })), [], message);
}

async function main() {
  const fixture = await startFixture();
  let browser;
  let authFailure = false;
  let sourceCalls = 0;
  let listCalls = 0;
  let detailCalls = 0;
  let capabilitiesCalls = 0;
  let identityVersion = 1;
  let submittedInput;
  let submittedPrincipal;
  let submittedLoadScenario;
  let submittedScheduleInput;
  let createdSchedule;
  const resourceReferenceId = 'a'.repeat(64);
  const nestedReferenceId = 'b'.repeat(64);
  const referenceRequests = [];
  const generatedPreviewCalls = [];
  let authorizationHeaderSeen = false;
  try {
    browser = await launchBrowser();
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: fixture.origin });
    const page = await context.newPage();
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    await selectFixture(context, fixture.origin, 'loaded');

    await page.route('**/api/v1/**', async route => {
      const request = route.request();
      const url = new URL(request.url());
      const path = url.pathname.slice(url.pathname.indexOf('/api/v1/') + '/api/v1/'.length);
      const method = request.method();
      if (request.headers().authorization) authorizationHeaderSeen = true;

      if (method === 'GET' && path === 'auth/capabilities') {
        capabilitiesCalls++;
        return jsonResponse(route, 200, {
          capabilities: { operate: true, accountManage: false, systemSettingsWrite: false, configurationAdmin: false },
          operate: true,
          accountManage: false,
          systemSettingsWrite: false,
          configurationAdmin: false,
          inspectionCacheContext: (identityVersion === 1 ? 'a' : 'b').repeat(43),
        });
      }
      if (authFailure && path.endsWith('/source')) {
        return jsonResponse(route, 401, { error: { code: 'UNAUTHORIZED', summary: 'authorization header rejected: canary-401-secret' } });
      }
      if (method === 'GET' && path === 'version') {
        return jsonResponse(route, 200, { apiVersion: '1', version: '4.2.1', unifiedRunVersion: 'att-execution/v1', inlineLoadEnabled: true, loadSchedulingEnabled: true });
      }
      if (method === 'GET' && ['load-schedules', 'schedules'].includes(path)) {
        return jsonResponse(route, 200, { items: createdSchedule ? [createdSchedule] : [], total: createdSchedule ? 1 : 0 });
      }
      if (method === 'POST' && ['load-schedules/preview', 'schedules/preview'].includes(path)) {
        return jsonResponse(route, 200, { ...request.postDataJSON(), nextRunAt: '2030-01-01T00:00:00.000Z' });
      }
      if (method === 'POST' && ['load-schedules', 'schedules'].includes(path)) {
        submittedScheduleInput = request.postDataJSON();
        createdSchedule = {
          id: 'schedule-inline-99', ...submittedScheduleInput, inlineSnapshot: true,
          nextRunAt: '2030-01-01T00:00:00.000Z',
        };
        return jsonResponse(route, 201, createdSchedule);
      }
      if (method === 'GET' && path === 'packages') {
        return jsonResponse(route, 200, { items: [{ packageId: 'payments', name: 'payments', state: 'ready' }], total: 1 });
      }
      if (method === 'GET' && path === 'jobs') return jsonResponse(route, 200, { items: [], total: 0, offset: 0, limit: 100, groups: [], principalSuggestions: [], packageSuggestions: [] });
      if (method === 'GET' && path === 'jobs/job-debug-99') {
        return jsonResponse(route, 200, { jobId: 'job-debug-99', packageId: 'payments', status: 'QUEUED', command: 'DEBUG' });
      }
      if (method === 'GET' && path === 'packages/payments/resources') {
        listCalls++;
        return jsonResponse(route, 200, {
          items: [
            { type: 'template', resourceId: 'checkout', logicalId: 'checkout', name: 'checkout', state: 'ready', sourceAvailable: true },
            { type: 'template', resourceId: 'empty', logicalId: 'empty', name: 'empty', state: 'ready', sourceAvailable: true },
          ], total: 2, nextCursor: null,
        });
      }
      const detailMatch = path.match(/^packages\/payments\/resources\/template\/(checkout|empty)$/);
      if (method === 'GET' && detailMatch) {
        detailCalls++;
        const id = detailMatch[1];
        return jsonResponse(route, 200, {
          resource: { type: 'template', resourceId: id, logicalId: id, name: id, state: 'ready', sourceAvailable: true, references: [], referencedBy: [] },
          definition: { template: id, inputs: ['credential'] },
        });
      }
      const sourceMatch = path.match(/^packages\/payments\/resources\/template\/(checkout|empty)\/source$/);
      if (method === 'GET' && sourceMatch) {
        sourceCalls += 1;
        const id = sourceMatch[1];
        return jsonResponse(route, 200, {
          available: true,
          format: 'yaml',
          logicalPath: 'templates/checkout/template.yaml',
          text: id === 'empty' ? '' : Array.from({ length: 5000 }, (_, index) => `step-${index + 1}: ${index === 0 ? `identity-${identityVersion} ` + '<img src=x onerror=alert(1)>' : 'safe'}`).join('\n') + '\npayload: &{./payloads/request.xml}\n',
          fileReferences: id === 'empty' ? [] : [{ referenceId: resourceReferenceId, logicalPath: 'templates/checkout/payloads/request.xml', available: true }],
        });
      }
      if (method === 'GET' && path === 'packages/payments/resources/template/checkout/source-file') {
        const referenceId = url.searchParams.get('referenceId');
        const origin = url.searchParams.get('origin');
        referenceRequests.push({ referenceId, origin });
        if (referenceId === resourceReferenceId) {
          return jsonResponse(route, 200, {
            available: true, logicalPath: 'templates/checkout/payloads/request.xml', format: 'xml',
            text: '<request>&{./refs/child.json}</request>',
            fileReferences: [{ referenceId: nestedReferenceId, logicalPath: 'templates/checkout/payloads/refs/child.json', available: true }],
          });
        }
        if (referenceId === nestedReferenceId) {
          return jsonResponse(route, 200, { available: true, logicalPath: 'templates/checkout/payloads/refs/child.json', format: 'json', text: '{"ok":true}', fileReferences: [] });
        }
      }
      if (method === 'GET' && (path === 'packages/payments/configuration' || path === 'configuration')) {
        return jsonResponse(route, 200, {
          state: 'ready', globals: {}, sections: [],
          environments: [{ name: 'staging', state: 'active', default: true }, { name: 'production', state: 'active' }],
        });
      }
      if (method === 'GET' && (path === 'packages/payments/configuration/compare' || path === 'configuration/compare')) {
        return jsonResponse(route, 200, {
          state: 'ready', leftEnvironment: 'staging', rightEnvironment: 'production',
          fields: [{ path: 'database.password', left: { state: 'hidden' }, right: { state: 'missing' }, change: 'changed' }],
        });
      }
      if (method === 'GET' && (path === 'packages/payments/configuration/files' || path === 'configuration/files')) {
        const requested = url.searchParams.get('path') || '';
        const items = requested === '' ? [
          { name: 'config', path: 'config', kind: 'directory' },
        ] : requested === 'config' ? [
          { name: 'application.yaml', path: 'config/application.yaml', kind: 'file', format: 'yaml', sourceAvailable: true, sizeBytes: 248 },
          { name: 'restricted.yaml', path: 'config/restricted.yaml', kind: 'file', format: 'yaml', sourceAvailable: false, reason: 'visibility-policy' },
        ] : [];
        return jsonResponse(route, 200, { path: requested, items, total: items.length, nextOffset: null, truncated: false });
      }
      if (method === 'GET' && (path === 'packages/payments/configuration/source' || path === 'configuration/source')) {
        assert.equal(url.searchParams.get('path'), 'config/application.yaml');
        return jsonResponse(route, 200, {
          available: true, logicalPath: 'config/application.yaml', format: 'yaml',
          text: 'server:\n  environment: dev\n# descriptor: testdata/accounts.yaml\n',
        });
      }
      if (method === 'GET' && (path === 'packages/payments/configuration/effective' || path === 'configuration/effective')) {
        const section = url.searchParams.get('section') || 'globals';
        const entries = section === 'testdata' ? [{
          id: 'accounts', source: 'testdata/accounts.yaml', generated: false,
          fields: {
            recordCount: { state: 'visible', value: 3 },
            'selection.strategy': { state: 'visible', value: 'roundRobin' },
            'selection.exhaustion': { state: 'visible', value: 'cycle' },
          },
          references: [{ type: 'template', resourceId: 'checkout', resolution: 'resolved' }],
        }] : [];
        return jsonResponse(route, 200, {
          state: 'ready', environment: 'staging', section,
          globals: { baseDir: { state: 'visible', value: 'config' } },
          sections: [{ id: 'testdata', title: 'Testdata', entries: [
            ...entries,
            { id: 'generatedAccounts', source: 'testdata/generated.yaml', generated: true, fields: {
              generated: { state: 'visible', value: true },
              recordCount: { state: 'visible', value: 120 },
              'selection.strategy': { state: 'visible', value: 'sequential' },
            } },
          ] }],
          nextOffset: null,
        });
      }
      const testdataRecordsMatch = path.match(/^packages\/payments\/configuration\/testdata\/(accounts|generatedAccounts)\/records$/);
      if (method === 'GET' && testdataRecordsMatch) {
        if (testdataRecordsMatch[1] === 'generatedAccounts') {
          const previewGenerated = url.searchParams.get('previewGenerated') === 'true';
          generatedPreviewCalls.push(previewGenerated);
          return jsonResponse(route, 200, {
            descriptorId: 'generatedAccounts', environment: 'staging', generated: true,
            previewGenerated, previewRequired: !previewGenerated, state: previewGenerated ? 'ready' : 'preview-required',
            offset: 0, limit: 50, total: 120, recordCount: 120, redactionEnabled: true,
            generation: { generate: { seq: { from: 1000, to: 1119, format: '%06d' } }, record: { id: 'A-%{seq}' } },
            records: previewGenerated ? [{ index: 0, sequence: 1000, value: { id: 'A-001000' } }] : [],
          });
        }
        return jsonResponse(route, 200, {
          descriptorId: 'accounts', environment: 'staging', state: 'ready', offset: 0, limit: 50,
          total: 1, nextOffset: null, redactionEnabled: true,
          records: [{ index: 0, sequence: 1, value: { id: 'account-001', tier: 'standard' } }],
        });
      }
      if (method === 'GET' && path === 'packages/payments/load-policy') {
        const templateId = url.searchParams.get('templateId');
        const quickSmoke = templateId === 'bundled:quick-smoke';
        return jsonResponse(route, 200, {
          model: url.searchParams.get('model') || 'virtualUsers',
          policy: {
            schemaVersion: 'att-load/v1.6',
            load: quickSmoke ? { users: 2, duration: '10s' } : { users: 1, duration: '30s' },
            execution: quickSmoke ? { thinkTime: '250ms' } : {},
            thresholds: {}, evidence: quickSmoke ? { mode: 'failures' } : {},
          },
          templates: [
            { id: 'load/load.arrivalrate.yaml', name: 'load.arrivalrate.yaml', model: 'arrivalRate', bundled: false },
            { id: 'bundled:quick-smoke', name: 'Quick Smoke · 2 users · 10s', model: 'virtualUsers', bundled: true },
          ],
          redacted: false,
        });
      }
      if (method === 'GET' && path === 'packages/payments/resources/template/checkout/quick-load-form') {
        return jsonResponse(route, 200, {
          target: { type: 'template', id: 'checkout' },
          model: url.searchParams.get('model') || 'virtualUsers',
          input: { inputs: { account: 'A-1' }, vars: {} }, redacted: false,
        });
      }
      if (method === 'POST' && path === 'drafts/load') {
        submittedLoadScenario = request.postDataJSON().scenario;
        return jsonResponse(route, 201, {
          draftId: 'LOAD-DRAFT-QUICK-99', packageId: 'payments',
          preview: { status: 'PASS' }, previewYaml: 'schemaVersion: att-load/v1.6\nload:\n  users: 1\n  duration: 10s\nexecution:\n  thinkTime: 250ms\nevidence:\n  mode: failures\nworkloads:\n  - id: quick-load\n    load:\n      users: 2\n    target:\n      type: template\n      id: checkout\n    inputs:\n      account: A-1\n',
          redacted: false, diagnostics: [], expiresAt: new Date(Date.now() + 60_000).toISOString(),
        });
      }
      if (method === 'GET' && path === 'packages/payments/resources/template/checkout/debug-form') {
        return jsonResponse(route, 200, {
          target: { type: 'template', id: 'checkout' },
          input: { inputs: { credential: { $attDebugKeepDefault: '/inputs/credential' } }, vars: { retries: 2 } },
          redacted: true,
        });
      }
      if (method === 'POST' && path === 'drafts/debug') {
        const body = request.postDataJSON();
        submittedInput = body.input;
        return jsonResponse(route, 200, { draftId: 'draft-99', previewYaml: 'target: checkout\nstatus: valid', expiresAt: new Date(Date.now() + 60_000).toISOString(), redacted: true });
      }
      if (method === 'POST' && path === 'drafts/run') {
        submittedInput = request.postDataJSON().input;
        return jsonResponse(route, 201, { draftId: 'draft-99', executionType: 'run', previewYaml: 'target: checkout\nstatus: valid', expiresAt: new Date(Date.now() + 60_000).toISOString() });
      }
      if (method === 'POST' && path === 'jobs/debug') {
        const body = request.postDataJSON();
        submittedPrincipal = body.principal;
        return jsonResponse(route, 202, { jobId: 'job-debug-99' });
      }
      return jsonResponse(route, 404, { error: { code: 'FIXTURE_NOT_FOUND', summary: `No Explorer fixture route: ${method} ${path}` } });
    });

    const base = `${fixture.origin}/att/ui/`;
    await page.goto(`${base}#/packages/payments/resources/template/checkout`);
    await page.getByRole('heading', { name: 'Resource details', exact: true }).waitFor();
    await page.getByRole('heading', { name: 'checkout' }).waitFor();
    await page.getByRole('tab', { name: 'Definition' }).focus();
    await page.keyboard.press('ArrowRight');
    await page.getByRole('tab', { name: 'Source' }).waitFor({ state: 'visible' });
    await page.waitForFunction(() => document.querySelector('[role="tab"][data-tab="source"]')?.getAttribute('aria-selected') === 'true');
    await page.locator('.att-explorer-source').getByText('Code viewer ready.').waitFor();
    assert.equal(await page.locator('.att-explorer-source .att-code-host .cm-line').count() <= 80, true, 'thousands of CodeMirror lines should remain virtualized');
    await page.locator('.att-explorer-source .att-code-goto input').fill('5000');
    await page.locator('.att-explorer-source .att-code-editor').getByRole('button', { name: 'Go' }).click();
    await page.locator('.att-explorer-source .att-code-host .cm-line').filter({ hasText: 'step-5000: safe' }).waitFor();
    assert.equal(await page.locator('.att-explorer-source .att-code-host img').count(), 0, 'source is rendered as text, not HTML');
    await page.locator('.att-explorer-source .att-code-editor').getByRole('button', { name: 'Copy' }).click();
    await page.getByText('Copied displayed code.').waitFor();
    assert.equal((await page.evaluate(() => navigator.clipboard.readText())).includes('step-5000: safe'), true);
    // A completed revisit must hit session memory while still verifying capabilities.
    const sourceBeforeRevisit = sourceCalls;
    await page.getByRole('tab', { name: 'Definition', exact: true }).click();
    const capabilityResponse = page.waitForResponse(response => response.url().endsWith('/api/v1/auth/capabilities'));
    await page.getByRole('tab', { name: 'Source', exact: true }).click();
    await capabilityResponse;
    await page.getByRole('button', { name: 'Refresh source', exact: true }).waitFor();
    assert.equal(sourceCalls, sourceBeforeRevisit, 'revisiting fresh source reuses the verified session cache');
    assert.ok(capabilitiesCalls > 0, 'inspection reads verify the live capability token');

    const unrelatedReads = { listCalls, detailCalls };
    for (let refresh = 0; refresh < 2; refresh++) {
      const expectedCalls = sourceCalls + 1;
      // Register before clicking and await the actual GET response, then the idle UI.
      const response = page.waitForResponse(response => response.url().endsWith('/resources/template/checkout/source') && response.request().method() === 'GET');
      await page.getByRole('button', { name: 'Refresh source', exact: true }).click();
      await (await response).finished();
      await page.getByRole('button', { name: 'Refresh source', exact: true }).waitFor();
      assert.equal(sourceCalls, expectedCalls, 'each deliberate refresh performs exactly one fresh source read');
    }
    assert.deepEqual({ listCalls, detailCalls }, unrelatedReads, 'source refresh preserves unrelated inspection responses');

    // Simulate another tab changing the account: the next live capability check
    // must replace the old session and fetch the same source for the new identity.
    await page.getByRole('tab', { name: 'Definition', exact: true }).click();
    identityVersion = 2;
    const identityResponse = page.waitForResponse(response => response.url().endsWith('/resources/template/checkout/source'));
    await page.getByRole('tab', { name: 'Source', exact: true }).click();
    await (await identityResponse).finished();
    await page.getByRole('button', { name: 'Refresh source', exact: true }).waitFor();
    await page.locator('.att-explorer-source .att-code-goto input').fill('1');
    await page.locator('.att-explorer-source .att-code-editor').getByRole('button', { name: 'Go' }).click();
    await page.locator('.att-explorer-source .cm-line').filter({ hasText: 'identity-2' }).waitFor();
    assert.equal(await page.locator('.att-explorer-source').innerText().then(text => text.includes('identity-1')), false, 'identity changes discard the previous source projection');

    await page.locator('.att-explorer-source .att-code-goto input').fill('5001');
    await page.locator('.att-explorer-source .att-code-editor').getByRole('button', { name: 'Go' }).click();
    const firstReference = page.getByRole('link', { name: 'Open package file ./payloads/request.xml' });
    await firstReference.waitFor();
    await firstReference.click();
    await page.getByRole('heading', { name: 'Configuration', level: 1 }).waitFor();
    assert.equal(await page.locator('.att-config-source-view .att-code-language').innerText(), 'XML');
    await page.locator('.att-config-source-view .att-code-status').getByText('Code viewer ready.').waitFor();
    const nestedReference = page.getByRole('link', { name: 'Open package file ./refs/child.json' });
    await nestedReference.waitFor();
    await nestedReference.click();
    await page.getByText('{"ok":true}', { exact: true }).waitFor();
    assert.deepEqual(referenceRequests, [
      { referenceId: resourceReferenceId, origin: 'templates/checkout/template.yaml' },
      { referenceId: nestedReferenceId, origin: 'templates/checkout/payloads/request.xml' },
    ], 'linked-file clicks use the server-issued opaque reference and the current origin');
    await page.goBack();
    await page.getByRole('link', { name: 'Open package file ./refs/child.json' }).waitFor();
    await page.goBack();
    await page.getByRole('heading', { name: 'Resource details', exact: true }).waitFor();
    assert.match(page.url(), /#\/packages\/payments\/resources\/template\/checkout(?:\?detail=source&id=checkout)?$/);

    await page.goto(`${base}#/packages/payments/resources/template/empty`);
    await page.getByRole('heading', { name: 'empty' }).waitFor();
    await page.getByRole('tab', { name: 'Definition' }).focus();
    await page.keyboard.press('ArrowRight');
    await page.getByText('Safe source is empty.').waitFor();
    assert.equal(await page.locator('.att-explorer-source .att-code-editor').count(), 0, 'an empty source does not mount a copy toolbar');

    await page.goto(`${base}#/configuration/payments?tab=compare`);
    await page.getByRole('heading', { name: 'Configuration', level: 1 }).waitFor();
    await page.getByRole('table', { name: 'Configuration profile comparison' }).waitFor();
    const comparisonText = await page.getByRole('table', { name: 'Configuration profile comparison' }).innerText();
    assert.equal(comparisonText.includes('hidden') && comparisonText.includes('not declared'), true, 'configuration comparison distinguishes hidden from missing');
    await page.goto(`${base}#/packages/payments/resources/template/checkout`);
    await page.getByRole('heading', { name: 'checkout' }).waitFor();
    await page.getByRole('button', { name: 'Run', exact: true }).click();
    await page.getByRole('heading', { name: 'Run', exact: true }).waitFor();
    assert.match(page.url(), /#\/run\/payments\/template\/checkout\?(?:environment=[^&]+&)?id=checkout$/);
    await page.getByText("Typed fields use the selected target's safe Debug defaults. Advanced JSON remains available for nested inputs.").waitFor();
    await page.getByRole('button', { name: 'Validate', exact: true }).click();
    try { await page.getByRole('heading', { name: 'Review', exact: true }).waitFor({ timeout: 10000 }); }
    catch (error) {
      console.error(JSON.stringify({ url: page.url(), body: await page.locator('body').innerText(), submittedInput }));
      throw error;
    }
    assert.deepEqual(submittedInput, {
      inputs: { credential: { $attDebugKeepDefault: '/inputs/credential' } }, vars: { retries: 2 },
    }, 'Explorer Run uses the safe typed sidecar defaults and preserves hidden package values');
    assert.equal(await page.getByRole('button', { name: 'Schedule…', exact: true }).isDisabled(), false);
    await page.getByRole('button', { name: 'Schedule…' }).click();
    await page.waitForURL(url => url.hash.startsWith('#/schedules/new'));
    await page.getByRole('heading', { name: 'New Schedule', exact: true }).waitFor();
    await page.getByText('Schedule form loaded from the validated RUN draft. Choose a recurrence and review the Server next-run preview.').waitFor();
    assert.equal(await page.locator('#schedule-debug-target').inputValue(), 'template · checkout');
    await page.getByText('Review the validated RUN definition snapshot').click();
    assert.match(await page.locator('.att-schedule-snapshot-review pre').textContent(), /target: checkout/);
    await page.getByText(/^Next run:/).waitFor();
    await page.getByRole('button', { name: 'Save schedule', exact: true }).click();
    try { await page.locator('#schedules-title').waitFor({ timeout: 10000 }); }
    catch (error) {
      console.error(JSON.stringify({ url: page.url(), body: await page.locator('body').innerText(), submittedScheduleInput }));
      throw error;
    }
    await page.getByText(/Saved .*\(ID: schedule-inline-99\)/).waitFor();
    await page.getByRole('button', { name: 'View schedule history', exact: true }).waitFor();
    assert.match(page.url(), /#\/schedules$/);
    assert.equal(submittedScheduleInput.draftId, 'draft-99');
    assert.equal(submittedScheduleInput.executionType, 'run');
    assert.equal(Object.hasOwn(submittedScheduleInput, 'scenario'), false);
    assert.equal(submittedPrincipal, undefined, 'saving the schedule must not start a job');

    await page.goto(`${base}#/run/payments/template/checkout?environment=staging&id=checkout`);
    await page.getByRole('heading', { name: 'Run', exact: true }).waitFor();
    await page.getByLabel('Debug diagnostics', { exact: true }).check();
    await page.locator('[data-nav="jobs"]').click();
    const discardDialog = page.getByRole('dialog', { name: 'Discard your changes?' });
    await discardDialog.waitFor({ state: 'visible' });
    await discardDialog.getByRole('button', { name: 'Discard changes and continue' }).click();
    await page.getByRole('heading', { name: 'Jobs', exact: true }).waitFor();
    assert.match(page.url(), /#\/jobs$/);

    await page.setViewportSize({ width: 768, height: 1024 });
    await page.goto(`${base}#/configuration?tab=source`);
    await page.getByRole('heading', { name: 'Configuration', level: 1 }).waitFor();
    assert.equal(await page.getByRole('combobox', { name: 'Package', exact: true }).count(), 0);
    assert.equal(await page.getByText('Configuration is scoped to a registered package.', { exact: true }).count(), 0);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ animations: 'disabled', fullPage: true, path: path.join(process.env.ATT_UI_SCREENSHOT_DIR || '/tmp', 'att-shared-configuration-mobile.png') });
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.getByRole('heading', { name: 'Configuration', level: 1 }).waitFor();
    const configurationTree = page.getByRole('tree', { name: 'Configuration file tree' });
    const configurationFolder = configurationTree.locator('[role="treeitem"][data-path="config"]');
    await configurationFolder.focus();
    await page.keyboard.press('ArrowRight');
    const applicationFile = configurationTree.locator('[role="treeitem"][data-path="config/application.yaml"]');
    await applicationFile.waitFor({ state: 'visible' });
    await page.keyboard.press('ArrowRight');
    assert.equal(await page.evaluate(() => (document.activeElement instanceof HTMLElement ? document.activeElement.dataset.path : undefined)), 'config/application.yaml');
    await page.keyboard.press('ArrowLeft');
    assert.equal(await page.evaluate(() => (document.activeElement instanceof HTMLElement ? document.activeElement.dataset.path : undefined)), 'config');
    await page.keyboard.press('ArrowLeft');
    assert.equal(await configurationFolder.getAttribute('aria-expanded'), 'false');
    assert.equal(await applicationFile.count(), 0, 'ArrowLeft collapses the open configuration directory');
    await configurationFolder.focus();
    await page.keyboard.press('ArrowRight');
    await applicationFile.waitFor({ state: 'visible' });
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('Enter');
    await page.getByText('Safe read-only source').waitFor();
    await page.getByText('environment: dev', { exact: false }).waitFor();
    assert.equal(await page.locator('.att-config-source-view img').count(), 0, 'configuration source is rendered as text');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth), true, 'the Configuration workspace has no horizontal overflow at tablet width');
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.getByRole('tab', { name: 'Testdata' }).click();
    await page.getByRole('combobox', { name: 'Testdata package', exact: true }).selectOption('payments');
    await page.getByRole('table').filter({ hasText: 'account-001' }).waitFor();
    assert.equal(await page.locator('.att-testdata-table-scroll').last().innerText().then(text => text.includes('account-001')), true);
    await page.getByRole('button', { name: /generatedAccounts/ }).click();
    await page.getByText('Generated records are not loaded automatically.').waitFor();
    assert.equal(await page.getByText('A-001000').count(), 0, 'generated records require an explicit bounded preview');
    await page.getByRole('button', { name: 'Preview first 50 generated records' }).click();
    await page.getByText('A-001000', { exact: true }).last().waitFor();
    assert.deepEqual(generatedPreviewCalls, [false, true]);

    await page.goto(`${base}#/quick-load/payments/template/checkout`);
    await page.getByRole('heading', { name: 'Quick Load', level: 1 }).waitFor();
    await page.getByText(/selected model’s package policy or Server fallback is loaded|Safe model-specific policy defaults loaded/).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Schedule…' }).isDisabled(), true, 'scheduling requires a validated draft');
    await page.locator('select[aria-label="Load template or preset"] option[value="load/load.arrivalrate.yaml"]').waitFor({ state: 'attached' });
    await page.getByRole('combobox', { name: 'Load template or preset' }).selectOption('bundled:quick-smoke');
    await page.getByRole('button', { name: 'Validate' }).click();
    await page.getByText('The Server accepted this draft for review.').waitFor();
    assert.deepEqual(submittedLoadScenario.workloads[0], {
      id: 'quick-load', load: { users: 2 }, target: { type: 'template', id: 'checkout' },
      inputs: { account: 'A-1' },
    });
    assert.equal(submittedLoadScenario.load.duration, '10s');
    assert.equal(submittedLoadScenario.execution.thinkTime, '250ms');
    assert.equal(await page.getByRole('button', { name: 'Run Now' }).isDisabled(), false);
    assert.equal(await page.getByRole('button', { name: 'Schedule…' }).isDisabled(), false, 'validated draft enables scheduling');
    const quickLoadUrl = page.url();
    await page.getByRole('button', { name: 'Schedule…' }).click();
    await page.waitForURL(url => url.hash.startsWith('#/schedules/new'));
    await page.getByRole('heading', { name: 'New Schedule', exact: true }).waitFor();
    await page.getByText('Schedule form loaded from the validated Load draft.').waitFor();
    assert.equal(await page.locator('#schedule-scenario').inputValue(), 'Validated Load snapshot');
    await page.getByText('Review the validated Load scenario snapshot').click();
    const scheduledSnapshot = await page.locator('.att-schedule-snapshot-review pre').textContent();
    assert.ok(scheduledSnapshot.includes('account: A-1') && scheduledSnapshot.includes('users: 2'), 'the schedule page should show the validated target inputs and load values');
    await page.route(`${fixture.origin}/att/axe-core.js`, route => route.fulfill({
      status: 200, contentType: 'text/javascript', path: require.resolve('axe-core/axe.min.js'),
    }));
    await page.addScriptTag({ url: `${fixture.origin}/att/axe-core.js` });
    await assertAxe(page, 'The Load schedule page should pass WCAG 2.2 AA checks');
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.waitForURL(`**/#${quickLoadUrl.split('#')[1]}`);
    await page.getByRole('heading', { name: 'Quick Load', level: 1 }).waitFor();
    assert.equal(page.url(), quickLoadUrl, 'Cancel returns to the originating Quick Load route');

    await page.getByRole('combobox', { name: 'Load template or preset' }).selectOption('bundled:quick-smoke');
    await page.getByRole('button', { name: 'Validate' }).click();
    await page.getByText('The Server accepted this draft for review.').waitFor();
    await page.getByRole('button', { name: 'Schedule…' }).click();
    await page.waitForURL(url => url.hash.startsWith('#/schedules/new'));
    await page.getByRole('heading', { name: 'New Schedule', exact: true }).waitFor();
    await page.getByText(/^Next run:/).waitFor();
    await page.getByRole('button', { name: 'Save schedule', exact: true }).click();
    await page.locator('#schedules-title').waitFor();
    await page.getByText(/Saved .*\(ID: schedule-inline-99\)/).waitFor();
    await page.getByRole('button', { name: 'View schedule history', exact: true }).waitFor();
    assert.match(page.url(), /#\/schedules$/);
    assert.equal(submittedScheduleInput.draftId, 'LOAD-DRAFT-QUICK-99');
    assert.equal(submittedScheduleInput.executionType, 'load');
    assert.equal(Object.hasOwn(submittedScheduleInput, 'scenario'), false, 'scheduling submits the validated draft ID, not a scenario path');
    await page.goto(quickLoadUrl);
    await page.getByRole('heading', { name: 'Quick Load', level: 1 }).waitFor();
    assert.deepEqual(pageErrors, [], 'Configuration and Quick Load should not produce uncaught browser errors');

    authFailure = true;
    await page.goto(`${base}#/packages/payments/resources/template/checkout`);
    await page.getByRole('heading', { name: 'checkout' }).waitFor();
    await page.getByRole('tab', { name: 'Definition' }).focus();
    await page.keyboard.press('ArrowRight');
    await page.getByText('Your session has expired. Sign in again, then refresh safe source.').waitFor();
    assert.equal(await page.locator('body').innerText().then(text => text.includes('canary-401-secret')), false, 'auth errors must not expose response bodies');
    assert.deepEqual(pageErrors, [], 'Explorer interactions should not produce uncaught browser errors');
    console.log('ATT Explorer Playwright smoke passed: resource source/redaction, Configuration files and literal/generated Testdata, Quick Load preset validation, immutable draft scheduling, dirty navigation, anonymous job, and 401 handling.');
  } finally {
    if (browser) await browser.close();
    await stopFixture(fixture);
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
