package streaming.core.discovery.domain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorsTest {
    private static final String FILTER=FilterHash.of("channels","ab",null,null);

    @Test void streamCursorRoundTrips() {
        var cursor=new StreamCursor(UUID.randomUUID(),40);
        assertThat(StreamCursor.parse(cursor.format())).isEqualTo(cursor);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings={"","x","123","not-a-uuid.5","00000000-0000-0000-0000-000000000000.-1","00000000-0000-0000-0000-000000000000.",
            "00000000-0000-0000-0000-000000000000.1234567","00000000-0000-0000-0000-000000000000.1.2","1-1-1-1-1.3",
            "00000000-0000-0000-0000-00000000000G.1","00000000-0000-0000-0000-000000000000.1 "})
    void malformedStreamCursorsAreRejected(String value) {
        assertThatThrownBy(()->StreamCursor.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void streamCursorRejectsUppercaseUuidSoEveryCursorHasOneForm() {
        String id=UUID.randomUUID().toString().toUpperCase();
        assertThatThrownBy(()->StreamCursor.parse(id+".1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void channelCursorRoundTrips() {
        var cursor=new ChannelCursor(1,0,"caster_01","usr_abc-1",FILTER);
        assertThat(ChannelCursor.parse(cursor.format())).isEqualTo(cursor);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings={"","!!!","YQ","a b"})
    void garbageChannelCursorsAreRejected(String value) {
        assertThatThrownBy(()->ChannelCursor.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void tamperedChannelCursorsAreRejected() {
        for(String raw:new String[]{"3|0|h|u|"+FILTER,"0|2|h|u|"+FILTER,"0|0||u|"+FILTER,"0|0|h|u|nothex","0|0|h|u","0|0|h|u|"+FILTER+"|x",
                "0|0|h h|u|"+FILTER,"0|0|h|u/../x|"+FILTER,"0|0|"+"a".repeat(65)+"|u|"+FILTER,"-1|0|h|u|"+FILTER}) {
            String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(()->ChannelCursor.parse(encoded)).as(raw).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void filterHashSeparatesKindQueryAndFilters() {
        String base=FilterHash.of("streams","a","cat","tag");
        assertThat(base).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(FilterHash.of("streams","a","cat","tag")).isEqualTo(base);
        for(String other:new String[]{FilterHash.of("channels","a","cat","tag"),FilterHash.of("streams","b","cat","tag"),
                FilterHash.of("streams","a","cat2","tag"),FilterHash.of("streams","a","cat","tag2"),FilterHash.of("streams","a",null,"tag"),
                FilterHash.of("streams","a","cat",null),FilterHash.of("streams",null,"cat","tag")})
            assertThat(other).isNotEqualTo(base);
        // Moving a value between fields must not collide.
        assertThat(FilterHash.of("streams","a","b",null)).isNotEqualTo(FilterHash.of("streams","a",null,"b"));
    }
}
