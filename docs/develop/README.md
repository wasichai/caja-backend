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
| `CAJA_MUNICIPALIDAD_NOMBRE` | `MUNICIPALIDAD DISTRITAL DE EJEMPLO` | el nombre que encabeza el recibo. **Obligatoria**: sin ella el servidor no arranca |
| `WASICHAI_JWT_SECRET` | valor de desarrollo | firma de los tokens. Mínimo 32 bytes; en cualquier entorno real, uno propio |
| `WASICHAI_SEED_DEV` | `true` | crea el usuario `admin@wasichai.local` / `admin`. Apagarlo fuera de desarrollo |
| `WASICHAI_CORE` | `http://localhost:8091` | URL que usarán los scripts de carga del modelo (model/apply.py y los importadores) |
| `WASICHAI_EMAIL` / `WASICHAI_PASSWORD` | el admin de desarrollo | login de esos scripts |
| `WASICHAI_TEST_DB_*` | comentadas | solo para tests de integración contra una base externa (ver 6) |

Todas menos `CAJA_MUNICIPALIDAD_NOMBRE` tienen el mismo valor por defecto en `src/main/resources/application.yml`, así
que con la base de `compose.yml` el servidor arranca cargando solo esa. `develop/.env` sirve para cambiarlas sin tocar el yaml.

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

