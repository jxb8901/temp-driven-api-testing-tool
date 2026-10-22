import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { gzipSync } from 'node:zlib';

const outputRoot = resolve(process.cwd(), 'dist');
const manifest = JSON.parse(await readFile(resolve(outputRoot, '.vite/manifest.json'), 'utf8'));
const entries = Object.entries(manifest);
const entry = entries.find(([source, chunk]) => chunk.isEntry && source.endsWith('src/main.tsx'))
  || entries.find(([, chunk]) => chunk.isEntry);
if (!entry) throw new Error('Vite did not emit a frontend entry in its build manifest.');

const includedFiles = new Set();
const visited = new Set();
function includeStaticDependencies(key) {
  if (visited.has(key)) return;
  visited.add(key);
  const chunk = manifest[key];
  if (!chunk) throw new Error('Vite manifest is missing a static dependency: ' + key);
  for (const file of [chunk.file, ...(chunk.css || [])]) {
    if (/\.(?:js|css)$/.test(file)) includedFiles.add(file);
  }
  for (const dependency of chunk.imports || []) includeStaticDependencies(dependency);
}
includeStaticDependencies(entry[0]);

const files = [...includedFiles];
if (files.length === 0) throw new Error('The Vite entry has no JavaScript or CSS assets.');
const compressedBytes = gzipSync(Buffer.concat(await Promise.all(
  files.map(file => readFile(resolve(outputRoot, file))),
))).byteLength;
const limitBytes = 150 * 1024;
console.log('ATT UI initial asset budget: ' + compressedBytes + ' / ' + limitBytes + ' bytes gzip');
console.log('Included entry and static imports: ' + files.join(', '));

const lazyChunks = entries
  .filter(([, chunk]) => chunk.isDynamicEntry || (chunk.isEntry && !includedFiles.has(chunk.file)))
  .flatMap(([, chunk]) => [chunk.file, ...(chunk.css || [])])
  .filter(file => /\.(?:js|css)$/.test(file))
  .filter((file, index, all) => all.indexOf(file) === index)
  .map(async file => ({ file, gzipBytes: gzipSync(await readFile(resolve(outputRoot, file))).byteLength }));
const lazyChunkSizes = await Promise.all(lazyChunks);
if (lazyChunkSizes.length) {
  console.log('ATT UI lazy chunk gzip sizes (informational):');
  for (const chunk of lazyChunkSizes) console.log('  ' + chunk.file + ': ' + chunk.gzipBytes + ' bytes gzip');
}

const lazyRoutes = [
  ['Explorer', 'src/features/explorer/ExplorerPage.tsx'],
  ['Load', 'src/features/load/LoadPage.tsx'],
  ['Jobs', 'src/features/load/JobsPage.tsx'],
  ['Schedules', 'src/features/schedules/ScheduledLoadPage.tsx'],
  ['Settings', 'src/SettingsPage.tsx'],
  ['Job console', 'src/features/load/StandaloneJobConsolePage.tsx'],
];
console.log('ATT UI lazy route gzip sizes (informational; no route budget gate):');
for (const [route, source] of lazyRoutes) {
  const chunk = manifest[source];
  if (!chunk) {
    console.log('  ' + route + ': not emitted');
    continue;
  }
  const routeFiles = [chunk.file, ...(chunk.css || [])].filter(file => /\.(?:js|css)$/.test(file));
  const routeGzipBytes = gzipSync(Buffer.concat(await Promise.all(
    routeFiles.map(file => readFile(resolve(outputRoot, file))),
  ))).byteLength;
  console.log('  ' + route + ': ' + routeGzipBytes + ' bytes gzip (' + routeFiles.join(', ') + ')');
}
if (compressedBytes > limitBytes) throw new Error('ATT UI initial static assets exceed the 150 KiB gzip review budget.');
