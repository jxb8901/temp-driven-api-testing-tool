const assert = require('node:assert/strict');
const { chromium, firefox, webkit, launchBrowser, selectFixture, startFixture, stopFixture } = require('./browser-support.cjs');

const REGISTRY_REVISION = 'c'.repeat(64);
const SNAPSHOT_BEFORE = 'd'.repeat(64);
const SNAPSHOT_AFTER = 'e'.repeat(64);
const INITIAL_REVISION = 'a'.repeat(64);
const TEMPLATE_PATH = 'templates/checkout/template.yaml';
const FILE_PATH = 'templates/checkout/payloads/request.xml';
const INITIAL_SOURCE = 'template: checkout\nversion: A\npayload: &{./payloads/request.xml}\n';
const TESTDATA_PATH = 'testdata/uat-accounts.yaml';
const TESTDATA_INITIAL = 'schemaVersion: att-testdata/v1.0\nid: accounts\nrecords: [{id: U-1}]\n';

function send(route, body, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

function makeDraft(changeSetId, state, baseSourceRevision, operations) {
  return {
    changeSetId, domain: 'PACKAGE_SOURCE', scopeId: 'payments', state, baseSourceRevision,
    registryRevision: REGISTRY_REVISION, expiresAt: '2030-01-01T00:00:00Z',
    changedFiles: operations.map(operation => ({
      operation: operation.operation, path: operation.path, targetPath: operation.targetPath,
      oldBytes: operation.operation === 'create' ? 0 : 40, newBytes: operation.content?.length || 0,
      oldHash: operation.operation === 'create' ? null : '1'.repeat(64), newHash: '2'.repeat(64),
      contentDiff: ['- version: A', '+ version: B'],
      inboundReferences: operation.path === FILE_PATH ? [{ kind: 'TEMPLATE', relationship: 'FILE_REFERENCE',
        packageId: 'payments', canonicalPath: TEMPLATE_PATH, callSite: 'checkout.prepare', certainty: 'KNOWN', referenceCount: 1 }] : [],
    })),
  };
}

async function installApi(page, state, unexpected) {
  await page.route('**/api/v1/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.slice(url.pathname.indexOf('/api/v1/') + '/api/v1/'.length);
    const method = request.method();

    if (method === 'GET' && path === 'version') {
      return send(route, { apiVersion: '1', version: '4.4.1', authMode: 'container', authenticationRequired: false });
    }
    if (method === 'GET' && path === 'auth/capabilities') {
      return send(route, { capabilities: { operate: true, accountManage: false, systemSettingsWrite: false, configurationAdmin: false },
        operate: true, accountManage: false, systemSettingsWrite: false, configurationAdmin: false,
        inspectionCacheContext: 'f'.repeat(64) });
    }
    if (method === 'GET' && path === 'packages') {
      return send(route, { items: [{ packageId: 'payments', name: 'payments', state: 'ready' }], total: 1 });
    }
    if (method === 'GET' && path === 'packages/payments/resources') {
      return send(route, { items: [{ type: 'template', resourceId: 'checkout', logicalId: 'checkout',
        name: 'checkout', state: 'ready', sourceAvailable: true }], total: 1, nextCursor: null, diagnostics: [] });
    }
    if (method === 'GET' && path === 'packages/payments/resources/template/checkout') {
      return send(route, { resource: { type: 'template', resourceId: 'checkout', logicalId: 'checkout',
        name: 'checkout', state: 'ready', sourceAvailable: true, references: [], referencedBy: [] },
      definition: { template: 'checkout', actions: { prepare: { type: 'assign', expression: '&{./payloads/request.xml}' } } },
      actionOrder: ['prepare'] });
    }
    if (method === 'GET' && path === 'packages/payments/resources/template/checkout/source') {
      return send(route, { available: true, logicalPath: TEMPLATE_PATH, format: 'yaml', text: state.source, fileReferences: [] });
    }
    if (method === 'GET' && path === 'packages/payments/sut-metrics/catalog') {
      return send(route, { schemaVersion: 'att-package-sut-metrics-catalog/v1', packageId: 'payments',
        environment: url.searchParams.get('environment') || 'SIT', canEdit: true,
        metricSources: [], groups: [], profiles: [] });
    }
    if (method === 'GET' && path === 'packages/payments/authoring-source') {
      assert.equal(url.searchParams.get('type'), 'resource');
      assert.equal(url.searchParams.get('resourceType'), 'template');
      assert.equal(url.searchParams.get('resourceId'), 'checkout');
      state.sourceReads++;
      if (state.revoked) return send(route, { error: { code: 'FORBIDDEN', summary: 'Source owner permission was revoked.' } }, 403);
      const revision = state.revision;
      return send(route, { available: true, present: true, logicalPath: TEMPLATE_PATH, format: 'yaml',
        text: state.source, sourceEtag: `"sha256:${revision}"`, revision,
        baseSourceRevision: revision, registryRevision: REGISTRY_REVISION });
    }
    if (method === 'POST' && path === 'packages/payments/change-sets') {
      const input = request.postDataJSON();
      state.stageRequests.push(input);
      if (input.baseSourceRevision !== state.revision || input.registryRevision !== REGISTRY_REVISION) {
        return send(route, { error: { code: 'CONFLICT', summary: 'Fixture source revision changed.' } }, 409);
      }
      const changeSetId = `CS${state.nextDraft++}`;
      const draft = makeDraft(changeSetId, 'draft', state.revision, input.operations || []);
      state.drafts.set(changeSetId, draft);
      return send(route, draft, 201);
    }
    const actionMatch = path.match(/^packages\/payments\/change-sets\/([^/]+)\/(validate|review|publish|discard)$/);
    if (method === 'POST' && actionMatch) {
      const [, changeSetId, action] = actionMatch;
      const draft = state.drafts.get(changeSetId);
      if (!draft) return send(route, { error: { code: 'ATT-SERVER-NOT-FOUND', summary: 'Draft not found.' } }, 404);
      if (action === 'validate') { draft.state = 'validated'; return send(route, draft); }
      if (action === 'review') {
        assert.equal(draft.state, 'validated');
        draft.state = 'reviewed';
        return send(route, draft);
      }
      if (action === 'publish') {
        assert.equal(draft.state, 'reviewed');
        if (draft.baseSourceRevision !== state.revision) {
          return send(route, { error: { code: 'CONFLICT', summary: 'Fixture source revision changed.' } }, 409);
        }
        const templateOperation = draft.changedFiles.find(file => file.path === TEMPLATE_PATH);
        if (templateOperation?.content) state.source = templateOperation.content;
        state.revision = 'b'.repeat(64);
        state.publishedOperations = draft.changedFiles;
        state.published = true;
        state.drafts.delete(changeSetId);
        return send(route, { published: true, domain: 'PACKAGE_SOURCE', scopeId: 'payments', changeSetId,
          beforeRevision: INITIAL_REVISION, revision: state.revision, publishedRevision: state.revision,
          auditConfirmed: true });
      }
      if (action === 'discard') {
        state.drafts.delete(changeSetId);
        return send(route, { discarded: true, changeSetId });
      }
    }
    if (method === 'GET' && path === 'packages/payments/source-history') {
      return send(route, { schemaVersion: 'att-source-history/v1', domain: 'PACKAGE_SOURCE', scopeId: 'payments',
        items: state.published ? [{ transactionId: 'TX-PUBLISH-1', action: 'publish', outcome: 'published',
          createdAt: '2026-11-19T02:00:00Z', author: 'admin', beforeRevision: INITIAL_REVISION,
          afterRevision: state.revision, beforeSnapshotId: SNAPSHOT_BEFORE, afterSnapshotId: SNAPSHOT_AFTER,
          changedResources: [TEMPLATE_PATH, FILE_PATH], changedResourceCount: 2, revisionsAvailable: true }] : [],
        offset: 0, limit: 20, total: state.published ? 1 : 0, hasMore: false,
        currentSourceRevision: state.revision, registryRevision: REGISTRY_REVISION,
        retention: { retainedTransactions: state.published ? 1 : 0, transactionLimit: 100, state: 'available' } });
    }
    if (method === 'GET' && path === 'packages/payments/source-history/diff') {
      assert.equal(url.searchParams.get('beforeSnapshotId'), SNAPSHOT_BEFORE);
      assert.equal(url.searchParams.get('afterSnapshotId'), SNAPSHOT_AFTER);
      return send(route, { schemaVersion: 'att-source-history-diff/v1', domain: 'PACKAGE_SOURCE', scopeId: 'payments',
        beforeSnapshotId: SNAPSHOT_BEFORE, afterSnapshotId: SNAPSHOT_AFTER,
        items: [{ path: TEMPLATE_PATH, change: 'modified', beforeBytes: INITIAL_SOURCE.length,
          afterBytes: state.source.length, contentAvailable: true, beforeContent: INITIAL_SOURCE,
          afterContent: state.source, contentDiff: ['- version: A', '+ version: B'] }],
        offset: 0, limit: 100, total: 1, hasMore: false, noStore: true });
    }
    if (method === 'POST' && path === 'packages/payments/source-history/restore') {
      const input = request.postDataJSON();
      assert.equal(input.snapshotId, SNAPSHOT_AFTER);
      assert.equal(input.expectedSourceRevision, state.revision);
      assert.equal(input.registryRevision, REGISTRY_REVISION);
      state.restoreRequests.push(input);
      const draft = makeDraft('CS-RESTORE-1', 'draft', state.revision,
        [{ operation: 'replace', path: TEMPLATE_PATH, content: INITIAL_SOURCE }]);
      state.drafts.set(draft.changeSetId, draft);
      return send(route, draft, 201);
    }

    unexpected.push(`${method} ${path}`);
    return send(route, { error: { code: 'FIXTURE_NOT_FOUND', summary: `Unexpected request ${method} ${path}.` } }, 404);
  });
}

