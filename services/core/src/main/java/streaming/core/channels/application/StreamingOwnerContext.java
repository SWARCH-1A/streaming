package streaming.core.channels.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import streaming.core.taxonomy.application.CatalogContexts;
import streaming.core.taxonomy.application.CatalogSelections;
import streaming.core.taxonomy.application.TaxonomyException;
import tools.jackson.databind.JsonNode;

/** Channels owns the ownership decision; Accounts and Catalog retain their own data. */
@Service
public class StreamingOwnerContext {
    private final AccountAuthentication accounts;
    private final ChannelStore channels;
    private final CatalogSelections catalog;
    public StreamingOwnerContext(AccountAuthentication accounts,ChannelStore channels,CatalogSelections catalog) {
        this.accounts=accounts; this.channels=channels; this.catalog=catalog;
    }

    public Context authorize(String credential,JsonNode request) {
        var principal=accounts.introspect(credential).orElseThrow(()->new ChannelException(
                HttpStatus.UNAUTHORIZED,"SESSION_INVALID","La sesión no está vigente."));
        UUID commandId;
        Operation operation;
        try {
            if(!request.isObject() || !request.path("commandId").isTextual() || !request.path("operation").isTextual()) throw malformed();
            commandId=UUID.fromString(request.get("commandId").asText());
            if(!commandId.toString().equalsIgnoreCase(request.get("commandId").asText())) throw malformed();
            operation=Operation.valueOf(request.get("operation").asText());
        } catch(IllegalArgumentException error) { throw malformed(); }
        JsonNode channelId=request.get("channelId");
        if(channelId==null || !channelId.isTextual() || !channelId.asText().matches("[A-Za-z0-9_-]{1,128}")) throw malformed();
        var channel=channels.find(channelId.asText()).orElseThrow(()->new ChannelException(
                HttpStatus.NOT_FOUND,"CHANNEL_NOT_FOUND","El canal no existe."));
        if(!channel.ownerUserId().equals(principal.userId())) throw new ChannelException(
                HttpStatus.FORBIDDEN,"CHANNEL_FORBIDDEN","Solo el propietario puede operar el canal.");

        String category=null;
        List<String> tags=null;
            if(request.has("categoryId") || operation==Operation.CREATE_CONFIG) {
                JsonNode id=request.get("categoryId");
                if(id!=null && !id.isNull() && !id.isTextual()) throw new TaxonomyException("categoryId","INVALID_ID");
                category=id==null || id.isNull()?null:id.asText();
            }
            if(request.has("tagIds")) {
                JsonNode tagIds=request.get("tagIds");
                if(!tagIds.isArray()) throw new TaxonomyException("tagIds","INVALID_ID");
                var input=new ArrayList<String>();
                for(JsonNode id:tagIds) {
                    if(!id.isTextual()) throw new TaxonomyException("tagIds","INVALID_ID");
                    input.add(id.asText());
                }
                tags=input;
            }
        var selected=catalog.validate(request.has("categoryId") || operation==Operation.CREATE_CONFIG,category,request.has("tagIds"),tags);
        return new Context(commandId,operation,principal.userId(),channel.channelId(),Instant.now(),selected.catalogVersion(),selected.category(),selected.tags());
    }
    private static ChannelException malformed() {
        return new ChannelException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","La solicitud no cumple el contrato.");
    }
    public enum Operation { CREATE_CONFIG, PATCH_METADATA, ROTATE_KEY, STOP_SESSION }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Context(UUID commandId,Operation operation,String userId,String channelId,Instant authorizedAtUtc,
            long catalogVersion,CatalogContexts.Value category,List<CatalogContexts.Value> tags) { }
}
