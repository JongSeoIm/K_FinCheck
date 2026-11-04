import sys
from pathlib import Path

# tests 폴더에서 실행해도 ai-server 모듈(main, classifier …)을 찾도록
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
