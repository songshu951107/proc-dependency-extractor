# -*- coding: utf-8 -*-
"""从 mapping_extracted.json 生成 transform_config.json（一次性生成工具）"""
import json
from pathlib import Path

src = json.loads(Path('mapping_extracted.json').read_text(encoding='utf-8'))
rep = src['replacement']

col_map = {}
special = {}
join_cols = {}
for m in rep['column_mapping']:
    old, kind = m['old_col'], m['kind']
    if kind == 'rename':
        col_map[old] = m['new_col']
    elif kind == 'need_join':
        join_cols[old] = {
            'join_table': '{ODM}.%s' % m['join_source_table'],
            'take_col': m['join_source_col'],
            'join_on': '{J}.cust_id={A}.cust_id',
            'join_filter': ("{J}.start_dt<=DATE'{ETL_TX_DT}' "
                            "AND {J}.end_dt>DATE'{ETL_TX_DT}' "
                            "AND {J}.del_ind<>'D'"),
        }
    elif kind == 'constant':
        # 拉链控制列人工口径：del_flag→del_ind（同语义改名）；
        # start_dt/end_dt 两边同名直映不进映射
        if old.lower() == 'del_flag':
            special[old] = 'del_ind'
        elif old.lower() in ('start_dt', 'end_dt'):
            pass  # 同名直映，无需配置
        else:
            print('⚠ 未处理的 constant 列：%s（via=%s）' % (old, m.get('via')))

config = {
    'tables': {
        rep['old_table']: {
            'new_table': rep['new_table'],
            'old_db': 'FDM',
            'new_db': 'ODM',
            'filter': 'zip',
            'column_mapping': col_map,
            'special_columns': special,
            'join_columns': join_cols,
        }
    }
}
Path('transform_config.json').write_text(
    json.dumps(config, ensure_ascii=False, indent=2), encoding='utf-8')
print('[OK] transform_config.json 生成：')
print('  - column_mapping: %d 条' % len(col_map))
print('  - special_columns: %s' % special)
print('  - join_columns: %s' % list(join_cols))
