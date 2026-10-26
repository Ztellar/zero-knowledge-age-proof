# Active Authentication dentro de la prueba — Medición

Fecha: 2026-10-26. Programa con la comprobación 6 de la
[decisión 0006](../decisions/0006-active-authentication-in-proof.md): DG15 contra el SOD y
firma ECDSA P-256 del chip sobre los primeros 8 bytes de `device_key_sha256`. Cédula de
prueba de [`gen.py`](../../prover/fixtures/gen.py) (CSCA RSA 3072, DSC RSA 2048, chip P-256).
Una corrida por caso.

## Ciclos

`cargo run --release -p host -- execute`, que cuenta los ciclos de cada paso con
`env::cycle_count`:

| Paso | Ciclos |
| --- | --- |
| Arranque y lectura de los inputs | 8.671 |
| Parseo y claves | 15.574 |
| Firma del DSC (RSA 3072) | 65.081 |
| Firma del SOD (RSA 2048) | 53.318 |
| messageDigest y hash de DG1 | 5.193 |
| Edad y vencimiento | 1.622 |
| **Active Authentication (P-256)** | **217.412** |
| Fuera de los pasos: registros de ciclos, journal y cierre (379.900 − 366.871) | 13.029 |
| **Total del programa** | **379.900** |

El guest usa el `p256` 0.13.2 de RISC Zero (`p256/v0.13.2-risczero.1` en
`methods/guest/Cargo.lock`), que acelera la aritmética de la curva. Aun así la firma P-256
cuesta más que las dos RSA juntas (65.081 + 53.318 = 118.399). Antes el programa usaba
161.167 ciclos y cabía en un segmento de 2^18 = 262.144; ahora no.

## Mac

Apple Silicon, `cargo run --release -p host -- succinct`. Sin la comprobación: 31,72 s, un
segmento de 2^18 (medición del commit `5f6cb3d`).

| Segmento | Segmentos | Tiempo | RAM máxima | Recibo | Verificación |
| --- | --- | --- | --- | --- | --- |
| 2^19 | 1 | 59,52 s | 2,62 GB | 223.778 B | 12,52 ms |
| 2^18 | 3 | 97,98 s | 2,34 GB | 223.778 B | 12,60 ms |

RAM máxima de `/usr/bin/time -l` (maximum resident set size, en bytes): 2.624.421.888 y
2.343.419.904.

## Galaxy S25

Con [el script de medición](../../prover/scripts/android-measure.sh), por `adb`, una
corrida tras otra en el mismo orden de la tabla. `MemTotal` 11.380.220 kB; antes de
empezar `MemAvailable` era 3.793.476 kB. RAM máxima = `VmHWM` × 1.024.

| Segmento | Segmentos | Tiempo | RAM máxima | Recibo | Verificación |
| --- | --- | --- | --- | --- | --- |
| 2^19 | 1 | 128,59 s | 4,41 GB (4.309.936 KiB) | 223.778 B | 21,17 ms |
| 2^18 | 3 | 195,40 s | 2,47 GB (2.412.972 KiB) | 223.778 B | 17,55 ms |
| 2^17 | 6 | 366,12 s | 1,52 GB (1.480.312 KiB) | 223.778 B | 20,33 ms |

Sin la comprobación, la misma medición daba 64,70 s y 2,34 GB con un segmento de 2^18
([etapa 2](stage-2-android.md)). Con 2^19 el tiempo es 128,59 / 64,70 = 1,99 veces el de
antes.

## Lectura

- **El recibo y la verificación no cambian.** Presentar la prueba cuesta lo mismo.
- **El tamaño del segmento intercambia tiempo por memoria**, sin tocar la seguridad. La app
  elige el mayor cuyo pico quepa en el 40% de la RAM del teléfono (`SegmentSize`). El 40%
  es un criterio, no una medición. Con lo medido: 12 GB → 2^19, 8 GB → 2^18, 6 GB o menos
  → 2^17.
- **No se midió en un teléfono de gama media ni antiguo.** Con un procesador más lento, 2^17
  puede pasar con holgura de los 6 minutos del S25.
- **Las corridas fueron seguidas**, así que las últimas pudieron sufrir más por el calor del
  teléfono.
- **El límite de memoria por app de Android 17 sigue sin medirse**, y 4,41 GB es el pico
  más alto que ha tenido el proyecto.
