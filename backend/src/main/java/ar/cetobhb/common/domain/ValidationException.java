package ar.cetobhb.common.domain;

/** VALIDATION_ERROR(reason): input or state rejected by a business rule. The message carries the code only. */
public class ValidationException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final ReasonCode reason;

    public ValidationException(ReasonCode reason) {
        super("VALIDATION_ERROR(" + reason + ")");
        this.reason = reason;
    }

    public ReasonCode reason() {
        return reason;
    }
}
