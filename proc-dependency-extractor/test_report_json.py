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
    """真实脚本：3 处 edits 的 line 必须是全文行号（原文 F 表引用/旧列名所在行）。"""
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
    """含 core_cust_no 的脚本：JOIN 注入净增 4 行（首尾换行包裹 3 行 JOIN 子句），
    注入点行及其后 edits 的 new_line 随之位移，注入点之前 edit 行位不变。"""
    TMP.mkdir(exist_ok=True)
    sql = ('#更新对象 CDM.T_OUT\n'
           'bsql="""\n'
           'INSERT INTO {CDM}.T_OUT\n'
           'SELECT A.cust_no, A.core_cust_no, A.uscc_cd\n'
           '  FROM {FDM}.F_PT_ECIF_PUB_CUST_INF A\n'
           ' WHERE A.del_flag <> \'D\';\n'
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
    # 注入点之前的 rename（uscc_cd 所在 SELECT 行 L4 位于 WHERE 注入点之前，
    # 行位不变；new_line 行内容必须是新列名——契约断言）
    rn = [e for e in rep['edits'] if e['type'] == 'rename'
          and 'uscc' in e['old'].lower()]
    assert rn and rn[0]['new_line'] == rn[0]['line'], rn[0]
    assert 'busi_lic_no' in conv_lines[rn[0]['new_line'] - 1], rn[0]
    # 注入点所在 WHERE 行（原文 L6）上的 rename（del_flag→del_ind）：
    # 随注入下移 4 行，new_line 行内容必须是新列名
    dl = [e for e in rep['edits'] if e['type'] == 'rename'
          and 'del_flag' in e['old'].lower()]
    assert dl and dl[0]['new_line'] == dl[0]['line'] + 4, dl[0]
    assert 'del_ind' in conv_lines[dl[0]['new_line'] - 1], dl[0]
    shutil.rmtree(TMP, ignore_errors=True)


if __name__ == '__main__':
    test_line_numbers_full_text()
    test_join_inject_new_line()
    print('ALL PASS')
