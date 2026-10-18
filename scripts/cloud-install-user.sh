#!/usr/bin/env bash
# User-space toolchains for disposable Ubuntu/Debian cloud VMs without sudo.
set -euo pipefail
[[ $(uname -s) == Linux && ${LUMIRA_CLOUD_DEV:-} == 1 && $EUID != 0 ]] || {
  echo 'User-space installation requires an unprivileged disposable Linux VM.' >&2; exit 1;
}
source /etc/os-release
[[ ${ID:-} == ubuntu || ${ID:-} == debian ]] || { echo 'Ubuntu or Debian is required.' >&2; exit 1; }
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
default_tools="$HOME/.local/share/lumira-cloud"
[[ ! -d /workspace || ! -w /workspace ]] || default_tools=/workspace/lumira-tools/linux
tools=${LUMIRA_CLOUD_TOOLS_DIR:-$default_tools}
mkdir -p "$tools"
tools=$(cd "$tools" && pwd)
case "$tools/" in "$root/"*) echo 'Keep cloud toolchains outside the source checkout.' >&2; exit 1 ;; esac
for command in curl python3 tar sha256sum gcc make perl dpkg-deb apt-get; do
  command -v "$command" >/dev/null || { echo "Required VM tool missing: $command" >&2; exit 1; }
done
case $(uname -m) in x86_64) arch=x64 ;; aarch64|arm64) arch=aarch64 ;; *) exit 1 ;; esac
work=$(mktemp -d "$tools/install.XXXXXX")
trap 'rm -rf "$work"' EXIT
# PDFBox does not scan XDG's user font directory. Fetch the font from the
# distribution's signed repository and place a uniquely named copy in ~/.fonts.
apt_dir="$tools/apt"
mkdir -p "$apt_dir/lists/partial" "$apt_dir/cache/archives/partial" "$apt_dir/state" "$work/font-deb"
touch "$apt_dir/state/status"
if [[ $ID == debian ]]; then
  key=/usr/share/keyrings/debian-archive-keyring.gpg
  mirror=https://deb.debian.org/debian
  components=main
else
  key=/usr/share/keyrings/ubuntu-archive-keyring.gpg
  mirror=https://archive.ubuntu.com/ubuntu
  [[ $arch != aarch64 ]] || mirror=https://ports.ubuntu.com/ubuntu-ports
  components='main universe'
fi
[[ -r $key ]] || { echo 'Official distribution archive signing key is unavailable.' >&2; exit 1; }
apt_arch=$(dpkg --print-architecture)
printf 'deb [arch=%s signed-by=%s] %s %s %s\n' "$apt_arch" "$key" "$mirror" "$VERSION_CODENAME" "$components" > "$apt_dir/sources.list"
cat > "$apt_dir/apt.conf" <<APT
Dir::Etc::main "-";
Dir::Etc::parts "-";
Dir::Etc::sourcelist "$apt_dir/sources.list";
Dir::Etc::sourceparts "-";
Dir::State "$apt_dir/state";
Dir::State::lists "$apt_dir/lists";
Dir::State::status "$apt_dir/state/status";
Dir::Cache "$apt_dir/cache";
Dir::Cache::archives "$apt_dir/cache/archives";
APT::Sandbox::User "$(id -un)";
APT::Get::AllowUnauthenticated "false";
Acquire::AllowInsecureRepositories "false";
APT
APT_CONFIG="$apt_dir/apt.conf" apt-get update
(cd "$work/font-deb" && APT_CONFIG="$apt_dir/apt.conf" apt-get download fonts-wqy-zenhei)
font_package=$(find "$work/font-deb" -maxdepth 1 -type f -name 'fonts-wqy-zenhei_*.deb' -print -quit)
[[ -n $font_package ]] || { echo 'Signed fonts-wqy-zenhei package was not downloaded.' >&2; exit 1; }
font_package_root="$tools/font-packages/fonts-wqy-zenhei"
mkdir -p "$font_package_root"
dpkg-deb -x "$font_package" "$font_package_root"
source_font="$font_package_root/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
[[ -s $source_font && -s "$font_package_root/usr/share/doc/fonts-wqy-zenhei/copyright" ]] || {
  echo 'The downloaded WQY font package is missing its font or license.' >&2; exit 1;
}
font_target="$HOME/.fonts/lumira-wqy-zenhei.ttc"
font_marker="$tools/font-packages/fonts-wqy-zenhei/user-font.sha256"
mkdir -p "$HOME/.fonts"
new_font_sha=$(sha256sum "$source_font" | awk '{print $1}')
if [[ -L $font_target ]]; then
  echo "Refusing to replace a symbolic link at $font_target." >&2; exit 1
