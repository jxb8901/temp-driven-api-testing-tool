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
const reportJsonPath = path.join(outputDirectory, 'visual-differences.json');
const reportMarkdownPath = path.join(outputDirectory, 'visual-differences.md');
const expectedBrowsers = ['chromium', 'firefox', 'webkit'];
const expectedWidths = [1280, 768, 390];
const expectedThemes = ['light', 'dark'];

function percent(ratio) {
  return `${(ratio * 100).toFixed(2)}%`;
}

function expectedCaptureFiles() {
  return expectedBrowsers.flatMap(browser => expectedWidths.flatMap(width =>
    expectedThemes.map(theme => `${browser}-scheduled-${width}-${theme}.png`)));
}

async function appendStepSummary(markdown) {
  if (!process.env.GITHUB_STEP_SUMMARY) return;
  await fs.appendFile(process.env.GITHUB_STEP_SUMMARY, `${markdown}\n`);
}

function visualDifferenceMarkdown(differences) {
  const rows = differences.map(difference =>
    `| ${difference.browser} | ${difference.width}px | ${difference.theme} | ${difference.changedPixels} | ` +
    `${percent(difference.changedRatio)} | ${percent(difference.maxChangedPixelRatio)} | ${difference.file} |`);
  return [
    '## Scheduled Load visual regression',
    '',
    `${differences.length} of 18 browser, viewport, and theme captures exceeded the ` +
      `${percent(visualDifferenceLimit)} changed-pixel limit.`,
    '',
    '| Browser | Viewport | Theme | Changed pixels | Changed ratio | Limit | Capture |',
    '| --- | ---: | --- | ---: | ---: | ---: | --- |',
    ...rows,
    '',
    `Baseline directory: \`${path.relative(__dirname, visualBaselineDirectory)}\``,
    `Actual captures: \`${path.relative(__dirname, outputDirectory)}\``,
    `Machine-readable report: \`${path.relative(__dirname, reportJsonPath)}\``,
    '',
    'Review the actual captures against the baseline before changing any image. ' +
      'Refresh a platform baseline only after confirming the difference is intentional.',
    '',
  ].join('\n');
}

async function validateBaselineManifest(baselineManifest) {
  assert.equal(baselineManifest.platform, process.platform, `Schedules screenshots must use the ${process.platform} baseline, not another platform's captures.`);
  const expectedFiles = expectedCaptureFiles();
  assert.equal(baselineManifest.captures?.length, expectedFiles.length,
    `The ${process.platform} baseline manifest must describe all ${expectedFiles.length} browser, viewport, and theme captures.`);
  const declaredFiles = new Set((baselineManifest.captures ?? []).map(capture => capture.file));
  const missingDeclarations = expectedFiles.filter(file => !declaredFiles.has(file));
  assert.deepEqual(missingDeclarations, [],
    `The ${process.platform} baseline manifest is missing: ${missingDeclarations.join(', ')}.`);
  for (const file of expectedFiles) {
    try {
      await fs.access(path.join(visualBaselineDirectory, file));
    } catch {
      assert.fail(`Missing ${process.platform} screenshot baseline: ${path.join(visualBaselineDirectory, file)}. ` +
        'Regenerate it on the matching platform and review the captures before committing.');
    }
  }
}

async function assertAxe(page, engine, theme, width) {
  await page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') });
  const audit = await page.evaluate(async () => window.axe.run(document, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
  }));
  assert.deepEqual(audit.violations.map(({ id, impact, nodes }) => ({
    id, impact, selectors: nodes.map(node => node.target),
  })), [], `${engine} ${theme} ${width}px Schedules accessibility audit`);
}

