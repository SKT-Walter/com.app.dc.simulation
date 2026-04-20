#!/usr/bin/env bash
set -euo pipefail

DEPLOY_ROOT="${DEPLOY_ROOT:-/srv/dc}"
SERVICE_NAME="${SERVICE_NAME:-SIMSvr}"
MAIN_CLASS="${MAIN_CLASS:-SIMSvr}"

SERVICE_DIR="${DEPLOY_ROOT}/dc/${SERVICE_NAME}"
EXT_DIR="${DEPLOY_ROOT}/tpc/tpc"

cd "${SERVICE_DIR}"

mkdir -p "${DEPLOY_ROOT}/data" "${DEPLOY_ROOT}/log/${SERVICE_NAME}"

if [ ! -e "${SERVICE_DIR}/data" ]; then
  ln -s "${DEPLOY_ROOT}/data" "${SERVICE_DIR}/data"
fi

if [ ! -e "${SERVICE_DIR}/log" ]; then
  ln -s "${DEPLOY_ROOT}/log" "${SERVICE_DIR}/log"
fi

if [ -d "${EXT_DIR}" ]; then
  export LD_LIBRARY_PATH="${EXT_DIR}:${LD_LIBRARY_PATH:-}"
fi

JAVA_OPTS_DEFAULT="-server -Xmx2048m -Xms2048m -Xmn512m -Djava.security.auth.login.config=../../control/jaas.ini"
JAVA_OPTS="${JAVA_OPTS:-$JAVA_OPTS_DEFAULT}"

CLASSPATH="classes:config:lib/*"
if [ -d "${EXT_DIR}" ]; then
  CLASSPATH="${CLASSPATH}:${EXT_DIR}/*"
fi

exec java \
  ${JAVA_OPTS} \
  ${EXTRA_JAVA_OPTS:-} \
  -Duser.home="${DEPLOY_ROOT}" \
  -Duser.dir="${SERVICE_DIR}" \
  -Djava.library.path="${EXT_DIR}" \
  -cp "${CLASSPATH}" \
  "${MAIN_CLASS}" \
  ${APP_ARGS:-}