El modelo (`area`, `caja`, `tasa`, `turno`, `recibo`, `orden_de_cobro`, `linea_recibo` y `pago_evento`, con sus once
relaciones) está en
`model/model.json`, los roles de caja en `model/roles.json`, y cuatro scripts de Python (solo librería estándar, 3.11 o más) lo cargan en un core que ya esté corriendo. El detalle de cada
campo está en la sección «Modelo» del [README principal](../../README.md#modelo).

```bash
set -a; source develop/.env; set +a     # WASICHAI_CORE (http://localhost:8091), WASICHAI_EMAIL, WASICHAI_PASSWORD
./gradlew bootRun                       # en otra terminal: el core tiene que estar arriba

cd model
python3 apply.py --validate-only        # el modelo cumple las reglas de core (no necesita core)
python3 apply.py                        # crea 8 objetos y 11 relaciones; la segunda vez no crea nada
python3 apply_roles.py                  # crea o sincroniza SISTEMA_ORIGEN, CAJERO, SUPERVISOR_CAJA y TESORERIA
python3 import_cajas.py --dry-run       # 5 cajas y 3 áreas del ejemplo, sin escribir
python3 import_cajas.py                 # data/ejemplos/cajas.csv
python3 import_tasas.py --archivo tasas.csv --dry-run
python3 -m unittest -v                  # las pruebas, con un core falso (FakeCore): no necesitan base ni servidor
```

- **Roles.** `apply_roles.py` deja cada rol con los permisos de `roles.json`, ni uno más: lo que se agregue a mano en
  el admin se pierde en la siguiente corrida. Va después de `apply.py`, porque nombra sus objetos. `--dry-run` imprime
  lo que mandaría sin llamar a core.
- **Orden.** `apply.py` antes que los importadores, y `import_cajas.py` antes que `import_tasas.py`: las tasas exigen que
  su área exista.
- **El rechazo es por fila.** Una fila mala se informa con su línea y su motivo y no impide las siguientes. Los dos
  importadores comprueban lo que core ya tiene antes de escribir, porque core contesta 500, sin detalle, a una violación
  de unicidad. Salen con 1 si core rechaza algo o no responde.
- **Las tarifas no están en el repositorio.** Las cifras del TUPA salen de la normativa verificada; `import_tasas.py`
  recibe el CSV por `--archivo`.
- `python3 apply.py --drop` borra los objetos del modelo y sus datos, en orden inverso (`--dry-run` lo muestra antes).
- Un core sin Docker: `./gradlew bootRun` contra cualquier PostgreSQL 18 (`WASICHAI_DB_*`, ver 2), por ejemplo un clúster
  desechable de `initdb` en otro puerto con una base `caja`.

## 6. Tests

```bash
./gradlew build --no-daemon                  # ktlint + tests unitarios (excluye los de integración)
./gradlew ktlintFormat                       # formatea el Kotlin según .editorconfig
./gradlew compileTestKotlin                  # compila también los tests de integración, sin correrlos
./gradlew integrationTest                    # tests de integración: Testcontainers, necesita un Docker local
./gradlew integrationTest --tests 'caja.cobro.OrdenesApiTest'   # una sola clase
```

**Qué son los tests de integración.** `CajaSmokeTest`, `OrdenesApiTest`, `CajasApiTest`, `CobroApiTest`,
`CobroEnUnaTransaccionApiTest`, `CandadosTest`, `TasasApiTest`, `VistaPreviaApiTest`, `ReciboApiTest` y
`AnulacionApiTest`, con `@Tag("integration")`
(lo heredan de `WasichaiIntegrationTest`): `build` los excluye e `integrationTest` los corre. Levantan la app entera
(`CajaApplication`, en un puerto aleatorio) contra un PostgreSQL plano (`postgres:18`, la propiedad
`wasichai.test.db.image` de `build.gradle.kts`) y la llaman por HTTP.

- **La base se comparte** entre todas las clases de una corrida: cada test crea sus propios objetos con nombres únicos
  y no supone que la base está vacía.
- **Una clase nueva de la API** hereda de `CajaApiTest`: antes de cada test aplica `model/model.json` y
  `model/roles.json` (como `apply.py` y `apply_roles.py`) y deja el token del admin en `token`. Da `funcionario("CAJERO")`
  (un usuario con un rol de `roles.json`), `funcionario(listOf(permiso("caja", "READ")))` (uno con un rol propio;
  `rolPropio(permisos)` da el rol para dárselo a varios con `cuenta(rol)`),
  `rejected(método, ruta, cuerpo, campo)` (un 400 cuyo primer error es ese campo), `orden(...)` (un alta válida con una
  referencia nueva), `registro(objeto, atributos)` (un registro por la API de core), `cuenta("CAJERO")` (un usuario con
  su correo: el cajero de la sesión), `nuevaCaja()` (una caja activa con una serie única), `nuevaTasa(codigo, importe, desde, hasta)` (una vigencia de
  una tasa, con un área nueva; las cifras son de la prueba), `codigoDeTasa()` (un código único), `reciboEscrito(caja, numero, emitidoEn, documento)` (un recibo
  con su turno escrito como admin, sin pasar por la cobranza: un instante de emisión fijo o un recibo sin evento) y
  `registros(objeto, filtros)` (lo guardado, leído como admin). Cada clase fija `caja.municipalidad.nombre` por `@TestPropertySource`.
- **La concurrencia y la transacción.** Las pruebas de diez y de veinte cobros simultáneos, la de diez anulaciones
  simultáneas y la del fallo a mitad (`CobroEnUnaTransaccionApiTest`, con su propio contexto por el
  `RecordChangeListener` de prueba), son el corazón de la cobranza: no se dan por buenas sin correrlas contra un
  PostgreSQL de verdad.
- **El reloj.** `AnulacionApiTest` tiene su propio contexto con un `Clock` `@Primary` que se adelanta (`RelojMovible`):
  el recibo de hoy, anulado mañana, es el recibo de ayer.

**Por qué no corren en local con un Docker remoto.** Testcontainers crea el contenedor en el daemon al que apunta
`DOCKER_HOST`, pero lo busca en `localhost:<puerto publicado>`. Con un Docker remoto (otro servidor, o su socket
reenviado por SSH, como `unix:///tmp/docker.sock`), el contenedor y sus puertos quedan en ese servidor: ni Ryuk ni
PostgreSQL responden en `localhost` y la suite falla al arrancar, aunque `docker info` funcione. Entonces:

1. Lo habitual: compilarlos (`./gradlew compileTestKotlin`) y dejar que los corra el CI.
2. O usar una base externa ya levantada en ese servidor y tunelizada. Descomenta `WASICHAI_TEST_DB_*` en
   `develop/.env`. Su nombre **debe terminar en `_test`**, porque la suite la limpia entera al empezar. Luego:

   ```bash
   set -a; source develop/.env; set +a
   ./gradlew integrationTest --rerun
   ```

   `--rerun` evita que la caché de Gradle devuelva un resultado verde viejo. Detalles en
   `wasichai/docs/development/getting-started.md#integration-tests`.
3. O un clúster desechable de PostgreSQL 18 en la máquina (Homebrew `postgresql@18`), nunca el de Homebrew:

   ```bash
   PG=/opt/homebrew/opt/postgresql@18/bin
   D=$(mktemp -d)/pg      # ruta corta: el socket de unix no admite más de 103 bytes
   $PG/initdb -D "$D" -U postgres --auth=trust >/dev/null
   $PG/pg_ctl -D "$D" -o "-p 5455 -k $D" -l "$D/log" start
   $PG/createdb -h localhost -p 5455 -U postgres caja_test
   WASICHAI_TEST_DB_HOST=localhost WASICHAI_TEST_DB_PORT=5455 WASICHAI_TEST_DB_NAME=caja_test \
   WASICHAI_TEST_DB_USERNAME=postgres WASICHAI_TEST_DB_PASSWORD=postgres ./gradlew integrationTest --rerun
   $PG/pg_ctl -D "$D" stop
   ```

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
