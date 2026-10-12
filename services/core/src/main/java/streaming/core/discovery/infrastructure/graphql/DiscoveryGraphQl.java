package streaming.core.discovery.infrastructure.graphql;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphqlErrorBuilder;
import graphql.execution.DataFetcherExceptionHandler;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import graphql.language.Document;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import streaming.core.discovery.application.DiscoveryException;
import streaming.core.discovery.application.DiscoveryQueryService;

/**
 * The executable GraphQL schema of SPEC-07. The only data fetchers are the two root queries; everything below them is
 * read from the already-built result objects. Errors become stable {@code extensions.code/httpStatus/requestId}.
 */
@Component
public class DiscoveryGraphQl {
    private static final Logger log=LoggerFactory.getLogger(DiscoveryGraphQl.class);
    private final GraphQL graphQl;

    public DiscoveryGraphQl(DiscoveryQueryService queries) {
        var wiring=RuntimeWiring.newRuntimeWiring().scalar(dateTime())
                .type("Query",type->type
                        .dataFetcher("streams",env->queries.streams(env.getArgument("q"),env.getArgument("categoryId"),env.getArgument("tagId"),
                                env.getArgument("limit"),env.getArgument("cursor")))
                        .dataFetcher("channels",env->queries.channels(env.getArgument("q"),env.getArgument("limit"),env.getArgument("cursor"))))
                .build();
        GraphQLSchema schema=new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(sdl()),wiring);
        this.graphQl=GraphQL.newGraphQL(schema).defaultDataFetcherExceptionHandler(new ErrorMapper()).build();
    }

    public ExecutionResult execute(String query,String operationName,Map<String,Object> variables,String requestId) {
        ExecutionInput input=ExecutionInput.newExecutionInput().query(query).operationName(operationName)
                .variables(variables==null?Map.of():variables).graphQLContext(context->context.put("requestId",requestId)).build();
        return graphQl.execute(input);
    }

    /** Syntax check used before the guard; returns the parsed document or throws {@link DiscoveryException} (400). */
    public Document parse(String query) {
        try { return graphql.parser.Parser.parse(query); }
        catch(RuntimeException e) { throw new DiscoveryException(org.springframework.http.HttpStatus.BAD_REQUEST,"BAD_REQUEST","La consulta GraphQL no es sintácticamente válida."); }
    }

    private static String sdl() {
        try(InputStream in=DiscoveryGraphQl.class.getResourceAsStream("/discovery/schema.graphqls")) {
            if(in==null) throw new IllegalStateException("discovery/schema.graphqls is missing");
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException e) { throw new IllegalStateException(e); }
    }

    private static GraphQLScalarType dateTime() {
        return GraphQLScalarType.newScalar().name("DateTime").description("UTC instant, ISO-8601").coercing(new Coercing<Instant,String>() {
            @Override public String serialize(Object value,graphql.GraphQLContext context,Locale locale) {
                if(value instanceof Instant instant) return DateTimeFormatter.ISO_INSTANT.format(instant);
                throw new CoercingSerializeException("DateTime expects an Instant");
            }
            @Override public Instant parseValue(Object input,graphql.GraphQLContext context,Locale locale) {
                throw new CoercingParseValueException("DateTime is output only");
            }
            @Override public Instant parseLiteral(graphql.language.Value<?> input,graphql.execution.CoercedVariables variables,
                    graphql.GraphQLContext context,Locale locale) {
                throw new CoercingParseLiteralException("DateTime is output only");
            }
        }).build();
    }

    private static final class ErrorMapper implements DataFetcherExceptionHandler {
        @Override public CompletableFuture<DataFetcherExceptionHandlerResult> handleException(DataFetcherExceptionHandlerParameters parameters) {
            Throwable cause=parameters.getException();
            while(cause instanceof CompletionException && cause.getCause()!=null) cause=cause.getCause();
            String requestId=String.valueOf(parameters.getDataFetchingEnvironment().getGraphQlContext().getOrDefault("requestId","unknown"));
            var extensions=new LinkedHashMap<String,Object>();
            String message;
            if(cause instanceof DiscoveryException e) {
                message=e.getMessage(); extensions.put("code",e.code()); extensions.put("httpStatus",e.status().value());
                if(!e.fieldErrors().isEmpty()) extensions.put("fieldErrors",e.fieldErrors());
            } else if(cause instanceof DataAccessException) {
                message="Descubrimiento no está disponible temporalmente."; extensions.put("code","DISCOVERY_UNAVAILABLE"); extensions.put("httpStatus",503);
                log.warn("event=discovery_query_failed component=core module=discovery requestId={} reason={}",requestId,cause.getClass().getSimpleName());
            } else {
                message="No fue posible completar la consulta."; extensions.put("code","INTERNAL_ERROR"); extensions.put("httpStatus",500);
                log.error("event=discovery_query_error component=core module=discovery requestId={} reason={}",requestId,cause.getClass().getName());
            }
            extensions.put("requestId",requestId);
            var error=GraphqlErrorBuilder.newError(parameters.getDataFetchingEnvironment()).message(message).extensions(extensions).build();
            return CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().error(error).build());
        }
    }
}
