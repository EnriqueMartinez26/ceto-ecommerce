package ar.cetobhb.common.domain;

/**
 * Base type for business-rule violations. The message carries codes and ids only, never personal
 * data (CLAUDE.md section 5).
 */
public class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DomainException(String message) {
        super(message);
    }
}
