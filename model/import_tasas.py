#!/usr/bin/env python3
"""Carga las tasas (las tarifas del TUPA) desde un CSV con la cabecera
codigo,descripcion,codigoArea,partidaPresupuestal,importe,vigenciaDesde,vigenciaHasta,documentoFuente.

No hay archivo de tarifas en este repositorio: las cifras del TUPA salen de normativa (el corpus verificado a doble
firma), no se escriben aquí. El archivo se pasa con --archivo.

Reglas:
- El rechazo es por fila, nunca por archivo: una fila rechazada se informa con su línea y su motivo, y no impide las
  siguientes.
- El área tiene que existir por código; si no, la fila se rechaza (las áreas las crea import_cajas.py).
- importe es un decimal de hasta 2 decimales y mayor o igual que 0. Se lee con Decimal, nunca con float.
- vigenciaDesde es una fecha AAAA-MM-DD; vigenciaHasta va vacía o es una fecha mayor o igual que vigenciaDesde.
- documentoFuente es obligatorio.
- clave_vigencia es <codigo>|<vigenciaDesde>, y la fila se rechaza si core ya tiene esa clave (o el archivo ya la
  cargó): se comprueba antes de escribir, porque core contesta 500 a un duplicado. El unique del modelo queda de red.

Correr: python3 import_tasas.py --archivo tasas.csv [--dry-run]
Salida: 0 si va bien, 1 si core rechaza algo o no responde.
"""
import re
import sys
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from core_client import Client, CoreError
from importador import argumentos, imprimir_rechazadas, leer_filas

TASA = "tasa"
AREA = "area"
COLUMNAS = ("codigo", "descripcion", "codigoArea", "partidaPresupuestal", "importe", "vigenciaDesde", "vigenciaHasta",
            "documentoFuente")
IMPORTE = re.compile(r"\d+(\.\d{1,2})?")
FECHA = re.compile(r"\d{4}-\d{2}-\d{2}")
CENTAVO = Decimal("0.01")
# celda obligatoria -> cómo la nombra el motivo del rechazo
OBLIGATORIAS = (("codigo", "el código"), ("descripcion", "la descripción"), ("codigoArea", "el código del área"),
                ("partidaPresupuestal", "la partida presupuestal"), ("importe", "el importe"),
                ("vigenciaDesde", "la vigencia desde"), ("documentoFuente", "el documento fuente"))


@dataclass
class Informe:
    tasas_creadas: int = 0
    rechazadas: list = field(default_factory=list)  # (línea, motivo)
    core_rechazadas: list = field(default_factory=list)  # (línea, CoreError)


def _fecha(texto, nombre):
    """(fecha, None) o (None, motivo)."""
    try:
        if FECHA.fullmatch(texto):
            return date.fromisoformat(texto), None
    except ValueError:
        pass
    return None, f"{nombre} '{texto}' no es una fecha AAAA-MM-DD"


def parsear(valores, columnas):
    """La tasa de una fila y el código de su área, o el motivo del rechazo: (atributos, codigo_area, None) o
    (None, None, motivo). Falta el área por resolver: la agrega cargar()."""
    if columnas < len(COLUMNAS):
        return None, None, f"la fila trae {columnas} columna(s) y hacen falta {len(COLUMNAS)}"
    for columna, nombre in OBLIGATORIAS:
        if not valores[columna]:
            return None, None, f"falta {nombre}"
    if not IMPORTE.fullmatch(valores["importe"]):
        return None, None, f"el importe '{valores['importe']}' no es un decimal de hasta 2 decimales y mayor o igual que 0"
    importe = Decimal(valores["importe"]).quantize(CENTAVO)
    desde, motivo = _fecha(valores["vigenciaDesde"], "la vigencia desde")
    if motivo:
        return None, None, motivo
    atributos = {
        "codigo": valores["codigo"],
        "descripcion": valores["descripcion"],
        "partida_presupuestal": valores["partidaPresupuestal"],
        "importe": str(importe),
        "vigencia_desde": desde.isoformat(),
        "documento_fuente": valores["documentoFuente"],
        "clave_vigencia": f"{valores['codigo']}|{desde.isoformat()}",
    }
    if valores["vigenciaHasta"]:
        hasta, motivo = _fecha(valores["vigenciaHasta"], "la vigencia hasta")
        if motivo:
            return None, None, motivo
        if hasta < desde:
            return None, None, f"la vigencia hasta {hasta.isoformat()} es anterior a la vigencia desde {desde.isoformat()}"
        atributos["vigencia_hasta"] = hasta.isoformat()
    return atributos, valores["codigoArea"].upper(), None


def cargar(client, filas, dry_run=False):
    """Carga las filas una por una y devuelve el Informe. Con dry_run lee core y no escribe."""
    informe = Informe()
    claves = {r["attributes"].get("clave_vigencia") for r in client.list_all(TASA)}
    areas = {r["attributes"].get("codigo"): r["id"] for r in client.list_all(AREA)}

    for linea, valores, columnas in filas:
        atributos, codigo_area, motivo = parsear(valores, columnas)
        if motivo is None and codigo_area not in areas:
            motivo = f"el área '{codigo_area}' no existe"
        if motivo is None and atributos["clave_vigencia"] in claves:
            motivo = f"ya hay una tasa con la clave de vigencia '{atributos['clave_vigencia']}'"
        if motivo is not None:
            informe.rechazadas.append((linea, motivo))
            continue
        claves.add(atributos["clave_vigencia"])
        try:
            if not dry_run:
                client.post(f"/api/objects/{TASA}/records", {"attributes": {**atributos, "area": areas[codigo_area]}})
            informe.tasas_creadas += 1
        except CoreError as e:
            informe.core_rechazadas.append((linea, e))
    return informe


def main(argv=None):
    args = argumentos("Carga las tasas en wasichai Core.", None, sys.argv[1:] if argv is None else argv)
    if args.archivo is None:
        print("error: falta --archivo: las tarifas salen de normativa y no hay archivo de ejemplo en el repositorio", file=sys.stderr)
        return 1
    try:
        filas = leer_filas(args.archivo, COLUMNAS)
    except (OSError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    print(f"filas: {len(filas)}")
    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        informe = cargar(client, filas, args.dry_run)
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    imprimir_rechazadas(informe.rechazadas)
    print(f"tasas: {informe.tasas_creadas} {'por crear' if args.dry_run else 'creadas'}, {len(informe.rechazadas)} rechazadas")
    for linea, e in informe.core_rechazadas:
        print(f"core rechazó la línea {linea} -> {e.status}\n{e.body}", file=sys.stderr)
    if args.dry_run:
        print("dry run: no se escribió nada")
    return 1 if informe.core_rechazadas else 0


if __name__ == "__main__":
    sys.exit(main())
