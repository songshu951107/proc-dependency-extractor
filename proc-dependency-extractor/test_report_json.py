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


def test_inject_at_stmt_end_same_line_notes():
    """C1 形态1：别名定义在子查询内 → _find_inject_pos 括号深度变负，
    注入点落到语句尾 ; 前（行尾）。同一行上位于注入点之前的 note
    （WHERE 行内的 rename / subquery_prop）不得吃注入位移：
    每个 edit 的 old 必须出现在原文 line 行、new 必须出现在转换后
    new_line 行（契约断言）。"""
    TMP.mkdir(exist_ok=True)
    sql = ('#形态1：别名定义在子查询内，注入点落到语句尾\n'
           'bsql="""\n'
           'INSERT INTO {CDM}.T_OUT\n'
           'SELECT X.cust_no, X.uscc_cd\n'
           '  FROM (SELECT S.cust_no, S.core_cust_no, S.uscc_cd\n'
           '          FROM {FDM}.F_PT_ECIF_PUB_CUST_INF S) X\n'
           ' WHERE X.cust_no = \'1\' AND S.del_flag <> \'D\';\n'
           '"""\n')
    src = TMP / 'f1.py'
    out = TMP / 'f1_out.py'
    src.write_text(sql, encoding='utf-8')
    rep = _run(src, out)
    orig_lines = src.read_text(encoding='utf-8').splitlines()
    conv_lines = out.read_text(encoding='utf-8').splitlines()
    inj = [e for e in rep['edits'] if e['type'] == 'join_inject']
    assert len(inj) == 1, rep['edits']
    # 注入的 JOIN 行内容必须精确出现在 new_line 行
    assert 'LEFT JOIN {ODM}.O_ECIF_M_CI_CUSTOMER FJ1' in conv_lines[inj[0]['new_line'] - 1]
    # 契约断言（join_inject 的 old='(无)' 无行定位语义，跳过）：
    # old 真的出现在原文 line 行、new 真的出现在转换后 new_line 行
    for e in rep['edits']:
        if e['type'] == 'join_inject':
            continue
        assert e['line'] <= len(orig_lines), e
        assert e['old'] in orig_lines[e['line'] - 1], e
        assert e['new_line'] <= len(conv_lines), e
        assert e['new'] in conv_lines[e['new_line'] - 1], e
    # 形态1核心：WHERE 行（注入点之前的同行）上的 rename 不位移
    dl = [e for e in rep['edits'] if e['type'] == 'rename'
          and 'del_flag' in e['old'].lower()]
    assert dl and dl[0]['new_line'] == dl[0]['line'], dl[0]
    assert 'del_ind' in conv_lines[dl[0]['new_line'] - 1], dl[0]
    # WHERE 行上的 subquery_prop（X.cust_no → X.cust_id）同样不位移
    sp = [e for e in rep['edits'] if e['type'] == 'subquery_prop'
          and e['line'] == dl[0]['line']]
    assert sp and sp[0]['new_line'] == sp[0]['line'], sp[0]
    assert 'cust_id' in conv_lines[sp[0]['new_line'] - 1], sp[0]
    shutil.rmtree(TMP, ignore_errors=True)


def test_subquery_prop_line_after_injection():
    """C1 形态2：pass4 的 note line 记录于 pass3 注入后的文本——当传播位置
    在某注入点之后跨行时 line 被污染（≠转换前原文行号），且 _fix_line_numbers
    再叠加注入位移 → 双重计数（new_line 越界）。断言：line 行真的含 old、
    new_line 行真的含 new。"""
    TMP.mkdir(exist_ok=True)
    sql = ('#形态2：pass4 行号口径污染 + 位移双重计数\n'
           'bsql="""\n'
           'INSERT INTO {CDM}.T_OUT\n'
           'SELECT A.cust_no, A.core_cust_no\n'
           '  FROM (SELECT S.cust_no, S.core_cust_no\n'
           '          FROM {FDM}.F_PT_ECIF_PUB_CUST_INF S\n'
           '         WHERE S.del_flag <> \'D\') A\n'
           ' WHERE A.core_cust_no IS NOT NULL\n'
           '   AND A.cust_no <> \'X\';\n'
           '"""\n')
    src = TMP / 'f2.py'
    out = TMP / 'f2_out.py'
    src.write_text(sql, encoding='utf-8')
    rep = _run(src, out)
    orig_lines = src.read_text(encoding='utf-8').splitlines()
    conv_lines = out.read_text(encoding='utf-8').splitlines()
    inj = [e for e in rep['edits'] if e['type'] == 'join_inject']
    assert len(inj) == 1, rep['edits']
    assert 'LEFT JOIN {ODM}.O_ECIF_M_CI_CUSTOMER FJ1' in conv_lines[inj[0]['new_line'] - 1]
    # 注入点之前跨行的 subquery_prop（外层 WHERE/AND 引用子查询输出列）：
    # line 必须是转换前口径（old 出现在原文该行），new_line 不得双重计数；
    # 注入点之前的传播（SELECT 行）行位不变，之后的位移恰为注入净增 4 行
    sps = [e for e in rep['edits'] if e['type'] == 'subquery_prop']
    assert len(sps) == 4, rep['edits']  # SELECT 行 2 处 + WHERE/AND 行 2 处
    inj_line = inj[0]['line']
    for e in sps:
        assert e['line'] <= len(orig_lines), e
        assert e['old'] in orig_lines[e['line'] - 1], e
        assert e['new_line'] <= len(conv_lines), e
        assert e['new'] in conv_lines[e['new_line'] - 1], e
        if e['line'] < inj_line:
            assert e['new_line'] == e['line'], e
        else:
            assert e['new_line'] == e['line'] + 4, e
    # 外层 WHERE 行的 A.core_cust_no：原文行在注入点之后 → new_line = line + 4
    core = [e for e in sps if 'core_cust_no' in e['old'].lower()
            and e['line'] >= inj_line][0]
    assert 'CORE_NO' in conv_lines[core['new_line'] - 1], core
    shutil.rmtree(TMP, ignore_errors=True)


if __name__ == '__main__':
    test_line_numbers_full_text()
    test_join_inject_new_line()
    test_inject_at_stmt_end_same_line_notes()
    test_subquery_prop_line_after_injection()
    print('ALL PASS')
