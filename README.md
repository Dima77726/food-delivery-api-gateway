# food-delivery-api-gateway

Сервис платформы доставки еды. Платформа целиком (docker-compose, наблюдаемость, Postman,
общая документация) — в репозитории [food-delivery-platform](https://github.com/Dima77726/food-delivery-platform);
контракты событий, gRPC и REST — в [food-delivery-contracts](https://github.com/Dima77726/food-delivery-contracts).

## Сборка

Контракты лежат в GitHub Packages, а он требует токен даже для чтения. Один раз в
`~/.m2/settings.xml`:

```xml
<servers>
  <server>
    <id>github</id>
    <username>ВАШ_GITHUB_ЛОГИН</username>
    <password>ТОКЕН_С_ПРАВОМ_read:packages</password>
  </server>
</servers>
```

```bash
./mvnw verify
```

Интеграционным тестам нужен Docker (Testcontainers).

## Образ

```bash
docker build --secret id=maven_settings,src=$HOME/.m2/settings.xml -t food-delivery-api-gateway .
```

CI публикует образ в `ghcr.io/<владелец>/food-delivery-api-gateway` на каждый пуш в `main` и `dev`.
