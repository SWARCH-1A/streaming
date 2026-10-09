#!/usr/bin/env python3
"""Generate and verify neutral contracts from the canonical Markdown, without network I/O."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

from graphql import build_schema, parse, print_ast, validate
from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import SchemaError, ValidationError
from graphql import GraphQLError

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/contratos_modelo_datos.md"
OUTPUT = ROOT / "contracts/generated"
FORBIDDEN = {"email", "password", "passwordHash", "sessionCredential", "credential", "streamKey",
             "streamKeyHash", "leaseToken", "serviceToken", "rtmpUrl", "ingestKey", "token"}


def read_json(text: str):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    return json.loads(text, object_pairs_hook=unique)


def load(source: Path = SOURCE):
    text = source.read_text(encoding="utf-8")
    blocks = re.findall(r"^```p1-contracts\s*\n(.*?)^```\s*$", text, re.M | re.S)
    sdls = re.findall(r"^```graphql\s*\n(.*?)^```\s*$", text, re.M | re.S)
    if len(blocks) != 1 or len(sdls) != 1:
        raise ValueError("Expected exactly one p1-contracts block and one GraphQL SDL")
    return read_json(blocks[0]), sdls[0].rstrip() + "\n"


def walk(value):
    if isinstance(value, dict):
        yield value
        for item in value.values():
            yield from walk(item)
    elif isinstance(value, list):
        for item in value:
            yield from walk(item)


def validator(bundle, name):
    if name not in bundle["schemas"]:
        raise ValueError(f"Unknown schema: {name}")
    schema = {"$schema": "https://json-schema.org/draft/2020-12/schema",
              "$defs": bundle["schemas"], "$ref": f"#/$defs/{name}"}
    return Draft202012Validator(schema, format_checker=FormatChecker())


def validate_payload(bundle, name, value, public=False):
    validator(bundle, name).validate(value)
    if public:
        for obj in walk(value):
            leaked = FORBIDDEN.intersection(obj)
            if leaked:
                raise ValueError(f"Private fields in public payload: {sorted(leaked)}")


def verify(bundle, sdl):
    schemas = bundle["schemas"]
    if bundle["version"] != 1 or not schemas or not bundle["operations"]:
        raise ValueError("Empty or unsupported contract bundle")
    for name, schema in schemas.items():
        Draft202012Validator.check_schema(schema)
        for obj in walk(schema):
            if "$dynamicRef" in obj or "$id" in obj:
                raise ValueError(f"Schema identifiers/dynamic references are not allowed in {name}")
            if "$ref" in obj:
                ref = obj["$ref"]
                if not ref.startswith("#/$defs/") or ref[8:] not in schemas:
                    raise ValueError(f"Nonlocal or unresolved reference in {name}: {ref}")
        if not schema.get("examples"):
            raise ValueError(f"Missing example: {name}")
        for value in schema["examples"]:
            validate_payload(bundle, name, value)
    seen, paths = set(), set()
    required = {"id", "method", "path", "provider", "consumer", "owner", "auth", "request",
                "responses", "timeoutMs", "idempotency", "recovery", "spec", "visibility", "limits"}
    for op in bundle["operations"]:
        if required - set(op):
            raise ValueError(f"Incomplete operation: {op.get('id')}")
        identity = (op["provider"], op["method"], op["path"])
        if op["id"] in seen or identity in paths:
            raise ValueError(f"Duplicate operation: {op['id']}")
        seen.add(op["id"]); paths.add(identity)
        if not op["consumer"] or not op["owner"] or op["timeoutMs"] <= 0 or not op["responses"] or not op["limits"]:
            raise ValueError(f"Missing consumer/owner/budget/response: {op['id']}")
        if op["request"] is not None:
            validator(bundle, op["request"])
        for status, name in op["responses"].items():
            if not (status.isdigit() or status == "frame"):
                raise ValueError(f"Invalid response status: {op['id']}")
            if name is not None:
                validator(bundle, name)
                if op["visibility"] == "public":
                    for example in schemas[name]["examples"]:
                        validate_payload(bundle, name, example, public=True)
    graphql = build_schema(sdl)
    for query in bundle["graphqlQueries"]:
        errors = validate(graphql, parse(query["query"]))
        if errors:
            raise ValueError(f"Invalid GraphQL consumer query {query['name']}: {errors[0].message}")
    for negative in bundle["negativeExamples"]:
        try:
            validate_payload(bundle, negative["schema"], negative["value"], negative.get("public", False))
        except (ValueError, ValidationError):
            continue
        raise ValueError(f"Negative example unexpectedly valid: {negative['name']}")


def typescript(bundle, sdl):
    def ts(schema):
        if "$ref" in schema: return schema["$ref"].split("/")[-1]
        if "const" in schema: return json.dumps(schema["const"])
        if "enum" in schema: return " | ".join(json.dumps(v) for v in schema["enum"])
        if "anyOf" in schema: return " | ".join(ts(v) for v in schema["anyOf"])
        kind = schema.get("type")
        if isinstance(kind, list): return " | ".join(ts({"type": v}) for v in kind)
        if kind == "array": return "Array<" + ts(schema["items"]) + ">"
        if kind == "object":
            props = schema.get("properties", {})
            if not props: return "Record<string, unknown>"
            required = schema.get("required", [])
            return "{ " + "; ".join(json.dumps(k) + ("" if k in required else "?") + ": " + ts(v) for k, v in props.items()) + " }"
        return {"string": "string", "integer": "number", "number": "number", "boolean": "boolean", "null": "null"}.get(kind, "unknown")
    lines = ["// Generated from docs/contratos_modelo_datos.md by contracts/generate.py; do not edit."]
    lines += ["export type " + name + " = " + ts(schema) + ";" for name, schema in sorted(bundle["schemas"].items())]
    from graphql import GraphQLObjectType, GraphQLNonNull, GraphQLList
    def gql(t):
        if isinstance(t, GraphQLNonNull): return gql_inner(t.of_type)
        return gql_inner(t) + " | null"
    def gql_inner(t):
        if isinstance(t, GraphQLList): return "Array<" + gql(t.of_type) + ">"
        return {"ID":"string", "String":"string", "DateTime":"string", "Int":"number", "Boolean":"boolean"}.get(t.name, "Gql" + t.name)
    for name, obj in sorted(build_schema(sdl).type_map.items()):
        if isinstance(obj, GraphQLObjectType) and not name.startswith("__"):
            lines.append("export type Gql" + name + " = { " + "; ".join(json.dumps(k) + ": " + gql(v.type) for k,v in obj.fields.items()) + " };")
    return "\n".join(lines) + "\n"


def artifacts(bundle, sdl):
    encode = lambda value: json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
    schema = {"$schema": "https://json-schema.org/draft/2020-12/schema", "$defs": bundle["schemas"]}
    files = {"p1.schema.json": encode(schema), "operations.json": encode(bundle["operations"]),
             "discovery.graphqls": sdl, "graphql-queries.json": encode(bundle["graphqlQueries"]),
             "local-interfaces.json": encode(bundle["localInterfaces"]), "p1.d.ts": typescript(bundle, sdl)}
    files["manifest.json"] = encode({"source": "docs/contratos_modelo_datos.md", "version": bundle["version"],
        "sha256": {name: hashlib.sha256(content.encode()).hexdigest() for name, content in files.items()}})
    return files


def check_core_sdl(sdl):
    actual = (ROOT / "services/core/src/main/resources/discovery/schema.graphqls").read_text()
    # Descriptions/comments do not change the wire schema; all type/field definitions do.
    def semantic(text):
        ast = parse(text)
        for definition in ast.definitions:
            if hasattr(definition, "description"):
                definition.description = None
        return print_ast(ast)
    if semantic(sdl) != semantic(actual):
        raise ValueError("Core GraphQL SDL differs from the canonical contract")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    parser.add_argument("--payload", nargs=2, metavar=("SCHEMA", "JSON_FILE"))
    parser.add_argument("--public", action="store_true")
    args = parser.parse_args()
    bundle, sdl = load()
    verify(bundle, sdl)
    check_core_sdl(sdl)
    if args.payload:
        name, path = args.payload
        validate_payload(bundle, name, read_json(Path(path).read_text()), args.public)
    files = artifacts(bundle, sdl)
    if args.write:
        OUTPUT.mkdir(exist_ok=True)
        for name, content in files.items():
            (OUTPUT / name).write_text(content, encoding="utf-8")
    else:
        for name, content in files.items():
            path = OUTPUT / name
            if not path.is_file() or path.read_text(encoding="utf-8") != content:
                raise ValueError(f"Generated contract drift: {name}; run --write")
        unexpected = {p.name for p in OUTPUT.iterdir() if p.is_file()} - set(files) - {"README.md"}
        if unexpected:
            raise ValueError(f"Unexpected generated files: {sorted(unexpected)}")
    print(f"PASS: {len(bundle['schemas'])} schemas, {len(bundle['operations'])} operations, examples and SDL")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, ValidationError, SchemaError, GraphQLError) as error:
        # Schema/payload validation can contain credentials. Never print instance/error repr.
        print(f"Contract validation failed ({type(error).__name__}); inspect the schema or source locally.", file=sys.stderr)
        sys.exit(1)
