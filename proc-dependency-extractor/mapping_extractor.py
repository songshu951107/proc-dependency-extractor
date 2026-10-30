# -*- coding: utf-8 -*-
"""
mapping_extractor.py — 映射提取层（transform-script 工具链 Step 2）

职责：解析加工逻辑脚本（PY 三引号 SQL，INSERT INTO ... (列清单) SELECT ... FROM 源表 结构），
产出「目标表列 ← 源表列」的结构化映射 JSON（mapping_extracted.json，给转换层吃）
与人类可读报告（mapping_report.md，给人工确认关口看）。

模式：
  1) 单脚本提取：--script <加工脚本.py>
     产出该脚本目标表的「列 ← 源表列」映射
  2) 新旧表配对（表替换场景）：--old-script <旧表加工脚本.py> --new-script <新表加工脚本.py>
     两脚本共享同一 T 层源表时，经共同源列推导出「旧表列 → 新表列」替换映射；
     旧表列在新表中无对应时标记 need_join（由转换层注入 LEFT JOIN 补齐）。

确定性工具：零大模型依赖，同一输入永远产出同一输出。
"""

import argparse
import json
import re
import sys
from pathlib import Path

# ---------------------------------------------------------------- SQL 块提取

# PY 源码里的三引号 SQL 块：varname="""..."""（允许空白）
SQL_BLOCK_RE = re.compile(r'(\w+)\s*=\s*"""(.*?)"""', re.S)

# INSERT INTO <db.>target [(col, ...)] SELECT ... 形态（列清单括号可缺省：
# O 层 SETP2 常见 INSERT INTO VT SELECT T01.col ...，目标列名即 SELECT 表达式列名）
INSERT_INTO_RE = re.compile(
    r'INSERT\s+INTO\s+(?:\{\w+\}\.)?(\w+)\s*(\((.*?)\))?\s*SELECT\b', re.I | re.S)

# FROM/JOIN 源表：{DB}.TBL_{ETL_TX_DT} alias / {DB}.TBL alias
# 表名允许 _{ETL_TX_DT} 等占位符尾巴（\w 匹配不了 `{`，须显式匹配）
FROM_CLAUSE_RE = re.compile(
    r'(?:FROM|JOIN)\s+(\{\w+\}|\w+)\.([A-Za-z]\w*(?:_\{\w+\})?)\s+'
    r'(?:AS\s+)?([A-Za-z]\w*)', re.I)

# _{ETL_TX_DT} 每日快照尾巴
ETL_DT_SUFFIX_RE = re.compile(r'_\{ETL_TX_DT\}\s*$')

# SELECT 列表达式：alias.col / 常量 'x' / DATE '{VAR}' 等（按顶层逗号切分后再识别）
SIMPLE_COL_RE = re.compile(r'^([A-Za-z]\w*)\.(\w+)$')
CONST_STR_RE = re.compile(r"^'(.*?)'$")


def extract_sql_blocks(py_text):
    """提取 PY 中所有三引号 SQL 块 [(varname, sql), ...]"""
    return [(m.group(1), m.group(2)) for m in SQL_BLOCK_RE.finditer(py_text)]


def split_top_level_commas(s):
    """按顶层逗号切分（括号深度为 0 处的逗号；引号内/`--` 行注释内逗号不切）"""
    parts, depth, cur = [], 0, []
    i, n, in_str = 0, len(s), False
    while i < n:
        ch = s[i]
        if in_str:
            cur.append(ch)
            if ch == "'":
                in_str = False
            i += 1
            continue
        if ch == "'":
            in_str = True
            cur.append(ch)
            i += 1
            continue
        if ch == '-' and i + 1 < n and s[i + 1] == '-':
            # 行注释整段并入当前切片（保留原文，strip_comment 统一去除）
            j = s.find('\n', i)
            if j == -1:
                j = n
            cur.append(s[i:j])
            i = j
            continue
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
        elif ch == ',' and depth == 0:
            parts.append(''.join(cur))
            cur = []
            i += 1
            continue
        cur.append(ch)
        i += 1
    if ''.join(cur).strip():
        parts.append(''.join(cur))
    return parts


def strip_comment(expr):
    """去掉行内 -- 注释与首尾空白（注释里的内容不参与解析）"""
    return re.sub(r'--[^\n]*', '', expr).strip()


