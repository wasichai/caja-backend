# caja-backend

Backend de **caja** (cobranzas) sobre [wasichai](https://github.com/wasichai/wasichai): un servidor Spring Boot armado
solo con starters de wasichai. Reescribe el negocio de `caja` con la misma forma con la que `rentas` se reescribió como
`srtm-backend`. Hoy tiene el modelo de configuración de caja (áreas, cajas y tasas) con sus scripts de carga, la
**orden de cobro** (lo único que esta caja sabe cobrar) con su alta idempotente, la lista de la ventanilla, el catálogo
de cajas, los roles y **la cobranza**: cobrar órdenes emite el recibo en una sola transacción (el turno, el número, el
recibo, las órdenes PAGADA y el evento del pago en el buzón), con el original en PDF. Tiene también **la caja de
tasas** (derechos del TUPA cobrados con el precio de la tarifa vigente, nunca el de la petición), la lista de las tasas
vigentes y la **vista previa del total** de los dos cobros. Y **el recibo después de emitido**: la consulta de recibos
(listado y ficha), el duplicado en PDF, registrado y marcado, y la anulación del mismo día, que se agrega sin tocar el
recibo. Y **el turno**: el turno del día de quien pregunta, su arqueo en vivo por forma de pago, el cierre que lo
congela y la reversión que lo reabre, con el candado del turno que impide que un cobro o una anulación se cuelen en un
cierre en curso. Y **el buzón de salida**: el publicador que entrega cada pago y cada anulación al sistema de origen
de sus órdenes (configurable y **apagado por defecto**), los pagos que no se pudieron entregar con la alerta a una
persona con nombre, su explicación por escrito, y dos defensas frente a un evento inventado por la API genérica de
wasichai.

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
| `CAJA_BUZON_HABILITADO` | `false` | enciende el publicador del buzón (ver «El buzón de salida») |
| `CAJA_BUZON_DESTINOS_<SISTEMA>_URL` / `_TOKEN` | ninguno | a dónde se entregan los pagos de ese sistema de origen, y su token Bearer opcional |
| `CAJA_CONCILIACION_RESPONSABLE` / `CAJA_CONCILIACION_CANAL` | ninguno | a quién avisa un pago que muere. **Obligatorios con el buzón habilitado**: sin ellos la app no arranca |

## Cargar el modelo

El modelo de metadata vive en `model/model.json` y `model/apply.py` lo crea en un core que ya esté corriendo (solo
librería estándar de Python, 3.11 o más). Con el servidor levantado (`./gradlew bootRun`):

```bash
set -a; source develop/.env; set +a      # WASICHAI_CORE, WASICHAI_EMAIL y WASICHAI_PASSWORD
cd model
python3 apply.py --validate-only          # revisa el modelo contra las reglas de core, sin tocar nada
python3 apply.py --dry-run                # imprime lo que enviaría
python3 apply.py                          # crea 13 objetos y 18 relaciones ("done: 31 created")
python3 apply.py                          # la segunda vez no crea nada ("done: 0 created, 0 updated, 31 skipped")
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
- `codigo` se guarda recortado y en mayúsculas, como lo pide la ventanilla.
- Calcula `clave_vigencia` (`<codigo>|<vigenciaDesde>`) y rechaza la fila si core ya la tiene.
- **No hay archivo de tarifas en este repositorio**: las cifras del TUPA salen de la normativa verificada a doble firma
  de `normativa`, no se escriben aquí. El CSV se pasa con `--archivo`; las tarifas de las pruebas son inventadas.

## Modelo

Trece objetos, que se crean en este orden (el destino de una relación va antes que su origen: `recibo` antes que
`orden_de_cobro`, que lo nombra): `area`, `caja`, `tasa`, `turno`, `recibo`, `orden_de_cobro`, `linea_recibo`,
`pago_evento`, `anulacion_recibo`, `reimpresion_recibo`, `cierre_turno`, `cierre_turno_linea` y `reversion_cierre`, y
dieciocho relaciones. Vienen de las tablas `area`, `caja` y `tasa` de `backend/kamayuk-caja-esquema/.../V1__baseline.sql`
de `caja`, de `cierre_caja`, `recibo` y `recibo_detalle` de V3 y V29, de `orden_de_cobro` y `pago_evento` de
`V2__ordenes_de_cobro_y_outbox.sql`, de `recibo_movimiento` de V30, partida en dos, y de `cierre_turno` y
`cierre_turno_detalle` de V32 (líneas 266-296 del baseline), con la reversión como objeto propio. wasichai pone el `id`, y la columna
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
**No se abre por un endpoint propio**: el primer cobro del día lo abre, de forma implícita e idempotente, y
`GET /api/caja/turnos/del-dia` lo publica sin abrirlo. No tiene estado: si está abierto o cerrado se deriva de sus
cierres y reversiones (ver «El turno»). Relación `turno_caja` (campo `caja`, obligatoria).

| Campo         | Tipo      | Qué guarda                                                                                  | Columna de `caja`             |
| ------------- | --------- | ------------------------------------------------------------------------------------------- | ----------------------------- |
| `cajero`      | TEXT      | El correo de la sesión que cobró. Obligatorio.                                              | `cierre_caja.cajero`          |
| `fecha`       | DATE      | El día de trabajo, en Lima. Obligatoria.                                                    | `cierre_caja.fecha`           |
| `abierto_en`  | DATETIME  | El instante en que se abrió, según el reloj de la caja. Obligatorio.                        | `cierre_caja.fecha_apertura`  |
| `observacion` | LONG_TEXT | Por qué se abrió: la del cobro que lo abrió (regla 10). Obligatoria.                        | `cierre_caja.observacion`     |
| `clave_turno` | TEXT      | `<caja id>\|<cajero>\|<fecha>`. Obligatoria, única.                                         | calculada: reemplaza `cierre_uq` |

### `recibo`

El papel que se entrega en ventanilla. **No se edita ni se borra**: ningún rol tiene `UPDATE` ni `DELETE` sobre él, ni
sobre sus líneas, su evento, su anulación ni sus reimpresiones (V29 de `caja`), y `src/main` no tiene ningún `replace`,
`update` ni `delete` sobre ellos (`InmutabilidadDelReciboTest`). No tiene `estado`: anulado o no se deriva de que
exista su `anulacion_recibo`. Relaciones `recibo_caja` y `recibo_turno` (las dos obligatorias).

| Campo                | Tipo      | Qué guarda                                                                                  | Columna de `caja`              |
| -------------------- | --------- | ------------------------------------------------------------------------------------------- | ------------------------------ |
| `serie`              | TEXT      | La serie de la caja. Obligatoria.                                                           | `recibo.serie`                 |
| `numero`             | INTEGER   | El correlativo en la serie, desde 1 y sin huecos. Obligatorio.                              | `recibo.numero`                |
| `numero_impreso`     | TEXT      | `<serie>-<número en 7 dígitos>`, como `001-0000123`. Obligatorio, único: la red de la numeración. | calculado: `NumeroDeRecibo` |
| `cajero`             | TEXT      | Quien cobró, el correo de la sesión. Obligatorio.                                           | `recibo.cajero`                |
| `pagador_documento`, `pagador_nombre`, `pagador_externo_id` | TEXT, TEXT, INTEGER | El pagador de la primera orden, congelado.  | `recibo.pagador_*`             |
| `emitido_en`         | DATETIME  | El instante de emisión. Obligatorio.                                                        | `recibo.fecha_registro`        |
| `forma_pago`         | ENUM      | `forma_pago`. Obligatoria.                                                                  | `recibo.forma_pago`            |
| `tipo_pago`          | ENUM      | `tipo_pago`: `NORMAL` si cobra órdenes, `TASA` si cobra tasas del TUPA. Obligatorio.        | `recibo.tipo_pago`             |
| `total`              | DECIMAL   | La suma exacta de sus líneas. Obligatorio.                                                  | `recibo.total`                 |
| `actualizado_a`      | DATE      | A qué fecha están sus importes (regla 9): la fecha de pago. Obligatoria.                    | `recibo.actualizado_a`         |
| `clave_idempotencia` | TEXT      | La cabecera `Idempotency-Key` del cobro, si vino. Única.                                    | `recibo.clave_idempotencia`    |
| `observacion`        | LONG_TEXT | Por qué se cobró (regla 10). Obligatoria.                                                   | `recibo.observacion`           |

### `linea_recibo`

Lo que cobró un recibo, una línea por orden (o por concepto del TUPA), congelada. **Sale solo de la orden**: no lleva tributo, ejercicio,
periodo, predio ni vehículo, ni el desglose en insoluto, reajuste, interés y gastos (ADR-0045 de `caja` no se porta).
Relaciones `linea_recibo_recibo` (campo `recibo`, obligatoria), `linea_recibo_orden` (campo `orden`) y
`linea_recibo_tasa` (campo `tasa`, en una línea de tasa).

| Campo                | Tipo    | Qué guarda                                                   | Columna de `caja`                |
| -------------------- | ------- | ------------------------------------------------------------ | -------------------------------- |
| `sistema_origen`     | TEXT    | El de la orden.                                              | `recibo_detalle.tributo` (desde P5D) |
| `concepto`           | TEXT    | El concepto de la orden, o la descripción de la tasa. Obligatorio. | `recibo_detalle.concepto`  |
| `detalle`            | TEXT    | El detalle de la orden.                                      | `recibo_detalle.detalle`         |
| `referencia_externa` | TEXT    | La referencia de la orden.                                   | `recibo_detalle.referencia_externa` |
| `cantidad`, `precio_unitario` | INTEGER, DECIMAL | Solo en una línea de tasa: cuántas veces y la tarifa vigente. | `recibo_detalle.cantidad`, `precio_unitario` |
| `monto`              | DECIMAL | El importe de la orden, o `precio_unitario × cantidad` en una tasa. Obligatorio. | la suma del desglose |

### `anulacion_recibo`

El acta de la anulación de un recibo (`recibo_movimiento` de `caja`, tipo `ANULACION`): **se agrega, y el recibo no se
toca**. Crearla es el privilegio `ELIMINACION` de `caja`. Relaciones `anulacion_recibo_recibo`, `anulacion_recibo_caja`
y `anulacion_recibo_turno` (las tres obligatorias): la caja y el turno son **los del recibo**, para que el arqueo de ese
turno reste lo anulado.

| Campo                    | Tipo      | Qué guarda                                                                                     |
| ------------------------ | --------- | ---------------------------------------------------------------------------------------------- |
| `recibo_anulado`         | TEXT      | El id del recibo, otra vez. Obligatorio, **único**: reemplaza `recibo_movimiento_anulacion_uq`, un recibo se anula una sola vez. |
| `fecha`                  | DATE      | El día de la anulación, en Lima: el del turno del recibo. Obligatoria.                         |
| `motivo`                 | TEXT      | El sustento del acto, de hasta 80 y no en blanco: se imprime en el duplicado. Obligatorio.     |
| `autorizado_por`         | TEXT      | Quien lo autorizó, si consta: hasta 80.                                                        |
| `documento_autorizacion` | TEXT      | El memorando o la resolución, si consta: hasta 40.                                             |
| `importe`                | DECIMAL   | El total del recibo, congelado. Obligatorio.                                                   |
| `usuario`                | TEXT      | Quien anuló: el correo de la sesión. Obligatorio.                                              |
| `observacion`            | LONG_TEXT | Por qué se registra (regla 10); es otra cosa que el motivo. Obligatoria.                       |

### `reimpresion_recibo`

Un duplicado de un recibo (`recibo_movimiento` de `caja`, tipo `DUPLICADO`): cada reimpresión deja su fila, y de
contarlas sale el «DUPLICADO N.°» del papel. Crearla es el privilegio `IMPRESION` de `caja`. Relación
`reimpresion_recibo_recibo` (obligatoria).

| Campo         | Tipo      | Qué guarda                                                                                         |
| ------------- | --------- | -------------------------------------------------------------------------------------------------- |
| `fecha`       | DATE      | El día de la reimpresión, en Lima. Obligatoria.                                                    |
| `resumen`     | TEXT      | El SHA-256 (64 caracteres hexadecimales) de lo congelado en el recibo y sus líneas (ver «El duplicado»). Obligatorio. |
| `usuario`     | TEXT      | Quien la pidió. Obligatorio.                                                                       |
| `observacion` | LONG_TEXT | Por qué se reimprime (regla 10). Obligatoria.                                                      |

### `pago_evento`

El buzón de salida: el aviso al sistema de origen de que se cobró (o se anuló) un recibo. **Se escribe en la misma
transacción que el recibo**: si la fila está, el recibo está. Lo entrega el publicador (ver «El buzón de salida»).
Relaciones `pago_evento_recibo` y `pago_evento_turno` (las dos obligatorias). `estado_evento`: nace `PENDIENTE` (el
pago en tránsito, con la hora de su `created_at`), y queda `ENTREGADO`, `MUERTO` (no se pudo entregar: dinero cobrado
sin registrar) o `EXPLICADO` (un `MUERTO` del que alguien se hizo cargo por escrito).

| Campo             | Tipo      | Qué guarda                                                                                       |
| ----------------- | --------- | ------------------------------------------------------------------------------------------------ |
| `evento_id`       | UUID      | El `pagoId`. Lo genera la caja al cobrar: un reintento de entrega manda el mismo. Obligatorio, único. |
| `tipo`            | ENUM      | `tipo_evento_pago`. Obligatorio.                                                                 |
| `sistema_destino` | TEXT      | El sistema de origen de las órdenes. Obligatorio.                                                |
| `cuerpo`          | LONG_TEXT | El evento en JSON, congelado al cobrar. Obligatorio.                                             |
| `estado`          | ENUM      | `estado_evento`: nace `PENDIENTE`. Obligatorio.                                                  |
| `intentos`        | INTEGER   | Desde 0. Obligatorio.                                                                            |
| `ultimo_error`    | TEXT      | Por qué falló el último intento, recortado a 400 caracteres y sin credenciales. Lo escribe el publicador. |
| `entregado_en`    | DATETIME  | Solo si está `ENTREGADO`. Lo escribe el publicador.                                              |
| `explicacion`     | LONG_TEXT | Solo si está `EXPLICADO`: qué pasó y qué se hizo, al menos 5 caracteres.                        |

### `cierre_turno`

El acta del cierre de un turno con su arqueo **congelado** (`cierre_turno` de `caja`, V32, RF-087). **Solo se agrega**:
un cierre no se modifica ni se borra, se reversa con una `reversion_cierre`. Crearlo es el privilegio `REGISTRO` de
`cierre_caja`. Relación `cierre_turno_turno` (campo `turno`, obligatoria). Todos los campos son obligatorios.

| Campo                                                   | Tipo     | Qué guarda                                                                 |
| ------------------------------------------------------- | -------- | -------------------------------------------------------------------------- |
| `secuencia`                                             | INTEGER  | Su lugar en la historia del turno, desde 1, **común con la reversión**: los movimientos que había más uno. |
| `fecha`                                                 | DATE     | El día del turno que se cierra, en Lima: la fecha a la que se leyó el arqueo. |
| `registrado_en`                                         | DATETIME | El instante en que se firmó.                                               |
| `total_cobrado`, `total_anulado`, `neto`                | DECIMAL  | Lo cobrado, lo que sacaron sus anulaciones y la diferencia.                |
| `total_declarado`, `diferencia`                         | DECIMAL  | Lo contado en el cajón y lo declarado menos el neto (negativa si falta dinero). |
| `recibos_emitidos`, `recibos_anulados`                  | INTEGER  | Cuántos recibos emitió el turno y cuántos de ellos se anularon.            |
| `cobrado_con_evento`, `cobrado_sin_evento`              | DECIMAL  | Las dos mitades del cuadre (órdenes y tasas): suman el neto.               |
| `usuario`, `observacion`                                | TEXT, LONG_TEXT | Quien cerró y por qué (regla 10).                                   |
| `clave_secuencia`                                       | TEXT     | `<turno>\|<secuencia>`. **Único**: la red bajo el candado del turno (reemplaza `cierre_turno_secuencia_uq`). |

### `cierre_turno_linea`

El arqueo del cierre forma de pago por forma de pago (`cierre_turno_detalle` de `caja`). Relación
`cierre_turno_linea_cierre_turno` (campo `cierre_turno`, obligatoria). Todos los campos son obligatorios.

| Campo                                   | Tipo    | Qué guarda                                                                           |
| --------------------------------------- | ------- | ------------------------------------------------------------------------------------ |
| `forma_pago`                            | ENUM    | `forma_pago`.                                                                        |
| `cobrado`, `anulado`, `neto`, `declarado` | DECIMAL | Lo cobrado y lo anulado con esa forma, el neto y lo declarado (cero si no se declaró). |
| `clave`                                 | TEXT    | `<cierre>\|<forma_pago>`. **Única**: una línea por forma de pago y cierre.          |

### `reversion_cierre`

Deja sin efecto el cierre vigente de un turno y **lo reabre** (`cierre_turno` de `caja`, tipo `REVERSION`). El cierre
reversado no se toca. Crearla es el privilegio `ELIMINACION` de `cierre_caja`. Relación `reversion_cierre_turno` (campo
`turno`, obligatoria). Todos los campos son obligatorios.

| Campo                      | Tipo      | Qué guarda                                                                               |
| -------------------------- | --------- | ---------------------------------------------------------------------------------------- |
| `cierre_revertido`         | TEXT      | El id del `cierre_turno` que deja sin efecto. **Único**: un cierre se reversa una vez (reemplaza `cierre_turno_reversion_uq`). |
| `secuencia`                | INTEGER   | Su lugar en la historia del turno, común con el cierre.                                  |
| `motivo`                   | TEXT      | El sustento de reabrir una caja ya arqueada: hasta 80 y no en blanco.                    |
| `fecha`, `registrado_en`   | DATE, DATETIME | El día del turno que se reabre y el instante en que se reversó.                     |
| `usuario`, `observacion`   | TEXT, LONG_TEXT | Quien reversó y por qué se registra (regla 10); la observación es otra cosa que el motivo. |
| `clave_secuencia`          | TEXT      | `<turno>\|<secuencia>`. **Único**.                                                      |

## API

Bajo `/api/caja`, con el token de core (`Authorization: Bearer …`; sin token, 401). Los permisos los aplica
`RecordService` de core **como el usuario que llama**: un rol sin el permiso recibe el 403 de core, en problem+json.

| Ruta                               | Qué hace                                                                                  | Permiso que exige                       |
| ---------------------------------- | ----------------------------------------------------------------------------------------- | --------------------------------------- |
| `POST /api/caja/ordenes-de-cobro`  | Da de alta una orden (servidor a servidor): **201** si es nueva, **200** si ya estaba.     | CREATE sobre `orden_de_cobro`, y READ para releer la que ya estaba |
| `GET /api/caja/ordenes-de-cobro`   | Lista paginada para la ventanilla: `?pagador_documento=&estado=&page=&size=`, por fecha de exigibilidad. Sin `estado`, las `PENDIENTE`. | READ sobre `orden_de_cobro` |
| `GET /api/caja/cajas`              | Lista paginada de cajas por código: `codigo`, `nombre`, `serie`, `area_codigo`, `area_nombre` y `activa`. La de baja sale con `activa: false`; una sin área, con el área en `null`. | READ sobre `caja` y sobre `area` |
| `POST /api/caja/cobros`            | Cobra órdenes y emite el recibo (ver «La cobranza»): **201** con el recibo, **200** si es el reenvío de una `Idempotency-Key` ya usada. | CREATE sobre `recibo` y UPDATE sobre `orden_de_cobro` (403 antes de empezar, diciendo cuál falta); al escribir, core exige además CREATE sobre `turno`, `linea_recibo` y `pago_evento` |
| `POST /api/caja/cobros/vista-previa` | Lo que costaría cobrar unas órdenes hoy, **sin escribir nada** (ver «La vista previa del total»): **200**. | READ sobre `orden_de_cobro` |
| `POST /api/caja/cobros/tasas`      | Cobra conceptos del TUPA y emite el recibo (ver «La caja de tasas»): **201**, o **200** en el reenvío de una `Idempotency-Key`. | CREATE sobre `recibo` (403 antes de empezar); al escribir, core exige además READ sobre `tasa` y CREATE sobre `turno` y `linea_recibo` |
| `POST /api/caja/cobros/tasas/vista-previa` | Lo que costaría cobrar unos conceptos hoy, sin escribir nada: **200**. | READ sobre `tasa` |
| `GET /api/caja/tasas`              | Las tasas vigentes a `?vigentes_a=AAAA-MM-DD` (por defecto hoy en Lima), por código: la lista que ofrece la ventanilla. | READ sobre `tasa` y sobre `area` |
| `GET /api/caja/recibos/{numero_impreso}/pdf` | **El original** del recibo, en `application/pdf`. Solo para el cajero que lo emitió, el mismo día, con su turno abierto y sin anular; si no, 409, que remite al duplicado. | READ sobre `recibo`, `caja`, `linea_recibo` y `anulacion_recibo` |
| `GET /api/caja/recibos`            | El listado paginado (ver «La consulta de recibos»): `?documento=&caja=&cajero=&desde=&hasta=&estado=&page=&size=`, del más reciente al más antiguo. | READ sobre `recibo`, `anulacion_recibo` y `reimpresion_recibo` (y `caja` si se filtra por ella) |
| `GET /api/caja/recibos/{numero_impreso}` | La ficha: el recibo con sus líneas, `estado`, `duplicados` y `anulacion`. **404** si no existe, **400** si el número está mal formado. | READ sobre `recibo`, `linea_recibo`, `caja`, `tasa`, `anulacion_recibo` y `reimpresion_recibo` |
| `POST /api/caja/recibos/{numero_impreso}/duplicados` | El duplicado en PDF, marcado y numerado, y lo registra (ver «El duplicado»): **201** `application/pdf`; **409** si ya no se dibuja igual. | CREATE sobre `reimpresion_recibo` (403 antes de empezar) |
| `POST /api/caja/recibos/{numero_impreso}/anulacion` | Anula el recibo del día (ver «La anulación»): **201** con el acta. | CREATE sobre `anulacion_recibo` (403 antes de empezar); el recibo de otro cajero, además, el rol `SUPERVISOR_CAJA`; al escribir, core exige UPDATE sobre `orden_de_cobro` y CREATE sobre `pago_evento` |
| `GET /api/caja/turnos/del-dia`     | Los turnos de hoy de quien pregunta y su situación (ver «El turno»). **No abre ningún turno**; cualquier parámetro es 400. | READ sobre `turno`, `caja`, `cierre_turno` y `reversion_cierre` |
| `GET /api/caja/turnos/{turno_id}/arqueo` | El arqueo en vivo, las dos mitades del cuadre y lo que impide cerrar: **200**; **404** si el turno no existe, **400** si el id no es un uuid. | READ sobre `turno`, `recibo`, `anulacion_recibo`, `pago_evento`, `cierre_turno` y `reversion_cierre` |
| `POST /api/caja/turnos/cierre`     | Cierra el turno con su arqueo (ver «El cierre»): **201** con el acta. | CREATE sobre `cierre_turno` y `cierre_turno_linea` (403 antes de empezar) |
| `POST /api/caja/turnos/reversion`  | Reversa el cierre vigente y reabre el turno (ver «La reversión»): **201**. | CREATE sobre `reversion_cierre` (403 antes de empezar): `SUPERVISOR_CAJA` |
| `GET /api/caja/pagos/sin-entregar` | Los pagos `MUERTO`, del más antiguo al más reciente (ver «Los pagos sin entregar»): **200** con una lista. | READ sobre `pago_evento` y `recibo` (403 antes de empezar) |
| `POST /api/caja/pagos/{pago_id}/explicacion` | Pasa un pago `MUERTO` a `EXPLICADO` con su `explicacion` y su `observacion`: **200** con el pago; **409** si no está `MUERTO`. | UPDATE sobre `pago_evento` (403 antes de empezar): `SUPERVISOR_CAJA` |

- **Claves snake_case**, las de los campos del modelo, en el cuerpo y en la respuesta.
- **Errores en problem+json** (RFC 7807). Un 400 lleva `errors[]` con el `field` (la clave snake_case que falló) y su
  `message`; el alta y los cobros juntan en un solo 400 todos los campos que fallan (en el de tasas, también los de cada
  concepto).
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
    "pagador_documento": "12345678", "pagador_nombre": "FLORES OTINIANO JUNIOR", "pagador_externo_id": 1234,
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
El recibo lleva además `pagador_documento`, `pagador_nombre` y `pagador_externo_id` **tal como quedaron guardados** (el
documento recortado y en mayúsculas), en el cobro de órdenes, en el de tasas, en el reenvío y en la ficha: la ventanilla
muestra lo que dice el recibo, no lo que tecleó el cajero.

El cobro de tasas recibe `caja`, `forma_pago`, `conceptos` (`[{"codigo": "T-001", "cantidad": 3}]`, la cantidad es 1 si
no viene; el código se lee recortado y en mayúsculas), `observacion` y, opcionales, `cajero`, `fecha_de_cobro` (hoy en Lima, como `fecha_de_pago`),
`pagador_documento`, `pagador_nombre` y `pagador_externo_id` (el pagador puede ser anónimo). **No lleva precio ni
importe**: un `importe` o un `precio` en el cuerpo o en un concepto es un 400 que lo nombra (`importe`,
`conceptos[0].precio`). Contesta lo mismo que el cobro de órdenes, con `tipo_pago` `TASA`, `pago_id` `null` y
`estado_del_pago` `SIN_EVENTO`; cada línea lleva además `codigo`, `cantidad` y `precio_unitario` (con su fecha):

```json
{"codigo": "T-001", "concepto": "CONSTANCIA DE NO ADEUDO", "cantidad": 3,
 "precio_unitario": {"importe": "12.30", "actualizado_a": "2026-10-02"},
 "monto": {"importe": "36.90", "actualizado_a": "2026-10-02"},
 "orden_id": null, "sistema_origen": null, "detalle": null, "referencia_externa": null}
```

`GET /api/caja/tasas` devuelve una lista de `{"codigo", "descripcion", "area" (el código del área),
"partida_presupuestal", "precio": {"importe", "actualizado_a"}}`; `actualizado_a` es la fecha consultada. Una fecha que
no es AAAA-MM-DD es 400 en `vigentes_a`.

Las dos vistas previas reciben lo mismo que su cobro (`{"ordenes": [...], "fecha_de_pago"?}` y `{"conceptos": [...],
"fecha_de_cobro"?}`) y contestan `{"lineas": [...], "total": {"importe", "actualizado_a"} o null si no hay líneas,
"cobrable": true|false, "motivos": ["..."]}`, con las líneas como las del recibo: solo las que se pueden cobrar.

El listado (`GET /api/caja/recibos`) contesta una página de filas:

```json
{
  "content": [
    {"numero_impreso": "001-0000002", "emitido_en": "2026-10-02T10:20:00.123456-05:00", "pagador_documento": "12345678",
     "pagador_nombre": "FLORES OTINIANO JUNIOR", "total": {"importe": "150.50", "actualizado_a": "2026-10-02"},
     "forma_pago": "EFECTIVO", "duplicados": 1, "estado": "ANULADO"}
  ],
  "page": 0, "size": 25, "totalElements": 1, "totalPages": 1
}
```

La ficha (`GET /api/caja/recibos/{numero_impreso}`) lleva `numero_impreso`, `serie`, `numero`, `caja` (el código),
`cajero`, `emitido_en`, `pagador_documento`, `pagador_nombre`, `pagador_externo_id`, `forma_pago`, `tipo_pago`, `total`, `observacion`,
`lineas` (como las del cobro), `estado`, `duplicados` y `anulacion`: `{"fecha", "motivo", "autorizado_por",
"documento_autorizacion", "usuario"}` o `null`. El número se escribe como en el papel (`001-0000123`; `001-123` también
vale); lo que no tiene esa forma es 400 en `numero_impreso`.

El duplicado recibe `{"observacion": "..."}` y contesta el PDF (`Content-Disposition: inline;
filename="recibo-001-0000123-duplicado-2.pdf"`). La anulación recibe `{"motivo", "autorizado_por"?,
"documento_autorizacion"?, "observacion"}` y contesta **201**:

```json
{"numero_impreso": "001-0000123", "estado": "ANULADO", "fecha": "2026-10-02", "motivo": "COBRO EN DEMASÍA",
 "autorizado_por": "JEFE DE CAJA", "documento_autorizacion": "MEMO 12-2026", "usuario": "jefe@muni.gob.pe",
 "importe": {"importe": "150.50", "actualizado_a": "2026-10-02"}, "pago_anulado_id": "…"}
```

`pago_anulado_id` es el `pagoId` del `PAGO_ANULADO`, o `null` en un recibo de tasas.

El turno del día (`GET /api/caja/turnos/del-dia`) contesta, también sin ningún turno (`"situacion": "SIN_ABRIR", "turnos": []`):

```json
{"cajero": "ana@muni.gob.pe", "fecha": "2026-10-02", "situacion": "ABIERTO",
 "turnos": [{"turno_id": "…", "caja": "C-01", "caja_nombre": "VENTANILLA 1", "cajero": "ana@muni.gob.pe",
             "fecha": "2026-10-02", "abierto_en": "2026-10-02T08:01:12.345-05:00", "estado_del_turno": "ABIERTO"}]}
```

El arqueo en vivo (`GET /api/caja/turnos/{turno_id}/arqueo`) lleva cada cifra como `Importe` a hoy, y **`declarado`,
`diferencia`, `total_declarado` y `cuadra` en `null`**: un GET no lleva el recuento del cajón, y un cero se leería como
«se contó cero».

```json
{"turno_id": "…", "estado_del_turno": "ABIERTO", "puede_cerrar": false,
 "arqueo": {"lineas": [{"forma_pago": "EFECTIVO", "cobrado": {"importe": "187.40", "actualizado_a": "2026-10-02"},
                        "anulado": {…}, "neto": {…}, "declarado": null, "diferencia": null}],
            "recibos_emitidos": 4, "recibos_anulados": 1, "total_cobrado": {…}, "total_anulado": {…}, "neto": {…},
            "total_declarado": null, "diferencia": null, "cuadra": null},
 "cobrado_con_evento": {"importe": "150.50", "actualizado_a": "2026-10-02"},
 "cobrado_sin_evento": {"importe": "37.60", "actualizado_a": "2026-10-02"},
 "lo_que_impide_cerrar": [{"pago_id": "…", "tipo": "PAGO_REGISTRADO", "estado": "PENDIENTE"}]}
```

El cierre recibe `{"caja", "cajero"?, "fecha"?, "declarado": {"EFECTIVO": "187.40", …}, "observacion"}` y contesta
**201** con `cierre_id`, `turno_id`, `caja`, `cajero`, `fecha`, `secuencia`, `registrado_en`, `usuario`, `observacion`,
`"estado_del_turno": "CERRADO"`, el `arqueo` (como el de en vivo, con `declarado`, `diferencia`, `total_declarado` y
`cuadra` llenos) y las dos mitades del cuadre. La reversión recibe `{"caja", "cajero"?, "fecha"?, "motivo",
"observacion"}` y contesta **201** con `reversion_id`, `turno_id`, `caja`, `cajero`, `fecha`, `secuencia`,
`cierre_revertido`, `motivo`, `registrado_en`, `usuario`, `observacion` y `"estado_del_turno": "ABIERTO"`. El 409 de
los pagos sin entregar lleva, además del `detail`, `"pagos_sin_entregar": [{"pago_id", "tipo", "estado"}]`.

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

`POST /api/caja/cobros` (`caja.cobro.CobroService` sobre `caja.cobro.Ventanilla`, de `CobrarOrdenes` de `caja`) hace todo en **una sola transacción**
de la base: el turno, el número de la serie, el recibo con sus líneas, las órdenes `PAGADA` con su recibo y el evento
`PAGO_REGISTRADO` en el buzón se confirman juntos o no queda nada. **Si la fila del buzón está, el recibo está.**

wasichai no abre transacciones (`RecordService`, ADR-0025 de wasichai), pero escribe por `DatabaseClient`, que se une a
la transacción en curso: `caja.comun.Transaccion` la abre con el `TransactionalOperator` de Spring, y el usuario que
llama sigue en el contexto, así que core aplica sus permisos dentro igual que fuera. `CobroEnUnaTransaccionApiTest` lo
demuestra: un `RecordChangeListener` de prueba revienta al crearse el `pago_evento`, cuando el turno, el recibo, la
línea y la orden PAGADA ya están escritos, y tras el 500 no queda ninguno y el número no avanza.

Antes de empezar: el cajero es el correo de la sesión (un `cajero` distinto en el cuerpo es **403**) y el día es hoy en
Lima (una `fecha_de_pago` distinta es **400**); se exige CREATE sobre `recibo` y UPDATE sobre `orden_de_cobro` (**403**
que dice cuál falta). **El reenvío de una `Idempotency-Key` ya emitida se contesta antes de la transacción**, con una
lectura por la clave (un recibo confirmado no cambia): antes de abrir o crear el turno y de mirar si la caja sigue
activa. Así un reenvío al día siguiente no abre un turno vacío, y uno posterior a dar de baja la caja devuelve el
recibo original. Luego, en este orden:

1. **El turno.** Candado `TURNO_CLAVE` con la clave `<clave_turno>`; se busca por `clave_turno` y, si no está, se crea con `abierto_en`
   según el reloj y la observación del cobro. El primer cobro del día abre el turno, una vez. Una caja inexistente es
   **404**; una de baja, **409**.
2. **El candado del turno**, `TURNO` con el id del turno: el que toman la anulación y el cierre, para que un cobro no se
   cuele en un cierre en curso.
3. **La idempotencia**, otra vez, bajo el candado: dos primeras peticiones con la misma clave se ordenan. Si ya hay un
   recibo con esa clave se devuelve ése, con el mismo `pago_id` y `emitido: false` (**200**). La clave de otro cajero o
   de otra caja es **409**; la de un recibo **ya anulado** también: **409** «el recibo de ese cobro está anulado», en vez
   de devolverlo como un cobro exitoso.
4. **El turno cerrado no cobra: 409 «Turno cerrado».** Se mira bajo el candado del turno, el mismo que toma el cierre:
   un cobro que esperaba a un cierre en curso lo encuentra cerrado. Para seguir cobrando ese día hay que reversarlo.
5. **Las órdenes.** Candado `ORDEN` con el id de cada una, **ordenadas por id**, y se leen después de tomarlos. Una que no
   existe es **404**; de dos sistemas de origen, **400** en `ordenes` (un recibo se anula entero); una ya pagada,
   anulada o todavía no exigible a la fecha de pago, **409** con su id. La misma orden dos veces en la petición es 400.
6. **El número.** Candado `SERIE` con la serie de la caja; el siguiente es `max(numero) + 1` de la serie y `numero_impreso`
   es `"%s-%07d"`. No deja huecos: si algo falla después, nada se confirma.
7. **El recibo y sus líneas**, una por orden con su concepto, detalle, referencia, sistema y monto (el importe de la
   orden). `total` es la suma exacta; `tipo_pago`, `NORMAL`; `actualizado_a`, la fecha de pago; el pagador, el de la
   primera orden.
8. **Las órdenes pasan a `PAGADA`** con su `recibo` (`Registros.replace`, bajo el candado de cada orden).
9. **El evento.** El `pago_evento` `PAGO_REGISTRADO`, `PENDIENTE`, con 0 intentos, `sistema_destino` el de las órdenes y
   el cuerpo de `rentas.json` congelado.

**Los candados** (`caja.comun.Candados`) son consultivos de transacción, en la forma de dos enteros
`pg_advisory_xact_lock(<clase>, hashtext(<clave>))`, con **una clase fija por tipo de candado** (`caja.comun.Candado`:
`TURNO_CLAVE`, `TURNO`, `ORDEN`, `SERIE` y `RECIBO`; la clave va sin prefijo, la clase la separa). wasichai no bloquea filas ni tiene unicidad compuesta. Se sueltan en el
commit o el rollback, nunca antes, y `Candados.bloquear` **falla fuera de una transacción** (en autocommit no protegería
nada). Se toman siempre en el mismo orden, **turno-clave → turno → órdenes por id → serie → recibo**, para que dos
operaciones no se esperen en cruz (la anulación toma el del turno de su recibo y luego los de sus órdenes; el cierre y
la reversión, solo el del turno; la reimpresión, solo el del recibo); cada decisión se toma con lo leído después de tomar su candado. Los `unique` de `clave_turno`,
`numero_impreso`, `clave_idempotencia` y `evento_id` son la red: si uno salta (`DuplicateKeyException`), la transacción
entera se revierte y el cobro contesta 409 («vuelva a intentarlo»), sin reintentar dentro (postgres no deja leer nada en
una transacción abortada); cualquier otra violación de integridad se propaga como lo que es (500). `hashtext` da 32
bits: dos claves **de la misma clase** pueden caer en el mismo candado, lo que solo ordena de más dentro de esa clase.
Con la forma de un entero, un choque entre una clave de turno y una de serie las habría vuelto el mismo candado, y dos
cobros podrían haberlo tomado en órdenes distintos y esperarse en cruz (40P01): por eso cada clase tiene su espacio.

`max(numero)` se lee como el usuario que llama: un rol con «solo sus registros» (`own_records_only`) no vería los
recibos ajenos y su cobro chocaría con el `unique` de `numero_impreso` (409, sin datos). Los roles de `roles.json` no lo
tienen.

### La caja de tasas

`POST /api/caja/cobros/tasas` (`caja.cobro.TasasService`, de `CobrarTasa` de `caja`) cobra derechos y tasas del TUPA.
**El precio sale de la tasa vigente a la fecha del cobro, nunca de la petición ni de una constante (regla 5).** Que
viniera de la petición sería dejar que el cliente ponga la tarifa; que estuviera compilada sería una tarifa que solo se
cambia desplegando, y esas son las que se acaban cobrando mal. **La tarifa es un dato**: la tabla `tasa`, con su
`documento_fuente` y su vigencia, que carga `import_tasas.py` desde la normativa; `src/main` no tiene ninguna cifra.

- **La tarifa vigente** (`tarifaVigente`) es la de `vigencia_desde ≤ fecha` y `vigencia_hasta` vacía o `≥ fecha`, ambos
  extremos incluidos. Si dos vigencias del mismo código se solaparan por error, rige la de `vigencia_desde` más reciente.
  Una vigencia que termina antes de empezar es un dato mal cargado: su cobro es **409**, la vista previa lo dice en
  `motivos` y cotiza los demás conceptos, y `GET /tasas` omite ese código con una línea WARN en el log. Nunca es un
  409 de toda la lista ni de toda la vista previa.
- **El monto de la línea** es `precio × cantidad`, en `BigDecimal` y sin redondear (`montoDeLinea`, la regla de
  `recibo_detalle_tasa_ck` de `caja`): `12.30 × 3 = 36.90`. La cantidad es al menos 1, y 1 si no viene.
- **El código** se lee recortado y en mayúsculas, en la petición y en `import_tasas.py`, como en `caja`.
- **Sin tarifa vigente** a la fecha, el concepto es **404** con su código; con la tarifa **en cero**, **409** («tarifa en
  cero»: es un dato mal cargado, y un recibo por cero no documenta un cobro). Sin conceptos, **400**.
- **El mismo mecanismo que la cobranza.** `caja.cobro.Ventanilla` es el acto común de los dos cobros, extraído de la
  cobranza: la caja, el turno, el candado del turno, la idempotencia, el número de la serie, el recibo y sus líneas, en
  la misma transacción y en el mismo orden. Las tasas no toman candados propios (las tarifas son configuración): entre
  el candado del turno y el de la serie leen las vigencias de cada código. **La numeración y el turno se comparten**
  con el cobro de órdenes de la misma caja: una orden y una tasa seguidas dan `…-0000001` y `…-0000002`.
- **El recibo** lleva `tipo_pago` `TASA`, el pagador de la petición (puede ser anónimo) y `actualizado_a`, la fecha a la
  que la tarifa estaba vigente; una `linea_recibo` por concepto con `tasa`, `concepto` (la descripción de la tasa),
  `cantidad`, `precio_unitario` y `monto`.
- **No hay evento**: una tasa no vino de una orden, no hay sistema de origen al que avisarle
  (`TipoDePago.produceEvento()` es falso para `TASA`). La respuesta dice `estado_del_pago: SIN_EVENTO`.
- **La `Idempotency-Key`** es la de un cobro de tasas de ese cajero en esa caja: la de un cobro de órdenes es **409**.

### La vista previa del total

La garantía de la UI es que **ningún total sale del cliente**, y el cajero necesita saber cuánto cobrar antes de emitir.
`POST /api/caja/cobros/vista-previa` y `POST /api/caja/cobros/tasas/vista-previa` aplican **las mismas funciones** que
su cobro (`impedimentosDelCobro`, `lineaDeOrden`, `cotizar`, `lineaDeTasa`, `totalDe`), sin candados ni escritura. Lo que
impediría el cobro (una orden que no existe, ya pagada o no exigible, dos sistemas; una tasa sin tarifa vigente o en
cero, una vigencia al revés) **no es un error**: va en `motivos`, `cobrable` es `false` y **queda fuera de las líneas y
del total**, en las dos vistas previas por igual: el total es el de lo que sí se puede cobrar. Una petición mal hecha (sin órdenes, un campo
desconocido, una fecha que no es hoy) sí es 400. `VistaPreviaApiTest` compara el total de la vista previa con el del
recibo emitido después, para las mismas órdenes y para las mismas tasas.

### El recibo después de emitido

**El recibo es inmutable.** Ni se edita ni se borra: no tiene `estado`, y anularlo es **agregar** una
`anulacion_recibo`; reimprimirlo, agregar una `reimpresion_recibo`. El número, las líneas y el total siguen donde
estaban, porque el pagador tiene ese papel en la mano. Nadie tiene `UPDATE` ni `DELETE` sobre esos objetos
(`test_apply_roles.py`) y `src/main` no tiene ningún `replace`, `update` ni `delete` sobre ellos
(`InmutabilidadDelReciboTest` lo vigila). El estado (`EMITIDO` o `ANULADO`) se deriva de que exista la anulación, y
los duplicados se cuentan.

#### La consulta de recibos

`GET /api/caja/recibos` (`caja.recibo.ConsultaDeRecibos`, de `ConsultaDeRecibos` de `caja`) es para quien perdió el
papel. Todos los filtros son opcionales y exactos: `documento` (el `pagador_documento`, en mayúsculas), `caja` (su
código; una caja que no existe no tiene recibos), `cajero` (el correo, tal cual), `desde` y `hasta` (**días de Lima**
sobre `emitido_en`, los dos incluidos: `hasta` llega hasta las 23:59:59 de Lima) y `estado` (`EMITIDO` o `ANULADO`, que
se resuelve con un `EXISTS` sobre la tabla de `anulacion_recibo`). Un rango al revés, una fecha mal escrita o un estado
desconocido son **400** (un filtro que no se entiende no es «todos»). Una búsqueda sin resultados es una página vacía.

El orden es `emitido_en` descendente con **desempate estable por id**: el `ORDER BY` de core es de una sola columna, así
que se leen los recibos entre el instante del último de la página y el del primero, se cuentan los más recientes, y la
página es el tramo que le toca en el orden (`emitido_en`, `id`). Dos recibos del mismo instante no se repiten ni se
pierden al pasar de página. Esas lecturas, y las de las anulaciones y reimpresiones de la página, van en **una
transacción de solo lectura en `REPEATABLE READ`** (`Transaccion.lectura`): ven una sola foto de la base, y un cobro que
se confirme entre una y otra no las descuadra.

#### El duplicado

`POST /api/caja/recibos/{numero}/duplicados` (`caja.recibo.DuplicadoDeRecibo`, de `DuplicadoDeRecibo` de `caja`) da el
PDF del recibo **marcado «DUPLICADO N.° n»**, en la cabecera y en el pie, con las mismas cifras que el original: nada se
recalcula. Si el recibo está anulado, el papel lo dice («RECIBO ANULADO — no acredita pago» y «Anulado el <fecha> —
<motivo>»). Cada duplicado registra su `reimpresion_recibo` bajo el candado `RECIBO` del recibo: dos a la vez no salen
con el mismo número. **Solo PDF.**

**El resumen.** Cada reimpresión guarda el SHA-256 de una **representación canónica del recibo y sus líneas** (claves en
orden fijo, cifras con `toPlainString`, el instante en ISO, las líneas en el orden del papel), no de los bytes del PDF,
que no son deterministas. Si ya hubo una reimpresión y el resumen de ahora no coincide, **409**: el recibo ya no se
dibuja igual, y entregarlo sería dar otro papel con el mismo número. No entra en el resumen lo que no está congelado en
el recibo: el nombre de la caja, que se edita en el admin, se imprime como está hoy.

#### La anulación

`POST /api/caja/recibos/{numero}/anulacion` (`caja.recibo.AnularRecibo`, de `AnularRecibo` de `caja`). Antes de
empezar: CREATE sobre `anulacion_recibo` (**403**) y la petición (**400**, todos los campos juntos: `motivo`
obligatorio, de hasta 80 y no en blanco; `autorizado_por` hasta 80; `documento_autorizacion` hasta 40; `observacion`
de 5 a 500; una clave desconocida). Luego, en **una transacción**:

1. **El candado del turno del recibo** (`TURNO`, el mismo de la cobranza) y el turno releído bajo él. Un recibo que no
   existe es **404**.
2. **Solo el mismo día**: la fecha del turno contra el que se cobró tiene que ser hoy en Lima. Si no, **422** «fuera del
   día de pago»: ese dinero ya cuadró en el arqueo de su día, y lo que corresponde es una devolución. Y **con el turno
   cerrado, 409 «Turno cerrado»**, bajo el mismo candado: su arqueo ya congeló el recibo como cobrado.
3. **Una sola vez**: si ya tiene su anulación, **409**. El `unique` de `recibo_anulado` es la red: dos anulaciones a la
   vez dan una y un 409, sin releer dentro.
4. **El recibo de otro cajero** exige el rol `SUPERVISOR_CAJA` (o ADMIN): **403** que lo nombra. Es el privilegio
   `ESPECIAL` de `caja`, y es **un hueco declarado**: wasichai no tiene acciones propias además de las CRUD, así que se
   comprueba por el nombre del rol y no por un permiso que se pueda dar en el admin.
5. **El acta**: la `anulacion_recibo` con la caja y el turno del recibo y `importe` igual a su total.
6. **Las órdenes del recibo vuelven a `PENDIENTE` y sin recibo**: candado `ORDEN` de cada una, por id, relectura y
   `replace`. No se marcan `ANULADA`: el dinero volvió y la deuda sigue, así que se pueden cobrar otra vez.
7. **El evento**: si el recibo es `NORMAL`, `PAGO_ANULADO` en el buzón, `PENDIENTE`, con el `sistema_destino` y el
   `pagoId` (`pagoOriginalId`) de su `PAGO_REGISTRADO`. Si no existe el `PAGO_REGISTRADO`, **409**: no se anula lo que
   no avisó. **Un recibo de tasas no avisa a nadie** (`pago_anulado_id` null).
8. **El recibo no se modifica.** Su original ya no se imprime (409, que remite al duplicado, que dice que está anulado).

El cuerpo de `PAGO_ANULADO` sigue `ComponedorDeEventosJson.pagoAnulado` de `caja`: `pagoId`, `tipo`, `pagoOriginalId`,
`recibo` (`numero`, `serie`, `fechaDePago`, `cajero`, `formaDePago`), `motivo`, `fecha` y `total` en cadena.

### El turno

El turno es la apertura de una caja por un cajero en un día: lo abre el primer cobro y es único por (caja, cajero,
fecha). **No tiene estado**: está `ABIERTO` o `CERRADO` según el **último** de sus movimientos por `secuencia` (un
`cierre_turno` lo cierra, una `reversion_cierre` lo reabre; sin movimientos, abierto), y de ahí sale el cierre vigente.
Las reglas son **funciones puras** en `caja.turno` (`ArqueoDelTurno` y `CierreDeTurno`): sin Spring, sin reloj y sin
base, la fecha entra como argumento (regla 6). `PurezaDelTurnoTest` lee sus fuentes y falla si importan Spring, el reloj,
la base, un `suspend`, un `Double` o un redondeo. `caja.turno.LibroDelTurno` lee lo que la base sabe de un turno (su
historia, sus recibos con lo que devolvió su anulación y sus pagos sin entregar) como el usuario que llama.

#### El turno del día

`GET /api/caja/turnos/del-dia` (`TurnoController` y `ConsultaDelTurno` de `caja`, #97) da los turnos de **hoy en Lima**
de **quien pregunta**, en todas sus ventanillas, por código de caja, con su caja, su hora de apertura y su estado. El
cajero sale de la sesión y el día del reloj: **cualquier parámetro es 400** que lo nombra (un `?cajero=` lo convertiría
en «el turno de quien yo diga»). La `situacion` distingue `SIN_ABRIR` (ningún turno: es un dato, no un error),
`ABIERTO` (exactamente uno), `CERRADO` (todos cerrados: al que cerró no le falta abrir, le falta reversar) y
`VARIOS_ABIERTOS` (abierto en dos ventanillas: no se elige uno). **Preguntar no abre un turno.**

#### El arqueo

`ArqueoDelTurno.de(recibos, declarado, fecha)` recibe los recibos del turno (`numero`, `tipo_pago`, `forma_pago`,
`total` y lo que devolvió su anulación, el importe congelado del acta), lo declarado por forma de pago (lo que falta
cuenta como cero) y la fecha. Da **una línea por forma de pago en el orden del enumerado**, sin las vacías salvo que
tengan declarado (veinte soles en cheque declarados que el sistema no registró son un descuadre que hay que ver), con
`neto = cobrado − anulado` y `diferencia = declarado − neto`, que puede ser negativa; y `recibos_emitidos`,
`recibos_anulados`, los totales (la suma de las líneas, nunca una cifra aparte) y `cuadra()`. Lo imposible lanza: lo
anulado mayor que lo cobrado, un declarado negativo, una forma de pago que no existe.

**Sin redondeo, y no es un olvido (D-03d sigue abierta).** No hay ninguna división: cada recibo tiene una sola forma de
pago y su total va entero a una línea, así que la suma de las partes es el total exacto. Todo es `BigDecimal`, sin
`setScale` ni `RoundingMode`.

**El cuadre** parte el neto en `cobrado_con_evento` (los recibos `NORMAL`, que avisan a su sistema de origen) y
`cobrado_sin_evento` (las tasas), cada recibo con su neto. **Las dos mitades suman el neto**, y el cierre lo exige.

`GET /api/caja/turnos/{turno_id}/arqueo` (`EstadoDelCierreController` de `caja`) es el arqueo **en vivo**, a hoy, sin
lo declarado: `declarado`, `diferencia`, `total_declarado` y `cuadra` van en `null`, nunca en cero. Lleva las dos
mitades, `estado_del_turno`, `puede_cerrar` (abierto y sin pagos que lo impidan) y `lo_que_impide_cerrar`: cada
`pago_evento` del turno `PENDIENTE` o `MUERTO`, con su `pago_id`, su `tipo` y su `estado`. Lee en una sola foto
(`Transaccion.lectura`).

#### El cierre

`POST /api/caja/turnos/cierre` (`caja.turno.CerrarTurno`, de `CerrarTurno` de `caja`). Antes de empezar: CREATE sobre
`cierre_turno` y `cierre_turno_linea` (**403**); el cajero es el de la sesión (otro en el cuerpo es **403**: nadie cierra
el turno de otro, tampoco un supervisor); la petición (**400**, todos los campos juntos): `caja`, `fecha` (hoy **o un
día pasado**: el turno que se quedó abierto ayer tiene que poder cerrarse; mañana es 400), `declarado` (cada cifra en
cadena, un decimal sin signo de hasta 2 decimales y 13 enteros, por una forma de pago conocida: si no, 400 en
`declarado` que dice cuál), `observacion` y una clave desconocida. Luego, en **una transacción**:

1. **El turno de la caja, del cajero de la sesión y de esa fecha** (por `clave_turno`): **404** si no hay, o si la caja
   no existe. Una caja de baja también cierra.
2. **El candado del turno** (`TURNO`), y todo lo que sigue se lee después de tomarlo.
3. **Ya cerrado: 409** («ya está cerrado»): dos arqueos vigentes sobre el mismo dinero.
4. **Un pago `PENDIENTE` o `MUERTO` en el turno: 409 «hay pagos sin entregar»**, con la lista en el `detail` y en
   `pagos_sin_entregar`. Un turno cerrado con uno de ellos dejaría el acta firmada, el cajón cuadrado y la deuda viva.
5. **El arqueo** con lo declarado, a la fecha del turno, y **el cuadre, que tiene que sumar el neto** (si no, es un
   defecto: 500).
6. **El acta**: el `cierre_turno` con sus cifras congeladas y `secuencia` = movimientos + 1, y una `cierre_turno_linea`
   por forma de pago.
7. **201** con el acta, su arqueo declarado y `estado_del_turno: CERRADO`.

**El descuadre se guarda, no se rechaza**: si lo declarado no coincide con el neto, el cierre se firma igual, con su
diferencia. Si el cierre exigiera cero, al cajero al que le faltan diez soles le bastaría declarar lo que dice el sistema.

#### La reversión

`POST /api/caja/turnos/reversion` exige CREATE sobre `reversion_cierre` (el privilegio `ELIMINACION` de `cierre_caja`:
`SUPERVISOR_CAJA`), el cajero de la sesión (la reversión del cierre de otro es **403**, igual que en `caja`), `motivo`
(obligatorio, no en blanco, hasta 80) y `observacion`. Con el candado del turno, exige un cierre vigente (si no, **409**
«Nada que reversar») y agrega la `reversion_cierre` con `cierre_revertido` y la secuencia siguiente: **el turno queda
abierto** y se sigue cobrando en él. El cierre reversado no se toca; el cierre siguiente vuelve a congelar sus totales,
que ya incluyen lo cobrado después. Es la única forma de volver a cobrar ese día.

**Solo se agregan filas (regla 4).** Nadie tiene UPDATE ni DELETE sobre `cierre_turno`, `cierre_turno_linea` ni
`reversion_cierre` (`test_apply_roles.py`), `src/main` no tiene ningún `replace`, `update` ni `delete` sobre ellos
(`InmutabilidadDelReciboTest`) y ninguna ruta de caja los modifica. Los `unique` de `clave_secuencia`, de la `clave` de
cada línea y de `cierre_revertido` son la red: un cierre se reversa una sola vez.

#### El candado del turno

**Nada se cuela en un cierre en curso.** El cierre y la reversión toman **solo** el candado `TURNO` del turno, el mismo
que toman el cobro de órdenes, el de tasas y la anulación, y releen todo después de tomarlo. Mientras el cierre lo
tenga, un cobro o una anulación de ese turno **esperan**, y al soltarlo encuentran el turno cerrado: **409 «Turno
cerrado»**, comprobado bajo el candado (en `Ventanilla` después de la idempotencia, en `AnularRecibo` después del mismo
día). La caja vecina es otro turno y no espera. El orden de los candados no cambia: **turno-clave → turno → órdenes por
id → serie → recibo → pago** (el del pago lo toma solo la explicación de un pago sin entregar), y el cierre, que solo toma el del turno, no puede esperar en cruz con nadie. La secuencia es común
al cierre y a la reversión: wasichai no tiene unicidad compuesta entre objetos, así que **la serializa el candado del
turno**; dos cierres a la vez dan uno, y el segundo encuentra el turno cerrado.

El **original** del recibo (`GET …/pdf`) también exige el turno abierto: con el turno cerrado es 409 y remite al
duplicado.

### El evento `PAGO_REGISTRADO`

El cuerpo sigue `docs/50-api/contratos-que-consume/rentas.json` de `caja`: `pagoId`, `tipo`, `sistemaOrigen`, `total`,
`actualizadoA`, `recibo` (`numero`, `serie`, `fechaDePago`, `cajero`, `formaDePago`), `pagador` (`documento`, `nombre`,
`idExterno`) y `ordenes[]` (`ordenId`, `referenciaExterna`, `importe`, `actualizadoA`), con los importes en cadena. No
lleva imputación: el origen decide qué extingue.

**Cambio del contrato:** `ordenes[].ordenId` es ahora el UUID de la orden **en cadena** (en `caja` era un entero, el id
de su tabla). `rentas` tiene que leerlo como texto.

### El buzón de salida

`caja.buzon.PublicadorDelBuzon` (de `PublicadorDelBuzon`, `EntregarEventos`, `AnotarLaEntrega` y
`ClienteHttpDelSistemaDeOrigen` de `caja`) entrega cada `pago_evento` `PENDIENTE` (`PAGO_REGISTRADO` y `PAGO_ANULADO`)
al sistema de origen de sus órdenes. **La ventanilla nunca le pregunta nada a nadie**: con el sistema de origen apagado
se cobra igual, el pago queda `PENDIENTE` (`estado_del_pago: EN_TRANSITO`, con su hora) y sale cuando el destino vuelve.

#### La configuración

En `application.yml`, comentada, y en `develop/example.env`. **El destino es configurable y está apagado por defecto**:
con el buzón apagado no arranca ningún bucle, los pagos quedan `PENDIENTE` y el turno que los tiene no cierra.

| Propiedad | Valor por defecto |
|---|---|
| `caja.buzon.habilitado` | `${CAJA_BUZON_HABILITADO:false}` |
| `caja.buzon.intervalo` | `PT10S`: la espera entre el final de una vuelta y la siguiente |
| `caja.buzon.por-vuelta` | `50`: cuántos eventos `PENDIENTE` lee por organización en cada vuelta |
| `caja.buzon.intentos` | `8`: con cuántos intentos fallidos un pago que no contesta muere |
| `caja.buzon.timeout` | `10s`: la espera de la conexión y de la respuesta |
| `caja.buzon.destinos.<sistema>.url` | sin valor: la raíz del sistema de origen; se llama a `POST {url}/pagos` |
| `caja.buzon.destinos.<sistema>.token` | opcional: va como `Authorization: Bearer`, nunca en el cuerpo |
| `caja.conciliacion.responsable` y `.canal` | **obligatorios con el buzón habilitado**: si faltan, el arranque falla nombrándolos |

`<sistema>` es el `sistema_origen` de las órdenes (`rentas`, `mercados`…): un sistema nuevo es una línea de
configuración, no un despliegue. Por variables: `CAJA_BUZON_DESTINOS_RENTAS_URL` y `CAJA_BUZON_DESTINOS_RENTAS_TOKEN`.

#### Una vuelta

`caja.buzon.BucleDelBuzon` es un `SmartLifecycle` que solo arranca con `habilitado`: espera el intervalo, da una vuelta y
vuelve a esperar. En cada vuelta:

1. Intenta **`CerrojoBuzon`**, un `pg_try_advisory_lock` **de sesión** sobre una conexión propia (el `CerrojoEmision` de
   `srtm`). Si otro lo tiene, la vuelta no hace nada: **un solo publicador por base, no uno por réplica**. Una instancia
   que muere lo suelta con su sesión.
2. Con el cerrojo, recorre cada organización y lee hasta `por-vuelta` eventos `PENDIENTE`, por orden de creación
   (`BuzonStore`).
3. Cada evento se comprueba contra su recibo (la defensa (a), abajo). Si coincide, se entrega **fuera de cualquier
   transacción**: `POST {url}/pagos` con el `cuerpo` **congelado**, tal cual se escribió al cobrar.
   `ClienteDelSistemaDeOrigen` comprueba que no hay una transacción abierta antes de llamar, y falla si la hay.
4. Cada marca va **en su propia transacción y es condicional**: `WHERE estado = 'PENDIENTE' AND intentos = :leidos`.
   Si dos publicadores llegaran a coincidir, se cuenta un solo intento. Cada marca se audita con `AuditService` y
   usuario `null` (la escribió el sistema).

| Respuesta | Qué es | Qué queda |
|---|---|---|
| 200, 201, 202 o **409** (el receptor ya lo tenía: deduplicó por `pagoId`) | Entregado | `ENTREGADO`, `entregado_en`, `intentos + 1` y `ultimo_error` vacío |
| 401 o 403 | No contesta, con un diagnóstico de credencial: sin token, token que no vale o caducó, o falta un permiso en el destino | `intentos + 1` y `ultimo_error`; se reintenta |
| Otro 4xx | Rechazado: el motivo no va a cambiar solo | **`MUERTO` ya**, con `intentos + 1` |
| 5xx, error de E/S, tiempo agotado o **sin URL configurada** | No contesta | `intentos + 1` y `ultimo_error`; `MUERTO` cuando `intentos + 1 ≥ caja.buzon.intentos` |

`ultimo_error` se recorta a 400 caracteres, con el remedio delante y el corte a la vista (`…`). Lo que contestó el
destino viaja en él **tachado**: el token configurado y todo lo que parece una credencial (`Authorization`, `Bearer …`,
`token=`…) se reemplazan por `«…»`, porque un proxy puede devolver el eco de la petición.

Una organización cuyo buzón revienta no tumba a las demás: se registra y la vuelta sigue. Lo ya marcado queda marcado,
y lo que no se marcó sigue `PENDIENTE`; si el destino ya lo tenía, lo recibe otra vez con el mismo `pagoId` y lo
deduplica.

#### La alerta

Cuando un pago muere, la vuelta escribe **una línea ERROR que empieza con `DINERO COBRADO SIN REGISTRAR`**, que nombra al
responsable de la conciliación y su canal y lista cada pago (su `pagoId`, tipo, destino, número de recibo, turno,
intentos y último error). Es dinero que entró por ventanilla y que su sistema de origen no sabe que entró. **Es un
registro, no un correo**: llega a una persona si la observabilidad del despliegue alerta sobre las líneas ERROR, y
aquí nada comprueba que llegue (el mismo hueco declarado que `AlertaEnElRegistro` de `caja`).

#### Los pagos sin entregar

- **`GET /api/caja/pagos/sin-entregar`** da los `MUERTO`, con `pago_id`, `tipo`, `destino`, `recibo` (el número
  impreso), `turno_id`, `estado`, `intentos`, `ultimo_error`, `creado_en`, `entregado_en` y `explicacion`. Exige READ
  sobre `pago_evento` (y sobre `recibo`, por el número).
- **`POST /api/caja/pagos/{pago_id}/explicacion`** con `{explicacion, observacion}` (`ExplicarPagoSinEntregar` de
  `caja`) pasa un `MUERTO` a `EXPLICADO`. **Un turno con un pago `MUERTO` no cierra, y uno `EXPLICADO` sí**: es la única
  salida de un pago que de verdad no se puede entregar, y cuesta lo que tiene que costar:
  1. Exige UPDATE sobre `pago_evento`, que solo tiene `SUPERVISOR_CAJA` (403 antes de empezar).
  2. `explicacion`, de al menos 5 caracteres (400 en `explicacion`), y `observacion`, de 5 a 500 (regla 10). Una clave
     desconocida es 400.
  3. En una transacción, toma el candado del evento (`Candado.PAGO`) y lo **relee**: si no está `MUERTO`, **409** (uno
     `PENDIENTE` se entregaría solo, y explicarlo lo sacaría de la cola).
  4. Lo escribe por `RecordService`, **como el usuario**: `estado` y `explicacion`, con la auditoría de core. La
     `observacion` va a otra fila de auditoría del mismo acto: `pago_evento` no tiene ese campo.

#### La segunda puerta: un evento inventado

La API genérica de wasichai (`POST /api/objects/pago_evento/records`) aplica los permisos de objeto de core y nada más:
un `CAJERO`, que tiene CREATE sobre `pago_evento` para cobrar, puede escribir por ella un evento que nunca ocurrió (el
hallazgo de la revisión del PR 4b; **wasichai#15**). Hay dos defensas, y ninguna lo impide del todo:

- **(a) Coherencia antes de enviar.** El publicador comprueba que el evento coincide con su recibo, leído en la base:
  el recibo existe; el tipo del cuerpo es el de la fila; un `PAGO_REGISTRADO` es de un recibo `NORMAL`; el `total` del
  cuerpo es el del recibo; las `ordenes` del cuerpo son las de las líneas del recibo; un `PAGO_ANULADO` tiene su
  `anulacion_recibo`. Si algo no cuadra, **no se envía**: pasa a `MUERTO` con `ultimo_error` «el evento no coincide con
  su recibo: …», y salta la alerta. **Su límite**: la copia exacta de un evento legítimo con otro `pagoId` cuadra con su
  recibo y se envía; el receptor la tomaría por otro pago.
- **(b) El detector.** `caja.comun.GuardiaDeEscrituras`, un `RecordChangeListener`, escribe una línea ERROR
  (`ESCRITURA FUERA DE CAJA: …`, con el objeto, el id y el usuario) por toda creación, cambio o borrado que se haga
  **fuera de la API de caja** sobre `recibo`, `linea_recibo`, `pago_evento`, `anulacion_recibo`, `reimpresion_recibo`,
  `turno`, `cierre_turno`, `cierre_turno_linea` y `reversion_cierre` (un cierre forjado cerraría un turno ajeno), y por
  los cambios de `orden_de_cobro`. Las escrituras de caja llevan la marca `EscrituraDeCaja`, un elemento del contexto de
  la corrutina que `Registros` pone alrededor de cada `create`, `replace` y `delete`. **Su límite: no puede vetar**,
  porque wasichai llama a los listeners después de escribir: lo forjado queda escrito, y la línea es lo que permite
  verlo. Impedirlo es wasichai#15.

#### Los huecos de wasichai que se rodean aquí

- **No hay programación de tareas ni ayuda de candados** (**wasichai#18**). El bucle es propio (un `SmartLifecycle` con
  `delay`, como `AutomationDrain` de wasichai) y `CerrojoBuzon` es un candado de sesión de postgres.
- **El trabajo de fondo no tiene principal para `RecordService`** (**wasichai#18**): `CurrentUser` exige un usuario. El
  publicador lee y marca `pago_evento` (y lee el recibo, sus líneas y su anulación) con `DatabaseClient` sobre la tabla
  física resuelta en `custom_objects`, como `EmisionMasivaService` de `srtm`, y audita con `AuditService` y usuario
  `null`. **Todo eso vive en una sola clase, `BuzonStore`**: es el único acceso a tablas físicas de caja (aparte de
  `Candados` y `CerrojoBuzon`, que solo toman candados). Lo que escribe no pasa por los `RecordChangeListener`.
- **La API genérica es una segunda puerta** (**wasichai#15**): las dos defensas de arriba.

## Roles

`model/roles.json` declara los roles de caja y, por rol, las acciones (`READ`, `CREATE`, `UPDATE` o `DELETE`) sobre
cada objeto del modelo. Los PR siguientes lo amplían con sus objetos.

| Rol               | Puede                                                    |
| ----------------- | -------------------------------------------------------- |
| `SISTEMA_ORIGEN`  | READ y CREATE sobre `orden_de_cobro`: da de alta órdenes |
| `CAJERO`          | READ sobre `area`, `caja` y `tasa`; READ y UPDATE sobre `orden_de_cobro`; READ y CREATE sobre `turno`, `recibo`, `linea_recibo` y `pago_evento`: cobra. READ sobre `anulacion_recibo` y `reimpresion_recibo`: no anula ni reimprime. READ y CREATE sobre `cierre_turno` y `cierre_turno_linea`: cierra su turno. READ sobre `reversion_cierre`: no reversa |
| `SUPERVISOR_CAJA` | lo mismo que `CAJERO`, y además CREATE sobre `anulacion_recibo` (anula, también el recibo de otro cajero), sobre `reimpresion_recibo` (reimprime) y sobre `reversion_cierre` (reversa el cierre de su propio turno), y **UPDATE sobre `pago_evento`** (explica un pago sin entregar) |
| `TESORERIA`       | READ sobre cada objeto del modelo                        |

**Nadie tiene UPDATE ni DELETE sobre `recibo`, `linea_recibo`, `pago_evento`, `anulacion_recibo`,
`reimpresion_recibo`, `cierre_turno`, `cierre_turno_linea` ni `reversion_cierre`** (`test_apply_roles.py` lo comprueba):
un recibo no se corrige, su anulación se agrega; un cierre no se corrige, se reversa. **La única excepción es UPDATE
sobre `pago_evento` para `SUPERVISOR_CAJA`**: explicar un pago `MUERTO` (lo fija `test_apply_roles.py`). El publicador
marca la entrega sin pasar por los roles (`BuzonStore`). Los privilegios de `caja` se
vuelven permisos CRUD de wasichai: anular (`ELIMINACION`) es CREATE sobre `anulacion_recibo`; reimprimir (`IMPRESION`),
CREATE sobre `reimpresion_recibo`; cerrar (`REGISTRO` de `cierre_caja`), CREATE sobre `cierre_turno`, y reversar
(`ELIMINACION` de `cierre_caja`), CREATE sobre `reversion_cierre`, así que la UI los lee de `/api/auth/me/permissions`.
Cobrar y anular leen además la historia del turno: un rol propio que cobre necesita READ sobre `cierre_turno` y
`reversion_cierre`.
`ESPECIAL` (anular el recibo de otro cajero) no cabe en CRUD: es el rol `SUPERVISOR_CAJA` (ver «La anulación»).

`model/apply_roles.py` los crea o sincroniza por la API de core (`POST /api/roles` y `PUT /api/roles/{name}/permissions`).
Es idempotente: un rol que falta se crea, uno que existe queda con los permisos de `roles.json` (**un permiso dado a mano
en el admin se pierde**) y con su etiqueta; la segunda corrida no escribe nada. Valida `roles.json` contra `model.json`
antes de llamar a core y lleva `--dry-run` (no llama a core), `--core`, `--email` y `--password`. `ADMIN` no se declara:
core lo deja pasar todo. Los usuarios y sus roles se asignan en el admin de core.

## Emisión del recibo

`GET /api/caja/recibos/{numero_impreso}/pdf` da **el original**: solo al cajero que lo emitió, el mismo día, con su
turno abierto (con el turno cerrado, 409) y mientras no esté anulado. Cualquier
otro recibe 409, que remite al duplicado (`POST .../duplicados`), que dice «DUPLICADO N.° n» y, si se anuló, que está
anulado. Lo dibuja `caja.emision.PdfRenderer`, copiado de `srtm-backend` sin su cabecera
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
  `PAGO_REGISTRADO` y lo que llega en la petición); `TasasTest`, las de la caja de tasas (la vigencia con sus extremos,
  la tarifa vigente, `12.30 × 3 = 36.90` y `0.10 × 7 = 0.70`, la vigencia al revés, la cantidad y el precio en la
  petición); `PdfRendererTest` y `ReciboPdfTest`, el PDF (el original y el duplicado, con y sin anulación);
  `RecibosTest`, las del recibo después de emitido (el resumen estable que cambia con una cifra, los largos de motivo,
  autorizado y memorando, el número del papel, los filtros, el mismo día, el recibo ajeno y el cuerpo de
  `PAGO_ANULADO`); `InmutabilidadDelReciboTest` recorre `src/main` y falla si aparece un `replace`, `update` o `delete`
  sobre el recibo, sus líneas, su anulación, sus reimpresiones, su evento, el cierre, sus líneas o su reversión, salvo
  la explicación de un pago sin entregar. `EntregaTest` fija las reglas del publicador: la clasificación de cada
  respuesta, el recorte de `ultimo_error`, la marca de cada intento y la coherencia del evento con su recibo;
  `ResponsableDeLaConciliacionTest`, que con el buzón encendido el arranque falla sin responsable ni canal.
  `ArqueoDelTurnoTest` es el de `caja` portado (la suma, la diferencia, lo imposible, el cuadre y el estado);
  `CierreDeTurnoTest`, la máquina de estados del turno y la situación del cajero; `TurnosTest`, lo que llega en las
  peticiones del turno; `PurezaDelTurnoTest`, que el arqueo y el cierre no dependen de Spring, del reloj ni de la base.
- **Integración de la API**: `CajaApiTest` es su base (aplica `model.json` y `roles.json`, da usuarios con un rol y
  comprueba los 400 por campo). `OrdenesApiTest` cubre el alta (201, 200, diez simultáneas, los 400, el 403 de un
  `CAJERO`) y la lista por pagador; `CajasApiTest`, el catálogo con y sin área. `CobroApiTest` cubre la cobranza: el
  caso feliz, el doble cobro, la idempotencia, los 400, 403, 404 y 409, **diez cobros simultáneos de la misma orden**
  (un recibo y nueve 409) y **veinte simultáneos en la misma caja** (del 1 al 20, sin huecos ni repetidos), los
  permisos y el PDF, y **dos cobros simultáneos con la misma `Idempotency-Key`** (un solo recibo).
  `CobroEnUnaTransaccionApiTest` es la prueba de la transacción, y `CandadosTest` la del candado (y de que la misma
  clave en dos clases son dos candados). `TasasApiTest` cubre la caja de tasas (el precio de la tabla con dos
  vigencias, el 400 de un precio en el cuerpo, 404 sin tarifa vigente, 409 con tarifa en cero, la idempotencia, el
  pagador anónimo, los 400, la numeración compartida con el cobro de órdenes, ningún `pago_evento`, el 403 de
  `TESORERIA`) y la lista de tasas vigentes; `VistaPreviaApiTest`, las dos vistas previas, sus motivos y sus permisos,
  y que su total es el del recibo emitido después. `ReciboApiTest` cubre la consulta (página vacía, los seis filtros, el
  rango de días de Lima con el `hasta` entero, el estado derivado, los duplicados contados, el desempate estable, el 403
  sin READ y los 400) y el duplicado (marcado y registrado, dos registros, el de un anulado, el 409 si ya no se dibuja
  igual, el 403 de un `CAJERO`); `AnulacionApiTest`, la anulación (la orden vuelve a `PENDIENTE` y el recibo sigue
  igual, `PAGO_ANULADO` con su `pagoOriginalId`, el recibo de ayer con un `Clock` de prueba, dos veces, **diez
  simultáneas dan una**, el recibo de tasas, sin evento, los 400, el recibo ajeno y el 403 del `CAJERO`).
  `TurnoApiTest` cubre el turno del día (entero y con su hora, sin turno, cerrado, dos abiertos, sin parámetros, y que
  preguntar no abre), el arqueo en vivo sin declarado, el cajero y el día del cierre (el turno de otro, el de ayer,
  mañana, reversar sin `SUPERVISOR_CAJA`) y el reenvío (al día siguiente no abre un turno; tras la baja de la caja o el
  cierre devuelve el recibo), con un `Clock` de prueba movible. `CierreApiTest` cubre **el día completo**, que cuadra
  céntimo a céntimo con órdenes, tasas y una anulación; el turno cerrado (no se cobra, no se anula, reversar reabre); el
  pago `PENDIENTE` que impide cerrar; la inmutabilidad; **dos cierres simultáneos dan uno**, y **el cierre en curso**: un
  `RecordChangeListener` de prueba retiene el cierre con el candado tomado, y un cobro de tasa, uno de orden y una
  anulación esperan y reciben 409, mientras la caja vecina cobra sin esperar. `CierreEnUnaTransaccionApiTest` revienta
  el cierre a mitad (al escribir una línea) y no queda nada; `OriginalEnUnaFotoApiTest` cuela un cierre y su reversión
  entre las dos lecturas de la historia del turno y el original no se rompe. `BuzonApiTest` cubre el buzón contra un
  sistema de origen falso por HTTP (MockWebServer): el pago `EN_TRANSITO` con su hora, el cuerpo con la referencia y sin
  imputación, el reintento sin perder el pago, la muerte con su alerta, el 401 que sigue vivo y el 422 que muere, el
  token que no viaja en el cuerpo ni en `ultimo_error`, **dos publicadores que cuentan un solo intento**, **la llamada
  fuera de toda transacción** (mirando desde otra conexión mientras el destino contesta), **el evento inventado por la
  API genérica** (no se envía, muere y salta el detector), la explicación que solo vale con un `MUERTO` y
  **`elPagoMuertoSeExplicaYEntoncesCierra`**. `BucleDelBuzonApiTest` deja correr el bucle; `BuzonApagadoApiTest`
  comprueba que apagado no arranca; `GuardiaDeEscriturasApiTest`, que el detector ve un cierre forjado y no ve lo que
  escribe caja.
- **Integración** (`@Tag("integration")`): `CajaSmokeTest` levanta la app entera (`CajaApplication`) y la llama por HTTP.
  Comprueba que la salud responde `UP`, que los módulos instalados (views, forms, pages) responden y los que se dejan
  fuera (workflow, documents, gis, automatización) dan 404, que una ruta bajo `/api/caja/**` sin token da 401 y que la
  forma del modelo que usará caja funciona de punta a punta: ENUM, TEXT único, DECIMAL y una relación MANY_TO_ONE
  obligatoria.
- Con un Docker remoto no corren en local tal cual (Testcontainers no llega a sus puertos): ver
  [docs/develop/README.md](docs/develop/README.md#6-tests).

## Siguientes pasos (fuera de este alcance)

- **Negocio:** la conciliación y la recaudación.
