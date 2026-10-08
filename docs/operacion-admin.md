# Operación de la cuenta de administración

Esta guía es para quien despliega la aplicación. La tienda tiene una sola
cuenta de administración. Su usuario y el hash de su contraseña llegan por
variables de entorno; no hay valor por defecto y no se versiona ninguno
(`NoCommittedHashTest` revisa todos los archivos de texto del repositorio).

## Variables requeridas

| Variable | Propiedad | Qué es |
|---|---|---|
| `ADMIN_USERNAME` | `ceto.admin.username` | Nombre de usuario de la cuenta. |
| `ADMIN_PASSWORD_HASH` | `ceto.admin.password-hash` | Hash BCrypt de la contraseña, con prefijo `{bcrypt}`. |

Sin las dos variables, o con un valor inválido, la aplicación **no arranca**.
El mensaje de error nombra la propiedad y orienta sobre la causa probable
(espacios, saltos de línea, escape `$$`), pero nunca imprime el valor recibido.
Esto lo prueba `AdminCredentialsStartupTest` con un valor centinela.

## Formato del hash

- El valor es el texto `{bcrypt}` seguido del hash BCrypt tal como lo emite la
  herramienta que lo genera: empieza con `$2a$`, `$2b$` o `$2y$`, sigue el costo
  de dos dígitos y `$`, y terminan 53 caracteres del alfabeto BCrypt (letras,
  números, `.` y `/`).
- Solo se acepta `{bcrypt}`. Cualquier otro prefijo (`{noop}`, `{sha256}`), la
  ausencia de prefijo o un hash truncado o con caracteres de más detienen el
  arranque.
- **Costo permitido: de 10 a 14. Recomendado: 12.** Otro costo detiene el
  arranque (decisión D-20261008-33).

### Costo y latencia en un VPS de 1 vCPU

Cada intento de login, acertado o no, hace un cálculo BCrypt completo. El costo
duplica el tiempo con cada punto. Los tiempos siguientes son **aproximados**,
para un núcleo de un VPS común; midalo en el servidor real antes de decidir:

| Costo | Tiempo por intento (aproximado) |
|---|---|
| 10 | 0,05 a 0,15 s |
| 11 | 0,1 a 0,3 s |
| 12 (recomendado) | 0,25 a 0,6 s |
| 13 | 0,5 a 1,2 s |
| 14 | 1 a 2,5 s |

Con un solo núcleo, un costo alto encarece cada intento fallido y deja menos
margen para el resto de la aplicación mientras alguien prueba contraseñas. Por
eso 12 es el equilibrio recomendado y 14 es el máximo.

## Contraseña: máximo de 72 bytes

BCrypt solo lee los primeros 72 bytes de la contraseña. La aplicación rechaza
sin calcular cualquier intento de más de 72 bytes UTF-8, así que **una
contraseña más larga nunca podrá iniciar sesión**. Elija una de hasta 72 bytes
(los caracteres con tilde o símbolos ocupan más de un byte). Decisión
D-20261008-34.

## `$` escrito como `$$` en archivos compose

Docker Compose interpreta `$` como inicio de una variable. Un hash BCrypt
contiene varios `$`, por lo que en un archivo `compose` o `.env` leído por
Compose **cada `$` debe escribirse `$$`**. Si se olvida el escape, Compose
sustituye fragmentos por variables vacías, el valor llega alterado y la
aplicación se niega a arrancar. Por ejemplo, el fragmento `$2a$12$abc` se
escribe `$$2a$$12$$abc`.

## Espacios y `\r` perdidos

Un espacio al principio o al final, un salto de línea o un `\r` (frecuente en
archivos de entorno editados en Windows) vuelven inválido el hash y también el
usuario. Se rechazan a propósito, sin recortarlos: un valor corregido en
silencio ocultaría un error de despliegue. Si el arranque falla, revise:

