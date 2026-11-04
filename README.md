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
infra/               Containerfile и статическая конфигурация Redis Sentinel
docs/                архитектура, ER-диаграмма и соответствие заданию
requests.http        примеры REST-запросов для IDE
```

## Запуск через Podman

Требуются Podman и стандартный Compose-провайдер. На macOS их можно установить через Homebrew:

```bash
brew install podman podman-compose

# Только при первой настройке, если machine ещё не создана:
podman machine init
# Если machine остановлена:
podman machine start

```

Из корня репозитория вся система запускается одной обычной командой:

```bash
podman compose up --build
```

Для запуска в фоне добавьте `-d`. Пользовательские скрипты запуска не нужны. Сборка приложений, запуск двух PostgreSQL, Redis с репликой и тремя Sentinel, а также применение Flyway-миграций выполняются контейнерами. Приложения ожидают готовности зависимостей. Ручное создание таблиц не требуется.

Compose содержит демонстрационные значения для локального запуска. При необходимости можно создать `.env` командой `cp .env.example .env` и изменить порты, пароли и `GRPC_API_KEY`. Для другого окружения демонстрационные credentials необходимо заменить. Ключ передаётся обоим приложениям через окружение контейнеров.

Альтернативный запуск с Docker Compose:

```bash
docker compose up --build
```

| Интерфейс | Адрес с хоста |
| --- | --- |
| Booking Service REST | `http://localhost:8080` |
| Booking Service health | `http://localhost:8080/actuator/health` |
| Flight Service gRPC | `localhost:9090` |
| Flight Service health | `http://localhost:8081/actuator/health` |
| PostgreSQL Flight | `localhost:55441` |
| PostgreSQL Booking | `localhost:55442` |

Redis и Sentinel доступны только внутри Compose-сети. Сервисные базы не объединены: Booking не обращается к таблицам Flight, а Flight — к таблицам Booking. Java 17 на хосте нужна только для запуска Gradle вне контейнера.

```bash
podman compose ps
podman compose logs -f booking-service flight-service
podman compose down
```

`down` сохраняет данные в именованных томах. Для полностью нового демонстрационного окружения можно выполнить `podman compose down -v`: эта команда **удаляет данные проекта**.

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

Для модульных тестов достаточно Java 17 и стандартного Gradle Wrapper:

```bash
./gradlew test
```

Тесты с PostgreSQL включаются только при наличии переменных `FLIGHT_TEST_DB_URL` и `BOOKING_TEST_DB_URL`. Без них Gradle отмечает интеграционные тесты как пропущенные. Для полного запуска сначала поднимите контейнеры и один раз создайте отдельные тестовые базы:

```bash
podman compose up --build -d
podman compose exec -T flight-db createdb -U flight flights_test
podman compose exec -T booking-db createdb -U booking bookings_test
```

Если база уже создана, повторный `createdb` сообщит об этом; удалять её для повторного запуска тестов не нужно. Тесты изменяют данные только своих тестовых баз, поэтому не направляйте их на `flights` и `bookings`.

```bash
FLIGHT_TEST_DB_URL=jdbc:postgresql://localhost:55441/flights_test \
FLIGHT_TEST_DB_USER=flight \
FLIGHT_TEST_DB_PASSWORD=flight-local \
BOOKING_TEST_DB_URL=jdbc:postgresql://localhost:55442/bookings_test \
BOOKING_TEST_DB_USER=booking \
BOOKING_TEST_DB_PASSWORD=booking-local \
./gradlew test --rerun-tasks
```

Если порты или пароли в `.env` изменены, подставьте соответствующие значения. Результат запуска доступен в выводе команды и Gradle-отчётах `*/build/reports/tests/test/index.html`. Тесты проверяют конкурентное резервирование последнего места, gRPC-аутентификацию, retry, Circuit Breaker, транзакции, идемпотентность и сохранённые операции восстановления.

REST можно проверить обычными командами `curl` из раздела выше или запросами [requests.http](requests.http). Проверка полного цикла: создать бронирование двух мест, убедиться в уменьшении `available_seats`, получить бронирование и список пользователя, дважды отменить его и проверить однократный возврат мест. Запрос с количеством мест больше остатка должен вернуть HTTP 409, а с `seat_count = 0` — HTTP 400.

## Ручная проверка отказов

Эти команды временно останавливают зависимости только текущего демонстрационного Compose-проекта. Выполняйте их без параллельного создания бронирований и после проверки запуска всей системы.

Сначала узнайте текущий Redis master:

```bash
podman compose exec -T sentinel-1 redis-cli -p 26379 SENTINEL get-master-addr-by-name flights
```

На новом окружении ответ содержит `redis-master` и порт `6379`. Если после предыдущего failover мастером уже стал `redis-replica`, в следующей команде остановите именно его. Остановите текущий master и проверьте, что REST продолжает читать данные через PostgreSQL:

```bash
podman compose stop redis-master
curl -i http://localhost:8080/flights/11111111-1111-1111-1111-111111111111
podman compose exec -T sentinel-1 redis-cli -p 26379 SENTINEL get-master-addr-by-name flights
```

Через несколько секунд повторный запрос к Sentinel должен показать нового мастера. Для исходного мастера `redis-master` новым станет `redis-replica`. Прочитайте рейс повторно и посмотрите ключи кеша на новом мастере:

```bash
curl -i http://localhost:8080/flights/11111111-1111-1111-1111-111111111111
podman compose exec -T redis-replica redis-cli --scan --pattern 'flight:*'
```

Для одного из найденных ключей выполните `redis-cli TTL`: значение должно быть положительным и не превышать 300 секунд при стандартных настройках. Подставьте настоящий суффикс ревизии вместо `<revision>`. Затем обязательно запустите остановленный Redis обратно:

```bash
podman compose exec -T redis-replica redis-cli TTL 'flight:11111111-1111-1111-1111-111111111111:v<revision>'
podman compose start redis-master
```

Для Circuit Breaker остановите Flight Service:

```bash
podman compose stop flight-service
curl -i http://localhost:8080/flights/11111111-1111-1111-1111-111111111111
```

Повторите `curl` не менее шести раз подряд. После достижения порога технических ошибок ответ должен стать быстрым HTTP 503 с `code = CIRCUIT_OPEN`. Логи покажут переход в OPEN. Запустите Flight обратно, дождитесь его готовности и снова вызовите REST:

```bash
podman compose logs booking-service
podman compose start flight-service
podman compose ps
curl -i http://localhost:8080/flights/11111111-1111-1111-1111-111111111111
```

При стандартных настройках пробный запрос допускается через 10 секунд после открытия breaker. После восстановления ожидаются HTTP 200 и переходы OPEN → HALF_OPEN → CLOSED в логах. Если завершаете проверку раньше, обязательно выполните `podman compose start flight-service` и запустите остановленный Redis. Failover может оставить реплику в роли мастера — это нормальное состояние до следующего пересоздания окружения.

## Что требуется для сдачи

- [Архитектура, транзакции, кеш и ограничения](docs/architecture.md).
- [ER-диаграмма и обоснование 3NF](docs/er-diagram.md).
- [Соответствие десяти пунктам задания](docs/assignment-checklist.md).
- [Результаты локальных проверок](docs/verification.md).
- Исходный код, миграции, `.proto`, Compose-файл и воспроизводимые проверки.

Проект оформлен отдельным локальным Git-репозиторием. Публикация в удалённый репозиторий выполняется отдельно после выбора хостинга и адреса; для локальной сборки remote не требуется.
