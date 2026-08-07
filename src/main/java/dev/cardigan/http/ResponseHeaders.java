// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable response header fields stored as a compact interleaved array.
 * Names are normalized to lowercase so the same representation can be
 * written directly by HTTP/1.1 and HPACK.
 */
public final class ResponseHeaders {
    public static final ResponseHeaders EMPTY =
        new ResponseHeaders(new String[0], 0);
    private static final int MAX_FIELDS = 64;
    private static final int MAX_BYTES = 8 * 1024;

    private final String[] fields;
    private final int bytes;

    private ResponseHeaders(String[] fields, int bytes) {
        this.fields = fields;
        this.bytes = bytes;
    }

    public static ResponseHeaders of(String name, String value) {
        return builder().add(name, value).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int size() {
        return fields.length >>> 1;
    }

    public boolean isEmpty() {
        return fields.length == 0;
    }

    public String name(int index) {
        checkIndex(index);
        return fields[index << 1];
    }

    public String value(int index) {
        checkIndex(index);
        return fields[(index << 1) + 1];
    }

    /** Upper bound for the uncompressed name/value bytes. */
    public int byteSize() {
        return bytes;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException(index);
        }
    }

    public static final class Builder {
        private String[] fields = new String[8];
        private int count;
        private int bytes;

        private Builder() {
        }

        public Builder add(String name, String value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (count == MAX_FIELDS) {
                throw new IllegalArgumentException(
                    "Too many response header fields");
            }

            String normalized = normalizeName(name);
            validateValue(value);
            int nextBytes = bytes + normalized.length() + value.length();
            if (nextBytes > MAX_BYTES) {
                throw new IllegalArgumentException(
                    "Response header fields exceed " + MAX_BYTES + " bytes");
            }
            int offset = count << 1;
            if (offset == fields.length) {
                fields = Arrays.copyOf(fields, fields.length << 1);
            }
            fields[offset] = normalized;
            fields[offset + 1] = value;
            count++;
            bytes = nextBytes;
            return this;
        }

        public Builder addAll(ResponseHeaders source) {
            Objects.requireNonNull(source, "source");
            for (int index = 0; index < source.size(); index++) {
                add(source.name(index), source.value(index));
            }
            return this;
        }

        public ResponseHeaders build() {
            if (count == 0) {
                return EMPTY;
            }
            return new ResponseHeaders(
                Arrays.copyOf(fields, count << 1), bytes);
        }
    }

    private static String normalizeName(String name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException(
                "Response header name must not be empty");
        }
        for (int index = 0; index < name.length(); index++) {
            char current = name.charAt(index);
            if (current > 0x7f || !isToken(current)) {
                throw new IllegalArgumentException(
                    "Invalid response header name: " + name);
            }
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static void validateValue(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current > 0xff
                || (current < 0x20 && current != '\t')
                || current == 0x7f) {
                throw new IllegalArgumentException(
                    "Invalid response header value");
            }
        }
    }

    private static boolean isToken(char value) {
        return value >= '0' && value <= '9'
            || value >= 'a' && value <= 'z'
            || value >= 'A' && value <= 'Z'
            || switch (value) {
                case '!', '#', '$', '%', '&', '\'', '*', '+', '-', '.',
                     '^', '_', '`', '|', '~' -> true;
                default -> false;
            };
    }
}
