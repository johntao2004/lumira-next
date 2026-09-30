#!/usr/bin/env bash
# Source this file before manual cloud commands; exports persist in this shell only.
lumira_cloud_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
[[ $(uname -s) == Linux && -f "$lumira_cloud_root/.runtime/cloud-development" ]] || {
  echo 'Cloud commands require a prepared Linux development VM.' >&2
  return 1 2>/dev/null || exit 1
}
source "$lumira_cloud_root/.runtime/cloud.env"
export PSQL_BIN=${PSQL_BIN:-/usr/lib/postgresql/18/bin/psql}
export LUMIRA_COOKIE_SECURE=false
[[ $(node -p 'process.versions.node.split(".")[0]') == 24 ]]
[[ $("$JAVA_HOME/bin/javac" -version 2>&1) == javac\ 25* ]]

if [[ -n ${LUMIRA_CLOUD_MAVEN_SETTINGS:-} ]]; then
  python3 "$lumira_cloud_root/scripts/cloud-maven-settings.py"
fi
