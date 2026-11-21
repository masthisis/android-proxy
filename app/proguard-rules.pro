# NeuroRift release rules.
# Compose/AndroidX/OkHttp/DataStore consumer rules are bundled with the
# libraries; only app-specific additions belong here.

# snakeyaml's introspector references java.beans.* — JDK-only APIs that do
# not exist on Android (found by the v1.0.0-rc.1 R8 gate, issue #21). The
# reflective bean paths are never taken on Android: configs are parsed from
# strings into LinkedHashMaps only.
-dontwarn java.beans.**
