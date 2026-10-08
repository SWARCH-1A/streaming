package streaming.core.discovery.infrastructure.graphql;

import graphql.language.Argument;
import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.IntValue;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.language.VariableDefinition;
import graphql.language.VariableReference;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import streaming.core.discovery.application.DiscoveryException;
import streaming.core.discovery.application.DiscoveryQueryService;

/**
 * Rejects, before any SQL, the shapes the public contract forbids: more than one operation, anything but a query,
 * root fields other than {@code streams}/{@code channels} (each at most once), aliases, fragments, introspection
 * ({@code __*}), more than 50 rows per connection and more than 100 rows in total. Violations are
 * {@code 422 QUERY_LIMIT_EXCEEDED}.
 */
@Component
public class GraphQlQueryGuard {
    private static final Set<String> ROOTS=Set.of("streams","channels");
    private static final int MAX_TOTAL=100;

    public void check(Document document,Map<String,Object> variables) {
        OperationDefinition operation=null;
        for(Definition<?> definition:document.getDefinitions()) {
            if(!(definition instanceof OperationDefinition candidate)) throw exceeded("Solo se admite una operación de consulta; no se admiten fragments ni definiciones de esquema.");
            if(operation!=null) throw exceeded("Solo se admite una operación por solicitud.");
            operation=candidate;
        }
        if(operation==null) throw exceeded("La solicitud no contiene ninguna operación.");
        if(operation.getOperation()!=OperationDefinition.Operation.QUERY) throw exceeded("Solo se admiten consultas.");

        var seen=new HashSet<String>();
        long total=0;
        for(Selection<?> selection:operation.getSelectionSet().getSelections()) {
            if(!(selection instanceof Field field)) throw exceeded("No se admiten fragments.");
            rejectAliasOrIntrospection(field);
            if(!ROOTS.contains(field.getName())) throw exceeded("Solo se admiten las consultas streams y channels.");
            if(!seen.add(field.getName())) throw exceeded("Cada consulta raíz solo puede aparecer una vez.");
            walk(field.getSelectionSet());
            long rows=requestedRows(field,operation,variables);
            if(rows>DiscoveryQueryService.MAX_LIMIT) throw exceeded("limit admite como máximo "+DiscoveryQueryService.MAX_LIMIT+" filas por conexión.");
            total+=Math.max(rows,0);
        }
        if(total>MAX_TOTAL) throw exceeded("La suma de limit no puede superar "+MAX_TOTAL+" filas por solicitud.");
    }

    private void walk(SelectionSet selections) {
        if(selections==null) return;
        for(Selection<?> selection:selections.getSelections()) {
            if(!(selection instanceof Field field)) throw exceeded("No se admiten fragments.");
            rejectAliasOrIntrospection(field);
            walk(field.getSelectionSet());
        }
    }

    private static void rejectAliasOrIntrospection(Field field) {
        if(field.getAlias()!=null) throw exceeded("No se admiten alias.");
        if(field.getName().startsWith("__")) throw exceeded("No se admite introspección.");
    }

    /** Effective {@code limit} of a root field: literal, variable (or its default) or the schema default of 20. */
    private static long requestedRows(Field field,OperationDefinition operation,Map<String,Object> variables) {
        for(Argument argument:field.getArguments()) {
            if(!"limit".equals(argument.getName())) continue;
            if(argument.getValue() instanceof IntValue literal) return literal.getValue().min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
            if(argument.getValue() instanceof VariableReference reference) return fromVariable(reference.getName(),operation,variables);
            return DiscoveryQueryService.DEFAULT_LIMIT;
        }
        return DiscoveryQueryService.DEFAULT_LIMIT;
    }

    private static long fromVariable(String name,OperationDefinition operation,Map<String,Object> variables) {
        Object supplied=variables==null?null:variables.get(name);
        if(supplied instanceof Number number) return number.longValue();
        if(supplied==null) {
            List<VariableDefinition> definitions=operation.getVariableDefinitions();
            for(VariableDefinition definition:definitions)
                if(definition.getName().equals(name) && definition.getDefaultValue() instanceof IntValue literal)
                    return literal.getValue().min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        }
        return DiscoveryQueryService.DEFAULT_LIMIT;
    }

    private static DiscoveryException exceeded(String message) { return DiscoveryException.limitExceeded(message); }
}
