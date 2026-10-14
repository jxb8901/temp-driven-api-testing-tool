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
  let submittedInput;
  let submittedPrincipal;
  let submittedLoadScenario;
  let submittedScheduleInput;
  let createdSchedule;
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

      if (authFailure && path.endsWith('/source')) {
        return jsonResponse(route, 401, { error: { code: 'UNAUTHORIZED', summary: 'authorization header rejected: canary-401-secret' } });
      }
      if (method === 'GET' && path === 'version') {
        return jsonResponse(route, 200, { apiVersion: '1', version: '4.2.0', inlineLoadEnabled: true, loadSchedulingEnabled: true });
      }
      if (method === 'GET' && path === 'load-schedules') {
        return jsonResponse(route, 200, { items: createdSchedule ? [createdSchedule] : [], total: createdSchedule ? 1 : 0 });
      }
      if (method === 'POST' && path === 'load-schedules/preview') {
        return jsonResponse(route, 200, { ...request.postDataJSON(), nextRunAt: '2030-01-01T00:00:00.000Z' });
      }
      if (method === 'POST' && path === 'load-schedules') {
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
      if (method === 'GET' && path === 'jobs') return jsonResponse(route, 200, { items: [], total: 0 });
      if (method === 'GET' && path === 'jobs/job-debug-99') {
        return jsonResponse(route, 200, { jobId: 'job-debug-99', packageId: 'payments', status: 'QUEUED', command: 'DEBUG' });
      }
      if (method === 'GET' && path === 'packages/payments/resources') {
        return jsonResponse(route, 200, {
          items: [
            { type: 'template', resourceId: 'checkout', logicalId: 'checkout', name: 'checkout', state: 'ready', sourceAvailable: true },
            { type: 'template', resourceId: 'empty', logicalId: 'empty', name: 'empty', state: 'ready', sourceAvailable: true },
          ], total: 2, nextCursor: null,
        });
      }
      const detailMatch = path.match(/^packages\/payments\/resources\/template\/(checkout|empty)$/);
      if (method === 'GET' && detailMatch) {
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
          text: id === 'empty' ? '' : Array.from({ length: 5000 }, (_, index) => `step-${index + 1}: ${index === 0 ? '<img src=x onerror=alert(1)>' : 'safe'}`).join('\n'),
        });
      }
      if (method === 'GET' && path === 'packages/payments/configuration') {
        return jsonResponse(route, 200, {
          state: 'ready', globals: {}, sections: [],
          environments: [{ name: 'staging', state: 'active', default: true }, { name: 'production', state: 'active' }],
        });
      }
      if (method === 'GET' && path === 'packages/payments/configuration/compare') {
        return jsonResponse(route, 200, {
          state: 'ready', leftEnvironment: 'staging', rightEnvironment: 'production',
          fields: [{ path: 'database.password', left: { state: 'hidden' }, right: { state: 'missing' }, change: 'changed' }],
        });
      }
      if (method === 'GET' && path === 'packages/payments/configuration/files') {
        const requested = url.searchParams.get('path') || '';
        const items = requested === '' ? [
          { name: 'config', path: 'config', kind: 'directory' },
        ] : requested === 'config' ? [
          { name: 'application.yaml', path: 'config/application.yaml', kind: 'file', format: 'yaml', sourceAvailable: true, sizeBytes: 248 },
          { name: 'restricted.yaml', path: 'config/restricted.yaml', kind: 'file', format: 'yaml', sourceAvailable: false, reason: 'visibility-policy' },
          { name: 'testdata', path: 'config/testdata', kind: 'directory' },
        ] : requested === 'config/testdata' ? [
          { name: 'accounts.yaml', path: 'config/testdata/accounts.yaml', kind: 'file', format: 'yaml', sourceAvailable: true, sizeBytes: 156 },
        ] : [];
        return jsonResponse(route, 200, { path: requested, items, total: items.length, nextOffset: null, truncated: false });
      }
      if (method === 'GET' && path === 'packages/payments/configuration/source') {
        assert.equal(url.searchParams.get('path'), 'config/application.yaml');
        return jsonResponse(route, 200, {
          available: true, logicalPath: 'config/application.yaml', format: 'yaml',
          text: 'server:\n  environment: dev\n# descriptor: config/testdata/accounts.yaml\n',
        });
      }
      if (method === 'GET' && path === 'packages/payments/configuration/effective') {
        const section = url.searchParams.get('section') || 'globals';
        const entries = section === 'testdata' ? [{
          id: 'accounts', source: 'config/testdata/accounts.yaml', generated: false,
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
            { id: 'generatedAccounts', source: 'config/testdata/generated.yaml', generated: true, fields: {
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
        return jsonResponse(route, 200, {
          model: url.searchParams.get('model') || 'virtualUsers',
          policy: { schemaVersion: 'att-load/v1.6', load: { users: 1, duration: '30s' }, execution: {}, thresholds: {}, evidence: {} },
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
      if (method === 'POST' && path === 'jobs/debug') {
        const body = request.postDataJSON();
        submittedPrincipal = body.principal;
        return jsonResponse(route, 202, { jobId: 'job-debug-99' });
      }
      return jsonResponse(route, 404, { error: { code: 'FIXTURE_NOT_FOUND', summary: `No Explorer fixture route: ${method} ${path}` } });
    });

    const base = `${fixture.origin}/att/ui/`;
    await page.goto(`${base}#/packages/payments/resources/template/checkout`);
    await page.getByRole('heading', { name: 'Package Explorer' }).waitFor();
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
    await page.getByRole('button', { name: 'Refresh source' }).click();
    await page.getByRole('button', { name: 'Refresh source' }).waitFor();
    await page.getByRole('button', { name: 'Refresh source' }).click();
    await page.getByRole('button', { name: 'Refresh source' }).waitFor();
    assert.equal(sourceCalls >= 3, true, 'source can be refreshed repeatedly');

    await page.goto(`${base}#/packages/payments/resources/template/empty`);
    await page.getByRole('heading', { name: 'empty' }).waitFor();
    await page.getByRole('tab', { name: 'Definition' }).focus();
    await page.keyboard.press('ArrowRight');
    await page.getByText('Safe source is empty.').waitFor();
    assert.equal(await page.locator('.att-explorer-source .att-code-editor').count(), 0, 'an empty source does not mount a copy toolbar');

    await page.goto(`${base}#/configuration/payments?tab=compare`);
    await page.getByRole('heading', { name: 'Configuration', level: 1 }).waitFor();
    await page.getByRole('table', { name: 'Configuration profile comparison' }).waitFor();
    assert.equal(await page.getByRole('table', { name: 'Configuration profile comparison' }).innerText().then(text => text.includes('hidden') && text.includes('missing')), true, 'configuration comparison distinguishes hidden from missing');
    await page.goto(`${base}#/packages/payments/resources/template/checkout`);
    await page.getByRole('heading', { name: 'checkout' }).waitFor();
    await page.getByRole('button', { name: 'Debug this target' }).click();
    await page.getByText(/Hidden package-authored values are preserved/).waitFor();
    const keepDefault = page.getByRole('checkbox', { name: /Keep hidden package default/ });
    await keepDefault.waitFor();
    assert.equal(await keepDefault.isChecked(), true, 'redacted defaults remain untouched until the user opts out');
    assert.equal(await page.locator('body').innerText().then(text => text.includes('real-secret')), false, 'secret defaults are not exposed');
    await keepDefault.uncheck();
    await page.getByRole('combobox', { name: 'credential value type' }).selectOption('string');
    await page.getByRole('textbox', { name: 'credential value' }).fill('explicit-override');

    await page.locator('[data-nav="jobs"]').click();
    const dialog = page.getByRole('dialog', { name: 'Discard your changes?' });
    await dialog.waitFor({ state: 'visible' });
    await dialog.getByRole('button', { name: 'Stay on this page' }).click();
    await dialog.waitFor({ state: 'hidden' });
    assert.match(page.url(), /resources\/template\/checkout$/);

    await page.getByRole('button', { name: 'Reset defaults' }).click();
    await keepDefault.waitFor();
    assert.equal(await keepDefault.isChecked(), true, 'reset restores the redacted marker');
    await keepDefault.uncheck();
    await page.getByRole('combobox', { name: 'credential value type' }).selectOption('string');
    const credentialValue = page.getByRole('textbox', { name: 'credential value' });
    await credentialValue.fill('submitted-override');
    assert.equal(await credentialValue.inputValue(), 'submitted-override', 'typed sidecar override stays visible before submitting');
    await page.locator('.att-sidecar-advanced summary').click();
    await page.waitForFunction(() => document.querySelector('.att-sidecar-advanced .cm-content')?.textContent?.includes('submitted-override'));
    await page.getByLabel('Anonymous job label (optional)').fill('anonymous-e2e');
    page.once('dialog', dialogEvent => dialogEvent.accept());
    await page.getByRole('button', { name: 'Start Debug' }).click();
    await page.getByRole('heading', { name: 'Job job-debug-99' }).waitFor();
    assert.deepEqual(submittedInput, { inputs: { credential: 'submitted-override' }, vars: { retries: 2 } });
    assert.equal(submittedPrincipal, 'anonymous-e2e');
    assert.equal(authorizationHeaderSeen, false, 'the anonymous flow does not add an authorization header');
    assert.equal(JSON.stringify(submittedInput).includes('real-secret'), false);

    await page.goBack();
    await page.getByRole('heading', { name: 'checkout' }).waitFor();
    await page.getByRole('button', { name: 'Debug this target' }).click();
    await page.getByText(/Hidden package-authored values are preserved/).waitFor();
    await page.getByRole('checkbox', { name: /Keep hidden package default/ }).uncheck();
    await page.getByRole('combobox', { name: 'credential value type' }).selectOption('string');
    await page.getByRole('textbox', { name: 'credential value' }).fill('discard-me');
    await page.locator('[data-nav="jobs"]').click();
    const discardDialog = page.getByRole('dialog', { name: 'Discard your changes?' });
    await discardDialog.waitFor({ state: 'visible' });
    await discardDialog.getByRole('button', { name: 'Discard changes and continue' }).click();
    await page.getByRole('heading', { name: 'Jobs' }).waitFor();
    assert.match(page.url(), /#\/jobs$/);

    await page.goto(`${base}#/configuration/payments?tab=source&file=config%2Fapplication.yaml`);
    await page.getByRole('heading', { name: 'Configuration', level: 1 }).waitFor();
    await page.getByText('Safe read-only source').waitFor();
    await page.getByText('environment: dev', { exact: false }).waitFor();
    assert.equal(await page.locator('.att-config-source-view img').count(), 0, 'configuration source is rendered as text');
    await page.getByRole('tab', { name: 'Testdata' }).click();
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
    await page.getByRole('combobox', { name: 'Load template or preset' }).selectOption('quick-profile');
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
    let scheduleDialog = page.getByRole('dialog');
    await scheduleDialog.getByRole('heading', { name: 'Create schedule', level: 2 }).waitFor();
    await page.getByText('Schedule form loaded from the validated Load draft.').waitFor();
    assert.equal(page.url(), quickLoadUrl, 'Schedule should open without leaving the Quick Load route');
    assert.equal(await page.locator('#schedule-scenario').inputValue(), 'Validated Load snapshot');
    await scheduleDialog.getByText('Review the validated Load scenario snapshot').click();
    const scheduledSnapshot = await scheduleDialog.locator('.att-schedule-snapshot-review pre').textContent();
    assert.ok(scheduledSnapshot.includes('account: A-1') && scheduledSnapshot.includes('users: 2'), 'the dialog should show the validated target inputs and load values');
    await page.route(`${fixture.origin}/att/axe-core.js`, route => route.fulfill({
      status: 200, contentType: 'text/javascript', path: require.resolve('axe-core/axe.min.js'),
    }));
    await page.addScriptTag({ url: `${fixture.origin}/att/axe-core.js` });
    await assertAxe(page, 'The Load schedule dialog should pass WCAG 2.2 AA checks');
    await scheduleDialog.getByRole('button', { name: 'Cancel and return to Load' }).click();
    await scheduleDialog.waitFor({ state: 'detached' });
    assert.equal(page.url(), quickLoadUrl, 'Cancel should return to the same Quick Load route');
    assert.equal(await page.getByRole('button', { name: 'Schedule…' }).isDisabled(), false, 'the validated Load draft should remain available after cancel');

    await page.getByRole('button', { name: 'Schedule…' }).click();
    scheduleDialog = page.getByRole('dialog');
    await scheduleDialog.getByRole('heading', { name: 'Create schedule', level: 2 }).waitFor();
    await page.getByText(/^Next run:/).waitFor();
    await page.getByRole('button', { name: 'Save schedule' }).click();
    await page.getByText(/Saved Validated Load snapshot\. Next run:/).waitFor();
    assert.equal(submittedScheduleInput.draftId, 'LOAD-DRAFT-QUICK-99');
    assert.equal(Object.hasOwn(submittedScheduleInput, 'scenario'), false, 'inline scheduling submits the validated draft ID, not a scenario path');
    await scheduleDialog.getByRole('button', { name: 'Return to Load', exact: true }).click();
    await scheduleDialog.waitFor({ state: 'detached' });
    assert.equal(page.url(), quickLoadUrl, 'Saving should leave the user on the originating Quick Load route');
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
