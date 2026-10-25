#!/bin/bash
# 앱·k6 인스턴스에서 1초마다 [시각, CPU 누적 jiffies(user nice system idle iowait irq softirq steal), java 프로세스 RSS 합(KB)] 를 남긴다.
# 멈춤 파일이 생기면 끝난다. 사용법: bash host_sampler.sh <출력> <멈춤 파일>
out=$1
stop=$2
: > "$out"
while [ ! -f "$stop" ]; do
  echo "$(date +%s.%N) $(head -1 /proc/stat | cut -d' ' -f3-10) $(ps -C java -o rss= | awk '{s+=$1} END {print s+0}')" >> "$out"
  sleep 1
done
