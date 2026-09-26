# Деплой сервера (AWS EC2)

Одна виртуальная машина в AWS: сервер в Docker, рядом с ним PostgreSQL, перед ними Caddy с сертификатом Let's Encrypt. Сервер обновляется сам после каждого push в `main` ([Автообновление](#автообновление)), база каждую ночь сохраняется в дамп ([Бэкапы](#бэкапы)). Файлы лежат в [`deploy/`](../deploy/): `aws-user-data.sh` готовит машину при первом запуске, `compose.yaml` запускает сервер, PostgreSQL и Caddy, `hovanki-update.sh` с `hovanki-update.service` и `hovanki-update.timer` обновляют сервер, `hovanki-backup.sh` с `hovanki-backup.service` и `hovanki-backup.timer` делают бэкапы базы.

## Почему так

- **Одна постоянно работающая копия.** Игры, чат и приглашения хранятся в памяти сервера. Платформы, которые усыпляют сервис без запросов или поднимают несколько копий, не подходят. Рестарт и обновление обрывают идущие игры.
- **PostgreSQL на той же машине.** Аккаунты, друзья, группы и жалобы хранятся в PostgreSQL 17 в контейнере рядом с сервером. Managed-база (RDS) — это ещё одна машина и ещё один счёт, а нагрузка пока маленькая. Порт базы наружу не открыт: к ней ходит только сервер внутри сети compose.
- **Память.** У `t3.micro` 1 ГБ. Серверу — 560 МБ (heap — 75% от этого), Postgres — 192 МБ с маленькими настройками (`shared_buffers=32MB`, `max_connections=20`, `work_mem=4MB`), остальное — Caddy и система, плюс 1 ГБ swap. Лимиты — `mem_limit` в `compose.yaml`.
- **HTTPS.** Тестовые и релизные сборки ходят только по HTTPS. Caddy сам получает и продлевает сертификат.
- **Франкфурт (`eu-central-1`).** Через сервер идут координаты игроков и данные аккаунтов, поэтому данные остаются в ЕС (GDPR).
- **Без Terraform.** Одна машина, группа безопасности и IP-адрес создаются в консоли минут за десять. Повторяемость дают файлы в `deploy/`.

## Сколько стоит

