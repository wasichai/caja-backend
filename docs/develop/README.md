# Desarrollo local

Cómo preparar, iniciar y probar caja-backend en una máquina de desarrollo. La descripción del proyecto está en el
[README principal](../../README.md).

## 1. Requisitos

| Herramienta | Versión | Para |
|---|---|---|
| JDK | 25 | el servidor (toolchain de Gradle) |
| Docker | cualquiera reciente | PostgreSQL (`compose.yml`) y los tests de integración |
| Node | >= 26 | solo si también corres el front, `../caja-ui` |

Las librerías de wasichai (`wasichai:wasichai-bom:0.2.0` y sus starters) se resuelven así, en este orden:

1. **GitHub Packages**. Pide un token aunque sea para leer. En `~/.gradle/gradle.properties`:
   ```properties
   gpr.user=<usuario de github>
   gpr.key=<PAT con read:packages>
   ```
   También sirven `GITHUB_ACTOR` / `GITHUB_TOKEN` en `develop/.env`.
2. **mavenLocal**, como respaldo mientras no haya una release publicada. En un checkout de
   [wasichai](https://github.com/wasichai/wasichai):
   ```bash
   cd ../wasichai && ./gradlew publishToMavenLocal -x test
   ```
   Hay que repetirlo cada vez que cambie wasichai.

## 2. Variables de entorno

```bash
cp develop/example.env develop/.env      # develop/.env está en .gitignore
```

Edita `develop/.env` si hace falta y cárgalo en el shell donde vayas a trabajar:

```bash
set -a; source develop/.env; set +a
```

| Variable | Ejemplo | Uso |
|---|---|---|
| `WASICHAI_DB_HOST` / `_PORT` / `_NAME` | `localhost` / `5434` / `caja` | a qué PostgreSQL se conecta el servidor |
| `WASICHAI_DB_USERNAME` / `_PASSWORD` | `caja` / `caja` | credenciales de esa base |
| `CAJA_PG_PORT` | `5434` | puerto que publica `compose.yml` en `127.0.0.1` |
| `WASICHAI_JWT_SECRET` | valor de desarrollo | firma de los tokens. Mínimo 32 bytes; en cualquier entorno real, uno propio |
| `WASICHAI_SEED_DEV` | `true` | crea el usuario `admin@wasichai.local` / `admin`. Apagarlo fuera de desarrollo |
| `WASICHAI_CORE` | `http://localhost:8091` | URL que usarán los scripts de carga del modelo (llegan en un PR posterior) |
| `WASICHAI_EMAIL` / `WASICHAI_PASSWORD` | el admin de desarrollo | login de esos scripts |
| `WASICHAI_TEST_DB_*` | comentadas | solo para tests de integración contra una base externa (ver 6) |

Todas tienen el mismo valor por defecto en `src/main/resources/application.yml`, así que con la base de `compose.yml`
el servidor arranca aunque no cargues nada. `develop/.env` sirve para cambiarlas sin tocar el yaml.

## 3. Base de datos

```bash
docker compose up -d          # postgres:18, contenedor caja-postgres, 127.0.0.1:5434, base caja
docker compose ps             # debe salir healthy
```

- Es PostgreSQL 18 plano: caja no usa extensiones (ni PostGIS).
- Al arrancar, el servidor crea el esquema con las migraciones Flyway de wasichai. No hay migraciones propias.
- Para empezar de cero: `docker compose down -v`, que borra el volumen con todos los datos.

**Docker remoto.** Si `DOCKER_HOST` apunta a otro servidor, el contenedor corre allá y el puerto queda en el loopback
de ese servidor. Hace falta un túnel y dejarlo abierto:

```bash
ssh -N -L 5434:localhost:5434 <usuario>@<servidor>
```

## 4. Iniciar el backend

```bash
set -a; source develop/.env; set +a
./gradlew bootRun
curl http://localhost:8091/actuator/health       # {"status":"UP",...}
```

- El servidor escucha en `http://localhost:8091`. Se detiene con `Ctrl+C`.
- Para arrancarlo desde el IDE: la clase principal es `caja.CajaApplication`. Las variables de `develop/.env` van en
  la configuración de ejecución; en IntelliJ, con un plugin de EnvFile o pegándolas en *Environment variables*.
- Otra opción es el jar: `./gradlew bootJar && java -jar build/libs/app.jar`.

## 5. Modelo y datos

Todavía no existe: este PR trae solo el esqueleto. Los objetos, campos y relaciones de caja y su carga llegan en los
PR siguientes. Hasta entonces la base queda con el esquema de wasichai y el usuario de desarrollo.

## 6. Tests

```bash
./gradlew build --no-daemon                  # ktlint + tests unitarios (excluye los de integración)
./gradlew ktlintFormat                       # formatea el Kotlin según .editorconfig
./gradlew compileTestKotlin                  # compila también los tests de integración, sin correrlos
./gradlew integrationTest                    # tests de integración: Testcontainers, necesita un Docker local
./gradlew integrationTest --tests 'caja.CajaSmokeTest'   # una sola clase
```

**Qué son los tests de integración.** Hoy es `CajaSmokeTest`, con `@Tag("integration")` (lo hereda de
`WasichaiIntegrationTest`): `build` lo excluye e `integrationTest` lo corre. Levanta la app entera (`CajaApplication`,
en un puerto aleatorio) contra un PostgreSQL plano (`postgres:18`, la propiedad `wasichai.test.db.image` de
`build.gradle.kts`) y la llama por HTTP.

- **La base se comparte** entre todas las clases de una corrida: cada test crea sus propios objetos con nombres únicos
  y no supone que la base está vacía.
- **Una clase nueva** hereda de `WasichaiIntegrationTest`.

**Por qué no corren en local con un Docker remoto.** Testcontainers crea el contenedor en el daemon al que apunta
`DOCKER_HOST`, pero lo busca en `localhost:<puerto publicado>`. Con un Docker remoto (otro servidor, o su socket
reenviado por SSH, como `unix:///tmp/docker.sock`), el contenedor y sus puertos quedan en ese servidor: ni Ryuk ni
PostgreSQL responden en `localhost` y la suite falla al arrancar, aunque `docker info` funcione. Entonces:

1. Lo habitual: compilarlos (`./gradlew compileTestKotlin`) y dejar que los corra el CI cuando exista.
2. O usar una base externa ya levantada en ese servidor y tunelizada. Descomenta `WASICHAI_TEST_DB_*` en
   `develop/.env`. Su nombre **debe terminar en `_test`**, porque la suite la limpia entera al empezar. Luego:

   ```bash
   set -a; source develop/.env; set +a
   ./gradlew integrationTest --rerun
   ```

   `--rerun` evita que la caché de Gradle devuelva un resultado verde viejo. Detalles en
   `wasichai/docs/development/getting-started.md#integration-tests`.

## 7. Con el front (`../caja-ui`)

El front todavía no existe. Cuando llegue, no llamará al backend directamente: su servidor de Vite hará proxy de `/api`
a `http://localhost:8091`, así que no hará falta configurar CORS.

```bash
# terminal 1
set -a; source develop/.env; set +a
./gradlew bootRun

# terminal 2
cd ../caja-ui && yarn dev
```

Entra con `admin@wasichai.local` / `admin`.

## Problemas comunes

| Síntoma | Causa probable |
|---|---|
| `Could not find wasichai:wasichai-spring-boot-starter…` | sin token de GitHub Packages y sin `publishToMavenLocal` (ver 1) |
| `Connection refused` a `localhost:5434` | `docker compose up -d` no corrió, o falta el túnel con Docker remoto |
| El servidor no arranca: falta `wasichai.security.jwt.secret` | `WASICHAI_JWT_SECRET` vacío en `develop/.env` |
| Puerto 8091 ocupado | otro backend corriendo: `lsof -iTCP:8091 -sTCP:LISTEN` |
| `integrationTest` no arranca (Ryuk o PostgreSQL no responden) | un Docker remoto: Testcontainers no llega a sus puertos (ver 6) |
