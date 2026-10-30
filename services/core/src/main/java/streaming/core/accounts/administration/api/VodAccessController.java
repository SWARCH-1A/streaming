package streaming.core.accounts.administration.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import streaming.core.accounts.administration.application.VodAccessService;
import streaming.core.accounts.identity.application.IdentityException;
import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Set;

/** Permission is checked on the private connector before Spring parses this payload. */
@RestController
public class VodAccessController {
    private final VodAccessService service;
    public VodAccessController(VodAccessService service) { this.service=service; }
    @PostMapping(path="/internal/core/access-context",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VodAccessService.Context> context(@RequestBody JsonNode body,
            @RequestHeader(name="X-Session-Credential",required=false) String credential) {
        if(body==null||!body.isObject()||!body.has("operation")||!body.get("operation").isTextual()) throw invalid();
        for(String field:body.propertyNames()) if(!Set.of("operation","channelId","categoryId","tagIds").contains(field)) throw invalid();
        String operation=body.get("operation").textValue();
        String channel=body.has("channelId")&&body.get("channelId").isTextual()?body.get("channelId").textValue():"";
        var context=service.access(channel,credential,operation);
        if(body.has("categoryId")||body.has("tagIds")) {
            if(!operation.equals("WRITE")) throw invalid();
            String category=null; java.util.List<String> tags=null;
            if(body.has("categoryId")) { if(!body.get("categoryId").isTextual()) throw invalid(); category=body.get("categoryId").textValue(); }
            if(body.has("tagIds")) { if(!body.get("tagIds").isArray()||body.get("tagIds").size()>5) throw invalid(); tags=new ArrayList<>(); for(JsonNode value:body.get("tagIds")) { if(!value.isTextual()) throw invalid(); tags.add(value.textValue()); } }
            service.validateCatalog(category,tags);
        }
        return ResponseEntity.ok().header("Cache-Control","no-store").body(context);
    }
    private static IdentityException invalid() { return new IdentityException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Contexto VOD inválido."); }
}
