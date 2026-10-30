# -*- coding: utf-8 -*-
"""
transform_table_mapping.py — 转换执行层（transform-script 工具链 Step 4）

职责：按 transform_config.json 的表映射配置，把下游脚本中对旧表（如 FDM.F_PT_ECIF_PUB_CUST_INF）
的引用改写为直连新表（如 ODM.O_ECIF_M_CI_ORG），包括：
  1) 表引用替换（{FDM}.TBL / 裸 TBL → {ODM}.NEW_TBL）
  2) 别名作用域内的列改名（cust_no → cust_id；special_columns 如 del_flag → del_ind）
  3) need_join 列补齐：alias.core_cust_no → FJn.CORE_NO，并在该别名 FROM/JOIN 子句末尾
     注入 LEFT JOIN {ODM}.O_ECIF_M_CI_CUSTOMER FJn ON ...（拉链条件随配置）

词法纪律（红线）：
  - 只处理 PY 三引号 SQL 块内部；PY 代码、注释、字符串字面量、{ETL_TX_DT} 等 format
    占位符一律不碰；
  - 每处替换前校验匹配偏移落在 code 区域掩码内；
  - 输出确定性：同一输入 + 同一配置 = 同一输出；对已转换脚本重跑 = 0 转换 0 差异（幂等）。

产出：转换后脚本 + conversion_report.md + conversion_diff.txt + conversion_diff.html
"""

import argparse
import difflib
import json
import re
import sys
from datetime import datetime
from pathlib import Path

# ---------------------------------------------------------------- 词法扫描

# PY 三引号 SQL 块
SQL_BLOCK_RE = re.compile(r'(\w+)\s*=\s*"""(.*?)"""', re.S)

LEX_STATES = ('code', 'comment', 'str', 'placeholder')


def lex_sql(sql):
    """把 SQL 文本切成段 [(kind, start, end)]，kind ∈ code|comment|str|placeholder。
    comment: `--` 到行尾；str: 单引号串；placeholder: {XXX} format 占位符。
    """
    segs = []
    n = len(sql)
    i = 0
    state = 'code'
    start = 0
    while i < n:
        ch = sql[i]
        if state == 'code':
            if ch == '-' and i + 1 < n and sql[i + 1] == '-':
                if i > start:
                    segs.append(('code', start, i))
                state = 'comment'
                start = i
                i += 1  # 第二个 - 也纳入注释
            elif ch == "'":
                if i > start:
                    segs.append(('code', start, i))
                state = 'str'
                start = i
            elif ch == '{':
                if i > start:
                    segs.append(('code', start, i))
                state = 'placeholder'
                start = i
            else:
                pass
        elif state == 'comment':
            if ch == '\n':
                segs.append(('comment', start, i))
                state = 'code'
                start = i
        elif state == 'str':
            if ch == "'":
                segs.append(('str', start, i + 1))
                state = 'code'
                start = i + 1
        elif state == 'placeholder':
            if ch == '}':
                segs.append(('placeholder', start, i + 1))
                state = 'code'
                start = i + 1
        i += 1
    if start < n:
        segs.append((state, start, n))
    return segs


def build_code_mask(sql, segs=None):
    """bytearray：可改区域=1，其余=0。

    可改区域 = code 段 + 库名前缀占位符（`{DB}.` 形态，表引用替换需连动改库）；
    不可改 = 注释 / 字符串 / 日期等参数占位符（{ETL_TX_DT} 等）。
    """
    if segs is None:
        segs = lex_sql(sql)
    mask = bytearray(len(sql))
    for kind, s, e in segs:
        if kind == 'code':
            for k in range(s, e):
                mask[k] = 1
        elif kind == 'placeholder' and e < len(sql) and sql[e] == '.':
            for k in range(s, e):
                mask[k] = 1
    return mask


def line_of(text, pos):
    return text.count('\n', 0, pos) + 1


# ---------------------------------------------------------------- 语句切分

def split_statements(sql, mask):
    """按 code 区域的 `;` 把 SQL 切成语句区间 [(start, end)]（不含分号本身）。"""
    stmts = []
    s = 0
    for m in re.finditer(r';', sql):
        if mask[m.start()]:
            stmts.append((s, m.start()))
            s = m.end()
    if s < len(sql):
        stmts.append((s, len(sql)))
    return [(a, b) for a, b in stmts if sql[a:b].strip()]


# ---------------------------------------------------------------- 转换器

