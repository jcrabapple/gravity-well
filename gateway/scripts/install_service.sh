#!/usr/bin/env bash
set -euo pipefail

# Install Gravity Well Gateway as a systemd --user service.
# Supports --no-enable and --dry-run for isolated verification without touching live systemd.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
GATEWAY_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
UNIT_SRC="${GATEWAY_DIR}/systemd/gravity-well-gateway.service"

PYTHON_BIN="${GRAVITY_WELL_PYTHON_BIN:-/usr/bin/python3}"
UVICORN_BIN="${GATEWAY_DIR}/.venv/bin/uvicorn"

CONFIG_HOME="${XDG_CONFIG_HOME:-${HOME}/.config}"
GW_CONFIG_DIR="${CONFIG_HOME}/gravity-well"
GW_ENV_FILE="${GW_CONFIG_DIR}/gateway.env"
SYSTEMD_USER_DIR="${CONFIG_HOME}/systemd/user"
UNIT_DEST="${SYSTEMD_USER_DIR}/gravity-well-gateway.service"

ENABLE_SERVICE=1
DRY_RUN=0

for arg in "$@"; do
  case "$arg" in
    --no-enable)
      ENABLE_SERVICE=0
      ;;
    --dry-run)
      DRY_RUN=1
      ENABLE_SERVICE=0
      ;;
    -h|--help)
      echo "Usage: $0 [--no-enable] [--dry-run]"
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      exit 2
      ;;
  esac
done

if [[ ! -x "${PYTHON_BIN}" ]]; then
  echo "Expected Python interpreter at ${PYTHON_BIN}" >&2
  exit 1
fi

if [[ ! -f "${UNIT_SRC}" ]]; then
  echo "Missing systemd unit template at ${UNIT_SRC}" >&2
  exit 1
fi

if [[ "${DRY_RUN}" -eq 0 && ! -x "${UVICORN_BIN}" ]]; then
  echo "Installing gateway virtualenv pinned to ${PYTHON_BIN}..."
  (cd "${GATEWAY_DIR}" && env -u PYTHONPATH uv sync --python "${PYTHON_BIN}")
fi

if [[ "${DRY_RUN}" -eq 1 ]]; then
  echo "[dry-run] Would ensure ${GW_CONFIG_DIR} (0700) and ${GW_ENV_FILE} (0600)"
  echo "[dry-run] Would install ${UNIT_SRC} -> ${UNIT_DEST}"
  exit 0
fi

install -d -m 0700 "${GW_CONFIG_DIR}"
if [[ ! -f "${GW_ENV_FILE}" ]]; then
  TOKEN="$("${PYTHON_BIN}" -c 'import secrets; print(secrets.token_urlsafe(32))')"
  umask 0177
  {
    printf 'GRAVITY_WELL_TOKEN=%s\n' "${TOKEN}"
    printf 'GRAVITY_WELL_HOST=0.0.0.0\n'
    printf 'GRAVITY_WELL_PORT=9340\n'
  } > "${GW_ENV_FILE}"
  chmod 0600 "${GW_ENV_FILE}"
  echo "Initialized ${GW_ENV_FILE} (0600)."
fi

install -d -m 0755 "${SYSTEMD_USER_DIR}"
sed \
  -e "s|/home/jason/projects/gravity-well/gateway|${GATEWAY_DIR}|g" \
  "${UNIT_SRC}" > "${UNIT_DEST}"
chmod 0644 "${UNIT_DEST}"

if command -v systemd-analyze >/dev/null 2>&1; then
  systemd-analyze verify --user "${UNIT_DEST}" 2>/dev/null || true
fi

if [[ "${ENABLE_SERVICE}" -eq 1 ]]; then
  systemctl --user daemon-reload
  systemctl --user enable --now gravity-well-gateway.service
  systemctl --user status --no-pager gravity-well-gateway.service || true
else
  echo "Installed unit to ${UNIT_DEST} (enable skipped)."
fi
