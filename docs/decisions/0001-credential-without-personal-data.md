# 0001 — Credencial sin datos personales, atada a un teléfono

Fecha: 2026-10-22. Estado: en pausa desde la [decisión 0003](0003-android-app-post-quantum-proof.md). **No implementada.**

## Decisión

Tras leer el carnet, el teléfono guarda sólo una prueba de "mayor de 18 en la fecha D"
atada a una clave no extraíble del chip de seguridad del teléfono. No guarda nombre, RUT,
fecha de nacimiento ni foto, y ningún servidor los recibe. Cada uso genera una prueba nueva
que el sitio no puede vincular con otros usos. Un mismo carnet sólo tiene un teléfono activo
y la credencial se revalida periódicamente.

## Enmienda (2026-10-22): comparación de cara

Al registrarse, el teléfono lee también la foto del chip y la compara con la cara de la
persona frente a la cámara. Las dos imágenes se descartan al terminar y nunca salen del
teléfono. Motivo: ZKPassport lo hace y, sin esto, cualquiera puede registrar un carnet
prestado.

Es un dato biométrico (art. 16 ter de la Ley 21.719). Exige consentimiento expreso (art. 16)
e informar antes cuatro cosas: qué sistema biométrico se usa, para qué, durante cuánto
tiempo y cómo ejercer los derechos. Eso es una pantalla de consentimiento antes de la
cámara. Si el tratamiento dentro del teléfono nos hace responsables sigue sin resolverse
(ver decisión 0002); se aplica la regla igual, por prudencia.

## Por qué

- El requisito es que ningún dato personal llegue a terceros, incluidos proveedores
  extranjeros: el diseño lo impide por construcción, no por política.
- Una flag sola (`mayor_de_18 = true`) se falsifica modificando la app; con su prueba
  y la clave del teléfono, no.
- La edad sólo aumenta: "mayor de 18 en la fecha D" sigue siendo cierto después, así que la
  prueba no vence por edad, sólo por la revalidación o el vencimiento del carnet.

## Descartado

- **Consultar al Registro Civil en línea:** exige un convenio, el Estado vería cada sitio
  visitado y depende de que su sistema esté arriba. La firma del chip ya prueba el dato.
- **Guardar los datos del carnet cifrados en el teléfono:** sigue habiendo datos personales
  almacenados.
- **Cero almacenamiento (apoyar el carnet en cada uso):** demasiada fricción.

## Costo aceptado

"Un carnet, un teléfono" necesita un registro compartido de identificadores de documento.
No contiene datos personales, pero deja de ser un sistema sin ningún componente central.
Dónde vive ese registro se decidió en [la decisión 0002](0002-device-registry.md).
