package com.gow3.android.game;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.InputDevice;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Human-readable report for diagnosing controller rumble on the actual device.
 *
 * <p>Answers the question that decides everything: does Android expose the pad as a gamepad,
 * and does it expose any motors for it? Written for the "Test vibration" button in
 * Settings -> Button mapping and for the in-game drawer, so the report can be copied without adb.
 */
public final class ControllerDiagnostics {

    private ControllerDiagnostics() {
    }

    /** Plays the test pulse; false means Android sees no gamepad with motors. */
    public static boolean playTest(Context context) {
        return GamepadRumble.playTest(context, 220, 160, 700);
    }

    /**
     * @param statsFile nullable: the runtime's request counter (android-pad.rumble.stats),
     *                  available after the game has run once
     */
    public static String report(Context context, File statsFile) {
        StringBuilder out = new StringBuilder();
        out.append("Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("\n\n");
        out.append("Input devices:\n");

        InputManager im = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        int shown = 0;
        if (im != null) {
            for (int id : im.getInputDeviceIds()) {
                InputDevice device = im.getInputDevice(id);
                if (device == null) continue;
                int sources = device.getSources();
                if ((sources & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK
                        | InputDevice.SOURCE_DPAD | InputDevice.SOURCE_KEYBOARD)) == 0) continue;
                shown++;
                out.append("- ").append(device.getName())
                        .append(" (id ").append(id).append(")\n")
                        .append("  sources: ").append(sourceNames(sources)).append('\n')
                        .append("  vendor/product: ").append(device.getVendorId()).append('/')
                        .append(device.getProductId()).append('\n');
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    VibratorManager manager = device.getVibratorManager();
                    int[] motorIds = manager == null ? new int[0] : manager.getVibratorIds();
                    Vibrator combined = device.getVibrator();
                    out.append("  motors: ").append(motorIds.length)
                            .append(" | hasVibrator: ")
                            .append(combined != null && combined.hasVibrator()).append('\n');
                } else {
                    out.append("  motors: not exposed before Android 12\n");
                }
                out.append('\n');
            }
        }
        if (shown == 0) {
            out.append("- none (no gamepad/keyboard visible to Android)\n\n");
        }

        out.append("Game rumble requests: ");
        if (statsFile != null && statsFile.isFile()) {
            try {
                out.append(new String(Files.readAllBytes(statsFile.toPath()),
                        StandardCharsets.UTF_8).trim());
            } catch (Exception ex) {
                out.append("unreadable");
            }
        } else {
            out.append("none recorded yet (start the game once, then reopen this)");
        }
        out.append('\n');
        return out.toString();
    }

    private static String sourceNames(int sources) {
        StringBuilder names = new StringBuilder();
        if ((sources & InputDevice.SOURCE_GAMEPAD) != 0) names.append("gamepad ");
        if ((sources & InputDevice.SOURCE_JOYSTICK) != 0) names.append("joystick ");
        if ((sources & InputDevice.SOURCE_DPAD) != 0) names.append("dpad ");
        if ((sources & InputDevice.SOURCE_KEYBOARD) != 0) names.append("keyboard ");
        if ((sources & InputDevice.SOURCE_TOUCHSCREEN) != 0) names.append("touchscreen ");
        if ((sources & InputDevice.SOURCE_MOUSE) != 0) names.append("mouse ");
        return names.toString().trim();
    }
}
