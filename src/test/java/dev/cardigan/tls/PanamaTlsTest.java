// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.tls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanamaTlsTest {
    @Test
    void requiresOpenSsl35ForTls13KtlsRekey() {
        assertFalse(PanamaTls.supportsTls13KtlsRekey(0x300000d0L));
        assertFalse(PanamaTls.supportsTls13KtlsRekey(0x30400000L));
        assertTrue(PanamaTls.supportsTls13KtlsRekey(0x30500000L));
        assertTrue(PanamaTls.supportsTls13KtlsRekey(0x40000000L));
    }
}
