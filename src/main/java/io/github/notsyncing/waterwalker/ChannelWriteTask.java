package io.github.notsyncing.waterwalker;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

public class ChannelWriteTask {
    private Consumer<Exception> callback;
    private long writeByteCount = 0;
    private int length = 0;
    private ByteBuffer buffer;

    public Consumer<Exception> getCallback() {
        return callback;
    }

    public void setCallback(Consumer<Exception> callback) {
        this.callback = callback;
    }

    public long getWriteByteCount() {
        return writeByteCount;
    }

    public void setWriteByteCount(long writeByteCount) {
        this.writeByteCount = writeByteCount;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }

    public void setBuffer(ByteBuffer buffer) {
        this.buffer = buffer;
    }
}
