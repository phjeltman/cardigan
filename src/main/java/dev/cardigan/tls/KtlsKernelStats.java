// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.tls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Linux-global kTLS software/device counters sampled around a server run. */
record KtlsKernelStats(
        boolean available,
        long txSoftware,
        long rxSoftware,
        long txDevice,
        long rxDevice,
        long txRekeyOk,
        long rxRekeyOk,
        long txRekeyError,
        long rxRekeyError) {
    private static final Path STATISTICS = Path.of("/proc/net/tls_stat");
    private static final KtlsKernelStats UNAVAILABLE = new KtlsKernelStats(
        false, 0, 0, 0, 0, 0, 0, 0, 0);

    static KtlsKernelStats read() {
        List<String> lines;
        try {
            lines = Files.readAllLines(STATISTICS);
        } catch (IOException | RuntimeException ignored) {
            return UNAVAILABLE;
        }
        return parse(lines);
    }

    static KtlsKernelStats parse(List<String> lines) {
        long txSoftware = 0;
        long rxSoftware = 0;
        long txDevice = 0;
        long rxDevice = 0;
        long txRekeyOk = 0;
        long rxRekeyOk = 0;
        long txRekeyError = 0;
        long rxRekeyError = 0;
        for (String line : lines) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length != 2) {
                continue;
            }
            long value;
            try {
                value = Long.parseLong(fields[1]);
            } catch (NumberFormatException ignored) {
                continue;
            }
            switch (fields[0]) {
                case "TlsTxSw" -> txSoftware = value;
                case "TlsRxSw" -> rxSoftware = value;
                case "TlsTxDevice" -> txDevice = value;
                case "TlsRxDevice" -> rxDevice = value;
                case "TlsTxRekeyOk" -> txRekeyOk = value;
                case "TlsRxRekeyOk" -> rxRekeyOk = value;
                case "TlsTxRekeyError" -> txRekeyError = value;
                case "TlsRxRekeyError" -> rxRekeyError = value;
                default -> {
                }
            }
        }
        return new KtlsKernelStats(
            true,
            txSoftware,
            rxSoftware,
            txDevice,
            rxDevice,
            txRekeyOk,
            rxRekeyOk,
            txRekeyError,
            rxRekeyError
        );
    }

    KtlsKernelStats since(KtlsKernelStats before) {
        if (!available || !before.available) {
            return UNAVAILABLE;
        }
        return new KtlsKernelStats(
            true,
            nonNegative(txSoftware - before.txSoftware),
            nonNegative(rxSoftware - before.rxSoftware),
            nonNegative(txDevice - before.txDevice),
            nonNegative(rxDevice - before.rxDevice),
            nonNegative(txRekeyOk - before.txRekeyOk),
            nonNegative(rxRekeyOk - before.rxRekeyOk),
            nonNegative(txRekeyError - before.txRekeyError),
            nonNegative(rxRekeyError - before.rxRekeyError)
        );
    }

    String summary() {
        if (!available) {
            return "unavailable";
        }
        return "tx.software=" + txSoftware
            + ", rx.software=" + rxSoftware
            + ", tx.device=" + txDevice
            + ", rx.device=" + rxDevice
            + ", tx.rekey.ok=" + txRekeyOk
            + ", rx.rekey.ok=" + rxRekeyOk
            + ", tx.rekey.error=" + txRekeyError
            + ", rx.rekey.error=" + rxRekeyError;
    }

    private static long nonNegative(long value) {
        return Math.max(0, value);
    }
}
