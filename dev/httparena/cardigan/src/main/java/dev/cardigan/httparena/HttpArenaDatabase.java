// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.httparena;

import org.postgresql.client.core.PgConnection;
import org.postgresql.client.core.PgConnectionConfig;
import org.postgresql.client.core.PgConnections;
import org.postgresql.client.core.PgPreparedStatement;
import org.postgresql.client.core.PgResultStream;
import org.postgresql.client.core.Secret;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;

/** Bounded, lazy pool of pg-java connections and prepared queries. */
final class HttpArenaDatabase implements AutoCloseable {
    private static final String QUERY = """
        SELECT id, name, category, price, quantity, active, tags,
               rating_score, rating_count
        FROM items
        WHERE price BETWEEN $1 AND $2
        LIMIT $3
        """;

    private final PgConnectionConfig connectionConfig;
    private final ArrayBlockingQueue<Session> idle;
    private final Semaphore permits;
    private volatile boolean closed;

    HttpArenaDatabase(HttpArenaDatabaseSettings settings) {
        connectionConfig = settings.connectionConfig();
        idle = new ArrayBlockingQueue<>(settings.maxConnections());
        permits = new Semaphore(settings.maxConnections());
    }

    HttpArenaDatabaseResult query(int minimum, int maximum, int limit) {
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

    int idleConnections() {
        return idle.size();
    }

    private Session borrow() {
        try {
            permits.acquire();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while waiting for a database connection", error);
        }
        if (closed) {
            permits.release();
            throw new IllegalStateException("Database pool is closed");
        }

        Session session = idle.poll();
        if (session != null) {
            return session;
        }
        try {
            if (closed) {
                throw new IllegalStateException("Database pool is closed");
            }
            PgConnection connection = PgConnections.connect(connectionConfig);
            try {
                return new Session(connection, connection.prepare(QUERY));
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
            if (closed || !idle.offer(session)) {
                session.close();
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

    @Override
    public void close() {
        closed = true;
        Session session;
        while ((session = idle.poll()) != null) {
            session.close();
        }
        Secret password = connectionConfig.password();
        if (password != null) {
            password.close();
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
