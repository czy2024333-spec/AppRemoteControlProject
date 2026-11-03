"""运行原始官方basic_usage示例；默认关闭绘图窗口，保留20秒官方工况。

vendor文件不修改。仅在AST中把plot/animate相关开关置False，并拦截CSV
保存位置，将CSV固定输出到results/official。--interactive保留官方绘图及动画开关。
"""
import argparse
import ast
import contextlib
import hashlib
import io
import json
from pathlib import Path
import time
import traceback

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--interactive", action="store_true")
    args = parser.parse_args()
    import matplotlib
    if not args.interactive:
        matplotlib.use("Agg")
    import numpy as np
    from rotorpy.environments import Environment
    from rotorpy.utils.postprocessing import unpack_sim_data
    from run_lab import check_version, plot_result, summarize, jsonable

    check_version()
    source = ROOT / "vendor/examples/basic_usage.py"
    tree = ast.parse(source.read_text(encoding="utf-8"), filename=str(source))
    changed = []
    # 官方示例导入但未使用Dryden模块；RotorPy3.0该模块隐式依赖torch。
    # 保留实际使用的SinusoidWind，删除未使用的导入，不伪造torch模块。
    tree.body = [node for node in tree.body if not (isinstance(node, ast.ImportFrom)
                 and node.module == "rotorpy.wind.dryden_winds")]
    if not args.interactive:
        for node in ast.walk(tree):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr == "run":
                for keyword in node.keywords:
                    if keyword.arg in {"plot", "plot_mocap", "plot_estimator", "plot_imu", "animate_bool", "animate_wind"}:
                        keyword.value = ast.Constant(False)
                        changed.append(keyword.arg)
    tree = ast.fix_missing_locations(tree)
    output = ROOT / "results" / ("official_interactive" if args.interactive else "official")
    output.mkdir(parents=True, exist_ok=True)
    original_save = Environment.save_to_csv
    saved = []

    def save_here(self, savepath=None):
        filename = Path(savepath or "rotorpy_simulation_results.csv").name
        if not filename.endswith(".csv"):
            filename += ".csv"
        path = output / filename
        unpack_sim_data(self.result).to_csv(path, index=False)
        saved.append(path.name)

    Environment.save_to_csv = save_here
    np.random.seed(7)
    log = io.StringIO()
    namespace = {"__name__": "__main__", "__file__": str(source)}
    start = time.perf_counter()
    try:
        with contextlib.redirect_stdout(log), contextlib.redirect_stderr(log):
            exec(compile(tree, str(source), "exec"), namespace)
        result = namespace["results"]
        checks = summarize(result, expected_duration=20.0, hover_target=None)
        plot_result(result, output / "curves.png", "Official circular trajectory")
        report = {"source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "package_version": "3.0.0", "seed": 7, "adaptations": {"disabled_visual_flags": changed, "csv_output_redirected": True, "unused_dryden_import_removed": True},  "wall_time_s": time.perf_counter() - start, "saved_files": saved, **checks}
        (output / "summary.json").write_text(json.dumps(jsonable(report), indent=2, ensure_ascii=False), encoding="utf-8")
        print(json.dumps(jsonable(report), indent=2, ensure_ascii=False))
        if not checks["checks_passed"]:
            raise RuntimeError(f"官方工况检查未通过: exit={checks['exit_status']}, t={checks['simulation_end_s']}; 查看summary.json")
    except Exception:
        log.write(traceback.format_exc())
        raise
    finally:
        Environment.save_to_csv = original_save
        (output / "run.log").write_text(log.getvalue(), encoding="utf-8")


if __name__ == "__main__":
    main()
