#!/usr/bin/env python3
"""Create a reproducible source archive from the standalone project."""

import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
version = "0.1.1"
excluded = {
    ".git",
    ".gradle",
    ".kotlin",
    "build",
    "node_modules",
    "dist",
    ".idea",
    "__pycache__",
    "xcuserdata",
}
trees = {"audio", "samples", "gradle", "tools", ".github", "kotlin-js-store"}
files = {
    "README.md",
    "LICENSE",
    "VERIFICATION.md",
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    ".gitignore",
    "gradlew",
    "gradlew.bat",
    ".gitattributes",
    ".editorconfig",
    ".clang-format",
    ".swift-format",
    ".prettierrc.json",
    ".prettierignore",
    "package.json",
    "package-lock.json",
    "eslint.config.mjs",
    "ruff.toml",
    "playwright.config.mjs",
}
destination = root / "build" / f"kmp-procedural-audio-{version}.zip"
destination.parent.mkdir(parents=True, exist_ok=True)

with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if not path.is_file() or any(part in excluded for part in relative.parts):
            continue
        if relative.parts[0] not in trees and relative.as_posix() not in files:
            continue
        if path.name == "local.properties" or path.name.startswith(".env") or path.suffix == ".pyc":
            continue
        info = zipfile.ZipInfo(f"kmp-procedural-audio-{version}/{relative.as_posix()}")
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = (0o100755 if path.stat().st_mode & 0o111 else 0o100644) << 16
        archive.writestr(info, path.read_bytes())

print(destination)
