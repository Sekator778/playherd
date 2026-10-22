package crowdvsmarket.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Security review H4: an IPv6 address is limited by its /64, an IPv4 address by itself. */
class RegistrationIpRepositoryTest {

    @Test
    void addressesOfOneIpv6Slash64ShareAKey() {
        String a = RegistrationIpRepository.limitKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd");
        assertThat(RegistrationIpRepository.limitKey("2001:db8:1:2::1")).isEqualTo(a);
        assertThat(RegistrationIpRepository.limitKey("2001:0DB8:0001:0002:ffff:ffff:ffff:ffff")).isEqualTo(a);
        assertThat(RegistrationIpRepository.limitKey("[2001:db8:1:2::9]")).isEqualTo(a);
        assertThat(RegistrationIpRepository.limitKey("2001:db8:1:2::9%eth0")).isEqualTo(a);
        assertThat(RegistrationIpRepository.limitKey("2001:db8:1:3::1")).isNotEqualTo(a);
        assertThat(RegistrationIpRepository.limitKey("2001:db9:1:2::1")).isNotEqualTo(a);
    }

    @Test
    void ipv4AndWhatIsNotAnAddressStayAsTheyAre() {
        assertThat(RegistrationIpRepository.limitKey("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(RegistrationIpRepository.limitKey("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(RegistrationIpRepository.limitKey("unknown")).isEqualTo("unknown");
        assertThat(RegistrationIpRepository.limitKey("not:an:address!")).isEqualTo("not:an:address!");
        assertThat(RegistrationIpRepository.limitKey(null)).isNull();
    }
}
