package streaming.core.discovery.domain;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustedProxiesTest {
    @Test void withoutTrustedProxiesTheSocketAddressAlwaysWins() {
        var proxies=TrustedProxies.parse("");
        assertThat(proxies.isEmpty()).isTrue();
        assertThat(proxies.clientAddress("203.0.113.9",List.of("1.2.3.4"))).isEqualTo("203.0.113.9");
        assertThat(TrustedProxies.parse(null).clientAddress("203.0.113.9",List.of("1.2.3.4, 5.6.7.8"))).isEqualTo("203.0.113.9");
    }

    @Test void aForgedHeaderFromAnUntrustedPeerIsIgnored() {
        var proxies=TrustedProxies.parse("10.0.0.0/8");
        assertThat(proxies.clientAddress("198.51.100.7",List.of("9.9.9.9"))).isEqualTo("198.51.100.7");
    }

    @Test void aTrustedProxyContributesTheRealClientFromTheRight() {
        var proxies=TrustedProxies.parse("10.0.0.0/8, 192.168.1.5");
        assertThat(proxies.clientAddress("10.1.2.3",List.of("203.0.113.9"))).isEqualTo("203.0.113.9");
        // The client may prepend anything; only what trusted proxies appended counts.
        assertThat(proxies.clientAddress("10.1.2.3",List.of("6.6.6.6, 203.0.113.9"))).isEqualTo("203.0.113.9");
        // A chain of two trusted hops is skipped.
        assertThat(proxies.clientAddress("10.1.2.3",List.of("203.0.113.9, 10.9.9.9, 192.168.1.5"))).isEqualTo("203.0.113.9");
        // Several header lines behave like one comma-separated list.
        assertThat(proxies.clientAddress("10.1.2.3",List.of("6.6.6.6","203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test void aTrustedPeerWithoutAHeaderIsItsOwnClient() {
        var proxies=TrustedProxies.parse("10.0.0.0/8");
        assertThat(proxies.clientAddress("10.1.2.3",List.of())).isEqualTo("10.1.2.3");
        assertThat(proxies.clientAddress("10.1.2.3",null)).isEqualTo("10.1.2.3");
        assertThat(proxies.clientAddress("10.1.2.3",List.of("10.5.5.5, 10.6.6.6"))).isEqualTo("10.1.2.3");
    }

    @Test void aMalformedEntryStopsTheWalkAndFallsBackToThePeer() {
        var proxies=TrustedProxies.parse("10.0.0.0/8");
        for(String header:new String[]{"unknown","203.0.113.9, garbage","203.0.113.9:8080","","  ","1.2.3.4, ","<script>"})
            assertThat(proxies.clientAddress("10.1.2.3",List.of(header))).as(header).isEqualTo("10.1.2.3");
    }

    @Test void cidrBoundariesAreExact() {
        var proxies=TrustedProxies.parse("192.168.4.0/22");
        assertThat(proxies.clientAddress("192.168.4.1",List.of("8.8.8.8"))).isEqualTo("8.8.8.8");
        assertThat(proxies.clientAddress("192.168.7.255",List.of("8.8.8.8"))).isEqualTo("8.8.8.8");
        assertThat(proxies.clientAddress("192.168.8.0",List.of("8.8.8.8"))).isEqualTo("192.168.8.0");
        assertThat(proxies.clientAddress("192.168.3.255",List.of("8.8.8.8"))).isEqualTo("192.168.3.255");
        // When every hop is trusted the walk is exhausted and the direct peer is used, never an unverified header.
        assertThat(TrustedProxies.parse("0.0.0.0/0").clientAddress("1.2.3.4",List.of("8.8.8.8"))).isEqualTo("1.2.3.4");
        assertThat(TrustedProxies.parse("203.0.113.9/32").clientAddress("203.0.113.9",List.of("8.8.8.8"))).isEqualTo("8.8.8.8");
        assertThat(TrustedProxies.parse("203.0.113.9").clientAddress("203.0.113.10",List.of("8.8.8.8"))).isEqualTo("203.0.113.10");
    }

    @Test void ipv6IsSupportedAndNeverMixedWithIpv4Ranges() {
        var proxies=TrustedProxies.parse("::1, fd00::/8");
        assertThat(proxies.clientAddress("0:0:0:0:0:0:0:1",List.of("2001:db8::7"))).isEqualTo("2001:db8:0:0:0:0:0:7");
        assertThat(proxies.clientAddress("fd12::5",List.of("203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(proxies.clientAddress("10.0.0.1",List.of("203.0.113.9"))).isEqualTo("10.0.0.1");
    }

    @Test void theKeyIsNormalizedSoEquivalentSpellingsShareOneBucket() {
        var proxies=TrustedProxies.parse("");
        assertThat(proxies.clientAddress("0:0:0:0:0:0:0:1",List.of())).isEqualTo(proxies.clientAddress("::1",List.of()));
    }

    @Test void anUnparsableSocketAddressIsKeptVerbatimAndNeverCrashes() {
        assertThat(TrustedProxies.parse("10.0.0.0/8").clientAddress("not-an-ip",List.of("8.8.8.8"))).isEqualTo("not-an-ip");
        assertThat(TrustedProxies.parse("").clientAddress(null,List.of())).isEqualTo("unknown");
    }

    @ParameterizedTest
    @ValueSource(strings={"not-an-ip","10.0.0.0/33","::1/129","10.0.0.0/-1","10.0.0.0/x","10.0.0.0/","example.com","10.0.0.256"})
    void aMalformedConfigurationFailsFastInsteadOfSilentlyTrustingNothing(String value) {
        assertThatThrownBy(()->TrustedProxies.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
