# Pruebas de mutación (PIT)

Los tests verdes dicen que el código pasa; no dicen cuánto detectarían un
error. La prueba de mutación lo mide: PIT introduce cambios pequeños en el
código de producción (invertir un `if`, mover un borde `<` a `<=`, borrar una
llamada, devolver otro valor) y vuelve a correr los tests. Si algún test falla,
el mutante está **muerto** (bien). Si todos pasan, el mutante **sobrevive**: ese
cambio no lo detecta ningún test y esa línea merece lectura.

Es una corrida **manual**. No forma parte de `./mvnw clean verify` (tarda unos
18 minutos) ni del CI. Las dependencias están en `docs/dependencias.md`
(sección "Mutation testing", pendiente de revisión de Mateo).

## Cómo correrla

Desde `backend/`, con Docker encendido (las ITs usan Testcontainers) y sin otras
suites pesadas corriendo a la vez:

```bash
# Dominio puro, con tests unitarios (rápido, ~3 min)
./mvnw -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage@domain

# Servicios críticos, con sus ITs sobre Postgres real (~15 min)
./mvnw -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage@services
```

Los informes quedan en `backend/target/pit-reports/domain` y
`backend/target/pit-reports/services` (ignorados por git). Abrir `index.html`
para ver cada clase con sus mutantes sobrevivientes en rojo; `mutations.xml`
trae el detalle para procesarlo.

Qué se muta:

| Corrida | Clases mutadas | Tests que se usan |
|---|---|---|
| `domain` | `catalog.domain`, `inventory.domain`, `orders.domain`, `common.domain` | solo `*Test` (unitarios) |
| `services` | `InventoryService`, `CatalogService`, `orders.service.*` | `*Test` y `*IT` |

Cada JVM de PIT levanta su propio contenedor de Postgres, por eso la corrida
`services` usa solo 2 hilos. Los mutantes que cuelgan una ráfaga o un lock
cuentan como detectados (`TIMED_OUT`).

## Cómo leer el resultado

- **Mutation score** = mutantes muertos (incluye `TIMED_OUT`) / mutantes
  generados. Más alto es mejor, pero no hay que perseguir 100 %.
- **No coverage**: ningún test de esa corrida ejecuta la línea. En la corrida
  `domain` es esperable para clases que solo ejercitan las ITs (por ejemplo
  `catalog.domain.ProductSnapshot`); no significa que no tengan prueba.
- **Equivalentes**: algunos sobrevivientes no cambian el comportamiento (por
  ejemplo un borde en un `if` cuyo valor no importa). Se leen y se descartan a
  mano; no se agregan tests para ellos.
- Prioridad de lectura: sobrevivientes en `orders.service`,
  `inventory.service` y dinero/stock/estados. Un `toString` o un mensaje de
  log que sobrevive es ruido.

## Línea base (2026-10-07)

Corrida con PIT 1.30.0, sobre la rama `chore/pit-mutation-testing`.

| Corrida | Paquete | Mutantes | Muertos | Sobreviven | Sin cobertura | Score |
|---|---|---|---|---|---|---|
| domain | `common.domain` | 56 | 49 | 7 | 0 | 88 % |
| domain | `orders.domain` | 79 | 72 | 3 | 4 | 91 % |
| domain | `catalog.domain` | 26 | 14 | 1 | 11 | 54 % |
| domain | `inventory.domain` | 17 | 15 | 1 | 1 | 88 % |
| services | `orders.service` | 78 | 68 | 10 | 0 | 87 % |
| services | `inventory.service` | 27 | 27 | 0 | 0 | 100 % |
| services | `catalog.service` | 22 | 21 | 0 | 1 | 95 % |
| **Total** | | **305** | **266** | **39 (incl. sin cobertura)** | | **87,2 %** |

Cobertura de líneas (solo clases mutadas): `domain` 214/249 (86 %), `services`
262/268 (98 %). Duración: `domain` ~3 min, `services` ~15 min.

Si PIT informa "0 tests examined" tras actualizar JUnit o Spring Boot, el
plugin `pitest-junit5-plugin` no es compatible con la nueva versión de JUnit
Platform: ver la nota en `docs/dependencias.md`.
