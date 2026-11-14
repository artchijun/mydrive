package main

import "golang.org/x/sys/unix"

// diskSpace: 그 폴더가 있는 볼륨의 전체·남은 바이트 (웹드라이브가 탐색기에 보여 줌)
func diskSpace(dir string) (total, free uint64) {
	var s unix.Statfs_t
	if unix.Statfs(dir, &s) != nil {
		return 0, 0
	}
	return s.Blocks * uint64(s.Bsize), s.Bavail * uint64(s.Bsize)
}
