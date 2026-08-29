// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.http;

import dev.cardigan.serdes.Serdes;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import dev.cardigan.ffi.RawSegment;

public class Router {
    public static final int BODY_BUFFERED = 0;
    public static final int BODY_STREAMING = 1;
    public static final int BODY_STREAMING_ISOLATED = 2;


    public static final class FastPrefixRoute {
        public final int methodCode;
        public final int prefixLen;
        public final long prefixMask;
        public final long prefixLong;
        public final Route route;

        public FastPrefixRoute(int methodCode, String prefix, Route route) {
            this.methodCode = methodCode;
            this.prefixLen = prefix.length();
            this.prefixMask = prefixLen == 8 ? -1L : (1L << (prefixLen * 8)) - 1;
            byte[] bytes = prefix.getBytes(StandardCharsets.US_ASCII);
            long word = 0;
            for (int i = 0; i < bytes.length; i++) {
                word |= ((long) (bytes[i] & 0xff)) << (i * 8);
            }
            this.prefixLong = word;
            this.route = route;
        }
    }

    private static final class MutableRouteNode {
        private final Map<String, MutableRouteNode> staticChildren =
            new HashMap<>();
        private MutableRouteNode parameterChild;
        private Route getRoute;
        private Route postRoute;

        private void add(Route route, int segmentIndex) {
            if (segmentIndex == route.segmentCount) {
                if (route.methodCode == 1) {
                    if (getRoute == null) {
                        getRoute = route;
                    }
                } else if (route.methodCode == 2 && postRoute == null) {
                    postRoute = route;
                }
                return;
            }

            MutableRouteNode child;
            if (route.isParam[segmentIndex]) {
                child = parameterChild;
                if (child == null) {
                    child = new MutableRouteNode();
                    parameterChild = child;
                }
            } else {
                child = staticChildren.computeIfAbsent(
                    route.segments[segmentIndex],
                    ignored -> new MutableRouteNode());
            }
            child.add(route, segmentIndex + 1);
        }
    }

    private static final class StaticRouteEdge {
        private final byte[] segment;
        private final int hash;
        private final CompiledRouteNode child;

        private StaticRouteEdge(
                String segment, CompiledRouteNode child) {
            this.segment = segment.getBytes(StandardCharsets.UTF_8);
            this.hash = hashBytes(this.segment);
            this.child = child;
        }

        private boolean matches(long baseAddress, long offset, int length) {
            return segment.length == length
                && matchSegmentBytes(
                    baseAddress, offset, length, segment);
        }
    }

    private static final class CompiledRouteNode {
        private final StaticRouteEdge[] staticChildren;
        private final int staticMask;
        private final CompiledRouteNode parameterChild;
        private final Route getRoute;
        private final Route postRoute;

        private CompiledRouteNode(MutableRouteNode source) {
            int childCount = source.staticChildren.size();
            if (childCount == 0) {
                staticChildren = new StaticRouteEdge[0];
                staticMask = 0;
            } else {
                int capacity = 2;
                while (capacity < childCount * 2) {
                    capacity <<= 1;
                }
                staticChildren = new StaticRouteEdge[capacity];
                staticMask = capacity - 1;
                for (Map.Entry<String, MutableRouteNode> entry
                        : source.staticChildren.entrySet()) {
                    StaticRouteEdge edge = new StaticRouteEdge(
                        entry.getKey(),
                        new CompiledRouteNode(entry.getValue()));
                    int slot = spread(edge.hash) & staticMask;
                    while (staticChildren[slot] != null) {
                        slot = (slot + 1) & staticMask;
                    }
                    staticChildren[slot] = edge;
                }
            }
            parameterChild = source.parameterChild == null
                ? null : new CompiledRouteNode(source.parameterChild);
            getRoute = source.getRoute;
            postRoute = source.postRoute;
        }

        private Route find(
                long baseAddress, long[] segments, int segmentCount,
                int methodCode) {
            return findFrom(
                baseAddress, segments, segmentCount, methodCode, 0);
        }

        private Route findFrom(
                long baseAddress, long[] segments, int segmentCount,
                int methodCode, int segmentIndex) {
            if (segmentIndex == segmentCount) {
                return methodCode == 1
                    ? getRoute
                    : methodCode == 2 ? postRoute : null;
            }

            long packed = segments[segmentIndex];
            long offset = packed >>> 32;
            int length = (int) packed;
            CompiledRouteNode staticChild = findStaticChild(
                baseAddress, offset, length);
            if (staticChild != null) {
                Route route = staticChild.findFrom(
                    baseAddress, segments, segmentCount,
                    methodCode, segmentIndex + 1);
                if (route != null) {
                    return route;
                }
            }
            return parameterChild == null
                ? null
                : parameterChild.findFrom(
                    baseAddress, segments, segmentCount,
                    methodCode, segmentIndex + 1);
        }

