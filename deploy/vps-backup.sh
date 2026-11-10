#!/usr/bin/env bash
# Резервная копия wg-warden: дамп PostgreSQL + .env.vps (в нём APP_MASTER_KEY).
# Без APP_MASTER_KEY дамп бесполезен: сохранённые SSH-пароли и ключи WireGuard не расшифруются.
# Запуск: ./deploy/vps-backup.sh [каталог]
set -euo pipefail

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$PROJECT_DIR"

ENV_FILE=".env.vps"
BACKUP_DIR="${1:-${BACKUP_DIR:-$PROJECT_DIR/backups}}"
KEEP="${BACKUP_KEEP:-7}"

say() { printf '==> %s\n' "$*"; }
die() { printf 'Ошибка: %s\n' "$*" >&2; exit 1; }

[ -f "$ENV_FILE" ] || die "не найден $ENV_FILE. Сначала выполните ./deploy/vps-install.sh"

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

compose ps --status running --services 2>/dev/null | grep -qx db || die "контейнер базы не запущен: ./deploy/vps-service.sh start"

stamp="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

dump="$BACKUP_DIR/wg-warden-${stamp}.sql.gz"
say "Дамп базы в $dump"
# --clean --if-exists: такой дамп восстанавливается поверх существующей базы.
compose exec -T db pg_dump -U "$db_user" -d wgwarden --clean --if-exists | gzip > "${dump}.part"
mv "${dump}.part" "$dump"

env_backup="$BACKUP_DIR/env-${stamp}.vps"
cp "$ENV_FILE" "$env_backup"
chmod 600 "$dump" "$env_backup"

size="$(du -h "$dump" | awk '{print $1}')"
printf '\nГотово: %s (%s) и %s\n' "$dump" "$size" "$env_backup"

if [ "$KEEP" -gt 0 ]; then
  # Оставляем последние $KEEP дампов и .env-копий.
  ls -1t "$BACKUP_DIR"/wg-warden-*.sql.gz 2>/dev/null | tail -n "+$((KEEP + 1))" | xargs -r rm -f
  ls -1t "$BACKUP_DIR"/env-*.vps 2>/dev/null | tail -n "+$((KEEP + 1))" | xargs -r rm -f
fi

cat <<'HINT'

Копии лежат на этом же сервере: при потере VPS они пропадут.
Скачайте их на свою машину и храните отдельно (например: scp user@IP:~/wg-warden/backups/* ./).
Восстановление: ./deploy/vps-restore.sh <файл-дампа>
HINT
