import re

with open('d:/HB/work_zuoye/jiaoben/dwa_c_cus_pos_mect_inf.py', 'r', encoding='gbk', errors='ignore') as f:
    content = f.read()

# 查找所有包含 VT_C_CUS_POS_MECT_INFO_01 的块
pattern = r'(CREATE\s+TEMPORARY\s+TABLE|INSERT\s+INTO)[\s\S]*?VT_C_CUS_POS_MECT_INFO_01[\s\S]*?(?=CREATE\s+TEMPORARY\s+TABLE|INSERT\s+INTO|$)'
matches = re.findall(pattern, content, re.IGNORECASE)
print(f"包含 VT_C_CUS_POS_MECT_INFO_01 的块数: {len(matches)}")
for i, m in enumerate(matches):
    print(f"\n=== 块 {i+1} (前 300 字符) ===")
    print(m[:300])
    print(f"...总长度: {len(m)}")

# 统计 CREATE TEMPORARY TABLE 和 INSERT INTO 的数量
create_count = len(re.findall(r'CREATE\s+TEMPORARY\s+TABLE', content, re.IGNORECASE))
insert_count = len(re.findall(r'INSERT\s+INTO', content, re.IGNORECASE))
print(f"\nCREATE TEMPORARY TABLE 数量: {create_count}")
print(f"INSERT INTO 数量: {insert_count}")

# 查找 VT_C_CUS_POS_MECT_INFO_01 出现的所有位置
positions = [m.start() for m in re.finditer(r'VT_C_CUS_POS_MECT_INFO_01', content, re.IGNORECASE)]
print(f"\nVT_C_CUS_POS_MECT_INFO_01 出现位置: {positions}")
for pos in positions:
    # 取该位置前 50 字符和后 100 字符作为上下文
    start = max(0, pos - 50)
    end = min(len(content), pos + 100)
    ctx = content[start:end].replace('\n', ' ')
    print(f"  位置 {pos}: ...{ctx}...")
