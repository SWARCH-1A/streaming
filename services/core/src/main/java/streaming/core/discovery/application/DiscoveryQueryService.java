package streaming.core.discovery.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import streaming.core.discovery.application.DiscoveryReadModel.ChannelRow;
import streaming.core.discovery.application.DiscoveryReadModel.StreamQuery;
import streaming.core.discovery.application.DiscoveryReadModel.StreamRow;
import streaming.core.discovery.application.DiscoveryViews.ChannelConnection;
import streaming.core.discovery.application.DiscoveryViews.LiveStream;
import streaming.core.discovery.application.DiscoveryViews.PublicChannel;
import streaming.core.discovery.application.DiscoveryViews.PublicChannelResult;
import streaming.core.discovery.application.DiscoveryViews.StreamConnection;
import streaming.core.discovery.application.DiscoveryViews.TaxonomyValue;
import streaming.core.discovery.domain.ChannelCursor;
import streaming.core.discovery.domain.FilterHash;
import streaming.core.discovery.domain.FreshnessRules;
import streaming.core.discovery.domain.SearchText;
import streaming.core.discovery.domain.StreamCursor;
import streaming.core.taxonomy.application.CatalogContexts;
import streaming.core.taxonomy.application.CatalogValues.ValueType;

/**
 * Use cases of the public GraphQL queries. Streams are listed only while Streaming's own observation is fresh;
 * a channel whose state cannot be confirmed is reported UNKNOWN, never OFFLINE.
 */
@Service
public class DiscoveryQueryService {
    public static final int DEFAULT_LIMIT=20;
    public static final int MAX_LIMIT=50;
    private static final int MAX_RANKED=500;
    private static final Pattern ID=Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private final DiscoveryReadModel read;
    private final RankingSnapshots snapshots;
    private final ProjectionStore projection;
    private final CatalogContexts catalog;
    private final DiscoveryClock clock;
    private final Duration snapshotTtl;

    public DiscoveryQueryService(DiscoveryReadModel read,RankingSnapshots snapshots,ProjectionStore projection,CatalogContexts catalog,
            DiscoveryClock clock,@Value("${discovery.ranking-snapshot-ttl:PT5M}") Duration snapshotTtl) {
        this.read=read; this.snapshots=snapshots; this.projection=projection; this.catalog=catalog; this.clock=clock; this.snapshotTtl=snapshotTtl;
    }

    public StreamConnection streams(String q,String categoryId,String tagId,Integer limitArgument,String cursor) {
        Instant now=clock.now();
        int limit=limit(limitArgument);
        String normalized=SearchText.queryOrNull(q), category=blankToNull(categoryId), tag=blankToNull(tagId);
        validateFilters(category,tag);
        String filter=FilterHash.of("streams",normalized,category,tag);
        Instant oldest=FreshnessRules.oldestFresh(now), newest=FreshnessRules.newestFresh(now);

        List<String> ids;
        int offset=0;
        UUID snapshotId=null;
        if(cursor==null) {
            ids=read.rankedStreamIds(new StreamQuery(normalized,category,tag),oldest,newest,MAX_RANKED);
            if(ids.size()>limit) {
                snapshotId=UUID.randomUUID();
                snapshots.save(snapshotId,filter,ids,now,now.plus(snapshotTtl));
            }
        } else {
            StreamCursor position;
            try { position=StreamCursor.parse(cursor); } catch(IllegalArgumentException e) { throw DiscoveryException.invalidCursor(); }
            var snapshot=snapshots.find(position.snapshotId(),now).filter(s->s.filterHash().equals(filter)).orElseThrow(DiscoveryException::invalidCursor);
            if(position.offset()>snapshot.streamIds().size()) throw DiscoveryException.invalidCursor();
            ids=snapshot.streamIds(); offset=position.offset(); snapshotId=snapshot.snapshotId();
        }

        List<String> page=ids.subList(Math.min(offset,ids.size()),Math.min(offset+limit,ids.size()));
        Map<String,StreamRow> rows=new HashMap<>();
        for(StreamRow row:read.streams(page,oldest,newest)) rows.put(row.streamId(),row);
        var items=new ArrayList<LiveStream>();
        // A stream that stopped being playable since the ranking was frozen disappears instead of being invented.
        for(String id:page) { StreamRow row=rows.get(id); if(row!=null) items.add(toStream(row,now)); }
        String next=snapshotId!=null && offset+limit<ids.size()?new StreamCursor(snapshotId,offset+limit).format():null;
        return new StreamConnection(items,next,now,true);
    }

