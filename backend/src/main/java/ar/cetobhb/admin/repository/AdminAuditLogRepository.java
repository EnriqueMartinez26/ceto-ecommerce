package ar.cetobhb.admin.repository;

import org.springframework.data.repository.Repository;

/**
 * Append-only by construction: the only operation is {@code save}. There is no find, update or delete to misuse
 * (the database trigger refuses them as well).
 */
public interface AdminAuditLogRepository extends Repository<AdminAuditLog, Long> {

    AdminAuditLog save(AdminAuditLog entry);
}