def parse_insert_block(sql):
    """解析一个 INSERT INTO ... (列) SELECT ... FROM ... 块。

    返回 dict：{target, columns[(target_col, comment)], select_exprs,
               sources[(db_expr, table, alias)], source_map{alias: table}}
    无法解析返回 None。
    """
    m = INSERT_INTO_RE.search(sql)
    if not m:
        return None
    target = m.group(1)
    col_body = m.group(3)  # 可能为 None（无列清单 INSERT INTO VT SELECT ...）
    # SELECT 起点到 FROM 顶层（第一个非子查询的 FROM）
    select_start = m.end()
    # 找 FROM（不含括号内）：粗略取 SELECT ... FROM 第一段
    seg = sql[select_start:]
    # FROM 关键字（括号深度 0）
    frm = None
    depth = 0
    in_str = False
    for i, ch in enumerate(seg):
        if in_str:
            if ch == "'":
                in_str = False
            continue
        if ch == "'":
            in_str = True
        elif ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
        elif depth == 0 and re.match(r'FROM\b', seg[i:], re.I):
            frm = i
            break
    if frm is None:
        return None
    select_body = seg[:frm]

    # SELECT 表达式顶层切分
    exprs = [strip_comment(e) for e in split_top_level_commas(select_body)]
    exprs = [e for e in exprs if e]

    if col_body:
        # INSERT 列清单切分
        cols = []
        for c in split_top_level_commas(col_body):
            c = strip_comment(c)
            if c:
                cols.append(c)
        if len(cols) != len(exprs):
            # 位置配对失败：报告冲突，交人工判断
            return {'target': target, 'error':
                    f'列数不匹配: INSERT {len(cols)} 列 vs SELECT {len(exprs)} 表达式'}
    else:
        # 无列清单：目标列名取自 SELECT 表达式本身（alias.col → col；带 AS 别名取别名）
        cols = []
        for e in exprs:
            cm = SIMPLE_COL_RE.match(e)
            if cm:
                cols.append(cm.group(2))
                continue
            am = re.match(r'.*\bas\s+([A-Za-z]\w*)\s*$', e, re.I)
            cols.append(am.group(1) if am else None)

    # FROM 段：主表 + JOIN 源表（拉链条件等细节不进入映射，只取表名与别名）
    from_seg = seg[frm:frm + 4000]
    from_seg = from_seg.split(';', 1)[0]
    sources, source_map = [], {}
    for fm in FROM_CLAUSE_RE.finditer(from_seg):
        db_expr, table, alias = fm.group(1), fm.group(2), fm.group(3)
        base_table = ETL_DT_SUFFIX_RE.sub('', table)
        sources.append({'db': db_expr, 'table': table, 'base_table': base_table,
                        'alias': alias})
        source_map[alias] = {'db': db_expr, 'table': table,
                             'base_table': base_table}

    rows = []
    for col, expr in zip(cols, exprs):
        cm = SIMPLE_COL_RE.match(expr)
        if cm:
            alias, scol = cm.group(1), cm.group(2)
            src = source_map.get(alias)
            rows.append({
                'target_col': col,
                'source_table': src['base_table'] if src else alias,
                'source_db': src['db'] if src else None,
                'source_col': scol,
                'source_alias': alias,
                'kind': 'column',
            })
        else:
            sm = CONST_STR_RE.match(expr)
            rows.append({
                'target_col': col,
                'source_table': None,
                'source_col': None,
                'source_alias': None,
                'kind': 'constant',
                'const_expr': expr,
            })

    return {'target': target, 'columns': rows, 'sources': sources,
            'source_map': source_map}


