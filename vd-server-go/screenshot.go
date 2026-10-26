package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"image"
	"image/jpeg"
	"os/exec"
	"strconv"
	"time"
)

const screenshotTimeout = 10 * time.Second

type screenshotRunner func(context.Context, string, ...string) ([]byte, error)

func runScreenshotCommand(ctx context.Context, name string, args ...string) ([]byte, error) {
	return exec.CommandContext(ctx, name, args...).Output()
}

func captureScreenshot(parent context.Context, targetDid int, run screenshotRunner) ([]byte, error) {
	// SurfaceFlinger 查询和截图共用截止；请求取消后回收在途截图进程。
	ctx, cancel := context.WithTimeout(parent, screenshotTimeout)
	defer cancel()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	var args []string
	if targetDid != 0 {
		display := strconv.Itoa(targetDid)
		out, err := run(ctx, "/system/bin/dumpsys", "SurfaceFlinger", "--display-id")
		if err == nil {
			if match := reSfDisplay.FindStringSubmatch(string(out)); len(match) > 1 {
				display = match[1]
			}
		}
		args = []string{"-d", display}
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	raw, err := run(ctx, "/system/bin/screencap", args...)
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if err != nil {
		return nil, fmt.Errorf("SCREENSHOT_CAPTURE_FAILED")
	}
	if len(raw) < 16 {
		return nil, fmt.Errorf("SCREENSHOT_INVALID_FRAME")
	}
	w, h := binary.LittleEndian.Uint32(raw[:4]), binary.LittleEndian.Uint32(raw[4:8])
	// 先使用宽整数检查边界，避免截断或异常尺寸触发越界与整数溢出。
	if w == 0 || h == 0 || w > 16384 || h > 16384 {
		return nil, fmt.Errorf("SCREENSHOT_INVALID_FRAME")
	}
	needed := uint64(16) + uint64(w)*uint64(h)*4
	if needed > uint64(len(raw)) {
		return nil, fmt.Errorf("SCREENSHOT_INVALID_FRAME")
	}
	img := &image.RGBA{Pix: raw[16:int(needed)], Stride: int(w) * 4, Rect: image.Rect(0, 0, int(w), int(h))}
	var jpegBytes bytes.Buffer
	if err := jpeg.Encode(&jpegBytes, img, &jpeg.Options{Quality: 85}); err != nil {
		return nil, fmt.Errorf("SCREENSHOT_ENCODE_FAILED")
	}
	return jpegBytes.Bytes(), nil
}
