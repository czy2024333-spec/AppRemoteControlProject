"""独立探测脚本，避免Windows PowerShell 5传递python -c内层引号时丢失引号。"""
import importlib.util
import os
from pathlib import Path
import sys

sys.path.insert(0,os.environ.get('DRONE_CAMERA_DEPS',str(Path(__file__).resolve().parent/'.deps')))
raise SystemExit(0 if importlib.util.find_spec('cv2') else 1)
