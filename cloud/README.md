# KataGo Cloud Engine

A self-hostable, authenticated HTTP service that runs the real KataGo analysis engine. The first implementation is deliberately conservative: one persistent KataGo worker, serialized analysis requests, bounded search budgets, and a private-by-default network binding.

## Current scope

- Real KataGo analysis-mode subprocess; not a mock engine and not phone-side computation.
- Complete board state: board dimensions, rules, komi, initial setup stones, side to move and move history.
- Versioned JSON API, request IDs, model SHA-256, visit/time budgets and analysis output.
- Bearer-token authentication, bounded payloads/searches, request serialization and worker restart after timeout.
- CPU Eigen build as the portable baseline. A CUDA worker image and multi-worker scheduling are separate qualification steps; do not label this CPU image as GPU.
- No public endpoint is exposed by the default Compose file.

## Requirements

Docker Engine with Compose plugin. For the first smoke test, use a host with enough memory for the chosen KataGo network. Model files and analysis configuration are intentionally not committed.

## Prepare files

1. Copy `.env.example` to `.env` and set a long random `KATAGO_API_TOKEN`. Keep `.env` private.
2. Put a KataGo-compatible neural-network model at `models/model.bin.gz`.
3. Put a matching analysis configuration at `data/analysis.cfg`. Start from the official KataGo analysis/GTP example configuration and tune `numSearchThreads`, `nnMaxBatchSize`, and `nnCacheSizePowerOfTwo` for the actual host RAM/CPU. Do not blindly copy GPU settings into a CPU host.
4. Build and start:

```sh
docker compose --env-file .env up --build -d
docker compose --env-file .env logs -f
```

The service listens only on `127.0.0.1:8080`. This is intentional. For access from the phone over the internet, place an HTTPS reverse proxy in front, configure TLS and rate limits, and expose only that proxy. Never expose port 8080 directly or commit the token.

## API

- `GET /v1/healthz`: non-sensitive liveness probe.
- `GET /v1/readyz`: authenticated engine/model readiness probe.
- `POST /v1/analyze`: authenticated KataGo analysis request.

Example:

```sh
curl -sS http://127.0.0.1:8080/v1/analyze \
  -H "Authorization: Bearer $KATAGO_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "request_id": "smoke-001",
    "board_x_size": 19,
    "board_y_size": 19,
    "rules": "chinese",
    "komi": 7.5,
    "to_move": "B",
    "moves": [],
    "initial_stones": [],
    "max_visits": 1200
  }'
```

The response includes `source: cloud`, the matching request ID, model checksum, actual completed visits, elapsed time, best move and KataGo analysis details. The client must still validate the returned move against its current board before applying it.

## Important limits

- This initial service is a working CPU-first foundation, not yet a deployed public cloud and not yet connected to the Android app.
- The Compose service uses one worker to avoid concurrent writes to a single analysis process. Scaling requires a worker pool with one process per worker and bounded queueing.
- GPU acceleration requires a separately compiled KataGo CUDA backend, a compatible NVIDIA host/driver and runtime qualification. It is not enabled by the CPU Dockerfile.
- Free compute hosts can suspend or enforce quotas. No always-on GPU capacity is promised.
- Use a reverse proxy with HTTPS before remote use. The API token is a secret and must never be embedded as a shared constant in the APK.
- Compare strength using identical model, rules, komi and search budgets; visits/second alone are not a strength measurement.

## Tests

```sh
python3 -m pip install -r requirements.txt pytest
PYTHONPATH=. pytest -q
```
