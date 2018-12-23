#!/bin/bash
mvn sonar:sonar \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.login=99fd7ae6accb13b56e81f66cd9e8bf30d3f4154c

