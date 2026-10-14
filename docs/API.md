# API pública de Ceto BHB

Referencia escrita a mano de las 3 rutas públicas del backend, para quien arma
el frontend y para Mateo (D-20261007-18). Todo lo que figura acá bajo un
encabezado `Golden:` es semánticamente igual (mismo JSON; el formato y el orden
de las claves no cuentan) a un archivo de referencia que los tests comparan
contra la respuesta real; si cambia el contrato, el test falla y este
documento se corrige en el mismo cambio.

**No exponer el backend sin Caddy adelante** (ver [Despliegue](#despliegue)).

## Camino rápido

1. `GET /api/store/products`: lista de productos con precio, disponibilidad y
   el tope de unidades por línea (`maxQuantity`).
2. La compradora arma el carrito en el navegador (el servidor nunca confía en
   precios ni totales del cliente: manda solo ids y cantidades).
3. `POST /api/checkout/orders` con una `Idempotency-Key` nueva por intento
   (un UUID). 201 si crea el pedido, 200 si es un reintento de la misma clave.
4. Con la respuesta, el frontend arma el link `https://wa.me/<número>?text=...`
   y abre WhatsApp (ver [Mensaje de WhatsApp](#mensaje-de-whatsapp)).
5. Si algo falla, el cuerpo siempre trae `code`; el frontend decide el mensaje
   según el [catálogo de errores](#errores).

## Convenciones

| Tema | Regla |
|---|---|
| Base | Mismo origen que el sitio, detrás de Caddy. No hay CORS ni cabeceras `Access-Control-*` (D-20261007-14). |
| Formato | JSON UTF-8. Las respuestas son `application/json`; los errores, `application/problem+json` (RFC 9457). |
| Dinero | String con 2 decimales: `"45000.00"`, `"90301.50"`, nunca un número JSON (D-20261007-16). Parsear con una librería decimal, no con `float`. La moneda no viaja: el precio es el de la tienda. |
| Fechas | ISO-8601 en UTC, a segundos y con `Z`: `"2026-03-12T12:00:00Z"`. Mostrar en `America/Argentina/Cordoba` es tarea del frontend. |
| Ids | Enteros positivos de hasta 2^53 − 1 (seguros en JavaScript). |
| Campos desconocidos | Se rechazan con 400 `MALFORMED_REQUEST`: el cuerpo del `POST` no admite precios, totales ni estado. |
| Números | `"2"` en lugar de `2` es 400 `MALFORMED_REQUEST`; no hay conversión implícita. |
| Parámetros de consulta | Se ignoran en todas las rutas, salvo `GET /api/admin/orders` (ver [Administración: pedidos](#administración-pedidos-apiadminorders)). |
| Id de petición | Toda respuesta trae `X-Request-Id` (un UUID generado por el servidor; el que mande el cliente se ignora). Los errores lo repiten en `requestId`. Mencionarlo al pedir soporte. |

## Endpoints

### `GET /api/store/products`

Lista de productos visibles, por id ascendente, hasta `ceto.web.storefront-max-products`
(200 por defecto). No hay paginación ni filtros.

Golden: `backend/src/test/resources/contract/storefront-list.json`

```json
{
  "products": [
    {
      "id": 1,
      "name": "Solid shampoo",
      "kind": "BASE",
      "comboUnits": null,
      "price": "45000.00",
      "compareAtPrice": "50000.00",
      "availability": "AVAILABLE",
      "maxQuantity": 30
    },
    {
      "id": 2,
      "name": "Shampoo pack",
      "kind": "COMBO",
      "comboUnits": 3,
      "price": "120000.50",
      "compareAtPrice": null,
      "availability": "AVAILABLE",
      "maxQuantity": 30
    },
    {
      "id": 3,
      "name": "Cream",
      "kind": "BASE",
      "comboUnits": null,
      "price": "100.00",
      "compareAtPrice": null,
      "availability": "SOLD_OUT",
      "maxQuantity": 30
    }
  ]
}
```

| Campo | Significado |
|---|---|
| `kind` | `BASE` o `COMBO`. Un combo agrupa `comboUnits` (≥ 2) unidades de un producto base y no tiene stock propio. |
| `price` / `compareAtPrice` | Precio de venta y precio anterior para mostrar tachado; `null` si no hay oferta. |
| `availability` | `AVAILABLE` o `SOLD_OUT`. La tienda nunca informa cantidades de stock (D-20261007-20, propuesta pendiente de OK de la clienta). |
| `maxQuantity` | Tope de unidades **por línea de pedido** (no es el stock). Viene de `ORDER_MAX_UNITS_PER_LINE`, 30 por defecto. |

### `GET /api/store/products/{id}`

Un producto visible. Suma `presentation`, `ingredients` y `usage` (los textos
los provee y aprueba la clienta). `id` va de 1 a 9007199254740991.

Golden: `backend/src/test/resources/contract/storefront-detail.json`

```json
{
  "id": 1,
  "name": "Solid shampoo",
  "kind": "BASE",
  "comboUnits": null,
  "price": "45000.00",
  "compareAtPrice": "50000.00",
  "availability": "AVAILABLE",
  "maxQuantity": 30,
  "presentation": "100 g bar",
  "ingredients": "Coconut oil, shea butter",
  "usage": "Lather on wet hair"
}
```

Un producto inactivo, oculto o inexistente da el mismo 404 `NOT_FOUND`, con el
mismo cuerpo salvo `instance` y `requestId`: no se puede averiguar cuál de los
tres es. Un `id` fuera de rango o no numérico es 400 `VALIDATION_ERROR`. El
golden de abajo es el de una ruta inexistente (`instance` apunta a una ruta de
checkout); la forma del 404 es la misma en la tienda.

Golden: `backend/src/test/resources/contract/error-not-found.json`

```json
{
  "title": "Not Found",
  "status": 404,
  "detail": "The resource was not found.",
  "instance": "/api/checkout/unknown",
  "code": "NOT_FOUND"
}
```

### `POST /api/checkout/orders`

Crea el pedido y reserva el stock (48 h por defecto, `ORDER_RESERVATION_HOURS`).
Cabeceras de la petición: `Content-Type: application/json` e
`Idempotency-Key: <UUID>`.

Cuerpo (ejemplo del test `CheckoutHttpIT`, constante `BODY`):

```json
{
  "buyer": {
    "name": "Maria Perez",
    "phone": "+5493515550000",
    "address": "Calle Falsa 123",
    "zone": "RESTO_DEL_PAIS"
  },
  "lines": [
    { "productId": 2, "quantity": 3 },
    { "productId": 1, "quantity": 2 }
  ]
}
```

| Campo | Regla |
|---|---|
| `buyer.name` | Obligatorio, hasta 120 caracteres. |
| `buyer.phone` | Obligatorio. Celular argentino (con o sin `+54`, `9` o `15`, con espacios, guiones o paréntesis) o celular extranjero en formato internacional. Un fijo o texto con letras es `INVALID_PHONE`. El servidor lo guarda como E.164 y nunca lo devuelve. |
| `buyer.address` | Obligatorio, hasta 500 caracteres. |
| `buyer.zone` | `CORDOBA_CAPITAL` o `RESTO_DEL_PAIS`. El costo de envío no se calcula en la web: se coordina por WhatsApp (D-20261006-03). |
| `lines` | Entre 1 y 20 líneas (tope absoluto del esquema: 50), sin productos repetidos. |
| `lines[].productId` | Id de un producto o combo visible. |
| `lines[].quantity` | De 1 a `maxQuantity` (30 por defecto). |

Los espacios se normalizan (se recortan y se colapsan) y se rechazan los
caracteres de control y de formato (`INVALID_CHARACTERS`). El servidor
reemplaza los precios por los de la base: el total que se cobra es el de la
respuesta, aunque el precio cambie después.

#### Respuesta 201: pedido creado

Golden: `backend/src/test/resources/contract/checkout-201.json`

```json
{
  "orderNumber": "CETO-000001",
  "status": "PAGO_PENDIENTE",
  "zone": "RESTO_DEL_PAIS",
  "total": "90301.50",
  "reservedUntil": "2026-03-12T12:00:00Z",
  "replayed": false,
  "lines": [
    {
      "productId": 1,
      "name": "Solid shampoo",
      "quantity": 2,
      "unitPrice": "45000.00",
      "lineTotal": "90000.00"
    },
    {
      "productId": 2,
      "name": "Cream",
      "quantity": 3,
      "unitPrice": "100.50",
      "lineTotal": "301.50"
    }
  ]
}
```

Un precio guardado como número entero también sale con 2 decimales:

Golden: `backend/src/test/resources/contract/checkout-201-whole-price.json`

```json
{
  "orderNumber": "CETO-000001",
  "status": "PAGO_PENDIENTE",
  "zone": "RESTO_DEL_PAIS",
  "total": "30.00",
  "reservedUntil": "2026-03-12T12:00:00Z",
  "replayed": false,
  "lines": [
    {
      "productId": 3,
      "name": "Whole price",
      "quantity": 3,
      "unitPrice": "10.00",
      "lineTotal": "30.00"
    }
  ]
}
```

| Campo | Significado |
|---|---|
| `orderNumber` | Número legible (`CETO-000123`); es el que viaja en el WhatsApp para que la clienta lo encuentre en el panel. Crece, pero puede tener huecos. |
| `status` | Siempre `PAGO_PENDIENTE` al crear. |
| `total` | Suma de las líneas. No incluye envío. |
| `reservedUntil` | Hasta cuándo se reserva el stock sin pago. Pasada esa hora el pedido se cancela solo. |
| `replayed` | `false` al crear, `true` en un reintento. |
| `lines` | Foto del pedido, ordenada por `productId`, con el nombre y el precio cobrados. |

La respuesta **no** incluye el id interno, el teléfono, la dirección ni el
nombre de la compradora, y no lleva `Location` (no hay un GET del pedido).

#### Respuesta 200: reintento con la misma clave

Misma clave y mismo contenido devuelve el pedido existente, con los precios
cobrados en su momento, aunque el producto se haya desactivado, el stock se haya
agotado o el pedido esté cancelado (D-20261006-11, D-20261007-04). Misma clave
con otro contenido es 409 `IDEMPOTENCY_CONFLICT`.

Golden: `backend/src/test/resources/contract/checkout-200-replay.json`

```json
{
  "orderNumber": "CETO-000001",
  "status": "PAGO_PENDIENTE",
  "zone": "RESTO_DEL_PAIS",
  "total": "90301.50",
  "reservedUntil": "2026-03-12T12:00:00Z",
  "replayed": true,
  "lines": [
    {
      "productId": 1,
      "name": "Solid shampoo",
      "quantity": 2,
      "unitPrice": "45000.00",
      "lineTotal": "90000.00"
    },
    {
      "productId": 2,
      "name": "Cream",
      "quantity": 3,
      "unitPrice": "100.50",
      "lineTotal": "301.50"
    }
  ]
}
```

**Regla para el frontend:** generar la `Idempotency-Key` una vez por intento de
compra y **reusar la misma** en cada reintento (doble toque, red caída, 503).
Una clave nueva crea un pedido nuevo.

## Administración: sesión (`/api/admin/*`)

Rutas de la única cuenta de administración. Spring Security solo ve `/api/admin/*`
(D-20261008-05): las rutas públicas de arriba no cambian. Toda ruta de
administración responde 401 `UNAUTHENTICATED` a quien no inició sesión, salvo
`GET /api/admin/session` (la única ruta anónima que atiende un controller) y las
rutas de Security que abren y cierran la sesión (login y logout, ver la tabla de
abajo), que tienen su propio contrato. Un `GET /api/admin/login` anónimo no es
una página de login: responde el 401 `UNAUTHENTICATED` de siempre y no llega a
ningún handler. El login tiene límite de intentos por IP (ver abajo).

Flujo: `GET /api/admin/session` entrega la cookie `__Host-XSRF-TOKEN`; el cliente la
repite en la cabecera `X-XSRF-TOKEN` de cada `POST`; `POST /api/admin/login` abre
la sesión y su respuesta 204 ya trae un `__Host-XSRF-TOKEN` nuevo (Security rota el
token al autenticar; el anterior deja de ser el de la cookie). Al cerrar sesión
la cookie `__Host-XSRF-TOKEN` se vence: antes del próximo login se vuelve a llamar a
`GET /api/admin/session` para tener un token.

La cookie del token es `__Host-XSRF-TOKEN` (`Secure`, `Path=/`, sin `Domain`: el
navegador solo acepta el prefijo `__Host-` con esas tres condiciones) y la cabecera
sigue siendo `X-XSRF-TOKEN`. El token **no está atado a la sesión** (doble envío
sin estado: basta que la cabecera coincida con la cookie), así que la rotación al
autenticar solo cambia la cookie; el token anterior no queda invalidado en el servidor.

| Ruta | Éxito | Errores |
|---|---|---|
| `GET /api/admin/session` | 200 `{"authenticated": bool}`, sin usuario ni datos personales. Siempre responde `{authenticated}`, nunca 401: con la cookie de una sesión desplazada por un login nuevo o vencida por el tope absoluto es 200 `{"authenticated":false}` y entrega igual el `__Host-XSRF-TOKEN`. Fija `__Host-XSRF-TOKEN` (`Secure`, `Path=/`, `SameSite=Strict`, legible por el navegador, sin `HttpOnly`). Es la **única** ruta anónima y solo con `GET`: `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`, otra capitalización, barra final o `%73ession` dan 401. | |
| `POST /api/admin/login` | 204 sin cuerpo y cookie `JSESSIONID` (`HttpOnly`, `Secure`, `SameSite=Strict`) nueva: el id anterior se descarta. Cuerpo `application/x-www-form-urlencoded` con `username` y `password`, de hasta 128 caracteres cada uno (la contraseña, además, hasta 72 bytes UTF-8). Cabecera `X-XSRF-TOKEN` obligatoria. | 401 `INVALID_CREDENTIALS` (el mismo cuerpo para usuario inexistente y contraseña incorrecta), 403 `FORBIDDEN` sin token o con uno inválido, 413 `PAYLOAD_TOO_LARGE` si el cuerpo supera `max-body-bytes`. |
| `POST /api/admin/logout` | 204 sin cuerpo; termina la sesión del servidor y vence la cookie `JSESSIONID`. Sin sesión también es 204. Cabecera `X-XSRF-TOKEN` obligatoria. | 403 `FORBIDDEN` sin token o con uno inválido. |

Política de la sesión: la cookie `JSESSIONID` es `HttpOnly`, `Secure` y `SameSite=Strict` (la
aplicación no arranca con otra configuración). Solo hay **una sesión de administración**
a la vez: un login nuevo termina la anterior, y la anterior recibe 401 `UNAUTHENTICATED` en su
próxima petición (cuerpo problem+json, como cualquier 401), salvo que esa petición sea el bootstrap, el
login o el logout: esas tres siguen como anónimas. La sesión termina también por inactividad
(`ADMIN_SESSION_IDLE_MINUTES`, 480 por defecto) y por un **tope absoluto** desde el login
(`ADMIN_SESSION_ABSOLUTE_MINUTES`, de 60 a 1440, 720 por defecto: una sesión usada sin parar
también termina a las 12 horas y recibe el mismo 401). En todos los casos la cookie vieja
no vuelve a servir. Un login correcto siempre cambia el id de la sesión. La cookie no admite `Domain` ni otro nombre que `JSESSIONID` (la aplicación no arranca).

Contrato de `POST /api/admin/login`: los datos viajan **solo en el cuerpo**,
`application/x-www-form-urlencoded` (se acepta cualquier parámetro `charset`),
con **un único** `username` y **un único** `password`. Un filtro de la cadena de
administración lo comprueba antes del CSRF y antes de cualquier trabajo de
BCrypt, y no abre sesión en ningún caso de rechazo:

- con query string (aunque sea un `?` vacío): 400 `MALFORMED_REQUEST`;
- con otro `Content-Type` (JSON, `multipart/form-data`, ninguno): 415
  `UNSUPPORTED_MEDIA_TYPE`;
- con `username` o `password` repetido, ausente o con una codificación
  porcentual inválida en el cuerpo: 400 `MALFORMED_REQUEST`.

**Límite de intentos.** Antes de ese filtro, del CSRF y de cualquier trabajo de
BCrypt, cada `POST /api/admin/login` gasta un intento del cubo de su IP: 5 de golpe y
uno más cada 90 segundos (40 por hora), sin importar qué traiga (sin token CSRF,
mal formado, con la contraseña correcta). El 6.º seguido es 429 `RATE_LIMITED` con
`Retry-After` en segundos enteros (mínimo 1; con el cubo vacío, 90); un intento
limitado no calcula ningún hash, no abre sesión y no empuja más lejos el próximo
intento. Cada IP tiene su cubo y no hay bloqueo por cuenta ni cubo global
(D-20261008-07); aun así, quien comparte IP o clave de cubo con un atacante (una
red compartida, un `TRUSTED_PROXIES` mal puesto) comparte también su cubo, y la
tabla de clientes llena manda a los nuevos a un cubo de desborde común
(D-20261008-43). Es una negación de acceso, no un modo de adivinar la
contraseña: el ritmo de adivinanzas sigue acotado. La IP es la que
resuelve el proxy de confianza (`TRUSTED_PROXIES`). Un cuerpo que *declara* más de
`max-body-bytes` es 413 antes del límite y no gasta intento. Solo cuenta
`POST /api/admin/login` escrito exactamente así: `;x=1` es 400 del firewall, y
barra final u otra capitalización no son el login (401 o 404), sin trabajo de BCrypt y
sin gastar intento.

**Tope de verificaciones simultáneas.** Además del límite por IP, a lo sumo
`ceto.admin.login-rate-limit.max-concurrent-verifications` (1 a 8, 2 por defecto)
contraseñas se verifican a la vez en todo el servidor, para acotar la CPU de
BCrypt aunque lleguen intentos de muchas IPs. Un intento que no encuentra lugar
en 200 ms recibe 503 `SERVICE_BUSY` con `Retry-After: 1`, sin calcular ningún
hash ni abrir sesión (su intento ya gastó el del cubo de su IP). No recuerda a
nadie ni cuenta nada en el tiempo: no es un límite de intentos ni un bloqueo
(D-20261008-45). Un campo de más de 128 caracteres se rechaza antes de pedir
lugar.

El cuerpo de estos errores es fijo y no repite ningún valor de la petición.

Golden: `backend/src/test/resources/contract/admin-session-anonymous.json`

```json
{
  "authenticated": false
}
```

Golden: `backend/src/test/resources/contract/admin-session-authenticated.json`

```json
{
  "authenticated": true
}
```

Golden: `backend/src/test/resources/contract/admin-error-invalid-credentials.json`

```json
{
  "title": "Unauthorized",
  "status": 401,
  "detail": "The credentials are not valid.",
  "code": "INVALID_CREDENTIALS"
}
```

El 401 de login trae `instance: "/api/admin/login"` y `requestId`, que el golden
no incluye (los tests los comparan aparte).

## Administración: pedidos (`/api/admin/orders`)

Todas las rutas de esta sección exigen sesión de administración (sin ella, el 401
`UNAUTHENTICATED` de arriba) y responden con `Cache-Control: no-store`. Los datos de la
compradora (nombre, teléfono, dirección) solo salen en la respuesta de estas rutas autenticadas: nunca en un
error ni en el log, y la dirección solo en el detalle, no en la lista.

### `GET /api/admin/orders`

Lista paginada, del pedido más nuevo al más viejo (por id descendente). Parámetros de consulta:

| Parámetro | Valores | Por defecto |
|---|---|---|
| `status` | `all`, `PAGO_PENDIENTE`, `PAGADO`, `DESPACHADO`, `ENTREGADO` o `CANCELADO` (exacto, con esas mayúsculas) | `all` |
| `q` | Búsqueda exacta, hasta 64 caracteres: el número de pedido (`CETO-000123`, en cualquier combinación de mayúsculas y minúsculas, o solo los dígitos) o un teléfono en cualquier forma de escritura (`351 555-0199`, `+54 9 351 555 0199`). No hay búsqueda parcial: `CETO-0001` no trae los pedidos que empiezan así. Un texto que no es ni número ni teléfono da una página vacía, no un error. Se combina con `status`. | sin búsqueda |
| `size` | 1 a 50 | 20 |
| `after` | 1 a 9007199254740991: el `nextAfter` de la página anterior | primera página |

Un valor fuera de rango, que no sea un número o un `status` desconocido es 400 `VALIDATION_ERROR`.
`nextAfter` es `null` en la última página. Cada fila trae `overdue`: verdadero solo en un `PAGO_PENDIENTE` cuyo plazo
(`reservedUntil`) ya pasó y el job de vencimiento todavía no lo canceló; el pedido sigue pagable. `reservedUntil` es `null` fuera de `PAGO_PENDIENTE`.

Golden: `backend/src/test/resources/contract/admin-orders-list.json`

```json
{
  "orders": [
    {
      "id": 2,
      "orderNumber": "CETO-000002",
      "status": "PAGO_PENDIENTE",
      "buyerName": "Beto Ruiz",
      "buyerPhone": "+5493515550102",
      "total": "100.00",
      "createdAt": "2026-03-10T12:05:00Z",
      "reservedUntil": "2026-03-12T12:05:00Z",
      "overdue": false
    },
    {
      "id": 1,
      "orderNumber": "CETO-000001",
      "status": "PAGADO",
      "buyerName": "Ana Gomez",
      "buyerPhone": "+5493515550101",
      "total": "200.00",
      "createdAt": "2026-03-10T12:00:00Z",
      "reservedUntil": null,
      "overdue": false
    }
  ],
  "nextAfter": null
}
```

### `GET /api/admin/orders/{id}`

El detalle de un pedido, con las líneas tal como se cobraron. `id` va de 1 a 9007199254740991 (fuera de rango o no
numérico es 400 `VALIDATION_ERROR`); un pedido inexistente es 404 `NOT_FOUND`, sin repetir el id en el cuerpo.
`reservedUntil` y `secondsLeft` solo vienen en un `PAGO_PENDIENTE`; `secondsLeft` es 0 cuando el plazo pasó y el
pedido sigue pagable (`overdue` verdadero). `cancelReason` solo en un `CANCELADO`.

Golden: `backend/src/test/resources/contract/admin-order-detail.json`

```json
{
  "id": 1,
  "orderNumber": "CETO-000001",
  "status": "PAGO_PENDIENTE",
  "cancelReason": null,
  "buyerName": "Ana Gomez",
  "buyerPhone": "+5493515550101",
  "shippingAddress": "Calle Falsa 123",
  "zone": "CORDOBA_CAPITAL",
  "total": "200.00",
  "createdAt": "2026-03-10T12:00:00Z",
  "updatedAt": "2026-03-10T12:00:00Z",
  "paidAt": null,
  "dispatchedAt": null,
  "deliveredAt": null,
  "cancelledAt": null,
  "reservedUntil": "2026-03-12T12:00:00Z",
  "secondsLeft": 86400,
  "overdue": false,
  "lines": [
    {
      "lineNo": 1,
      "productId": 1,
      "productName": "Frasco",
      "combo": false,
      "unitPrice": "100.00",
      "quantity": 2,
      "baseUnitsPerUnit": 1,
      "baseUnitsTotal": 2,
      "lineTotal": "200.00"
    }
  ]
}
```

El mismo pedido ya pagado: sin plazo y con la hora del pago.

Golden: `backend/src/test/resources/contract/admin-order-detail-paid.json`

```json
{
  "id": 1,
  "orderNumber": "CETO-000001",
  "status": "PAGADO",
  "cancelReason": null,
  "buyerName": "Ana Gomez",
  "buyerPhone": "+5493515550101",
  "shippingAddress": "Calle Falsa 123",
  "zone": "CORDOBA_CAPITAL",
  "total": "200.00",
  "createdAt": "2026-03-10T12:00:00Z",
  "updatedAt": "2026-03-10T13:00:00Z",
  "paidAt": "2026-03-10T13:00:00Z",
  "dispatchedAt": null,
  "deliveredAt": null,
  "cancelledAt": null,
  "reservedUntil": null,
  "secondsLeft": null,
  "overdue": false,
  "lines": [
    {
      "lineNo": 1,
      "productId": 1,
      "productName": "Frasco",
      "combo": false,
      "unitPrice": "100.00",
      "quantity": 2,
      "baseUnitsPerUnit": 1,
      "baseUnitsTotal": 2,
      "lineTotal": "200.00"
    }
  ]
}
```

### Acciones sobre un pedido: `POST /api/admin/orders/{id}/pay|dispatch|deliver|cancel`

Una acción por ruta, sin cuerpo (si se manda uno, se ignora): el estado de destino lo decide la ruta y nunca un valor del
cliente. Exigen el token CSRF (`X-XSRF-TOKEN`, ver arriba): sin él es el 403 `FORBIDDEN` y no cambia nada. Responden 200 con
el mismo detalle de arriba, ya actualizado.

| Ruta | Transición | Efecto en el stock |
|---|---|---|
| `pay` | `PAGO_PENDIENTE` a `PAGADO` | descuenta en firme (`on_hand` y `reserved`); sigue valiendo si el plazo pasó y el job no corrió |
| `dispatch` | `PAGADO` a `DESPACHADO` | ninguno |
| `deliver` | `DESPACHADO` a `ENTREGADO` | ninguno; el paso está pendiente de la decisión de la clienta (D-20261008-16) |
| `cancel` | `PAGO_PENDIENTE` o `PAGADO` a `CANCELADO`, con motivo `CANCELLED_BY_ADMIN` | libera la reserva, o devuelve las unidades a `on_hand` si ya estaba pagado |

Cada acción confirmada deja una fila en el registro de auditoría, en la misma transacción. Una arista que el grafo de estados
no admite (despachar un pendiente, repetir un pago, cualquier acción sobre un `ENTREGADO` o un `CANCELADO`) es 409
`ILLEGAL_TRANSITION`: no cambia el estado ni el stock y no escribe auditoría; un segundo toque de la misma acción es lo mismo.
Con dos pagos simultáneos del mismo pedido uno recibe 200 y el otro 409. Un `id` inexistente es 404 `NOT_FOUND` y uno fuera de
rango o no numérico, 400 `VALIDATION_ERROR`. El cuerpo del 409 no nombra los estados ni al pedido.

El pedido cancelado, tal como lo devuelve `cancel` sobre un `PAGO_PENDIENTE`; el de `pay` es el golden pagado de arriba.

Golden: `backend/src/test/resources/contract/admin-order-cancelled.json`

```json
{
  "id": 1,
  "orderNumber": "CETO-000001",
  "status": "CANCELADO",
  "cancelReason": "CANCELLED_BY_ADMIN",
  "buyerName": "Ana Gomez",
  "buyerPhone": "+5493515550101",
  "shippingAddress": "Calle Falsa 123",
  "zone": "CORDOBA_CAPITAL",
  "total": "200.00",
  "createdAt": "2026-03-10T12:00:00Z",
  "updatedAt": "2026-03-10T12:30:00Z",
  "paidAt": null,
  "dispatchedAt": null,
  "deliveredAt": null,
  "cancelledAt": "2026-03-10T12:30:00Z",
  "reservedUntil": null,
  "secondsLeft": null,
  "overdue": false,
  "lines": [
    {
      "lineNo": 1,
      "productId": 1,
      "productName": "Frasco",
      "combo": false,
      "unitPrice": "100.00",
      "quantity": 2,
      "baseUnitsPerUnit": 1,
      "baseUnitsTotal": 2,
      "lineTotal": "200.00"
    }
  ]
}
```

Golden: `backend/src/test/resources/contract/admin-error-illegal-transition.json`

```json
{
  "title": "Conflict",
  "status": 409,
  "detail": "The order cannot move to that state from its current one.",
  "code": "ILLEGAL_TRANSITION"
}
```

### Errores de las rutas de pedidos

Tienen la forma de siempre, con `instance` igual a la ruta pedida; los goldens no lo incluyen (los tests lo comparan aparte).

Golden: `backend/src/test/resources/contract/admin-error-validation.json`

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "The request is invalid.",
  "code": "VALIDATION_ERROR"
}
```

Golden: `backend/src/test/resources/contract/admin-error-not-found.json`

```json
{
  "title": "Not Found",
  "status": 404,
  "detail": "The resource was not found.",
  "code": "NOT_FOUND"
}
```

## Cabeceras

| Cabecera | Dirección | Dónde | Detalle |
|---|---|---|---|
| `Idempotency-Key` | petición | `POST /api/checkout/orders` | UUID de 36 caracteres. Obligatoria: ausente, mayor a 64 caracteres o que no sea un UUID es 400 `VALIDATION_ERROR` con `reason: MISSING_IDEMPOTENCY_KEY`. |
| `Content-Type` | petición | `POST` | `application/json`. Otro tipo (o ninguno) es 415. |
| `Accept` | petición | todas | `application/json` o sin definir. Otro tipo (por ejemplo XML) es 406. |
| `X-Request-Id` | respuesta | todas | UUID del servidor; los errores lo repiten en `requestId`. El de la petición se ignora. |
| `Retry-After` | respuesta | 429 y 503 | Segundos de espera. En 429 `RATE_LIMITED` es el tiempo hasta el próximo intento permitido (mínimo 1). En 503 `SERVICE_BUSY` es `1` si hay otro checkout del mismo teléfono en curso (o, en el login, si las verificaciones de contraseña están al tope) y `5` si el servicio está saturado. |
| `Cache-Control` | respuesta | checkout y todo error | `no-store`. Los `GET` de la tienda no fijan `Cache-Control`: la política de caché de la lista la define el frontend o Caddy. |
| `Cache-Control`, `X-Frame-Options` | respuesta | `/api/admin/*` | Toda respuesta de administración lleva `Cache-Control` con `no-store` (los errores `no-store` a secas; en las demás, Security suma `no-cache`, `Pragma` y `Expires`) y `X-Frame-Options: DENY`. Nunca hay `Access-Control-Allow-Origin` ni `Strict-Transport-Security`. Las rutas públicas no cambian sus cabeceras. |
| `Allow` | respuesta | 405 | Métodos permitidos de la ruta. |
| `Connection` | respuesta | 413 | `close`: el servidor puede no haber leído todo el cuerpo. |
| `X-Content-Type-Options`, `Referrer-Policy`, `Content-Security-Policy` | respuesta | todas | `nosniff`, `no-referrer` y `default-src 'none'; frame-ancestors 'none'`. `Strict-Transport-Security` lo agrega Caddy. |

## Errores

Todo error es `application/problem+json` con esta forma: `title`, `status`,
`detail` (texto fijo en inglés, nunca un valor de la petición), `instance` (la
ruta pedida; ver la excepción de `/api/admin/*` más abajo), `code` (estable, para decidir qué mostrar) y `requestId`. Se suma
`reason` (solo en `VALIDATION_ERROR`) o `productIds` (solo en
`PRODUCT_UNAVAILABLE`). Nunca hay `type`, stack trace ni mensajes de excepción.
Los goldens de abajo no incluyen `requestId` porque el test lo compara aparte
contra `X-Request-Id`.

| Status | `code` | Cuándo | Campos extra | Reintentar |
|---|---|---|---|---|
| 400 | `VALIDATION_ERROR` | Un campo no cumple una regla, la clave de idempotencia falta o está mal, o el `id` de la ruta es inválido. | `reason` (ver abajo) | No: corregir el dato. |
| 400 | `MALFORMED_REQUEST` | El cuerpo no se puede leer: JSON inválido, campo desconocido o repetido, tipo equivocado, anidado de más de 8 niveles o texto demasiado largo. En `/api/admin/*` también cuando el firewall de Security rechaza la ortografía de la ruta (`;`, `//`, `/./`, `/../`) y cuando el login trae query string o un `username`/`password` repetido o ausente. | | No. |
| 400 | `BAD_REQUEST` | Otro error de cliente que Spring detecta antes de la lógica y no tiene código propio. | | No. |
| 401 | `UNAUTHENTICATED` | Solo en `/api/admin/*`: no hay sesión de administración. Nunca dice por qué ni nombra al usuario. | | Sí, después de iniciar sesión. |
| 401 | `INVALID_CREDENTIALS` | Solo en `POST /api/admin/login`: usuario inexistente, contraseña incorrecta o un campo de más de 128 caracteres. Es siempre el mismo cuerpo: no dice cuál fue el motivo ni nombra al usuario. | | Sí, con otros datos. |
| 403 | `FORBIDDEN` | Solo en `/api/admin/*`: falta el token CSRF o es inválido, o la sesión no puede hacer esto. | | No: pedir un token nuevo. |
| 404 | `NOT_FOUND` | Ruta inexistente (incluidas variantes como barra final o `//`) o producto inexistente, inactivo u oculto o, en `/api/admin/orders/{id}` y sus acciones, pedido inexistente. | | No. |
| 405 | `METHOD_NOT_ALLOWED` | Método no permitido para la ruta (por ejemplo `POST` a la lista). | `Allow` | No. |
| 406 | `NOT_ACCEPTABLE` | `Accept` no admite JSON. | | No. |
| 409 | `PRODUCT_UNAVAILABLE` | Un producto del carrito está inactivo, oculto o sin fila de stock. | `productIds`: ids **pedidos** afectados | No: sacar esos productos. |
| 409 | `INSUFFICIENT_STOCK` | No alcanza el stock. No dice cuál producto ni cuánto queda. | | Solo si el carrito cambia. |
| 409 | `IDEMPOTENCY_CONFLICT` | La clave ya se usó con otro contenido. No nombra ningún pedido. | | No: usar una clave nueva si es otro pedido. |
| 409 | `ILLEGAL_TRANSITION` | Solo en `POST /api/admin/orders/{id}/<acción>`: la arista no está en el grafo de estados del pedido (incluye el segundo toque de la misma acción). No nombra estados ni pedido. | | No: recargar el pedido y ver su estado. |
| 409 | `CONFLICT` | Una restricción de la base o un conflicto de versión que la lógica no previó. | | Una vez, con la misma clave. |
| 413 | `PAYLOAD_TOO_LARGE` | El cuerpo supera `ceto.web.max-body-bytes` (16 KB por defecto). | | No. |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` distinto de `application/json` (en `POST /api/admin/login`, distinto de `application/x-www-form-urlencoded`). | | No. |
| 429 | `RATE_LIMITED` | La IP agotó sus intentos de checkout o, en `POST /api/admin/login`, sus intentos de login. Mismo código y texto en ambos. | `Retry-After` | Sí, después de esa espera. |
| 429 | `TOO_MANY_PENDING_ORDERS` | El teléfono ya tiene el máximo de pedidos `PAGO_PENDIENTE`. | | No: pagar o esperar que venzan. |
| 500 | `INTERNAL_ERROR` | Falla inesperada. Se registra con el `requestId`. | | Una vez, con la misma clave. |
| 503 | `SERVICE_BUSY` | Otro checkout del mismo teléfono está en curso, un lock tardó más de 5 s o no hay conexión a la base. En `POST /api/admin/login`: ya hay verificaciones de contraseña en curso hasta el tope (`ceto.admin.login-rate-limit.max-concurrent-verifications`, 2 por defecto) y este intento esperó 200 ms sin conseguir lugar; no se calculó ningún hash ni se abrió sesión. | `Retry-After` | Sí, con la misma clave (en el login, otra vez en un segundo). |

Los errores que Tomcat genera **antes** de la cadena de filtros (línea de
petición inválida, 414, 431) no tienen esta forma ni las cabeceras de seguridad:
los atiende `/error` de Boot sin detalles (D-20261007-23).

Goldens, uno por código que el endpoint de checkout puede devolver:

Golden: `backend/src/test/resources/contract/error-validation.json`

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "The request is invalid.",
  "instance": "/api/checkout/orders",
  "code": "VALIDATION_ERROR",
  "reason": "MISSING_IDEMPOTENCY_KEY"
}
```

Golden: `backend/src/test/resources/contract/error-malformed.json`

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "The request could not be read.",
  "instance": "/api/checkout/orders",
  "code": "MALFORMED_REQUEST"
}
```

Golden: `backend/src/test/resources/contract/error-product-unavailable.json`

```json
{
  "title": "Conflict",
  "status": 409,
  "detail": "One or more products are not available.",
  "instance": "/api/checkout/orders",
  "code": "PRODUCT_UNAVAILABLE",
  "productIds": [2]
}
```

Golden: `backend/src/test/resources/contract/error-insufficient-stock.json`

```json
{
  "title": "Conflict",
  "status": 409,
  "detail": "There is not enough stock for this order.",
  "instance": "/api/checkout/orders",
  "code": "INSUFFICIENT_STOCK"
}
```

Golden: `backend/src/test/resources/contract/error-idempotency-conflict.json`

```json
{
  "title": "Conflict",
  "status": 409,
  "detail": "The idempotency key was already used for a different order.",
  "instance": "/api/checkout/orders",
  "code": "IDEMPOTENCY_CONFLICT"
}
```

Golden: `backend/src/test/resources/contract/error-payload-too-large.json`

```json
{
  "title": "Content Too Large",
  "status": 413,
  "detail": "The request is too large.",
  "instance": "/api/checkout/orders",
  "code": "PAYLOAD_TOO_LARGE"
}
```

Golden: `backend/src/test/resources/contract/error-unsupported-media-type.json`

```json
{
  "title": "Unsupported Media Type",
  "status": 415,
  "detail": "The content type is not supported.",
  "instance": "/api/checkout/orders",
  "code": "UNSUPPORTED_MEDIA_TYPE"
}
```

Golden: `backend/src/test/resources/contract/error-rate-limited.json`

```json
{
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Too many requests. Try again later.",
  "instance": "/api/checkout/orders",
  "code": "RATE_LIMITED"
}
```

Golden: `backend/src/test/resources/contract/error-too-many-pending.json`

```json
{
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Too many pending orders for this phone.",
  "instance": "/api/checkout/orders",
  "code": "TOO_MANY_PENDING_ORDERS"
}
```

Golden: `backend/src/test/resources/contract/error-service-busy.json`

```json
{
  "title": "Service Unavailable",
  "status": 503,
  "detail": "The service is busy. Try again shortly.",
  "instance": "/api/checkout/orders",
  "code": "SERVICE_BUSY"
}
```

Tres formas más, todas con la misma estructura: el 405 de un método no
permitido (trae la cabecera `Allow: POST`), el 409 genérico `CONFLICT` y el 500
`INTERNAL_ERROR`.

Golden: `backend/src/test/resources/contract/error-method-not-allowed.json`

```json
{
  "title": "Method Not Allowed",
  "status": 405,
  "detail": "The method is not allowed for this resource.",
  "instance": "/api/checkout/orders",
  "code": "METHOD_NOT_ALLOWED"
}
```

Golden: `backend/src/test/resources/contract/error-conflict.json`

```json
{
  "title": "Conflict",
  "status": 409,
  "detail": "The request conflicts with the current state.",
  "instance": "/api/checkout/orders",
  "code": "CONFLICT"
}
```

Golden: `backend/src/test/resources/contract/error-internal.json`

```json
{
  "title": "Internal Server Error",
  "status": 500,
  "detail": "An unexpected error occurred.",
  "instance": "/api/checkout/orders",
  "code": "INTERNAL_ERROR"
}
```

### Errores de Security en `/api/admin/*`

Los produce Spring Security, no el controller, y tienen la misma forma, con una
diferencia en `instance`. En el 401 y el 403 es la ruta pedida, como en el resto
de los errores, pero si la ruta pasa de 256 caracteres vale `/api/admin` (el
cuerpo no crece con la petición). En el 400 `MALFORMED_REQUEST` del firewall
(una ruta con `;`, `//`, `/./` o `/../`, o un login con query string o campos
repetidos) **no hay `instance`**: lo rechazado no se repite. Estos cinco goldens no incluyen
`instance` (cambia en cada ruta) ni `requestId`; los tests los comparan aparte
(`ContractAssertions.assertMatchesGoldenForPath`).

Golden: `backend/src/test/resources/contract/admin-error-unauthenticated.json`

```json
{
  "title": "Unauthorized",
  "status": 401,
  "detail": "Authentication is required.",
  "code": "UNAUTHENTICATED"
}
```

Golden: `backend/src/test/resources/contract/admin-error-forbidden.json`

```json
{
  "title": "Forbidden",
  "status": 403,
  "detail": "The request is not allowed.",
  "code": "FORBIDDEN"
}
```

Golden: `backend/src/test/resources/contract/admin-error-rejected-request.json`

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "The request could not be read.",
  "code": "MALFORMED_REQUEST"
}
```

Golden: `backend/src/test/resources/contract/admin-error-rate-limited.json`

```json
{
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Too many requests. Try again later.",
  "code": "RATE_LIMITED"
}
```

Golden: `backend/src/test/resources/contract/admin-error-service-busy.json`

```json
{
  "title": "Service Unavailable",
  "status": 503,
  "detail": "The service is busy. Try again shortly.",
  "code": "SERVICE_BUSY"
}
```

Golden: `backend/src/test/resources/contract/admin-error-unsupported-media-type.json`

```json
{
  "title": "Unsupported Media Type",
  "status": 415,
  "detail": "The content type is not supported.",
  "code": "UNSUPPORTED_MEDIA_TYPE"
}
```

### Valores de `reason`

Solo acompaña a `VALIDATION_ERROR`. Si varias reglas fallan, se informa la
primera en este orden: clave, nombre, teléfono, dirección, zona, líneas.

| `reason` | Cuándo |
|---|---|
| `MISSING_IDEMPOTENCY_KEY` | `Idempotency-Key` ausente, de más de 64 caracteres o que no es un UUID. |
| `BLANK_BUYER_NAME` | Sin `buyer` o nombre vacío. |
| `BUYER_NAME_TOO_LONG` | Más de 120 caracteres. |
| `INVALID_PHONE` | No es un celular válido, tiene letras, extensión o más de 64 caracteres. |
| `BLANK_ADDRESS` | Dirección vacía. |
| `ADDRESS_TOO_LONG` | Más de 500 caracteres. |
| `INVALID_CHARACTERS` | Nombre o dirección con caracteres de control o de formato. |
| `INVALID_ZONE` | Zona ausente o distinta de `CORDOBA_CAPITAL` y `RESTO_DEL_PAIS`. |
| `EMPTY_CART` | Sin líneas. |
| `TOO_MANY_LINES` | Más de 20 líneas (el tope absoluto del esquema es 50). |
| `INVALID_LINE` | Línea nula o `productId` ausente, menor a 1 o mayor a 9007199254740991. |
| `QUANTITY_BELOW_MIN` | Cantidad ausente o menor a 1. |
| `QUANTITY_ABOVE_MAX` | Cantidad mayor a `maxQuantity` (30 por defecto; el esquema admite hasta 100). |
| `DUPLICATE_PRODUCT_LINE` | El mismo `productId` en dos líneas. |
| `ORDER_TOTAL_OUT_OF_RANGE` | El total calculado no entra en `NUMERIC(12,2)`. |

Estos valores existen en el código pero **no** los devuelven las 3 rutas
públicas (son del panel y del catálogo): `INVALID_COMBO_LINK`,
`INVALID_PRICE_PAIR`, `INVALID_PRODUCT_NAME`, `STOCK_BELOW_RESERVED`,
`INVALID_CANCEL_REASON`, `INVALID_STOCK_DELTA` (ajuste de stock con delta 0) y
`STOCK_OUT_OF_RANGE` (el ajuste dejaría el stock físico por encima de
2147483647).

En errores estructurales de Bean Validation `reason` aparece solo si el mensaje
de la restricción es uno de los valores de la tabla; si no, el 400 va sin
`reason`. Por ejemplo, un `id` inválido en `/api/store/products/{id}` es
`VALIDATION_ERROR` sin `reason`.

## Mensaje de WhatsApp

El servidor no arma ni devuelve el link (D-20261006-02: no se usa la API de
WhatsApp Business). El frontend lo construye con los campos de la respuesta 201
o 200: `https://wa.me/<número de la clienta>?text=<texto url-encoded>`, donde el
texto lleva `orderNumber`, las `lines` (`name`, `quantity`, `lineTotal`), el
`total` y `zone`, y aclara "costo de envío a coordinar por WhatsApp"
(D-20261006-03). El pedido ya está registrado cuando se abre WhatsApp: si no
abre, la clienta lo ve igual en el panel. El texto del mensaje lo define la
clienta; este documento no lo redacta.

## Límites y abuso

| Límite | Valor | Respuesta | Decisión |
|---|---|---|---|
| Intentos de checkout por IP | 10 de golpe y uno más cada 6 minutos (10 por hora). Solo cuenta el `POST`, antes de leer el cuerpo. Un cuerpo demasiado grande no gasta intento. | 429 `RATE_LIMITED` + `Retry-After` | D-20261007-17 |
| Intentos de login por IP | 5 de golpe y uno más cada 90 segundos (40 por hora), `ceto.admin.login-rate-limit`. Cuenta todo `POST /api/admin/login` antes del CSRF y de BCrypt; un cuerpo declarado demasiado grande no gasta intento. Sin bloqueo por cuenta ni cubo global. | 429 `RATE_LIMITED` + `Retry-After` | D-20261008-07, D-20261008-43 |
| Verificaciones de contraseña simultáneas | 2 por defecto (1 a 8), `ceto.admin.login-rate-limit.max-concurrent-verifications`, 200 ms de espera por un lugar. No es un límite de intentos. | 503 `SERVICE_BUSY` + `Retry-After: 1` | D-20261008-45 |
| Pedidos `PAGO_PENDIENTE` por teléfono | 3 (`ORDER_MAX_PENDING_PER_PHONE`, de 1 a 20). Un pendiente vencido que el job aún no canceló sigue contando. Pagado, cancelado y vencido liberan el cupo. | 429 `TOO_MANY_PENDING_ORDERS` | D-20261007-19 (propuesta, pendiente de OK de la clienta), D-20261007-24 |
| Checkouts simultáneos del mismo teléfono | Uno. El lock por teléfono no espera: el segundo recibe 503 al instante y ningún pedido retiene una conexión esperando. | 503 `SERVICE_BUSY` + `Retry-After: 1` | D-20261008-01 |
| Tabla del limitador por IP llena | Las IPs nuevas comparten un balde de desborde (checkout: 100 de golpe, 100 por hora; login: 20 de golpe, 40 por hora). | 429 `RATE_LIMITED` | D-20261007-25, D-20261008-43 |
| Tamaño del cuerpo | 16 KB por defecto (1 KB a 256 KB) | 413 `PAYLOAD_TOO_LARGE` | |
| Unidades por línea / líneas por pedido | 30 (1 a 100) / 20 | 400 `VALIDATION_ERROR` | D-20261007-13, D-20261007-05 |
| Los `GET` de la tienda | Sin límite por IP | | D-20261007-22 |

Un reintento de la misma clave que llega mientras el primer pedido todavía se
está creando recibe 503 (`Retry-After: 1`); uno posterior recibe 200. Un
reintento con un pedido ya creado nunca es 429 `TOO_MANY_PENDING_ORDERS`, ni
siquiera con el teléfono en el tope; el límite por IP (429 `RATE_LIMITED`) se
evalúa antes y sí puede aplicarse.

### Riesgo residual

Quien rota teléfonos e IPs puede reservar stock sin pagar hasta que vencen las
reservas (48 h): se acepta (D-20261007-21, propuesta pendiente de OK de la
clienta). Ante una ráfaga distribuida desde muchas IPs, las nuevas comparten el
balde de desborde y pueden recibir 429 (D-20261007-25); lo mismo vale para el
login: con la tabla de 10000 IPs llena de un ataque distribuido, la dueña desde una
IP nueva comparte un balde de 20 de golpe y 40 por hora (D-20261008-43). La protección del stock
es el tope por teléfono; la defensa contra ataques distribuidos es el límite en
el borde (Caddy) o Turnstile, que son trabajo futuro.

## Despliegue

**El backend no debe publicarse sin Caddy adelante.** La aplicación no termina
TLS, no agrega `Strict-Transport-Security`, y confía en `X-Forwarded-For` solo
de los proxies que se le declaran. Solo Caddy publica puertos; el backend queda
en la red interna de Docker Compose.

| Requisito | Detalle |
|---|---|
| `TRUSTED_PROXIES` | **Obligatoria, sin valor por defecto**: si falta, la aplicación no arranca. Lista CIDR separada por comas, sin regex, con cada entrada dentro de rangos privados (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`, `169.254.0.0/16`, `fc00::/7`, `fe80::/10`, `::1/128`). Ejemplos válidos: `172.18.0.5/32`, `172.18.0.0/16`. Rechazados: `0.0.0.0/0`, `::/0`, `.*`, rangos públicos, entradas vacías. Preferir el `/32` o la subred de la red de Compose donde vive Caddy: confiar en `127.0.0.0/8` o `169.254.0.0/16` confía en todo el host o todo el enlace. Si el valor es incorrecto, todas las IPs parecen la del proxy y el límite por IP deja de distinguir compradoras y, en el login, **un solo cubo de 5 intentos lo comparten todos, la dueña incluida**. |
| Caddy | Debe descartar el `X-Forwarded-For` que mande el cliente y fijar la IP real. No declarar `trusted_proxies` en Caddy salvo que haya otro proxy adelante, y verificar el despliegue con una petición con un `X-Forwarded-For` falso: no debe cambiar la IP que ve la aplicación. Agrega `Strict-Transport-Security` y es el lugar para un límite de borde. |
| Variables `ORDER_*` | `ORDER_RESERVATION_HOURS` (48; 1 a 168), `ORDER_MAX_UNITS_PER_LINE` (30; 1 a 100), `ORDER_MAX_PENDING_PER_PHONE` (3; 1 a 20). Un valor fuera de rango detiene el arranque. |
| Propiedades `ceto.web.*` | `max-body-bytes` (16384; 1024 a 262144), `storefront-max-products` (200; 1 a 500) y `checkout-rate-limit.{burst, refill-interval-seconds, max-tracked-clients, overflow-burst, overflow-refill-per-hour}`. Se cambian en `application.yml` o con variables de entorno en la forma canónica de Spring Boot (mayúsculas, puntos a guion bajo y sin guiones: `CETO_WEB_MAXBODYBYTES`). Todas se validan con mínimo y máximo, salvo `trusted-proxies`, que se valida con la regla CIDR de `TRUSTED_PROXIES`. |
| Pool de conexiones | 10 por instancia, 3 s de espera por una conexión; sin conexión, 503 `SERVICE_BUSY`. Postgres permite 100: no subir el pool sin revisar ese techo. |
| `lock_timeout` | 5 s en cada conexión: una espera de lock más larga termina en 503 `SERVICE_BUSY` con `Retry-After: 5`. Las migraciones usan `SET LOCAL`. |
| `idle_in_transaction_session_timeout` | 30 s en cada conexión: respaldo contra una transacción que retiene locks y dejó de hablar con la base. |
| Cuerpo | Tope de 16 KB por defecto, verificado por `Content-Length` y contando los bytes del cuerpo chunked; Tomcat además limita lo que absorbe (`max-swallow-size` 64 KB, formularios iguales al tope de la aplicación). |
| Arranque | Postgres 16, `ddl-auto: validate` y migraciones solo por Flyway. |

Verificación previa a exponer: `./mvnw clean verify` en verde (compila,
pruebas unitarias, integración con Postgres real y reglas de arquitectura).

## Referencias

- Goldens: `backend/src/test/resources/contract/*.json`, comparados por
  `StorefrontHttpIT`, `CheckoutHttpIT`, `CheckoutErrorsHttpIT`, `RateLimitIT`,
  `LockTimeoutIT`, `PoolExhaustionIT`, `BodySizeLimitHttpIT` y `PiiHttpPathIT`.
- Este documento se verifica con `ApiDocConsistencyTest`: cada bloque marcado
  con `Golden:` es igual como JSON al archivo citado, todo golden está citado, todo
  `code` del catálogo de errores y todo `reason` del código figuran acá.
- Decisiones: `docs/DECISIONES.md`. Reglas duras y guardas: `CLAUDE.md` (§4
  reglas 3 y 10, §5).
