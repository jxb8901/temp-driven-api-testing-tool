const assert = require('node:assert/strict');
const { launchBrowser, startFixture, stopFixture } = require('./browser-support.cjs');

(async () => {
  const fixture = await startFixture(); let browser;
  try {
    browser = await launchBrowser();
    const page = await browser.newPage();
    const errors = []; const requests = [];
    page.on('pageerror', error => errors.push(error.message));
    let loadSubmissions = 0; page.on('request', request => { if (request.method() === 'POST' && request.url().includes('/jobs/load')) loadSubmissions++; });
    const item = { id: 'LS1', name: 'Traffic', packageId: 'demo', model: 'arrivalRate', revision: 2, description: '', tags: [], updatedAt: '', workloadCount: 1 };
    await page.route('**/api/v1/saved-load-scenarios**', async route => {
      const request = route.request(); requests.push({ method: request.method(), url: request.url(), body: request.postDataJSON() });
      const result = request.url().includes('/validate')
        ? { draftId: 'A1', packageId: 'demo', previewYaml: 'approved: true', expiresAt: new Date(Date.now() + 60000).toISOString() }
        : { items: [item], nextOffset: null };
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(result) });
    });
    await page.goto(fixture.origin + '/att/ui/#/advanced-load?tab=scenarios');
    const saved = page.getByRole('region', { name: 'Saved scenarios' });
    await saved.getByRole('row').filter({ hasText: 'Traffic' }).waitFor();
    await saved.getByRole('button', { name: 'Validate / Review' }).click();
    const review = page.getByRole('dialog', { name: 'Review Traffic' });
    await review.waitFor();
    assert.match(await review.innerText(), /Revision 2/);
    assert.deepEqual(requests.find(request => request.url.includes('/validate')).body, { expectedRevision: 2 });
    assert.equal(loadSubmissions, 0);
    await review.getByRole('button', { name: 'Close' }).click();
    assert.deepEqual(errors, []);
    console.log('Saved scenario browser review passed: revision-bound validation without execution.');
  } finally { if (browser) await browser.close(); await stopFixture(fixture); }
})().catch(error => { console.error(error); process.exitCode = 1; });
