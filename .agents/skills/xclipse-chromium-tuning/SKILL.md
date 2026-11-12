---
name: xclipse-chromium-tuning
description: >-
  Actionable guide for configuring hardware-accelerated graphics (RADV Xclipse / sgpu)
  and high-performance WebGL / Chromium tuning on Wayland inside PRoot Linux.
---

# Xclipse GPU & Chromium Wayland Tuning Guide

This skill provides reference configurations and troubleshooting steps for running hardware-accelerated Linux applications (specifically Chromium, WebGL, and Vulkan) on Samsung Exynos devices with AMD RDNA GPUs (Xclipse 920, 940, 950) nested within a mobile Wayland compositor.

---

## 1. Hardware & Driver Architecture

### Device Detection
- **Target Hardware:** Samsung Exynos 2200 / 2400 / 2400e / 2500 (`s5e9925`, `s5e9945`, `s5e9955`).
- **GPU Node:** `/dev/dri/renderD128` (and card nodes) mapped via the Samsung `sgpu` kernel driver.
- **Physical Driver:** `radv-xclipse` (community glibc ARM64 build of Mesa RADV with Xclipse support).
  - Staged at: `/usr/local/lib64/libvulkan_radeon.so`
  - ICD Manifest: `/usr/local/share/vulkan/icd.d/radeon_xclipse_icd.aarch64.json`

### Environment Variables Checklist
```bash
# Vulkan Driver Selection
export VK_ICD_FILENAMES=/usr/local/share/vulkan/icd.d/radeon_xclipse_icd.aarch64.json
export VK_DRIVER_FILES=/usr/local/share/vulkan/icd.d/radeon_xclipse_icd.aarch64.json

# Mesa WSI for Wayland shared memory (wl_shm)
export MESA_VK_WSI_DEBUG=sw
export MESA_VK_WSI_PRESENT_MODE=mailbox
export vblank_mode=0
export MESA_NO_ERROR=1
export RADV_PERFTEST=gpl,nggc

# Patched Mesa Zink (OpenGL-over-Vulkan)
export MESA_LOADER_DRIVER_OVERRIDE=zink
export GALLIUM_DRIVER=zink
export LIBGL_DRIVERS_PATH=/usr/local/omalauncher-mesa/lib/dri
export __EGL_VENDOR_LIBRARY_FILENAMES=/usr/local/share/glvnd/egl_vendor.d/50_mesa.json
```

---

## 2. Chromium Launch Configuration

### High-Performance Command Line
```bash
exec chromium \
    --no-sandbox \
    --ozone-platform=wayland \
    --ignore-gpu-blocklist \
    --enable-gpu-rasterization \
    --enable-zero-copy \
    --enable-webgl \
    --enable-accelerated-2d-canvas \
    --num-raster-threads=4 \
    --disable-gpu-watchdog \
    --use-gl=angle \
    --use-angle=vulkan \
    --enable-features=CanvasOopRasterization,TouchpadOverscrollHistoryNavigation \
    --disable-dev-shm-usage \
    "$@"
```

### Critical Rules & Pitfalls
1. **Never use `--in-process-gpu`:**
   In Chromium's Ozone Wayland implementation, `--in-process-gpu` triggers a fatal crash assertion (`FATAL: in-process-gpu is not supported with ozone/wayland`).
2. **Never hardcode `--force-device-scale-factor`:**
   If the mobile Wayland compositor operates at scale 2, setting `--force-device-scale-factor=1.5` causes a buffer mismatch ($1.5 / 2.0 = 75\%$), leaving 25% black empty space on the display.
3. **Use ANGLE Vulkan (`--use-angle=vulkan`):**
   - `--use-angle=gl` incurs double translation: $\text{WebGL} \to \text{ANGLE GLES} \to \text{Desktop GL} \to \text{Zink} \to \text{Vulkan}$ (scores ~160 in Basemark Web).
   - `--use-angle=vulkan` maps WebGL directly to Vulkan command buffers executed by the Xclipse GPU (scores 677+).
4. **Do NOT pass `--enable-features=Vulkan` unconditionally:**
   Wayland `wl_shm` compositors do not expose Vulkan display extensions. Enabling the Vulkan display compositor causes Chromium to fail window presentation and fall back to software compositing. Let ANGLE handle Vulkan for WebGL while the window presentation stays on EGL/shm.

---

## 3. Verifying `chrome://gpu`

Expected status for full hardware acceleration on Xclipse:
- **Canvas:** `Hardware accelerated` (Green)
- **Compositing:** `Hardware accelerated` or `Hardware accelerated on all pages`
- **Rasterization:** `Hardware accelerated on all pages` (Green)
- **OpenGL:** `Enabled` (Green)
- **WebGL / WebGL2:** `Hardware accelerated` (Green)
- **GL Implementation:** `ANGLE (Vulkan)`
