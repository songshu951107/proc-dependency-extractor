# 脚本转换可视化 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 把 F→O 表映射脚本转换集成进血缘系统——Python 离线批量转换产出机读报告，网页只读展示「转换点为中心」的前后对比视图与批量管理列表，支持上传替换脚本。

**架构：** Python 引擎（transform_table_mapping.py）零逻辑改动，只加 report.json 产出与行号修正；transform_batch.py 批量包装器按 `input/ → output/<脚本名>/` 目录约定落盘；Spring Boot 新增 `/api/transform` 只读文件服务（无新表）；Vue3 新增独立菜单（列表页 + 对比详情页）。

**技术栈：** Python 3（无新依赖）/ Spring Boot 3.2.5 + MyBatis-Plus（JDK17）/ Vue 3 + Element Plus + axios / 既有 `Result<T>` 统一返回体与 `GlobalExceptionHandler`。

**规格：** `docs/superpowers/specs/2026-10-30-transform-visualization-design.md`（行号语义契约见规格 §5.1，是任务 1 的验收核心）。

**多表对限制（实现时注明在代码注释里）：** 当前配置只有 1 个表对。`edits[].line` 以「该表处理前」的文本行号为准，单表对场景 = input 原文行号；未来多表对并存时 `line` 语义需重新定义，本计划不覆盖。

---

## 文件结构（将创建/修改的全部文件）

| 文件 | 职责 |
|------|------|
| 修改 `transform_table_mapping.py` | notes 行号换算为全文行号 + new_line；新增 `build_report_json()` / `convert_one()`；main 增加 `--report-json` |
| 创建 `transform_batch.py` | 批量包装器：遍历 input/ 调 convert_one，产物按目录约定落盘，汇总打印 |
| 创建 `test_report_json.py` | 行号契约测试（全文行号 / new_line / join_inject 移行） |
| 创建 `test_transform_batch.py` | 批量端到端：产物齐全、失败脚本 report.json 兜底、幂等 |
| 创建 `src/main/java/com/hb/extractor/dto/TransformScriptVO.java` | 列表行 VO |
| 创建 `src/main/java/com/hb/extractor/service/TransformService.java` + `impl/TransformServiceImpl.java` | 目录扫描、状态机、路径安全、文件读取 |
| 创建 `src/main/java/com/hb/extractor/controller/TransformController.java` | 5 个 REST 接口 |
| 修改 `src/main/resources/application.yml` | `transform.base-dir` 配置 |
| 创建 `src/test/java/com/hb/extractor/service/TransformServiceImplTest.java` | 状态机单测（@TempDir） |
| 创建 `src/test/java/com/hb/extractor/controller/TransformControllerTest.java` | MockMvc 接口测试 |
| 创建 `frontend/src/api/transform.js` | axios 封装（复用 request.js 实例） |
| 修改 `frontend/src/router/index.js` | 注册 /transform 与 /transform/:name（复刻 ProcDetail 模式） |
| 创建 `frontend/src/views/TransformListView.vue` | 列表 + 上传替换 + 状态徽标 |
| 创建 `frontend/src/views/TransformDetailView.vue` | 转换点列表 + 筛选 + 全文定位 |
| 修改 `docs/deploy/schema.sql` | 追加 sys_menu 菜单记录 INSERT |

不新建 MySQL 表，不动 Neo4j，前端不引新依赖（无 highlight.js，详情页用纯文本行渲染）。

---

### 任务 1：Python — report.json 产出与全文行号契约

**文件：**
- 修改：`transform_table_mapping.py`（transform_text 在 L522-543；pass3_join 的 edits splice 收尾处；main 在 L591-684）
- 测试：创建 `test_report_json.py`

背景（工程师必读）：
- 现有 note 的 `line` 是**三引号块内行号**（pass1/2/3 用 `line_of(block_sql, ...)` 计算），前端需要**全文行号**。
- `edits[].line`=转换前全文行号；`edits[].new_line`=转换后全文行号。rename/table_ref/pass4 类替换**不改行数**，new_line = line + 块内之前注入累计偏移 + 前面块累计净增；`join_inject` 注入 `\n<ins>\n` 净增 2 行，用注入内容（含全局唯一的 FJn 别名）在转换后块文本中 `find` 精确定位。
- `residues[].line` 已是转换后全文行号（residue_check 在 new_text 上跑，L618、L575），**不要动**。

- [ ] **步骤 1：写失败的测试**

创建 `test_report_json.py`：

```python
# -*- coding: utf-8 -*-
"""report.json 行号契约测试：edits[].line=转换前全文行号，new_line=转换后全文行号。"""
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).parent
TMP = ROOT / '.tmp_report_json_test'


def _run(script, out):
    r = subprocess.run(
        [sys.executable, str(ROOT / 'transform_table_mapping.py'),
         '--script', str(script), '--out', str(out),
         '--report-json', str(out.with_suffix('.report.json'))],
        capture_output=True, text=True, cwd=ROOT)
    assert r.returncode == 0, r.stderr
    return json.loads(out.with_suffix('.report.json').read_text(encoding='utf-8'))


def test_line_numbers_full_text():
    """真实脚本：3 处 table_ref 的 line 必须是全文行号（原文 F 表引用所在行）。"""
    TMP.mkdir(exist_ok=True)
    src = ROOT / 'tmp1030' / 'dwa_c_agt_deposit_acct_inf.py'
    out = TMP / 'agt.py'
    rep = _run(src, out)
    assert rep['summary']['total'] == 3, rep['summary']  # 1 table_ref + 2 rename
    assert rep['summary']['table_ref'] == 1
    # 原文 L477 是 LEFT JOIN {FDM}.F_PT_ECIF_PUB_CUST_INF    T2
    orig_lines = src.read_text(encoding='utf-8').splitlines()
    tr = [e for e in rep['edits'] if e['type'] == 'table_ref'][0]
    assert 'F_PT_ECIF_PUB_CUST_INF' in orig_lines[tr['line'] - 1], tr['line']
    # new_line 行（转换后文本）必须是 O 表引用
    conv_lines = out.read_text(encoding='utf-8').splitlines()
    assert 'O_ECIF_M_CI_ORG' in conv_lines[tr['new_line'] - 1], tr['new_line']
    # rename：old/new 行内容校验
    for e in rep['edits']:
        if e['type'] == 'rename':
            assert e['old'].split('.', 1)[1] in orig_lines[e['line'] - 1], e
            assert e['new'].split('.', 1)[1] in conv_lines[e['new_line'] - 1], e
    shutil.rmtree(TMP, ignore_errors=True)


def test_join_inject_new_line():
    """含 core_cust_no 的脚本：JOIN 注入后，注入行及其后 edits 的 new_line 均移 +2。"""
    TMP.mkdir(exist_ok=True)
    sql = ('#更新对象 CDM.T_OUT\n'
           'bsql="""\n'
           'INSERT INTO {CDM}.T_OUT\n'
           'SELECT A.cust_no, A.core_cust_no, A.uscc_cd\n'
           '  FROM {FDM}.F_PT_ECIF_PUB_CUST_INF A\n'
           ' WHERE A.del_flag <> \\'D\\';\n'
           '"""\n')
    src = TMP / 'inj.py'
    out = TMP / 'inj_out.py'
    src.write_text(sql, encoding='utf-8')
    rep = _run(src, out)
    conv_lines = out.read_text(encoding='utf-8').splitlines()
    inj = [e for e in rep['edits'] if e['type'] == 'join_inject']
    assert len(inj) == 1, rep['edits']
    # 注入的 JOIN 行内容必须精确出现在 new_line 行
    assert 'LEFT JOIN {ODM}.O_ECIF_M_CI_CUSTOMER FJ1' in conv_lines[inj[0]['new_line'] - 1]
    # 注入点之后的 rename（uscc_cd→busi_lic_no 原文 L4 → 转换后 L6）
    rn = [e for e in rep['edits'] if e['type'] == 'rename'
          and 'uscc' in e['old'].lower()]
    assert rn and rn[0]['new_line'] == rn[0]['line'] + 2, rn[0]
    shutil.rmtree(TMP, ignore_errors=True)


if __name__ == '__main__':
    test_line_numbers_full_text()
    test_join_inject_new_line()
    print('ALL PASS')
```

