# BOSS Codebase

FILES, SEARCH and GIT, in the left sidebar.

Three tabs over the host's providers: an IntelliJ-style lazy file tree, global search and
replace, and a changes accordion with a true lane commit graph. It backs the `codebase_*`,
`git_*` and `project_*` MCP tools, and as of 1.6.0 it supersedes the standalone git-status and
git-log plugins.

## FILES

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

## SEARCH

Global content search and replace over the host's `ProjectSearchProvider`.

- **Include and exclude globs**, both applied *inside* the engine. Exclusion used to be a
  filter over the returned list, which meant the result cap was reached before the exclude
  ran - so excluding a busy directory quietly returned fewer results than existed.
- **Replace** is dry-run first: the confirmation sheet states what will change before anything
  is written, and refuses to run at all while the search is capped, because "replace all" over
  a truncated result set is a partial write with no error.
- Open buffers are searched and edited through the editor's undoable path; closed files are
  written to disk.

## GIT

- **Changes accordion** over `GitDataProvider`: staged, unstaged and untracked groups, as a
  list or a compacted tree, with stage / unstage / discard / open-diff row actions. Every
  destructive action goes through a confirmation sheet.
- **Commit graph** with a real lane layout, ref pills, a branch picker, and row actions for
  checkout, revert, cherry-pick and show-diff.
- **Commit box**, with an optional AI-drafted message built from what is actually staged. A
  draft, never an automatic commit.
- **Agent Review** hands the fluck-agent a brief plus the uncommitted diff, bounded to an
  inline budget. Commits already on the branch are not attached - the brief points the agent
  at `git_diff_between` for those.

## MCP tools

`codebase_*` - the file tree:

| Tool | Purpose |
|---|---|
| `codebase_tree` | List the file tree under a path (depth 1-6, default 2) |
| `codebase_read` | Read a text file, truncated at 50,000 characters |
| `codebase_write` | Create or overwrite a text file |
| `codebase_open` | Open a file in the BOSS editor |
| `codebase_projects` | List recent projects |
| `codebase_select_project` | Open or select a project by path |

`git_*` / `project_*` - absorbed from the retired git-status, git-log and search-replace
plugins. Names and schemas are carried over verbatim, so an agent that already knows them
needs no change:

| Tool | Permission | Purpose |
|---|---|---|
| `git_status` | - | Working-tree status in porcelain `XY path` form |
| `git_log` | - | Recent commits (limit clamped to 1..500) |
| `git_diff` | - | One file's working-tree or staged diff |
| `git_diff_all` | - | Every changed file as `STATUS path` |
| `git_diff_ref` | - | The changes a single commit introduced |
| `git_diff_between` | - | The diff between two refs |
| `project_search` | - | Search file contents across the project |
| `git_stage` | `git.write` | Stage a file |
| `git_unstage` | `git.write` | Unstage a file |
| `git_stage_all` | `git.write` | Stage everything changed |
| `git_unstage_all` | `git.write` | Unstage everything |
| `git_discard` | `git.write` | Discard working-tree changes (irreversible) |
| `git_checkout` | `git.write` | Check out a commit, branch or tag |
| `git_cherry_pick` | `git.write` | Cherry-pick a commit |
| `git_revert` | `git.write` | Revert a commit |
| `project_replace` | `project.replace` | Replace across an explicit file list, dry-run by default |

The RBAC split is deliberate. An empty `requiredPermissions` is not a neutral default: the
host's MCP registry exposes such a tool to every local session, including one where nobody is
signed in. Reads are open on that basis; anything that mutates git state or writes file
contents sits behind a grant. Admins bypass both. Refs arriving from an agent are checked
against the same `isSafeRef` rule the UI uses, so a leading dash cannot become a git flag.

On a host too old for `include_hidden`, `codebase_tree` still answers but adds a `note:` line
saying the flag was ignored, rather than silently returning a filtered tree.

## Requirements

- BOSS >= **9.5.7**, boss-plugin-api >= **1.0.87** (`minApiVersion`), for `GitDataProvider`,
  `ProjectSearchProvider` and the ref-scoped `logGraphFor`.
- Host providers: `fileSystemDataProvider`, `contextMenuProvider`, `directoryPickerProvider`,
  `splitViewOperations`, `projectDataProvider`, `gitDataProvider`, `projectSearchProvider`.
  Every one is optional at runtime: a missing provider degrades that tab to a hint, never a
  crash.
- No external binaries.

**Release ordering.** BossConsole retires git-status and git-log at `codebase >= 1.6.0`, so
this version must ship in the same wave as - or before - the host build carrying that floor.
The failure mode if it lands late is silent: those two panels are hidden and nothing replaces
them.

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
