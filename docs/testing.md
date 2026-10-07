# Pruebas de mutación (PIT)

Los tests verdes dicen que el código pasa; no dicen cuánto detectarían un
error. La prueba de mutación lo mide: PIT introduce cambios pequeños en el
código de producción (invertir un `if`, mover un borde `<` a `<=`, borrar una
llamada, devolver otro valor) y vuelve a correr los tests. Si algún test falla,
el mutante está **muerto** (bien). Si todos pasan, el mutante **sobrevive**: ese
cambio no lo detecta ningún test y esa línea merece lectura.

Es una corrida **manual**. No forma parte de `./mvnw clean verify` (tarda unos
20 minutos) ni del CI. Las dependencias están en `docs/dependencias.md`
(sección "Mutation testing", pendiente de revisión de Mateo).

## Cómo correrla

Desde `backend/`, con Docker encendido (las ITs usan Testcontainers) y sin otras
suites pesadas corriendo a la vez:

```bash
# Dominio puro, con tests unitarios (rápido, ~4 min)
./mvnw -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage@domain

# Servicios críticos, con sus ITs sobre Postgres real (~17 min)
./mvnw -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage@services

# Opcional, contraste: las mismas clases de dominio con TODOS los tests (~25 min)
./mvnw -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage@domain-all
```

Los informes quedan en `backend/target/pit-reports/domain`,
`backend/target/pit-reports/services` y `backend/target/pit-reports/domain-all`
(ignorados por git). Abrir `index.html`
para ver cada clase con sus mutantes sobrevivientes en rojo; `mutations.xml`
trae el detalle para procesarlo.

Qué se muta:

| Corrida | Clases mutadas | Tests que se usan |
|---|---|---|
| `domain` | `catalog.domain`, `inventory.domain`, `orders.domain`, `common.domain` | solo `*Test` (unitarios) |
| `domain-all` | las mismas | `*Test` y `*IT` |
| `services` | `InventoryService`, `CatalogService`, `orders.service.*` | `*Test` y `*IT` |

Por qué `domain` sigue siendo solo unitaria: la primera medición mostró
`catalog.domain` con 54 % porque ningún test unitario la ejercitaba (solo las
ITs), así que el número no decía nada de la calidad de las pruebas. Se corrigió
agregando los tests unitarios que faltaban (`ProductSnapshotTest`, más casos en
`CatalogRulesTest`, `PiiRedactionTest`, `DomainExceptionPayloadTest`) en vez de
sumar las ITs a la corrida: con las ITs adentro, `domain` pasa de ~4 a ~25 min
(cada JVM de PIT que toca una IT levanta su propio contexto de Spring y su
Postgres) y los mutantes igual los matan los tests unitarios. Se comprobó con
`domain-all` (178 mutantes, 177 muertos, ninguno muerto por una IT): da el mismo
resultado que `domain`. Una clase de dominio que quede "sin cobertura" en
`domain` es un test unitario que falta, no una IT.

Cada JVM de PIT levanta su propio contenedor de Postgres, por eso la corrida
`services` usa solo 2 hilos. Los mutantes que cuelgan una ráfaga o un lock
cuentan como detectados (`TIMED_OUT`).

## Cómo leer el resultado

- **Mutation score** = mutantes muertos (incluye `TIMED_OUT`) / mutantes
  generados. Más alto es mejor, pero no hay que perseguir 100 %.
- **No coverage**: ningún test de esa corrida ejecuta la línea. En la corrida
  `domain` (solo unitarios) significa que falta un test unitario, aunque una IT
  pase por esa línea: se agrega el unitario (ver "Por qué `domain` sigue siendo
  solo unitaria").
- **Equivalentes**: algunos sobrevivientes no cambian el comportamiento (por
  ejemplo un borde en un `if` cuyo valor no importa). Se leen y se descartan a
  mano; no se agregan tests para ellos. Los aceptados hoy están al final.
- Prioridad de lectura: sobrevivientes en `orders.service`,
  `inventory.service` y dinero/stock/estados. Un `toString` o un mensaje de
  log que sobrevive es ruido.

## Línea base y resultado (2026-10-07)

Corrida con PIT 1.30.0, sobre la rama `chore/pit-mutation-testing`.

Antes: primera medición (`domain` solo con unitarios, sin los tests que se
agregaron después). La columna "con ITs" es la misma medición con todos los
tests (`domain-all`), para separar lo que era falta de cobertura unitaria de lo
que eran tests flojos. Después: con los tests agregados para matar mutantes.

| Corrida | Paquete | Mutantes | Antes (unitarios) | Antes (con ITs) | Después |
|---|---|---|---|---|---|
| domain | `common.domain` | 56 | 49 (88 %) | 49 (88 %) | 55 (98 %) |
| domain | `orders.domain` | 79 | 72 (91 %) | 72 (91 %) | 79 (100 %) |
| domain | `catalog.domain` | 26 | 14 (54 %) | 25 (96 %) | 26 (100 %) |
| domain | `inventory.domain` | 17 | 15 (88 %) | 17 (100 %) | 17 (100 %) |
| services | `orders.service` | 78 | 68 (87 %) | 68 (87 %) | 78 (100 %) |
| services | `inventory.service` | 27 | 27 (100 %) | 27 (100 %) | 27 (100 %) |
| services | `catalog.service` | 22 | 21 (95 %) | 21 (95 %) | 22 (100 %) |
| **Total** | | **305** | **266 (87,2 %)** | **279 (91,5 %)** | **304 (99,7 %)** |

Cobertura de líneas (solo clases mutadas): `domain` 238/249 (96 %) contra
214/249 (86 %) antes, `services` 262/268 (98 %). Duración: `domain` ~4 min,
`services` ~17 min (`domain-all`, ~25 min).

### Mutantes equivalentes aceptados

| Clase | Línea | Mutación | Por qué es equivalente |
|---|---|---|---|
| `PhoneNumberNormalizer.isAmbiguousArgentineNumber` | 109 | negar `type == FIXED_LINE_OR_MOBILE` | libphonenumber nunca informa `FIXED_LINE_OR_MOBILE` para un número argentino (los tipos AR válidos son `FIXED_LINE`, `MOBILE`, `TOLL_FREE`, `PREMIUM_RATE` y `UAN`; muestreo de 360 000 números candidatos de 10 dígitos). Negar la condición solo sumaría a la ruta ambigua números `TOLL_FREE`/`PREMIUM_RATE`/`UAN`, y con el `9` insertado ninguno es un móvil válido (0 casos), así que se rechazan igual. Depende de los datos de libphonenumber: si una versión nueva cambia eso, el mutante pasaría a ser matable. |

Si PIT informa "0 tests examined" tras actualizar JUnit o Spring Boot, el
plugin `pitest-junit5-plugin` no es compatible con la nueva versión de JUnit
Platform: ver la nota en `docs/dependencias.md`.
