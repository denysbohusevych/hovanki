# Деплой сервера (AWS)

Одна виртуальная машина EC2: сервер в Docker, перед ним Caddy с сертификатом Let's Encrypt. База — отдельно от машины, в Amazon RDS for PostgreSQL в том же регионе. Сервер обновляется сам после каждого push в `main` ([Автообновление](#автообновление)), бэкапы базы делает RDS ([Бэкапы](#бэкапы)). Файлы лежат в [`deploy/`](../deploy/): `aws-user-data.sh` готовит машину при первом запуске, `compose.yaml` запускает сервер и Caddy, `hovanki-update.sh` с `hovanki-update.service` и `hovanki-update.timer` обновляют сервер.

## Почему так

- **Одна постоянно работающая копия сервера.** Игры, чат и приглашения хранятся в памяти сервера. Платформы, которые усыпляют сервис без запросов или поднимают несколько копий, не подходят. Рестарт и обновление обрывают идущие игры.
- **База отдельно от машины — в RDS.** Аккаунты, друзья, группы и жалобы живут в PostgreSQL 17 на Amazon RDS ([ADR 0004](adr/0004-accounts-friends-chat.md#1-хранилище-postgresql-в-amazon-rds)):
  - база переживает потерю или пересоздание машины;
  - бэкапы с восстановлением на любую секунду последних 7 дней (пока аккаунт AWS на Free plan — последних суток), обновления PostgreSQL и шифрование диска делает AWS;
  - вся память машины достаётся серверу и Caddy.

  Цена — отдельный счёт за RDS ([Сколько стоит](#сколько-стоит)). В интернет база не смотрит: подключаться к ней может только машина `hovanki`, и только по TLS с проверкой сертификата.
- **HTTPS.** Тестовые и релизные сборки ходят только по HTTPS. Caddy сам получает и продлевает сертификат.
- **Франкфурт (`eu-central-1`).** Через сервер идут координаты игроков и данные аккаунтов, поэтому и машина, и база — в ЕС (GDPR).
- **Без Terraform.** Машина, база, группы безопасности и IP-адрес создаются в консоли минут за двадцать. Повторяемость дают файлы в `deploy/` и шаги ниже.

## Сколько стоит

Цены — порядок величины на 2026 год, точные — в [AWS Pricing Calculator](https://calculator.aws/) для `eu-central-1`.

| Что | Примерно в месяц |
|---|---|
| EC2 `t3.micro`, публичный IPv4, диск 10 ГБ | $13 |
| RDS `db.t4g.micro` Single-AZ (в `us-east-1` — $0.016 в час, во Франкфурте немного дороже) | $14–15 |
| Диск RDS: gp3, 20 ГБ | $2–3 |
| Бэкапы RDS в пределах размера диска (20 ГБ) | $0 |
| **Итого** | **около $30** |

- Free plan AWS (аккаунты, созданные с 15 июля 2025): $100 кредитов при регистрации и ещё до $100 за задания по $20. Три задания сделаются по ходу: бюджет в AWS Budgets, запуск EC2 и создание базы RDS. План действует 6 месяцев или пока не кончатся кредиты. При ~$30 в месяц $160 хватает примерно на 5 месяцев.
- **Машину и базу крупнее не брать.** На Free plan аккаунт закрывается, когда кончаются кредиты, и сервер и база пропадают вместе с ним.
- **Free plan ограничивает RDS**: только классы `micro` и автоматические бэкапы не дольше 1 дня — с большим сроком база не создаётся (`FreeTierRestrictionError: The specified backup retention period exceeds the maximum available to free tier customers`). Поэтому на Free plan базу можно восстановить только на момент в пределах последних суток.
- **До конца 6 месяцев перейти на Paid plan.** Иначе аккаунт закроют: данные хранятся 90 дней, потом удаляются. Оставшиеся кредиты действуют до 12 месяцев с регистрации. После перехода поднять срок бэкапов до 7 дней: RDS → Databases → `hovanki` → Modify → Backup retention period **7 days** → Continue → Apply immediately.
- Дешевле потом: зарезервировать `db.t4g.micro` на год (Reserved Instance, заметно дешевле почасовой цены) или переехать на VPS за 4–5 € в месяц с PostgreSQL рядом; база переезжает дампом ([Бэкапы](#бэкапы)).

## Что понадобится

- Аккаунт AWS на Free plan с MFA на root-пользователе.
- Имя для сертификата: поддомен на [duckdns.org](https://www.duckdns.org) (вход через GitHub). sslip.io не подходит: у него один общий на всех лимит Let's Encrypt, и он часто исчерпан.
- GitHub PAT (classic) со scope `read:packages`: образ в GHCR приватный, как и репозиторий. GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic).
- Образ сервера в GHCR. Образ `main` публикует `ci.yml` после каждого push в `main`, релизные `0.1.0` и `latest` — `release.yml` по тегу `v*` ([CI/CD](ci-cd.md#образ-сервера)).
- Почтовый ящик, с которого сервер шлёт коды подтверждения email и сброса пароля. Для начала — Gmail с паролем приложения ([Почта](#почта)).
- Менеджер паролей: пароль администратора базы и пароль сервера к ней нужны при обслуживании.

## Первый запуск

1. **Бюджет.** Billing and Cost Management → Budgets → Create budget → Monthly cost budget на $35, письмо на свою почту. Предупредит, если что-то стоит больше ожидаемого.
2. **Регион.** Открыть EC2 (поиск вверху) и справа вверху выбрать Europe (Frankfurt), `eu-central-1`. На страницах глобальных сервисов, например Billing, выбор региона заблокирован и показывает «Global» — это нормально.
3. **Машина.** EC2 → Launch instance:
   - Name: `hovanki`.
   - Application and OS Images: Ubuntu Server 24.04 LTS, 64-bit (x86): образ сервера собирается под amd64.
   - Instance type: `t3.micro`.
   - Key pair: Create new key pair → `hovanki`, ED25519, `.pem`. Скачать файл и выполнить `chmod 400 ~/Downloads/hovanki.pem`.
   - Network settings: Create security group. SSH — только с **My IP**; отметить «Allow HTTPS traffic from the internet» и «Allow HTTP traffic from the internet». Порт 80 нужен Let's Encrypt для проверки домена.
   - Configure storage: 10 GiB, gp3.
   - Advanced details → User data: вставить содержимое [`deploy/aws-user-data.sh`](../deploy/aws-user-data.sh). Он ставит Docker и кладёт в `/opt/hovanki/rds-ca.pem` сертификаты RDS.
   - Launch instance.
4. **Постоянный IP.** EC2 → Elastic IPs → Allocate Elastic IP address → Allocate. Затем Actions → Associate Elastic IP address → машина `hovanki`. Без него IP меняется после остановки машины.
5. **Имя.** На duckdns.org добавить поддомен, например `hovanki`, и указать в поле current ip Elastic IP. Проверка: `dig +short hovanki.duckdns.org` отвечает этим IP.
6. **База.** Создать базу в RDS и в ней пользователя сервера — [База (RDS)](#база-rds).
7. **Почта.** Завести ящик и пароль приложения ([Почта](#почта)).
8. **Сервер.** Выполнить с компьютера, из корня репозитория:
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
   DATABASE_HOST=hovanki.xxxxxxxxxxxx.eu-central-1.rds.amazonaws.com
   DATABASE_PASSWORD=<пароль пользователя hovanki из шага «База»>
   SPRING_MAIL_HOST=smtp.gmail.com
   SPRING_MAIL_PORT=587
   SPRING_MAIL_USERNAME=<адрес Gmail>
   SPRING_MAIL_PASSWORD=<пароль приложения Gmail: 16 букв без пробелов>
   HOVANKI_MAIL_FROM=<адрес Gmail>
   EOF
   chmod 600 .env
   docker compose config --quiet     # молчит, если всё на месте
   docker login ghcr.io -u denysbohusevych     # пароль — PAT с read:packages
   docker compose up -d
   sudo cp hovanki-update.service hovanki-update.timer /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now hovanki-update.timer
   ```
   Если `docker` отвечает «permission denied», переподключиться по SSH: группа `docker` применяется при новом входе.
9. **Проверка.** На машине: `docker compose ps` — `server` и `caddy` в статусе `running`; `docker compose logs server | grep -i flyway` — миграции применены. С компьютера: `curl https://hovanki.duckdns.org/actuator/health` → `{"status":"UP",…}`. Если нет, смотреть `docker compose logs caddy` и `docker compose logs server`. Обычно причина одна из четырёх: закрыт порт 80, имя указывает не на Elastic IP, в `.env` не хватает переменной или сервер не достучался до базы ([База (RDS)](#база-rds), «Если сервер не подключается»). Затем зарегистрироваться в приложении: аккаунт работает сразу, письмо с кодом подтверждения приходит за минуту.
10. **Адрес в сборках.** Строка `hovanki.serverUrl=https://hovanki.duckdns.org` в `gradle.properties` (уже вписана). Тестовые сборки из `main` ходят только на этот адрес ([CI/CD](ci-cd.md#адрес-сервера)). Другое имя — поменять строку.

## Настройки (`.env`)

Файл `/opt/hovanki/.env` есть только на машине, в git его нет. Права — `600`: в нём пароли.

| Переменная | Что это |
|---|---|
| `DOMAIN` | Имя для сертификата, например `hovanki.duckdns.org` |
| `HOVANKI_TAG` | Тег образа сервера: `main` — автообновление, `sha-<коммит>` — закрепить версию |
| `DATABASE_HOST` | Endpoint базы RDS: RDS → Databases → `hovanki` → Connectivity & security → Endpoint, например `hovanki.xxxxxxxxxxxx.eu-central-1.rds.amazonaws.com` |
| `DATABASE_PASSWORD` | Пароль пользователя `hovanki` в базе ([База (RDS)](#база-rds)) |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | SMTP-сервер и порт. Порт — 587 (STARTTLS): сервер без шифрования не отправляет, а порт 25 на EC2 закрыт |
| `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | Логин и пароль SMTP |
| `HOVANKI_MAIL_FROM` | Адрес отправителя писем |

`compose.yaml` передаёт их серверу как `SPRING_DATASOURCE_*` (база `hovanki`, пользователь `hovanki`, TLS с проверкой сертификата по `rds-ca.pem`), `SPRING_MAIL_*` и `HOVANKI_MAIL_FROM` и включает настоящую отправку писем (`HOVANKI_MAIL_SENDER=smtp`). Без `DOMAIN`, `DATABASE_HOST`, `DATABASE_PASSWORD`, `SPRING_MAIL_HOST` или `HOVANKI_MAIL_FROM` `docker compose` не запускается и пишет, какой переменной не хватает; без файла `rds-ca.pem` рядом не запускается сервер. Проверить `.env`, ничего не запуская: `docker compose config --quiet` (молчит, если всё на месте). После правки `.env` — `docker compose up -d`.

### Почта

Сервер шлёт письма с 6-значными кодами: подтверждение email при регистрации и сброс пароля.

**Быстрый старт — Gmail.** Для тестов с друзьями хватает.

1. Отдельный Google-аккаунт для игры, например `hovanki.app@gmail.com`, с двухэтапной аутентификацией: без неё паролей приложений нет.
2. [myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords) → название `hovanki` → Create. 16 букв, которые покажет Google, без пробелов — это `SPRING_MAIL_PASSWORD`.
3. В `.env`: `SPRING_MAIL_HOST=smtp.gmail.com`, `SPRING_MAIL_PORT=587`, `SPRING_MAIL_USERNAME` и `HOVANKI_MAIL_FROM` — адрес этого аккаунта. Другой адрес в `HOVANKI_MAIL_FROM` Gmail всё равно заменит адресом аккаунта.

Ограничения: около 500 писем в сутки; письма с `@gmail.com` от приложения чаще попадают в спам; смена пароля Google-аккаунта отзывает пароль приложения — тогда создать новый и выполнить `docker compose up -d`. Письмо не пришло — смотреть `docker compose logs server` (адреса и коды сервер не логирует, только ошибку отправки) и папку «Спам».

**Для релиза в сторы** — свой домен и сервис рассылки, например Amazon SES в том же `eu-central-1`. Почтовые сервисы доставляют надёжно, только если у домена отправителя есть DNS-записи SPF, DKIM и DMARC. Поддомену DuckDNS свои записи не добавить (DuckDNS умеет только адрес и один TXT на домен), поэтому DKIM для него не настроить — нужен свой домен. В SES: подтвердить домен (Easy DKIM — три CNAME-записи), запросить production access (в sandbox письма уходят только на подтверждённые адреса), создать SMTP-учётку (SMTP settings → Create SMTP credentials). В `.env`: `SPRING_MAIL_HOST=email-smtp.eu-central-1.amazonaws.com`, `SPRING_MAIL_PORT=587`, логин и пароль SMTP-учётки, `HOVANKI_MAIL_FROM=no-reply@<домен>`; затем `docker compose up -d`.

## База (RDS)

### Создать базу

Машина `hovanki` уже должна быть запущена: RDS сам настроит доступ к базе только с неё.

1. RDS (поиск вверху, регион тот же — `eu-central-1`) → Databases → Create database:
   - Choose a database creation method: **Full configuration** (Standard create).
   - Engine: **PostgreSQL**, версия — последняя **17.x**.
   - Templates: **Free tier**, если он есть, иначе Sandbox / Dev/Test. Дальше проверить, что выбрано всё, как ниже.
   - Availability and durability: **Single-AZ DB instance** (одна копия; Multi-AZ вдвое дороже).
   - DB instance identifier: `hovanki`.
   - Master username: `hovanki_admin`. Credentials management: **Self managed**, пароль — `openssl rand -hex 24`, сохранить в менеджер паролей. Этот пользователь — только для обслуживания, сервер ходит под своим (ниже).
   - Instance configuration: Burstable classes → **db.t4g.micro**.
   - Storage: **gp3, 20 GiB**; Storage autoscaling — включить, maximum 50 GiB (диск растёт сам, если кончится место).
   - Connectivity: **Connect to an EC2 compute resource** → машина `hovanki`. RDS сам создаст группы безопасности: база принимает подключения на порт 5432 только с этой машины. Public access: **No**.
   - Database authentication: Password authentication.
   - Monitoring: как есть (без Enhanced Monitoring — он платный).
   - Additional configuration: Initial database name — **пусто** (базу создадим сами); Backup — automated backups включены, retention **1 day** на Free plan (больше он не даёт), **7 days** на Paid plan; Encryption — включено (ключ `aws/rds` по умолчанию); Auto minor version upgrade — включено; **Deletion protection — включить**.
   - Create database. База создаётся минут десять. Endpoint — RDS → Databases → `hovanki` → Connectivity & security.
2. Пользователь и база сервера. На машине (psql — из контейнера PostgreSQL, отдельно его ставить не нужно):
   ```bash
   cd /opt/hovanki
   openssl rand -hex 24     # пароль для пользователя hovanki: скопировать, понадобится дважды
   DB_HOST=hovanki.xxxxxxxxxxxx.eu-central-1.rds.amazonaws.com    # Endpoint из шага 1
   docker run --rm -it -v /opt/hovanki/rds-ca.pem:/rds-ca.pem:ro postgres:17-alpine \
     psql "host=$DB_HOST dbname=postgres user=hovanki_admin sslmode=verify-full sslrootcert=/rds-ca.pem"
   ```
   psql спросит пароль `hovanki_admin`. Затем в psql:
   ```sql
   CREATE ROLE hovanki LOGIN;
   \password hovanki
   GRANT hovanki TO hovanki_admin;
   CREATE DATABASE hovanki OWNER hovanki;
   \q
   ```
   `\password` спросит новый пароль дважды — вставить сгенерированный; он же пойдёт в `DATABASE_PASSWORD` в `.env`. `GRANT` нужен, чтобы администратор мог назначить `hovanki` владельцем базы.

Сервер работает под `hovanki`: это владелец только своей базы. Он создаёт и обновляет в ней таблицы (миграции Flyway при старте сервера), но не видит ничего другого и не может ничего сломать на уровне всего PostgreSQL.

### Безопасность

- **Сеть.** База без публичного адреса. Группа безопасности базы (`rds-ec2-…`) пускает на порт 5432 только группу машины (`ec2-rds-…`). С компьютера к базе напрямую не подключиться — только через машину.
- **TLS.** Сервер подключается с `sslmode=verify-full`: соединение шифруется, а сертификат базы должен быть выдан центром сертификации RDS на её имя. Центры сертификации лежат в `/opt/hovanki/rds-ca.pem` (его скачивает `aws-user-data.sh`), `compose.yaml` монтирует файл в контейнер сервера. RDS для PostgreSQL 15+ и сама не принимает подключения без TLS (`rds.force_ssl=1`).
- **Диск и бэкапы** зашифрованы ключом `aws/rds`.

### Если сервер не подключается

`docker compose logs server` при старте:

| Ошибка | Причина |
|---|---|
| `Connection attempt timed out` / `connect timed out` | База не пускает машину: при создании базы не выбрано «Connect to an EC2 compute resource». RDS → Databases → `hovanki` → Actions → Set up EC2 connection → машина `hovanki` |
| `password authentication failed for user "hovanki"` | `DATABASE_PASSWORD` в `.env` не тот, что задан через `\password hovanki` |
| `database "hovanki" does not exist` | Не выполнен `CREATE DATABASE` из шага 2 |
| `PKIX path building failed`, `unable to find valid certification path` | `rds-ca.pem` устарел или не тот: скачать заново (ниже, «Сертификаты RDS») |
| `bind source path does not exist: /opt/hovanki/rds-ca.pem` | Файла нет: машина создана со старым `aws-user-data.sh`. Скачать (ниже) |

## Переход на базу

Машине, поднятой по инструкции без базы, нужны база в RDS, настройки почты и новые файлы из `deploy/`. Сервер с аккаунтами без них не запускается: если новый образ придёт в `main` раньше, автообновление поставит его, сервер будет падать при старте, а Caddy — отвечать 502. Поэтому всё ниже — **до** merge в `main`, пока на машине работает старый сервер.

1. На машине — остановить автообновление, чтобы старый `hovanki-update.service` не перезапустил сервер, пока файлы наполовину новые:
   ```bash
   sudo systemctl stop hovanki-update.timer
   ```
2. Создать базу — [Создать базу](#создать-базу), шаг 1. Перед шагом 2 скачать на машину сертификаты RDS (машина создана со старым `aws-user-data.sh`):
   ```bash
   curl -fsSL -o /opt/hovanki/rds-ca.pem https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
   ```
   Затем шаг 2 — пользователь и база сервера.
3. С компьютера, из корня репозитория, на ветке с базой:
   ```bash
   scp -i ~/Downloads/hovanki.pem deploy/compose.yaml deploy/hovanki-* ubuntu@<IP>:/opt/hovanki/
   ```
4. На машине — дописать в `.env` базу и почту ([Почта](#почта)):
   ```bash
   cd /opt/hovanki
   cat >> .env <<'EOF'
   DATABASE_HOST=hovanki.xxxxxxxxxxxx.eu-central-1.rds.amazonaws.com
   DATABASE_PASSWORD=<пароль пользователя hovanki>
   SPRING_MAIL_HOST=smtp.gmail.com
   SPRING_MAIL_PORT=587
   SPRING_MAIL_USERNAME=<адрес Gmail>
   SPRING_MAIL_PASSWORD=<пароль приложения Gmail: 16 букв без пробелов>
   HOVANKI_MAIL_FROM=<адрес Gmail>
   EOF
   chmod 600 .env
   docker compose config --quiet     # молчит, если всё на месте
   ```
5. Поставить новый сервис обновления и вернуть таймер:
   ```bash
   sudo cp hovanki-update.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl start hovanki-update.timer
   ```
   Пока образ в `main` старый, новый `hovanki-update.sh` ничего не делает. Старый сервер переменные базы игнорирует и работает дальше.
6. После merge, минут через 10: `journalctl -u hovanki-update -n 50` — строки «New server image …» и «Server updated»; `docker compose logs server | grep -i flyway` — миграции применены; `curl https://hovanki.duckdns.org/actuator/health` → `UP`. Зарегистрироваться в приложении и дождаться письма с кодом.

Если что-то не так — [откатить](#обслуживание) сервер на прежний `sha-<коммит>`: старый сервер базу не использует и работает с новым `compose.yaml`.

**Если PostgreSQL уже работал в контейнере на машине** (по прежней версии этой инструкции, с сервисом `postgres` в `compose.yaml` и `hovanki-backup.timer`): перенести данные в RDS и убрать контейнер. На машине, после шагов 1–2, пока в `compose.yaml` ещё есть `postgres`:

```bash
cd /opt/hovanki
docker compose stop server
docker compose exec -T postgres pg_dump -U hovanki -Fc hovanki > hovanki-move.dump
DB_HOST=hovanki.xxxxxxxxxxxx.eu-central-1.rds.amazonaws.com
read -rsp 'Пароль пользователя hovanki в RDS: ' PGPASSWORD && export PGPASSWORD && echo
docker run --rm -i -e PGPASSWORD -v /opt/hovanki/rds-ca.pem:/rds-ca.pem:ro postgres:17-alpine \
  pg_restore --no-owner --role=hovanki --single-transaction \
  -d "host=$DB_HOST dbname=hovanki user=hovanki sslmode=verify-full sslrootcert=/rds-ca.pem" < hovanki-move.dump
unset PGPASSWORD
sudo systemctl disable --now hovanki-backup.timer
docker compose down postgres
```

Затем шаги 3–5, `docker compose up -d` — сервер стартует уже на RDS. Когда всё работает: `docker volume rm hovanki_postgres-data`, `rm -r backups hovanki-move.dump hovanki-backup.*` и `sudo rm /etc/systemd/system/hovanki-backup.*`. В дампах и томе — личные данные, их не оставлять.

## Автообновление

Каждый push в `main`, прошедший проверки CI, публикует образ `ghcr.io/denysbohusevych/hovanki-server:main` (job `Server image` в `ci.yml`). На машине таймер `hovanki-update.timer` раз в 2 минуты запускает `hovanki-update.service`, а тот — `hovanki-update.sh`:

1. `docker compose pull server` — скачать образ тега `HOVANKI_TAG` из `.env`;
2. если это не тот образ, из которого запущен контейнер сервера, — `docker compose up -d server`: перезапустить сервер с новым образом. В журнал пишется «New server image … at <время UTC>»;
3. `docker image prune -f` — удалить старые образы.

Сервер обновляется примерно через 5–10 минут после push: проверки CI, сборка образа, до 2 минут ожидания таймера. Caddy таймер не трогает.

- **Каждое обновление обрывает идущие игры**: они хранятся в памяти. Образ собирается на каждый push в `main`, в том числе на правки только документации. Когда начнутся игры, таймер стоит научить ждать, пока игр нет.
- **Миграции.** Новый сервер при старте применяет миграции Flyway. Если миграция испортила данные, RDS восстанавливает базу на момент до обновления: время — в строке «New server image … at …» ([Восстановление](#восстановление)).
- **Закрепить версию**: `HOVANKI_TAG=sha-<коммит>` в `.env`, затем `docker compose up -d`. Таймер продолжит работать, но этот тег не меняется. Вернуть автообновление — `HOVANKI_TAG=main`.
- **Выключить**: `sudo systemctl disable --now hovanki-update.timer`.
- **Проверить**: `systemctl list-timers hovanki-update.timer` — когда следующий запуск; `journalctl -u hovanki-update -n 50` — что было при последних. Обновление пишет «New server image …» и «Server updated». Ошибка `unauthorized` значит, что истёк PAT: создать новый и повторить `docker login ghcr.io`.

## Бэкапы

Бэкапы делает RDS, отдельно от машины:

- **Автоматические**: раз в сутки снимок диска базы плюс журнал изменений каждые 5 минут. Из них RDS восстанавливает базу **на любую секунду срока хранения** (point-in-time recovery): 7 дней, пока аккаунт на Free plan — 1 день ([Сколько стоит](#сколько-стоит)). Хранятся у AWS в S3, в том же регионе; в пределах размера диска (20 ГБ) бесплатны.
- **Ручной снимок** перед рискованным действием: RDS → Databases → `hovanki` → Actions → Take snapshot. Хранится, пока его не удалить, и занимает место сверх бесплатного. Ненужные ручные снимки удалять (RDS → Snapshots).
- **Личные данные.** В бэкапах email и хэши паролей. Удалённый аккаунт пропадает из автоматических бэкапов, когда кончается их срок (не больше 7 дней), из ручных снимков — только вместе с ними.
- **Дамп к себе** — чтобы переехать с AWS или иметь копию вне аккаунта. На машине:
  ```bash
  cd /opt/hovanki
  DB_HOST=$(grep ^DATABASE_HOST= .env | cut -d= -f2-)
  export PGPASSWORD=$(grep ^DATABASE_PASSWORD= .env | cut -d= -f2-)
  docker run --rm -e PGPASSWORD -v /opt/hovanki/rds-ca.pem:/rds-ca.pem:ro postgres:17-alpine \
    pg_dump -Fc "host=$DB_HOST dbname=hovanki user=hovanki sslmode=verify-full sslrootcert=/rds-ca.pem" > hovanki.dump
  unset PGPASSWORD
  ```
  Затем `scp` к себе и `rm hovanki.dump` на машине.

### Восстановление

RDS восстанавливает бэкап в **новую** базу рядом со старой; сервер переключается на неё сменой `DATABASE_HOST`.

1. RDS → Databases → `hovanki` → Actions → **Restore to point in time**:
   - время: последнее хорошее в пределах срока хранения бэкапов, например за минуту до «New server image … at …» из `journalctl -u hovanki-update`;
   - DB instance identifier: `hovanki-restored`;
   - класс, диск, Single-AZ — как у `hovanki`; Connectivity: та же VPC, группа безопасности базы `rds-ec2-…` (как у `hovanki`), Public access: No;
   - Restore. Минут 10–20.
2. На машине:
   ```bash
   sudo systemctl stop hovanki-update.timer     # если восстанавливаете из-за неудачной миграции
   cd /opt/hovanki
   # В .env: DATABASE_HOST=<endpoint hovanki-restored>; при неудачной миграции ещё HOVANKI_TAG=sha-<прежний коммит>
   docker compose up -d server
   ```
   Идущие игры обрываются.
3. Проверить приложение. Старую базу `hovanki` потом удалить (сначала снять Deletion protection: Modify) или оставить на время разбора; каждая база — отдельный счёт.

- Вместе с данными восстанавливается история миграций Flyway: новые миграции сервер применит при старте. Поэтому при неудачной миграции сначала закрепите прежний образ (`HOVANKI_TAG`), иначе та же миграция выполнится снова.
- Восстановить **дамп** (например, после переезда): создать базу по [Создать базу](#создать-базу) и загрузить его, как в «Если PostgreSQL уже работал в контейнере» ([Переход на базу](#переход-на-базу)): `pg_restore --no-owner --role=hovanki --single-transaction` тем же контейнером `postgres:17-alpine`, пароль — через `PGPASSWORD`.

## Обслуживание

Команды — на машине, в `/opt/hovanki`. `DB_HOST` — endpoint базы (`grep ^DATABASE_HOST= .env`).

| Задача | Как |
|---|---|
| Обновить сервер | Само, после каждого push в `main` ([Автообновление](#автообновление)). Сразу, не дожидаясь таймера: `sudo systemctl start hovanki-update`. Обновить Caddy: `docker compose pull caddy && docker compose up -d caddy`. |
| Откатить | Прописать нужный `sha-<коммит>` в `HOVANKI_TAG` в `.env` и выполнить `docker compose up -d`. Пока там не `main`, автообновление стоит. Если старый сервер не запускается на базе после новой миграции — [восстановить](#восстановление) базу на момент до обновления. |
| Логи сервера | `docker compose logs -f server`. Ротация — 3 файла по 10 МБ на контейнер. Access-лог Caddy выключен: Caddy пишет только ошибки проксирования (например, 502, пока сервер перезапускается), значения `Authorization` и cookies в них скрыты. Сервер координаты, токены, пароли, коды, email и текст чата не логирует. |
| Логи и нагрузка базы | RDS → Databases → `hovanki` → Monitoring (CPU, память, свободное место, подключения) и Logs & events. Запросы PostgreSQL не логирует. |
| Консоль базы | `docker run --rm -it -v /opt/hovanki/rds-ca.pem:/rds-ca.pem:ro postgres:17-alpine psql "host=$DB_HOST dbname=hovanki user=hovanki sslmode=verify-full sslrootcert=/rds-ca.pem"` (спросит пароль из `DATABASE_PASSWORD`): `\dt` — таблицы, `\q` — выход. Там личные данные игроков: заходить только для обслуживания. |
| Место | Диск базы: RDS → Monitoring → Free storage space (растёт сам до 50 ГБ). Размер базы: в консоли базы `SELECT pg_size_pretty(pg_database_size('hovanki'));`. Диск машины: `df -h /`, `docker system df`. |
| Обновить PostgreSQL | Минорные версии 17.x RDS ставит сам в окно обслуживания (Auto minor version upgrade): несколько секунд база недоступна, сервер переподключается сам. Следующая major-версия — RDS → Modify → Engine version, после ручного снимка и проверки, что сервер с ней работает. |
| Сменить пароль сервера к базе | В консоли базы под `hovanki_admin` (`dbname=postgres user=hovanki_admin`): `\password hovanki`; затем новый пароль в `DATABASE_PASSWORD` в `.env` и `docker compose up -d server`. |
| Сертификаты RDS | AWS меняет центры сертификации раз в несколько лет и заранее пишет об этом на почту аккаунта. Обновить файл: `curl -fsSL -o /opt/hovanki/rds-ca.pem https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem && docker compose up -d --force-recreate server`. |
| Перезагрузка машины | Контейнеры поднимаются сами (`restart: unless-stopped`). Обновления безопасности Ubuntu ставит сама, перезагрузку после обновления ядра делать руками в спокойное время: `sudo reboot`. База от перезагрузки машины не зависит. |
| SSH не пускает | Скорее всего, сменился домашний IP. EC2 → Security Groups → правило SSH → Source: My IP. |
| Удалить всё | Сначала сделать дамп к себе ([Бэкапы](#бэкапы)). RDS → `hovanki` → Modify → снять Deletion protection → Delete (без final snapshot или с ним — он хранится и стоит денег, пока его не удалить). Затем EC2 → Instances → Terminate и Elastic IPs → Release. Непривязанный Elastic IP тоже стоит денег. |
