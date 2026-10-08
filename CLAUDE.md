# Ceto BHB — arquitectura, reglas duras y forma de trabajar con IA

Tienda online de una sola vendedora (Córdoba Capital) para el suplemento
dietario Ceto BHB. Venta de punta a punta en la web, cierre y atención por
WhatsApp. Backend: Java 21 + Spring Boot 4.1 + Spring Data JPA + Flyway +
Postgres. Frontend: Next.js (App Router) + TypeScript + Tailwind. Infra:
Docker Compose en un VPS (postgres, backend, frontend, caddy).

Este archivo se carga en cada sesión y es contexto autoritativo para
cualquier cambio. Está escrito para el asistente de IA tanto como para las
personas. **Estado al 2026-10-08: el backend (`backend/`, Spring Boot 4.1.1)
tiene la guarda de ddl-auto, las reglas ArchUnit, el catálogo, el stock, el
dominio y el ciclo de vida de los pedidos (reserva, pago, cancelación y
vencimiento por job), la normalización del teléfono y la API pública de la
tienda y del checkout (3 rutas, documentadas a mano en `docs/API.md`, con
límites por IP y por teléfono, tope de cuerpo, cabeceras de seguridad y
límites de recursos), todo probado contra Postgres real. No existen el panel
de administración, el frontend ni el resto de las áreas de §4.** Por eso
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
  falla ante una violación. Reglas de la capa HTTP en el mismo lugar:
  `WEB_TYPES_ONLY_IN_CONTROLLER_PACKAGES` (controllers y advice solo en
  `..controller..`), `SERVICES_AND_DOMAIN_ARE_WEB_FREE` (sin web ENTRANTE en
  `..service..`/`..domain..` de una funcionalidad: servlet, `web.bind`,
  `web.servlet`, `boot.web`/`boot.webmvc`, `org.springframework.security` (quién está
  autenticado lo decide la cadena de filtros y el controller, no un service; fixtures
  `SecurityAwareService` y `SecurityAwareDomain`, con llamada y con referencia a
  método), `ResponseEntity`/`HttpStatus`/
  `ProblemDetail`/`RequestEntity`; los clientes salientes como `RestClient` se
  permiten y un paquete `service` bajo `..web..` no cuenta), `NO_CROSS_ORIGIN`
  (ni `@CrossOrigin`, ni `CorsFilter`/`CorsRegistry`/`CorsRegistration`/tipos
  `web.cors`, ni `addCorsMappings`), `NO_FUNCTIONAL_ENDPOINTS` (ni
  `RouterFunction` ni `ServerResponse`) y `REQUEST_INPUTS_ARE_BOUNDED`
  (sección 5). La API pública se documenta a mano en `docs/API.md` (sin
  springdoc, D-20261007-18) y `ApiDocConsistencyTest` hace fallar el build si un
  ejemplo deja de ser idéntico a su golden, si un golden no está citado o si un
  `code` o `reason` del código no figura en el documento. `EndpointInventoryIT` fija el
  inventario: las 3 rutas públicas, las de administración (`EndpointInventory.ADMIN_ROUTES`,
  hoy ninguna; cada una se llama sin sesión y debe dar el golden 401, generado
  desde los mapeos) más `ANY /error` de `BasicErrorController`
  (permitido por tipo de handler, no por ruta), todos los `HandlerMapping` por
  clase (los de URL, welcome page y `RouterFunctionMapping` vacíos), sin
  `RouterFunction`, sin mapeos de recursos ni actuator, y todo otro camino es
  el 404 estándar, salvo `/api/admin/*`, que ya no es el 404 estándar: lo
  responde Security (401, 403 o 400, ver §5); no hay `CorsFilter`. La regla de capas ya no usa `allowEmptyShould` ni
  capas opcionales porque existen controllers (`StorefrontController`): si un
  renombre o movimiento deja una capa vacía, el build falla
  (`layeringRuleFailsWhenNoLayerHasAnyClassSoARenameCannotEmptyIt`) y los
  fixtures conformes traen las tres capas (`clean/catalog/controller`). Las
  reglas de `@Transactional` en métodos y de acceso al
  repository tampoco lo usan porque `CatalogService` y `ProductRepository`
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
- **Auditoría de las acciones de administración** (D-20261008-11): la tabla
  `admin_audit_log` (migración `V4__create_admin_audit_log.sql`) solo admite
  conjuntos cerrados (acción, tipo de entidad, motivo, valores numéricos): sin
  texto libre, sin nombres, teléfonos, direcciones ni IP, y es de solo
  inserción (trigger por sentencia: `UPDATE`, `DELETE` y `TRUNCATE` fallan
  aunque no toquen filas). Guardas: `AdminAuditLogConstraintsIT` (SQL crudo
  sobre Postgres real), `AuditActionIT` (los enums de Java y los `CHECK` son el
  mismo conjunto), `AuditEntryTest` y `AdminAuditServiceIT` (`record` es
  `MANDATORY`: corre en la transacción de quien llama, con el reloj inyectado;
  un rollback se lleva la fila; el repositorio solo expone `save`; ninguna
  columna de texto contiene un centinela de datos personales). Evidencia por
  mutación (2026-10-08): quitar el trigger hizo fallar los cuatro tests de
  solo-inserción de `AdminAuditLogConstraintsIT`. Límite: el dueño de la tabla
  puede desactivar o borrar el trigger; frena errores y bugs de la aplicación,
  no a un dueño de la base hostil. Todavía no hay rutas ni fachada que escriban
  en ella (ver `docs/DECISIONES.md`).
- **Lectura de pedidos para administración** (`OrderQueryService`, solo
  servicio, todavía sin rutas): la lista es paginada por clave (id descendente,
  `size` 1..50, `after` 1..2^53-1, pedida con `size + 1` filas para saber si hay
  otra página) y el detalle sale de proyecciones, sin lock y sin dejar la
  entidad `Order` en el contexto de persistencia. La búsqueda es exacta, nunca
  `LIKE`: `OrderSearchKey` (`OrderSearchKeyTest`) lee `CETO-000123` como número,
  1 a 9 dígitos como número y también como teléfono, y el resto como teléfono
  E.164 por `PhoneNumberNormalizer`; el prefijo `CETO-` se acepta en cualquier
  combinación de mayúsculas y minúsculas, pero el relleno de ceros tiene que ser
  el canónico (`CETO-0000123` y `CETO-0` no dan clave); lo que no se puede leer
  da una página vacía. El texto buscado, el nombre, el teléfono y la dirección
  no se loguean (`OrderQueryPiiLoggingIT`: lista sin búsqueda, por teléfono, por
  número, texto ilegible y detalle, con textos centinela; evidencia por
  mutación: un `log.info` del texto buscado en `OrderQueryService.list` hace
  fallar el test). La fila de la lista no tiene dirección
  (`OrderPageRepositoryIT`, `OrderQueryServiceIT`). `secondsLeft` es nulo salvo
  en `PAGO_PENDIENTE` y 0 si el plazo pasó y el job no corrió; `overdue` es
  verdadero solo en `PAGO_PENDIENTE` con `now >= reserved_until` (el mismo borde
  inclusivo del job) y distingue "pasó el plazo" de "la cuenta regresiva llegó
  a 0", en el detalle y en la fila de la lista (`ReservationCountdownTest`,
  `OrderQueryServiceIT`).
