# 0003 — App Android open source con prueba post-cuántica

Fecha: 2026-10-22. Estado: aceptada. **No implementada.** Deja en pausa las decisiones
[0001](0001-credential-without-personal-data.md) y [0002](0002-device-registry.md).

## Decisión

El proyecto pasa a ser un proyecto de portfolio, open source y pensado para que otros
aprendan. La primera versión es una app Android que:

1. lee una cédula chilena por NFC;
2. comprueba la firma del Registro Civil contra los certificados públicos de Chile;
3. genera en el teléfono una prueba de conocimiento cero de "mayor de 18" con un sistema de
   pruebas post-cuántico (STARK, basado sólo en funciones hash).

## Por qué

- Como negocio no compite: ZKPassport ya resuelve lo diseñado en 0001 y 0002, y en varios
  puntos mejor (ver "Lo que ZKPassport ya resuelve" en [el plan](../plan.md)).
- Como portfolio sí: criptografía aplicada a la cédula chilena es poco común.
- ZKPassport usa UltraHonk, basado en curvas elípticas y no post-cuántico. Una versión con
  STARK es una diferencia real y explicable.

## Límite reconocido

El sistema **no puede ser post-cuántico de punta a punta**: la firma del Registro Civil es
RSA y la elige el Estado. Un computador cuántico capaz de romper RSA podría falsificar
cédulas. El proyecto hace post-cuántico lo que controla y documenta lo que no.

## Fuera de la primera versión

Comparación de cara, registro de teléfonos, OPRF, cambio de teléfono y revalidación (0001
y 0002). Se retoman si la primera versión funciona.
