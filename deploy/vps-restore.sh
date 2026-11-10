#!/usr/bin/env bash
# Восстановление базы wg-warden из дампа ./deploy/vps-backup.sh.
# Запуск: ./deploy/vps-restore.sh backups/wg-warden-20260101-120000.sql.gz
set -euo pipefail

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$PROJECT_DIR"

ENV_FILE=".env.vps"
dump="${1:-}"

say() { printf '==> %s\n' "$*"; }
die() { printf 'Ошибка: %s\n' "$*" >&2; exit 1; }

[ -n "$dump" ] || die "укажите файл дампа: ./deploy/vps-restore.sh backups/wg-warden-<дата>.sql.gz"
[ -f "$dump" ] || die "файл не найден: $dump"
[ -f "$ENV_FILE" ] || die "не найден $ENV_FILE"

env_value() {
  sed -n "s|^$1=||p" "$ENV_FILE" | tail -n 1
}

db_user="$(env_value DB_USER)"
db_user="${db_user:-wgwarden}"
app_domain="$(env_value APP_DOMAIN)"
app_domain="${app_domain// /}"
app_domain="${app_domain#http://}"
app_domain="${app_domain#https://}"
app_domain="${app_domain%%/*}"
app_domain="${app_domain%:443}"
app_domain="${app_domain%:80}"
app_domain="${app_domain%.}"
app_domain="${app_domain,,}"
export APP_DOMAIN="$app_domain"
if [ -n "$app_domain" ]; then
  COMPOSE_FILE="docker-compose.tls.yml"
else
  COMPOSE_FILE="docker-compose.vps.yml"
fi

compose() {
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

cat <<WARN
Восстановление заменит текущие данные в базе содержимым $dump.
Серверы, peers, пользователи и метрики, созданные после этого дампа, будут потеряны.
Секреты в дампе расшифровываются только тем APP_MASTER_KEY, с которым он снят:
если .env.vps менялся, восстановите и его (копия лежит рядом с дампом — env-<дата>.vps).
WARN

if [ -t 0 ]; then
  printf 'Продолжить? Введите yes: '
  read -r answer
  [ "$answer" = "yes" ] || die "отменено"
else
  die "восстановление требует подтверждения в терминале (запустите команду вручную)"
fi

# После смены APP_DOMAIN (HTTP ↔ HTTPS) контейнеры принадлежат другому стеку
# и другой сети. Тогда их нужно удалить, иначе up упадёт с network not found.
current_config="$(docker ps -a \
  --filter "label=com.docker.compose.project=wg-warden" \
  --format '{{.Label "com.docker.compose.project.config_files"}}' 2>/dev/null | head -n 1)"
switched=0
if [ -n "$current_config" ] && [ "${current_config##*/}" != "$COMPOSE_FILE" ]; then
  switched=1
  say "Стек изменился (${current_config##*/} → ${COMPOSE_FILE}), пересоздаю контейнеры"
  compose down --remove-orphans >/dev/null 2>&1 || true
else
  say "Останавливаю панель"
  compose stop web app
fi

if [ "$switched" -eq 1 ] || ! compose ps --status running --services 2>/dev/null | grep -qx db; then
  say "Запускаю базу"
  compose up -d db
  for _ in $(seq 1 30); do
    compose exec -T db pg_isready -U "$db_user" >/dev/null 2>&1 && break
    sleep 2
  done
fi

say "Загружаю дамп"
gunzip -c "$dump" | compose exec -T db psql -U "$db_user" -d wgwarden -v ON_ERROR_STOP=1 >/dev/null

say "Запускаю панель"
compose up -d --remove-orphans

printf '\nБаза восстановлена из %s\n' "$dump"
