package main

// 사진 미리보기 (휴대폰 목록의 작은 그림): GET /v1/thumb?root=&path=&s=256 → JPEG
//   JPEG 은 사진 안에 들어 있는 작은 그림(EXIF)을 먼저 쓰고, 없으면 전체를 풀어 줄임. PNG·GIF 도 줄임.
//   사진 방향(EXIF Orientation)을 맞춰 돌림. 만든 것은 state/thumbs 에 두고 다시 씀 (파일이 바뀌면 새로).

import (
	"bytes"
	"crypto/sha1"
	"encoding/binary"
	"encoding/hex"
	"image"
	"image/color"
	_ "image/gif"
	"image/jpeg"
	_ "image/png"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

var thumbSem = make(chan struct{}, 2)

func hThumb(w http.ResponseWriter, r *http.Request) {
	rt := getRoot(w, r)
	if rt == nil {
		return
	}
	rel, ok := cleanRel(r.URL.Query().Get("path"))
	if !ok {
		httpErr(w, 400, "bad_path", "경로가 올바르지 않음")
		return
	}
	ext := strings.ToLower(filepath.Ext(rel))
	if ext != ".jpg" && ext != ".jpeg" && ext != ".png" && ext != ".gif" {
		httpErr(w, 415, "no_thumb", "미리보기를 만들 수 없는 종류")
		return
	}
	size, _ := strconv.Atoi(r.URL.Query().Get("s"))
	if size < 64 || size > 640 {
		size = 256
	}
	abs := filepath.Join(rt.Dir, filepath.FromSlash(rel))
	fi, err := os.Stat(abs)
	if err != nil || fi.IsDir() {
		httpErr(w, 404, "not_found", "파일이 없음")
		return
	}
	h := sha1.Sum([]byte(abs + "\x00" + strconv.FormatInt(fi.Size(), 10) + "\x00" + strconv.FormatInt(fi.ModTime().UnixNano(), 10) + "\x00" + strconv.Itoa(size)))
	key := hex.EncodeToString(h[:])
	cache := filepath.Join(stateDir, "thumbs", key[:2], key+".jpg")
	w.Header().Set("Cache-Control", "private, max-age=86400")
	if b, err := os.ReadFile(cache); err == nil {
		w.Header().Set("Content-Type", "image/jpeg")
		w.Write(b)
		return
	}
	thumbSem <- struct{}{}
	b, err := makeThumb(abs, ext, size, fi.Size())
	<-thumbSem
	if err != nil {
		httpErr(w, 422, "no_thumb", err.Error())
		return
	}
	os.MkdirAll(filepath.Dir(cache), 0700)
	os.WriteFile(cache, b, 0600)
	w.Header().Set("Content-Type", "image/jpeg")
	w.Write(b)
}

func makeThumb(abs, ext string, size int, fsize int64) ([]byte, error) {
	f, err := os.Open(abs)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	orient := 1
	var img image.Image
	if ext == ".jpg" || ext == ".jpeg" {
		head := make([]byte, 256<<10)
		n, _ := io.ReadFull(f, head)
		head = head[:n]
		var small []byte
		orient, small = exifInfo(head)
		if small != nil {
			if im, err := jpeg.Decode(bytes.NewReader(small)); err == nil && max(im.Bounds().Dx(), im.Bounds().Dy()) >= size*5/8 {
				img = im
			}
		}
		f.Seek(0, io.SeekStart)
	}
	if img == nil {
		cfg, _, err := image.DecodeConfig(f)
		if err != nil {
			return nil, err
		}
		if int64(cfg.Width)*int64(cfg.Height) > 80_000_000 {
			return nil, os.ErrInvalid
		}
		f.Seek(0, io.SeekStart)
		if img, _, err = image.Decode(f); err != nil {
			return nil, err
		}
	}
	out := orientImg(shrink(img, size), orient)
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, out, &jpeg.Options{Quality: 80}); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// 긴 변이 size 가 되게 줄임 (한 칸마다 3×3 점을 평균 — 빠르고 계단이 덜 짐)
func shrink(src image.Image, size int) *image.RGBA {
	b := src.Bounds()
	sw, sh := b.Dx(), b.Dy()
	k := float64(size) / float64(max(sw, sh))
	if k > 1 {
		k = 1
	}
	dw, dh := max(1, int(float64(sw)*k+0.5)), max(1, int(float64(sh)*k+0.5))
	dst := image.NewRGBA(image.Rect(0, 0, dw, dh))
	fx, fy := float64(sw)/float64(dw), float64(sh)/float64(dh)
	for y := 0; y < dh; y++ {
		for x := 0; x < dw; x++ {
			var r, g, bl, n uint32
			for j := 0; j < 3; j++ {
				for i := 0; i < 3; i++ {
					sx := b.Min.X + int((float64(x)+(float64(i)+0.5)/3)*fx)
					sy := b.Min.Y + int((float64(y)+(float64(j)+0.5)/3)*fy)
					cr, cg, cb, _ := src.At(min(sx, b.Max.X-1), min(sy, b.Max.Y-1)).RGBA()
					r, g, bl, n = r+cr, g+cg, bl+cb, n+1
				}
			}
			dst.SetRGBA(x, y, color.RGBA{uint8(r / n >> 8), uint8(g / n >> 8), uint8(bl / n >> 8), 255})
		}
	}
	return dst
}

// EXIF 방향(1~8)대로 돌리기·뒤집기
func orientImg(src *image.RGBA, o int) image.Image {
	if o <= 1 || o > 8 {
		return src
	}
	b := src.Bounds()
	w, h := b.Dx(), b.Dy()
	dw, dh := w, h
	if o >= 5 {
		dw, dh = h, w
	}
	dst := image.NewRGBA(image.Rect(0, 0, dw, dh))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			var nx, ny int
			switch o {
			case 2:
				nx, ny = w-1-x, y
			case 3:
				nx, ny = w-1-x, h-1-y
			case 4:
				nx, ny = x, h-1-y
			case 5:
				nx, ny = y, x
			case 6:
				nx, ny = h-1-y, x
			case 7:
				nx, ny = h-1-y, w-1-x
			case 8:
				nx, ny = y, w-1-x
			}
			dst.SetRGBA(nx, ny, src.RGBAAt(x, y))
		}
	}
	return dst
}

