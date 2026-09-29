#!/usr/bin/env python3
"""Known-answer generator for RomM's hash_zip_contents (backend/handler/
filesystem/assets_handler.py), used to build a Kotlin known-answer test for
Caulker's hashZipContents/hashLocalContentAsZip (SaveZipContentHash.kt).

Algorithm (mirrors RomM 5.3.1; Argosy's SaveArchiver.calculateZipHash is equivalent):
  1. Walk sorted(zf.namelist()), skip names ending in "/".
  2. For each file entry, compute the lowercase-hex md5 of its decompressed
     bytes.
  3. Join f"{name}:{md5}" lines with "\n" (no trailing newline).
  4. Return the lowercase-hex md5 of that UTF-8-encoded joined string.
"""
import hashlib
import io
import zipfile


def hash_zip_contents(zip_bytes: bytes) -> str:
    buf = io.BytesIO(zip_bytes)
    with zipfile.ZipFile(buf, "r") as zf:
        lines = []
        for name in sorted(zf.namelist()):
            if name.endswith("/"):
                continue
            data = zf.read(name)
            md5 = hashlib.md5(data).hexdigest()
            lines.append(f"{name}:{md5}")
        joined = "\n".join(lines)
        return hashlib.md5(joined.encode("utf-8")).hexdigest()


def build_folder_zip() -> bytes:
    # Mirrors what Caulker's packSaveZip produces for a single-root FOLDER
    # save with a nested subdirectory: no entry for the root itself, but an
    # explicit entry for the nested "sub/" dir (SaveZipPacker.kt).
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("SAVE1/sub/", "")
        zf.writestr("SAVE1/DATA.BIN", "data-contents")
        zf.writestr("SAVE1/PARAM.SFO", "param-contents")
        zf.writestr("SAVE1/sub/EXTRA.BIN", "extra-contents")
    return buf.getvalue()


def build_fileset_zip() -> bytes:
    # Mirrors a flat FILE_SET bundle (SaveZipPacker.kt point 1): no
    # directory prefix at all, 3 members.
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("Star Ocean.srm", "srm-contents")
        zf.writestr("Star Ocean.rtc", "rtc-contents")
        zf.writestr("Star Ocean.extra", "extra-member-contents")
    return buf.getvalue()


if __name__ == "__main__":
    folder_zip = build_folder_zip()
    fileset_zip = build_fileset_zip()
    print("FOLDER   entries:", sorted(n for n in zipfile.ZipFile(io.BytesIO(folder_zip)).namelist()))
    print("FOLDER   hash:", hash_zip_contents(folder_zip))
    print("FILE_SET entries:", sorted(n for n in zipfile.ZipFile(io.BytesIO(fileset_zip)).namelist()))
    print("FILE_SET hash:", hash_zip_contents(fileset_zip))
