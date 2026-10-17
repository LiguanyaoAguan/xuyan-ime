#!/data/data/com.dsharnessmobile.shell/files/usr/bin/bash
# 把 SenseVoice 模型导入「语音输入法」的私有目录。
#
# 为什么需要它：输入法把模型放在 /data/user/0/dev.dsh.voiceime/files/models，
# 那是应用内部存储——**卸载 App 会连带删掉**，重装后必须重新导入。
#
# 为什么不能直接 cp：Android 11+ 分区存储下，App 自己的 uid 读不了
# 用户自己指定的模型目录（本脚本只负责用 run-as 管道写进 App 私有目录）。
# 所以走管道：shell 侧读文件 → stdin → App uid 侧写文件。
#
# 用法（必须用特权 shell / Shizuku 运行，普通 bash 里没有 run-as）：
#   bash import-models.sh

set -euo pipefail

PKG="dev.dsh.voiceime"
SRC_DEFAULT="${MODEL_SRC:-./models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17}"
SRC="${1:-$SRC_DEFAULT}"

say() { echo "[import] $*"; }

[ -d "$SRC" ] || { say "模型目录不存在：$SRC"; exit 1; }
for f in model.int8.onnx tokens.txt; do
  [ -f "$SRC/$f" ] || { say "缺少 $SRC/$f"; exit 1; }
done

say "检查 $PKG 是否已安装"
pm list packages 2>/dev/null | grep -q "^package:$PKG$" || { say "未安装 $PKG"; exit 1; }

say "创建目标目录"
run-as "$PKG" /system/bin/mkdir -p files/models

for f in tokens.txt model.int8.onnx; do
  say "导入 $f（$(du -h "$SRC/$f" | cut -f1)）..."
  cat "$SRC/$f" | run-as "$PKG" /system/bin/sh -c "cat > files/models/$f"
done

say "校验结果"
run-as "$PKG" /system/bin/ls -l files/models

# 大小比对：源与目标必须一致，否则说明管道被截断
for f in tokens.txt model.int8.onnx; do
  a=$(stat -c %s "$SRC/$f")
  b=$(run-as "$PKG" /system/bin/stat -c %s "files/models/$f")
  if [ "$a" != "$b" ]; then
    say "✗ $f 大小不符：源 $a / 目标 $b"
    exit 1
  fi
  say "✓ $f $b 字节"
done

say "完成。打开输入法设置页确认「模型」为绿勾。"
