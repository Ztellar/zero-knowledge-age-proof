# Etapa 2 — Medición en Android

Fecha: 2026-10-22. Teléfono: Samsung Galaxy S25 (SM-S931B), Snapdragon SM8750, 8 núcleos,
11,4 GB de RAM (`MemTotal` 11.380.220 kB), Android 16, sólo CPU. RISC Zero 3.0.6 con el
probador dentro del proceso (feature `prove` del host), porque en Android no hay `r0vm`.
NDK de Homebrew, API 29.

## Qué se prueba

Lo mismo que en la [etapa 1](stage-1-desktop.md), con el guest ya optimizado: la cédula de
prueba, edad de 18 años o más el 2026-10-22, documento vigente. Mismos ciclos que en el
Mac: 198.377 del programa, 262.144 en total, un segmento.


Para reproducirla: la cédula de prueba por omisión ahora usa los tamaños de clave reales
(3072/2048); esta medición usó `gen.py --csca-bits 4096 --dsc-bits 4096`. Ver
[tamaños de clave](chile-key-sizes.md).

## Resultados

| Recibo | Corrida | Tiempo de prueba | RAM máxima | Tamaño | Verificación |
| --- | --- | --- | --- | --- | --- |
| Sucinto | 1 | 64,70 s | 2,34 GB | 223.514 B | 16,7 ms |
| Sucinto | 2 | 64,98 s | 2,47 GB | 223.514 B | 17,5 ms |
| Compuesto | 1 | 46,93 s | 2,47 GB | 256.250 B | 20,2 ms |

GB = 10^9 bytes, igual que en la etapa 1. `VmHWM` viene en KiB: 2.288.176 KiB × 1.024 =
2.343.092.224 B; 2.408.192 KiB = 2.465.988.608 B; 2.409.048 KiB = 2.466.865.152 B. En el
Mac el sucinto llegó a 2.571.370.496 B, sumando el host y `r0vm`. Las corridas fueron
seguidas, con el teléfono conectado por USB y la pantalla encendida.

Cómo se midió: [el script de medición](../../prover/scripts/android-measure.sh) compila el host para `aarch64-linux-android`, lo copia con las fixtures por `adb` y lee
`VmHWM` del proceso cada 0,5 s. `VmHWM` es el máximo que guarda el kernel, así que la
última lectura es el pico.

## Lectura

- **RISC Zero compila y corre en Android sin cambios.** No hace falta Plonky3.
- **El teléfono tarda unas 2 veces lo del Mac:** 64,7 s / 32,8 s = 1,97 en la sucinta;
  46,9 s / 22,8 s = 2,06 en la compuesta.
- **La memoria cabe, pero justa.** Antes de la prueba Android reportaba `MemAvailable`
  2.565.356 kB (2,6 GB) y el proceso llegó a 2,47 GB sin que el sistema lo matara. Con
  más apps abiertas o en un teléfono de 6–8 GB podría morir: hay que probarlo dentro de la
  app real.
- **La compuesta es 18 s más rápida** pero pesa 32.736 bytes más (256.250 − 223.514).
  Para registrar una cédula una sola vez, la diferencia de tamaño no importa; la de tiempo
  sí se nota.
- **No se midió el calor ni la batería.** Leer la temperatura de la batería pide permisos
  que `adb shell` no tiene. Tampoco se midió con la pantalla apagada ni con la app en
  segundo plano, donde Android puede frenar el proceso.
