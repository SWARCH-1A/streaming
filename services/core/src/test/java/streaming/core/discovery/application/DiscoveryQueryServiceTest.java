package streaming.core.discovery.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import streaming.core.discovery.application.DiscoveryReadModel.ChannelRow;
import streaming.core.discovery.application.DiscoveryReadModel.StreamQuery;
import streaming.core.discovery.application.DiscoveryReadModel.StreamRow;
import streaming.core.discovery.application.ProjectionStore.ReconciliationState;
import streaming.core.discovery.domain.ChannelCursor;
import streaming.core.discovery.domain.FilterHash;
import streaming.core.discovery.domain.StreamCursor;
import streaming.core.discovery.domain.StreamProjection;
import streaming.core.taxonomy.application.CatalogContexts;
import streaming.core.taxonomy.application.CatalogValues.ValueType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoveryQueryServiceTest {
    private static final Instant NOW=Instant.parse("2026-10-06T12:00:00Z");
    private static final String CAT="cat_00000000000000000000000000000001";
    private static final String TAG="tag_00000000000000000000000000000001";
    @Mock DiscoveryReadModel read;
    @Mock RankingSnapshots snapshots;
    @Mock ProjectionStore projection;
    @Mock CatalogContexts catalog;
    private DiscoveryQueryService service;

    @BeforeEach void setUp() {
        service=new DiscoveryQueryService(read,snapshots,projection,catalog,()->NOW,Duration.ofMinutes(5));
    }

    private static StreamRow row(String id,int viewers,Instant countObserved) {
        return new StreamRow(id,"ses_"+id,"chn_"+id,"h_"+id,"Name "+id,null,"Title "+id,CAT,"Conversación",
                List.of(new StreamProjection.Tag(TAG,"Español")),"LIVE","PLAYABLE",viewers,countObserved,NOW.minusSeconds(600),3,4,NOW.minusSeconds(1));
    }
    private static ChannelRow channel(String handle,boolean hasProjection,String status,String availability,Instant observed) {
        return new ChannelRow("chn_"+handle,"usr_"+handle,handle,"Name "+handle,null,2,0,0,hasProjection,hasProjection?"Title":null,status,availability,
                hasProjection?3L:null,hasProjection?4L:null,observed);
    }
    private void catalogHas(CatalogContexts.Value... values) { when(catalog.resolve(anyList())).thenReturn(new CatalogContexts.Snapshot(1,List.of(values))); }

    // --- streams: limits, filters

    @Test void defaultsToTwentyRowsAndNeverStoresASnapshotWhenOnePageSuffices() {
        when(read.rankedStreamIds(any(),any(),any(),anyInt())).thenReturn(List.of("a","b"));
        when(read.streams(eq(List.of("a","b")),any(),any())).thenReturn(List.of(row("a",9,NOW),row("b",3,NOW)));

        var result=service.streams(null,null,null,null,null);

        assertThat(result.items()).extracting(s->s.streamId()).containsExactly("a","b");
        assertThat(result.nextCursor()).isNull();
        assertThat(result.statusFresh()).isTrue();
        assertThat(result.generatedAtUtc()).isEqualTo(NOW);
        verifyNoInteractions(snapshots,catalog);
    }

    @Test void limitsAreValidated() {
        for(int bad:new int[]{0,-1,-50}) assertThatThrownBy(()->service.streams(null,null,null,bad,null))
                .isInstanceOfSatisfying(DiscoveryException.class,e->{ assertThat(e.code()).isEqualTo("INVALID_LIMIT"); assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY); });
        for(int big:new int[]{51,100,Integer.MAX_VALUE}) assertThatThrownBy(()->service.streams(null,null,null,big,null))
                .isInstanceOfSatisfying(DiscoveryException.class,e->assertThat(e.code()).isEqualTo("QUERY_LIMIT_EXCEEDED"));
        assertThatThrownBy(()->service.channels(null,0,null)).isInstanceOfSatisfying(DiscoveryException.class,e->assertThat(e.code()).isEqualTo("INVALID_LIMIT"));
        assertThatThrownBy(()->service.channels(null,51,null)).isInstanceOfSatisfying(DiscoveryException.class,e->assertThat(e.code()).isEqualTo("QUERY_LIMIT_EXCEEDED"));
        verifyNoInteractions(read);
    }

    @Test void searchTextWithNulIsAFieldErrorBeforeAnyQueryRuns() {
        for(String bad:new String[]{"\0","a\0b","\0x","x\0"}) {
            assertThatThrownBy(()->service.streams(bad,null,null,null,null)).isInstanceOfSatisfying(DiscoveryException.class,e->{
                assertThat(e.code()).isEqualTo("INVALID_FILTER");
                assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.fieldErrors()).containsEntry("q","INVALID_TEXT");
            });
            assertThatThrownBy(()->service.channels(bad,null,null)).isInstanceOfSatisfying(DiscoveryException.class,
                    e->assertThat(e.fieldErrors()).containsEntry("q","INVALID_TEXT"));
        }
        verifyNoInteractions(read,snapshots,catalog,projection);
    }

    @Test void unknownInactiveOrWrongKindFiltersAreRejectedAsFieldErrors() {
        catalogHas(new CatalogContexts.Value(CAT,ValueType.CATEGORY,"Conversación",true),new CatalogContexts.Value(TAG,ValueType.TAG,"Español",false));
        assertThatThrownBy(()->service.streams(null,"cat_unknown",null,null,null)).isInstanceOfSatisfying(DiscoveryException.class,e->{
            assertThat(e.code()).isEqualTo("INVALID_FILTER");
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.fieldErrors()).containsEntry("categoryId","UNKNOWN_OR_INACTIVE");
        });
        assertThatThrownBy(()->service.streams(null,null,TAG,null,null)).isInstanceOfSatisfying(DiscoveryException.class,
                e->assertThat(e.fieldErrors()).containsEntry("tagId","UNKNOWN_OR_INACTIVE"));            // inactive tag
        assertThatThrownBy(()->service.streams(null,TAG,null,null,null)).isInstanceOfSatisfying(DiscoveryException.class,
                e->assertThat(e.fieldErrors()).containsKey("categoryId"));                                // a tag is not a category
        assertThatThrownBy(()->service.streams(null,null,CAT,null,null)).isInstanceOfSatisfying(DiscoveryException.class,
                e->assertThat(e.fieldErrors()).containsKey("tagId"));                                     // a category is not a tag
        verify(read,never()).rankedStreamIds(any(),any(),any(),anyInt());
    }

    @Test void filterIdsThatCannotBeIdentifiersNeverReachTheCatalog() {
        for(String bad:new String[]{"a b","../x","x".repeat(65),"cat;DROP"}) assertThatThrownBy(()->service.streams(null,bad,null,null,null))
                .isInstanceOfSatisfying(DiscoveryException.class,e->assertThat(e.code()).isEqualTo("INVALID_FILTER"));
        verifyNoInteractions(catalog,read);
    }

    @Test void categoryAndTagAreCombinedAndTheQueryIsNormalized() {
        catalogHas(new CatalogContexts.Value(CAT,ValueType.CATEGORY,"Conversación",true),new CatalogContexts.Value(TAG,ValueType.TAG,"Español",true));
        when(read.rankedStreamIds(any(),any(),any(),anyInt())).thenReturn(List.of());

        service.streams("  ＣＡＦÉ ",CAT,TAG,null,null);

        var query=ArgumentCaptor.forClass(StreamQuery.class);
        verify(read).rankedStreamIds(query.capture(),eq(NOW.minusSeconds(5)),eq(NOW.plusSeconds(5)),anyInt());
        assertThat(query.getValue()).isEqualTo(new StreamQuery("café",CAT,TAG));
    }

    @Test void blankFiltersMeanNoFilter() {
        when(read.rankedStreamIds(any(),any(),any(),anyInt())).thenReturn(List.of());
        service.streams("   ","","  ",null,null);
        verify(read).rankedStreamIds(new StreamQuery(null,null,null),NOW.minusSeconds(5),NOW.plusSeconds(5),500);
        verifyNoInteractions(catalog);
    }

    // --- streams: stable pagination

    @Test void aResultOverOnePageFreezesItsOrderingAndHandsOutACursor() {
        var ids=List.of("a","b","c","d","e");
        when(read.rankedStreamIds(any(),any(),any(),anyInt())).thenReturn(ids);
        when(read.streams(eq(List.of("a","b")),any(),any())).thenReturn(List.of(row("a",9,NOW),row("b",8,NOW)));

        var first=service.streams(null,null,null,2,null);

        assertThat(first.items()).extracting(s->s.streamId()).containsExactly("a","b");
        var saved=ArgumentCaptor.forClass(UUID.class);
        verify(snapshots).save(saved.capture(),eq(FilterHash.of("streams",null,null,null)),eq(ids),eq(NOW),eq(NOW.plus(Duration.ofMinutes(5))));
        assertThat(first.nextCursor()).isEqualTo(new StreamCursor(saved.getValue(),2).format());
    }

    @Test void followingPagesSliceTheFrozenOrderEvenIfCountsChangedMeanwhile() {
        UUID id=UUID.randomUUID();
        String filter=FilterHash.of("streams",null,null,null);
        when(snapshots.find(id,NOW)).thenReturn(Optional.of(new RankingSnapshots.Snapshot(id,filter,List.of("a","b","c","d","e"))));
        when(read.streams(eq(List.of("c","d")),any(),any())).thenReturn(List.of(row("d",1,NOW),row("c",99,NOW)));   // counts moved; order must not
        when(read.streams(eq(List.of("e")),any(),any())).thenReturn(List.of(row("e",1,NOW)));

        var second=service.streams(null,null,null,2,new StreamCursor(id,2).format());
        assertThat(second.items()).extracting(s->s.streamId()).containsExactly("c","d");
        assertThat(second.nextCursor()).isEqualTo(new StreamCursor(id,4).format());

        var third=service.streams(null,null,null,2,second.nextCursor());
        assertThat(third.items()).extracting(s->s.streamId()).containsExactly("e");
        assertThat(third.nextCursor()).isNull();
        verify(read,never()).rankedStreamIds(any(),any(),any(),anyInt());
    }

    @Test void aStreamThatStoppedBeingPlayableDisappearsInsteadOfBeingInvented() {
        UUID id=UUID.randomUUID();
        when(snapshots.find(id,NOW)).thenReturn(Optional.of(new RankingSnapshots.Snapshot(id,FilterHash.of("streams",null,null,null),List.of("a","b","c"))));
        when(read.streams(eq(List.of("b","c")),any(),any())).thenReturn(List.of(row("c",1,NOW)));
        var page=service.streams(null,null,null,2,new StreamCursor(id,1).format());
        assertThat(page.items()).extracting(s->s.streamId()).containsExactly("c");
    }

    @Test void aCursorOnlyWorksForTheFilterThatProducedIt() {
        UUID id=UUID.randomUUID();
        when(snapshots.find(id,NOW)).thenReturn(Optional.of(new RankingSnapshots.Snapshot(id,FilterHash.of("streams","ab",null,null),List.of("a","b","c"))));
        assertThatThrownBy(()->service.streams("other",null,null,2,new StreamCursor(id,1).format()))
                .isInstanceOfSatisfying(DiscoveryException.class,e->{ assertThat(e.code()).isEqualTo("INVALID_CURSOR"); assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY); });
        assertThatThrownBy(()->service.streams(null,null,null,2,new StreamCursor(id,1).format())).isInstanceOf(DiscoveryException.class);
    }

    @Test void expiredUnknownMalformedOrOutOfRangeCursorsAreInvalid() {
        UUID id=UUID.randomUUID();
        when(snapshots.find(id,NOW)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.streams(null,null,null,2,new StreamCursor(id,1).format())).isInstanceOfSatisfying(DiscoveryException.class,
                e->assertThat(e.code()).isEqualTo("INVALID_CURSOR"));
        for(String bad:new String[]{"garbage","","123","x.y"}) assertThatThrownBy(()->service.streams(null,null,null,2,bad)).isInstanceOfSatisfying(
                DiscoveryException.class,e->assertThat(e.code()).isEqualTo("INVALID_CURSOR"));
        UUID known=UUID.randomUUID();
        when(snapshots.find(known,NOW)).thenReturn(Optional.of(new RankingSnapshots.Snapshot(known,FilterHash.of("streams",null,null,null),List.of("a"))));
        assertThatThrownBy(()->service.streams(null,null,null,2,new StreamCursor(known,5).format())).isInstanceOfSatisfying(DiscoveryException.class,
                e->assertThat(e.code()).isEqualTo("INVALID_CURSOR"));
    }

    // --- streams: freshness of the viewer count

    @Test void viewerCountFreshnessIsIndependentFromStatusFreshness() {
        when(read.rankedStreamIds(any(),any(),any(),anyInt())).thenReturn(List.of("a","b","c"));
        when(read.streams(anyList(),any(),any())).thenReturn(List.of(row("a",5,NOW.minusSeconds(2)),row("b",5,NOW.minusSeconds(6)),row("c",5,null)));

        var items=service.streams(null,null,null,null,null).items();

        assertThat(items.get(0).viewerCountFresh()).isTrue();
        assertThat(items.get(1).viewerCountFresh()).isFalse();
        assertThat(items.get(1).viewerCount()).as("the last known count is kept for ranking").isEqualTo(5);
        assertThat(items.get(2).viewerCountFresh()).isFalse();
        assertThat(items.get(2).viewerCountObservedAtUtc()).as("no observation: falls back to the state observation").isEqualTo(NOW.minusSeconds(1));
        assertThat(items).allSatisfy(i->assertThat(i.statusFresh()).isTrue());
    }

    @Test void theStreamViewCarriesTheContractFields() {
        when(read.rankedStreamIds(any(),any(),any(),anyInt())).thenReturn(List.of("a"));
        when(read.streams(anyList(),any(),any())).thenReturn(List.of(row("a",9,NOW)));
        var s=service.streams(null,null,null,null,null).items().getFirst();
        assertThat(s.sessionId()).isEqualTo("ses_a");
        assertThat(s.channel().handle()).isEqualTo("h_a");
        assertThat(s.category().id()).isEqualTo(CAT);
        assertThat(s.tags()).extracting(t->t.id()).containsExactly(TAG);
        assertThat(s.status()).isEqualTo("LIVE");
        assertThat(s.availability()).isEqualTo("PLAYABLE");
        assertThat(s.metadataVersion()).isEqualTo(3);
        assertThat(s.sessionVersion()).isEqualTo(4);
    }

    // --- channels

    private void cut(Instant capturedAt) { when(projection.reconciliationState()).thenReturn(new ReconciliationState("s",1L,capturedAt,capturedAt,capturedAt,null,null)); }

    @Test void aChannelWithAFreshProjectionReportsItsRealState() {
        cut(null);
        when(read.channels(eq(null),eq(null),anyInt())).thenReturn(List.of(
                channel("live_one",true,"LIVE","PLAYABLE",NOW.minusSeconds(1)),
                channel("grace_one",true,"LIVE","RECONNECTING",NOW.minusSeconds(2)),
                channel("off_one",true,"OFFLINE","OFFLINE",NOW.minusSeconds(3)),
                channel("prep_one",true,"PREPARING","OFFLINE",NOW.minusSeconds(3)),
                channel("ended_one",true,"ENDED","OFFLINE",NOW.minusSeconds(3))));

        var items=service.channels(null,null,null).items();

        assertThat(items).extracting(c->c.status()+"/"+c.availability()).containsExactly("LIVE/PLAYABLE","LIVE/RECONNECTING","OFFLINE/OFFLINE","OFFLINE/OFFLINE","OFFLINE/OFFLINE");
        assertThat(items).allSatisfy(c->assertThat(c.statusFresh()).isTrue());
        assertThat(items.getFirst().title()).isEqualTo("Title");
        assertThat(items.getFirst().metadataVersion()).isEqualTo(3L);
    }

    @Test void aStaleProjectionIsUnknownNeverOffline() {
        cut(NOW.minusSeconds(1));     // even a fresh cut must not rejuvenate a row that exists and is stale
        when(read.channels(eq(null),eq(null),anyInt())).thenReturn(List.of(channel("stale",true,"LIVE","PLAYABLE",NOW.minusSeconds(6))));
        var item=service.channels(null,null,null).items().getFirst();
        assertThat(item.status()).isEqualTo("UNKNOWN");
        assertThat(item.availability()).isEqualTo("UNKNOWN");
        assertThat(item.statusFresh()).isFalse();
    }

    @Test void absenceIsOfflineOnlyWhileAFullCutIsFresh() {
        when(read.channels(eq(null),eq(null),anyInt())).thenReturn(List.of(channel("never",false,null,null,null)));
        cut(NOW.minusSeconds(4));
        var proven=service.channels(null,null,null);
        assertThat(proven.items().getFirst().status()).isEqualTo("OFFLINE");
        assertThat(proven.items().getFirst().statusFresh()).isTrue();
        assertThat(proven.items().getFirst().title()).isNull();
        assertThat(proven.items().getFirst().metadataVersion()).isNull();

        cut(NOW.minusSeconds(6));
        var old=service.channels(null,null,null);
        assertThat(old.items().getFirst().status()).isEqualTo("UNKNOWN");
        assertThat(old.items().getFirst().statusFresh()).isFalse();
        assertThat(old.statusFresh()).as("the connection is fresh only if every row is").isFalse();

        cut(null);
        assertThat(service.channels(null,null,null).items().getFirst().status()).isEqualTo("UNKNOWN");
    }

    @Test void anEmptyResultIsAValidFreshConnection() {
        cut(null);
        when(read.channels(eq("zzz"),eq(null),anyInt())).thenReturn(List.of());
        var result=service.channels("zzz",null,null);
        assertThat(result.items()).isEmpty();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.statusFresh()).isTrue();
    }

    @Test void channelPagingUsesAKeysetCursorBoundToTheQuery() {
        cut(null);
        var rows=List.of(channel("aaa",false,null,null,null),channel("bbb",false,null,null,null),channel("ccc",false,null,null,null));
        when(read.channels(eq("a"),eq(null),eq(3))).thenReturn(List.of(rows.get(0),rows.get(1),rows.get(2)));

        var first=service.channels("A",2,null);

        assertThat(first.items()).extracting(c->c.handle()).containsExactly("aaa","bbb");
        var expected=new ChannelCursor(0,0,"bbb","usr_bbb",FilterHash.of("channels","a",null,null));
        assertThat(first.nextCursor()).isEqualTo(expected.format());

        when(read.channels(eq("a"),eq(expected),eq(3))).thenReturn(List.of(rows.get(2)));
        var second=service.channels("a",2,first.nextCursor());
        assertThat(second.items()).extracting(c->c.handle()).containsExactly("ccc");
        assertThat(second.nextCursor()).isNull();

        assertThatThrownBy(()->service.channels("different",2,first.nextCursor())).isInstanceOfSatisfying(DiscoveryException.class,
                e->assertThat(e.code()).isEqualTo("INVALID_CURSOR"));
        assertThatThrownBy(()->service.channels("a",2,"garbage!")).isInstanceOfSatisfying(DiscoveryException.class,
                e->assertThat(e.code()).isEqualTo("INVALID_CURSOR"));
    }

    @Test void channelResultsExposeOnlyPublicFields() {
        cut(null);
        when(read.channels(eq(null),eq(null),anyInt())).thenReturn(List.of(channel("pub",true,"LIVE","PLAYABLE",NOW)));
        var c=service.channels(null,null,null).items().getFirst();
        assertThat(c.channelId()).isEqualTo("chn_pub");
        assertThat(c.userId()).isEqualTo("usr_pub");
        assertThat(c.channelVersion()).isEqualTo(2);
        assertThat(c.displayName()).isEqualTo("Name pub");
        assertThat(c.getClass().getRecordComponents()).extracting(rc->rc.getName()).doesNotContain("email","passwordHash","credential","streamKey");
    }
}
