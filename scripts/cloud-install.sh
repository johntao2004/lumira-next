#!/usr/bin/env bash
# Install only in a disposable Codex Ubuntu VM, never on the local Mac.
set -euo pipefail
[[ $(uname -s) == Linux && ${LUMIRA_CLOUD_DEV:-} == 1 ]] || {
  echo 'Run in a Codex Ubuntu VM: LUMIRA_CLOUD_DEV=1 bash scripts/cloud-install.sh' >&2
  exit 1
}
source /etc/os-release
[[ ${ID:-} == ubuntu ]] || { echo 'This installer requires Ubuntu.' >&2; exit 1; }
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
as_root() { if [[ $EUID == 0 ]]; then "$@"; else sudo -n "$@"; fi; }
as_root env DEBIAN_FRONTEND=noninteractive apt-get update
as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y curl ca-certificates gnupg xz-utils python3
download_dir=$(mktemp -d)
trap 'rm -rf "$download_dir"' EXIT
if ! command -v node >/dev/null || [[ $(node -p 'process.versions.node.split(".")[0]') != 24 ]]; then
  case $(uname -m) in x86_64) node_arch=x64 ;; aarch64|arm64) node_arch=arm64 ;; *) exit 1 ;; esac
  curl -fsSL --retry 3 https://nodejs.org/dist/latest-v24.x/SHASUMS256.txt -o "$download_dir/SHASUMS256.txt"
  node_archive=$(awk -v arch="$node_arch" '$2 ~ ("^node-v24\\..*-linux-" arch "\\.tar\\.xz$") {print $2}' "$download_dir/SHASUMS256.txt")
  [[ -n $node_archive && $node_archive != *$'\n'* ]]
  curl -fsSL --retry 3 "https://nodejs.org/dist/latest-v24.x/$node_archive" -o "$download_dir/$node_archive"
  (cd "$download_dir" && awk -v archive="$node_archive" '$2 == archive' SHASUMS256.txt | sha256sum -c -)
  as_root install -d /opt/lumira-cloud/node
  as_root tar -xJf "$download_dir/$node_archive" --strip-components=1 -C /opt/lumira-cloud/node
  export PATH="/opt/lumira-cloud/node/bin:$PATH"
fi
as_root install -d /usr/share/keyrings
curl -fsSL --retry 3 https://packages.adoptium.net/artifactory/api/gpg/key/public -o "$download_dir/adoptium.asc"
gpg --batch --yes --dearmor -o "$download_dir/adoptium.gpg" "$download_dir/adoptium.asc"
as_root install -m 644 "$download_dir/adoptium.gpg" /usr/share/keyrings/lumira-adoptium.gpg
printf 'deb [signed-by=/usr/share/keyrings/lumira-adoptium.gpg] https://packages.adoptium.net/artifactory/deb %s main\n' "$VERSION_CODENAME" > "$download_dir/adoptium.list"
as_root install -m 644 "$download_dir/adoptium.list" /etc/apt/sources.list.d/lumira-adoptium.list
curl -fsSL --retry 3 https://www.postgresql.org/media/keys/ACCC4CF8.asc -o "$download_dir/postgresql.asc"
as_root install -m 644 "$download_dir/postgresql.asc" /usr/share/keyrings/lumira-postgresql.asc
printf 'deb [signed-by=/usr/share/keyrings/lumira-postgresql.asc] https://apt.postgresql.org/pub/repos/apt %s-pgdg main\n' "$VERSION_CODENAME" > "$download_dir/pgdg.list"
as_root install -m 644 "$download_dir/pgdg.list" /etc/apt/sources.list.d/lumira-pgdg.list
as_root env DEBIAN_FRONTEND=noninteractive apt-get update
as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y temurin-25-jdk postgresql-18 postgresql-client-18
export JAVA_HOME="/usr/lib/jvm/temurin-25-jdk-$(dpkg --print-architecture)"
[[ -x $JAVA_HOME/bin/javac ]]
export PATH="$JAVA_HOME/bin:$PATH"
if ! command -v pnpm >/dev/null || [[ $(pnpm --version) != 10.33.0 ]]; then
  as_root env PATH="$PATH" "$(command -v npm)" install -g --prefix /opt/lumira-cloud/pnpm pnpm@10.33.0
  export PATH="/opt/lumira-cloud/pnpm/bin:$PATH"
fi
mkdir -p .runtime
chmod 700 .runtime
# The marker makes startup refuse the local Mac and an unprepared server.
touch .runtime/cloud-development
node_bin=$(dirname "$(readlink -f "$(command -v node)")")
printf 'export JAVA_HOME=%q\nexport PATH=%q:"$PATH"\n' "$JAVA_HOME" "/opt/lumira-cloud/pnpm/bin:$node_bin:$JAVA_HOME/bin" > .runtime/cloud.env
chmod 600 .runtime/cloud.env
source scripts/cloud-env.sh
pnpm install --frozen-lockfile
bash scripts/cloud-database.sh
pnpm typecheck
pnpm test
pnpm build
bash scripts/cloud-check.sh backend
echo 'Cloud dependencies and build checks passed. Run bash scripts/cloud-start.sh to verify services.'
