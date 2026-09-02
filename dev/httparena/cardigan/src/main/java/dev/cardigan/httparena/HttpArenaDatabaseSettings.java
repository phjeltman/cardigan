// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.httparena;

import org.postgresql.client.core.PgConnectionConfig;
import org.postgresql.client.core.internal.jdbc.ConnectionStringParser;

import java.util.Map;

/** PostgreSQL settings supplied by the HttpArena runner. */
record HttpArenaDatabaseSettings(
        PgConnectionConfig connectionConfig,
        int maxConnections) {
    private static final int DEFAULT_MAX_CONNECTIONS = 256;

    static HttpArenaDatabaseSettings fromEnvironment(
            Map<String, String> environment) {
        String databaseUrl = environment.get("DATABASE_URL");
        if (databaseUrl == null) {
            throw new IllegalArgumentException("DATABASE_URL is required");
        }
        int maxConnections = parseMaxConnections(
            environment.get("DATABASE_MAX_CONN"));
        return new HttpArenaDatabaseSettings(
            ConnectionStringParser.parse(
                databaseUrl,
                Map.of("PGAPPNAME", "cardigan-httparena")),
            maxConnections);
    }

    private static int parseMaxConnections(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_CONNECTIONS;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                "DATABASE_MAX_CONN must be an integer", error);
        }
        if (parsed < 1) {
            throw new IllegalArgumentException(
                "DATABASE_MAX_CONN must be positive");
        }
        return parsed;
    }
}
