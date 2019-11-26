/*
 * Copyright 2010-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.http.nio.netty.internal.http2;

import static software.amazon.awssdk.http.nio.netty.internal.utils.NettyUtils.doInEventLoop;
import static software.amazon.awssdk.http.nio.netty.internal.utils.NettyUtils.warnIfNotInEventLoop;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.utils.Logger;

/**
 * Contains a {@link Future} for the actual socket channel and tracks available
 * streams based on the MAX_CONCURRENT_STREAMS setting for the connection.
 */
@SdkInternalApi
public class MultiplexedChannelRecord {
    private static final Logger log = Logger.loggerFor(MultiplexedChannelRecord.class);

    private final Channel connection;
    private final long maxConcurrencyPerConnection;
    private final AtomicLong availableChildChannels;

    // Only read or write in the connection.eventLoop()
    private final Map<ChannelId, Http2StreamChannel> childChannels = new HashMap<>();

    // Only write in the connection.eventLoop()
    private volatile RecordState state = RecordState.OPEN;

    MultiplexedChannelRecord(Channel connection, long maxConcurrencyPerConnection) {
        this.connection = connection;
        this.maxConcurrencyPerConnection = maxConcurrencyPerConnection;
        this.availableChildChannels = new AtomicLong(maxConcurrencyPerConnection);
    }

    boolean acquireStream(Promise<Channel> promise) {
        if (reserveStream()) {
            releaseReservationOnFailure(promise);
            acquireReservedStream(promise);
            return true;
        }
        return false;
    }

    private void acquireReservedStream(Promise<Channel> promise) {
        doInEventLoop(connection.eventLoop(), () -> {
            if (state != RecordState.OPEN) {
                String message = "Connection received GOAWAY or was closed while acquiring new stream.";
                promise.setFailure(new IllegalStateException(message));
                return;
            }

            Future<Http2StreamChannel> streamFuture = new Http2StreamChannelBootstrap(connection).open();
            streamFuture.addListener((GenericFutureListener<Future<Http2StreamChannel>>) future -> {
                warnIfNotInEventLoop(connection.eventLoop());

                if (!future.isSuccess()) {
                    promise.setFailure(future.cause());
                    return;
                }

                Http2StreamChannel channel = future.getNow();
                childChannels.put(channel.id(), channel);
                promise.setSuccess(channel);
            });
        }, promise);
    }

    private void releaseReservationOnFailure(Promise<Channel> promise) {
        try {
            promise.addListener(f -> {
                if (!promise.isSuccess()) {
                    releaseReservation();
                }
            });
        } catch (Throwable e) {
            releaseReservation();
            throw e;
        }
    }

    private void releaseReservation() {
        if (availableChildChannels.incrementAndGet() > maxConcurrencyPerConnection) {
            assert false;
            log.warn(() -> "Child channel count was caught attempting to be increased over max concurrency. "
                           + "Please report this issue to the AWS SDK for Java team.");
            availableChildChannels.decrementAndGet();
        }
    }

    /**
     * Handle a {@link Http2GoAwayFrame} on this connection, preventing new streams from being created on it, and closing any
     * streams newer than the last-stream-id on the go-away frame.
     */
    public void handleGoAway(int lastStreamId, GoAwayException exception) {
        doInEventLoop(connection.eventLoop(), () -> {
            if (state == RecordState.CLOSED) {
                return;
            }

            if (state == RecordState.OPEN) {
                state = RecordState.CLOSED_TO_NEW;
            }

            childChannels.values().stream()
                         .filter(cc -> cc.stream().id() > lastStreamId)
                         .forEach(cc -> cc.pipeline().fireExceptionCaught(exception));
        });
    }

    /**
     * Close all registered child channels, and prohibit new streams from being created on this connection.
     */
    public void closeChildren() {
        doInEventLoop(connection.eventLoop(), () -> {
            if (state == RecordState.CLOSED) {
                return;
            }
            state = RecordState.CLOSED;

            for (Channel childChannel : childChannels.values()) {
                childChannel.close();
            }
        });
    }

    /**
     * Delivers the exception to all registered child channels, and prohibits new streams being created on this connection.
     */
    public void closeChildren(Throwable t) {
        doInEventLoop(connection.eventLoop(), () -> {
            if (state == RecordState.CLOSED) {
                return;
            }
            state = RecordState.CLOSED;

            for (Channel childChannel : childChannels.values()) {
                childChannel.pipeline().fireExceptionCaught(t);
            }
        });
    }

    public void closeAndRelease(Channel childChannel) {
        childChannel.close();
        doInEventLoop(connection.eventLoop(), () -> {
            childChannels.remove(childChannel.id());
            releaseReservation();
        });
    }

    public Channel getConnection() {
        return connection;
    }

    public boolean reserveStream() {
        for (int attempt = 0; attempt < 5; ++attempt) {
            if (state != RecordState.OPEN) {
                return false;
            }

            long currentlyAvailable = availableChildChannels.get();

            if (currentlyAvailable <= 0) {
                return false;
            }
            if (availableChildChannels.compareAndSet(currentlyAvailable, currentlyAvailable - 1)) {
                return true;
            }
        }

        return false;
    }

    boolean canBeReleased() {
        return state != RecordState.OPEN && availableChildChannels.get() == maxConcurrencyPerConnection;
    }

    private enum RecordState {
        OPEN,
        CLOSED_TO_NEW,
        CLOSED
    }
}
