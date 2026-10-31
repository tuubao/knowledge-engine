"""下载官方 BAAI/bge-m3 的固定版本，并保存来源与文件 SHA-256。"""
import argparse
import hashlib
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REVISION = "5617a9f61b028005a4858fdac845db406aefb181"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=ROOT / "models/bge-m3")
    args = parser.parse_args()
    os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "120")
    os.environ.setdefault("HF_HUB_DISABLE_PROGRESS_BARS", "1")
    os.environ.setdefault("HF_HOME", str(ROOT / ".cache/huggingface"))
    os.environ.setdefault("HF_XET_CACHE", str(ROOT / ".cache/huggingface/xet"))
    from huggingface_hub import snapshot_download
    destination = args.directory.resolve()
    snapshot_download("BAAI/bge-m3", revision=REVISION, endpoint="https://huggingface.co",
        local_dir=destination, cache_dir=ROOT / ".cache/huggingface", token=False,
        allow_patterns=["*.json", "*.pt", "pytorch_model.bin", "sentencepiece.bpe.model", "README.md"],
        max_workers=3)
    hashes = {}
    for path in sorted(destination.rglob("*")):
        if path.is_file() and ".cache" not in path.parts and path.name != "download-manifest.json":
            digest = hashlib.sha256()
            with path.open("rb") as stream:
                for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
                    digest.update(block)
            hashes[str(path.relative_to(destination)).replace("\\", "/")] = digest.hexdigest()
    manifest = {"repository": "BAAI/bge-m3", "revision": REVISION,
        "source": "https://huggingface.co/BAAI/bge-m3", "sha256": hashes}
    (destination / "download-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps({"downloaded": True, "revision": REVISION, "file_count": len(hashes)}))


if __name__ == "__main__":
    main()
