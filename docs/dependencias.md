# Verificación de dependencias no gestionadas por Spring Boot

Regla: CLAUDE.md §1 (dependencias nuevas solo con verificación humana,
contra slopsquatting). Cada fila dice quién verificó y con qué evidencia.

## G0 — 2026-10-07

Verificación hecha por el asistente con aprobación previa delegada por Mateo
("tenés mi go aprobado desde ya", 2026-10-07). Evidencia revisada y
aprobada por Mateo el 2026-10-07.

| Coordenadas | Evidencia en Maven Central | Publica | Licencia |
|---|---|---|---|
| `com.tngtech.archunit:archunit-junit5:1.5.1` | `maven-metadata.xml`: latest/release 1.5.1, actualizado 2026-09-25; versiones previas 1.5.0, 1.4.2, 1.4.1, 1.4.0. POM: url y scm `https://github.com/TNG/ArchUnit`. | TNG Technology Consulting GmbH (Peter Gafert, Roland Weisleder, Manfred Hanke y otros) | Apache 2.0 |
| `com.googlecode.libphonenumber:libphonenumber:9.0.40` | `maven-metadata.xml`: latest/release 9.0.40, actualizado 2026-09-23; versiones previas 9.0.39 a 9.0.36. POM padre `libphonenumber-parent:9.0.40`: url `https://github.com/google/libphonenumber/`, scm `github.com/google/libphonenumber.git`. | Google (Shaopeng Jia, Lara Rennie) | Apache 2.0 |

Estado en el `pom.xml`: `archunit-junit5` 1.5.1 (slice S2) y
`libphonenumber` 9.0.40 (slice S6) ya están declaradas con versión fija.
`spring-boot-starter-validation` está gestionada por el BOM de Spring Boot
y no requiere verificación aparte (criterio del asistente, confirmado por Mateo el 2026-10-07).

Fuentes:
- https://repo.maven.apache.org/maven2/com/tngtech/archunit/archunit-junit5/maven-metadata.xml
- https://repo.maven.apache.org/maven2/com/tngtech/archunit/archunit-junit5/1.5.1/archunit-junit5-1.5.1.pom
- https://repo.maven.apache.org/maven2/com/googlecode/libphonenumber/libphonenumber/maven-metadata.xml
- https://repo.maven.apache.org/maven2/com/googlecode/libphonenumber/libphonenumber-parent/9.0.40/libphonenumber-parent-9.0.40.pom

Nota: la verificación previa encontró en la página de GitHub una fecha
("September 2024") que no coincide con el jar de Maven Central (2026-09-23).
La evidencia de Maven Central (metadata, POM padre de Google, historial de
versiones consecutivas) es consistente; la discrepancia se atribuye a la
página leída, no al artefacto.

## Mutation testing — 2026-10-07

**Verificado por el asistente; pendiente de revisión de Mateo.** Mateo todavía
no aprobó estas dependencias. Son solo de build (perfil Maven `mutation`,
fuera de `./mvnw clean verify`); no entran al classpath de runtime ni a la imagen.

| Coordenadas | Evidencia en Maven Central | Publica | Licencia |
|---|---|---|---|
| `org.pitest:pitest-maven:1.30.0` (plugin) | `maven-metadata.xml`: latest/release 1.30.0, actualizado 2026-08-27 (POM publicado el 2026-08-27); 159 versiones, la más antigua con POM de 2020 y 1.15.0 de 2023. POM padre `pitest-parent:1.30.0`: url `https://pitest.org`, scm `https://github.com/hcoles/pitest`. | Henry Coles (proyecto PIT, `org.pitest`) | Apache 2.0 |
| `org.pitest:pitest-junit5-plugin:1.2.3` | `maven-metadata.xml`: latest/release 1.2.3, actualizado 2025-05-20; versiones previas 1.2.2, 1.2.1, 1.2.0, 1.1.2; primera versión 0.2 y 1.0.0 de 2022-06. POM: url `http://pitest.org`, scm `https://github.com/pitest/pitest-junit5-plugin`, desarrollador Henry Coles. | Henry Coles (proyecto PIT, `org.pitest`) | Apache 2.0 |

Notas:
- El plugin de JUnit 5 oficial es `org.pitest:pitest-junit5-plugin`. El grupo
  `com.arcmutate:pitest-junit5-plugin` no existe en Maven Central (HTTP 404 en
  su `maven-metadata.xml`); Arcmutate es la oferta comercial del mismo autor y
  no se usa.
- El plugin 1.2.3 se compiló contra `junit-platform` 1.9.2, pero el proyecto usa
  JUnit Platform 6.0.3 (Spring Boot 4.1). Verificado con una corrida real: PIT
  descubre y ejecuta los tests (unitarios e ITs) sin errores. Si una versión
  futura de JUnit lo rompe, el síntoma es "0 tests examined".
- Versión fijada en `backend/pom.xml` como propiedades `pitest-maven.version` y
  `pitest-junit5-plugin.version`.

Fuentes:
- https://repo.maven.apache.org/maven2/org/pitest/pitest-maven/maven-metadata.xml
- https://repo.maven.apache.org/maven2/org/pitest/pitest-maven/1.30.0/pitest-maven-1.30.0.pom
- https://repo.maven.apache.org/maven2/org/pitest/pitest-parent/1.30.0/pitest-parent-1.30.0.pom
- https://repo.maven.apache.org/maven2/org/pitest/pitest-junit5-plugin/maven-metadata.xml
- https://repo.maven.apache.org/maven2/org/pitest/pitest-junit5-plugin/1.2.3/pitest-junit5-plugin-1.2.3.pom
