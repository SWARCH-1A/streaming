package streaming.core.discovery.application;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import streaming.core.discovery.application.ProjectionReconciler.Result;

import static org.assertj.core.api.Assertions.assertThat;

/** The real Spring scheduling of the rebuild cut: a steady start-to-start cadence even when each cut is slow. */
class ReconciliationSchedulingTest {
    @Configuration @EnableScheduling static class SchedulingConfig { }

    @Test void slowCutsDoNotStretchTheCadenceBeyondTheConfiguredInterval() throws Exception {
        List<Long> starts=new CopyOnWriteArrayList<>();
        var reconciler=Mockito.mock(ProjectionReconciler.class);
        Mockito.when(reconciler.runOnce()).thenAnswer(call-> {
            starts.add(System.nanoTime());
            try { Thread.sleep(600); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }   // closing the context interrupts a slow cut
            return Result.SUCCESS;
        });
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of(
                    "discovery.reconcile-interval","PT1S","discovery.reconcile-poll","PT0.05S","discovery.reconcile-initial-delay","PT0S")));
            context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());   // what Spring Boot installs: "PT1S" to Duration
            context.registerBean(ProjectionReconciler.class,()->reconciler);
            context.register(SchedulingConfig.class,ReconciliationScheduler.class);
            context.refresh();
            long deadline=System.nanoTime()+8_000_000_000L;
            while(starts.size()<3 && System.nanoTime()<deadline) Thread.sleep(50);
        }
        assertThat(starts).as("three cuts started by the real scheduler").hasSizeGreaterThanOrEqualTo(3);
        double seconds=(starts.get(2)-starts.get(0))/1e9;
        // 2 intervals start to start (about 2.1 s); a fixed delay would add each 0.6 s cut and need 3.2 s or more
        assertThat(seconds).as("two cadence steps").isBetween(1.9,2.8);
    }
}
