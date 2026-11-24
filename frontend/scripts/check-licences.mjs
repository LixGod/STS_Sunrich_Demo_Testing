// Fails if any installed package is under the commercial PrimeUI licence (PrimeNG 22+, primeicons 8+, @primeuix/themes 3+).
// Ship2Ship uses only the MIT-licensed PrimeNG 21 line until a commercial licence is approved.
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const root = new URL('../node_modules/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const offenders = [];

function packageDirs() {
  const dirs = [];
  for (const entry of readdirSync(root)) {
    if (entry.startsWith('.')) continue;
    if (entry.startsWith('@')) {
      for (const scoped of readdirSync(join(root, entry))) dirs.push(join(root, entry, scoped));
    } else {
      dirs.push(join(root, entry));
    }
  }
  return dirs;
}

for (const dir of packageDirs()) {
  const manifest = join(dir, 'package.json');
  if (!existsSync(manifest)) continue;
  const pkg = JSON.parse(readFileSync(manifest, 'utf8'));
  for (const file of ['LICENSE.md', 'LICENSE', 'LICENSE.txt']) {
    const path = join(dir, file);
    if (existsSync(path) && /PrimeUI License/i.test(readFileSync(path, 'utf8').slice(0, 400))) {
      offenders.push(`${pkg.name}@${pkg.version}`);
      break;
    }
  }
}

if (offenders.length) {
  console.error(`Commercially licensed (PrimeUI) packages found: ${offenders.join(', ')}`);
  process.exit(1);
}
console.log('Licence check passed: no PrimeUI-licensed packages installed.');
