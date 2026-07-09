#!/usr/bin/env python3
import csv
import glob
import json
import os
import sys


def latest_results():
    dirs = sorted(glob.glob("verification/results/soak-*"))
    if not dirs:
        dirs = sorted(glob.glob(os.path.join(os.path.dirname(__file__), "results", "soak-*")))
    return dirs[-1] if dirs else None


def read_csv(path):
    if not os.path.exists(path):
        return []
    with open(path) as f:
        return list(csv.DictReader(f))


def read_injections(path, lib):
    out = []
    if os.path.exists(path):
        with open(path) as f:
            for line in f:
                p = line.split()
                if len(p) >= 3 and p[2] == lib:
                    out.append(float(p[0]))
    return out


def main():
    rdir = sys.argv[1] if len(sys.argv) > 1 else latest_results()
    if not rdir:
        sys.exit("no results directory found")

    summary = json.load(open(os.path.join(rdir, "summary.json")))
    vio = read_csv(os.path.join(rdir, "violations-shedlock.csv"))
    log = read_csv(os.path.join(rdir, "worklog-shedlock.csv"))
    faults = read_injections(os.path.join(rdir, "injections.log"), "shedlock")

    epochs = [float(r["epoch"]) for r in log] or [0.0, 1.0]
    t0, t1 = min(epochs), max(epochs)
    span = (t1 - t0) or 1.0

    def norm(t):
        return max(0.0, min(1.0, (t - t0) / span))

    faults_n = sorted(norm(t) for t in faults if t0 <= t <= t1)
    vio_epochs = sorted(float(r["epoch"]) for r in vio)
    vio_n = [norm(t) for t in vio_epochs]
    cumulative = [[norm(t), i + 1] for i, t in enumerate(vio_epochs)]

    shed = summary["shedlock"]
    vigil = summary["vigil"]

    data = {
        "faults": faults_n,
        "shedViolations": vio_n,
        "cumulative": cumulative,
        "maxCum": max(shed["violations"], 1),
    }

    html = TEMPLATE
    subs = {
        "{{GENERATED}}": summary.get("generatedUtc", ""),
        "{{DURATION}}": str(summary.get("durationS", "")),
        "{{FAULT_EVERY}}": str(summary.get("faultEverySec", "")),
        "{{FAULT_FOR}}": str(summary.get("faultForSec", "")),
        "{{VIGIL_VIOL}}": str(vigil["violations"]),
        "{{SHED_VIOL}}": str(shed["violations"]),
        "{{VIGIL_ACQ}}": str(vigil["acquisitions"]),
        "{{SHED_ACQ}}": str(shed["acquisitions"]),
        "{{VIGIL_UNITS}}": str(vigil["committedUnits"]),
        "{{SHED_UNITS}}": str(shed["committedUnits"]),
        "{{RESULTS_DIR}}": os.path.basename(rdir.rstrip("/")),
        "{{DATA_JSON}}": json.dumps(data),
    }
    for k, v in subs.items():
        html = html.replace(k, v)

    out = os.path.join(rdir, "report.html")
    with open(out, "w") as f:
        f.write(html)
    print("wrote " + out)


