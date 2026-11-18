# NaturalStreaming backend scope

- Backend repository: tuohongwei/naturalstreaming-new, use tuohongwei Git credentials. Never push the legacy naturalstreaming history here.
- Frontend repository: taviking727-code/NaturalStreaming-App, use taviking727-code credentials. Only backend handoff documents and tools are maintained there by this collaborator.
- Requirements: docs/requirements-v1.0.md (content revision 1.2). API.md and PLAYBACK.md define integration. Voice/AI, radio/map and admin web are out of release scope.
- Development server: 13.214.63.156. Operate only /srv/naturalstreaming, /etc/naturalstreaming, naturalstreaming schema/ns_* database identities, naturalstreaming-* units and its single Nginx configuration. Follow docs/OPERATIONS.md and preserve other applications.
- Never modify home/SSH/ancestor permissions to deploy. Review every write command, preserve recovery and verify independently.
- Credentials are under ignored .local/ or server private configuration, never commit them or include upload credentials in an APK. The frontend uploader cannot publish.
- Build all modules and run real regression before delivery. test_e2e.py creates isolated fixtures; test_race.py needs explicit development-server authority. Never use real account recordings as fixtures.
