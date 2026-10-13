const { spawn } = require('node:child_process');
const { once } = require('node:events');
const path = require('node:path');
const readline = require('node:readline');
const { chromium, firefox, webkit } = require('playwright');

const harnessRoot = __dirname;
const repositoryRoot = path.resolve(harnessRoot, '../..');
const defaultChrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

async function startFixture() {
  const fixture = spawn(process.env.PYTHON || 'python3', [path.join(harnessRoot, 'server.py')], {
    cwd: repositoryRoot,
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  const lines = readline.createInterface({ input: fixture.stdout });
  let origin;
  const ready = new Promise((resolve, reject) => {
    fixture.once('error', reject);
    fixture.once('exit', code => {
      if (!origin) reject(new Error(`Fixture server exited before ready (${code}).`));
    });
    lines.on('line', line => {
      if (line.startsWith('READY ')) {
        origin = line.slice('READY '.length);
        resolve(origin);
      }
    });
  });
  return { fixture, lines, origin: await ready };
}

async function launchBrowser(browserType = chromium) {
  const executablePath = browserType === chromium
    ? process.env.ATT_UI_CHROME_PATH || (require('node:fs').existsSync(defaultChrome) ? defaultChrome : undefined)
    : undefined;
  return browserType.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
}

async function selectFixture(context, origin, scenario) {
  await context.clearCookies({ name: 'att-ui-fixture' });
  await context.addCookies([{ name: 'att-ui-fixture', value: scenario, url: `${origin}/att/` }]);
}

async function stopFixture(handle) {
  if (!handle || handle.fixture.exitCode !== null) return;
  handle.fixture.kill('SIGTERM');
  await Promise.race([once(handle.fixture, 'exit'), new Promise(resolve => setTimeout(resolve, 1500))]);
  handle.lines.close();
}

module.exports = { chromium, firefox, webkit, launchBrowser, selectFixture, startFixture, stopFixture };
