// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.tls;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KtlsKernelStatsTest {
    @Test
    void separatesSoftwareDeviceAndRekeyCounters() {
        KtlsKernelStats stats = KtlsKernelStats.parse(List.of(
            "TlsTxSw 10",
            "TlsRxSw\t20",
            "TlsTxDevice 3",
            "TlsRxDevice 4",
            "TlsTxRekeyOk 7",
            "TlsRxRekeyOk 8",
            "TlsTxRekeyError 1",
            "TlsRxRekeyError 2",
            "FutureCounter 99"
        ));

        assertTrue(stats.available());
        assertEquals(10, stats.txSoftware());
        assertEquals(20, stats.rxSoftware());
        assertEquals(3, stats.txDevice());
        assertEquals(4, stats.rxDevice());
        assertEquals(7, stats.txRekeyOk());
        assertEquals(8, stats.rxRekeyOk());
        assertEquals(1, stats.txRekeyError());
        assertEquals(2, stats.rxRekeyError());
    }

    @Test
    void reportsNonNegativeRunDelta() {
        KtlsKernelStats before = KtlsKernelStats.parse(List.of(
            "TlsTxSw 10", "TlsRxSw 10"));
        KtlsKernelStats after = KtlsKernelStats.parse(List.of(
            "TlsTxSw 12", "TlsRxSw 9"));

        KtlsKernelStats delta = after.since(before);
        assertEquals(2, delta.txSoftware());
        assertEquals(0, delta.rxSoftware());
    }
}
