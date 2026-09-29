package streaming.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import streaming.identity.application.IdentityStore.Account;
import streaming.identity.application.IdentityStore.PublicIdentity;
import streaming.identity.application.IdentityStore.Registration;
import streaming.identity.application.IdentityStore.RegistrationDraft;
import streaming.identity.application.IdentityStore.SessionIdentity;
import streaming.identity.domain.IdentityRules;

@Service
public class IdentityApplicationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final IdentityStore store;
    private final ChannelProvisioner channels;
    private final PasswordEncoder passwords;
    private final String rateSecret;
    private static final Duration REGISTRATION_TTL=Duration.ofHours(24);
    private static final Duration SESSION_TTL=Duration.ofHours(24);
    private final String dummyHash;

    public IdentityApplicationService(IdentityStore store, ChannelProvisioner channels, PasswordEncoder passwords,
            @Value("${identity.rate-limit-hmac-secret}") String rateSecret) {
        this.store=store; this.channels=channels; this.passwords=passwords; this.rateSecret=rateSecret;
        this.dummyHash=passwords.encode("not-a-real-user-password");
    }

    public RegistrationView register(UUID key, String emailInput, String handleInput, String password, String remoteIp) {
        if (key == null) throw bad("IDEMPOTENCY_KEY_REQUIRED", "Se requiere Idempotency-Key UUID.");
        String email,handle;
        try { email=IdentityRules.canonicalEmail(emailInput); handle=IdentityRules.canonicalHandle(handleInput); IdentityRules.validatePassword(password); }
        catch(IllegalArgumentException e) { throw bad("VALIDATION_ERROR",e.getMessage()); }
        String keyHash=sha256(key.toString());
        String fingerprint=hmac(email+"\0"+handle+"\0"+password);
        Registration r=store.registrationByKeyHash(keyHash).orElse(null);
        if (r != null) {
            if (!constantEquals(r.fingerprint(),fingerprint)) throw conflict("IDEMPOTENCY_KEY_REUSED", "La clave de idempotencia ya se usó con otros datos.");
            return view(process(r));
        }
        Instant now=Instant.now();
        String ipHash=hmac(remoteIp == null ? "unknown" : remoteIp);
        if (!store.tryRecordRegistrationKey(ipHash,keyHash,now)) throw limited(3600);
        String registrationId="reg_"+randomId(18);
        String userId="usr_"+randomId(18);
        RegistrationDraft draft=new RegistrationDraft(registrationId,keyHash,fingerprint,email,email,handle,passwords.encode(password),userId,now,now.plus(REGISTRATION_TTL),now.plus(Duration.ofDays(30)));
        try { r=store.reserve(draft); }
        catch (org.springframework.dao.DuplicateKeyException e) {
            Registration raced=store.registrationByKeyHash(keyHash).orElse(null);
            if(raced!=null) {
                if(!constantEquals(raced.fingerprint(),fingerprint)) throw conflict("IDEMPOTENCY_KEY_REUSED", "La clave de idempotencia ya se usó con otros datos.");
                return view(process(raced));
            }
            throw conflict("REGISTRATION_UNAVAILABLE", "No fue posible completar el registro con esos datos.");
        }
        return view(process(r));
    }

    public RegistrationView registrationStatus(String id, UUID key) {
        if (key == null) throw bad("IDEMPOTENCY_KEY_REQUIRED", "Se requiere la misma Idempotency-Key.");
        Registration r=store.registration(id,sha256(key.toString())).orElseThrow(() -> notFound("REGISTRATION_NOT_FOUND", "No se encontró la operación de registro."));
        return view(process(r));
    }

    private Registration process(Registration r) {
        if ("ACTIVE".equals(r.status()) || "EXPIRED".equals(r.status())) return r;
        Instant now=Instant.now();
        if (!now.isBefore(r.deadline())) {
            store.expire(r.registrationId(),now);
            compensate(r.registrationId());
            return store.registration(r.registrationId(),r.keyHash()).orElse(r);
        }
        try {
            Optional<String> channelId=channels.provision(r.userId(),r.registrationId(),r.deadline());
            if (channelId.isPresent()) {
                if (store.activate(r.registrationId(),channelId.get(),Instant.now())) return store.registration(r.registrationId(),r.keyHash()).orElse(r);
                Registration current=store.registration(r.registrationId(),r.keyHash()).orElse(r);
                if ("ACTIVE".equals(current.status())) return current;
                if (!Instant.now().isBefore(current.deadline())) {
                    store.expire(r.registrationId(),Instant.now());
                    compensate(r.registrationId());
                } else scheduleRetry(current);
            } else {
                scheduleRetry(r);
            }
        } catch (RuntimeException unavailable) {
            scheduleRetry(r);
        }
        return store.registration(r.registrationId(),r.keyHash()).orElse(r);
    }

    private void scheduleRetry(Registration r) {
        long seconds=Math.min(300, 1L << Math.min(9, r.attemptCount()));
        store.scheduleRetry(r.registrationId(),Instant.now().plusSeconds(seconds));
    }

    private void compensate(String registrationId) {
        try {
            ChannelProvisioner.ProvisionState state=channels.find(registrationId);
            if (state==ChannelProvisioner.ProvisionState.PROVISIONED) channels.compensate(registrationId);
            if (state==ChannelProvisioner.ProvisionState.PROVISIONED || state==ChannelProvisioner.ProvisionState.ABSENT) store.markCompensationComplete(registrationId);
        } catch (RuntimeException ignored) { /* durable expired row is retried by the scheduled reconciler */ }
    }

    @Scheduled(fixedDelayString="${identity.registration-worker-delay:PT1S}")
    public void reconcileRegistrations() {
        Instant now=Instant.now();
        for (Registration r:store.dueRegistrations(now,50)) process(r);
        for (Registration r:store.expiredNeedingCompensation(now,50)) compensate(r.registrationId());
    }
    @Scheduled(fixedDelayString="PT1H")
    public void purgeExpiredData() { store.purgeExpired(Instant.now()); }

    public LoginResult login(String loginInput, String password, String remoteIp) {
        String login=loginInput == null ? "" : loginInput.trim().toLowerCase(Locale.ROOT);
        boolean email=login.contains("@");
        if (!email && !login.matches("[a-z0-9_]{4,25}")) throw invalidLogin();
        String identifierHash=hmac(login);
        String ipHash=hmac(remoteIp == null ? "unknown" : remoteIp);
        Instant now=Instant.now();
        if (store.loginLimitReached(identifierHash,ipHash,now.minus(Duration.ofMinutes(15)))) throw limited(900);
        Account account=store.activeByLogin(login,email).orElse(null);
        String supplied=password == null ? "" : password;
        boolean withinLimit=supplied.codePointCount(0,supplied.length())<=128;
        boolean matches=withinLimit && passwords.matches(supplied, account == null ? dummyHash : account.passwordHash());
        if (account==null || !matches) {
            if (!store.tryRecordLoginFailure(identifierHash,ipHash,Instant.now())) throw limited(900);
            throw invalidLogin();
        }
        store.resetIdentifierFailures(identifierHash);
        String credential=newCredential();
        Instant expires=Instant.now().plus(SESSION_TTL);
        store.createSession(account.userId(),sha256(credential),Instant.now(),expires);
        return new LoginResult(account.userId(),account.handle(),credential,expires);
    }

    // Used only to equalize the password-hash path when an account does not exist.
    public void logout(String credential) { if (credential!=null && !credential.isBlank()) store.revokeSession(sha256(credential),Instant.now()); }

    public Optional<SessionView> introspect(String credential) {
        if (credential==null || credential.isBlank()) return Optional.empty();
        return store.introspect(sha256(credential),Instant.now()).map(s->new SessionView(s.userId(),s.handle(),s.expiresAt()));
    }

    public PublicIdentityView publicByUserId(String userId) {
        return store.activeByUserId(userId).map(i->new PublicIdentityView(i.userId(),i.handle()))
                .orElseThrow(()->notFound("IDENTITY_NOT_FOUND","No se encontró la identidad pública."));
    }
    public PublicIdentityView publicByHandle(String handleInput) {
        String handle;
        try { handle=IdentityRules.canonicalHandle(handleInput); }
        catch(IllegalArgumentException e) { throw bad("VALIDATION_ERROR",e.getMessage()); }
        return store.activeByHandle(handle).map(i->new PublicIdentityView(i.userId(),i.handle()))
                .orElseThrow(()->notFound("IDENTITY_NOT_FOUND","No se encontró la identidad pública."));
    }

    private RegistrationView view(Registration r) {
        if ("EXPIRED".equals(r.status())) throw new IdentityException(HttpStatus.GONE,"REGISTRATION_EXPIRED","El plazo de registro venció.");
        boolean active="ACTIVE".equals(r.status());
        return new RegistrationView(r.registrationId(),r.status(),active?r.userId():null,active?r.channelId():null,2);
    }
    private static String newCredential() { byte[] b=new byte[32]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String randomId(int size) { byte[] b=new byte[size]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String hmac(String value) {
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(rateSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static boolean constantEquals(String a,String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8)); }
    private static IdentityException invalidLogin() { return new IdentityException(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","Las credenciales no son válidas."); }
    private static IdentityException limited(long seconds) { return new IdentityException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Se alcanzó el límite temporal de solicitudes.",seconds); }
    private static IdentityException bad(String code,String text) { return new IdentityException(HttpStatus.BAD_REQUEST,code,text); }
    private static IdentityException conflict(String code,String text) { return new IdentityException(HttpStatus.CONFLICT,code,text); }
    private static IdentityException notFound(String code,String text) { return new IdentityException(HttpStatus.NOT_FOUND,code,text); }

    public record RegistrationView(String registrationId,String status,String userId,String channelId,int retryAfterSeconds) { }
    public record LoginResult(String userId,String handle,String credential,Instant expiresAt) { }
    public record SessionView(String userId,String handle,Instant expiresAt) { }
    public record PublicIdentityView(String userId,String handle) { }
}
