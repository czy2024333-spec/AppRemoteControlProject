"""运行当前版本Python回归及两个DLL演示，并保存机器可读验证记录。"""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest


def main():
    root=Path(__file__).parent.resolve()
    out=root/'results/validation_3d'
    out.mkdir(parents=True,exist_ok=True)
    os.environ['ROTORPY_CHECK_SAVED_RESULTS']='1'
    suite=unittest.TestLoader().loadTestsFromNames(['test_lab','test_core','test_core_3d','test_environment'])
    stream=io.StringIO()
    result=unittest.TextTestRunner(stream=stream,verbosity=2).run(suite)
    (out/'python_tests.log').write_text(stream.getvalue(),encoding='utf-8')
    report={'python_tests':{'run':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped)}}
    for script in ('run_core_demo.py','verify_cpp_bridge.py','run_3d_demo.py','run_disturbance_demo.py'):
        completed=subprocess.run([sys.executable,str(root/script)],cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,
                                 encoding='utf-8',env={**os.environ,'PYTHONUTF8':'1','MPLBACKEND':'Agg'})
        (out/(script+'.log')).write_text(completed.stdout,encoding='utf-8')
        report[script]={'exit_code':completed.returncode}
    for name in ('test.log','scheduler_test.log','3d_test.log','environment_test.log','c_api_smoke.log'):
        path=root/'cpp/build'/name
        report[name]={'exists':path.is_file(),'sha256':hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None}
    report['dll_sha256']=hashlib.sha256((root/'cpp/build/drone_core_c.dll').read_bytes()).hexdigest()
    report['ue_3d_integration']='not_implemented_or_engine_validated'
    report['mobile_integration']='not_in_scope'
    report['passed']=result.wasSuccessful() and not result.skipped and all(report[s]['exit_code']==0 for s in ('run_core_demo.py','verify_cpp_bridge.py','run_3d_demo.py','run_disturbance_demo.py'))
    (out/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    if not report['passed']: raise SystemExit(1)


if __name__=='__main__': main()