        private CompiledRouteNode findStaticChild(
                long baseAddress, long offset, int length) {
            if (staticChildren.length == 0) {
                return null;
            }
            int hash = hashSegment(baseAddress, offset, length);
            int slot = spread(hash) & staticMask;
            StaticRouteEdge edge;
            while ((edge = staticChildren[slot]) != null) {
                if (edge.hash == hash
                        && edge.matches(baseAddress, offset, length)) {
                    return edge.child;
                }
                slot = (slot + 1) & staticMask;
            }
            return null;
        }
    }

    private final MutableRouteNode mutableRouteRoot = new MutableRouteNode();
    private volatile CompiledRouteNode compiledRouteRoot;
    private volatile boolean routeTreeDirty = true;
    private volatile FastPrefixRoute[] fastPrefixRoutes = new FastPrefixRoute[0];

    public synchronized void registerController(Object controller) {
        for (Method method : controller.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(Get.class)) {
                Get get = method.getAnnotation(Get.class);
                registerRoute("GET", get.value(), method, controller);
            } else if (method.isAnnotationPresent(Post.class)) {
                Post post = method.getAnnotation(Post.class);
                registerRoute("POST", post.value(), method, controller);
            }
        }
    }

    private synchronized void registerRoute(String httpMethod, String pattern, Method method, Object controller) {
        String[] parts = pattern.split("/");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            if (!p.isEmpty()) {
                list.add(p);
            }
        }

        String[] segments = list.toArray(new String[0]);
        boolean[] isParam = new boolean[segments.length];
        String[] paramNames = new String[segments.length];

        for (int i = 0; i < segments.length; i++) {
            String s = segments[i];
            if (s.startsWith("{") && s.endsWith("}")) {
                isParam[i] = true;
                paramNames[i] = s.substring(1, s.length() - 1);
            } else {
                isParam[i] = false;
            }
        }

        Class<?>[] parameterTypes = method.getParameterTypes();
        int paramCount = 0;
        for (boolean b : isParam) {
            if (b) paramCount++;
        }
        int[] paramIndexMap = new int[paramCount];
        int mapIdx = 0;
        for (int i = 0; i < isParam.length; i++) {
            if (isParam[i]) {
                paramIndexMap[mapIdx++] = i;
            }
        }

        java.lang.invoke.MethodHandle mh = null;
        RouteHandler handler = null;
        try {
            method.setAccessible(true);
            mh = java.lang.invoke.MethodHandles.lookup().unreflect(method).bindTo(controller);
            handler = compileLambda(method, controller);
        } catch (Throwable t) {
            t.printStackTrace();
        }

        boolean isBodyRecord = false;
        Class<? extends Record> bodyRecordClass = null;
        for (Class<?> ptype : parameterTypes) {
            if (Record.class.isAssignableFrom(ptype)) {
                isBodyRecord = true;
                bodyRecordClass = (Class<? extends Record>) ptype;
                break;
            }
        }

        Route newRoute = new Route(httpMethod, pattern, segments, isParam, paramNames, method, mh, handler, controller, parameterTypes, paramIndexMap, isBodyRecord, bodyRecordClass);
        if (newRoute.isStreamingBody && newRoute.methodCode == 1) {
            throw new IllegalArgumentException(
                "Request-body streaming is only supported on unsafe routes: "
                    + method);
        }
        if (newRoute.longBodyDecoder != null
            && (newRoute.methodCode == 1
                || newRoute.paramIndexMap.length != 0)) {
            throw new IllegalArgumentException(
                "@DecodedBody requires an unsafe route without path "
                    + "parameters: " + method);
        }
        mutableRouteRoot.add(newRoute, 0);
        routeTreeDirty = true;

        if (paramCount == 1 && isParam[segments.length - 1]
                && !newRoute.bindsArguments) {
            int braceIdx = pattern.indexOf('{');
            if (braceIdx > 0 && braceIdx <= 8) {
                String prefix = pattern.substring(0, braceIdx);
                int mCode = "GET".equalsIgnoreCase(httpMethod) ? 1 : ("POST".equalsIgnoreCase(httpMethod) ? 2 : 0);
                FastPrefixRoute fpr = new FastPrefixRoute(mCode, prefix, newRoute);
                List<FastPrefixRoute> fprList = new ArrayList<>(List.of(fastPrefixRoutes));
                fprList.add(fpr);
                this.fastPrefixRoutes = fprList.toArray(new FastPrefixRoute[0]);
            }
        }

        System.out.println("Registered Route: [" + httpMethod + "] " + pattern + " -> " + method.getDeclaringClass().getSimpleName() + "." + method.getName());
    }

    private CompiledRouteNode compiledRoutes() {
        CompiledRouteNode routes = compiledRouteRoot;
        if (!routeTreeDirty && routes != null) {
            return routes;
        }
        synchronized (this) {
            if (routeTreeDirty || compiledRouteRoot == null) {
                compiledRouteRoot = new CompiledRouteNode(mutableRouteRoot);
                routeTreeDirty = false;
            }
            return compiledRouteRoot;
        }
    }

    private static int hashBytes(byte[] bytes) {
        int hash = 0x811c9dc5;
        for (byte value : bytes) {
            hash = (hash ^ (value & 0xff)) * 0x01000193;
        }
        return hash;
    }

    private static int hashSegment(
            long baseAddress, long offset, int length) {
        int hash = 0x811c9dc5;
        for (int index = 0; index < length; index++) {
            hash = (hash ^ (RawSegment.getByte(
                baseAddress, offset + index) & 0xff)) * 0x01000193;
        }
        return hash;
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private RouteHandler compileLambda(Method method, Object controller) {
        try {
            java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
            method.setAccessible(true);
            java.lang.invoke.MethodHandle mh = lookup.unreflect(method);
            boolean isIsolated = method.isAnnotationPresent(Isolated.class);

            Class<?>[] ptypes = method.getParameterTypes();
            if (ptypes.length == 1 && (ptypes[0] == long.class || ptypes[0] == int.class)) {
                int type = ptypes[0] == int.class ? FastRouteHandler.TYPE_INT_PARAM : FastRouteHandler.TYPE_LONG_PARAM;
                return isIsolated ? new IsolatedRouteHandler(controller, mh, type) : new FastRouteHandler(controller, mh, type);
            } else if (ptypes.length == 1 && Record.class.isAssignableFrom(ptypes[0])) {
                int type = FastRouteHandler.TYPE_RECORD_PARAM;
                return isIsolated ? new IsolatedRouteHandler(controller, mh, type) : new FastRouteHandler(controller, mh, type);
            } else if (ptypes.length == 1 && ptypes[0] == HttpRequest.class) {
                int type = FastRouteHandler.TYPE_REQUEST_PARAM;
                return isIsolated ? new IsolatedRouteHandler(controller, mh, type) : new FastRouteHandler(controller, mh, type);
            } else if (ptypes.length == 1 && ptypes[0] == dev.cardigan.simdjson.ondemand.Value.class) {
                int type = FastRouteHandler.TYPE_VALUE_PARAM;
                return isIsolated ? new IsolatedRouteHandler(controller, mh, type) : new FastRouteHandler(controller, mh, type);
            } else if (ptypes.length == 1 && ptypes[0] == RequestBody.class) {
                int type = FastRouteHandler.TYPE_STREAMING_BODY_PARAM;
                return isIsolated ? new IsolatedRouteHandler(controller, mh, type) : new FastRouteHandler(controller, mh, type);
            } else if (ptypes.length == 2
                    && ptypes[0] == HttpRequest.class
                    && ptypes[1] == RequestBody.class) {
                int type = FastRouteHandler.TYPE_REQUEST_STREAMING_BODY_PARAM;
                return isIsolated ? new IsolatedRouteHandler(controller, mh, type) : new FastRouteHandler(controller, mh, type);
            } else if (ptypes.length == 2
                    && (ptypes[0] == long.class || ptypes[0] == int.class)
                    && ptypes[1] == HttpRequest.class) {
                int type = ptypes[0] == int.class
                    ? FastRouteHandler.TYPE_INT_REQUEST_PARAM
                    : FastRouteHandler.TYPE_LONG_REQUEST_PARAM;
                return isIsolated ? new IsolatedRouteHandler(controller, mh, type) : new FastRouteHandler(controller, mh, type);
            } else if (ptypes.length == 2
                    && ptypes[0] == int.class && ptypes[1] == int.class
                    && hasQueryParameter(method)) {
                int type = FastRouteHandler.TYPE_TWO_INT_PARAMS;
                return isIsolated ? new IsolatedRouteHandler(controller, mh, type) : new FastRouteHandler(controller, mh, type);
            } else if (ptypes.length == 3
                    && ptypes[0] == int.class && ptypes[1] == int.class
                    && ptypes[2] == RequestBody.class
                    && hasQueryParameter(method)) {
                int type = FastRouteHandler.TYPE_TWO_INT_STREAMING_BODY_PARAMS;
                return isIsolated ? new IsolatedRouteHandler(controller, mh, type) : new FastRouteHandler(controller, mh, type);
            } else if (ptypes.length == 0) {
                int type = FastRouteHandler.TYPE_NO_ARG;
                return isIsolated ? new IsolatedRouteHandler(controller, mh, type) : new FastRouteHandler(controller, mh, type);
            }
            int type = FastRouteHandler.TYPE_BOUND_ARGUMENTS;
            return isIsolated
                ? new IsolatedRouteHandler(controller, mh, type)
                : new FastRouteHandler(controller, mh, type);
        } catch (Throwable t) {
            return (req, pathLong, bodyObj) -> {
                method.setAccessible(true);
                Class<?>[] ptypes = method.getParameterTypes();
                if (ptypes.length == 1 && (ptypes[0] == long.class || ptypes[0] == int.class)) {
                    return (Response) method.invoke(controller, ptypes[0] == int.class ? (int) pathLong : pathLong);
                } else if (ptypes.length == 1 && Record.class.isAssignableFrom(ptypes[0])) {
                    return (Response) method.invoke(controller, bodyObj);
                } else if (ptypes.length == 1 && ptypes[0] == HttpRequest.class) {
                    return (Response) method.invoke(controller, req);
                } else if (ptypes.length == 1 && ptypes[0] == dev.cardigan.simdjson.ondemand.Value.class) {
                    return (Response) method.invoke(controller, req.bodyJson());
                } else if (ptypes.length == 1 && ptypes[0] == RequestBody.class) {
                    return (Response) method.invoke(controller, req.bodyStream());
                } else if (ptypes.length == 2
                        && ptypes[0] == HttpRequest.class
                        && ptypes[1] == RequestBody.class) {
                    return (Response) method.invoke(
                        controller, req, req.bodyStream());
                } else if (ptypes.length == 2
                        && (ptypes[0] == long.class || ptypes[0] == int.class)
                        && ptypes[1] == HttpRequest.class) {
                    return (Response) method.invoke(
                        controller,
                        ptypes[0] == int.class ? (int) pathLong : pathLong,
                        req);
                } else if (ptypes.length == 2
                        && ptypes[0] == int.class && ptypes[1] == int.class
                        && hasQueryParameter(method)) {
                    return (Response) method.invoke(
                        controller,
                        (int) pathLong,
                        (int) (pathLong >>> 32));
                } else if (ptypes.length == 3
                        && ptypes[0] == int.class && ptypes[1] == int.class
                        && ptypes[2] == RequestBody.class
                        && hasQueryParameter(method)) {
                    return (Response) method.invoke(
                        controller,
                        (int) pathLong,
                        (int) (pathLong >>> 32),
                        req.bodyStream());
                } else if (ptypes.length == 0) {
                    return (Response) method.invoke(controller);
                }
                return Response.error("Unsupported endpoint signature");
            };
        }
    }

    private static boolean hasQueryParameter(Method method) {
        for (java.lang.annotation.Annotation[] annotations
                : method.getParameterAnnotations()) {
            for (java.lang.annotation.Annotation annotation : annotations) {
                if (annotation instanceof QueryParam) {
                    return true;
                }
            }
        }
        return false;
    }

    public Route findRoute(HttpRequest request) {
        if (request.routeResolved()) {
            return request.resolvedRoute();
        }
        Utf8Slice pathSlice = request.path();
        if (pathSlice == null) {
            return request.cacheRoute(null, -1);
        }

        MemorySegment segment = pathSlice.segment();
        long baseAddr = segment.address();
        long pathOffset = pathSlice.offset();
        long pathLen = pathSlice.length();

        long methodOffset = request.picoRequest().methodOffset;
        long methodLen = request.picoRequest().methodLen;
        int reqMethodCode = request.picoRequest().methodCode;
        if (reqMethodCode == 0 && methodOffset >= 0
            && methodLen == 3
            && (RawSegment.getInt(baseAddr, methodOffset) & 0x00ffffff)
                == 0x00544547) {
            reqMethodCode = 1; // GET
        } else if (reqMethodCode == 0 && methodOffset >= 0
            && methodLen == 4
            && RawSegment.getInt(baseAddr, methodOffset) == 0x54534f50) {
            reqMethodCode = 2; // POST
        }

        FastPrefixRoute[] fprs = this.fastPrefixRoutes;
        int fprCount = fprs.length;
        if (fprCount > 0 && pathLen >= 8) {
            long headWord = RawSegment.getLong(baseAddr, pathOffset);
            for (int i = 0; i < fprCount; i++) {
                FastPrefixRoute fpr = fprs[i];
                if (reqMethodCode == fpr.methodCode && pathLen > fpr.prefixLen) {
                    if ((headWord & fpr.prefixMask) == fpr.prefixLong) {
                        return request.cacheRoute(fpr.route, -1);
                    }
                }
            }
        }

        long[] segPacked = request.segPacked();
        int segCount = splitPath(baseAddr, pathOffset, pathLen, segPacked);
        Route route = compiledRoutes().find(
            baseAddr, segPacked, segCount, reqMethodCode);
        return request.cacheRoute(route, segCount);
    }

    public boolean isIsolatedRoute(HttpRequest request) {
        Route route = findRoute(request);
        return route != null && route.isIsolated;
    }

    public boolean acceptsStreamingBody(HttpRequest request) {
        return streamingBodyMode(request) != BODY_BUFFERED;
    }

    /**
     * Resolves streaming and isolation together so an unsafe request never
     * has to query the route twice.
     */
    public int streamingBodyMode(HttpRequest request) {
        // Safe methods cannot register streaming-body routes and need no lookup.
        if (isSafeMethod(request)) {
            return BODY_BUFFERED;
        }
        Route route = findRoute(request);
        if (route == null || !route.isStreamingBody) {
            return BODY_BUFFERED;
        }
        return route.isIsolated
            ? BODY_STREAMING_ISOLATED
            : BODY_STREAMING;
    }

    public boolean isSafeMethod(HttpRequest request) {
        if (request.picoRequest().methodCode != 0) {
            return request.picoRequest().methodCode == 1;
        }
        long methodLength = request.picoRequest().methodLen;
        return request.picoRequest().methodOffset >= 0
            && methodLength == 3
            && (RawSegment.getInt(request.address(), request.picoRequest().methodOffset) & 0x00ff_ffff)
            == 0x0054_4547;
    }

    public PreparedInvocation prepare(HttpRequest request) {
        return prepare(request, new PreparedInvocation());
    }

    public PreparedInvocation prepare(HttpRequest request, PreparedInvocation target) {
        return prepare(request, target, null);
    }

    public PreparedInvocation prepare(HttpRequest request, PreparedInvocation target,
                                      Runnable requestMaterializer) {
        return prepare(request, target, requestMaterializer, null);
    }

    /**
     * Prepares a request while optionally transferring ownership of the
     * request's backing storage. Storage not needed by the matched route is
     * closed before this method returns.
     */
    public PreparedInvocation prepare(
            HttpRequest request,
            PreparedInvocation target,
            Runnable requestMaterializer,
            AutoCloseable requestStorage) {
        target.beginPreparation();
        try {
            return prepareInternal(
                request, target, requestMaterializer, requestStorage);
        } finally {
            if (requestStorage != null
                    && !target.ownsRequestStorage(requestStorage)) {
                closeRequestStorage(requestStorage);
            }
        }
    }

    private PreparedInvocation prepareInternal(
            HttpRequest request,
            PreparedInvocation target,
            Runnable requestMaterializer,
            AutoCloseable requestStorage) {
        Utf8Slice path = request.path();
        if (path == null) {
            return target.setImmediate(Response.notFound(), false);
        }

        MemorySegment segment = path.segment();
        long baseAddress = segment.address();
        long pathOffset = path.offset();
        long pathLength = path.length();
        long methodOffset = request.picoRequest().methodOffset;
        long methodLength = request.picoRequest().methodLen;
        int methodCode = request.picoRequest().methodCode;
        if (methodCode == 0 && methodOffset >= 0
            && methodLength == 3
            && (RawSegment.getInt(baseAddress, methodOffset) & 0x00ff_ffff)
                == 0x0054_4547) {
            methodCode = 1;
        } else if (methodCode == 0 && methodOffset >= 0
            && methodLength == 4
            && RawSegment.getInt(baseAddress, methodOffset) == 0x5453_4f50) {
            methodCode = 2;
        }

        if (request.routeResolved()
            && request.resolvedSegmentCount() >= 0) {
            Route route = request.resolvedRoute();
            if (route == null) {
                return target.setImmediate(
                    Response.notFound(), methodCode == 1);
            }
            return prepareResolvedRoute(
                route,
                request,
                target,
                requestMaterializer,
                requestStorage,
                request.resolvedSegmentCount()
            );
        }

        FastPrefixRoute[] prefixRoutes = fastPrefixRoutes;
        if (pathLength >= 8 && prefixRoutes.length != 0) {
            long headWord = RawSegment.getLong(baseAddress, pathOffset);
            for (FastPrefixRoute prefixRoute : prefixRoutes) {
                if (methodCode == prefixRoute.methodCode && pathLength > prefixRoute.prefixLen
                    && (headWord & prefixRoute.prefixMask) == prefixRoute.prefixLong) {
                    long parameterLength = pathLength - prefixRoute.prefixLen;
                    long pathLong = parseLongFast(
                        baseAddress,
                        pathOffset + prefixRoute.prefixLen,
                        parameterLength
                    );
                    return prepareMatchedRoute(
                        prefixRoute.route, request, pathLong, target,
                        requestMaterializer, requestStorage);
                }
            }
        }

        Route route = findRoute(request);
        if (route == null) {
            return target.setImmediate(Response.notFound(), methodCode == 1);
        }

        return prepareResolvedRoute(
            route,
            request,
            target,
            requestMaterializer,
            requestStorage,
            request.resolvedSegmentCount()
        );
    }

    private PreparedInvocation prepareResolvedRoute(
        Route route,
        HttpRequest request,
        PreparedInvocation target,
        Runnable requestMaterializer,
        AutoCloseable requestStorage,
        int segmentCount
    ) {
        try {
            long[] segPacked = request.segPacked();

            long pathLong = 0;
            if (route.paramIndexMap.length > 0) {
                int paramIndex = route.paramIndexMap[0];
                if (paramIndex < segmentCount) {
                    long packed = segPacked[paramIndex];
                    pathLong = parseLongFast(
                        request.address(),
                        packed >>> 32,
                        packed & 0xffff_ffffL
                    );
                }
            }
            return prepareMatchedRoute(
                route, request, pathLong, target,
                requestMaterializer, requestStorage);
        } catch (Throwable t) {
            return target.setImmediate(
                Response.error("Internal Server Error: " + t.getMessage()),
                route.methodCode == 1
            );
        }
    }

    private PreparedInvocation prepareMatchedRoute(Route route, HttpRequest request, long pathLong,
                                                    PreparedInvocation target,
                                                    Runnable requestMaterializer,
                                                    AutoCloseable requestStorage) {
        boolean safe = route.methodCode == 1;
        try {
            if (route.handler == null) {
                materializeRequest(requestMaterializer);
                return target.setFallback(
                    this,
                    target.storeRequest(request, requestStorage, false),
                    safe);
            }

            if (route.longBodyDecoder != null) {
                pathLong = route.longBodyDecoder.decode(
                    request.segment(),
                    request.bodyOffset(),
                    request.bodyLength()
                );
            } else {
                pathLong = bindPackedIntArguments(
                    route, request, pathLong);
            }

            Object bodyRecord = null;
            if (route.isStreamingBody) {
                bodyRecord = request.bodyStream();
            } else if (route.isBodyRecord && request.bodyLength() > 0) {
                bodyRecord = Serdes.fromJson(
                    request.segment(),
                    request.bodyOffset(),
                    request.bodyLength(),
                    route.bodyRecordMetadata
                );
            }

            HttpRequest handlerRequest = null;
            if (route.requiresRequestStorage) {
                materializeRequest(requestMaterializer);
                handlerRequest = target.storeRequest(
                    request, requestStorage, route.isIsolated);
            }
            if (route.bindsArguments) {
                HttpRequest bindingRequest = handlerRequest == null
                    ? request : handlerRequest;
                bodyRecord = bindRouteArguments(
                    route, bindingRequest, pathLong, bodyRecord, target);
            }
            return target.setHandler(route.handler, handlerRequest, pathLong, bodyRecord, safe);
        } catch (Throwable t) {
            return target.setImmediate(
                Response.error("Internal Server Error: " + t.getMessage()),
                safe
            );
        }
    }

    private static void materializeRequest(Runnable requestMaterializer) {
        if (requestMaterializer != null) {
            requestMaterializer.run();
        }
    }

    private static void closeRequestStorage(AutoCloseable storage) {
        try {
            storage.close();
        } catch (Throwable ignored) {
            // Request preparation has already produced its result.
        }
    }

    public Response dispatch(HttpRequest request) {
        Utf8Slice pathSlice = request.path();
        if (pathSlice == null) {
            return Response.notFound();
        }

        MemorySegment segment = pathSlice.segment();
        long baseAddr = segment.address();
        long pathOffset = pathSlice.offset();
        long pathLen = pathSlice.length();

        long methodOffset = request.picoRequest().methodOffset;
        long methodLen = request.picoRequest().methodLen;
        int reqMethodCode = request.picoRequest().methodCode;
        if (reqMethodCode == 0 && methodOffset >= 0
            && methodLen == 3
            && (RawSegment.getInt(baseAddr, methodOffset) & 0x00ffffff)
                == 0x00544547) {
            reqMethodCode = 1; // GET
        } else if (reqMethodCode == 0 && methodOffset >= 0
            && methodLen == 4
            && RawSegment.getInt(baseAddr, methodOffset) == 0x54534f50) {
            reqMethodCode = 2; // POST
        }

        if (request.routeResolved()
            && request.resolvedSegmentCount() >= 0) {
            Route route = request.resolvedRoute();
            return route == null
                ? Response.notFound()
                : invokeMatchedRoute(
                    route,
                    request,
                    segment,
                    baseAddr,
                    request.resolvedSegmentCount()
                );
        }

        FastPrefixRoute[] fprs = this.fastPrefixRoutes;
        int fprCount = fprs.length;
        if (fprCount > 0 && pathLen >= 8) {
            long headWord = RawSegment.getLong(baseAddr, pathOffset);
            for (int i = 0; i < fprCount; i++) {
                FastPrefixRoute fpr = fprs[i];
                if (reqMethodCode == fpr.methodCode && pathLen > fpr.prefixLen) {
                    if ((headWord & fpr.prefixMask) == fpr.prefixLong) {
                        long paramLen = pathLen - fpr.prefixLen;
                        long id = parseLongFast(baseAddr, pathOffset + fpr.prefixLen, paramLen);
                        try {
                            if (fpr.route.handler != null) {
                                Object body = fpr.route.isStreamingBody
                                    ? request.bodyStream()
                                    : null;
                                long arguments = bindPackedIntArguments(
                                    fpr.route, request, id);
                                return fpr.route.handler.handle(
                                    request, arguments, body);
                            }
                        } catch (Throwable t) {
                            return Response.error("Internal Server Error: " + t.getMessage());
                        }
                    }
                }
            }
        }

        long[] segPacked = request.segPacked();
        int segCount = splitPath(baseAddr, pathOffset, pathLen, segPacked);
        Route route = compiledRoutes().find(
            baseAddr, segPacked, segCount, reqMethodCode);
        return route == null
            ? Response.notFound()
            : invokeMatchedRoute(
                route, request, segment, baseAddr, segCount);
    }

    private Response invokeMatchedRoute(
        Route route,
        HttpRequest request,
        MemorySegment segment,
        long baseAddress,
        int segmentCount
    ) {
        try {
            if (route.handler != null) {
                long pathLong = 0;
                if (route.paramIndexMap.length > 0) {
                    int paramIndex = route.paramIndexMap[0];
                    if (paramIndex < segmentCount) {
                        long packed = request.segPacked()[paramIndex];
                        pathLong = parseLongFast(
                            baseAddress,
                            packed >>> 32,
                            packed & 0xffff_ffffL
                        );
                    }
                }
                if (route.longBodyDecoder != null) {
                    pathLong = route.longBodyDecoder.decode(
                        request.segment(),
                        request.bodyOffset(),
                        request.bodyLength()
                    );
                } else {
                    pathLong = bindPackedIntArguments(
                        route, request, pathLong);
                }
                Object bodyRecord = null;
                if (route.isStreamingBody) {
                    bodyRecord = request.bodyStream();
                } else if (route.isBodyRecord) {
                    long bodyOffset = request.bodyOffset();
                    long bodyLength = request.bodyLength();
                    if (bodyLength > 0) {
                        bodyRecord = Serdes.fromJson(
                            request.segment(), bodyOffset, bodyLength,
                            route.bodyRecordMetadata);
                    }
                }
                if (route.bindsArguments) {
                    bodyRecord = bindRouteArguments(
                        route, request, pathLong, bodyRecord, null);
                }
                return route.handler.handle(
                    request, pathLong, bodyRecord);
            }
            Object[] args = resolveArgs(segment, request, route);
            return (Response) route.method.invoke(
                route.controller, args);
        } catch (Throwable failure) {
            if (!(failure instanceof RequestBodyException)) {
                failure.printStackTrace();
            }
            Throwable cause = failure.getCause();
            return Response.error(
                cause != null ? cause.getMessage() : failure.getMessage());
        }
    }

    private static long bindPackedIntArguments(
            Route route, HttpRequest request, long pathLong) {
        if (!route.packsTwoIntArguments) {
            return pathLong;
        }
        int first = 0;
        int second = 0;
        int argument = 0;
        for (int index = 0;
                index < route.parameterTypes.length && argument < 2;
                index++) {
            if (route.parameterTypes[index] != int.class) {
                continue;
            }
            String queryName = route.queryParameterNames[index];
            int value = queryName == null
                ? (int) pathLong
                : request.queryInt(
                    queryName, route.queryParameterDefaults[index]);
            if (argument++ == 0) {
                first = value;
            } else {
                second = value;
            }
        }
        return (first & 0xffff_ffffL)
            | ((long) second << 32);
    }

    private static Object[] bindRouteArguments(
            Route route,
            HttpRequest request,
            long decodedLong,
            Object decodedBody,
            PreparedInvocation target) {
        int argumentCount = route.parameterTypes.length;
        Object[] arguments = target == null
            ? new Object[argumentCount]
            : target.arguments(argumentCount, route.isIsolated);
        long baseAddress = request.address();
        long[] segments = request.segPacked();

        for (int argument = 0; argument < argumentCount; argument++) {
            int binding = route.argumentBindings[argument];
            if (binding >= Route.ARGUMENT_PATH_LONG
                    && binding <= Route.ARGUMENT_PATH_STRING
                    && route.argumentPathSegments[argument] < 0) {
                arguments[argument] = null;
                continue;
            }
            switch (binding) {
                case Route.ARGUMENT_PATH_LONG -> {
                    long packed = pathSegment(
                        route, segments, argument);
                    arguments[argument] = parseLongFast(
                        baseAddress,
                        packed >>> 32,
                        packed & 0xffff_ffffL);
                }
                case Route.ARGUMENT_PATH_INT -> {
                    long packed = pathSegment(
                        route, segments, argument);
                    arguments[argument] = (int) parseLongFast(
                        baseAddress,
                        packed >>> 32,
                        packed & 0xffff_ffffL);
                }
                case Route.ARGUMENT_PATH_STRING -> {
                    long packed = pathSegment(
                        route, segments, argument);
                    byte[] bytes = request.segment().asSlice(
                        packed >>> 32,
                        packed & 0xffff_ffffL
                    ).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                    arguments[argument] = new String(
                        bytes, StandardCharsets.UTF_8);
                }
                case Route.ARGUMENT_REQUEST ->
                    arguments[argument] = request;
                case Route.ARGUMENT_VALUE ->
                    arguments[argument] = request.bodyJson();
                case Route.ARGUMENT_BODY_STREAM,
                     Route.ARGUMENT_BODY_RECORD ->
                    arguments[argument] = decodedBody;
                case Route.ARGUMENT_QUERY_INT ->
                    arguments[argument] = request.queryInt(
                        route.queryParameterNames[argument],
                        route.queryParameterDefaults[argument]);
                case Route.ARGUMENT_DECODED_LONG ->
                    arguments[argument] = decodedLong;
                default -> arguments[argument] = null;
            }
        }
        return arguments;
    }

    private static long pathSegment(
            Route route, long[] segments, int argument) {
        int segmentIndex = route.argumentPathSegments[argument];
        return segmentIndex < 0 ? 0 : segments[segmentIndex];
    }

    public Response dispatch(String httpMethod, HttpRequest request) {
        return dispatch(request);
    }

    private Object[] resolveArgs(MemorySegment segment, HttpRequest request, Route route) {
        Class<?>[] paramTypes = route.parameterTypes;
        Object[] args = new Object[paramTypes.length];
        
        int pathParamIndex = 0;
        long baseAddr = segment.address();
        long[] segPacked = request.segPacked();

        for (int j = 0; j < paramTypes.length; j++) {
            Class<?> type = paramTypes[j];

            if (route.longBodyDecoder != null && j == 0) {
                args[j] = route.longBodyDecoder.decode(
                    request.segment(),
                    request.bodyOffset(),
                    request.bodyLength()
                );
            } else if (type == HttpRequest.class) {
                args[j] = request;
            } else if (type == dev.cardigan.simdjson.ondemand.Value.class) {
                args[j] = request.bodyJson();
            } else if (type == RequestBody.class) {
                args[j] = request.bodyStream();
            } else if (Record.class.isAssignableFrom(type)) {
                Utf8Slice body = request.body();
                if (body != null && body.length() > 0) {
                    args[j] = Serdes.fromJson(body.segment(), body.offset(), body.length(), type);
                } else {
                    args[j] = null;
                }
            } else {
                int segmentIndex = -1;
                if (pathParamIndex < route.paramIndexMap.length) {
                    segmentIndex = route.paramIndexMap[pathParamIndex++];
                } else {
                    pathParamIndex++;
                }
                if (segmentIndex == -1) {
                    args[j] = null;
                    continue;
                }

                long seg = segPacked[segmentIndex];
                long offset = seg >>> 32;
                long len = seg & 0xFFFFFFFFL;

                if (type == long.class || type == Long.class) {
                    args[j] = parseLongFast(baseAddr, offset, len);
                } else if (type == int.class || type == Integer.class) {
                    args[j] = (int) parseLongFast(baseAddr, offset, len);
                } else if (type == String.class) {
                    byte[] bytes = segment.asSlice(offset, len).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                    args[j] = new String(bytes, StandardCharsets.UTF_8);
                } else {
                    args[j] = null;
                }
            }
        }
        return args;
    }

    private static int splitPath(long baseAddr, long offset, long length, long[] segPacked) {
        long idx = offset;
        long end = offset + length;
        if (idx < end && RawSegment.getByte(baseAddr, idx) == '/') idx++;

        long segStart = idx;
        int count = 0;

        while (idx + 8 <= end) {
            long word = RawSegment.getLong(baseAddr, idx);
            long diff = word ^ 0x2f2f2f2f2f2f2f2fL;
            long matchMask = (diff - 0x0101010101010101L) & ~diff & 0x8080808080808080L;
            while (matchMask != 0) {
                int slashByteOffset =
                    Long.numberOfTrailingZeros(matchMask) >>> 3;
                long segEnd = idx + slashByteOffset;
                if (RawSegment.getByte(baseAddr, segEnd) == '/') {
                    long segLen = segEnd - segStart;
                    if (segLen > 0) {
                        segPacked[count++] =
                            (segStart << 32) | segLen;
                    }
                    segStart = segEnd + 1;
                }
                matchMask &= (matchMask - 1);
            }
            idx += 8;
        }

        while (idx < end) {
            if (RawSegment.getByte(baseAddr, idx) == '/') {
                long segLen = idx - segStart;
                if (segLen > 0) {
                    segPacked[count++] = (segStart << 32) | segLen;
                }
                segStart = idx + 1;
            }
            idx++;
        }

        if (idx > segStart) {
            long segLen = idx - segStart;
            if (segLen > 0) {
                segPacked[count++] = (segStart << 32) | segLen;
            }
        }

        return count;
    }

    private static boolean matchSegmentBytes(long baseAddr, long offset, long len, byte[] target) {
        long ptr = baseAddr + offset;
        for (int i = 0; i < len; i++) {
            if (RawSegment.getByte(ptr, i) != target[i]) {
                return false;
            }
        }
        return true;
    }

    private static long parseLongFast(long baseAddr, long offset, long len) {
        long index = offset;
        long end = offset + len;
        long val = 0;
        while (index < end) {
            byte b = RawSegment.getByte(baseAddr, index);
            if (b >= '0' && b <= '9') {
                val = val * 10 + (b - '0');
            } else {
                break;
            }
            index++;
        }
        return val;
    }
}
