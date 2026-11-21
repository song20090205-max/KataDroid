# Compute modes: local, cloud and hybrid

Status: architecture specification; this document does not claim that cloud execution is implemented.

## User-facing modes

- **Local**: the existing in-process KataGo engine owns search and inference. It works offline and is the stability baseline.
- **Cloud**: a configured remote KataGo service owns search. The Android app sends a complete, validated position and receives a legal move plus analysis. Cloud mode must report a connection/service error rather than silently pretending that a local search was a cloud result.
- **Hybrid**: cloud search is preferred; local KataGo remains available as a fallback when the remote service is unavailable or times out. The UI must always identify the source of the current result.

Hybrid is intentionally not described as merging two independent MCTS visit trees. KataGo's search trees, neural-network versions, rules, komi, and search parameters must match before visit statistics can be meaningfully combined. A future cooperative-search protocol would need explicit support from both engines.

## Remote service contract

The app must not assume that a generic HTTP URL is a KataGo engine. The cloud connector needs a documented, versioned protocol and a compatible server implementation.

Minimum request fields:

- protocol version and request ID;
- board size, rules, komi, player to move;
- complete move history and setup stones (not only the last move);
- requested search budget or time limit;
- model identifier and optional expected model checksum.

Minimum response fields:

- matching request ID and protocol version;
- selected move or pass;
- model identifier/checksum and server-side search budget;
- completed visits, elapsed time, and optional principal variation / win-rate data;
- explicit error status.

The client must validate response IDs, move legality, board dimensions, rules and komi before accepting a result. Cancellation, timeout, stale responses and app backgrounding must not publish a move for a newer board position.

## Security and reliability

- Use HTTPS/WSS only; never transmit credentials in query strings.
- Store any user-supplied token using Android Keystore-backed encrypted storage; redact it from logs and diagnostics.
- Do not embed a shared cloud credential in the APK.
- Bound payload sizes, request duration and concurrent jobs.
- Treat cloud output as untrusted until validated against the current local board state.
- Keep local engine operation independent of network availability.
- Do not promise free GPU capacity. A free cloud notebook is not a stable always-on inference server and may disconnect or enforce quotas.

## Rollout gates

1. Preserve the existing local engine as the known-good baseline.
2. Add the mode preference and UI only together with real engine routing and truthful connection/source status.
3. Implement and test the remote protocol/server independently before enabling Cloud mode in production.
4. Test malformed responses, disconnects, timeouts, cancellation, stale responses, illegal moves and fallback.
5. Compare Local, Cloud and Hybrid under identical positions, rules, model and time/visit budgets. Report win rate and completed visits, not visits/s alone.

## Acceptance criteria

- Local mode remains fully usable offline.
- Cloud mode never labels local output as remote output.
- Hybrid mode switches to local only after a visible, recorded cloud failure/timeout.
- No mode can crash the app on network loss, malformed server data or cancellation.
- The app exposes the active compute source and model for every accepted move.
