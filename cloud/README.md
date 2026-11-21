# KataGo Cloud Engine

A self-hostable, authenticated HTTP service that runs the real KataGo analysis engine. The first implementation is deliberately conservative: one persistent KataGo worker, serialized analysis requests, bounded visit budgets, and a private-by-default network binding.

## Current scope

- Real KataGo analysis-mode subprocess; not a mock engine and not phone-side computation.
- Complete board state: board dimensions, rules, komi, initial setup stones, side to move and move history.
- Versioned JSON API, request IDs, model SHA-256, visit budgets and analysis output.
- Bearer-token authentication, bounded payloads/searches, request serialization and worker restart after timeout.
- CPU Eigen build as the portable baseline. A CUDA worker image and multi-worker scheduling are separate qualification steps; do not label this CPU image as GPU.
- No public endpoint is exposed by the default Compose file.

## Requirements

Docker Engine with Compose plugin. For the first smoke test, use a host with enough memory for the chosen KataGo network. Model files and analysis configuration are intentionally not committed.

## Prepare files

1. Copy `.env.example` to `.env` and set a long random `KATAGO_API_TOKEN`. Keep `.env` private.
2. Run `bash download_model.sh` to fetch the official large b11c768 neural-network model into `models/model.bin.gz` (about 212 MB). The model is separate from the Android `.kdmodel` file; the two formats are not interchangeable.
3. The image includes KataGo's official `analysis_example.cfg` as a working baseline. It reports win rates and score lead from Black's perspective; keep `reportAnalysisWinratesAs = BLACK` in any custom config so the Android app interprets evaluations correctly. For performance tuning, copy it out, adjust `numSearchThreadsPerAnalysisThread`, `nnMaxBatchSize`, and `nnCacheSizePowerOfTwo` for the actual host, then mount your tuned config read-only. Do not blindly copy GPU settings into a CPU host.
4. Build and start:

```sh
docker compose --env-file .env up --build -d
docker compose --env-file .env logs -f
```

The service listens only on `127.0.0.1:8080`. This is intentional. For access from the phone over the internet, place an HTTPS reverse proxy in front, configure TLS and rate limits, and expose only that proxy. Never expose port 8080 directly or commit the token.

## Public HTTPS endpoint

The default Compose file binds port 8080 to loopback only. To expose the API safely to the phone:

1. Set `CADDY_DOMAIN` in `.env` to a DNS name whose A/AAAA records point to this host.
2. Allow inbound TCP 80/443 (and optionally UDP 443) on the host firewall.
3. Start the CPU service with `docker compose --env-file .env -f docker-compose.yml -f docker-compose.public.yml up --build -d`, or the CUDA service with `docker compose --env-file .env -f docker-compose.cuda.yml -f docker-compose.public.yml up --build -d`.
4. Caddy obtains and renews the HTTPS certificate automatically. The proxy exposes only `/v1/*`; the FastAPI docs and other paths remain closed.
5. Enter `https://<your-domain>` and the same API token in KataGo Master.

A public DNS name and a host reachable from the internet are required for this direct HTTPS setup. If you do not have either, the server can still run privately, but the phone cannot reach it over the public internet yet. Do not disable TLS or place the API token in a URL.

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

The service config reports win rates and score lead from Black's perspective. The response includes `source: cloud`, the matching request ID, model checksum, actual completed visits, elapsed time, best move and KataGo analysis details. The client must still validate the returned move against its current board before applying it.

## Important limits

- This initial service is a working CPU-first foundation, not yet a deployed public cloud and not yet connected to the Android app.
- The Compose service uses one worker to avoid concurrent writes to a single analysis process. Scaling requires a worker pool with one process per worker and bounded queueing.
- GPU acceleration is available as a separate `Dockerfile.cuda` build. It requires a compatible NVIDIA GPU, host driver, NVIDIA Container Toolkit and runtime qualification. It is not enabled by the CPU Dockerfile. Start it with `docker compose --env-file .env -f docker-compose.cuda.yml up --build -d`.
- Free compute hosts can suspend or enforce quotas. No always-on GPU capacity is promised.
- Use a reverse proxy with HTTPS before remote use. The API token is a secret and must never be embedded as a shared constant in the APK.
- Compare strength using identical model, rules, komi and search budgets; visits/second alone are not a strength measurement.

## Tests

```sh
python3 -m pip install -r requirements.txt pytest
PYTHONPATH=. pytest -q
```