fi
if [[ -e $font_target ]]; then
  if ! cmp -s "$source_font" "$font_target"; then
    existing_font_sha=$(sha256sum "$font_target" | awk '{print $1}')
    [[ -r $font_marker && $(cat "$font_marker") == "$existing_font_sha" ]] || {
      echo "Refusing to replace an unrelated font at $font_target." >&2; exit 1;
    }
    install -m 644 "$source_font" "$font_target"
  fi
else
  install -m 644 "$source_font" "$font_target"
fi
printf '%s\n' "$new_font_sha" > "$font_marker"
export PATH="$tools/node/bin:$tools/pnpm/bin:$tools/build-tools/usr/bin:$PATH"
# Persist relocated Bison data lookup when retrying with cached build tools.
if [[ -x $tools/build-tools/usr/bin/bison ]]; then
  export BISON_PKGDATADIR="$tools/build-tools/usr/share/bison"
fi
if [[ -x $tools/build-tools/usr/bin/m4 ]]; then
  export M4="$tools/build-tools/usr/bin/m4"
fi
if ! command -v node >/dev/null || [[ $(node -p 'process.versions.node.split(".")[0]') != 24 ]]; then
  node_arch=$arch; [[ $arch != aarch64 ]] || node_arch=arm64
  curl -fsSL --retry 3 https://nodejs.org/dist/latest-v24.x/SHASUMS256.txt -o "$work/SHASUMS256.txt"
  archive=$(awk -v arch="$node_arch" '$2 ~ ("^node-v24\\..*-linux-" arch "\\.tar\\.xz$") {print $2}' "$work/SHASUMS256.txt")
  [[ -n $archive && $archive != *$'\n'* ]]
  curl -fsSL --retry 3 "https://nodejs.org/dist/latest-v24.x/$archive" -o "$work/$archive"
  (cd "$work" && awk -v archive="$archive" '$2 == archive' SHASUMS256.txt | sha256sum -c -)
  mkdir -p "$tools/node"
  tar -xJf "$work/$archive" --strip-components=1 -C "$tools/node"
fi
if [[ ! -x $tools/jdk/bin/javac ]] || [[ $("$tools/jdk/bin/javac" -version 2>&1) != javac\ 25* ]]; then
  curl -fsSL --retry 3 "https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=$arch&image_type=jdk&os=linux&vendor=eclipse" -o "$work/jdk.json"
  python3 - "$work/jdk.json" "$work/jdk-package" <<'PY'
import json, re, sys
from urllib.parse import urlparse
p = json.load(open(sys.argv[1]))[0]['binary']['package']
assert urlparse(p['link']).scheme == 'https' and urlparse(p['link']).hostname == 'github.com'
assert re.fullmatch(r'[a-f0-9]{64}', p['checksum'])
open(sys.argv[2], 'w').write(p['link'] + '\n' + p['checksum'] + '\n')
PY
  mapfile -t package < "$work/jdk-package"
  curl -fsSL --retry 3 "${package[0]}" -o "$work/jdk.tar.gz"
  printf '%s  %s\n' "${package[1]}" "$work/jdk.tar.gz" | sha256sum -c -
  mkdir -p "$tools/jdk"
  tar -xzf "$work/jdk.tar.gz" --strip-components=1 -C "$tools/jdk"
