package streaming.core.accounts.administration.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdministrationStore {
    record Account(String userId, String handle, String status, long accountVersion) { }
    record Operation(String target, String status, long expectedVersion, String reason, Account result) { }
    boolean isAdministrator(String userId);
    void lock(String actor, String target, UUID operationId);
    List<Account> accounts(String after, int limit);
    Optional<Account> account(String userId);
    Optional<Operation> operation(String actor, UUID operationId);
    Account change(String target, String status, Instant now);
    void record(String actor, UUID key, Operation operation, Instant now);
    void enqueue(String userId, long version, UUID eventId, String envelope, Instant now);
}
