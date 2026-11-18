import os
import sys


def main(args):
    if len(args) == 2 and args[0] == "echo":
        print(os.environ.get("TEXTKIT_PREFIX", "") + args[1])
        return 0

    if args == ["lines"]:
        text = sys.stdin.buffer.read().decode("utf-8")
        print(text.count("\n") + int(bool(text) and not text.endswith("\n")))
        return 0

    print("usage: textkit echo TEXT | lines", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
