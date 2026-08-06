# BOSS Codebase

The project file tree, in the left sidebar.

An IntelliJ-style lazy tree over the host's `FileSystemDataProvider`, with multi-select, a full
context menu, and a background watcher that keeps the tree honest when files change on disk.
It also backs the `codebase_*` MCP tools.

## What it does

- **Lazy tree** with compacted middle packages, expand and collapse, and a per-node loading
  state, so a deep project does not stall on open.
- **Multi-select**: plain click, cmd-click to add, shift-click for a range from the anchor row.
- **Context menu**: New File, New Folder, Copy Path, Copy Relative Path, Reveal in Finder, Open
  in Terminal, Open With (Editor, Browser, Terminal, Default App), Rename and Delete. Bulk
  variants appear for a multi-selection ("Copy 4 Paths", "Delete 4 Items"), with confirmation.
- **Quiet refresh**: a background watcher notices directories that change underneath you - a
  git branch switch, an external rename - and refreshes them without collapsing your tree. It
  watches at most 512 directories, and only ones actually rendered.
- **Show hidden files**, as a toggle that is hidden entirely when the host binary predates the
  `showHidden` overloads rather than offering a switch that does nothing.
- **Project switching** through `ProjectDataProvider` and a directory picker. With no project
  open, the empty state offers "Open Project".

## MCP tools

| Tool | Purpose |
|---|---|
| `codebase_tree` | List the file tree under a path (depth 1-6, default 2) |
| `codebase_read` | Read a text file, truncated at 50,000 characters |
| `codebase_write` | Create or overwrite a text file |
| `codebase_open` | Open a file in the BOSS editor |
| `codebase_projects` | List recent projects |
| `codebase_select_project` | Open or select a project by path |

On a host too old for `include_hidden`, `codebase_tree` still answers but adds a `note:` line
saying the flag was ignored, rather than silently returning a filtered tree.

## Requirements

- BOSS >= 9.2.20, boss-plugin-api >= **1.0.66** (`minApiVersion`), needed for the
  `FileSystemDataProvider.scanDirectory(path, showHidden)` overloads.
- Host providers: `fileSystemDataProvider`, `contextMenuProvider`, `directoryPickerProvider`,
  `splitViewOperations`, `projectDataProvider`.
- No external binaries.

All scanning goes through the host provider. The plugin-local `LocalFileScanner` was
deliberately retired, so there is no second code path that can disagree with the host.

## Build

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-codebase-*.jar ~/.boss/plugins/
./gradlew test
```

See [AGENTS.md](AGENTS.md) for architecture and conventions.

## License

Proprietary - Risa Labs Inc.
