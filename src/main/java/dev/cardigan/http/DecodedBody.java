// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a complete buffered request body to one primitive {@code long} route
 * argument. The decoder runs on the connection owner before exchange
 * handover, so it must be bounded, non-blocking, and must not retain the
 * supplied memory segment.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface DecodedBody {
    Class<? extends LongBodyDecoder> value();
}
