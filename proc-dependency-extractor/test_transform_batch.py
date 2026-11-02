# -*- coding: utf-8 -*-
"""transform_batch 端到端：产物齐全 / 失败脚本 report.json 兜底 / 幂等。"""
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).parent
TMP = ROOT / '.tmp_batch_test'


def _batch(extra=()):
    r = subprocess.run(
        [sys.executable, str(ROOT / 'transform_batch.py'),
         '--input', str(TMP / 'input'), '--output', str(TMP / 'output'),
         '--config', str(ROOT / 'transform_config.json'), *extra],
        capture_output=True, text=True, cwd=ROOT)
    return r


def test_batch_end_to_end():
    shutil.rmtree(TMP, ignore_errors=True)
    (TMP / 'input').mkdir(parents=True)
    shutil.copy(ROOT / 'tmp1030' / 'dwa_c_agt_deposit_acct_inf.py',
                TMP / 'input' / 'dwa_c_agt_deposit_acct_inf.py')
    # 语法错误脚本：py_compile FAIL 但必须产出 report.json
    # （简报步骤 5 预案：原样三引号闭合内容是合法 Python 字符串，py_compile 会 PASS；
    #  改为未闭合三引号的确定性语法错误）
    (TMP / 'input' / 'broken.py').write_text(
        '#更新对象 X\nbsql="""\nSELECT FROM WHERE\n', encoding='utf-8')

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


def test_batch_blacklist_skip():
    """黑名单前缀（dwa_f/dwa_o，不区分大小写）跳过不转换，不产产物目录。"""
    shutil.rmtree(TMP, ignore_errors=True)
    (TMP / 'input').mkdir(parents=True)
    shutil.copy(ROOT / 'tmp1030' / 'dwa_c_agt_deposit_acct_inf.py',
                TMP / 'input' / 'dwa_c_agt_deposit_acct_inf.py')
    (TMP / 'input' / 'dwa_f_pt_ecif_pub_cust_inf.py').write_text(
        '#更新对象 FDM.T\nbsql="""SELECT 1"""\n', encoding='utf-8')
    (TMP / 'input' / 'DWA_O_DEMO.py').write_text('#x\n', encoding='utf-8')

    r = _batch()
    assert r.returncode == 0, r.stderr
    assert (TMP / 'output' / 'dwa_c_agt_deposit_acct_inf' / 'report.json').exists()
    assert not (TMP / 'output' / 'dwa_f_pt_ecif_pub_cust_inf').exists()
    assert not (TMP / 'output' / 'DWA_O_DEMO').exists()
    assert '跳过 2' in r.stdout
    shutil.rmtree(TMP, ignore_errors=True)


def test_batch_only_filter():
    """--only 指定单脚本：只转换它，其余不动；指定不存在的脚本退出码 1。"""
    shutil.rmtree(TMP, ignore_errors=True)
    (TMP / 'input').mkdir(parents=True)
    shutil.copy(ROOT / 'tmp1030' / 'dwa_c_agt_deposit_acct_inf.py',
                TMP / 'input' / 'dwa_c_agt_deposit_acct_inf.py')
    (TMP / 'input' / 'other.py').write_text(
        '#更新对象 X\nbsql="""SELECT 1"""\n', encoding='utf-8')

    r = _batch(('--only', 'dwa_c_agt_deposit_acct_inf'))
    assert r.returncode == 0, r.stderr
    assert (TMP / 'output' / 'dwa_c_agt_deposit_acct_inf' / 'report.json').exists()
    assert not (TMP / 'output' / 'other').exists()
    assert '共 1' in r.stdout

    r2 = _batch(('--only', 'ghost'))
    assert r2.returncode == 1 and '未找到指定脚本' in r2.stderr
    shutil.rmtree(TMP, ignore_errors=True)


if __name__ == '__main__':
    test_batch_end_to_end()
    test_batch_idempotent()
    test_batch_blacklist_skip()
    test_batch_only_filter()
    print('ALL PASS')
