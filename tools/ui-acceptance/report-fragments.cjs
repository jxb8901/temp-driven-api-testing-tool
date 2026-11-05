const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {launchBrowser, startFixture, stopFixture} = require('./browser-support.cjs');

async function main() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'att-report-fragments-'));
  const report = '<!doctype html><html><body><nav aria-label="Report sections"><a href="#summary">Summary</a></nav><div style="height:1800px"></div><section id="summary"><h2>Machine-readable summary</h2></section></body></html>';
  const file = path.join(directory, 'index.html'); fs.writeFileSync(file, report);
  const fixture = await startFixture(), browser = await launchBrowser();
  try {
    const context = await browser.newContext(), page = await context.newPage();
    await page.goto(pathToFileURL(file).href);
    await page.getByRole('link', {name:'Summary'}).click();
    assert.equal(await page.evaluate(() => location.hash), '#summary');
    await context.route('**/api/v1/jobs/J1234567890ABCDEF', route => route.fulfill({json:{jobId:'J1234567890ABCDEF',status:'PASS',packageId:'payments',command:'load'}}));
    await context.route('**/api/v1/jobs/J1234567890ABCDEF/artifacts?*', route => route.fulfill({json:{archiveStatus:'READY',items:[]}}));
    await context.route('**/api/v1/jobs/*/artifacts/view/**', route => route.fulfill({status:200,contentType:'text/html',body:report}));
    await page.goto(fixture.origin + '/att/ui/#/jobs/J1234567890ABCDEF/artifact?path=report%2Findex.html');
    const frame = page.frameLocator('iframe');
    await frame.getByRole('link', {name:'Summary'}).focus();
    await page.keyboard.press('Enter');
    await frame.getByRole('heading', {name:'Machine-readable summary'}).waitFor();
    assert.equal(await frame.locator('#summary').count(), 1, 'fragment navigation must retain the sandboxed report');
    assert.ok(await frame.locator('#summary').evaluate(element => element.getBoundingClientRect().top < innerHeight), 'fragment navigation must scroll to the target');
    console.log('Offline and sandboxed report fragment navigation passed.');
  } finally { await browser.close(); await stopFixture(fixture); fs.rmSync(directory, {recursive:true,force:true}); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
