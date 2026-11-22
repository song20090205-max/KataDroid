# KataGo Cloud Engine

This directory contains an optional, self-hostable KataGo analysis API. It is **not a free hosted service** and is not deployed by this repository.

## Current project decision

- The Android app uses `HYBRID` as its default compute mode: it tries Read19 when a valid `kg_live_` analysis key is configured and falls back to the selected local model if the cloud connection fails. Without a valid key, it stays on local analysis.
- Do not use Kaggle Notebooks, Colab runtimes, or other interactive notebook sessions as a persistent API host, public proxy, or tunnel-backed server.
- The former Kaggle + Cloudflare Quick Tunnel workflow has been retired. The Kaggle notebook is now an archival notice only.
- Read19 is the supported hosted cloud provider in the Android app. Its documented KataGo JSON WebSocket URL is `wss://read19.com/v1/katago/<API_KEY>`; the API key is embedded in the URL path, not sent as an Authorization header. Treat the complete WSS URL as a secret and never log, screenshot, or commit it. The app's endpoint setting accepts the HTTPS base `https://read19.com` and constructs the WSS URL internally.
- No always-on free GPU host is assumed or promised. Do not create replacement accounts or work around a platform suspension.
- The exact cloud model configured by this project remains `kata1-tf3-b11c768-s11003M-d5973M-7gres`; do not silently substitute another network.

## What is included

- A FastAPI service that invokes the real KataGo analysis engine.
- Complete 19x19 board state, rules, komi, setup stones and move history.
- Versioned JSON API, request IDs, model SHA-256, visit budgets and analysis output.
- Bearer-token authentication, bounded payloads/searches, serialized requests and worker restart after timeout.
- CPU Eigen and optional CUDA Docker build definitions.
- HTTPS reverse-proxy configuration for a host that the operator owns and is authorized to administer.

The self-hosted FastAPI service is separate from the Read19 adapter and from the Android app's bundled LiteRT model files. The Read19 server chooses its own cloud model; it does not use the locally selected model or guarantee the pinned network listed above. The formats are not interchangeable.

## Requirements for a future self-hosted deployment

Only proceed when you have a host you are authorized to use, adequate memory/compute, and a network setup that permits this service. A GPU deployment additionally requires a compatible NVIDIA GPU, host driver and NVIDIA Container Toolkit. A public endpoint requires a domain and HTTPS reverse proxy. Do not expose port 8080 directly and do not put the API token in the APK, source control, a URL, or a public notebook.

1. Copy `.env.example` to `.env` on the host and set a private, long `KATAGO_API_TOKEN`.
2. Run `bash download_model.sh` to fetch the pinned official model into `models/model.bin.gz`.
3. For a CPU-only private smoke test, run:

   ```sh
   docker compose --env-file .env up --build -d
   docker compose --env-file .env logs -f
   ```

   The service binds to `127.0.0.1:8080` by default and is not remotely reachable.
4. For a remotely accessible service, configure the included Caddy HTTPS overlay on a host/domain you control. Review firewall, authentication, request limits and host-provider terms before enabling access.
5. In KataGo Master, enter the HTTPS endpoint and API token only after the service has passed health, readiness and analysis tests.

## API

- `GET /v1/healthz`: liveness probe.
- `GET /v1/readyz`: authenticated engine/model readiness probe.
- `POST /v1/analyze`: authenticated KataGo analysis request.

The response identifies `source: cloud`, request ID, model checksum, completed visits, elapsed time, best move and analysis details. The Android client must validate a returned move against the current board before applying it.

## Safety and reliability limits

- This repository does not provision a cloud host, GPU, domain, tunnel, API token or persistent endpoint.
- Interactive notebook GPU quotas and sessions are not a substitute for a server deployment.
- Keep the API token private and rotate it if it is exposed.
- Use HTTPS for remote access; never disable TLS for convenience.
- Keep request concurrency and visit budgets bounded.
- Remote analysis may be unavailable. The app should continue to support local analysis without depending on a remote endpoint.
- Compare playing strength using identical model, rules, komi and search budgets; visits/second alone is not a strength measurement.

## Tests

```sh
python3 -m pip install -r requirements.txt pytest
PYTHONPATH=. pytest -q
```
