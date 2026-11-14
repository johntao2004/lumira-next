/**
 * 按状态、版本和摘要协调插件资源，并把回调绑定到原始 script 对象，防止卸载或升级后的迟到事件误认领新任务。
 * Reconcile plugin assets by state, version and digest while binding callbacks to their original script objects so late events cannot claim replacement attempts.
 */
import type { FrontendModule } from './types';

export type PluginAsset = {
  id: string; version: string; digest: string;
  installed: boolean; enabled: boolean; status: string;
  manifest: { routes: readonly { id: string }[]; files: Record<string, string> };
};
type Attempt = {
  plugin: PluginAsset; script: HTMLScriptElement; styles: HTMLLinkElement[];
  resolve: (module: FrontendModule) => void; reject: (error: Error) => void;
};
type Loaded = { digest: string; version: string; remove: () => void; script: HTMLScriptElement; styles: HTMLLinkElement[] };

/**
 * 传统脚本同步注册；每个回调须绑定确切脚本，不能只凭标识关联。
 * Classic scripts register synchronously. Bind each callback to its exact script, not just an ID.
 */
export function createPluginLoader(options: {
  document: Document;
  assetUrl: (digest: string, file: string) => string;
  register: (module: FrontendModule) => () => void;
  changed: (id: string, removed: boolean) => void;
}) {
  const pending = new Map<string, Attempt>();
  const loaded = new Map<string, Loaded>();
  const attempts = new WeakMap<HTMLScriptElement, Attempt>();
  let desired = new Map<string, PluginAsset>();
  const current = (attempt: Attempt) => pending.get(attempt.plugin.id) === attempt
    && desired.get(attempt.plugin.id)?.digest === attempt.plugin.digest
    && desired.get(attempt.plugin.id)?.version === attempt.plugin.version;
  const clean = (item: { script: HTMLScriptElement; styles: HTMLLinkElement[] }) => {
    item.script.onload = null; item.script.onerror = null;
    item.script.remove(); item.styles.forEach(style => style.remove());
  };
  const fail = (attempt: Attempt, error: Error) => {
    if (pending.get(attempt.plugin.id) !== attempt) return;
    pending.delete(attempt.plugin.id);
    clean(attempt);
    attempt.reject(error);
  };
  const remove = (id: string, item: Loaded) => {
    loaded.delete(id);
    item.remove(); clean(item); options.changed(id, true);
  };
  function load(plugin: PluginAsset): Promise<FrontendModule> {
    return new Promise((resolve, reject) => {
      const script = options.document.createElement('script');
      const attempt: Attempt = { plugin, script, styles: [], resolve, reject };
      pending.set(plugin.id, attempt); attempts.set(script, attempt);
      try {
        script.src = options.assetUrl(plugin.digest, 'plugin.js');
        script.onload = () => { if (current(attempt)) fail(attempt, new Error('pluginFrontendMissing')); };
        script.onerror = () => fail(attempt, new Error('pluginFrontendLoadFailed'));
        for (const file of Object.keys(plugin.manifest.files).filter(file => file.startsWith('frontend/') && file.endsWith('.css'))) {
          const link = options.document.createElement('link');
          link.rel = 'stylesheet'; link.href = options.assetUrl(plugin.digest, file.slice('frontend/'.length));
          attempt.styles.push(link); options.document.head.appendChild(link);
        }
        options.document.head.appendChild(script);
      } catch (error) { fail(attempt, error instanceof Error ? error : new Error('pluginFrontendLoadFailed')); }
    });
  }
  return {
    register(id: string, bundle: Record<string, FrontendModule>) {
      const script = options.document.currentScript as HTMLScriptElement | null;
      const attempt = script ? attempts.get(script) : undefined;
      if (!attempt || attempt.plugin.id !== id || !current(attempt)) return;
      const module = Object.values(bundle).find(module => module?.id === id);
      if (!module || module.version !== attempt.plugin.version
        || module.routes.length !== attempt.plugin.manifest.routes.length
        || module.routes.some(route => !attempt.plugin.manifest.routes.some(expected => expected.id === route.id))) {
        fail(attempt, new Error('pluginFrontendIncompatible')); return;
      }
      try {
        const unregister = options.register(module);
        pending.delete(id);
        loaded.set(id, { digest: attempt.plugin.digest, version: attempt.plugin.version, remove: unregister, script: attempt.script, styles: attempt.styles });
        attempt.script.onload = null; attempt.script.onerror = null;
        attempt.resolve(module); options.changed(id, false);
      } catch (error) { fail(attempt, error instanceof Error ? error : new Error('pluginFrontendIncompatible')); }
    },
    synchronize(plugins: readonly PluginAsset[]) {
      desired = new Map(plugins.filter(plugin => plugin.installed && plugin.enabled && plugin.status === 'RUNNING').map(plugin => [plugin.id, plugin]));
      for (const attempt of pending.values()) if (!current(attempt)) fail(attempt, new Error('pluginFrontendCancelled'));
      for (const [id, item] of loaded) if (desired.get(id)?.digest !== item.digest || desired.get(id)?.version !== item.version) remove(id, item);
      return [...desired.values()].filter(plugin => !loaded.has(plugin.id) && !pending.has(plugin.id)).map(plugin => ({ id: plugin.id, promise: load(plugin) }));
    },
    dispose() {
      desired.clear();
      for (const attempt of pending.values()) fail(attempt, new Error('pluginFrontendCancelled'));
      for (const [id, item] of loaded) remove(id, item);
    },
  };
}
