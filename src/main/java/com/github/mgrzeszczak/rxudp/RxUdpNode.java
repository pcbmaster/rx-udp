package com.github.mgrzeszczak.rxudp;

import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.DatagramChannel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.logging.Level;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.reactivex.Completable;
import io.reactivex.Emitter;
import io.reactivex.Flowable;
import io.reactivex.Scheduler;
import io.reactivex.Single;

public class RxUdpNode {

    private static final String LOG_TAG = "rx-udp";
    private static final int MAX_SIZE = 65507;

    private final DatagramChannel channel;
    private final Runnable start;
    private final Set<Emitter<Packet>> subscribers = new HashSet<>();
    private final ByteBuffer buffer = ByteBuffer.allocate(MAX_SIZE);

    private boolean isRunning;

    RxUdpNode(@NotNull DatagramChannel channel) {
        this.channel = channel;
        this.start = () -> Executors.newSingleThreadExecutor().execute(this::listen);
    }

    RxUdpNode(@NotNull DatagramChannel channel, @NotNull Scheduler scheduler) {
        this.channel = channel;
        this.start = () -> scheduler.scheduleDirect(this::listen);
    }

    public Completable send(@NotNull byte[] data, @NotNull SocketAddress target) {
        return Completable.fromAction(() -> channel.send(ByteBuffer.wrap(data), target));
    }

    public Flowable<Packet> packets() {
        synchronized (this) {
            if (!isRunning) {
                isRunning = true;
                start.run();
            }
        }
        return Flowable.generate(emitter -> {
            synchronized (subscribers) {
                subscribers.add(emitter);
            }
        });
    }

    public Single<SocketAddress> address() {
        return Single.fromCallable(channel::getLocalAddress);
    }

    public Completable close() {
        return Completable.fromAction(
                () -> {
                    synchronized (RxUdpNode.this) {
                        if (!isRunning) {
                            return;
                        }
                        synchronized (subscribers) {
                            subscribers.forEach(Emitter::onComplete);
                            subscribers.clear();
                        }
                        closeSilently(channel);
                        isRunning = false;
                    }
                }
        );
    }

    static void closeSilently(Channel channel) {
        try {
            channel.close();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(LOG_TAG).log(Level.WARNING, "error closing channel: " + e.getMessage());
        }
    }

    @SuppressWarnings("InfiniteLoopStatement")
    private void listen() {
        try {
            while (true) {
                SocketAddress address = channel.receive(buffer);
                buffer.flip();
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                Packet packet = Packet.builder()
                        .data(data)
                        .from(address)
                        .build();
                buffer.clear();
                synchronized (subscribers) {
                    subscribers.forEach(e -> e.onNext(packet));
                }
            }
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger(LOG_TAG).log(Level.SEVERE, ex.getMessage());
            synchronized (subscribers) {
                subscribers.forEach(e -> e.onError(new RxUdpException("processing exception", ex)));
            }
        } finally {
            close().blockingAwait();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private final Map<SocketOption, Object> socketOptions = new HashMap<>();

        private SocketAddress address;
        private Scheduler scheduler;
        private ProtocolFamily protocolFamily = StandardProtocolFamily.INET;

        Builder() {

        }

        public <T> Builder socketOption(@NotNull SocketOption<T> option, @Nullable T value) {
            Objects.requireNonNull(option);
            socketOptions.put(option, value);
            return this;
        }

        public Builder scheduler(@NotNull Scheduler scheduler) {
            Objects.requireNonNull(scheduler);
            this.scheduler = scheduler;
            return this;
        }

        public Builder socketAddress(@NotNull SocketAddress address) {
            Objects.requireNonNull(address);
            this.address = address;
            return this;
        }

        public Builder protocolFamily(@NotNull ProtocolFamily protocolFamily) {
            Objects.requireNonNull(protocolFamily);
            this.protocolFamily = protocolFamily;
            return this;
        }

        @SuppressWarnings("unchecked")
        public RxUdpNode build() {
            DatagramChannel channel;
            try {
                channel = DatagramChannel.open(protocolFamily);
            } catch (Exception e) {
                java.util.logging.Logger.getLogger(LOG_TAG).log(Level.SEVERE, "failed to open datagram channel " + e.getMessage());
                throw new RxUdpException("failed to open datagram channel", e);
            }

            if (address != null) {
                try {
                    channel.bind(address);
                } catch (Exception e) {
                    java.util.logging.Logger.getLogger(LOG_TAG).log(Level.SEVERE, "failed to bind to address " + e.getMessage());
                    closeSilently(channel);
                    throw new RxUdpException("failed to bind to address", e);
                }
            }

            for (Map.Entry<SocketOption, Object> entry : socketOptions.entrySet()) {
                try {
                    channel.setOption(entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    java.util.logging.Logger.getLogger(LOG_TAG).log(Level.SEVERE, String.format("failed to set socket option %s to value %s %s %s", entry.getKey(), entry.getValue(), address, e.getMessage()));
                    closeSilently(channel);
                    throw new RxUdpException("failed to set socket option", e);
                }
            }

            if (scheduler != null) {
                return new RxUdpNode(channel, scheduler);
            } else {
                return new RxUdpNode(channel);
            }
        }

    }


}
