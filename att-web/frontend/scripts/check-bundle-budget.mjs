import { readdir, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { gzipSync } from 'node:zlib';

const assetRoot = resolve(process.cwd(), 'dist/assets');
const assetFiles = (await readdir(assetRoot, { withFileTypes: true }))
  .filter(entry => entry.isFile() && /\.(?:js|css)$/.test(entry.name))
  .map(entry => resolve(assetRoot, entry.name));
if (assetFiles.length === 0) throw new Error('The Vite build did not emit JavaScript or CSS assets.');
const compressedBytes = gzipSync(Buffer.concat(await Promise.all(assetFiles.map(file => readFile(file))))).byteLength;
const limitBytes = 150 * 1024;
console.log('ATT UI initial asset budget: ' + compressedBytes + ' / ' + limitBytes + ' bytes gzip');
if (compressedBytes > limitBytes) throw new Error('ATT UI production assets exceed the 150 KiB gzip review budget.');
