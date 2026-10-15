import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  environmentDrift,
  assertNoJvmIdentityOverrides,
  assertNoSpringRuntimeOverrides,
  parsePort,
  readLocalRuntime,
  readProcCommandLine,
  readProcEnvironment,
  runtimeConfigPaths,
} from "./local-runtime-config.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const runtime = path.join(root, ".runtime");
const stateFile = path.join(runtime, "processes.json");
const localWorkerConfig = path.join(runtime, "office-worker", "local.json");
const runtimePaths = runtimeConfigPaths(root);

function alive(pid) {
  if (process.platform === "win32") return null;
  try {
    process.kill(Number(pid), 0);
    return true;
  } catch (error) {
    return error?.code === "EPERM";
  }
}

function readJson(file, fallback) {
  if (!existsSync(file)) return fallback;
  try {
    return JSON.parse(readFileSync(file, "utf8"));
  } catch {
    return fallback;
  }
}

function readProcessState() {
  const value = readJson(stateFile, []);
  if (Array.isArray(value)) return value;
  console.log("process state: invalid JSON");
  process.exitCode = 1;
  return [];
}

let config = {};
let localRuntime = null;
let pinDrift = null;
try {
  localRuntime = readLocalRuntime(root, { requireConfig: false });
  config = localRuntime.config;
} catch (error) {
  pinDrift = error instanceof Error ? error.message : "Local runtime pin validation failed.";
  config = readJson(runtimePaths.configFile, {});
  process.exitCode = 1;
}

if (localRuntime?.pinned) {
  console.log("local runtime pin: valid");
  console.log(`database: ${localRuntime.database.host}:${localRuntime.database.port}/${localRuntime.database.database}`);
  console.log(`files root: ${localRuntime.files.relativePath}`);
  console.log(`plugins root: ${localRuntime.plugins.relativePath}`);
} else if (pinDrift) {
  console.log(`local runtime pin: drift (${pinDrift})`);
} else {
  console.log("local runtime pin: not set");
}

const processes = readProcessState();
for (const processInfo of processes) {
  const state = alive(processInfo.pid);
  const label = state === null ? "unknown (run status in WSL)" : state ? "running" : "stopped";
  console.log(`${processInfo.name}: ${label} (pid ${processInfo.pid})`);
}
if (!processes.length) console.log("scaffold: stopped");

let inventoryDrift = null;
if (localRuntime?.pinned) {
  try {
    const inventory = await import("./local-plugin-inventory.mjs");
    const snapshot = inventory.assertLocalPluginInventory({ root, config });
    console.log(`plugin inventory: matches pinned snapshot (${snapshot.plugins.length} records)`);
  } catch (error) {
    inventoryDrift = error instanceof Error ? error.message : "Plugin inventory validation failed.";
    console.log(`plugin inventory: drift (${inventoryDrift})`);
    process.exitCode = 1;
  }
}

const backendPort = (() => {
  try {
    return parsePort(config.LUMIRA_PORT ?? "8090", "LUMIRA_PORT");
  } catch {
    return localRuntime?.pin?.ports?.backend ?? 8090;
  }
})();
const checks = [
  ["backend", `http://127.0.0.1:${backendPort}/actuator/health/readiness`],
  ["frontend", "http://127.0.0.1:8010/user/login"],
];
let workerUrl = localRuntime?.pinned
  ? config.LUMIRA_OFFICE_WORKER_URL
  : process.env.LUMIRA_OFFICE_WORKER_URL;
if (!workerUrl && existsSync(localWorkerConfig)) {
  workerUrl = readJson(localWorkerConfig, {})?.url;
}
if (workerUrl) checks.push(["Office worker", `${workerUrl.replace(/\/$/, "")}/health`]);

let backendProcessVerified = !localRuntime?.pinned;
let environmentDriftKeys = [];
let environmentUnavailable = false;
if (localRuntime?.pinned) {
  for (const service of ["backend", "web"]) {
    const serviceProcess = processes.find((entry) => entry.name === service);
    if (!serviceProcess) {
      environmentUnavailable = true;
      console.log(`${service} runtime environment: no tracked PID to verify`);
      process.exitCode = 1;
      continue;
    }
    const state = alive(serviceProcess.pid);
    if (state !== true || process.platform === "win32") {
      environmentUnavailable = true;
      console.log(`${service} runtime environment: unavailable; run status from the same WSL instance`);
      process.exitCode = 1;
      continue;
    }
    try {
      const actualEnvironment = readProcEnvironment(serviceProcess.pid, "/proc", {
        includeJvmOptions: service === "backend",
      });
      if (service === "backend") {
        const commandLine = readProcCommandLine(serviceProcess.pid);
        assertNoJvmIdentityOverrides({
          ...actualEnvironment,
          JAVA_TOOL_OPTIONS: `${actualEnvironment.JAVA_TOOL_OPTIONS ?? ""} ${commandLine.replaceAll("\0", " ")}`,
        });
        assertNoSpringRuntimeOverrides(actualEnvironment);
      }
      const drift = environmentDrift(config, actualEnvironment, { service });
      if (drift.length) {
        environmentDriftKeys.push(...drift);
        console.log(`${service} runtime environment drift: ${drift.join(", ")}`);
        process.exitCode = 1;
      } else {
        console.log(`${service} runtime environment: matches pinned config`);
        if (service === "backend") backendProcessVerified = true;
      }
    } catch (error) {
      environmentUnavailable = true;
      const detail = error?.code === "SPRING_RUNTIME_OVERRIDE" || error?.code === "JVM_IDENTITY_OVERRIDE"
        ? `drift (${error.message})`
        : "unavailable; run status from the same WSL instance";
      console.log(`${service} runtime environment: ${detail}`);
      process.exitCode = 1;
    }
  }
}

const pinOrInventoryDrift = Boolean(pinDrift || inventoryDrift || environmentDriftKeys.length);
for (const [name, url] of checks) {
  if (name === "backend" && (pinDrift || localRuntime?.pinned)) {
    if (pinDrift || pinOrInventoryDrift || environmentUnavailable || !backendProcessVerified) {
      console.log("backend health: not trusted because the pinned runtime could not be verified");
      continue;
    }
  }
  if (name === "frontend" && localRuntime?.pinned && (pinOrInventoryDrift || environmentUnavailable)) {
    console.log("frontend health: not trusted because the pinned runtime could not be verified");
    continue;
  }
  try {
    const response = await fetch(url, {
      signal: AbortSignal.timeout(3000),
      redirect: "error",
    });
    console.log(`${name} health: HTTP ${response.status}`);
    if (response.status !== 200) process.exitCode = 1;
  } catch (error) {
    console.log(`${name} health: unavailable (${error instanceof Error ? error.message : "unknown error"})`);
    process.exitCode = 1;
  }
}
