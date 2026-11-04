# Flight Booking: gRPC + Redis

Учебная распределённая система бронирования авиабилетов на Kotlin и Spring Boot. Booking Service предоставляет REST API, Flight Service управляет рейсами и резервированием по gRPC. У каждого сервиса собственная PostgreSQL. Redis кеширует информацию о рейсах и результаты поиска; Sentinel обеспечивает переключение мастера.

## Стек и структура

- Kotlin 2.2.21, Java 17, Spring Boot 3.5.16.
- Gradle Wrapper 8.14.3, gRPC Java 1.75.0, Protocol Buffers 4.32.1. Прикладной код написан на Kotlin; Java-классы контракта генерирует `protoc`.
- Spring JDBC, PostgreSQL, Flyway; Redis и клиент Lettuce с поддержкой Sentinel.
- Podman + Compose для локального запуска; Compose-файл также предназначен для Docker Compose.

```text
flight-contract/     .proto и сборка сгенерированных сообщений и stub-классов
flight-service/      gRPC API, рейсы, резервирование, кеш
booking-service/     REST API, бронирования, gRPC-клиент, retry и circuit breaker
scripts/             запуск через Podman и проверки системы
docs/                архитектура, ER-диаграмма и соответствие заданию
requests.http        примеры REST-запросов для IDE
```

## Запуск через Podman

Требуются Podman и Python 3. Скрипт запуска на macOS запускает существующую Podman machine, если она остановлена. При отсутствии `podman-compose` скрипт устанавливает зафиксированную версию в локальное окружение `.local/compose`; для первого запуска нужен доступ к сети. Java на хосте нужна только для сборки и тестов без контейнеров.

```bash
# Только если Podman machine ещё не запущена:
podman machine start

cp .env.example .env
./scripts/podman.sh up --build -d
```

В `.env` задаются параметры локального окружения, включая общий `GRPC_API_KEY` двух сервисов. Демонстрационные значения предназначены для локального запуска. Для другого окружения их необходимо заменить.

Сборка приложений, запуск двух PostgreSQL, Redis с репликой и тремя Sentinel, а также применение Flyway-миграций выполняются контейнерами. Приложения ожидают готовности зависимостей. Ручное создание таблиц не требуется.

Альтернативный запуск с Docker Compose:

```bash
docker compose up --build -d
```

| Интерфейс | Адрес с хоста |
| --- | --- |
| Booking Service REST | `http://localhost:8080` |
| Booking Service health | `http://localhost:8080/actuator/health` |
| Flight Service gRPC | `localhost:9090` |
| Flight Service health | `http://localhost:8081/actuator/health` |
| PostgreSQL Flight | `localhost:55441` |
| PostgreSQL Booking | `localhost:55442` |

Redis и Sentinel доступны только внутри Compose-сети. Сервисные базы не объединены: Booking не обращается к таблицам Flight, а Flight — к таблицам Booking.

```bash
./scripts/podman.sh ps
./scripts/podman.sh logs -f booking-service flight-service
./scripts/podman.sh down
```

`down` сохраняет данные в именованных томах. Для полностью нового демонстрационного окружения можно выполнить `./scripts/podman.sh down -v`: эта команда **удаляет данные проекта**.

## Быстрая проверка REST API

Демонстрационный рейс `11111111-1111-1111-1111-111111111111` летит из SVO в LED через 30 дней после первого применения seed-миграции, по календарю UTC. Изначально у него 10 мест, цена места — 500 000 копеек (5 000 ₽). После повторного запуска с сохранёнными томами дата и остатки не сбрасываются.

```bash
curl -s 'http://localhost:8080/flights?origin=SVO&destination=LED'

curl -s http://localhost:8080/flights/11111111-1111-1111-1111-111111111111

curl -s -X POST http://localhost:8080/bookings \
  -H 'Content-Type: application/json' \
  -d '{
    "user_id": "demo-user",
    "flight_id": "11111111-1111-1111-1111-111111111111",
    "passenger_name": "Иван Иванов",
    "passenger_email": "ivan@example.com",
    "seat_count": 2
  }'
```

В ответе создания сохраните `id` бронирования. Два места по 500 000 копеек дают `total_price_minor = 1000000`. Цена фиксируется в бронировании и не меняется при обновлении цены рейса.

```bash
# Подставьте id, полученный при создании:
BOOKING_ID='<booking-id>'
curl -s "http://localhost:8080/bookings/$BOOKING_ID"
curl -s 'http://localhost:8080/bookings?user_id=demo-user'
curl -s -X POST "http://localhost:8080/bookings/$BOOKING_ID/cancel"
```

