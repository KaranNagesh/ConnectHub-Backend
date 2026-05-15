package com.connecthub.auth.service;

import com.connecthub.auth.entity.AuditLog;
import com.connecthub.auth.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * AuditService — Async Audit Trail Logging for Admin Actions
 *
 * PURPOSE:
 *   Provides a persistent audit trail of every admin action taken on the platform
 *   (suspend user, reactivate user, delete user, etc.). These records are stored
 *   in the MySQL database via AuditLogRepository and viewable in the Admin Dashboard.
 *
 *   An audit trail is important for:
 *   - Accountability: knowing which admin performed what action and when
 *   - Security: detecting abuse of admin privileges
 *   - Compliance: having a tamper-evident log for regulatory requirements
 *   - Debugging: tracing the cause of a user's account state change
 *
 * WHY @Async:
 *   The log() method is annotated with @Async so it executes in a separate thread
 *   from the calling request. This means the admin action (e.g., suspending a user)
 *   completes immediately from the caller's perspective — the audit log write happens
 *   in the background. The admin API response is not delayed by a slow database write.
 *
 *   @Async requires Spring's @EnableAsync on a configuration class to be active.
 *   If the async thread pool is busy, Spring queues the write; it will still complete
 *   even if not immediately.
 *
 * AUDIT LOG FIELDS:
 *   - actorId    — the userId of the admin who performed the action
 *   - action     — what was done (e.g., "SUSPEND_USER", "DELETE_USER")
 *   - entityType — what type of entity was affected (e.g., "USER")
 *   - entityId   — the ID of the affected entity as a string
 *   - details    — a human-readable description (e.g., "Suspended user @johndoe")
 *   - ipAddress  — the IP address of the admin making the request
 *   - createdAt  — set automatically by the AuditLog entity's @CreationTimestamp
 *
 * getLogs() PAGINATION:
 *   Audit logs are returned in reverse chronological order (newest first) using
 *   Spring Data's PageRequest. The admin dashboard uses page-based navigation
 *   with Prev/Next buttons, passing the page number to this method.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditRepo;

    /**
     * log — asynchronously saves an audit log entry to the database.
     * The @Async annotation means this method returns immediately and the actual
     * database insert happens on a background thread managed by Spring's task executor.
     *
     * @param actorId    userId of the admin performing the action
     * @param action     action string constant (e.g., "SUSPEND_USER")
     * @param entityType type of entity affected (e.g., "USER")
     * @param entityId   string ID of the affected entity
     * @param details    human-readable description for the audit log entry
     * @param ip         IP address from the admin's HTTP request
     */
    @Async
    @SuppressWarnings("null")
    public void log(int actorId, String action, String entityType, String entityId, String details, String ip) {
        AuditLog entry = AuditLog.builder()
                .actorId(actorId)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .details(details)
                .ipAddress(ip)
                .build();
        auditRepo.save(entry);
        log.info("AUDIT: actor={} action={} entity={}:{}", actorId, action, entityType, entityId);
    }

    /**
     * getLogs — returns a paginated, newest-first list of all audit log entries.
     * Used by the Admin Dashboard's Audit Logs tab with Prev/Next pagination.
     *
     * @param page zero-based page number (0 = most recent entries)
     * @param size number of entries per page
     */
    public Page<AuditLog> getLogs(int page, int size) {
        return auditRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    }
}
