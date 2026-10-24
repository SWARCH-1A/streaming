package streaming.core.discovery.application;

import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrokerRepositoryBoundaryTest {
    @Test void brokerApplicationDependsOnRepositoryPortsAndDoesNotOwnAJdbcConnection() {
        var constructors=BrokerEventIngestService.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).contains(BrokerEventStore.class,ProjectionStore.class);
        assertThat(Arrays.stream(BrokerEventIngestService.class.getDeclaredFields()).map(Field::getType))
                .noneMatch(type->type.getPackageName().startsWith("org.springframework.jdbc")
                        || type.getPackageName().equals("java.sql") || type.getPackageName().equals("javax.sql"));
    }
}
