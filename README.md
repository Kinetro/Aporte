# TurnoJusto — AP-U1 (Aplicaciones Distribuidas, ISR-701)

Prototipo distribuido de turnos de atención para una agencia con tres ventanillas.
El **coordinador** (proceso 0) entrega números consecutivos y sin repetir, controla el
cupo de la jornada, atiende llamados en orden FIFO, autentica con token, detecta
ventanillas caídas por latidos y mantiene un reloj lógico de Lamport. Las **ventanillas**
(V1, V2 y V3) son procesos independientes que se manejan por consola.

Comunicación: **sockets TCP** con mensajes de texto delimitados por línea (UTF-8).
Sin bibliotecas externas; solo la biblioteca estándar de **Java 21**.

## Estructura del repositorio

```
.
├── config.properties
├── README.md
├── docs/
│   ├── entrega_SGA.tex
│   ├── informe.tex
│   └── img/
├── logs/                         (se crea al ejecutar)
│   ├── coordinador.log
│   ├── v1.log
│   ├── v2.log
│   ├── v3.log
│   └── orden_total.log
└── src/
    ├── Coordinador.java
    ├── Ventanilla.java
    ├── PruebaCarga.java
    └── OrdenTotal.java
```

## Requisitos

- JDK 21 (probado con `java 21.0.11` y `javac 21.0.11`).
- Windows 10/11 con PowerShell o CMD.
- No se requieren dependencias, Maven ni conexión a Internet.

Verifique la versión:

```powershell
java -version
javac -version
```

## Configuración

El archivo `config.properties` está en la raíz del repositorio:

| Clave    | Valor por defecto | Significado                                      |
|----------|-------------------|--------------------------------------------------|
| `cupo`   | `10`              | Turnos máximos de la jornada                     |
| `token`  | `1234`            | Token compartido entre coordinador y ventanillas |
| `host`   | `127.0.0.1`       | Interfaz donde escucha el coordinador            |
| `puerto` | `5000`            | Puerto TCP del coordinador                       |

Coordinador, Ventanilla y PruebaCarga leen `config.properties` del directorio de trabajo y, si no
existe, buscan `src/config.properties`. **Ejecute siempre los comandos desde la raíz
del repositorio** para que coincidan la configuración y la carpeta `logs/`.

Para cambiar el cupo o el token, edite el archivo y reinicie el coordinador y las
ventanillas.

## Compilación

Desde la raíz del repositorio:

```powershell
javac -d out src\Coordinador.java src\Ventanilla.java src\PruebaCarga.java src\OrdenTotal.java
```

También funciona con todos los archivos de `src`:

```powershell
$archivos = Get-ChildItem src\*.java | ForEach-Object { $_.FullName }
javac -d out $archivos
```

Se generan las clases en `out/`. No hay que compilar de nuevo al cambiar solo
`config.properties`.

## Ejecución

### 1. Coordinador (primero)

En una terminal, desde la raíz:

```powershell
java -cp out Coordinador
```

Modo normal (exclusión mutua en la asignación de turnos). Para el escenario E3
también existe el modo inseguro:

```powershell
java -cp out Coordinador --inseguro
```

En `--inseguro` la lectura de `ultimo` y su actualización se hacen por separado, con
una pausa de 50 ms, para comparar el efecto de la condición de carrera.

### 2. Ventanillas (después del coordinador)

Abra tres terminales más y ejecute una ventanilla por terminal:

```powershell
java -cp out Ventanilla 1
java -cp out Ventanilla 2
java -cp out Ventanilla 3
```

Órdenes por consola:

| Orden     | Atajos       | Efecto                                                    |
|-----------|--------------|-----------------------------------------------------------|
| `turno`   | `t`          | Pide un número nuevo para una persona que llega            |
| `llamar`  | `l`          | Pide el siguiente turno en espera para atenderlo           |
| `estado`  | `e`          | Muestra emitidos, pendientes, cupo y estado de V1, V2, V3  |
| `salir`   | `s`, `q`     | Termina la ventanilla                                      |
| `ayuda`   | `h`, `?`     | Repite el banner de ayuda                                  |

Cada ventanilla envía un latido al coordinador cada 2 s. Si el coordinador deja de
responder, la ventanilla muestra el error, registra el evento y **sigue en ejecución**
para reintentar cuando el coordinador vuelva (escenario E5).

### 3. PruebaCarga

Con el coordinador en modo normal y sin necesidad de ventanillas abiertas:

```powershell
java -cp out PruebaCarga
```

Lanza 30 solicitudes `TURNO` simultáneas (10 por ventanilla) y muestra al final:
asignadas, rechazadas por motivo, números distintos, duplicados y cuántos turnos dice
el coordinador que emitió. Se puede indicar otro número de solicitudes:

```powershell
java -cp out PruebaCarga 30
```

