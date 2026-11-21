package com.athnasia.swarmwatchsvc.tools;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

final class PublicUrlPolicy {
    interface Resolver { InetAddress[] resolve(String host) throws Exception; }
    private final Resolver resolver;
    PublicUrlPolicy() { this(InetAddress::getAllByName); }
    PublicUrlPolicy(Resolver resolver) { this.resolver = resolver; }

    boolean allowed(String address) {
        try { destination(address); return true; }
        catch (Exception invalid) { return false; }
    }

    InetAddress destination(String address) {
        try {
            URI uri = URI.create(address);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65535) throw blocked();
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) throw blocked();
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses.length == 0) throw blocked();
            for (InetAddress ip : addresses) if (!publicAddress(ip)) throw blocked();
            return addresses[0];
        } catch (Exception invalid) { throw blocked(); }
    }

    private static ComputerUseException blocked() { return new ComputerUseException("The browser destination is not a permitted public HTTP endpoint."); }

    private boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] ip = address.getAddress();
        if (ip.length == 4) {
            int a = ip[0] & 255, b = ip[1] & 255, c = ip[2] & 255;
            return a != 0 && a != 10 && a != 127 && a < 224
                    && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 192 && (b == 168 || b == 0 && (c == 0 || c == 2)))
                    && !(a == 198 && (b == 18 || b == 19 || b == 51 && c == 100))
                    && !(a == 203 && b == 0 && c == 113);
        }
        if (ip.length == 16) {
            if ((ip[0] & 0xe0) != 0x20) return false;
            if ((ip[0] & 255) == 0x20 && (ip[1] & 255) == 0x02) return false;
            return !((ip[0] & 255) == 0x20 && (ip[1] & 255) == 0x01
                    && ((ip[2] == 0 && ip[3] == 0) || ((ip[2] & 255) == 0x0d && (ip[3] & 255) == 0xb8)));
        }
        return false;
    }
}