- [ ] **步骤 2：运行测试验证失败**

运行：`python test_report_json.py`
预期：FAIL —— `--report-json` 参数不存在（argparse 报 unrecognized arguments）。

- [ ] **步骤 3：实现 pass3 注入行号记录**

在 `pass3_join` 中，edits 倒序 splice 应用完毕之后、`return sql, ...` 之前，对每个 join_inject note 补记块内转换后行号（FJn 别名全文唯一，find 锚点可靠）：

```python
        # join_inject：在转换后块文本中精确定位注入内容行（FJn 别名唯一）
        for note in notes:
            if note.get('type') == 'join_inject' and note.get('_fj') :
                pos = sql.find(' ' + note['_fj'] + ' ')
                if pos >= 0:
                    note['_new_line_block'] = line_of(sql, pos)
```

对应地，pass3 生成注入 edit 时在 note 里加 `'_fj': join_alias`（即 FJn 字符串）。注意 pass3 现有 note 字段里 join 别名信息在 `new` 文本内，不单独存在——必须新增该字段。

- [ ] **步骤 4：实现 transform_text 行号换算**

将 `transform_text`（L522-543）替换为：

```python
def _fix_line_numbers(old_block, new_block, notes, base_before, add_lines):
    """把块内行号换算为全文行号（契约见规格 §5.1）。
    line = 转换前全文行号；new_line = 转换后全文行号。
    非注入类 edit 不改行数；join_inject 净增 2 行，其 new_line 用 pass3
    记录的 _new_line_block 精确换算。"""
    inj = 0
    for note in sorted(notes, key=lambda x: x['line']):
        note['line'] = note['line'] + base_before - 1
        if note.get('type') == 'join_inject':
            note['new_line'] = (note.pop('_new_line_block')
                                + base_before - 1 + add_lines)
            inj += 2
        else:
            note['new_line'] = note['line'] + inj + add_lines


def transform_text(text, tables_cfg, notes_by_table):
    """对整份 PY 文本做转换：三引号块内逐表三遍转换，块外原样。
    notes 的 line/new_line 均为全文行号（单表对场景：line=input 原文行号）。"""
    add_lines = 0  # 已处理块累计净增行数
    for old_table, cfg in tables_cfg.items():
        tr = TableTransformer(old_table, cfg)
        notes = notes_by_table.setdefault(old_table, [])

        def do_block(block_sql, notes=notes, tr=tr):
            tr.reset_block_state()
            b, n1 = tr.pass1_table_ref(block_sql, notes)
            b, n2 = tr.pass2_rename(b, notes)
            b, n3 = tr.pass3_join(b, notes)
            b, n4 = tr.pass4_subquery(b, notes)
            return b

        out, last = [], 0
        for m in SQL_BLOCK_RE.finditer(text):
            n0 = len(notes)
            new_block = do_block(m.group(2))
            base_before = text.count('\n', 0, m.start(2)) + 1
            _fix_line_numbers(m.group(2), new_block, notes[n0:],
                              base_before, add_lines)
            add_lines += new_block.count('\n') - m.group(2).count('\n')
            out.append(text[last:m.start(2)])
            out.append(new_block)
            last = m.end(2)
        out.append(text[last:])
        text = ''.join(out)
    return text
```

注意：`add_lines` 在表循环**外**初始化（多表对时第二表在第一表替换后的文本上跑，`base_before` 天然已是新文本行号；单表对时 `add_lines` 全程为 0 起步逐步累计）。

- [ ] **步骤 5：实现 build_report_json + main 接线**

在 `residue_check` 之后新增：

```python
def build_report_json(src_name, tables_cfg, notes_by_table, residues,
                      py_status, error=None):
    """机读报告（规格 §5.1）。residues 元组结构为 (type, content, line)。"""
    edits = []
    for old_table, notes in notes_by_table.items():
        for n in notes:
            edits.append({'type': n['type'], 'table': old_table,
                          'line': n['line'], 'new_line': n['new_line'],
                          'old': n['old'], 'new': n['new']})
    edits.sort(key=lambda e: (e['line'], e['new_line']))
    by_type = {}
    for e in edits:
        by_type[e['type']] = by_type.get(e['type'], 0) + 1
    first_tbl = next(iter(tables_cfg))
    first_cfg = tables_cfg[first_tbl]
    rep = {
        'script': src_name,
        'converted_at': datetime.now().isoformat(timespec='seconds'),
        'config': {'old_db': first_cfg.get('old_db', ''),
                   'old_table': first_tbl,
                   'new_db': first_cfg.get('new_db', ''),
                   'new_table': first_cfg['new_table']},
        'summary': dict(by_type, total=len(edits), residues=len(residues)),
        'edits': edits,
        'residues': [{'type': t, 'content': c, 'line': ln}
                     for t, c, ln in residues],
        'py_compile': py_status,
    }
    if error:
        rep['error'] = error
    return rep
```

文件头 import 区补 `from datetime import datetime`。

`main()` 修改（对照 L591-684 现有代码）：
1. argparse 增加 `ap.add_argument('--report-json', default=None, help='report.json 输出路径；缺省为 <out>_report.json')`
2. py_compile 段改为：无论 PASS/FAIL 都生成 report：

```python
    # py_compile 校验（FAIL 也要产出 report.json，前端展示失败态）
    import py_compile
    py_status, error = 'PASS', None
    try:
        py_compile.compile(args.out, doraise=True)
        print('  - py_compile: PASS')
    except py_compile.PyCompileError as e:
        py_status, error = 'FAIL', str(e)
        print('  - py_compile: FAIL %s' % e, file=sys.stderr)
    report = build_report_json(src.name, tables_cfg, notes_by_table,
                               residues, py_status, error)
    report_path = (Path(args.report_json) if args.report_json
                   else Path(args.out).with_suffix('').with_suffix('.report.json')
                   if Path(args.out).suffix == '.py'
                   else Path(args.out).with_suffix('.report.json'))
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2),
                           encoding='utf-8')
    print('  - report.json: %s' % report_path)
    if py_status == 'FAIL':
        sys.exit(2)
```

默认路径规则简化说明：`dwa_x.py` → `dwa_x.report.json`（`with_suffix('')` 去掉 `.py` 再接 `.report.json` 的写法等价于 `Path(str(out)[:-3] + '.report.json')`，实现时用后者更直白）。

- [ ] **步骤 6：运行测试验证通过**

运行：`python test_report_json.py`
预期：输出 `ALL PASS`。

- [ ] **步骤 7：回归现有测试**

运行：`python test_transform_mini.py; python test_transform_mini2.py`
预期：两者无断言异常（exit 0）。

