// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

/**
 * Connects reactor epochs to an application runtime without exposing how its
 * work is represented or suspended.
 */
interface ApplicationLane extends ApplicationDispatcher {
    long tailSnapshot();

    boolean sealRange(long producerTail);

    boolean sealDeferredRange();

    void beginEpoch();

    boolean hasDeferredWork();

    int pendingRanges();
}
