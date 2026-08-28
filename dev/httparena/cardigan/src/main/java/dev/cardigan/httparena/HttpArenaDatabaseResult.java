// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.httparena;

import dev.cardigan.http.EncodedBody;
import org.postgresql.client.core.PgResultStream;
import org.postgresql.client.core.Row;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Materialized query rows encoded directly into Cardigan response storage. */
final class HttpArenaDatabaseResult {
    private static final byte[] ROOT_PREFIX = ascii("{\"items\":[");
    private static final byte[] ID = ascii("{\"id\":");
    private static final byte[] NAME = ascii(",\"name\":");
    private static final byte[] CATEGORY = ascii(",\"category\":");
    private static final byte[] PRICE = ascii(",\"price\":");
    private static final byte[] QUANTITY = ascii(",\"quantity\":");
    private static final byte[] ACTIVE = ascii(",\"active\":");
    private static final byte[] TAGS = ascii(",\"tags\":");
    private static final byte[] RATING_SCORE =
        ascii(",\"rating\":{\"score\":");
    private static final byte[] RATING_COUNT = ascii(",\"count\":");
    private static final byte[] ITEM_SUFFIX = ascii("}}");
    private static final byte[] ROOT_COUNT = ascii("],\"count\":");
    private static final byte[] TRUE = ascii("true");
    private static final byte[] FALSE = ascii("false");
    private static final HttpArenaDatabaseResult EMPTY =
        new HttpArenaDatabaseResult(new Item[0]);

    private final Item[] items;
    private final int encodedLength;

    private HttpArenaDatabaseResult(Item[] items) {
        this.items = items;
        int length = ROOT_PREFIX.length + ROOT_COUNT.length
            + digits(items.length) + 1;
        for (int index = 0; index < items.length; index++) {
            Item item = items[index];
            if (index != 0) {
                length++;
            }
            length += ID.length + digits(item.id);
            length += NAME.length + item.name.length;
            length += CATEGORY.length + item.category.length;
            length += PRICE.length + digits(item.price);
            length += QUANTITY.length + digits(item.quantity);
            length += ACTIVE.length + (item.active ? TRUE.length : FALSE.length);
            length += TAGS.length + item.tags.length;
            length += RATING_SCORE.length + digits(item.ratingScore);
            length += RATING_COUNT.length + digits(item.ratingCount);
            length += ITEM_SUFFIX.length;
        }
        encodedLength = length;
    }

    static HttpArenaDatabaseResult read(PgResultStream rows) {
        List<Item> items = new ArrayList<>(50);
        while (rows.next()) {
            Row row = rows.currentRow();
            items.add(new Item(
                row.getIntPrimitive(1),
                jsonString(required(row.getString(2), "name")),
                jsonString(required(row.getString(3), "category")),
                row.getIntPrimitive(4),
                row.getIntPrimitive(5),
                row.getBooleanPrimitive(6),
                required(row.getString(7), "tags")
                    .getBytes(StandardCharsets.UTF_8),
                row.getIntPrimitive(8),
                row.getIntPrimitive(9)
            ));
        }
        return items.isEmpty()
            ? EMPTY
            : new HttpArenaDatabaseResult(items.toArray(Item[]::new));
    }

    static HttpArenaDatabaseResult of(List<ItemData> items) {
        if (items.isEmpty()) {
            return EMPTY;
        }
        Item[] encoded = new Item[items.size()];
        for (int index = 0; index < items.size(); index++) {
            ItemData item = items.get(index);
            encoded[index] = new Item(
                item.id(), jsonString(item.name()),
                jsonString(item.category()), item.price(),
                item.quantity(), item.active(),
                item.tagsJson().getBytes(StandardCharsets.UTF_8),
                item.ratingScore(), item.ratingCount());
        }
        return new HttpArenaDatabaseResult(encoded);
    }

    static HttpArenaDatabaseResult empty() {
        return EMPTY;
    }

    int count() {
        return items.length;
    }

    EncodedBody encodedBody() {
        return EncodedBody.of(encodedLength, this::encode);
    }