Para comparar E3, detenga el coordinador, arránquelo con `--inseguro` y repita
`PruebaCarga`. Con cupo 10 y el coordinador recién iniciado, el modo normal debe entregar exactamente 10 números distintos (1 a 10); el modo inseguro puede producir duplicados. Las cifras de ambos modos quedan pendientes de medición.

### 4. OrdenTotal

Con los registros generados:

```powershell
java -cp out OrdenTotal
```

Lee `logs/coordinador.log`, `logs/v1.log`, `logs/v2.log` y `logs/v3.log`, y escribe
`logs/orden_total.log` ordenado por marca de Lamport y, en caso de empate, por
identificador de proceso.

## Formato de los mensajes

TCP en `host:puerto`, texto UTF-8, **un mensaje por línea** terminado en `\n`.
Cada solicitud abre una conexión, envía una línea, recibe una línea y se cierra. El
delimitador de línea evita que dos solicitudes seguidas se mezclen o se corten.

### Solicitud

```
COMANDO;token;id_ventanilla;lamport
```

- `COMANDO`: `TURNO`, `LLAMAR`, `ESTADO` o `LATIDO`.
- `token`: valor de `token` en `config.properties`.
- `id_ventanilla`: `1`, `2` o `3`.
- `lamport`: reloj lógico del emisor en el momento del envío.

Ejemplo:

```
TURNO;1234;2;17
```

### Respuesta

```
RESULTADO;campo=valor;...;lamport=N
```

| Resultado      | Ejemplo                                                                    |
|----------------|----------------------------------------------------------------------------|
| `ASIGNADO`     | `ASIGNADO;numero=8;emitidos=8;cupo=10;lamport=19`                          |
| `LLAMADO`      | `LLAMADO;numero=1;pendientes=2;lamport=21`                                 |
| `ESTADO`       | `ESTADO;emitidos=4;pendientes=2;cupo=10;V1=ACTIVA;V2=CAIDA;V3=ACTIVA;lamport=23` |
| `RECHAZADA`    | `RECHAZADA;motivo=CUPO_AGOTADO;emitidos=10;cupo=10;lamport=24`             |
| `RECHAZADA`    | `RECHAZADA;motivo=SIN_TURNOS;lamport=25`                                   |
| `RECHAZADA`    | `RECHAZADA;motivo=TOKEN_INVALIDO;lamport=26`                               |
| `RECHAZADA`    | `RECHAZADA;motivo=COMANDO_INVALIDO;lamport=27`                             |
| `LATIDO_OK`    | `LATIDO_OK;lamport=5`                                                      |

Motivos de rechazo: `FORMATO_INVALIDO`, `TOKEN_INVALIDO`, `CUPO_AGOTADO`,
`SIN_TURNOS` y `COMANDO_INVALIDO`. Todo rechazo por token inválido queda registrado
en `logs/coordinador.log`.

## Relojes de Lamport y registros

Cada proceso mantiene su propio reloj:

- incrementa en 1 antes de cada evento local o envío;
- al recibir un mensaje lo fija en `max(local, recibido) + 1`;
- todo mensaje, incluidos los latidos, lleva la marca del emisor;
- la respuesta del coordinador también lleva su marca.

Cada línea de bitácora tiene el formato:

```
lamport;id_proceso;evento;detalle
```

| Archivo                 | id_proceso | Contenido                                  |
|-------------------------|------------|--------------------------------------------|
| `logs/coordinador.log`  | `0`        | solicitudes, asignaciones, rechazos, latidos, caídas y reactivaciones |
| `logs/v1.log`           | `1`        | eventos de la ventanilla V1                |
| `logs/v2.log`           | `2`        | eventos de la ventanilla V2                |
| `logs/v3.log`           | `3`        | eventos de la ventanilla V3                |
| `logs/orden_total.log`  | —          | los cuatro registros en orden total        |

Los archivos se crean al arrancar y se amplían en cada ejecución. Para una corrida limpia, utilice una copia de trabajo independiente y preserve los registros originales.

## Escenarios E0 a E6

Antes de cada captura, muestre usuario, equipo y fecha:

```powershell
whoami; hostname; Get-Date
```

