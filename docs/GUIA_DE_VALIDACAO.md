# Guia de validação — Feature Flags Service

## Estado e abrangência

API Java Spring Boot de feature flags em memória com administração autenticada.

## Fontes para revisão

`pom.xml`, `src/main/java/dev/juniordev/flags/FlagController.java`, `FeatureFlagsApplication.java` e testes Spring.

## Comandos de verificação

`mvn -B test`; para executar, definir `FEATURE_FLAGS_ADMIN_TOKEN` forte e usar `mvn spring-boot:run`.

Os testes automatizados devem ser revisados junto do código. Caso não tenham sido executados, registrar essa limitação no PR.

## Matriz de regressão

Verificar flag desconhecida (false), GET sem token permitido, alterações sem token rejeitadas, PATCH/PUT inválidos, espaços nos nomes e reinitialização.

## Próximo incremento fundamentado

Persistência, auditoria e controle de acesso por usuário ainda faltam; evitar anunciar como feature flag distribuída.

## Critérios de contribuição

- [ ] Alteração atende comportamento verificável.
- [ ] Foi criada evidência de teste ou procedimento reproduzível.
- [ ] Nenhuma credencial ou dado pessoal foi versionado.
- [ ] O PR identifica claramente o que mudou e o que ainda falta.
