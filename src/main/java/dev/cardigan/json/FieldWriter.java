// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.json;

import java.lang.foreign.MemorySegment;

@FunctionalInterface
public interface FieldWriter {
    long write(MemorySegment segment, long offset, Record record) throws Throwable;
}
