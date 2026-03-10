/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api;

import com.artipie.api.perms.ApiRepositoryPermission;
import com.artipie.api.verifier.ExistenceVerifier;
import com.artipie.api.verifier.ReservedNamesVerifier;
import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Meta;
import com.artipie.asto.Storage;
import com.artipie.http.auth.AuthUser;
import com.artipie.security.policy.Policy;
import com.artipie.settings.RepoData;
import com.artipie.settings.repo.CrudRepoSettings;
import io.vertx.core.Handler;
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
import java.util.concurrent.CompletableFuture;
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
     * Maximum length for search query (ReDoS safety).
     */
    private static final int MAX_SEARCH_QUERY_LENGTH = 256;

    /**
     * Default max upload size (100 MiB).
     */
    private static final long DEFAULT_MAX_UPLOAD_BYTES = 100L * 1024 * 1024;

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
        rbr.operation("deleteContent")
            .handler(
                new AuthzHandler(
                    this.policy,
                    new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.DELETE)
                )
            )
            .handler(this::deleteContent)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("uploadContent")
            .handler(this.uploadAuthz())
            .handler(this::uploadContent)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("searchContent")
            .handler(
                new AuthzHandler(
                    this.policy,
                    new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.READ)
                )
            )
            .handler(this::searchContent)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("getContentMetadata")
            .handler(
                new AuthzHandler(
                    this.policy,
                    new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.READ)
                )
            )
            .handler(this::getContentMetadata)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
    }

    /**
     * Authz for upload: require CREATE or UPDATE.
     *
     * @return Handler
     */
    private Handler<RoutingContext> uploadAuthz() {
        return context -> {
            final var user = context.user();
            if (user == null) {
                context.response().setStatusCode(HttpStatus.FORBIDDEN_403).end();
                return;
            }
            final var perms = this.policy.getPermissions(
                new AuthUser(
                    user.principal().getString(AuthTokenRest.SUB),
                    user.principal().getString(AuthTokenRest.CONTEXT)
                )
            );
            final var create = new ApiRepositoryPermission(
                ApiRepositoryPermission.RepositoryAction.CREATE
            );
            final var update = new ApiRepositoryPermission(
                ApiRepositoryPermission.RepositoryAction.UPDATE
            );
            if (perms.implies(create) || perms.implies(update)) {
                context.next();
            } else {
                context.response().setStatusCode(HttpStatus.FORBIDDEN_403).end();
            }
        };
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
        final boolean includeSize = context.queryParam("includeSize").stream()
            .anyMatch("true"::equalsIgnoreCase);
        final boolean includeDownloadUrl = context.queryParam("includeDownloadUrl").stream()
            .anyMatch("true"::equalsIgnoreCase);
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
                    .thenCompose(
                        entries -> {
                            if (includeSize) {
                                return ContentRest.enrichSizes(storage, prefix, entries);
                            }
                            return CompletableFuture.completedFuture(entries);
                        }
                    )
            )
            .thenAccept(
                entries -> {
                    if (includeDownloadUrl) {
                        String host = context.request().getHeader("X-Forwarded-Host");
                        if (host == null || host.isEmpty()) {
                            host = context.request().host();
                        }
                        final String base = "http://" + host + ":8080/" + rname;
                        for (final JsonObject entry : entries) {
                            if ("file".equals(entry.getString("type"))) {
                                entry.put(
                                    "downloadUrl",
                                    base + "/" + entry.getString("path")
                                );
                            }
                        }
                    }
                    context.response()
                        .setStatusCode(HttpStatus.OK_200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonArray(entries).encode());
                }
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
     * Delete repository content (artifact or directory).
     * Path is required. Use recursive=true to delete a directory and all children.
     *
     * @param context Routing context
     */
    private void deleteContent(final RoutingContext context) {
        final RepositoryName rname = new RepositoryName.FromRequest(context);
        final Validator baseValidator = new Validator.All(
            Validator.validator(new ReservedNamesVerifier(rname), HttpStatus.BAD_REQUEST_400),
            Validator.validator(new ExistenceVerifier(rname, this.crs), HttpStatus.NOT_FOUND_404)
        );
        if (!baseValidator.validate(context)) {
            return;
        }
        final String pathParam = context.queryParam("path").stream().findFirst().orElse("");
        if (pathParam.isEmpty()) {
            context.response()
                .setStatusCode(HttpStatus.BAD_REQUEST_400)
                .end("Path is required for delete");
            return;
        }
        final List<String> pathSegments = ContentRest.normalizePath(pathParam);
        if (pathSegments == null) {
            context.response()
                .setStatusCode(HttpStatus.BAD_REQUEST_400)
                .end("Invalid path: must not contain '..' or invalid segments");
            return;
        }
        final boolean recursive = context.queryParam("recursive").stream()
            .anyMatch("true"::equalsIgnoreCase)
            || "true".equalsIgnoreCase(context.request().getHeader("X-Recursive-Delete"));
        final Key key = ContentRest.prefixKey(rname.toString(), pathSegments);
        this.data.storage(rname)
            .thenCompose(
                storage -> {
                    if (recursive) {
                        return storage.deleteAll(key);
                    }
                    return storage.list(key).thenCompose(
                        children -> {
                            final int keyParts = key.parts().size();
                            final boolean hasDescendants = children.stream()
                                .anyMatch(k -> k.parts().size() > keyParts);
                            if (hasDescendants) {
                                return java.util.concurrent.CompletableFuture
                                    .failedFuture(
                                        new IllegalStateException(
                                            "Directory not empty; use recursive=true"
                                        )
                                    );
                            }
                            if (children.isEmpty()) {
                                return storage.exists(key)
                                    .thenCompose(
                                        exists -> exists
                                            ? storage.delete(key)
                                            : java.util.concurrent.CompletableFuture
                                                .completedFuture(null)
                                    );
                            }
                            return storage.delete(key);
                        }
                    );
                }
            )
            .thenAccept(
                nothing -> context.response()
                    .setStatusCode(HttpStatus.OK_200)
                    .end()
            )
            .exceptionally(
                err -> {
                    final Throwable cause = err.getCause() != null ? err.getCause() : err;
                    if (cause instanceof IllegalStateException) {
                        context.response()
                            .setStatusCode(HttpStatus.BAD_REQUEST_400)
                            .end(cause.getMessage());
                    } else {
                        context.response()
                            .setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                            .end();
                    }
                    return null;
                }
            );
    }

    /**
     * Upload repository content (artifact). Path required; body is raw binary. Overwrites if exists.
     *
     * @param context Routing context
     */
    private void uploadContent(final RoutingContext context) {
        final RepositoryName rname = new RepositoryName.FromRequest(context);
        final Validator baseValidator = new Validator.All(
            Validator.validator(new ReservedNamesVerifier(rname), HttpStatus.BAD_REQUEST_400),
            Validator.validator(new ExistenceVerifier(rname, this.crs), HttpStatus.NOT_FOUND_404)
        );
        if (!baseValidator.validate(context)) {
            return;
        }
        final String pathParam = context.queryParam("path").stream().findFirst().orElse("");
        if (pathParam.isEmpty()) {
            context.response()
                .setStatusCode(HttpStatus.BAD_REQUEST_400)
                .end("Path is required for upload");
            return;
        }
        final List<String> pathSegments = ContentRest.normalizePath(pathParam);
        if (pathSegments == null) {
            context.response()
                .setStatusCode(HttpStatus.BAD_REQUEST_400)
                .end("Invalid path: must not contain '..' or invalid segments");
            return;
        }
        final String contentLengthHeader = context.request().getHeader("Content-Length");
        if (contentLengthHeader != null) {
            try {
                final long len = Long.parseLong(contentLengthHeader.trim());
                if (len > ContentRest.DEFAULT_MAX_UPLOAD_BYTES) {
                    context.response()
                        .setStatusCode(HttpStatus.REQUEST_ENTITY_TOO_LARGE_413)
                        .end("Payload too large");
                    return;
                }
            } catch (final NumberFormatException ignored) {
                // ignore invalid Content-Length
            }
        }
        final byte[] body = context.body() != null && context.body().length() > 0
            ? context.body().getBytes()
            : new byte[0];
        if (body.length > ContentRest.DEFAULT_MAX_UPLOAD_BYTES) {
            context.response()
                .setStatusCode(HttpStatus.REQUEST_ENTITY_TOO_LARGE_413)
                .end("Payload too large");
            return;
        }
        final Key key = ContentRest.prefixKey(rname.toString(), pathSegments);
        this.data.storage(rname)
            .thenCompose(storage -> storage.save(key, new Content.From(body)))
            .thenAccept(
                nothing -> context.response()
                    .setStatusCode(HttpStatus.OK_200)
                    .end()
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
     * Get metadata for a single artifact path.
     *
     * @param context Routing context
     */
    private void getContentMetadata(final RoutingContext context) {
        final RepositoryName rname = new RepositoryName.FromRequest(context);
        final Validator baseValidator = new Validator.All(
            Validator.validator(new ReservedNamesVerifier(rname), HttpStatus.BAD_REQUEST_400),
            Validator.validator(new ExistenceVerifier(rname, this.crs), HttpStatus.NOT_FOUND_404)
        );
        if (!baseValidator.validate(context)) {
            return;
        }
        final String pathParam = context.queryParam("path").stream().findFirst().orElse("");
        if (pathParam.isEmpty()) {
            context.response()
                .setStatusCode(HttpStatus.BAD_REQUEST_400)
                .end("Path is required");
            return;
        }
        final List<String> pathSegments = ContentRest.normalizePath(pathParam);
        if (pathSegments == null) {
            context.response()
                .setStatusCode(HttpStatus.BAD_REQUEST_400)
                .end("Invalid path");
            return;
        }
        final Key key = ContentRest.prefixKey(rname.toString(), pathSegments);
        final String pathStr = String.join("/", pathSegments);
        final String name = pathSegments.isEmpty() ? "" : pathSegments.get(pathSegments.size() - 1);
        this.data.storage(rname)
            .thenCompose(
                storage -> storage.exists(key)
                    .thenCompose(
                        exists -> {
                            if (exists) {
                                return storage.metadata(key)
                                    .thenApply(
                                        meta -> {
                                            final JsonObject obj = new JsonObject()
                                                .put("name", name)
                                                .put("path", pathStr)
                                                .put("type", "file");
                                            meta.read(Meta.OP_SIZE).ifPresent(s -> obj.put("size", s));
                                            return obj;
                                        }
                                    );
                            }
                            return storage.list(key).thenApply(
                                children -> {
                                    if (children.isEmpty()) {
                                        return null;
                                    }
                                    return new JsonObject()
                                        .put("name", name)
                                        .put("path", pathStr)
                                        .put("type", "dir");
                                }
                            );
                        }
                    )
            )
            .thenAccept(
                obj -> {
                    if (obj == null) {
                        context.response().setStatusCode(HttpStatus.NOT_FOUND_404).end();
                        return;
                    }
                    context.response()
                        .setStatusCode(HttpStatus.OK_200)
                        .putHeader("Content-Type", "application/json")
                        .end(obj.encode());
                }
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
     * Search repository content by path/name prefix.
     *
     * @param context Routing context
     */
    private void searchContent(final RoutingContext context) {
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
        final String q = context.queryParam("q").stream().findFirst().orElse("");
        if (q.isEmpty()) {
            context.response()
                .setStatusCode(HttpStatus.BAD_REQUEST_400)
                .end("Search query q is required");
            return;
        }
        if (q.length() > ContentRest.MAX_SEARCH_QUERY_LENGTH
            || q.contains("..")
            || q.contains("\u0000")) {
            context.response()
                .setStatusCode(HttpStatus.BAD_REQUEST_400)
                .end("Invalid search query");
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
        final int prefixParts = prefix.parts().size();
        this.data.storage(rname)
            .thenCompose(
                storage -> storage.list(prefix)
                    .thenApply(
                        keys -> {
                            final String qLower = q.toLowerCase(java.util.Locale.ROOT);
                            return keys.stream()
                                .filter(
                                    key -> {
                                        if (key.parts().size() <= prefixParts) {
                                            return false;
                                        }
                                        final String relPath = String.join(
                                            "/",
                                            key.parts().subList(prefixParts, key.parts().size())
                                        );
                                        return relPath.toLowerCase(java.util.Locale.ROOT)
                                            .startsWith(qLower)
                                            || relPath.toLowerCase(java.util.Locale.ROOT)
                                                .contains("/" + qLower);
                                    }
                                )
                                .map(
                                    key -> {
                                        final List<String> parts = key.parts();
                                        final String name = parts.get(parts.size() - 1);
                                        final String relPath = String.join(
                                            "/",
                                            parts.subList(prefixParts, parts.size())
                                        );
                                        final String type = "file";
                                        return new JsonObject()
                                            .put("name", name)
                                            .put("path", relPath)
                                            .put("type", type);
                                    }
                                )
                                .sorted((a, b) -> a.getString("path").compareTo(b.getString("path")))
                                .skip(offset)
                                .limit(limit)
                                .collect(Collectors.toList());
                        }
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
     * Enrich file entries with size from storage metadata.
     *
     * @param storage Storage
     * @param prefix List prefix key
     * @param entries Mutable list of entries
     * @return Completion with same list (entries mutated with size)
     */
    private static CompletableFuture<List<JsonObject>> enrichSizes(
        final Storage storage,
        final Key prefix,
        final List<JsonObject> entries
    ) {
        final List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (final JsonObject entry : entries) {
            if ("file".equals(entry.getString("type"))) {
                final Key key = new Key.From(prefix, entry.getString("name"));
                futures.add(
                    storage.metadata(key)
                        .thenAccept(
                            meta -> {
                                final Long size = meta.read(Meta.OP_SIZE).orElse(-1L);
                                if (size >= 0) {
                                    entry.put("size", size);
                                }
                            }
                        )
                        .exceptionally(err -> null)
                );
            }
        }
        return CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        ).thenApply(v -> entries);
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
