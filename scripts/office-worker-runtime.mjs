import { createHash, randomBytes } from "node:crypto";
import {
  chmodSync,
  existsSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const ownerId = createHash("sha256").update(root).digest("hex").slice(0, 12);
const privateDir = path.join(root, ".runtime", "office-worker");
const composeFile = path.join(root, "deploy", "office-worker.compose.yml");

function paths(profile) {
  if (!/^[a-z0-9-]{1,24}$/.test(profile)) throw new Error("Invalid Office worker profile.");
  return {
    project: `lumira-office-${ownerId}-${profile}`,
    config: path.join(privateDir, `${profile}.json`),
  };
}

function loadPrivateConfig(profile, defaults = {}) {
  const { config } = paths(profile);
  mkdirSync(privateDir, { recursive: true, mode: 0o700 });
  chmodSync(privateDir, 0o700);
  let value;
  if (existsSync(config)) {
    value = JSON.parse(readFileSync(config, "utf8"));
    if (value.ownerId !== ownerId)
      throw new Error(`Office worker configuration at ${config} belongs to a different checkout or predates ownership tracking; nothing was changed.`);
  } else {
    value = { ownerId, token: randomBytes(48).toString("hex") };
  }
  value = { ...value, ...defaults, ownerId };
  writeFileSync(config, `${JSON.stringify(value)}\n`, { mode: 0o600 });
  chmodSync(config, 0o600);
  if (typeof value.token !== "string" || !/^[a-f0-9]{96}$/.test(value.token))
    throw new Error(`Invalid private Office worker configuration at ${config}.`);
  return value;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: root,
    encoding: "utf8",
    env: options.env ?? process.env,
    stdio: options.stdio ?? "ignore",
  });
  if (result.error) throw new Error(`Unable to run ${command}: ${result.error.message}`);
  if (result.status !== 0) {
    const detail = options.stdio === "inherit" ? " See the command output above." : "";
    throw new Error(`${command} exited with status ${result.status ?? "unknown"}.${detail}`);
  }
  return result.stdout?.trim() ?? "";
}

