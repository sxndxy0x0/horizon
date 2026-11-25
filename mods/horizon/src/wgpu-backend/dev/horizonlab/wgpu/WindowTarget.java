package dev.horizonlab.wgpu;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.lwjgl.sdl.SDLMetal;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

/**
 * An SDL window as the Rust side takes it (its {@code WindowTarget}): the platform's own handles, read from SDL's
 * window properties. Win32 (HWND, HINSTANCE), X11 (window, Display*), Wayland (wl_surface*, wl_display*), or on
 * macOS an NSView with a Metal layer that SDL makes for the window.
 */
final class WindowTarget {
    private WindowTarget() {}

    /** The description, in {@code arena}; NULL when SDL gives no handle this backend knows. */
    static MemorySegment of(long window, Arena arena) {
        int props = SDLVideo.SDL_GetWindowProperties(window);
        int kind;
        long a;
        long b;
        long hwnd = SDLProperties.SDL_GetPointerProperty(props, SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
        long wayland = SDLProperties.SDL_GetPointerProperty(props,
                SDLVideo.SDL_PROP_WINDOW_WAYLAND_SURFACE_POINTER, 0L);
        long x11 = SDLProperties.SDL_GetNumberProperty(props, SDLVideo.SDL_PROP_WINDOW_X11_WINDOW_NUMBER, 0L);
        if (hwnd != 0L) {
            kind = 1;
            a = hwnd;
            b = SDLProperties.SDL_GetPointerProperty(props, SDLVideo.SDL_PROP_WINDOW_WIN32_INSTANCE_POINTER, 0L);
        } else if (wayland != 0L) {
            kind = 3;
            a = wayland;
            b = SDLProperties.SDL_GetPointerProperty(props, SDLVideo.SDL_PROP_WINDOW_WAYLAND_DISPLAY_POINTER, 0L);
        } else if (x11 != 0L) {
            kind = 2;
            a = x11;
            b = SDLProperties.SDL_GetPointerProperty(props, SDLVideo.SDL_PROP_WINDOW_X11_DISPLAY_POINTER, 0L);
        } else if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            kind = 4;
            a = SDLMetal.SDL_Metal_CreateView(window);
            b = 0L;
        } else {
            return MemorySegment.NULL;
        }
        if (a == 0L) {
            return MemorySegment.NULL;
        }
        MemorySegment t = arena.allocate(24, 8);
        t.set(ValueLayout.JAVA_INT, 0, kind);
        t.set(ValueLayout.JAVA_LONG, 8, a);
        t.set(ValueLayout.JAVA_LONG, 16, b);
        return t;
    }
}
