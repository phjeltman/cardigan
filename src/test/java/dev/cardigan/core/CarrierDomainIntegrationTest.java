// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
final class CarrierDomainIntegrationTest {
    @Test
    void ringThreadAndMountedVirtualThreadShareOnlyTheirCarrierDomain()
            throws Exception {
        try (UringEventLoop loop = new UringEventLoop(0, 64)) {
            CompletableFuture<Boolean> ringThreadResult =
                new CompletableFuture<>();
            loop.executeProtocol(() -> ringThreadResult.complete(
                loop.inCarrierDomain()));
            assertTrue(ringThreadResult.get(5, TimeUnit.SECONDS));

            CompletableFuture<Boolean> ringVirtualResult =
                new CompletableFuture<>();
            Thread ringVirtual = loop.loomRuntime().startVirtualThread(
                () -> ringVirtualResult.complete(loop.inCarrierDomain()));
            assertTrue(ringVirtualResult.get(5, TimeUnit.SECONDS));
            ringVirtual.join(5_000);

            assertFalse(loop.inCarrierDomain());

            CompletableFuture<Boolean> unrelatedVirtualResult =
                new CompletableFuture<>();
            Thread unrelatedVirtual = Thread.ofVirtual().start(
                () -> unrelatedVirtualResult.complete(
                    loop.inCarrierDomain()));
            assertFalse(unrelatedVirtualResult.get(5, TimeUnit.SECONDS));
            unrelatedVirtual.join(5_000);
        }
    }
}