// JPEG 앞부분에서 EXIF 방향과 들어 있는 작은 그림(IFD1)을 찾음
func exifInfo(b []byte) (orient int, thumb []byte) {
	orient = 1
	if len(b) < 4 || b[0] != 0xFF || b[1] != 0xD8 {
		return
	}
	p := 2
	for p+4 <= len(b) {
		if b[p] != 0xFF {
			return
		}
		marker := b[p+1]
		ln := int(binary.BigEndian.Uint16(b[p+2:]))
		if marker == 0xDA || ln < 2 || p+2+ln > len(b) {
			return
		}
		seg := b[p+4 : p+2+ln]
		if marker == 0xE1 && len(seg) > 14 && string(seg[:6]) == "Exif\x00\x00" {
			return parseTIFF(seg[6:])
		}
		p += 2 + ln
	}
	return
}

func parseTIFF(t []byte) (orient int, thumb []byte) {
	orient = 1
	if len(t) < 8 {
		return
	}
	var bo binary.ByteOrder = binary.LittleEndian
	if string(t[:2]) == "MM" {
		bo = binary.BigEndian
	} else if string(t[:2]) != "II" {
		return
	}
	u16 := func(o int) int {
		if o+2 > len(t) || o < 0 {
			return 0
		}
		return int(bo.Uint16(t[o:]))
	}
	u32 := func(o int) int {
		if o+4 > len(t) || o < 0 {
			return 0
		}
		return int(bo.Uint32(t[o:]))
	}
	ifd0 := u32(4)
	n := u16(ifd0)
	for i := 0; i < n; i++ {
		e := ifd0 + 2 + i*12
		if u16(e) == 0x0112 {
			orient = u16(e + 8)
		}
	}
	ifd1 := u32(ifd0 + 2 + n*12)
	if ifd1 <= 0 || ifd1 >= len(t) {
		return
	}
	off, ln := 0, 0
	m := u16(ifd1)
	for i := 0; i < m; i++ {
		e := ifd1 + 2 + i*12
		switch u16(e) {
		case 0x0201:
			off = u32(e + 8)
		case 0x0202:
			ln = u32(e + 8)
		}
	}
	if off > 0 && ln > 0 && off+ln <= len(t) {
		thumb = t[off : off+ln]
	}
	return
}
