import { existsSync, readFileSync } from "node:fs";
import net from "node:net";
import path from "node:path";
import {
  LocalRuntimeConfigError,
  adoptRunningBackend,
  backupAndWriteRuntimeConfig,
  configFingerprint,
  copyLocalJsonBackup,
  createPinMetadata,
  parseArguments,
  parseLocalConfigFile,
  projectRootFromScript,
  readLocalRuntime,
  relativeWorkspacePath,
  restoreRuntimeFile,
  restoreRuntimeFiles,
  runtimeConfigPaths,
  sha256File,
  validatePinnedConfig,
  withLocalRuntimeLock,
  writeLocalJson,
} from "./local-runtime-config.mjs";

const root = projectRootFromScript(import.meta.url);

function serviceIsAlive(pid) {
  try {
    process.kill(Number(pid), 0);
    return true;
  } catch (error) {
    return error?.code === "EPERM";
  }
}

async function isTcpPortOpen(port) {
  return await new Promise((resolve) => {
    const socket = net.createConnection({ host: "127.0.0.1", port });
    socket.setTimeout(800);
    socket.once("connect", () => {
      socket.destroy();
      resolve(true);
    });
    socket.once("timeout", () => {
      socket.destroy();
      resolve(false);
    });
    socket.once("error", () => resolve(false));
  });
}

async function assertServicesStopped(...configs) {
  if (process.platform === "win32")
    throw new LocalRuntimeConfigError(
      "Run the local runtime mutation command inside WSL so Linux PIDs and listeners can be verified.",
      "PROC_UNAVAILABLE",
    );
  const { processFile } = runtimeConfigPaths(root);
  if (existsSync(processFile)) {
    let processes;
    try {
      processes = JSON.parse(readFileSync(processFile, "utf8"));
    } catch {
      throw new LocalRuntimeConfigError(
        "Local process state is invalid; refusing runtime mutation until it is inspected.",
        "INVALID_PROCESS_STATE",
      );
    }
    if (!Array.isArray(processes))
      throw new LocalRuntimeConfigError("Local process state is invalid; refusing runtime mutation.", "INVALID_PROCESS_STATE");
    const live = processes.filter((entry) => serviceIsAlive(entry.pid));
    if (live.length)
      throw new LocalRuntimeConfigError(
        "Stop the local scaffold before switching its pinned database or plugin inventory.",
        "LOCAL_SERVICES_RUNNING",
      );
  }
  const ports = new Set([8010]);
  for (const config of configs) ports.add(validatePinnedConfig(root, config).backendPort);
  for (const port of ports) {
    if (await isTcpPortOpen(port))
      throw new LocalRuntimeConfigError(
        `A process is still listening on local service port ${port}; stop local services before this runtime mutation.`,
        "LOCAL_SERVICE_PORT_IN_USE",
      );
  }
}

async function adopt(pid, { psqlDockerContainer, acceptChangesReason } = {}) {
  if (!pid)
    throw new LocalRuntimeConfigError(
      "Usage: node scripts/local-runtime.mjs adopt --pid <wsl-pid> [--psql-docker-container NAME] [--accept-changes REASON]",
      "INVALID_ARGUMENT",
    );
  const paths = runtimeConfigPaths(root);
  const originals = new Map(
    [paths.configFile, paths.pinFile, paths.inventoryFile].map((file) => [
      file,
      existsSync(file) ? readFileSync(file) : null,
    ]),
  );
  const adopted = await adoptRunningBackend({ root, pid, psqlDockerContainer });
  const pin = createPinMetadata(root, adopted.config, { artifact: adopted.artifact });
  const guard = await import("./local-plugin-inventory.mjs");
  if (typeof guard.captureLocalPluginInventory !== "function")
    throw new LocalRuntimeConfigError("The local plugin inventory capture API is unavailable.", "PLUGIN_GUARD_API_MISSING");
  try {
    const backups = backupAndWriteRuntimeConfig(root, adopted.config, pin);
    await guard.captureLocalPluginInventory({
      root,
      config: adopted.config,
      acceptChangesReason,
    });
    await guard.assertLocalPluginInventory({ root, config: adopted.config });
    console.log(`Pinned the verified local backend process ${adopted.pid}.`);
    if (backups.backup) console.log(`Previous canonical config backed up to ${path.relative(root, backups.backup)}.`);
    console.log(`Backend artifact pinned at ${pin.artifact.path} (SHA-256 ${pin.artifact.sha256}).`);
    console.log("Captured and verified the local database/plugin inventory.");
  } catch (error) {
    try {
      restoreRuntimeFiles(originals);
    } catch (rollbackError) {
      throw new LocalRuntimeConfigError(
        `${error.message} Rollback also failed: ${rollbackError.message}`,
        "LOCAL_RUNTIME_ROLLBACK_FAILED",
      );
    }
    throw error;
  }
}

