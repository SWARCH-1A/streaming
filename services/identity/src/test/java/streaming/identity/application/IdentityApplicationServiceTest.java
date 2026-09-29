package streaming.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import streaming.identity.application.IdentityStore.Account;
import streaming.identity.application.IdentityStore.Registration;
import streaming.identity.application.IdentityStore.RegistrationDraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityApplicationServiceTest {
    @Mock IdentityStore store;
    @Mock ChannelProvisioner channels;
    @Mock PasswordEncoder passwords;
    private IdentityApplicationService service;

    @BeforeEach
    void setUp() {
        when(passwords.encode(anyString())).thenReturn("encoded-password");
        service = new IdentityApplicationService(store, channels, passwords, "unit-test-rate-secret");
    }

    @Test
    void loginReturnsOpaqueCredentialAndPersistsOnlyItsHash() {
        when(store.loginLimitReached(anyString(), anyString(), any(Instant.class))).thenReturn(false);
        when(store.activeByLogin("caster_01", false))
                .thenReturn(Optional.of(new Account("usr_1", "caster_01", "stored-password-hash")));
        when(passwords.matches("  exact pass  ", "stored-password-hash")).thenReturn(true);

        var result = service.login(" CASTER_01 ", "  exact pass  ", "192.0.2.10");

        assertThat(result.credential()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(result.expiresAt()).isAfter(Instant.now());
        ArgumentCaptor<String> credentialHash = ArgumentCaptor.forClass(String.class);
        verify(store).createSession(eq("usr_1"), credentialHash.capture(), any(Instant.class), any(Instant.class));
        assertThat(credentialHash.getValue()).matches("[0-9a-f]{64}").isNotEqualTo(result.credential());
        verify(passwords).matches("  exact pass  ", "stored-password-hash");
        verify(store).resetIdentifierFailures(anyString());
    }

    @Test
    void unavailableChannelLeavesRegistrationPendingAndDoesNotCreateSession() {
        when(store.registrationByKeyHash(anyString())).thenReturn(Optional.empty());
        when(store.tryRecordRegistrationKey(anyString(), anyString(), any(Instant.class))).thenReturn(true);
        AtomicReference<Registration> saved = new AtomicReference<>();
        when(store.reserve(any(RegistrationDraft.class))).thenAnswer(invocation -> {
            RegistrationDraft draft = invocation.getArgument(0);
            Registration registration = new Registration(draft.registrationId(), draft.idempotencyKeyHash(),
                    draft.fingerprint(), "PENDING", draft.userId(), null, draft.now(), draft.deadline(),
                    draft.email(), draft.normalizedEmail(), draft.handle(), draft.passwordHash(), 0);
            saved.set(registration);
            return registration;
        });
        when(store.registration(anyString(), anyString())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(channels.provision(anyString(), anyString(), any(Instant.class))).thenReturn(Optional.empty());

        var result = service.register(UUID.fromString("fcb4b8f4-28c9-4d64-9f93-2e4ba7d04c05"),
                "person@example.test", "Caster_01", "  exact pass  ", "192.0.2.10");

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.userId()).isNull();
        assertThat(result.channelId()).isNull();
        assertThat(result.retryAfterSeconds()).isEqualTo(2);
        verify(store).scheduleRetry(anyString(), any(Instant.class));
        verify(store, never()).createSession(anyString(), anyString(), any(Instant.class), any(Instant.class));
    }
}
