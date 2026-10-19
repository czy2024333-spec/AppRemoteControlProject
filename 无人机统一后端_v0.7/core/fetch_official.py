"""从固定的官方GitHub提交保存示例/参数/许可；不执行下载的代码。"""
from pathlib import Path
import base64
import hashlib
import json
from datetime import datetime, timezone
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent
COMMIT = "40b09697a6a1596d404a67f459941d46a39eedd3"
FILES = ["examples/basic_usage.py", "rotorpy/worlds/double_pillar.json", "LICENSE.md", "pyproject.toml", "rotorpy/vehicles/crazyflie_params.py", "rotorpy/controllers/quadrotor_control.py", "rotorpy/trajectories/hover_traj.py", "rotorpy/vehicles/multirotor.py", "rotorpy/environments.py", "rotorpy/simulate.py"]


def main():
    records = []
    for source in FILES:
        url = f"https://api.github.com/repos/spencerfolk/rotorpy/contents/{source}?ref={COMMIT}"
        req = Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": "RotorPy-Day1-Lab"})
        with urlopen(req, timeout=45) as response:
            data = json.load(response)
        content = base64.b64decode(data["content"])
        dest = ROOT / "vendor" / source
        dest.parent.mkdir(parents=True, exist_ok=True)
        if dest.exists():
            if dest.read_bytes() != content:
                raise FileExistsError(f"拒绝覆盖不同的官方文件: {dest}")
        else:
            dest.write_bytes(content)
        records.append({"path": str(dest.relative_to(ROOT)).replace("\\", "/"), "source": source, "url": url, "sha256": hashlib.sha256(content).hexdigest()})
        print(source, len(content))
    manifest = {"repository": "https://github.com/spencerfolk/rotorpy", "commit": COMMIT, "expected_package_version": "3.0.0", "retrieved_at": datetime.now(timezone.utc).isoformat(), "files": records}
    (ROOT / "official_sources.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
