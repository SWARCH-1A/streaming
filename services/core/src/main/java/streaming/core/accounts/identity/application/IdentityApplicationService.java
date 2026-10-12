package streaming.core.accounts.identity.application;

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
import streaming.core.accounts.identity.application.IdentityStore.Account;
import streaming.core.accounts.identity.application.IdentityStore.Registration;
import streaming.core.accounts.identity.application.IdentityStore.RegistrationDraft;
import streaming.core.accounts.identity.domain.IdentityRules;
import streaming.core.accounts.profile.application.ProfileInitializer;
import streaming.core.channels.application.ChannelInitializer;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdentityApplicationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final IdentityStore store;
    private final ChannelInitializer channels;
    private final ProfileInitializer profiles;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwords;
    private final String rateSecret;
    private static final Duration SESSION_TTL=Duration.ofHours(24);
    private final String dummyHash;

    public IdentityApplicationService(IdentityStore store, ChannelInitializer channels, ProfileInitializer profiles, TransactionTemplate transactions, PasswordEncoder passwords,
            @Value("${core.rate-limit-hmac-secret}") String rateSecret) {
        this.store=store; this.channels=channels; this.profiles=profiles; this.transactions=transactions; this.passwords=passwords; this.rateSecret=rateSecret;
        if(rateSecret.getBytes(StandardCharsets.UTF_8).length<32) throw new IllegalArgumentException("CORE_RATE_LIMIT_HMAC_SECRET requiere al menos 32 bytes.");
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
            return view(r);
        }
        Instant now=Instant.now();
        String ipHash=hmac(remoteIp == null ? "unknown" : remoteIp);
        if (!store.tryRecordRegistrationKey(ipHash,keyHash,now)) throw limited(3600);
        String registrationId="reg_"+randomId(18);
        String userId="usr_"+randomId(18);
        RegistrationDraft draft=new RegistrationDraft(registrationId,keyHash,fingerprint,email,email,handle,passwords.encode(password),userId,now,now.plus(Duration.ofDays(30)));
        try {
            r=transactions.execute(status -> {
                store.createAccount(draft);
                profiles.createInitialProfile(draft.userId(),draft.handle(),draft.now());
                String channelId=channels.createInitialChannel(draft.userId(),draft.now());
                return store.saveRegistration(draft,channelId);
            });
        }
        catch (org.springframework.dao.DuplicateKeyException e) {
            Registration raced=store.registrationByKeyHash(keyHash).orElse(null);
            if(raced!=null) {
                if(!constantEquals(raced.fingerprint(),fingerprint)) throw conflict("IDEMPOTENCY_KEY_REUSED", "La clave de idempotencia ya se usó con otros datos.");
                return view(raced);
            }
            throw conflict("REGISTRATION_UNAVAILABLE", "No fue posible completar el registro con esos datos.");
        }
        return view(r);
    }

    public RegistrationView registrationStatus(String id, UUID key) {
        if (key == null) throw bad("IDEMPOTENCY_KEY_REQUIRED", "Se requiere la misma Idempotency-Key.");
        Registration r=store.registration(id,sha256(key.toString())).orElseThrow(() -> notFound("REGISTRATION_NOT_FOUND", "No se encontró la operación de registro."));
        return view(r);
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
        return new RegistrationView(r.registrationId(),"ACTIVE",r.userId(),r.channelId());
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

    public record RegistrationView(String registrationId,String status,String userId,String channelId) { }
    public record LoginResult(String userId,String handle,String credential,Instant expiresAt) { }
    public record SessionView(String userId,String handle,Instant expiresAt) { }
    public record PublicIdentityView(String userId,String handle) { }
}
