/*
 * Copyright 2018-2025 The simdjson authors
 * Copyright 2026 dev.cardigan Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.cardigan.simdjson;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class NibbleTest {
    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

    private static final byte[] HIGH_ARR = new byte[SPECIES.length()];
    private static final byte[] LOW_ARR = new byte[SPECIES.length()];

    static {
        HIGH_ARR[2] = 0x01; // comma ','
        HIGH_ARR[3] = 0x02; // colon ':'
        HIGH_ARR[5] = 0x04; // '[' and ']'
        HIGH_ARR[7] = 0x08; // '{' and '}'

        LOW_ARR[12] = 0x01; // ',' (0x2C)
        LOW_ARR[10] = 0x02; // ':' (0x3A)
        LOW_ARR[11] = 0x0C; // '[' (0x5B) and '{' (0x7B)
        LOW_ARR[13] = 0x0C; // ']' (0x5D) and '}' (0x7D)
    }

    private static final ByteVector HIGH_TABLE = ByteVector.fromArray(SPECIES, HIGH_ARR, 0);
    private static final ByteVector LOW_TABLE = ByteVector.fromArray(SPECIES, LOW_ARR, 0);

    @Test
    public void testNibbleLookup() {
        String testStr = "{\"key\": [10, 20], \"val\": true}";
        byte[] bytes = testStr.getBytes(StandardCharsets.UTF_8);
        byte[] padded = Arrays.copyOf(bytes, 64);
        MemorySegment seg = MemorySegment.ofArray(padded);

        ByteVector v = ByteVector.fromMemorySegment(SPECIES, seg, 0, ByteOrder.nativeOrder());

        // Fast nibble lookup
        ByteVector lowNibbles = v.and((byte) 0x0F);
        ByteVector highNibbles = v.lanewise(VectorOperators.LSHR, 4).and((byte) 0x0F);

        ByteVector h = highNibbles.selectFrom(HIGH_TABLE);
        ByteVector l = lowNibbles.selectFrom(LOW_TABLE);

        VectorMask<Byte> fastMask = h.and(l).compare(VectorOperators.NE, (byte) 0);

        // Slow 6-eq mask
        VectorMask<Byte> slowMask = v.eq((byte)'{').or(v.eq((byte)'}')).or(v.eq((byte)'['))
                                     .or(v.eq((byte)']')).or(v.eq((byte)':')).or(v.eq((byte)','));

        assertEquals(slowMask.toLong(), fastMask.toLong(), "Nibble lookup mask mismatch");
    }
}

