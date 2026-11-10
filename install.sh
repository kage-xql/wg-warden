#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$PROJECT_DIR"

if ! command -v docker >/dev/null 2>&1; then
  echo "Ошибка: Docker не найден. Установите Docker Desktop или Docker Engine."
  exit 1
fi

if ! command -v openssl >/dev/null 2>&1; then
  echo "Ошибка: openssl не найден; он нужен для генерации секретов."
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "Ошибка: curl не найден; он нужен для проверки готовности приложения."
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "Ошибка: требуется Docker Compose v2 (команда 'docker compose')."
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "Ошибка: Docker Engine не запущен. Запустите Docker Desktop или Docker Engine."
  exit 1
fi

if [ ! -f .env ]; then
  cp .env.example .env
  db_password="$(openssl rand -hex 24)"
  app_master_key="$(openssl rand -base64 32 | tr -d '\n')"
  jwt_secret="$(openssl rand -base64 48 | tr -d '\n')"
  bootstrap_token="$(openssl rand -hex 24)"
  sed -i.bak "s|^APP_MASTER_KEY=.*|APP_MASTER_KEY=${app_master_key}|" .env
  sed -i.bak "s|^JWT_SECRET=.*|JWT_SECRET=${jwt_secret}|" .env
  sed -i.bak "s|^DB_PASSWORD=.*|DB_PASSWORD=${db_password}|" .env
  sed -i.bak "s|^APP_BOOTSTRAP_TOKEN=.*|APP_BOOTSTRAP_TOKEN=${bootstrap_token}|" .env
  rm -f .env.bak
  chmod 600 .env
  echo "Создан .env с локальными секретами."
fi

# Обновление со старой версии: добавляем setup-токен, если его ещё нет.
if ! grep -q '^APP_BOOTSTRAP_TOKEN=..*' .env; then
  bootstrap_token="$(openssl rand -hex 24)"
  if grep -q '^APP_BOOTSTRAP_TOKEN=' .env; then
    sed -i.bak "s|^APP_BOOTSTRAP_TOKEN=.*|APP_BOOTSTRAP_TOKEN=${bootstrap_token}|" .env
    rm -f .env.bak
  else
    printf '\nAPP_BOOTSTRAP_TOKEN=%s\n' "$bootstrap_token" >> .env
  fi
  echo "Добавлен APP_BOOTSTRAP_TOKEN для создания первого аккаунта."
fi
bootstrap_token="$(sed -n 's/^APP_BOOTSTRAP_TOKEN=//p' .env | tail -n 1)"

# Keep an existing database volume when moving from the former project name.
if ! grep -q '^PGDATA_VOLUME=' .env; then
  if docker volume inspect wg-manager_pgdata >/dev/null 2>&1; then
    pgdata_volume='wg-manager_pgdata'
  else
    pgdata_volume='wg-warden_pgdata'
  fi
  printf '\nPGDATA_VOLUME=%s\n' "$pgdata_volume" >> .env
fi

if ! grep -q '^APP_MASTER_KEY=[^[:space:]]' .env || ! grep -q '^JWT_SECRET=[^[:space:]]' .env || ! grep -q '^DB_PASSWORD=[^[:space:]]' .env; then
  echo "Ошибка: APP_MASTER_KEY, JWT_SECRET и DB_PASSWORD должны быть заполнены в .env."
  exit 1
fi

echo "Собираю и запускаю wg-warden в фоне..."
# Remove containers from the previous Compose project name, without deleting its volume.
legacy_containers="$(docker compose -p wg-manager ps -q -a)"
if [ -n "$legacy_containers" ]; then
  docker compose -p wg-manager down
fi
if ! docker compose up -d --build; then
  echo
  echo "Ошибка: не удалось запустить контейнеры."
  echo "Проверьте свободное место (df -h), память (free -h) и логи: docker compose logs"
  exit 1
fi

web_port="$(sed -n 's/^WEB_PORT=//p' .env | tail -n 1)"
web_port="${web_port:-8080}"
echo "Ожидаю готовности приложения..."
for attempt in $(seq 1 60); do
  if curl -fsS --max-time 5 "http://localhost:${web_port}/actuator/health" >/dev/null 2>&1; then
    echo
    echo "wg-warden запущен: http://localhost:${web_port}"
    echo
    echo "Токен для создания первого аккаунта: ${bootstrap_token}"
    echo "Он также сохранён в .env как APP_BOOTSTRAP_TOKEN."
    echo
    echo "Сервис настроен на запуск вместе с Docker."
    echo "Включить: ./service.sh start"
    echo "Выключить: ./service.sh stop"
    echo "Статус: ./service.sh status"
    exit 0
  fi
  sleep 2
done

echo "Приложение ещё запускается. Проверьте логи: docker compose logs -f"
exit 1
