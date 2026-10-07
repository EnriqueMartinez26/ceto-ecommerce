# Registro de decisiones — Ceto BHB

Cada decisión lleva un ID `D-AAAAMMDD-NN` y dice quién la tomó. `CLAUDE.md`
cita estos IDs; una decisión sin fila acá es una propuesta, no una decisión.
Una fila no se edita para cambiar su sentido: se agrega una nueva que la
reemplace y se marca la anterior como reemplazada.

| ID | Decisión | Decide | Origen | Estado |
|---|---|---|---|---|
| D-20261006-01 | Sin cuentas de compradoras: checkout como invitada. La única cuenta es la de administración. | La clienta | Relevamiento 2026-10-06 | Vigente |
| D-20261006-02 | No usar la API de WhatsApp Business (Meta cobra por mensaje). El cierre es con link `wa.me` y los avisos automáticos por mail. | La clienta | Relevamiento 2026-10-06 | Vigente |
| D-20261006-03 | El envío no se calcula en la web: zona Córdoba Capital (Uber Moto) o Resto del país (Correo Argentino), costo a coordinar por WhatsApp. | La clienta | Relevamiento 2026-10-06 | Vigente |
| D-20261006-04 | Reseñas abiertas sin compra verificada, con moderación previa de la clienta. | La clienta | Relevamiento 2026-10-06 | Vigente |
| D-20261006-05 | Stack: Java 21 + Spring Boot 3 + JPA + Flyway + Postgres; Next.js + TypeScript + Tailwind; Docker Compose + Caddy. Motivo: Mateo domina Java y puede revisar el backend que genera la IA; SSR para SEO y carga mobile. | Mateo | Propuesta 2026-10-06 | Vigente, salvo la versión de Spring Boot (reemplazada por D-20261006-08) |
| D-20261006-06 | Sin Redis ni RabbitMQ: volumen bajo; jobs con `@Scheduled` + `SKIP LOCKED` y mails por outbox en Postgres. Se reconsidera solo con métricas. | Mateo | Propuesta 2026-10-06 | Vigente |
| D-20261006-07 | Sin estado `NUEVO`: el pedido nace en `PAGO_PENDIENTE` (con su reserva de stock). Grafo: `PAGO_PENDIENTE → PAGADO → DESPACHADO → ENTREGADO`, `CANCELADO` desde `PAGO_PENDIENTE` o `PAGADO`. Motivo: `NUEVO` y `PAGO_PENDIENTE` no se distinguían en la operación real. | Mateo | 2026-10-06 | Vigente |
| D-20261006-08 | Spring Boot 4.1 (no 3.x): el soporte OSS de Boot 3.5 terminó el 2026-06-30 y el proyecto tiene mantenimiento mensual por años. Costo aceptado: Jackson 3, Hibernate 7 y starters de test reorganizados; la IA tiende a generar código de Boot 3 y se revisa. | Mateo | 2026-10-06 | Vigente |
| D-20261006-09 | El reembolso de un pedido `PAGADO` que se cancela se acuerda por WhatsApp, fuera del sistema. La web solo devuelve el stock (`on_hand += n`). Reserva de stock: 48 h por defecto (`ORDER_RESERVATION_HOURS`, configurable). | Mateo | 2026-10-06 | Vigente |
| D-20261006-10 | Datos de la compradora en el pedido, por ahora: nombre, teléfono E.164, dirección y zona de envío. Email, DNI, CP y ciudad quedan para el cambio del checkout, cuando la clienta los defina. | Mateo | 2026-10-06 | Vigente |
| D-20261006-11 | Idempotencia del pedido: misma clave y mismo contenido devuelve el pedido existente; misma clave con otro contenido es 409. | Mateo | 2026-10-06 | Vigente |
| D-20261006-12 | Motivo de cancelación (`cancel_reason`): `EXPIRED`, `CANCELLED_BY_ADMIN`, `CANCELLED_BY_BUYER`. Vencer es cancelar con `EXPIRED`, no un estado nuevo. | Mateo | 2026-10-06 | Vigente |
| D-20261007-01 | Teléfono normalizado a E.164 con `libphonenumber` (Google). Dependencia nueva: se verifica en Maven Central antes de agregarla (CLAUDE.md §1). | Mateo | 2026-10-07 | Vigente |
| D-20261007-02 | Tope de 10 unidades por línea de pedido, configurable. Motivo: el checkout de invitada reserva stock 48 h sin pago; el tope limita cuánto stock bloquea un solo pedido. Pendiente: límite de pedidos pendientes por teléfono/IP en el cambio del checkout. | Mateo | 2026-10-07 | Propuesta por Mateo, pendiente de OK de la clienta |
| D-20261007-03 | Paquete base y groupId `ar.cetobhb`. Es un namespace interno de una aplicación privada: no depende del dominio que se compre. | Mateo | 2026-10-07 | Vigente |
| D-20261007-04 | Un pedido `PAGO_PENDIENTE` vencido que el job todavía no canceló se puede marcar `PAGADO`: si la clienta recibió el pago, rechazarlo es peor y el stock sigue reservado. Reintentar con la clave de un pedido ya cancelado devuelve ese pedido; un pedido nuevo usa una clave nueva. | Mateo | 2026-10-07 | Vigente |
| D-20261007-05 | Tope de 20 líneas por pedido (CLAUDE.md §5 exige mínimo y máximo). | Mateo | 2026-10-07 | Vigente |
| D-20261007-06 | El teléfono de la compradora tiene que ser un celular válido: todo el flujo de atención es por WhatsApp. | Mateo | 2026-10-07 | Vigente |
| D-20261007-07 | Postgres 16 en producción, la misma versión que la imagen de los tests. | Mateo | 2026-10-07 | Vigente |
| D-20261007-08 | Un combo agrupa N ≥ 2 unidades del producto base (un "combo de 1" se resuelve con el precio tachado). Superar el tope de líneas por pedido (D-20261007-05) se rechaza con el código `TOO_MANY_LINES`. | Mateo | 2026-10-07 | Vigente |
| D-20261007-09 | El núcleo (`core-catalog-stock-orders`) se entrega en PRs encadenados de hasta ~400 líneas cada uno, para que cada uno se pueda leer completo antes de aceptarlo. | Mateo | 2026-10-07 | Vigente |
| D-20261007-10 | Cancelar un pedido `PAGADO` con motivo `EXPIRED` es `VALIDATION_ERROR(INVALID_CANCEL_REASON)`: la transición PAGADO→CANCELADO es legal, el motivo no. La legalidad de la transición se verifica antes que el motivo. | Mateo (delegado al asistente) | 2026-10-07 | Vigente |
| D-20261007-11 | Un número argentino de 10 dígitos escrito sin 15 ni 9 (ambiguo entre fijo y celular) se acepta y se normaliza como celular `+549…`: en el flujo la compradora escribe ella misma por WhatsApp y el teléfono del pedido sirve para agrupar el historial; rechazarlo cuesta ventas. Se rechazan números con extensión. Se aceptan celulares extranjeros en formato internacional. Ajusta D-20261007-06. | Mateo (delegado al asistente) | 2026-10-07 | Vigente (aprobada por Mateo el 2026-10-07) |
| D-20261007-12 | El job de vencimiento intenta la tanda en una transacción; si falla, reintenta pedido por pedido en transacciones propias, registra en el log (ERROR: id, clase de la excepción y SQLState si lo hay, sin datos personales) los pedidos que fallan y deja avanzar al resto. Un pedido corrupto queda PAGO_PENDIENTE hasta que se repare a mano. Límite conocido: el pedido fallido se reintenta en cada ciclo (un ERROR por pedido fallido por ciclo) y, si hay tantos pedidos corruptos como el tamaño del lote en la ventana de los más antiguos vencidos, los más nuevos dejan de vencer hasta que se reparen a mano; el log de ERROR es la señal. | Mateo (delegado al asistente) | 2026-10-07 | Vigente |