    public ChannelConnection channels(String q,Integer limitArgument,String cursor) {
        Instant now=clock.now();
        int limit=limit(limitArgument);
        String normalized=SearchText.queryOrNull(q);
        String filter=FilterHash.of("channels",normalized,null,null);
        ChannelCursor after=null;
        if(cursor!=null) {
            try { after=ChannelCursor.parse(cursor); } catch(IllegalArgumentException e) { throw DiscoveryException.invalidCursor(); }
            if(!after.filter().equals(filter)) throw DiscoveryException.invalidCursor();
        }
        List<ChannelRow> rows=read.channels(normalized,after,limit+1);
        boolean more=rows.size()>limit;
        List<ChannelRow> page=more?rows.subList(0,limit):rows;
        Instant cutCapturedAt=projection.reconciliationState().capturedAt();
        boolean cutFresh=FreshnessRules.fresh(cutCapturedAt,now);
        var items=new ArrayList<PublicChannelResult>();
        boolean allFresh=true;
        for(ChannelRow row:page) {
            PublicChannelResult item=toChannel(row,now,cutFresh);
            allFresh&=item.statusFresh();
            items.add(item);
        }
        String next=null;
        if(more) { ChannelRow last=page.getLast(); next=new ChannelCursor(last.bestClass(),last.fieldRank(),last.handle(),last.userId(),filter).format(); }
        return new ChannelConnection(items,next,now,allFresh);
    }

    private static PublicChannelResult toChannel(ChannelRow row,Instant now,boolean cutFresh) {
        String status, availability;
        boolean fresh;
        if(row.hasProjection()) {
            fresh=FreshnessRules.fresh(row.stateObservedAt(),now);
            if(fresh) { status="LIVE".equals(row.status())?"LIVE":"OFFLINE"; availability=row.availability(); }
            else { status="UNKNOWN"; availability="UNKNOWN"; }
        } else if(cutFresh) {
            // A full cut taken under 5 s ago proves the channel has no stream configuration.
            fresh=true; status="OFFLINE"; availability="OFFLINE";
        } else { fresh=false; status="UNKNOWN"; availability="UNKNOWN"; }
        return new PublicChannelResult(row.channelId(),row.userId(),row.handle(),row.displayName(),row.avatarUri(),status,availability,
                row.hasProjection()?row.title():null,row.channelVersion(),row.metadataVersion(),row.sessionVersion(),fresh);
    }

    private static LiveStream toStream(StreamRow row,Instant now) {
        Instant countObserved=row.viewerCountObservedAt()==null?row.stateObservedAt():row.viewerCountObservedAt();
        boolean countFresh=row.viewerCountObservedAt()!=null && FreshnessRules.fresh(row.viewerCountObservedAt(),now);
        return new LiveStream(row.streamId(),row.sessionId(),new PublicChannel(row.channelId(),row.handle(),row.displayName(),row.avatarUri()),
                row.title(),new TaxonomyValue(row.categoryId(),row.categoryName()),
                row.tags().stream().map(t->new TaxonomyValue(t.id(),t.name())).toList(),row.status(),row.availability(),
                row.viewerCount(),countFresh,countObserved,row.startedAt(),row.metadataVersion(),row.sessionVersion(),true);
    }

    private void validateFilters(String category,String tag) {
        var ids=new ArrayList<String>();
        if(category!=null) { if(!ID.matcher(category).matches()) throw DiscoveryException.invalidFilter("categoryId","UNKNOWN_OR_INACTIVE"); ids.add(category); }
        if(tag!=null) { if(!ID.matcher(tag).matches()) throw DiscoveryException.invalidFilter("tagId","UNKNOWN_OR_INACTIVE"); ids.add(tag); }
        if(ids.isEmpty()) return;
        var values=catalog.resolve(ids).values();
        if(category!=null && values.stream().noneMatch(v->v.id().equals(category) && v.kind()==ValueType.CATEGORY && v.active()))
            throw DiscoveryException.invalidFilter("categoryId","UNKNOWN_OR_INACTIVE");
        if(tag!=null && values.stream().noneMatch(v->v.id().equals(tag) && v.kind()==ValueType.TAG && v.active()))
            throw DiscoveryException.invalidFilter("tagId","UNKNOWN_OR_INACTIVE");
    }

    private static int limit(Integer argument) {
        if(argument==null) return DEFAULT_LIMIT;
        if(argument>MAX_LIMIT) throw DiscoveryException.limitExceeded("limit admite como máximo "+MAX_LIMIT+" filas por conexión.");
        if(argument<1) throw DiscoveryException.invalidLimit();
        return argument;
    }
    private static String blankToNull(String value) { return value==null || value.isBlank()?null:value.strip(); }
}
