# Mixed Deno Types (WebStorm plugin)

Correct TypeScript types in WebStorm for monorepos that mix **Deno**, **Node** and **browser** code,
using WebStorm's own TypeScript service. No Deno LSP, no Deno workspaces.

Target: WebStorm 2026.3+.

## How it works

The plugin scans the project for scopes:

| Scope | Detected by | Types |
|---|---|---|
| Deno | `deno.json(c)` with `imports`, `importMap` or `compilerOptions` (task-only configs are ignored) | Deno globals (`deno types`), DOM, import map as `paths`, real files for `jsr:` / `npm:` / `https:` |
| Web | `package.json` + `tsconfig.json` | untouched (DOM, `node_modules`) |
| Universal | files in a Deno scope that browser code imports and that use no Deno APIs / remote imports | DOM + ES only |

For every Deno scope it writes `tsconfig.deno.json`:

- `deno types` goes to `<root>/.deno-types/deno.d.ts`.
- Each external module is resolved with `deno info --json`. Remote modules (jsr, https) are copied from the Deno
  cache, where they have hashed names, into `<root>/.deno-types/remote/<host>/<path>`, keeping URL layout and
  extension so relative imports and go to definition work. `npm:` packages point to the scope's
  `node_modules` or the global Deno npm cache.
- Bare specifiers follow Deno's rules: exact keys, `prefix/` keys, and `jsr:`/`npm:` keys covering
  `key/subpath`. Each file uses the import map of its nearest `deno.json`.

Shared folders imported by browser apps get a `tsconfig.universal.json` listing the files that are safe for both.
A browser file importing a Deno-only module is flagged by the **Cross-runtime import problems** inspection.

Everything regenerates automatically when `deno.json`, `deno.lock`, `package.json`, `tsconfig.json` or imports change.

## Setup

1. Settings › Plugins › ⚙ › **Manage Plugin Repositories**, add
   `https://github.com/pagoru/ws_plugin/releases/latest/download/updatePlugins.xml`,
   then install **Mixed Deno Types** from the Marketplace tab. Updates then arrive like any other plugin.
   (Or install the zip from the [releases](../../releases) with Install Plugin from Disk, without updates.)
2. Set **Settings › Languages & Frameworks › Deno** to *Disable* for the project. The bundled Deno plugin
   would otherwise hand every file under a `deno.json` to the Deno LSP.
3. Run **Tools › Mixed Deno Types › Configure tsconfig Files…** once. It:
   - makes each Deno scope's `tsconfig.json` extend `./tsconfig.deno.json` (creating it if missing) and removes
     `lib`, `types`, `paths`, `baseUrl`, `target` and `module` options that would override it,
   - limits browser tsconfigs to `src` with `vite/client` types and adds `tsconfig.node.json` for `vite.config.ts`.
4. Add to `.gitignore`:
   ```
   .deno-types/
   tsconfig.deno.json
   tsconfig.universal.json
   ```
   (`.deno-types` gets its own `.gitignore` too.) The generated files contain paths to your local Deno cache.

Deno must be installed (PATH, `~/.deno/bin`, or set in the plugin settings).

## Command line

Same generation without the IDE (CI, other editors):

```sh
./gradlew generateTypes --args="/path/to/repo --configure"
```

## Build / release

```sh
./gradlew buildPlugin        # build/distributions/mixed-deno-types-<version>.zip
./gradlew runIde             # sandbox IDE
```

Push a tag `v1.2.3` (or run the **Release** workflow manually with a version) to publish a GitHub release
with the plugin zip attached.