- **Acciones de administración sobre pedidos** (`AdminOrderService`, solo
  servicio, todavía sin rutas): pagar, despachar, entregar y cancelar llaman a
  `OrderLifecycleService` (la máquina de estados y el stock siguen ahí), escriben
  una fila de auditoría y devuelven el detalle, todo en una transacción: el
  motivo de cancelación es `CANCELLED_BY_ADMIN`. La fila solo existe si la
  transición se confirmó, y una transición no se confirma sin su fila: si el
  insert de auditoría falla, o la transacción falla al hacer commit, no queda ni
  el cambio de estado, ni el de stock, ni la fila (`AdminOrderActionsIT`, con
  triggers de prueba). Una arista ilegal o un id desconocido no escriben nada. El
  orden de locks no cambia (la auditoría no toma locks). Dos pagos simultáneos del
  mismo pedido: uno gana y el otro recibe `IllegalOrderTransitionException`, el
  stock se descuenta una vez (`AdminOrderDoublePayIT`); admin y comprador sobre
  los mismos productos base, en orden opuesto, sin deadlock en 20 iteraciones
  (`AdminLockOrderingIT`). Evidencia por mutación (2026-10-08): quitar las
  llamadas a `record` hizo fallar siete tests de `AdminOrderActionsIT`; leer el
  pedido con `findById` en vez de `FOR UPDATE` hizo fallar
  `AdminOrderDoublePayIT` en la iteración 0; bloquear las filas de stock en
  `release` en orden descendente hizo fallar `AdminLockOrderingIT` (deadlock
  `40P01`); esa prueba sostiene solo el orden de locks, porque sus pedidos no
  están vencidos. Una acción de administración contra el job de vencimiento
  sobre el MISMO pedido ya vencido deja un solo ganador, con fila de auditoría
  solo si ganó la administración y el stock sin deriva, y el único fallo
  tolerado es `55P03`, nunca `40P01` (`AdminVsExpiryRaceIT`). Con la fila
  retenida por la acción, el job la saltea sin esperar (prueba determinista de
  ese mismo archivo; evidencia por mutación: quitar `FOR UPDATE SKIP LOCKED` de
  `lockExpiredBatch` la hizo fallar). La carrera sin sincronía no detecta esa
  mutación por sí sola: `@Version` y la relectura del respaldo por pedido
  curan la ventana. Una cancelación de administración mezclada con reintentos
  de la misma clave de idempotencia deja los reintentos como `replayed=true` (o
  503 `SERVICE_BUSY`), una sola fila `ORDER_CANCELLED` y el stock cuadrado
  (`AdminCancelVsReplayIT`). Las acciones no loguean nombre, teléfono ni
  dirección, ni en éxito, ni en transición ilegal, ni con el insert de
  auditoría fallado (`AdminOrderActionsPiiLoggingIT`).
- **Stock de administración** (`AdminInventoryService`, solo servicio, todavía
  sin rutas): `adjust(productId, delta, reason)` llama a
  `InventoryService.adjustBy`, escribe una fila `STOCK_ADJUSTED` con el stock
  antes y después y el motivo del conjunto cerrado (sin nota), todo en una
  transacción; un delta en 0 o fuera de ±10000 (D-20261008-10), un descuento
  por debajo de `reserved`, un resultado por encima de `Integer.MAX_VALUE`
  (`STOCK_OUT_OF_RANGE`), un combo, un producto sin fila de stock o un id
  desconocido no escriben nada, y si falla el insert de auditoría vuelve atrás
  el stock (`AdminInventoryServiceAdjustIT`, que también prueba que un base
  desactivado se puede ajustar con su fila de auditoría). Los motivos del
  conjunto cerrado (`COUNT_CORRECTION`, `RESTOCK`, `DAMAGED_OR_LOST`, `RETURN`,
  `OTHER`) son la propuesta D-20261008-18, pendiente de OK de la clienta. La
  fila de stock de un producto base se crea al crearlo, en cero
  (D-20261008-15). La vista de stock
  (`AdminInventoryService.stockView`, tope `ceto.admin.max-listed-products`, 1 a
  500) y la conciliación (`reconciliation`, tope
  `ceto.admin.max-reconciliation-rows`, 1 a 1000) son lecturas sin locks ni
  filas gestionadas: combo derivado del producto base, base sin fila listada
  como no configurada y sin cifras (`AdminStockViewIT`, `AdminReconciliationIT`,
  `AdminInventoryServiceCapsTest`; los topes y sus valores por defecto,
  `AdminPropertiesCapsValidationTest`, que además exige que el fallo de
  arranque nombre la propiedad). Ninguna trunca en silencio: piden una fila de
  más que el tope y devuelven `CappedList` con `truncated`, verdadero solo si
  había más filas (`AdminCappedReadsTruncationIT` contra las consultas reales
  con tope 2: con 3 filas, las dos primeras por id y `truncated`; con tope igual
  a la cantidad, no). Cada línea de la vista lleva `sellable`, verdadero solo si
  el producto está activo y, en un combo, también su base: un combo sobre una
  base desactivada conserva las cifras pero no es vendible
  (`AdminStockViewIT.aComboOverAnInactiveBaseKeepsItsFiguresButIsNotSellable`).
  La vista lee en dos sentencias bajo READ COMMITTED (productos y luego
  cifras), así que un cambio concurrente puede mezclar instantáneas; se acepta
  para una pantalla de lectura que se refresca. El ajuste toma solo el lock de la fila
  de stock, así que no agrega aristas al orden de locks: lo prueba con
  retención determinista `StockAdjustBurstIT` (una compra espera mientras un
  ajuste retiene la fila y un ajuste espera a una fila retenida y recién
  después verifica contra el `reserved` confirmado; antes de afirmar que la
  sonda no terminó, `LockProbe` consulta `pg_stat_activity` y `pg_locks` con un
  plazo de 5 s hasta ver un backend esperando un lock; una fila retenida más
  de `lock_timeout`, que la prueba verifica en `5s`, da `55P03` sin cambios ni
  auditoría) y, con ráfagas, el
  estado final de ajustes contra compras de las últimas unidades (los
  contadores finales son exactamente los de las operaciones que tuvieron éxito
  y nunca se tolera `40P01`) y `OrderLockOrderingIT` con `adjustBy` mezclado.
  Evidencia por mutación (2026-10-08): leer la fila con `findById` en vez de
  `FOR UPDATE` hizo fallar las dos pruebas de retención y la ráfaga; quitar el
  chequeo de Java de `adjustBy` hizo fallar la prueba de retención y la ráfaga
  (llega la violación del CHECK en vez de `InsufficientStockException`); hacer
  que `adjustBy` tome además la fila `productId - 1` hizo fallar
  `OrderLockOrderingIT` con `40P01`; quitar `record` hizo fallar tres tests de
  `AdminInventoryServiceAdjustIT`; pedir solo `cap` filas en vez de `cap + 1`,
  ignorar la base en `sellable`, quitar el chequeo de rango de `adjustBy` y
  rechazar productos inactivos en `adjustBy` hicieron fallar las pruebas
  correspondientes (2026-10-08). Los caminos de stock no loguean nombres de
  producto ni datos de la compradora (`AdminStockPiiLoggingIT`; evidencia por
  mutación: imprimir los nombres en la vista lo hizo fallar).
