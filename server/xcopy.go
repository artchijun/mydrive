package main

import (
	"io"
	"os"
	"path/filepath"
)

// moveAcross: 다른 공유 폴더로 옮기기.
//
//	시놀로지 btrfs 는 공유 폴더마다 따로 된 하위 볼륨이라 rename 이 안 됨(EXDEV) → 복사한 뒤 원본을 지움.
//	btrfs 면 reflink(FICLONE)로 복사해 순간에 끝나고 디스크 공간도 더 쓰지 않음 (같은 데이터 블록을 함께 가리킴).
//	reflink 가 안 되는 볼륨(ext4 등)이면 보통 복사 — 그래도 PC 로 받았다가 다시 올리는 것보다 훨씬 빠름.
//	복사 도중 실패하면 만든 것을 지우고 원본은 그대로 둠.
func moveAcross(src, dst string) error {
	if err := copyTree(src, dst); err != nil {
		os.RemoveAll(dst)
		return err
	}
	return os.RemoveAll(src)
}

func copyTree(src, dst string) error {
	fi, err := os.Lstat(src)
	if err != nil {
		return err
	}
	switch {
	case fi.IsDir():
		if err := os.Mkdir(dst, fi.Mode().Perm()|0o700); err != nil {
			return err
		}
		ents, err := os.ReadDir(src)
		if err != nil {
			return err
		}
		for _, e := range ents {
			if err := copyTree(filepath.Join(src, e.Name()), filepath.Join(dst, e.Name())); err != nil {
				return err
			}
		}
		return os.Chtimes(dst, fi.ModTime(), fi.ModTime())
	case fi.Mode()&os.ModeSymlink != 0:
		t, err := os.Readlink(src)
		if err != nil {
			return err
		}
		return os.Symlink(t, dst)
	case !fi.Mode().IsRegular():
		return nil // 장치 파일 등은 건너뜀
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_EXCL, fi.Mode().Perm())
	if err != nil {
		return err
	}
	if cloneFile(out, in) != nil {
		if _, err := io.Copy(out, in); err != nil {
			out.Close()
			return err
		}
	}
	if err := out.Close(); err != nil {
		return err
	}
	return os.Chtimes(dst, fi.ModTime(), fi.ModTime())
}
