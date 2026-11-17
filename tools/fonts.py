"""Prepare the licensed, offline reader assets before packaging."""
import concurrent.futures
import hashlib
import io
import json
from pathlib import Path
import sys
import urllib.request
import zipfile

root = Path(__file__).resolve().parents[1]
lock = root / "tools/fonts.lock.json"
target = root / "app/build/generated/bookAssets/fonts"
target.mkdir(parents=True, exist_ok=True)
fonts = json.loads(lock.read_text())
refresh = sys.argv[1:] == ["--refresh-checksums"]

def install(font):
    for url_key, name, checksum_key in [
        ("url", font["file"], "sha256"),
        ("license", font["id"] + "-LICENSE.txt", "licenseSha256"),
    ]:
        path = target / name
        expected = font.get(checksum_key)
        if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == expected:
            continue
        with urllib.request.urlopen(font[url_key], timeout=120) as response:
            data = response.read()
        if url_key == "url" and "member" in font:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                member, = [n for n in archive.namelist() if n.split("/")[-1] == font["member"] and not n.startswith("__MACOSX")]
                data = archive.read(member)
        if url_key == "url" and data[:4] not in (b"OTTO", b"\0\1\0\0"):
            raise ValueError("Unexpected font type for " + name)
        actual = hashlib.sha256(data).hexdigest()
        if not refresh and actual != expected:
            raise ValueError("Checksum mismatch for " + name)
        font[checksum_key] = actual
        path.write_bytes(data)

with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    list(pool.map(install, fonts))
if refresh:
    lock.write_text(json.dumps(fonts, indent=2) + "\n")
(target / "catalog.json").write_text(json.dumps(fonts))