async function capture(acceptChangesReason) {
  const runtime = readLocalRuntime(root);
  if (!runtime.pinned)
    throw new LocalRuntimeConfigError("Capture requires an adopted local runtime pin.", "LOCAL_RUNTIME_NOT_PINNED");
  const guard = await import("./local-plugin-inventory.mjs");
  if (typeof guard.captureLocalPluginInventory !== "function")
    throw new LocalRuntimeConfigError("The local plugin inventory capture API is unavailable.", "PLUGIN_GUARD_API_MISSING");
  await guard.captureLocalPluginInventory({
    root,
    config: runtime.config,
    acceptChangesReason,
  });
  console.log("Captured the pinned source database and plugin inventory in .runtime/local-plugin-inventory.json.");
}

function mergeTargetConfig(sourceConfig, targetConfig) {
  const merged = { ...sourceConfig, ...targetConfig };
  merged.LUMIRA_LOCAL_RUNTIME_PIN_VERSION = "1";
  const port = String(merged.LUMIRA_PORT ?? "8090");
  merged.LUMIRA_API_ORIGIN = `http://127.0.0.1:${port}`;
  return merged;
}

async function switchRuntime(targetConfigPath) {
  if (!targetConfigPath)
    throw new LocalRuntimeConfigError(
      "Usage: node scripts/local-runtime.mjs switch --target-config <path>",
      "INVALID_ARGUMENT",
    );
  const current = readLocalRuntime(root);
  if (!current.pinned)
    throw new LocalRuntimeConfigError("Runtime switch requires an adopted source pin.", "LOCAL_RUNTIME_NOT_PINNED");
  const resolvedTargetConfig = path.isAbsolute(targetConfigPath)
    ? targetConfigPath
    : path.resolve(root, targetConfigPath);
  const targetConfig = mergeTargetConfig(current.config, parseLocalConfigFile(resolvedTargetConfig));
  validatePinnedConfig(root, targetConfig);
  await assertServicesStopped(current.config, targetConfig);

  const inventoryPath = current.inventoryFile;
  const guard = await import("./local-plugin-inventory.mjs");
  if (
    typeof guard.verifyLocalPluginInventoryTransfer !== "function" ||
    typeof guard.writeLocalPluginInventorySnapshot !== "function" ||
    typeof guard.assertLocalPluginInventory !== "function"
  )
    throw new LocalRuntimeConfigError("The local plugin inventory transfer API is unavailable.", "PLUGIN_GUARD_API_MISSING");
  const transfer = await guard.verifyLocalPluginInventoryTransfer({
    root,
    sourceConfig: current.config,
    targetConfig,
  });
  if (transfer?.verified !== true || !transfer.snapshotForTarget || !transfer.sourceSnapshotDigest)
    throw new LocalRuntimeConfigError("The plugin inventory guard did not verify the target database.", "PLUGIN_TRANSFER_NOT_VERIFIED");

  const nextPin = createPinMetadata(root, targetConfig, {
    artifact: current.pin.artifact ?? null,
  });
  const originals = new Map(
    [current.configFile, current.pinFile, inventoryPath].map((file) => [
      file,
      existsSync(file) ? readFileSync(file) : null,
    ]),
  );
  const backups = [];
  for (const file of originals.keys()) {
    if (existsSync(file)) backups.push(copyLocalJsonBackup(root, file, "runtime-switch"));
  }
  try {
    writeLocalJson(root, ".runtime/local.json", targetConfig);
    writeLocalJson(root, ".runtime/local-runtime.json", nextPin);
    await guard.writeLocalPluginInventorySnapshot({
      root,
      snapshot: transfer.snapshotForTarget,
      expectedSourceDigest: transfer.sourceSnapshotDigest,
    });
    await assertServicesStopped(targetConfig);
    const verified = readLocalRuntime(root);
    if (verified.pin.configSha256 !== configFingerprint(verified.config))
      throw new LocalRuntimeConfigError("The promoted runtime pin did not match the canonical config.", "RUNTIME_PROMOTION_FAILED");
    await guard.assertLocalPluginInventory({ root, config: verified.config });
  } catch (error) {
    try {
      restoreRuntimeFiles(originals);
    } catch (rollbackError) {
      throw new LocalRuntimeConfigError(
        `${error.message} Rollback also failed: ${rollbackError.message}`,
        "LOCAL_RUNTIME_ROLLBACK_FAILED",
      );
    }
    throw error;
  }
  console.log("Verified and promoted the restored database and plugin inventory.");
  console.log(`Protected backups created: ${backups.length}.`);
}

