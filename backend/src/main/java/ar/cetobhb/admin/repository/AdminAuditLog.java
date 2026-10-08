package ar.cetobhb.admin.repository;

import ar.cetobhb.admin.domain.AuditEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * One row of the append-only audit log. Immutable: no setters and Hibernate never issues an UPDATE for it (the
 * database trigger refuses one anyway). It holds closed-set values and ids only (D-20261008-11).
 */
@Entity
@Immutable
@Table(name = "admin_audit_log")
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "action", nullable = false, length = 30)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 10)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private long entityId;

    @Column(name = "reason", length = 30)
    private String reason;

    @Column(name = "before_value", precision = 12, scale = 2)
    private BigDecimal beforeValue;

    @Column(name = "after_value", precision = 12, scale = 2)
    private BigDecimal afterValue;

    protected AdminAuditLog() {
    }

    public static AdminAuditLog of(AuditEntry entry, Instant occurredAt) {
        AdminAuditLog row = new AdminAuditLog();
        row.occurredAt = occurredAt;
        row.action = entry.action().name();
        row.entityType = entry.entityType().name();
        row.entityId = entry.entityId();
        row.reason = entry.reason();
        row.beforeValue = entry.beforeValue();
        row.afterValue = entry.afterValue();
        return row;
    }

    public Long id() {
        return id;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public String action() {
        return action;
    }

    public String entityType() {
        return entityType;
    }

    public long entityId() {
        return entityId;
    }

    public String reason() {
        return reason;
    }

    public BigDecimal beforeValue() {
        return beforeValue;
    }

    public BigDecimal afterValue() {
        return afterValue;
    }
}
