# caja-backend

Backend de **caja** (cobranzas) sobre [wasichai](https://github.com/wasichai/wasichai): un servidor Spring Boot armado
solo con starters de wasichai. Reescribe el negocio de `caja` con la misma forma con la que `rentas` se reescribió como
`srtm-backend`. Hoy tiene el esqueleto y el modelo de configuración de caja (áreas, cajas y tasas) con sus scripts de
carga; el cobro, las reglas y la API de caja llegan en los PR siguientes.

| | |
|---|---|
| Módulos | core, views, forms, pages (sin workflow, documents ni gis) |
| Servidor | `src/`, puerto 8091 |
| Base de datos | PostgreSQL 18 plano, sin extensiones (`compose.yml`, imagen `postgres:18`, puerto 5434, base `caja`) |
| Login de desarrollo | `admin@wasichai.local` / `admin` (seed de desarrollo, `WASICHAI_SEED_DEV=true` por defecto) |

## Requisitos

- JDK 25 y Docker (para PostgreSQL y para los tests de integración con Testcontainers).
- Node 26 y yarn 1, solo para el formato (prettier) y los hooks de commit (husky + commitlint): `yarn install`.
- Las librerías de wasichai (`wasichai:wasichai-bom:0.2.0` y los starters). Se resuelven desde:
  1. **GitHub Packages** (`https://maven.pkg.github.com/wasichai/wasichai`). Pide un token aunque sea para leer
     (`read:packages` basta). En `~/.gradle/gradle.properties`:
     ```properties
     gpr.user=<usuario de github>
     gpr.key=<PAT con read:packages>
     ```
     O, si no, las variables `GITHUB_ACTOR` / `GITHUB_TOKEN`.
  2. **mavenLocal**, como respaldo mientras no haya release publicada. En un checkout de wasichai:
     `./gradlew publishToMavenLocal`.

## Arrancar

La guía completa de desarrollo local (variables, base, IDE, tests, front) está en
[docs/develop/README.md](docs/develop/README.md).

```bash
cp develop/example.env develop/.env  # variables de ejemplo; develop/.env no se versiona
set -a; source develop/.env; set +a
docker compose up -d                 # postgres 18 en localhost:5434, base caja (usuario/clave caja)
./gradlew bootRun                    # servidor en http://localhost:8091
```

`compose.yml` publica el puerto solo en `127.0.0.1`. Con un Docker remoto (`DOCKER_HOST` apuntando a otro servidor),
ese puerto queda en el loopback del servidor: llega por un túnel, `ssh -N -L 5434:localhost:5434 <servidor>`.

| Variable | Default | |
|---|---|---|
| `WASICHAI_DB_HOST` / `WASICHAI_DB_PORT` / `WASICHAI_DB_NAME` | `localhost` / `5434` / `caja` | |
| `WASICHAI_DB_USERNAME` / `WASICHAI_DB_PASSWORD` | `caja` / `caja` | |
| `WASICHAI_SEED_DEV` | `true` | crea el admin de desarrollo; apagarlo fuera de desarrollo |
| `WASICHAI_JWT_SECRET` | un valor solo para desarrollo | poner uno propio (>= 32 bytes) en cualquier entorno real |
| `CAJA_PG_PORT` | `5434` | puerto publicado por `compose.yml` |

## Cargar el modelo

El modelo de metadata vive en `model/model.json` y `model/apply.py` lo crea en un core que ya esté corriendo (solo
librería estándar de Python, 3.11 o más). Con el servidor levantado (`./gradlew bootRun`):

```bash
set -a; source develop/.env; set +a      # WASICHAI_CORE, WASICHAI_EMAIL y WASICHAI_PASSWORD
cd model
python3 apply.py --validate-only          # revisa el modelo contra las reglas de core, sin tocar nada
python3 apply.py --dry-run                # imprime lo que enviaría
python3 apply.py                          # crea 3 objetos y 2 relaciones ("done: 5 created")
python3 apply.py                          # la segunda vez no crea nada ("done: 0 created, 0 updated, 5 skipped")
```

- Sobre un core que ya tiene el modelo, `apply.py` sincroniza: agrega los campos que faltan, relaja un campo que el
  modelo ya no exige y rehace la etiqueta que difiere. Nunca renombra ni cambia el tipo de un campo.
- `python3 apply.py --drop` lo borra todo en orden inverso, datos incluidos.
- Opciones comunes: `--core` (por defecto `$WASICHAI_CORE` o `http://localhost:8091`), `--email` y `--password`.

## Cargar los datos (áreas, cajas y tasas)

Los datos de una municipalidad entran por REST con dos importadores, sin dependencias. El rechazo es **por fila, nunca
por archivo**: una fila rechazada se informa con su número de línea y su motivo y no impide las siguientes. Los dos
comprueban lo que core ya tiene **antes** de escribir, porque core contesta 500 (sin detalle) a una violación de
unicidad; el `unique` del modelo queda como red. Ambos llevan `--dry-run` (lee core y no escribe), `--core`, `--email`,
`--password` y `--archivo`, y salen con 0 si va bien y con 1 si core rechaza algo o no responde.

```bash
cd model
python3 import_cajas.py --dry-run         # data/ejemplos/cajas.csv: "cajas: 5 por crear", "áreas: 3 por crear"
python3 import_cajas.py                   # crea las áreas y las cajas
python3 import_tasas.py --archivo tasas.csv [--dry-run]
```

**`import_cajas.py`** lee `data/ejemplos/cajas.csv` (cabecera `codigo,nombre,serie,codigoArea,nombreArea`; una copia del
ejemplo de `caja`, porque es configuración de una municipalidad y no una cifra normativa).

- Se rechaza la fila incompleta (sin código, nombre o serie) y la de una serie de más de 5 caracteres.
- Un área que ya existe por `codigoArea` se reutiliza (su nombre no se reescribe); si no existe se crea con
  `nombreArea`, y sin nombre la fila se rechaza. Una caja sin `codigoArea` entra sin área.
- El código y la serie no se repiten: una fila cuyo código o serie ya existe (en core o antes en el mismo archivo) se
  rechaza. Por eso una segunda corrida del mismo archivo no escribe nada y rechaza cada fila por repetida.
- Las cajas y las áreas nuevas entran `activa = true`.

**`import_tasas.py`** lee un CSV con la cabecera
`codigo,descripcion,codigoArea,partidaPresupuestal,importe,vigenciaDesde,vigenciaHasta,documentoFuente`.

- El área tiene que existir por código (se crea con `import_cajas.py`); si no, la fila se rechaza.
- `importe` es un decimal de hasta 2 decimales y mayor o igual que 0, escrito con `0-9`, leído con `Decimal` (nunca
  `float`). Uno demasiado grande para `Decimal` rechaza su fila, no la corrida.
- `vigenciaHasta` va vacía o es mayor o igual que `vigenciaDesde`; `documentoFuente` es obligatorio.
- Calcula `clave_vigencia` (`<codigo>|<vigenciaDesde>`) y rechaza la fila si core ya la tiene.
- **No hay archivo de tarifas en este repositorio**: las cifras del TUPA salen de la normativa verificada a doble firma
  de `normativa`, no se escriben aquí. El CSV se pasa con `--archivo`; las tarifas de las pruebas son inventadas.

## Modelo

Tres objetos, que se crean en este orden, y dos relaciones. Vienen de las tablas `area`, `caja` y `tasa` de
`backend/kamayuk-caja-esquema/.../V1__baseline.sql` de `caja`. wasichai pone el `id`, y la columna `municipalidad_id`
de `caja` es la organización de wasichai, así que ninguna de las dos es un campo.

### `area`

La dependencia a la que se imputa lo que recauda una caja y que fija las tasas.

| Campo    | Tipo    | Qué guarda                                              | Columna de `caja`  |
| -------- | ------- | ------------------------------------------------------- | ------------------ |
| `codigo` | TEXT    | Como la nombra la municipalidad. Obligatorio, único.    | `area.codigo`      |
| `nombre` | TEXT    | El rótulo del área. Obligatorio.                        | `area.nombre`      |
| `activa` | BOOLEAN | Si sigue en uso: se da de baja, no se borra. Obligatorio. | `area.activa`    |

### `caja`

Una ventanilla de cobro. Relación `caja_area` (campo `area`, **no obligatoria**: las cajas tributarias no tienen área).

| Campo    | Tipo    | Qué guarda                                                                        | Columna de `caja` |
| -------- | ------- | --------------------------------------------------------------------------------- | ----------------- |
| `codigo` | TEXT    | Como la nombra la municipalidad. Obligatorio, único.                              | `caja.codigo`     |
| `nombre` | TEXT    | El rótulo de la ventanilla. Obligatorio.                                          | `caja.nombre`     |
| `serie`  | TEXT    | La serie de sus recibos: de 1 a 5 caracteres, en mayúsculas. Obligatoria, única. | `caja.serie`      |
| `activa` | BOOLEAN | Si sigue en uso. Obligatorio.                                                     | `caja.activa`     |
| `area`   | relación | El área a la que imputa. Opcional.                                              | `caja.area_id`    |

### `tasa`

La tarifa de un trámite o servicio del TUPA en una vigencia. Relación `tasa_area` (campo `area`, **obligatoria**).

| Campo                  | Tipo     | Qué guarda                                                                                     | Columna de `caja`                |
| ---------------------- | -------- | ---------------------------------------------------------------------------------------------- | -------------------------------- |
| `codigo`               | TEXT     | El código de la tasa; se repite en cada vigencia. Obligatorio.                                 | `tasa.codigo`                    |
| `descripcion`          | TEXT     | Qué se cobra. Obligatorio.                                                                     | `tasa.descripcion`               |
| `partida_presupuestal` | TEXT     | La partida a la que se imputa. Obligatorio.                                                    | `tasa.partida_presupuestal`      |
| `importe`              | DECIMAL  | La tarifa en soles, de hasta 2 decimales y no negativa. Es un dato, no un literal. Obligatorio. | `tasa.importe`                   |
| `vigencia_desde`       | DATE     | Primer día en que rige. Obligatorio.                                                           | `tasa.vigencia_desde`            |
| `vigencia_hasta`       | DATE     | Último día en que rige; vacía si sigue vigente.                                                | `tasa.vigencia_hasta`            |
| `documento_fuente`     | TEXT     | La norma o el TUPA de donde sale la tarifa. Obligatorio.                                       | `tasa.documento_fuente`          |
| `clave_vigencia`       | TEXT     | `<codigo>\|<vigencia_desde en ISO>`. Obligatoria, única.                                       | calculada: reemplaza el `UNIQUE (codigo, vigencia_desde)` (`tasa_codigo_uq`) |
| `area`                 | relación | El área que fija la tasa. Obligatoria.                                                         | `tasa.area_id`                   |

`clave_vigencia` existe porque wasichai no tiene unicidad compuesta: en vez de `(codigo, vigencia_desde)` único, un solo
campo único con los dos valores.

## Tests

```bash
./gradlew build             # ktlint + tests unitarios (hoy no hay: el smoke test es de integración)
(cd model && python3 apply.py --validate-only && python3 -m unittest -v)   # modelo e importadores
./gradlew integrationTest   # CajaSmokeTest contra Testcontainers postgres:18 (o WASICHAI_TEST_DB_*)
yarn format:check           # prettier sobre yaml y json (yarn format lo corrige)
```

- **Integración** (`@Tag("integration")`): `CajaSmokeTest` levanta la app entera (`CajaApplication`) y la llama por HTTP.
  Comprueba que la salud responde `UP`, que los módulos instalados (views, forms, pages) responden y los que se dejan
  fuera (workflow, documents, gis, automatización) dan 404, que una ruta bajo `/api/caja/**` sin token da 401 y que la
  forma del modelo que usará caja funciona de punta a punta: ENUM, TEXT único, DECIMAL y una relación MANY_TO_ONE
  obligatoria.
- Con un Docker remoto no corren en local tal cual (Testcontainers no llega a sus puertos): ver
  [docs/develop/README.md](docs/develop/README.md#6-tests).

## Siguientes pasos (fuera de este alcance)

- **Negocio:** la API de caja bajo `/api/caja/**`, el cobro y su comprobante en PDF.
