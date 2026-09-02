// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.httparena;

import dev.cardigan.http.Get;
import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.Response;

/** HttpArena's PostgreSQL-backed range-query endpoint. */
public final class HttpArenaDatabaseController {
    private final Query query;

    HttpArenaDatabaseController(HttpArenaDatabase database) {
        this(database::query);
    }

    HttpArenaDatabaseController(Query query) {
        this.query = query;
    }

    @Get("/async-db")
    public Response asyncDatabase(HttpRequest request) {
        int minimum = request.queryInt("min", 10);
        int maximum = request.queryInt("max", 50);
        int limit = request.queryInt("limit", 50);
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        HttpArenaDatabaseResult result;
        try {
            result = query.execute(minimum, maximum, boundedLimit);
        } catch (RuntimeException error) {
            result = HttpArenaDatabaseResult.empty();
        }
        return Response.encoded("application/json", result.encodedBody());
    }

    @FunctionalInterface
    interface Query {
        HttpArenaDatabaseResult execute(
            int minimum, int maximum, int limit);
    }
}
