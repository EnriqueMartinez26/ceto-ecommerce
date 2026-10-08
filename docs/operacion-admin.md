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
- **Con la entrada redirigida** lee la primera línea de la entrada estándar, sin
  terminador de línea y respetando los espacios, hasta 1 KB (1024 bytes): una línea más
  larga se rechaza con un mensaje fijo, código de salida 1 y sin repetir nada de ella. Es
  para scripts; cómo pasarle la contraseña sin dejarla en el historial está en la sección
  siguiente.
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

El comando está probado en `AdminPasswordHashCommandTest` (reglas, límite de línea,
ausencia de la contraseña en la salida, buffers propios borrados) y
`AdminPasswordHashCommandProcessTest` (una JVM aparte: códigos de salida y qué va a cada
flujo).

**Alcance del borrado de memoria.** El comando sobrescribe, en la medida de lo posible,
los buffers que él mismo crea y que tuvieron la contraseña. El codificador BCrypt convierte
la contraseña a un `String` por dentro y esa copia no se puede borrar, así que el borrado
reduce la ventana de exposición pero no garantiza que la contraseña desaparezca de la
memoria del proceso. La protección firme es otra: que la contraseña no salga en la salida,
en los argumentos ni en el historial.

**Terminal o entrada redirigida.** El proyecto apunta a Java 21, donde `System.console()`
es `null` apenas la entrada o la salida están redirigidas; el comando lo usa así. En un JDK
posterior (22 o más) la consola existe aunque se redirija, y el comando consulta
`Console.isTerminal()` para decidir; el resultado para el operador es el mismo.

## Ingreso seguro de la contraseña

- **Preferir el modo interactivo**: ejecutar el comando en una terminal; pide la contraseña
  sin eco, dos veces, y no deja nada en el historial.
- **Para scripts**, leer la contraseña sin eco a una variable que no se exporta y pasarla
  por la entrada estándar; después borrarla:

  ```sh
  read -rs CLAVE
  printf '%s\n' "$CLAVE" | java -Dloader.main=ar.cetobhb.admin.security.AdminPasswordHashCommand \
       -cp ceto-backend-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher
  unset CLAVE
  ```

  Nunca escribir `CLAVE=...` en la línea de comandos (queda en el historial y en la lista
  de procesos) ni exportar la variable (la heredan todos los procesos hijos). `printf` es
  una orden interna del intérprete: la contraseña no aparece en la lista de procesos.
- Como medida adicional, `HISTCONTROL=ignorespace` (en bash) permite que una línea que
  empiece con un espacio no se guarde en el historial. Es una ayuda, no una garantía: no
  reemplaza a las reglas anteriores.

## Archivos que contienen `ADMIN_PASSWORD_HASH`

El hash es un verificador que se puede intentar romper sin conexión: quien lo obtenga puede
probar contraseñas en su propia máquina, sin el límite de intentos del login. Por eso:

- El archivo `.env` y el override de compose que lo contienen tienen permisos **600**
  (`chmod 600`), pertenecen al usuario que despliega y **no se versionan**.
- Quedan **fuera de los respaldos sin cifrar** (el respaldo diario de Postgres con copia
  fuera del VPS no debe arrastrar esos archivos en claro).
- Una contraseña larga y única es la defensa real; el costo 12 solo encarece cada intento.

## Desarrollo local con la cookie `Secure`

La cookie de sesión es `Secure` y la aplicación no arranca de otro modo (`AdminSessionGuard`).
En desarrollo local:

- `http://localhost` funciona en Chrome y Firefox, que tratan `localhost` como contexto
  seguro y aceptan cookies `Secure` por HTTP.
- **No** funciona en Safari ni con una IP de la red local (`http://192.168...`) por HTTP:
  el navegador descarta la cookie y el login parece no hacer nada.
- En esos casos usar un **Caddy local con TLS** (`tls internal`) delante de la aplicación.
- **No existe un perfil inseguro y nadie debe agregarlo**: la guarda corre en todos los
  perfiles, y debilitar la cookie para comodidad local es justo lo que impide.

## Sesión de administración

| Tema | Valor |
|---|---|
| Cookie | `JSESSIONID`, `HttpOnly`, `Secure`, `SameSite=Strict`. La aplicación **no arranca** si `server.servlet.session.cookie.secure`, `http-only`, `same-site` (`strict`) o `tracking-modes` (`cookie`) tienen otro valor, en cualquier perfil, tampoco si `cookie.domain` está definido ni si `cookie.name` no es `JSESSIONID`, y la guarda corre aun con `spring.main.lazy-initialization=true` (`AdminSessionGuard`, D-20261008-42). `Secure` exige que el navegador llegue por HTTPS (Caddy). |
| Una sola sesión | Un login nuevo cierra la sesión anterior, que recibe 401 en su próxima petición. El control no es atómico: dos logins correctos simultáneos pueden sobrevivir ambos hasta el siguiente login (límite aceptado, D-20261008-40). |
| Inactividad | `ADMIN_SESSION_IDLE_MINUTES` (480 por defecto; de 5 a 1440). Pasado ese tiempo sin peticiones, la sesión termina y la siguiente petición es 401. |
| Tope absoluto | `ADMIN_SESSION_ABSOLUTE_MINUTES` (720 por defecto, es decir 12 horas; de 60 a 1440). Desde el login, una sesión termina pasado ese tiempo aunque se use sin parar, y la siguiente petición es 401 (D-20261008-39). Un valor fuera de rango detiene el arranque. Es un valor técnico y se puede ajustar. |
| Cookie CSRF | `__Host-XSRF-TOKEN` (`Secure`, `Path=/`, sin `Domain`); la cabecera es `X-XSRF-TOKEN` (D-20261008-41). |
| Memoria | Las sesiones están en memoria: un reinicio o un despliegue las cierra (aceptado). |

