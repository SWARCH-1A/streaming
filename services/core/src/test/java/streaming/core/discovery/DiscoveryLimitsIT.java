package streaming.core.discovery;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** CA-08: shape, cost and size of what the public endpoint accepts, and the HTTP mapping of every failure. */
class DiscoveryLimitsIT extends DiscoveryITBase {

    private HttpResponse<String> post(String contentType,byte[] body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/discovery/graphql")).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if(contentType!=null) request.header("Content-Type",contentType);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode error(HttpResponse<String> response) {
        JsonNode errors=json.readTree(response.body()).get("errors");
        assertThat(errors).as(response.body()).isNotNull();
        assertThat(errors.get(0).at("/extensions/requestId").textValue()).as("every failure carries a request id").isNotBlank();
        return errors.get(0);
    }
    private void expect(HttpResponse<String> response,int status,String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(error(response).at("/extensions/code").textValue()).isEqualTo(code);
        assertThat(error(response).at("/extensions/httpStatus").intValue()).isEqualTo(status);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain("Exception","org.springframework","graphql.","at streaming.");
    }

    // ---- 422: shape and cost

    @Test void aliasesFragmentsAndIntrospectionAreRejected() throws Exception {
        for(String query:List.of("{ a: streams { items { streamId } } }","{ streams { items { id: streamId } } }",
                "{ streams { items { ...F } } } fragment F on LiveStream { streamId }","{ streams { items { ... on LiveStream { streamId } } } }",
                "{ __schema { types { name } } }","{ __type(name:\"Query\") { name } }","{ streams { __typename } }",
                "mutation { streams { items { streamId } } }","subscription { streams { items { streamId } } }",
                "query A { streams { items { streamId } } } query B { channels { items { handle } } }",
                "{ streams { items { streamId } } streams { items { streamId } } }")) {
            expect(graphql(query,Map.of()),422,"QUERY_LIMIT_EXCEEDED");
        }
    }

    @Test void moreThanFiftyRowsPerConnectionOrOneHundredInTotalIsRejected() throws Exception {
        expect(graphql("{ streams(limit:51){items{streamId}} }",Map.of()),422,"QUERY_LIMIT_EXCEEDED");
        expect(graphql("{ channels(limit:100000){items{handle}} }",Map.of()),422,"QUERY_LIMIT_EXCEEDED");
        expect(graphql(STREAMS,Map.of("limit",51)),422,"QUERY_LIMIT_EXCEEDED");
        expect(graphql("{ streams(limit:50){items{streamId}} channels(limit:51){items{handle}} }",Map.of()),422,"QUERY_LIMIT_EXCEEDED");
        assertThat(graphql("{ streams(limit:50){items{streamId}} channels(limit:50){items{handle}} }",Map.of()).statusCode()).as("exactly 100 is allowed").isEqualTo(200);
    }

    @Test void aBodyOverSixteenKibibytesIsRejectedBeforeAnyParsing() throws Exception {
        String padding="#"+"x".repeat(16*1024);
        expect(graphqlRaw("{\"query\":\"{ channels { items { handle } } }\",\"variables\":{\"pad\":\""+padding+"\"}}"),422,"QUERY_LIMIT_EXCEEDED");
        String exact="{\"query\":\"{ channels { items { handle } } }\",\"variables\":{\"pad\":\"";
        String tail="\"}}";
        int room=16*1024-exact.getBytes(StandardCharsets.UTF_8).length-tail.getBytes(StandardCharsets.UTF_8).length;
        var atLimit=graphqlRaw(exact+"x".repeat(room)+tail);
        assertThat(exact.length()+room+tail.length()).isEqualTo(16*1024);
        assertThat(atLimit.statusCode()).as("exactly 16 KiB is accepted").isEqualTo(200);
        var oneOver=graphqlRaw(exact+"x".repeat(room+1)+tail);
        expect(oneOver,422,"QUERY_LIMIT_EXCEEDED");
    }

    // ---- 400 / 415: malformed requests

    @Test void malformedRequestsAre400() throws Exception {
        for(String body:List.of("{","not json","","[]","\"text\"","null","{}","{\"query\":5}","{\"query\":\"\"}","{\"query\":\"   \"}",
                "{\"query\":\"{ channels { items { handle } } }\",\"variables\":[1]}","{\"query\":\"{ channels { items { handle } } }\",\"variables\":\"x\"}",
                "{\"query\":\"{ channels { items { handle } } }\",\"operationName\":5}","{\"query\":\"{ channels { items { handle \"}",
                "{\"query\":\"{ channels { items { nonexistent } } }\"}","{\"query\":\"{ streams { nonexistent } }\"}")) {
            var response=graphqlRaw(body);
            expect(response,400,"BAD_REQUEST");
        }
    }

