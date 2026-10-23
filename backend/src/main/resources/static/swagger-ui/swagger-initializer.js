/* Same-origin sessions only; tokens are fetched per mutation after login/logout rotation. */
window.addEventListener("load", async () => {
  const base = new URL("../", window.location.href);
  try {
    const response = await fetch(new URL("v3/api-docs", base), {credentials: "same-origin"});
    if (!response.ok) throw new Error(`OpenAPI HTTP ${response.status}`);
    const spec = await response.json();
    spec.servers = [{url: base.pathname}];
    window.ui = SwaggerUIBundle({
      spec,
      dom_id: "#swagger-ui",
      deepLinking: true,
      docExpansion: "none",
      filter: true,
      validatorUrl: null,
      persistAuthorization: false,
      withCredentials: true,
      requestInterceptor: async request => {
        const target = new URL(request.url, base);
        const apiRoot = new URL("api/", base).pathname;
        if (target.origin === base.origin && target.pathname.startsWith(apiRoot)
            && !["GET", "HEAD", "OPTIONS"].includes(request.method.toUpperCase())) {
          const csrf = await fetch(new URL("api/auth/csrf", base), {credentials: "same-origin", cache: "no-store"});
          if (!csrf.ok) throw new Error(`CSRF HTTP ${csrf.status}`);
          const token = await csrf.json();
          request.headers[token.headerName] = token.token;
        }
        return request;
      }
    });
  } catch (error) {
    document.getElementById("swagger-ui").textContent = `API 문서를 불러오지 못했습니다: ${error.message}`;
  }
});
