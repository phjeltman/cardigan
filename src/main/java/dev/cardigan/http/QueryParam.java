// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Binds an integer handler argument from the URI query component. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface QueryParam {
    String value();
    int defaultValue() default 0;
}
