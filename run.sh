#!/bin/sh
mkdir -p out
javac -d out src/sportground/*.java && java -cp out sportground.App
