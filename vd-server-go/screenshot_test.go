package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"image/jpeg"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"
)

func screenshotFrame(w, h uint32, pixels int) []byte {
	raw := make([]byte, 16+pixels*4)
	binary.LittleEndian.PutUint32(raw[:4], w)
	binary.LittleEndian.PutUint32(raw[4:8], h)
	binary.LittleEndian.PutUint32(raw[8:12], 1)
	for i := 16; i < len(raw); i++ {
		raw[i] = 255
	}
	return raw
}

func TestScreenshotDisplayAndJPEG(t *testing.T) {
	for _, tc := range []struct {
		name, surface string
		display       int
		want          []string
	}{
		{"physical", "", 0, []string{}},
		{"virtual", "Display 123456 (Agent VirtualDisplay)", 8, []string{"-d", "123456"}},
		{"fallback", "", 8, []string{"-d", "8"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var calls []string
			run := func(ctx context.Context, name string, args ...string) ([]byte, error) {
				calls = append(calls, name)
				if deadline, ok := ctx.Deadline(); !ok || time.Until(deadline) > screenshotTimeout {
					t.Fatal("截图命令缺少截止时间")
				}
				if strings.HasSuffix(name, "dumpsys") {
					return []byte(tc.surface), nil
				}
				if !reflect.DeepEqual(append([]string{}, args...), tc.want) {
					t.Fatalf("截图参数 %v，期望 %v", args, tc.want)
				}
				return screenshotFrame(2, 2, 4), nil
			}
			data, err := captureScreenshot(context.Background(), tc.display, run)
			if err != nil {
				t.Fatal(err)
			}
			img, err := jpeg.Decode(bytes.NewReader(data))
			if err != nil || img.Bounds().Dx() != 2 || img.Bounds().Dy() != 2 {
				t.Fatalf("JPEG 解码失败: %v", err)
			}
			if tc.display == 0 && len(calls) != 1 {
				t.Fatal("主屏截图额外查询了虚拟显示")
			}
		})
	}
}

func TestScreenshotRejectsInvalidFrame(t *testing.T) {
	for _, raw := range [][]byte{nil, screenshotFrame(0, 1, 1), screenshotFrame(2, 2, 1), screenshotFrame(^uint32(0), ^uint32(0), 0)} {
		_, err := captureScreenshot(context.Background(), 0, func(context.Context, string, ...string) ([]byte, error) { return raw, nil })
		if err == nil || err.Error() != "SCREENSHOT_INVALID_FRAME" {
			t.Fatalf("无效帧返回 %v", err)
		}
	}
}

func TestScreenshotDeadlineAndCancellation(t *testing.T) {
	for _, display := range []int{0, 8} {
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
		calls := 0
		_, err := captureScreenshot(ctx, display, func(ctx context.Context, _ string, _ ...string) ([]byte, error) {
			calls++
			<-ctx.Done()
			return nil, ctx.Err()
		})
		cancel()
		if !errors.Is(err, context.DeadlineExceeded) || calls != 1 {
			t.Fatalf("截止后仍有命令或未返回超时: calls=%d err=%v", calls, err)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := captureScreenshot(ctx, 0, func(context.Context, string, ...string) ([]byte, error) {
		t.Fatal("请求取消后执行了截图")
		return nil, nil
	})
	if !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}

func TestScreenshotCommandKillsTimedOutProcess(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	started := time.Now()
	_, err := runScreenshotCommand(ctx, os.Args[0], "-test.run=^TestScreenshotWaitProcess$", "--", "screenshot-wait")
	if err == nil || ctx.Err() != context.DeadlineExceeded || time.Since(started) > 3*time.Second {
		t.Fatalf("截图子进程未及时回收: %v", err)
	}
}

func TestScreenshotWaitProcess(t *testing.T) {
	if os.Args[len(os.Args)-1] == "screenshot-wait" {
		time.Sleep(time.Minute)
	}
}
