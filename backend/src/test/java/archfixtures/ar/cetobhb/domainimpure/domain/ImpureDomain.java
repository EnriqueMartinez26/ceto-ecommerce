package archfixtures.ar.cetobhb.domainimpure.domain;

import jakarta.persistence.EntityManager;

/**
 * Deliberate violation: a domain class tied to JPA. Only imported by ArchitectureRulesTest.
 * Fixtures live under the {@code archfixtures} root, outside the application base package
 * ({@code ar.cetobhb}), so Spring component and entity scanning never sees them.
 */
public class ImpureDomain {

    private EntityManager entityManager;

    EntityManager entityManager() {
        return entityManager;
    }
}
