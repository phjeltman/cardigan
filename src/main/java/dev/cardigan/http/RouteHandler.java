// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

@FunctionalInterface
public interface RouteHandler {
    Response handle(HttpRequest request, long pathParamLong, Object bodyRecord) throws Throwable;
}