| Escenario | Pasos sugeridos |
|-----------|-----------------|
| **E0** | Desconecte la red y capture `Get-NetAdapter \| Format-Table Name,Status`. Cree el repositorio y haga el primer commit con el código de sesión 1. |
| **E1** | Arranque coordinador, V1, V2 y V3. V1 pide `turno` (1), V2 pide `turno` (2), V3 pide `turno` (3), V1 pide otro `turno` (4). V2 hace `llamar` (recibe 1) y V3 hace `llamar` (recibe 2). Ejecute `estado` en las tres ventanillas: debe mostrar los mismos emitidos y pendientes. |
| **E2** | Con cupo 10, pida el turno 11 y capture el rechazo `CUPO_AGOTADO`. Con la cola vacía, ejecute `llamar` y capture `SIN_TURNOS`. Envíe una solicitud con token incorrecto (ver comando abajo) y capture `TOKEN_INVALIDO`; verifique la línea en `logs/coordinador.log`. |
| **E3** | Con el coordinador normal, ejecute `java -cp out PruebaCarga` con el coordinador recién iniciado y anote las cifras (se esperan 10 asignadas, 10 distintas del 1 al 10 y 20 rechazadas por cupo). Reinicie con `--inseguro` en una copia de trabajo separada, repita `PruebaCarga` y anote las cifras. |
| **E4** | Con V3 activa, cierre su proceso. Espere más de 6 s y ejecute `estado`: V3 aparece `CAIDA` y queda `VENTANILLA_CAIDA` en la bitácora. Vuelva a arrancar V3, espere un latido y verifique `ACTIVA` y `VENTANILLA_ACTIVA ... reactivada=true`. |
| **E5** | Con las ventanillas activas, detenga el coordinador y pida `turno` desde V1. La ventanilla muestra que no pudo contactar al coordinador, registra `ERROR_CONEXION` y sigue esperando. Explique en el informe qué priorizó el sistema. |
| **E6** | Ejecute `java -cp out OrdenTotal` y revise `logs/orden_total.log`. Identifique dos pares envío→recepción donde la marca del envío sea menor que la de recepción. |

Solicitud con token incorrecto (E2), desde PowerShell:

```powershell
$cliente = New-Object Net.Sockets.TcpClient('127.0.0.1', 5000)
$escritor = New-Object IO.StreamWriter($cliente.GetStream())
$escritor.AutoFlush = $true
$escritor.WriteLine('TURNO;token-malo;1;1')
$lector = New-Object IO.StreamReader($cliente.GetStream())
$lector.ReadLine()
$cliente.Close()
```

Guarde las capturas en `docs/img/` con nombres `E0.png` … `E6.png` y conserve los
registros en `logs/`.

## Compilación del informe y de la página del SGA

Los documentos LaTeX están en `docs/`. Se compilan con `pdflatex` (distribución
MiKTeX o TeX Live ya instalada, sin descargar paquetes nuevos):

```powershell
cd docs
pdflatex -interaction=nonstopmode informe.tex
pdflatex -interaction=nonstopmode informe.tex
pdflatex -interaction=nonstopmode entrega_SGA.tex
pdflatex -interaction=nonstopmode entrega_SGA.tex
```

Dos pasadas resuelven las referencias internas. Se generan `docs/informe.pdf` y
`docs/entrega_SGA.pdf` (una sola página, con la URL del repositorio en una línea).

## Código reutilizado

No se reutilizó código.

## Decisiones de diseño y limitaciones

- La asignación de turnos en modo normal usa una sección crítica (`synchronized`)
  que cubre la verificación del cupo, el incremento de `ultimo` y el encolado. La
  cola es `ConcurrentLinkedQueue`, por lo que `LLAMAR` entrega cada turno a una sola
  ventanilla y en orden FIFO.
- El modo `--inseguro` separa lectura y actualización con una pausa de 50 ms y sin
  exclusión mutua; existe únicamente para evidenciar la condición de carrera en E3.
- El coordinador es un punto único de fallo: si cae, las ventanillas no pueden pedir
  turnos y solo reintentan. Se prioriza la consistencia de la numeración sobre la
  disponibilidad (CAP).
- Los latidos usan 2 s de intervalo y 6 s de umbral, de modo que se toleran hasta dos
  latidos perdidos antes de declarar una ventanilla caída.
- No hay persistencia: al reiniciar el coordinador, `ultimo` y la cola vuelven a cero.
- El alcance es local (`127.0.0.1`); no se implementan reconexión del coordinador,
  réplicas ni elección de líder.

## Revisión documental posterior a la sesión

Se incorporó documentación complementaria con asistencia de IA después de la evaluación. No se modificó el código Java ni se generaron evidencias de la sesión original. `logs/orden_total.log` es una reconstrucción posterior de los 79 eventos disponibles en `coordinador.log`, `v1.log` y `v3.log`, ordenados por Lamport e identificador de proceso con el mismo criterio de `OrdenTotal`. Falta `v2.log`; los reinicios de los relojes impiden atribuir un orden causal global a todas las ejecuciones mezcladas. No se certifica la ejecución del escenario E6 durante la sesión. Las imágenes E0–E2 muestran el avance en el IDE, no la ejecución completa de esos escenarios. Quedan pendientes las evidencias de ejecución de E0–E6 y las cifras reales de E3. La carátula usa `docs/logo-uteq.jpg`, recuperado de un repositorio propio previo (`Kinetro/TA-IND-01-Unidad1`, `imagenes/logo-uteq.jpg`). La declaración de reutilización del código debe ser verificada por el estudiante. Cualquier uso de este complemento en la evaluación está sujeto a autorización del docente.
