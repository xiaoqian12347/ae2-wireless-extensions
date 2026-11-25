# libs

Put any 1.12.2 mod JAR here. `build.gradle` picks up every `*.jar` in this
folder and adds it as a `deobf` dependency, so no script edit is needed.

The jars are only for compiling and running the dev client. They are not
bundled into the published mod. Remove a jar here if the game should load the
player-installed version instead.
