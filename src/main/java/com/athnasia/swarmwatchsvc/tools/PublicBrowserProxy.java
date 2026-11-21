package com.athnasia.swarmwatchsvc.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

final class PublicBrowserProxy implements AutoCloseable {
    interface Connector { Socket connect(InetAddress address, int port) throws IOException; }
    private final PublicUrlPolicy policy;
    private final Connector connector;
    private final ServerSocket server;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Semaphore capacity = new Semaphore(128);
    private volatile boolean closed;

    PublicBrowserProxy(PublicUrlPolicy policy) throws IOException {
        this(policy, (address, port) -> {
            Socket socket = new Socket();
            try { socket.connect(new InetSocketAddress(address, port), 10000); return socket; }
            catch (IOException failure) { socket.close(); throw failure; }
        });
    }

    PublicBrowserProxy(PublicUrlPolicy policy, Connector connector) throws IOException {
        this.policy = policy;
        this.connector = connector;
        this.server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        Thread.ofVirtual().name("computer-proxy-accept").start(this::accept);
    }

    int port() { return server.getLocalPort(); }

    private void accept() {
        while (!closed) {
            try {
                Socket client = server.accept();
                if (!capacity.tryAcquire()) { client.close(); continue; }
                sockets.add(client);
                Thread.ofVirtual().name("computer-proxy-connection").start(() -> handle(client));
            } catch (IOException stopped) { if (closed) return; }
        }
    }

    private void handle(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(30000);
            String[] lines = readHeaders(client.getInputStream()).split("\r\n");
            String[] request = lines[0].split(" ", 3);
            if (request.length != 3 || !request[0].matches("[A-Z]{3,10}") || !request[2].matches("HTTP/1\\.[01]")) throw new IllegalArgumentException();
            boolean tunnel = request[0].equals("CONNECT");
            URI uri = URI.create(tunnel ? "https://" + request[1] : request[1]);
            if (!tunnel && !"http".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException();
            InetAddress destination = policy.destination(uri.toString());
            int port = uri.getPort() == -1 ? tunnel ? 443 : 80 : uri.getPort();
            upstream = connector.connect(destination, port);
            upstream.setSoTimeout(30000);
            sockets.add(upstream);
            if (tunnel) {
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
            } else {
                String target = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
                if (uri.getRawQuery() != null) target += "?" + uri.getRawQuery();
                StringBuilder outgoing = new StringBuilder(request[0]).append(' ').append(target).append(' ').append(request[2]).append("\r\n");
                for (int index = 1; index < lines.length; index++) {
                    String lower = lines[index].toLowerCase(java.util.Locale.ROOT);
                    if (!lower.startsWith("proxy-") && !lower.startsWith("connection:")) outgoing.append(lines[index]).append("\r\n");
                }
                outgoing.append("Connection: close\r\n\r\n");
                upstream.getOutputStream().write(outgoing.toString().getBytes(StandardCharsets.ISO_8859_1));
                upstream.getOutputStream().flush();
            }
            Socket connected = upstream;
            Thread.ofVirtual().name("computer-proxy-upload").start(() -> copy(client, connected));
            copy(upstream.getInputStream(), client.getOutputStream());
        } catch (ComputerUseException denied) {
            error(client, 403, "Destination blocked");
        } catch (IllegalArgumentException malformed) {
            error(client, 400, "Invalid proxy request");
        } catch (IOException unavailable) {
            error(client, 502, "Browser connection failed");
        } finally {
            closeSocket(client);
            if (upstream != null) closeSocket(upstream);
            capacity.release();
        }
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int end = 0;
        while (header.size() < 32768) {
            int value = input.read();
            if (value < 0) throw new IOException("Incomplete proxy headers");
            header.write(value);
            end = (end << 8) | value;
            if (end == 0x0d0a0d0a) return header.toString(StandardCharsets.ISO_8859_1);
        }
        throw new IOException("Proxy headers too large");
    }

    private static void copy(Socket source, Socket destination) {
        try { copy(source.getInputStream(), destination.getOutputStream()); }
        catch (IOException closed) { }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16384];
        for (int count; (count = input.read(buffer)) != -1;) { output.write(buffer, 0, count); output.flush(); }
    }

    private static void error(Socket client, int code, String message) {
        try {
            byte[] body = message.getBytes(StandardCharsets.US_ASCII);
            client.getOutputStream().write(("HTTP/1.1 " + code + " " + message + "\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().write(body);
            client.getOutputStream().flush();
        } catch (IOException disconnected) { }
    }

    private void closeSocket(Socket socket) {
        sockets.remove(socket);
        try { socket.close(); } catch (IOException closed) { }
    }

    @Override
    public void close() {
        closed = true;
        try { server.close(); } catch (IOException stopped) { }
        for (Socket socket : sockets) closeSocket(socket);
    }
}
