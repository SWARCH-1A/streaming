package streaming.core.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/** A second connector, never selected by forwarded headers or by the caller's Host header. */
@Component
public class PrivateCoreListener implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    private final boolean enabled;
    private final int port;
    private final boolean developmentHttp;
    private final String keystore;
    private final String keystorePassword;
    private final byte[] token;
    private final byte[] catalogToken;
    private Connector connector;

    public PrivateCoreListener(@Value("${core.internal.enabled:false}") boolean enabled,
            @Value("${core.internal.port:8082}") int port,
            @Value("${core.internal.development-http:false}") boolean developmentHttp,
            @Value("${core.internal.tls-keystore:}") String keystore,
            @Value("${core.internal.tls-keystore-password:}") String keystorePassword,
            @Value("${core.internal.streaming-service-token:}") String token,
            @Value("${core.internal.streaming-catalog-service-token:}") String catalogToken) {
        this.enabled=enabled; this.port=port; this.developmentHttp=developmentHttp;
        this.keystore=keystore; this.keystorePassword=keystorePassword;
        this.token=token.getBytes(StandardCharsets.UTF_8);
        this.catalogToken=catalogToken.getBytes(StandardCharsets.UTF_8);
        if(enabled && (!token.matches("[A-Za-z0-9_-]{32,256}") || port<0 || port>65535))
            throw new IllegalStateException("Private Core requires a valid port and a 32+ character service secret");
        if(enabled && !catalogToken.isEmpty() && (!catalogToken.matches("[A-Za-z0-9_-]{32,256}")
                || MessageDigest.isEqual(this.token,this.catalogToken)))
            throw new IllegalStateException("Private Core requires a distinct 32+ character catalog-only secret when configured");
        if(enabled && !developmentHttp && (keystore.isBlank() || keystorePassword.isBlank()))
            throw new IllegalStateException("Private Core requires a PKCS12 keystore and its password; HTTP must be explicitly enabled for isolated development");
    }

    @Override public void customize(TomcatServletWebServerFactory factory) {
        if(!enabled) return;
        if(port!=0 && port==factory.getPort()) throw new IllegalStateException("Public and private Core ports must differ");
        connector=new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(port);
        connector.setProperty("maxPostSize","16384");
        if(!developmentHttp) {
            connector.setScheme("https"); connector.setSecure(true); connector.setProperty("SSLEnabled","true");
            var ssl=new SSLHostConfig();
            ssl.setProtocols("TLSv1.2,TLSv1.3");
            var certificate=new SSLHostConfigCertificate(ssl,SSLHostConfigCertificate.Type.UNDEFINED);
            certificate.setCertificateKeystoreFile(keystore);
            certificate.setCertificateKeystorePassword(keystorePassword);
            certificate.setCertificateKeystoreType("PKCS12");
            ssl.addCertificate(certificate); connector.addSslHostConfig(ssl);
        }
        factory.addAdditionalConnectors(connector);
    }

    public int localPort() { return connector==null?-1:connector.getLocalPort(); }
    public boolean isPrivate(HttpServletRequest request) { return enabled && localPort()>0 && request.getLocalPort()==localPort(); }
    public enum Permission { OWNER_CONTEXT, CATALOG_VALUES, DISCOVERY_EVENTS }

    public boolean permits(HttpServletRequest request,Permission permission) {
        if(!isPrivate(request) || !"streaming".equals(request.getHeader("X-Service-Name"))) return false;
        String supplied=request.getHeader("X-Service-Token");
        if(supplied==null) return false;
        byte[] candidate=supplied.getBytes(StandardCharsets.UTF_8);
        boolean full=MessageDigest.isEqual(token,candidate);
        boolean catalogOnly=catalogToken.length>0 && MessageDigest.isEqual(catalogToken,candidate);
        return switch(permission) {
            case OWNER_CONTEXT, DISCOVERY_EVENTS -> full;
            case CATALOG_VALUES -> full || catalogOnly;
        };
    }
}
