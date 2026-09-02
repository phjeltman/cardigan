// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.httparena;

import dev.cardigan.core.CarrierLocal;
import org.postgresql.client.core.PgConnection;
import org.postgresql.client.core.PgConnectionConfig;
import org.postgresql.client.core.PgConnections;
import org.postgresql.client.core.PgPreparedStatement;
import org.postgresql.client.core.PgResultStream;
import org.postgresql.client.core.Secret;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Carrier-owned, lazy pools of pg-java connections and prepared queries. */
final class HttpArenaDatabase implements AutoCloseable {
    private static final String QUERY = """
        SELECT id, name, category, price, quantity, active, tags,
               rating_score, rating_count
        FROM items
        WHERE price BETWEEN $1 AND $2
        LIMIT $3
        """;

    private final PgConnectionConfig connectionConfig;
    private final int carrierCount;
    private final int baseCapacity;
    private final int carriersWithExtraConnection;
    private final AtomicInteger nextCarrier = new AtomicInteger();
    private final ConcurrentLinkedQueue<Shard> createdShards =
        new ConcurrentLinkedQueue<>();
    private final CarrierLocal<Shard> localShard;
    private volatile boolean closed;

    HttpArenaDatabase(HttpArenaDatabaseSettings settings) {
        this(settings, 1);
    }

    HttpArenaDatabase(
            HttpArenaDatabaseSettings settings, int carrierCount) {
        if (carrierCount < 1) {
            throw new IllegalArgumentException(
                "Carrier count must be positive");
        }
        if (settings.maxConnections() < carrierCount) {
            throw new IllegalArgumentException(
                "DATABASE_MAX_CONN must be at least the Cardigan carrier "
                    + "count: " + carrierCount);
        }
        connectionConfig = settings.connectionConfig();
        this.carrierCount = carrierCount;
        baseCapacity = settings.maxConnections() / carrierCount;
        carriersWithExtraConnection =
            settings.maxConnections() % carrierCount;
        localShard = CarrierLocal.withInitial(this::createShard);
    }

    HttpArenaDatabaseResult query(int minimum, int maximum, int limit) {
        if (closed) {
            throw new IllegalStateException("Database pool is closed");
        }
        return localShard.get().query(minimum, maximum, limit);
    }

    int idleConnections() {
        int count = 0;
        for (Shard shard : createdShards) {
            count += shard.idleConnections();
        }
        return count;
    }

    private Shard createShard() {
        int index = nextCarrier.getAndIncrement();
        if (index >= carrierCount) {
            throw new IllegalStateException(
                "Database route used more carriers than configured: "
                    + carrierCount);
        }
        int capacity = baseCapacity
            + (index < carriersWithExtraConnection ? 1 : 0);
        Shard shard = new Shard(capacity);
        createdShards.add(shard);
        return shard;
    }

    @Override
    public void close() {
        closed = true;
        for (Shard shard : createdShards) {
            shard.close();
        }
        Secret password = connectionConfig.password();
        if (password != null) {
            password.close();
        }
    }

    private final class Shard implements AutoCloseable {
        private final ArrayDeque<Session> idle;
        private final Semaphore permits;

        private Shard(int capacity) {
            idle = new ArrayDeque<>(capacity);
            permits = new Semaphore(capacity);
        }

        private HttpArenaDatabaseResult query(
                int minimum, int maximum, int limit) {
            Session session = borrow();
            boolean reusable = false;
            try {
                List<Object> parameters = List.of(
                    minimum, maximum, (long) limit);
                HttpArenaDatabaseResult result;
                try (PgResultStream rows =
                        session.statement.execute(parameters)) {
                    result = HttpArenaDatabaseResult.read(rows);
                }
                reusable = !session.connection.isBroken();
                return result;
            } finally {
                if (reusable) {
                    release(session);
                } else {
                    discard(session);
                }
            }
        }

        private Session borrow() {
            try {
                permits.acquire();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "Interrupted while waiting for a database connection",
                    error);
            }
            if (closed) {
                permits.release();
                throw new IllegalStateException("Database pool is closed");
            }

            Session session = idle.pollFirst();
            if (session != null) {
                return session;
            }
            try {
                if (closed) {
                    throw new IllegalStateException(
                        "Database pool is closed");
                }
                PgConnection connection =
                    PgConnections.connect(connectionConfig);
                try {
                    return new Session(
                        connection, connection.prepare(QUERY));
                } catch (Throwable error) {
                    connection.close();
                    throw error;
                }
            } catch (Throwable error) {
                permits.release();
                throw error;
            }
        }

        private void release(Session session) {
            try {
                if (closed) {
                    session.close();
                } else {
                    idle.addLast(session);
                }
            } finally {
                permits.release();
            }
        }

        private void discard(Session session) {
            try {
                session.close();
            } finally {
                permits.release();
            }
        }

        private int idleConnections() {
            return idle.size();
        }

        @Override
        public void close() {
            Session session;
            while ((session = idle.pollFirst()) != null) {
                session.close();
            }
        }
    }

    private record Session(
            PgConnection connection,
            PgPreparedStatement statement) implements AutoCloseable {
        @Override
        public void close() {
            try {
                statement.close();
            } finally {
                connection.close();
            }
        }
    }
}
