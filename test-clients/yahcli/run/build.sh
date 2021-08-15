#! /bin/sh
TAG=${1:-'6.6.6'}

cd ..
mvn clean compile assembly:single@yahcli-jar
cd -
run/refresh-jar.sh

docker build -t yahcli:$TAG .