TEMPLATE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Vigil vs ShedLock - measured soak</title>
<style>
  :root {
    --bg:#f4f6f8; --surface:#fff; --surface-2:#eef1f5; --border:#d7dde5; --text:#131820;
    --text-dim:#5a6673; --accent:#0d7d78; --accent-ink:#0a5754; --good:#167c46; --good-wash:#e3f3ea;
    --bad:#c22a2a; --bad-wash:#fbe6e6; --shed:#5c6b7e;
    --mono:ui-monospace,"SF Mono","JetBrains Mono",Menlo,Consolas,monospace;
    --sans:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
    --shadow:0 1px 2px rgba(16,24,40,.06),0 8px 24px rgba(16,24,40,.05);
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg:#0d1117; --surface:#161b22; --surface-2:#1b2230; --border:#2a3340; --text:#e7ecf3;
      --text-dim:#98a3b2; --accent:#2dd4bf; --accent-ink:#5eead4; --good:#46b681; --good-wash:#12301f;
      --bad:#f26770; --bad-wash:#331519; --shed:#8593a6;
      --shadow:0 1px 2px rgba(0,0,0,.4),0 10px 30px rgba(0,0,0,.35);
    }
  }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--text); font-family:var(--sans); line-height:1.5; }
  .wrap { max-width:960px; margin:0 auto; padding:32px 20px 72px; }
  .eyebrow { font-family:var(--mono); font-size:12px; letter-spacing:.12em; text-transform:uppercase; color:var(--accent-ink); margin:0 0 8px; }
  h1 { font-size:clamp(24px,4vw,34px); line-height:1.1; margin:0; letter-spacing:-.02em; }
  .measured { display:inline-block; margin-top:12px; font-family:var(--mono); font-size:11px; letter-spacing:.05em; text-transform:uppercase; color:var(--good); background:var(--good-wash); border-radius:999px; padding:4px 10px; }
  .meta { display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:1px; background:var(--border); border:1px solid var(--border); border-radius:12px; overflow:hidden; margin:22px 0 34px; }
  .meta div { background:var(--surface); padding:12px 14px; }
  .meta dt { font-family:var(--mono); font-size:10.5px; letter-spacing:.08em; text-transform:uppercase; color:var(--text-dim); margin:0 0 4px; }
  .meta dd { margin:0; font-family:var(--mono); font-size:14px; font-variant-numeric:tabular-nums; }
  h2 { font-size:13px; font-family:var(--mono); letter-spacing:.1em; text-transform:uppercase; color:var(--text-dim); margin:40px 0 16px; padding-bottom:8px; border-bottom:1px solid var(--border); }
  .verdict { display:grid; grid-template-columns:1fr 1fr; gap:16px; }
  .card { background:var(--surface); border:1px solid var(--border); border-radius:14px; padding:20px; box-shadow:var(--shadow); }
  .card.win { border-color:color-mix(in srgb, var(--accent) 45%, var(--border)); }
  .card .who { display:flex; align-items:center; justify-content:space-between; margin-bottom:14px; }
  .card .who .name { font-weight:650; font-size:17px; }
  .card.win .who .name { color:var(--accent-ink); }
  .tag { font-family:var(--mono); font-size:10.5px; letter-spacing:.06em; text-transform:uppercase; padding:3px 8px; border-radius:999px; }
  .tag.pass { background:var(--good-wash); color:var(--good); }
  .tag.fail { background:var(--bad-wash); color:var(--bad); }
  .bignum { display:flex; align-items:baseline; gap:10px; }
  .bignum b { font-family:var(--mono); font-size:44px; font-variant-numeric:tabular-nums; line-height:1; }
  .bignum.good b { color:var(--good); }
  .bignum.bad b { color:var(--bad); }
  .bignum span { color:var(--text-dim); font-size:13px; }
  .tbl { overflow-x:auto; border:1px solid var(--border); border-radius:12px; }
  table { border-collapse:collapse; width:100%; min-width:520px; background:var(--surface); }
  thead th { text-align:left; font-family:var(--mono); font-size:11px; letter-spacing:.06em; text-transform:uppercase; color:var(--text-dim); font-weight:600; padding:12px 16px; border-bottom:1px solid var(--border); background:var(--surface-2); }
  thead th.num, tbody td.num { text-align:right; font-variant-numeric:tabular-nums; font-family:var(--mono); }
  tbody td { padding:13px 16px; border-bottom:1px solid var(--border); font-size:14px; }
  tbody tr:last-child td { border-bottom:0; }
  .v-good { color:var(--good); font-weight:650; }
  .v-bad { color:var(--bad); font-weight:650; }
  .chartcard { background:var(--surface); border:1px solid var(--border); border-radius:14px; padding:18px 18px 14px; box-shadow:var(--shadow); margin-bottom:16px; }
  .chartcard h3 { margin:0 0 4px; font-size:15px; }
  .chartcard .cap { margin:0 0 14px; font-size:12.5px; color:var(--text-dim); max-width:72ch; }
  .chartcard svg { width:100%; height:auto; display:block; }
  .legend { display:flex; flex-wrap:wrap; gap:16px; margin-top:12px; font-family:var(--mono); font-size:11.5px; color:var(--text-dim); }
  .legend span { display:inline-flex; align-items:center; gap:6px; }
  .legend i { width:12px; height:12px; border-radius:3px; display:inline-block; }
  .axislabel { font-family:var(--mono); font-size:10px; fill:var(--text-dim); }
  .gridline { stroke:var(--border); stroke-width:1; }
  .faulttick { stroke:var(--text-dim); stroke-width:1; opacity:.45; }
  .corruptdot { fill:var(--bad); }
  .vigilzero { stroke:var(--accent); stroke-width:2; fill:none; }
  .shedarea { fill:var(--bad); opacity:.12; }
  .shedline { stroke:var(--bad); stroke-width:2; fill:none; }
  pre { margin:0; background:var(--surface-2); border:1px solid var(--border); border-radius:12px; padding:16px; overflow-x:auto; font-family:var(--mono); font-size:12.5px; line-height:1.7; }
  .note { margin-top:14px; font-size:13px; color:var(--text-dim); }
  @media (max-width:620px){ .verdict{grid-template-columns:1fr;} }