# FROM/JOIN 表引用：{DB}.TBL alias 或 TBL alias（alias 必须存在，别名为空不匹配）
TABLE_REF_RE_TMPL = (
    r'(?:(?:FROM|JOIN)\s+)(?:\{(\w+)\}\.)?({tbl})\s+(?:AS\s+)?([A-Za-z]\w*)')

# 子句结束关键字（顶层、括号外）：下一个 JOIN/WHERE/GROUP/ORDER/HAVING/UNION 等
CLAUSE_END_RE = re.compile(
    r'\b(JOIN|WHERE|GROUP\s+BY|ORDER\s+BY|HAVING|UNION|EXCEPT|INTERSECT|'
    r'INSERT|UPDATE|DELETE|CREATE|DROP|SELECT)\b', re.I)

# 子查询右括号后跟随的关键字（非别名）——这些情况不构成「( SELECT ... ) 别名」
RESERVED_AFTER_SUBQ = {
    'where', 'group', 'order', 'having', 'union', 'except', 'intersect',
    'on', 'join', 'left', 'right', 'inner', 'outer', 'full', 'cross',
    'from', 'insert', 'update', 'delete', 'create', 'drop', 'and', 'or',
    'not', 'in', 'exists', 'limit', 'select', 'by', 'as', 'then', 'else',
    'end', 'when', 'case', 'set', 'values', 'with',
}