- [ ] **步骤 8：Commit**

```bash
git add transform_table_mapping.py test_report_json.py
git commit -m "feat(transform): 转换产出机读 report.json，行号契约改为全文行号+new_line"
```

---

### 任务 2：Python — convert_one 抽取与 transform_batch 批量包装器

**文件：**
- 修改：`transform_table_mapping.py`（main 的核心逻辑抽为 `convert_one`）
- 创建：`transform_batch.py`
- 测试：创建 `test_transform_batch.py`

目录约定（规格 §4）：`output/<脚本名去 .py>/` 下产出 `source.py`（原文快照）、`converted.py`、`report.json`、`report.md`、`diff.html`、`diff.txt`。

- [ ] **步骤 1：写失败的测试**

创建 `test_transform_batch.py`：

```python
# -*- coding: utf-8 -*-
"""transform_batch 端到端：产物齐全 / 失败脚本 report.json 兜底 / 幂等。"""
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).parent
TMP = ROOT / '.tmp_batch_test'


def _batch():
    r = subprocess.run(
        [sys.executable, str(ROOT / 'transform_batch.py'),
         '--input', str(TMP / 'input'), '--output', str(TMP / 'output'),
         '--config', str(ROOT / 'transform_config.json')],
        capture_output=True, text=True, cwd=ROOT)
    return r


def test_batch_end_to_end():
    shutil.rmtree(TMP, ignore_errors=True)
    (TMP / 'input').mkdir(parents=True)
    shutil.copy(ROOT / 'tmp1030' / 'dwa_c_agt_deposit_acct_inf.py',
                TMP / 'input' / 'dwa_c_agt_deposit_acct_inf.py')
    # 语法错误脚本：py_compile FAIL 但必须产出 report.json
    (TMP / 'input' / 'broken.py').write_text(
        '#更新对象 X\nbsql="""\nSELECT FROM WHERE\n"""\n', encoding='utf-8')

    r = _batch()
    assert r.returncode == 0, r.stderr
    good = TMP / 'output' / 'dwa_c_agt_deposit_acct_inf'
    for f in ('source.py', 'converted.py', 'report.json',
              'report.md', 'diff.html', 'diff.txt'):
        assert (good / f).exists(), f
    rep = json.loads((good / 'report.json').read_text(encoding='utf-8'))
    assert rep['summary']['total'] == 3 and rep['py_compile'] == 'PASS'
    bad = TMP / 'output' / 'broken'
    brep = json.loads((bad / 'report.json').read_text(encoding='utf-8'))
    assert brep['py_compile'] == 'FAIL' and brep.get('error')
    assert (bad / 'source.py').exists()
    # 汇总输出包含成功/失败统计
    assert '成功 1' in r.stdout and '失败 1' in r.stdout
    shutil.rmtree(TMP, ignore_errors=True)


def test_batch_idempotent():
    """对同一 input 重跑：成功脚本 report.json 的 edits 完全一致。"""
    shutil.rmtree(TMP, ignore_errors=True)
    (TMP / 'input').mkdir(parents=True)
    shutil.copy(ROOT / 'tmp1030' / 'dwa_c_agt_deposit_acct_inf.py',
                TMP / 'input' / 'dwa_c_agt_deposit_acct_inf.py')
    _batch()
    p1 = (TMP / 'output' / 'dwa_c_agt_deposit_acct_inf' / 'report.json'
          ).read_text(encoding='utf-8')
    _batch()
    p2 = (TMP / 'output' / 'dwa_c_agt_deposit_acct_inf' / 'report.json'
          ).read_text(encoding='utf-8')
    j1, j2 = json.loads(p1), json.loads(p2)
    j1.pop('converted_at'), j2.pop('converted_at')
    assert j1 == j2
    shutil.rmtree(TMP, ignore_errors=True)


if __name__ == '__main__':
    test_batch_end_to_end()
    test_batch_idempotent()
    print('ALL PASS')
```

- [ ] **步骤 2：运行测试验证失败**

运行：`python test_transform_batch.py`
预期：FAIL —— `transform_batch.py` 不存在（subprocess 报文件找不到或 returncode 断言失败）。

- [ ] **步骤 3：抽取 convert_one**

在 `transform_table_mapping.py` 中，把 main() 里「读配置之后到 diff 输出」的核心逻辑抽成可复用函数（main 改为调用它）：

```python
def convert_one(src, out, config_path):
    """转换单脚本：写 out、report.md/_diff.txt/_diff.html，返回 report dict。
    report.json 写到 <out 去扩展名>.report.json；py_compile FAIL 不抛异常，
    通过 report['py_compile']/report['error'] 表达。"""
    text = Path(src).read_text(encoding='utf-8')
    config = json.loads(Path(config_path).read_text(encoding='utf-8'))
    tables_cfg = config.get('tables', {})
    notes_by_table = {}
    new_text = transform_text(text, tables_cfg, notes_by_table)
    Path(out).write_text(new_text, encoding='utf-8')
    residues = residue_check(new_text, tables_cfg)
    # ……（原 main 中生成 report.md / diff.txt / diff.html 的代码原样搬入，
    #      文件名前缀改用 Path(out).with_suffix('') 作为前缀基准）
    import py_compile
    py_status, error = 'PASS', None
    try:
        py_compile.compile(str(out), doraise=True)
    except py_compile.PyCompileError as e:
        py_status, error = 'FAIL', str(e)
    report = build_report_json(Path(src).name, tables_cfg, notes_by_table,
                               residues, py_status, error)
    Path(str(out)[:-3] + '.report.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    return report
```

main() 保留 argparse（含任务 1 的 `--report-json`，改为透传给 convert_one 后按需重命名，或直接改为调用 convert_one 后把默认 report.json 复制到 --report-json 路径）；行为与任务 1 完全一致，重跑 `python test_report_json.py` 确认不回归。

- [ ] **步骤 4：实现 transform_batch.py**

```python
# -*- coding: utf-8 -*-
"""
transform_batch.py — 批量转换包装器（离线执行，一条命令）

用法：
  python transform_batch.py --input <待转换目录> --output <产物目录> \
      [--config transform_config.json]

目录约定（规格 §4）：
  input/*.py                    待转换脚本（网页上传或手工放置）
  output/<脚本名>/              每脚本一个产物目录：
    source.py converted.py report.json report.md diff.txt diff.html
"""
import argparse
import json
import shutil
import sys
from pathlib import Path

from transform_table_mapping import convert_one


def main():
    ap = argparse.ArgumentParser(description='批量转换 input/ 下全部 .py 脚本')
    ap.add_argument('--input', required=True)
    ap.add_argument('--output', required=True)
    ap.add_argument('--config', default='transform_config.json')
    args = ap.parse_args()

    in_dir, out_dir = Path(args.input), Path(args.output)
    scripts = sorted(in_dir.glob('*.py'))
    if not scripts:
        print('input 目录无 .py 脚本：%s' % in_dir)
        return
    ok = fail = 0
    for src in scripts:
        name = src.stem
        d = out_dir / name
        d.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, d / 'source.py')
        print('转换 %s ...' % src.name)
        try:
            rep = convert_one(src, d / 'converted.py', args.config)
        except Exception as e:  # 转换引擎异常也兜底成 report.json
            rep = {'script': src.name, 'py_compile': 'FAIL', 'error': str(e),
                   'summary': {'total': 0, 'residues': 0},
                   'edits': [], 'residues': [], 'config': {}}
            (d / 'report.json').write_text(
                json.dumps(rep, ensure_ascii=False, indent=2),
                encoding='utf-8')
        # convert_one 把 report 写在 converted.report.json，统一挪为 report.json
        rj = d / 'converted.report.json'
        if rj.exists():
            rj.replace(d / 'report.json')
        if rep.get('py_compile') == 'PASS':
            ok += 1
            print('  [OK] %s 处转换，残留 %s'
                  % (rep['summary']['total'], rep['summary']['residues']))
        else:
            fail += 1
            print('  [FAIL] %s' % rep.get('error', ''), file=sys.stderr)
    print('批量完成：成功 %d，失败 %d，共 %d' % (ok, fail, len(scripts)))
    if fail:
        sys.exit(3)


if __name__ == '__main__':
    main()
```

