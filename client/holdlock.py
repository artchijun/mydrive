# 시험용: 파일을 공유 없이 잡고 있다가 N초 뒤 놓음 — python holdlock.py 경로 초
import sys, time
sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from tlib import lock, unlock
h = lock(sys.argv[1]); time.sleep(float(sys.argv[2])); unlock(h)