class TableTransformer:
    """单张旧表的转换器：表引用替换 / 列改名 / need_join JOIN 注入。"""

    def __init__(self, old_table, cfg):
        self.old_table = old_table
        self.new_table = cfg['new_table']
        self.old_db = cfg.get('old_db', '')          # FDM（不带花括号）
        self.new_db = cfg.get('new_db', '')          # ODM
        self.col_map = {k.lower(): v for k, v in cfg.get('column_mapping', {}).items()}
        self.special = {k.lower(): v for k, v in cfg.get('special_columns', {}).items()}
        self.join_cols = {k.lower(): v for k, v in cfg.get('join_columns', {}).items()}
        # 改名全表（rename + special）：pass2 用
        self.rename_map = dict(self.col_map)
        self.rename_map.update(self.special)
        # 表引用正则：{FDM}.TBL 或 裸 TBL（{ODM}.TBL 等其他库前缀不匹配）
        self.table_ref_re = re.compile(
            r'(?:\{%s\}\.)?(?<!\w)%s(?![\w])\s*(?:AS\s+)?([A-Za-z]\w*)'
            % (self.old_db, re.escape(self.old_table)), re.I)
        # pass4 依赖：本次转换已应用的改名 (alias_lower, old_col, new_col)
        # 与 join 别名反查 {stmt_idx: {tbl_alias_lower: (fj_alias, orig_alias)}}
        self.applied_renames = []
        self.fj_map = {}
        # 行号换算旁路信息（不改转换行为，仅供 _fix_line_numbers 消费）：
        # 各 pass 的替换/注入事件表 (start, old_len, new_len)，start 为该 pass
        # 输入文本基准的偏移
        self._p1_edits = []   # pass1 表引用替换（块原文基准）
        self._p2_edits = []   # pass2 列改名（pass1 后基准）
        self._inj_segs = []   # pass3 注入段 (S, L, nl)：S/L 为 pass3 后基准
                              # 起点/长度，nl 为净增行数

    def reset_block_state(self):
        """每三引号块处理前重置改写记录（记录仅块内有效）。"""
        self.applied_renames = []
        self.fj_map = {}
        self._p1_edits = []
        self._p2_edits = []
        self._inj_segs = []

    # ---- pass1: 表引用替换 ----
    def pass1_table_ref(self, sql, notes):
        mask = build_code_mask(sql)
        out, last, changed = [], 0, 0
        for m in self.table_ref_re.finditer(sql):
            if not mask[m.start()]:
                continue
            # match 范围含库前缀+表名+别名，替换时保留别名
            new_ref = '{%s}.%s %s' % (self.new_db, self.new_table, m.group(1))
            self._p1_edits.append((m.start(), m.end() - m.start(), len(new_ref)))
            out.append(sql[last:m.start()])
            out.append(new_ref)
            last = m.end()
            notes.append({
                'type': 'table_ref', 'line': line_of(sql, m.start()),
                'old': ' '.join(m.group(0).split()),
                'new': '{%s}.%s %s' % (self.new_db, self.new_table, m.group(1)),
                # 块原文基准偏移（_fix_line_numbers 换算用）
                '_off': m.start(),
            })
            changed += 1
        out.append(sql[last:])
        return ''.join(out), changed

    # ---- pass2: 别名作用域内列改名（语句级作用域）----
    def pass2_rename(self, sql, notes):
        mask = build_code_mask(sql)
        stmts = split_statements(sql, mask)
        col_pat = '|'.join(re.escape(c) for c in
                           sorted(self.rename_map, key=len, reverse=True))
        edits = []
        for a, b in stmts:
            aliases = self._collect_aliases(sql, mask, (a, b))
            if not aliases:
                continue
            alias_pat = '|'.join(re.escape(x) for x in
                                 sorted(aliases, key=len, reverse=True))
            col_ref_re = re.compile(r'\b(%s)\.(%s)(?![\w])' % (alias_pat, col_pat),
                                    re.I)
            for m in col_ref_re.finditer(sql, a, b):
                if not mask[m.start()]:
                    continue
                alias, old_col = m.group(1), m.group(2)
                new_col = self.rename_map[old_col.lower()]
                if new_col.lower() == old_col.lower():
                    continue  # 恒等改名：文本不变，跳过（保证幂等重跑 0 转换）
                edits.append((m.start(), m.end(), '%s.%s' % (alias, new_col), {
                    'type': 'rename', 'line': line_of(sql, m.start()),
                    'old': '%s.%s' % (alias, old_col),
                    'new': '%s.%s' % (alias, new_col),
                    '_rename': (alias.lower(), old_col, new_col),
                    # pass1 后基准偏移（_fix_line_numbers 换算用）
                    '_off': m.start(),
                }))
        # 旁路：pass2 替换事件表（pass1 后基准），供行号换算统一基准
        self._p2_edits = [(s, e - s, len(new)) for s, e, new, _n in edits]
        edits.sort(key=lambda x: x[0], reverse=True)
        for s, e, new, note in edits:
            sql = sql[:s] + new + sql[e:]
            if '_rename' in note:
                self.applied_renames.append(note.pop('_rename'))
            notes.append(note)
        return sql, len(edits)

    # ---- pass3: need_join 列改写 + JOIN 注入（语句级作用域，edits 统一倒序应用）----
    def pass3_join(self, sql, notes):
        mask = build_code_mask(sql)
        if not self.join_cols:
            return sql, 0
        stmts = split_statements(sql, mask)
        # 已占用的 join 别名（避免与现有标识符冲突）
        used_ja = {u.upper() for u in re.findall(r'\b(FJ\d+)\b', sql, re.I)}
        ja_counter = [1]

        def next_ja():
            while True:
                a = 'FJ%d' % ja_counter[0]
                ja_counter[0] += 1
                if a.upper() not in used_ja:
                    used_ja.add(a.upper())
                    return a

        need_pat = '|'.join(re.escape(c) for c in
                            sorted(self.join_cols, key=len, reverse=True))
        edits = []
        stmt_alias_ja = {}  # si -> {alias_lower: (join_alias, 原大小写别名)}
        # pass A：收集 need 列引用改写 + 按(语句,别名)分配 join 别名
        for si, (a, b) in enumerate(stmts):
            aliases = self._collect_aliases(sql, mask, (a, b))
            if not aliases:
                continue
            alias_pat = '|'.join(re.escape(x) for x in
                                 sorted(aliases, key=len, reverse=True))
            need_ref_re = re.compile(r'\b(%s)\.(%s)(?![\w])' % (alias_pat, need_pat),
                                     re.I)
            for m in need_ref_re.finditer(sql, a, b):
                if not mask[m.start()]:
                    continue
                alias, need_col = m.group(1), m.group(2)
                alc = alias.lower()
                # 注意：setdefault(alc, (next_ja(), alias)) 的参数会先求值，
                # 每个引用都会空耗一个编号——须显式判断后再分配
                if si not in stmt_alias_ja:
                    stmt_alias_ja[si] = {}
                if alc not in stmt_alias_ja[si]:
                    stmt_alias_ja[si][alc] = (next_ja(), alias)
                ja = stmt_alias_ja[si][alc][0]
                jcfg = self.join_cols[need_col.lower()]
                edits.append((m.start(), m.end(), '%s.%s' % (ja, jcfg['take_col']), {
                    'type': 'join_ref', 'line': line_of(sql, m.start()),
                    'old': '%s.%s' % (alias, need_col),
                    'new': '%s.%s' % (ja, jcfg['take_col']),
                    '_joinrec': (alc, need_col, jcfg['take_col']),
                    # pass2 后基准偏移（与注入点同基准，_fix_line_numbers 比较用）
                    '_off': m.start(),
                }))
        if not edits:
            return sql, 0
        # pass B：注入位置（全部基于原文偏移收集，最后统一倒序 splice）
        for si, amap in stmt_alias_ja.items():
            a, b = stmts[si]
            aliases = self._collect_aliases(sql, mask, (a, b))
            for alc, (ja, alias) in amap.items():
                if alc not in aliases:
                    continue
                pos = self._find_inject_pos(sql, mask, aliases, alc, a, b)
                if pos is None:
                    notes.append({
                        'type': 'join_inject_failed', 'line': line_of(sql, a),
                        'old': '别名 %s（语句 %d）未找到注入点' % (alias, si + 1),
                        'new': '请人工补齐 LEFT JOIN',
                        # pass2 后基准偏移（语句首，无注入位移语义）
                        '_off': a,
                    })
                    continue
                ins = self._build_join_sql(ja, alias)
                edits.append((pos, pos, '\n' + ins + '\n', {
                    'type': 'join_inject', 'line': line_of(sql, pos),
                    'old': '(无)', 'new': ' '.join(ins.split()),
                    '_fj': ja,
                    # 注入文本 = 换行 + 3 行 JOIN 子句 + 换行，净增行数随记
                    '_inj_nl': ins.count('\n') + 2,
                    # 注入点 pass2 后基准偏移（与各 note 的 _off 同基准比较）
                    '_inj_off': pos,
                }))
        edits.sort(key=lambda x: x[0], reverse=True)
        # 旁路：注入段表（pass3 后基准）——S 为注入文本在 pass3 输出文本中
        # 的起点（前面注入的长度累计推移），供 pass4 note 口径回退与
        # _fix_line_numbers 统一排序
        inj_raw = sorted(((s, len(new), note['_inj_nl'])
                          for s, _e, new, note in edits if '_inj_nl' in note),
                         key=lambda x: x[0])
        inj_segs, acc = [], 0
        for s, seg_len, seg_nl in inj_raw:
            inj_segs.append((s + acc, seg_len, seg_nl))
            acc += seg_len
        self._inj_segs = inj_segs
        n = 0
        for s, e, new, note in edits:
            sql = sql[:s] + new + sql[e:]
            if '_joinrec' in note:
                self.applied_renames.append(note.pop('_joinrec'))
            notes.append(note)
            n += 1
        # join_inject：在转换后块文本中精确定位注入内容行（FJn 别名块内唯一；
        # _build_join_sql 生成的 JOIN 行以 FJn+换行结尾，故锚点为「空格+FJn+换行」）
        for note in notes:
            if note.get('type') == 'join_inject' and note.get('_fj'):
                p = sql.find(' ' + note['_fj'] + '\n')
                if p >= 0:
                    note['_new_line_block'] = line_of(sql, p)
        # join 别名反查（pass4 子查询传播用）
        for si, amap in stmt_alias_ja.items():
            self.fj_map[si] = dict(amap)
        return sql, n

    # ---- pass4: 子查询输出列名跨层传播 ----
    def pass4_subquery(self, sql, notes):
        """内层子查询 SELECT 裸列项（alias.col，无 AS）改名后输出列名变化，
        外层引用该子查询别名的同名列必须跟随（S.cust_no → S.cust_id）。
        有 AS 别名的输出名固定，不传播。"""
        mask = build_code_mask(sql)
        stmts = split_statements(sql, mask)
        edits = []
        # 改写记录索引：tbl_alias_lower -> {new_col_lower: old_col}
        rn = {}
        for al, oc, nc in self.applied_renames:
            rn.setdefault(al, {})[nc.lower()] = oc
        for si, (a, b) in enumerate(stmts):
            aliases = self._collect_aliases(sql, mask, (a, b))
            fj_rev = {fj.lower(): tal for tal, (fj, _o)
                      in self.fj_map.get(si, {}).items()}
            i = a
            while i < b:
                m = re.compile(r'\(\s*SELECT\b', re.I).search(sql, i, b)
                if not m:
                    break
                qs = m.start()
                qe = self._match_paren(sql, mask, qs, b)
                if qe is None:
                    break
                # 子查询右括号后的别名（排除关键字）
                am = re.match(r'\s*(?:AS\s+)?([A-Za-z]\w*)', sql[qe + 1:b], re.I)
                if am:
                    s_al = am.group(1)
                    if s_al.lower() not in RESERVED_AFTER_SUBQ:
                        prop = self._propagate_output_names(
                            sql, mask, qs + 1, qe, aliases, rn, fj_rev)
                        if prop:
                            old_pat = '|'.join(re.escape(c) for c in
                                               sorted(prop, key=len, reverse=True))
                            s_re = re.compile(
                                r'\b(%s)\.(%s)(?![\w])' % (re.escape(s_al), old_pat),
                                re.I)
                            for m2 in s_re.finditer(sql, a, b):
                                if qs <= m2.start() <= qe:
                                    continue  # 子查询体内部引用不动
                                if not mask[m2.start()]:
                                    continue
                                oc = m2.group(2).lower()
                                nc = prop[oc]
                                edits.append((m2.start(), m2.end(),
                                              '%s.%s' % (s_al, nc), {
                                    'type': 'subquery_prop',
                                    'line': line_of(sql, m2.start()),
                                    'old': '%s.%s' % (s_al, m2.group(2)),
                                    'new': '%s.%s' % (s_al, nc),
                                    # pass3 注入后口径的块内偏移
                                    # （_fix_line_numbers 回退注入段换算）
                                    '_off': m2.start(),
                                }))
                i = qe + 1
        edits.sort(key=lambda x: x[0], reverse=True)
        for s, e, new, note in edits:
            sql = sql[:s] + new + sql[e:]
            notes.append(note)
        return sql, len(edits)

    def _match_paren(self, sql, mask, qs, limit):
        """qs 处 `(` 的配对右括号位置（仅 code 区域计数）。"""
        depth = 0
        j = qs
        while j < limit:
            if mask[j]:
                if sql[j] == '(':
                    depth += 1
                elif sql[j] == ')':
                    depth -= 1
                    if depth == 0:
                        return j
            j += 1
        return None

    def _propagate_output_names(self, sql, mask, qs, qe, tbl_aliases, rn, fj_rev):
        """扫子查询体各 UNION 段顶层 SELECT 裸列项，推导输出列名变化 {old: new}。"""
        prop = {}
        # 按 UNION/EXCEPT/INTERSECT 分段（顶层）——段内再找 SELECT 列表
        seg_bounds = []
        depth = 0
        last = qs
        i = qs
        while i < qe:
            if not mask[i]:
                i += 1
                continue
            ch = sql[i]
            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
            elif depth == 0:
                m = re.match(r'UNION|EXCEPT|INTERSECT', sql[i:], re.I)
                if m:
                    seg_bounds.append((last, i))
                    last = i + len(m.group(0))
                    i = last - 1
            i += 1
        seg_bounds.append((last, qe))
        for sa, sb in seg_bounds:
            # 段内深度 0 的第一个 SELECT
            sel = None
            depth = 0
            j = sa
            while j < sb:
                if not mask[j]:
                    j += 1
                    continue
                ch = sql[j]
                if ch == '(':
                    depth += 1
                elif ch == ')':
                    depth -= 1
                elif depth == 0 and re.match(r'SELECT\b', sql[j:], re.I):
                    sel = j
                    break
                j += 1
            if sel is None:
                continue
            # 列表项：SELECT 后到段内深度 0 的 FROM（或段尾）
            frm = sb
            depth = 0
            j = sel + 6
            while j < sb:
                if not mask[j]:
                    j += 1
                    continue
                ch = sql[j]
                if ch == '(':
                    depth += 1
                elif ch == ')':
                    depth -= 1
                elif depth == 0 and re.match(r'FROM\b', sql[j:], re.I):
                    frm = j
                    break
                j += 1
            from mapping_extractor import split_top_level_commas, strip_comment
            items = split_top_level_commas(sql[sel + 6:frm])
            for it in items:
                it_s = strip_comment(it).strip()
                cm = re.match(r'^([A-Za-z]\w*)\.(\w+)$', it_s)
                if not cm:
                    continue  # 带 AS/表达式/常量的输出名固定，不传播
                x_al, col1 = cm.group(1).lower(), cm.group(2)
                src = fj_rev.get(x_al) if x_al in fj_rev else (
                    x_al if x_al in tbl_aliases else None)
                if src is None:
                    continue
                new2old = rn.get(src, {})
                old = new2old.get(col1.lower())
                if old and old.lower() not in prop:
                    prop[old.lower()] = col1
                elif old and prop.get(old.lower()) != col1:
                    return {}  # 多段传播冲突，交人工
        return prop

    def _build_join_sql(self, ja, alias):
        """取任一 join_col 配置生成注入 SQL（同表多个 need 列共用一个 JOIN）。"""
        jcfg = next(iter(self.join_cols.values()))
        on = jcfg['join_on'].replace('{J}', ja).replace('{A}', alias)
        flt = jcfg['join_filter'].replace('{J}', ja).replace('{A}', alias)
        return ('LEFT JOIN %s %s\n  ON %s\n  AND %s'
                % (jcfg['join_table'], ja, on, flt))

    def _find_inject_pos(self, sql, mask, aliases, alias, stmt_a, stmt_b):
        """定位别名定义子句末尾：别名定义点起，括号深度 0 的下一个子句关键字前。"""
        d_start, d_end = aliases[alias]
        if not (stmt_a <= d_start < stmt_b):
            return None
        depth = 0
        i = d_end
        while i < stmt_b:
            if not mask[i]:
                i += 1
                continue
            ch = sql[i]
            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
            elif ch.isspace():
                pass
            elif depth == 0:
                m = CLAUSE_END_RE.match(sql, i)
                if m:
                    return i
            i += 1
        return stmt_b

    # ---- 辅助：别名 → 定义位置（语句区间内 FROM/JOIN 该新表的别名）----
    def _collect_aliases(self, sql, mask, stmt_range=None):
        """返回 {alias_lower: (def_start, def_end)}（pass1 后按 new_table 找）。"""
        res = {}
        tbl_re = re.compile(
            r'(?:FROM|JOIN)\s+(?:\{\w+\}\.)?%s\s+(?:AS\s+)?([A-Za-z]\w*)'
            % re.escape(self.new_table), re.I)
        a0, b0 = stmt_range if stmt_range else (0, len(sql))
        for m in tbl_re.finditer(sql, a0, b0):
            if not mask[m.start()]:
                continue
            res[m.group(1).lower()] = (m.start(), m.end())
        return res