def scan_mapping_segments(sql, target_name=None):
    """块级扫描：找块内所有「SELECT 纯列段 + FROM 物理表」配对（INSERT 解析失败的降级路径，
    覆盖 O 层 SETP2 的子查询 EXCEPT ALL 形态——映射藏在 FROM ( SELECT T1.col FROM 源表 ) 内层）。

    排除规则：FROM 目标表自身（EXCEPT 闭链比对段）、FROM VT_/ST_ 临时表段；
    SELECT 段必须为纯 alias.col 形态（含常量的映射段不可靠，交人工）。
    """
    segs = []
    for fm in FROM_CLAUSE_RE.finditer(sql):
        db_expr, table, alias = fm.group(1), fm.group(2), fm.group(3)
        base = ETL_DT_SUFFIX_RE.sub('', table)
        if target_name and base.upper() == target_name.upper():
            continue
        if base.upper().startswith(('VT_', 'ST_')):
            continue
        # 向前找最近一个 SELECT 关键字
        m2 = None
        for m2 in re.finditer(r'\bSELECT\b', sql[:fm.start()], re.I):
            pass
        if m2 is None:
            continue
        expr_body = sql[m2.end():fm.start()]
        # 段内不得含 FROM/EXCEPT/JOIN 等（否则该 SELECT 不是本 FROM 的直接列清单）
        if re.search(r'\b(FROM|EXCEPT|JOIN|UNION|INSERT|WHERE)\b', expr_body, re.I):
            continue
        exprs = [strip_comment(e) for e in split_top_level_commas(expr_body)]
        exprs = [e for e in exprs if e]
        if not exprs:
            continue
        if any(not SIMPLE_COL_RE.match(e) for e in exprs):
            continue  # 含常量/表达式列的段不作映射候选
        # FROM 段向后截取（到 ; 或下一个 DML 关键字），解析主表与 JOIN 表
        tail = sql[fm.start():]
        tail = re.split(r';|\bINSERT\b|\bDELETE\b|\bUPDATE\b', tail, 1, flags=re.I)[0]
        tail = tail[:4000]
        srcs, src_map = [], {}
        for f2 in FROM_CLAUSE_RE.finditer(tail):
            db2, tbl2, al2 = f2.group(1), f2.group(2), f2.group(3)
            b2 = ETL_DT_SUFFIX_RE.sub('', tbl2)
            srcs.append({'db': db2, 'table': tbl2, 'base_table': b2, 'alias': al2})
            src_map[al2] = {'db': db2, 'table': tbl2, 'base_table': b2}
        rows = []
        for e in exprs:
            cm = SIMPLE_COL_RE.match(e)
            a, c = cm.group(1), cm.group(2)
            src = src_map.get(a)
            rows.append({'target_col': c,
                         'source_table': src['base_table'] if src else a,
                         'source_db': src['db'] if src else None,
                         'source_col': c, 'source_alias': a, 'kind': 'column'})
        segs.append({'columns': rows, 'sources': srcs, 'source_map': src_map,
                     'ncols': len(rows)})
    return segs


def extract_mapping_from_script(path):
    """解析单个加工脚本：优先取 mapping 块（INSERT INTO ST），否则取第一个可解析 INSERT。"""
    text = Path(path).read_text(encoding='utf-8')
    blocks = extract_sql_blocks(text)
    header = {}
    hm = re.search(r'#脚本名称：(.*)', text)
    if hm:
        header['script'] = hm.group(1).strip()
    tm = re.search(r'#更新对象：(.*)', text)
    if tm:
        header['target'] = tm.group(1).strip()
    wm = re.search(r'#更新方式：(.*)', text)
    if wm:
        header['update_type'] = wm.group(1).strip()

    parsed_all, chosen = [], None
    target_name = header.get('target', '')
    if '.' in target_name:
        target_name = target_name.split('.', 1)[1]
    for name, sql in blocks:
        if 'INSERT' not in sql.upper():
            continue
        p = parse_insert_block(sql)
        ok_insert = False
        if p and not p.get('error') and p.get('sources'):
            main_src = p['sources'][0]['base_table'].upper()
            ok_insert = not main_src.startswith(('VT_', 'ST_')) and \
                main_src != target_name.upper()
        if ok_insert:
            # 可信度检查：SELECT 表达式别名大量未对应到物理表（外层 T01 与
            # 内层 T1 断链的子查询形态）时，source_table 全是裸别名兜底，不可信
            col_rows = [r for r in p['columns'] if r['kind'] == 'column']
            if col_rows:
                n_fallback = sum(1 for r in col_rows
                                 if r['source_table'] == r['source_alias'])
                ok_insert = n_fallback <= len(col_rows) // 2
        if ok_insert:
            parsed_all.append({'block': name, **p})
            if 'mapping' in name.lower():
                chosen = parsed_all[-1]
        else:
            # 降级：块级扫描（O 层 SETP2 子查询 EXCEPT ALL 形态）
            segs = scan_mapping_segments(sql, target_name or None)
            if segs:
                best = max(segs, key=lambda s: s['ncols'])
                parsed_all.append({'block': name + '(scan)',
                                   'target': target_name or '', **best})
                if 'mapping' in name.lower():
                    chosen = parsed_all[-1]
    if chosen is None and parsed_all:
        chosen = parsed_all[0]
    return {'file': str(path), 'header': header, 'chosen': chosen,
            'blocks_parsed': parsed_all}


