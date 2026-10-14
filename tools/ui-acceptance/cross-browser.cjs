const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium, firefox, webkit, launchBrowser, selectFixture, startFixture, stopFixture } = require('./browser-support.cjs');
const { comparePng } = require('./png-compare.cjs');

const outputDirectory = path.join(__dirname, 'artifacts', 'scheduled-load');
const visualBaselineDirectory = path.join(__dirname, 'visual-baselines', 'scheduled-load', process.platform);
const updateVisualBaselines = process.env.ATT_UI_UPDATE_VISUAL_BASELINES === '1';
const visualDifferenceLimit = 0.04;
const channelTolerance = 32;

async function assertAxe(page, engine, theme, width) {
  await page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') });
  const audit = await page.evaluate(async () => window.axe.run(document, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
  }));
  assert.deepEqual(audit.violations.map(({ id, impact, nodes }) => ({
    id, impact, selectors: nodes.map(node => node.target),
  })), [], `${engine} ${theme} ${width}px Scheduled Load accessibility audit`);
}

async function main() {
  if (!updateVisualBaselines) {
    assert.ok(['darwin', 'linux'].includes(process.platform), `No committed Scheduled Load screenshot baseline exists for ${process.platform}.`);
    const baselineManifest = JSON.parse(await fs.readFile(path.join(visualBaselineDirectory, 'manifest.json'), 'utf8'));
    assert.equal(baselineManifest.platform, process.platform, `Scheduled Load screenshots must use the ${process.platform} baseline, not another platform's captures.`);
    assert.equal(baselineManifest.captures?.length, 18, `The ${process.platform} baseline must describe all 18 browser, viewport, and theme captures.`);
  }
  await fs.mkdir(outputDirectory, { recursive: true });
  const fixture = await startFixture();
  const manifest = [];
  const visualDifferences = [];
  try {
    for (const engine of [chromium, firefox, webkit]) {
      const engineName = engine.name();
      const browser = await launchBrowser(engine);
      try {
        for (const width of [1280, 768, 390]) {
          for (const theme of ['light', 'dark']) {
            const context = await browser.newContext({ viewport: { width, height: 900 }, colorScheme: theme, bypassCSP: true });
            await selectFixture(context, fixture.origin, 'scheduled-workspace');
            const page = await context.newPage();
            const pageErrors = [];
            page.on('pageerror', error => pageErrors.push(error.message));
            await page.goto(`${fixture.origin}/att/ui/#/load-schedules`);
            await page.getByRole('heading', { name: 'Scheduled Load', exact: true }).waitFor();
            await page.locator('.att-schedule-table-wrap:visible, .att-schedule-cards:visible').getByText('Nightly payments', { exact: true }).waitFor();
            assert.equal(await page.locator('[data-nav="scheduled-load"]').getAttribute('aria-current'), 'page');
            assert.equal(await page.evaluate(() => document.documentElement.dataset.theme), theme);
            assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), `${engine} overflows at ${width}px`);
            if (engineName === 'webkit') {
              // Playwright WebKit uses macOS keyboard access defaults, where Tab skips links.
              await page.locator('.skip-link').focus();
            } else {
              await page.keyboard.press('Tab');
            }
            assert.equal(await page.evaluate(() => document.activeElement?.classList.contains('skip-link')), true, `${engineName} should expose the skip link to keyboard users`);
            assert.ok(await page.locator('.skip-link').evaluate(element => element.getBoundingClientRect().top >= 0), `${engineName} skip link should be visible when focused`);
            if (width <= 820) {
              await page.getByRole('button', { name: 'Open navigation' }).click();
              await page.waitForFunction(() => document.querySelector('.app-layout')?.classList.contains('mobile-nav-open'));
              assert.equal(await page.locator('.app-layout').evaluate(element => element.classList.contains('mobile-nav-open')), true);
              await page.keyboard.press('Escape');
              await page.waitForFunction(() => !document.querySelector('.app-layout')?.classList.contains('mobile-nav-open'));
              assert.equal(await page.locator('.app-layout').evaluate(element => element.classList.contains('mobile-nav-open')), false, `${engine} Escape should close mobile navigation`);
            }
            if (width <= 820) {
              assert.equal(await page.locator('.att-schedule-cards').isVisible(), true, `${engine} cards should be visible at ${width}px`);
              assert.equal(await page.locator('.att-schedule-table-wrap').isVisible(), false, `${engine} desktop table should be hidden at ${width}px`);
            } else {
              assert.equal(await page.locator('.att-schedule-table-wrap').isVisible(), true, `${engine} desktop table should be visible at ${width}px`);
              assert.equal(await page.locator('.att-schedule-cards').isVisible(), false, `${engine} cards should be hidden at ${width}px`);
            }
            await page.emulateMedia({ reducedMotion: 'reduce' });
            assert.equal(await page.evaluate(() => window.matchMedia('(prefers-reduced-motion: reduce)').matches), true);
            await assertAxe(page, engineName, theme, width);
            if (width === 1280) {
              await page.keyboard.press('/');
              await page.getByRole('dialog').getByLabel('Search pages').fill('Scheduled');
              await page.getByRole('button', { name: /Scheduled Load/ }).click();
              await page.getByRole('heading', { name: 'Scheduled Load', exact: true }).waitFor();
            }
            assert.deepEqual(pageErrors, [], `${engineName} should not report browser errors`);
            const file = `${engineName}-scheduled-${width}-${theme}.png`;
            const screenshot = await page.screenshot({ path: path.join(outputDirectory, file), animations: 'disabled' });
            const baselinePath = path.join(visualBaselineDirectory, file);
            if (updateVisualBaselines) {
              await fs.mkdir(visualBaselineDirectory, { recursive: true });
              await fs.writeFile(baselinePath, screenshot);
            } else {
              const baseline = await fs.readFile(baselinePath);
              const comparison = comparePng(baseline, screenshot);
              if (comparison.changedRatio > visualDifferenceLimit) {
                visualDifferences.push({ file, ...comparison });
              }
            }
            manifest.push({ browser: engineName, version: browser.version(), width, height: 900, theme, route: '#/load-schedules', file });
            await context.close();
          }
        }
      } finally {
        await browser.close();
      }
    }
    await fs.writeFile(path.join(outputDirectory, 'manifest.json'), `${JSON.stringify({
      repositoryVersion: '4.1.4', generatedAt: new Date().toISOString(), platform: process.platform,
      architecture: process.arch, captures: manifest,
    }, null, 2)}\n`);
    if (updateVisualBaselines) {
      await fs.writeFile(path.join(visualBaselineDirectory, 'manifest.json'), `${JSON.stringify({
        repositoryVersion: '4.1.4', generatedAt: new Date().toISOString(), platform: process.platform,
        architecture: process.arch,
        maxChangedPixelRatio: visualDifferenceLimit, channelTolerance, captures: manifest,
      }, null, 2)}\n`);
    }
    if (visualDifferences.length > 0) {
      await fs.writeFile(path.join(outputDirectory, 'visual-differences.json'), `${JSON.stringify({
        maxChangedPixelRatio: visualDifferenceLimit, differences: visualDifferences,
      }, null, 2)}\n`);
      assert.fail(`${visualDifferences.length} screenshot(s) exceeded the ${(visualDifferenceLimit * 100).toFixed(0)}% visual regression threshold. ` +
        'Review tools/ui-acceptance/artifacts/scheduled-load/visual-differences.json and the uploaded screenshots.');
    }
    console.log(`Scheduled Load cross-browser checks passed; captured ${manifest.length} screenshots.`);
  } finally {
    await stopFixture(fixture);
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
