package io.github.notsyncing.waterwalker.tests;

import io.github.notsyncing.waterwalker.WaterWalkerServer;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;

public class WaterWalkerServerTest {
    private WaterWalkerServer server;

    @Before
    public void setUp() throws IOException {
        server = new WaterWalkerServer("localhost", 8080);
    }

    @After
    public void tearDown() throws IOException, InterruptedException {
        server.stop();
    }

    private void sendDataAsync(String data) {
        CompletableFuture.runAsync(() -> {
            try (Socket socket = new Socket("localhost", 8080)) {
                socket.getOutputStream().write(data.getBytes("utf-8"));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private CompletableFuture<String> readUntil(int currentBufferSkip, int maxLength, Predicate<String> condition) {
        CompletableFuture<String> future = new CompletableFuture<>();

        server.setDefaultReadCallback((channel, buf) -> {
            server.readUntil(channel, buf, currentBufferSkip, maxLength, b -> {
                return condition.test(new String(b.array()));
            }, (finalBuffer, ex) -> {
                if (ex != null) {
                    future.completeExceptionally(ex);
                } else {
                    try {
                        future.complete(new String(finalBuffer.array(), "utf-8"));
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    }
                }
            });
        });

        return future;
    }

    private CompletableFuture<String> readUntilClose(int currentBufferSkip, int maxLength) {
        CompletableFuture<String> future = new CompletableFuture<>();

        server.setDefaultReadCallback((channel, buf) -> {
            server.readUntilClose(channel, buf, currentBufferSkip, maxLength, (finalBuffer, ex) -> {
                if (ex != null) {
                    future.completeExceptionally(ex);
                } else {
                    try {
                        future.complete(new String(finalBuffer.array(), "utf-8"));
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    }
                }
            });
        });

        return future;
    }

    @Test
    public void testReadUntilWithShortData() throws IOException, ExecutionException, InterruptedException {
        CompletableFuture<String> future = readUntil(1, 100, s -> s.contains("!"));

        sendDataAsync("THIS IS SOME TEST SENTENCE!");

        String expected = "HIS IS SOME TEST SENTENCE!";
        String actual = future.get().substring(0, expected.length());

        assertEquals(expected, actual);
    }

    @Test
    public void testReadUntilWithLongData() throws ExecutionException, InterruptedException {
        StringBuilder testData = new StringBuilder();

        for (int i = 0; i < 10000; i++) {
            testData.append(String.valueOf(i));
        }

        testData.append("!");

        CompletableFuture<String> future = readUntil(0, testData.length(), s -> s.contains("!"));

        sendDataAsync(testData.toString());

        assertEquals(testData.toString(), future.get().substring(0, testData.length()));
    }

    private String makeLongData() {
        StringBuilder testData = new StringBuilder();

        for (int i = 0; i < 10000; i++) {
            testData.append(String.valueOf(i));
        }

        testData.append("!");

        return testData.toString();
    }

    @Test
    public void testReadUntilWithLongDataAndMaxLength() throws ExecutionException, InterruptedException {
        String testData = makeLongData();

        CompletableFuture<String> future = readUntil(0, 5000, s -> s.contains("!"));

        sendDataAsync(testData);

        assertEquals(testData.substring(0, 5000), future.get().substring(0, 5000));
    }

    @Test
    public void testReadUntilClose() throws ExecutionException, InterruptedException {
        CompletableFuture<String> future = readUntilClose(1, 100);

        sendDataAsync("THIS IS SOME TEST SENTENCE!");

        String expected = "HIS IS SOME TEST SENTENCE!";
        String actual = future.get().substring(0, expected.length());

        assertEquals(expected, actual);
    }

    @Test
    public void testPumpToStreamUntilClose() throws ExecutionException, InterruptedException, UnsupportedEncodingException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        CompletableFuture<Void> future = new CompletableFuture<>();

        server.setDefaultReadCallback((channel, buf) -> {
            server.pumpToStreamUntilClose(channel, buf, 1, stream, 100, ex -> {
                if (ex != null) {
                    future.completeExceptionally(ex);
                } else {
                    future.complete(null);
                }
            });
        });

        sendDataAsync("THIS IS SOME TEST SENTENCE!");

        future.get();

        String expected = "HIS IS SOME TEST SENTENCE!";
        String actual = stream.toString("utf-8").substring(0, expected.length());

        assertEquals(expected, actual);
    }

    private void writeDataOnConnect(String data) {
        server.setConnectedCallback(channel -> {
            try {
                server.write(channel, ByteBuffer.wrap(data.getBytes("utf-8")), data.length(), ex -> {
                    if (ex != null) {
                        ex.printStackTrace();
                    }

                    try {
                        channel.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void writeStreamOnConnect(InputStream data) {
        server.setConnectedCallback(channel -> {
            try {
                server.pumpFromStream(channel, data, ex -> {
                    if (ex != null) {
                        ex.printStackTrace();
                    }

                    try {
                        channel.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @Test
    public void testWriteShortData() throws IOException {
        String expected = "THIS IS SOME TEST SENTENCE!";

        writeDataOnConnect(expected);

        try (Socket socket = new Socket("localhost", 8080)) {
            String actual = IOUtils.toString(socket.getInputStream(), "utf-8");

            assertEquals(expected, actual);
        }
    }

    @Test
    public void testWriteLongData() throws IOException {
        String expected = makeLongData();

        writeDataOnConnect(expected);

        try (Socket socket = new Socket("localhost", 8080)) {
            String actual = IOUtils.toString(socket.getInputStream(), "utf-8");

            assertEquals(expected, actual);
        }
    }

    @Test
    public void testPumpFromStreamWithLongData() throws IOException {
        String expected = makeLongData();
        InputStream stream = new ByteArrayInputStream(expected.getBytes("utf-8"));

        writeStreamOnConnect(stream);

        try (Socket socket = new Socket("localhost", 8080)) {
            String actual = IOUtils.toString(socket.getInputStream(), "utf-8");

            assertEquals(expected, actual);
        }
    }
}