- **Catálogo de administración** (`AdminCatalogService`, solo servicio,
  todavía sin rutas): crear un producto base crea en la misma transacción su
  fila de stock en 0 (D-20261008-15) y la fila `PRODUCT_CREATED`, así que la
  tienda lo muestra
  `SOLD_OUT` sin error y `AVAILABLE` tras reponer; la creación vive en la
  fachada y no en `CatalogService` porque `inventory` ya depende de `catalog`
  (`AdminCatalogCreateBaseIT`, con un trigger de prueba que hace fallar el
  insert del stock). Un combo no crea fila de stock, su disponible se deriva y
  `CatalogRules.requireComboUnits` acota las unidades de 2 a 100
  (`AdminCatalogCreateComboIT`, `CatalogRulesTest`); la base y el N de un combo
  son inmutables (D-20261008-14: la edición no recibe el vínculo) y su precio es
  libre (D-20261008-31, propuesta pendiente de OK de la clienta). El nombre no
  es único y no hay idempotencia en la creación (D-20261008-30). Crear un combo
  y reactivar uno leen el producto base con `FOR SHARE`
  (`ProductRepository.findByIdForShare`), de modo que una desactivación
  concurrente de la base o termina antes (y el combo se rechaza con
  `INVALID_COMBO_LINK`) o espera: `AdminComboBaseRaceIT` retiene la fila de la
  base desactivándola y verifica con `pg_stat_activity`/`pg_locks`
  (`LockProbe`, plazo de 5 s) que la creación y la reactivación esperan un
  lock; al confirmar la retención fallan con `INVALID_COMBO_LINK`, sin combo
  creado ni activado y sin fila de auditoría. Las filas de producto no se
  lockean mientras se retiene un lock de pedido o de stock, así que el orden
  pedido, luego stock por id no gana ninguna arista; varios combos sobre una
  misma base comparten el lock sin bloquearse entre sí, y el `FOR KEY SHARE` de
  la clave foránea solo no alcanza, porque una actualización que no toca la
  clave no choca con él. Dos ediciones
  desde la misma versión, o una edición contra una desactivación, también se
  prueban con retención: el perdedor espera la fila, falla con
  `OptimisticLockingFailureException` y quedan persistidos y auditados solo los
  cambios del ganador (`AdminComboBaseRaceIT`). La edición escribe una fila
  por grupo de campos que cambió (`PRICE_CHANGED` con precio antes y después,
  `PRODUCT_UPDATED`); una edición sin cambios no escribe filas ni sube la
  versión, una versión vieja no cambia nada, y activar o desactivar escribe
  una fila solo si cambió el estado; desactivar no toca los pedidos pendientes,
  que siguen pagables y cancelables, y un pedido conserva su precio tras una
  edición (`AdminCatalogUpdateIT`, que también fija que cambiar solo el precio
  tachado escribe `PRODUCT_UPDATED` y no `PRICE_CHANGED`, y que reactivar un
  combo con la base inactiva se rechaza sin fila de auditoría). Listar incluye
  inactivos y combos con su tope y no trunca en silencio: pide una fila de más
  y devuelve `CappedList` con `truncated` (`AdminCatalogListIT`,
  `AdminCatalogListTruncationIT` con tope 2 contra la consulta real,
  `AdminCatalogServiceCapsTest`). Si falla el
  insert de auditoría vuelve atrás el cambio. Evidencia por mutación
  (2026-10-08): quitar la creación de la fila de stock hizo fallar tres tests
  de `AdminCatalogCreateBaseIT`; quitar las llamadas a `record` hizo fallar seis
  tests de las tres clases de creación y edición; auditar siempre el precio o
  la activación, aunque no cambien, hizo fallar tres de `AdminCatalogUpdateIT`;
  leer la base sin lock hizo fallar las dos pruebas de retención de combo
  (ninguna espera aparece en `pg_locks`); quitar `@Version` de `Product` hizo
  fallar las tres carreras de edición; quitar el chequeo de reactivación hizo
  fallar la prueba de reactivación; pedir solo `cap` filas hizo fallar la prueba
  de truncamiento de la lista. Los caminos de catálogo no loguean nombres de producto ni datos de la
  compradora (`AdminCatalogPiiLoggingIT`; evidencia por mutación: imprimir el
  nombre al crear lo hizo fallar).

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
   (`InventoryServiceIT`). El ajuste de administración es relativo
   (`InventoryService.adjustBy`, D-20261008-10): suma en `long`, rechaza con
   `InsufficientStockException` lo que dejaría `on_hand` por debajo de
   `reserved`, y un combo o un producto sin fila de stock no se ajusta
   (`StockNotAdjustableException`, nunca se crea la fila). Guardas:
   `InventoryStockAdjustByTest`, `InventoryServiceAdjustByIT`; evidencia por
   mutación (2026-10-08): quitar el chequeo de Java hizo fallar
   `InventoryServiceAdjustByIT` con una violación de `inventory_stock_rule1_ck`
   (la base frena, pero no con `InsufficientStockException`) y fallar el test
   unitario `InventoryStockAdjustByTest` porque no se lanzó ninguna excepción.
   Nadie fuera de `InventoryService` puede llamar al ajuste absoluto `adjust`,
   ni directamente ni con una referencia a método (`AbsoluteAdjustNotReachableTest`).
   Las lecturas de administración no bloquean ni dejan filas gestionadas:
   `InventoryService.levels` (contadores por producto, `InventoryServiceLevelsIT`)
   y `InventoryService.reservationMismatches` (una sola sentencia de solo lectura
   que compara `reserved` con las líneas de pedidos `PAGO_PENDIENTE`, con
   `reserved` nulo si falta la fila de stock, `ReservationMismatchIT`, que fija
   la foto del combo, la exclusión de pagados y cancelados, el vencido aún no
   cancelado y el orden por id). Ambas rechazan entradas sin cota con
   `IllegalArgumentException` (límite de 1 a 1000; hasta 1000 ids,
   `InventoryServiceBoundsTest`).
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
   (`cartWithBaseAndItsComboAggregatesIntoOneEntry`). La vista de tienda
   (`StorefrontService`) expone solo un estado derivado, `AVAILABLE` o
   `SOLD_OUT`, nunca cifras de stock (D-20261007-20, pendiente de OK de la
   clienta): `StockStatusTest` (combo por piso, fila de stock ausente =
   agotado) y `StorefrontServiceIT`; en HTTP, `StorefrontHttpIT` fija el JSON
   exacto con goldens (`contract/storefront-*.json`, sin campos de stock). Esa lectura no bloquea filas ni deja filas
   de stock en el contexto de persistencia, así que un lock posterior en la misma
   transacción lee la base y no una copia vieja (`InventoryAvailabilityIT`:
   `doesNotWaitForARowLockHeldByAnotherTransaction`,
   `leavesNoManagedStockRowSoALaterLockQueryReadsTheCommittedValue`; con
   `findAllById` este último falló, evidencia del 2026-10-07).
