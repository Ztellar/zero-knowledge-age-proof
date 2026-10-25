# Etapa 1 — Medición en el computador

Fecha: 2026-10-22. Máquina: MacBook Pro M1 Pro, 16 GB de RAM, sólo CPU. RISC Zero 3.0.6
(`r0vm` local, sin Bonsai). Toolchain del guest: Rust 1.97.0 (rzup); host: Rust 1.99.1.

## Qué se prueba

El guest ([`prover/methods/guest`](../../prover/methods/guest/src/main.rs)) corre `age_core::verify` sobre una cédula de prueba
generada por [el generador de fixtures](../../prover/fixtures/gen.py):

- CSCA → DSC → SOD, ambas firmas RSA 4096 con SHA-256 (PKCS#1 v1.5);
- hash de DG1 dentro del SOD;
- edad de 18 años o más el 2026-10-22, y documento vigente.

`rsa` 0.9.9 y `sha2` 0.10.9 usan los forks acelerados de RISC Zero (verificado en
[`prover/methods/guest/Cargo.lock`](../../prover/methods/guest/Cargo.lock)). Ciclos del programa: **975.995**.

## Resultados iniciales

Primera versión: ASN.1 con `cms` y `x509-cert`, entradas con el serializador por defecto.

| Recibo | Segmento máximo | Segmentos | Ciclos totales | Tiempo de prueba | RAM máxima | Tamaño | Verificación |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Compuesto | por defecto (2^20) | 2 | 1.179.648 | 134,6 s | 4,06 GB | 525.852 B | 31,0 ms |
| Sucinto | por defecto (2^20) | 2 | 1.179.648 | 151,6 s | 6,60 GB | 223.514 B | 12,7 ms |
| Sucinto | 2^18 | 6 | 1.441.792 | 236,4 s | 3,35 GB | 223.514 B | no medida |
| Sucinto | 2^16 | 50 | 3.244.032 | 1.274,0 s | 1,75 GB | 223.514 B | no medida |

Cómo se midió: [`prover/host`](../../prover/host/src/main.rs) con `SEGMENT_PO2` y el modo como argumento, bajo
`/usr/bin/time -l`. La RAM es el "maximum resident set size" que reporta `time`, que
incluye al proceso `r0vm`; un muestreo aparte de `r0vm` cada 0,5 s dio 6.297 MB para el
sucinto por defecto, consistente con la tabla. Una sola corrida por fila, sin repetir.

## Después de optimizar

Contando ciclos por paso dentro del guest (`env::cycle_count`) se vio que las firmas RSA no
eran el problema:

| Paso | Antes | Después | Cambio |
| --- | --- | --- | --- |
| Leer las entradas | 565.943 | 7.641 | Bytes crudos con prefijo de largo en vez del serializador por defecto, que gasta una palabra de 32 bits por byte |
| Interpretar SOD y certificados, construir claves | ~240.000 | 21.080 | Lector DER mínimo sin copias ([der_walk](../../prover/core/src/der_walk.rs)) en vez de decodificar todo; claves RSA desde módulo y exponente |
| Firma DSC (RSA 4096) | ~75.000 | 77.050 | Sin cambio: es el piso |
| Firma SOD (RSA 4096) | 81.139 | 74.981 | Sin cambio |
| Hashes, edad y vencimiento | 13.015 | 6.364 | — |
| **Total del programa** | **983.689** | **198.377** | 5,0 veces menos |

Con la paginación (49.998 ciclos) el total cabe en un segmento de 2^18 (262.144). Con
212.432 ciclos no cabía y se rellenaba hasta 2^19: ese último ajuste partió el tiempo
a la mitad.

| Recibo | Segmentos | Ciclos totales | Tiempo de prueba | RAM máxima | Tamaño | Verificación |
| --- | --- | --- | --- | --- | --- | --- |
| Sucinto | 1 | 262.144 | **32,8 s** | **2,57 GB** | 223.514 B | 12,9 ms |
| Compuesto | 1 | 262.144 | 22,8 s | 2,49 GB | 256.250 B | 14,1 ms |

Mismo método que la tabla inicial: una corrida por fila.

Aviso: el lector DER propio no valida estructuras completas, sólo ubica campos. Es seguro
porque todo lo que entrega queda cubierto por una firma que se verifica después, pero es
código criptográfico sensible y debe revisarse antes de usarlo con algo real.

## Lectura

- **Achicar segmentos rinde cada vez menos.** De 2^20 a 2^18 baja la RAM un 49% a cambio
  de un 56% más de tiempo. De 2^18 a 2^16 la baja un 48% pero el tiempo sube 5,4 veces.
  Los ciclos de relleno crecen de 1,18 a 3,24 millones.
- **La prueba sucinta pesa 218 KiB.** Un QR binario admite unos 2.950 bytes: no cabe en
  uno.
- **Para un teléfono, después de optimizar:** 2,6 GB de RAM y 33 s en este Mac. Un teléfono
  de gama alta (12 GB) debería poder; uno de 4 GB queda justo. Se confirma en la etapa 2.
