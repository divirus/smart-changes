<p align="center">
  <img src="src/main/resources/META-INF/pluginIcon.svg" width="80" height="80" alt="Smart Changes logo">
</p>

<h1 align="center">Smart Changes</h1>

<p align="center">A focused changes view for WebStorm. Sort, filter, stage, and commit from one panel.</p>

<p align="center">
  <a href="https://github.com/divirus/smart-changes/actions/workflows/build-plugin.yml"><img src="https://github.com/divirus/smart-changes/actions/workflows/build-plugin.yml/badge.svg" alt="Build plugin"></a>
  <a href="https://github.com/divirus/smart-changes/releases"><img src="https://img.shields.io/github/v/release/divirus/smart-changes" alt="Latest release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT license"></a>
</p>

Smart Changes is an open-source WebStorm plugin that adds a sortable changes view to the Commit tool window. It helps review and commit selected changes without switching to the native Changes tab.

## Install

1. Download the plugin ZIP from [Releases](https://github.com/divirus/smart-changes/releases/latest).
2. In WebStorm, open **Settings → Plugins → ⚙ → Install Plugin from Disk** and select the ZIP.
3. Restart the IDE if prompted, then open **Commit → Smart Changes**.

## Preview

<p align="center">
  <img src="docs/images/smart-changes.png" alt="Smart Changes tab in the WebStorm Commit tool window" width="640">
</p>

## Features

- Sort changed files by name, file type, path, or modification time; optionally group them by extension.
- Choose which columns to show from the header context menu. Column visibility is saved per project.
- Hide unrelated changes from the main list and restore them from a separate hidden list. Hidden paths and view preferences are saved per project.
- In Git staging mode, show separate staged and unstaged rows when a file has changes in both areas. Stage and unstage through native IDE actions.
- Open diffs, copy paths, and use native VCS context actions.
- Use the embedded commit message editor, commit checks, progress panel, and Commit or Commit and Push workflows.
- Keep non-Git VCS changes available. Git staging integration is optional.

## Compatibility

- WebStorm 2026.1+ (build 261+; build SDK: 2026.1.2)
- Java 21 to build the plugin

## Usage

- Click **Name**, **Type**, **Path**, or **Modified** to sort; click again to reverse the order. Modified starts with the newest files.
- Right-click the table header to hide or restore optional columns.
- Use **Group by** to group files by extension.
- Select files and choose **Hide selected** to remove them from the main view. Use **Show hidden** to restore them.
- In Git staging mode, use checkboxes to stage or unstage files, then enter a message and choose **Commit** or **Commit and Push**.

Modification-time sorting uses filesystem timestamps captured with each snapshot. Deleted files have no timestamp; this is not Git commit or staging time.

## Build

The Gradle wrapper downloads the configured WebStorm SDK, so a local WebStorm installation is not required to build the project.

```sh
./gradlew buildPlugin
```

To build against an installed IDE without downloading the SDK:

```sh
./gradlew test buildPlugin -PlocalIdePath=/path/to/WebStorm
```

Run `./gradlew test` for regression tests and `./gradlew verifyPlugin` for API compatibility checks.

The plugin ZIP is created in `build/distributions/`.

To install it in WebStorm, open **Settings → Plugins**, choose **Install Plugin from Disk**, and select the ZIP.

## Behavior notes

- Hiding a change only affects the Smart Changes view. It does not edit files or change their staging state. If hidden changes are staged, the plugin warns before opening the commit workflow.
- The plugin registers a Smart Changes tab inside the Commit tool window. It refreshes as the IDE reports VCS or staging-area changes.
- The build targets WebStorm 2026.1.2. The plugin descriptor has no upper build limit. Compatibility with newer IDE versions requires Plugin Verifier and manual testing.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

## Support and contributing

Report bugs and request features in [GitHub Issues](https://github.com/divirus/smart-changes/issues). For bugs, include your WebStorm version, plugin version, steps to reproduce, and relevant IDE log excerpts with private data removed.

Pull requests are welcome. Keep changes focused and include verification steps. Maintained by [@divirus](https://github.com/divirus).

See [publishing instructions](docs/PUBLISHING.md) for the JetBrains Marketplace workflow.