3. **Lock pesimista antes de reservar.** `SELECT ... FOR UPDATE` sobre la
   fila del producto base antes de leer disponibilidad. Prohibido "verificar
   y luego actuar" sin lock. Varias filas se lockean siempre en orden de id
   para no generar deadlocks. Ráfaga de la última unidad: `OrderStockBurstIT`
   (`lastUnitGoesToExactlyOneOfManyConcurrentBuyers`) prueba que no hay
   sobreventa y que `reserved` queda correcto, pero por sí sola no prueba el
   lock (`@Version` y el CHECK de stock también la frenan). La prueba que falla
   sin `FOR UPDATE` es `oppositeOrderMultiProductCartsNeverDeadlock` (evidencia
   por mutación, S9b: sin el lock en `lockByProductId` fallaron 11 de 12 hilos
   con `ObjectOptimisticLockingFailureException`). El ORDEN de locks (pedido
   primero, luego filas de stock por id ascendente en toda ruta: reserva,
   pago, cancelación y vencimiento) lo prueba `OrderLockOrderingIT`
   (`placementsPaymentsCancellationsAndExpiryOnSharedBaseProductsNeverDeadlock`):
   carritos con los productos en orden de id opuesto, un pago, una
   cancelación y dos corridas de vencimiento a la vez, 20 iteraciones sin
   deadlock. Evidencia por mutación (registrada el 2026-10-07): cambiar
   `InventoryService.release` para lockear las filas de stock en orden de id
   DESCENDENTE (en vez de ascendente) hizo fallar la misma ráfaga en la
   iteración 0 con `40P01 deadlock detected` de Postgres; con el orden
   ascendente restaurado el test pasa. Al crear un pedido, ANTES de la fila
   del pedido, se toma el advisory lock transaccional del teléfono de la
   compradora (`OrderRepository.tryLockBuyerPhone`, `pg_try_advisory_xact_lock`)
   para serializar el tope de pedidos pendientes por teléfono (regla 10). El
   lock NO espera (D-20261008-01): si otro checkout del mismo teléfono lo tiene,
   se lanza `CheckoutInProgressException` (503 `SERVICE_BUSY` con
   `Retry-After: 1`), así ningún pedido retiene una conexión del pool esperando.
   Orden completo: advisory(teléfono), fila del pedido, filas de stock
   ascendentes; solo la colocación toma ese lock y nunca espera por él, así que
   no cierra ciclos. Las demás esperas de lock están acotadas: cada conexión del
   pool arranca con `SET lock_timeout = '5s'` y
   `SET idle_in_transaction_session_timeout = '30s'`
   (`spring.datasource.hikari.connection-init-sql`; el segundo es el respaldo
   contra una transacción que retiene locks y dejó de hablar con la base).
   Vencido el `lock_timeout`, Postgres responde `55P03`, Spring lo traduce a
   `CannotAcquireLockException` y la API contesta 503 `SERVICE_BUSY` con
   `Retry-After: 5` sin dejar filas (`LockTimeoutIT`: fila de stock retenida por
   otra transacción; un teléfono ocupado responde 503 al instante; un tick del
   job de vencimiento tras un lock termina sin lanzar, corta el respaldo por
   pedido en el primer timeout y reintenta en el siguiente). Una ruta
   `JdbcTemplate` que tome locks recibe `UncategorizedSQLException`: el advice la
   mapea a 503 por su SQLState `55P03`. Las migraciones usan
   `SET LOCAL lock_timeout` (y `statement_timeout`), nunca `SET` a secas: un `SET`
   de sesión se queda en la conexión del pool y se filtra a los requests
   siguientes; `MigrationTimeoutScanTest` recorre `db/migration` y falla ante
   cualquier `SET [SESSION] lock_timeout|statement_timeout|idle_in_transaction_session_timeout`
   sin `LOCAL`.
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
   El vencimiento lo prueba `OrderExpiryIT`: borde inclusivo
   (`reserved_until <= now`), lotes acotados por antigüedad, corridas
   repetidas e idénticas, dos corridas concurrentes sin doble liberación,
   fila bloqueada por otra transacción salteada y carrera contra el pago.
   Libera desde los snapshots del pedido, con `OrderLifecycleService.expireDueOrders`
   (una transacción por lote; si el lote falla se revierte entero y se
   reintenta pedido por pedido, cada uno en su propia transacción: el pedido
   que sigue fallando se registra en el log con ERROR (id, clase de la
   excepción y SQLState si lo hay, sin datos personales ni nombres de producto)
   y queda `PAGO_PENDIENTE` hasta repararlo a mano, mientras el resto avanza,
   D-20261007-12; `OrderExpiryIT` lo prueba con una fila de stock borrada).
   Límite conocido: el pedido fallido se reintenta en cada ciclo (un ERROR por
   pedido fallido por ciclo) y, si hay tantos pedidos corruptos como el tamaño
   del lote en la ventana de los más antiguos vencidos, los más nuevos dejan de
   vencer hasta que se reparen a mano; el log de ERROR es la señal. Un
   ciclo del job repite lotes mientras vuelvan llenos (se corta en cuanto un
   lote tiene pedidos fallidos, con un WARN), hasta
   `ceto.orders.expiry.max-batches-per-tick` (1 a 100, 10 por defecto;
   `OrderExpiryJobDrainIT`). El job (`OrderExpiryJob`) solo se agenda
   si `ceto.scheduling.enabled` no es `false`; en tests no se agenda
   (`OrderExpiryJobSchedulingTest`, `OrderExpiryJobWiringIT`). La
   reconciliación de `reserved` contra los pedidos pendientes
   (`StockReconciliationIT`) cierra cada escenario.

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
   campo (`OrderMappingIT.orderItemsAreImmutableOnceStored`). La edición de
   administración de un producto (`CatalogService.update`, D-20261008-21) usa
   el bloqueo optimista `products.version` (migración `V5`, `@Version`): una
   edición basada en una lectura vieja falla con `StaleProductVersionException`
   y dos ediciones que compiten no se pisan (`ProductVersionIT`,
   `CatalogServiceAdminIT.twoConcurrentEditsFromTheSameReadNeverOverwriteEachOther`);
   cambiar el precio no toca las líneas de pedidos existentes y desactivar un
   producto con un pedido pendiente deja el pedido pagable y cancelable
   (`CatalogServiceAdminIT`). Una compradora nunca recibe el conflicto de una
   edición de administración: colocar pedidos mientras se edita el precio no
   produce ningún `OptimisticLockingFailureException` y cada línea queda con un
   precio efectivamente confirmado (`BuyerNeverSeesAdminEditConflictIT`;
   evidencia por mutación, 2026-10-08: hacer que `findForOrder` ensucie el
   producto hizo fallar el test con `ObjectOptimisticLockingFailureException`).
   Activar y desactivar (`setActive`) no recibe la versión y es idempotente
   (D-20261008-28); reactivar un combo con la base inactiva falla con
   `INVALID_COMBO_LINK` (`CatalogServiceAdminIT`). El nombre de un producto debe
   tener al menos un carácter visible y no admite separadores de línea o
   párrafo, uso privado ni puntos de código sin asignar (`CatalogRulesTest`).
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
    Por HTTP (`POST /api/checkout/orders`, cabecera `Idempotency-Key`): 201 al
    crear y 200 en el reintento, con el precio cobrado y sin `orderId`;
    `CheckoutHttpIT` y los goldens `contract/checkout-201.json` y
    `contract/checkout-200-replay.json`. Si a un producto base le falta la fila
    de stock, la API responde `PRODUCT_UNAVAILABLE` con los ids pedidos (nunca el
    id base de un combo) y deja un ERROR para la operadora:
    `StockNotConfiguredPlacementIT`; los errores del endpoint tienen un golden
    por código en `contract/error-*.json` (`CheckoutErrorsHttpIT`).
    La búsqueda por clave va antes de tocar catálogo o stock; si dos pedidos
    compiten, el perdedor choca con `orders_idempotency_key_uk` al insertar la
    fila (antes de reservar) y repite la búsqueda en otra transacción. Un
    reintento de la misma clave que alcanza al creador a mitad de su transacción
    no espera: recibe 503 `SERVICE_BUSY` con `Retry-After: 1` (D-20261008-01) y,
    reintentado después del commit, 200. Pruebas:
    `OrderIdempotencyIT` (reintentos secuenciales y ráfagas con la misma clave:
    `sameKeyBurstWithTheSamePayloadCreatesOneOrderAndReservesOnce`,
    `sameKeyBurstOnTheLastUnitStillCreatesOneOrder`,
    `sameKeyBurstWithDifferentPayloadsKeepsTheWinnerAndRejectsTheOthers`,
    `sameKeyBurstWhoseWinnerRollsBackLeavesNoOrderAndDoesNotBurnTheKey`).
    **Tope de pedidos pendientes por teléfono** (D-20261007-19,
    `ceto.orders.max-pending-per-phone`, 3 por defecto, 1..20): un pedido nuevo
    con 3 `PAGO_PENDIENTE` del mismo teléfono E.164 lanza
    `TooManyPendingOrdersException` (429 `TOO_MANY_PENDING_ORDERS`, sin teléfono
    ni cuenta) antes de reservar stock y de consumir `order_number_seq`. Orden:
    lock del teléfono, segunda búsqueda por clave (un reintento con el pedido ya
    creado es 200, nunca 429, aunque la compradora esté en el tope), conteo,
    alta. Pagado, cancelado y vencido liberan el cupo. Pruebas:
    `OrderPendingCapIT` (reglas secuenciales y el lock) y `OrderPendingCapBurstIT`
    (10 claves distintas del mismo teléfono: nunca más de 3 pedidos y el resto
    429 o 503, sin ningún otro fallo; evidencia por mutación: sin el lock
    —`tryLockBuyerPhone` siempre `true`— fallaron las 10 iteraciones de
    `distinctKeysOfOnePhoneInABurstNeverExceedTheCapAndTheRestAre429Or503`; sin la
    segunda búsqueda falla, de forma determinista,
    `OrderPlacementServiceTest.aRetryCommittedBetweenTheFirstLookupAndTheLockIsReplayedNotCounted`).
    Un teléfono ocupado responde 503 al instante (`OrderPendingCapIT`, `LockTimeoutIT`). Por HTTP: golden `contract/error-too-many-pending.json` y el
    reintento en el tope sigue siendo 200 (`CheckoutErrorsHttpIT`). Un
    `PAGO_PENDIENTE` vencido que el job todavía no canceló sigue contando
    (D-20261007-24, `OrderPendingCapIT`). El 429 se registra en DEBUG. Los ITs
    que colocan muchos pedidos usan `BuyerFixtures.freshBuyer()` (un teléfono
    por pedido), si no el tope y el lock los serializan.
    **Límite por IP** (D-20261007-17, D-20261007-22): `POST /api/checkout/orders` gasta
    un intento del cubo de la IP antes de leer el cuerpo (`CheckoutRateLimitInterceptor`,
    10 de golpe y uno cada 6 minutos, `ceto.web.checkout-rate-limit`); el 11.º es 429
    `RATE_LIMITED` con `Retry-After` (`RateLimitIT`, golden `contract/error-rate-limited.json`),
    en DEBUG y sin la IP. La clave sale solo de `getRemoteAddr()`; detrás del proxy de confianza
    (`TRUSTED_PROXIES`, solo CIDR privados, `RemoteIpValve`) es el cliente real y la cabecera de
    un par no confiable (127.0.0.2) se ignora (`ClientIpHttpIT`, puerto aleatorio). En macOS
    127.0.0.2 no existe por defecto: antes de correr `ClientIpHttpIT` hay que
    crearlo con `sudo ifconfig lo0 alias 127.0.0.2 up` (en Linux y Windows responde
    sin más). La aplicación rechaza `TRUSTED_PROXIES` en forma de regex y confiar
    en `127.0.0.0/8` o `169.254.0.0/16` abre todo el host o todo el enlace local,
    no solo al proxy; el arranque además exige `host-header`, `port-header` y
    `protocol-header-https-value` vacíos y `protocol-header` sin definir o
    `X-Forwarded-Proto` (`WebConfig`). Las variantes
    de ruta se fijan en `CheckoutPathLimitHttpIT`: con `;x=1` cuenta y con barra final, otra
    capitalización o `//` da 404 sin leer el cuerpo. Los GET de la tienda no se limitan.

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

