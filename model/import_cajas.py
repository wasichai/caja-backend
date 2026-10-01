#!/usr/bin/env python3
"""Carga las ventanillas de una municipalidad y las áreas a las que imputan: una fila por caja, con las columnas
codigo,nombre,serie,codigoArea,nombreArea (codigoArea y nombreArea pueden ir vacías). Es la carga de ImportarCajas de
caja, sobre wasichai por REST.

El archivo de ejemplo es data/ejemplos/cajas.csv: es configuración de una municipalidad, no una cifra normativa.

Reglas:
- El rechazo es por fila, nunca por archivo: una fila rechazada se informa con su línea y su motivo, y no impide las
  siguientes.
- Se rechaza la fila incompleta (sin código, nombre o serie), la de una serie de más de 5 caracteres y la de un área
  que no existe y a la que la fila no le pone nombre.
- Un área que ya existe por codigoArea se reutiliza (su nombre no se reescribe); si no existe se crea con nombreArea.
- Una caja sin codigoArea entra sin área: las cajas tributarias no tienen.
- El código y la serie de una caja no se repiten. Se comprueba contra lo que core ya tiene (y contra lo que el archivo
  ya cargó) antes de escribir, porque core contesta 500 a un duplicado; el unique del modelo queda como red.
- Las cajas nuevas entran activas, y las áreas nuevas también.

Una segunda corrida con el mismo archivo no escribe nada: rechaza cada fila por repetida.

Correr: python3 import_cajas.py [--archivo cajas.csv] [--dry-run]
Salida: 0 si va bien, 1 si core rechaza algo o no responde.
"""
import os
import sys
from dataclasses import dataclass, field

from core_client import Client, CoreError
from importador import argumentos, imprimir_rechazadas, leer_filas

HERE = os.path.dirname(os.path.abspath(__file__))
ARCHIVO = os.path.join(HERE, "data", "ejemplos", "cajas.csv")
CAJA = "caja"
AREA = "area"
COLUMNAS = ("codigo", "nombre", "serie")
COLUMNAS_MINIMAS = 3
LARGO_MAXIMO_DE_SERIE = 5


@dataclass
class Informe:
    cajas_creadas: int = 0
    areas_creadas: int = 0
    areas_reutilizadas: int = 0
    rechazadas: list = field(default_factory=list)  # (línea, motivo)
    core_rechazadas: list = field(default_factory=list)  # (línea, CoreError)


def parsear(valores, columnas):
    """La caja de una fila y el área que nombra, o el motivo por el que se rechaza: (caja, codigo_area, nombre_area, None)
    o (None, None, None, motivo)."""
    if columnas < COLUMNAS_MINIMAS:
        return None, None, None, f"la fila trae {columnas} columna(s) y hacen falta al menos {COLUMNAS_MINIMAS}: codigo, nombre, serie"
    codigo = valores["codigo"].upper()
    serie = valores["serie"].upper()
    if not codigo:
        return None, None, None, "falta el código"
    if not valores["nombre"]:
        return None, None, None, "falta el nombre"
    if not serie:
        return None, None, None, "falta la serie"
    if len(serie) > LARGO_MAXIMO_DE_SERIE:
        return None, None, None, f"la serie '{serie}' tiene {len(serie)} caracteres y debe tener de 1 a {LARGO_MAXIMO_DE_SERIE}"
    caja = {"codigo": codigo, "nombre": valores["nombre"], "serie": serie, "activa": True}
    return caja, valores.get("codigoArea", "").upper(), valores.get("nombreArea", ""), None


def cargar(client, filas, dry_run=False):
    """Carga las filas una por una y devuelve el Informe. Con dry_run lee core y no escribe."""
    informe = Informe()
    existentes = [r["attributes"] for r in client.list_all(CAJA)]
    codigos = {a.get("codigo") for a in existentes}
    series = {a.get("serie") for a in existentes}
    areas = {r["attributes"].get("codigo"): r["id"] for r in client.list_all(AREA)}

    for linea, valores, columnas in filas:
        caja, codigo_area, nombre_area, motivo = parsear(valores, columnas)
        if motivo is None and (caja["codigo"] in codigos or caja["serie"] in series):
            motivo = f"ya hay una caja con el código '{caja['codigo']}' o con la serie '{caja['serie']}'"
        if motivo is None and codigo_area and codigo_area not in areas and not nombre_area:
            motivo = f"el área '{codigo_area}' no está registrada y la fila no dice cómo se llama: sin nombre no se puede dar de alta"
        if motivo is not None:
            informe.rechazadas.append((linea, motivo))
            continue
        codigos.add(caja["codigo"])
        series.add(caja["serie"])
        try:
            if codigo_area:
                if codigo_area in areas:
                    informe.areas_reutilizadas += 1
                else:
                    areas[codigo_area] = None if dry_run else _crear_area(client, codigo_area, nombre_area)
                    informe.areas_creadas += 1
                if not dry_run:
                    caja["area"] = areas[codigo_area]
            if not dry_run:
                client.post(f"/api/objects/{CAJA}/records", {"attributes": caja})
            informe.cajas_creadas += 1
        except CoreError as e:
            informe.core_rechazadas.append((linea, e))
    return informe


def _crear_area(client, codigo, nombre):
    _, area = client.post(f"/api/objects/{AREA}/records", {"attributes": {"codigo": codigo, "nombre": nombre, "activa": True}})
    return area["id"]


def main(argv=None):
    args = argumentos("Carga las cajas y sus áreas en wasichai Core.", ARCHIVO, sys.argv[1:] if argv is None else argv)
    try:
        filas = leer_filas(args.archivo, COLUMNAS)
    except ValueError as e:
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
    verbo = "por crear" if args.dry_run else "creadas"
    imprimir_rechazadas(informe.rechazadas)
    print(f"áreas: {informe.areas_creadas} {verbo}, {informe.areas_reutilizadas} reutilizadas")
    print(f"cajas: {informe.cajas_creadas} {verbo}, {len(informe.rechazadas)} rechazadas")
    for linea, e in informe.core_rechazadas:
        print(f"core rechazó la línea {linea} -> {e.status}\n{e.body}", file=sys.stderr)
    if args.dry_run:
        print("dry run: no se escribió nada")
    return 1 if informe.core_rechazadas else 0


if __name__ == "__main__":
    sys.exit(main())
