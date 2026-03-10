# Artipie management Rest API

Artipie provides Rest API to manage [repositories](./Configuration-Repository), [users](./Configuration-Credentials) 
and [storages aliases](./Configuration-Storage#Storage-Aliases). API is self-documented with [Swagger](https://swagger.io/)
interface, Swagger documentation pages are available on URLs `http://{host}:{api}/api/index.html`.

In Swagger documentation have three definitions - Repositories, Users and Auth Token. You can switch
between the definitions with the help of "Select a definition" listbox.

<img src="https://user-images.githubusercontent.com/14931449/193015387-3e25f937-7f23-4b27-884c-f183ca9dc8a0.png" alt="Swagger documentation" width="400"/>

All Rest API endpoints require JWT authentication token to be passed in `Authentification` header. 
The token can be issued with the help of `POST /api/v1/oauth/token` request on the "Auth Token" 
definition page in Swagger. Once token is received, copy it, open another definition, press 
"Authorize" button and paste the token. Swagger will add the token to any request you perform.

## Manage repository API

Rest API allows to manage repository settings: read, create, update and remove operations are supported. 
Note, that jsons, accepted by Rest endpoints, are equivalents of the YAML repository settings. Which means, 
that API accepts all the repository specific settings fields which are applicable to the repository. 
Choose repository you are interested in from [this table](./Configuration-Repository#Supported-repository-types) 
to learn all the details. 

Rest API provides method to rename repository `PUT /api/v1/{repo_name}/move` (`{repo_name}` is the 
name of the repository) and move all the data
from repository with the `{repo_name}` to repository with new name (new name is provided in json 
request body, check Swagger docs to learn the format). Response is returned immediately, but data 
manipulation is performed in asynchronous mode, so to make sure data transfer is complete, 
call `HEAD /api/v1/{repo_name}` and verify status `404 NOT FOUND` is returned.

## Content API (browse artifacts)

You can list repository content (browse artifacts) with `GET /api/v1/repository/{rname}/content`. 
Listing is **one level only** (direct children of the given path); there is no deep recursion. Use `path` to drill down (e.g. `path=subdir` then `path=subdir/nested`).

Query parameters:

- **path** (optional): Path within the repository; default is root. Must not contain `..` or invalid segments.
- **limit** (optional): Maximum number of entries to return (default 100, max 1000). Use with **offset** for pagination on large directories.
- **offset** (optional): Number of entries to skip for pagination (default 0).

Response is a JSON array of entries, each with `name`, `path`, `type` (`file` or `dir`), and optionally `size` (bytes).

**Large repos:** List can be slow on very large storages. Always use `limit` and `offset` when paging; avoid requesting huge directories in one call.

**Supported repository types:** Content listing works for types with a listable storage layout: **file**, **file-proxy**, and **huggingface-proxy** (and other proxies that cache to storage). For file-proxy and huggingface-proxy, the list shows cached paths (same as a file repo from storage perspective). Other types (e.g. Maven, Docker) may return empty or unsupported behaviour; support can be added per type later.

All content endpoints require the same JWT authentication and repo READ permission as other repository API calls. The dashboard UI that uses this API (artifact browser, upload/delete from UI) is implemented in the [Artipie front](https://github.com/artipie/front) repository (Phase 2); this repo only provides the API.

## Health endpoints (container deployment)

For container and orchestrator health checks, the following endpoints are available:

| Port   | Path           | Purpose                                                                 |
|--------|----------------|-------------------------------------------------------------------------|
| 8080   | `GET /.health/live` | **Liveness**: process is running; no storage or dependency checks. Returns `200` and `{"status":"up"}`. |
| 8080   | `GET /.health`      | **Readiness**: checks config storage; returns `200` with `[{"storage":"ok"}]` or `503` if storage fails. |
| 8086   | `GET /api/health`   | **API liveness**: management API process is up. Returns `200` and `{"status":"up"}`. No authentication. |

Use `/.health/live` for Kubernetes `livenessProbe` so the container is only restarted when the process is dead. Use `/.health` on the repo port for `readinessProbe` so traffic is stopped when storage is unavailable. Use `/api/health` on the API port (8086) for API server liveness. These endpoints do not expose config, repo names, or tokens.

Example Kubernetes probes:

```yaml
# Repo port (8080)
livenessProbe:
  httpGet:
    path: /.health/live
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 10
readinessProbe:
  httpGet:
    path: /.health
    port: 8080
  initialDelaySeconds: 5
  periodSeconds: 5

# API port (8086)
livenessProbe:
  httpGet:
    path: /api/health
    port: 8086
  initialDelaySeconds: 10
  periodSeconds: 10
```

## Storage aliases
[Storage aliases](./Configuration-Storage#Storage-Aliases) can also be managed with Rest API, 
there are methods to read, create, update and remove aliases. Note, that concrete storage settings 
depends on storage type, Rest API accepts all the parameters in json format equivalent to the 
YAML storages setting. 

## Users management API

Use Rest API to obtain list of the users, check user info, add, update, remove or deactivate user. Also, it's
possible to change password by calling `POST /api/v1/{username}/alter/password` method providing
old and new password in json request body.

Users API is available if either `artipie` credentials type or `artipie` policy is used.  

### Roles management API

Rest API endpoint allow to create or update, obtain roles list or single role info details, 
deactivate or remove roles. Roles API endpoints are available if `artipie` policy is used.

Check [policy section](./Configuration-Policy) to learn more about users or roles info format.

## Out of scope / notes

- **Dashboard (Phase 2):** The artifact browser, search, and CRUD-from-UI are implemented in the [Artipie front](https://github.com/artipie/front) repo. This Artipie server repo only defines and implements the REST API (including the Content API and health endpoints).
- **Large repos:** List and future search may be slow for huge storages; use `limit`/`offset` and document usage; v1 lists one level only (no deep recursion).
- **Proxies (e.g. huggingface-proxy, file-proxy):** Content list shows cached paths; behaviour is the same as a file repo from storage perspective.