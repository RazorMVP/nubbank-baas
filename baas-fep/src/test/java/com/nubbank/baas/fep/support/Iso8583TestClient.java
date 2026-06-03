package com.nubbank.baas.fep.support;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * Minimal test helper that speaks the 2-byte length-prefixed ISO 8583 framing
 * used by the FEP TCP server.
 *
 * <p>Wire format (matches Netty LengthFieldBasedFrameDecoder / LengthFieldPrepender):
 * <pre>
 *   [2-byte big-endian length][payload bytes]
 * </pre>
 * The 2-byte length encodes the payload length only — the header itself is NOT counted.
 *
 * <p>Usage:
 * <pre>
 *   try (Iso8583TestClient client = new Iso8583TestClient("127.0.0.1", port)) {
 *       byte[] response = client.send(isoPayload);
 *   }
 * </pre>
 */
public class Iso8583TestClient implements AutoCloseable {

    private final Socket socket;
    private final DataOutputStream out;
    private final DataInputStream  in;

    public Iso8583TestClient(String host, int port) throws IOException {
        socket = new Socket(host, port);
        out    = new DataOutputStream(socket.getOutputStream());
        in     = new DataInputStream(socket.getInputStream());
    }

    /**
     * Send {@code payload} with a 2-byte big-endian length prefix and block
     * until the server replies.
     *
     * @param payload  raw ISO 8583 wire bytes (no framing)
     * @return         raw ISO 8583 reply bytes (framing stripped)
     */
    public byte[] send(byte[] payload) throws IOException {
        // Write: 2-byte big-endian length + payload
        out.writeShort(payload.length);  // DataOutputStream.writeShort is big-endian
        out.write(payload);
        out.flush();

        // Read: 2-byte big-endian length, then that many bytes
        int len = in.readShort() & 0xFFFF;
        byte[] response = new byte[len];
        in.readFully(response);
        return response;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
