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
from datetime import datetime
from pathlib import Path

from transform_table_mapping import convert_one


def main():
    ap = argparse.ArgumentParser(description='批量转换 input/ 下全部 .py 脚本')
    ap.add_argument('--input', required=True)
    ap.add_argument('--output', required=True)
    ap.add_argument('--config', default='transform_config.json')
    ap.add_argument('--blacklist', default='dwa_f,dwa_o',
                    help='文件名主干黑名单前缀（逗号分隔，不区分大小写），'
                         '命中则跳过不转换——F/O 层自身加工脚本无需转换')
    args = ap.parse_args()

    blacklist = [p.strip().lower() for p in args.blacklist.split(',') if p.strip()]
    in_dir, out_dir = Path(args.input), Path(args.output)
    scripts = sorted(in_dir.glob('*.py'))
    if not scripts:
        print('input 目录无 .py 脚本：%s' % in_dir)
        return
    ok = fail = skipped = 0
    # 引擎异常兜底 report 用的映射快照，与正常路径 report 的 config 字段同构
    # （见 transform_table_mapping.build_report_json）；配置本身加载失败
    # （常见异常根因）时退化为 {}
    try:
        _tables = json.loads(
            Path(args.config).read_text(encoding='utf-8')).get('tables', {})
        _t = next(iter(_tables))
        cfg_snapshot = {'old_db': _tables[_t].get('old_db', ''),
                        'old_table': _t,
                        'new_db': _tables[_t].get('new_db', ''),
                        'new_table': _tables[_t].get('new_table', '')}
    except Exception:
        cfg_snapshot = {}
    for src in scripts:
        if any(src.stem.lower().startswith(p) for p in blacklist):
            print('跳过黑名单 %s' % src.name)
            skipped += 1
            continue
        name = src.stem
        d = out_dir / name
        d.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, d / 'source.py')
        print('转换 %s ...' % src.name)
        try:
            rep = convert_one(src, d / 'converted.py', args.config)
        except Exception as e:  # 转换引擎异常也兜底成 report.json
            rep = {'script': src.name,
                   'converted_at': datetime.now().isoformat(timespec='seconds'),
                   'config': dict(cfg_snapshot),
                   'py_compile': 'FAIL', 'error': str(e),
                   'summary': {'total': 0, 'residues': 0},
                   'edits': [], 'residues': []}
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
    print('批量完成：成功 %d，失败 %d，跳过 %d，共 %d'
          % (ok, fail, skipped, len(scripts)))


if __name__ == '__main__':
    main()
