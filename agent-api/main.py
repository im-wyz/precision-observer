"""兼容旧启动方式：uvicorn main:app。

新代码入口位于 agent_api.main。保留这个薄入口，避免本地脚本或旧命令突然失效。
"""

from __future__ import annotations

import sys
from pathlib import Path

SRC_DIR = Path(__file__).resolve().parent / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from agent_api.main import app, run


if __name__ == "__main__":
    run()
