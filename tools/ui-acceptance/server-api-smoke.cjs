const assert = require('node:assert/strict');
const { launchBrowser } = require('./browser-support.cjs');

async function main() {
  const origin = process.env.ATT_UI_SERVER_ORIGIN;
  const username = process.env.ATT_UI_SERVER_USERNAME;
  const password = process.env.ATT_UI_SERVER_PASSWORD;
  assert.ok(origin, 'ATT_UI_SERVER_ORIGIN is required');
  assert.ok(username && password, 'Tomcat test credentials are required');

  const browser = await launchBrowser();
  const context = await browser.newContext({
    httpCredentials: { username, password, send: 'always' },
    viewport: { width: 1440, height: 1000 },
  });
  await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin });
  const page = await context.newPage();
  const pageErrors = [];
  const consoleErrors = [];
  const navigationEvents = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('framenavigated', frame => { if (frame === page.mainFrame()) navigationEvents.push(frame.url()); });
  await page.addInitScript(() => {
    localStorage.setItem('att-ui-theme', 'light');
    window.__attCspViolations = [];
    document.addEventListener('securitypolicyviolation', event => {
      window.__attCspViolations.push(`${event.violatedDirective}: ${event.blockedURI}`);
    });
  });

  try {
    const entryResponse = await page.goto(`${origin}/tools/att/ui/index.html#/packages/payments`);
    assert.ok(entryResponse, 'Studio entry response should be available');
    const entryHeaders = entryResponse.headers();
    const entryHtml = await entryResponse.text();
    const csp = entryHeaders['content-security-policy'] || '';
    const pageNonce = entryHtml.match(/<script[^>]+nonce="([^"]+)"/)?.[1];
    assert.ok(pageNonce && pageNonce !== '__ATT_CSP_NONCE__', 'the packaged entry must receive a runtime CSP nonce');
    assert.ok(csp.includes(`style-src 'self' 'nonce-${pageNonce}'`), 'the page nonce must match the CSP style nonce');
    assert.ok(csp.includes("style-src-attr 'unsafe-inline'"), 'CodeMirror geometry styles must be permitted for the Studio document');
    assert.ok(!csp.includes("script-src 'self' 'unsafe-inline'"), 'Studio scripts must not receive an unsafe-inline exception');
    await page.getByRole('heading', { name: 'Package Explorer' }).waitFor();
    assert.equal(await page.locator('.att-query-filter-panel[aria-label="Resource filters"]').count(), 1,
      'Explorer must use the shared query filter panel');
    assert.equal(await page.locator('.att-query-results-panel[aria-label="Resource results"] .att-data-table').count(), 1,
      'Explorer must use the shared results and data-table contract');
    await page.getByRole('button', { name: 'Clear filters' }).click();
    await page.getByRole('button', { name: 'Refresh resources' }).click();
    await page.getByRole('button', { name: 'Reference Flow' }).waitFor();
    await page.getByRole('button', { name: 'Reference Flow' }).click();
    await page.getByRole('heading', { name: 'Reference Flow' }).waitFor();

    const definitionEditor = page.locator('#att-explorer-panel-definition .cm-editor');
    await definitionEditor.waitFor();
    const inspectEditor = (locator, tokenSelector) => locator.evaluate((element, selector) => {
      const content = element.querySelector('.cm-content');
      const token = content?.querySelector(selector);
      return {
        text: Array.from(content?.querySelectorAll('.cm-line') || []).map(line => line.textContent || '').join('\n'),
        height: element.getBoundingClientRect().height,
        tokenText: token?.textContent || '',
        tokenColor: token ? getComputedStyle(token).color : '',
        editorColor: content ? getComputedStyle(content).color : '',
      };
    }, tokenSelector);
    const jsonLight = await inspectEditor(definitionEditor, '.att-token-string');
    assert.ok(jsonLight.text.includes('reference.flow.v1'), 'the JSON Definition source must be visible under the production CSP');
    assert.ok(jsonLight.height > 0, 'the JSON Definition editor must have visible layout under the production CSP');
    assert.ok(jsonLight.tokenText && jsonLight.tokenColor !== jsonLight.editorColor,
      `JSON string tokens must have visible syntax color in the light theme: ${JSON.stringify(jsonLight)}`);
    await page.getByRole('button', { name: 'Switch to dark theme' }).click();
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark');
    const jsonDark = await inspectEditor(definitionEditor, '.att-token-string');
    assert.ok(jsonDark.text.includes('reference.flow.v1') && jsonDark.height > 0, 'JSON Definition text and layout must remain visible in the dark theme');
    assert.ok(jsonDark.tokenColor && jsonDark.tokenColor !== jsonDark.editorColor, 'JSON string tokens must have visible syntax color in the dark theme');
    await page.getByRole('button', { name: 'Switch to light theme' }).click();
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'light');

    await page.getByRole('tab', { name: 'Source' }).click();
    await page.getByRole('tabpanel', { name: 'Source' }).getByText('Code viewer ready.').waitFor();
    const editor = page.locator('.att-explorer-source .cm-editor');
    await editor.waitFor();
    const editorState = await editor.evaluate(element => {
      const content = element.querySelector('.cm-content');
      const keyToken = content?.querySelector('.att-token-property');
      return {
        text: Array.from(content?.querySelectorAll('.cm-line') || []).map(line => line.textContent || '').join('\n'),
        height: element.getBoundingClientRect().height,
        generatedStyles: Array.from(document.querySelectorAll('style[nonce]')).map(style => style.nonce),
        keyText: keyToken?.textContent || '',
        keyColor: keyToken ? getComputedStyle(keyToken).color : '',
        editorColor: content ? getComputedStyle(content).color : '',
      };
    });
    assert.ok(editorState.text.includes('&{templates/SCHEDULED/payload/request.xml}'), 'CodeMirror must show the source text under the production CSP');
    assert.ok(editorState.height > 0, 'CodeMirror must have visible layout under the production CSP');
    assert.ok(editorState.generatedStyles.length > 0 && editorState.generatedStyles.every(nonce => nonce === pageNonce), 'generated editor styles must carry the page nonce');
    assert.ok(editorState.keyText && editorState.keyColor !== editorState.editorColor,
      `YAML keys must have visible syntax color in the light theme: ${JSON.stringify(editorState)}`);
    await editor.locator('xpath=ancestor::section[contains(@class,"att-code-editor")]').getByRole('button', { name: 'Copy' }).click();
    await page.getByText('Copied displayed code.').waitFor();
    assert.equal(await page.evaluate(() => navigator.clipboard.readText()), editorState.text, 'Copy must match the visible YAML source under the production CSP');
    await page.getByRole('button', { name: 'Switch to dark theme' }).click();
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark');
    const yamlDark = await inspectEditor(editor, '.att-token-property');
    assert.ok(yamlDark.text.includes('&{templates/SCHEDULED/payload/request.xml}') && yamlDark.height > 0, 'YAML Source text and layout must remain visible in the dark theme');
    assert.ok(yamlDark.tokenText && yamlDark.tokenColor !== yamlDark.editorColor, 'YAML keys must have visible syntax color in the dark theme');
    await page.getByRole('button', { name: 'Switch to light theme' }).click();
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'light');
    const fileLink = page.locator('.att-explorer-source [data-att-reference]');
    await fileLink.waitFor();
    assert.equal(await fileLink.getAttribute('data-att-file'), 'templates/SCHEDULED/payload/request.xml');
    const referenceLine = await fileLink.evaluate(element => {
      const lines = Array.from(element.closest('.cm-content')?.querySelectorAll('.cm-line') || []);
      return lines.findIndex(line => line.contains(element)) + 1;
    });
    assert.ok(referenceLine > 0, 'the projected source reference should have a CodeMirror line');
    const goToLine = page.locator('.att-explorer-source .att-code-goto input');
    await goToLine.fill(String(referenceLine));
    await page.locator('.att-explorer-source .att-code-editor').getByRole('button', { name: 'Go' }).click();
    await fileLink.click();
    try {
      await page.getByRole('heading', { name: 'Configuration', level: 1 }).waitFor({ timeout: 10000 });
    } catch (error) {
      const headings = await page.locator('h1').allTextContents();
      const body = (await page.locator('body').innerText()).slice(0, 1200);
      const dialogs = await page.locator('dialog').evaluateAll(elements => elements.map(element => ({ open: element.open, text: element.innerText })));
      const link = await fileLink.evaluate(element => element.outerHTML);
      throw new Error(`${error.stack || error}\nCurrent URL: ${page.url()}\nHeadings: ${headings.join(' | ')}\nNavigations: ${navigationEvents.join(' -> ')}\nPage errors: ${pageErrors.join(' | ')}\nDialogs: ${JSON.stringify(dialogs)}\nLink: ${link}\nPage: ${body}`);
    }
    const payloadSource = page.locator('.att-config-source-view .att-code-editor .cm-content');
    await payloadSource.waitFor();
    const payloadSourceText = await payloadSource.evaluate(element => element.textContent || '');
    assert.ok(payloadSourceText.includes('<request>safe payload</request>'),
      `the safe package source should remain visible through CodeMirror: ${payloadSourceText}`);
    assert.equal(await page.locator('.att-code-language').last().textContent(), 'XML');
    assert.equal(await page.getByText('template-secret').count(), 0, 'classified source values must remain redacted');

    await page.goBack();
    await page.getByRole('heading', { name: 'Reference Flow' }).waitFor();
    await page.locator('a[data-nav="configuration"]').click();
    await page.getByRole('heading', { name: 'Configuration', level: 1 }).waitFor();
    await page.getByRole('tab', { name: 'Source' }).click();
    await page.getByRole('treeitem', { name: /config/ }).click();
    await page.getByRole('treeitem', { name: /tools/ }).click();
    await page.getByRole('treeitem', { name: /att-helper\.sh/ }).click();
    await page.getByText('# Safe configuration helper source').waitFor();

    await page.getByRole('treeitem', { name: /config\.yaml/ }).click();
    const configSource = page.locator('.att-config-source-view .att-code-editor .cm-content');
    await configSource.getByText('testcase:', { exact: false }).waitFor();
    await page.getByText(/Safe read-only source/).waitFor();

    await page.getByRole('tab', { name: 'Testdata' }).click();
    await page.getByRole('heading', { name: 'Testdata' }).waitFor();
    await page.getByRole('button', { name: 'generatedAccounts' }).click();
    await page.getByRole('button', { name: 'Preview first 50 generated records' }).click();
    await page.getByRole('table').getByText('A-001000', { exact: true }).waitFor();

    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto(`${origin}/tools/att/ui/index.html#/packages/payments`);
    await page.getByRole('heading', { name: 'Package Explorer' }).waitFor();
    await page.getByRole('button', { name: 'Reference Flow' }).click();
    await page.locator('.att-explorer-detail-actions').getByRole('button', { name: 'Run', exact: true }).click();
    await page.getByRole('heading', { name: 'Run', level: 1, exact: true }).waitFor();
    const runGrid = async () => page.evaluate(() => {
      const chrome = document.querySelector('.page-chrome');
      const content = document.querySelector('#main-content .page-content');
      const heading = content?.querySelector('h1');
      if (!chrome || !content || !heading) return null;
      const chromeLeft = chrome.getBoundingClientRect().left + parseFloat(getComputedStyle(chrome).paddingLeft);
      const contentLeft = content.getBoundingClientRect().left + parseFloat(getComputedStyle(content).paddingLeft);
      return { chromeLeft, contentLeft, headingLeft: heading.getBoundingClientRect().left,
        gutter: parseFloat(getComputedStyle(content).paddingLeft), overflow: document.documentElement.scrollWidth > window.innerWidth };
    });
    const assertRunGrid = async (expectedGutter) => {
      const geometry = await runGrid();
      assert.ok(geometry, 'RUN page and shared page chrome should be present');
      assert.ok(Math.abs(geometry.chromeLeft - geometry.contentLeft) <= 2, `RUN content must align with page chrome: ${JSON.stringify(geometry)}`);
      assert.ok(Math.abs(geometry.headingLeft - geometry.contentLeft) <= 2, `RUN heading must align with its content gutter: ${JSON.stringify(geometry)}`);
      assert.equal(geometry.gutter, expectedGutter, `the shared gutter should be ${expectedGutter}px: ${JSON.stringify(geometry)}`);
      assert.equal(geometry.overflow, false, 'RUN must not overflow the viewport');
    };
    await assertRunGrid(32);
    await page.getByRole('button', { name: 'Collapse navigation' }).click();
    await assertRunGrid(32);
    await page.getByRole('button', { name: 'Expand navigation' }).click();
    for (const [width, gutter] of [[1920, 32], [1440, 32], [1024, 24], [768, 24], [390, 16], [320, 16]]) {
      await page.setViewportSize({ width, height: 1000 });
      await assertRunGrid(gutter);
      if (width <= 820) {
        await page.getByRole('button', { name: 'Open navigation' }).click();
        await assertRunGrid(gutter);
        await page.getByRole('button', { name: 'Close navigation' }).click();
        await assertRunGrid(gutter);
      }
    }
    await page.getByRole('button', { name: 'Back to Explorer' }).click();
    await page.getByRole('heading', { name: 'Resource details' }).waitFor();
    await page.getByRole('button', { name: 'Back to resources', exact: true }).click();
    await page.getByRole('heading', { name: 'Package Explorer' }).waitFor();

    await page.setViewportSize({ width: 768, height: 1024 });
    await page.getByRole('button', { name: 'Reference Flow' }).click();
    await page.locator('.att-explorer-detail-actions').getByRole('button', { name: 'Quick LOAD' }).click();
    await page.getByRole('heading', { name: 'Quick Load' }).waitFor();
    const quickLoadTarget = page.locator('.att-quick-load-page .att-section-heading strong');
    await quickLoadTarget.waitFor();
    const quickLoadEnvironment = page.locator('.att-quick-load-page .att-load-grid > label').filter({ hasText: /^Environment/ }).locator('select');
    const quickLoadLabels = await page.locator('.att-quick-load-page .att-load-grid > label').allTextContents();
    assert.equal(await quickLoadEnvironment.count(), 1, `Quick Load must expose an Environment selector: ${JSON.stringify(quickLoadLabels)}`);
    await quickLoadEnvironment.selectOption('UAT');
    await page.waitForFunction(() => document.querySelector('.att-quick-load-page .att-section-heading strong')?.textContent === 'flow · reference.flow.v1');
    const targetDefaultsReady = page.getByText('Target defaults are ready.', { exact: true });
    await targetDefaultsReady.waitFor({ timeout: 15000 });
    assert.equal(await targetDefaultsReady.count(), 1,
      'Quick Load should resolve the ready Flow and its safe defaults without validating the Load scenario');
    await page.waitForFunction(() => {
      const button = Array.from(document.querySelectorAll('button')).find(item => item.textContent?.trim() === 'Validate');
      return button && !button.disabled;
    });
    await page.getByRole('button', { name: 'Validate' }).click();
    await page.waitForFunction(() => {
      const state = document.querySelector('.att-quick-load-page .att-validation-state');
      const message = state?.textContent || '';
      return state?.getAttribute('aria-busy') === 'false'
        && !message.includes('Validating the single-target scenario on the Server…')
        && message !== 'Validate this single target before running.';
    });
    const quickLoadValidation = await page.locator('.att-quick-load-page .att-validation-state').innerText();
    assert.ok(quickLoadValidation.includes('The Server accepted this draft for review.'),
      `Quick Load should validate a ready Flow under UAT: ${quickLoadValidation}`);
    assert.equal(await page.getByRole('button', { name: 'Run Now' }).isDisabled(), false,
      'a ready Flow should validate as a Quick Load target under UAT');
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    assert.equal(overflow, false, 'the live Configuration and Quick Load UI must fit a tablet viewport');

    await page.goto(`${origin}/tools/att/ui/index.html#/advanced-load`);
    await page.getByRole('heading', { name: 'LOAD Catalog', level: 1, exact: true }).waitFor();
    assert.equal(await page.locator('.att-query-filter-panel[aria-label="LOAD Catalog filters"]').count(), 1,
      'LOAD Catalog filters must use the shared query contract');
    assert.equal(await page.locator('.att-query-results-panel[aria-label="Available LOAD YAML resources"]').count(), 1);
    assert.equal(await page.locator('.att-query-results-panel[aria-label="Recent LOAD job results"]').count(), 1,
      'Catalog resources and recent jobs must remain separate result datasets');
    await page.getByRole('button', { name: 'Clear filters' }).click();
    const scenarioCard = page.locator('.att-load-catalog-card').filter({ hasText: 'load/scenario.yaml' });
    await scenarioCard.waitFor();
    const catalogEnvironment = page.getByLabel(/Catalog Environment for payments/);
    await catalogEnvironment.selectOption('UAT');
    await page.getByText('Environment: UAT', { exact: false }).waitFor();
    await scenarioCard.getByRole('button', { name: 'Validate load/scenario.yaml for UAT' }).click();
    await page.getByText(/load\/scenario\.yaml validation refreshed for UAT: valid/).waitFor();
    assert.equal(await scenarioCard.getByRole('button', { name: 'Schedule' }).isDisabled(), false,
      'a current UAT validation should create an authorized Schedule draft');
    await scenarioCard.getByRole('button', { name: 'View', exact: true }).click();
    await page.getByRole('heading', { name: 'load/scenario.yaml', level: 1 }).waitFor();
    assert.equal(await page.locator('.att-code-editor').count(), 0, 'structured details must load before optional source');
    await page.getByRole('button', { name: 'Show YAML Source' }).click();
    await page.getByRole('region', { name: 'Load YAML Source', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Back to LOAD Catalog' }).click();
    await page.getByRole('heading', { name: 'LOAD Catalog', level: 1, exact: true }).waitFor();
    await page.getByText('Environment: UAT', { exact: false }).waitFor();
    const restoredScenarioCard = page.locator('.att-load-catalog-card').filter({ hasText: 'load/scenario.yaml' });
    const restoredSchedule = restoredScenarioCard.getByRole('button', { name: 'Schedule' });
    assert.equal(await restoredSchedule.isDisabled(), true, 'returning from details requires fresh validation before scheduling');
    await restoredScenarioCard.getByRole('button', { name: 'Validate load/scenario.yaml for UAT' }).click();
    await page.getByText(/load\/scenario\.yaml validation refreshed for UAT: valid/).waitFor();
    await restoredSchedule.click();
    await page.getByRole('heading', { name: 'New Schedule', level: 2 }).waitFor();
    await page.getByText('This schedule uses the immutable LOAD scenario snapshot from your validated draft.').waitFor();
    assert.equal(await page.locator('#schedule-scenario').count(), 0, 'the schedule route must carry a server-backed snapshot, not a mutable path field');
    assert.equal(await page.locator('#schedule-name').inputValue(), 'Validated LOAD snapshot');
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.getByRole('heading', { name: 'LOAD Catalog', level: 1, exact: true }).waitFor();

    await page.goto(`${origin}/tools/att/ui/index.html#/jobs`);
    await page.getByRole('heading', { name: 'Jobs', level: 1, exact: true }).waitFor();
    assert.equal(await page.locator('.att-query-filter-panel[aria-label="Job filters"]').count(), 1,
      'Jobs must use the shared query filter panel');
    assert.equal(await page.locator('.att-query-results-panel[aria-label="Job results"] .att-data-table').count(), 1,
      'Jobs must use the shared results and data-table contract');
    await page.getByRole('button', { name: 'Clear filters' }).click();
    await page.getByRole('button', { name: 'Refresh jobs' }).click();

    await page.goto(`${origin}/tools/att/ui/index.html#/schedules`);
    await page.getByRole('heading', { name: 'Schedules', level: 1, exact: true }).waitFor();
    await page.getByText('1 of 1 schedules shown.', { exact: true }).waitFor();
    assert.equal(await page.locator('.att-query-filter-panel[aria-label="Schedule filters"]').count(), 1,
      'Schedules must use the shared query filter panel');
    assert.equal(await page.locator('.att-query-results-panel[aria-label="Schedule results"] .att-data-table').count(), 1,
      'Schedules must use the shared results and data-table contract');
    assert.equal(await page.locator('#schedule-name').count(), 0, 'the packaged Schedules landing must not render an editor');
    await page.setViewportSize({ width: 1440, height: 1000 });
    const desktopScheduleAction = page.locator('.att-schedule-table .att-schedule-actions button').first();
    const desktopActionHeight = await desktopScheduleAction.evaluate(element => element.getBoundingClientRect().height);
    assert.ok(desktopActionHeight >= 30 && desktopActionHeight <= 34,
      `schedule list actions should be compact at 30–34px: ${desktopActionHeight}px`);
    await page.getByRole('button', { name: 'History Packaged UI schedule' }).click();
    await page.getByRole('heading', { name: /Execution history/ }).waitFor();
    const desktopHistoryAction = page.locator('.att-schedule-history .att-schedule-actions button').first();
    const desktopHistoryHeight = await desktopHistoryAction.evaluate(element => element.getBoundingClientRect().height);
    assert.ok(desktopHistoryHeight >= 30 && desktopHistoryHeight <= 34,
      `history actions should match the compact list size at 30–34px: ${desktopHistoryHeight}px`);
    await page.getByRole('button', { name: 'Close history' }).click();
    await page.getByRole('heading', { name: 'Schedules', level: 1, exact: true }).waitFor();
    await page.getByRole('button', { name: 'New Schedule', exact: true }).click();
    await page.locator('#schedule-name').waitFor();
    await page.locator('#schedule-name').fill('Unsaved packaged check');
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await page.getByRole('heading', { name: 'Schedules', level: 1, exact: true }).waitFor();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('.att-schedule-cards').waitFor({ state: 'visible' });
    assert.equal(await page.locator('.att-schedule-cards').isVisible(), true);
    const mobileScheduleAction = page.locator('.att-schedule-card .att-schedule-actions button').first();
    const mobileActionHeight = await mobileScheduleAction.evaluate(element => element.getBoundingClientRect().height);
    assert.ok(mobileActionHeight >= 40 && mobileActionHeight <= 44,
      `schedule actions should have 40–44px mobile touch targets: ${mobileActionHeight}px`);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false, 'Schedules must fit a phone viewport');
    await page.getByRole('button', { name: 'Open navigation' }).click();
    await page.locator('a[data-nav="scheduled-load"]').click();
    await page.getByRole('heading', { name: 'Schedules', level: 1, exact: true }).waitFor();
    assert.match(page.url(), /#\/schedules$/, 'the packaged Schedules navigation must use the canonical route');
    const cspViolations = await page.evaluate(() => window.__attCspViolations);
    assert.deepEqual(cspViolations, [], `the live Studio must not violate its CSP: ${JSON.stringify(cspViolations)}`);
    assert.deepEqual(pageErrors, [], 'the live ATT Studio UI must not throw browser errors');
    assert.deepEqual(consoleErrors.filter(message => /content security policy|refused to apply inline style/i.test(message)), [], 'the live Studio must not produce CSP console errors');
    process.stdout.write('Packaged-WAR Playwright API smoke passed under /tools/att; source references, Configuration, Testdata, and Quick Load used the real Server API.\n');
  } finally {
    await context.close();
    await browser.close();
  }
}

main().catch(error => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
});