async function openSourcePage(context, fixture, state, unexpected) {
  await selectFixture(context, fixture.origin, 'loaded');
  await context.addInitScript(() => localStorage.setItem('att-ui-language', 'en'));
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  await installApi(page, state, unexpected);
  await page.goto(`${fixture.origin}/att/ui/#/packages/payments/resources/template/checkout`);
  await page.getByRole('heading', { name: 'checkout', exact: true }).waitFor();
  await page.getByRole('tab', { name: 'Source', exact: true }).click();
  await page.getByRole('region', { name: /template source/i }).waitFor();
  await page.getByRole('button', { name: 'Edit', exact: true }).waitFor();
  return { page, pageErrors };
}

async function runBrowser(browserType, fixture) {
  const browser = await launchBrowser(browserType);
  const state = { source: INITIAL_SOURCE, revision: INITIAL_REVISION, sourceReads: 0,
    nextDraft: 1, drafts: new Map(), stageRequests: [], restoreRequests: [], published: false,
    publishedOperations: [], revoked: false };
  const unexpected = [];
  const contextA = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const contextB = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  try {
    const a = await openSourcePage(contextA, fixture, state, unexpected);
    const b = await openSourcePage(contextB, fixture, state, unexpected);
    const editorA = a.page.locator('.cm-content[contenteditable="true"]');
    const editorB = b.page.locator('.cm-content[contenteditable="true"]');
    const sourceB = b.page.getByRole('region', { name: /template source/i });
    await a.page.getByRole('button', { name: 'Edit', exact: true }).click();
    await editorA.waitFor();
    const staleText = `${INITIAL_SOURCE}local_change: preserve-me\n`;
    await editorA.fill(staleText);

    await b.page.getByRole('button', { name: 'Edit', exact: true }).click();
    await editorB.waitFor();
    const publishedText = 'template: checkout\nversion: B\npayload: &{./payloads/request.xml}\n';
    await editorB.fill(publishedText);
    await b.page.getByRole('button', { name: 'Add current file to candidate', exact: true }).click();
    await b.page.getByRole('alertdialog').getByRole('button', { name: 'Add to candidate', exact: true }).click();
    const candidate = b.page.locator('.att-source-candidate');
    await candidate.getByRole('heading', { name: 'Candidate file changes', exact: true }).waitFor();
    await candidate.locator('fieldset input').fill(FILE_PATH);
    await candidate.locator('fieldset textarea').fill('<request version="B"/>\n');
    await b.page.getByRole('button', { name: 'Add related file', exact: true }).click();
    await b.page.getByRole('button', { name: 'Stage candidate (2 files)', exact: true }).click();
    await b.page.getByRole('heading', { name: 'Draft CS1', exact: true }).waitFor();
    assert.equal(state.stageRequests.length, 1);
    assert.equal(state.stageRequests[0].baseSourceRevision, INITIAL_REVISION);
    assert.deepEqual(state.stageRequests[0].operations.map(operation => operation.path), [TEMPLATE_PATH, FILE_PATH]);
    assert.equal(state.stageRequests[0].registryRevision, REGISTRY_REVISION);

    await sourceB.getByRole('button', { name: 'Validate', exact: true }).click();
    await sourceB.getByText('Draft validation completed.', { exact: true }).waitFor();
    await sourceB.getByRole('button', { name: 'Review changed files', exact: true }).click();
    await sourceB.getByText('Changed-file review completed.', { exact: true }).waitFor();
    await sourceB.getByRole('button', { name: 'Publish', exact: true }).click();
    await b.page.getByRole('alertdialog').getByRole('button', { name: 'Publish source', exact: true }).click();
    await sourceB.locator('.att-source-authoring-status').getByText('Package source published successfully.', { exact: true }).waitFor();
    assert.equal(state.published, true);
    assert.equal(state.publishedOperations.length, 2);

    // Simulate a browser page-show reauthorization after the other editor published.
    // The dirty local text remains visible, but the stale base cannot be staged.
    await a.page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow')));
    await a.page.getByText(/The Package source changed after these local edits began/).waitFor();
    assert.equal((await editorA.textContent()).replace(/\s+/g, ''), staleText.replace(/\s+/g, ''));
    assert.equal(await a.page.getByRole('button', { name: 'Add current file to candidate', exact: true }).isDisabled(), true);
    assert.equal(await a.page.getByRole('button', { name: 'Rebase', exact: true }).count(), 0);
    assert.match(await a.page.getByText('Published source', { exact: true }).textContent(), /Published source/);

    await sourceB.getByRole('button', { name: 'History / Restore', exact: true }).click();
    const history = b.page.getByRole('region', { name: 'Source history and restore', exact: true });
    await history.getByText('publish · published', { exact: false }).waitFor();
    await history.getByRole('button', { name: 'View changes', exact: true }).click();
    await history.getByRole('region', { name: 'Historical source comparison', exact: true }).getByText(TEMPLATE_PATH, { exact: true }).waitFor();
    await history.getByRole('button', { name: 'Stage restore as draft', exact: true }).click();
    await b.page.getByRole('alertdialog').getByRole('button', { name: 'Stage restore draft', exact: true }).click();
    await sourceB.getByRole('heading', { name: 'Draft CS-RESTORE-1', exact: true }).waitFor();
    assert.equal(state.restoreRequests.length, 1);
    assert.equal(await sourceB.getByRole('button', { name: 'Publish', exact: true }).isDisabled(), true,
      'a restore is a new draft and remains gated until fresh validation and review');

    state.revoked = true;
    await a.page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow')));
    await a.page.getByText(/Package source editing is unavailable for this identity/).waitFor();
    assert.equal(await a.page.locator('.cm-content[contenteditable="true"]').count(), 0,
      'revoked source access must remove the editor');
    assert.equal((await a.page.locator('body').innerText()).includes('preserve-me'), false,
      'revoked source access must clear local source bytes');

    assert.deepEqual(unexpected, [], `unexpected API routes for ${browserType.name()}`);
    assert.deepEqual(a.pageErrors, [], `page errors for stale editor in ${browserType.name()}`);
    assert.deepEqual(b.pageErrors, [], `page errors for publisher in ${browserType.name()}`);
    console.log(`Source Authoring browser acceptance passed (${browserType.name()}): shared-revision two-editor publish, two-file candidate, validate/review/publish, stale-editor preservation and safe failure, history diff/restore gating, editor revocation clearing.`);
  } finally {
    await contextA.close();
    await contextB.close();
    await browser.close();
  }
}

