/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.hf;

import com.amihaiemil.eoyaml.Yaml;
import com.artipie.adapters.hf.HuggingFaceProxy;
import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.cache.StoragesCache;
import com.artipie.http.Headers;
import com.artipie.http.Slice;
import com.artipie.http.client.jetty.JettyClientSlices;
import com.artipie.http.hm.RsHasStatus;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.http.RsStatus;
import com.artipie.settings.StorageByAlias;
import com.artipie.settings.repo.RepoConfig;
import com.artipie.test.TestStoragesCache;
import org.hamcrest.CustomMatcher;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Tests for {@link HuggingFaceProxy}.
 */
class HuggingFaceProxyTest {

    /**
     * Storages cache for config resolution.
     */
    private StoragesCache cache;

    @BeforeEach
    void setUp() {
        this.cache = new TestStoragesCache();
    }

    @ParameterizedTest
    @MethodSource("goodConfigs")
    void shouldBuildFromConfig(final String yaml) throws Exception {
        final Slice slice = huggingFaceProxy(this.cache, yaml);
        MatcherAssert.assertThat(
            slice.response(
                new RequestLine(RqMethod.GET, "/"), Headers.EMPTY, Content.EMPTY
            ).join(),
            new RsHasStatus(
                new IsNot<>(
                    new CustomMatcher<>("is server error") {
                        @Override
                        public boolean matches(final Object item) {
                            return ((RsStatus) item).serverError();
                        }
                    }
                )
            )
        );
    }

    @ParameterizedTest
    @MethodSource("badConfigs")
    void shouldFailBuildFromBadConfig(final String yaml) {
        Assertions.assertThrows(
            RuntimeException.class,
            () -> huggingFaceProxy(this.cache, yaml).response(
                new RequestLine(RqMethod.GET, "/"), Headers.EMPTY, Content.EMPTY
            ).join()
        );
    }

    private static HuggingFaceProxy huggingFaceProxy(
        final StoragesCache cache,
        final String yaml
    ) throws IOException {
        return new HuggingFaceProxy(
            new JettyClientSlices(),
            RepoConfig.from(
                Yaml.createYamlInput(yaml).readYamlMapping(),
                new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
                Key.ROOT, cache, false
            ),
            Optional.empty()
        );
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private static Stream<String> goodConfigs() {
        return Stream.of(
            "repo:\n  type: huggingface-proxy",
            "repo:\n  type: huggingface-proxy\n  remotes:\n    - url: https://huggingface.co",
            String.join(
                "\n",
                "repo:",
                "  type: huggingface-proxy",
                "  storage:",
                "    type: fs",
                "    path: /var/artipie/data",
                "  remotes:",
                "    - url: https://huggingface.co"
            ),
            String.join(
                "\n",
                "repo:",
                "  type: huggingface-proxy",
                "  remotes:",
                "    - url: https://huggingface.co",
                "      username: alice",
                "      password: secret"
            )
        );
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private static Stream<String> badConfigs() {
        return Stream.of(
            "",
            "repo:",
            "repo:\n  type: huggingface-proxy\n  remotes:\n    - attr: value",
            "repo:\n  type: huggingface-proxy\n  remotes:\n    - username: alice",
            "repo:\n  type: huggingface-proxy\n  remotes:\n    - url: https://huggingface.co\n      username: alice"
        );
    }
}
