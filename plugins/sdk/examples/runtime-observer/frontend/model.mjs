export function formatMetric(value, suffix = "") {
  if (typeof value !== "number" || !Number.isFinite(value)) return "—";
  return value.toLocaleString() + suffix;
}

export function normalizeStatus(value) {
  if (typeof value !== "string" || value.trim().length === 0) return "UNKNOWN";
  return value.trim().toUpperCase();
}

function moduleDependencies(module, plugin) {
  const values = [
    ...(Array.isArray(module?.dependencies) ? module.dependencies : []),
    ...(Array.isArray(plugin?.manifest?.dependencies)
      ? plugin.manifest.dependencies.map(dependency => dependency?.id)
      : []),
  ];
  return [...new Set(values.filter(value => typeof value === "string" && value.length > 0))]
    .sort((left, right) => left.localeCompare(right));
}

export function mergeModuleRecords(modules = [], plugins = []) {
  const moduleById = new Map(
    modules
      .filter(module => typeof module?.id === "string" && module.id.length > 0)
      .map(module => [module.id, module]),
  );
  const pluginById = new Map(
    plugins
      .filter(plugin => typeof plugin?.id === "string" && plugin.id.length > 0)
      .map(plugin => [plugin.id, plugin]),
  );
  const ids = [...new Set([...moduleById.keys(), ...pluginById.keys()])].sort();

  return ids.map(id => {
    const module = moduleById.get(id) ?? null;
    const plugin = pluginById.get(id) ?? null;
    const enabled = typeof module?.enabled === "boolean"
      ? module.enabled
      : typeof plugin?.enabled === "boolean" ? plugin.enabled : null;
    const status = typeof plugin?.status === "string" && plugin.status.length > 0
      ? plugin.status
      : null;
    return {
      id,
      name: module?.name ?? plugin?.name ?? null,
      version: module?.version ?? plugin?.version ?? null,
      dependencies: moduleDependencies(module, plugin),
      enabled,
      installed: typeof plugin?.installed === "boolean" ? plugin.installed : null,
      status,
      required: typeof module?.required === "boolean" ? module.required : null,
      core: typeof module?.core === "boolean" ? module.core : null,
      stateVersion: typeof module?.stateVersion === "number" ? module.stateVersion : null,
      module,
      plugin,
    };
  });
}

export function buildDependencyGraph(records = []) {
  const known = new Map(
    records
      .filter(record => typeof record?.id === "string" && record.id.length > 0)
      .map(record => [record.id, record]),
  );
  const nodes = new Map(
    [...known.entries()].map(([id, record]) => [id, {
      id,
      label: record.name || id,
      known: true,
      record,
      dependencies: [...new Set(record.dependencies ?? [])],
    }]),
  );

  for (const record of known.values()) {
    for (const dependencyId of record.dependencies ?? []) {
      if (typeof dependencyId !== "string" || dependencyId.length === 0 || nodes.has(dependencyId)) continue;
      nodes.set(dependencyId, {
        id: dependencyId,
        label: "未知模块：" + dependencyId,
        known: false,
        record: null,
        dependencies: [],
      });
    }
  }

  const depthCache = new Map();
  function depth(id, visiting = new Set()) {
    if (depthCache.has(id)) return depthCache.get(id);
    if (visiting.has(id)) return 0;
    const nextVisiting = new Set(visiting);
    nextVisiting.add(id);
    const dependencies = nodes.get(id)?.dependencies ?? [];
    const rank = dependencies.reduce((maximum, dependencyId) => (
      nodes.has(dependencyId)
        ? Math.max(maximum, depth(dependencyId, nextVisiting) + 1)
        : maximum
    ), 0);
    depthCache.set(id, rank);
    return rank;
  }

  const byColumn = new Map();
  for (const node of nodes.values()) {
    const rank = depth(node.id);
    const column = byColumn.get(rank) ?? [];
    column.push(node);
    byColumn.set(rank, column);
  }

  const marginX = 34;
  const marginY = 32;
  const columnGap = 236;
  const rowGap = 104;
  const nodeWidth = 174;
  const nodeHeight = 68;
  const positioned = [];
  const positions = new Map();
  const ranks = [...byColumn.keys()].sort((left, right) => left - right);
  for (const rank of ranks) {
    const column = byColumn.get(rank).sort((left, right) => left.id.localeCompare(right.id));
    column.forEach((node, index) => {
      const position = {
        ...node,
        rank,
        x: marginX + rank * columnGap,
        y: marginY + index * rowGap,
        width: nodeWidth,
        height: nodeHeight,
      };
      positioned.push(position);
      positions.set(node.id, position);
    });
  }

  const edgeKeys = new Set();
  const edges = [];
  for (const record of known.values()) {
    for (const dependencyId of record.dependencies ?? []) {
      const key = dependencyId + "->" + record.id;
      if (edgeKeys.has(key) || !positions.has(dependencyId) || !positions.has(record.id)) continue;
      edgeKeys.add(key);
      edges.push({
        id: key,
        from: dependencyId,
        to: record.id,
        source: positions.get(dependencyId),
        target: positions.get(record.id),
      });
    }
  }
  edges.sort((left, right) => left.id.localeCompare(right.id));
  const maxRank = Math.max(0, ...positioned.map(node => node.rank));
  const maxRows = Math.max(1, ...[...byColumn.values()].map(column => column.length));

  return {
    nodes: positioned,
    edges,
    width: marginX * 2 + maxRank * columnGap + nodeWidth,
    height: marginY * 2 + (maxRows - 1) * rowGap + nodeHeight,
  };
}

function finiteOrNull(value) {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

export function appendSample(samples = [], snapshot, limit = 30) {
  const measuredAt = snapshot?.summary?.measuredAt;
  if (typeof measuredAt !== "string" || !Number.isFinite(Date.parse(measuredAt))) return samples;
  if (samples.some(sample => sample.measuredAt === measuredAt)) return samples;

  const next = {
    measuredAt,
    pendingOutbox: finiteOrNull(snapshot.summary.pendingOutbox),
    processingOutbox: finiteOrNull(snapshot.summary.processingOutbox),
    failedOutbox: finiteOrNull(snapshot.summary.failedOutbox),
    deadLetterOutbox: finiteOrNull(snapshot.summary.deadLetterOutbox),
    processCpuUsage: finiteOrNull(snapshot.machine?.processCpuUsage),
  };
  if (Object.entries(next).slice(1).every(([, value]) => value === null)) return samples;
  return [...samples, next].slice(-Math.max(1, limit));
}
