# 0005 — Verificador web con QR

Fecha: 2026-10-26. Estado: aceptada. **Implementada** (2026-10-26): librería
`prover/verifier`, servidor `prover/verifier-web` y escaneo y envío en la app. Probada con
una cédula real en el S25: el verificador aceptó la prueba.
Implementa la presentación de la [0004](0004-prove-once-present-many.md).

## Contexto

La prueba guardada pesa 223.770 B y la firma ML-DSA-65 3.309 B. Un QR versión 40 lleva
como máximo 2.953 B en modo binario, así que la prueba no cabe en un QR: hay que mandarla
por la red.

Opciones que se consideraron:

| Opción | Por qué no |
| --- | --- |
| CLI con archivo exportado | El desafío se copia a mano; como demo convence poco. Queda como herramienta de prueba |
| Teléfono a teléfono por NFC o Bluetooth | La más compleja, y 224 KB por NFC es lento |

## Decisión

1. El verificador es un servidor web. Su página muestra un QR con su origen y un desafío
   aleatorio de 32 bytes.
2. La app escanea el QR, muestra a quién le va a responder y, si la persona acepta, firma
   el mensaje de presentación con la clave del teléfono y envía por HTTPS la prueba, la
   clave pública y la firma a ese origen.
3. El servidor verifica y la página muestra el resultado.

El servidor usa HTTPS con un certificado autofirmado que genera al arrancar, y el QR lleva
su SHA-256: la app confía sólo en ese certificado (lo fija), no en una CA. Así no hace
falta HTTP sin cifrar, que Android bloquea por defecto. El QR también lleva una sesión, y
la página consulta su resultado; cada sesión dura 5 minutos y se responde una vez.

El mensaje que se firma es `"zkage-presentation-v1" || 0x00 || u16 largo del origen ||
origen || desafío`. El origen va firmado para que un sitio malicioso no pueda reenviar el
QR de otro sitio y usar la respuesta (ataque de relevo): el servidor rechaza firmas hechas
para otro origen.

El núcleo es una librería de Rust que comprueba, en orden:

1. el recibo STARK contra el image ID del programa del proyecto;
2. que el hash de la CSCA del journal sea de una CSCA de Chile;
3. que la edad mínima del journal sea al menos la que pide el verificador;
4. que la fecha del journal no sea futura ni más antigua que la vigencia del verificador
   (12 meses por defecto, como sugiere la 0004);
5. que el SHA-256 de la clave pública coincida con el del journal;
6. la firma ML-DSA-65 del mensaje de presentación.

La usan el servidor y, para probarlo sin teléfono, el ejemplo `mock_presentation`
(arma la parte del teléfono con la cédula de prueba).

## Costo aceptado

- Para la demo el servidor corre en el computador. En vez de la misma red Wi-Fi, que en
  algunas redes da IPs públicas en subredes distintas, el teléfono lo alcanza por USB con
  `adb reverse tcp:8443 tcp:8443` y el servidor escucha sólo en `127.0.0.1`. Publicarlo en
  internet tiene costo y queda fuera por ahora.
- El navegador advierte que el certificado es autofirmado; la app no, porque lo fija.
- El escáner de QR es el de Google Play Services (sin permiso de cámara): la app depende
  de un componente que no es open source.
- Si la app sube a `targetSdk` 37, Android 17 le exige el permiso de red local para hablar
  con un servidor de la misma red.
- Las presentaciones siguen siendo enlazables entre verificadores (0004).
