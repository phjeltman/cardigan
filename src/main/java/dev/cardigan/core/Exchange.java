// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import dev.cardigan.http.PreparedInvocation;
import dev.cardigan.http.Response;

/**
 * A protocol-neutral request execution identified by its protocol-assigned
 * exchange ID. Protocol sessions decide how completed responses are ordered
 * and framed.
 */
final class Exchange implements Runnable {
    @FunctionalInterface
    interface Completion {
        void complete(Exchange exchange, Response response);
    }

    private long id;
    private final PreparedInvocation invocation = new PreparedInvocation();
    private boolean keepAlive;
    private final Completion completion;

    Exchange(Completion completion) {
        this.completion = completion;
    }

    void prepare(long id, boolean keepAlive) {
        this.id = id;
        this.keepAlive = keepAlive;
    }

    PreparedInvocation invocation() {
        return invocation;
    }

    long id() {
        return id;
    }

    boolean keepAlive() {
        return keepAlive;
    }

    @Override
    public void run() {
        completion.complete(this, invocation.invoke());
    }
}
