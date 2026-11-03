"""根据pip解析清单并行下载依赖，仅接受PyPI wheel并校验SHA256。

临时下载位于托管Python环境目录，不进入用户全局Python。为避免低速
单连接，将大wheel分为128KB范围请求；缺少Range支持立即报错。
"""
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
import re
from urllib.request import Request, build_opener, ProxyHandler

# 只影响本脚本，不改变系统代理配置。
OPENER = build_opener(ProxyHandler({}))
from urllib.parse import urlparse

ENV = Path.home() / ".workbuddy-ai/binaries/python/envs/rotorpy-day1"
PLAN = ENV / "install-plan.json"
DEST = ENV / "wheelhouse"
CHUNK = 128_000


def retrieve(url, start, end, size=None):
    from http.client import IncompleteRead, RemoteDisconnected
    from urllib.error import URLError
    # 网络连接被重置时只重试同一分段；有界重试且不改变目标文件。
    last_error = None
    for attempt in range(8):
        try:
            return retrieve_once(url, start, end, size, attempt)
        except (IncompleteRead, RemoteDisconnected, URLError, ConnectionError, TimeoutError) as error:
            last_error = error
    raise last_error


def retrieve_once(url, start, end, size=None, attempt=0):
    # 分段和重试使用独立缓存键，避免中间缓存复用错误的Range响应。
    mirror_url = url.replace("https://files.pythonhosted.org/packages/", "https://mirrors.aliyun.com/pypi/packages/")
    request = Request(f"{mirror_url}?part={start}-{end}&attempt={attempt}", headers={"Range": f"bytes={start}-{end}", "User-Agent": "RotorPy-Day1-Lab"})
    with OPENER.open(request, timeout=30) as response:
        data = response.read()
        if response.status != 206:
            raise RuntimeError(f"服务不支持范围请求: {url}")
        match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", response.headers["Content-Range"])
        if not match or int(match[1]) != start or int(match[2]) != end or len(data) != end - start + 1:
            raise RuntimeError("下载分段范围/长度不匹配")
        actual_size = int(match[3])
        if size is not None and actual_size != size:
            raise RuntimeError("分段总大小不匹配")
        return data, actual_size


def main():
    DEST.mkdir(exist_ok=True)
    packages = json.loads(PLAN.read_text(encoding="utf-8"))["install"]
    manifest = []
    for pkg in packages:
        info = pkg["download_info"]
        url = info["url"]
        if urlparse(url).hostname != "files.pythonhosted.org" or not url.endswith(".whl"):
            raise ValueError(f"只接受PyPI wheel: {url}")
        filename = url.rsplit("/", 1)[-1]
        expected = info["archive_info"]["hashes"]["sha256"]
        target = DEST / filename
        if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
            print("cached", filename, flush=True)
            manifest.append({"filename": filename, "sha256": expected, "url": url})
            continue
        # 查询总大小只读取一个字节。
        first, size = retrieve(url, 0, 0)
        parts = {}
        with ThreadPoolExecutor(max_workers=8) as pool:
            futures = {pool.submit(retrieve, url, start, min(size - 1, start + CHUNK - 1), size): start
                       for start in range(0, size, CHUNK)}
            for future in as_completed(futures):
                parts[futures[future]] = future.result()[0]
        data = b"".join(parts[start] for start in sorted(parts))
        if hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError(f"SHA256不匹配: {filename}")
        target.write_bytes(data)
        manifest.append({"filename": filename, "sha256": expected, "url": url})
        print("verified", filename, len(data), flush=True)
    (ENV / "verified-wheel-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    root = Path(__file__).resolve().parent
    (root / "results/dependency_integrity.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    hash_lock = ["# Python3.13 / Windows x64已验证wheel哈希；其他平台使用requirements-lock.txt。"]
    for pkg in packages:
        info = pkg["download_info"]
        hash_lock.append(f"{pkg['metadata']['name']}=={pkg['metadata']['version']} --hash=sha256:{info['archive_info']['hashes']['sha256']}")
    (root / "requirements-windows-hashed.txt").write_text("\n".join(hash_lock) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
