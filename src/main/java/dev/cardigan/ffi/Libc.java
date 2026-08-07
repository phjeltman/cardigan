// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.ffi;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public class Libc {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = LINKER.defaultLookup();

    public static final MethodHandle socket;
    public static final MethodHandle bind;
    public static final MethodHandle listen;
    public static final MethodHandle setsockopt;
    public static final MethodHandle shutdown;
    public static final MethodHandle close;
    public static final MethodHandle pthread_self;
    public static final MethodHandle pthread_getaffinity_np;
    public static final MethodHandle pthread_setaffinity_np;
    public static final MethodHandle eventfd;
    public static final MethodHandle write;
    private static final MethodHandle errnoLocation;

    static {
        try {
            socket = LINKER.downcallHandle(
                LOOKUP.find("socket").orElseThrow(() -> new LinkError("socket")),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,      // return fd
                    ValueLayout.JAVA_INT,      // domain
                    ValueLayout.JAVA_INT,      // type
                    ValueLayout.JAVA_INT       // protocol
                )
            );

            bind = LINKER.downcallHandle(
                LOOKUP.find("bind").orElseThrow(() -> new LinkError("bind")),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,      // return status
                    ValueLayout.JAVA_INT,      // sockfd
                    ValueLayout.ADDRESS,       // sockaddr *addr
                    ValueLayout.JAVA_INT       // socklen_t addrlen
                )
            );

            listen = LINKER.downcallHandle(
                LOOKUP.find("listen").orElseThrow(() -> new LinkError("listen")),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,      // return status
                    ValueLayout.JAVA_INT,      // sockfd
                    ValueLayout.JAVA_INT       // backlog
                )
            );

            setsockopt = LINKER.downcallHandle(
                LOOKUP.find("setsockopt").orElseThrow(() -> new LinkError("setsockopt")),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,      // return status
                    ValueLayout.JAVA_INT,      // sockfd
                    ValueLayout.JAVA_INT,      // level
                    ValueLayout.JAVA_INT,      // optname
                    ValueLayout.ADDRESS,       // optval
                    ValueLayout.JAVA_INT       // optlen
                )
            );

            shutdown = LINKER.downcallHandle(
                LOOKUP.find("shutdown").orElseThrow(
                    () -> new LinkError("shutdown")),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT
                )
            );

            close = LINKER.downcallHandle(
                LOOKUP.find("close").orElseThrow(() -> new LinkError("close")),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,      // return status
                    ValueLayout.JAVA_INT       // fd
                )
            );

            pthread_self = LINKER.downcallHandle(
                LOOKUP.find("pthread_self").orElseThrow(() -> new LinkError("pthread_self")),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG      // returns pthread_t (unsigned long)
                )
            );

            pthread_setaffinity_np = LINKER.downcallHandle(
                LOOKUP.find("pthread_setaffinity_np").orElseThrow(() -> new LinkError("pthread_setaffinity_np")),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,      // return status
                    ValueLayout.JAVA_LONG,     // pthread_t thread
                    ValueLayout.JAVA_LONG,     // size_t cpusetsize
                    ValueLayout.ADDRESS        // const cpu_set_t *cpuset
                )
            );

            pthread_getaffinity_np = LINKER.downcallHandle(
                LOOKUP.find("pthread_getaffinity_np").orElseThrow(() -> new LinkError("pthread_getaffinity_np")),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,      // return status
                    ValueLayout.JAVA_LONG,     // pthread_t thread
                    ValueLayout.JAVA_LONG,     // size_t cpusetsize
                    ValueLayout.ADDRESS        // cpu_set_t *cpuset
                )
            );

            eventfd = LINKER.downcallHandle(
                LOOKUP.find("eventfd").orElseThrow(() -> new LinkError("eventfd")),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,      // return fd
                    ValueLayout.JAVA_INT,      // initval
                    ValueLayout.JAVA_INT       // flags
                )
            );

            write = LINKER.downcallHandle(
                LOOKUP.find("write").orElseThrow(() -> new LinkError("write")),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,     // return ssize_t
                    ValueLayout.JAVA_INT,      // fd
                    ValueLayout.ADDRESS,       // buf
                    ValueLayout.JAVA_LONG      // count (size_t)
                )
            );

            errnoLocation = LINKER.downcallHandle(
                LOOKUP.find("__errno_location").orElseThrow(
                    () -> new LinkError("__errno_location")),
                FunctionDescriptor.of(ValueLayout.ADDRESS)
            );

        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static int errno() {
        try {
            MemorySegment location =
                (MemorySegment) errnoLocation.invokeExact();
            return location.reinterpret(ValueLayout.JAVA_INT.byteSize())
                .get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable error) {
            throw new IllegalStateException("Failed to read errno", error);
        }
    }

    private static class LinkError extends RuntimeException {
        public LinkError(String name) {
            super("Failed to resolve libc symbol: " + name);
        }
    }
}