注意：`convert_one` 内部 diff/report 文件名前缀基于 `converted.py`，即生成 `converted_report.md` / `converted_diff.html`——与目录约定里的 `report.md`/`diff.html` 命名不符。**在 batch 中重命名**：转换后把 `converted_report.md→report.md`、`converted_report_diff.txt→diff.txt`、`converted_report_diff.html→diff.html` 一并 replace（加在 report.json 挪名同处）。测试步骤 1 的断言文件名以目录约定为准。

- [ ] **步骤 5：运行测试验证通过**

运行：`python test_transform_batch.py`
预期：`ALL PASS`。若 `broken.py` 实际未触发 py_compile FAIL（例如它恰好语法合法），改用 `bsql="""\nSELECT ~~FROM~~\n` 这类确定性语法错误并重跑。

- [ ] **步骤 6：全量回归**

运行：`python test_report_json.py; python test_transform_mini.py; python test_transform_mini2.py`
预期：全部 PASS（exit 0）。

- [ ] **步骤 7：Commit**

```bash
git add transform_table_mapping.py transform_batch.py test_transform_batch.py
git commit -m "feat(transform): convert_one 可复用化 + transform_batch 批量包装器（目录约定产物）"
```

---

### 任务 3：后端 — TransformService（目录扫描 / 状态机 / 路径安全）

**文件：**
- 创建：`src/main/java/com/hb/extractor/dto/TransformScriptVO.java`
- 创建：`src/main/java/com/hb/extractor/service/TransformService.java`
- 创建：`src/main/java/com/hb/extractor/service/impl/TransformServiceImpl.java`
- 测试：创建 `src/test/java/com/hb/extractor/service/TransformServiceImplTest.java`

参照模式：`Result<T>`（dto/Result.java，`Result.success(...)`）；`@Value("${extractor.report.dir:...}")` 配置注入（ReportController.java L40-52）；`BusinessException` + GlobalExceptionHandler 统一兜底。

**状态机（规格 §6）：** `PENDING`（仅 input 无 output/report.json）/ `CONVERTED`（report.json 且 py_compile=PASS 且无 error）/ `FAILED`（report.json 且 py_compile=FAIL 或 error 非空，或 output 目录存在但无 report.json）/ `STALE`（已转换但 input 文件 lastModified > output/source.py lastModified，即源已被替换）。`residueCount > 0` 不改变状态，由前端展示黄色徽标。

- [ ] **步骤 1：写失败的测试**

```java
package com.hb.extractor.service;

import com.hb.extractor.dto.TransformScriptVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransformServiceImplTest {

    @TempDir
    Path tmp;

    private TransformServiceImpl service() {
        return new TransformServiceImpl(tmp.toString());
    }

    private void putInput(String name) throws Exception {
        Files.writeString(tmp.resolve("input").resolve(name),
                "#更新对象 X\nbsql=\"\"\"SELECT 1\"\"\"\n");
    }

    private void putOutput(String dirName, String reportJson) throws Exception {
        Path d = tmp.resolve("output").resolve(dirName);
        Files.createDirectories(d);
        Files.writeString(d.resolve("source.py"), "orig\n");
        Files.writeString(d.resolve("converted.py"), "conv\n");
        Files.writeString(d.resolve("report.json"), reportJson);
    }

    private static final String OK_REPORT = """
            {"script":"a.py","py_compile":"PASS",
             "config":{"old_db":"FDM","old_table":"F_T","new_db":"ODM","new_table":"O_T"},
             "summary":{"total":3,"residues":0},
             "edits":[],"residues":[]}""";

    @Test
    void pending_whenOnlyInput() throws Exception {
        putInput("a.py");
        List<TransformScriptVO> list = service().listScripts();
        assertEquals(1, list.size());
        assertEquals("PENDING", list.get(0).getStatus());
    }

    @Test
    void converted_whenReportOk() throws Exception {
        putInput("a.py");
        putOutput("a", OK_REPORT);
        TransformScriptVO vo = service().listScripts().get(0);
        assertEquals("CONVERTED", vo.getStatus());
        assertEquals(3, vo.getTotalEdits());
        assertEquals(0, vo.getResidueCount());
    }

    @Test
    void failed_whenCompileFail() throws Exception {
        putInput("a.py");
        putOutput("a", OK_REPORT.replace("\"PASS\"", "\"FAIL\"")
                .replace("}],", "}],\"error\":\"syntax\""));
        assertEquals("FAILED", service().listScripts().get(0).getStatus());
    }

    @Test
    void stale_whenInputReplaced() throws Exception {
        putInput("a.py");
        putOutput("a", OK_REPORT);
        // 确保输入 mtime 严格晚于 source.py
        Path input = tmp.resolve("input").resolve("a.py");
        input.toFile().setLastModified(
                tmp.resolve("output/a/source.py").toFile().lastModified() + 60_000);
        assertEquals("STALE", service().listScripts().get(0).getStatus());
    }

    @Test
    void rejectsIllegalName() {
        assertThrows(Exception.class, () -> service().getReport("../etc"));
        assertThrows(Exception.class, () -> service().readFile("a b", "source"));
    }

    @Test
    void readFileSourceAndConverted() throws Exception {
        putInput("a.py");
        putOutput("a", OK_REPORT);
        assertEquals("orig\n", service().readFile("a", "source"));
        assertEquals("conv\n", service().readFile("a", "converted"));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -Dtest=TransformServiceImplTest -q`
预期：编译失败（类不存在）。

- [ ] **步骤 3：实现 VO 与 Service**

`TransformScriptVO.java`：

```java
package com.hb.extractor.dto;

import lombok.Data;

/** 脚本转换列表行 VO（状态机见规格 §6） */
@Data
public class TransformScriptVO {
    private String name;          // 脚本名（含 .py）
    private String status;        // PENDING / CONVERTED / FAILED / STALE
    private Integer totalEdits;
    private Integer residueCount;
    private String pyCompile;     // PASS / FAIL / null(未转换)
    private String convertedAt;   // report.json converted_at
    private String errorMsg;      // 失败摘要
    private String oldTable;      // 映射快照（F 表）
    private String newTable;      // 映射快照（O 表）
}
```

`TransformService.java`：

```java
package com.hb.extractor.service;

import com.hb.extractor.dto.TransformScriptVO;

import java.util.List;
import java.util.Map;

/** 脚本转换产物只读服务（目录约定见规格 §4） */
public interface TransformService {
    List<TransformScriptVO> listScripts();
    Map<String, Object> getReport(String name);
    String readFile(String name, String ver);   // ver: source | converted
    Path upload(MultipartFile file);            // 存 input/，同名覆盖
    Path resolveDownload(String name, String type); // type: converted|md|html
}
```

