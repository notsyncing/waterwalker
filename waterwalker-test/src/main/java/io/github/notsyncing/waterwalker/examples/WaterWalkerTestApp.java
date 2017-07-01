package io.github.notsyncing.waterwalker.examples;

import io.github.notsyncing.waterwalker.WaterWalkerServer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

public class WaterWalkerTestApp {
    private WaterWalkerServer server;

    public static void main(String[] args) throws IOException {
        WaterWalkerTestApp app = new WaterWalkerTestApp();
        app.start();
    }

    public void start() throws IOException {
        String data = "HTTP/1.1 200 OK\r\nCache-Control: max-age=0\r\nContent-Length: 24\r\n\r\n<div>Hello, world!</div>";
        byte[] dataBuf = data.getBytes("utf-8");

        server = new WaterWalkerServer("localhost", 8080);

        server.setDefaultReadCallback((channel, byteBuffer) -> {
            if (byteBuffer == null) {
                return;
            }

            try {
                String s = new String(byteBuffer.array(), "utf-8");

                if (s.contains("GET ")) {
                    CompletableFuture.runAsync(() -> {
                        try {
                            server.write(channel, ByteBuffer.wrap(dataBuf), dataBuf.length, ex -> {
                                try {
                                    channel.socket().close();
                                    channel.close();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        System.out.println("Server started.");
    }
}
