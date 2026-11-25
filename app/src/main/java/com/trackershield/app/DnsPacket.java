package com.trackershield.app;

/**
 * Minimal IPv4 + UDP + DNS helpers for the packets that arrive on the VPN interface.
 * Plain Java with no Android imports, so it can be tested off the phone.
 */
final class DnsPacket {
    static final int PROTO_UDP = 17;
    private static final int IP_HEADER = 20;
    private static final int UDP_HEADER = 8;
    private static final int DNS_HEADER = 12;
    private static final int RCODE_NXDOMAIN = 3;

    private DnsPacket() {}

    /** A DNS lookup taken out of an IPv4/UDP packet. */
    static final class Query {
        final byte[] srcIp = new byte[4];
        final byte[] dstIp = new byte[4];
        int srcPort;
        int dstPort;
        /** The DNS message exactly as the phone's resolver sent it. */
        byte[] dnsPayload;
        /** Lower-case name being looked up, without the trailing dot, or null if unreadable. */
        String name;
        int qtype;
        /** Offset in {@link #dnsPayload} just past the question section. */
        int questionEnd;
    }

    /**
     * Reads an IPv4/UDP packet. Returns null for anything that isn't an unfragmented IPv4 UDP
     * packet carrying at least a DNS header. {@link Query#name} is null when the DNS question
     * can't be read (such lookups are passed on unchanged).
     */
    static Query parse(byte[] pkt, int len) {
        if (pkt == null || len < IP_HEADER + UDP_HEADER || len > pkt.length) return null;
        if (((pkt[0] >> 4) & 0xF) != 4) return null;
        int ihl = (pkt[0] & 0xF) * 4;
        if (ihl < IP_HEADER) return null;
        int totalLen = u16(pkt, 2);
        if (totalLen < ihl + UDP_HEADER || totalLen > len) return null;
        if ((u16(pkt, 6) & 0x3FFF) != 0) return null; // "more fragments" set or non-zero offset
        if ((pkt[9] & 0xFF) != PROTO_UDP) return null;

        int udpLen = u16(pkt, ihl + 4);
        if (udpLen < UDP_HEADER + DNS_HEADER || ihl + udpLen > totalLen) return null;

        Query q = new Query();
        System.arraycopy(pkt, 12, q.srcIp, 0, 4);
        System.arraycopy(pkt, 16, q.dstIp, 0, 4);
        q.srcPort = u16(pkt, ihl);
        q.dstPort = u16(pkt, ihl + 2);
        int dnsLen = udpLen - UDP_HEADER;
        q.dnsPayload = new byte[dnsLen];
        System.arraycopy(pkt, ihl + UDP_HEADER, q.dnsPayload, 0, dnsLen);
        readQuestion(q);
        return q;
    }

    private static void readQuestion(Query q) {
        byte[] d = q.dnsPayload;
        int flags = u16(d, 2);
        if ((flags & 0x8000) != 0) return;          // a response, not a query
        if (((flags >> 11) & 0xF) != 0) return;     // not a standard query
        if (u16(d, 4) != 1) return;                 // we only handle the usual single question

        StringBuilder name = new StringBuilder();
        int pos = DNS_HEADER;
        while (true) {
            if (pos >= d.length) return;
            int labelLen = d[pos++] & 0xFF;
            if (labelLen == 0) break;
            if (labelLen > 63) return;              // compression pointers don't belong in a question
            if (pos + labelLen > d.length) return;
            if (name.length() > 0) name.append('.');
            for (int i = 0; i < labelLen; i++) {
                int c = d[pos + i] & 0xFF;
                if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
                name.append((char) c);
            }
            pos += labelLen;
            if (name.length() > 253) return;
        }
        if (pos + 4 > d.length) return;
        q.qtype = u16(d, pos);
        q.questionEnd = pos + 4;
        q.name = name.toString();
    }

    /** A "no such domain" answer to the query, so the app gives up on that server. */
    static byte[] blockedResponse(Query q) {
        byte[] r = new byte[q.questionEnd];
        System.arraycopy(q.dnsPayload, 0, r, 0, q.questionEnd);
        int recursionDesired = u16(q.dnsPayload, 2) & 0x0100;
        put16(r, 2, 0x8000 | recursionDesired | 0x0080 | RCODE_NXDOMAIN); // QR, RD copied, RA, NXDOMAIN
        put16(r, 4, 1);  // one question
        put16(r, 6, 0);  // no answers
        put16(r, 8, 0);
        put16(r, 10, 0);
        return r;
    }

    /** True if {@code reply} looks like the DNS answer to {@code q} (same transaction id, QR set). */
    static boolean isReplyTo(Query q, byte[] reply, int len) {
        return len >= DNS_HEADER
                && reply[0] == q.dnsPayload[0]
                && reply[1] == q.dnsPayload[1]
                && (reply[2] & 0x80) != 0;
    }

    /**
     * For an upstream answer too big to pass back in one packet: the answer's header with the
     * "truncated" bit set and only the question, which tells the resolver to retry another way.
     */
    static byte[] truncatedResponse(Query q, byte[] upstreamReply) {
        int end = q.name != null ? q.questionEnd : DNS_HEADER;
        byte[] r = new byte[end];
        System.arraycopy(q.dnsPayload, 0, r, 0, end);
        put16(r, 2, u16(upstreamReply, 2) | 0x0200);
        put16(r, 4, q.name != null ? 1 : 0);
        put16(r, 6, 0);
        put16(r, 8, 0);
        put16(r, 10, 0);
        return r;
    }

    /** Wraps a DNS answer in IPv4/UDP, addressed back to whoever sent {@code q}. */
    static byte[] buildResponsePacket(Query q, byte[] dns, int dnsLen) {
        int udpLen = UDP_HEADER + dnsLen;
        int total = IP_HEADER + udpLen;
        byte[] p = new byte[total];
        p[0] = 0x45;                       // IPv4, 20-byte header
        put16(p, 2, total);
        put16(p, 6, 0x4000);               // don't fragment
        p[8] = 64;                         // TTL
        p[9] = PROTO_UDP;
        System.arraycopy(q.dstIp, 0, p, 12, 4);
        System.arraycopy(q.srcIp, 0, p, 16, 4);
        put16(p, 10, checksum(p, 0, IP_HEADER, 0));

        put16(p, IP_HEADER, q.dstPort);
        put16(p, IP_HEADER + 2, q.srcPort);
        put16(p, IP_HEADER + 4, udpLen);
        System.arraycopy(dns, 0, p, IP_HEADER + UDP_HEADER, dnsLen);
        put16(p, IP_HEADER + 6, udpChecksum(p, udpLen));
        return p;
    }

    private static int udpChecksum(byte[] p, int udpLen) {
        long pseudo = 0;
        for (int i = 12; i < 20; i += 2) pseudo += u16(p, i);   // source + destination address
        pseudo += PROTO_UDP;
        pseudo += udpLen;
        int sum = checksum(p, IP_HEADER, udpLen, pseudo);
        return sum == 0 ? 0xFFFF : sum;
    }

    /** Internet (one's complement) checksum. */
    static int checksum(byte[] buf, int off, int len, long initial) {
        long sum = initial;
        int i = off;
        int end = off + len;
        for (; i + 1 < end; i += 2) sum += ((buf[i] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
        if (i < end) sum += (buf[i] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v >> 8);
        b[off + 1] = (byte) v;
    }
}
