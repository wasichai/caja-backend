# Órdenes que cambian en el origen: plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que caja no cobre una deuda que el sistema de origen ya reemplazó, y que el pago de una deuda reemplazada se cuente como pago «a cuenta» en vez de dejar un pago MUERTO.

**Architecture:** cuatro cambios en `caja.cobro`, `caja.buzon` y `caja.recaudacion`, uno por issue y uno por PR:
1. el retiro de una orden (#39);
2. el alta que choca y la sustitución atómica (#40);
3. el pago «a cuenta» en el buzón y la conciliación (#41);
4. la comprobación opcional en línea antes de cobrar (#42).

El modelo crece en `model/model.json` (sin Flyway). Las escrituras de caja van con `EscrituraDeCaja`, y los candados se toman con `Candados.bloquear` dentro de `Transaccion.en`.

**Tech Stack:** Kotlin, Spring Boot 4 (WebFlux, corrutinas), wasichai 0.6.0 (`RecordService` a través de `Registros`), JUnit 5, Testcontainers (`postgres:18`), MockWebServer (`SistemaDeOrigenFalso`).

**Spec:**
- caja-backend #39, #40, #41 y #42;
- su contraparte en srtm-backend: #163 (alta), #164 (retiro y sustitución), #165 (pagos) y #166 (conciliación).

## Validación contra srtm (2026-10-22)

Revisé srtm-backend en main `b940b50`. Esto cambia o precisa lo que piden los issues:

| Hecho en srtm | Consecuencia para caja |
|---|---|
| Las cuotas de arbitrios son inmutables (`cuota_arbitrio` es `appendOnly`). Cada redeterminación escribe una versión nueva con otra `clave` (`predio\|servicio\|anio\|periodo\|secuencia\|version`), aunque el monto no cambie: a veces cambia la zona, el parámetro o el contribuyente (`Redeterminacion.kt:85-110`). | La `referencia_externa` de una versión nueva siempre es nueva. El 409 por importe distinto (#40) protege contra un error del origen, pero no es el camino normal: el normal es la **sustitución** (`sustituye_a`), aunque el importe sea el mismo. |
| El predial se liquida **por contribuyente** y al vuelo, sin cuotas guardadas (`ImpuestoPredial.kt:76-133`). Cambia con cualquier DJ del contribuyente o de otro condómino. | Sin un registro versionado en srtm, la misma referencia volvería con otro importe y caería en el 409. Eso queda en srtm-backend #163 (corregido). Para caja confirma que el 409 es lo correcto. |
| srtm no tiene intereses ni moratorios: el importe no cambia con la fecha dentro de una versión. | El 409 compara el importe exacto (`compareTo`). `actualizado_a` no entra en la comparación (Review Focus 3). |
| Una cuota de arbitrios puede valer 0 y, sin `REDONDEO_ARBITRIO`, tener más de 2 decimales (`Calculo.kt:89`). | El alta ya rechaza el cero y los más de 2 decimales con 400 (`Importe.kt:29-36`). srtm no manda ceros y redondea a 2 (#163). Caja no cambia. |
| Los id de srtm son UUID y el `codigo` del contribuyente puede estar vacío. | `pagador_externo_id` (INTEGER) queda vacío para `rentas`. El 409 compara solo `pagador_documento`, no el nombre ni el id externo. |
| El origen no tiene pagos: un 409 de retiro («ya PAGADA») le llega a srtm antes que el `PAGO_REGISTRADO` del buzón. | El 409 del retiro dice el recibo en `detail`. srtm no lo parsea: la imputación llega por el buzón (#165). |
| La redeterminación, la anulación manual y la anulación de la DJ retiran cuotas sin reemplazo (`Redeterminacion.kt:8-12`). | El retiro (#39) es independiente de la sustitución (#40). |
| `POST /pagos` y la conciliación de srtm todavía no existen. El `rentas` viejo (hneyra/caja) responde 201, 409 o 503. | El 202 «a cuenta» (#41) es contrato nuevo, y lo fija este plan: `{aplicado:false, a_cuenta:true, motivo}`. |

**Decisiones que se apartan del texto de los issues:**
1. **#39, el cobro de una orden ANULADA:** responde **422**, una clase nueva `OrdenRetirada`. La PAGADA y la no exigible siguen en 409.
   - La **vista previa** sigue respondiendo 200 con `cobrable:false` y el motivo, como documenta el README (742-751, «no es un error»). La UI distingue el caso por `estado` al volver a leer (caja-ui #27).
2. **#39, quién escribe el retiro:** caja lo escribe **como quien llama**, por `Registros` y con `EscrituraDeCaja`, después de comprobar que el sistema de la cuenta de servicio es el de la orden. Por eso `SISTEMA_ORIGEN` tiene UPDATE sobre `orden_de_cobro` (`roles.json`).
   - El plan decía escribir como plataforma, como `BuzonStore.marcar`. No se puede: `asPlatform` de wasichai rechaza una escritura dentro de una petición, y el buzón escribe fuera de ellas.
   - El UPDATE no abre otra puerta: `orden_de_cobro` es `apiOnly`, y `GuardiaDeEscrituras` rechaza cualquier escritura sin la marca `EscrituraDeCaja`, así que la API genérica no cambia el importe. La sustitución (#40) escribe `sustituida_por` de la misma forma.
3. **#40, `sustituye_a`:** se guarda como texto, la `referencia_externa` de la orden sustituida del mismo sistema, no como relación. Es lo que la UI muestra («reemplaza a …»), y no hace falta navegar.
4. **#41, el cuerpo del 202:** la entrega sigue decidiéndose solo por el código (`Entrega.kt:121-124`, «nunca el cuerpo»). Un 202 siempre es ENTREGADO. El cuerpo solo **anota** `a_cuenta` y el motivo, saneado con `tachar`/`recortar`. Un cuerpo ilegible es un ENTREGADO sin anotar.
5. **#42, la configuración:** va en `caja.buzon.destinos.<sistema>.verificar` y `.sin-respuesta`, el mismo destino del buzón, en vez de un `caja.origenes.*` nuevo. El cliente HTTP queda en `buzon`, y `cobro` declara el puerto (`VigenciaEnElOrigen`), para que `DependenciasEntrePaquetesTest` no vea un ciclo.
6. **#41, el contrato `rentas.json` de hneyra/caja:** es un archivo generado de otro repositorio. Aquí solo se corrige el README de caja-backend (952-955, 1534-1536). El generador queda anotado en «Siguientes pasos».

## Global Constraints

- Mensajes de commit `tipo(alcance): asunto en minúsculas`, en castellano, de hasta 120 caracteres. Los alcances son los paquetes (`cobro`, `buzon`, `recaudacion`, `modelo`). Cada commit lleva el pie de la sesión.
- Los comentarios del código van en castellano, en minúsculas, y explican el porqué.
- Cada campo nuevo del modelo toca cuatro lugares: `model/model.json`, `model/test_model.py` (los conjuntos exactos), el DTO de `modelo/*.kt` y la tabla del README.
- Las escrituras de objetos de caja llevan `EscrituraDeCaja` y una razón constante, sin caracteres de control.
- Los candados se toman en este orden: TURNO_CLAVE → TURNO → ORDEN (por id ordenado) → SERIE → RECIBO → PAGO, y siempre dentro de `Transaccion.en`.
- Ningún llamado HTTP al origen ocurre dentro de una transacción (`check(!enTransaccion())`).
- Los importes viajan en cadena con 2 decimales y se comparan con `compareTo`.
- **CI:** `./gradlew build`, `./gradlew integrationTest`, `cd model && python3 apply.py --validate-only && python3 -m unittest -v` y `yarn format:check`.

## Review Focus

1. **Un retiro que llega mientras un cobro tiene el candado de la orden.** El retiro espera. Al entrar ve PAGADA y responde 409, nunca deja ANULADA una orden pagada. Lo cubre el Task 1, Step 1: `retiro y cobro a la vez`.
2. **Un recibo anulado el mismo día devuelve a PENDIENTE una orden que el origen intentó retirar** (recibió 409 porque estaba PAGADA). La orden vuelve a ser cobrable con el importe viejo. Caja no puede saberlo. El `PAGO_ANULADO` le avisa a srtm, que debe reintentar el retiro (anotado en srtm-backend #164). Caja lo documenta en el README («El retiro»). Lo cubre el Task 1, Step 1: `una orden devuelta a PENDIENTE por la anulación se puede retirar`.
3. **El reenvío del alta con `actualizado_a` distinto y el mismo importe.** Es 200 idempotente, no 409: `actualizado_a` no entra en la comparación. Lo cubre el Task 2, Step 1.
4. **El reenvío de un cobro con la misma `Idempotency-Key` después de que el origen dijo «no vigente».** Devuelve el recibo de la primera vez, sin volver a consultar al origen: la comprobación va después de la búsqueda del reenvío (`Ventanilla.cobrar`, antes de la transacción). Lo cubre el Task 4, Step 1: `el reenvío no vuelve a consultar`.
5. **Una referencia con `|`** (las claves de arbitrios de srtm). El retiro la recibe en el cuerpo, nunca en la ruta. El 404 la repite tal cual. Lo cubre el Task 1, Step 1, con la referencia `ARB-p1|LIMP|2026|3|1|2`.

---

### Task 1: el retiro de una orden (#39)

**Files:**
- Modify:
  - `model/model.json`: los campos `motivo_retiro` (TEXT, 1-200) y `retirada_en` (DATETIME) de `orden_de_cobro`, y su descripción del estado ANULADA;
  - `model/test_model.py:93-100`, para los campos nuevos;
  - `src/main/kotlin/caja/modelo/OrdenDeCobro.kt`: `motivoRetiro: String?` y `retiradaEn: OffsetDateTime?`;
  - `src/main/kotlin/caja/cobro/Dtos.kt`: `RetiroDeOrden : CuerpoEstricto(referencia_externa, motivo, sistema_origen?)`, y `motivo_retiro` y `retirada_en` en `OrdenRespuesta`;
  - `src/main/kotlin/caja/cobro/CobroController.kt`: `POST /ordenes-de-cobro/retiro`;
  - `src/main/kotlin/caja/cobro/OrdenesService.kt`: `retirar`;
  - `src/main/kotlin/caja/cobro/Cobranza.kt:100-110`: la ANULADA pasa a ser `OrdenRetirada`, un 422;
  - `README.md`, en el modelo (239), la API (403) y una sección nueva, «El retiro», después de «El alta de órdenes» (450).
- Create: `src/main/kotlin/caja/cobro/Retiro.kt`, con las reglas puras del retiro.
- Test:
  - `src/test/kotlin/caja/cobro/RetiroApiTest.kt` (nuevo);
  - `src/test/kotlin/caja/cobro/CobranzaTest.kt:91-100`;
  - `src/test/kotlin/caja/cobro/CobroApiTest.kt:219`.

**Interfaces:**
- Produces:
  - `suspend fun OrdenesService.retirar(body: RetiroDeOrden): OrdenRespuesta`;
  - `class OrdenRetirada(orden: OrdenDeCobro) : WasichaiException(HttpStatus.UNPROCESSABLE_CONTENT, …)` en `Cobranza.kt`;
  - `internal suspend fun OrdenesService.retirarBajoCandado(orden: OrdenDeCobro, motivo: String, ahora: OffsetDateTime): OrdenDeCobro`. Toma el candado ORDEN, relee la orden y escribe ANULADA como quien llama. La reutilizan el Task 2 y el Task 4.
  - `fun motivoDeRetiro(valor: String?): String` en `Retiro.kt`: hace `trim`, es obligatorio, de 1 a 200 caracteres sin caracteres de control, y si no cumple da 400 en `motivo`.

- [ ] **Step 1: Write the failing tests** in `RetiroApiTest : CajaApiTest`. Cada caso da de alta su orden con `cuentaDeServicio("rentas")`:
  - `retirar una PENDIENTE la deja ANULADA con su motivo y su fecha`:
    - 200;
    - `estado == "ANULADA"`, `motivo_retiro == "Redeterminación por la DJ n° 12"`, y `retirada_en` no nulo;
    - `estadoDe(id) == "ANULADA"`.
  - `retirar dos veces es idempotente`: el segundo retiro responde 200, con el mismo `retirada_en` y el primer motivo.
  - `retirar una PAGADA es 409 y dice el recibo`:
    - cobrar la orden y después retirarla da 409;
    - `detail` contiene el número del recibo;
    - la orden sigue PAGADA.
  - `una referencia que no existe es 404`, con la referencia `ARB-p1|LIMP|2026|3|1|2`, que el `detail` repite tal cual.
  - `otro sistema no retira lo ajeno`:
    - la cuenta `mercados` retirando una orden de `rentas` da 404, igual que si no existiera;
    - un funcionario con el rol SISTEMA_ORIGEN da 403;
    - un ADMIN sin `sistema_origen` en el cuerpo da 400 en `sistema_origen`.
  - `sin motivo es 400 en motivo`.
  - `retiro y cobro a la vez: gana uno`:
    - se lanzan los dos con `coroutineScope { async {…}; async {…} }`;
    - una de dos: el cobro responde 201 y el retiro 409, o el retiro 200 y el cobro 422;
    - nunca queda ANULADA con recibo.
  - `el cobro de una ANULADA es 422`, con el `detail` «el sistema que la emitió la retiró».
  - `la vista previa de una ANULADA sigue siendo 200 con cobrable false`.
  - `la lista de pendientes no la muestra; estado=ANULADA sí, con su motivo`.
  - `una orden devuelta a PENDIENTE por la anulación del recibo se puede retirar`: cobrar, anular el recibo y retirar da 200 ANULADA.

  En `CobranzaTest:91-100`, el impedimento de una ANULADA es `OrdenRetirada`, con estado 422.

- [ ] **Step 2: Run** `./gradlew integrationTest --tests 'caja.cobro.RetiroApiTest'` y `./gradlew test --tests 'caja.cobro.CobranzaTest'`. Expected: FAIL (404 en la ruta, 409 en lugar de 422).

- [ ] **Step 3: Implement.**
  - **El modelo y los DTO**, como en Files.
  - **`retirar`:** resuelve el sistema con `sistemaDeLaCuenta` (el mismo camino que `registrar`) y busca la orden con `porOrigen`. Si no existe, 404. Si existe, `transaccion.en { retirarBajoCandado(...) }`.
  - **Bajo el candado:**
    - PENDIENTE → ANULADA, con `motivo_retiro`, `retirada_en = now(reloj)` y la razón constante `"retiro de la orden por su sistema de origen"`;
    - ANULADA → la devuelve tal cual;
    - PAGADA → `ConflictException("La orden … ya se cobró con el recibo {serie-numero}: el sistema de origen la imputa cuando reciba el pago")`.
  - **La escritura** va como quien llama, con `EscrituraDeCaja` (ver «Decisiones», 2).
  - **`impedimentosDelCobro`:** una ANULADA da `OrdenRetirada`. El resto no cambia.

- [ ] **Step 4: Run** los dos comandos del Step 2, y además `./gradlew build` y `cd model && python3 -m unittest -v`. Expected: PASS.

- [ ] **Step 5: README y commit.**
  - **README:**
    - la sección «El retiro»: el contrato, sus cinco respuestas y la carrera con el cobro;
    - el caso del Review Focus 2;
    - la fila de la API.
  - **Commit:** `git commit -m "feat(cobro): el sistema de origen retira una orden pendiente"`.

### Task 2: el alta que choca y la sustitución (#40)

**Files:**
- Modify:
  - `src/main/kotlin/caja/cobro/OrdenesService.kt:33-47`, el `catch` del alta, y una rama nueva para `sustituye_a`;
  - `src/main/kotlin/caja/cobro/Dtos.kt`: `NuevaOrden.sustituyeA: String?` (`sustituye_a`), y `sustituye_a` y `sustituida_por` en `OrdenRespuesta`;
  - `model/model.json`: los campos TEXT `sustituye_a` y `sustituida_por` de `orden_de_cobro` (hasta 120, la regla de la referencia);
  - `model/test_model.py`;
  - `modelo/OrdenDeCobro.kt`;
  - `README.md`: el alta (433-450), la tabla del modelo y una guía de la referencia para un origen que redetermina.
- Create: `src/main/kotlin/caja/cobro/Choque.kt`, pura, con `fun diferencias(existente: OrdenDeCobro, pedida: Map<String, Any?>): List<String>`. Compara `importe` (con `compareTo`), `fecha_exigibilidad` y `pagador_documento` (en mayúsculas), y devuelve los nombres de los campos que difieren.
- Test:
  - `src/test/kotlin/caja/cobro/OrdenesApiTest.kt:28-32`, que hoy espera un 200 con otro importe;
  - `src/test/kotlin/caja/cobro/SustitucionApiTest.kt` (nuevo);
  - `src/test/kotlin/caja/cobro/ChoqueTest.kt` (nuevo);
  - `src/test/kotlin/caja/cobro/FronteraDeLaOrdenTest.kt`.

**Interfaces:**
- Consumes: `retirarBajoCandado` (Task 1).
- Produces: `OrdenRespuesta.sustituyeA: String?` y `OrdenRespuesta.sustituidaPor: String?`. Los lee caja-ui (#27).

- [ ] **Step 1: Write the failing tests.**
  - **`ChoqueTest`:**
    - `importe 100.0 contra 100.00 no difiere`;
    - `importe 100.01 difiere en importe`;
    - `otra fecha difiere en fecha_exigibilidad`;
    - `el documento en minúsculas no difiere`;
    - `actualizado_a no se compara`.
  - **`OrdenesApiTest`:**
    - `la misma referencia con otro importe es 409`: el `detail` nombra `importe` y la orden existente no cambia;
    - `la misma referencia con los mismos datos es 200 nueva false`;
    - `la misma referencia con otro actualizado_a es 200`.
  - **`SustitucionApiTest`:**
    - `sustituir una PENDIENTE`:
      - 201;
      - la nueva queda PENDIENTE, con `sustituye_a = "ARB-…-v1"`;
      - la vieja queda ANULADA, con `motivo_retiro = "Sustituida por ARB-…-v2"` y `sustituida_por = "ARB-…-v2"`.
    - `reenviar la misma sustitución es 200 nueva false y no cambia nada`.
    - `sustituir una PAGADA es 409 y no crea nada`: la referencia nueva no existe después.
    - `sustituir una que no existe es 404 en sustituye_a`: no crea nada.
    - `sustituir una ya ANULADA sin sustituta crea la nueva`, que es el caso de srtm cuando retiró primero.
    - `sustituirse a sí misma es 400 en sustituye_a`.
    - `sustitución y cobro de la vieja a la vez: gana uno`: o el 201 de la nueva con la vieja ANULADA, o el cobro de la vieja con el 409 de la sustitución.
  - **`FronteraDeLaOrdenTest`:** pasa con los campos nuevos. No empiezan con `tributo`, `ejercicio` ni `periodo`.

- [ ] **Step 2: Run** `./gradlew test --tests 'caja.cobro.ChoqueTest'` y `./gradlew integrationTest --tests 'caja.cobro.OrdenesApiTest' --tests 'caja.cobro.SustitucionApiTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **El alta sin `sustituye_a`:** en el `catch`, si `diferencias(...)` no está vacío, responde `ConflictException("Ya hay una orden … con esa referencia y otro {campos}: …")`.
  - **El alta con `sustituye_a`:**
    1. Fuera de la transacción, busca la nueva referencia. Si ya existe, es un reenvío: compara con `diferencias` y responde 200 o 409.
    2. Si no existe, `transaccion.en`:
       - toma el candado ORDEN de la vieja;
       - la relee;
       - si está PAGADA, 409;
       - si está PENDIENTE, `retirarBajoCandado` con el motivo «Sustituida por {nueva}»;
       - crea la nueva con `sustituye_a`;
       - escribe `sustituida_por` en la vieja.
    3. Un `DuplicateKeyException` dentro de la transacción (dos sustituciones a la vez) se resuelve como reenvío: relee fuera y compara.

- [ ] **Step 4: Run** los comandos del Step 2 y `./gradlew build`. Expected: PASS.

- [ ] **Step 5: README y commit.**
  - **README:**
    - el alta que choca;
    - la sustitución;
    - «cómo arma su referencia un origen que redetermina: una por versión, p. ej. `ARB-{clave de srtm}`, y sustituye la versión anterior».
  - **Commit:** `git commit -m "feat(cobro): el alta que choca es 409 y una orden puede sustituir a otra"`.

### Task 3: el pago «a cuenta» (#41)

**Files:**
- Modify:
  - `src/main/kotlin/caja/buzon/Entrega.kt:42-52,126-140`: `Respuesta.Entregado` pasa a ser `data class Entregado(val aCuenta: String? = null)`, donde `aCuenta` es el motivo o null. El cuerpo se lee solo con 202.
  - `src/main/kotlin/caja/buzon/BuzonStore.kt:113-123`: `entregado(..., aCuenta: String?)` escribe `a_cuenta` y `motivo_a_cuenta`.
  - `src/main/kotlin/caja/buzon/PublicadorDelBuzon.kt:155-185`.
  - `model/model.json`: `pago_evento.a_cuenta` (BOOLEAN) y `pago_evento.motivo_a_cuenta` (TEXT, hasta 400).
  - `model/test_model.py:144-150`.
  - `modelo/PagoEvento.kt`.
  - `buzon/Dtos.kt` (`PagoDelBuzon`) y `buzon/Pagos.kt:32-48`.
  - `src/main/kotlin/caja/buzon/PagoController.kt`: `GET /api/caja/pagos/a-cuenta?fecha=`, con READ sobre `pago_evento`.
  - `src/main/kotlin/caja/recaudacion/Conciliacion.kt:83-198` y `recaudacion/Dtos.kt:111-129`.
  - `README.md`: `pago_evento` (331-350), el evento y `ordenId` (952-955), el buzón y la conciliación (1242-1264), y «Siguientes pasos» (1534-1536).
- Test:
  - `src/test/kotlin/caja/buzon/EntregaTest.kt`;
  - `src/test/kotlin/caja/buzon/BuzonApiTest.kt`;
  - `src/test/kotlin/caja/recaudacion/ConciliacionTest.kt`;
  - `src/test/kotlin/caja/recaudacion/RecaudacionApiTest.kt`.

**Interfaces:**
- Produces:
  - `fun aCuentaDe(cuerpo: String?): String?`: el motivo, si el cuerpo es `{"a_cuenta": true, "motivo": "…"}`. Si falta el motivo, da «el origen no dijo por qué». Usa `tachar`/`recortar`.
  - `AplicadoEnElOrigen(recibidos, aplicados, rechazados, importeAplicado, recibidosACuenta: Long = 0, importeACuenta: BigDecimal = ZERO)`.
  - `LineaDeConciliacionRespuesta.recibidosACuenta: Long?` e `importeACuenta: Importe?` (`recibidos_a_cuenta`, `importe_a_cuenta`).
  - `GET /api/caja/pagos/a-cuenta?fecha=` responde `[{pago_id, recibo, sistema_destino, total, motivo_a_cuenta, entregado_en}]`. La lee la hoja de cierre de caja-ui (#28).

- [ ] **Step 1: Write the failing tests.**
  - **`EntregaTest`:**
    - `202 con a_cuenta true es entregado a cuenta con su motivo`;
    - `202 sin cuerpo o con cuerpo ilegible es entregado sin anotar`;
    - `200 con a_cuenta true no se anota, solo el 202 lo dice`;
    - `el motivo se recorta a 400 y pierde los caracteres de control`.
  - **`BuzonApiTest`:** `un 202 a cuenta queda ENTREGADO con a_cuenta y su motivo, y aparece en pagos/a-cuenta del día`.
  - **`ConciliacionTest`:**
    - `sin los campos a cuenta, el origen viejo sigue leyéndose con ceros a cuenta`;
    - `diferencia = neto − importe_aplicado − importe_a_cuenta`;
    - `con pagos a cuenta y sin rechazos cuadra`;
    - `importe_a_cuenta con 3 decimales no se sabe`.
  - **`RecaudacionApiTest`:** `la línea de rentas trae recibidos_a_cuenta e importe_a_cuenta`.

- [ ] **Step 2: Run** `./gradlew test --tests 'caja.buzon.EntregaTest' --tests 'caja.recaudacion.ConciliacionTest'` y `./gradlew integrationTest --tests 'caja.buzon.BuzonApiTest' --tests 'caja.recaudacion.RecaudacionApiTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **`clasificar`:** con 202, `Entregado(aCuentaDe(cuerpo))`. Con el resto de los éxitos, `Entregado()`.
  - **El comentario de la línea 121** se reescribe: el código decide la entrega, y el cuerpo de un 202 solo anota.
  - **`aplicadoDe`:** lee `recibidos_a_cuenta` (entero) e `importe_a_cuenta` (cadena, con a lo sumo 2 decimales; también `importeACuenta`). Son opcionales, y si faltan valen 0.
  - **`cuadra()`:** la fórmula no cambia. Lo único que cambia es la `diferencia`.

- [ ] **Step 4: Run** los comandos del Step 2, y además `./gradlew build` y `cd model && python3 -m unittest -v`. Expected: PASS.

- [ ] **Step 5: README y commit.**
  - **README:**
    - el contrato del 202 con un ejemplo;
    - el `PAGO_ANULADO` de un pago a cuenta: el origen revierte el saldo;
    - `ordenId` es un UUID en cadena;
    - el generador de hneyra/caja, en «Siguientes pasos».
  - **Commit:** `git commit -m "feat(buzon): el pago que el origen recibe a cuenta se entrega y se concilia aparte"`.

### Task 4: la comprobación en línea (#42)

**Files:**
- Create:
  - `src/main/kotlin/caja/cobro/VigenciaEnElOrigen.kt`, el puerto:
    - `interface VigenciaEnElOrigen { suspend fun consultar(sistema: String, ordenes: List<OrdenDeCobro>): Vigencia }`;
    - `sealed interface Vigencia { data object NoSeConsulta; data class Contesto(val noVigentes: Map<String, String>); data class NoContesta(val motivo: String, val cobrar: Boolean) }`, donde las claves de `noVigentes` son id de orden y los valores, motivos.
  - `src/main/kotlin/caja/buzon/VigenciaPorHttp.kt`: la implementación con `ClienteDelSistemaDeOrigen`.
- Modify:
  - `buzon/ClienteDelSistemaDeOrigen.kt`: `suspend fun preguntar(sistema: String, ruta: String, cuerpo: String): Lectura`, un POST fuera de la transacción;
  - `buzon/ConfiguracionDelBuzon.kt`: `Destino(url, token, verificar: Boolean = false, sinRespuesta: SinRespuesta = COBRAR)`;
  - `application.yml:29-56`, comentado;
  - `cobro/CobroService.kt:43-69`;
  - `cobro/Ventanilla.kt:86-100`: un parámetro `antes: suspend () -> Unit`, que corre después de la búsqueda del reenvío y antes de la transacción;
  - `model/model.json`: `recibo.advertencia_origen` (TEXT, hasta 400);
  - `model/test_model.py`;
  - `modelo/Recibo.kt`;
  - `buzon/PagoController.kt`: `GET /api/caja/cobros/sin-verificar?fecha=`, o `cobro/CobroController.kt` (se elige el paquete que no cree un ciclo: `cobro`);
  - `README.md`.
- Test:
  - `src/test/kotlin/caja/buzon/SistemaDeOrigenFalso.kt`: un manejador para `POST /ordenes/vigencia`, con `vigencia(referencia, vigente, importe, motivo)`;
  - `src/test/kotlin/caja/cobro/VigenciaApiTest.kt` (nuevo);
  - `src/test/kotlin/caja/DependenciasEntrePaquetesTest.kt`, que tiene que pasar sin cambios.

**Interfaces:**
- Consumes: `retirarBajoCandado` (Task 1).
- Produces: el contrato del origen.
  - **Pregunta:** `POST {url}/ordenes/vigencia` con `{"ordenes":[{"referencia_externa":"…","importe":"12.50"}]}`.
  - **Respuesta:** 200 con `{"ordenes":[{"referencia_externa":"…","vigente":true|false,"importe":"12.50"|null,"motivo":"…"|null}]}`.
  - **Regla:** una orden no vigente, o con otro importe, no se cobra. Una referencia que falta en la respuesta cuenta como «no contesta».
  - **srtm** lo implementa en un issue nuevo, que se abre al cerrar este plan.

- [ ] **Step 1: Write the failing tests** in `VigenciaApiTest`, con el destino `rentas` y `verificar=true`:
  - `todo vigente cobra`: 201, y el origen recibió una sola pregunta con las referencias y los importes.
  - `una no vigente: 422 y la orden queda ANULADA con el motivo del origen`.
  - `importe distinto: 422 y la orden queda ANULADA con «el origen dice otro importe: 13.00»`.
  - `origen caído y sin-respuesta COBRAR: cobra, y el recibo trae advertencia_origen y aparece en cobros/sin-verificar`.
  - `origen caído y sin-respuesta NO_COBRAR: 503 y no se emite nada`.
  - `verificar false: no pregunta`.
  - `el reenvío con la misma Idempotency-Key no vuelve a consultar` (Review Focus 4).
  - `respuesta ilegible cuenta como origen caído`.

- [ ] **Step 2: Run** `./gradlew integrationTest --tests 'caja.cobro.VigenciaApiTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **El flujo en `CobroService.cobrar`:** pasa en `antes` una lectura de las órdenes, el sistema único y `vigencia.consultar`.
    - Con `noVigentes`: por cada una, `transaccion.en { retirarBajoCandado(..., "el origen dice: {motivo}") }`, y después la excepción `OrdenRetirada`.
    - Con `NoContesta` y `cobrar`: sigue, y deja la advertencia para el `pagar`, que la escribe en el recibo.
    - Con `NoContesta` sin `cobrar`: `ServiceUnavailable`.
  - **La implementación HTTP** es un `@Component` en `buzon`. Si no hay destino o `verificar` es false, devuelve `NoSeConsulta`.

- [ ] **Step 4: Run** el Step 2 y `./gradlew build`, que incluye `DependenciasEntrePaquetesTest`. Expected: PASS.

- [ ] **Step 5: README y commit.** El README lleva el contrato de vigencia como paso opcional y su configuración. Commit: `git commit -m "feat(cobro): comprobar con el origen antes de cobrar, si se configura"`.

## Entrega

Hay un PR por task, en este orden:
- 1 y 3 son independientes;
- 2 depende de 1;
- 4 depende de 1.

Cada PR cierra su issue, con el CI en verde. Se mezcla cuando el usuario lo pide (lo pidió el 2026-10-21: «asegurate que los PRs pinten verde, los mezclas y continuas»).
