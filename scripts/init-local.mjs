import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { randomBytes } from "node:crypto";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { readLocalRuntime, withLocalRuntimeLock } from "./local-runtime-config.mjs";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
await withLocalRuntimeLock(root, "db:prepare", async () => {
const file = path.join(root, ".runtime/local.json");
const localRuntime = readLocalRuntime(root, { requireConfig: false });
if (localRuntime.pinned) {
  console.log(
    `Pinned local database ${localRuntime.database.host}:${localRuntime.database.port}/${localRuntime.database.database} is configured. db:prepare validated the canonical runtime and did not create or switch databases.`,
  );
  return;
}
mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
const config = existsSync(file)
  ? JSON.parse(readFileSync(file, "utf8"))
  : {
      LUMIRA_DB_URL: "jdbc:postgresql://127.0.0.1:5432/lumira_next",
      LUMIRA_DB_USER: "lumira_next",
      LUMIRA_DB_PASSWORD: randomBytes(32).toString("hex"),
      LUMIRA_BOOTSTRAP_PASSWORD: randomBytes(24).toString("base64url"),
      LUMIRA_COOKIE_SECURE: "false",
    };
if (!existsSync(file))
  writeFileSync(file, JSON.stringify(config, null, 2) + "\n", { mode: 0o600 });
if (
  config.LUMIRA_DB_URL !== "jdbc:postgresql://127.0.0.1:5432/lumira_next" ||
  config.LUMIRA_DB_USER !== "lumira_next"
)
  throw new Error(
    "db:prepare only prepares the fresh-clone default lumira_next database. For an existing custom database, adopt the running backend or use the guarded switch workflow; no SQL was run.",
  );
const psql = process.env.PSQL_BIN ?? config.PSQL_BIN ?? "psql";
const dockerContainer = process.env.LUMIRA_PSQL_DOCKER_CONTAINER ?? config.LUMIRA_PSQL_DOCKER_CONTAINER;
function sql(statement) {
  const command = dockerContainer ? "docker" : psql;
  const args = dockerContainer
    ? ["exec", "-i", dockerContainer, "psql", "-X", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-At"]
    : ["-X", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-At"];
  const result = spawnSync(
    command,
    args,
    { input: statement, encoding: "utf8" },
  );
  if (result.status !== 0)
    throw new Error(
      result.stderr || "PostgreSQL client unavailable; set PSQL_BIN",
    );
  return result.stdout.trim();
}
const literal = (s) => "'" + s.replaceAll("'", "''") + "'";
if (!sql("SELECT 1 FROM pg_roles WHERE rolname='lumira_next'"))
  sql(
    `CREATE ROLE lumira_next LOGIN PASSWORD ${literal(config.LUMIRA_DB_PASSWORD)}`,
  );
if (!sql("SELECT 1 FROM pg_database WHERE datname='lumira_next'"))
  sql("CREATE DATABASE lumira_next OWNER lumira_next");
console.log(
  "Independent database lumira_next is ready. Local credentials: .runtime/local.json (mode 0600).",
);
});
