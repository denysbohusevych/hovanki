# Деплой сервера (AWS EC2)

Одна виртуальная машина в AWS: сервер в Docker, перед ним Caddy с сертификатом Let's Encrypt. Файлы лежат в [`deploy/`](../deploy/): `aws-user-data.sh` готовит машину при первом запуске, `compose.yaml` запускает сервер и Caddy.

## Почему так

- **Одна постоянно работающая копия.** Игры хранятся в памяти сервера. Платформы, которые усыпляют сервис без запросов или поднимают несколько копий, не подходят. Рестарт и обновление обрывают идущие игры.
- **HTTPS.** Тестовые и релизные сборки ходят только по HTTPS. Caddy сам получает и продлевает сертификат.
- **Франкфурт (`eu-central-1`).** Через сервер идут координаты игроков, поэтому данные остаются в ЕС (GDPR).
- **Без Terraform.** Одна машина, группа безопасности и IP-адрес создаются в консоли минут за десять. Повторяемость дают файлы в `deploy/`.

## Сколько стоит

- Free plan AWS (аккаунты, созданные с 15 июля 2025): $100 кредитов при регистрации и ещё до $100 за задания по $20. Два задания сделаются по ходу: бюджет в AWS Budgets и запуск EC2. План действует 6 месяцев или пока не кончатся кредиты.
- `t3.micro` во Франкфурте вместе с публичным IPv4 и диском на 10 ГБ — порядка $13 в месяц. $100 хватает больше чем на полгода.
- **Машину крупнее не брать.** На Free plan аккаунт закрывается, когда кончаются кредиты, и сервер пропадает вместе с ним.
- **До конца 6 месяцев перейти на Paid plan.** Иначе аккаунт закроют: данные хранятся 90 дней, потом удаляются. Оставшиеся кредиты действуют до 12 месяцев с регистрации.
- Когда кредиты кончатся, дешевле переехать на VPS за 4–5 € в месяц с тем же `compose.yaml`.

## Что понадобится

- Аккаунт AWS на Free plan с MFA на root-пользователе.
- Имя для сертификата: поддомен на [duckdns.org](https://www.duckdns.org) (вход через GitHub). sslip.io не подходит: у него один общий на всех лимит Let's Encrypt, и он часто исчерпан.
- GitHub PAT (classic) со scope `read:packages`: образ в GHCR приватный, как и репозиторий. GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic).
- Образ сервера в GHCR. Его публикует `release.yml`: тег `v*` даёт образы `0.1.0` и `latest`, ручной запуск (Actions → Release → Run workflow) — `sha-<коммит>` ([CI/CD](ci-cd.md#образ-сервера)).

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
6. **Сервер.** Через 2–3 минуты после запуска машины (скрипт из user data ставит Docker) выполнить с компьютера, из корня репозитория:
   ```bash
   scp -i ~/Downloads/hovanki.pem deploy/compose.yaml ubuntu@<IP>:/opt/hovanki/
   ssh -i ~/Downloads/hovanki.pem ubuntu@<IP>
   ```
   Затем на машине:
   ```bash
   cloud-init status --wait     # должно быть "status: done"
   cd /opt/hovanki
   cat > .env <<'EOF'
   DOMAIN=hovanki.duckdns.org
   HOVANKI_TAG=latest
   EOF
   docker login ghcr.io -u denysbohusevych     # пароль — PAT с read:packages
   docker compose up -d
   ```
   Пока тега `v*` нет, образа `latest` тоже нет: в `HOVANKI_TAG` указать `sha-<коммит>` из ручного запуска Release. Если `docker` отвечает «permission denied», переподключиться по SSH: группа `docker` применяется при новом входе.
7. **Проверка.** С компьютера: `curl https://hovanki.duckdns.org/actuator/health` → `{"status":"UP",…}`. Если нет, смотреть `docker compose logs caddy`. Обычно причина одна из двух: закрыт порт 80 или имя указывает не на Elastic IP.
8. **Адрес в сборках.** Строка `hovanki.serverUrl=https://hovanki.duckdns.org` в `gradle.properties` (уже вписана). Тестовые сборки из `main` стартуют с этим адресом ([CI/CD](ci-cd.md#адрес-сервера-по-умолчанию)). Другое имя — поменять строку.

## Обслуживание

Все команды — на машине, в `/opt/hovanki`.

| Задача | Как |
|---|---|
| Обновить сервер | Новый образ публикует `release.yml`. Затем `docker compose pull && docker compose up -d`. Идущие игры оборвутся. |
| Откатить | Прописать предыдущий тег в `HOVANKI_TAG` в `.env` и выполнить `docker compose up -d` |
| Логи | `docker compose logs -f server`. Ротация — 3 файла по 10 МБ на контейнер. Access-лог Caddy выключен: Caddy пишет только ошибки проксирования (например, 502, пока сервер перезапускается), значения `Authorization` и cookies в них скрыты. Сервер координаты и токены не логирует. |
| Перезагрузка машины | Контейнеры поднимаются сами (`restart: unless-stopped`). Обновления безопасности Ubuntu ставит сама, перезагрузку после обновления ядра делать руками в спокойное время: `sudo reboot`. |
| SSH не пускает | Скорее всего, сменился домашний IP. EC2 → Security Groups → правило SSH → Source: My IP. |
| Удалить всё | EC2 → Instances → Terminate, затем Elastic IPs → Release. Непривязанный Elastic IP тоже стоит денег. |
