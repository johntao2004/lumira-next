# Lumira Web

- The application framework is Umi Max and the bundler is Mako.
- Keep browser routes in `.umirc.ts`; dashboard business pages remain lazy-loaded through the feature registry.
- Preserve the Spring Boot `/api` contract, cookie authentication, CSRF handling, Ant Design 6 theme, and route URLs.
- Do not introduce Next.js runtime APIs or a production Node rendering requirement.
