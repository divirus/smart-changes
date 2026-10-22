# Publishing

## JetBrains Marketplace

1. Run `./gradlew test verifyPlugin buildPlugin` and test the plugin in the IDE versions you intend to support. No upper build limit guarantees API compatibility.
2. Follow the signing instructions: https://plugins.jetbrains.com/docs/intellij/plugin-signing.html. Keep signing keys and tokens outside the repository.
3. Sign in at https://plugins.jetbrains.com/ and choose **Add new plugin**. The first upload is manual. Upload the distribution ZIP (the signed ZIP if signing is configured), not the JAR.
4. Complete the vendor profile, description, MIT license, source/support links, category, and screenshots; submit for review. The SVG logo is bundled in META-INF.
5. Upload later releases on the plugin page, with a new version and release notes. Gradle publishing can be configured later using a private Marketplace token.

Official guide: https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html
