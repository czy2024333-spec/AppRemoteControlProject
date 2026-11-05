"""一次运行接口回归与动力学回归；任一失败均返回非零状态。"""
import os
from pathlib import Path
import subprocess
import sys


def main():
    root=Path(__file__).resolve().parent
    logs=root/'results/checks';logs.mkdir(parents=True,exist_ok=True)
    cases=[('backend',root,['test_logs_bounds','test_independent','test_unified','test_upgrade','test_cluster','test_boundary','test_ctl','test_inputs']),
           ('dynamics',root/'core',['test_core','test_core_3d','test_environment','test_lab'])]
    ok=True
    for name,directory,modules in cases:
        print('正在运行 '+name+'，请等待测试结束。',flush=True)
        completed=subprocess.run([sys.executable,'-m','unittest',*modules,'-v'],cwd=directory,
            stdout=subprocess.PIPE,stderr=subprocess.STDOUT,encoding='utf-8',errors='replace',
            env=dict(os.environ,PYTHONIOENCODING='utf-8'),timeout=300)
        (logs/(name+'.txt')).write_text(completed.stdout,encoding='utf-8')
        print('\n'.join(completed.stdout.splitlines()[-5:]),flush=True)
        ok &= completed.returncode==0
    return 0 if ok else 1


if __name__=='__main__':raise SystemExit(main())
