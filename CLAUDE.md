# CLAUDE.md — Distributed P2P File Transfer System

Academic project. Users share files that other users download **from several peers at once**. A central tracker coordinates; file bytes go peer → peer.
The full design is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), and the slide diagrams are in [docs/diagrams/](docs/diagrams/). Read the doc before changing behaviour, and keep the doc in sync with the code.

## Fixed decisions (chosen by the course menu / the user — don't change without asking)
- **Architecture:** microservices. **Communication:** gRPC (protobuf, TLS). **DB:** MongoDB, one database per service.
- **Language:** Java 21 + **Spring Boot 3.3** (the user switched from plain Java on 2026-11-19). gRPC via `net.devh:grpc-spring-boot-starter` (`@GrpcService`, `@GrpcClient`, `@GrpcGlobalServerInterceptor`); persistence via **Spring Data MongoDB**; the peer UI via **Spring MVC** + `SseEmitter`. Each process uses ~160 MB (13 JVMs ≈ 2.1 GB on a ~7 GB machine): keep `JAVA_OPTS` in `scripts/common.sh` small, and the three services run with `web-application-type: none` (gRPC only, no Tomcat).
- Central tracker. Multi-peer parallel downloads. Resumable downloads. Accounts **and** anonymous guests.
- Owners choose visibility: `PUBLIC` / `REGISTERED` / `PRIVATE` + `allowedUsers`. Guests can only share `PUBLIC`.
- SHA-256 per chunk + whole-file hash. TLS on every gRPC channel.
- Everything runs on **one machine**. The Spring Boot services and peers run as normal `java -jar` processes (**no Docker**); only **MongoDB** runs in a Docker container (`p2p-mongo`, `mongo:7`, volume `p2p-mongo-data`, auto-restarts). The user asked for this on 2026-11-19 because the server isn't installed natively. Target: ~10 peers, files < 1 GB.
- Web UI: each peer node serves its own UI (Spring MVC, `src/main/resources/static/`, bound to 127.0.0.1). Browser ↔ own node is HTTP + SSE (browsers can't speak native gRPC). **Everything else is gRPC.**

## Layout
| Module | What | Port |
|---|---|---|
| `proto/` | `p2p.proto`: all gRPC contracts → generated stubs (`com.p2p.proto`) | |
| `common/` | JWT/ticket tokens, RSA keys, `AuthInterceptor`, `GrpcServerConfig` (status-transmitting interceptor), peer TLS channels, bitfield/hash utils | |
| `auth-service/` | accounts (bcrypt), guest login, issues user JWTs | gRPC 9091 · `auth_db` |
| `catalog-service/` | file manifests, access rules (`AccessPolicy`), search, signs Download Tickets | gRPC 9092 · `catalog_db` |
| `tracker-service/` | live peers (TTL heartbeat), swarm bitfields, `GetPeers` | gRPC 9093 · `tracker_db` |
| `peer-node/` | web UI, share, `DownloadTask` + `ChunkScheduler` (rarest-first), `PeerTransferService` | UI 8080+N · gRPC 50050+N |
| `scripts/` | build / certs / start / stop | |
| `docs/` | architecture doc + diagram generator (`docs/diagrams/build_diagrams.py`) | |

Scripts keep pids in `run/` and check that each pid is really our `java -jar` process, since a stale pid after a reboot could belong to something else.
Runtime data (git-ignored): `data/` (service keys), `peers/peer-N/` (shared, downloads, state, session), `certs/`, `logs/`, `run/`.

## Commands
```bash
./mvnw -q package -DskipTests        # build all Spring Boot jars → */target/<module>.jar
./mvnw test                          # unit tests
scripts/gen-certs.sh                 # local CA + localhost cert in certs/ (run automatically; TLS is mandatory)
scripts/start-mongo.sh               # MongoDB in Docker on localhost:27017
scripts/start-services.sh            # auth, catalog, tracker
scripts/start-peers.sh 4 [2MB]       # peers 1..4, optional upload limit (makes the demo visible)
scripts/stop-all.sh [peer-2 ...]     # all, or just the named ones (MongoDB keeps running)
scripts/kill-peer.sh 2               # demo: crash a peer with SIGKILL
```
One peer can be started in a mode that sends corrupted chunks, for the demo: `scripts/start-peers.sh 5-5 2MB --peer.debug-corrupt=true`.
Peer settings are `peer.*` properties (`PeerProperties`); `--peer.index=N` sets the name, ports and data folder.
Logs go to `logs/<name>.log`. Peer N's UI is at http://localhost:808N (peer 10 → :8090).

## Conventions
- Every request's identity comes from the JWT in gRPC metadata (`authorization: Bearer …`), read via `AuthInterceptor.current()/require()`. Never trust user ids sent in request bodies.
- Access to a file's swarm or chunks always needs a **Download Ticket** (a JWT signed by Catalog). It's checked by the Tracker (`GetPeers`, `Announce`) and by every peer (`DownloadChunk`, `GetBitfield`).
- Services verify tokens **locally** with public keys fetched once via `GetPublicKey` (`RemoteKey` beans).
- Config lives in each module's `src/main/resources/application.yml` (ports, Mongo URI, TLS files, `grpc.client.*` addresses). Spring Data index names must match the existing ones (`username_1`, `name_1`, `ownerId_1`, `lastSeen_1`, `fileId_1`, `fileId_1_peerId_1`), or startup fails on the existing database.
- Map errors to proper gRPC `Status` codes (UNAUTHENTICATED, PERMISSION_DENIED, NOT_FOUND, INVALID_ARGUMENT, ALREADY_EXISTS). Throw `StatusRuntimeException`; `GrpcSupport.startServer` installs `TransmitStatusRuntimeExceptionInterceptor` so the real status reaches the client (without it, clients see UNKNOWN).
- Keep pure logic in small, unit-testable classes (`AccessPolicy`, `ChunkScheduler`, `Bitfields`).
- Chunk size is 1 MiB, streamed in 64 KiB pieces. Downloaders ask the tracker for new peers every 20 s and the peers they know for fresh bitfields (`GetBitfield`) every 3 s. Peer state files are written atomically (temp + rename).
- When the design changes, update `docs/ARCHITECTURE.md`. Regenerate the diagrams with `python3 docs/diagrams/build_diagrams.py`; the PNGs are exported via `docs/diagrams/export.html`.

## Status
- [x] Design doc + diagrams
- [x] Code: all modules, gRPC contracts, services, peer node, UI, scripts
- [x] Migrated to Spring Boot (2026-11-19), end-to-end tests re-run and passing
- [x] Verified end to end (2026-11-19, MongoDB in Docker): multi-peer swarming, private/registered/guest access, crash + resume, failover, corrupted-chunk detection, data survives a power cut
- [ ] Presentation demo rehearsal (see ARCHITECTURE.md §9)
