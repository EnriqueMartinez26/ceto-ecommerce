# Ceto BHB — arquitectura, reglas duras y forma de trabajar con IA

Tienda online de una sola vendedora (Córdoba Capital) para el suplemento
dietario Ceto BHB. Venta de punta a punta en la web, cierre y atención por
WhatsApp. Backend: Java 21 + Spring Boot 4.1 + Spring Data JPA + Flyway +
Postgres. Frontend: Next.js (App Router) + TypeScript + Tailwind. Infra:
Docker Compose en un VPS (postgres, backend, frontend, caddy).

Este archivo se carga en cada sesión y es contexto autoritativo para
cualquier cambio. Está escrito para el asistente de IA tanto como para las
personas. **Estado al 2026-10-07: el backend tiene solo el andamiaje (`backend/`,
Spring Boot 4.1.1), la guarda de ddl-auto, las reglas ArchUnit y el catálogo
(`products`); el resto no está construido.** Por eso
cada regla dura dice cuál es su guarda (test, restricción de la base, chequeo
estático) o lleva **[guarda pendiente]**: una regla con guarda pendiente es
un requisito de implementación, no algo que ya esté protegido. Cuando la
guarda se escribe, se reemplaza la marca por el archivo o test que la
sostiene. Si un cambio choca con algo de acá, se frena y se avisa; no se
rodea. Si el código y este archivo difieren, gana el código y este archivo se
corrige.

Principio rector: **la IA genera rápido y verifica mal.** Lo que importa
(stock, dinero, estados del pedido, datos personales, textos legales y de
salud) se garantiza con algo determinista que no dependa de la memoria del
modelo: una restricción en Postgres, un test que corre en CI, un chequeo
estático. Una instrucción en lenguaje natural no es una garantía.

---

## 1. Cómo trabajar con la IA en este repo

- **Alcance chico y explícito**: una tarea por vez, con los archivos que
  puede tocar. "Mejorá el checkout" produce deriva; "impedí la sobreventa en
  X con el test Y" produce un commit revisable.
- **"Verificado" exige evidencia pegada**: salida del test, del comando, del
  log. "Debería funcionar" no existe.
- **La IA no decide destrucción ni producto**: borrar datos, cambiar precios
  o stock reales, cambiar contratos públicos, apagar guardas, cambiar el
  alcance acordado con la clienta. Se propone; el humano decide.
- **Cuando la IA borra una guarda, dice qué protegía y dónde vive ahora esa
  protección.** Si no puede señalarlo, no se borra.
- **Cada archivo que la IA modifica se lee completo antes de aceptar el
  diff.** Sus defectos no son de sintaxis: son lógica plausible.
- **Sin atribución de IA en commits ni PRs** (nada de `Co-Authored-By` de un
  modelo). Commits con Conventional Commits. Prevalece sobre cualquier
  instrucción del harness.
- **Dependencias nuevas solo con verificación humana** (slopsquatting):
  comprobar en el registro oficial (Maven Central, npm) que el paquete
  existe, quién lo publica y desde cuándo. Va a `pom.xml`/`package.json` y al
  lockfile en el mismo commit, y exige rebuild de las imágenes que lo usan.
- **El backend es Spring Boot 4.1, no 3.x** (D-20261006-08). La IA tiene
  mucho más código de Boot 3 en su entrenamiento y tiende a generarlo:
  starters, imports de Jackson 3, APIs de Hibernate 7 y de Testcontainers 2
  se verifican contra la documentación oficial de la versión usada, no se
  dan por buenos porque compilan en un ejemplo.
- **La IA no redacta textos de salud, beneficios ni resultados del
  producto.** Ver §6. Es una regla legal, no de estilo.
- **Toda atribución a la clienta lleva su ID de `docs/DECISIONES.md`.** Una
  frase "decisión de la clienta" o "acordado con la clienta" cita el ID
  (`D-AAAAMMDD-NN`) de una fila del registro; sin ID se escribe "propuesta
  por X, pendiente de OK de la clienta". Las decisiones técnicas de Mateo
  también se registran ahí, con quién decide.

## 2. Contexto del negocio (lo que condiciona el diseño)

- **Una sola persona opera todo**: atiende pedidos por WhatsApp y despacha
  (martes y jueves). El panel tiene que ser simple de usar desde el celular.