    @Test void anUnknownRootFieldIsAShapeViolation() throws Exception {
        expect(graphql("{ nonexistent }",Map.of()),422,"QUERY_LIMIT_EXCEEDED");
        expect(graphql("{ channels { items { handle } } viewer { id } }",Map.of()),422,"QUERY_LIMIT_EXCEEDED");
    }

    @Test void variablesOfTheWrongTypeAreRejectedBeforeExecution() throws Exception {
        expect(graphql(STREAMS,Map.of("limit","abc")),400,"BAD_REQUEST");
        expect(graphql(STREAMS,Map.of("limit",1.5)),400,"BAD_REQUEST");
        expect(graphql(STREAMS,Map.of("q",List.of("a"))),400,"BAD_REQUEST");
        expect(graphql("query($l:Int!){ streams(limit:$l){items{streamId}} }",Map.of()),400,"BAD_REQUEST");
    }

    @Test void onlyJsonIsAccepted() throws Exception {
        byte[] body="{\"query\":\"{ channels { items { handle } } }\"}".getBytes(StandardCharsets.UTF_8);
        for(String type:new String[]{null,"text/plain","application/x-www-form-urlencoded","application/graphql","application/xml"}) {
            expect(post(type,body),415,"UNSUPPORTED_MEDIA_TYPE");
        }
        assertThat(post("application/json; charset=utf-8",body).statusCode()).isEqualTo(200);
        assertThat(post("application/json",body).statusCode()).isEqualTo(200);
    }

    @Test void thereIsNoGetAndNoOtherVerbOnThePublicEndpoint() throws Exception {
        for(String method:List.of("GET","PUT","DELETE","PATCH")) {
            var response=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/discovery/graphql?query=%7Bchannels%7Bitems%7Bhandle%7D%7D%7D"))
                    .method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(method).isBetween(400,499);
            assertThat(response.body()).doesNotContain("handle\":");
        }
    }

    @Test void thePublicEndpointNeedsNoCredentialAndNoCsrfToken() throws Exception {
        channel("anon",null);
        var response=graphql(CHANNELS,Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
    }

    @Test void onlyTheWebOriginMayCallItFromABrowser() throws Exception {
        String url="http://localhost:"+port+"/api/discovery/graphql";
        var preflight=http.send(HttpRequest.newBuilder(URI.create(url)).method("OPTIONS",HttpRequest.BodyPublishers.noBody()).header("Origin","http://localhost:3000")
                .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","content-type,x-request-id").build(),HttpResponse.BodyHandlers.ofString());
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Methods").orElse("")).contains("POST");
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase()).contains("content-type");

        var foreign=http.send(HttpRequest.newBuilder(URI.create(url)).method("OPTIONS",HttpRequest.BodyPublishers.noBody()).header("Origin","https://evil.example")
                .header("Access-Control-Request-Method","POST").build(),HttpResponse.BodyHandlers.ofString());
        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(foreign.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();

        var actual=http.send(HttpRequest.newBuilder(URI.create(url)).header("Origin","http://localhost:3000").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ channels { items { handle } } }\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(actual.statusCode()).isEqualTo(200);
        assertThat(actual.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
    }

    // ---- field errors keep the rest of the answer

    @Test void zeroOrNegativeLimitsAreFieldErrorsNotShapeErrors() throws Exception {
        for(int limit:new int[]{0,-1,-50}) {
            var response=graphql(STREAMS,Map.of("limit",limit));
            assertThat(response.statusCode()).as("limit "+limit).isEqualTo(200);
            JsonNode body=json.readTree(response.body());
            assertThat(body.at("/errors/0/extensions/code").textValue()).isEqualTo("INVALID_LIMIT");
            assertThat(body.at("/errors/0/extensions/httpStatus").intValue()).isEqualTo(422);
            assertThat(body.at("/data/streams").isNull()).isTrue();
        }
        var limits=graphql(CHANNELS,Map.of("limit",0));
        assertThat(json.readTree(limits.body()).at("/errors/0/extensions/code").textValue()).isEqualTo("INVALID_LIMIT");
    }

    @Test void theRequestIdOfTheCallerIsEchoedInErrors() throws Exception {
        String id="3f2c6b0e-6f0e-4a55-9f6b-0c1d2e3f4a5b";
        var response=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/discovery/graphql")).header("Content-Type","application/json").header("X-Request-Id",id)
                .POST(HttpRequest.BodyPublishers.ofString("{")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(error(response).at("/extensions/requestId").textValue()).isEqualTo(id);
    }
}
