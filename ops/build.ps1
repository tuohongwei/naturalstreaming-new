$ErrorActionPreference='Stop'
$env:JAVA_HOME='C:/Users/Administrator/.aichef-tools/jdk-26.0.2.1'
& 'C:/Users/Administrator/.aichef-tools/apache-maven-3.9.11/bin/mvn.cmd' -f "$PSScriptRoot/../backend/pom.xml" clean verify
if($LASTEXITCODE -ne 0){throw 'Backend build or tests failed'}
