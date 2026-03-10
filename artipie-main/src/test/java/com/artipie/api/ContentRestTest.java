/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api;

import com.artipie.asto.Key;
import com.artipie.asto.blocking.BlockingStorage;
import com.artipie.asto.fs.FileStorage;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.eclipse.jetty.http.HttpStatus;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link ContentRest}.
 *
 * @since 0.1
 */
@ExtendWith(VertxExtension.class)
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ContentRestTest extends RestApiServerBase {

    /**
     * Temp dir for repo storage.
     */
    @TempDir
    Path temp;

    /**
     * Repo name used in tests.
     */
    private static final String REPO_NAME = "content-repo";

    /**
     * Repository settings YAML with storage path pointing to temp.
     *
     * @return YAML string
     */
    private String repoSettings() {
        return String.join(
            System.lineSeparator(),
            "repo:",
            "  type: file",
            "  storage:",
            "    type: fs",
            String.format("    path: %s", this.temp.toString())
        );
    }

    @BeforeEach
    void setUpRepoConfig() {
        this.save(
            new ConfigKeys(ContentRestTest.REPO_NAME).yamlKey(),
            this.repoSettings().getBytes(StandardCharsets.UTF_8)
        );
    }

    @Test
    void listContentReturnsOkWithEntries(final io.vertx.core.Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        final BlockingStorage repoStorage = new BlockingStorage(new FileStorage(this.temp));
        repoStorage.save(new Key.From(ContentRestTest.REPO_NAME, "a"), new byte[0]);
        repoStorage.save(new Key.From(ContentRestTest.REPO_NAME, "b"), new byte[0]);
        repoStorage.save(new Key.From(ContentRestTest.REPO_NAME, "dir", "nested.txt"), new byte[0]);
        this.requestAndAssert(
            vertx, ctx,
            new TestRequest(
                HttpMethod.GET,
                "/api/v1/repository/" + ContentRestTest.REPO_NAME + "/content"
            ),
            resp -> {
                MatcherAssert.assertThat(
                    resp.statusCode(),
                    new IsEqual<>(HttpStatus.OK_200)
                );
                final var array = resp.body().toJsonArray();
                MatcherAssert.assertThat(array.size(), Matchers.greaterThanOrEqualTo(2));
                final var names = array.stream()
                    .map(obj -> ((io.vertx.core.json.JsonObject) obj).getString("name"))
                    .collect(Collectors.toList());
                MatcherAssert.assertThat(names, Matchers.hasItems("a", "b", "dir"));
            }
        );
    }

    @Test
    void listContentReturnsNotFoundForMissingRepo(final io.vertx.core.Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.requestAndAssert(
            vertx, ctx,
            new TestRequest(
                HttpMethod.GET,
                "/api/v1/repository/nonexistent-repo/content"
            ),
            resp -> MatcherAssert.assertThat(
                resp.statusCode(),
                new IsEqual<>(HttpStatus.NOT_FOUND_404)
            )
        );
    }

    @Test
    void listContentReturnsBadRequestForInvalidPath(final io.vertx.core.Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.requestAndAssert(
            vertx, ctx,
            new TestRequest(
                HttpMethod.GET,
                "/api/v1/repository/" + ContentRestTest.REPO_NAME + "/content?path=../etc"
            ),
            resp -> MatcherAssert.assertThat(
                resp.statusCode(),
                new IsEqual<>(HttpStatus.BAD_REQUEST_400)
            )
        );
    }

    @Test
    void listContentWithPathReturnsOneLevel(final io.vertx.core.Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        final BlockingStorage repoStorage = new BlockingStorage(new FileStorage(this.temp));
        repoStorage.save(
            new Key.From(ContentRestTest.REPO_NAME, "sub", "nested.txt"),
            new byte[0]
        );
        this.requestAndAssert(
            vertx, ctx,
            new TestRequest(
                HttpMethod.GET,
                "/api/v1/repository/" + ContentRestTest.REPO_NAME + "/content?path=sub"
            ),
            resp -> {
                MatcherAssert.assertThat(
                    resp.statusCode(),
                    new IsEqual<>(HttpStatus.OK_200)
                );
                final var array = resp.body().toJsonArray();
                MatcherAssert.assertThat(array.size(), Matchers.equalTo(1));
                MatcherAssert.assertThat(
                    array.getJsonObject(0).getString("name"),
                    Matchers.equalTo("nested.txt")
                );
            }
        );
    }

    @Test
    void deleteContentDeletesFileAndReturnsOk(final io.vertx.core.Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        final BlockingStorage repoStorage = new BlockingStorage(new FileStorage(this.temp));
        repoStorage.save(
            new Key.From(ContentRestTest.REPO_NAME, "to-delete.txt"),
            new byte[0]
        );
        this.requestAndAssert(
            vertx, ctx,
            new TestRequest(
                HttpMethod.DELETE,
                "/api/v1/repository/" + ContentRestTest.REPO_NAME + "/content?path=to-delete.txt"
            ),
            resp -> MatcherAssert.assertThat(
                resp.statusCode(),
                new IsEqual<>(HttpStatus.OK_200)
            )
        );
        MatcherAssert.assertThat(
            repoStorage.exists(new Key.From(ContentRestTest.REPO_NAME, "to-delete.txt")),
            Matchers.is(false)
        );
    }

    @Test
    void deleteContentWithRecursiveDeletesDirectory(final io.vertx.core.Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        final BlockingStorage repoStorage = new BlockingStorage(new FileStorage(this.temp));
        repoStorage.save(
            new Key.From(ContentRestTest.REPO_NAME, "dir", "nested.txt"),
            new byte[0]
        );
        this.requestAndAssert(
            vertx, ctx,
            new TestRequest(
                HttpMethod.DELETE,
                "/api/v1/repository/" + ContentRestTest.REPO_NAME
                    + "/content?path=dir&recursive=true"
            ),
            resp -> MatcherAssert.assertThat(
                resp.statusCode(),
                new IsEqual<>(HttpStatus.OK_200)
            )
        );
        MatcherAssert.assertThat(
            repoStorage.list(new Key.From(ContentRestTest.REPO_NAME, "dir")).isEmpty(),
            Matchers.is(true)
        );
    }

    @Test
    void deleteContentReturnsBadRequestWhenPathMissing(final io.vertx.core.Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.requestAndAssert(
            vertx, ctx,
            new TestRequest(
                HttpMethod.DELETE,
                "/api/v1/repository/" + ContentRestTest.REPO_NAME + "/content"
            ),
            resp -> MatcherAssert.assertThat(
                resp.statusCode(),
                new IsEqual<>(HttpStatus.BAD_REQUEST_400)
            )
        );
    }

    @Test
    void deleteContentReturnsBadRequestWhenDirectoryNotEmptyAndNotRecursive(
        final io.vertx.core.Vertx vertx, final VertxTestContext ctx) throws Exception {
        final BlockingStorage repoStorage = new BlockingStorage(new FileStorage(this.temp));
        repoStorage.save(
            new Key.From(ContentRestTest.REPO_NAME, "dir", "file.txt"),
            new byte[0]
        );
        this.requestAndAssert(
            vertx, ctx,
            new TestRequest(
                HttpMethod.DELETE,
                "/api/v1/repository/" + ContentRestTest.REPO_NAME + "/content?path=dir"
            ),
            resp -> MatcherAssert.assertThat(
                resp.statusCode(),
                new IsEqual<>(HttpStatus.BAD_REQUEST_400)
            )
        );
    }

    @Test
    void deleteContentReturnsNotFoundForMissingRepo(final io.vertx.core.Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.requestAndAssert(
            vertx, ctx,
            new TestRequest(
                HttpMethod.DELETE,
                "/api/v1/repository/nonexistent-repo/content?path=any"
            ),
            resp -> MatcherAssert.assertThat(
                resp.statusCode(),
                new IsEqual<>(HttpStatus.NOT_FOUND_404)
            )
        );
    }

    @Test
    void uploadContentCreatesFileAndReturnsOk(final io.vertx.core.Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        final String path = "/api/v1/repository/" + ContentRestTest.REPO_NAME
            + "/content?path=uploaded.txt";
        final String token = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhcnRpcGllIiwiY29udGV4dCI6InRlc3QiLCJpYXQiOjE2ODIwODgxNTh9.QjQPLQ0tQFbiRIWpE-GUtUFXvUXvXP4p7va_DOBHjTM";
        final Buffer body = Buffer.buffer("uploaded data");
        WebClient.create(vertx, this.webClientOptions())
            .put(this.port(), RestApiServerBase.HOST, path)
            .bearerTokenAuthentication(token)
            .sendBuffer(body)
            .onSuccess(
                response -> {
                    MatcherAssert.assertThat(
                        response.statusCode(),
                        new IsEqual<>(HttpStatus.OK_200)
                    );
                    final BlockingStorage repoStorage =
                        new BlockingStorage(new FileStorage(this.temp));
                    MatcherAssert.assertThat(
                        repoStorage.exists(
                            new Key.From(ContentRestTest.REPO_NAME, "uploaded.txt")
                        ),
                        Matchers.is(true)
                    );
                    final byte[] content = repoStorage.value(
                        new Key.From(ContentRestTest.REPO_NAME, "uploaded.txt")
                    );
                    MatcherAssert.assertThat(
                        new String(content, StandardCharsets.UTF_8),
                        Matchers.equalTo("uploaded data")
                    );
                    ctx.completeNow();
                }
            )
            .onFailure(ctx::failNow);
    }

    @Test
    void uploadContentReturnsBadRequestWhenPathMissing(final io.vertx.core.Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.requestAndAssert(
            vertx, ctx,
            new TestRequest(HttpMethod.PUT, "/api/v1/repository/" + ContentRestTest.REPO_NAME + "/content"),
            resp -> MatcherAssert.assertThat(
                resp.statusCode(),
                new IsEqual<>(HttpStatus.BAD_REQUEST_400)
            )
        );
    }

    @Test
    void uploadContentReturnsNotFoundForMissingRepo(final io.vertx.core.Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        final String path = "/api/v1/repository/nonexistent-repo/content?path=a.txt";
        this.requestAndAssert(
            vertx, ctx,
            new TestRequest(HttpMethod.PUT, path),
            resp -> MatcherAssert.assertThat(
                resp.statusCode(),
                new IsEqual<>(HttpStatus.NOT_FOUND_404)
            )
        );
    }

    @Test
    void searchContentReturnsMatchingEntries(final io.vertx.core.Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        final BlockingStorage repoStorage = new BlockingStorage(new FileStorage(this.temp));
        repoStorage.save(
            new Key.From(ContentRestTest.REPO_NAME, "foo.txt"),
            new byte[0]
        );
        repoStorage.save(
            new Key.From(ContentRestTest.REPO_NAME, "foobar.dat"),
            new byte[0]
        );
        repoStorage.save(
            new Key.From(ContentRestTest.REPO_NAME, "other.txt"),
            new byte[0]
        );
        this.requestAndAssert(
            vertx, ctx,
            new TestRequest(
                HttpMethod.GET,
                "/api/v1/repository/" + ContentRestTest.REPO_NAME
                    + "/content/search?q=foo"
            ),
            resp -> {
                MatcherAssert.assertThat(
                    resp.statusCode(),
                    new IsEqual<>(HttpStatus.OK_200)
                );
                final var array = resp.body().toJsonArray();
                MatcherAssert.assertThat(array.size(), Matchers.greaterThanOrEqualTo(2));
                final var paths = array.stream()
                    .map(o -> ((io.vertx.core.json.JsonObject) o).getString("path"))
                    .collect(Collectors.toList());
                MatcherAssert.assertThat(paths, Matchers.hasItems("foo.txt", "foobar.dat"));
            }
        );
    }

    @Test
    void searchContentReturnsBadRequestWhenQueryMissing(final io.vertx.core.Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.requestAndAssert(
            vertx, ctx,
            new TestRequest(
                HttpMethod.GET,
                "/api/v1/repository/" + ContentRestTest.REPO_NAME + "/content/search"
            ),
            resp -> MatcherAssert.assertThat(
                resp.statusCode(),
                new IsEqual<>(HttpStatus.BAD_REQUEST_400)
            )
        );
    }
}