# ---------------------------------------------------------------- 主流程

def _map_offset(off, events):
    """把「替换应用前」基准的偏移映射到「应用后」基准。
    events 为替换事件表 [(start, old_len, new_len)]，start 均为应用前基准；
    仅当替换段整体位于 off 之前时 off 随长度差推移（各 pass 的替换互不
    重叠，故可任意顺序累加）。"""
    return off + sum(nl - ol for s, ol, nl in events if s + ol <= off)


def _fix_line_numbers(notes, tr, base_before, add_lines):
    """把块内行号换算为全文行号（契约见规格 §5.1）。
    line = 转换前全文行号；new_line = 转换后全文行号。

    各 pass 记录 note 位置的文本基准不同：table_ref=块原文、rename=pass1 后、
    join_ref/join_inject=pass2 后、subquery_prop=pass3 注入后。统一换算到
    「pass2 后」基准（= 注入点基准）后，按 (块内行号, 块内偏移) 排序：

    - subquery_prop 先回退 pass3 注入段（line 与偏移同步减），消除注入
      造成的口径污染（形态2 的双重计数根源）；
    - table_ref/rename 的偏移经 _map_offset 顺向映射，消除 pass1/pass2
      替换造成的行内偏移漂移；
    - 同行 note 与注入点的先后由块内偏移精确判定：注入点之前的 note
      不吃注入位移，之后的随注入位移（形态1 的「join_inject 同行优先」
      排序无法区分前后，故弃用）。

    join_inject 自身的 new_line 用 pass3 记录的 _new_line_block 精确换算。"""
    # 1) 统一各 note 到 pass2 后基准的 (块内行号, 块内偏移)
    segs = sorted(tr._inj_segs, key=lambda x: x[0], reverse=True)
    for note in notes:
        typ = note.get('type')
        if typ == 'subquery_prop':
            off, ln = note.pop('_off'), note['line']
            for S, L, nl in segs:
                if off >= S + L:  # 注入段整体在该位置之前
                    off -= L
                    ln -= nl
            note['line'] = ln
            note['_off'] = off
        elif typ == 'table_ref':
            off = _map_offset(note.pop('_off'), tr._p1_edits)
            note['_off'] = _map_offset(off, tr._p2_edits)
        elif typ == 'rename':
            note['_off'] = _map_offset(note.pop('_off'), tr._p2_edits)
        # join_ref/_inj_off 本就是 pass2 后基准，无需换算
    # 2) 注入点与 note 统一排序：(块内行号, 块内偏移)；同位置时注入优先
    events = []
    for note in notes:
        if note.get('type') == 'join_inject':
            events.append((note['line'], note.pop('_inj_off'), 0, note))
        else:
            events.append((note['line'], note.pop('_off', 0), 1, note))
    events.sort(key=lambda x: (x[0], x[1], x[2]))
    # 3) 顺序扫描：注入点之前的 note 不位移，之后的累计注入位移
    inj = 0
    for line, _off, _kind, note in events:
        note['line'] = line + base_before - 1
        if note.get('type') == 'join_inject':
            note['new_line'] = (note.pop('_new_line_block')
                                + base_before - 1 + add_lines)
            inj += note.pop('_inj_nl')
        else:
            note['new_line'] = note['line'] + inj + add_lines


