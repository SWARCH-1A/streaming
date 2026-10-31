package streaming.core.accounts.administration.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import streaming.core.accounts.identity.application.IdentityApplicationService;
import streaming.core.accounts.identity.application.IdentityException;
import streaming.core.channels.application.ChannelStore;
import streaming.core.taxonomy.application.CatalogValues;
import java.util.List;
import java.util.Set;

/** Limited private VOD context. Every module reads through its published repository contract. */
@Service
public class VodAccessService {
    private final IdentityApplicationService identity;
    private final AdministrationStore accounts;
    private final ChannelStore channels;
    private final CatalogValues catalog;
    public VodAccessService(IdentityApplicationService identity, AdministrationStore accounts, ChannelStore channels, CatalogValues catalog) {
        this.identity=identity; this.accounts=accounts; this.channels=channels; this.catalog=catalog;
    }
    @Transactional(readOnly=true)
    public Context access(String channelId,String credential,String operation) {
        if(!Set.of("READ","WRITE","SESSION","ADMIN").contains(operation)) throw invalid();
        var session=credential==null||credential.isBlank()?null:identity.introspect(credential).orElseThrow(()->
            new IdentityException(HttpStatus.UNAUTHORIZED,"SESSION_REQUIRED","Se requiere una sesión activa."));
        if(!operation.equals("READ")&&session==null) throw new IdentityException(HttpStatus.UNAUTHORIZED,"SESSION_REQUIRED","Se requiere una sesión activa.");
        String actor=session==null?null:session.userId();
        boolean admin=actor!=null&&accounts.isAdministrator(actor);
        if(operation.equals("ADMIN")&&!admin) throw new IdentityException(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED","Se requiere rol administrativo.");
        if(operation.equals("SESSION")) return new Context(actor,null,0,true,admin);
        if(channelId==null||!channelId.matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
        var channel=channels.find(channelId).orElseThrow(()->new IdentityException(HttpStatus.NOT_FOUND,"CHANNEL_NOT_FOUND","Canal no disponible."));
        var owner=accounts.account(channel.ownerUserId()).orElseThrow(()->new IdentityException(HttpStatus.NOT_FOUND,"CHANNEL_NOT_FOUND","Canal no disponible."));
        if(operation.equals("WRITE")&&!channel.ownerUserId().equals(actor)) throw new IdentityException(HttpStatus.FORBIDDEN,"VOD_FORBIDDEN","Solo el propietario puede modificar VOD.");
        return new Context(actor,channel.ownerUserId(),owner.accountVersion(),owner.status().equals("ACTIVE"),admin);
    }
    @Transactional
    public void validateCatalog(String categoryId,List<String> tagIds) {
        if(categoryId!=null) catalog.requireActiveCategory(categoryId);
        if(tagIds!=null) catalog.requireActiveTags(tagIds);
    }
    private static IdentityException invalid() { return new IdentityException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Contexto VOD inválido."); }
    public record Context(String userId,String ownerUserId,long accountVersion,boolean active,boolean administrator) {}
}
