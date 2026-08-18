# Contributing

1. Branch from `main`. Protect `main` with GitHub rules (required PR). Do not push commits straight to `main`.
2. Follow hexagonal packages and the Java rules in `.cursor/rules/java-rules.mdc`.
3. `mvn test` must pass. CI runs `mvn -B package` on JDK 21.
4. Update this book when you change API, config, or lifecycle.
5. Rebuild docs: `cd docs && mdbook build`.

Design-level changes also belong in repository file `doc/design.md`.