- **Spring Security solo ve `/api/admin/*`** (D-20261008-05). El filtro
  `springSecurityFilterChain` se registra en el contenedor únicamente para esa
  ruta (`SecurityFilterScopeConfig`) y la cadena declara el mismo
  `securityMatcher`, así que la tienda y el checkout no pasan por el firewall
  ni por los escritores de cabeceras de Security: mismas cabeceras, sin
  cookies, sin token CSRF y las mismas respuestas ante `;`, `//` y `/./`.
  Guardas: `SecurityFilterScopeIT` y `SecurityFilterScopeServerIT` (mapeo del
  filtro en el contexto y en Tomcat, el firewall no ve una ruta pública),
  `PublicSurfaceUnchangedIT` y `PublicSurfaceUnchangedServerIT` (conjunto exacto
  de nombres de cabecera fijado en `PublicSurface`, sin `Set-Cookie`, variantes
  de ruta de la tienda) y `CheckoutPathLimitHttpIT` sin cambios. Evidencia por
  mutación (2026-10-08): mapear el filtro a `/*` hizo fallar 6 casos de
  `CheckoutPathLimitHttpIT`, 4 variantes de `PublicSurfaceUnchangedServerIT` y
  el test del firewall y del mapeo de `SecurityFilterScopeIT`.
  Bajo `/api/admin/` ninguna petición sin autenticar recibe un 2xx ni un handler
  (D-20261008-32): un GET responde 401, una mutación sin token CSRF responde 403
  (el CSRF corre antes que la autenticación), las grafías de ruta que el firewall
  rechaza responden 400 (`;`, `//`, `.` y `..`, barra o contrabarra codificadas) y
  las variantes de mayúsculas, que no entran a Security, caen en el 404 de rutas
  sin handler. Todavía no hay login, así que nadie puede autenticarse, y nunca
  se abre una sesión de servidor. Guardas, sobre un
  handler de prueba que vive solo en tests (`adminprobe.AdminProbeController`,
  fuera del árbol escaneado, así que `EndpointInventoryIT` sigue exacto):
  `AdminPathVariantsIT` y `AdminPathVariantsServerIT` piden 16 grafías de la ruta
  con GET y POST sin credenciales, fijan el estado exacto de cada una y exigen
  que la respuesta jamás lleve el cuerpo centinela, jamás sea 2xx y jamás fije
  `JSESSIONID`; `AdminProbeAccessIT` prueba con `user()` que el handler es
  alcanzable, para que esos rechazos no sean vacíos. `AdminAnonymousSessionIT` y
  `AdminAnonymousSessionServerIT` fijan, para GET, POST, PUT, PATCH y DELETE, que
  no hay `HttpSession` ni `JSESSIONID`: el token CSRF vive en la cookie
  `XSRF-TOKEN` (`csrf.spa()` con `CookieCsrfTokenRepository`, `Secure`,
  `SameSite=Strict`, sin `HttpOnly`), que `csrf.spa()` entrega en toda respuesta
  de admin, la 401 incluida; con el repositorio por defecto, un POST anónimo
  abría una sesión (RED registrado). `AdminChainMarkerIT` y
  `AdminChainMarkerServerIT` marcan con un filtro de prueba, primero en la
  cadena, las peticiones que entran a la cadena de admin: las públicas nunca
  llevan la marca y las de admin siempre.
  Defensa en profundidad: una segunda cadena sin `securityMatcher` y con
  `anyRequest().denyAll()` (`AdminSecurityChainConfig.adminFallbackDenyChain`)
  niega todo lo que la cadena de admin no cubra; solo ve `/api/admin/*` por el
  mapeo del filtro, así que no toca rutas públicas (`AdminFallbackDenyChainIT`;
  mutación 2026-10-08: con el matcher de la cadena de admin reducido a
  `/api/admin/orders/**` el handler sigue sin alcanzarse, y sin la cadena de
  respaldo el centinela aparece 59 veces en el log). El arranque falla con
  `IllegalStateException` salvo que se reescriba exactamente un registro del
  filtro (`SecurityFilterScopeFailFastTest`; mutación: con otro nombre de filtro
  `SecurityFilterScopeIT` no levanta el contexto). Boot no crea usuario ni
  contraseña generados porque hay un `UserDetailsService` propio
  (`AdminUserDetailsService`, que solo conoce la cuenta configurada;
  `NoGeneratedUserIT` en el contexto real y `NoGeneratedPasswordTest`, que
  además tiene un control positivo de que Boot sí lo crea y lo loguea sin él).
  El filtro de Security se registra para los cinco tipos de despacho (REQUEST,
  ASYNC, ERROR, FORWARD, INCLUDE), fijado en
  `SecurityFilterScopeIT.theSecurityFilterRegistrationDispatchesOnEveryDispatcherType`;
  igual, ninguna clase hace `RequestDispatcher.forward` o `include`, ni como
  llamada ni como referencia a método, y los `@Controller` son `@ResponseBody`
  sin `ModelAndView` ni `View` (`ArchitectureTest.noForwardOrInclude`; ArchUnit
  no lee literales, por eso un nombre de vista `forward:` se cierra por tipo, no
  por texto). Todo handler de `..admin.controller..` mapea bajo `/api/admin/` y
  ningún otro mapea esa ruta (`ArchitectureTest.adminHandlerPathsAreUnderTheAdminPrefix`,
  con fixtures en `ArchitectureRulesTest`); hoy no hay controllers de admin, así
  que la primera mitad no tiene handlers que mirar y las fixtures prueban que
  muerde. Evidencia por mutación (2026-10-08): mapear un controller de la tienda
  bajo `/api/admin/` o agregarle un `getRequestDispatcher(..).forward(..)` hizo
  fallar ambas reglas.
