package streaming.core.watchparty.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import streaming.core.watchparty.application.AccountSessions.Principal;
import streaming.core.watchparty.application.ChannelDirectory.ChannelCard;
import streaming.core.watchparty.application.PartyView.SourceState;
import streaming.core.watchparty.application.StreamDirectory.Lookup;
import streaming.core.watchparty.application.StreamDirectory.StreamSnapshot;
import streaming.core.watchparty.application.WatchPartyStore.Operation;
import streaming.core.watchparty.application.WatchPartyStore.Party;
import streaming.core.watchparty.application.WatchPartyStore.PartyStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WatchPartyApplicationServiceTest {
    private static final String PARTY="wp_"+"a".repeat(32);
    private static final String STREAM="str_550e8400-e29b-41d4-a716-446655440000";
    private static final String CODE="A".repeat(43);
    private static final Instant T0=Instant.parse("2026-10-30T20:00:00Z");
    private static final UUID KEY=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Principal OWNER=new Principal("usr_owner"), MEMBER=new Principal("usr_member"), STRANGER=new Principal("usr_stranger");

    @Mock WatchPartyStore store;
    @Mock AccountSessions accounts;
    @Mock StreamDirectory streams;
    @Mock ChannelDirectory channels;
    private WatchPartyApplicationService service;

    @BeforeEach void setUp() {
        service=new WatchPartyApplicationService(store,accounts,streams,channels,new TransactionTemplate(new NoopTransactions()));
    }

    // --- sessions and idempotency keys

    @Test void requirePrincipalReturnsThePrincipalOfItsOwnPort() {
        when(accounts.introspect("cookie")).thenReturn(Optional.of(OWNER));
        assertThat(service.requirePrincipal("cookie")).isSameAs(OWNER);
    }

    @Test void requirePrincipalRejectsMissingOrUnknownSessions() {
        when(accounts.introspect(null)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.requirePrincipal(null)).isInstanceOfSatisfying(WatchPartyException.class,e-> {
            assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(e.code()).isEqualTo("AUTH_REQUIRED");
        });
    }

    @Test void everyCommandRequiresAnIdempotencyKeyBeforeTouchingAnything() {
        Runnable[] commands={()->service.create(OWNER,null,body("title","A")),()->service.join(MEMBER,null,body("accessCode",CODE)),
                ()->service.addStream(OWNER,null,PARTY,body("streamId",STREAM)),()->service.removeStream(OWNER,null,PARTY,STREAM),
                ()->service.rotateAccessCode(OWNER,null,PARTY),()->service.close(OWNER,null,PARTY)};
        for(Runnable command:commands) assertThatThrownBy(command::run).isInstanceOfSatisfying(WatchPartyException.class,e-> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        });
        verifyNoInteractions(store,streams,channels);
    }

    // --- RF-046 create

    @Test void createShowsTheCodeOnceStoresOnlyItsSha256AndRecordsTheOutcome() {
        when(store.create(anyString(),eq("usr_owner"),eq("Final del torneo"),anyString(),any())).thenAnswer(i->party(i.getArgument(0),"OPEN",0));
        when(store.memberCount(anyString())).thenReturn(1);

        PartyView view=service.create(OWNER,KEY,body("title","  Final del torneo  "));

        var hash=ArgumentCaptor.forClass(String.class);
        var id=ArgumentCaptor.forClass(String.class);
        verify(store).create(id.capture(),eq("usr_owner"),eq("Final del torneo"),hash.capture(),any());
        assertThat(id.getValue()).matches("wp_[0-9a-f]{32}");
        assertThat(view.accessCode()).matches("[A-Za-z0-9_-]{43}");
        assertThat(hash.getValue()).isEqualTo(sha256(view.accessCode())).hasSize(64).isNotEqualTo(view.accessCode());
        assertThat(view.status()).isEqualTo("OPEN");
        assertThat(view.isOwner()).isTrue();
        assertThat(view.maxSources()).isEqualTo(4);
        assertThat(view.memberCount()).isEqualTo(1);
        assertThat(view.partyVersion()).isZero();
        assertThat(view.sources()).isEmpty();
        // The key and the payload are recorded only as hashes; the code is never recorded.
        verify(store).rememberOperation(eq("usr_owner"),eq(sha256(KEY.toString())),eq(sha256("CREATE|Final del torneo")),eq(id.getValue()),any(),any());
    }

    @Test void everyCreationGetsADifferentCode() {
        when(store.create(anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(i->party(i.getArgument(0),"OPEN",0));
        UUID other=UUID.fromString("22222222-2222-4222-8222-222222222222");
        assertThat(service.create(OWNER,KEY,body("title","A")).accessCode()).isNotEqualTo(service.create(OWNER,other,body("title","A")).accessCode());
    }

    @Test void repeatingTheSameCreationReplaysTheRoomWithoutShowingTheCodeAgain() {
        when(store.operation(eq("usr_owner"),eq(sha256(KEY.toString())),any())).thenReturn(Optional.of(new Operation(sha256("CREATE|A"),PARTY)));
        when(store.find(PARTY)).thenReturn(Optional.of(party(PARTY,"OPEN",0)));

        PartyView view=service.create(OWNER,KEY,body("title","A"));

        assertThat(view.partyId()).isEqualTo(PARTY);
        assertThat(view.accessCode()).isNull();
        verify(store,never()).create(anyString(),anyString(),anyString(),anyString(),any());
        verify(store,never()).rememberOperation(anyString(),anyString(),anyString(),anyString(),any(),any());
    }

    @Test void theSameKeyWithAnotherPayloadIsAConflictAndChangesNothing() {
        when(store.operation(eq("usr_owner"),eq(sha256(KEY.toString())),any())).thenReturn(Optional.of(new Operation(sha256("CREATE|A"),PARTY)));
        assertConflict(()->service.create(OWNER,KEY,body("title","B")),"IDEMPOTENCY_CONFLICT");
        verify(store,never()).create(anyString(),anyString(),anyString(),anyString(),any());
    }

    @Test void createRejectsInvalidBodiesBeforeTouchingStorage() {
        JsonNode[] bad={JsonNodeFactory.instance.arrayNode(),JsonNodeFactory.instance.stringNode("x"),JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode().put("title",5),JsonNodeFactory.instance.objectNode().putNull("title"),
                body("title","   "),body("title","x".repeat(101)),body("title","a\u0000b"),
                JsonNodeFactory.instance.objectNode().put("title","ok").put("ownerUserId","usr_other")};
        for(JsonNode node:bad) assertThatThrownBy(()->service.create(OWNER,KEY,node)).isInstanceOfSatisfying(WatchPartyException.class,e-> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo("VALIDATION_ERROR");
        });
        verifyNoInteractions(store);
    }

    // --- reading (RF-049, RF-051) and the honest state of every source

    @Test void malformedOrUnknownPartyIdsAreNotFoundAndMalformedOnesNeverReachStorage() {
        assertNotFound(()->service.get(OWNER,"not-a-party"));
        verifyNoInteractions(store);
        when(store.find(PARTY)).thenReturn(Optional.empty());
        assertNotFound(()->service.get(OWNER,PARTY));
    }

    @Test void aNonMemberCannotTellAnExistingPartyFromAMissingOne() {
        when(store.find(PARTY)).thenReturn(Optional.of(party(PARTY,"OPEN",0)));
        when(store.isMember(PARTY,"usr_stranger")).thenReturn(false);
        assertNotFound(()->service.get(STRANGER,PARTY));
        verifyNoInteractions(streams);
    }

    @Test void aMemberReadsAPlayableSourceWithItsSessionAndChannel() {
        memberReads(List.of(new PartyStream(STREAM,"chn_1",T0)));
        when(streams.findAll(List.of(STREAM))).thenReturn(Map.of(STREAM,Lookup.found(snapshot("LIVE","PLAYABLE",true,8,"ses_1"))));
        when(channels.byChannelId("chn_1")).thenReturn(Optional.of(card("chn_1","usr_c1","caster_02")));
        when(channels.byOwner("usr_owner")).thenReturn(Optional.of(card("chn_0","usr_owner","caster_01")));
        when(store.memberCount(PARTY)).thenReturn(2);

        PartyView view=service.get(MEMBER,PARTY);

        assertThat(view.isOwner()).isFalse();
        assertThat(view.accessCode()).isNull();
        assertThat(view.memberCount()).isEqualTo(2);
        assertThat(view.partyVersion()).isEqualTo(3);
        assertThat(view.owner().handle()).isEqualTo("caster_01");
        var source=view.sources().getFirst();
        assertThat(source.state()).isEqualTo(SourceState.PLAYABLE);
        assertThat(source.sessionId()).isEqualTo("ses_1");
        assertThat(source.title()).isEqualTo("En vivo");
        assertThat(source.category()).isEqualTo("Conversación");
        assertThat(source.viewerCount()).isEqualTo(8);
        assertThat(source.statusFresh()).isTrue();
        assertThat(source.channel().handle()).isEqualTo("caster_02");
    }

    @Test void onlyAFreshLiveAndPlayableSourceCarriesASessionAndNothingUnconfirmedLooksLive() {
        when(channels.byChannelId("chn_1")).thenReturn(Optional.of(card("chn_1","usr_c1","caster_02")));
        record Case(Lookup lookup,SourceState expected) { }
        Case[] cases={
                new Case(Lookup.found(snapshot("LIVE","RECONNECTING",true,4,"ses_1")),SourceState.RECONNECTING),
                new Case(Lookup.found(snapshot("OFFLINE","OFFLINE",true,0,null)),SourceState.OFFLINE),
                new Case(Lookup.found(snapshot("PREPARING","OFFLINE",true,0,"ses_1")),SourceState.OFFLINE),
                new Case(Lookup.found(snapshot("ENDED","OFFLINE",true,0,"ses_1")),SourceState.ENDED),
                new Case(Lookup.found(snapshot("LIVE","PLAYABLE",false,4,"ses_1")),SourceState.UNKNOWN),
                new Case(Lookup.found(snapshot("LIVE","PLAYABLE",true,4,null)),SourceState.OFFLINE),
                new Case(Lookup.notFound(),SourceState.ENDED),
                new Case(Lookup.unavailable(),SourceState.UNKNOWN)};
        for(Case c:cases) {
            memberReads(List.of(new PartyStream(STREAM,"chn_1",T0)));
            when(streams.findAll(List.of(STREAM))).thenReturn(Map.of(STREAM,c.lookup()));
            var source=service.get(MEMBER,PARTY).sources().getFirst();
            assertThat(source.state()).as(c.lookup().toString()).isEqualTo(c.expected());
            assertThat(source.sessionId()).as(c.lookup().toString()).isNull();
            assertThat(source.channel().handle()).isEqualTo("caster_02");
        }
    }

    @Test void aSuspendedChannelIsHiddenAndNeverConsumableFromTheRoom() {
        memberReads(List.of(new PartyStream(STREAM,"chn_1",T0)));
        when(streams.findAll(List.of(STREAM))).thenReturn(Map.of(STREAM,Lookup.found(snapshot("LIVE","PLAYABLE",true,8,"ses_1"))));
        when(channels.byChannelId("chn_1")).thenReturn(Optional.empty());

        var source=service.get(MEMBER,PARTY).sources().getFirst();

        assertThat(source.state()).isEqualTo(SourceState.SUSPENDED);
        assertThat(source.sessionId()).isNull();
        assertThat(source.channel()).isNull();
        assertThat(source.title()).isNull();
        assertThat(source.viewerCount()).isNull();
        assertThat(source.streamId()).isEqualTo(STREAM);
    }

    @Test void oneSuspendedSourceDoesNotChangeTheOthers() {
        memberReads(List.of(new PartyStream(STREAM,"chn_1",T0),new PartyStream("str_2","chn_2",T0)));
        when(streams.findAll(List.of(STREAM,"str_2"))).thenReturn(Map.of(
                STREAM,Lookup.found(snapshot("LIVE","PLAYABLE",true,8,"ses_1")),
                "str_2",Lookup.found(new StreamSnapshot("str_2","chn_2","ses_2","Otra","Música","LIVE","PLAYABLE",true,2))));
        when(channels.byChannelId("chn_1")).thenReturn(Optional.empty());
        when(channels.byChannelId("chn_2")).thenReturn(Optional.of(card("chn_2","usr_c2","caster_03")));

        var sources=service.get(MEMBER,PARTY).sources();

        assertThat(sources).extracting(PartyView.Source::state).containsExactly(SourceState.SUSPENDED,SourceState.PLAYABLE);
        assertThat(sources.get(1).sessionId()).isEqualTo("ses_2");
    }

    @Test void aClosedRoomListsNoSourceAndNeverAsksStreaming() {
        when(store.find(PARTY)).thenReturn(Optional.of(party(PARTY,"CLOSED",4)));
        when(store.isMember(PARTY,"usr_member")).thenReturn(true);

        PartyView view=service.get(MEMBER,PARTY);

        assertThat(view.status()).isEqualTo("CLOSED");
        assertThat(view.sources()).isEmpty();
        assertThat(view.closedAtUtc()).isEqualTo(T0);
        verifyNoInteractions(streams);
        verify(store,never()).streams(anyString());
    }

    // --- RF-050 join

    @Test void joinWithAMalformedCodeLooksLikeAnUnknownCodeAndNeverReachesStorage() {
        assertNotFound(()->service.join(MEMBER,KEY,body("accessCode","short")));
        verifyNoInteractions(store);
    }

    @Test void joinRequiresAWellFormedBody() {
        for(JsonNode bad:new JsonNode[]{JsonNodeFactory.instance.objectNode(),JsonNodeFactory.instance.objectNode().put("accessCode",1),
                JsonNodeFactory.instance.objectNode().put("accessCode",CODE).put("x","y"),JsonNodeFactory.instance.arrayNode()})
            assertThatThrownBy(()->service.join(MEMBER,KEY,bad)).isInstanceOfSatisfying(WatchPartyException.class,
                    e->assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test void joinUnknownCodeIsNotFound() {
        when(store.lockByCodeHash(sha256(CODE))).thenReturn(Optional.empty());
        assertNotFound(()->service.join(MEMBER,KEY,body("accessCode",CODE)));
    }

    @Test void joinClosedPartyIsAConflict() {
        when(store.lockByCodeHash(sha256(CODE))).thenReturn(Optional.of(party(PARTY,"CLOSED",4)));
        assertClosed(()->service.join(MEMBER,KEY,body("accessCode",CODE)));
        verify(store,never()).addMember(anyString(),anyString(),any());
    }

    @Test void joinAddsTheMemberUsingOnlyTheHashOfTheCodeAndNeverRecordsTheCode() {
        when(store.lockByCodeHash(sha256(CODE))).thenReturn(Optional.of(party(PARTY,"OPEN",0)));
        when(store.memberCount(PARTY)).thenReturn(2);

        PartyView view=service.join(MEMBER,KEY,body("accessCode",CODE));

        verify(store).addMember(eq(PARTY),eq("usr_member"),any());
        verify(store).rememberOperation(eq("usr_member"),anyString(),eq(sha256("JOIN|"+sha256(CODE))),eq(PARTY),any(),any());
        assertThat(view.accessCode()).isNull();
        assertThat(view.isOwner()).isFalse();
    }

    // --- RF-047 add

    @Test void addRequiresTheOwnerAndHidesThePartyFromStrangers() {
        when(store.find(PARTY)).thenReturn(Optional.of(party(PARTY,"OPEN",0)));
        when(store.isMember(PARTY,"usr_member")).thenReturn(true);
        when(store.isMember(PARTY,"usr_stranger")).thenReturn(false);

        assertThatThrownBy(()->service.addStream(MEMBER,KEY,PARTY,body("streamId",STREAM))).isInstanceOfSatisfying(WatchPartyException.class,e-> {
            assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(e.code()).isEqualTo("WATCH_PARTY_FORBIDDEN");
        });
        assertNotFound(()->service.addStream(STRANGER,KEY,PARTY,body("streamId",STREAM)));
        verifyNoInteractions(streams);
        verify(store,never()).addStream(anyString(),anyString(),anyString(),any());
    }

    @Test void addRejectsABadStreamIdBeforeAnyLookup() {
        for(String bad:new String[]{"../x","a/b","","a b"}) assertThatThrownBy(()->service.addStream(OWNER,KEY,PARTY,body("streamId",bad)))
                .isInstanceOfSatisfying(WatchPartyException.class,e->assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));
        verifyNoInteractions(store,streams);
    }

    @Test void addToAClosedPartyDoesNotCallStreaming() {
        when(store.find(PARTY)).thenReturn(Optional.of(party(PARTY,"CLOSED",2)));
        assertClosed(()->service.addStream(OWNER,KEY,PARTY,body("streamId",STREAM)));
        verifyNoInteractions(streams);
    }

    @Test void addDuplicateOrFullFailsEarlyWithoutACallToStreaming() {
        when(store.find(PARTY)).thenReturn(Optional.of(party(PARTY,"OPEN",2)));
        when(store.hasStream(PARTY,STREAM)).thenReturn(true);
        assertConflict(()->service.addStream(OWNER,KEY,PARTY,body("streamId",STREAM)),"STREAM_ALREADY_IN_PARTY");

        when(store.hasStream(PARTY,STREAM)).thenReturn(false);
        when(store.countStreams(PARTY)).thenReturn(4);
        assertConflict(()->service.addStream(OWNER,KEY,PARTY,body("streamId",STREAM)),"WATCH_PARTY_FULL");
        verifyNoInteractions(streams);
    }

    @Test void addFailsClosedWhenStreamingIsDownAndStoresNothing() {
        openPartyForOwner();
        when(streams.find(STREAM)).thenReturn(Lookup.unavailable());
        assertThatThrownBy(()->service.addStream(OWNER,KEY,PARTY,body("streamId",STREAM))).isInstanceOfSatisfying(WatchPartyException.class,e-> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.code()).isEqualTo("STREAMING_UNAVAILABLE");
        });
        verify(store,never()).addStream(anyString(),anyString(),anyString(),any());
        verify(store,never()).rememberOperation(anyString(),anyString(),anyString(),anyString(),any(),any());
    }

    @Test void addRejectsUnknownAndNotLiveStreams() {
        openPartyForOwner();
        when(streams.find(STREAM)).thenReturn(Lookup.notFound());
        assertThatThrownBy(()->service.addStream(OWNER,KEY,PARTY,body("streamId",STREAM))).isInstanceOfSatisfying(WatchPartyException.class,e-> {
            assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.code()).isEqualTo("STREAM_NOT_FOUND");
        });
        StreamSnapshot[] notLive={snapshot("OFFLINE","OFFLINE",true,0,null),snapshot("LIVE","RECONNECTING",true,1,"ses_1"),
                snapshot("LIVE","PLAYABLE",false,1,"ses_1"),snapshot("ENDED","OFFLINE",true,0,"ses_1"),snapshot("LIVE","PLAYABLE",true,1,null)};
        for(StreamSnapshot candidate:notLive) {
            when(streams.find(STREAM)).thenReturn(Lookup.found(candidate));
            assertConflict(()->service.addStream(OWNER,KEY,PARTY,body("streamId",STREAM)),"STREAM_NOT_LIVE");
        }
        verify(store,never()).addStream(anyString(),anyString(),anyString(),any());
    }

    @Test void addRejectsAStreamWhoseChannelIsNotPublishedBecauseItsOwnerIsSuspended() {
        openPartyForOwner();
        when(streams.find(STREAM)).thenReturn(Lookup.found(snapshot("LIVE","PLAYABLE",true,1,"ses_1")));
        when(channels.byChannelId("chn_1")).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.addStream(OWNER,KEY,PARTY,body("streamId",STREAM))).isInstanceOfSatisfying(WatchPartyException.class,
                e->assertThat(e.code()).isEqualTo("STREAM_NOT_FOUND"));
        verify(store,never()).addStream(anyString(),anyString(),anyString(),any());
    }

    @Test void addStoresTheChannelReportedByStreamingReusesTheLookupAndRecordsTheOutcome() {
        openPartyForOwner();
        when(streams.find(STREAM)).thenReturn(Lookup.found(snapshot("LIVE","PLAYABLE",true,5,"ses_1")));
        when(channels.byChannelId("chn_1")).thenReturn(Optional.of(card("chn_1","usr_c1","caster_02")));
        when(store.lock(PARTY)).thenReturn(Optional.of(party(PARTY,"OPEN",0)));
        when(store.bump(eq(PARTY),any())).thenReturn(party(PARTY,"OPEN",1));
        when(store.streams(PARTY)).thenReturn(List.of(new PartyStream(STREAM,"chn_1",T0)));

        PartyView view=service.addStream(OWNER,KEY,PARTY,body("streamId",STREAM));

        verify(store).addStream(eq(PARTY),eq(STREAM),eq("chn_1"),any());
        verify(store).rememberOperation(eq("usr_owner"),eq(sha256(KEY.toString())),eq(sha256("ADD|"+PARTY+"|"+STREAM)),eq(PARTY),any(),any());
        verify(streams,never()).findAll(any());
        assertThat(view.partyVersion()).isEqualTo(1);
        assertThat(view.sources()).hasSize(1);
        assertThat(view.sources().getFirst().viewerCount()).isEqualTo(5);
    }

    @Test void repeatingTheSameAddAnswersWithoutAskingStreamingAgain() {
        when(store.find(PARTY)).thenReturn(Optional.of(party(PARTY,"OPEN",1)));
        when(store.operation(eq("usr_owner"),eq(sha256(KEY.toString())),any())).thenReturn(Optional.of(new Operation(sha256("ADD|"+PARTY+"|"+STREAM),PARTY)));
        when(store.streams(PARTY)).thenReturn(List.of(new PartyStream(STREAM,"chn_1",T0)));
        when(streams.findAll(List.of(STREAM))).thenReturn(Map.of(STREAM,Lookup.found(snapshot("ENDED","OFFLINE",true,0,"ses_1"))));
        when(channels.byChannelId("chn_1")).thenReturn(Optional.of(card("chn_1","usr_c1","caster_02")));

        PartyView view=service.addStream(OWNER,KEY,PARTY,body("streamId",STREAM));

        // The stream ended meanwhile, yet the recorded command is replayed instead of failing with STREAM_NOT_LIVE.
        assertThat(view.sources()).hasSize(1);
        verify(streams,never()).find(anyString());
        verify(store,never()).addStream(anyString(),anyString(),anyString(),any());
    }

    @Test void theLimitAndDuplicatesAreCheckedAgainUnderTheRowLockToSurviveRaces() {
        openPartyForOwner();
        when(streams.find(STREAM)).thenReturn(Lookup.found(snapshot("LIVE","PLAYABLE",true,1,"ses_1")));
        when(channels.byChannelId("chn_1")).thenReturn(Optional.of(card("chn_1","usr_c1","caster_02")));
        when(store.lock(PARTY)).thenReturn(Optional.of(party(PARTY,"OPEN",0)));
        // The cheap pre-checks passed (stubs of openPartyForOwner) but a concurrent request filled the party meanwhile.
        when(store.countStreams(PARTY)).thenReturn(3,4);
        assertConflict(()->service.addStream(OWNER,KEY,PARTY,body("streamId",STREAM)),"WATCH_PARTY_FULL");

        when(store.countStreams(PARTY)).thenReturn(0);
        when(store.hasStream(PARTY,STREAM)).thenReturn(false,true);
        assertConflict(()->service.addStream(OWNER,KEY,PARTY,body("streamId",STREAM)),"STREAM_ALREADY_IN_PARTY");
        verify(store,never()).addStream(anyString(),anyString(),anyString(),any());
    }

    @Test void thePartyClosingMeanwhileWinsOverTheAdd() {
        openPartyForOwner();
        when(streams.find(STREAM)).thenReturn(Lookup.found(snapshot("LIVE","PLAYABLE",true,1,"ses_1")));
        when(channels.byChannelId("chn_1")).thenReturn(Optional.of(card("chn_1","usr_c1","caster_02")));
        when(store.lock(PARTY)).thenReturn(Optional.of(party(PARTY,"CLOSED",3)));
        assertClosed(()->service.addStream(OWNER,KEY,PARTY,body("streamId",STREAM)));
        verify(store,never()).addStream(anyString(),anyString(),anyString(),any());
    }

    // --- RF-048 remove

    @Test void removeBumpsTheVersionOnlyWhenTheStreamWasThere() {
        when(store.lock(PARTY)).thenReturn(Optional.of(party(PARTY,"OPEN",2)));
        when(store.removeStream(PARTY,STREAM)).thenReturn(true);
        when(store.bump(eq(PARTY),any())).thenReturn(party(PARTY,"OPEN",3));
        assertThat(service.removeStream(OWNER,KEY,PARTY,STREAM).partyVersion()).isEqualTo(3);

        when(store.removeStream(PARTY,STREAM)).thenReturn(false);
        assertThat(service.removeStream(OWNER,KEY,PARTY,STREAM).partyVersion()).isEqualTo(2);
        verify(store).bump(eq(PARTY),any());
    }

    @Test void removeIsOwnerOnlyAndRejectedOnAClosedParty() {
        when(store.lock(PARTY)).thenReturn(Optional.of(party(PARTY,"OPEN",0)),Optional.of(party(PARTY,"OPEN",0)),Optional.of(party(PARTY,"CLOSED",1)));
        when(store.isMember(PARTY,"usr_member")).thenReturn(true);
        when(store.isMember(PARTY,"usr_stranger")).thenReturn(false);
        assertThatThrownBy(()->service.removeStream(MEMBER,KEY,PARTY,STREAM)).isInstanceOfSatisfying(WatchPartyException.class,
                e->assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
        assertNotFound(()->service.removeStream(STRANGER,KEY,PARTY,STREAM));
        assertClosed(()->service.removeStream(OWNER,KEY,PARTY,STREAM));
        verify(store,never()).removeStream(anyString(),anyString());
    }

    // --- rotate and close

    @Test void rotateStoresTheHashOfANewCodeAndShowsItOnce() {
        when(store.lock(PARTY)).thenReturn(Optional.of(party(PARTY,"OPEN",1)));
        when(store.rotateCode(eq(PARTY),anyString(),any())).thenReturn(party(PARTY,"OPEN",2));

        PartyView view=service.rotateAccessCode(OWNER,KEY,PARTY);

        var hash=ArgumentCaptor.forClass(String.class);
        verify(store).rotateCode(eq(PARTY),hash.capture(),any());
        assertThat(view.accessCode()).matches("[A-Za-z0-9_-]{43}");
        assertThat(hash.getValue()).isEqualTo(sha256(view.accessCode()));
        assertThat(view.partyVersion()).isEqualTo(2);
    }

    @Test void repeatingARotationReplaysTheRoomWithoutRotatingAgainOrShowingACode() {
        when(store.operation(eq("usr_owner"),eq(sha256(KEY.toString())),any())).thenReturn(Optional.of(new Operation(sha256("ROTATE|"+PARTY),PARTY)));
        when(store.find(PARTY)).thenReturn(Optional.of(party(PARTY,"OPEN",2)));

        PartyView view=service.rotateAccessCode(OWNER,KEY,PARTY);

        assertThat(view.accessCode()).isNull();
        verify(store,never()).rotateCode(anyString(),anyString(),any());
    }

    @Test void rotateIsOwnerOnlyAndRejectedOnAClosedParty() {
        when(store.lock(PARTY)).thenReturn(Optional.of(party(PARTY,"OPEN",0)),Optional.of(party(PARTY,"CLOSED",1)));
        when(store.isMember(PARTY,"usr_member")).thenReturn(true);
        assertThatThrownBy(()->service.rotateAccessCode(MEMBER,KEY,PARTY)).isInstanceOfSatisfying(WatchPartyException.class,
                e->assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
        assertClosed(()->service.rotateAccessCode(OWNER,KEY,PARTY));
        verify(store,never()).rotateCode(anyString(),anyString(),any());
    }

    @Test void closeIsIdempotentAndOwnerOnly() {
        when(store.lock(PARTY)).thenReturn(Optional.of(party(PARTY,"OPEN",1)),Optional.of(party(PARTY,"CLOSED",2)),Optional.of(party(PARTY,"OPEN",1)));
        when(store.close(eq(PARTY),any())).thenReturn(party(PARTY,"CLOSED",2));
        when(store.isMember(PARTY,"usr_member")).thenReturn(true);

        assertThat(service.close(OWNER,KEY,PARTY).status()).isEqualTo("CLOSED");
        assertThat(service.close(OWNER,KEY,PARTY).status()).isEqualTo("CLOSED");
        verify(store).close(eq(PARTY),any());
        assertThatThrownBy(()->service.close(MEMBER,KEY,PARTY)).isInstanceOfSatisfying(WatchPartyException.class,
                e->assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test void commandsOnAMalformedPartyIdAreNotFoundWithoutStorageAccess() {
        assertNotFound(()->service.close(OWNER,KEY,"x"));
        assertNotFound(()->service.rotateAccessCode(OWNER,KEY,"x"));
        assertNotFound(()->service.removeStream(OWNER,KEY,"x",STREAM));
        assertNotFound(()->service.addStream(OWNER,KEY,"x",body("streamId",STREAM)));
        verifyNoInteractions(store);
    }

    @Test void expiredOperationsArePurgedOnASchedule() {
        service.purge();
        verify(store).purgeOperations(any());
    }

    // --- helpers

    private void openPartyForOwner() {
        when(store.find(PARTY)).thenReturn(Optional.of(party(PARTY,"OPEN",0)));
    }
    private void memberReads(List<PartyStream> items) {
        when(store.find(PARTY)).thenReturn(Optional.of(party(PARTY,"OPEN",3)));
        when(store.isMember(PARTY,"usr_member")).thenReturn(true);
        when(store.streams(PARTY)).thenReturn(items);
    }
    private static Party party(String id,String status,long version) {
        return new Party(id,"usr_owner","Final del torneo",status,version,T0,T0,"CLOSED".equals(status)?T0:null);
    }
    private static StreamSnapshot snapshot(String status,String availability,boolean fresh,int viewers,String sessionId) {
        return new StreamSnapshot(STREAM,"chn_1",sessionId,"En vivo","Conversación",status,availability,fresh,viewers);
    }
    private static ChannelCard card(String channelId,String owner,String handle) {
        return new ChannelCard(channelId,owner,handle,"Nombre "+handle,null,"Descripción",null);
    }
    private static JsonNode body(String field,String value) { return JsonNodeFactory.instance.objectNode().put(field,value); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(WatchPartyException.class,e-> {
            assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.code()).isEqualTo("WATCH_PARTY_NOT_FOUND");
        });
    }
    private static void assertClosed(Runnable call) { assertConflict(call,"WATCH_PARTY_CLOSED"); }
    private static void assertConflict(Runnable call,String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(WatchPartyException.class,e-> {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    /** Executes transaction callbacks directly; real atomicity is covered by the PostgreSQL integration test. */
    private static final class NoopTransactions implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
        @Override public void commit(TransactionStatus status) { }
        @Override public void rollback(TransactionStatus status) { }
    }
}
