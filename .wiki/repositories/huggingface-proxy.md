## Hugging Face Proxy

Hugging Face proxy repository forwards download requests to the [Hugging Face Hub](https://huggingface.co) (or a custom remote) and caches model files in the configured storage. **Clients point `HF_ENDPOINT` at your Artipie repository URL**; the proxy then uses one remote (e.g. `https://huggingface.co`) as the upstream. Clients use the standard Hugging Face URL pattern `{HF_ENDPOINT}/{repo_id}/resolve/{revision}/{filename}` so that requests go to Artipie, which fetches from the Hub when needed.

Only **models** are supported. Datasets and Spaces use different path prefixes and are not supported by this proxy.

### Configuration

```yaml
repo:
  type: huggingface-proxy
  storage:
    type: fs
    path: /var/artipie/data
  http_client:
    connection_timeout: 120000   # recommended for large models (ms)
  remotes:                        # optional; if omitted, defaults to https://huggingface.co
    - url: https://huggingface.co
      username: alice             # optional, for gated models when Hub challenges
      password: secret
```

- **storage** (optional): If set, downloaded files are cached. Recommended for production so that repeated downloads are served from cache. Prefer file storage (e.g. NFS/EFS) for large caches.
- **remotes** (optional): If empty or omitted, the proxy uses `https://huggingface.co`. At most one remote is used; you can set `url` and optionally `username` and `password` for Basic/Bearer when the Hub requires authentication (e.g. gated models).
- **http_client** (optional): Connection timeout and other HTTP client settings. For large models, set `connection_timeout` to at least 120000 ms.

### Client configuration

Point the Hugging Face client at **your Artipie repository URL** (the URL you use to reach this proxy), not at huggingface.co. The proxy is configured with one remote, `url: https://huggingface.co`, which is the upstream Hub; clients talk only to Artipie.

Set the client environment variables to your Artipie proxy URL:

```bash
# Your Artipie proxy URL (host and repo name you configured)
export HF_ENDPOINT="http://localhost:8080/my-huggingface-proxy"
export HF_HUB_DOWNLOAD_TIMEOUT=120
export HF_HUB_ETAG_TIMEOUT=1800
```

When Artipie requires authentication, include credentials in the URL:

```bash
export HF_ENDPOINT="http://<token>:<password>@localhost:8080/my-huggingface-proxy"
```

Use user tokens rather than personal credentials where possible.

### Example: download a model

```bash
pip install huggingface_hub
export HF_ENDPOINT="http://localhost:8080/my-huggingface-proxy"
export HF_HUB_DOWNLOAD_TIMEOUT=120
python -c "
from huggingface_hub import snapshot_download
snapshot_download(repo_id=\"bert-base-uncased\", repo_type=\"model\", local_dir=\"./model\")
"
```

### Notes

- The first time a model is requested through the proxy, it is fetched from the Hub and stored in Artipie; subsequent requests are served from cache when storage is configured.
- For gated models, configure `username` and `password` in `remotes` with a Hugging Face token so that the proxy can authenticate to the Hub when challenged.
- Large models can take a long time to download; increase timeouts on both the proxy (`http_client.connection_timeout`) and the client (`HF_HUB_DOWNLOAD_TIMEOUT`, `HF_HUB_ETAG_TIMEOUT`) as needed.
