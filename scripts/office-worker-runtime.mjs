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
} = {}) {
  requireDocker();
  const { project } = paths(profile);
  const privateConfig = loadPrivateConfig(profile, {
    hostPort,
    url: `http://127.0.0.1:${hostPort}`,
    testMode,
    containerName,
    networkName,
    image,
    externalNetwork,
  });
  const { token } = privateConfig;
  const url = privateConfig.url;
  const env = {
    ...process.env,
    LUMIRA_OFFICE_WORKER_TOKEN: token,
    LUMIRA_OFFICE_WORKER_TEST_MODE: testMode ? "true" : "false",
    LUMIRA_OFFICE_WORKER_HOST_PORT: String(hostPort),
    LUMIRA_OFFICE_WORKER_CONTAINER_NAME: containerName,
    LUMIRA_OFFICE_WORKER_NETWORK: networkName,
    LUMIRA_OFFICE_WORKER_IMAGE: image,
    LUMIRA_OFFICE_WORKER_NETWORK_EXTERNAL: externalNetwork ? "true" : "false",
    LUMIRA_OFFICE_WORKER_OWNER: ownerId,
  };
  run(
    "docker",
    ["compose", "-p", project, "-f", composeFile, "up", "-d", "--build", "office-worker"],
    { env, stdio: "inherit" },
  );
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
  const composeEnv = {
    ...process.env,
    LUMIRA_OFFICE_WORKER_TOKEN: token,
    LUMIRA_OFFICE_WORKER_TEST_MODE: value.testMode ? "true" : "false",
    LUMIRA_OFFICE_WORKER_HOST_PORT: String(value.hostPort ?? 18091),
    LUMIRA_OFFICE_WORKER_CONTAINER_NAME:
      value.containerName ?? `lumira-next-office-worker-${profile}`,
    LUMIRA_OFFICE_WORKER_NETWORK:
      value.networkName ?? `lumira-office-${ownerId}-${profile}-private`,
    LUMIRA_OFFICE_WORKER_IMAGE:
      value.image ?? `lumira-office-worker:${ownerId}-${profile}`,
    LUMIRA_OFFICE_WORKER_NETWORK_EXTERNAL: value.externalNetwork ? "true" : "false",
    LUMIRA_OFFICE_WORKER_OWNER: ownerId,
  };
  const compose = ["compose", "-p", project, "-f", composeFile];
  const ids = run("docker", [...compose, "ps", "-aq", "office-worker"], {
    env: composeEnv,
    stdio: "pipe",
  })
    .split(/\s+/)
    .filter(Boolean);
  if (!ids.length) return;
  if (ids.length !== 1) throw new Error("Office worker Compose ownership is ambiguous; nothing was stopped.");
  const actual = run(
    "docker",
    [
      "inspect",
      "--format",
      '{{.Name}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}|{{index .Config.Labels "com.lumira.office-worker.owner"}}',
      ids[0],
    ],
    { stdio: "pipe" },
  );
  const [name, actualProject, service, actualOwner] = actual.replace(/^\//, "").split("|");
  if (
    name !== composeEnv.LUMIRA_OFFICE_WORKER_CONTAINER_NAME ||
    actualProject !== project ||
    service !== "office-worker" ||
    actualOwner !== ownerId
  )
    throw new Error("Office worker Compose ownership did not match; nothing was stopped.");
  run(
    "docker",
    [...compose, "stop", "--timeout", "10", "office-worker"],
    { env: composeEnv, stdio: "inherit" },
  );
}
