// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.Get;
import dev.cardigan.http.HttpRequest;
import dev.cardigan.http.Post;
import dev.cardigan.http.Response;

import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicReference;

public class ParallelController {

    @Get("/p/{id}")
    public Response getParallel(HttpRequest request) {
        AtomicReference<String> thread1Header = new AtomicReference<>("NONE");
        AtomicReference<String> thread2Header = new AtomicReference<>("NONE");

        try (var scope = StructuredTaskScope.open()) {
            scope.fork(() -> {
                if (request != null && request.getHeader("X-Custom-Header") != null) {
                    thread1Header.set(request.getHeader("X-Custom-Header").toString());
                }
                return null;
            });

            scope.fork(() -> {
                if (request != null && request.getHeader("X-Test-Id") != null) {
                    thread2Header.set(request.getHeader("X-Test-Id").toString());
                }
                return null;
            });

            scope.join();
        } catch (Throwable t) {
            return Response.error("Thread error: " + t.getMessage());
        }

        return Response.text("ID:99,H1:" + thread1Header.get() + ",H2:" + thread2Header.get());
    }

    @Post("/jumbo-parallel")
    public Response handleJumboParallel(HttpRequest request) {
        AtomicReference<String> bodySliceStr = new AtomicReference<>("");

        try (var scope = StructuredTaskScope.open()) {
            scope.fork(() -> {
                if (request != null && request.body() != null) {
                    bodySliceStr.set(request.body().toString());
                }
                return null;
            });
            scope.join();
        } catch (Throwable t) {
            return Response.error("Thread error: " + t.getMessage());
        }

        return Response.text("Received " + bodySliceStr.get().length() + " bytes");
    }
}
