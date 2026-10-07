package ar.cetobhb.architecture.fixtures.domainimpure.domain;

import jakarta.persistence.EntityManager;

/**
 * Deliberate violation: a domain class tied to JPA. Only imported by ArchitectureRulesTest.
 * Not annotated with @Entity on purpose: the fixtures sit under the app package, and an entity
 * without an id would break entity scanning in the integration tests.
 */
public class ImpureDomain {

    private EntityManager entityManager;

    EntityManager entityManager() {
        return entityManager;
    }
}
