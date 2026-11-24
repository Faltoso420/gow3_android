import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNTIME_PAD = ROOT / "src/runtime_pad.c"
PAD_BRIDGE = ROOT / "android-launcher/app/src/main/java/com/gow3/android/game/PadBridge.java"
RUMBLE = ROOT / "android-launcher/app/src/main/java/com/gow3/android/game/GamepadRumble.java"
GAME_ACTIVITY = ROOT / "android-launcher/app/src/main/java/com/gow3/android/game/GameActivity.java"
MANIFEST = ROOT / "android-launcher/app/src/main/AndroidManifest.xml"
RUN_BIONIC = ROOT / "tools/android/device/run-bionic.sh"
SETTINGS_CPP = ROOT / "gpu/shim/gow3_settings.cpp"
SETTINGS_H = ROOT / "gpu/shim/gow3_settings.h"
GOW3_GPU = ROOT / "gpu/shim/gow3gpu.cpp"
PAD_MAPPING_ACTIVITY = ROOT / "android-launcher/app/src/main/java/com/gow3/android/feature/settings/PadMappingActivity.kt"


def read(path):
    # 显式 UTF-8：Windows 默认 cp1252 会把仓库里的中文注释读崩（上游 CI 是 Linux）。
    return path.read_text(encoding="utf-8")


class VibrationSettingsContractTest(unittest.TestCase):
    """上游 9d0f51c 震动设置链：解析、裁剪、env 覆盖与缩放（full/reduced/disabled 场景）。"""

    def test_settings_parse_clamps_and_defaults(self):
        """full=100 默认；reduced 按值；disabled=0；越界裁剪到 0..100。"""
        source = read(SETTINGS_CPP)
        self.assertIn('key == "vibration"', source)
        self.assertIn("value.empty() ? 100 : std::clamp(i, 0, 100)", source)
        header = read(SETTINGS_H)
        self.assertIn("std::atomic<int> vibration{100};", header)
        self.assertIn("0 turns it off", header)

    def test_env_override_is_registered(self):
        """GOW3_VIBRATION env 覆盖注册存在（missing env 时回落 ini/默认）。"""
        self.assertIn('{"GOW3_VIBRATION", "vibration"}', read(SETTINGS_CPP))

    def test_scaling_applies_percent_and_clamps(self):
        """scePadSetVibration 参数按百分比缩放，percent>100 裁剪。"""
        source = read(RUNTIME_PAD)
        self.assertIn("strength * 257u * percent / 100u", source)
        self.assertIn("if (percent > 100) percent = 100;", source)

    def test_vibration_percent_bridge_symbol_exists(self):
        """GPU shim 导出 gow3gpu_vibration_percent（设置 -> pad 运行时的桥）。"""
        self.assertIn("gow3gpu_vibration_percent(void)", read(GOW3_GPU))


class RumbleBridgeContractTest(unittest.TestCase):
    """Android 震动回程：游戏侧文件写入 <-> Java 轮询驱动手柄马达（design.md 决策 9）。"""

    def test_native_writes_rumble_file_atomically_with_seq(self):
        source = read(RUNTIME_PAD)
        self.assertIn("GOW3_PAD_RUMBLE_FILE", source)
        # 单行协议带递增 seq：行变化才触发
        self.assertIn('"rumble %d %d 300 %u\\n"', source)
        # 原子替换：tmp + rename
        self.assertIn('snprintf(tmp,sizeof tmp,"%s.tmp",path)', source)
        self.assertIn("rename(tmp,path);", source)
        # 100ms 重写门（心跳）；归零发布一次后停住，不再刷心跳
        self.assertIn("t-last_us<100000", source)
        self.assertIn("if (s==0 && w==0 && last_s==0 && last_w==0) return;", source)

    def test_native_publishes_stop_and_scales_both_motors(self):
        """大/小马达分别缩放；归零行让 Java 立即取消，而不是等 300ms 脉冲自然衰减。"""
        source = read(RUNTIME_PAD)
        self.assertIn("const int s=percent ? (int)strong*(int)percent/100 : 0;", source)
        self.assertIn("const int w=percent ? (int)weak*(int)percent/100 : 0;", source)
        # 停止行同样走 fprintf（同一协议），因此 Java 的 r[0]/r[1]==0 分支可达
        self.assertIn("rumble_report(param[0],param[1],percent)", source)

    def test_sdl_desktop_path_unchanged(self):
        """桌面路径保持：SDL_RumbleGamepad 仍以 scale_vibration 结果调用。"""
        source = read(RUNTIME_PAD)
        self.assertIn("SDL_RumbleGamepad(g,scale_vibration(param[0], percent),", source)
        self.assertLess(source.index("SDL_RumbleGamepad"), source.index("rumble_report(param[0]"))

    def test_run_bionic_exports_rumble_path_next_to_pad_state(self):
        source = read(RUN_BIONIC)
        self.assertIn('export GOW3_PAD_RUMBLE_FILE="$base/android-pad.rumble"', source)