- **Credencial de administración** (`ADMIN_USERNAME`, `ADMIN_PASSWORD_HASH`, sin
  valor por defecto; el login y la sesión llegan en cortes siguientes). Se
  valida en `AdminCredentials.parse` con mensajes fijos, no con Bean Validation:
  el informe de fallo de Boot imprime el valor rechazado, y `{noop}secreto`
  terminaría en el log de arranque. Solo se acepta `{bcrypt}` con costo 10 a 14,
  usuario de 1 a 64 caracteres sin espacios ni caracteres de control o de
  formato, y un `${...}` sin resolver (el binder lo deja como texto literal en
  vez de fallar) se rechaza. `AdminCredentialsTest` (valores inválidos y
  centinela fuera del mensaje), `AdminCredentialsStartupTest` (corre
  `SpringApplication` real: el valor rechazado no aparece en la salida, con un
  control positivo de que Boot sí imprime un valor rechazado por validación) y
  `AdminPropertiesValidationTest` (rangos de sesión y del límite de login, con
  mínimo y máximo). `AdminProperties.toString()` no imprime usuario ni hash.
  No hay `DelegatingPasswordEncoder`. `AdminPasswordEncoder` responde `false` sin
  hacer BCrypt si la clave es nula o pasa de 72 bytes UTF-8
  (`AdminPasswordEncoderTest`; mutación M6, 2026-10-08: sin la guarda fallaron 4
  tests); el usuario se compara con `MessageDigest.isEqual`
  (`AdminAuthenticationTest.theUsernameIsComparedInConstantTime`, estructural:
  con `Arrays.equals` falla) y usuario desconocido, usuario de más de 128
  caracteres y clave errónea son el mismo `BadCredentialsException`, con un solo
  BCrypt en ambos casos. Ningún hash se versiona: los tests usan una clave
  aleatoria por JVM (`AdminTestCredentials`) y `NoCommittedHashTest` revisa
  todos los archivos de texto versionados (`git ls-files`), no una lista de
  nombres, y detecta el hash con prefijo `{bcrypt}` y el hash suelto, con y sin
  el escape `$$` de compose (con plantas en `.md`, `.sh` y compose; la aguja
  anterior no veía la forma `$$`: mutación del 2026-10-08, 3 fallas).
  El único `PasswordEncoder` del contexto es el `AdminPasswordEncoder` con la
  guarda de 72 bytes; el BCrypt crudo vive en un contenedor que no es un
  `PasswordEncoder` (`AdminBcrypt`), así que una inyección por tipo no puede
  saltarse la guarda (`AdminPasswordEncoderBeanIT`). Spring Security imprime en
  DEBUG el usuario tecleado (`Failed to find user '...'`): la categoría
  `org.springframework.security.authentication.dao` está en `OFF` en
  `application.yml` y `AdminAuthenticationLoggingIT` fija el nivel efectivo, un
  control positivo con esa categoría en DEBUG y que un login fallido con un
  usuario centinela no deja rastro aunque `org.springframework.security` esté en
  DEBUG (sin la línea de `application.yml` fallan 2 de 3). El runbook del
  operador está en `docs/operacion-admin.md`.
- **Errores y cabeceras de la cadena de admin.** Un 401 sin autenticar
  (`UNAUTHENTICATED`), un 403 (`FORBIDDEN`, incluido un token CSRF ausente o
  inválido, que cae en el mismo manejador de acceso denegado) y el 400 del
  firewall (`MALFORMED_REQUEST`) son `application/problem+json` con texto fijo,
  escritos por `ProblemResponses` desde `common.web.SecurityProblem`, la única
  fuente de código y texto (`SecurityProblemTest`; el catálogo deriva las filas
  y `ApiDocConsistencyTest` exige que estén en `docs/API.md` y que los tres
  goldens `admin-error-*.json` estén citados). `AdminSecurityChainIT` fija los
  tres cuerpos contra sus goldens (sin `instance`, que se compara aparte contra
  la ruta pedida), que `X-Request-Id` es igual a `requestId`, que cada cabecera
  de endurecimiento aparece una sola vez, `Cache-Control` con `no-store`, ningún
  `Access-Control-Allow-Origin` ni `Strict-Transport-Security` (tampoco en
  peticiones seguras: HSTS lo agrega Caddy; mutación 2026-10-08: sin
  `headers.httpStrictTransportSecurity(disable)` falla) y que el cuerpo no repite
  nada de lo que mandó el cliente. `SecurityFirewallVariantsIT` (Tomcat real)
  fija que `;`, `//`, `/./` y `/../` son un 400 con ese cuerpo y las cabeceras;
  una barra, contrabarra o NUL codificados los rechaza Tomcat antes de cualquier
  filtro (sin cuerpo de problema, ya documentado en `docs/API.md`). El orden de
  los filtros (id de petición, cabeceras, límite de cuerpo y recién después
  Security) lo fija `SecurityFilterOrderIT`; mutación 2026-10-08: con Security
  primero fallan ese test, 3 de `AdminSecurityChainIT` y 8 de
  `SecurityFirewallVariantsIT`. M9 (cache de peticiones activada): fallan
  `AdminAnonymousSessionIT`, porque el 401 anónimo abre
  una sesión. `instance` del 401 y el 403 es la ruta pedida, acotada a 256
  caracteres (más largo vale `/api/admin`); el 400 del firewall no lleva
  `instance`, porque la ruta es lo rechazado (`ProblemResponsesIT`,
  `SecurityFirewallVariantsIT`, D-20261008-38). La página de error de Tomcat para
  esas rutas (barra, contrabarra o NUL codificados, en rutas públicas y de admin)
  no nombra al servidor, ni versión ni traza, y no hay cabecera `Server`:
  `ContainerErrorPageIT` lo fija en un servidor real; es el valor por defecto de
  Tomcat 11.0.24, sin código propio (D-20261008-37), y con un `ErrorReportValve`
  que muestra reporte e información del servidor el test falla (7 de 7).