fi
export JAVA_HOME="$tools/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
if ! command -v pnpm >/dev/null || [[ $(pnpm --version) != 10.33.0 ]]; then
  npm install --cache "$tools/npm-cache" -g --prefix "$tools/pnpm" pnpm@10.33.0
fi
if [[ ! -x $tools/postgresql/bin/postgres ]] || [[ $("$tools/postgresql/bin/postgres" --version) != 'postgres (PostgreSQL) 18.6' ]]; then
  # Download from the VM's signed Debian/Ubuntu apt indexes; extract without
  # installing packages or altering the system. Bison needs its relocated data.
  if ! command -v bison >/dev/null || ! command -v flex >/dev/null || ! command -v m4 >/dev/null; then
    mkdir -p "$work/deb" "$tools/build-tools"
    (cd "$work/deb" && APT_CONFIG="$apt_dir/apt.conf" apt-get download bison flex m4)
    for deb in "$work/deb/"*.deb; do dpkg-deb -x "$deb" "$tools/build-tools"; done
    export BISON_PKGDATADIR="$tools/build-tools/usr/share/bison"
    export M4="$tools/build-tools/usr/bin/m4"
  fi
  command -v bison >/dev/null
  command -v flex >/dev/null
  command -v m4 >/dev/null
  curl -fsSL --retry 3 https://ftp.postgresql.org/pub/source/v18.6/postgresql-18.6.tar.bz2 -o "$work/postgresql.tar.bz2"
  printf '555610c24d53e4316da5b7d3fc25c279d96856d5e0e23ee308c328c5fa881d9f  %s\n' "$work/postgresql.tar.bz2" | sha256sum -c -
  tar -xjf "$work/postgresql.tar.bz2" -C "$work"
  (cd "$work/postgresql-18.6" && ./configure --prefix="$tools/postgresql" --without-icu --without-readline && make -j "${LUMIRA_CLOUD_BUILD_JOBS:-4}" && make install)
fi
mkdir -p .runtime
chmod 700 .runtime
touch .runtime/cloud-development
node_bin=$(dirname "$(readlink -f "$(command -v node)")")
printf 'export JAVA_HOME=%q\nexport PATH=%q:"$PATH"\nexport LUMIRA_CLOUD_PG_MODE=user\nexport LUMIRA_CLOUD_PG_HOME=%q\nexport PSQL_BIN=%q\n' "$JAVA_HOME" "$tools/pnpm/bin:$node_bin:$JAVA_HOME/bin:$tools/postgresql/bin" "$tools/postgresql" "$tools/postgresql/bin/psql" > .runtime/cloud.env
# Keep the prepared workspace's existing store rather than forcing a reinstall.
pnpm_store=$(python3 scripts/cloud-pnpm-store.py node_modules/.modules.yaml "$tools/pnpm-store")
mkdir -p "$tools/cache" "$tools/npm-cache" "$tools/pnpm-cache" "$tools/maven/repository"
printf 'export XDG_CACHE_HOME=%q\nexport npm_config_cache=%q\nexport npm_config_cache_dir=%q\nexport npm_config_store_dir=%q\n' "$tools/cache" "$tools/npm-cache" "$tools/pnpm-cache" "$pnpm_store" >> .runtime/cloud.env
printf 'export MAVEN_USER_HOME=%q\nexport LUMIRA_CLOUD_MAVEN_REPO=%q\n' "$tools/maven" "$tools/maven/repository" >> .runtime/cloud.env
maven_mirror=${LUMIRA_CLOUD_MAVEN_MIRROR:-https://maven-central.storage-download.googleapis.com/maven2}
printf 'export MVNW_REPOURL=%q\nexport LUMIRA_CLOUD_MAVEN_SETTINGS=%q\n' "$maven_mirror" "$tools/maven/settings.xml" >> .runtime/cloud.env
chmod 600 .runtime/cloud.env
source scripts/cloud-env.sh
pnpm install --frozen-lockfile
bash scripts/cloud-database.sh
pnpm typecheck
pnpm test
pnpm build
bash scripts/cloud-check.sh backend
echo 'Cloud dependency and build checks passed. Run bash scripts/cloud-start.sh to verify services.'
