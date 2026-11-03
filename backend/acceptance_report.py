"""检查硬件验收材料缺项，不自动宣称真实硬件验收通过。"""
import argparse
import json
from pathlib import Path


def report(record):
    missing=[]
    template=json.loads(Path(__file__).with_name('hardware_acceptance_template.json').read_text(encoding='utf-8'))
    if record.get('evidence_type')!='REAL_HARDWARE':
        missing.append('缺少真实硬件测试证据，模拟报文不计入验收')
    for key in ('hardware_profile_reviewed','operator','witness','test_date'):
        if not record.get(key):
            missing.append('缺少'+key)
    for source in ('ring','voice'):
        items=record.get(source)
        if not isinstance(items,dict):
            missing.append('缺少'+source+'记录')
            continue
        # 按必测清单检查，不能用空对象或删掉失败项目绕过材料缺项检查。
        for key in template[source]:
            result=items.get(key)
            if not isinstance(result,dict) or result.get('passed') is not True or not result.get('evidence'):
                missing.append(source+'.'+key+'未提供通过结论及证据')
    if not record.get('evidence_files'):
        missing.append('缺少现场原始证据文件清单')
    return {'hardware_acceptance_complete':False,'material_check_only':True,
            'missing_items':missing,'requires_joint_real_device_signoff':True,
            'task_module_gaps':['takeoff','land','return_home']}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--record',type=Path,default=Path(__file__).resolve().parent/'hardware_acceptance_template.json')
    result=report(json.loads(parser.parse_args().record.read_text(encoding='utf-8')))
    print(json.dumps(result,ensure_ascii=False,indent=2))