| Метод и путь | Поведение |
| --- | --- |
| `GET /flights?origin=SVO&destination=LED&date=YYYY-MM-DD` | Поиск через gRPC; `date` необязателен, возвращаются только `SCHEDULED` |
| `GET /flights/{id}` | Информация о рейсе через gRPC; отсутствующий рейс — 404 |
| `POST /bookings` | Получение цены, резервирование и запись бронирования `CONFIRMED` |
| `GET /bookings/{id}` | Получение бронирования из Booking DB; отсутствующее — 404 |
| `GET /bookings?user_id=demo-user` | Бронирования пользователя |
| `POST /bookings/{id}/cancel` | Освобождение мест и статус `CANCELLED`; повтор не возвращает места дважды |

Все денежные поля с суффиксом `_minor` — целые копейки, валюта RUB. В REST используется `snake_case`, даты и время ответа записываются в ISO 8601 UTC. Аэропорты задаются трёхбуквенными IATA-кодами. Фильтр `date` означает календарный день вылета в UTC.

`user_id` — идентификатор из запроса, а не результат проверки личности клиента: пользовательская аутентификация в этом задании не предусмотрена. Межсервисные gRPC-вызовы аутентифицируются отдельно.

## gRPC-контракт и аутентификация

Контракт находится в [flight.proto](flight-contract/src/main/proto/flight.proto). Обязательные операции — `SearchFlights`, `GetFlight`, `ReserveSeats`, `ReleaseReservation`. Дополнительно реализован `UpdateFlight` для изменения цены и статуса: задание упоминает его при инвалидации кеша, хотя в приложении не задаёт его контракт.

Все методы Flight Service требуют metadata `x-api-key` со значением `GRPC_API_KEY`. Отсутствующий или неверный ключ даёт `UNAUTHENTICATED`. Секрет передаётся через окружение; исходный код не содержит ключа окружения.

Пример с установленным `grpcurl` (не входит в обязательные инструменты запуска):

```bash
# Используйте то же значение, что указано в .env:
export GRPC_API_KEY='<значение из .env>'

grpcurl -plaintext \
  -import-path flight-contract/src/main/proto -proto flight.proto \
  -H "x-api-key: $GRPC_API_KEY" \
  -d '{"flightId":"11111111-1111-1111-1111-111111111111"}' \
  localhost:9090 flight.v1.FlightService/GetFlight
```

Для сообщений gRPC время вылета и прилёта представлено `google.protobuf.Timestamp`, статусы — `enum`. Необязательный фильтр календарной даты передаётся строкой `YYYY-MM-DD`, поскольку календарный день не является моментом времени.

| Ситуация | gRPC status |
| --- | --- |
| Невалидный UUID, маршрут, дата, число мест или статус | `INVALID_ARGUMENT` |
| Рейс не найден | `NOT_FOUND` |
| Мест недостаточно | `RESOURCE_EXHAUSTED` |
| Рейс недоступен для резервирования, цена изменилась или повторный `booking_id` противоречит прежней операции | `FAILED_PRECONDITION` |
| Ключ отсутствует или неверен | `UNAUTHENTICATED` |
| Недоступна необходимая инфраструктура | `UNAVAILABLE` |
| Превышен deadline вызова | `DEADLINE_EXCEEDED` |

Подробности транзакций, ошибок REST и согласованности приведены в [описании архитектуры](docs/architecture.md).

## Настройки приложения

Compose задаёт контейнерные адреса БД и Sentinel. Для запуска через `bootRun` адреса должны быть доступны с хоста; Sentinel из Compose-сети напрямую с хоста не маршрутизируется. Основной воспроизводимый сценарий запуска всей системы — Compose.

