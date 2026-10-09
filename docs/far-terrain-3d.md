# Terreno lejano en 3D: propuesta

Investigación, sin implementar. Pregunta: ¿puede el terreno lejano de mcopt dejar de ser un mapa de alturas (aleros, acantilados
socavados, arcos, bocas de cueva, islas flotantes) sin perder rendimiento?

**Respuesta corta: sí, por fases, y el costo por frame en el hilo de render no cambia.** Lo que crece es memoria, cantidad de caras
(quads) y trabajo de los hilos de malla, en proporción a cuánto del mundo tiene esas formas cerca de la superficie. Ese número (f)
todavía no lo sabemos para un mundo real; `mcopt.lod.spanStats=true` lo mide (ver Fase 0).

## 1. Qué hay hoy

- **Una celda = una columna.** Por celda hay una palabra de geometría (alto del tope, agua, válida, copa, grosor de copa, claro,
  profundidad: los 32 bits ocupados) y una de color. En el nivel 0 hay además palabras de copa, "runs", textura y plantas
  (28 B por celda en el nivel 0, 8 B en los niveles gruesos; ~240 MB en HIGH).
- **Ya existe un segundo span: la copa de los árboles.** Hojas sobre aire: tope y grosor en la palabra de geometría, el suelo de
  abajo en la palabra de copa, y hasta 4 "runs" (los pisos de un abeto) en otra palabra. Solo en el nivel 0 por defecto.
- **El mallador ya trabaja con intervalos, no con un mapa de alturas** (`lodmesh.c`): cada celda es una lista de hasta 5 intervalos
  sólidos (`MAX_IV`), y las caras salen de la resta de intervalos entre vecinos (`faceDiff`): topes, caras de abajo, paredes y
  faldones entre niveles. Es lo mismo que hace Distant Horizons (`ColumnBox`).
- **Lo que se pierde está antes del mallador:**
  - Los chunks reales se resumen en una columna con un tope, un agua y una copa de hojas: todo lo que está bajo el primer bloque
    sólido (cuevas, el aire bajo un alero o un arco) desaparece, y un saliente se convierte en un pilar.
  - El ruido de los niveles 0-1 tiene toda la columna de densidad pero se queda con el cruce de más arriba; los niveles 2+ muestrean
    cada 32 bloques y descartan a propósito los bolsones flotantes.
  - Los niveles gruesos toman una muestra por celda (la esquina): no hay mezcla.
- **Tres partes del GPU asumen una superficie por columna:**
  - el cull del horizonte: cada celda tapa como si fuera sólida desde abajo hasta el tope de su primer intervalo (el suelo;
    bajo una copa, el suelo de abajo, no la copa). Con un span suspendido ya es correcto, solo que el alero no ayuda a tapar;
  - el color de cada píxel, que se busca por posición en una palabra de color por celda;
  - los detalles cercanos (AO, textura de paredes según la profundidad desde el tope, agua, plantas).

## 2. Qué hacen Distant Horizons y Voxy

| | Distant Horizons | Voxy |
|---|---|---|
| Dato | columna = lista de runs (bloque, bioma, tope, base, luz), 8 B cada uno | vóxeles de 32³ por nivel, 8 B cada uno |
| Altura en niveles gruesos | exacta (1 bloque) | cuantizada a 2^nivel bloques (escalones de 16 en el nivel 4) |
| Cómo baja de nivel | 2×2 → 1 con unión de transiciones; el sólido gana al aire | 2×2×2 → 1; el sólido gana |
| Límite de detalle | K runs por columna según distancia ("vertical quality": 6 cerca, 4 lejos en MEDIUM) | ninguno, pero toda la memoria |
| Cuevas | se rellenan con una regla de luz del cielo bajo y=60 | se guardan y se mallan; las tapa la oclusión HiZ |
| Memoria | densa: 32 KiB × K por sección, haya o no formas | 256 KiB por sección 32³ en RAM; heap de GPU de 0,5-4 GB |
| Render | un draw por buffer por sección, cull en CPU, sin oclusión | todo en GPU: octree, HiZ en dos fases, draws indirectos |

