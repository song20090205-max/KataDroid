#!/usr/bin/env python3
"""Generate a Taiwan Traditional Chinese Android resource file from zh-CN."""
from pathlib import Path
import xml.etree.ElementTree as ET
from opencc import OpenCC

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
    "落子": "下棋",
    "变化": "變化",
    "搜索": "搜尋",
    "访问数": "訪問數",
    "自动": "自動",
    "黑棋": "黑棋",
    "白棋": "白棋",
    "导入": "匯入",
    "导出": "匯出",
    "文件": "檔案",
    "屏幕": "螢幕",
    "应用": "套用",
    "启动": "啟動",
    "关闭": "關閉",
    "暂停": "暫停",
    "继续": "繼續",
    "节点": "節點",
    "变化树": "變化樹",
    "计算": "計算",
    "引擎": "引擎",
    "棋局": "棋局",
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
print(f"Generated {target} from {source}")