| Переменная | Назначение и значение приложения по умолчанию |
| --- | --- |
| `GRPC_API_KEY` | Общий сервисный ключ, обязателен для обоих приложений |
| `FLIGHT_DB_URL`, `FLIGHT_DB_USER`, `FLIGHT_DB_PASSWORD` | Подключение Flight к своей PostgreSQL |
| `BOOKING_DB_URL`, `BOOKING_DB_USER`, `BOOKING_DB_PASSWORD` | Подключение Booking к своей PostgreSQL |
| `FLIGHT_GRPC_TARGET` | Адрес gRPC, локальный default `localhost:9090` |
| `HTTP_PORT`, `GRPC_PORT` | Порты Flight: `8081`, `9090` |
| `BOOKING_HTTP_PORT` | Порт Booking: `8080` |
| `CACHE_TTL_SECONDS` | Срок жизни кеша: `300` секунд |
| `REDIS_HOST`, `REDIS_PORT` | Standalone Redis для запуска вне Sentinel: `localhost:6379` |
| `SPRING_DATA_REDIS_SENTINEL_MASTER`, `SPRING_DATA_REDIS_SENTINEL_NODES` | Имя мастера и список Sentinel для Lettuce |
| `GRPC_DEADLINE_MS` | Deadline одной попытки: `1000` мс, допустимо 1–5000 мс |
| `GRPC_MAX_ATTEMPTS` | Общее число попыток: `3`, допустимо от 1 до 3 |
| `GRPC_RETRY_INITIAL_DELAY_MS` | Начальная пауза перед повтором: `100` мс, допустимо 0–1000 мс |
| `CB_FAILURE_THRESHOLD` | Порог технических ошибок в окне: `5` |
| `CB_WINDOW_SIZE`, `CB_MIN_CALLS` | Размер окна и минимум завершённых вызовов: `10`, `5` |
| `CB_OPEN_DURATION_MS` | Время в OPEN до пробного вызова: `10000` мс |
| `CLEANUP_GRACE_MS` | Возраст незавершённой операции перед фоновой обработкой: `30000` мс |
| `CLEANUP_INTERVAL_MS`, `CLEANUP_BATCH_SIZE` | Интервал и размер пакета восстановления: `5000` мс, `20` |

## Сборка и проверки

```bash
# Java 17; генерация контракта, компиляция и модульные тесты:
./gradlew test

# Проверки с реальными PostgreSQL и Redis:
./scripts/integration.sh

# HTTP-сценарии против запущенного Compose-окружения:
python3 scripts/smoke.py
# При другом REST-порте:
BASE_URL=http://localhost:18080 python3 scripts/smoke.py
```

Интеграционные тесты используют отдельные тестовые базы; команды и параметры описаны в скриптах. HTTP smoke проверяет поиск с датой и без неё, создание с фиксацией цены, чтение, список, инвалидацию кеша, повторную отмену, ошибки валидации и нехватки мест. Для последнего места запускаются восемь одновременных покупателей: ожидается одно успешное бронирование и семь HTTP 409. Созданные бронирования отменяются в `finally`; тестовые строки истории остаются в БД. Для одновременного ручного тестирования те же seed-рейсы не используйте. Модульные и интеграционные тесты дополнительно проверяют gRPC-аутентификацию, идемпотентность и восстановление после ошибок. Результат конкретного запуска следует смотреть в выводе команды и Gradle-отчётах `*/build/reports/tests/test/index.html`.

## Проверка отказов на отдельном демонстрационном стеке

После обычного smoke-теста можно выполнить:

```bash
./scripts/fault-check.py
```

Скрипт временно останавливает текущий Redis master, проверяет выбор нового мастера через Sentinel, чтение рейса во время отказа и заполнение кеша с конечным TTL. Затем останавливает Flight Service, проверяет переход Circuit Breaker в OPEN и быстрый HTTP 503, запускает Flight обратно и ожидает восстановления HTTP 200. В `finally` он запускает все остановленные им контейнеры.

Запускайте эту проверку только для нового демонстрационного Compose-проекта `flight-booking`, без параллельных запросов создания бронирований. Скрипт получает ID контейнеров по меткам Compose и проверяет метку проекта перед остановкой. Состояние мастера Redis после проверки может отличаться от первоначального: это нормальный результат failover. Команда не удаляет БД и тома, но во время её работы Flight и один Redis временно недоступны. Для другого REST-порта используйте `BASE_URL`; для явно переименованного проекта — соответствующий `COMPOSE_PROJECT_NAME`.

## Что требуется для сдачи

- [Архитектура, транзакции, кеш и ограничения](docs/architecture.md).
- [ER-диаграмма и обоснование 3NF](docs/er-diagram.md).
- [Соответствие десяти пунктам задания](docs/assignment-checklist.md).
- [Результаты локальных проверок](docs/verification.md).
- Исходный код, миграции, `.proto`, Compose-файл и воспроизводимые проверки.

Проект оформлен отдельным локальным Git-репозиторием. Публикация в удалённый репозиторий выполняется отдельно после выбора хостинга и адреса; для локальной сборки remote не требуется.
