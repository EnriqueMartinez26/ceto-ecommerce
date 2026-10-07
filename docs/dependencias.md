# Verificación de dependencias no gestionadas por Spring Boot

Regla: CLAUDE.md §1 (dependencias nuevas solo con verificación humana,
contra slopsquatting). Cada fila dice quién verificó y con qué evidencia.

## G0 — 2026-10-07

Verificación hecha por el asistente con aprobación previa delegada por Mateo
("tenés mi go aprobado desde ya", 2026-10-07). **Pendiente de revisión de
Mateo** sobre esta evidencia.

| Coordenadas | Evidencia en Maven Central | Publica | Licencia |
|---|---|---|---|
| `com.tngtech.archunit:archunit-junit5:1.5.1` | `maven-metadata.xml`: latest/release 1.5.1, actualizado 2026-09-25; versiones previas 1.5.0, 1.4.2, 1.4.1, 1.4.0. POM: url y scm `https://github.com/TNG/ArchUnit`. | TNG Technology Consulting GmbH (Peter Gafert, Roland Weisleder, Manfred Hanke y otros) | Apache 2.0 |
| `com.googlecode.libphonenumber:libphonenumber:9.0.40` | `maven-metadata.xml`: latest/release 9.0.40, actualizado 2026-09-23; versiones previas 9.0.39 a 9.0.36. POM padre `libphonenumber-parent:9.0.40`: url `https://github.com/google/libphonenumber/`, scm `github.com/google/libphonenumber.git`. | Google (Shaopeng Jia, Lara Rennie) | Apache 2.0 |

Estado en el `pom.xml`: `archunit-junit5` 1.5.1 (slice S2) y
`libphonenumber` 9.0.40 (slice S6) ya están declaradas con versión fija.
`spring-boot-starter-validation` está gestionada por el BOM de Spring Boot
y no requiere verificación aparte (criterio del asistente, a confirmar por Mateo).

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
