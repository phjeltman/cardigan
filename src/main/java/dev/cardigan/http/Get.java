// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Get {
    String value();
}
