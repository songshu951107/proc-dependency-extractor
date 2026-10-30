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
        # convert_one 产物文件名基于 converted 前缀，按目录约定统一挪名：
        # converted_report.md→report.md、converted_diff.txt→diff.txt、
        # converted_diff.html→diff.html、converted.report.json→report.json
        for old_name, new_name in (('converted_report.md', 'report.md'),
                                   ('converted_diff.txt', 'diff.txt'),
                                   ('converted_diff.html', 'diff.html'),
                                   ('converted.report.json', 'report.json')):
            f = d / old_name
            if f.exists():
                f.replace(d / new_name)
        if rep.get('py_compile') == 'PASS':
            ok += 1
            print('  [OK] %s 处转换，残留 %s'
                  % (rep['summary']['total'], rep['summary']['residues']))
        else:
            fail += 1
            print('  [FAIL] %s' % rep.get('error', ''), file=sys.stderr)
    print('批量完成：成功 %d，失败 %d，共 %d' % (ok, fail, len(scripts)))


if __name__ == '__main__':
    main()
