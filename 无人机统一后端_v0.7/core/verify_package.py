"""核对官方RotorPy wheel与PyPI公开SHA256；不安装或执行该wheel。"""
from pathlib import Path
import hashlib
import json
import urllib.request

ROOT = Path(__file__).resolve().parent
URL = "https://files.pythonhosted.org/packages/3f/ec/e66a429ba2b09c4756cf62ad8911c47d3c2061d1092d03024bff48d2d359/rotorpy-3.0.0-py3-none-any.whl"
EXPECTED = "baf5504763c5acac227582f90b09847a131d386a435cf496f2bc92c09a7b1884"


def main():
    request = urllib.request.Request(URL, headers={"User-Agent": "RotorPy-Day1-Lab"})
    with urllib.request.urlopen(request, timeout=45) as response:
        content = response.read()
    actual = hashlib.sha256(content).hexdigest()
    if actual != EXPECTED:
        raise RuntimeError(f"wheel哈希不匹配: {actual}")
    report = {"version": "3.0.0", "wheel_sha256": actual, "pypi_expected_sha256": EXPECTED,
              "verified": True, "download_url": URL, "metadata_source": "https://pypi.org/pypi/rotorpy/json"}
    (ROOT / "results/package_integrity.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print("RotorPy wheel SHA256 verified:", actual)


if __name__ == "__main__":
    main()
