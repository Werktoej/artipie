/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api;

import com.artipie.api.perms.ApiRepositoryPermission;
import com.artipie.api.verifier.ExistenceVerifier;
import com.artipie.api.verifier.ReservedNamesVerifier;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.security.policy.Policy;
import com.artipie.settings.RepoData;
import com.artipie.settings.repo.CrudRepoSettings;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.jetty.http.HttpStatus;

/**
 * REST handlers for repository content (list/browse artifacts).
 * <p>
 * Listing is one level only (direct children of the given path); no deep recursion.
 * Use {@code path}, {@code limit}, and {@code offset} for pagination; large repos may be slow.
 * Supported for repo types with listable storage (file, file-proxy, huggingface-proxy, etc.);
 * proxies show cached paths like a file repo.
 *
 * @since 0.1
 */
public final class ContentRest extends BaseRest {

    /**
     * Default limit for list content.
     */
    private static final int DEFAULT_LIMIT = 100;

    /**
     * Default offset for list content.
     */
    private static final int DEFAULT_OFFSET = 0;

    /**
     * Maximum limit for list content.
     */
    private static final int MAX_LIMIT = 1000;

    /**
     * Safe path segment pattern: alphanumeric, underscore, hyphen, dot.
     */
    private static final String SEGMENT_PATTERN = "[a-zA-Z0-9_.-]+";

    /**
     * Repository data (storage resolution).
     */
    private final RepoData data;

    /**
     * Repository settings CRUD (existence check).
     */
    private final CrudRepoSettings crs;

    /**
     * Artipie policy (permissions).
     */
    private final Policy<?> policy;

    /**
     * Ctor.
     *
     * @param data Repo data for storage resolution
     * @param crs Repository settings for existence check
     * @param policy Policy for READ permission
     */
    public ContentRest(
        final RepoData data,
        final CrudRepoSettings crs,
        final Policy<?> policy
    ) {
        this.data = data;
        this.crs = crs;
        this.policy = policy;
    }

    @Override
    public void init(final RouterBuilder rbr) {
        rbr.operation("listContent")
            .handler(
                new AuthzHandler(
                    this.policy,
                    new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.READ)
                )
            )
            .handler(this::listContent)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
    }

    /**
     * List repository content (browse) at the given path.
     *
     * @param context Routing context
     */
    private void listContent(final RoutingContext context) {
        final RepositoryName rname = new RepositoryName.FromRequest(context);
        final Validator baseValidator = new Validator.All(
            Validator.validator(new ReservedNamesVerifier(rname), HttpStatus.BAD_REQUEST_400),
            Validator.validator(new ExistenceVerifier(rname, this.crs), HttpStatus.NOT_FOUND_404)
        );
        if (!baseValidator.validate(context)) {
            return;
        }
        final String pathParam = context.queryParam("path").stream().findFirst().orElse("");
        final List<String> pathSegments = ContentRest.normalizePath(pathParam);
        if (pathSegments == null) {
            context.response()
                .setStatusCode(HttpStatus.BAD_REQUEST_400)
                .end("Invalid path: must not contain '..' or invalid segments");
            return;
        }
        final int limit = context.queryParam("limit").stream()
            .findFirst()
            .map(s -> {
                try {
                    return Math.min(Integer.parseInt(s), ContentRest.MAX_LIMIT);
                } catch (final NumberFormatException e) {
                    return ContentRest.DEFAULT_LIMIT;
                }
            })
            .orElse(ContentRest.DEFAULT_LIMIT);
        final int offset = context.queryParam("offset").stream()
            .findFirst()
            .map(s -> {
                try {
                    return Math.max(0, Integer.parseInt(s));
                } catch (final NumberFormatException e) {
                    return ContentRest.DEFAULT_OFFSET;
                }
            })
            .orElse(ContentRest.DEFAULT_OFFSET);
        final Key prefix = ContentRest.prefixKey(rname.toString(), pathSegments);
        this.data.storage(rname)
            .thenCompose(
                storage -> storage.list(prefix)
                    .thenApply(
                        keys -> ContentRest.entriesFromKeys(
                            rname.toString(),
                            pathSegments,
                            prefix,
                            keys,
                            limit,
                            offset
                        )
                    )
            )
            .thenAccept(
                entries -> context.response()
                    .setStatusCode(HttpStatus.OK_200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonArray(entries).encode())
            )
            .exceptionally(
                err -> {
                    context.response()
                        .setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                        .end();
                    return null;
                }
            );
    }

    /**
     * Normalize and validate path: no "..", no empty segments, safe characters only.
     *
     * @param path Raw path query value
     * @return List of segments or null if invalid
     */
    private static List<String> normalizePath(final String path) {
        if (path == null) {
            return Collections.emptyList();
        }
        final String trimmed = path.replaceAll("^/+|/+$", "");
        if (trimmed.isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> segments = new ArrayList<>();
        for (final String segment : trimmed.split("/")) {
            if (segment.isEmpty() || "..".equals(segment)) {
                return null;
            }
            if (!segment.matches(ContentRest.SEGMENT_PATTERN)) {
                return null;
            }
            segments.add(segment);
        }
        return segments;
    }

    /**
     * Build list prefix key from repo name and path segments.
     *
     * @param repo Repository name
     * @param pathSegments Path segments (can be empty for root)
     * @return Key prefix for list
     */
    private static Key prefixKey(final String repo, final List<String> pathSegments) {
        final List<String> parts = new ArrayList<>();
        parts.add(repo);
        parts.addAll(pathSegments);
        return new Key.From(parts);
    }

    /**
     * Build API response entries from storage keys (one level, with type dir/file).
     *
     * @param repo Repository name
     * @param pathSegments Current path segments
     * @param prefix List prefix key
     * @param keys All keys under prefix
     * @param limit Max entries to return
     * @param offset Skip this many entries
     * @return List of JSON objects (name, path, type, optional size)
     */
    private static List<JsonObject> entriesFromKeys(
        final String repo,
        final List<String> pathSegments,
        final Key prefix,
        final Collection<Key> keys,
        final int limit,
        final int offset
    ) {
        final int prefixParts = prefix.parts().size();
        final Map<String, Boolean> segmentIsDir = new HashMap<>();
        for (final Key key : keys) {
            final List<String> parts = key.parts();
            if (parts.size() <= prefixParts) {
                continue;
            }
            final String segment = parts.get(prefixParts);
            segmentIsDir.put(
                segment,
                segmentIsDir.getOrDefault(segment, Boolean.FALSE) || parts.size() > prefixParts + 1
            );
        }
        final String basePath = pathSegments.isEmpty()
            ? ""
            : String.join("/", pathSegments) + "/";
        final List<JsonObject> entries = segmentIsDir.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(
                e -> {
                    final String name = e.getKey();
                    final String path = basePath + name;
                    final String type = e.getValue() ? "dir" : "file";
                    final JsonObject obj = new JsonObject()
                        .put("name", name)
                        .put("path", path)
                        .put("type", type);
                    return obj;
                }
            )
            .collect(Collectors.toList());
        final int from = Math.min(offset, entries.size());
        final int to = Math.min(from + limit, entries.size());
        return entries.subList(from, to);
    }
}
