# Central configuration (Sprint 6A.4)

How Setu's services get their configuration and their secrets without either being baked into the repository, an image, or a developer's
machine. The server is `com.setu.config.ConfigServerApplication` (port 8888); the contract is in
[docs/contracts/config-server.md](../../docs/contracts/config-server.md).

## What is in this directory

| File | Served to | Contains |
|---|---|---|
| `application.yml` | every application | settings that must agree everywhere (token issuer, audience and lifetimes, topic prefix) |
| `application-local.yml` | every application run with profile `local` | host ports of the docker compose stack on a developer machine |
| `application-demo.yml` | profile `demo` | turns on the demo data (on top of `local`) |
| `identity.yml`, `academic.yml`, `gateway.yml` | that application | its port and name |
| `secrets.example/` | nobody | the **names** of the secrets, with `CHANGE_ME` as the value |

**No secret is ever in this directory.** `RepositoryConfigFilesTests` fails the build if a key that looks like a password, secret, key or token
has a literal value instead of a `${PLACEHOLDER}`, an empty string or `CHANGE_ME` (only in `secrets.example/`).

## Secrets

Real secrets sit in a second directory with the same file names, layered **on top** of this one, so a secret can also override a default:

* default location `infra/config/secrets/` (git-ignored), or any directory named by `APP_CONFIGSERVER_SECRETSDIR`, for example a mounted
  volume or a secret store's mount in a deployment;
* `secrets/application.yml` is served to every application (the database password); `secrets/identity.yml` only to Identity (the signing key).

Start from `secrets.example/`.

## Layers, lowest to highest

`application.yml`, `application-<profile>.yml`, `<app>.yml`, `<app>-<profile>.yml` from this directory, then the same four from the secrets
directory. Then, in the receiving application, environment variables and the command line still win over everything the server sent, so an
operator can always override a value without touching a file.

## Running it

```bash
# 1. a token per application, at least 24 random characters each (never reuse one across applications)
export APP_CONFIGSERVER_TOKENS_IDENTITY=$(node -e "console.log(require('crypto').randomBytes(24).toString('base64url'))")
export APP_CONFIGSERVER_TOKENS_ACADEMIC=$(node -e "console.log(require('crypto').randomBytes(24).toString('base64url'))")
# 2. start the server
./mvnw spring-boot:run -Dspring-boot.run.main-class=com.setu.config.ConfigServerApplication
# 3. start a service that fetches its configuration (token = the one for that application)
SPRING_CONFIG_IMPORT=optional:setu-config:http://localhost:8888 SPRING_PROFILES_ACTIVE=local,demo \
  SETU_CONFIG_TOKEN=$APP_CONFIGSERVER_TOKENS_IDENTITY ./mvnw spring-boot:run -Dspring-boot.run.main-class=com.setu.identity.IdentityServiceApplication
```

A service does nothing differently unless `SPRING_CONFIG_IMPORT` is set: without it, it reads the environment and `.env.local` exactly as before.