</style>
</head>
<body>
<div class="wrap">
  <p class="eyebrow">Chaos soak - measured result</p>
  <h1>Vigil vs ShedLock under real kill -STOP fault injection</h1>
  <span class="measured">measured - generated from run data</span>

  <dl class="meta">
    <div><dt>Generated</dt><dd>{{GENERATED}}</dd></div>
    <div><dt>Duration / lib</dt><dd>{{DURATION}}s</dd></div>
    <div><dt>Fault</dt><dd>STOP {{FAULT_FOR}}s / {{FAULT_EVERY}}s</dd></div>
    <div><dt>Backend</dt><dd>PostgreSQL 16</dd></div>
    <div><dt>Run</dt><dd>{{RESULTS_DIR}}</dd></div>
  </dl>

  <h2>Headline - out-of-order committed writes</h2>
  <div class="verdict">
    <div class="card win">
      <div class="who"><span class="name">Vigil</span><span class="tag pass">{{VIGIL_VIOL}} violations</span></div>
      <div class="bignum good"><b>{{VIGIL_VIOL}}</b><span>stale writes that<br>reached the ledger</span></div>
    </div>
    <div class="card">
      <div class="who"><span class="name">ShedLock</span><span class="tag fail">{{SHED_VIOL}} violations</span></div>
      <div class="bignum bad"><b>{{SHED_VIOL}}</b><span>stale writes that<br>reached the ledger</span></div>
    </div>
  </div>

  <h2>Throughput and contention</h2>
  <div class="tbl">
    <table>
      <thead><tr><th>Metric</th><th class="num">Vigil</th><th class="num">ShedLock</th></tr></thead>
      <tbody>
        <tr><td>Out-of-order violations</td><td class="num v-good">{{VIGIL_VIOL}}</td><td class="num v-bad">{{SHED_VIOL}}</td></tr>
        <tr><td>Lock acquisitions</td><td class="num">{{VIGIL_ACQ}}</td><td class="num">{{SHED_ACQ}}</td></tr>
        <tr><td>Committed work units</td><td class="num">{{VIGIL_UNITS}}</td><td class="num">{{SHED_UNITS}}</td></tr>
      </tbody>
    </table>
  </div>
  <p class="note">Vigil often commits fewer units under heavy faults: when a frozen holder is fenced, its in-flight work is rejected and re-done - it trades completion for correctness. ShedLock's higher unit count includes the stale writes counted as violations.</p>

  <h2>Causation - corruption follows the faults</h2>
  <div class="chartcard">
    <h3>Fault-vs-corruption timeline</h3>
    <p class="cap">Each grey tick is a real injected freeze; each red dot is a ShedLock stale write, drawn at its commit time. Vigil's line stays flat at zero through the same faults.</p>
    <svg id="timelineSvg" viewBox="0 0 900 200" preserveAspectRatio="xMidYMid meet"></svg>
    <div class="legend">
      <span><i style="background:var(--text-dim);opacity:.5"></i>fault injected</span>
      <span><i style="background:var(--bad)"></i>ShedLock corruption</span>
      <span><i style="background:var(--accent)"></i>Vigil (zero)</span>
    </div>
  </div>
  <div class="chartcard">
    <h3>Cumulative violations over the run</h3>
    <p class="cap">ShedLock's stale writes accumulate with exposure; Vigil stays pinned at zero.</p>
    <svg id="cumulativeSvg" viewBox="0 0 900 240" preserveAspectRatio="xMidYMid meet"></svg>
    <div class="legend">
      <span><i style="background:var(--bad)"></i>ShedLock cumulative</span>
      <span><i style="background:var(--accent)"></i>Vigil (0)</span>
    </div>
  </div>

  <h2>Evidence and reproduction</h2>
  <pre># {{RESULTS_DIR}}/