def transform_text(text, tables_cfg, notes_by_table):
    """对整份 PY 文本做转换：三引号块内逐表三遍转换，块外原样。
    notes 的 line/new_line 均为全文行号（多表对时 line 以该表处理前文本为准，
    当前单表对场景 = input 原文行号）。"""
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
            _fix_line_numbers(notes[n0:], tr, base_before, add_lines)
            add_lines += new_block.count('\n') - m.group(2).count('\n')
            out.append(text[last:m.start(2)])
            out.append(new_block)
            last = m.end(2)
        out.append(text[last:])
        text = ''.join(out)
    return text


def residue_check(text, tables_cfg):
    """残留检查（语句级作用域）：旧表引用残留 / 别名作用域内旧列残留。"""
    res = []
    for old_table, cfg in tables_cfg.items():
        tr = TableTransformer(old_table, cfg)
        mask = build_code_mask(text)
        # 表引用残留（排除注释里的提及）
        for m in tr.table_ref_re.finditer(text):
            if mask[m.start()]:
                res.append(('table_residue', old_table,
                            line_of(text, m.start())))
        # 别名作用域内旧列残留（恒等改名除外：新旧同名，转换后文本不变，必为误报；
        # join_cols 保留——pass3 漏改的 need 列引用是真问题）
        col_residue = (set(tr.join_cols)
                       | {c for c in tr.rename_map
                          if tr.rename_map[c].lower() != c.lower()})
        col_pat = '|'.join(re.escape(c) for c in
                           sorted(col_residue, key=len, reverse=True))
        for a, b in split_statements(text, mask):
            aliases = tr._collect_aliases(text, mask, (a, b))
            if not aliases:
                continue
            alias_pat = '|'.join(re.escape(x) for x in
                                 sorted(aliases, key=len, reverse=True))
            col_re = re.compile(r'\b(%s)\.(%s)(?![\w])' % (alias_pat, col_pat),
                                re.I)
            for m in col_re.finditer(text, a, b):
                if mask[m.start()]:
                    res.append(('col_residue', '%s.%s' % (m.group(1), m.group(2)),
                                line_of(text, m.start())))
    return res


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