async function refreshArtifact(jarPath, expectedSha256) {
  if (!jarPath || !/^[a-f0-9]{64}$/i.test(String(expectedSha256 ?? "")))
    throw new LocalRuntimeConfigError(
      "Usage: node scripts/local-runtime.mjs refresh-artifact --jar <workspace-jar> --sha256 <64-hex-sha256>",
      "INVALID_ARGUMENT",
    );
  const current = readLocalRuntime(root);
  if (!current.pinned)
    throw new LocalRuntimeConfigError("Artifact refresh requires an adopted local runtime pin.", "LOCAL_RUNTIME_NOT_PINNED");
  await assertServicesStopped(current.config);
  const candidate = path.isAbsolute(jarPath) ? jarPath : path.resolve(root, jarPath);
  const relativeJar = relativeWorkspacePath(root, candidate).split(path.sep).join("/");
  const actualSha256 = sha256File(candidate);
  if (actualSha256 !== expectedSha256.toLowerCase())
    throw new LocalRuntimeConfigError("The selected backend JAR does not match the supplied SHA-256.", "BACKEND_JAR_CHECKSUM_MISMATCH");

  const oldPin = readFileSync(current.pinFile);
  copyLocalJsonBackup(root, current.pinFile, "artifact-refresh");
  try {
    const nextPin = createPinMetadata(root, current.config, {
      artifact: { path: relativeJar, sha256: actualSha256 },
    });
    writeLocalJson(root, ".runtime/local-runtime.json", nextPin);
    const verified = readLocalRuntime(root);
    if (verified.pin.artifact?.path !== relativeJar || verified.pin.artifact?.sha256 !== actualSha256)
      throw new LocalRuntimeConfigError("The refreshed backend artifact pin did not verify.", "ARTIFACT_REFRESH_FAILED");
  } catch (error) {
    restoreRuntimeFile(current.pinFile, oldPin);
    throw error;
  }
  console.log(`Pinned backend artifact ${relativeJar} (SHA-256 ${actualSha256}).`);
}

async function main() {
  const { command, options } = parseArguments(process.argv.slice(2));
  if (command === "adopt")
    return withLocalRuntimeLock(root, command, () => adopt(options.pid, {
      psqlDockerContainer: options.psqldockercontainer,
      acceptChangesReason: options.acceptchanges,
    }));
  if (command === "capture") return withLocalRuntimeLock(root, command, () => capture(options.acceptchanges));
  if (command === "switch") return withLocalRuntimeLock(root, command, () => switchRuntime(options.targetconfig));
  if (command === "refresh-artifact") return withLocalRuntimeLock(root, command, () => refreshArtifact(options.jar, options.sha256));
  throw new LocalRuntimeConfigError(
    "Usage: node scripts/local-runtime.mjs <adopt --pid PID | capture [--accept-changes REASON] | switch --target-config PATH | refresh-artifact --jar PATH --sha256 SHA>",
    "INVALID_COMMAND",
  );
}

try {
  await main();
} catch (error) {
  const message = error instanceof Error ? error.message : "Unknown local runtime failure.";
  console.error(`Local runtime operation failed: ${message}`);
  process.exitCode = 1;
}