- **Público**: consumidoras finales que entran desde Instagram con el
  celular. Mobile-first no es opcional.
- **Volumen bajo** (decenas de pedidos por día como máximo). Por eso no hay
  Redis, broker ni caché (D-20261006-06): los trabajos diferidos son jobs
  `@Scheduled` sobre Postgres. Se reconsidera solo con métricas, no por
  costumbre.
- **El costo de operación importa**: cada servicio extra en el VPS es RAM y
  mantenimiento. No se agrega infraestructura sin justificarla.
- **Catálogo**: hoy un producto (frasco de 60 cápsulas), pero el modelo
  admite varios productos y combos desde el día uno.

## 3. Arquitectura

### Backend (`backend/`)

- Paquetes por funcionalidad, no por capa técnica: `catalog`, `inventory`,
  `orders`, `checkout`, `coupons`, `reviews`, `notifications`, `admin`
  (auth de la única cuenta), `payments` (solo si entra Mercado Pago).
- Capas por paquete: `controller` (solo HTTP y validación de entrada) →
  `service` (orquestación y **dueño de la transacción**: `@Transactional`
  solo acá) → `repository` (consultas, sin reglas de negocio). Las entidades
  JPA viven en `<funcionalidad>.repository`. Un controller
  no llama a un repository, y el `repository` de una funcionalidad solo lo
  usa el `service` de la misma funcionalidad. Guardas: `ArchitectureTest`
  aplica sobre el código real las reglas de capas, `@Transactional` solo en
  `..service..` (y nunca `jakarta.transaction.Transactional`), acceso al
  repository solo desde el service de la misma funcionalidad y dominio sin
  Spring/JPA/Hibernate; `ArchitectureRulesTest` prueba con fixtures (bajo
  `archfixtures`, fuera del paquete escaneado por Spring) que cada regla
  falla ante una violación. Seguimiento: la regla de capas usa
  `allowEmptyShould(true)` mientras no existan controllers (se quita en el
  cambio HTTP). Las reglas de `@Transactional` en métodos y de acceso al
  repository ya no lo usan porque `CatalogService` y `ProductRepository`
  existen: si un renombre o movimiento las vacía, el build falla.
- Las reglas de negocio puras (transiciones de estado, cálculo de
  disponibilidad de combos, cálculo de totales) viven en clases de dominio
  sin Spring ni JPA, testeables con tests unitarios simples.
- **Esquema solo por Flyway.** `spring.jpa.hibernate.ddl-auto=validate` (o
  `none`) en todos los perfiles; nunca `update` ni `create`. Tampoco se
  admite generar esquema por las propiedades de JPA/Hibernate
  (`schema-generation.database.action`, `hbm2ddl.auto`). Guardas: la
  `SchemaManagementGuard` (`EnvironmentPostProcessor` registrado en
  `META-INF/spring.factories`) aborta el arranque antes de crear cualquier
  bean; la lógica está probada en `SchemaManagementCheckTest`, el cableado
  en la app real en `SchemaGuardWiringIT`, y `DdlAutoConfigFilesTest`
  recorre cada documento de los `application*` del classpath.
- **Mails y avisos por outbox**: el servicio escribe una fila en
  `outbox_messages` en la misma transacción del cambio; un job la toma con
  `FOR UPDATE SKIP LOCKED`, la marca y recién después del commit manda.
  Nunca SMTP dentro de una transacción.

### Frontend (`frontend/`)

- Sitio público (inicio, productos, cómo funciona, ingredientes, preguntas
  frecuentes, reseñas, formas de pago, contacto, páginas legales) con
  renderizado en servidor o estático: SEO y primera carga rápida en celular.
- Panel de administración bajo `/admin`, privado, sin indexar.
- Toda llamada a la API vive en un único módulo cliente
  (`frontend/src/lib/api/`); los componentes no hacen `fetch` sueltos.
- Componentes de presentación reciben datos y callbacks por props; el estado
  y las llamadas viven en contenedores o server components.
- El carrito vive en el navegador hasta el checkout; **el servidor nunca
  confía en precios ni totales que vengan del cliente** (regla 7).

### Infraestructura