def build_html_diff(old_text, new_text, src, out):
    old_lines = old_text.splitlines()
    new_lines = new_text.splitlines()
    hd = difflib.HtmlDiff(tabsize=4, wrapcolumn=100)
    html = hd.make_file(old_lines, new_lines,
                        fromdesc='%s (原)' % src, todesc='%s (转换后)' % out,
                        context=True, numlines=3)
    if '<meta charset' not in html and '<head>' in html:
        html = html.replace('<head>', '<head><meta charset="utf-8">', 1)
    return html


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

    # 报告文件名前缀基准：out 去扩展名（converted.py → converted）
    report_prefix = str(Path(out).with_suffix(''))

    # 残留检查（转换后文本）
    residues = residue_check(new_text, tables_cfg)

    # unified diff
    diff = difflib.unified_diff(
        text.splitlines(keepends=True), new_text.splitlines(keepends=True),
        fromfile=str(src), tofile=str(out))
    diff_txt = ''.join(diff)
    Path(report_prefix + '_diff.txt').write_text(diff_txt, encoding='utf-8')

    # HTML 并排
    html = build_html_diff(text, new_text, str(src), str(out))
    Path(report_prefix + '_diff.html').write_text(html, encoding='utf-8')

    # 报告
    rep = ['# 转换报告（%s → %s）\n' % (str(src), str(out))]
    n_total = 0
    for old_table, notes in notes_by_table.items():
        rep.append('## 表：%s → %s\n' % (old_table,
                                         tables_cfg[old_table]['new_table']))
        by_type = {}
        for n in notes:
            by_type.setdefault(n['type'], []).append(n)
        for t, items in by_type.items():
            rep.append('- %s：**%d 处**' % (t, len(items)))
            n_total += len(items)
        rep.append('')
        rep.append('| 行号 | 类型 | 原文 | 转换后 |')
        rep.append('|------|------|------|--------|')
        for n in sorted(notes, key=lambda x: x['line']):
            rep.append('| %d | %s | `%s` | `%s` |' %
                       (n['line'], n['type'],
                        n['old'].replace('|', '\\|')[:120],
                        n['new'].replace('|', '\\|')[:120]))
        rep.append('')
    if not n_total:
        rep.append('**0 处转换**（脚本可能已转换过或不含目标表引用——幂等自查口径）\n')

    rep.append('## 残留检查\n')
    if residues:
        rep.append('| 行号 | 类型 | 内容 |')
        rep.append('|------|------|------|')
        for typ, content, ln in residues:
            rep.append('| %d | %s | %s |' % (ln, typ, content))
        rep.append('')
        rep.append('⚠ 存在残留，需人工排查（可能为映射外引用或作用域外裸列）。\n')
    else:
        rep.append('✅ 无旧表引用残留、无别名作用域内旧列残留。\n')

    Path(report_prefix + '_report.md').write_text(
        '\n'.join(rep), encoding='utf-8')

    # py_compile 校验（FAIL 也要产出 report.json，前端展示失败态）
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


