/**
 * 按规范化路径将相对导入限制在本插件或公共前端 SDK 目录内，阻止用相对路径绕过宿主接口白名单。
 * Constrain normalized relative imports to the current plugin or public frontend SDK directories so relative paths cannot bypass the host API allowlist.
 */
import path from 'node:path';
/**
 * 相对源码导入不能绕过精确的宿主 SDK 模块允许清单。
 * Relative source imports cannot bypass the exact host SDK module allowlist.
 */
export function assertPluginRelativeImport(root, pluginDirectory, importer, specifier) {
  if (!specifier.startsWith('.') || !importer) return;
  const inside = (file, directory) => file === directory || file.startsWith(directory + path.sep);
  const normalizedRoot = path.resolve(root);
  const normalizedPlugin = path.resolve(pluginDirectory);
  const normalizedImporter = path.resolve(importer);
  const sdkDirectory = path.resolve(normalizedRoot, 'plugins/sdk/frontend');
  if (!inside(normalizedImporter, normalizedPlugin) && !inside(normalizedImporter, sdkDirectory)) return;
  const target = path.resolve(path.dirname(normalizedImporter), specifier);
  if (!inside(target, normalizedPlugin) && !inside(target, sdkDirectory)) {
    throw new Error(`Plugin relative import escapes its source and public SDK: ${specifier}`);
  }
}
