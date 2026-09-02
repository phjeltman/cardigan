// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.httparena;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpArenaDatabaseIntegrationTest {
    @Test
    @EnabledIfEnvironmentVariable(
        named = "CARDIGAN_TEST_DATABASE_URL", matches = ".+")
    void queriesPostgresThroughPgJava() {
        HttpArenaDatabaseSettings settings =
            HttpArenaDatabaseSettings.fromEnvironment(Map.of(
                "DATABASE_URL",
                System.getenv("CARDIGAN_TEST_DATABASE_URL"),
                "DATABASE_MAX_CONN", "2"
            ));
        try (HttpArenaDatabase database =
                new HttpArenaDatabase(settings)) {
            assertEquals(7, database.query(5, 80, 7).count());
            assertEquals(1, database.idleConnections());
        }
    }

}
