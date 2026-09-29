import { cp, mkdir, readdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const directory = dirname(fileURLToPath(import.meta.url));
const source = resolve(directory, '../../audio/build/dist/js/productionLibrary');
const destination = resolve(directory, 'dist');
const entries = await readdir(source);
const modules = entries.filter((name) => name.endsWith('.mjs'));
const entry =
  modules.find((name) => name === 'pcm-audio.mjs') ??
  modules.find((name) => name.includes('audio'));
if (!entry)
  throw new Error(`No compiled audio ES module in ${source}. Build the JS library first.`);
await mkdir(destination, { recursive: true });
await cp(source, destination, { recursive: true });
if (entry !== 'pcm-audio.mjs') {
  await writeFile(
    resolve(destination, 'pcm-audio.mjs'),
    `export { WebAudioEngine } from './${entry}';\n`,
  );
}
for (const name of ['index.html', 'audio-worklet.mjs', 'browser-audio.mjs']) {
  await cp(resolve(directory, name), resolve(destination, name));
}
console.log(`Web sample prepared at ${destination}`);
