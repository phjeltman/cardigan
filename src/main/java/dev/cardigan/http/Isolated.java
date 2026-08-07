// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs a route on a virtual thread whose carrier is isolated from the
 * io_uring event loops.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Isolated {
}
