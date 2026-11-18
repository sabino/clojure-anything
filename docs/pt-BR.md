# guia rápido do clojure-anything

O `clojure-anything` gera código em Clojure (`.clj`) ou ClojureScript (`.cljs`). Para JSON, EDN e texto, ele gera um namespace com uma variável `data`, localmente. Para código-fonte ou uma descrição do que o programa deve fazer, ele usa o Codex ou outro agente que você indicar.

## Instale e teste a conversão

Instale o Babashka 1.13.229 ou mais recente. Os exemplos usam um shell POSIX. [Instalação do Babashka](https://github.com/babashka/babashka#installation).

```sh
git clone --depth 1 https://github.com/sabino/clojure-anything.git
cd clojure-anything
bb --version
bb clojure-anything --help
```

O comando `bb --version` deve mostrar `babashka v…`. Se houver outro programa chamado `bb`, use o caminho completo do executável do Babashka.

```sh
printf '%s\n' '{"mensagem":"Olá, mundo!"}' \
  | bb clojure-anything convert --from json --to cljs -
```

Você verá um namespace `converted.core` com a variável `data`. `-` lê da entrada padrão. `--to clj` gera Clojure. Para salvar o resultado em um projeto, acrescente `--out` com o caminho de uma pasta nova antes de `-`.

## Compare dois programas

Com Python 3 e Babashka, rode o exemplo que acompanha o projeto. Ele registra a saída normal, a saída de erro e o código de saída do programa original para cada caso e compara esses resultados com a versão em Clojure.

```sh
tool_dir=$PWD
work_dir=$(mktemp -d)

bb clojure-anything record examples/textkit/cases.edn "$work_dir/recording.edn" \
  -- python3 examples/textkit/original.py

bb clojure-anything check "$work_dir/recording.edn" \
  -- bb -cp examples/textkit/ports/src -m textkit.core
```

O resultado esperado é `5/5 cases matched.` Se houver uma diferença, `check` mostra o caso, o valor esperado e o valor obtido. Para seu próprio programa, coloque cada cenário em um arquivo como `cases.edn` e passe os comandos que executam o original e a versão portada depois de `--`. [Arquivo de casos do exemplo](../examples/textkit/cases.edn).

## Gere uma versão com o Codex

A opção `--codex` usa a CLI do Codex com sua conta conectada. Ela envia o código selecionado e a gravação indicada por `--cases`. Continue no mesmo terminal para reutilizar a gravação criada acima. [Codex CLI](https://github.com/openai/codex).

```sh
bb clojure-anything convert --codex --to clj --namespace textkit.generated \
  --cases "$work_dir/recording.edn" --out "$work_dir/generated" \
  --brief 'Use core Clojure and expose -main at textkit.generated.' \
  examples/textkit/original.py
```

Abra o README que o agente gerou e confira como executar o código. Com a CLI do Clojure instalada, rode o comando solicitado no exemplo e compare com a gravação.

```sh
(
  cd "$work_dir/generated" || exit
  clojure -P && bb -cp "$tool_dir/src" -m clojure-anything.main \
    check "$work_dir/recording.edn" -- clojure -M -m textkit.generated
)
```

Para gerar ClojureScript, use `--to cljs` e escolha outra pasta em `--out`. Use `--brief` para indicar a plataforma, a interface ou as dependências desejadas. Você também pode escrever uma descrição do programa e passar o texto pela entrada padrão.

Veja no README como compilar e testar o exemplo em ClojureScript no Node.js, usar outro agente e converter um projeto maior por módulos. [README completo](../README.md).
