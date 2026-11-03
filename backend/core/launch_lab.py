"""可从编辑器运行的入口。使用本机已经准备好的专用环境，不自动安装。

用任意Python启动本文件，它将调用隔离环境依次运行官方示例、教学实验和测试。
若迁移到另一台电脑，按实验说明安装；再用新环境分别运行这三个脚本。
"""
from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
PYTHON = Path.home() / ".workbuddy-ai/binaries/python/envs/rotorpy-day1/Scripts/python.exe"


def main():
    if not PYTHON.is_file():
        raise SystemExit(f"专用环境不存在: {PYTHON}\n请按实验说明.html的安装步骤准备环境。")
    env = dict(os.environ, PYTHONUTF8="1", MPLBACKEND="Agg")
    (ROOT / "results").mkdir(parents=True, exist_ok=True)
    for script in ("run_official.py", "run_lab.py", "test_lab.py", "test_core.py", "run_core_demo.py"):
        completed = subprocess.run([str(PYTHON), str(ROOT / script)], cwd=ROOT, env=env,
                                   capture_output=True, text=True, encoding="utf-8", errors="replace")
        log = completed.stdout + completed.stderr
        (ROOT / "results" / f"{Path(script).stem}.log").write_text(log, encoding="utf-8")
        print(log)
        completed.check_returncode()
    print(f"完成。查看 {ROOT / 'results'}")


if __name__ == "__main__":
    main()