- Free plan AWS (аккаунты, созданные с 15 июля 2025): $100 кредитов при регистрации и ещё до $100 за задания по $20. Два задания сделаются по ходу: бюджет в AWS Budgets и запуск EC2. План действует 6 месяцев или пока не кончатся кредиты.
- `t3.micro` во Франкфурте вместе с публичным IPv4 и диском на 10 ГБ — порядка $13 в месяц. $100 хватает больше чем на полгода.
- **Машину крупнее не брать.** На Free plan аккаунт закрывается, когда кончаются кредиты, и сервер пропадает вместе с ним.
- **До конца 6 месяцев перейти на Paid plan.** Иначе аккаунт закроют: данные хранятся 90 дней, потом удаляются. Оставшиеся кредиты действуют до 12 месяцев с регистрации.
- Когда кредиты кончатся, дешевле переехать на VPS за 4–5 € в месяц с тем же `compose.yaml`; база переезжает дампом ([Восстановление](#восстановление)).

## Что понадобится

- Аккаунт AWS на Free plan с MFA на root-пользователе.
- Имя для сертификата: поддомен на [duckdns.org](https://www.duckdns.org) (вход через GitHub). sslip.io не подходит: у него один общий на всех лимит Let's Encrypt, и он часто исчерпан.
- GitHub PAT (classic) со scope `read:packages`: образ в GHCR приватный, как и репозиторий. GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic).
- Образ сервера в GHCR. Образ `main` публикует `ci.yml` после каждого push в `main`, релизные `0.1.0` и `latest` — `release.yml` по тегу `v*` ([CI/CD](ci-cd.md#образ-сервера)).
- Почтовый ящик, с которого сервер шлёт коды подтверждения email и сброса пароля. Для начала — Gmail с паролем приложения ([Почта](#почта)).

## Первый запуск

1. **Бюджет.** Billing and Cost Management → Budgets → Create budget → Monthly cost budget на $15, письмо на свою почту. Предупредит, если что-то стоит больше ожидаемого.
2. **Регион.** Открыть EC2 (поиск вверху) и справа вверху выбрать Europe (Frankfurt), `eu-central-1`. На страницах глобальных сервисов, например Billing, выбор региона заблокирован и показывает «Global» — это нормально.
3. **Машина.** EC2 → Launch instance:
   - Name: `hovanki`.
   - Application and OS Images: Ubuntu Server 24.04 LTS, 64-bit (x86): образ сервера собирается под amd64.
   - Instance type: `t3.micro`.
   - Key pair: Create new key pair → `hovanki`, ED25519, `.pem`. Скачать файл и выполнить `chmod 400 ~/Downloads/hovanki.pem`.
   - Network settings: Create security group. SSH — только с **My IP**; отметить «Allow HTTPS traffic from the internet» и «Allow HTTP traffic from the internet». Порт 80 нужен Let's Encrypt для проверки домена.
   - Configure storage: 10 GiB, gp3.
   - Advanced details → User data: вставить содержимое [`deploy/aws-user-data.sh`](../deploy/aws-user-data.sh).
   - Launch instance.
4. **Постоянный IP.** EC2 → Elastic IPs → Allocate Elastic IP address → Allocate. Затем Actions → Associate Elastic IP address → машина `hovanki`. Без него IP меняется после остановки машины.
5. **Имя.** На duckdns.org добавить поддомен, например `hovanki`, и указать в поле current ip Elastic IP. Проверка: `dig +short hovanki.duckdns.org` отвечает этим IP.
6. **Почта.** Завести ящик и пароль приложения ([Почта](#почта)).
7. **Сервер.** Через 2–3 минуты после запуска машины (скрипт из user data ставит Docker) выполнить с компьютера, из корня репозитория:
   ```bash
   scp -i ~/Downloads/hovanki.pem deploy/compose.yaml deploy/hovanki-* ubuntu@<IP>:/opt/hovanki/
   ssh -i ~/Downloads/hovanki.pem ubuntu@<IP>
   ```
   Затем на машине (что значат переменные — [Настройки](#настройки-env)):
   ```bash
   cloud-init status --wait     # должно быть "status: done"
   cd /opt/hovanki
   cat > .env <<'EOF'
   DOMAIN=hovanki.duckdns.org
   HOVANKI_TAG=main
   SPRING_MAIL_HOST=smtp.gmail.com
   SPRING_MAIL_PORT=587
   SPRING_MAIL_USERNAME=hovanki.app@gmail.com
   SPRING_MAIL_PASSWORD=abcdefghijklmnop
   HOVANKI_MAIL_FROM=hovanki.app@gmail.com
   EOF
   echo "POSTGRES_PASSWORD=$(openssl rand -hex 24)" >> .env
   chmod 600 .env
   docker login ghcr.io -u denysbohusevych     # пароль — PAT с read:packages
   docker compose up -d
   sudo cp hovanki-update.service hovanki-update.timer hovanki-backup.service hovanki-backup.timer /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now hovanki-update.timer hovanki-backup.timer
   ```
   Если `docker` отвечает «permission denied», переподключиться по SSH: группа `docker` применяется при новом входе.
8. **Проверка.** На машине: `docker compose ps` — у `postgres` статус `healthy`, `server` и `caddy` — `running`; `sudo systemctl start hovanki-backup && ls -l backups/` — первый дамп. С компьютера: `curl https://hovanki.duckdns.org/actuator/health` → `{"status":"UP",…}`. Если нет, смотреть `docker compose logs caddy` и `docker compose logs server`. Обычно причина одна из трёх: закрыт порт 80, имя указывает не на Elastic IP или в `.env` не хватает переменной. Затем зарегистрироваться в приложении: письмо с кодом приходит за минуту.
9. **Адрес в сборках.** Строка `hovanki.serverUrl=https://hovanki.duckdns.org` в `gradle.properties` (уже вписана). Тестовые сборки из `main` ходят только на этот адрес ([CI/CD](ci-cd.md#адрес-сервера)). Другое имя — поменять строку.

## Настройки (`.env`)

Файл `/opt/hovanki/.env` есть только на машине, в git его нет. Права — `600`: в нём пароли.

| Переменная | Что это |
|---|---|
| `DOMAIN` | Имя для сертификата, например `hovanki.duckdns.org` |
| `HOVANKI_TAG` | Тег образа сервера: `main` — автообновление, `sha-<коммит>` — закрепить версию |
| `POSTGRES_PASSWORD` | Пароль базы, случайный: `openssl rand -hex 24`. Postgres берёт его только при первом запуске, когда создаёт том `postgres-data`; поменять потом — [Обслуживание](#обслуживание) |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | SMTP-сервер и порт. Порт — 587 (STARTTLS): сервер без шифрования не отправляет, а порт 25 на EC2 закрыт |
| `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | Логин и пароль SMTP |
| `HOVANKI_MAIL_FROM` | Адрес отправителя писем |

`compose.yaml` передаёт их серверу как `SPRING_DATASOURCE_*`, `SPRING_MAIL_*` и `HOVANKI_MAIL_FROM` и включает настоящую отправку писем (`HOVANKI_MAIL_SENDER=smtp`). Без `DOMAIN`, `POSTGRES_PASSWORD`, `SPRING_MAIL_HOST` или `HOVANKI_MAIL_FROM` `docker compose` не запускается и пишет, какой переменной не хватает. Проверить `.env`, ничего не запуская: `docker compose config --quiet` (молчит, если всё на месте). После правки `.env` — `docker compose up -d`.

### Почта

Сервер шлёт письма с 6-значными кодами: подтверждение email при регистрации и сброс пароля.

**Быстрый старт — Gmail.** Для тестов с друзьями хватает.

1. Отдельный Google-аккаунт для игры, например `hovanki.app@gmail.com`, с двухэтапной аутентификацией: без неё паролей приложений нет.
2. [myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords) → название `hovanki` → Create. 16 букв, которые покажет Google, без пробелов — это `SPRING_MAIL_PASSWORD`.
3. В `.env`: `SPRING_MAIL_HOST=smtp.gmail.com`, `SPRING_MAIL_PORT=587`, `SPRING_MAIL_USERNAME` и `HOVANKI_MAIL_FROM` — адрес этого аккаунта. Другой адрес в `HOVANKI_MAIL_FROM` Gmail всё равно заменит адресом аккаунта.

Ограничения: около 500 писем в сутки; письма с `@gmail.com` от приложения чаще попадают в спам; смена пароля Google-аккаунта отзывает пароль приложения — тогда создать новый и выполнить `docker compose up -d`. Письмо не пришло — смотреть `docker compose logs server` (адреса и коды сервер не логирует, только ошибку отправки) и папку «Спам».

**Для релиза в сторы** — свой домен и сервис рассылки, например Amazon SES в том же `eu-central-1`. Почтовые сервисы доставляют надёжно, только если у домена отправителя есть DNS-записи SPF, DKIM и DMARC. Поддомену DuckDNS свои записи не добавить (DuckDNS умеет только адрес и один TXT на домен), поэтому DKIM для него не настроить — нужен свой домен. В SES: подтвердить домен (Easy DKIM — три CNAME-записи), запросить production access (в sandbox письма уходят только на подтверждённые адреса), создать SMTP-учётку (SMTP settings → Create SMTP credentials). В `.env`: `SPRING_MAIL_HOST=email-smtp.eu-central-1.amazonaws.com`, `SPRING_MAIL_PORT=587`, логин и пароль SMTP-учётки, `HOVANKI_MAIL_FROM=no-reply@<домен>`; затем `docker compose up -d`.

## Переход на базу

Машине, поднятой по инструкции до PostgreSQL, нужны база, настройки почты и новые файлы из `deploy/`. Сервер с аккаунтами без них не запускается: если новый образ придёт в `main` раньше, автообновление поставит его, сервер будет падать при старте, а Caddy — отвечать 502. Поэтому всё ниже — **до** merge в `main`, пока на машине работает старый сервер.

1. На машине — остановить автообновление, чтобы старый `hovanki-update.service` не перезапустил сервер, пока файлы наполовину новые:
   ```bash
   sudo systemctl stop hovanki-update.timer
   ```
2. С компьютера, из корня репозитория, на ветке с базой:
   ```bash
   scp -i ~/Downloads/hovanki.pem deploy/compose.yaml deploy/hovanki-* ubuntu@<IP>:/opt/hovanki/
   ```
3. На машине — дописать в `.env` пароль базы и почту ([Почта](#почта)):
   ```bash
   cd /opt/hovanki
   echo "POSTGRES_PASSWORD=$(openssl rand -hex 24)" >> .env
   cat >> .env <<'EOF'
   SPRING_MAIL_HOST=smtp.gmail.com
   SPRING_MAIL_PORT=587
   SPRING_MAIL_USERNAME=hovanki.app@gmail.com
   SPRING_MAIL_PASSWORD=abcdefghijklmnop
   HOVANKI_MAIL_FROM=hovanki.app@gmail.com
   EOF
   chmod 600 .env
   docker compose config --quiet     # молчит, если всё на месте
   ```
4. Запустить только базу; старый сервер продолжает работать:
   ```bash
   docker compose up -d postgres
   docker compose ps                 # у postgres — healthy
   ```
5. Поставить бэкапы и новый сервис обновления, вернуть таймер:
   ```bash
   sudo cp hovanki-update.service hovanki-backup.service hovanki-backup.timer /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl start hovanki-backup && ls -l backups/     # пробный дамп пока пустой базы
   sudo systemctl enable --now hovanki-backup.timer
   sudo systemctl start hovanki-update.timer
   ```
   Пока образ в `main` старый, новый `hovanki-update.sh` ничего не делает.
6. После merge, минут через 10: `journalctl -u hovanki-update -n 50` — строки «New server image …» и «Server updated»; в `backups/` — дамп `…-before-update.dump`; `docker compose logs server | grep -i flyway` — миграции применены; `curl https://hovanki.duckdns.org/actuator/health` → `UP`. Зарегистрироваться в приложении и дождаться письма с кодом.

Если что-то не так — [откатить](#обслуживание) сервер на прежний `sha-<коммит>`: старый сервер базу не использует и работает с новым `compose.yaml`.

## Автообновление

Каждый push в `main`, прошедший проверки CI, публикует образ `ghcr.io/denysbohusevych/hovanki-server:main` (job `Server image` в `ci.yml`). На машине таймер `hovanki-update.timer` раз в 2 минуты запускает `hovanki-update.service`, а тот — `hovanki-update.sh`:

1. `docker compose pull server` — скачать образ тега `HOVANKI_TAG` из `.env`;
2. если это не тот образ, из которого запущен контейнер сервера, — дамп базы `hovanki-backup.sh before-update` ([Бэкапы](#бэкапы)). Это страховка: новый сервер при старте применяет миграции Flyway. Нет дампа — нет и обновления: скрипт завершается с ошибкой и пробует снова через 2 минуты;
3. `docker compose up -d server` — перезапустить сервер с новым образом;
4. `docker image prune -f` — удалить старые образы.

Сервер обновляется примерно через 5–10 минут после push: проверки CI, сборка образа, до 2 минут ожидания таймера. Caddy и Postgres таймер не трогает.

- **Каждое обновление обрывает идущие игры**: они хранятся в памяти. Образ собирается на каждый push в `main`, в том числе на правки только документации. Когда начнутся игры, таймер стоит научить ждать, пока игр нет.
- **Закрепить версию**: `HOVANKI_TAG=sha-<коммит>` в `.env`, затем `docker compose up -d`. Таймер продолжит работать, но этот тег не меняется. Вернуть автообновление — `HOVANKI_TAG=main`.
- **Выключить**: `sudo systemctl disable --now hovanki-update.timer`.
- **Проверить**: `systemctl list-timers hovanki-update.timer` — когда следующий запуск; `journalctl -u hovanki-update -n 50` — что было при последних. Обновление пишет «New server image …» и «Server updated». Ошибка `unauthorized` значит, что истёк PAT: создать новый и повторить `docker login ghcr.io`.

## Бэкапы

- **Каждую ночь.** `hovanki-backup.timer` между 03:00 и 03:30 UTC запускает `hovanki-backup.sh`: `pg_dump -Fc` (сжатый формат PostgreSQL) через `docker compose exec` в `/opt/hovanki/backups/hovanki-<время UTC>.dump`. Хранятся 7 последних — неделя. Если машина в это время была выключена, бэкап делается после включения.
- **Перед обновлением.** `hovanki-update.sh` перед новым образом сервера делает `hovanki-<время UTC>-before-update.dump`. Их тоже 7, отдельно от ночных (день частых push'ей не вытесняет ночные), и не старше 7 дней.
- **Целиком или никак.** Дамп пишется во временный файл, проверяется чтением через `pg_restore` и только потом получает своё имя. Неудачный запуск не оставляет обрезанный дамп и не удаляет старые, а ошибка видна в `systemctl --failed` и `journalctl -u hovanki-backup`.
- **Личные данные.** В дампах email и хэши паролей, поэтому читать их может только `ubuntu` (каталог `700`, файлы `600`). Удалённый аккаунт пропадает из бэкапов через 7 дней.
- **На той же машине.** Бэкапы спасают от неудачной миграции и случайного удаления, но не от потери машины или диска. Копия вне машины (S3) — позже. Пока перед рискованными действиями скопировать свежий дамп к себе:
  ```bash
  scp -i ~/Downloads/hovanki.pem ubuntu@<IP>:/opt/hovanki/backups/hovanki-20260927T031204Z.dump .
  ```
- **Вручную**: `sudo systemctl start hovanki-backup` (считается ночным). **Проверить**: `ls -lh backups/`; `systemctl list-timers hovanki-backup.timer` — когда следующий; `journalctl -u hovanki-backup -n 20` — что было.

### Восстановление

База восстанавливается из дампа целиком, вместо текущей. Сервер на это время остановлен, идущие игры обрываются. На машине, в `/opt/hovanki`:

```bash
sudo systemctl stop hovanki-update.timer     # чтобы автообновление не запустило сервер посередине
docker compose stop server
# Текущее состояние, на всякий случай: в ротацию не входит, удалить потом руками
docker compose exec -T postgres pg_dump -U hovanki -Fc hovanki > backups/before-restore.dump
docker compose exec postgres dropdb -U hovanki hovanki
docker compose exec postgres createdb -U hovanki hovanki
docker compose exec -T postgres pg_restore -U hovanki -d hovanki --single-transaction < backups/hovanki-20260927T031204Z.dump
docker compose up -d server
sudo systemctl start hovanki-update.timer
```

- База пересоздаётся, а не очищается: таблицы, которых нет в дампе (например, из более новой миграции), не остаются.
- Вместе с данными восстанавливается история миграций Flyway: новые миграции сервер применит при старте. Если восстанавливаете из-за неудачной миграции, сначала закрепите прежний образ (`HOVANKI_TAG=sha-<коммит>` в `.env`), иначе та же миграция выполнится снова.
- Перенос на другую машину — так же: дамп скопировать в её `backups/` и выполнить те же команды.

## Обслуживание

Все команды — на машине, в `/opt/hovanki`.

| Задача | Как |
|---|---|
| Обновить сервер | Само, после каждого push в `main` ([Автообновление](#автообновление)). Сразу, не дожидаясь таймера: `sudo systemctl start hovanki-update`. Обновить Caddy: `docker compose pull caddy && docker compose up -d caddy`. |
| Откатить | Прописать нужный `sha-<коммит>` в `HOVANKI_TAG` в `.env` и выполнить `docker compose up -d`. Пока там не `main`, автообновление стоит. Если старый сервер не запускается на базе после новой миграции — восстановить дамп `…-before-update.dump` ([Восстановление](#восстановление)). |
| Логи | `docker compose logs -f server`. Ротация — 3 файла по 10 МБ на контейнер. Access-лог Caddy выключен: Caddy пишет только ошибки проксирования (например, 502, пока сервер перезапускается), значения `Authorization` и cookies в них скрыты. Сервер координаты, токены, пароли, коды, email и текст чата не логирует. |
| Логи базы | `docker compose logs -f postgres`. Запросы Postgres не логирует: только запуск, остановку, checkpoints и ошибки. |
| Консоль базы | `docker compose exec postgres psql -U hovanki`: `\dt` — таблицы, `\q` — выход. Там личные данные игроков: заходить только для обслуживания. |
| Место на диске | `df -h /` — весь диск; `du -sh backups` — бэкапы; `docker system df` — образы, контейнеры и тома; размер базы: `docker compose exec postgres psql -U hovanki -c "SELECT pg_size_pretty(pg_database_size('hovanki'))"`. |
| Обновить Postgres | Внутри 17-й версии: `docker compose pull postgres && docker compose up -d postgres`; несколько секунд запросы к аккаунтам отвечают ошибкой, сервер переподключается сам. Следующая major-версия — только через дамп и [восстановление](#восстановление) в новый том, поэтому тег в `compose.yaml` закреплён на 17. |
| Сменить пароль базы | `docker compose exec postgres psql -U hovanki -c '\password hovanki'`, затем новый пароль в `POSTGRES_PASSWORD` в `.env` и `docker compose up -d server`. Одна правка `.env` пароль уже созданной базы не меняет. |
| Перезагрузка машины | Контейнеры поднимаются сами (`restart: unless-stopped`), пропущенный ночной бэкап делается после включения. Обновления безопасности Ubuntu ставит сама, перезагрузку после обновления ядра делать руками в спокойное время: `sudo reboot`. |
| SSH не пускает | Скорее всего, сменился домашний IP. EC2 → Security Groups → правило SSH → Source: My IP. |
| Удалить всё | Сначала скопировать к себе свежий дамп ([Бэкапы](#бэкапы)): бэкапы лежат на той же машине. Затем EC2 → Instances → Terminate и Elastic IPs → Release. Непривязанный Elastic IP тоже стоит денег. |
