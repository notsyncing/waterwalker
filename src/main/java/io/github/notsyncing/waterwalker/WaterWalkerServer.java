package io.github.notsyncing.waterwalker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class WaterWalkerServer {
    private static final int BUFFER_SIZE = 1024;

    private int listenPort;
    private String listenAddress;
    private Selector selector = Selector.open();
    private ServerSocketChannel channel = ServerSocketChannel.open();
    private Thread selectorThread;
    private ConcurrentHashMap<SocketChannel, ChannelReadTask> channelReadTasks = new ConcurrentHashMap<>();
    private ConcurrentHashMap<SocketChannel, ConcurrentLinkedQueue<ChannelWriteTask>> channelWriteTasks = new ConcurrentHashMap<>();
    private BiConsumer<SocketChannel, ByteBuffer> defaultChannelReadCallback;
    private Consumer<SocketChannel> channelConnectedCallback;

    private boolean stop = false;

    /**
     * Create a TCP server
     * @param listenAddress address to listen to, e.g. "0.0.0.0" for all hosts, or "localhost"
     * @param listenPort port to listen to, e.g. 8080
     * @throws IOException when something goes wrong
     */
    public WaterWalkerServer(String listenAddress, int listenPort) throws IOException {
        this.listenAddress = listenAddress;
        this.listenPort = listenPort;

        channel.socket().bind(new InetSocketAddress(InetAddress.getByName(listenAddress), listenPort));
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_ACCEPT);

        selectorThread = new Thread(this::selectorWorker);
        selectorThread.start();
    }

    /**
     * Get current listening port
     * @return current listening port
     */
    public int getListenPort() {
        return listenPort;
    }

    /**
     * Get current listening address
     * @return current listening address
     */
    public String getListenAddress() {
        return listenAddress;
    }

    /**
     * Stop the server
     * @throws InterruptedException
     * @throws IOException
     */
    public void stop() throws InterruptedException, IOException {
        stop = true;
        selector.wakeup();

        selectorThread.join();

        selector.close();
        channel.close();

        channelReadTasks.clear();
        channelWriteTasks.clear();
    }

    private void selectorWorker() {
        while (!stop) {
            try {
                if (selector.select() == 0) {
                    continue;
                }

                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();

                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();

                    if (!key.isValid()) {
                        iterator.remove();
                        continue;
                    }

                    if (key.isAcceptable()) {
                        acceptConnection(key);
                    } else if (key.isReadable()) {
                        readFromConnection(key);
                    } else if (key.isWritable()) {
                        writeToConnection(key);
                    }

                    iterator.remove();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void acceptConnection(SelectionKey key) throws IOException {
        SocketChannel clientChannel = ((ServerSocketChannel)key.channel()).accept();
        clientChannel.configureBlocking(false);
        clientChannel.register(key.selector(), SelectionKey.OP_READ, ByteBuffer.allocate(BUFFER_SIZE));

        if (channelConnectedCallback != null) {
            channelConnectedCallback.accept(clientChannel);
        }
    }

    private void readFromConnection(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ByteBuffer buffer = (ByteBuffer)key.attachment();

        if (buffer != null) {
            buffer.clear();
        } else {
            buffer = ByteBuffer.allocate(BUFFER_SIZE);
            key.attach(buffer);
        }

        int length = clientChannel.read(buffer);

        if (length < 0) {
            buffer = null;
            clientChannel.close();
        }

        ChannelReadTask clientReadTask = channelReadTasks.get(clientChannel);

        if (clientReadTask != null) {
            clientReadTask.getCallback().accept(buffer);
        } else {
            if (defaultChannelReadCallback != null) {
                defaultChannelReadCallback.accept(clientChannel, buffer);
            }
        }
    }

    private void writeToConnection(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        Queue<ChannelWriteTask> clientWriteTasks = channelWriteTasks.get(clientChannel);

        if ((clientWriteTasks == null) || (clientWriteTasks.isEmpty())) {
            key.interestOps(SelectionKey.OP_READ);
            return;
        }

        ChannelWriteTask task = clientWriteTasks.poll();

        if (task == null) {
            return;
        }

        ByteBuffer bufToWrite = task.getBuffer();

        if (bufToWrite.capacity() > task.getLength()) {
            bufToWrite.limit(task.getLength());
        }

        try {
            int length = clientChannel.write(task.getBuffer());
            task.setWriteByteCount(task.getWriteByteCount() + length);

            if (task.getWriteByteCount() != task.getLength()) {
                clientWriteTasks.offer(task);
            } else {
                task.getCallback().accept(null);
            }
        } catch (Exception e) {
            task.getCallback().accept(e);
        }

        if (key.isValid()) {
            int ops = SelectionKey.OP_READ;

            if (!clientWriteTasks.isEmpty()) {
                ops = ops | SelectionKey.OP_WRITE;
            }

            key.interestOps(ops);
        }
    }

    /**
     * When some data was read into buffer, this handler will be called, you can process the read data
     * @param callback a function containing current socket and read data
     */
    public void setDefaultReadCallback(BiConsumer<SocketChannel, ByteBuffer> callback) {
        this.defaultChannelReadCallback = callback;
    }

    /**
     * When a new connection was established, this handler will be called
     * @param callback a function containing current socket
     */
    public void setConnectedCallback(Consumer<SocketChannel> callback) {
        this.channelConnectedCallback = callback;
    }

    /**
     * Read all data until a condition is met, or the max length is reached
     * @param channel current socket channel
     * @param currentBuffer current already read data buffer
     * @param currentBufferSkip if you has already read some data from the buffer, fill with the length you have read
     * @param maxLength max data bytes to read regardless the conditions
     * @param condition a predicate with current data block as parameter, indicating if we should end reading
     * @param doneCallback a function called when all data was read or an exception was thrown
     */
    public void readUntil(SocketChannel channel, ByteBuffer currentBuffer, int currentBufferSkip, int maxLength,
                          Predicate<ByteBuffer> condition, BiConsumer<ByteBuffer, Exception> doneCallback) {
        ChannelReadTask task = channelReadTasks.get(channel);

        if (task == null) {
            task = new ChannelReadTask();
            task.setBuffer(ByteBuffer.allocate(maxLength));
            task.setCallback(buf -> readUntil(channel, buf, 0, maxLength, condition, doneCallback));

            channelReadTasks.put(channel, task);
        }

        try {
            boolean shouldEnd = false;

            if (currentBuffer != null) {
                byte[] currBytes = currentBuffer.array();
                int length = currBytes.length - currentBufferSkip;

                if (task.getReadByteCount() + length >= task.getBuffer().limit()) {
                    length = task.getBuffer().limit() - task.getReadByteCount();
                    shouldEnd = true;
                }

                task.getBuffer().put(currBytes, currentBufferSkip, length);
                task.setReadByteCount(task.getReadByteCount() + length);
            } else {
                shouldEnd = true;
            }

            if ((condition.test(currentBuffer)) || (shouldEnd)) {
                channelReadTasks.remove(channel);
                doneCallback.accept(task.getBuffer(), null);
            }
        } catch (Exception e) {
            doneCallback.accept(null, e);
        }
    }

    /**
     * Read all data until a condition is met, or the max length is reached, CompletableFuture version
     * @param channel current socket channel
     * @param currentBuffer current already read data buffer
     * @param currentBufferSkip if you has already read some data from the buffer, fill with the length you have read
     * @param maxLength max data bytes to read regardless the conditions
     * @param condition a predicate with current data block as parameter, indicating if we should end reading
     * @return a CompletableFuture indicating the end of reading
     */
    public CompletableFuture<ByteBuffer> readUntil(SocketChannel channel, ByteBuffer currentBuffer,
                                                   int currentBufferSkip, int maxLength,
                                                   Predicate<ByteBuffer> condition) {
        CompletableFuture<ByteBuffer> future = new CompletableFuture<>();

        readUntil(channel, currentBuffer, currentBufferSkip, maxLength, condition, (buf, ex) -> {
            if (ex == null) {
                future.complete(buf);
            } else {
                future.completeExceptionally(ex);
            }
        });

        return future;
    }

    /**
     * Read all data until the connection is closed by peer, or the max length is reached
     * @param channel current socket channel
     * @param currentBuffer current already read data buffer
     * @param currentBufferSkip if you has already read some data from the buffer, fill with the length you have read
     * @param maxLength max data bytes to read regardless the conditions
     * @param doneCallback a function called when all data was read or an exception was thrown
     */
    public void readUntilClose(SocketChannel channel, ByteBuffer currentBuffer, int currentBufferSkip, int maxLength,
                               BiConsumer<ByteBuffer, Exception> doneCallback) {
        readUntil(channel, currentBuffer, currentBufferSkip, maxLength, Objects::isNull, doneCallback);
    }

    /**
     * Read all data until the connection is closed by peer, or the max length is reached, CompletableFuture version
     * @param channel current socket channel
     * @param currentBuffer current already read data buffer
     * @param currentBufferSkip if you has already read some data from the buffer, fill with the length you have read
     * @param maxLength max data bytes to read regardless the conditions
     * @return a CompletableFuture indicating all data was read
     */
    public CompletableFuture<ByteBuffer> readUntilClose(SocketChannel channel, ByteBuffer currentBuffer,
                                                        int currentBufferSkip, int maxLength) {
        return readUntil(channel, currentBuffer, currentBufferSkip, maxLength, Objects::isNull);
    }

    /**
     * Read all data into an OutputStream until a condition is met, or the max length is reached
     * @param channel current socket channel
     * @param currentBuffer current already read data buffer
     * @param currentBufferSkip if you has already read some data from the buffer, fill with the length you have read
     * @param outputStream the OutputStream to write
     * @param maxLength max data bytes to read regardless the conditions
     * @param condition a predicate with current data block as parameter, indicating if we should end reading
     * @param doneCallback a function called when all data was read or an exception was thrown
     */
    public void pumpToStreamUntil(SocketChannel channel, ByteBuffer currentBuffer, int currentBufferSkip,
                                  OutputStream outputStream, int maxLength, Predicate<ByteBuffer> condition,
                                  Consumer<Exception> doneCallback) {
        ChannelReadTask task = channelReadTasks.get(channel);

        if (task == null) {
            task = new ChannelReadTask();
            task.setCallback(buf -> pumpToStreamUntil(channel, buf, 0, outputStream, maxLength,
                    condition, doneCallback));

            channelReadTasks.put(channel, task);
        }

        try {
            boolean shouldEnd = false;

            if (currentBuffer != null) {
                byte[] currBytes = currentBuffer.array();
                int length = currBytes.length - currentBufferSkip;

                if (task.getReadByteCount() + length >= maxLength) {
                    length = maxLength;
                    shouldEnd = true;
                }

                outputStream.write(currBytes, currentBufferSkip, length);
                task.setReadByteCount(task.getReadByteCount() + length);
            } else {
                shouldEnd = true;
            }

            if ((condition.test(currentBuffer)) || (shouldEnd)) {
                channelReadTasks.remove(channel);
                doneCallback.accept(null);
            }
        } catch (Exception e) {
            doneCallback.accept(e);
        }
    }

    /**
     * Read all data into an OutputStream until a condition is met, or the max length is reached, CompletableFuture version
     * @param channel current socket channel
     * @param currentBuffer current already read data buffer
     * @param currentBufferSkip if you has already read some data from the buffer, fill with the length you have read
     * @param outputStream the OutputStream to write
     * @param maxLength max data bytes to read regardless the conditions
     * @param condition a predicate with current data block as parameter, indicating if we should end reading
     * @return a CompletableFuture indicating all data was read
     */
    public CompletableFuture<Void> pumpToStreamUntil(SocketChannel channel, ByteBuffer currentBuffer,
                                                     int currentBufferSkip, OutputStream outputStream,
                                                     int maxLength, Predicate<ByteBuffer> condition) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        pumpToStreamUntil(channel, currentBuffer, currentBufferSkip, outputStream, maxLength, condition, ex -> {
            if (ex == null) {
                future.complete(null);
            } else {
                future.completeExceptionally(ex);
            }
        });

        return future;
    }

    /**
     * Read all data into an OutputStream until the connection is closed by peer, or the max length is reached
     * @param channel current socket channel
     * @param currentBuffer current already read data buffer
     * @param currentBufferSkip if you has already read some data from the buffer, fill with the length you have read
     * @param outputStream the OutputStream to write
     * @param maxLength max data bytes to read regardless the conditions
     * @param doneCallback a function called when all data was read or an exception was thrown
     */
    public void pumpToStreamUntilClose(SocketChannel channel, ByteBuffer currentBuffer, int currentBufferSkip,
                                       OutputStream outputStream, int maxLength, Consumer<Exception> doneCallback) {
        pumpToStreamUntil(channel, currentBuffer, currentBufferSkip, outputStream, maxLength, Objects::isNull,
                doneCallback);
    }

    /**
     * Read all data into an OutputStream until the connection is closed by peer, or the max length is reached, CompletableFuture version
     * @param channel current socket channel
     * @param currentBuffer current already read data buffer
     * @param currentBufferSkip if you has already read some data from the buffer, fill with the length you have read
     * @param outputStream the OutputStream to write
     * @param maxLength max data bytes to read regardless the conditions
     * @return a CompletableFuture indicating all data was read
     */
    public CompletableFuture<Void> pumpToStreamUntilClose(SocketChannel channel, ByteBuffer currentBuffer,
                                                          int currentBufferSkip, OutputStream outputStream,
                                                          int maxLength) {
        return pumpToStreamUntil(channel, currentBuffer, currentBufferSkip, outputStream, maxLength, Objects::isNull);
    }

    /**
     * Write data to a connection
     * @param channel the connection socket channel to write to
     * @param buf the data to write
     * @param length max byte count to write
     * @param doneCallback a function called when the writing is done
     * @throws ClosedChannelException when the connection has closed
     */
    public void write(SocketChannel channel, ByteBuffer buf, int length,
                      Consumer<Exception> doneCallback) throws ClosedChannelException {
        ChannelWriteTask task = new ChannelWriteTask();
        task.setBuffer(buf);
        task.setLength(length);
        task.setCallback(doneCallback);

        ConcurrentLinkedQueue<ChannelWriteTask> queue = channelWriteTasks.get(channel);

        if (queue == null) {
            queue = new ConcurrentLinkedQueue<>();
            channelWriteTasks.put(channel, queue);
        }

        queue.offer(task);

        channel.register(selector, SelectionKey.OP_WRITE);
        selector.wakeup();
    }

    /**
     * Write data to a connection, CompletableFuture version
     * @param channel the connection socket channel to write to
     * @param buf the data to write
     * @param length max byte count to write
     * @return a CompletableFuture indicating the writing is done
     * @throws ClosedChannelException when the connection has closed
     */
    public CompletableFuture<Void> write(SocketChannel channel, ByteBuffer buf,
                                         int length) throws ClosedChannelException {
        CompletableFuture<Void> future = new CompletableFuture<>();

        write(channel, buf, length, ex -> {
            if (ex == null) {
                future.complete(null);
            } else {
                future.completeExceptionally(ex);
            }
        });

        return future;
    }

    /**
     * Write data to a connection, CompletableFuture version
     * @param channel the connection socket channel to write to
     * @param buf the data to write
     * @return a CompletableFuture indicating the writing is done
     * @throws ClosedChannelException when the connection has closed
     */
    public CompletableFuture<Void> write(SocketChannel channel, ByteBuffer buf) throws ClosedChannelException {
        return write(channel, buf, buf.capacity());
    }

    private void writeStream(SocketChannel channel, InputStream stream, byte[] currentBuffer,
                             Consumer<Exception> doneCallback) throws IOException {
        int readLength = stream.read(currentBuffer);

        if (readLength < 0) {
            doneCallback.accept(null);
            return;
        }

        write(channel, ByteBuffer.wrap(currentBuffer), readLength, ex -> {
            if (ex == null) {
                try {
                    writeStream(channel, stream, currentBuffer, doneCallback);
                } catch (IOException e) {
                    doneCallback.accept(e);
                }
            } else {
                doneCallback.accept(ex);
            }
        });
    }

    /**
     * Write all data from an InputStream to a connection
     * @param channel the connection socket channel to write to
     * @param inputStream the InputStream to read from
     * @param doneCallback a function called when the writing is done
     * @throws ClosedChannelException when the connection has closed
     */
    public void pumpFromStream(SocketChannel channel, InputStream inputStream,
                               Consumer<Exception> doneCallback) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        writeStream(channel, inputStream, buf, doneCallback);
    }

    /**
     * Write all data from an InputStream to a connection, CompletableFuture version
     * @param channel the connection socket channel to write to
     * @param inputStream the InputStream to read from
     * @return a CompletableFuture indicating the writing is done
     * @throws ClosedChannelException when the connection has closed
     */
    public CompletableFuture<Void> pumpFromStream(SocketChannel channel, InputStream inputStream) throws IOException {
        CompletableFuture<Void> future = new CompletableFuture<>();

        pumpFromStream(channel, inputStream, ex -> {
            if (ex == null) {
                future.complete(null);
            } else {
                future.completeExceptionally(ex);
            }
        });

        return future;
    }
}
