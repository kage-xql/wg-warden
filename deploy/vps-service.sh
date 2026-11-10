#!/usr/bin/env bash
# Управление VPS-запуском wg-warden.
set -euo pipefail

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$PROJECT_DIR"

if [ ! -f .env.vps ]; then
  echo "Не найден .env.vps. Сначала выполните ./deploy/vps-install.sh"
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "Docker недоступен. Запустите Docker Engine и повторите команду."
  exit 1
fi

env_value() {
  sed -n "s|^$1=||p" .env.vps | tail -n 1
}

normalize_domain() {
  local value="$1"
  value="${value// /}"
  value="${value#http://}"
  value="${value#https://}"
  value="${value%%/*}"
  value="${value%:443}"
  value="${value%:80}"
  value="${value%.}"
  printf '%s' "${value,,}"
}

is_ipv4() {
  local value="$1" octet
  [[ "$value" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]] || return 1
  for octet in "${BASH_REMATCH[@]:1}"; do
    [ "$octet" -le 255 ] || return 1
  done
  return 0
}

app_domain="$(normalize_domain "$(env_value APP_DOMAIN)")"
# Даже если в .env.vps домен записан как https://..., compose должен получить чистое имя.
export APP_DOMAIN="$app_domain"

if [ -n "$app_domain" ]; then
  COMPOSE_FILE="docker-compose.tls.yml"
  tls_mode="$(env_value TLS_MODE)"
  tls_mode="${tls_mode:-acme}"
  tls_mode="${tls_mode,,}"
  if is_ipv4 "$app_domain" && [ "$tls_mode" = "acme" ]; then
    echo "Внимание: для IP-адреса используется TLS_MODE=internal. Запустите ./deploy/vps-install.sh, чтобы исправить .env.vps." >&2
  fi
  https_port="$(env_value HTTPS_PORT)"
  https_port="${https_port:-443}"
  address="https://${app_domain}"
  [ "$https_port" = "443" ] || address="${address}:${https_port}"
else
  COMPOSE_FILE="docker-compose.vps.yml"
  web_port="$(env_value WEB_PORT)"
  web_port="${web_port:-80}"
  address="http://$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}')"
  [ "$web_port" = "80" ] || address="${address}:${web_port}"
fi

compose() {
  docker compose -f "$COMPOSE_FILE" --env-file .env.vps "$@"
}

# При смене APP_DOMAIN (HTTP ↔ HTTPS) compose переключает файл стека и сеть.
# Старые контейнеры нужно удалить до up, иначе Docker не сможет их переподключить.
prepare_stack() {
  local current
  current="$(docker ps -a \
    --filter "label=com.docker.compose.project=wg-warden" \
    --format '{{.Label "com.docker.compose.project.config_files"}}' 2>/dev/null | head -n 1)"
  if [ -n "$current" ] && [ "${current##*/}" != "$COMPOSE_FILE" ]; then
    echo "Переключаю стек ${current##*/} → ${COMPOSE_FILE}..."
    compose down --remove-orphans >/dev/null 2>&1 || true
  fi
}

case "${1:-}" in
  start)
    prepare_stack
    compose up -d --remove-orphans
    echo "wg-warden включён: ${address}"
    ;;
  stop)
    compose stop
    echo "wg-warden выключен. Данные сохранены."
    ;;
  restart)
    prepare_stack
    # --force-recreate применяет и изменённые .env.vps, и правки в Caddyfile:
    # bind-mount сам по себе не перезапускает контейнер.
    compose up -d --force-recreate --remove-orphans
    echo "wg-warden применён: ${address}"
    ;;
  status)
    compose ps
    ;;
  logs)
    compose logs -f
    ;;
  *)
    echo "Использование: ./deploy/vps-service.sh {start|stop|restart|status|logs}"
    exit 2
    ;;
esac