async function installTestdataApi(page, state, unexpected) {
  await page.route('**/api/v1/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.slice(url.pathname.indexOf('/api/v1/') + '/api/v1/'.length);
    const method = request.method();

    if (method === 'GET' && path === 'version')
      return send(route, { apiVersion: '1', version: '4.4.1', authMode: 'container', authenticationRequired: false });
    if (method === 'GET' && path === 'auth/capabilities')
      return send(route, { capabilities: { operate: true, accountManage: false, systemSettingsWrite: false, configurationAdmin: false },
        operate: true, accountManage: false, systemSettingsWrite: false, configurationAdmin: false,
        inspectionCacheContext: 'f'.repeat(64) });
    if (method === 'GET' && path === 'packages')
      return send(route, { items: [{ packageId: 'payments', name: 'payments', state: 'ready', canEditPackageContent: true }], total: 1 });
    if (method === 'GET' && path === 'packages/payments/configuration') {
      assert.equal(url.searchParams.get('view'), 'declared');
      return send(route, { environments: [{ name: 'UAT', state: 'active' }] });
    }
    if (method === 'GET' && path === 'packages/payments/configuration/effective') {
      assert.equal(url.searchParams.get('environment'), 'UAT');
      assert.equal(url.searchParams.get('section'), 'testdata');
      state.effectiveReads++;
      return send(route, { environment: 'UAT', sections: [{ id: 'testdata', entries: [
        { id: 'accounts', source: TESTDATA_PATH },
      ] }], total: 1 });
    }
    if (method === 'GET' && path === 'packages/payments/configuration/testdata/accounts/records') {
      assert.equal(url.searchParams.get('environment'), 'UAT');
      return send(route, { descriptorId: 'accounts', offset: 0, limit: 50, total: 0, records: [] });
    }
    if (method === 'GET' && path === 'packages/payments/authoring-source') {
      assert.equal(url.searchParams.get('type'), 'testdata');
      assert.equal(url.searchParams.get('resourceId'), 'accounts');
      assert.equal(url.searchParams.get('environment'), 'UAT');
      state.sourceReads++;
      return send(route, { available: true, present: true, logicalPath: TESTDATA_PATH, format: 'yaml',
        text: TESTDATA_INITIAL, sourceEtag: `"sha256:${state.revision}"`, revision: state.revision,
        baseSourceRevision: state.revision, registryRevision: REGISTRY_REVISION });
    }
    if (method === 'POST' && path === 'packages/payments/change-sets') {
      const input = request.postDataJSON();
      state.stageRequests.push(input);
      assert.equal(input.baseSourceRevision, state.revision);
      assert.equal(input.registryRevision, REGISTRY_REVISION);
      const changeSetId = `CS-TESTDATA-${state.nextDraft++}`;
      return send(route, { changeSetId, domain: 'PACKAGE_SOURCE', scopeId: 'payments', state: 'draft',
        baseSourceRevision: state.revision, registryRevision: REGISTRY_REVISION, expiresAt: '2030-01-01T00:00:00Z',
        changedFiles: input.operations.map(operation => ({ operation: operation.operation, path: operation.path,
          oldBytes: operation.operation === 'create' ? 0 : 40, newBytes: operation.content?.length || 0,
          oldHash: operation.operation === 'create' ? null : '1'.repeat(64), newHash: '2'.repeat(64),
          contentDiff: ['- id: U-1', '+ id: U-2'], inboundReferences: [] })) }, 201);
    }

    unexpected.push(`${method} ${path}`);
    return send(route, { error: { code: 'FIXTURE_NOT_FOUND', summary: `Unexpected request ${method} ${path}.` } }, 404);
  });
}

