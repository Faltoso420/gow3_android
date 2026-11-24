package com.gow3.android.game;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.InputDevice;

/**
 * Force feedback (rumble) on the physical gamepad the player is actually using.
 *
 * <p>Android does not let an app talk to a controller's motors directly: the only public route
 * is {@link InputDevice}. From Android 12 (API 31) an input device can own a
 * {@link VibratorManager} with one {@link Vibrator} per motor — index 0 is the left/low-frequency
 * (strong) motor and index 1 the right/high-frequency (weak) one. That is how dual-motor pads
 * (GameSir, DualSense, Xbox, 8BitDo...) rumble. Before API 31 there is no public API, so this
 * class degrades to "no rumble" instead of vibrating the phone by mistake.
 *
 * <p>The motors are resolved lazily and again whenever the active device or the device list
 * changes, so a pad connected after the game started still works.
 */
public final class GamepadRumble implements InputManager.InputDeviceListener {
    /** Left, low-frequency motor. */
    private static final int MOTOR_STRONG = 0;
    /** Right, high-frequency motor. */
    private static final int MOTOR_WEAK = 1;

    private final InputManager inputManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Vibrator[] motors = new Vibrator[0];
    /** Gamepad that produced the last input event; -1 means "any gamepad with motors". */
    private int deviceId = -1;
    private boolean open;

    public GamepadRumble(Context context) {
        this(context, true);
    }

    private GamepadRumble(Context context, boolean watchDevices) {
        Context app = context.getApplicationContext();
        InputManager found = null;
        try {
            found = (InputManager) (app != null ? app : context)
                    .getSystemService(Context.INPUT_SERVICE);
        } catch (RuntimeException ignored) {
        }
        inputManager = found;
        open = true;
        if (watchDevices && inputManager != null) {
            try {
                inputManager.registerInputDeviceListener(this, main);
            } catch (RuntimeException ignored) {
                // OEM builds have been seen rejecting the listener; rumble still works event-driven.
            }
        }
    }

    /** Tells the bridge which pad the player is holding (the one sending input events). */
    public void setActiveDevice(int id) {
        if (id == deviceId) return;
        deviceId = id;
        resolve();
    }

    public boolean hasMotor() {
        if (motors.length == 0) resolve();
        return motors.length > 0;
    }

    /**
     * Plays one rumble pulse.
     *
     * @param strong left-motor strength, 0..255 (PS4's large motor)
     * @param weak   right-motor strength, 0..255 (PS4's small motor)
     * @param ms     pulse length in milliseconds; clamped to 40..1000
     */
    public void play(int strong, int weak, int ms) {
        if (strong <= 0 && weak <= 0) {
            stop();
            return;
        }
        if (!open) return;
        if (motors.length == 0) resolve();
        if (motors.length == 0) return;
        int duration = Math.max(40, Math.min(1000, ms));
        Vibrator left = motors[MOTOR_STRONG];
        // A single-motor pad combines both channels, like the old combined vibrator did.
        if (motors.length == 1) {
            playOne(left, Math.max(strong, weak), duration);
            return;
        }
        if (strong > 0) playOne(left, strong, duration);
        if (weak > 0) playOne(motors[MOTOR_WEAK], weak, duration);
    }

    public void stop() {
        for (Vibrator motor : motors) {
            try {
                motor.cancel();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Stops polling/effects and unhooks the device listener. */
    public void close() {
        stop();
        detach();
    }

    private void detach() {
        open = false;
        if (inputManager != null) {
            try {
                inputManager.unregisterInputDeviceListener(this);
            } catch (RuntimeException ignored) {
            }
        }
        motors = new Vibrator[0];
    }

    // ---- device resolution ----

    private void resolve() {
        if (!open) return;
        Vibrator[] found = deviceId >= 0 ? motorsOf(deviceId) : null;
        if (found == null && inputManager != null) {
            try {
                for (int id : inputManager.getInputDeviceIds()) {
                    InputDevice device = inputManager.getInputDevice(id);
                    if (device == null || !isGamepad(device)) continue;
                    found = motorsOf(id);
                    if (found != null) break;
                }
            } catch (RuntimeException ignored) {
            }
        }
        motors = found == null ? new Vibrator[0] : found;
    }

    private static boolean isGamepad(InputDevice device) {
        int sources = device.getSources();
        return (sources & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK)) != 0;
    }

    private Vibrator[] motorsOf(int id) {
        if (inputManager == null) return null;
        InputDevice device;
        try {
            device = inputManager.getInputDevice(id);
        } catch (RuntimeException ignored) {
            return null;
        }
        if (device == null || !isGamepad(device)) return null;
        // Controller motors are only exposed as of Android 12 (API 31).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
        return motorsFromManager(device);
    }

    /** Prefers the per-motor Manager (two motors); falls back to the combined vibrator. */
    private static Vibrator[] motorsFromManager(InputDevice device) {
        try {
            VibratorManager manager = device.getVibratorManager();
            if (manager != null) {
                int[] ids = manager.getVibratorIds();
                Vibrator[] found = new Vibrator[ids.length];
                int count = 0;
                for (int id : ids) {
                    // Si el sistema lista el motor, se usa: en algunas ROMs hasVibrator()
                    // devuelve false para vibradores de InputDevice aunque existan.
                    Vibrator motor = manager.getVibrator(id);
                    if (motor != null) found[count++] = motor;
                }
                if (count > 0) return java.util.Arrays.copyOf(found, count);
            }
            // Some single-motor pads only populate the combined vibrator.
            Vibrator combined = device.getVibrator();
            if (combined != null && combined.hasVibrator()) return new Vibrator[]{combined};
        } catch (RuntimeException ignored) {
            // UnsupportedOperationException/IllegalStateException on some vendor builds.
        }
        return null;
    }

    private static void playOne(Vibrator motor, int amplitude, int ms) {
        int level = Math.max(1, Math.min(255, amplitude));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int effect = motor.hasAmplitudeControl() ? level : VibrationEffect.DEFAULT_AMPLITUDE;
                motor.vibrate(VibrationEffect.createOneShot(ms, effect));
            } else {
                motor.vibrate(ms);
            }
        } catch (SecurityException ignored) {
            // android.permission.VIBRATE is declared in the manifest; never take the game down.
        } catch (RuntimeException ignored) {
        }
    }

    // ---- test pulse (Settings -> Button mapping / in-game drawer) ----

    /**
     * Buzzes the connected gamepad for a moment so the player can check it works.
     *
     * @return false when there is no gamepad with motors (caller shows a hint)
     */
    public static boolean playTest(Context context, int strong, int weak, int ms) {
        GamepadRumble probe = new GamepadRumble(context, false);
        boolean available = probe.hasMotor();
        if (available) probe.play(strong, weak, ms);
        // Detach the (unused) listener without cancelling the one-shot pulse already started.
        probe.detach();
        return available;
    }

    // ---- InputManager.InputDeviceListener ----

    @Override public void onInputDeviceAdded(int id) {
        // A pad connected while the game runs: pick it up if we have none.
        if (motors.length == 0) resolve();
    }

    @Override public void onInputDeviceRemoved(int id) {
        if (id == deviceId) {
            deviceId = -1;
            motors = new Vibrator[0];
            resolve();
        } else if (motors.length == 0) {
            resolve();
        }
    }

    @Override public void onInputDeviceChanged(int id) {
        if (id == deviceId || motors.length == 0) resolve();
    }
}