`TransformServiceImpl.java` 关键实现（完整类）：

```java
package com.hb.extractor.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.dto.TransformScriptVO;
import com.hb.extractor.exception.BusinessException;
import com.hb.extractor.service.TransformService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class TransformServiceImpl implements TransformService {

    /** 名称白名单：字母数字下划线点横线，不以点开头，防路径穿越（规格 §6） */
    private static final Pattern NAME_RE = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9_.-]*");
    private static final long MAX_UPLOAD = 10L * 1024 * 1024;

    private final Path baseDir;
    private final ObjectMapper om = new ObjectMapper();

    public TransformServiceImpl(
            @Value("${transform.base-dir:./transforms}") String baseDir) {
        this.baseDir = Path.of(baseDir);
    }

    private Path inputDir() { return baseDir.resolve("input"); }
    private Path outputDir() { return baseDir.resolve("output"); }

    private Path safe(String name, String... more) {
        if (!NAME_RE.matcher(name).matches()) {
            throw new BusinessException("非法脚本名: " + name);
        }
        Path p = baseDir.resolve(name);
        for (String m : more) p = p.resolve(m);
        return p;
    }

    @Override
    public List<TransformScriptVO> listScripts() {
        Map<String, TransformScriptVO> map = new TreeMap<>();
        if (Files.isDirectory(inputDir())) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(inputDir(), "*.py")) {
                for (Path p : ds) {
                    TransformScriptVO vo = new TransformScriptVO();
                    vo.setName(p.getFileName().toString());
                    vo.setStatus("PENDING");
                    map.put(vo.getName(), vo);
                }
            } catch (Exception e) {
                throw new BusinessException("扫描 input 目录失败: " + e.getMessage());
            }
        }
        if (Files.isDirectory(outputDir())) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(outputDir())) {
                for (Path d : ds) {
                    if (!Files.isDirectory(d)) continue;
                    String stem = d.getFileName().toString();
                    Path report = d.resolve("report.json");
                    TransformScriptVO vo = map.computeIfAbsent(stem + ".py", n -> {
                        TransformScriptVO v = new TransformScriptVO();
                        v.setName(n);
                        return v;
                    });
                    if (!Files.exists(report)) {
                        vo.setStatus("FAILED");
                        vo.setErrorMsg("output 缺少 report.json");
                        continue;
                    }
                    try {
                        Map<String, Object> r = om.readValue(report.toFile(), Map.class);
                        vo.setPyCompile((String) r.get("py_compile"));
                        vo.setErrorMsg((String) r.get("error"));
                        vo.setConvertedAt((String) r.get("converted_at"));
                        Map<String, Object> cfg = (Map<String, Object>) r.get("config");
                        if (cfg != null) {
                            vo.setOldTable(cfg.get("old_db") + "." + cfg.get("old_table"));
                            vo.setNewTable(cfg.get("new_db") + "." + cfg.get("new_table"));
                        }
                        Map<String, Object> sum = (Map<String, Object>) r.get("summary");
                        vo.setTotalEdits(sum == null ? 0 : ((Number) sum.getOrDefault("total", 0)).intValue());
                        vo.setResidueCount(sum == null ? 0 : ((Number) sum.getOrDefault("residues", 0)).intValue());
                        boolean bad = "FAIL".equals(vo.getPyCompile())
                                || (vo.getErrorMsg() != null && !vo.getErrorMsg().isBlank());
                        vo.setStatus(bad ? "FAILED" : "CONVERTED");
                        // 源被替换：input mtime 晚于转换时的 source.py 快照
                        Path input = inputDir().resolve(stem + ".py");
                        Path snapshot = d.resolve("source.py");
                        if (Files.exists(input) && Files.exists(snapshot)
                                && input.toFile().lastModified() > snapshot.toFile().lastModified()) {
                            vo.setStatus("STALE");
                        }
                    } catch (Exception e) {
                        vo.setStatus("FAILED");
                        vo.setErrorMsg("report.json 解析失败: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                throw new BusinessException("扫描 output 目录失败: " + e.getMessage());
            }
        }
        return new ArrayList<>(map.values());
    }

    @Override
    public Map<String, Object> getReport(String name) {
        Path p = safe(name.replace(".py", ""), "output",
                      name.replace(".py", ""), "report.json");
        if (!Files.exists(p)) throw new BusinessException("该脚本尚未转换");
        try { return om.readValue(p.toFile(), Map.class); }
        catch (Exception e) { throw new BusinessException("report.json 解析失败"); }
    }

    @Override
    public String readFile(String name, String ver) {
        String stem = name.replace(".py", "");
        Path p = switch (ver) {
            case "source" -> safe(stem, "output", stem, "source.py");
            case "converted" -> safe(stem, "output", stem, "converted.py");
            default -> throw new BusinessException("ver 仅支持 source|converted");
        };
        if (!Files.exists(p)) throw new BusinessException("文件不存在: " + ver);
        try { return Files.readString(p); }
        catch (Exception e) { throw new BusinessException("读取失败"); }
    }

    @Override
    public Path upload(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || !name.endsWith(".py") || !NAME_RE.matcher(name).matches()) {
            throw new BusinessException("仅支持上传 .py 脚本，且文件名限字母数字下划线点横线");
        }
        try {
            if (file.getSize() > MAX_UPLOAD) throw new BusinessException("文件超过 10MB 上限");
            Files.createDirectories(inputDir());
            Path dst = inputDir().resolve(name);
            file.transferTo(dst.toAbsolutePath());
            return dst;
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException("保存上传文件失败: " + e.getMessage()); }
    }

    @Override
    public Path resolveDownload(String name, String type) {
        String stem = name.replace(".py", "");
        Path p = switch (type) {
            case "converted" -> safe(stem, "output", stem, "converted.py");
            case "md" -> safe(stem, "output", stem, "report.md");
            case "html" -> safe(stem, "output", stem, "diff.html");
            default -> throw new BusinessException("type 仅支持 converted|md|html");
        };
        if (!Files.exists(p)) throw new BusinessException("产物不存在: " + type);
        return p;
    }
}
```

若 `BusinessException` 构造签名不同（先查看 `exception/BusinessException.java`，按现有构造器调整）。`Instant` import 若未用则删除。

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn test -Dtest=TransformServiceImplTest -q`
预期：6 个测试全 PASS。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/hb/extractor/dto/TransformScriptVO.java src/main/java/com/hb/extractor/service/TransformService.java src/main/java/com/hb/extractor/service/impl/TransformServiceImpl.java src/test/java/com/hb/extractor/service/TransformServiceImplTest.java
git commit -m "feat(transform): TransformService 目录扫描与状态机（PENDING/CONVERTED/FAILED/STALE）+ 路径安全"
```

---

### 任务 4：后端 — TransformController 与配置

**文件：**
- 创建：`src/main/java/com/hb/extractor/controller/TransformController.java`
- 修改：`src/main/resources/application.yml`（`extractor.report.dir` 附近追加）
- 测试：创建 `src/test/java/com/hb/extractor/controller/TransformControllerTest.java`

- [ ] **步骤 1：写失败的测试**

参照 `AuthControllerTest` 的 MockMvc 组织方式（先读该文件确认 @SpringBootTest/webEnvironment 或 @WebMvcTest 与鉴权绕过手法，完全复刻其头部配置）：

