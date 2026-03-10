/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.adapters.hf;

import com.artipie.asto.Content;
import com.artipie.asto.Storage;
import com.artipie.asto.cache.Cache;
import com.artipie.asto.cache.FromStorageCache;
import com.artipie.files.FileProxySlice;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.client.ClientSlices;
import com.artipie.http.client.RemoteConfig;
import com.artipie.http.client.auth.AuthClientSlice;
import com.artipie.http.rq.RequestLine;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.settings.repo.RepoConfig;

import java.net.URI;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/**
 * Hugging Face proxy slice created from config.
 * Proxies requests to https://huggingface.co (or configured remote) and caches model files.
 *
 * @since 0.1
 */
public final class HuggingFaceProxy implements Slice {

    /**
     * Default Hugging Face Hub URL when no remotes are configured.
     */
    private static final URI DEFAULT_REMOTE = URI.create("https://huggingface.co");

    /**
     * Delegate slice.
     */
    private final Slice slice;

    /**
     * Ctor.
     *
     * @param client HTTP client.
     * @param cfg Repository configuration.
     * @param events Artifact events queue
     */
    public HuggingFaceProxy(
        final ClientSlices client,
        final RepoConfig cfg,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        final RemoteConfig remote = cfg.remotes().isEmpty()
            ? new RemoteConfig(HuggingFaceProxy.DEFAULT_REMOTE, 0, null, null)
            : cfg.remoteConfig();
        final Optional<Storage> asto = cfg.storageOpt();
        this.slice = new FileProxySlice(
            AuthClientSlice.withUriClientSlice(client, remote),
            asto.<Cache>map(FromStorageCache::new).orElse(Cache.NOP),
            asto.flatMap(ignored -> events),
            cfg.name()
        );
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return this.slice.response(line, headers, body);
    }
}
