# Architecture and validation notes

Target: WebStorm 2026.1.2 (build 261.24374.125). These notes describe the current source tree and the validation performed for version 0.5.17.

## Findings addressed in 0.5.17

- Content disposal called `dispose()` directly, leaving listeners and other Disposer children attached. Content is now created once, registered under the project, and closed through Disposer. Commit sessions are registered under their panel.
- Secondary mouse clicks could toggle checkboxes, grouping, or sort order. These operations now accept primary clicks only.
- A staging workflow could receive a mixture of Git staging rows and regular VCS items, silently omitting the latter. Mixed selections are rejected explicitly. A selection consisting only of regular VCS items uses the change-list workflow even when Git staging is available elsewhere in the project.
- Persisted hidden paths containing null entries could fail during settings reads. Loading now removes invalid/duplicate entries while preserving legacy preference migration.
- Normalizing backslashes on Unix could make a valid filename collide with a different path. Separator normalization now follows the host filesystem.
- Git tracker updates received during commit execution were skipped. The staging panel now resynchronizes when execution ends.
- Early/partial VCS snapshots could clear persisted hidden paths. Initial loading waits for the first VCS update, and refreshes defer while an update is active.
- Standalone regression mains were not discovered by Gradle's standard test task. A JUnit suite now runs them under `check` with assertions enabled.

## Commit architecture

Smart Changes owns its commit sessions. The change-list adapter exposes the native inclusion model directly; it does not depend on an invisible Changes tree. The Git adapter uses a staging workflow with commit-all disabled. Git-specific classes stay behind the optional Git integration.

Background refresh results cross an explicit EDT boundary before touching the editor, inclusion model, or workflow. Expired/disposed preparation callbacks are ignored. Preparation timeouts and asynchronous failures produce visible errors. Workflow busy state prevents duplicate button submissions.

## Validation scope and limits

Automated regressions cover direct commit inclusion, inclusion-model detachment, EDT dispatch, stale callbacks, asynchronous errors, partial staging policy, conflict exclusion, hidden-file identity, settings migration, sorting, grouping, and collapse behavior.

These tests exercise data adapters and policies without booting an IDE. They do not prove the native end-to-end commit/push UI, Git hooks, credentials, multiple repository push dialogs, or dynamic plugin unloading. Native commit workflows use implementation APIs whose compatibility must be checked against each targeted IDE build. A successful structure or binary compatibility check does not prove runtime behavior.

## Verification results

- Standard Gradle `check`: four JUnit regression groups passed with assertions enabled.
- Plugin archive creation and `verifyPluginStructure`: passed.
- Plugin Verifier 1.411: binary compatible with WS-261.24374.125. Its task fails on internal API usage; this is an unresolved compatibility risk, not a clean verifier pass. No failure category was suppressed.
- Declared compatibility is capped at 261.*. A newer IDE branch requires a fresh API audit and verification before lifting that cap.
