package crowdvsmarket.server.geoip;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What the country lookup does with the text it is given as an address: only a literal is an address (a name would cost a
 * DNS query in the JDK's parser — see {@link IpAddresses}), and an address that is never a visitor's is set aside before
 * the database is asked.
 */
class IpAddressesTest {

    private static String hostAddress(String text) {
        InetAddress address = IpAddresses.parseLiteral(text);
        return address == null ? null : address.getHostAddress();
    }

    @Test
    void aDottedQuadIsAnIpv4Address() {
        InetAddress address = IpAddresses.parseLiteral("203.0.113.7");

        assertThat(address).isInstanceOf(Inet4Address.class);
        assertThat(address.getHostAddress()).isEqualTo("203.0.113.7");
        assertThat(hostAddress("0.0.0.0")).isEqualTo("0.0.0.0");
        assertThat(hostAddress("255.255.255.255")).isEqualTo("255.255.255.255");
    }

    @Test
    void ipv6InEveryTextFormIsParsed() {
        assertThat(IpAddresses.parseLiteral("2001:db8::1")).isInstanceOf(Inet6Address.class);
        assertThat(hostAddress("2001:db8::1")).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(hostAddress("2001:0db8:0000:0000:0000:0000:0000:0001")).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(hostAddress("2001:DB8::ABCD")).isEqualTo("2001:db8:0:0:0:0:0:abcd");
        assertThat(hostAddress("::")).isEqualTo("0:0:0:0:0:0:0:0");
        assertThat(hostAddress("::1")).isEqualTo("0:0:0:0:0:0:0:1");
        assertThat(hostAddress("1::")).isEqualTo("1:0:0:0:0:0:0:0");
        assertThat(hostAddress("1:2:3:4:5:6:7::")).isEqualTo("1:2:3:4:5:6:7:0");
        assertThat(hostAddress("1:2:3:4:5:6:7:8")).isEqualTo("1:2:3:4:5:6:7:8");
        assertThat(hostAddress("64:ff9b::192.0.2.33")).isEqualTo("64:ff9b:0:0:0:0:c000:221");
    }

    @Test
    void anIpv4MappedIpv6AddressIsTheIpv4AddressAsTheJdkHasIt() {
        InetAddress address = IpAddresses.parseLiteral("::ffff:203.0.113.7");

        assertThat(address).isInstanceOf(Inet4Address.class);
        assertThat(address.getHostAddress()).isEqualTo("203.0.113.7");
        assertThat(hostAddress("::FFFF:cb00:7107")).isEqualTo("203.0.113.7");
    }

    @Test
    void bracketsAndAZoneAreDropped() {
        assertThat(hostAddress("[2001:db8::1]")).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(hostAddress("fe80::1%eth0")).isEqualTo("fe80:0:0:0:0:0:0:1");
        assertThat(hostAddress("  203.0.113.7  ")).isEqualTo("203.0.113.7");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "unknown", "localhost", "example.com", "evil.example.com", "abc:def", "abc", "deadbeef", "1.2.3",
            "1.2.3.4.5", "1.2.3.256", "1.2.3.-1", "01.2.3.4", "1.2.3.04", "0x7f.0.0.1", "1.2.3.4/24", "1.2.3.4:80",
            "1..3.4", ".1.2.3", "1.2.3.", "2001:db8::1::2", ":::", "1:::2", "1:2:3:4:5:6:7:8:9",
            "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8::", ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:", "12345::1", "g::1", "::1.2.3",
            "1.2.3.4::1", "::1.2.3.4:1", "1:2:3:4:5:6:7:1.2.3.4", "[::1", "::1]", "١::1", "２００１::1", "::ffff:1.2.3.4.5",
            "1:2:3:4:5:6:7:8:", "a:b:c:d:e:f:g:h", "[1.2.3.4]", "[unknown]", "%eth0"
    })
    void whatIsNotAnIpLiteralIsNull(String text) {
        // "unknown" is what RpcResource#clientIp answers when a request has no peer address
        assertThat(IpAddresses.parseLiteral(text)).as("%s", text).isNull();
    }

    @Test
    void nullIsNull() {
        assertThat(IpAddresses.parseLiteral(null)).isNull();
    }

    @Test
    void aTextLongerThanAnyAddressIsNullWithoutBeingParsed() {
        assertThat(IpAddresses.parseLiteral("1".repeat(46))).isNull();
        assertThat(IpAddresses.parseLiteral("1:".repeat(40) + "1")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "127.255.255.254", "0.0.0.0", "0.1.2.3", "10.0.0.1", "10.255.255.255", "172.16.0.1",
            "172.31.255.255", "192.168.0.1", "192.168.255.255", "169.254.1.1", "100.64.0.1", "100.127.255.254",
            "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255", "::", "::1", "fe80::1", "fc00::1",
            "fd12:3456:789a::1", "ff02::1"
    })
    void anAddressThatIsNeverAVisitorsIsNotPublic(String text) {
        assertThat(IpAddresses.isNotPublic(IpAddresses.parseLiteral(text))).as("%s", text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8.8.8.8", "1.1.1.1", "172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1", "193.0.0.1", "223.255.255.254",
            "192.0.2.1", "198.51.100.7", "203.0.113.7", "2001:4860:4860::8888", "2001:db8::1", "2606:4700::1111"
    })
    void anAddressThatMayBeAVisitorsIsPublic(String text) {
        assertThat(IpAddresses.isNotPublic(IpAddresses.parseLiteral(text))).as("%s", text).isFalse();
    }

    @Test
    void theRangesOfRfc1918AreExactlyThose() {
        List<String> inside = List.of("172.16.0.0", "172.20.5.5", "172.31.255.255");
        List<String> outside = List.of("172.15.255.255", "172.32.0.0");
        inside.forEach(ip -> assertThat(IpAddresses.isNotPublic(IpAddresses.parseLiteral(ip))).as(ip).isTrue());
        outside.forEach(ip -> assertThat(IpAddresses.isNotPublic(IpAddresses.parseLiteral(ip))).as(ip).isFalse());
    }
}