function verifyWorkerContainers({
  compose,
  env,
  project,
  profile,
  ownerId,
  names,
  requireProfileLabel = false,
  expectedNetworks,
}) {
  const ids = run("docker", [...compose, "ps", "-aq"], { env, stdio: "pipe" })
    .split(/\s+/)
    .filter(Boolean);
  const expected = new Map([
    ["office-worker", names.worker],
    ["office-worker-ingress", names.ingress],
  ]);
  const found = new Map();
  for (const id of ids) {
    const actual = run(
      "docker",
      [
        "inspect",
        "--format",
        '{{.Name}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}|{{index .Config.Labels "com.lumira.office-worker.owner"}}|{{index .Config.Labels "com.lumira.office-worker.profile"}}|{{.State.Running}}|{{json .NetworkSettings.Networks}}',
        id,
      ],
      { stdio: "pipe" },
    );
    const [name, actualProject, service, actualOwner, actualProfile, running, networksJson] = actual
      .replace(/^\//, "")
      .replaceAll("<no value>", "")
      .split("|");
    const containerNetworks = Object.keys(JSON.parse(networksJson || "{}"));
    const expectedContainerNetworks = expectedNetworks?.[service];
    if (
      expected.get(service) !== name ||
      actualProject !== project ||
      actualOwner !== ownerId ||
      (actualProfile && actualProfile !== profile) ||
      (requireProfileLabel && actualProfile !== profile) ||
      found.has(service) ||
      (running === "true" &&
        expectedContainerNetworks &&
        (containerNetworks.length !== expectedContainerNetworks.length ||
          containerNetworks.some((network) => !expectedContainerNetworks.includes(network))))
    )
      throw new Error("Office worker container ownership did not match; nothing was changed.");
    found.set(service, { id, name });
  }
  return {
    services: [...found.keys()],
    allowedEndpoints: Object.fromEntries(
      [...found.values()].map(({ id, name }) => [id, name]),
    ),
  };
}

/** A same-name network with no owner labels can be removed so Compose recreates it labeled. */
export function legacyUnlabeledNetworkCanBeRetired({
  name,
  expectedName,
  ownerLabel,
  profileLabel,
  projectLabel,
  expectedProject,
  networkKey,
  expectedNetworkKey,
  endpointIds,
  allowedEndpointIds,
}) {
  if (name !== expectedName) return false;
  if (ownerLabel || profileLabel) return false;
  if (projectLabel && projectLabel !== expectedProject) return false;
  if (networkKey && networkKey !== expectedNetworkKey) return false;
  const allowed = new Set(allowedEndpointIds ?? []);
  return (endpointIds ?? []).every((id) => allowed.has(id));
}

function retireLegacyUnlabeledNetwork({
  name,
  project,
  networkKey,
  env,
  allowedEndpoints,
}) {
  const listed = run("docker", ["network", "ls", "--format", "{{.Name}}"], {
    env,
    stdio: "pipe",
  })
    .split(/\s+/)
    .filter(Boolean);
  if (!listed.includes(name)) return;
  const actual = run(
    "docker",
    [
      "network",
      "inspect",
      "--format",
      '{{.Name}}|{{index .Labels "com.docker.compose.project"}}|{{index .Labels "com.docker.compose.network"}}|{{index .Labels "com.lumira.office-worker.owner"}}|{{index .Labels "com.lumira.office-worker.profile"}}|{{json .Containers}}',
      name,
    ],
    { env, stdio: "pipe" },
  );
  const [actualName, actualProject, actualNetworkKey, actualOwner, actualProfile, containersJson] =
    actual.replaceAll("<no value>", "").split("|");
  const endpoints = JSON.parse(containersJson || "{}");
  if (
    !legacyUnlabeledNetworkCanBeRetired({
      name: actualName,
      expectedName: name,
      ownerLabel: actualOwner,
      profileLabel: actualProfile,
      projectLabel: actualProject,
      expectedProject: project,
      networkKey: actualNetworkKey,
      expectedNetworkKey: networkKey,
      endpointIds: Object.keys(endpoints),
      allowedEndpointIds: Object.keys(allowedEndpoints ?? {}),
    })
  )
    return;
  for (const id of Object.keys(endpoints))
    run("docker", ["network", "disconnect", "-f", name, id], { env, stdio: "pipe" });
  run("docker", ["network", "rm", name], { env, stdio: "pipe" });
}

function verifyManagedNetwork({
  name,
  project,
  networkKey,
  ownerId,
  profile,
  external,
  env,
  requireOwnerProfile = false,
  internal,
  allowedEndpoints,
}) {
  const listed = run("docker", ["network", "ls", "--format", "{{.Name}}"], {
    env,
    stdio: "pipe",
  })
    .split(/\s+/)
    .filter(Boolean);
  if (!listed.includes(name)) {
    if (external) throw new Error(`Required external Office worker network ${name} was not found.`);
    return;
  }
  if (external) return;
  const actual = run(
    "docker",
    [
      "network",
      "inspect",
      "--format",
      '{{.Name}}|{{.Internal}}|{{index .Labels "com.docker.compose.project"}}|{{index .Labels "com.docker.compose.network"}}|{{index .Labels "com.lumira.office-worker.owner"}}|{{index .Labels "com.lumira.office-worker.profile"}}',
      name,
    ],
    { env, stdio: "pipe" },
  );
  const [actualName, actualInternal, actualProject, actualNetworkKey, actualOwner, actualProfile] = actual
    .replaceAll("<no value>", "")
    .split("|");
  if (
    actualName !== name ||
    actualProject !== project ||
    actualNetworkKey !== networkKey ||
    (actualOwner && actualOwner !== ownerId) ||
    (actualProfile && actualProfile !== profile) ||
    (requireOwnerProfile && (actualOwner !== ownerId || actualProfile !== profile)) ||
    (internal !== undefined && actualInternal !== String(internal))
  )
    throw new Error("Office worker network ownership did not match; nothing was changed.");
  if (allowedEndpoints) {
    const endpoints = JSON.parse(
      run(
        "docker",
        ["network", "inspect", "--format", "{{json .Containers}}", name],
        { env, stdio: "pipe" },
      ) || "{}",
    );
    for (const [id, endpoint] of Object.entries(endpoints))
      if (allowedEndpoints[id] !== endpoint.Name)
        throw new Error("Office worker network has an unowned endpoint; nothing was changed.");
  }
}

function requireDocker() {
  try {
    run("docker", ["compose", "version"]);
    run("docker", ["info", "--format", "{{.ServerVersion}}"]);
  } catch {
    throw new Error(
      "Office previews require a running Docker Engine with Docker Compose v2; start or install Docker, then retry.",
    );
  }
}

async function waitForHealth(url, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${url}/health`, {
        signal: AbortSignal.timeout(1500),
        redirect: "error",
      });
      if (response.status === 200 && (await response.text()) === "ok") return;
    } catch {}
    await new Promise((resolve) => setTimeout(resolve, 400));
  }
  throw new Error(`Office worker did not become healthy at ${url}; inspect Docker container health and logs.`);
}

export async function startOfficeWorker({
  profile = "local",
  hostPort = 18091,
  testMode = false,
  containerName = `lumira-office-${ownerId}-${profile}`,
  networkName = `lumira-office-${ownerId}-${profile}-private`,
  image = `lumira-office-worker:${ownerId}-${profile}`,
  externalNetwork = false,
  buildImage = true,
} = {}) {
  requireDocker();
  const { project } = paths(profile);
  const privateConfig = loadPrivateConfig(profile, {
    hostPort,
    url: `http://127.0.0.1:${hostPort}`,
    testMode,
    containerName,
    ingressContainerName: `${containerName}-ingress`,
    networkName,
    ingressNetworkName: `${networkName}-ingress`,
    image,
    externalNetwork,
  });
  const { token } = privateConfig;
  const url = privateConfig.url;
  if (!Number.isInteger(hostPort) || hostPort < 1 || hostPort > 65535)
    throw new Error("Office worker host port must be an integer from 1 to 65535.");
  const networkOverrideFile = path.join(privateDir, `${profile}-network.compose.yml`);
  const networkOverride = externalNetwork
    ? "    external: true\n"
    : `    internal: true\n    labels:\n      com.lumira.office-worker.owner: "${ownerId}"\n      com.lumira.office-worker.profile: "${profile}"\n`;
  writeFileSync(
    networkOverrideFile,
    `networks:\n  office-worker:\n${networkOverride}`,
    { mode: 0o600 },
  );
  chmodSync(networkOverrideFile, 0o600);
  const env = {
    ...process.env,
    LUMIRA_OFFICE_WORKER_TOKEN: token,
    LUMIRA_OFFICE_WORKER_TEST_MODE: testMode ? "true" : "false",
    LUMIRA_OFFICE_WORKER_HOST_PORT: String(hostPort),
    LUMIRA_OFFICE_WORKER_CONTAINER_NAME: containerName,
    LUMIRA_OFFICE_WORKER_NETWORK: networkName,
    LUMIRA_OFFICE_WORKER_INGRESS_CONTAINER_NAME: privateConfig.ingressContainerName,
    LUMIRA_OFFICE_WORKER_INGRESS_NETWORK: privateConfig.ingressNetworkName,
    LUMIRA_OFFICE_WORKER_IMAGE: image,
    LUMIRA_OFFICE_WORKER_NETWORK_EXTERNAL: externalNetwork ? "true" : "false",
    LUMIRA_OFFICE_WORKER_OWNER: ownerId,
    LUMIRA_OFFICE_WORKER_PROFILE: profile,
  };
  const compose = ["compose", "-p", project, "-f", composeFile, "-f", networkOverrideFile];
  const existingContainers = verifyWorkerContainers({
    compose,
    env,
    project,
    profile,
    ownerId,
    names: {
      worker: containerName,
      ingress: privateConfig.ingressContainerName,
    },
    expectedNetworks: {
      "office-worker": [networkName],
      "office-worker-ingress": [networkName, privateConfig.ingressNetworkName],
    },
  });
  if (!externalNetwork)
    retireLegacyUnlabeledNetwork({
      name: networkName,
      project,
      networkKey: "office-worker",
      env,
      allowedEndpoints: existingContainers.allowedEndpoints,
    });
  retireLegacyUnlabeledNetwork({
    name: privateConfig.ingressNetworkName,
    project,
    networkKey: "office-worker-ingress",
    env,
    allowedEndpoints: existingContainers.allowedEndpoints,
  });
  verifyManagedNetwork({
    name: networkName,
    project,
    networkKey: "office-worker",
    ownerId,
    profile,
    external: externalNetwork,
    env,
    allowedEndpoints: existingContainers.allowedEndpoints,
  });
  verifyManagedNetwork({
    name: privateConfig.ingressNetworkName,
    project,
    networkKey: "office-worker-ingress",
    ownerId,
    profile,
    external: false,
    env,
    allowedEndpoints: existingContainers.allowedEndpoints,
  });
  run(
    "docker",
    [
      ...compose,
      "up",
      "-d",
      ...(buildImage ? ["--build"] : ["--no-build"]),
      "office-worker",
      "office-worker-ingress",
    ],
    { env, stdio: "inherit" },
  );
  const startedContainers = verifyWorkerContainers({
    compose,
    env,
    project,
    profile,
    ownerId,
    names: {
      worker: containerName,
      ingress: privateConfig.ingressContainerName,
    },
    requireProfileLabel: true,
    expectedNetworks: {
      "office-worker": [networkName],
      "office-worker-ingress": [networkName, privateConfig.ingressNetworkName],
    },
  });
  verifyManagedNetwork({
    name: networkName,
    project,
    networkKey: "office-worker",
    ownerId,
    profile,
    external: externalNetwork,
    env,
    requireOwnerProfile: !externalNetwork,
    internal: externalNetwork ? undefined : true,
    allowedEndpoints: startedContainers.allowedEndpoints,
  });
  verifyManagedNetwork({
    name: privateConfig.ingressNetworkName,
    project,
    networkKey: "office-worker-ingress",
    ownerId,
    profile,
    external: false,
    env,
    requireOwnerProfile: true,
    internal: false,
    allowedEndpoints: startedContainers.allowedEndpoints,
  });
  await waitForHealth(url);
  return { url, token };
}

