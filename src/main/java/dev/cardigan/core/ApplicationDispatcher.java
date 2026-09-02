// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

/** Accepts an application exchange admitted by a protocol reactor. */
@FunctionalInterface
interface ApplicationDispatcher {
    boolean submit(Runnable exchange);
}
