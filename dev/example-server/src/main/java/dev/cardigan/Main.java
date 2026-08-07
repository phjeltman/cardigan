// SPDX-License-Identifier: MPL-2.0

package dev.cardigan;

import dev.cardigan.core.CardiganServer;
import dev.cardigan.core.ProtocolMode;
import dev.cardigan.example.ExampleController;
import dev.cardigan.ffi.UnsupportedKernelException;

public class Main {
    public static void main(String[] args) {
        int port = 8080;
        int threads = Runtime.getRuntime().availableProcessors();
        ProtocolMode protocolMode = ProtocolMode.HTTP1_AND_HTTP2;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port format. Using default: " + port);
            }
        }
        if (args.length > 1) {
            try {
                threads = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid thread count format. Using default: " + threads);
            }
        }
        if (args.length > 2) {
            protocolMode = ProtocolMode.valueOf(args[2]);
        }
        System.setProperty("jdk.virtualThreadScheduler.parallelism", String.valueOf(threads));
        System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", String.valueOf(threads));

        try (CardiganServer server = CardiganServer.builder()
                .port(port)
                .eventLoops(threads)
                .protocol(protocolMode)
                .tlsFromSystemProperties()
                .routes(new ExampleController())
                .build()) {
            server.start();
            Runtime.getRuntime().addShutdownHook(
                Thread.ofPlatform()
                    .name("cardigan-shutdown")
                    .unstarted(server::close)
            );

            System.out.println("Cardigan Server is running. Press Ctrl+C to terminate.");
            Thread.currentThread().join();
        } catch (UnsupportedKernelException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            System.out.println("Server interrupted.");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Server exception: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