Problemas conocidos de cada uno: en DH, la regla de luz del cielo falla bajo objetos flotantes, la memoria crece con K aunque no
haya nada, y el corte de K se decide por columna (vecinas que no coinciden dejan paredes sueltas). En Voxy, la altura cuantizada
hace terrazas, abre y cierra huecos según el nivel, y la memoria es enorme.

## 3. Propuesta: spans dispersos sobre lo que ya hay

**Idea:** el suelo sigue siendo la palabra de geometría (sólido desde abajo hasta el tope), y solo las celdas que lo necesitan tienen
spans extra (sólidos flotantes sobre un hueco), guardados aparte por tile. Una celda sin aleros cuesta exactamente lo mismo que hoy.

### Fase 0: medir (hecho, `9cb21e4`)

`mcopt.lod.spanStats=true` recorre cada columna de los chunks que llegan, 64 bloques hacia abajo desde el tope, y cuenta sólidos
sobre huecos de aire de 2 bloques o más con sólido debajo. Un hueco es **abierto** si una columna vecina tiene su tope por debajo
del techo del hueco (se ve de costado: alero, arco, boca de cueva) y **cerrado** si no (bolsón de cueva, que el terreno lejano
dejaría sólido). Cada 30 s imprime el porcentaje de columnas con un span abierto, con dos o más, el grosor y el hueco promedio,
y aparte las copas de hojas. Ese porcentaje es f, y decide cuánto cuesta todo lo demás.

### Fase 1: la copa pasa a ser un "span suspendido" cualquiera (nivel 0, sin memoria nueva)

- La copa ya es exactamente "un sólido flotando sobre un suelo". Se generaliza a cualquier material: un alero de piedra, el techo
  de un arco, una isla flotante.
- La palabra de copa tiene 4 bits libres (28-31): uno distingue "hojas" de "sólido", para la textura y la luz de la cara de abajo
  (un techo de piedra es más oscuro que una copa).
- Fuentes:
  - **Chunks reales:** el resumen ya baja hasta 48 bloques por las hojas; se extiende a cualquier sólido sobre un hueco abierto
    (la prueba de vecinos de la Fase 0). Para no gastar más en el hilo de render, la búsqueda se hace en el worker con copias de
    las secciones, como ya hace realOcc.
  - **Imports:** DH ya trae runs por columna; Voxy trae vóxeles. Ambos pasan por el mismo resumen.
  - **Ruido, niveles 0-1:** la columna de densidad ya está calculada; en vez del primer cruce se toman los dos primeros.
  - **Servidor:** un campo más en el formato de tile (subir `PROTOCOL`/`VERSION`).
- Lo que no cambia: mallador (ya genera estas caras), cull del horizonte (`lodmesh.c` ya toma el primer intervalo, el suelo,
  como oclusor: un alero no tapa nada de más), formato de quad, memoria del nivel 0.
- Límite: un span suspendido por columna, y una columna con alero no puede tener además copa (gana el más alto).

### Fase 2: varios spans por columna y niveles gruesos

- **Pool por tile:** 8 B por span (base 12 bits, tope 12, material 8, colores tope/lado RGB565) más un índice de ~640 B por tile
  (máscara de 4096 bits y un contador por fila). Solo existe donde f > 0.
- **K por nivel**, como el "vertical quality" de DH: 4 spans en los niveles 0-1, 3 en 2-3, 2 más lejos, 1 en el más grueso.
  El mallador ya acepta 5 intervalos por celda.
- **Bajar de nivel** con la unión de transiciones de DH (alturas exactas, el sólido gana), y **recortar a K de forma coherente
  por bloque de 8×8**, no por columna: si no, dos vecinas se quedan una con la capa fina y la otra no, y aparecen paredes sueltas.