# ---------------------------------------------------------------- F→O 配对

def build_replacement_mapping(old, new):
    """经共同源表推导 旧表列 → 新表列 替换映射。

    规则：
      - 旧列来自共同源表 col C：映射到「新表中同源同列 C 的列」；
        新表若该源列在多列出现（少见）取第一个并告警
      - 旧列来自非共同源表（如 O_ECIF_M_CI_CUSTOMER）：need_join=true，
        带 join_table/join_on/join_filter/join_source_col，转换层注入 LEFT JOIN
      - 旧列为常量（del_flag=''）而新表有同语义列：按 config 的 special 覆盖
        （extractor 只标注 kind=constant，具体映射进 transform_config.json）
      - 拉链控制列 start_dt/end_dt：同名直映（两边拉链逻辑同构）
    """
    oc, nc = old['chosen'], new['chosen']
    if not oc or not nc:
        return None

    # 目标表名优先取脚本头部 #更新对象（bsqlmapping 的 INSERT 目标是 ST 临时表，非真身）
    def hdr_target(side):
        t = side['header'].get('target', '') or (side['chosen'] or {}).get('target', '')
        return t.split('.', 1)[-1] if '.' in t else t

    old_target, new_target = hdr_target(old), hdr_target(new)

    # 新表：源表列 → 新列（含多源归属）
    new_by_src = {}
    for row in nc['columns']:
        if row['kind'] == 'column':
            key = (row['source_table'], row['source_col'].lower())
            new_by_src.setdefault(key, []).append(row['target_col'])

    mapping, warnings = [], []
    for row in oc['columns']:
        fcol = row['target_col']
        if row['kind'] == 'column':
            key = (row['source_table'], row['source_col'].lower())
            hits = new_by_src.get(key)
            if hits:
                if len(hits) > 1:
                    warnings.append(
                        f'{fcol}: 新表 {new_target} 中源列 {row["source_table"]}.{row["source_col"]} '
                        f'对应多列 {hits}，取第一列 {hits[0]}')
                mapping.append({
                    'old_col': fcol, 'new_col': hits[0],
                    'via': f'{row["source_table"]}.{row["source_col"]}',
                    'kind': 'rename',
                })
            else:
                # 非共同源表列 → JOIN 补齐
                src = row.get('source_table')
                mapping.append({
                    'old_col': fcol, 'new_col': row['source_col'],
                    'via': f'{row["source_table"]}.{row["source_col"]}',
                    'kind': 'need_join',
                    'join_source_table': src,
                    'join_source_col': row['source_col'],
                })
                warnings.append(
                    f'{fcol}: 源列来自 {src}（非共同源表），新表 {new_target} 无对应列 → 需 LEFT JOIN {src} 补齐')
        else:
            mapping.append({
                'old_col': fcol, 'new_col': None, 'via': row.get('const_expr', ''),
                'kind': 'constant',
            })
            warnings.append(
                f'{fcol}: 旧表加工为常量表达式（{row.get("const_expr", "")}），'
                f'需人工在 transform_config.json 的 special_columns 中确认映射（如 del_flag→del_ind）')

    # 新表独有列（替换后下游可用的新能力，仅提示）
    old_keys = {(r['source_table'], r['source_col'].lower())
                for r in oc['columns'] if r['kind'] == 'column'}
    new_only = []
    for row in nc['columns']:
        if row['kind'] == 'column':
            key = (row['source_table'], row['source_col'].lower())
            if key not in old_keys:
                new_only.append({'new_col': row['target_col'],
                                 'via': f'{row["source_table"]}.{row["source_col"]}'})

    return {
        'old_table': old_target, 'new_table': new_target,
        'old_file': old['file'], 'new_file': new['file'],
        'old_header': old['header'], 'new_header': new['header'],
        'column_mapping': mapping,
        'new_table_extra_columns': new_only,
        'warnings': warnings,
    }


# ---------------------------------------------------------------- 报告

