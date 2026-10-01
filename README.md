# caja-backend

Backend de **caja** (cobranzas) sobre [wasichai](https://github.com/wasichai/wasichai): un servidor Spring Boot armado
solo con starters de wasichai. Reescribe el negocio de `caja` con la misma forma con la que `rentas` se reescribió como
`srtm-backend`. Hoy tiene el modelo de configuración de caja (áreas, cajas y tasas) con sus scripts de carga, la
**orden de cobro** (lo único que esta caja sabe cobrar) con su alta idempotente, la lista de la ventanilla, el catálogo
de cajas, los roles y **la cobranza**: cobrar órdenes emite el recibo en una sola transacción (el turno, el número, el
recibo, las órdenes PAGADA y el evento del pago en el buzón), con el original en PDF. Las tasas, la anulación, el cierre
y el publicador del buzón llegan en los PR siguientes.

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
| `CAJA_MUNICIPALIDAD_NOMBRE` | ninguno | el nombre que encabeza el recibo. **Obligatorio**: sin él la app no arranca |

## Cargar el modelo

El modelo de metadata vive en `model/model.json` y `model/apply.py` lo crea en un core que ya esté corriendo (solo
librería estándar de Python, 3.11 o más). Con el servidor levantado (`./gradlew bootRun`):

```bash
set -a; source develop/.env; set +a      # WASICHAI_CORE, WASICHAI_EMAIL y WASICHAI_PASSWORD
cd model
python3 apply.py --validate-only          # revisa el modelo contra las reglas de core, sin tocar nada
python3 apply.py --dry-run                # imprime lo que enviaría
python3 apply.py                          # crea 8 objetos y 11 relaciones ("done: 19 created")
python3 apply.py                          # la segunda vez no crea nada ("done: 0 created, 0 updated, 19 skipped")
python3 apply_roles.py                    # crea los 4 roles de caja ("done: 4 created"); ver «Roles»
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

Ocho objetos, que se crean en este orden (el destino de una relación va antes que su origen: `recibo` antes que
`orden_de_cobro`, que lo nombra): `area`, `caja`, `tasa`, `turno`, `recibo`, `orden_de_cobro`, `linea_recibo` y
`pago_evento`, y once relaciones. Vienen de las tablas `area`, `caja` y `tasa` de
`backend/kamayuk-caja-esquema/.../V1__baseline.sql` de `caja`, de `cierre_caja`, `recibo` y `recibo_detalle` de V3 y
V29, y de `orden_de_cobro` y `pago_evento` de `V2__ordenes_de_cobro_y_outbox.sql`. wasichai pone el `id`, y la columna
`municipalidad_id` de `caja` es la organización de wasichai, así que ninguna de las dos es un campo.

Los enumerados: `estado_orden` (`PENDIENTE`, `PAGADA`, `ANULADA`), `forma_pago` (`EFECTIVO`, `CHEQUE`, `DEPOSITO`,
`TARJETA`, `TRANSFERENCIA`), `tipo_pago` (`NORMAL`, `TASA`), `tipo_evento_pago` (`PAGO_REGISTRADO`, `PAGO_ANULADO`) y
`estado_evento` (`PENDIENTE`, `ENTREGADO`, `MUERTO`, `EXPLICADO`).

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

### `orden_de_cobro`

Lo único que la caja sabe cobrar: de dónde viene, cómo la llama quien la mandó, qué dice el papel, cuánto, desde cuándo
y a qué fecha está esa cifra. La da de alta el sistema de origen (`POST /api/caja/ordenes-de-cobro`). **No tiene
`tributo`, `ejercicio` ni `periodo`** (ver «Reglas»); el vínculo con el recibo llega con la cobranza.

| Campo                | Tipo      | Qué guarda                                                                                          | Columna de `caja`                     |
| -------------------- | --------- | --------------------------------------------------------------------------------------------------- | ------------------------------------- |
| `sistema_origen`     | TEXT      | Quién la mandó: minúsculas, `[a-z0-9_-]`, de 1 a 20. Obligatorio.                                   | `orden_de_cobro.sistema_origen`       |
| `referencia_externa` | TEXT      | Cómo la reconoce quien la mandó, hasta 120. **Opaca**. Obligatoria.                                 | `orden_de_cobro.referencia_externa`   |
| `clave_origen`       | TEXT      | `<sistema_origen>\|<referencia_externa>`. Obligatoria, única.                                       | calculada: reemplaza `orden_referencia_uq` |
| `concepto`           | TEXT      | Lo que se imprime en la línea del recibo, hasta 120. Obligatorio.                                   | `orden_de_cobro.concepto`             |
| `detalle`            | TEXT      | Lo que el origen quiera añadir, hasta 200.                                                          | `orden_de_cobro.detalle`              |
| `importe`            | DECIMAL   | Cuánto, a la fecha de `actualizado_a`: mayor que 0, 2 decimales a lo sumo. Obligatorio.             | `orden_de_cobro.importe`              |
| `fecha_exigibilidad` | DATE      | Desde cuándo se puede cobrar. Obligatoria.                                                          | `orden_de_cobro.fecha_exigibilidad`   |
| `actualizado_a`      | DATE      | A qué fecha está el importe (regla 9). Obligatoria.                                                 | `orden_de_cobro.actualizado_a`        |
| `pagador_documento`  | TEXT      | Con lo que se busca en ventanilla, en mayúsculas, hasta 20.                                         | `orden_de_cobro.pagador_documento`    |
| `pagador_nombre`     | TEXT      | Lo que se imprime en el recibo, hasta 150.                                                          | `orden_de_cobro.pagador_nombre`       |
| `pagador_externo_id` | INTEGER   | El id que le da el sistema de origen (en rentas, el contribuyente), mayor que 0.                    | `orden_de_cobro.pagador_externo_id`   |
| `estado`             | ENUM      | `estado_orden`: `PENDIENTE` (al nacer), `PAGADA` o `ANULADA`. Obligatorio.                          | `orden_de_cobro.estado`               |
| `observacion`        | LONG_TEXT | Por qué se dio de alta (regla 10): de 5 a 500 caracteres. Obligatoria.                              | `orden_de_cobro.observacion`          |

| `recibo`             | relación  | El recibo que la cobró (`orden_recibo`, opcional). Una orden `PAGADA` lo nombra.                    | `orden_de_cobro.recibo_id` (`orden_recibo_ck`) |

Los largos son de las columnas de `caja`: wasichai guarda TEXT sin largo, así que los comprueba el alta. `creada_en` es
el `created_at` de wasichai.

### `turno`

La apertura de una caja por un cajero en un día (`cierre_caja` de `caja`, con el nombre que tiene mientras está viva).
**No tiene endpoint propio**: el primer cobro del día lo abre, de forma implícita e idempotente. Relación `turno_caja`
(campo `caja`, obligatoria).

| Campo         | Tipo      | Qué guarda                                                                                  | Columna de `caja`             |
| ------------- | --------- | ------------------------------------------------------------------------------------------- | ----------------------------- |
| `cajero`      | TEXT      | El correo de la sesión que cobró. Obligatorio.                                              | `cierre_caja.cajero`          |
| `fecha`       | DATE      | El día de trabajo, en Lima. Obligatoria.                                                    | `cierre_caja.fecha`           |
| `abierto_en`  | DATETIME  | El instante en que se abrió, según el reloj de la caja. Obligatorio.                        | `cierre_caja.fecha_apertura`  |
| `observacion` | LONG_TEXT | Por qué se abrió: la del cobro que lo abrió (regla 10). Obligatoria.                        | `cierre_caja.observacion`     |
| `clave_turno` | TEXT      | `<caja id>\|<cajero>\|<fecha>`. Obligatoria, única.                                         | calculada: reemplaza `cierre_uq` |

### `recibo`

El papel que se entrega en ventanilla. **No se edita ni se borra**: ningún rol tiene `UPDATE` ni `DELETE` sobre él, ni
sobre sus líneas ni sobre su evento (V29 de `caja`). Relaciones `recibo_caja` y `recibo_turno` (las dos obligatorias).

| Campo                | Tipo      | Qué guarda                                                                                  | Columna de `caja`              |
| -------------------- | --------- | ------------------------------------------------------------------------------------------- | ------------------------------ |
| `serie`              | TEXT      | La serie de la caja. Obligatoria.                                                           | `recibo.serie`                 |
| `numero`             | INTEGER   | El correlativo en la serie, desde 1 y sin huecos. Obligatorio.                              | `recibo.numero`                |
| `numero_impreso`     | TEXT      | `<serie>-<número en 7 dígitos>`, como `001-0000123`. Obligatorio, único: la red de la numeración. | calculado: `NumeroDeRecibo` |
| `cajero`             | TEXT      | Quien cobró, el correo de la sesión. Obligatorio.                                           | `recibo.cajero`                |
| `pagador_documento`, `pagador_nombre`, `pagador_externo_id` | TEXT, TEXT, INTEGER | El pagador de la primera orden, congelado.  | `recibo.pagador_*`             |
| `emitido_en`         | DATETIME  | El instante de emisión. Obligatorio.                                                        | `recibo.fecha_registro`        |
| `forma_pago`         | ENUM      | `forma_pago`. Obligatoria.                                                                  | `recibo.forma_pago`            |
| `tipo_pago`          | ENUM      | `tipo_pago`: `NORMAL` si cobra órdenes. Obligatorio.                                        | `recibo.tipo_pago`             |
| `total`              | DECIMAL   | La suma exacta de sus líneas. Obligatorio.                                                  | `recibo.total`                 |
| `actualizado_a`      | DATE      | A qué fecha están sus importes (regla 9): la fecha de pago. Obligatoria.                    | `recibo.actualizado_a`         |
| `clave_idempotencia` | TEXT      | La cabecera `Idempotency-Key` del cobro, si vino. Única.                                    | `recibo.clave_idempotencia`    |
| `observacion`        | LONG_TEXT | Por qué se cobró (regla 10). Obligatoria.                                                   | `recibo.observacion`           |

### `linea_recibo`

Lo que cobró un recibo, una línea por orden, congelada. **Sale solo de la orden**: no lleva tributo, ejercicio,
periodo, predio ni vehículo, ni el desglose en insoluto, reajuste, interés y gastos (ADR-0045 de `caja` no se porta).
Relaciones `linea_recibo_recibo` (campo `recibo`, obligatoria), `linea_recibo_orden` (campo `orden`) y
`linea_recibo_tasa` (campo `tasa`, para las tasas del PR siguiente).

| Campo                | Tipo    | Qué guarda                                                   | Columna de `caja`                |
| -------------------- | ------- | ------------------------------------------------------------ | -------------------------------- |
| `sistema_origen`     | TEXT    | El de la orden.                                              | `recibo_detalle.tributo` (desde P5D) |
| `concepto`           | TEXT    | El concepto de la orden. Obligatorio.                        | `recibo_detalle.concepto`        |
| `detalle`            | TEXT    | El detalle de la orden.                                      | `recibo_detalle.detalle`         |
| `referencia_externa` | TEXT    | La referencia de la orden.                                   | `recibo_detalle.referencia_externa` |
| `cantidad`, `precio_unitario` | INTEGER, DECIMAL | Solo en una línea de tasa.                  | `recibo_detalle.cantidad`, `precio_unitario` |
| `monto`              | DECIMAL | El importe de la orden. Obligatorio.                         | la suma del desglose             |

### `pago_evento`

El buzón de salida: el aviso al sistema de origen de que se cobró un recibo. **Se escribe en la misma transacción que
el recibo**: si la fila está, el recibo está. Lo entregará un proceso aparte. Relaciones `pago_evento_recibo` y
`pago_evento_turno` (las dos obligatorias).

| Campo             | Tipo      | Qué guarda                                                                                       |
| ----------------- | --------- | ------------------------------------------------------------------------------------------------ |
| `evento_id`       | UUID      | El `pagoId`. Lo genera la caja al cobrar: un reintento de entrega manda el mismo. Obligatorio, único. |
| `tipo`            | ENUM      | `tipo_evento_pago`. Obligatorio.                                                                 |
| `sistema_destino` | TEXT      | El sistema de origen de las órdenes. Obligatorio.                                                |
| `cuerpo`          | LONG_TEXT | El evento en JSON, congelado al cobrar. Obligatorio.                                             |
| `estado`          | ENUM      | `estado_evento`: nace `PENDIENTE`. Obligatorio.                                                  |
| `intentos`        | INTEGER   | Desde 0. Obligatorio.                                                                            |
| `ultimo_error`, `entregado_en`, `explicacion` | TEXT, DATETIME, LONG_TEXT | Los escribirá el publicador.                 |

## API

Bajo `/api/caja`, con el token de core (`Authorization: Bearer …`; sin token, 401). Los permisos los aplica
`RecordService` de core **como el usuario que llama**: un rol sin el permiso recibe el 403 de core, en problem+json.

| Ruta                               | Qué hace                                                                                  | Permiso que exige                       |
| ---------------------------------- | ----------------------------------------------------------------------------------------- | --------------------------------------- |
| `POST /api/caja/ordenes-de-cobro`  | Da de alta una orden (servidor a servidor): **201** si es nueva, **200** si ya estaba.     | CREATE sobre `orden_de_cobro`, y READ para releer la que ya estaba |
| `GET /api/caja/ordenes-de-cobro`   | Lista paginada para la ventanilla: `?pagador_documento=&estado=&page=&size=`, por fecha de exigibilidad. Sin `estado`, las `PENDIENTE`. | READ sobre `orden_de_cobro` |
| `GET /api/caja/cajas`              | Lista paginada de cajas por código: `codigo`, `nombre`, `serie`, `area_codigo`, `area_nombre` y `activa`. La de baja sale con `activa: false`; una sin área, con el área en `null`. | READ sobre `caja` y sobre `area` |
| `POST /api/caja/cobros`            | Cobra órdenes y emite el recibo (ver «La cobranza»): **201** con el recibo, **200** si es el reenvío de una `Idempotency-Key` ya usada. | CREATE sobre `recibo` y UPDATE sobre `orden_de_cobro` (403 antes de empezar, diciendo cuál falta); al escribir, core exige además CREATE sobre `turno`, `linea_recibo` y `pago_evento` |
| `GET /api/caja/recibos/{numero_impreso}/pdf` | **El original** del recibo, en `application/pdf`. Solo para el cajero que lo emitió, el mismo día y con su turno abierto; si no, 409 (el duplicado llega con la consulta de recibos). | READ sobre `recibo`, `caja` y `linea_recibo` |

- **Claves snake_case**, las de los campos del modelo, en el cuerpo y en la respuesta.
- **Errores en problem+json** (RFC 7807). Un 400 lleva `errors[]` con el `field` (la clave snake_case que falló) y su
  `message`; el alta junta en un solo 400 todos los campos que fallan.
- **Todo importe va con su fecha** (regla 9) y en cadena (regla 1): `"importe": {"importe": "150.50", "actualizado_a":
  "2026-03-15"}`. El alta recibe el importe en cadena, `"150.50"`, nunca como número.

El alta recibe `sistema_origen`, `referencia_externa`, `concepto`, `detalle`, `importe`, `fecha_exigibilidad`,
`actualizado_a`, `pagador_documento`, `pagador_nombre`, `pagador_externo_id` y `observacion`. **Una propiedad que no
sea una de ésas es un 400 que la nombra** (`tributo`, `ejercicio`…). El importe va en cadena, de hasta 13 enteros y 2
decimales (`numeric(15,2)` de `caja`); `pagador_externo_id`, en cadena o en número, es un entero mayor que 0 (si no, 400
en `pagador_externo_id`). La respuesta lleva `orden_id`, los campos (con `actualizado_a` dentro de `importe`), el
`estado` y `nueva` (`true` en el 201, `false` en el 200).

El cobro recibe `caja` (el código), `forma_pago`, `ordenes` (los `orden_id`), `observacion` y, opcionales, `cajero` y
`fecha_de_pago`, con la cabecera opcional `Idempotency-Key` (de 1 a 64 caracteres). Contesta:

```json
{
  "recibo": {
    "numero_impreso": "001-0000001", "serie": "001", "numero": 1, "cajero": "ana@muni.gob.pe",
    "forma_pago": "EFECTIVO", "tipo_pago": "NORMAL", "emitido_en": "2026-10-02T10:15:30.123-05:00",
    "total": {"importe": "150.50", "actualizado_a": "2026-10-02"},
    "lineas": [{"orden_id": "…", "sistema_origen": "rentas", "concepto": "IMPUESTO PREDIAL 2026 - CUOTA 1",
                "detalle": "predio U-0001", "referencia_externa": "PREDIAL-2026-0001",
                "monto": {"importe": "150.50", "actualizado_a": "2026-10-02"}}]
  },
  "pago_id": "…", "estado_del_pago": "EN_TRANSITO", "emitido": true
}
```

`estado_del_pago` es `EN_TRANSITO` mientras el evento está `PENDIENTE` (cobrado, sin imputar todavía en el origen).
`emitido` es `false` en el reenvío de una `Idempotency-Key`: el mismo recibo y el mismo `pago_id`, sin cobrar otra vez.

## Reglas

- **La frontera.** Caja no sabe qué es un tributo: una orden no tiene `tributo`, `ejercicio` ni `periodo`. Si los
  ganara, la caja dejaría de servir para cobrar un puesto de mercado. Se defiende en la entrada (el alta rechaza la
  propiedad desconocida) y en el modelo (`FronteraDeLaOrdenTest` falla si `orden_de_cobro` gana un campo que empiece así).
- **La referencia es opaca.** `referencia_externa` no se analiza, no se compara por partes y no se ordena: solo se
  recorta. Es lo que permite que mañana sea el contrato de un puesto de mercado.
- **La idempotencia es del motor, no de un `if`** (#188 de `caja`). El alta inserta y, si el unique de `clave_origen`
  salta (`DuplicateKeyException`), relee la orden que ya estaba y la devuelve con 200, tal como estaba. No hay una
  lectura previa: dos altas simultáneas la pasarían las dos y el mismo administrado tendría dos órdenes por la misma
  deuda. `OrdenesApiTest` lanza diez altas simultáneas de la misma clave y exige una sola orden.
- **La caja no recalcula.** El importe y su fecha se guardan como llegan; la caja no comprueba el pagador contra ningún
  padrón.
- **Ningún `Double` ni `Float`**: el dinero es `BigDecimal`, y viaja en cadena.

### La cobranza

`POST /api/caja/cobros` (`caja.cobro.CobroService`, de `CobrarOrdenes` de `caja`) hace todo en **una sola transacción**
de la base: el turno, el número de la serie, el recibo con sus líneas, las órdenes `PAGADA` con su recibo y el evento
`PAGO_REGISTRADO` en el buzón se confirman juntos o no queda nada. **Si la fila del buzón está, el recibo está.**

wasichai no abre transacciones (`RecordService`, ADR-0025 de wasichai), pero escribe por `DatabaseClient`, que se une a
la transacción en curso: `caja.comun.Transaccion` la abre con el `TransactionalOperator` de Spring, y el usuario que
llama sigue en el contexto, así que core aplica sus permisos dentro igual que fuera. `CobroEnUnaTransaccionApiTest` lo
demuestra: un `RecordChangeListener` de prueba revienta al crearse el `pago_evento`, cuando el turno, el recibo, la
línea y la orden PAGADA ya están escritos, y tras el 500 no queda ninguno y el número no avanza.

Antes de empezar: el cajero es el correo de la sesión (un `cajero` distinto en el cuerpo es **403**) y el día es hoy en
Lima (una `fecha_de_pago` distinta es **400**); se exige CREATE sobre `recibo` y UPDATE sobre `orden_de_cobro` (**403**
que dice cuál falta). Luego, en este orden:

1. **El turno.** Candado `turno:<clave_turno>`; se busca por `clave_turno` y, si no está, se crea con `abierto_en`
   según el reloj y la observación del cobro. El primer cobro del día abre el turno, una vez. Una caja inexistente es
   **404**; una de baja, **409**.
2. **El candado del turno**, `turno:<id del turno>`: el que tomarán la anulación y el cierre, para que un cobro no se
   cuele en un cierre en curso.
3. **La idempotencia.** Con `Idempotency-Key`, si ya hay un recibo con esa clave se devuelve ése, con el mismo
   `pago_id` y `emitido: false` (**200**). La clave de otro cajero o de otra caja es **409**.
4. **Las órdenes.** Candado `orden:<id>` de cada una, **ordenadas por id**, y se leen después de tomarlos. Una que no
   existe es **404**; de dos sistemas de origen, **400** en `ordenes` (un recibo se anula entero); una ya pagada,
   anulada o todavía no exigible a la fecha de pago, **409** con su id. La misma orden dos veces en la petición es 400.
5. **El número.** Candado `serie:<serie de la caja>`; el siguiente es `max(numero) + 1` de la serie y `numero_impreso`
   es `"%s-%07d"`. No deja huecos: si algo falla después, nada se confirma.
6. **El recibo y sus líneas**, una por orden con su concepto, detalle, referencia, sistema y monto (el importe de la
   orden). `total` es la suma exacta; `tipo_pago`, `NORMAL`; `actualizado_a`, la fecha de pago; el pagador, el de la
   primera orden.
7. **Las órdenes pasan a `PAGADA`** con su `recibo` (`Registros.replace`, bajo el candado de cada orden).
8. **El evento.** El `pago_evento` `PAGO_REGISTRADO`, `PENDIENTE`, con 0 intentos, `sistema_destino` el de las órdenes y
   el cuerpo de `rentas.json` congelado.

**Los candados** (`caja.comun.Candados`) son consultivos de transacción, `pg_advisory_xact_lock(hashtext(:clave))`,
como `DocumentRepository` de wasichai: wasichai no bloquea filas ni tiene unicidad compuesta. Se sueltan en el commit o
el rollback, nunca antes, y `Candados.bloquear` **falla fuera de una transacción** (en autocommit no protegería nada).
Se toman siempre en el mismo orden, **turno-clave → turno → órdenes por id → serie**, para que dos cobros no se esperen
en cruz; cada decisión se toma con lo leído después de tomar su candado. Los `unique` de `clave_turno`,
`numero_impreso`, `clave_idempotencia` y `evento_id` son la red: si uno salta, la transacción entera se revierte y el
cobro contesta 409, sin reintentar dentro (postgres no deja leer nada en una transacción abortada). `hashtext` da 32
bits: dos claves pueden caer en el mismo candado, lo que solo ordena de más.

`max(numero)` se lee como el usuario que llama: un rol con «solo sus registros» (`own_records_only`) no vería los
recibos ajenos y su cobro chocaría con el `unique` de `numero_impreso` (409, sin datos). Los roles de `roles.json` no lo
tienen.

### El evento `PAGO_REGISTRADO`

El cuerpo sigue `docs/50-api/contratos-que-consume/rentas.json` de `caja`: `pagoId`, `tipo`, `sistemaOrigen`, `total`,
`actualizadoA`, `recibo` (`numero`, `serie`, `fechaDePago`, `cajero`, `formaDePago`), `pagador` (`documento`, `nombre`,
`idExterno`) y `ordenes[]` (`ordenId`, `referenciaExterna`, `importe`, `actualizadoA`), con los importes en cadena. No
lleva imputación: el origen decide qué extingue.

**Cambio del contrato:** `ordenes[].ordenId` es ahora el UUID de la orden **en cadena** (en `caja` era un entero, el id
de su tabla). `rentas` tiene que leerlo como texto.

## Roles

`model/roles.json` declara los roles de caja y, por rol, las acciones (`READ`, `CREATE`, `UPDATE` o `DELETE`) sobre
cada objeto del modelo. Los PR siguientes lo amplían con sus objetos.

| Rol               | Puede                                                    |
| ----------------- | -------------------------------------------------------- |
| `SISTEMA_ORIGEN`  | READ y CREATE sobre `orden_de_cobro`: da de alta órdenes |
| `CAJERO`          | READ sobre `area`, `caja` y `tasa`; READ y UPDATE sobre `orden_de_cobro`; READ y CREATE sobre `turno`, `recibo`, `linea_recibo` y `pago_evento`: cobra |
| `SUPERVISOR_CAJA` | lo mismo que `CAJERO`                                    |
| `TESORERIA`       | READ sobre cada objeto del modelo                        |

**Nadie tiene UPDATE ni DELETE sobre `recibo`, `linea_recibo` ni `pago_evento`** (`test_apply_roles.py` lo comprueba):
un recibo no se corrige; su anulación se agrega.

`model/apply_roles.py` los crea o sincroniza por la API de core (`POST /api/roles` y `PUT /api/roles/{name}/permissions`).
Es idempotente: un rol que falta se crea, uno que existe queda con los permisos de `roles.json` (**un permiso dado a mano
en el admin se pierde**) y con su etiqueta; la segunda corrida no escribe nada. Valida `roles.json` contra `model.json`
antes de llamar a core y lleva `--dry-run` (no llama a core), `--core`, `--email` y `--password`. `ADMIN` no se declara:
core lo deja pasar todo. Los usuarios y sus roles se asignan en el admin de core.

## Emisión del recibo

`GET /api/caja/recibos/{numero_impreso}/pdf` da **el original**: solo al cajero que lo emitió, el mismo día y con su
turno abierto (hoy todo turno del día lo está; el cierre añadirá esa condición). Cualquier otro recibe 409, que remite
al duplicado (PR de la consulta). Lo dibuja `caja.emision.PdfRenderer`, copiado de `srtm-backend` sin su cabecera
institucional: la plantilla `templates/emision/recibo.html` (Thymeleaf, standalone) a PDF con openhtmltopdf, A4, con
DejaVu Sans incrustada (`fonts/`, con su licencia) para que las tildes y la ñ salgan igual en todas partes.

El papel lleva el nombre de la municipalidad (`caja.municipalidad.nombre` / `CAJA_MUNICIPALIDAD_NOMBRE`, obligatorio:
la app no arranca sin él), el número impreso, la fecha y hora en **America/Lima**, la caja, el cajero, el pagador (su
nombre, si no su documento, si no «— (no se identificó al pagador)»), las líneas, el total, **«Importes actualizados al
<fecha>»**, la forma de pago y la observación. Todo sale del recibo y sus líneas, congelados: nada se relee de la orden.

## Tests

```bash
./gradlew build             # ktlint + tests unitarios (reglas, observación, frontera)
(cd model && python3 apply.py --validate-only && python3 -m unittest -v)   # modelo, roles e importadores
./gradlew integrationTest   # contra Testcontainers postgres:18 (o WASICHAI_TEST_DB_*)
yarn format:check           # prettier sobre yaml y json (yarn format lo corrige)
```

- **Unitarias**: `ReglasTest` y `ObservacionTest` fijan las reglas del alta; `FronteraDeLaOrdenTest` lee
  `model/model.json` y falla si la orden de cobro gana `tributo`, `ejercicio` o `periodo`. `CobranzaTest` fija las de
  la cobranza (el número impreso, el total exacto, una sola fuente, cobrable a la fecha, las claves del cuerpo de
  `PAGO_REGISTRADO` y lo que llega en la petición); `PdfRendererTest` y `ReciboPdfTest`, el PDF.
- **Integración de la API**: `CajaApiTest` es su base (aplica `model.json` y `roles.json`, da usuarios con un rol y
  comprueba los 400 por campo). `OrdenesApiTest` cubre el alta (201, 200, diez simultáneas, los 400, el 403 de un
  `CAJERO`) y la lista por pagador; `CajasApiTest`, el catálogo con y sin área. `CobroApiTest` cubre la cobranza: el
  caso feliz, el doble cobro, la idempotencia, los 400, 403, 404 y 409, **diez cobros simultáneos de la misma orden**
  (un recibo y nueve 409) y **veinte simultáneos en la misma caja** (del 1 al 20, sin huecos ni repetidos), los
  permisos y el PDF. `CobroEnUnaTransaccionApiTest` es la prueba de la transacción, y `CandadosTest` la del candado.
- **Integración** (`@Tag("integration")`): `CajaSmokeTest` levanta la app entera (`CajaApplication`) y la llama por HTTP.
  Comprueba que la salud responde `UP`, que los módulos instalados (views, forms, pages) responden y los que se dejan
  fuera (workflow, documents, gis, automatización) dan 404, que una ruta bajo `/api/caja/**` sin token da 401 y que la
  forma del modelo que usará caja funciona de punta a punta: ENUM, TEXT único, DECIMAL y una relación MANY_TO_ONE
  obligatoria.
- Con un Docker remoto no corren en local tal cual (Testcontainers no llega a sus puertos): ver
  [docs/develop/README.md](docs/develop/README.md#6-tests).

## Siguientes pasos (fuera de este alcance)

- **Negocio:** las tasas por la API de caja; la consulta, el duplicado y la anulación del recibo; el cierre del turno;
  el publicador del buzón.
