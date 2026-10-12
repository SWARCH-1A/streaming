package streaming.core.discovery.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.discovery.application.StreamingSnapshotClient.SnapshotExpiredException;
import streaming.core.discovery.application.StreamingSnapshotClient.SnapshotPage;
import streaming.core.discovery.application.StreamingSnapshotClient.SnapshotUnavailableException;
import streaming.core.discovery.domain.ApplyOutcome;
import streaming.core.discovery.domain.StreamProjection;

/**
 * Rebuilds and repairs the projection from Streaming's consistent cut. All pages are validated before anything is
 * written, then applied in one transaction: newer rows win, rows absent from the cut are removed only if committed
 * at or before its watermark, and a failed run leaves the previous projection (and its real age) untouched.
 * Events keep being received while a cut is read; version rules make both paths converge.
 */
@Service
public class ProjectionReconciler {
    private static final Logger log=LoggerFactory.getLogger(ProjectionReconciler.class);
    private static final int PAGE_SIZE=50;
    private static final int MAX_RESTARTS=3;
    private final StreamingSnapshotClient client;
    private final ProjectionParser parser;
    private final ProjectionStore store;
    private final DiscoveryClock clock;
    private final TransactionTemplate transactions;

    public ProjectionReconciler(StreamingSnapshotClient client,ProjectionParser parser,ProjectionStore store,DiscoveryClock clock,
            TransactionTemplate transactions) {
        this.client=client; this.parser=parser; this.store=store; this.clock=clock; this.transactions=transactions;
    }

    public enum Result { SKIPPED, SUCCESS, FAILED }

    /** One reconciliation attempt. Never throws: failures are recorded and logged without secrets. */
    public synchronized Result runOnce() {
        if(!client.configured()) return Result.SKIPPED;
        Instant attempt=clock.now();
        String failure="UNKNOWN";
        for(int restart=0;restart<MAX_RESTARTS;restart++) {
            try {
                Cut cut=readCut();
                apply(cut,attempt);
                log.info("event=discovery_reconciled component=core module=discovery snapshotId={} watermark={} streams={}",
                        cut.snapshotId(),cut.watermark(),cut.items().size());
                return Result.SUCCESS;
            } catch(SnapshotExpiredException e) {
                failure="SNAPSHOT_EXPIRED";
            } catch(SnapshotUnavailableException e) {
                failure=e.code();
                break;
            } catch(DiscoveryException e) {
                failure=e.code();
                break;
            } catch(RuntimeException e) {
                failure="RECONCILE_ERROR";
                log.warn("event=discovery_reconcile_failed component=core module=discovery reason={}",e.getClass().getSimpleName());
                break;
            }
        }
        fail(failure,attempt);
        return Result.FAILED;
    }

    private record Cut(String snapshotId,long watermark,Instant capturedAt,List<StreamProjection> items) { }

    private Cut readCut() {
        var items=new ArrayList<StreamProjection>();
        var seen=new LinkedHashSet<String>();
        SnapshotPage first=null;
        String cursor=null;
        do {
            SnapshotPage page=client.page(PAGE_SIZE,cursor);
            if(first==null) first=page;
            else if(!first.snapshotId().equals(page.snapshotId()) || first.watermark()!=page.watermark())
                throw new SnapshotUnavailableException("INCONSISTENT_CUT",null);
            for(var item:page.items()) {
                StreamProjection projection=parser.parsePayload(item);
                if(!seen.add(projection.streamId())) throw new SnapshotUnavailableException("DUPLICATE_IN_CUT",null);
                items.add(projection);
            }
            cursor=page.nextCursor();
        } while(cursor!=null);
        return new Cut(first.snapshotId(),first.watermark(),first.capturedAt(),items);
    }

    private void apply(Cut cut,Instant attempt) {
        Instant now=clock.now();
        transactions.executeWithoutResult(status-> {
            for(StreamProjection projection:cut.items()) {
                ApplyOutcome outcome=store.apply(projection,now,now);
                if(outcome.conflict()) log.warn("event=discovery_cut_conflict component=core module=discovery streamId={} outcome={}",projection.streamId(),outcome);
            }
            store.pruneAbsent(cut.items().stream().map(StreamProjection::streamId).toList(),cut.watermark());
            store.saveSuccess(cut.snapshotId(),cut.watermark(),cut.capturedAt(),now);
        });
    }

    private void fail(String code,Instant attempt) {
        try { transactions.executeWithoutResult(status->store.saveFailure(code,attempt)); }
        catch(RuntimeException e) { log.warn("event=discovery_reconcile_state_unavailable component=core module=discovery"); }
        log.warn("event=discovery_reconcile_failed component=core module=discovery reason={}",code);
    }
}