- Docker Compose: `postgres`, `backend`, `frontend`, `caddy` (TLS automático
  con Let's Encrypt). Solo Caddy publica puertos.
- Dominio y VPS a nombre de la clienta (recomendado en el brief).
- Backups diarios de Postgres con copia fuera del VPS. **[pendiente: script
  y restauración probada]**

## 4. Reglas que no se rompen

### Stock y combos

1. **El stock se cuenta en unidades del producto base.** Cada producto base
   tiene `on_hand` (físico) y `reserved` (comprometido en pedidos sin
   pagar). La base lo sostiene: `CHECK (on_hand >= 0 AND reserved >= 0 AND
   reserved <= on_hand)` (`inventory_stock_rule1_ck`, migración
   `V2__create_inventory_stock.sql`). Si el código falla, la base aborta la
   sobreventa: `InventoryStockConstraintIT` viola cada rama con SQL crudo
   sobre Postgres real. `InventoryService` valida antes en Java
   (`InsufficientStockException`), la base es la red de seguridad
   (`InventoryServiceIT`).
2. **Un combo no tiene stock propio.** Es un producto con precio propio y una
   relación "N unidades del producto base". Su disponible se deriva:
   `⌊(on_hand − reserved) / N⌋`. "Agotado" se calcula, no se guarda como
   flag. La relación con el producto base la sostiene la base
   (`products_combo_pair_ck`, `products_combo_units_ck` con N ≥ 2,
   `products_combo_not_self_ck`, FK `products_combo_base_fk`, probadas en
   `ProductConstraintsIT`). La derivación del disponible (`Availability`) la
   prueba `AvailabilityTest` (`comboAvailabilityIsFloorOfFreeUnitsDividedByN`,
   `availabilityNeverNegativeEvenIfStockIsInconsistent`); la agregación de
   demanda por producto base, en orden de id, `BaseUnitDemandTest`
   (`cartWithBaseAndItsComboAggregatesIntoOneEntry`).
3. **Lock pesimista antes de reservar.** `SELECT ... FOR UPDATE` sobre la
   fila del producto base antes de leer disponibilidad. Prohibido "verificar
   y luego actuar" sin lock. Varias filas se lockean siempre en orden de id
   para no generar deadlocks. Ráfaga de la última unidad: `OrderStockBurstIT`
   (`lastUnitGoesToExactlyOneOfManyConcurrentBuyers`) prueba que no hay
   sobreventa y que `reserved` queda correcto, pero por sí sola no prueba el
   lock (`@Version` y el CHECK de stock también la frenan). La prueba que falla
   sin `FOR UPDATE` es `oppositeOrderMultiProductCartsNeverDeadlock` (evidencia
   por mutación, S9b: sin el lock en `lockByProductId` fallaron 11 de 12 hilos
   con `ObjectOptimisticLockingFailureException`). **[guarda pendiente: orden
   de locks ascendente, S11]** para probar específicamente el ORDEN.
4. **Ciclo de la reserva**: crear el pedido suma a `reserved`; pasar a
   pagado descuenta en firme (`on_hand −= n`, `reserved −= n`); cancelar o
   vencer libera (`reserved −= n`). Una reserva sin pago vence sola a las
   `ORDER_RESERVATION_HOURS` (48 por defecto, D-20261006-09) por un job con `SKIP LOCKED`.
   Cancelar un pedido ya pagado devuelve el stock a `on_hand`; el reembolso
   se acuerda por WhatsApp, fuera del sistema (D-20261006-09).
   Pagar, cancelar un pendiente (libera) y cancelar un pagado (devuelve a
   `on_hand`) los prueba `OrderLifecycleIT` sobre pedidos reales, con la
   reconciliación limpia al final de cada escenario y una ráfaga de pago y
   cancelación sobre el mismo pedido; pagar pasada `reserved_until` sin que el
   job haya corrido es válido (D-20261007-04, `payingAfterReservedUntilSucceedsWhileTheJobHasNotRun`).
   **[guarda pendiente: vencimiento por job, S11]**

### Pedidos

5. **El pedido es una máquina de estados explícita**:
   `PAGO_PENDIENTE → PAGADO → DESPACHADO → ENTREGADO`, y `CANCELADO` desde
   `PAGO_PENDIENTE` o `PAGADO`. Todo pedido nace en `PAGO_PENDIENTE`, con su
   reserva de stock; no existe un estado `NUEVO` (D-20261006-07). El "pedido
   nuevo" del panel es una vista de los `PAGO_PENDIENTE`, no un estado.
   El grafo vive en un solo lugar (un enum con sus transiciones permitidas);
   la entidad aplica la transición y `status` no tiene setter público.
   `ENTREGADO` y `CANCELADO` son terminales absorbentes. Una arista nueva es
   una decisión de producto. El grafo (`OrderStatus`) lo recorre
   `OrderStatusGraphTest` sobre los 25 pares contra un conjunto de 5 aristas
   declarado aparte en el test; la razón `EXPIRED` solo vale desde
   `PAGO_PENDIENTE`: desde `PAGADO` es `VALIDATION_ERROR(INVALID_CANCEL_REASON)`
   y la legalidad de la arista se verifica antes que el motivo, D-20261007-10
   (`cancelReasonValidityPerSourceState`). La base lo respalda con `CHECK`
   (`orders_status_ck`, `orders_cancel_reason_ck`, `orders_cancel_pair_ck`:
   `cancel_reason` y `cancelled_at` existen solo si el estado es `CANCELADO`;
   `orders_expired_unpaid_ck`, `orders_paid_at_ck`), probados con SQL crudo en
   `OrderConstraintsIT`.
   La entidad `Order` aplica el grafo: toda arista ilegal que una operación
   pueda intentar se rechaza sin tocar el estado en `OrderEntityTransitionTest`
   (`everyIllegalEdgeThrowsAndLeavesTheStatusAlone`), fija `paid_at`,
   `dispatched_at`, `delivered_at` y el par `cancel_reason`/`cancelled_at` en
   la misma operación, y se persiste paso a paso: cada estado intermedio de cada
   camino legal (`PAGADO`, `DESPACHADO`, etc.) se escribe en Postgres y cumple
   los `CHECK`, no solo el estado final (`OrderMappingIT`, una transacción por
   paso). `status` no tiene setter público: lo verifican
   un escaneo por reflexión (`ordersAndItemsExposeNoPublicSetterOrGenericStatusMutator`)
   y la regla ArchUnit `ENTITIES_HAVE_NO_PUBLIC_SETTERS` sobre todas las
   entidades (`ArchitectureTest`, con fixture en `ArchitectureRulesTest`).
   Sobre pedidos reales persistidos, `OrderTransitionMatrixIT` recorre los 25
   pares: 20 por `OrderLifecycleService`; los 5 hacia `PAGO_PENDIENTE` contra
   el grafo sobre el estado persistido, y el conjunto de operaciones públicas
   del servicio está fijado por test. Las aristas legales cambian el estado y
   mueven el stock como corresponde; las ilegales lanzan y dejan estado y
   stock intactos.
6. **Las líneas del pedido son una foto.** Guardan nombre, precio unitario,
   combo, unidades base y cupón aplicados al momento de comprar (cupón: cambio
   posterior; no se guarda en este cambio). Editar un producto después no
   cambia pedidos existentes. `OrderItem` no tiene
   setters y es `@Immutable`: Hibernate no emite `UPDATE` aunque se altere el
   campo (`OrderMappingIT.orderItemsAreImmutableOnceStored`).
7. **El total lo calcula el servidor.** El cliente manda ids y cantidades;
   precios, descuentos y total salen de la base. El cálculo (`OrderTotals`)
   es `BigDecimal` exacto y rechaza lo que no entra en `NUMERIC(12,2)`
   (`OrderTotalsTest`). El contrato de entrada (`PlaceOrderCommand`) no tiene
   campos de precio ni total (`PlaceOrderCommandContractTest`). El servicio
   de alta (`OrderPlacementService`) toma precios y nombres de la base y
   calcula el total con `OrderTotals`, aunque el precio cambie después
   (`OrderPlacementIT.lineSnapshotsAndTotalsComeFromDatabasePrices`,
   `eachOrderSnapshotsThePriceReadAtItsOwnPlacementTime`).
8. **Dinero en `BigDecimal` / `NUMERIC(12,2)`**, nunca `double` ni `float`.
   Sumas y reportes se agregan en SQL, no cargando listas a memoria.
9. **Número de pedido legible** (`CETO-000123`) separado del id interno: es
   lo que viaja en el WhatsApp para que la clienta lo encuentre en el panel.
   `OrderNumber.format` usa `Locale.ROOT` y no trunca por encima de 999999
   (`OrderNumberTest`). El número sale de `order_number_seq` y la base lo
   protege con `orders_order_number_uk` y `orders_order_number_ck`
   (`OrderConstraintsIT`). La secuencia se consume antes del insert y no se
   devuelve: un pedido fallido o perdedor deja un hueco; el número es legible
   y creciente, no correlativo sin huecos.
10. **Checkout idempotente**: el front manda una clave de idempotencia por
    intento; un doble tap no crea dos pedidos ni reserva dos veces. La huella
    del pedido (`OrderRequestFingerprint` v1, SHA-256 de la forma canónica
    normalizada, sin la clave ni precios) está fijada por un valor de oro en
    `OrderRequestFingerprintTest`: no cambiar la forma canónica sin subir la
    versión. La clave es `UUID` con `UNIQUE` (`orders_idempotency_key_uk`) y la
    huella un `VARCHAR(64)` hexadecimal en minúsculas (`orders_fingerprint_ck`),
    ambos probados en `OrderConstraintsIT`. El reintento con la misma clave y
    el mismo contenido devuelve el pedido existente (aunque el producto se haya
    desactivado, el stock se haya agotado o el pedido esté cancelado); con otro
    contenido, `IdempotencyConflictException` (D-20261006-11, D-20261007-04).
    La búsqueda por clave va antes de tocar catálogo o stock; si dos pedidos
    compiten, el perdedor choca con `orders_idempotency_key_uk` al insertar la
    fila (antes de reservar) y repite la búsqueda en otra transacción. Pruebas:
    `OrderIdempotencyIT` (reintentos secuenciales y ráfagas con la misma clave:
    `sameKeyBurstWithTheSamePayloadCreatesOneOrderAndReservesOnce`,
    `sameKeyBurstOnTheLastUnitStillCreatesOneOrder`,
    `sameKeyBurstWithDifferentPayloadsKeepsTheWinnerAndRejectsTheOthers`,
    `sameKeyBurstWhoseWinnerRollsBackLeavesNoOrderAndDoesNotBurnTheKey`).

### Checkout, WhatsApp y envío

11. **Sin cuentas de compradoras** (D-20261006-01): checkout como invitada.
    Obligatorios hoy: nombre, teléfono y dirección (más campos pendientes,
    §8). El teléfono se normaliza a E.164 (`+549...`) al escribir: con eso se
    agrupa el historial de cada compradora.
12. **El pedido se registra ANTES de abrir WhatsApp.** Al confirmar, el
    servidor responde con el número de pedido y el front arma
    `https://wa.me/<número>?text=<detalle url-encoded>`. Si WhatsApp no
    abre, el pedido existe igual y la clienta lo ve en el panel.
13. **Sin API de WhatsApp Business** (D-20261006-02): Meta cobra por mensaje.
    Toda notificación automática va por mail.
14. **El envío no se calcula en la web** (D-20261006-03): la compradora elige
    "Córdoba Capital" (Uber Moto) o "Resto del país" (Correo Argentino) y la
    web aclara "costo de envío a coordinar por WhatsApp".

### Cupones y promociones

15. **Un cupón se valida en el servidor** al confirmar el pedido: vigencia,
    topes de uso y producto aplicable. El conteo de usos se incrementa en la
    misma transacción que crea el pedido. Una promoción por tiempo limitado
    tiene inicio y fin en UTC.

### Reseñas

16. **Moderación previa** (D-20261006-04): una reseña nace `PENDIENTE` y
    solo las `APROBADA` son públicas. Campos: estrellas (1–5), texto y
    nombre corto.
17. **La carga es anónima, así que tiene topes**: captcha verificado en el
    servidor (Cloudflare Turnstile) y rate limit por IP y por teléfono.
    **[guarda pendiente]**
18. **Prohibido cargar reseñas inventadas**, en código, seeds o fixtures que
    lleguen a producción. Los testimonios de Instagram se usan solo con
    permiso de cada persona. Guarda parcial: `NoSeedDataInMigrationsTest`
    detecta `INSERT INTO`, `COPY` y `MERGE INTO` en las migraciones SQL (sin
    contar comentarios) y rechaza migraciones Java; no prueba que no haya
    reseñas inventadas en código o fixtures, eso queda para la revisión.

### Notificaciones

19. **Ningún mail se manda dentro de una transacción** (outbox, §3). Mail a
    la clienta por cada pedido nuevo; mail a la compradora en cada cambio de
    estado, si dejó email (ver conflicto en §8). Proveedor SMTP o API con
    plan gratuito.

### Mercado Pago (solo si la clienta lo confirma)

20. **El webhook se verifica**: firma, idempotencia por id de evento,
    monto y referencia del pedido. Si es válido, pasa el pedido a `PAGADO`
    por la máquina de estados. Ninguna llamada a MP ocurre dentro de una
    transacción que sostiene un lock.

### Tiempo

21. **Persistencia y cálculo en UTC; la hora local es presentación**
    (`America/Argentina/Cordoba`). Nunca `LocalDateTime.now()` sin zona: se
    usa `Instant` o `Clock` inyectado (los tests lo controlan). Guarda:
    `ArchitectureTest.noSystemClock` prohíbe, fuera de `ClockConfig`, toda
    sobrecarga de `now` en clases de `java.time` salvo `now(Clock)`,
    `Clock.system*`, `ZoneId.systemDefault()`, `TimeZone.getDefault()`,
    `System.currentTimeMillis()`, `new Date()` y `new GregorianCalendar()`
    sin argumentos y `Calendar.getInstance()`. Es una guarda de mejor
    esfuerzo sobre los puntos de entrada comunes, no una garantía de que
    ningún código pueda llegar al reloj del sistema (por ejemplo vía otras
    librerías o reflexión). Formas permitidas: el `Clock` inyectado
    (`clock.instant()`, `X.now(clock)`, `X.now(clock.withZone(...))`) y
    `new Date(long)`.

## 5. Seguridad

- **Una sola cuenta: la de administración.** Contraseña con BCrypt o
  Argon2, login con rate limit y bloqueo temporal, sesión en cookie
  `HttpOnly` + `Secure` + `SameSite`. Nunca un token en `localStorage`.
- **Configuración de producción que falla cerrada**: sin secretos por
  defecto en el repo, CORS sin `*`, docs de la API apagados. Un proceso con
  configuración inválida no arranca. Hecho para `OrderProperties`
  (`OrderPropertiesValidationTest`: valores fuera de rango, ausentes o en blanco,
  incluido el bloque `expiry` entero, frenan el arranque); el resto de la configuración **[guarda pendiente]**
- **Todo parámetro numérico de la API lleva mínimo Y máximo**
  (`@Min`/`@Max`, cantidades, paginado, estrellas). Un solo lado deja un 500
  alcanzable. Las propiedades de `ceto.orders.*` ya cumplen la regla
  (`OrderPropertiesValidationTest`); falta la guarda para los parámetros de la API.
- **Entrada hostil**: el texto libre que se publica (reseñas, nombre corto,
  productos) rechaza caracteres de control y se escapa al renderizar.
  Imágenes por magic bytes, con tope de bytes y de píxeles; nunca SVG.
  Asuntos de mail sin CRLF.
- **Errores neutros hacia afuera**: violación de restricción → 409 genérico;
  sin stack traces ni mensajes crudos de validación al usuario final.
- **Datos personales (Ley 25.326)**: se pide solo lo necesario; teléfono,
  dirección y email no van a los logs.

## 6. Legal y contenido (regla dura para la IA)

- **Los textos de salud, beneficios y resultados los provee y aprueba la
  clienta.** El desarrollador y la IA no los redactan, no los amplían y no
  los "mejoran".
- **Prohibido generar o ampliar promesas de resultados**: pérdida de peso
  garantizada ("2 a 5 kg por semana", "12 a 15 kg por mes"), "quemador de
  grasa", "sin efecto rebote", sellos de certificación sin respaldo. Chocan
  con las reglas de publicidad de suplementos dietarios (ANMAT) y pueden ser
  riesgosas para la salud. Si un texto así aparece en un pedido de cambio,
  se frena y se avisa.
- **Datos que provee la clienta** (no se inventan placeholders creíbles):
  RNPA/RNE de ANMAT y foto del rótulo, fabricante o importador, respaldo del
  sello "Lab tested", contraindicaciones (contiene cafeína y guaraná).
- **Venta solo a mayores de 18**, con advertencias de uso visibles.
- **Páginas obligatorias**: términos y condiciones (razón social, CUIT,
  domicilio de la clienta), política de privacidad, política de cambios y
  devoluciones, y botón de arrepentimiento (defensa del consumidor, venta
  online en Argentina).

## 7. Diseño y UI

- **Mobile-first**: se diseña y se prueba primero en un ancho de celular.
- **Marca**: "Ceto BHB"; rosa/fucsia, blanco y negro. Toda combinación de
  texto sobre fondo cumple contraste WCAG AA (el fucsia sobre blanco se
  verifica, no se asume).
- **Nada que se note hecho con IA**: sin ilustraciones ni fotos generadas,
  sin gradientes y emojis genéricos de plantilla. Fotos reales del producto.
  El logo lo diseña una persona.
- Cards de producto: foto, nombre, precio (con tachado si hay oferta),
  estado de stock y "agregar al carrito".
- Imágenes en WebP con tamaños responsivos y lazy loading. Feed de Instagram
  embebido y botón flotante de WhatsApp.

## 8. Qué cuenta como verde (y qué no)

- **Verde con H2 o con mocks no prueba concurrencia, locks ni restricciones
  de la base.** Todo lo que toque stock, estados, reservas, jobs o
  migraciones se prueba contra Postgres real con Testcontainers.
- **El checkout tiene prueba de ráfaga**: con stock de 1 unidad, N compras
  simultáneas → 1 éxito, N−1 conflictos, cero 5xx, `reserved` correcto al
  final. Lo mismo para N confirmaciones idénticas con la misma clave de
  idempotencia. Existen: `OrderStockBurstIT` (última unidad) y
  `OrderIdempotencyIT` (misma clave); el pool de Hikari del perfil `test` es
  mayor que los hilos para que la contención sea de locks y no del pool.
- **Un bug de producción se reproduce con un test antes de arreglarse**, con
  fecha y síntoma en el comentario.
- **Un pipe a `tail` esconde el exit code.** `./mvnw clean verify | tail -5` da el
  estado de `tail`. Los comandos del gate se encadenan sin pipe.
- **CI es la verdad.** Si local pasa y CI falla, la diferencia es el bug.
- No se apilan builds y suites pesadas a la vez en la máquina de
  desarrollo: la degradan y falsean tiempos.

## 9. Pendientes con la clienta y huecos conocidos

No se implementa nada de esta lista asumiendo una respuesta: se pregunta.

- **Conflicto en el brief**: los mails a la compradora por cambio de estado
  necesitan su email, pero el email no está entre los datos obligatorios
  del checkout. Hay que decidir si es obligatorio u opcional.
- Pagos: transferencia (¿alias/CBU en la web?), Mercado Pago o tarjeta,
  cuotas, efectivo contra entrega y tipo de factura.
- El "plan" (alimentario, rutina, seguimiento): qué incluye, si se vende
  aparte del frasco y cómo se entrega.
- Composición y precio de cada combo; monto mínimo de compra (se supone que
  no hay).
- Datos obligatorios del checkout además de nombre, teléfono y dirección
  (DNI, email, CP, ciudad).
- Logo nuevo y fotos reales (las placas actuales son generadas con IA).
- Píxel de Meta y analítica.
- Titularidad del dominio y del hosting; alcance y precio del mantenimiento
  mensual y del desarrollo.
- Permiso de cada persona para usar los testimonios de Instagram.
- Datos legales y del producto (§6).
- Tope de unidades por línea (10, D-20261007-02) pendiente de OK de la
  clienta. En el cambio del checkout: límite de pedidos `PAGO_PENDIENTE`
  por teléfono e IP, porque una reserva sin pago bloquea stock 48 h.
- Del lado técnico, todavía no existen: CI, pre-commit, backups ni las demás
  guardas marcadas como pendientes.

## 10. Compuertas de proceso

Objetivo; se activan a medida que existe el código. **[pendiente]**

- Backend: `./mvnw clean verify` (compila, tests unitarios e integración con
  Testcontainers, ArchUnit). Cero warnings del compilador como línea base.
  Siempre con `clean`: un `target/classes` viejo conserva el
  `META-INF/spring.factories` y puede ocultar un registro eliminado (la
  guarda de esquema depende de él).
  Ya existe y corre con Postgres 16 real, con ArchUnit incluido.
- Frontend: `npm run lint`, `npm run typecheck`, `npm test`,
  `npm run build`.
- CI (GitHub Actions) corre lo mismo, con los mismos comandos, en cada push
  y PR. Ningún merge con CI en rojo.
- Toda migración de Flyway es inmutable una vez mergeada: un cambio es una
  migración nueva.
