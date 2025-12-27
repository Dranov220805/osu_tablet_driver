
# osu_tablet_driver

## Purpose

osu_tablet_driver is an Android application built with Kotlin that allows users to turn their Android device into a drawing tablet. This is especially useful for digital artists or rhythm game players who want to use their Android device as an input device for their PC.

## How It Works

- The app captures touch input from your Android device and transmits it to your PC over a USB connection.
- **USB Debugging** must be enabled on your Android device for the app to function.
- The app communicates with a server running on your PC, which receives the input data and emulates a drawing tablet or mouse input.
- This setup allows you to use your Android device as a drawing tablet in applications such as osu! or digital art software.

## Requirements

- An Android device with USB Debugging enabled.
- This repository (osu_tablet_driver) for the Android app.
- The companion server application running on your PC: [osu_tablet_server](https://github.com/Dranov220805/osu_tablet_server)

## Setup Instructions

1. **Enable USB Debugging** on your Android device:
	- Go to Settings > About phone > Tap 'Build number' 7 times to enable Developer Options.
	- Go to Developer Options and enable 'USB Debugging'.
2. **Build and install** the osu_tablet_driver app on your Android device.
3. **Download and run** the [osu_tablet_server](https://github.com/Dranov220805/osu_tablet_server) on your PC.
4. **Connect** your Android device to your PC via USB.
5. **Launch** the osu_tablet_driver app and follow the on-screen instructions to start using your device as a drawing tablet.

## References

- [osu_tablet_server (PC server)](https://github.com/Dranov220805/osu_tablet_server)
- [Android Developer Documentation - USB Debugging](https://developer.android.com/studio/debug/dev-options)
- [Kotlin Programming Language](https://kotlinlang.org/)

---

Feel free to contribute or open issues for suggestions and bug reports!
