"""固定输入交接样例：升→停→降→输入超时→保护悬停。"""
import argparse
import json
from pathlib import Path
from simulation_core import SimulationCore, to_ue_pose


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parent / "results/core")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    core = SimulationCore()
    records = [core.get_state()]
    # 【演示时序】800步×0.01s=8s仿真时间；离线执行无需真的等待8秒。
    for tick in range(800):
        # 0~2s每0.1s续一次上升输入；续期频率高于TTL到期频率。
        if tick < 200 and tick % 10 == 0:
            core.set_control(0.3, 0.5)
        elif tick == 200:
            # 2s捕获当前点并制动，2~4s不再发送运动输入，观察保持过程。
            core.stop()
        elif 400 <= tick < 500 and tick % 10 == 0:
            # 4~5s下降；最后一次续期在4.9s，TTL=0.5s，因此5.4s进入保护。
            core.set_control(-0.3, 0.5)
        # 【建议断点】单步进入step可追踪：超时检查→目标更新→控制器→动力学→状态提交。
        records.append(core.step())
    with (args.out / "states.jsonl").open("w", encoding="utf-8") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")
    final = records[-1]
    report = {"samples": len(records), "final": final, "ue_pose": to_ue_pose(final),
              "checks": {"ascended": records[200]["state"]["x"][2] > 1.3,
                         "stopped": abs(records[400]["state"]["v"][2]) < .01,
                         "descended": records[500]["state"]["x"][2] < records[400]["state"]["x"][2],
                         "timeout_latched": final["mode"] == "PROTECTED" and final["protection_reason"] == "INPUT_TIMEOUT",
                         "protected_hover": abs(final["state"]["v"][2]) < .01}}
    report["checks_passed"] = all(report["checks"].values())
    (args.out / "summary.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(report["checks"], ensure_ascii=False))
    if not report["checks_passed"]:
        raise SystemExit("核心演示未通过")


if __name__ == "__main__":
    main()
