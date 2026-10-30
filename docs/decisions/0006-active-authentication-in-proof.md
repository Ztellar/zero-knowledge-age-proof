# 0006 — Active Authentication dentro de la prueba

Fecha: 2026-10-26. Estado: aceptada. **Implementada** (2026-10-26) y probada con la cédula
de prueba en el Mac y en el S25. **Probada con la cédula real** (S25, 2026-10-29): prueba
con el chip autenticado en 92,5 s con segmento de 2^19, 223.778 B, aceptada por el
verificador web ("…con el chip de la cédula presente"). Complementa la
[0004](0004-prove-once-present-many.md).

## Contexto

La prueba demuestra que el Registro Civil firmó los datos de la cédula, pero no que el chip
estuviera presente al generarla. DG1 y el SOD son archivos que se pueden copiar: quien leyó
una cédula una vez (con la MRZ a la vista) puede generar la prueba de esa persona en otro
teléfono. La auditoría de Self les atribuye esa misma debilidad a ellos (no verificado: el
sitio de zkSecurity da error de certificado).

Active Authentication (ICAO 9303 parte 11, sección 6.1) la cierra: el chip firma un desafío
de 8 bytes con una clave privada que nunca sale de él, y la clave pública está en DG15, que
el SOD firma.

Lo que trae la cédula real usada en las pruebas (S25, 2026-10-26, leída con la app):

| Dato | Valor |
| --- | --- |
| Data groups con Active Authentication y Chip Authentication | DG15 y DG14, los dos en el SOD |
| Hash de DG15 contra el SOD | Coincide |
| Clave de DG15 | EC 256 bits, curva prime256v1 (P-256) |
| Algoritmo según DG14 | `0.4.0.127.0.7.1.1.4.1.3`, ecdsa-plain-SHA256 |
| Respuesta a un desafío aleatorio | 64 B (r‖s), firma válida |

Chip Authentication (DG14) no sirve para esto: establece un canal cifrado con el chip y no
deja una firma que se pueda verificar después, dentro de la prueba.

## Decisión

1. Antes de leer la cédula, la app elige la clave ML-DSA del teléfono que va a usar la
   prueba (hoy se crea después, en `ProofJob`). El desafío de Active Authentication son los
   primeros 8 bytes de `device_key_sha256`.
2. El programa del zkVM recibe además DG15 y la respuesta del chip, y comprueba:
   - que el hash de DG15 sea el que firma el SOD;
   - que la firma ECDSA P-256 con SHA-256 de la respuesta sea válida para esos 8 bytes de
     `device_key_sha256`, que ya están en el journal.
3. El journal gana un booleano `chip_authenticated`; la clave de DG15 no sale de la prueba
   (identifica a la cédula). El verificador puede exigirlo en su `Policy`, y el verificador
   web lo exige salvo con `--allow-no-chip`. Una cédula sin DG15, o con una clave que no sea
   P-256, sigue pudiendo probar, con `chip_authenticated = false`. **Desde el 2026-10-29 la
   app ya no genera esas pruebas: la [0009](0009-chip-must-be-original.md) la detiene antes.**
4. El programa ya no cabe en un segmento de 2^18. La app elige el tamaño del segmento según
   la RAM del teléfono (`SegmentSize`): el más grande cuyo pico medido quepa en el 40% de la
   RAM. Es un criterio propio, no una medición.

Así el chip firma qué teléfono va a presentar la prueba: una copia de los archivos no
alcanza para generarla.

## Costo aceptado

- **El ligado es de 64 bits**, porque ICAO fija el desafío en 8 bytes. Reutilizar la
  respuesta de un chip con otra clave de teléfono exige una clave cuyo SHA-256 empiece con
  los mismos 8 bytes: del orden de 2^64 intentos.
- **Prueba la presencia del chip, no de su dueño.** Quien tenga la cédula en la mano un
  momento puede generar una prueba en su teléfono. Comparar la cara es otra decisión
  (el punto 3, pendiente).
- **P-256 no es post-cuántico**, igual que el RSA del Registro Civil: es de la cédula y no
  se puede cambiar. Queda en el mapa post-cuántico del README.
- **La prueba tarda el doble y pide más memoria**
  ([medición](../measurements/active-auth.md)). La firma P-256 cuesta 217.412 ciclos, aun
  con el `p256` acelerado de RISC Zero, y el programa sube de 161.167 a 379.900. En el S25,
  con un segmento de 2^19, son 128,6 s y 4,41 GB, contra 64,7 s y 2,34 GB de antes. Un
  teléfono con menos RAM usa segmentos más chicos: 2^17 baja a 1,52 GB pero tarda 366,1 s
  en el S25. Como la prueba se hace una vez (0004), se acepta. El recibo y la verificación
  no cambian.
- La cédula de prueba de los tests necesita DG15 y una clave P-256 propias
  ([el generador de la cédula de prueba](../../prover/fixtures/gen.py)).