def main():
    ap = argparse.ArgumentParser(description='表映射驱动脚本转换（Step 4 执行层）')
    ap.add_argument('--script', required=True, help='待转换脚本 .py')
    ap.add_argument('--out', required=True, help='转换后脚本 .py')
    ap.add_argument('--config', default='transform_config.json')
    ap.add_argument('--report-prefix', default='conversion',
                    help='报告前缀：生成 <prefix>_report.md / _diff.txt / _diff.html')
    ap.add_argument('--report-json', default=None,
                    help='report.json 输出路径；缺省为 <out去.py>.report.json')
    args = ap.parse_args()

    cfg_path = Path(args.config)
    if not cfg_path.exists():
        print('缺少转换配置 %s（Step 3 人工确认后生成）' % args.config, file=sys.stderr)
        sys.exit(1)
    config = json.loads(cfg_path.read_text(encoding='utf-8'))
    tables_cfg = config.get('tables', {})
    if not tables_cfg:
        print('配置无 tables 条目', file=sys.stderr)
        sys.exit(1)

    report = convert_one(args.script, args.out, args.config)

    # convert_one 报告文件名基于 out 前缀；按 --report-prefix 挪名（任务 1 行为不变）
    out_prefix = str(Path(args.out).with_suffix(''))
    if out_prefix != args.report_prefix:
        for suffix in ('_report.md', '_diff.txt', '_diff.html'):
            f = Path(out_prefix + suffix)
            if f.exists():
                f.replace(Path(args.report_prefix + suffix))
    # report.json：convert_one 缺省写在 <out去.py>.report.json；
    # --report-json 指定时挪到该路径
    default_rj = Path(str(args.out)[:-3] + '.report.json')
    report_path = Path(args.report_json) if args.report_json else default_rj
    if report_path != default_rj and default_rj.exists():
        default_rj.replace(report_path)

    print('[OK] 转换完成：')
    print('  - 转换后脚本: %s' % args.out)
    print('  - 转换点: %d 处' % report['summary']['total'])
    print('  - 报告: %s_report.md' % args.report_prefix)
    print('  - diff: %s_diff.txt / %s_diff.html' % (args.report_prefix,
                                                    args.report_prefix))
    if report['summary']['residues']:
        print('  ⚠ 残留 %d 处，见报告残留检查节' % report['summary']['residues'])
    if report['py_compile'] == 'PASS':
        print('  - py_compile: PASS')
    else:
        print('  - py_compile: FAIL %s' % report.get('error', ''),
              file=sys.stderr)
    print('  - report.json: %s' % report_path)
    if report['py_compile'] == 'FAIL':
        sys.exit(2)


if __name__ == '__main__':
    main()
