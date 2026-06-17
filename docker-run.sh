#!/bin/bash
set -e

CLASSPATH=$(find /app/lib/ -name '*.jar' | tr '\n' ':' | sed 's/:$//')
exec java -cp "$CLASSPATH" fitforge.FitForgeMain
