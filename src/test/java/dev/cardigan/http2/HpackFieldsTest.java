// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HpackFieldsTest {
    @Test
    void storesFieldMetadataAndHeaderListAccounting() {
        HpackFields fields = new HpackFields(2);

        assertTrue(fields.add(
            11, 4, HpackFields.NAME_PATH,
            29, 7, 128));

        assertEquals(1, fields.count());
        assertEquals(43, fields.headerListSize());
        assertEquals(11, fields.nameOffset(0));
        assertEquals(4, fields.nameLength(0));
        assertEquals(HpackFields.NAME_PATH, fields.nameIndex(0));
        assertEquals(29, fields.valueOffset(0));
        assertEquals(7, fields.valueLength(0));
        assertEquals(HpackFields.SOURCE_OUTPUT, fields.nameSource(0));
        assertEquals(11, fields.nameReference(0));
        assertEquals(HpackFields.SOURCE_OUTPUT, fields.valueSource(0));
        assertEquals(29, fields.valueReference(0));
    }

    @Test
    void referencedAndMixedFieldsPreserveSourcesThroughMaterialization() {
        HpackFields fields = new HpackFields(3);

        assertTrue(fields.addReferenced(
            HpackFields.SOURCE_STATIC, 17,
            8, HpackFields.NAME_STATUS,
            HpackFields.SOURCE_DYNAMIC, 4,
            3, 256));
        assertEquals(-1, fields.nameOffset(0));
        assertEquals(HpackFields.SOURCE_STATIC, fields.nameSource(0));
        assertEquals(17, fields.nameReference(0));
        assertEquals(-1, fields.valueOffset(0));
        assertEquals(HpackFields.SOURCE_DYNAMIC, fields.valueSource(0));
        assertEquals(4, fields.valueReference(0));

        fields.materializeName(0, 41);
        assertEquals(41, fields.nameOffset(0));
        assertEquals(HpackFields.SOURCE_OUTPUT, fields.nameSource(0));
        assertEquals(HpackFields.SOURCE_DYNAMIC, fields.valueSource(0));

        fields.materializeValue(0, 53);
        assertEquals(53, fields.valueOffset(0));
        assertEquals(HpackFields.SOURCE_OUTPUT, fields.valueSource(0));
        assertEquals(8, fields.nameLength(0));
        assertEquals(3, fields.valueLength(0));
        assertEquals(HpackFields.NAME_STATUS, fields.nameIndex(0));

        assertTrue(fields.addMixed(
            HpackFields.SOURCE_OUTPUT, 7, 99,
            5, HpackFields.NAME_LITERAL,
            HpackFields.SOURCE_STATIC, 13, 23,
            6, 256));
        assertEquals(HpackFields.SOURCE_OUTPUT, fields.nameSource(1));
        assertEquals(7, fields.nameReference(1));
        assertEquals(HpackFields.SOURCE_STATIC, fields.valueSource(1));
        assertEquals(23, fields.valueReference(1));

        assertTrue(fields.addMixed(
            HpackFields.SOURCE_DYNAMIC, 101, 9,
            4, HpackFields.NAME_CONTENT_LENGTH,
            HpackFields.SOURCE_OUTPUT, 31, 103,
            2, 256));
        assertEquals(HpackFields.SOURCE_DYNAMIC, fields.nameSource(2));
        assertEquals(9, fields.nameReference(2));
        assertEquals(HpackFields.SOURCE_OUTPUT, fields.valueSource(2));
        assertEquals(31, fields.valueReference(2));
    }

    @Test
    void enforcesCapacityAndHeaderListLimitThenResetsForReuse() {
        HpackFields fields = new HpackFields(1);

        assertTrue(fields.add(0, 1, 0, 1, 2, 35));
        assertFalse(fields.add(3, 1, 0, 4, 1, 128));
        assertEquals(1, fields.count());
        assertEquals(35, fields.headerListSize());

        fields.reset();
        assertEquals(0, fields.count());
        assertEquals(0, fields.headerListSize());
        assertFalse(fields.add(0, 2, 0, 2, 2, 35));
        assertEquals(0, fields.count());
        assertEquals(0, fields.headerListSize());
        assertTrue(fields.add(5, 1, 0, 7, 2, 35));
        assertEquals(5, fields.nameOffset(0));
        assertEquals(7, fields.valueOffset(0));
    }

    @Test
    void rejectsInvalidAndOverflowingCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new HpackFields(0));
        assertThrows(
            IllegalArgumentException.class,
            () -> new HpackFields(Integer.MAX_VALUE / 5 + 1));
    }
}
