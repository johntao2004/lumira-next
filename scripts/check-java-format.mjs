import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const wrapper = path.join(repositoryRoot, 'backend', 'mvnw');
const checks = [
  ['host backend', ['-f', 'backend/pom.xml', '-B', 'spotless:check']],
  ['plugin backends', ['-f', 'plugins/pom.xml', '-B', 'spotless:check']],
];

for (const [label, args] of checks) {
  process.stdout.write(`Checking Java format: ${label}\n`);
  const result = spawnSync(wrapper, args, {
    cwd: repositoryRoot,
    stdio: 'inherit',
  });

  if (result.error) {
    process.stderr.write(`${label} Spotless check could not start: ${result.error.message}\n`);
    process.exit(1);
  }

  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}
