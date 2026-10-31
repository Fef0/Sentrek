#!/usr/bin/env python3
"""Checks every translation against the English texts.

Run from the project folder:  python scripts/check-translations.py [language]
For each strings.xml in a values-<language> folder it lists the texts that are missing, the
ones that no longer exist in English, and the ones whose placeholders (%s, %1$d, %%) differ.
Exits with 1 if anything is wrong, so it can run in CI too.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENTRY = re.compile(r'<(string|plurals)\s+name="([^"]+)"([^>]*)>(.*?)</\1>', re.S)
PLACEHOLDER = re.compile(r'%(?:\d+\$)?[sdf%]')


def entries(path):
    """name -> (placeholders, translatable) for each string or plural of a strings.xml."""
    out = {}
    for kind, name, attrs, body in ENTRY.findall(path.read_text(encoding='utf-8')):
        out[name] = (sorted(set(PLACEHOLDER.findall(body))), 'translatable="false"' not in attrs)
    return out


def main():
    wanted = sys.argv[1] if len(sys.argv) > 1 else None
    problems = 0
    for english in sorted(ROOT.glob('*/**/src/main/res/values/strings.xml')):
        source = entries(english)
        res = english.parent.parent
        for folder in sorted(res.glob('values-*')):
            translated_file = folder / 'strings.xml'
            language = folder.name.removeprefix('values-')
            if not translated_file.exists() or (wanted and language != wanted):
                continue
            translated = entries(translated_file)
            where = translated_file.relative_to(ROOT)
            for name, (marks, translatable) in source.items():
                if not translatable:
                    continue
                if name not in translated:
                    print(f'{where}: missing "{name}"')
                    problems += 1
                elif translated[name][0] != marks:
                    print(f'{where}: "{name}" has placeholders {translated[name][0]}, English has {marks}')
                    problems += 1
            for name in translated.keys() - source.keys():
                print(f'{where}: "{name}" is not in English any more, it can go')
                problems += 1
    print('All translations complete.' if problems == 0 else f'{problems} problem(s).')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
