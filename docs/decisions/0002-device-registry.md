# 0002 — Registro de teléfonos propio, sin blockchain, revalidación anual

Fecha: 2026-10-22. Estado: en pausa desde la [decisión 0003](0003-android-app-post-quantum-proof.md). **No implementada.** Complementa la
[decisión 0001](0001-credential-without-personal-data.md).

## Decisión

- El registro "un carnet, un teléfono" vive en un servidor propio, no en una blockchain.
- El código por carnet se deriva con OPRF: el servidor nunca ve el carnet y el código no se
  puede calcular sin la clave secreta del servidor y el carnet físico a la vez.
- Cambiar de teléfono: con el teléfono anterior a mano, un código de traspaso; sin él,
  volver a apoyar el carnet y una espera de 7 días en que el teléfono anterior puede
  rechazar el cambio.
- La credencial se revalida cada 12 meses, o antes si vence el carnet.

## Por qué

- Impedir que un carnet esté en dos teléfonos exige guardar algo fuera del teléfono nuevo:
  el teléfono nuevo no tiene forma de saber que existe el anterior, y el chip del carnet no
  se puede escribir. Lo mínimo es un código por carnet y la clave del teléfono activo.
- Blockchain descartada. Lo que se publica ahí es permanente, copiable por
  cualquiera y no se puede borrar: si algún día el código se puede calcular desde un carnet,
  el daño no tiene arreglo, y choca con el derecho de supresión de la Ley 21.719. Además las
  transacciones dejan metadatos (hora, billetera que paga).
- Un servidor propio permite borrar y corregir. Sólo decide qué teléfono está activo: no
  puede hacer pasar a nadie por mayor de edad, porque eso lo prueba la firma del chip.

## Encaje en la Ley 21.719

Revisado el 2026-10-22 contra el texto del Diario Oficial (`ley-21719-texto.txt` de la skill
`compliance-cl`). No constituye asesoría legal.

- **El código cuenta como dato personal seudonimizado, no anonimizado.** La anonimización
  exige un procedimiento "irreversible" que destruya el nexo (art. 2 letra k). Acá el nexo
  existe a propósito: con la clave del servidor y el carnet se recalcula el código, que es
  justo como se detectan duplicados. Calza con la seudonimización (art. 2 letra l), donde
  la información adicional "figure por separado" y esté protegida. La clave pública del
  teléfono y las fechas asociadas corren la misma suerte.
- **Obligaciones para el registro** (somos responsables de datos):
  - base de licitud: consentimiento al registrarse (art. 12);
  - informar qué se guarda y para qué (art. 14 ter);
  - proporcionalidad y conservación limitada (art. 3 letra c): borrar el código cuando la
    credencial vence sin renovarse;
  - seguridad (art. 14 quinquies, que nombra la seudonimización como medida) y protección
    desde el diseño (art. 14 quáter): el diseño ya apunta ahí;
  - reportar vulneraciones (art. 14 sexies);
  - derechos del titular, en particular la supresión: la persona apoya el carnet en la app,
    se recalcula su código y se borra.
- **Evaluación de impacto:** obligatoria en "tratamiento masivo de datos o a gran escala"
  (art. 15 ter letra b). Si apunta a todo Chile, hacerla antes de lanzar.
- **Regla de diseño derivada:** registrar el código sólo después de que la prueba de edad
  salga positiva. Así el registro nunca contiene códigos de menores.
- **Sin resolver por el texto:** si somos responsables de lo que la app procesa dentro del
  teléfono sin recibirlo nunca (nombre, RUT, fecha). Por prudencia, informarlo igual en la
  política de privacidad. `[verificar contra fuente oficial]`
