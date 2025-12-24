## Local Development

1. Compilation - `compile`
2. Test compilation - `Test / compile`
3. Run tests - `test`. First, you need to start postgres - `docker-compose -f services.yml up -d postgres`
4. Start server locally
    - `docker-compose -f services.yml up -d postgres` - Database
    - `docker-compose -f services.yml up -d jaeger` - Jaeger (optional)
    - `sbt -Denv.path=auth/dev/env.conf "project postgres-impl; run"` - postgres impl

## HTTP Server

Metrics are available on port 9345 at path `/metrics`
Liveness probe is available on port 9345 at path `/liveness`
Readiness probe is available on port 9345 at path `/readiness`
The application itself runs on port 8080