/*
 * The MIT License (MIT)
 * Copyright (c) 2018 Microsoft Corporation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package com.microsoft.azure.cosmosdb.internal.directconnectivity.rntbd;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.StoreResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.ReferenceCounted;
import io.netty.util.ResourceLeakDetector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.microsoft.azure.cosmosdb.internal.directconnectivity.rntbd.RntbdConstants.RntbdResponseHeader;
import static java.lang.Integer.min;

@JsonPropertyOrder({ "messageLength", "referenceCount", "frame", "headers", "content" })
public final class RntbdResponse implements ReferenceCounted {

    // region Fields

    private static final AtomicIntegerFieldUpdater REFERENCE_COUNT =
        AtomicIntegerFieldUpdater.newUpdater(RntbdResponse.class, "referenceCount");

    @JsonSerialize(using = PayloadSerializer.class)
    private final ByteBuf content;

    @JsonProperty
    private final RntbdResponseStatus frame;

    @JsonProperty
    private final RntbdResponseHeaders headers;

    private final ByteBuf message;

    @JsonProperty
    private final int messageLength;

    @JsonProperty
    private volatile int referenceCount;

    // endregion

    // region Constructors

    public RntbdResponse(
        final UUID activityId,
        final int statusCode,
        final Map<String, String> map,
        final ByteBuf content) {

        this.headers = RntbdResponseHeaders.fromMap(map, content.readableBytes() > 0);
        this.message = Unpooled.EMPTY_BUFFER;
        this.content = content.copy().retain();

        final HttpResponseStatus status = HttpResponseStatus.valueOf(statusCode);
        final int length = RntbdResponseStatus.LENGTH + this.headers.computeLength();

        this.frame = new RntbdResponseStatus(length, status, activityId);
        this.messageLength = length + this.content.writerIndex();
        this.referenceCount = 0;
    }

    private RntbdResponse(
        final ByteBuf message,
        final RntbdResponseStatus frame,
        final RntbdResponseHeaders headers,
        final ByteBuf content) {

        this.message = message.retain();
        this.referenceCount = 0;
        this.frame = frame;
        this.headers = headers;
        this.content = content.retain();
        this.messageLength = message.writerIndex();
    }

    // endregion

    // region Accessors

    @JsonIgnore
    public UUID getActivityId() {
        return this.frame.getActivityId();
    }

    @JsonIgnore
    public ByteBuf getContent() {
        return this.content;
    }

    @JsonIgnore
    public RntbdResponseHeaders getHeaders() {
        return this.headers;
    }

    @JsonIgnore
    public int getMessageLength() {
        return this.messageLength;
    }

    @JsonIgnore
    public HttpResponseStatus getStatus() {
        return this.frame.getStatus();
    }

    @JsonIgnore
    public Long getTransportRequestId() {
        return this.getHeader(RntbdResponseHeader.TransportRequestID);
    }

    // endregion

    // region Methods

    public void encode(final ByteBuf out) {

        final int start = out.writerIndex();

        this.frame.encode(out);
        this.headers.encode(out);

        final int length = out.writerIndex() - start;
        checkState(length == this.frame.getLength());

        if (this.hasPayload()) {
            out.writeIntLE(this.content.readableBytes());
            out.writeBytes(this.content);
        } else if (this.content.readableBytes() > 0) {
            throw new IllegalStateException();
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getHeader(final RntbdResponseHeader header) {
        return (T) this.headers.get(header).getValue();
    }

    public boolean hasPayload() {
        return this.headers.isPayloadPresent();
    }

    /**
     * Returns the reference count of this object.  If {@code 0}, it means this object has been deallocated.
     */
    @Override
    public int refCnt() {
        return this.referenceCount;
    }

    /**
     * Decreases the reference count by {@code 1} and deallocate this response if the count reaches {@code 0}.
     *
     * @return {@code true} if and only if the reference count became {@code 0} and this response is deallocated.
     */
    @Override
    public boolean release() {
        return this.release(1);
    }

    /**
     * Decreases the reference count by {@code decrement} and deallocates this response if the count reaches {@code 0}.
     *
     * @param decrement amount of the decrease.
     *
     * @return {@code true} if and only if the reference count became {@code 0} and this response has been deallocated.
     */
    @Override
    public boolean release(final int decrement) {

        return REFERENCE_COUNT.accumulateAndGet(this, decrement, (value, n) -> {

            value = value - min(value, n);

            if (value == 0) {

                checkState(this.headers != null && this.content != null);
                this.headers.releaseBuffers();

                if (this.message != Unpooled.EMPTY_BUFFER) {
                    this.message.release();
                }

                if (this.content != Unpooled.EMPTY_BUFFER) {
                    this.content.release();
                }

                // TODO: DANOBLE: figure out why PooledUnsafeDirectByteBuf violates these expectations:
                //    checkState(this.in == Unpooled.EMPTY_BUFFER || this.in.refCnt() == 0);
                //    checkState(this.content == Unpooled.EMPTY_BUFFER || this.content.refCnt() == 0);
                //  Specifically, why are this.in.refCnt() and this.content.refCnt() equal to 1?
            }

            return value;

        }) == 0;
    }

    /**
     * Increases the reference count by {@code 1}.
     */
    @Override
    public ReferenceCounted retain() {
        REFERENCE_COUNT.incrementAndGet(this);
        return this;
    }

    /**
     * Increases the reference count by the specified {@code increment}.
     *
     * @param increment amount of the increase
     */
    @Override
    public ReferenceCounted retain(final int increment) {
        REFERENCE_COUNT.addAndGet(this, increment);
        return this;
    }

    @Override
    public String toString() {
        return RntbdObjectMapper.toString(this);
    }

    /**
     * Records the current access location of this object for debugging purposes
     * <p>
     * If this object is determined to be leaked, the information recorded by this operation will be provided to you via
     * {@link ResourceLeakDetector}.  This method is a shortcut to {@link #touch(Object) touch(null)}.
     */
    @Override
    public ReferenceCounted touch() {
        return this;
    }

    /**
     * Records the current access location of this object with additional arbitrary information for debugging purposes
     * <p>
     * If this object is determined to be leaked, the information recorded by this operation will be provided to you via
     * {@link ResourceLeakDetector}.
     *
     * @param hint information useful for debugging (unused)
     */
    @Override
    public ReferenceCounted touch(final Object hint) {
        return this;
    }

    static RntbdResponse decode(final ByteBuf in) {

        final int start = in.markReaderIndex().readerIndex();

        final RntbdResponseStatus frame = RntbdResponseStatus.decode(in);
        final RntbdResponseHeaders headers = RntbdResponseHeaders.decode(in.readSlice(frame.getHeadersLength()));
        final boolean hasPayload = headers.isPayloadPresent();
        final ByteBuf content;

        if (hasPayload) {

            if (!RntbdFramer.canDecodePayload(in)) {
                headers.releaseBuffers();
                in.resetReaderIndex();
                return null;
            }

            content = in.readSlice(in.readIntLE());

        } else {

            content = Unpooled.EMPTY_BUFFER;
        }

        final int end = in.readerIndex();
        in.resetReaderIndex();

        return new RntbdResponse(in.readSlice(end - start), frame, headers, content);
    }

    StoreResponse toStoreResponse(final RntbdContext context) {

        checkNotNull(context, "context");
        final int length = this.content.readableBytes();

        return new StoreResponse(
            this.getStatus().code(),
            this.headers.asList(context, this.getActivityId()),
            length == 0 ? null : this.content.readCharSequence(length, StandardCharsets.UTF_8).toString()
        );
    }

    // endregion

    // region Types

    private static class PayloadSerializer extends StdSerializer<ByteBuf> {

        PayloadSerializer() {
            super(ByteBuf.class, true);
        }

        @Override
        public void serialize(
            final ByteBuf value,
            final JsonGenerator generator,
            final SerializerProvider provider) throws IOException {

            final int length = value.readableBytes();

            generator.writeStartObject();
            generator.writeObjectField("lengthInBytes", length);
            generator.writeObjectField("hexDump", ByteBufUtil.hexDump(value, 0, length));
            generator.writeObjectField("string", value.getCharSequence(0, length, StandardCharsets.UTF_8));
            generator.writeEndObject();
        }
    }

    // endregion
}
