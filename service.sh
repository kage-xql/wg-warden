#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$PROJECT_DIR"

if [ ! -f .env ]; then
  echo "Не найден .env. Сначала выполните ./install.sh"
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "Docker сейчас не запущен. Запустите Docker Desktop и повторите команду."
  exit 1
fi

action="${1:-}"
case "$action" in
  start)
    docker compose up -d
    web_port="$(sed -n 's/^WEB_PORT=//p' .env | tail -n 1)"
    web_port="${web_port:-8080}"
    echo "wg-warden включён: http://localhost:${web_port}"
    ;;
  stop)
    docker compose stop
    echo "wg-warden выключен. Данные сохранены."
    ;;
  restart)
    # --force-recreate применяет изменения в .env
    docker compose up -d --force-recreate
    web_port="$(sed -n 's/^WEB_PORT=//p' .env | tail -n 1)"
    web_port="${web_port:-8080}"
    echo "wg-warden применён: http://localhost:${web_port}"
    ;;
  status)
    docker compose ps
    ;;
  logs)
    docker compose logs -f
    ;;
  *)
    echo "Использование: ./service.sh {start|stop|restart|status|logs}"
    exit 2
    ;;
esac
