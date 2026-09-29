export type ModuleGraphNode = {
  readonly id: string;
  readonly dependencies: readonly string[];
  readonly version?: string;
  readonly required?: boolean;
};

/** Topologically order a manifest without importing any UI or runtime code. */
export function orderModuleGraph<T extends ModuleGraphNode>(candidates: readonly T[]): readonly T[] {
  const indexed = new Map<string, T>();
  for (const module of candidates) {
    if (!/^[a-z][a-z0-9-]{0,99}$/u.test(module.id)) throw new Error(`Invalid module id: ${module.id}`);
    if (module.version !== undefined && !module.version.trim()) throw new Error(`Invalid module version: ${module.id}`);
    if (module.dependencies.includes(module.id)) throw new Error(`Module cannot depend on itself: ${module.id}`);
    if (new Set(module.dependencies).size !== module.dependencies.length) {
      throw new Error(`Duplicate module dependency: ${module.id}`);
    }
    if (indexed.has(module.id)) throw new Error(`Duplicate module: ${module.id}`);
    indexed.set(module.id, module);
  }
  const visiting = new Set<string>();
  const visited = new Set<string>();
  const ordered: T[] = [];
  const visit = (id: string) => {
    if (visited.has(id)) return;
    const module = indexed.get(id);
    if (!module) throw new Error(`Missing module dependency: ${id}`);
    if (visiting.has(id)) throw new Error(`Circular module dependency: ${id}`);
    visiting.add(id);
    for (const dependency of module.dependencies) visit(dependency);
    visiting.delete(id);
    visited.add(id);
    ordered.push(module);
  };
  for (const module of candidates) visit(module.id);
  for (const module of ordered) {
    if (module.required) {
      for (const dependency of module.dependencies) {
        if (indexed.get(dependency)?.required === false) {
          throw new Error(`Required module depends on optional module: ${module.id}`);
        }
      }
    }
  }
  return Object.freeze(ordered);
}
