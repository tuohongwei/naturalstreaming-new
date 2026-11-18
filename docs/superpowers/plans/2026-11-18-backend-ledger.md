# Backend execution ledger

- Task 1: Established fresh orphan main for new empty backend repository. Old remote objects remain local only, not ancestors of delivery. Build began RED with missing policy classes; policy/validator implementation GREEN.
- Tasks 2â€“4: Implemented isolated identity, draft, media, catalog, download and migration services. Public regression is real HTTP/MySQL; initial missing service, null input and later security regressions observed RED, then fixed.
- Task 5: API/HANDOFF/PLAYBACK and uploader/smoke scripts complete. Secret values excluded from Git; private frontend scope is upload only.
- Task 6: Isolated server schema/identities/processes and HTTPS active; unrelated config hashes/PIDs independently preserved. Two original sample themes fully imported as drafts.
- Ruling: Content annotation/license is not invented to make sample publication pass. Published clearly named synthetic demo for immediate frontend integration; cost is that real music needs content owner input before publication.
- Ruling: Offline playback cannot instantly receive remote kicks. Enforce current device on every online request and 60-second client checks; cost is temporary offline coexistence explicitly agreed in requirements.
- Ruling: Use half-duration maximum overlap to guarantee the approved double-clip mixer budget. Cost is rejection of unusually long-overlap future packs rather than unsafe accumulation; existing packs remain compatible.
- Ruling: Final output uses conservative peak-budget scaling documented in PLAYBACK.md. Cost is lower loudness for high-gain many-track mixes; real device listening/performance remains frontend acceptance.
- Final review: independent read-only reviewer completed. Fixed Critical stale snapshot with locking version reads and current credential reads; test_race.py deterministic REDâ†’GREEN, two transaction interleavings pass.
- Final review: fixed Important media probe/container/decoded duration, input path and zip limits, credential binary identity, overlap bound, per-release private helpers and serialized deployment. JUnit4, input-boundary3 and public regression11 groups pass. Duplicate-scope issue regraded as credential usability defect and normalized, covered by public regression.
- Review boundary ruling: reviewer did not validate content rights or Android audio/hardware. Those are owned by content/front-end deliverables, not replaced by backend interface tests; cost is pending real-content publication and device acceptance, recorded in DELIVERY.md.
- Review correction: catalog_event.theme_id already ascii_bin; withdrawn finding needs no migration.
- Deferred minors: none.
- Task 7: Backend main and frontend main pushed with their distinct identities; fetched both and byte-compared remote API, handoff, uploader and backend sources. Final ops/verify.ps1 passed the complete suite; unrelated server baselines still match.