```java
package com.hb.extractor.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "transform.base-dir=target/.tmp_transform_it")
class TransformControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void listEmptyDir() throws Exception {
        mvc.perform(get("/api/transform/scripts"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void uploadPyThenListPending() throws Exception {
        MockMultipartFile f = new MockMultipartFile(
                "file", "dwa_demo.py", "text/plain", "#更新对象 X\n".getBytes());
        mvc.perform(multipart("/api/transform/upload").file(f))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("dwa_demo.py")));
        mvc.perform(get("/api/transform/scripts"))
           .andExpect(jsonPath("$.data[0].status").value("PENDING"));
        Files.deleteIfExists(Path.of("target/.tmp_transform_it/input/dwa_demo.py"));
    }

    @Test
    void uploadRejectsNonPy() throws Exception {
        MockMultipartFile f = new MockMultipartFile(
                "file", "evil.sh", "text/plain", "rm -rf".getBytes());
        mvc.perform(multipart("/api/transform/upload").file(f))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(500)); // BusinessException 全局处理码，以现有 GlobalExceptionHandler 实际值为准
    }

    @Test
    void pathTraversalRejected() throws Exception {
        mvc.perform(get("/api/transform/scripts/..%2Fetc/report"))
           .andExpect(jsonPath("$.code").value(500));
    }
}
```

注意：断言前先运行一次确认 `Result` 成功码实际值（dto/Result.java 的 success() 里 code 是 200 还是 0）与 GlobalExceptionHandler 返回的错误码，按实际值修正测试，不要臆造。

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -Dtest=TransformControllerTest -q`
预期：编译失败（Controller 不存在）或 404。

- [ ] **步骤 3：实现 Controller**

```java
package com.hb.extractor.controller;

import com.hb.extractor.dto.Result;
import com.hb.extractor.service.TransformService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 脚本转换可视化接口（只读展示 + 上传，规格 §6） */
@Slf4j
@RestController
@RequestMapping("/api/transform")
@RequiredArgsConstructor
public class TransformController {

    private final TransformService transformService;

    @GetMapping("/scripts")
    public Result<List<?>> scripts() {
        return Result.success(transformService.listScripts());
    }

    @GetMapping("/scripts/{name}/report")
    public Result<Map<String, Object>> report(@PathVariable String name) {
        return Result.success(transformService.getReport(name));
    }

    @GetMapping("/scripts/{name}/file")
    public Result<String> file(@PathVariable String name,
                               @RequestParam(defaultValue = "source") String ver) {
        return Result.success(transformService.readFile(name, ver));
    }

    @PostMapping("/upload")
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        Path saved = transformService.upload(file);
        boolean replaced = transformService.listScripts().stream()
                .anyMatch(v -> v.getName().equals(saved.getFileName().toString())
                        && !"PENDING".equals(v.getStatus()));
        return Result.success(replaced
                ? "已替换 " + saved.getFileName() + "，需重新离线转换"
                : "已上传 " + saved.getFileName());
    }

    @GetMapping("/scripts/{name}/download")
    public ResponseEntity<FileSystemResource> download(
            @PathVariable String name, @RequestParam String type) {
        Path p = transformService.resolveDownload(name, type);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + p.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(p));
    }
}
```

- [ ] **步骤 4：application.yml 追加配置**

在 `extractor:` 配置块同级追加（对齐现有缩进风格）：

```yaml
# 脚本转换产物根目录（input/ 待转换，output/ 产物；可用 TRANSFORM_BASE_DIR 环境变量覆盖）
transform:
  base-dir: ${TRANSFORM_BASE_DIR:./transforms}
```

- [ ] **步骤 5：运行测试验证通过**

运行：`mvn test -Dtest=TransformControllerTest -q`
预期：4 个测试 PASS。

- [ ] **步骤 6：全量后端回归**

运行：`mvn test -q`
预期：BUILD SUCCESS，无既有测试回归。

- [ ] **步骤 7：Commit**

```bash
git add src/main/java/com/hb/extractor/controller/TransformController.java src/test/java/com/hb/extractor/controller/TransformControllerTest.java src/main/resources/application.yml
git commit -m "feat(transform): /api/transform 五接口（列表/报告/全文/上传/下载）+ base-dir 配置"
```

---

### 任务 5：前端 — api 封装、路由与菜单

**文件：**
- 创建：`frontend/src/api/transform.js`
- 修改：`frontend/src/router/index.js`
- 修改：`docs/deploy/schema.sql`

- [ ] **步骤 1：api/transform.js**

先读 `frontend/src/api/request.js`（axios 实例，baseURL '/api'）与 `frontend/src/api/proc.js`（FormData 上传模式），然后：

```javascript
import request from './request'

// 脚本转换：列表 / 报告 / 全文 / 上传 / 下载地址
export function listScripts() {
  return request.get('/transform/scripts')
}

export function getReport(name) {
  return request.get(`/transform/scripts/${encodeURIComponent(name)}/report`)
}

export function getScriptFile(name, ver) {
  return request.get(`/transform/scripts/${encodeURIComponent(name)}/file`, {
    params: { ver },
  })
}

