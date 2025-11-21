## Локальная разработка

1. Компиляция - `compile`
2. Компиляция тестов `Test / compile`
3. Запуск тестов `test`. Предварительно нужно поднять postgres - `docker-compose -f services.yml up -d postgres`
4. Поднять сервер локально
    - `docker-compose -f services.yml up -d postgres` - БД
    - `docker-compose -f services.yml up -d jaeger` - Jaeger (не обязательно)
    - `sbt -Denv.path=auth/dev/env.conf "project auth; run"` - auth сервис

## Http Server

Метрики находятся на 9345 порту по пути `/metrics`
`Liveness` проба находится на 9345 порту по пути `/liveness`
`Readiness` проба находится на 9345 порту по пути `/readiness`
Само приложение находится на порту 8080