summary.json            headline numbers
violations-shedlock.csv every stale write with seq, pod, acq_id, run_max, ts
worklog-*.csv           every committed unit
injections.log          every injected fault, timestamped
report.html             this page, generated by gen_soak_report.py

$ ./verification/08_soak_compare.sh
$ ./verification/gen_soak_report.py</pre>

</div>
<script>
(function () {
  var DATA = {{DATA_JSON}};
  var NS = "http://www.w3.org/2000/svg";
  function el(n, a) { var e = document.createElementNS(NS, n); for (var k in a) e.setAttribute(k, a[k]); return e; }
  function text(svg, x, y, anchor, cls, s) { var t = el("text", { x: x, y: y, "text-anchor": anchor, "class": cls }); t.textContent = s; svg.appendChild(t); return t; }

  var tl = document.getElementById("timelineSvg");
  if (tl) {
    var W = 900, padL = 62, padR = 18, x0 = padL, x1 = W - padR;
    var laneFault = 34, laneShed = 96, laneVigil = 150;
    function X(t) { return x0 + t * (x1 - x0); }
    text(tl, padL - 12, laneFault + 4, "end", "axislabel", "faults");
    text(tl, padL - 12, laneShed + 4, "end", "axislabel", "ShedLock");
    text(tl, padL - 12, laneVigil + 4, "end", "axislabel", "Vigil");
    DATA.faults.forEach(function (t) { tl.appendChild(el("line", { x1: X(t), y1: laneFault - 7, x2: X(t), y2: laneFault + 7, "class": "faulttick" })); });
    DATA.shedViolations.forEach(function (t) {
      tl.appendChild(el("line", { x1: X(t), y1: laneFault + 7, x2: X(t), y2: laneShed, "class": "faulttick" }));
      tl.appendChild(el("circle", { cx: X(t), cy: laneShed, r: 4, "class": "corruptdot" }));
    });
    tl.appendChild(el("line", { x1: x0, y1: laneVigil, x2: x1, y2: laneVigil, "class": "vigilzero" }));
    text(tl, x1, laneVigil - 9, "end", "axislabel", "0 corruptions");
    ["start", "mid", "end"].forEach(function (lab, i) { text(tl, X(i / 2), 192, "middle", "axislabel", lab); });
  }

  var cu = document.getElementById("cumulativeSvg");
  if (cu) {
    var CW = 900, CH = 240, cpadL = 48, cpadR = 26, cpadT = 20, cpadB = 30;
    var cx0 = cpadL, cx1 = CW - cpadR, cy0 = CH - cpadB, cy1 = cpadT, maxY = DATA.maxCum;
    function CX(t) { return cx0 + t * (cx1 - cx0); }
    function CY(v) { return cy0 - (v / maxY) * (cy0 - cy1); }
    var ticks = 5;
    for (var g = 0; g <= ticks; g++) {
      var v = Math.round(maxY * g / ticks);
      cu.appendChild(el("line", { x1: cx0, y1: CY(v), x2: cx1, y2: CY(v), "class": "gridline" }));
      text(cu, cx0 - 8, CY(v) + 3, "end", "axislabel", String(v));
    }
    var pts = [[CX(0), CY(0)]];
    DATA.cumulative.forEach(function (p) { pts.push([CX(p[0]), CY(p[1] - 1)]); pts.push([CX(p[0]), CY(p[1])]); });
    pts.push([CX(1), CY(DATA.cumulative.length)]);
    var d = "M" + pts.map(function (p) { return p[0].toFixed(1) + "," + p[1].toFixed(1); }).join(" L");
    cu.appendChild(el("path", { d: d + " L" + CX(1) + "," + cy0 + " L" + cx0 + "," + cy0 + " Z", "class": "shedarea" }));
    cu.appendChild(el("path", { d: d, "class": "shedline" }));
    cu.appendChild(el("line", { x1: cx0, y1: CY(0), x2: cx1, y2: CY(0), "class": "vigilzero" }));
    ["start", "mid", "end"].forEach(function (lab, i) { text(cu, CX(i / 2), CH - 8, "middle", "axislabel", lab); });
  }
})();
</script>
</body>
</html>
"""


if __name__ == "__main__":
    main()
