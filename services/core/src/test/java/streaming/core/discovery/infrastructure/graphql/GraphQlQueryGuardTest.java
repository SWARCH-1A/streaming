package streaming.core.discovery.infrastructure.graphql;

import graphql.parser.Parser;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import streaming.core.discovery.application.DiscoveryException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphQlQueryGuardTest {
    private final GraphQlQueryGuard guard=new GraphQlQueryGuard();

    private void check(String query) { check(query,Map.of()); }
    private void check(String query,Map<String,Object> variables) { guard.check(Parser.parse(query),variables); }
    private void rejected(String query) { rejected(query,Map.of()); }
    private void rejected(String query,Map<String,Object> variables) {
        assertThatThrownBy(()->check(query,variables)).as(query).isInstanceOfSatisfying(DiscoveryException.class,e->{
            assertThat(e.code()).isEqualTo("QUERY_LIMIT_EXCEEDED");
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        });
    }

    @Test void theQueriesOfTheContractPass() {
        check("query LiveStreams($q:String,$categoryId:ID,$tagId:ID,$limit:Int,$cursor:String){streams(q:$q,categoryId:$categoryId,tagId:$tagId,limit:$limit,cursor:$cursor)"
                +"{items{streamId sessionId channel{channelId handle displayName avatarUri} title category{id name} tags{id name} status availability viewerCount "
                +"viewerCountFresh viewerCountObservedAtUtc startedAtUtc metadataVersion sessionVersion statusFresh} nextCursor generatedAtUtc statusFresh}}",
                Map.of("limit",20));
        check("{ channels(q:\"abc\", limit: 50){ items{ handle } } }");
        check("{ streams { items { streamId } } channels { items { handle } } }");
    }

    @Test void twoRootsAtFiftyAreExactlyTheAllowedTotal() {
        check("{ streams(limit:50){items{streamId}} channels(limit:50){items{handle}} }");
    }

    @Test void moreThanFiftyRowsInOneConnectionIsRejected() {
        rejected("{ streams(limit:51){items{streamId}} }");
        rejected("{ channels(limit:1000){items{handle}} }");
        rejected("{ streams(limit:99999999999999999999){items{streamId}} }");
        rejected("query($l:Int){ streams(limit:$l){items{streamId}} }",Map.of("l",51));
        rejected("query($l:Int=60){ streams(limit:$l){items{streamId}} }");
        check("query($l:Int=60){ streams(limit:$l){items{streamId}} }",Map.of("l",10));
        check("query($l:Int){ streams(limit:$l){items{streamId}} }");
    }

    @Test void nonPositiveLimitsAreLeftToTheFieldValidation() {
        assertThatCode(()->check("{ streams(limit:0){items{streamId}} }")).doesNotThrowAnyException();
        assertThatCode(()->check("{ streams(limit:-3){items{streamId}} }")).doesNotThrowAnyException();
    }

    @Test void rowsCountedAcrossRootFieldsNeverExceedOneHundred() {
        check("{ streams(limit:50){items{streamId}} channels(limit:50){items{handle}} }");
        rejected("{ streams(limit:50){items{streamId}} channels(limit:51){items{handle}} }");
    }

    @Test void aliasesAreRejectedAtEveryDepth() {
        rejected("{ a: streams { items { streamId } } }");
        rejected("{ streams { items { id: streamId } } }");
        rejected("{ streams { items { channel { name: handle } } } }");
    }

    @Test void fragmentsAreRejectedInAnyForm() {
        rejected("{ streams { items { ...F } } } fragment F on LiveStream { streamId }");
        rejected("{ streams { items { ... on LiveStream { streamId } } } }");
        rejected("{ ...Q } fragment Q on Query { streams { items { streamId } } }");
        rejected("fragment F on LiveStream { streamId }");
    }

    @Test void introspectionIsRejectedEvenAsATypenameOrNested() {
        rejected("{ __schema { types { name } } }");
        rejected("{ __type(name:\"Query\") { name } }");
        rejected("{ streams { __typename } }");
        rejected("{ streams { items { channel { __typename } } } }");
    }

    @Test void onlyStreamsAndChannelsMayBeRootFieldsEachOnce() {
        rejected("{ viewer { id } }");
        rejected("{ streams { items { streamId } } viewer { id } }");
        rejected("{ streams { items { streamId } } streams { items { streamId } } }");
        rejected("{ channels { items { handle } } channels { items { handle } } }");
    }

    @Test void onlyOneQueryOperationIsAllowed() {
        rejected("mutation { streams { items { streamId } } }");
        rejected("subscription { streams { items { streamId } } }");
        rejected("query A { streams { items { streamId } } } query B { channels { items { handle } } }");
        rejected("type Foo { a: Int }");
        rejected("query A { streams { items { streamId } } } fragment F on Query { channels { items { handle } } }");
    }
}