def render_report(rep):
    lines = []
    lines.append('# 映射提取报告（mapping_report）\n')
    lines.append(f'- 旧表：**{rep["old_table"]}**（{rep["old_file"]}，更新方式 {rep["old_header"].get("update_type", "?")}）')
    lines.append(f'- 新表：**{rep["new_table"]}**（{rep["new_file"]}，更新方式 {rep["new_header"].get("update_type", "?")}）')
    lines.append('')
    lines.append(f'## 列映射（{len(rep["column_mapping"])} 列）\n')
    lines.append('| # | 旧表列 | → 新表列 | 经由源列 | 类型 |')
    lines.append('|---|--------|----------|----------|------|')
    for i, m in enumerate(rep['column_mapping'], 1):
        new_col = m['new_col'] or '**需人工确认**'
        lines.append(f'| {i} | {m["old_col"]} | {new_col} | {m["via"]} | {m["kind"]} |')
    lines.append('')
    if rep['new_table_extra_columns']:
        lines.append('## 新表独有列（替换后下游可新增使用）\n')
        for c in rep['new_table_extra_columns']:
            lines.append(f'- {c["new_col"]} ← {c["via"]}')
        lines.append('')
    if rep['warnings']:
        lines.append('## 告警（人工确认重点）\n')
        for w in rep['warnings']:
            lines.append(f'- ⚠ {w}')
        lines.append('')
    return '\n'.join(lines) + '\n'


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description='表映射提取：加工脚本 → F→O 替换映射')
    ap.add_argument('--old-script', help='旧表加工脚本（下游要替换的表）')
    ap.add_argument('--new-script', help='新表加工脚本（替换为的表）')
    ap.add_argument('--script', help='单脚本提取模式（不做配对）')
    ap.add_argument('--out-json', default='mapping_extracted.json')
    ap.add_argument('--out-md', default='mapping_report.md')
    args = ap.parse_args()

    if args.old_script and args.new_script:
        old = extract_mapping_from_script(args.old_script)
        new = extract_mapping_from_script(args.new_script)
        rep = build_replacement_mapping(old, new)
        if rep is None:
            print('配对失败：两个脚本均未解析出 INSERT mapping 块', file=sys.stderr)
            sys.exit(1)
        payload = {'mode': 'pair', 'replacement': rep}
    elif args.script:
        one = extract_mapping_from_script(args.script)
        payload = {'mode': 'single', 'extract': one}
        rep = one
    else:
        ap.error('需要 --old-script + --new-script（配对模式）或 --script（单脚本模式）')

    Path(args.out_json).write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')

    md = (render_report(rep) if args.old_script and args.new_script
          else render_single_report(one))
    Path(args.out_md).write_text(md, encoding='utf-8')

    print(f'[OK] 映射提取完成')
    print(f'  - 机器输入: {args.out_json}')
    print(f'  - 人工报告: {args.out_md}')
    if args.old_script and args.new_script:
        n_rename = sum(1 for m in rep['column_mapping'] if m['kind'] == 'rename')
        n_join = sum(1 for m in rep['column_mapping'] if m['kind'] == 'need_join')
        n_const = sum(1 for m in rep['column_mapping'] if m['kind'] == 'constant')
        print(f'  - 列映射: rename={n_rename} need_join={n_join} constant(需人工)={n_const}')


def render_single_report(one):
    lines = ['# 单脚本映射提取报告\n']
    ch = one.get('chosen')
    if not ch:
        return '未解析出 INSERT mapping 块\n'
    lines.append(f'- 脚本：{one["file"]}')
    lines.append(f'- 目标表：**{ch["target"]}**')
    lines.append(f'- 源表：{", ".join(s["db"] + "." + s["base_table"] + " " + s["alias"] for s in ch["sources"])}')
    lines.append('')
    lines.append('| # | 目标列 | 源表.源列 | 类型 |')
    lines.append('|---|--------|-----------|------|')
    for i, r in enumerate(ch['columns'], 1):
        if r['kind'] == 'column':
            lines.append(f'| {i} | {r["target_col"]} | {r["source_table"]}.{r["source_col"]} | {r["kind"]} |')
        else:
            lines.append(f'| {i} | {r["target_col"]} | (常量) {r.get("const_expr", "")} | {r["kind"]} |')
    return '\n'.join(lines) + '\n'


if __name__ == '__main__':
    main()
