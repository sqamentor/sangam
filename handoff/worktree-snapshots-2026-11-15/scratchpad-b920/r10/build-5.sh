set -euo pipefail
dotnet build src/Sangam.Sdk/Sangam.Sdk.csproj -c Release -p:Version="$V"
dotnet pack src/Sangam.Sdk/Sangam.Sdk.csproj --no-build -c Release -p:Version="$V" -o "$GITHUB_WORKSPACE/packages/nuget"
