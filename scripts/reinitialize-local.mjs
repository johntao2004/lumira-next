import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const runtime = path.join(root, ".runtime");
const configPath = path.join(runtime, "local.json");
if (process.env.LUMIRA_ALLOW_LOCAL_DB_RESET !== "1") {
  throw new Error(
    "Refusing to reset the database. Set LUMIRA_ALLOW_LOCAL_DB_RESET=1 after stopping the scaffold.",
  );
}
if (!existsSync(configPath))
  throw new Error(".runtime/local.json is missing; run node scripts/init-local.mjs first.");

const config = JSON.parse(readFileSync(configPath, "utf8"));
const jdbc = String(config.LUMIRA_DB_URL ?? "").replace(/^jdbc:/, "");
const target = new URL(jdbc);
const database = decodeURIComponent(target.pathname.replace(/^\//, ""));
if (database !== "lumira_next")
  throw new Error("Refusing to reset a non-local target database; expected lumira_next.");
const dbUser = config.LUMIRA_DB_USER ?? "";
const dbPassword = config.LUMIRA_DB_PASSWORD ?? "";
const host = target.hostname || "127.0.0.1";
const port = target.port || "5432";
const stamp = new Date().toISOString().replace(/[-:TZ.]/g, "").slice(0, 14);
const backupDir = path.join(runtime, "backups");
const backup = path.join(backupDir, `${database}-${stamp}.dump`);
mkdirSync(backupDir, { recursive: true, mode: 0o700 });

const run = (command, args, extraEnv = {}) => {
  const result = spawnSync(command, args, {
    cwd: root,
    env: { ...process.env, ...extraEnv },
    stdio: "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} failed with exit code ${result.status}`);
};

// Backup uses the application credentials. Reset uses the local PostgreSQL
// administrator by default; remote administrators can provide the optional
// LUMIRA_DB_ADMIN_* variables without placing them in the repository.
run(
  process.env.PG_DUMP_BIN ?? "pg_dump",
  ["--format=custom", "--no-owner", "--no-privileges", "--host", host, "--port", port, "--username", dbUser, "--file", backup, database],
  { PGPASSWORD: dbPassword },
);
console.log(`Backup written to ${path.relative(root, backup)}`);

const adminUser = process.env.LUMIRA_DB_ADMIN_USER;
const adminHost = process.env.LUMIRA_DB_ADMIN_HOST;
const adminPort = process.env.LUMIRA_DB_ADMIN_PORT;
const adminDatabase = process.env.LUMIRA_DB_ADMIN_DATABASE ?? "postgres";
const adminPassword = process.env.LUMIRA_DB_ADMIN_PASSWORD;
const adminArgs = [];
if (adminUser) adminArgs.push("--username", adminUser);
if (adminHost) adminArgs.push("--host", adminHost);
if (adminPort) adminArgs.push("--port", adminPort);
adminArgs.push("--maintenance-db", adminDatabase);
const adminEnv = adminPassword ? { PGPASSWORD: adminPassword } : {};
run(process.env.DROPDB_BIN ?? "dropdb", [...adminArgs, "--if-exists", database], adminEnv);
run(
  process.env.CREATEDB_BIN ?? "createdb",
  [...adminArgs, "--owner", dbUser, database],
  adminEnv,
);
console.log(`Database ${database} recreated. Flyway will import the single baseline on next startup.`);