- **Defensa en profundidad sobre los handlers de admin.**
  `AdminAccessGuardInterceptor` (interceptor MVC sobre `/api/admin/**`, con la
  misma resolución de rutas que los handlers) responde 401 `UNAUTHENTICATED` si
  la petición no tiene principal, salvo `GET /api/admin/session`. Esa excepción
  solo vale con GET (`AdminAccessGuardInterceptorTest`, MockMvc independiente sin
  Security) y `AdminAccessGuardIT` arma un MockMvc sin ningún filtro desde el
  contexto real: una petición anónima al handler de prueba nunca lo alcanza;
  mutación 2026-10-08: sin registrar el interceptor el handler responde 200 con
  el centinela. `EndpointInventoryDriftIT` (contexto con el handler de prueba como
  una ruta de admin que nadie listó) prueba que el inventario la reporta y que la
  llamada anónima generada a cada ruta mapeada no pública da el golden 401;
  `EndpointInventoryIT` repite eso sobre el contexto real, exige que toda ruta de
  `/api/admin/` esté en `ADMIN_ROUTES` y que no exista ninguna ruta de
  restablecimiento o cambio de contraseña. Una ruta sin método declarado se
  llama con GET, POST, PUT, PATCH, DELETE, HEAD y OPTIONS y las siete dan 401
  (`EndpointInventoryDriftIT`; en servidor real, con token CSRF válido para las
  que lo exigen, HEAD sin cuerpo y OPTIONS como un 401 común sin `Allow` ni
  cabeceras CORS: `AdminAnonymousMethodsServerIT`; mutación 2026-10-08: con HEAD
  y OPTIONS permitidos fallan 2 y 1 tests). El generador de rutas concretas
  (`EndpointInventory.concretePath`) resuelve variables con regex y falla con un
  `AssertionError` ante `**`, `*`, `?` o `{*x}` (`EndpointInventoryTest`).
- **Una sola cuenta: la de administración.** Contraseña con BCrypt o
  Argon2, login con rate limit y bloqueo temporal, sesión en cookie
  `HttpOnly` + `Secure` + `SameSite`. Nunca un token en `localStorage`.
- **Configuración de producción que falla cerrada**: sin secretos por
  defecto en el repo, CORS sin `*`, docs de la API apagados. Un proceso con
  configuración inválida no arranca. Hecho para `OrderProperties`
  (`OrderPropertiesValidationTest`: valores fuera de rango, ausentes o en blanco,
  incluido el bloque `expiry` entero, frenan el arranque) y para `WebProperties`
  (`WebPropertiesValidationTest` y `TrustedProxiesTest`: rangos; `TRUSTED_PROXIES` ausente, vacío
  o que no sea una lista CIDR cuyas entradas queden enteras dentro de `10.0.0.0/8`, `172.16.0.0/12`,
  `192.168.0.0/16`, `127.0.0.0/8`, `169.254.0.0/16`, `fc00::/7`, `fe80::/10` o `::1/128`; sin
  regex, entradas vacías ni coma final; `WebConfigTest`: la misma regla sobre el valor que Tomcat
  usa de verdad, estrategia `native`, cabecera `X-Forwarded-For` y sin `trusted-proxies`
  ni `proxies-header`, también con variables de entorno); el resto de la configuración
  **[guarda pendiente]**
- **Todo parámetro numérico de la API lleva mínimo Y máximo**
  (`@Min`/`@Max`, cantidades, paginado, estrellas). Un solo lado deja un 500
  alcanzable. Las propiedades de `ceto.orders.*` ya cumplen la regla
  (`OrderPropertiesValidationTest`). Para la API la guarda es la regla ArchUnit
  `REQUEST_INPUTS_ARE_BOUNDED` (`ArchitectureTest`, con fixtures en
  `ArchitectureRulesTest`): todo parámetro numérico de un handler, el propio
  `@RequestBody` (también `List<Dto>`, `Map`, `String`, `Long`) y todo
  componente numérico de un body, `@ModelAttribute` u objeto de la app sin
  anotar (recorrido por DTO anidados, genéricos y arrays; `Optional<X>` cuenta
  como X) declara mínimo y máximo, y todo `String`, colección, mapa o array
  `@Size(max)`; un parámetro simple sin anotar cuenta como query param. Además
  exige `@Valid` en el body, en cada objeto modelo y en cada miembro que sea un
  DTO o una colección de DTOs; en una colección va sobre el miembro
  (`@Valid List<Line>`), no sobre el elemento. Los enums no se recorren.
  Hueco conocido: los elementos de una colección (`List<@Size String>`) no se
  verifican; hoy no se acepta ninguna colección de valores simples.
  `EndpointInventoryIT` repite la comprobación desde el lado Spring sobre los
  handlers realmente registrados.
- **Entrada hostil**: el texto libre que se publica (reseñas, nombre corto,
  productos) rechaza caracteres de control y se escapa al renderizar.
  Imágenes por magic bytes, con tope de bytes y de píxeles; nunca SVG.
  Asuntos de mail sin CRLF.
- **Tamaño del cuerpo y cabeceras**: `BodySizeLimitFilter` rechaza con 413
  `PAYLOAD_TOO_LARGE` un `Content-Length` mayor que `ceto.web.max-body-bytes`
  (configurable de 1 KB a 256 KB, 1024..262144 bytes; por defecto 16 KB, 16384) antes de leer, y cuenta los bytes de los
  cuerpos sin largo declarado (chunked) con `CountingInputStream`;
  `BodySizeLimitFilterTest`, `CountingInputStreamTest`, `BodySizeLimitHttpIT` y
  `BodySizeLimitServerIT` (socket real, chunked de 100 KB, largo mentido) lo
  fijan, y `ApiExceptionHandlerTest` fija que `BodyTooLargeException` en la
  cadena de causas es 413 y no 400. El filtro corre antes del interceptor del
  rate limit: una petición demasiado grande no gasta un token. El 413 que arma el
  advice también envía `Connection: close`. Lo que Tomcat lee por su cuenta queda
  fuera del contador y se acota por configuración (`BodyLimitContainerConfigIT`):
  `server.tomcat.max-swallow-size: 64KB` y `max-http-form-post-size` igual a
  `ceto.web.max-body-bytes` (ninguna ruta lee formularios con `getParameter`).
  `LimitedBodyRequest` respeta el contrato servlet (`getReader` y `getInputStream`
  son excluyentes; un charset inválido es `UnsupportedEncodingException`, 4xx).
  El contador solo cubre la lectura bloqueante: no se usa IO asíncrono
  (`startAsync` + `ReadListener`), que lo esquivaría; quien lo introduzca debe
  contar los bytes también ahí.
  `SecurityHeadersFilter` agrega `X-Content-Type-Options`, `Referrer-Policy` y
  `Content-Security-Policy` a toda respuesta, incluidos los errores de filtros
  e interceptor, y nunca hay `Access-Control-Allow-Origin`
  (`SecurityHeadersHttpIT`, `SecurityHeadersServerErrorIT`). Los errores del
  contenedor que ocurren antes de la cadena de filtros (400 por request line
  inválida, 414, 431) no llevan estas cabeceras. `Strict-Transport-Security` no
  se fija en la aplicación: lo agrega el proxy (Caddy), que es quien termina TLS.
