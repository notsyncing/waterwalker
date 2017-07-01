package io.github.notsyncing.waterwalker;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

public class ChannelReadTask {
    private Consumer<ByteBuffer> callback;
    private int readByteCount = 0;
    private ByteBuffer buffer;

    public Consumer<ByteBuffer> getCallback() {
        return callback;
    }

    public void setCallback(Consumer<ByteBuffer> callback) {
        this.callback = callback;
    }

    public int getReadByteCount() {
        return readByteCount;
    }

    public void setReadByteCount(int readByteCount) {
        this.readByteCount = readByteCount;
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }

    public void setBuffer(ByteBuffer buffer) {
        this.buffer = buffer;
    }
}
