# NaturalStreaming Backend

Java 26 / Spring Boot 4 / MySQL backend for local theme-based background-audio mixing.

- Requirements: [docs/requirements-v1.0.md](docs/requirements-v1.0.md)
- Frontend handoff: [docs/HANDOFF.md](docs/HANDOFF.md)
- API contract: [docs/API.md](docs/API.md)
- Playback contract: [docs/PLAYBACK.md](docs/PLAYBACK.md)
- Deployment and isolation: [docs/OPERATIONS.md](docs/OPERATIONS.md)

Build: `mvn -f backend/pom.xml clean verify` with JDK26. Windows local helper: `ops/build.ps1`.

Modules: core, api, admin, files, migrations. Environment credentials are required; never commit `.local/` or `.env` files. Test scripts read NS_CREDENTIALS_FILE. The frontend upload credential is separate from publication authority.

This new repository contains only this implementation. No history or source from the legacy naturalstreaming repository is imported.
