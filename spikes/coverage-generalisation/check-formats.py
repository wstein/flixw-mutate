#!/usr/bin/env python3
"""Checks the prototype outputs: lcov.info (record invariants) and cobertura.xml (against coverage-04.dtd:
elements, children, required attributes; no DTD validator is installed, so the DTD text itself is parsed).
Usage: python3 -I check-formats.py <run dir> <coverage-04.dtd>   (exit 1 on a violation)"""
import re
import sys
import xml.etree.ElementTree as ET

run, dtd = sys.argv[1], sys.argv[2]
bad = []

# --- LCOV: per record LF/LH/FNF/FNH/BRF/BRH equal the counted lines
def lcov(path):
    n = 0
    rec = None
    for raw in open(path):
        line = raw.rstrip("\n")
        k, _, v = line.partition(":")
        if line == "end_of_record":
            n += 1
            for a, b, c in (("LF", "DA", None), ("FNF", "FN", None), ("BRF", "BRDA", None)):
                if rec[a][0] != len(rec[b]):
                    bad.append(f"{path}: {rec['SF']} {a}={rec[a][0]} but {len(rec[b])} {b}")
            if rec["LH"][0] != sum(1 for x in rec["DA"] if int(x.split(",")[1]) > 0):
                bad.append(f"{path}: {rec['SF']} LH mismatch")
            if rec["FNH"][0] != sum(1 for x in rec["FNDA"] if int(x.split(",")[0]) > 0):
                bad.append(f"{path}: {rec['SF']} FNH mismatch")
            if rec["BRH"][0] != sum(1 for x in rec["BRDA"] if x.split(",")[3] not in ("0", "-")):
                bad.append(f"{path}: {rec['SF']} BRH mismatch")
            if len(rec["FN"]) != len(rec["FNDA"]):
                bad.append(f"{path}: {rec['SF']} FN and FNDA differ")
            rec = None
            continue
        if rec is None:
            rec = {x: [] for x in ("TN", "SF", "FN", "FNDA", "FNF", "FNH", "BRDA", "BRF", "BRH", "DA", "LF", "LH")}
        if k not in rec:
            bad.append(f"{path}: unknown tag {k}")
            continue
        if k in ("FNF", "FNH", "BRF", "BRH", "LF", "LH"):
            rec[k] = [int(v)]
        elif k in ("TN", "SF"):
            rec[k] = v
        else:
            rec[k].append(v)
    if rec is not None:
        bad.append(f"{path}: record without end_of_record")
    return n

# --- DTD
text = open(dtd).read()
children = {}
for name, model in re.findall(r"<!ELEMENT\s+(\S+)\s+(\([^>]*\)|EMPTY)\s*>", text):
    children[name] = set(re.findall(r"[A-Za-z]+", model)) - {"PCDATA", "EMPTY"}
required = {}
for el, att, kind in re.findall(r"<!ATTLIST\s+(\S+)\s+(\S+)\s+CDATA\s+(#REQUIRED|\"[^\"]*\")", text):
    required.setdefault(el, set())
    if kind == "#REQUIRED":
        required[el].add(att)

def cobertura(path):
    root = ET.parse(path).getroot()
    def walk(e):
        if e.tag not in children:
            bad.append(f"{path}: element {e.tag} not in the DTD")
            return
        for att in required.get(e.tag, ()):
            if att not in e.attrib:
                bad.append(f"{path}: <{e.tag}> lacks required attribute {att}")
        for c in e:
            if c.tag not in children[e.tag]:
                bad.append(f"{path}: <{c.tag}> not allowed in <{e.tag}>")
            walk(c)
    if root.tag != "coverage":
        bad.append("root is not coverage")
    walk(root)
    lines = root.findall(".//class/lines/line")
    cov = sum(1 for l in lines if int(l.get("hits")) > 0)
    if int(root.get("lines-valid")) != len(lines) or int(root.get("lines-covered")) != cov:
        bad.append(f"{path}: lines-valid/covered {root.get('lines-valid')}/{root.get('lines-covered')} but {len(lines)}/{cov} line elements")
    return len(lines)

n = lcov(run + "/lcov.info")
m = lcov(run + "/lcov-per-test.info")
l = cobertura(run + "/cobertura.xml")
print(f"lcov.info: {n} records; lcov-per-test.info: {m} records; cobertura.xml: {l} line elements; violations: {len(bad)}")
for b in bad:
    print("VIOLATION", b)
sys.exit(1 if bad else 0)