- **Niveles gruesos generados:** donde la muestra de 32 bloques ve más de un cambio de signo, se muestrea cada 8 bloques en esa
  franja (costo acotado a esas celdas).
- **Color por span:** el fragment shader resuelve el span por posición (el que tiene su tope, su base o contiene la y del píxel);
  así las caras se siguen fusionando por geometría sin agregar bits al quad.
- **Disco y red:** `FORMAT` 12 y `PROTOCOL` 3 con planos de spans (casi vacíos: se comprimen solos).

### Fase 3 (solo si hace falta): oclusión en GPU de dos fases

Con más caras de abajo y bocas de cueva, el cull del horizonte (que solo usa el suelo) tapa un poco menos. Si el GPU lo nota,
la oclusión HiZ en dos fases de Voxy (dibujar lo visible el frame anterior, construir la pirámide, probar el resto) se monta
sobre el cull por compute que ya existe.

## 4. Costos estimados

| | Fase 1 | Fase 2 (f = 2 %) | Fase 2 (f = 5 %) |
|---|---|---|---|
| CPU por frame (hilo de render) | igual | igual | igual |
| Memoria por nivel (N = 2048) | 0 en nivel 0 | ~1,9 MiB (~6 % de geom+color) | ~3,8 MiB (~12 %) |
| Quads por tile con esas formas | +4-5 por celda afectada | idem | idem |
| Disco y red | campo más | planos casi vacíos | planos casi vacíos |

Medido con el mallador real en un arnés sintético (terreno en terrazas con aleros en parches):

| Celdas con alero | Nivel 0 | Nivel 2 |
|---|---|---|
| 0 % | 1527 quads/tile | 3047 |
| 0,4 % / 1,7 % | +3 % | +11 % |
| 2,8 % / 10,8 % | +29 % | +65 % |

Para comparar: los bosques con copas ya cuestan 5,6 veces los quads de colinas peladas (11.781 contra 2.102 por tile), y hoy se
dibujan sin problema. Las caras de abajo miran hacia abajo, así que desde arriba el cull por orientación las descarta casi todas:
el costo en el GPU es menor que el aumento de quads.

## 5. Riesgos y cómo evitarlos

- **Capas finas que parpadean entre niveles:** recorte coherente por bloque de 8×8 y un umbral de grosor y hueco por nivel
  (un span sobrevive al nivel L solo si mide al menos 2^L).
- **Huecos que no se ven (cuevas):** la prueba de vecinos abiertos en lugar de la luz del cielo de DH, que falla bajo objetos
  flotantes y depende de una luz bien calculada.
- **Más trabajo al resumir chunks:** pasarlo al worker con copias de sección (realOcc ya lo hace).
- **Costuras con el terreno cercano:** los faldones siguen bajo el suelo; los spans flotantes no llegan al borde porque el hand-off
  es por columna completa.

## 6. Qué no conviene

- **Vóxeles completos como Voxy:** una ventana N = 2048 de nivel 0 con un mundo de 384 bloques son ~49.000 secciones, ~12 GiB sin
  comprimir, más un heap de GPU de gigas, y la altura cuantizada sería un retroceso frente a los topes exactos de hoy.
- **Almacenamiento denso de DH:** K spans en cada celda cuestan 128-192 MiB por nivel aunque el mundo sea plano.

## 7. Siguiente paso

Una sesión con `mcopt.lod.spanStats=true` en el cliente. Cuenta los chunks reales que carga el juego (en un hilo aparte, no en el
de render) y, si el guardado de Distant Horizons ya se importó, lo vuelve a leer solo para medir (un cuarto de sus chunks, sin
importar nada). Con f medido:

- f < 1 %: la Fase 1 sola cubre casi todo lo visible.
- f entre 1 y 5 %: Fase 1 + Fase 2 en los niveles 0-1.
- f > 5 %: Fase 2 completa, con K bajo en los niveles lejanos.
