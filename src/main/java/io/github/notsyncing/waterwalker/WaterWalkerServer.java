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

    public WaterWalkerServer(String listenAddress, int listenPort) throws IOException {
        this.listenAddress = listenAddress;
        this.listenPort = listenPort;

        channel.socket().bind(new InetSocketAddress(InetAddress.getByName(listenAddress), listenPort));
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_ACCEPT);

        selectorThread = new Thread(this::selectorWorker);
        selectorThread.start();
    }

    public int getListenPort() {
        return listenPort;
    }

    public String getListenAddress() {
        return listenAddress;
    }

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

    public void setDefaultReadCallback(BiConsumer<SocketChannel, ByteBuffer> callback) {
        this.defaultChannelReadCallback = callback;
    }

    public void setConnectedCallback(Consumer<SocketChannel> callback) {
        this.channelConnectedCallback = callback;
    }

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

    public void readUntilClose(SocketChannel channel, ByteBuffer currentBuffer, int currentBufferSkip, int maxLength,
                               BiConsumer<ByteBuffer, Exception> doneCallback) {
        readUntil(channel, currentBuffer, currentBufferSkip, maxLength, Objects::isNull, doneCallback);
    }

    public CompletableFuture<ByteBuffer> readUntilClose(SocketChannel channel, ByteBuffer currentBuffer,
                                                        int currentBufferSkip, int maxLength) {
        return readUntil(channel, currentBuffer, currentBufferSkip, maxLength, Objects::isNull);
    }

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

    public void pumpToStreamUntilClose(SocketChannel channel, ByteBuffer currentBuffer, int currentBufferSkip,
                                       OutputStream outputStream, int maxLength, Consumer<Exception> doneCallback) {
        pumpToStreamUntil(channel, currentBuffer, currentBufferSkip, outputStream, maxLength, Objects::isNull,
                doneCallback);
    }

    public CompletableFuture<Void> pumpToStreamUntilClose(SocketChannel channel, ByteBuffer currentBuffer,
                                                          int currentBufferSkip, OutputStream outputStream,
                                                          int maxLength) {
        return pumpToStreamUntil(channel, currentBuffer, currentBufferSkip, outputStream, maxLength, Objects::isNull);
    }

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

    public void pumpFromStream(SocketChannel channel, InputStream inputStream,
                               Consumer<Exception> doneCallback) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        writeStream(channel, inputStream, buf, doneCallback);
    }

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
