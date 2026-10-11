package crowdvsmarket.server.geoip;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * What the country lookup needs to know about the text it is given as an address.
 *
 * <p><b>It is parsed here, not by the JDK.</b> {@link InetAddress#getByName} takes a name as well as a literal and asks DNS
 * about every string it does not recognise as an address — also one that merely looks like an IPv6 literal and is not (a
 * string that starts with a hex digit and has a colon in it falls through to a lookup). The text reaches the country
 * lookup from a request's forwarded address, so it must never be able to cost a DNS query or a wait: {@link #parseLiteral}
 * accepts exactly the dotted-quad and the colon-hex forms, builds the bytes itself and hands them to {@link
 * InetAddress#getByAddress(byte[])}, which never resolves anything.
 */
final class IpAddresses {

    /** The longest textual form of an address: eight groups of four digits and seven colons is 39; an IPv4-mapped form with a
     *  dotted quad is 45. */
    private static final int MAX_TEXT_LENGTH = 45;

    private IpAddresses() {
    }

    /**
     * The address {@code text} spells, or {@code null} when it is not an IP literal: a host name, an empty string, a
     * network ({@code 10.0.0.0/8}), a port ({@code 1.2.3.4:80}) are all {@code null}. Accepted: a dotted quad of decimal
     * numbers 0..255 without leading zeros, an IPv6 address in the colon-hex form with at most one {@code ::} and an
     * optional dotted quad in its last 32 bits, optionally in square brackets and with a zone ({@code %eth0}, dropped). An
     * IPv4-mapped IPv6 address ({@code ::ffff:1.2.3.4}) comes out as the IPv4 address — the JDK's own rule.
     */
    static InetAddress parseLiteral(String text) {
        if (text == null) {
            return null;
        }
        String s = text.strip();
        if (s.length() > 2 && s.charAt(0) == '[' && s.charAt(s.length() - 1) == ']') {
            s = s.substring(1, s.length() - 1);
            if (s.indexOf(':') < 0) {
                return null; // brackets are for IPv6 only
            }
        }
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        if (s.isEmpty() || s.length() > MAX_TEXT_LENGTH) {
            return null;
        }
        byte[] bytes = s.indexOf(':') < 0 ? parseIpv4(s) : parseIpv6(s);
        if (bytes == null) {
            return null;
        }
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            return null; // not reachable for 4 or 16 bytes
        }
    }

    private static byte[] parseIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) {
                return null;
            }
            int value = 0;
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return null;
            }
            out[i] = (byte) value;
        }
        return out;
    }

    /** RFC 4291 §2.2 text forms 1–3: eight groups, or fewer with one {@code ::}, the last 32 bits maybe a dotted quad. */
    private static byte[] parseIpv6(String s) {
        int gap = s.indexOf("::");
        if (gap >= 0 && s.indexOf("::", gap + 1) >= 0) {
            return null; // two "::" (also ":::")
        }
        String head = gap < 0 ? s : s.substring(0, gap);
        String tail = gap < 0 ? "" : s.substring(gap + 2);
        short[] headGroups = parseGroups(head, gap < 0);
        short[] tailGroups = gap < 0 ? new short[0] : parseGroups(tail, true);
        if (headGroups == null || tailGroups == null) {
            return null;
        }
        int total = headGroups.length + tailGroups.length;
        if (gap < 0 ? total != 8 : total > 7) {
            return null;
        }
        short[] groups = new short[8];
        System.arraycopy(headGroups, 0, groups, 0, headGroups.length);
        System.arraycopy(tailGroups, 0, groups, 8 - tailGroups.length, tailGroups.length);
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[2 * i] = (byte) (groups[i] >> 8);
            out[2 * i + 1] = (byte) groups[i];
        }
        return out;
    }

    /**
     * The groups of one side of the {@code ::} ({@code part} may be empty = no groups). A dotted quad is allowed as the
     * last element when {@code mayEndWithQuad} — the tail of an address with {@code ::}, or the whole of one without
     * (when there is a {@code ::}, the head cannot be last). It counts as two groups.
     */
    private static short[] parseGroups(String part, boolean mayEndWithQuad) {
        if (part.isEmpty()) {
            return new short[0];
        }
        String[] items = part.split(":", -1);
        short[] groups = new short[items.length + 1]; // room for a quad's second group
        int n = 0;
        for (int i = 0; i < items.length; i++) {
            String item = items[i];
            if (item.indexOf('.') >= 0) {
                if (!mayEndWithQuad || i != items.length - 1) {
                    return null;
                }
                byte[] quad = parseIpv4(item);
                if (quad == null) {
                    return null;
                }
                groups[n++] = (short) (((quad[0] & 0xff) << 8) | (quad[1] & 0xff));
                groups[n++] = (short) (((quad[2] & 0xff) << 8) | (quad[3] & 0xff));
                continue;
            }
            if (item.isEmpty() || item.length() > 4) {
                return null;
            }
            int value = 0;
            for (int j = 0; j < item.length(); j++) {
                int digit = Character.digit(item.charAt(j), 16);
                if (digit < 0 || item.charAt(j) > 'f') { // Character.digit also takes full-width and other scripts' digits
                    return null;
                }
                value = value * 16 + digit;
            }
            groups[n++] = (short) value;
        }
        short[] out = new short[n];
        System.arraycopy(groups, 0, out, 0, n);
        return out;
    }

    /**
     * Whether the address can never be a visitor's public address — loopback, the unspecified address, link-local, the
     * private ranges (10/8, 172.16/12, 192.168/16, the IPv6 unique-local fc00::/7), carrier-grade NAT (100.64/10), multicast,
     * "this network" (0/8) and the reserved top of IPv4 (240/4). Such an address is answered {@code null} without asking
     * the database: a database that is asked about a private address may answer with whatever its builder put there.
     */
    static boolean isNotPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0 || first >= 240 || (first == 100 && (second & 0xc0) == 64);
        }
        return (b[0] & 0xfe) == 0xfc; // fc00::/7
    }
}
