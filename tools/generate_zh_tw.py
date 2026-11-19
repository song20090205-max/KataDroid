#!/usr/bin/env python3
"""Generate a Taiwan Traditional Chinese Android resource file from zh-CN."""
# This is generated during CI so the APK always contains the matching locale.
from pathlib import Path
import xml.etree.ElementTree as ET
from opencc import OpenCC
import re

source = Path("app/src/main/res/values-zh/strings.xml")
target_dir = Path("app/src/main/res/values-zh-rTW")
target_dir.mkdir(parents=True, exist_ok=True)
target = target_dir / "strings.xml"

converter = OpenCC("s2tw")
tree = ET.parse(source)
root = tree.getroot()

# Prefer Taiwan UI terminology over literal character conversion.
replacements = {
    "设置": "設定",
    "棋谱": "棋譜",
    "胜率": "勝率",
    "贴目": "貼目",
    "变化": "變化",
    "搜索": "搜尋",
    "访问数": "搜尋次數",
    "搜索次数": "搜尋次數",
    "搜索速度": "搜尋速度",
    "自动": "自動",
    "导入": "匯入",
    "导出": "匯出",
    "文件": "檔案",
    "屏幕": "螢幕",
    "应用": "套用",
    "棋局": "棋局",
    "启动": "啟動",
    "关闭": "關閉",
    "暂停": "暫停",
    "继续": "繼續",
    "节点": "節點",
    "次数": "次數",
    "变化树": "變化樹",
    "计算": "計算",
    "预热": "預熱",
    "实际": "實際",
    "耗时": "耗時",
    "胜率走势": "勝率走勢",
}
for node in root.iter():
    if node.text:
        value = converter.convert(node.text)
        for old, new in replacements.items():
            value = value.replace(old, new)
        node.text = value
    if node.tail:
        value = converter.convert(node.tail)
        for old, new in replacements.items():
            value = value.replace(old, new)
        node.tail = value

for node in root.findall("string"):
    if node.get("name") == "app_name":
        node.text = "KataGo Master"

ET.indent(tree, space="    ")
tree.write(target, encoding="utf-8", xml_declaration=True)
# Keep locale resources in lockstep and preserve Android formatting arguments.
source_names = {node.get("name"): node.text or "" for node in root.findall("string")}
generated_root = ET.parse(target).getroot()
generated_names = {node.get("name"): node.text or "" for node in generated_root.findall("string")}
if source_names.keys() != generated_names.keys():
    raise SystemExit("zh-TW resource names do not match the source locale")
for name, source_value in source_names.items():
    target_value = generated_names[name]
    source_args = re.findall(r"%\\d+\\$[sd]", source_value)
    target_args = re.findall(r"%\\d+\\$[sd]", target_value)
    if source_args != target_args:
        raise SystemExit(f"Formatting arguments changed for {name}: {source_args} != {target_args}")
print(f"Generated and validated {target} from {source}")
