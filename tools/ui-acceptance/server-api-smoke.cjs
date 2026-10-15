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
  const page = await context.newPage();
  const pageErrors = [];
  const consoleErrors = [];
  const navigationEvents = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('framenavigated', frame => { if (frame === page.mainFrame()) navigationEvents.push(frame.url()); });
  await page.addInitScript(() => {
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
    await page.getByRole('button', { name: 'Reference Flow' }).waitFor();
    await page.getByRole('button', { name: 'Reference Flow' }).click();
    await page.getByRole('heading', { name: 'Reference Flow' }).waitFor();

    await page.getByRole('tab', { name: 'Source' }).click();
    await page.getByRole('tabpanel', { name: 'Source' }).getByText('Code viewer ready.').waitFor();
    const editor = page.locator('.att-explorer-source .cm-editor');
    await editor.waitFor();
    const editorState = await editor.evaluate(element => ({
      text: element.querySelector('.cm-content')?.textContent || '',
      height: element.getBoundingClientRect().height,
      generatedStyles: Array.from(document.querySelectorAll('style[nonce]')).map(style => style.nonce),
    }));
    assert.ok(editorState.text.includes('&{templates/SCHEDULED/payload/request.xml}'), 'CodeMirror must show the source text under the production CSP');
    assert.ok(editorState.height > 0, 'CodeMirror must have visible layout under the production CSP');
    assert.ok(editorState.generatedStyles.length > 0 && editorState.generatedStyles.every(nonce => nonce === pageNonce), 'generated editor styles must carry the page nonce');
    await page.getByRole('button', { name: 'Switch to dark theme' }).click();
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark');
    assert.ok(await editor.evaluate(element => getComputedStyle(element).height !== '0px'), 'the editor must remain laid out in the dark theme');
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
    await page.getByText('<request>safe payload</request>').waitFor();
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

    await page.setViewportSize({ width: 768, height: 1024 });
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.getByRole('button', { name: 'Open navigation' }).click();
    await page.locator('a[data-nav="explorer"]').click();
    await page.getByRole('heading', { name: 'Package Explorer' }).waitFor();
    await page.getByRole('button', { name: 'Reference Flow' }).click();
    await page.getByRole('button', { name: 'Quick Load' }).click();
    await page.getByRole('heading', { name: 'Quick Load' }).waitFor();
    const quickLoadTarget = page.locator('.att-quick-load-page .att-section-heading strong');
    await quickLoadTarget.waitFor();
    assert.match(await quickLoadTarget.textContent(), /^flow · /, 'Quick Load should retain the selected Flow target');
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    assert.equal(overflow, false, 'the live Configuration and Quick Load UI must fit a tablet viewport');
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