async function openTestdataPage(context, fixture, state, unexpected) {
  const effectiveReadsBefore = state.effectiveReads;
  await selectFixture(context, fixture.origin, 'loaded');
  await context.addInitScript(() => localStorage.setItem('att-ui-language', 'en'));
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  await installTestdataApi(page, state, unexpected);
  await page.goto(`${fixture.origin}/att/ui/#/packages/payments/testdata?environment=UAT&descriptor=accounts`);
  await page.getByRole('heading', { name: 'Testdata', exact: true }).waitFor();
  await page.getByRole('region', { name: 'Structured Testdata catalog', exact: true }).waitFor();
  await page.getByRole('button', { name: 'Edit', exact: true }).waitFor();
  await page.getByText('Effective environment: UAT (selected).', { exact: true }).waitFor();
  const deadline = Date.now() + 5000;
  while (state.effectiveReads < effectiveReadsBefore + 1 && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(state.effectiveReads >= effectiveReadsBefore + 1, 'the Testdata catalog finishes its deep-link lookup before authoring begins');
  await page.waitForTimeout(100);
  return { page, pageErrors };
}

async function runTestdataBrowser(browserType, fixture) {
  const browser = await launchBrowser(browserType);
  const state = { revision: INITIAL_REVISION, sourceReads: 0, effectiveReads: 0, nextDraft: 1, stageRequests: [] };
  const unexpected = [];
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  try {
    const { page, pageErrors } = await openTestdataPage(context, fixture, state, unexpected);
    const catalog = page.getByRole('region', { name: 'Structured Testdata catalog', exact: true });
    const editor = page.locator('.cm-content[contenteditable="true"]');

    await catalog.getByRole('button', { name: 'Edit', exact: true }).click();
    await editor.waitFor();
    const editedSource = 'schemaVersion: att-testdata/v1.0\nid: accounts\nrecords: [{id: U-2}]\n';
    await editor.fill(editedSource);
    await page.getByRole('button', { name: 'Add current file to candidate', exact: true }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: 'Add to candidate', exact: true }).click();
    await page.getByRole('button', { name: 'Stage candidate (1 files)', exact: true }).click();
    await page.locator('.att-source-review').getByRole('heading', { name: 'Draft CS-TESTDATA-1', exact: true }).waitFor();
    assert.equal(state.stageRequests.length, 1);
    assert.deepEqual(state.stageRequests[0].operations, [{ operation: 'replace', path: TESTDATA_PATH, content: editedSource }]);

    // Start deletion in a fresh page so it has its own unmodified source revision.
    const { page: deletePage, pageErrors: deleteErrors } = await openTestdataPage(context, fixture, state, unexpected);
    const deleteCatalog = deletePage.getByRole('region', { name: 'Structured Testdata catalog', exact: true });
    await deleteCatalog.getByRole('button', { name: 'Delete', exact: true }).waitFor();
    await deleteCatalog.getByRole('button', { name: 'Delete', exact: true }).click();
    await deletePage.getByRole('alertdialog').getByRole('button', { name: 'Add deletion', exact: true }).click();
    const candidate = deletePage.locator('.att-source-candidate');
    await candidate.getByRole('heading', { name: 'Candidate file changes', exact: true }).waitFor();
    await candidate.locator('select[aria-label="Related file operation"]').selectOption('replace');
    await candidate.locator('input[aria-label="Related file path"]').fill('package.yaml');
    await candidate.locator('textarea[aria-label="Related file content"]').fill(
      'schemaVersion: att-package/v2\ntestdata:\n  descriptors: [testdata/accounts.yaml]\n  environments:\n    UAT: [testdata/uat-orders.yaml]\n');
    await candidate.getByRole('button', { name: 'Add related file', exact: true }).click();
    await candidate.locator('select[aria-label="Related file operation"]').selectOption('replace');
    await candidate.locator('input[aria-label="Related file path"]').fill('load/scenario.yaml');
    await candidate.locator('textarea[aria-label="Related file content"]').fill('schemaVersion: att-load/v1.6\ntestdata: []\nworkloads: []\n');
    await candidate.getByRole('button', { name: 'Add related file', exact: true }).click();
    await deletePage.getByRole('button', { name: 'Stage candidate (3 files)', exact: true }).click();
    await deletePage.locator('.att-source-review').getByRole('heading', { name: 'Draft CS-TESTDATA-2', exact: true }).waitFor();
    assert.equal(state.stageRequests.length, 2);
    assert.deepEqual(state.stageRequests[1].operations.map(operation => [operation.operation, operation.path]), [
      ['delete', TESTDATA_PATH], ['replace', 'package.yaml'], ['replace', 'load/scenario.yaml'],
    ]);
    assert.equal(state.sourceReads, 2);
    assert.deepEqual(unexpected, [], `unexpected API routes for ${browserType.name()} Testdata flow`);
    assert.deepEqual(pageErrors, [], `page errors while editing Testdata in ${browserType.name()}`);
    assert.deepEqual(deleteErrors, [], `page errors while deleting Testdata in ${browserType.name()}`);
    console.log(`Testdata browser acceptance passed (${browserType.name()}): UAT descriptor edit and delete stage revision-fenced Package source candidates with Manifest/LOAD reference updates.`);
  } finally {
    await context.close();
    await browser.close();
  }
}

async function main() {
  const fixture = await startFixture();
  const browserTypes = process.env.FULL_BROWSER_SUITE === 'true' ? [chromium, firefox, webkit] : [chromium];
  try {
    for (const browserType of browserTypes) {
      await runBrowser(browserType, fixture);
      await runTestdataBrowser(browserType, fixture);
    }
  } finally {
    await stopFixture(fixture);
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
