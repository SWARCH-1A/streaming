package streaming.core.discovery.api;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.language.Document;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import streaming.core.discovery.application.DiscoveryException;
import streaming.core.discovery.domain.RequestLimiter;
import streaming.core.security.TrustedProxies;
import streaming.core.discovery.infrastructure.graphql.DiscoveryGraphQl;
import streaming.core.discovery.infrastructure.graphql.GraphQlQueryGuard;
import streaming.core.security.RequestAuditFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /api/discovery/graphql}: public, anonymous and read-only. Order of checks: per-IP rate, media type,
 * body size (read capped at 16 KiB), JSON shape, GraphQL syntax, query shape/cost, then execution. Every failure is a
 * GraphQL error envelope with {@code extensions.code}, {@code httpStatus} and {@code requestId}.
 */
@RestController
public class DiscoveryGraphQlController {
    static final int MAX_BODY_BYTES=16*1024;
    private final DiscoveryGraphQl engine;
    private final GraphQlQueryGuard guard;
    private final RequestLimiter limiter;
    private final TrustedProxies proxies;
    private final ObjectMapper json;

    public DiscoveryGraphQlController(DiscoveryGraphQl engine,GraphQlQueryGuard guard,RequestLimiter limiter,TrustedProxies proxies,ObjectMapper json) {
        this.engine=engine; this.guard=guard; this.limiter=limiter; this.proxies=proxies; this.json=json;
    }

    @PostMapping("/api/discovery/graphql")
    public ResponseEntity<byte[]> graphql(HttpServletRequest request) throws IOException {
        String requestId=String.valueOf(request.getAttribute(RequestAuditFilter.REQUEST_ID_ATTRIBUTE));
        String client=proxies.clientAddress(request.getRemoteAddr(),Collections.list(request.getHeaders("X-Forwarded-For")));
        RequestLimiter.Decision decision;
        try { decision=limiter.tryAcquire(client); }
        catch(org.springframework.dao.DataAccessException e) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE,"DISCOVERY_UNAVAILABLE","No fue posible completar la consulta.",requestId);
        }
        if(!decision.allowed()) {
            var response=failure(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Se superó el límite de solicitudes; reintenta más tarde.",requestId);
            return ResponseEntity.status(response.getStatusCode()).headers(headers(response.getHeaders()))
                    .header(HttpHeaders.RETRY_AFTER,Long.toString(decision.retryAfterSeconds())).body(response.getBody());
        }
        try {
            return handle(request,requestId);
        } catch(DiscoveryException e) {
            return failure(e.status(),e.code(),e.getMessage(),requestId);
        }
    }

    private ResponseEntity<byte[]> handle(HttpServletRequest request,String requestId) throws IOException {
        if(!isJson(request.getContentType())) throw new DiscoveryException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"UNSUPPORTED_MEDIA_TYPE","Content-Type debe ser application/json.");
        if(request.getContentLengthLong()>MAX_BODY_BYTES) throw DiscoveryException.limitExceeded("El cuerpo no puede superar 16 KiB.");
        byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);
        if(bytes.length>MAX_BODY_BYTES) throw DiscoveryException.limitExceeded("El cuerpo no puede superar 16 KiB.");

        JsonNode body;
        try { body=json.readTree(bytes); } catch(RuntimeException e) { throw badRequest("El cuerpo no es JSON válido."); }
        if(body==null || !body.isObject()) throw badRequest("El cuerpo debe ser un objeto JSON.");
        JsonNode query=body.get("query"), operationName=body.get("operationName"), variables=body.get("variables");
        if(query==null || !query.isTextual() || query.textValue().isBlank()) throw badRequest("query es obligatorio y debe ser texto.");
        if(operationName!=null && !operationName.isNull() && !operationName.isTextual()) throw badRequest("operationName debe ser texto.");
        if(variables!=null && !variables.isNull() && !variables.isObject()) throw badRequest("variables debe ser un objeto.");
        @SuppressWarnings("unchecked")
        Map<String,Object> variableMap=variables==null || variables.isNull()?Map.of():json.convertValue(variables,Map.class);

        Document document=engine.parse(query.textValue());
        guard.check(document,variableMap);
        ExecutionResult result=engine.execute(query.textValue(),operationName==null || operationName.isNull()?null:operationName.textValue(),variableMap,requestId);
        return respond(result,requestId);
    }

    private ResponseEntity<byte[]> respond(ExecutionResult result,String requestId) {
        var payload=new LinkedHashMap<String,Object>();
        var errors=new ArrayList<Map<String,Object>>();
        int status=200;
        boolean executed=result.isDataPresent();
        for(GraphQLError error:result.getErrors()) {
            Map<String,Object> spec=new LinkedHashMap<>(error.toSpecification());
            @SuppressWarnings("unchecked")
            Map<String,Object> extensions=spec.get("extensions") instanceof Map<?,?> existing?new LinkedHashMap<>((Map<String,Object>)existing):new LinkedHashMap<>();
            if(!extensions.containsKey("code")) {
                // Errors raised by the engine itself: before execution they are invalid requests, afterwards defects.
                extensions.put("code",executed?"INTERNAL_ERROR":"BAD_REQUEST");
                extensions.put("httpStatus",executed?500:400);
            }
            extensions.put("requestId",requestId);
            spec.put("extensions",extensions);
            errors.add(spec);
            int code=((Number)extensions.get("httpStatus")).intValue();
            if(code>=500 || !executed) status=Math.max(status,code);
        }
        if(executed) payload.put("data",result.getData());
        if(!errors.isEmpty()) payload.put("errors",errors);
        return ResponseEntity.status(status).headers(headers(new HttpHeaders())).body(json.writeValueAsBytes(payload));
    }

    private ResponseEntity<byte[]> failure(HttpStatus status,String code,String message,String requestId) {
        var extensions=new LinkedHashMap<String,Object>();
        extensions.put("code",code); extensions.put("httpStatus",status.value()); extensions.put("requestId",requestId);
        var error=new LinkedHashMap<String,Object>();
        error.put("message",message); error.put("extensions",extensions);
        return ResponseEntity.status(status).headers(headers(new HttpHeaders())).body(json.writeValueAsBytes(Map.of("errors",List.of(error))));
    }

    private static HttpHeaders headers(HttpHeaders base) {
        var headers=new HttpHeaders(); headers.putAll(base);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl("no-store");
        return headers;
    }
    private static boolean isJson(String contentType) {
        if(contentType==null) return false;
        try { return MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType)); }
        catch(RuntimeException e) { return false; }
    }
    private static DiscoveryException badRequest(String message) { return new DiscoveryException(HttpStatus.BAD_REQUEST,"BAD_REQUEST",message); }
}
