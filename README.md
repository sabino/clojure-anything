# clojure-anything

Convert data, a program description, or an existing source project into Clojure (`.clj`) or ClojureScript (`.cljs`). The CLI is written in Clojure and runs on [Babashka](https://github.com/babashka/babashka#installation) with no additional libraries to install.

```sh
git clone https://github.com/sabino/clojure-anything.git
cd clojure-anything
bb clojure-anything --help
```

Use Babashka 1.13.229 or later. From another working directory, use `bb -cp /path/to/clojure-anything/src -m clojure-anything.main` in place of `bb clojure-anything`. Commands you record or check run in your current working directory.

## Convert data locally

```sh
printf '%s\n' '{"languages":["Clojure","ClojureScript"],"ready":true}' \
  | bb clojure-anything convert --from json --to cljs -
```

This prints a namespace with a `data` var. JSON keys stay strings and JSON `null` becomes `nil`. `.json`, `.edn` and `.txt` select formats automatically. Use `--from json|edn|text` for other extensions or stdin. `--namespace my-app.config --out port` writes a new directory with `deps.edn` and `src/my_app/config.clj` or `.cljs`. The Clojure target preserves exact JSON decimals. The ClojureScript target uses JavaScript numbers and rejects unsupported EDN numeric types and integers outside JavaScript’s safe range.

## Port source or implement a description

```sh
bb clojure-anything convert --codex --to cljs --out port ./legacy-app

printf '%s\n' 'Build a command-line stopwatch with start, lap, and reset commands.' \
  | bb clojure-anything convert --codex --to clj --out stopwatch -
```

Use a source file, a directory, or `-` to read stdin. `--codex` uses a signed-in [Codex CLI](https://github.com/openai/codex) and sends it the selected contents and any supplied recording. The generated project goes into a new directory. Review the files and use the build and run commands in the generated README. `--brief` lets you specify the interface, dependencies, or platform. ClojureScript command-line ports default to Node.js.

For larger projects, pass one module at a time. Directory input includes up to 128 UTF-8 files and 512 KiB of source. It skips hidden paths, symlinks, common build and dependency directories, and common binary assets and key files. An explicitly named file or stdin is read directly.

To use your own agent, replace `--codex` with an executable and its arguments after `--`. The executable reads the prompt from stdin and writes a single JSON object to stdout. The CLI includes the target, namespace, input contents, and optional behavior recording in the prompt. For example, an agent could return

```json
{"files":[{"path":"src/converted/core.clj","content":"(ns converted.core)\n(defn square [n] (* n n))\n"}]}
```

## Check behavior against the original

Create `cases.edn` with a unique `:name` for each case and optional `:args`, `:stdin`, and `:env`.

```clojure
[{:name "help" :args ["--help"]}
 {:name "Unicode input" :args ["summarize"] :stdin "Olá\nmundo\n"}
 {:name "invalid command" :args ["does-not-exist"]}]
```

```sh
bb clojure-anything record cases.edn original.edn -- python3 ./legacy/cli.py
bb clojure-anything convert --codex --to clj --cases original.edn --out port ./legacy
```

Build the port using its README. Replay the same cases by passing the port’s command after `--`. For a generated Clojure namespace with a `-main` entry point, run this from the `clojure-anything` checkout.

```sh
tool_dir="$PWD"
(cd port && clojure -P && bb -cp "$tool_dir/src" -m clojure-anything.main \
  check ../original.edn -- clojure -M -m converted.core)
```

`record` saves exit status and the exact bytes of stdout and stderr in a new EDN file. UTF-8 output stays readable, and other bytes are stored as Base64. `check` reports mismatching fields and exits `1` on a behavior difference or `2` on a command, input, or timeout error. `--timeout` sets the time per case in seconds and defaults to 15. To cover a library or an HTTP API, give both commands a driver that exercises the same interface.

## Development

```sh
bb test
```

The record-and-replay workflow was inspired by [rust-anything](https://github.com/erkamyaman/rust-anything). The fork retains its [MIT license](LICENSE).
