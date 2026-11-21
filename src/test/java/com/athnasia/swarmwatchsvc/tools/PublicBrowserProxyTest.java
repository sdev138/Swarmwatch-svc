package com.athnasia.swarmwatchsvc.tools;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicBrowserProxyTest {
    @Test
    void blocksPrivateHttpAndConnectDestinationsWithoutOpeningUpstreamSocket() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        PublicUrlPolicy policy = new PublicUrlPolicy(host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        try (PublicBrowserProxy proxy = new PublicBrowserProxy(policy, (ip, port) -> { connections.incrementAndGet(); throw new AssertionError("private socket"); })) {
            assertTrue(request(proxy, "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n").startsWith("HTTP/1.1 403"));
            assertTrue(request(proxy, "CONNECT 169.254.169.254:443 HTTP/1.1\r\nHost: 169.254.169.254:443\r\n\r\n").startsWith("HTTP/1.1 403"));
            assertEquals(0, connections.get());
        }
    }

    @Test
    void pinsValidatedPublicAddressAndDoesNotResolveAgainWhileConnecting() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicReference<InetAddress> destination = new AtomicReference<>();
        ByteArrayOutputStream forwarded = new ByteArrayOutputStream();
        PublicUrlPolicy policy = new PublicUrlPolicy(host -> new InetAddress[]{InetAddress.getByName(resolutions.getAndIncrement() == 0 ? "93.184.216.34" : "127.0.0.1")});
        try (PublicBrowserProxy proxy = new PublicBrowserProxy(policy, (ip, port) -> {
            destination.set(ip);
            Socket upstream = mock(Socket.class);
            when(upstream.getInputStream()).thenReturn(new ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.US_ASCII)));
            when(upstream.getOutputStream()).thenReturn(forwarded);
            return upstream;
        })) {
            String response = request(proxy, "GET http://example.com/path?q=hello HTTP/1.1\r\nHost: example.com\r\nProxy-Connection: keep-alive\r\n\r\n");
            assertTrue(response.endsWith("ok"));
            assertEquals("93.184.216.34", destination.get().getHostAddress());
            assertEquals(1, resolutions.get());
            assertTrue(forwarded.toString(StandardCharsets.US_ASCII).startsWith("GET /path?q=hello HTTP/1.1\r\n"));
            assertTrue(forwarded.toString(StandardCharsets.US_ASCII).contains("Connection: close"));
            assertFalse(forwarded.toString(StandardCharsets.US_ASCII).contains("Proxy-Connection:"));
        }
    }

    @Test
    void redirectToPrivateHostIsCheckedOnItsNewConnection() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        PublicUrlPolicy policy = new PublicUrlPolicy(host -> new InetAddress[]{InetAddress.getByName(host.equals("public.example") ? "93.184.216.34" : "127.0.0.1")});
        try (PublicBrowserProxy proxy = new PublicBrowserProxy(policy, (ip, port) -> {
            connections.incrementAndGet();
            Socket upstream = mock(Socket.class);
            when(upstream.getInputStream()).thenReturn(new ByteArrayInputStream("HTTP/1.1 302 Found\r\nLocation: http://private.example/\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
            when(upstream.getOutputStream()).thenReturn(new ByteArrayOutputStream());
            return upstream;
        })) {
            assertTrue(request(proxy, "GET http://public.example/ HTTP/1.1\r\nHost: public.example\r\n\r\n").startsWith("HTTP/1.1 302"));
            assertTrue(request(proxy, "GET http://private.example/ HTTP/1.1\r\nHost: private.example\r\n\r\n").startsWith("HTTP/1.1 403"));
            assertEquals(1, connections.get());
        }
    }

    private String request(PublicBrowserProxy proxy, String request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", proxy.port()), 3000);
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        }
    }
}