export function uploadScript(file) {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/transform/upload', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export function downloadUrl(name, type) {
  return `/api/transform/scripts/${encodeURIComponent(name)}/download?type=${type}`
}
```

- [ ] **步骤 2：注册路由**

先读 `router/index.js` 中 ProcDetailView 的路由声明与 beforeEach 守卫对动态段（`/proc/:id` 类）的匹配方式，**完全复刻该模式**注册：

```javascript
{
  path: '/transform',
  name: 'TransformList',
  component: () => import('@/views/TransformListView.vue'),
  meta: { title: '脚本转换' },
},
{
  path: '/transform/:name',
  name: 'TransformDetail',
  component: () => import('@/views/TransformDetailView.vue'),
  meta: { title: '转换对比' },
},
```

守卫若为 `user.menus` 精确路径匹配而动态段无法命中，则按 ProcDetailView 现有处理方式对齐（它已在线上工作，必有既有解法——照抄，不发明新机制）。

- [ ] **步骤 3：菜单记录**

在 `docs/deploy/schema.sql` 末尾追加（**先执行 `SELECT * FROM sys_menu LIMIT 3;` 确认实际字段名与既有行的 path/title 格式，按既有行样式对齐后落库**）：

```sql
-- 脚本转换可视化菜单（字段名以 sys_menu 实际结构为准，参照既有行样式）
INSERT INTO sys_menu (path, title, ...) VALUES ('/transform', '脚本转换', ...);
```

落库后用现有「角色管理」界面（RoleController `PUT /api/roles/{id}/menus`）给目标角色勾选新菜单。

- [ ] **步骤 4：验证路由可达**

运行：`cd frontend; npm run dev`，登录后确认菜单出现、/transform 打开空白列表页（此时视图文件尚未创建会报错——本步骤先创建两个最小占位视图文件，仅含 `<template><div>脚本转换</div></template>`，任务 6/7 再填充；占位文件也纳入本任务 commit）。

- [ ] **步骤 5：Commit**

```bash
git add frontend/src/api/transform.js frontend/src/router/index.js frontend/src/views/TransformListView.vue frontend/src/views/TransformDetailView.vue docs/deploy/schema.sql
git commit -m "feat(transform-front): api 封装 + /transform 路由与菜单 SQL"
```

---

### 任务 6：前端 — 列表页 TransformListView.vue

**文件：**
- 修改：`frontend/src/views/TransformListView.vue`（替换任务 5 的占位内容）

参照 `ProcListView.vue` 的 el-table + 查询区组织方式。

- [ ] **步骤 1：实现页面**

核心结构（script setup + el-table，徽标色映射规格 §7.1）：

```vue
<template>
  <div class="page">
    <div class="toolbar">
      <el-upload :show-file-list="false" accept=".py" :before-upload="beforeUpload"
                 :http-request="doUpload">
        <el-button type="primary">上传脚本</el-button>
      </el-upload>
      <el-button @click="fetchData" style="margin-left:12px">刷新</el-button>
    </div>
    <el-table :data="list" v-loading="loading" border>
      <el-table-column prop="name" label="脚本名" min-width="260" />
      <el-table-column label="状态" width="140">
        <template #default="{ row }">
          <el-tag :type="tagType(row)" effect="dark">{{ tagText(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="oldTable" label="旧表" min-width="200" />
      <el-table-column prop="newTable" label="新表" min-width="200" />
      <el-table-column prop="totalEdits" label="转换点" width="90" />
      <el-table-column label="残留" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.residueCount > 0" type="warning" effect="plain">
            {{ row.residueCount }}
          </el-tag>
          <span v-else>0</span>
        </template>
      </el-table-column>
      <el-table-column prop="pyCompile" label="py_compile" width="110" />
      <el-table-column prop="convertedAt" label="转换时间" width="170" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="row.status === 'PENDING'"
                     @click="$router.push(`/transform/${encodeURIComponent(row.name)}`)">
            查看对比
          </el-button>
          <el-button link :disabled="row.status === 'PENDING'"
                     @click="$el ? null : null; window.open(downloadUrl(row.name, 'converted'))">
            下载
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listScripts, uploadScript, downloadUrl } from '@/api/transform'

const list = ref([])
const loading = ref(false)

const tagType = (row) => ({
  PENDING: 'info', CONVERTED: 'success',
  FAILED: 'danger', STALE: 'warning',
}[row.status] || 'info')
const tagText = (row) => ({
  PENDING: '待转换', CONVERTED: '已转换',
  FAILED: '转换失败', STALE: '源已更新，待重转',
}[row.status] || row.status)

async function fetchData() {
  loading.value = true
  try {
    const { data } = await listScripts()
    list.value = data.data || []
  } finally {
    loading.value = false
  }
}

function beforeUpload(file) {
  if (!file.name.endsWith('.py')) {
    ElMessage.error('仅支持 .py 脚本')
    return false
  }
  return true
}

async function doUpload({ file }) {
  // 同名替换确认（规格 §7.1）
  const exists = list.value.find((r) => r.name === file.name)
  if (exists && exists.status !== 'PENDING') {
    try {
      await ElMessageBox.confirm(
        `已存在同名脚本 ${file.name}，替换后需重新离线转换。继续？`, '替换确认',
        { type: 'warning' })
    } catch { return }
  }
  const { data } = await uploadScript(file)
  ElMessage.success(data.data)
  fetchData()
}

onMounted(fetchData)
</script>
```

下载按钮的实现以项目内现有下载手法为准（若 request.js 支持 blob 下载则用 blob；否则用 `window.open(downloadUrl(...))`，注意鉴权 token 方式——若接口需要 Authorization 头则 window.open 不可行，必须走 axios blob；**先看现有页面有没有下载先例，照抄**）。

- [ ] **步骤 2：手动走查**

`npm run dev` 启动（后端 `mvn spring-boot:run` 或 startup.bat），走查清单：
1. 菜单出现「脚本转换」
2. 上传 tmp1030/dwa_c_agt_deposit_acct_inf.py → 出现 PENDING 行
3. 再次上传同名 → 弹「替换确认」
4. 上传 evil.sh 被拒（accept 拦截 + 后端 400 双保险）
5. 空目录、仅 input 两种状态下页面不报错

- [ ] **步骤 3：Commit**

```bash
git add frontend/src/views/TransformListView.vue
git commit -m "feat(transform-front): 转换脚本列表页（状态徽标+上传替换+下载）"
```

---

### 任务 7：前端 — 对比详情页 TransformDetailView.vue

**文件：**
- 修改：`frontend/src/views/TransformDetailView.vue`（替换占位内容）

数据源：`getReport(name)` → edits/residues/config；`getScriptFile(name, 'source' | 'converted')` → 全文行数组。定位契约（规格 §5.1/§7.2）：转换前视图用 `edit.line`，转换后视图用 `edit.new_line`；残留行在**转换后**视图用 `residues[].line`。

- [ ] **步骤 1：实现页面**

```vue
<template>
  <div class="page">
    <!-- 概要条 -->
    <el-alert v-if="report && report.residues.length" type="warning" show-icon
              :closable="false" style="margin-bottom:12px"
              :title="`存在 ${report.residues.length} 处残留，请人工排查（黄色高亮行）`" />
    <el-descriptions v-if="report" :column="4" size="small" border style="margin-bottom:12px">
      <el-descriptions-item label="映射">
        {{ `{{${report.config.old_db}}}.${report.config.old_table} → {{${report.config.new_db}}}.${report.config.new_table}` }}
      </el-descriptions-item>
      <el-descriptions-item label="转换点">{{ report.summary.total }}</el-descriptions-item>
      <el-descriptions-item label="残留">{{ report.summary.residues }}</el-descriptions-item>
      <el-descriptions-item label="py_compile">{{ report.py_compile }}</el-descriptions-item>
    </el-descriptions>

    <div class="layout">
      <!-- 左：转换点列表 -->
      <div class="edits-pane">
        <el-radio-group v-model="filterType" size="small" style="margin-bottom:8px">
          <el-radio-button value="">全部({{ edits.length }})</el-radio-button>
          <el-radio-button v-for="t in typeNames" :key="t.key" :value="t.key">
            {{ t.label }}({{ t.count }})
          </el-radio-button>
        </el-radio-group>
        <div v-for="(e, i) in shownEdits" :key="i" class="edit-item"
             :class="{ active: activeEdit === e }" @click="locate(e)">
          <el-tag size="small" :type="typeTag(e.type)">{{ typeLabel(e.type) }}</el-tag>
          <span class="ln">L{{ e.line }}</span>
          <span class="diff-text">{{ e.old }} → {{ e.new }}</span>
        </div>
      </div>
      <!-- 右：全文视图 -->
      <div class="code-pane">
        <el-tabs v-model="tab" @tab-change="onTabChange">
          <el-tab-pane label="转换前" name="source" />
          <el-tab-pane label="转换后" name="converted" />
        </el-tabs>
        <div class="code" ref="codeBox">
          <div v-for="(line, idx) in lines" :key="idx" class="code-line"
               :ref="'row' + (idx + 1)"
               :class="rowClass(idx + 1)">
            <span class="no">{{ idx + 1 }}</span>
            <span class="txt">{{ line || ' ' }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getReport, getScriptFile } from '@/api/transform'

const route = useRoute()
const name = decodeURIComponent(route.params.name)

const report = ref(null)
const sourceLines = ref([])
const convertedLines = ref([])
const tab = ref('source')
const filterType = ref('')
const activeEdit = ref(null)

const TYPE = {
  table_ref: { label: '表引用', tag: 'danger' },
  rename: { label: '列改名', tag: 'primary' },
  join_inject: { label: 'JOIN 注入', tag: 'warning' },
}
const typeLabel = (t) => TYPE[t]?.label || t
const typeTag = (t) => TYPE[t]?.tag || 'info'
const typeNames = computed(() => {
  const c = {}
  edits.value.forEach((e) => { c[e.type] = (c[e.type] || 0) + 1 })
  return Object.keys(TYPE).filter((k) => c[k]).map((k) => ({ key: k, label: TYPE[k].label, count: c[k] }))
})

const edits = computed(() => report.value?.edits || [])
const shownEdits = computed(() =>
  filterType.value ? edits.value.filter((e) => e.type === filterType.value) : edits.value)
const lines = computed(() => (tab.value === 'source' ? sourceLines : convertedLines).value)

// 行高亮：当前 Tab 视图下需要标记的行号集合
const markLines = computed(() => {
  const s = new Set()
  if (tab.value === 'source') edits.value.forEach((e) => s.add(e.line))
  else {
    edits.value.forEach((e) => e.new_line && s.add(e.new_line))
    report.value?.residues.forEach((r) => s.add(r.line)) // 残留行（转换后行号）
  }
  return s
})
const rowClass = (ln) => (markLines.value.has(ln) ? 'marked' : '')

// 定位：转换前用 e.line，转换后用 e.new_line（规格 §5.1 契约）
function locate(e) {
  activeEdit.value = e
  const target = tab.value === 'source' ? e.line : (e.new_line || e.line)
  const box = codeBox.value
  const row = box?.querySelector(`.code-line:nth-child(${target})`)
  if (row) {
    box.scrollTop = row.offsetTop - box.clientHeight / 3
    row.classList.add('flash')
    setTimeout(() => row.classList.remove('flash'), 1600)
  }
}
function onTabChange() { if (activeEdit.value) locate(activeEdit.value) }

onMounted(async () => {
  const [{ data: rep }, { data: s }, { data: c }] = await Promise.all([
    getReport(name), getScriptFile(name, 'source'), getScriptFile(name, 'converted'),
  ])
  report.value = rep.data
  sourceLines.value = s.data.split('\n')
  convertedLines.value = c.data.split('\n')
})
</script>

<style scoped>
.layout { display: flex; gap: 12px; height: calc(100vh - 260px); }
.edits-pane { width: 420px; overflow: auto; border: 1px solid #ebeef5; padding: 8px; }
.edit-item { padding: 6px 8px; cursor: pointer; border-radius: 4px; font-size: 12px; }
.edit-item:hover, .edit-item.active { background: #ecf5ff; }
.edit-item .ln { color: #909399; margin: 0 8px; }
.code-pane { flex: 1; border: 1px solid #ebeef5; overflow: hidden; display: flex; flex-direction: column; }
.code { flex: 1; overflow: auto; font-family: Consolas, Monaco, monospace; font-size: 12px; background: #fafafa; }
.code-line { display: flex; white-space: pre; line-height: 20px; }
.code-line .no { width: 56px; text-align: right; padding-right: 10px; color: #c0c4cc; user-select: none; flex: none; }
.code-line.marked { background: #d9ecff; }
.code-line.marked.residue, .code-line.residue-mark { background: #faecd8; }
.code-line.flash { outline: 2px solid #409eff; }
</style>
```

补充两处实现时必须处理的点：
1. **残留行黄标**：rowClass 里区分——转换后视图中 `residues[].line` 行加 `residue-mark`（黄），edits 行加 `marked`（蓝）；上面 rowClass 简化版需拆成两个 Set（editLines / residueLines）分别着色。
2. `{{` 花括号在模板中的转义：映射快照文案含 `{FDM}.F_X`，直接写在插值里会被 Vue 模板解析——用 JS 计算字符串（如 `mapText` computed）再插值，不要在模板里拼 `{{`。

- [ ] **步骤 2：手动走查（种子数据 = tmp1030 两脚本）**

先造数据：项目根执行

```bash
python transform_batch.py --input tmp1030 --output transforms/output --config transform_config.json
```

（tmp1030 下的两个下游脚本被批量转换；把产出目录对应到 `transform.base-dir` 指向的位置——开发环境把 `application.yml` 的 `transform.base-dir` 临时指向 `./transforms`，或将产物挪到 `transforms/output/` 下。）

走查清单：
1. 列表页两脚本均「已转换」，转换点 3 / 25，残留 0
2. 详情页概要条显示 `FDM.F_PT_ECIF_PUB_CUST_INF → ODM.O_ECIF_M_CI_ORG`
3. 左列表筛选「列改名」只剩 rename 项；点击任一项右侧滚动定位且蓝高亮
4. 切「转换后」Tab，定位仍准确（JOIN 注入场景需另造含 core_cust_no 的脚本验证 new_line 移行）
5. 残留场景：临时把某脚本 report.json 的 residues 手工加一条 {line: 10}，确认黄色高亮与顶部告警条出现（验证完删除）
6. 转换前/转换后行数差异符合预期（注入 +2）

- [ ] **步骤 3：Commit**

```bash
git add frontend/src/views/TransformDetailView.vue
git commit -m "feat(transform-front): 转换点对比详情页（类型筛选+全文定位+残留高亮）"
```

---

### 任务 8：端到端验收与构建

**文件：** 无新文件；验证 + 构建 + 部署物确认。

- [ ] **步骤 1：全量回归**

```bash
python test_report_json.py; python test_transform_batch.py; python test_transform_mini.py; python test_transform_mini2.py
mvn test -q
```
预期：全部 PASS。

- [ ] **步骤 2：前端构建**

```bash
cd frontend; npm run build
```
预期：产物进 `src/main/resources/static/`（确认 vite.config.js outDir 配置）。

- [ ] **步骤 3：整包验证**

`mvn package -DskipTests` 后以 jar 启动，走查规格 §9 验收标准：**不读代码，仅靠详情页逐处确认 25 + 3 处转换点正确性**；上传替换一个脚本 → 状态变「源已更新，待重转」→ 服务器重跑 transform_batch → 状态恢复。

- [ ] **步骤 4：Commit（如有修正）**

```bash
git add -A
git commit -m "chore(transform): 端到端验收修正与构建产物"
```

---

## 自检记录

1. **规格覆盖度**：§3 数据流（任务 2/3/4）、§4 目录约定（任务 2）、§5.1 report.json+行号（任务 1）、§5.2 batch（任务 2）、§6 五接口+状态机+安全（任务 3/4）、§7 前端两页+菜单（任务 5/6/7）、§8 错误处理（任务 3 状态机 FAILED/STALE、任务 2 error 兜底、任务 4 上传校验）、§9 测试（各任务步骤 + 任务 8）、§10 部署（任务 8）、§11 非目标未越界（无网页触发转换、无映射编辑、无新表、无 Monaco）。
2. **占位符扫描**：已排除「待定/TODO/类似任务 N」；两处「先读现有文件对齐」是对既有可工作代码的明确参照指令（Result 错误码、ProcDetail 路由守卫、下载鉴权手法），非占位符。
3. **类型一致性**：`convert_one(src, out, config_path)` 返回 report dict（任务 2 定义，batch 消费）；VO 字段 name/status/totalEdits/residueCount/pyCompile/convertedAt/errorMsg/oldTable/newTable 与前端 tagType/tagText 及表格列一一对应；report.json 键名（snake_case：`new_line`/`converted_at`/`py_compile`）与后端 Map 读取、前端 `e.new_line` 读取一致。
