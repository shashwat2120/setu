# Config server contract (Sprint 6A.4)

How a service gets its configuration and its secrets from one place, with the secrets kept out of the repository. The server is
`com.setu.config.ConfigServerApplication` (port 8888); the files it serves are in [`infra/config`](../../infra/config/README.md); a service
opts in with one line of configuration. Without that line nothing changes: services still read the environment and `.env.local`.

## 1. Why it is not Spring Cloud Config

Same reason as the gateway ([gateway.md](gateway.md) section 1): no Spring Cloud release train supports this project's Spring Boot 4.1.x. The
server is small (a directory reader and one endpoint) and the client side is a standard Spring Boot extension point
(`ConfigDataLocationResolver` and `ConfigDataLoader`), so nothing here depends on Spring Cloud. The protocol is deliberately simpler than
Spring Cloud Config's and not compatible with its client; moving to it later means changing the import line, not the files.

## 2. The files

A **configuration directory** (committed, `infra/config`) and a **secrets directory** (never committed: `infra/config/secrets/`, git-ignored,
or wherever `APP_CONFIGSERVER_SECRETSDIR` points, for example a mounted volume). Same file names in both:

| Layer, lowest to highest | File |
|---|---|
| 1 | `application.yml` |
| 2 | `application-<profile>.yml`, for each active profile in order |
| 3 | `<application>.yml` |
| 4 | `<application>-<profile>.yml` |

All four from the configuration directory, then the same four from the secrets directory on top. One YAML document per file (a profile is a
file name, not a section). `${...}` placeholders are returned as written and resolved by the receiving application against its own
environment. Profiles shipped: `local` (the docker compose stack on a developer machine) and `demo` (turns the demo data on, on top of `local`).

## 3. The endpoint

`GET /config/{application}[/{profiles}]` with `Authorization: Bearer <token>`; profiles are comma separated, `default` means none.

```json
{ "application": "identity", "profiles": ["local"], "version": "3f9c01ab77de", "properties": { "server.port": "8081", "...": "..." } }
```

| Situation | Status |
|---|---|
| a token for that application | 200, `Cache-Control: no-store` |
| no token, a malformed header, or a token nobody was issued | 401 |
| a valid token **for another application**, or an application with no token configured | 403 |
| a name that is not lowercase letters, digits and hyphens | 400 |

**One token per application** (`APP_CONFIGSERVER_TOKENS_<APPLICATION>`, at least 24 characters, compared in constant time). A leaked Academic
token reads Academic's configuration and nothing of Identity's, in particular not the token-signing key. Application and profile names are
matched against a strict pattern before they touch a path, so a name can never reach outside the two directories. Error bodies are the
standard envelope and never contain a token, a value or a path. Each fetch is logged with the application, profiles, caller address and
version, never a value. `/actuator/health` is public.

The server **refuses to start** without at least one token, with a token that is too short, with an application name that could not be a file
name, or with a missing configuration directory (a server that quietly serves nothing would let every application start on its defaults). A
missing secrets directory is a warning: only non-secret configuration is served.

## 4. The client

```
SPRING_CONFIG_IMPORT=optional:setu-config:http://localhost:8888
SPRING_PROFILES_ACTIVE=local,demo
SETU_CONFIG_TOKEN=<this application's token>
```

The application name asked for is `setu.config.application`, else `spring.application.name` without a trailing `-service` (so
`academic-service` asks for `academic`). The server's values rank like an imported file: above the application's packaged files and
below environment variables and the command line, so an operator can always override a value.

| Situation | Behaviour |
|---|---|
| server answers 200 | its properties are used |
| server unreachable, times out, or answers 404 or a server error | with `optional:` the application starts on what it already has and logs a warning; without it, start-up fails |
| **401 or 403** | start-up **fails even with `optional:`**, naming the variable to set. A refused token would otherwise start an application without its secrets and without anyone being told |
| plain `http` to a host that is not the local machine | warning: the token and every secret cross the network unencrypted |

The token is sent only as a bearer header and is never logged.

## 5. Keeping secrets out of the repository

`RepositoryConfigFilesTests` fails the build if any committed file under `infra/config` has a key ending in `password`, `secret`,
`signing-key`, `api-key`, `private-key`, `credential(s)` or `token` with a literal value (only empty, a bare `${NAME}` placeholder, or
`CHANGE_ME` in `secrets.example/` pass), if the example secrets stop naming what the services need, if `infra/config/secrets/` stops being
git-ignored, and if the backend's own `application.yml` grows a secret default beyond the two documented dev-only ones
(`spring.datasource.password`, `app.security.jwt.secret`, both overridden in every real deployment). It also assembles every service for
every profile from the committed files, so a typo in a profile file fails here, not at deployment.

## 6. Not done

- **Refresh at runtime.** Configuration is read at start-up. Changing a value means restarting the application that uses it. A `version` is
  returned so a later refresh mechanism can tell whether anything changed.
- **Encryption of values at rest, rotation of the per-application tokens, and TLS.** The secrets directory is the trust boundary (mount it from
  the platform's secret store); TLS is the deployment edge's job. The server is meant to be reachable only from the services' network.
- **Running as a real server on this machine.** Embedded Tomcat does not start on this Windows setup (`backend/README.md`), so the server is
  verified as a whole application through MockMvc, and the client against a real socket serving what the real controller produced.

## 7. Tests that hold this contract

| Contract | Test |
|---|---|
| Layer order, secrets on top, profile order, flattening, placeholders left alone, versions, hostile names | `ConfigAssemblerTests` |
| Per-application tokens, 401/403/400, no token or secret or path in an error | `ConfigControllerTests` |
| Refuses to start unsafely, never echoes a token | `ConfigServerConfigurationTests` |
| The whole application serving the real `infra/config` plus a secret; no database or other code | `ConfigServerWiringTests` |
| A real application start importing from a real socket: values, environment wins, optional rules, refused token always fatal | `SetuConfigDataTests` |
| No secret values committed; example secrets complete; secrets directory ignored; every service and profile assembles | `RepositoryConfigFilesTests` |