- **Errores neutros hacia afuera**: violación de restricción → 409 genérico;
  sin stack traces ni mensajes crudos de validación al usuario final. Guardas:
  un único `@RestControllerAdvice` (`ApiExceptionHandler`, tabla en
  `ApiErrorCatalog`) arma todas las respuestas `application/problem+json` con
  texto fijo, y `ApiExceptionHandlerTest` verifica la tabla completa sin filtrar
  mensajes; `ApiErrorCatalogTest` hace fallar el build si una subclase concreta de
  `DomainException` no tiene fila; `BootErrorEndpointIT` fija que el `/error` de
  Boot no devuelve stack trace ni mensaje (D-20261007-23). Un GET directo a
  `/error` (sin despacho ERROR del contenedor) responde el 404 estandar
  (`ErrorEndpointGuardInterceptor`, segun el spec; antes devolvia el 500
  generico de Boot) y `ErrorDispatchServerIT` prueba que un error real del contenedor
  sigue renderizando el cuerpo generico. Logs sin datos
  personales: nunca se loguea el mensaje de una excepción de framework,
  persistencia o Jackson (`SafeErrors` emite clases, SQLState y restricción;
  `SafeErrorsTest`), el logger de errores SQL de Hibernate 7
  (`org.hibernate.orm.jdbc.error`) está apagado en `application.yml`, y
  `PiiLoggingIT` fija con un teléfono centinela, a nivel de Hibernate/JPA y del
  advice (no del recorrido HTTP completo), que ni una violación CHECK real
  provocada por un repositorio ni un valor rechazado por Jackson llegan al log,
  y que los cuatro loggers silenciados siguen apagados en la configuración
  efectiva. `PiiHttpPathIT` cubre el recorrido HTTP completo con nombre,
  teléfono y dirección centinela: éxito (201 sin datos de la compradora),
  validación, cuerpo malformado, una violación CHECK forzada (409) y un fallo de
  base de datos que termina en 500 (un trigger cuyo mensaje lleva la fila); la
  salida completa capturada nunca contiene un centinela (evidencia por
  mutación: loggers de Hibernate/MVC en WARN y el advice logueando el mensaje
  hacen fallar los tests). Cada request lleva un id
  generado en el servidor (`RequestIdFilter`; `X-Request-Id` entrante se ignora)
  y los filtros escriben errores con `ProblemResponses`. El parseo JSON es
  estricto (`JacksonConfigTest`: propiedad extra, `"2"` por `2`, clave
  duplicada, profundidad > 8, strings y nombres largos).
- **Recursos acotados** (agotamiento de recursos): el pool de
  Hikari tiene 10 conexiones y espera 3 s por una (`connection-timeout`); sin
  conexión el request termina en 503 `SERVICE_BUSY` + `Retry-After`, escrituras
  y lecturas, y el servicio se recupera al volver las conexiones
  (`PoolExhaustionIT`, golden `contract/error-service-busy.json`; sin
  `connection-timeout` fallan los 3 tests). Diez conexiones por instancia
  quedan muy por debajo de los 100 de Postgres y los hilos de Tomcat de más
  esperan en el pool, no en la base. `lock_timeout` de 5 s y lock por teléfono
  sin espera: regla 3 de §4. Flyway usa el mismo DataSource, así que sus
  migraciones también corren con ese `lock_timeout`.
  **Riesgo residual aceptado**: quien rota teléfonos e IPs puede seguir
  reservando stock hasta que vencen las reservas (D-20261007-21), y con una
  ráfaga distribuida desde muchas IPs las nuevas comparten un balde de desborde
  que puede responder 429 (D-20261007-25). La protección del stock es el tope por
  teléfono; la defensa contra ataques distribuidos es el límite en el borde
  (Caddy) o Turnstile.
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
  idempotencia. Existen: `OrderStockBurstIT` (última unidad),
  `OrderIdempotencyIT` (misma clave), `OrderLockOrderingIT` (orden de locks
  entre reserva, pago, cancelación y vencimiento), `OrderExpiryIT`
  (vencimiento contra pago y corridas concurrentes) y, por HTTP real con
  puerto aleatorio (`AbstractServerIT`), `CheckoutBurstHttpIT` (última unidad:
  1 × 201, N−1 × 409 `INSUFFICIENT_STOCK`; misma clave: 1 × 201 y el resto 200
  `replayed:true` o 503 `SERVICE_BUSY` si alcanzó al creador a mitad de su
  transacción (D-20261008-01), cero otros 5xx); el pool de Hikari del perfil `test` es
  mayor que los hilos para que la contención sea de locks y no del pool. Cada
  pedido de `AbstractServerIT.post` manda su propia `X-Forwarded-For` (el perfil `test` confía
  solo en `127.0.0.1`), si no las ráfagas toparían con el límite por IP.
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
- Tope de unidades por línea: 30 (D-20261007-13, configurable). Una reserva
  sin pago bloquea stock 48 h, así que el checkout ya limita los pedidos
  `PAGO_PENDIENTE` por teléfono (3, D-20261007-19) y los intentos por IP
  (D-20261007-17). D-20261007-19, D-20261007-20 y D-20261007-21 siguen
  pendientes de OK de la clienta. Quedan abiertos: un tope global o Turnstile
  contra quien rota teléfonos e IPs (riesgo residual aceptado,
  D-20261007-21 y D-20261007-25) y el límite de borde en Caddy.
- Del lado técnico, todavía no existen: CI, pre-commit, backups ni las
  guardas que siguen marcadas como pendientes (los topes de la carga de
  reseñas y la configuración de producción fuera de `OrderProperties` y
  `WebProperties`).

## 10. Compuertas de proceso

Objetivo; se activan a medida que existe el código. **[pendiente]**

- Backend: `./mvnw clean verify` (compila, tests unitarios e integración con
  Testcontainers, ArchUnit). Cero warnings del compilador como línea base.
  Siempre con `clean`: un `target/classes` viejo conserva el
  `META-INF/spring.factories` y puede ocultar un registro eliminado (la
  guarda de esquema depende de él).
  Ya existe y corre con Postgres 16 real, con ArchUnit incluido.
- Pruebas de mutación (PIT): corrida **manual**, fuera del gate y del CI, con
  `./mvnw -Pmutation ...` (~20 min). Mide cuántos errores detectan los tests y
  señala dónde leer. Cómo correrla y la línea base: `docs/testing.md`.
- Frontend: `npm run lint`, `npm run typecheck`, `npm test`,
  `npm run build`.
- CI (GitHub Actions) corre lo mismo, con los mismos comandos, en cada push
  y PR. Ningún merge con CI en rojo.
- Toda migración de Flyway es inmutable una vez mergeada: un cambio es una
  migración nueva.