1. espacios o tabulaciones antes o después del valor;
2. saltos de línea (`\r\n`) en el archivo de entorno: guárdelo con finales de
   línea `LF`;
3. el escape `$$` en archivos compose;
4. comillas que hayan quedado dentro del valor.

## Reglas del usuario

- De 1 a 64 caracteres.
- Sin espacios, caracteres de control, de formato ni sin asignar.
- No puede contener `${` (una variable sin resolver no se acepta como nombre).

Decisión D-20261008-35.

## Cómo generar el hash

El hash se genera con el comando de aprovisionamiento que viene en el mismo jar de
la aplicación (`AdminPasswordHashCommand`). No necesita base de datos ni variables
de entorno, no arranca Spring y no escribe logs.

```sh
java -Dloader.main=ar.cetobhb.admin.security.AdminPasswordHashCommand \
     -cp ceto-backend-0.0.1-SNAPSHOT.jar \
     org.springframework.boot.loader.launch.PropertiesLauncher
```

- **En una terminal** pide la contraseña **sin eco** y la pide dos veces; si no
  coinciden, no emite nada.
- **Con la entrada redirigida** (`printf '%s\n' "$CLAVE" | java ...`) lee la primera línea
  de la entrada estándar, sin terminador de línea y respetando los espacios. Es para
  scripts; no pegue la contraseña en la línea de comandos de un historial compartido.
- **Nunca acepta la contraseña como argumento**: cualquier argumento se rechaza antes de
  leer nada (código de salida 2), porque una línea de comandos queda en el historial del
  intérprete y en la lista de procesos.
- Rechaza (código de salida 1, sin salida estándar) una contraseña de **menos de 14
  caracteres** o de **más de 72 bytes en UTF-8**: con esa longitud el login no podría
  aceptarla nunca (ver arriba).
- Escribe **solo el hash** en la salida estándar, en una línea terminada en LF (sin CR),
  con el prefijo `{bcrypt}` y costo 12; los mensajes van a la salida de error y nunca
  contienen la contraseña. Es el valor de `ADMIN_PASSWORD_HASH`.

La salida tiene la forma del formato descrito arriba (prefijo `{bcrypt}`, versión `2a`, costo `12` y 53 caracteres del alfabeto BCrypt); no se incluye aquí un ejemplo literal porque `NoCommittedHashTest` rechaza cualquier hash escrito en el repositorio.

Recuerde el escape **`$$`** al pegar el valor en un archivo compose (sección anterior). Con
las variables en un archivo de entorno que Compose lee, cada `$` del hash va escrito
`$$`.

**Cambiar la contraseña** no tiene endpoint (AUTH-14): se genera un hash nuevo con este
comando, se reemplaza `ADMIN_PASSWORD_HASH` y se vuelve a desplegar. Las sesiones viven
en memoria, así que un despliegue cierra la sesión abierta.

El comando está probado en `AdminPasswordHashCommandTest` (reglas, ausencia de la
contraseña en la salida, buffers borrados) y `AdminPasswordHashCommandProcessTest` (una
JVM aparte: códigos de salida y qué va a cada flujo).

## Sesión de administración

| Tema | Valor |
|---|---|
| Cookie | `JSESSIONID`, `HttpOnly`, `Secure`, `SameSite=Strict`. La aplicación **no arranca** si `server.servlet.session.cookie.secure`, `http-only`, `same-site` (`strict`) o `tracking-modes` (`cookie`) tienen otro valor, en cualquier perfil (`AdminSessionGuard`). `Secure` exige que el navegador llegue por HTTPS (Caddy). |
| Una sola sesión | Un login nuevo cierra la sesión anterior, que recibe 401 en su próxima petición. |
| Inactividad | `ADMIN_SESSION_IDLE_MINUTES` (480 por defecto; de 5 a 1440). Pasado ese tiempo sin peticiones, la sesión termina y la siguiente petición es 401. |
| Memoria | Las sesiones están en memoria: un reinicio o un despliegue las cierra (aceptado). |

