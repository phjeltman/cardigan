// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

/**
 * Process-wide admission statistics for {@link Isolated} routes.
 */
public record IsolatedRouteStats(
        int limit,
        int active,
        long admitted,
        long cancelled,
        long rejected) {

    public static IsolatedRouteStats snapshot() {
        return IsolatedRouteExecutor.stats();
    }
}
