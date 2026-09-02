// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.httparena;

import org.junit.jupiter.api.Test;
import org.postgresql.client.core.PgConnectionConfig;
import org.postgresql.client.core.PgConfigurationException;
import org.postgresql.client.core.SslMode;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpArenaDatabaseSettingsTest {
    @Test
    void readsHttpArenaEnvironmentAndPostgresUri() {
        HttpArenaDatabaseSettings settings =
            HttpArenaDatabaseSettings.fromEnvironment(Map.of(
                "DATABASE_URL",
                "postgresql://bench%2Buser:p%40ss@localhost:5544/benchmark"
                    + "?sslmode=disable",
                "DATABASE_MAX_CONN", "17"
            ));

        PgConnectionConfig config = settings.connectionConfig();
        assertEquals("localhost", config.firstHost().host());
        assertEquals(5544, config.firstHost().port());
        assertEquals("benchmark", config.database());
        assertEquals("bench+user", config.user());
        assertArrayEquals("p@ss".toCharArray(), config.password().chars());
        assertEquals(SslMode.DISABLE, config.sslMode());
        assertEquals(17, settings.maxConnections());
        config.password().close();
    }

    @Test
    void usesDefaultPortAndPoolLimit() {
        HttpArenaDatabaseSettings settings =
            HttpArenaDatabaseSettings.fromEnvironment(Map.of(
                "DATABASE_URL",
                "postgres://bench:bench@db/benchmark"
            ));

        assertEquals(5432, settings.connectionConfig().firstHost().port());
        assertEquals(256, settings.maxConnections());
        settings.connectionConfig().password().close();
    }

    @Test
    void requiresDatabaseUrlAndPositivePoolLimit() {
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpArenaDatabaseSettings.fromEnvironment(Map.of()));
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpArenaDatabaseSettings.fromEnvironment(Map.of(
                "DATABASE_URL", "postgres://bench:bench@db/benchmark",
                "DATABASE_MAX_CONN", "0")));
    }

    @Test
    void delegatesConnectionStringValidationToPgJava() {
        assertThrows(
            PgConfigurationException.class,
            () -> HttpArenaDatabaseSettings.fromEnvironment(Map.of(
                "DATABASE_URL", "http://bench:bench@db/benchmark")));
    }
}
