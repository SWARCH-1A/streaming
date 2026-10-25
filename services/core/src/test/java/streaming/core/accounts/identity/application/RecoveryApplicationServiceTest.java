package streaming.core.accounts.identity.application;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecoveryApplicationServiceTest {
    private static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
    private static final String HMAC="fixture-only-hmac-at-least-32-bytes";
    private final RecoveryStore store=mock(RecoveryStore.class);
    private final TransactionTemplate tx=mock(TransactionTemplate.class);
    private final String recovery="rec_"+"a".repeat(32),delivery="del_"+"b".repeat(32);
    private RecoveryApplicationService service;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        doAnswer(call->call.<TransactionCallback<Object>>getArgument(0).doInTransaction(new SimpleTransactionStatus()))
                .when(tx).execute(any());
        service=new RecoveryApplicationService(store,tx,mock(PasswordEncoder.class),JsonMapper.builder().build(),
                true,KEY,HMAC,"https://localhost:3445",java.time.Clock.systemUTC());
        state(5,"RETRYABLE_FAILURE");
    }
    @Test void operatorRedriveCanContinueBeyondThePreviousOperationalBudget() {
        var result=service.claim(UUID.randomUUID(),recovery,delivery,6);
        assertThat(result.attempt()).isEqualTo(6);
        assertThat(result.resetUrl()).endsWith("#token="+"t".repeat(43));
        verify(store).claim(recovery,6);
    }
    @Test void skippingAnAttemptStillConflicts() {
        assertThatThrownBy(()->service.claim(UUID.randomUUID(),recovery,delivery,7))
                .isInstanceOfSatisfying(IdentityException.class,error->assertThat(error.status().value()).isEqualTo(409));
        verify(store,never()).claim(anyString(),anyInt());
    }
    @Test void repeatedClaimKeepsItsLogicalAttempt() {
        state(5,"CLAIMED");
        assertThat(service.claim(UUID.randomUUID(),recovery,delivery,5).attempt()).isEqualTo(5);
        verify(store,never()).claim(anyString(),anyInt());
    }
    @Test void zeroAndNegativeAttemptsRemainInvalid() {
        for(int attempt:new int[]{0,-1}) assertThatThrownBy(()->service.claim(UUID.randomUUID(),recovery,delivery,attempt))
                .isInstanceOfSatisfying(IdentityException.class,error->assertThat(error.status().value()).isEqualTo(400));
    }
    private void state(int attempt,String status) {
        String material=new RecoverySecrets(KEY,HMAC).encrypt("fixture@example.test\n"+"t".repeat(43),recovery,delivery);
        when(store.recoveryForUpdate(recovery,delivery)).thenReturn(Optional.of(new RecoveryStore.Recovery(recovery,delivery,
                "usr_fixture","c".repeat(64),material,Instant.now().plusSeconds(1800),null,null,1,status,attempt)));
    }
}