    private int encode(MemorySegment output) {
        int offset = copy(ROOT_PREFIX, output, 0);
        for (int index = 0; index < items.length; index++) {
            Item item = items[index];
            if (index != 0) {
                output.set(ValueLayout.JAVA_BYTE, offset++, (byte) ',');
            }
            offset = copy(ID, output, offset);
            offset = writeInt(output, offset, item.id);
            offset = copy(NAME, output, offset);
            offset = copy(item.name, output, offset);
            offset = copy(CATEGORY, output, offset);
            offset = copy(item.category, output, offset);
            offset = copy(PRICE, output, offset);
            offset = writeInt(output, offset, item.price);
            offset = copy(QUANTITY, output, offset);
            offset = writeInt(output, offset, item.quantity);
            offset = copy(ACTIVE, output, offset);
            offset = copy(item.active ? TRUE : FALSE, output, offset);
            offset = copy(TAGS, output, offset);
            offset = copy(item.tags, output, offset);
            offset = copy(RATING_SCORE, output, offset);
            offset = writeInt(output, offset, item.ratingScore);
            offset = copy(RATING_COUNT, output, offset);
            offset = writeInt(output, offset, item.ratingCount);
            offset = copy(ITEM_SUFFIX, output, offset);
        }
        offset = copy(ROOT_COUNT, output, offset);
        offset = writeInt(output, offset, items.length);
        output.set(ValueLayout.JAVA_BYTE, offset++, (byte) '}');
        if (offset != encodedLength) {
            throw new IllegalStateException("Incorrect DB response length");
        }
        return offset;
    }

    private static byte[] jsonString(String value) {
        StringBuilder encoded = new StringBuilder(value.length() + 2);
        encoded.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> encoded.append("\\\"");
                case '\\' -> encoded.append("\\\\");
                case '\b' -> encoded.append("\\b");
                case '\f' -> encoded.append("\\f");
                case '\n' -> encoded.append("\\n");
                case '\r' -> encoded.append("\\r");
                case '\t' -> encoded.append("\\t");
                default -> {
                    if (current < 0x20) {
                        encoded.append("\\u00")
                            .append(Character.forDigit(current >>> 4, 16))
                            .append(Character.forDigit(current & 0xf, 16));
                    } else {
                        encoded.append(current);
                    }
                }
            }
        }
        return encoded.append('"').toString()
            .getBytes(StandardCharsets.UTF_8);
    }

    private static int copy(
            byte[] source, MemorySegment output, int offset) {
        MemorySegment.copy(
            source, 0, output, ValueLayout.JAVA_BYTE,
            offset, source.length);
        return offset + source.length;
    }

    private static int writeInt(
            MemorySegment output, int offset, int value) {
        int end = offset + digits(value);
        int cursor = end;
        boolean negative = value < 0;
        do {
            int remainder = value % 10;
            output.set(
                ValueLayout.JAVA_BYTE, --cursor,
                (byte) ('0' + Math.abs(remainder)));
            value /= 10;
        } while (value != 0);
        if (negative) {
            output.set(ValueLayout.JAVA_BYTE, offset, (byte) '-');
        }
        return end;
    }

    private static int digits(int value) {
        int result = value < 0 ? 2 : 1;
        while (value <= -10 || value >= 10) {
            value /= 10;
            result++;
        }
        return result;
    }

    private static String required(String value, String column) {
        return Objects.requireNonNull(value, column + " must not be null");
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    record ItemData(
        int id,
        String name,
        String category,
        int price,
        int quantity,
        boolean active,
        String tagsJson,
        int ratingScore,
        int ratingCount
    ) {
        ItemData {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(tagsJson, "tagsJson");
        }
    }

    private static final class Item {
        private final int id;
        private final byte[] name;
        private final byte[] category;
        private final int price;
        private final int quantity;
        private final boolean active;
        private final byte[] tags;
        private final int ratingScore;
        private final int ratingCount;

        private Item(
                int id,
                byte[] name,
                byte[] category,
                int price,
                int quantity,
                boolean active,
                byte[] tags,
                int ratingScore,
                int ratingCount) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.price = price;
            this.quantity = quantity;
            this.active = active;
            this.tags = tags;
            this.ratingScore = ratingScore;
            this.ratingCount = ratingCount;
        }
    }
}