async function main() {
  if (!updateVisualBaselines) {
    assert.ok(['darwin', 'linux'].includes(process.platform), `No committed Schedules screenshot baseline exists for ${process.platform}.`);
    const baselineManifest = JSON.parse(await fs.readFile(path.join(visualBaselineDirectory, 'manifest.json'), 'utf8'));
    await validateBaselineManifest(baselineManifest);
  } else {
    assert.match(process.env.ATT_UI_VISUAL_BASELINE_REASON ?? '', /\S/,
      'Set ATT_UI_VISUAL_BASELINE_REASON to the reviewed visual change before updating screenshot baselines.');
  }
  await fs.mkdir(outputDirectory, { recursive: true });
  const fixture = await startFixture();
  const manifest = [];
  const visualDifferences = [];
  const baselineReviewReason = (process.env.ATT_UI_VISUAL_BASELINE_REASON ?? '').trim();
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
            await page.getByRole('heading', { name: 'Schedules', level: 1, exact: true }).waitFor();
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
              await page.getByRole('dialog').getByLabel('Search pages').fill('Schedules');
              await page.getByRole('button', { name: /Schedules/ }).click();
              await page.getByRole('heading', { name: 'Schedules', level: 1, exact: true }).waitFor();
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
                visualDifferences.push({
                  browser: engineName,
                  browserVersion: browser.version(),
                  width,
                  height: 900,
                  theme,
                  route: '#/load-schedules',
                  file,
                  baseline: path.relative(__dirname, baselinePath),
                  actual: path.relative(__dirname, path.join(outputDirectory, file)),
                  maxChangedPixelRatio: visualDifferenceLimit,
                  channelTolerance,
                  ...comparison,
                });
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
      repositoryVersion: '4.2.1', generatedAt: new Date().toISOString(), platform: process.platform,
      architecture: process.arch, captures: manifest,
    }, null, 2)}\n`);
    if (updateVisualBaselines) {
      await fs.writeFile(path.join(visualBaselineDirectory, 'manifest.json'), `${JSON.stringify({
        repositoryVersion: '4.2.1', generatedAt: new Date().toISOString(), platform: process.platform,
        architecture: process.arch,
        maxChangedPixelRatio: visualDifferenceLimit, channelTolerance, captures: manifest,
        reviewReason: baselineReviewReason,
      }, null, 2)}\n`);
      const updated = [
        '## Scheduled Load visual baselines updated',
        '',
        `Updated ${manifest.length} ${process.platform} captures after review.`,
        '',
        `Review rationale: ${baselineReviewReason}`,
        '',
        `Baseline directory: \`${path.relative(__dirname, visualBaselineDirectory)}\``,
        '',
      ].join('\n');
      await appendStepSummary(updated);
      console.log(updated);
    }
    if (visualDifferences.length > 0) {
      await fs.writeFile(reportJsonPath, `${JSON.stringify({
        repositoryVersion: '4.2.1',
        generatedAt: new Date().toISOString(),
        platform: process.platform,
        maxChangedPixelRatio: visualDifferenceLimit,
        channelTolerance,
        differences: visualDifferences,
      }, null, 2)}\n`);
      const markdown = visualDifferenceMarkdown(visualDifferences);
      await fs.writeFile(reportMarkdownPath, markdown);
      await appendStepSummary(markdown);
      const details = visualDifferences.map(difference =>
        `${difference.browser} ${difference.width}px ${difference.theme}: ${percent(difference.changedRatio)} changed (${difference.changedPixels} pixels)`);
      assert.fail(`${visualDifferences.length} screenshot(s) exceeded the ${percent(visualDifferenceLimit)} visual regression threshold.\n` +
        `${details.join('\n')}\n` +
        `Review ${path.relative(__dirname, reportMarkdownPath)} and the uploaded screenshots.`);
    }
    const passed = [
      '## Scheduled Load visual comparison passed',
      '',
      `Compared ${manifest.length} browser, viewport, and theme captures against the ${process.platform} baseline.`,
      `Limit: ${percent(visualDifferenceLimit)} changed pixels; channel tolerance: ${channelTolerance}.`,
      '',
    ].join('\n');
    await appendStepSummary(passed);
    console.log(`Schedules cross-browser checks passed; captured ${manifest.length} screenshots.`);
  } finally {
    await stopFixture(fixture);
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
