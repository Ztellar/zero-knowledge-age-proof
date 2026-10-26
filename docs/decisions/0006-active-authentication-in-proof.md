# 0006 — Active Authentication dentro de la prueba

Fecha: 2026-10-26. Estado: propuesta. **No implementada**: hoy la app sólo comprueba Active
Authentication fuera de la prueba, para mostrarlo en la pantalla de lectura. Complementa la
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
   (identifica a la cédula). El verificador puede exigirlo en su `Policy`. Una cédula sin
   DG15 sigue pudiendo probar, con `chip_authenticated = false`.

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
- **Ciclos sin medir.** RISC Zero acelera P-256 con su versión de `p256` 0.13.2
  ([precompiles](https://dev.risczero.com/api/zkvm/precompiles)). El programa usa hoy
  161.167 ciclos y un segmento de 2^18 (262.144); con la paginación, que en la etapa 1 midió
  49.998, quedan unos 51.000 libres. Si la verificación ECDSA no cabe, la prueba pasa a 2^19
  y en la etapa 1 eso duplicó el tiempo ([mediciones](../measurements/stage-1-desktop.md)).
  Se mide antes de dar la decisión por buena.
- La cédula de prueba de los tests necesita DG15 y una clave P-256 propias
  ([el generador de la cédula de prueba](../../prover/fixtures/gen.py)).
