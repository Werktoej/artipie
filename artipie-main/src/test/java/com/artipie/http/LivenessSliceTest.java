/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.asto.Content;
import com.artipie.http.hm.ResponseAssert;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link LivenessSlice}.
 *
 * @since 0.1
 */
final class LivenessSliceTest {

    private static final RequestLine REQ_LINE =
        new RequestLine(RqMethod.GET, "/.health/live");

    @Test
    void returnsOkWithStatusUp() {
        ResponseAssert.check(
            new LivenessSlice().response(
                LivenessSliceTest.REQ_LINE, Headers.EMPTY, Content.EMPTY
            ).join(),
            RsStatus.OK,
            "{\"status\":\"up\"}".getBytes()
        );
    }
}
