import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

export function buildPluginRuntimeLock(pluginRoot) {
  const plugins = readdirSync(pluginRoot, { withFileTypes: true })
    .filter(entry => entry.isDirectory())
    .map(entry => path.join(pluginRoot, entry.name))
    .filter(directory => existsSync(path.join(directory, 'plugin.json')))
    .map(directory => {
      const manifest = JSON.parse(readFileSync(path.join(directory, 'plugin.json'), 'utf8'));
      if (typeof manifest.id !== 'string' || typeof manifest.version !== 'string') {
        throw new Error(`Invalid plugin descriptor: ${path.join(directory, 'plugin.json')}`);
      }
      const archive = path.join(directory, 'dist', `${manifest.id}-${manifest.version}.lumira-plugin.zip`);
      if (!existsSync(archive)) throw new Error(`Missing built plugin archive: ${archive}`);
      const digest = createHash('sha256').update(readFileSync(archive)).digest('hex');
      return { id: manifest.id, version: manifest.version, digest };
    })
    .sort((left, right) => left.id.localeCompare(right.id));

  const ids = new Set();
  for (const plugin of plugins) {
    if (ids.has(plugin.id)) throw new Error(`Duplicate plugin id: ${plugin.id}`);
    ids.add(plugin.id);
  }
  if (!plugins.length) throw new Error('No built plugin archives were found');
  return { formatVersion: 1, plugins };
}

export function writePluginRuntimeLock(pluginRoot, outputFile) {
  const lock = buildPluginRuntimeLock(pluginRoot);
  mkdirSync(path.dirname(outputFile), { recursive: true });
  writeFileSync(outputFile, `${JSON.stringify(lock, null, 2)}\n`);
  return lock;
}

export function writePluginRuntimeBundle(pluginRoot, outputDirectory) {
  const lock = buildPluginRuntimeLock(pluginRoot);
  mkdirSync(outputDirectory, { recursive: true });
  writeFileSync(path.join(outputDirectory, 'approved-graph.json'), `${JSON.stringify(lock, null, 2)}\n`);
  for (const plugin of lock.plugins) {
    const sourceDirectory = path.join(pluginRoot, plugin.id);
    const targetDirectory = path.join(outputDirectory, plugin.id);
    const targetDist = path.join(targetDirectory, 'dist');
    mkdirSync(targetDist, { recursive: true });
    copyFileSync(path.join(sourceDirectory, 'plugin.json'), path.join(targetDirectory, 'plugin.json'));
    copyFileSync(
      path.join(sourceDirectory, 'dist', `${plugin.id}-${plugin.version}.lumira-plugin.zip`),
      path.join(targetDist, `${plugin.id}-${plugin.version}.lumira-plugin.zip`),
    );
  }
  return lock;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const pluginRoot = path.join(root, 'plugins');
  if (process.argv[2] === '--bundle') {
    const outputDirectory = process.argv[3] ?? path.join(root, 'backend/target/plugin-runtime/bundle');
    const lock = writePluginRuntimeBundle(pluginRoot, outputDirectory);
    process.stdout.write(`Bundled ${lock.plugins.length} approved plugin packages to ${path.relative(root, outputDirectory)}\n`);
  } else {
    const output = process.argv[2] ?? path.join(root, 'backend/target/plugin-runtime/approved-graph.json');
    const lock = writePluginRuntimeLock(pluginRoot, output);
    process.stdout.write(`Locked ${lock.plugins.length} plugin packages to ${path.relative(root, output)}\n`);
  }
}
