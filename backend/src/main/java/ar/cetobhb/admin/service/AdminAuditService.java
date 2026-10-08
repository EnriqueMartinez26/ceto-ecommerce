package ar.cetobhb.admin.service;

import ar.cetobhb.admin.domain.AuditEntry;
import ar.cetobhb.admin.repository.AdminAuditLog;
import ar.cetobhb.admin.repository.AdminAuditLogRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes audit facts in the CALLER's transaction (AD-A14): the facade changes the domain state and records the fact
 * in one commit, or neither. MANDATORY makes a call outside a transaction fail instead of committing a fact on its
 * own, and a caller that rolls back takes the row with it. The timestamp comes from the injected clock.
 */
@Service
public class AdminAuditService {

    private final AdminAuditLogRepository log;
    private final Clock clock;

    public AdminAuditService(AdminAuditLogRepository log, Clock clock) {
        this.log = log;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        log.save(AdminAuditLog.of(entry, clock.instant()));
    }
}
