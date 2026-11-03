"""将已经通过检查的本地实验打包，并生成可离线阅读的中文说明页。"""
from pathlib import Path
import base64
import html
import json
import zipfile

ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT.parent / "outputs"


def esc(text):
    return html.escape(str(text))


def image(path):
    encoded = base64.b64encode(path.read_bytes()).decode("ascii")
    return f'<img src="data:image/png;base64,{encoded}" alt="仿真位置、姿态、误差和电机转速曲线" />'


def main():
    reports = {name: json.loads((ROOT / "results" / name / "summary.json").read_text(encoding="utf-8")) for name in ("official", "hover", "recovery")}
    if not all(r["checks_passed"] for r in reports.values()):
        raise RuntimeError("实验未通过，拒绝构建完成版交付")
    versions = json.loads((ROOT / "results/versions.json").read_text(encoding="utf-8"))
    hover = reports["hover"]
    params = hover["physical_params"]
    exe = ROOT.home() / ".workbuddy-ai/binaries/python/envs/rotorpy-day1/Scripts/python.exe"
    command = f'& "{exe}" "{ROOT / "launch_lab.py"}"'
    physical_rows = [
        ("mass", params["mass"], "kg", "质量。首日是Crazyflie小机型，并非甲方机型。"),
        ("Ixx / Iyy / Izz", f'{params["Ixx"]} / {params["Iyy"]} / {params["Izz"]}', "kg·m²", "转动惯量，决定力矩产生的角加速度。"),
        ("num_rotors", params["num_rotors"], "个", "当前为对称四旋翼。"),
        ("rotor_pos", "见参数源码", "m", "各电机相对重心的位置，决定推力产生的力矩。"),
        ("k_eta", params["k_eta"], "N/(rad/s)²", "单旋翼推力近似T = k_eta × omega²。"),
        ("k_m", params["k_m"], "N·m/(rad/s)²", "旋翼反扭矩系数，影响偏航。"),
        ("tau_m", params["tau_m"], "s", "电机一阶响应时间，实际转速不会瞬时等于命令。"),
        ("rotor_speed_max", params["rotor_speed_max"], "rad/s", "电机转速上限，不是rpm。"),
    ]
    row_html = ''.join(f'<tr><td><code>{esc(k)}</code></td><td>{esc(v)}</td><td>{esc(u)}</td><td>{esc(d)}</td></tr>' for k, v, u, d in physical_rows)
    case_html = ''.join(f'<tr><td>{esc(name)}</td><td>{r["simulation_end_s"]:.2f}s / {r["samples"]}点</td><td>{r["position_rmse_m"]:.6f}m</td><td>{r.get("tail_position_rmse_m", "不设悬停阈值") if name == "official" else format(r["tail_position_rmse_m"], ".8f")}</td><td>通过</td></tr>' for name, r in reports.items())
    source = json.loads((ROOT / "official_sources.json").read_text(encoding="utf-8"))
    comparisons = versions.get("source_comparisons", [])
    source_note = ("已核对保存的6份模型/控制/循环阅读源码与已安装wheel对应文件：SHA256全部一致。" if comparisons and all(x["identical"] for x in comparisons) else "GitHub阅读源码与已安装wheel分别记录哈希；逐项差异请查看versions.json。")
    page = f'''<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>负责人A · RotorPy第一天实验</title>
<style>
:root{{color-scheme:dark}}*{{box-sizing:border-box}}body{{margin:0;background:#111820;color:#e6edf3;font:16px/1.75 "Microsoft YaHei",sans-serif}}main{{max-width:1100px;margin:auto;padding:36px 28px 64px}}h1{{font-size:30px;line-height:1.4}}h2{{margin-top:36px;color:#7ed5c2;font-size:23px}}h3{{font-size:19px}}.tag{{color:#7ed5c2;font-size:14px}}.panel{{padding:20px 24px;background:#1a2531;border:1px solid #344455;border-radius:12px;margin:18px 0}}.warn{{border-left:4px solid #e2ac5d}}code{{font:14px/1.6 Consolas,monospace;color:#9ddcce;overflow-wrap:anywhere}}pre{{padding:16px;background:#0d141d;border:1px solid #344455;overflow:auto;white-space:pre-wrap}}pre code{{color:#dfeaf3}}table{{width:100%;border-collapse:collapse;font-size:14px}}th,td{{text-align:left;border-bottom:1px solid #344455;padding:10px;vertical-align:top}}th{{color:#9ddcce}}.scroll{{overflow-x:auto}}img{{width:100%;border-radius:8px;margin:12px 0}}a{{color:#8dc5ff}}.flow{{display:flex;flex-wrap:wrap;align-items:center;gap:12px}}.node{{padding:15px;background:#143d39;border:1px solid #579b8d;border-radius:8px;flex:1;min-width:150px;text-align:center}}.muted{{color:#a6b7c6}}details{{border:1px solid #344455;padding:15px;border-radius:8px;margin:12px 0}}summary{{cursor:pointer;color:#9ddcce}}li{{margin:6px 0}}@media(max-width:650px){{main{{padding:20px 14px}}h1{{font-size:24px}}table{{min-width:600px}}}}
</style></head><body><main>
<div class="tag">负责人 A / 第一天 / 已在本机实际运行</div><h1>RotorPy：先跑通模型，再读懂闭环</h1>
<p>今天不学习UE、不接MQTT、不连实机。目标是：复现官方示例，理解状态、控制器、机体参数，形成可复查的运行结果。Android工程未修改。</p>
<div class="panel"><b>当前固定基线：</b>RotorPy {esc(versions["rotorpy"])} · Python {esc(versions["python"])} · Crazyflie四旋翼 · 100Hz逻辑仿真步频。<br><b>官方源码提交：</b><code>{source["commit"]}</code><br><b>本机代码目录：</b><code>{esc(ROOT)}</code><br>全部依赖安装在专用隔离环境中，没有全局安装。官方PyPI wheel与GitHub阅读源码分别记录版本/哈希。{source_note}</div>
<h2>一、你现在怎么开始</h2>
<ol><li>在编辑器中打开代码目录，先看<code>run_lab.py</code>。</li><li>在Windows PowerShell终端复制下面一行，重新执行官方示例、两个教学实验和测试。</li><li>看到两行<code>PASS=True</code>及测试<code>OK</code>后，查看<code>results</code>中的曲线和JSON。</li></ol>
<pre><code>{esc(command)}</code></pre>
<p>也可选择解释器 <code>{esc(exe)}</code>，直接运行 <code>launch_lab.py</code>。默认不弹出绘图窗口，曲线保存为PNG，避免窗口阻塞。</p>
<h3>分别运行、逐步调试</h3><pre><code>{esc(f'& "{exe}" "{ROOT / "run_official.py"}"')}
{esc(f'& "{exe}" "{ROOT / "run_lab.py"}" --case hover')}
{esc(f'& "{exe}" "{ROOT / "run_lab.py"}" --case recovery')}
{esc(f'& "{exe}" "{ROOT / "test_lab.py"}"')}</code></pre>
<p>需要官方绘图窗口及动画时，用<code>run_official.py --interactive</code>；本轮完成的是关闭窗口的运行，交互窗口模式未验证。关闭全部窗口后脚本才会继续结束。</p>
<h2>二、文件怎么读</h2><div class="scroll"><table><tr><th>文件</th><th>你今天的动作</th></tr>
<tr><td><code>run_lab.py</code></td><td>按make_components → run_case → summarize的顺序读，理解对象如何组成闭环。</td></tr>
<tr><td><code>lab_config.json</code></td><td>只在这里改变时长、目标、初始偏差、质量倍数和位置P增益倍数。</td></tr>
<tr><td><code>inspect_model.py</code></td><td>打印真实状态、期望轨迹、一次控制计算和平衡转速，用来配合断点学习。</td></tr>
<tr><td><code>run_official.py</code></td><td>包装原始官方示例；切换绘图/动画标志、固定随机种子、改变CSV输出位置，并跳过未使用但隐式依赖torch的Dryden导入。实际圆轨迹与SinusoidWind工况保持不变。</td></tr>
<tr><td><code>vendor/examples/basic_usage.py</code></td><td>保存的未修改官方示例：20s圆轨迹、正弦风、障碍场景。</td></tr>
<tr><td><code>vendor/rotorpy/vehicles/crazyflie_params.py</code></td><td>看质量、惯量、旋翼位置、电机响应；这是示例参数，不是已标定的甲方无人机。</td></tr>
<tr><td><code>vendor/rotorpy/controllers/quadrotor_control.py</code></td><td>看SE3Control.update如何把位置误差转成总推力、姿态力矩和电机转速。</td></tr>
<tr><td><code>vendor/rotorpy/vehicles/multirotor.py</code></td><td>读step、statedot、get_cmd_motor_speeds；这里才是动力学推进。</td></tr>
<tr><td><code>requirements-lock.txt / official_sources.json</code></td><td>分别锁依赖版本、锁官方源码提交及每份文件SHA256。</td></tr>
<tr><td><code>results/versions.json</code></td><td>本次解释器、包版本、实际导入源码位置和哈希记录。</td></tr>
</table></div>
<h2>三、闭环里四件事不要混淆</h2>
<div class="flow"><div class="node">HoverTraj<br><small>期望位置与偏航</small></div><span>→</span><div class="node">SE3Control<br><small>期望＋实际状态</small></div><span>→</span><div class="node">Multirotor.step<br><small>推力／力矩推进状态</small></div><span>→</span><div class="node">state<br><small>下一时刻实际状态</small></div></div>
<p>Environment负责调用和记录。控制器不直接修改位置；模型也不自动知道你想去哪。实际状态回到控制器，构成反馈闭环。今天使用真实仿真状态反馈（<code>use_mocap=False</code>），不是传感器估计闭环。</p>
<pre><code>target = trajectory.update(t)
control = controller.update(t, state, target)
next_state = vehicle.step(state, control, dt)</code></pre>
<p>以上是核心关系示意；实际教学脚本先调用官方Environment.run。单步接口在单元测试中已验证，下一步才能封装自己的持续调度服务。</p>
<div class="scroll"><table><tr><th>state字段</th><th>含义 / 单位</th><th>常见误解</th></tr>
<tr><td><code>x</code></td><td>世界系位置[3]，m</td><td>不是目标位置，不是经纬度。</td></tr><tr><td><code>v</code></td><td>世界系速度[3]，m/s</td><td>不是APP机体系摇杆值。</td></tr><tr><td><code>q</code></td><td>姿态四元数[4]，排列[x,y,z,w]</td><td>不是roll/pitch/yaw。单位四元数[0,0,0,1]表示无旋转。</td></tr><tr><td><code>w</code></td><td>机体系角速度[3]，rad/s</td><td>不是欧拉角变化率，不能直接把四元数标量w当成它。</td></tr><tr><td><code>wind</code></td><td>世界系风速[3]，m/s</td><td>由风场对象更新。</td></tr><tr><td><code>rotor_speeds</code></td><td>每个电机的实际转速，rad/s</td><td>与cmd_motor_speeds命令转速不同，有响应滞后。</td></tr>
</table></div>
<p>模型z轴向上、重力向下；坐标约定和旋转正方向要以保存的源码为准。之后接UE时不能直接原样拷贝位置/四元数：单位与左右手坐标转换需要独立验证。</p>
<h2>四、机体参数与控制器怎么理解</h2><div class="scroll"><table><tr><th>参数</th><th>本次值</th><th>单位</th><th>意义</th></tr>{row_html}</table></div>
<div class="panel">对称四旋翼静态悬停近似：<code>4 × k_eta × omega² = mass × 9.81</code>。<br>本次质量 {params["mass"]:.3f}kg，总支撑推力约 {params["mass"]*9.81:.4f}N，初始平衡转速约 <b>{hover["hover_speed_estimate_rad_s"]:.2f}rad/s</b>。<br>初始就位于1m高度且电机处于平衡转速；这不是起飞过程。</div>
<p>SE3Control先根据位置误差和速度误差算期望力，再结合偏航目标算期望姿态，通过姿态/角速度误差算力矩，最后分配到四个电机。</p>
<p><code>kp_pos={esc(hover["controller_gains"]["kp_pos"])}</code>，<code>kd_pos={esc(hover["controller_gains"]["kd_pos"])}</code>。位置P增益代表纠偏力度，D项提供阻尼；增益越大不一定越好，可能激发振荡及电机饱和。</p>
<div class="panel warn">本次mass_scale同时改变模型和控制器的已知质量，并保持原惯量/几何。它只是控制教学实验，不是模型失配测试，也不能当作真实新机型。示例气动参数含近似值，需要后续标定。</div>
<h2>五、三个实际运行结果</h2><div class="scroll"><table><tr><th>工况</th><th>仿真时长 / 点数</th><th>全程位置RMSE</th><th>末1s位置RMSE(m)</th><th>数值检查</th></tr>{case_html}</table></div>
<p>本轮7项单元测试全部通过，测试日志在<code>results/test_lab.log</code>。8s教学工况实际输出到8.01s，是官方浮点时间累加导致多走一个0.01s步长，数据保留实际时间，不人为裁剪。</p>
<p>无风悬停误差为0是因为初始位置、姿态和转速恰好在平衡点且关闭电机噪声，不意味着现实无人机能够零误差悬停。官方圆轨迹全程RMSE包含起始3m参考偏差，不能与悬停尾段误差直接比较。</p>
<p>检查包括：数值有限、时间递增、运行至终止时刻、正常时间终止（TIMEOUT）、四元数归一化。教学工况另要求末1秒位置RMSE≤0.05m；这个阈值只是入门检查，不是甲方验收指标。</p>
<h3>官方示例：有风圆轨迹</h3>{image(ROOT / "results/official/curves.png")}
<h3>教学一：无风悬停</h3>{image(ROOT / "results/hover/curves.png")}
<h3>教学二：从偏差回到同一悬停点</h3><p>初始位置比目标多[0.3,-0.2,0.2]m。看误差逐步收敛，而不是瞬间把位置赋值到目标。</p>{image(ROOT / "results/recovery/curves.png")}
<h2>六、亲手做三个小实验</h2><ol>
<li><b>位置目标：</b>将hover_position_m改为[0,0,1.5]，运行recovery。注意hover工况的初始位置也会同步变到目标，所以单独运行hover不会演示升空。</li>
<li><b>恢复过程：</b>将recovery_offset_m改为[0.6,0,0]，运行recovery，比较全程RMSE、恢复时间、姿态和转速。</li>
<li><b>增益：</b>保持初始偏差不变，把position_gain_scale改为0.6，然后1.2，用不同--out目录保存对比结果。kd_pos保持不变。不要先去修改vendor源码。</li></ol>
<pre><code>{esc(f'& "{exe}" "{ROOT / "run_lab.py"}" --case recovery --out "{ROOT / "results/gain_06"}"')}</code></pre>
<p><code>motor_noise_rad_s</code>按RotorPy3.0实际代码理解为每步叠加到转速的高斯噪声标准差（rad/s），不是已标定的连续时间噪声密度；默认0。首日不要同时更改噪声与控制增益。</p>
<p>完成后恢复lab_config.json默认值，以免把不同参数的结果混当作同一基线。每个summary.json都保存本次参数。重复运行默认会覆盖相同工况的结果文件；需要保留对比时必须用不同--out目录。</p>
<h2>七、迁移到另一台电脑</h2><p>本机已经装好，无须重复安装。在另一台装有兼容Python的电脑上，先把代码包解压到项目目录，再创建专用虚拟环境，切勿装到全局Python。以下是通用PowerShell示例；把解释器和项目路径改为实际路径。</p>
<pre><code>python -m venv "C:/your-managed-python/envs/rotorpy-day1"
& "C:/your-managed-python/envs/rotorpy-day1/Scripts/python.exe" -m pip install -r "C:/your-project/rotorpy_day1/requirements-lock.txt"
& "C:/your-managed-python/envs/rotorpy-day1/Scripts/python.exe" "C:/your-project/rotorpy_day1/run_official.py"
& "C:/your-managed-python/envs/rotorpy-day1/Scripts/python.exe" "C:/your-project/rotorpy_day1/run_lab.py"
& "C:/your-managed-python/envs/rotorpy-day1/Scripts/python.exe" "C:/your-project/rotorpy_day1/test_lab.py"</code></pre>
<p>完全复现优先用Python {esc(versions["python"])} / Windows；锁定依赖不保证在所有操作系统和Python版本可安装。ZIP不包含虚拟环境。官方源码已经保存在vendor中，运行不需要再次从GitHub下载。</p>
<h2>八、常见问题与今天的完成标准</h2><details><summary>ModuleNotFoundError: rotorpy</summary>通常是编辑器选错解释器。选择本文给出的专用环境，或从launch_lab.py入口运行。不要在系统Python里补装。</details><details><summary>直接跑原始官方示例提示没有torch</summary>官方basic_usage.py导入了未使用的Dryden风模块，该模块在3.0版隐式依赖torch。包装脚本只跳过这个未使用导入，保留实际SinusoidWind和圆轨迹。vendor源码未修改，本次不是完全无适配地执行官方文件。不要为了本工况先安装大型深度学习依赖。</details><details><summary>提示没有FilterPy</summary>官方示例尝试导入可选风估计器，但本工况estimator=None，不需要安装filter扩展。基础动力学实验可以正常运行。</details><details><summary>没弹出动画窗口是不是没运行</summary>默认关闭窗口，查看results/official/run.log、summary.json和curves.png；只有--interactive才保留官方窗口。本轮未验证该模式。</details><details><summary>结果是不是实时仿真或交付证明</summary>不是。这是离线基线；100Hz表示时间步长0.01s，不表示每步墙钟时间保证。尚未接UE、APP、外设，未做连续4小时运行与通信延时验收。</details>
<ul><li>能重新运行得到PASS与测试OK。</li><li>能解释x/v/q/w和目标轨迹的区别。</li><li>能找到模型参数与控制增益分别在哪里。</li><li>能解释平衡转速为什么约为{hover["hover_speed_estimate_rad_s"]:.0f}rad/s。</li><li>能用曲线说明初始偏差是怎样收敛的。</li></ul>
<h2>来源与许可</h2><p><a href="https://github.com/spencerfolk/rotorpy/tree/{source["commit"]}">固定的官方源码提交</a> · <a href="https://pypi.org/project/rotorpy/3.0.0/">PyPI RotorPy 3.0.0</a>。官方示例与阅读源码未修改，MIT版权和许可全文保存在<code>vendor/LICENSE.md</code>。第三方依赖保持各自许可证。自写包装代码和教学脚本与官方源码分目录保存。</p>
<p class="muted">这一步建立的是可运行、可理解、可复查的模型学习基线，不等于已经完成本项目六自由度仿真验收。</p>
</main></body></html>'''
    guide = ROOT / "实验说明.html"
    guide.write_text(page, encoding="utf-8")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "RotorPy_负责人A_第一天实验说明.html").write_text(page, encoding="utf-8")
    archive = OUTPUT / "RotorPy_负责人A_第一天代码与结果.zip"
    auxiliary = {"download_wheels.py", "verify_package.py", "build_delivery.py"}
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        for path in sorted(ROOT.rglob("*")):
            if path.is_file() and "__pycache__" not in path.parts and path.name not in auxiliary:
                z.write(path, Path("rotorpy_day1") / path.relative_to(ROOT))
    print(archive)
    print(OUTPUT / "RotorPy_负责人A_第一天实验说明.html")


if __name__ == "__main__":
    main()