class GamepadRumbleContractTest(unittest.TestCase):
    """手柄力反馈：Android 12+ 的 per-motor VibratorManager，双马达分别驱动。"""

    def test_manifest_declares_vibrate_permission(self):
        """没有 VIBRATE 权限时 vibrate() 抛 SecurityException，震动永远不工作。"""
        self.assertIn('android.permission.VIBRATE', read(MANIFEST))

    def test_uses_input_device_vibrator_manager_with_api_guard(self):
        source = read(RUMBLE)
        self.assertIn("device.getVibratorManager()", source)
        self.assertIn("manager.getVibratorIds()", source)
        self.assertIn("Build.VERSION.SDK_INT < Build.VERSION_CODES.S", source)
        # 单马达手柄回退到合并 vibrator；老系统没有公开通道则不振动手机
        self.assertIn("device.getVibrator()", source)
        self.assertIn("InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK", source)

    def test_two_motors_drive_strong_and_weak_independently(self):
        source = read(RUMBLE)
        self.assertIn("if (strong > 0) playOne(left, strong, duration);", source)
        self.assertIn("playOne(motors[MOTOR_WEAK], weak, duration)", source)
        self.assertIn("Math.max(strong, weak)", source)  # 单马达合并
        self.assertIn("VibrationEffect.createOneShot(ms, effect)", source)
        self.assertIn("hasAmplitudeControl()", source)

    def test_never_crashes_without_permission_or_motor(self):
        source = read(RUMBLE)
        self.assertIn("catch (SecurityException ignored)", source)
        self.assertIn("catch (RuntimeException ignored)", source)

    def test_test_pulse_is_exposed_for_settings(self):
        """设置页/抽屉的「测试振动」按钮走同一条解析+驱动路径。"""
        self.assertIn("public static boolean playTest(Context context", read(RUMBLE))
        self.assertIn("GamepadRumble.playTest(this, 220, 160, 700)", read(PAD_MAPPING_ACTIVITY))


class PadBridgePollingContractTest(unittest.TestCase):

    def test_java_poller_parses_and_feeds_both_motors(self):
        source = read(PAD_BRIDGE)
        # 40ms 轮询、行去重（seq 心跳）、归零即停
        self.assertIn("RUMBLE_POLL_MS=40", source)
        self.assertIn("!text.equals(rumbleSeen)", source)
        self.assertIn("if (r[0]>0 || r[1]>0) sink.play(r[0],r[1],r[2]);", source)
        self.assertIn("else sink.stop();", source)
        self.assertIn("parseRumble", source)
        # 只摘轮询回调，不破坏 write() 的 8ms 合并
        self.assertIn("handler.removeCallbacks(rumbleLoop)", source)
        self.assertNotIn("removeCallbacksAndMessages(null)", source)

    def test_active_gamepad_is_the_one_that_sends_input(self):
        """多手柄/键盘混合时，震动打到最近发事件的那只手。"""
        source = read(PAD_BRIDGE)
        self.assertIn("rumble.setActiveDevice(device.getId())", source)
        self.assertIn("noteDevice(e.getDevice());", source)

    def test_activity_lifecycle_pairs_start_and_stop(self):
        source = read(GAME_ACTIVITY)
        self.assertIn('pad.startRumble(this,new File(BASE,"android-pad.rumble"))', source)
        self.assertIn("pad.stopRumble()", source)
        self.assertIn("pad.resumeRumble()", source)
        self.assertIn("pad.closeRumble()", source)
        # onPause 停止在先，onResume 恢复在后
        self.assertLess(source.index("pad.stopRumble()"), source.index("pad.resumeRumble()"))


if __name__ == "__main__":
    unittest.main()
