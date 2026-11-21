package com.athnasia.swarmwatchsvc.tools;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PublicUrlPolicyTest {
    @Test
    void rejectsLocalPrivateMetadataAndUnsupportedSchemesEvenWhenHostnameLooksPublic() throws Exception {
        PublicUrlPolicy policy = new PublicUrlPolicy(host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})});
        for (String url : List.of("file:///etc/passwd", "http://localhost", "http://127.0.0.1", "http://169.254.169.254/computeMetadata/v1/", "https://public.example/", "data:text/html,secret", "javascript:alert(1)", "http://[::1]/")) {
            assertFalse(policy.allowed(url), url);
        }
    }

    @Test
    void allResolvedAddressesMustBePublicAndCredentialsNeverBelongInNavigationUrls() throws Exception {
        PublicUrlPolicy publicPolicy = new PublicUrlPolicy(host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34})});
        assertTrue(publicPolicy.allowed("https://example.com/path?q=hello"));
        assertFalse(publicPolicy.allowed("https://user:secret@example.com/"));
        PublicUrlPolicy mixed = new PublicUrlPolicy(host -> new InetAddress[]{InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.1")});
        assertFalse(mixed.allowed("https://example.com/"));
    }

    @Test
    void deniesReservedIpv4CarrierNatMappedIpv6AndUniqueLocalIpv6() throws Exception {
        for (String ip : List.of("0.0.0.0", "10.1.2.3", "100.64.0.1", "172.16.0.1", "192.168.1.1", "198.18.0.1", "224.0.0.1", "::ffff:127.0.0.1", "fc00::1", "fe80::1", "2001:db8::1")) {
            PublicUrlPolicy policy = new PublicUrlPolicy(host -> new InetAddress[]{InetAddress.getByName(ip)});
            assertFalse(policy.allowed("https://example.com/"), ip);
        }
    }
}