export async function stopOfficeWorker(profile = "local") {
  const { project, config } = paths(profile);
  if (!existsSync(config)) return;
  const value = JSON.parse(readFileSync(config, "utf8"));
  if (value.ownerId !== ownerId)
    throw new Error("Office worker configuration ownership did not match this checkout; nothing was stopped.");
  if (typeof value.token !== "string" || !/^[a-f0-9]{96}$/.test(value.token))
    throw new Error("Invalid private Office worker configuration; nothing was stopped.");
  const { token } = value;
  requireDocker();
  const containerName = value.containerName ?? `lumira-next-office-worker-${profile}`;
  const networkName = value.networkName ?? `lumira-office-${ownerId}-${profile}-private`;
  const ingressContainerName = value.ingressContainerName ?? `${containerName}-ingress`;
  const ingressNetworkName = value.ingressNetworkName ?? `${networkName}-ingress`;
  const composeEnv = {
    ...process.env,
    LUMIRA_OFFICE_WORKER_TOKEN: token,
    LUMIRA_OFFICE_WORKER_TEST_MODE: value.testMode ? "true" : "false",
    LUMIRA_OFFICE_WORKER_HOST_PORT: String(value.hostPort ?? 18091),
    LUMIRA_OFFICE_WORKER_CONTAINER_NAME: containerName,
    LUMIRA_OFFICE_WORKER_INGRESS_CONTAINER_NAME: ingressContainerName,
    LUMIRA_OFFICE_WORKER_NETWORK: networkName,
    LUMIRA_OFFICE_WORKER_INGRESS_NETWORK: ingressNetworkName,
    LUMIRA_OFFICE_WORKER_IMAGE:
      value.image ?? `lumira-office-worker:${ownerId}-${profile}`,
    LUMIRA_OFFICE_WORKER_NETWORK_EXTERNAL: value.externalNetwork ? "true" : "false",
    LUMIRA_OFFICE_WORKER_OWNER: ownerId,
    LUMIRA_OFFICE_WORKER_PROFILE: profile,
  };
  const networkOverrideFile = path.join(privateDir, `${profile}-network.compose.yml`);
  const compose = ["compose", "-p", project, "-f", composeFile];
  if (existsSync(networkOverrideFile)) compose.push("-f", networkOverrideFile);
  const existingContainers = verifyWorkerContainers({
    compose,
    env: composeEnv,
    project,
    profile,
    ownerId,
    names: { worker: containerName, ingress: ingressContainerName },
    expectedNetworks: {
      "office-worker": [networkName],
      "office-worker-ingress": [networkName, ingressNetworkName],
    },
  });
  verifyManagedNetwork({
    name: networkName,
    project,
    networkKey: "office-worker",
    ownerId,
    profile,
    external: value.externalNetwork === true,
    env: composeEnv,
    allowedEndpoints: existingContainers.allowedEndpoints,
  });
  verifyManagedNetwork({
    name: ingressNetworkName,
    project,
    networkKey: "office-worker-ingress",
    ownerId,
    profile,
    external: false,
    env: composeEnv,
    allowedEndpoints: existingContainers.allowedEndpoints,
  });
  if (existingContainers.services.length)
    run(
      "docker",
      [...compose, "stop", "--timeout", "10", ...existingContainers.services],
      { env: composeEnv, stdio: "inherit" },
    );
}
