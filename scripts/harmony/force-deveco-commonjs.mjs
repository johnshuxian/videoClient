import { access, readFile } from 'node:fs/promises';
import { dirname, join, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

export async function load(url, context, nextLoad) {
  if (!url.startsWith('file:') || !url.endsWith('.js')) return nextLoad(url, context);
  const file = fileURLToPath(url);
  const toolRoot = process.env.DEVECO_HOME;
  if (!toolRoot || !file.toLowerCase().startsWith((toolRoot + sep).toLowerCase()))
    return nextLoad(url, context);

  for (let dir = dirname(file); dir.toLowerCase().startsWith(toolRoot.toLowerCase()); dir = dirname(dir)) {
    try {
      await access(join(dir, 'package.json'));
      return nextLoad(url, context);
    } catch {}
  }
  return { format: 'commonjs', shortCircuit: true, source: await readFile(file, 'utf8') };
}
