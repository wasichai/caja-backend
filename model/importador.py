"""Piezas comunes de import_cajas.py e import_tasas.py: la lectura del CSV fila por fila, las opciones de la línea de
comandos y el informe de las filas rechazadas.

El rechazo es por fila, nunca por archivo: una fila rechazada se informa con su número de línea y su motivo, y no
impide las siguientes.
"""
import argparse
import csv
import os


def leer_filas(ruta, obligatorias):
    """Las filas de un CSV como (línea, valores, columnas): la línea cuenta la cabecera como 1, los valores son un
    dict por nombre de columna sin espacios en los bordes (una celda que falta es ''), y columnas es cuántas
    celdas trajo la fila. Las líneas en blanco no son filas. Lanza ValueError si a la cabecera le falta una columna
    de las obligatorias: sin ella no se sabe qué es cada celda, y eso sí es del archivo."""
    with open(ruta, encoding="utf-8-sig", newline="") as f:
        lector = csv.reader(f)
        cabecera = [c.strip() for c in next(lector, [])]
        faltan = [c for c in obligatorias if c not in cabecera]
        if faltan:
            raise ValueError(f"a la cabecera de {ruta} le faltan las columnas: {', '.join(faltan)}")
        filas = []
        for celdas in lector:
            if not any(c.strip() for c in celdas):
                continue
            valores = {nombre: (celdas[i].strip() if i < len(celdas) else "") for i, nombre in enumerate(cabecera)}
            filas.append((lector.line_num, valores, len(celdas)))
        return filas


def agregar_opciones_de_core(parser, archivo_por_defecto):
    parser.add_argument("--archivo", default=archivo_por_defecto, help="el CSV a cargar")
    parser.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8091"))
    parser.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    parser.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    parser.add_argument("--dry-run", action="store_true", help="lee core y dice qué haría; no escribe nada")
    return parser


def argumentos(descripcion, archivo_por_defecto, argv):
    return agregar_opciones_de_core(argparse.ArgumentParser(description=descripcion), archivo_por_defecto).parse_args(argv)


def imprimir_rechazadas(rechazadas):
    for linea, motivo in rechazadas:
        print(f"  rechazada línea {linea}: {motivo}")
