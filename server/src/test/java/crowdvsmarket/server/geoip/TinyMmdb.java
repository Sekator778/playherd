package crowdvsmarket.server.geoip;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A writer of tiny MMDB files, for tests that must never download the real country database. Written from the MaxMind DB
 * File Format Specification 2.0 (https://maxmind.github.io/MaxMind-DB/): a binary search tree of 24-bit records, 16 zero
 * bytes, the data section, the metadata marker and the metadata map. The records mirror DB-IP's "IP to Country Lite"
 * layout — {@code continent} and {@code country} with {@code geoname_id}, {@code is_in_european_union}, {@code iso_code}
 * and {@code names} — so that reading only {@code country.iso_code} has to skip the rest, as it does on the real file
 * (checked against the file of 2026-10, 8.3 MB: the same layout, ip_version 6, 24-bit records).
 *
 * <p>The files are read by the real {@code com.maxmind.db.Reader} in the tests; this class is not used by the server. The
 * networks of one file must not overlap.
 */
public final class TinyMmdb {

    private static final int RECORD_BITS = 24;

    /** A node of the search tree; a child is a {@code Node}, an {@code Integer} (the index of a data item) or {@code null}. */
    private static final class Node {
        Object left;
        Object right;
        int id = -1;

        Object child(int bit) {
            return bit == 0 ? left : right;
        }

        void set(int bit, Object child) {
            if (bit == 0) {
                left = child;
            } else {
                right = child;
            }
        }
    }

    private final int ipVersion;
    private final Node root = new Node();
    private final List<byte[]> items = new ArrayList<>();
    private String databaseType = "DBIP-Country-Lite";

    private TinyMmdb(int ipVersion) {
        this.ipVersion = ipVersion;
    }

    /** A database of ip_version 6 — like DB-IP's: IPv4 networks live under {@code ::/96}. */
    public static TinyMmdb ipv6() {
        return new TinyMmdb(6);
    }

    /** A database of ip_version 4. */
    public static TinyMmdb ipv4() {
        return new TinyMmdb(4);
    }

    public TinyMmdb type(String databaseType) {
        this.databaseType = databaseType;
        return this;
    }

    /** {@code cidr} ({@code 192.0.2.0/24}, {@code 2001:db8::/32}) belongs to the country with this {@code iso_code}, as written. */
    public TinyMmdb country(String cidr, String isoCode) {
        return add(cidr, record(isoCode));
    }

    /** {@code cidr} has a record with a continent and no country at all. */
    public TinyMmdb continentOnly(String cidr) {
        Out out = new Out();
        out.map(1);
        out.string("continent");
        continent(out);
        return add(cidr, out.bytes());
    }

    private TinyMmdb add(String cidr, byte[] record) {
        int slash = cidr.indexOf('/');
        byte[] address = parse(cidr.substring(0, slash));
        int prefix = Integer.parseInt(cidr.substring(slash + 1));
        if (address.length == 16 && ipVersion == 4) {
            throw new IllegalArgumentException("an IPv6 network in an IPv4 database: " + cidr);
        }
        if (address.length == 4 && ipVersion == 6) {
            byte[] mapped = new byte[16];
            System.arraycopy(address, 0, mapped, 12, 4);
            address = mapped;
            prefix += 96;
        }
        items.add(record);
        insert(address, prefix, items.size() - 1);
        return this;
    }

    private static byte[] parse(String literal) {
        try {
            byte[] bytes = InetAddress.getByName(literal).getAddress(); // a literal in a test: no lookup
            return bytes;
        } catch (IOException e) {
            throw new IllegalArgumentException(literal, e);
        }
    }

    private void insert(byte[] address, int prefix, int item) {
        Node node = root;
        for (int i = 0; i < prefix - 1; i++) {
            int bit = bit(address, i);
            Object child = node.child(bit);
            if (child == null) {
                child = new Node();
                node.set(bit, child);
            } else if (!(child instanceof Node)) {
                throw new IllegalArgumentException("overlapping networks");
            }
            node = (Node) child;
        }
        int bit = bit(address, prefix - 1);
        if (node.child(bit) != null) {
            throw new IllegalArgumentException("overlapping networks");
        }
        node.set(bit, item);
    }

    private static int bit(byte[] address, int index) {
        return (address[index / 8] >> (7 - index % 8)) & 1;
    }

    // --- the record: what DB-IP's file has for a network ---

    private static void continent(Out out) {
        out.map(3);
        out.string("code");
        out.string("EU");
        out.string("geoname_id");
        out.uint(6, 6255148);
        out.string("names");
        out.map(2);
        out.string("de");
        out.string("Europa");
        out.string("en");
        out.string("Europe");
    }

    private static byte[] record(String isoCode) {
        Out out = new Out();
        out.map(2);
        out.string("continent");
        continent(out);
        out.string("country");
        out.map(4);
        out.string("geoname_id");
        out.uint(6, 2921044);
        out.string("is_in_european_union");
        out.bool(true);
        out.string("iso_code");
        out.string(isoCode);
        out.string("names");
        out.map(2);
        out.string("de");
        out.string("Land " + isoCode);
        out.string("en");
        out.string("Country " + isoCode);
        return out.bytes();
    }

    // --- the file ---

    public byte[] build() {
        int[] nodeCount = {0};
        number(root, nodeCount);
        int nodes = nodeCount[0];

        int[] offsets = new int[items.size()];
        int offset = 0;
        for (int i = 0; i < items.size(); i++) {
            offsets[i] = offset;
            offset += items.get(i).length;
        }

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        writeTree(file, root, nodes, offsets);
        file.writeBytes(new byte[16]); // the data section separator
        for (byte[] item : items) {
            file.writeBytes(item);
        }
        file.writeBytes(new byte[] {(byte) 0xAB, (byte) 0xCD, (byte) 0xEF});
        file.writeBytes("MaxMind.com".getBytes(StandardCharsets.US_ASCII));
        file.writeBytes(metadata(nodes));
        return file.toByteArray();
    }

    public Path writeTo(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, build());
        return file;
    }

    /** Numbers the nodes depth first, the root 0. */
    private static void number(Node node, int[] next) {
        node.id = next[0]++;
        if (node.left instanceof Node left) {
            number(left, next);
        }
        if (node.right instanceof Node right) {
            number(right, next);
        }
    }

    private static void writeTree(ByteArrayOutputStream out, Node node, int nodeCount, int[] offsets) {
        // nodes are written in id order: this one, then the subtrees as numbered
        write24(out, treeRecord(node.left, nodeCount, offsets));
        write24(out, treeRecord(node.right, nodeCount, offsets));
        if (node.left instanceof Node left) {
            writeTree(out, left, nodeCount, offsets);
        }
        if (node.right instanceof Node right) {
            writeTree(out, right, nodeCount, offsets);
        }
    }

    /** A record: the next node, "no data" (= the node count), or a pointer into the data section (+ 16 for the separator). */
    private static int treeRecord(Object child, int nodeCount, int[] offsets) {
        if (child == null) {
            return nodeCount;
        }
        if (child instanceof Node node) {
            return node.id;
        }
        return nodeCount + 16 + offsets[(Integer) child];
    }

    private static void write24(ByteArrayOutputStream out, int value) {
        if (value >= (1 << RECORD_BITS)) {
            throw new IllegalStateException("record " + value + " does not fit 24 bits");
        }
        out.write(value >> 16);
        out.write(value >> 8);
        out.write(value);
    }

    private byte[] metadata(int nodeCount) {
        Out out = new Out();
        out.map(9);
        out.string("binary_format_major_version");
        out.uint(5, 2);
        out.string("binary_format_minor_version");
        out.uint(5, 0);
        out.string("build_epoch");
        out.uint64(1_790_000_000L);
        out.string("database_type");
        out.string(databaseType);
        out.string("description");
        out.map(1);
        out.string("en");
        out.string("tiny test database, written by TinyMmdb");
        out.string("ip_version");
        out.uint(5, ipVersion);
        out.string("languages");
        out.array(1);
        out.string("en");
        out.string("node_count");
        out.uint(6, nodeCount);
        out.string("record_size");
        out.uint(5, RECORD_BITS);
        return out.bytes();
    }

    /** The data section's encoding (spec §"Data Section Format"): a control byte of type and size, the extended type, the size. */
    private static final class Out {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        byte[] bytes() {
            return bytes.toByteArray();
        }

        private void header(int type, int size) {
            int sizeBits;
            byte[] more;
            if (size < 29) {
                sizeBits = size;
                more = new byte[0];
            } else if (size < 29 + 256) {
                sizeBits = 29;
                more = new byte[] {(byte) (size - 29)};
            } else if (size < 285 + 65536) {
                sizeBits = 30;
                more = new byte[] {(byte) ((size - 285) >> 8), (byte) (size - 285)};
            } else {
                throw new IllegalArgumentException("size " + size);
            }
            if (type <= 7) {
                bytes.write((type << 5) | sizeBits);
            } else {
                bytes.write(sizeBits);
                bytes.write(type - 7);
            }
            bytes.writeBytes(more);
        }

        void string(String s) {
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            header(2, utf8.length);
            bytes.writeBytes(utf8);
        }

        void map(int pairs) {
            header(7, pairs);
        }

        void array(int length) {
            header(11, length);
        }

        void bool(boolean value) {
            header(14, value ? 1 : 0);
        }

        /** uint16 (type 5) or uint32 (type 6): big endian without leading zero bytes. */
        void uint(int type, long value) {
            int length = 0;
            while (length < 4 && (value >> (8 * length)) != 0) {
                length++;
            }
            header(type, length);
            for (int i = length - 1; i >= 0; i--) {
                bytes.write((int) (value >> (8 * i)));
            }
        }

        void uint64(long value) {
            int length = 0;
            while (length < 8 && (value >>> (8 * length)) != 0) {
                length++;
            }
            header(9, length);
            for (int i = length - 1; i >= 0; i--) {
                bytes.write((int) (value >>> (8 * i)));
            }
        }
    }
}
