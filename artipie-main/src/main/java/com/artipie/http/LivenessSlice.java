/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.asto.Content;
import com.artipie.http.rq.RequestLine;

import javax.json.Json;
import java.util.concurrent.CompletableFuture;

/**
 * Lightweight liveness probe: returns 200 with minimal JSON when the process is running.
 * Does not depend on storage or any other backend; use for container livenessProbe.
 *
 * @since 0.1
 */
public final class LivenessSlice implements Slice {

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .jsonBody(Json.createObjectBuilder().add("status", "up").build())
                .build()
        );
    }
}
